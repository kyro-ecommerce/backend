package com.kyro.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kyro.enums.OrderStatus;
import com.kyro.enums.PaymentMethod;
import com.kyro.enums.PaymentStatus;
import com.kyro.exceptions.DomainException;
import com.kyro.order.client.CatalogClient;
import com.kyro.order.dto.OrderDTO;
import com.kyro.order.event.OrderCancelledEvent;
import com.kyro.order.event.OrderConfirmedEvent;
import com.kyro.order.event.StockReleaseEvent;
import com.kyro.order.event.StockResultEvent;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

  @Test
  void purchaseCheckDelegatesWithoutLoadingOrderItems() {
    OrderService orderService =
        new OrderService(null, null, null, null, null, null) {
          @Override
          public boolean hasPurchasedAndDelivered(Long userId, Long productId) {
            return userId.equals(16L) && productId.equals(20L);
          }
        };

    assertTrue(new InternalOrderController(orderService).hasPurchasedAndDelivered(16L, 20L));
  }

  @Test
  void statusUpdateUsesLifecycleTransitionAndRejectsPendingReset() {
    Order order = new Order();
    order.setOrderStatus(OrderStatus.PENDING);
    OrderService orderService =
        new OrderService(null, null, null, null, null, null) {
          @Override
          public Order findOrderById(Long orderId) {
            return order;
          }

          @Override
          public Order confirmedOrder(Long orderId) {
            order.setOrderStatus(OrderStatus.CONFIRMED);
            return order;
          }
        };

    assertEquals(
        OrderStatus.CONFIRMED,
        orderService.updateOrderStatus(1L, OrderStatus.CONFIRMED).getOrderStatus());
    DomainException exception =
        assertThrows(
            DomainException.class, () -> orderService.updateOrderStatus(1L, OrderStatus.PENDING));
    assertEquals(409, exception.getStatus().value());
    assertEquals("INVALID_ORDER_STATE", exception.getErrorCode());
  }

  @Test
  void confirmsVnpayOnlyAfterStockAndNeverRevivesCancelledOrder() {
    Order order = new Order();
    order.setPaymentMethod(PaymentMethod.VNPAY);
    order.setPaymentStatus(PaymentStatus.PENDING);
    order.setOrderStatus(OrderStatus.PENDING);

    OrderService.applyPaymentStatus(order, PaymentStatus.COMPLETED);
    assertEquals(OrderStatus.PENDING, order.getOrderStatus());

    order.setStockReserved(true);
    OrderService.applyPaymentStatus(order, PaymentStatus.COMPLETED);
    assertEquals(OrderStatus.CONFIRMED, order.getOrderStatus());

    order.setOrderStatus(OrderStatus.CANCELLED);
    OrderService.applyPaymentStatus(order, PaymentStatus.COMPLETED);
    assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
  }

  @Test
  void cancellingPaidVnpayOrderDoesNotClaimRefund() {
    Order order = new Order();
    order.setPaymentMethod(PaymentMethod.VNPAY);
    order.setPaymentStatus(PaymentStatus.COMPLETED);
    order.setOrderStatus(OrderStatus.PENDING);

    OrderService.applyCancellationPaymentStatus(order);

    assertEquals(PaymentStatus.COMPLETED, order.getPaymentStatus());
  }

  @Test
  void failedVnpayAttemptKeepsOrderAndReservedStockUntilExpiration() {
    Order order = order(PaymentStatus.PENDING, false);
    OrderService.applyPaymentStatus(order, PaymentStatus.FAILED);
    assertEquals(OrderStatus.PENDING, order.getOrderStatus());
    OrderService.applyStockResult(order, true);
    assertEquals(OrderStatus.PENDING, order.getOrderStatus());
    assertEquals(PaymentStatus.FAILED, order.getPaymentStatus());
    assertTrue(order.isStockReserved());
  }

  @Test
  void successEventsConfirmInEitherOrderAndLateFailureCannotRevive() {
    Order paymentFirst = order(PaymentStatus.PENDING, false);
    OrderService.applyPaymentStatus(paymentFirst, PaymentStatus.COMPLETED);
    OrderService.applyStockResult(paymentFirst, true);
    assertEquals(OrderStatus.CONFIRMED, paymentFirst.getOrderStatus());

    Order stockFirst = order(PaymentStatus.PENDING, false);
    OrderService.applyStockResult(stockFirst, true);
    OrderService.applyPaymentStatus(stockFirst, PaymentStatus.COMPLETED);
    assertEquals(OrderStatus.CONFIRMED, stockFirst.getOrderStatus());

    stockFirst.setOrderStatus(OrderStatus.CANCELLED);
    OrderService.applyStockResult(stockFirst, true);
    assertEquals(OrderStatus.CANCELLED, stockFirst.getOrderStatus());
  }

  @Test
  void vnpayPublishesOnceForEitherSuccessEventOrder() {
    Order paymentFirst = order(PaymentStatus.PENDING, false);
    boolean wasPending = paymentFirst.getOrderStatus() == OrderStatus.PENDING;
    OrderService.applyPaymentStatus(paymentFirst, PaymentStatus.COMPLETED);
    assertFalse(OrderService.isNewlyConfirmed(wasPending, paymentFirst));
    OrderService.applyStockResult(paymentFirst, true);
    assertTrue(OrderService.isNewlyConfirmed(wasPending, paymentFirst));

    Order stockFirst = order(PaymentStatus.PENDING, false);
    wasPending = stockFirst.getOrderStatus() == OrderStatus.PENDING;
    OrderService.applyStockResult(stockFirst, true);
    assertFalse(OrderService.isNewlyConfirmed(wasPending, stockFirst));
    OrderService.applyPaymentStatus(stockFirst, PaymentStatus.COMPLETED);
    assertTrue(OrderService.isNewlyConfirmed(wasPending, stockFirst));
  }

  @Test
  void duplicateCodStockEventDoesNotPublishTwice() {
    Order order = order(PaymentStatus.PENDING, false);
    order.setPaymentMethod(PaymentMethod.COD);
    boolean firstWasPending = order.getOrderStatus() == OrderStatus.PENDING;
    OrderService.applyStockResult(order, true);
    assertTrue(OrderService.isNewlyConfirmed(firstWasPending, order));

    boolean duplicateWasPending = order.getOrderStatus() == OrderStatus.PENDING;
    OrderService.applyStockResult(order, true);
    assertFalse(OrderService.isNewlyConfirmed(duplicateWasPending, order));
  }

  @Test
  void failuresDoNotPublishConfirmation() {
    Order order = order(PaymentStatus.PENDING, false);
    boolean wasPending = order.getOrderStatus() == OrderStatus.PENDING;
    OrderService.applyStockResult(order, false);
    assertFalse(OrderService.isNewlyConfirmed(wasPending, order));
  }

  @Test
  void assignsExpirationOnlyToVnpayOrders() {
    Instant createdAt = Instant.parse("2026-08-16T08:00:00Z");

    assertEquals(
        createdAt.plusSeconds(15 * 60), OrderService.expirationFor(PaymentMethod.VNPAY, createdAt));
    assertEquals(null, OrderService.expirationFor(PaymentMethod.COD, createdAt));
  }

  @Test
  void serializesExpirationAsIsoInstant() throws Exception {
    OrderDTO order = new OrderDTO();
    order.setExpiresAt(Instant.parse("2026-08-16T14:51:38Z"));

    String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(order);

    assertTrue(json.contains("\"expiresAt\":\"2026-08-16T14:51:38Z\""));
  }

  @Test
  void assignsCustomerFacingOrderCode() {
    Order first = new Order();
    Order second = new Order();

    first.assignOrderCode();
    second.assignOrderCode();

    assertTrue(first.getOrderCode().matches("^KYR-[0-9A-F]{12}$"));
    assertFalse(first.getOrderCode().equals(second.getOrderCode()));
    assertEquals(first.getOrderCode(), new OrderDTO(first).getOrderCode());
  }

  @Test
  @SuppressWarnings("unchecked")
  void confirmationPayloadIncludesSku() {
    Order order = order(PaymentStatus.PENDING, true);
    order.setId(42L);
    order.assignOrderCode();
    order.setPaymentMethod(PaymentMethod.COD);
    order.setUserEmail("customer@example.com");
    order.setOrderDate(LocalDateTime.parse("2026-08-16T15:35:00"));
    order.setTotalDiscountedPrice(496_290L);
    order.setShippingAddress(
        new OrderAddress(1L, "Customer", "Province", "District", "Ward", "Street", null, "0123"));
    OrderItem item = order.getOrderItems().iterator().next();
    item.setSku("POWERBANK-20K");
    item.setProductName("Power Bank");
    item.setVariantName("20000mAh");
    item.setPrice(699_000L);
    item.setDiscountedPrice(496_290L);
    List<Object> events = new ArrayList<>();
    OrderService service =
        new OrderService(repositoryFor(order), null, null, null, null, events::add);

    service.confirmedOrder(42L);

    OrderConfirmedEvent event =
        (OrderConfirmedEvent)
            events.stream().filter(OrderConfirmedEvent.class::isInstance).findFirst().orElseThrow();
    Map<String, Object> orderData = (Map<String, Object>) event.payload().get("order");
    List<Map<String, Object>> items = (List<Map<String, Object>>) orderData.get("items");
    assertEquals("POWERBANK-20K", items.getFirst().get("sku"));
  }

  @Test
  void expiresReservedVnpayOrderAfterGraceCutoff() {
    Instant expiration = Instant.parse("2026-08-16T08:15:00Z");
    Order order = order(PaymentStatus.PENDING, true);
    order.setId(42L);
    order.setExpiresAt(expiration);
    order.getOrderItems().iterator().next().setVariantId(7L);
    OrderRepository repository = repositoryFor(order);
    boolean[] adjusted = {false};
    CatalogClient catalog =
        proxy(
            CatalogClient.class,
            (instance, method, arguments) -> {
              adjusted[0] = true;
              assertEquals(7L, arguments[0]);
              assertEquals(new CatalogClient.StockAdjustmentRequest(7L, 2), arguments[1]);
              return null;
            });
    List<Object> events = new ArrayList<>();
    OrderService service = new OrderService(repository, null, catalog, null, null, events::add);

    assertTrue(service.expireVnpayOrder(42L, expiration));

    assertEquals(
        List.of(new OrderCancelledEvent(42L, 10L, List.of(item(3L, 2)))),
        events.stream().filter(OrderCancelledEvent.class::isInstance).toList());
    assertFalse(adjusted[0]);
    assertTrue(events.stream().anyMatch(StockReleaseEvent.class::isInstance));
    assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
    assertEquals(PaymentStatus.CANCELLED, order.getPaymentStatus());
    assertFalse(order.isStockReserved());
  }

  @Test
  void skipsPaidOrNotYetExpiredOrders() {
    Instant expiration = Instant.parse("2026-08-16T08:15:00Z");
    Order pending = order(PaymentStatus.PENDING, true);
    pending.setExpiresAt(expiration);
    Order paid = order(PaymentStatus.COMPLETED, true);
    paid.setExpiresAt(expiration);

    assertFalse(
        OrderService.isExpiredVnpayOrder(
            pending,
            VnpayOrderExpirationScheduler.expirationCutoff(expiration.plusSeconds(5 * 60 - 1))));
    assertTrue(
        OrderService.isExpiredVnpayOrder(
            pending,
            VnpayOrderExpirationScheduler.expirationCutoff(expiration.plusSeconds(5 * 60))));
    assertFalse(OrderService.isExpiredVnpayOrder(paid, expiration.plusSeconds(1)));
  }

  @Test
  void expirationQueuesReleaseEvenWhenCatalogIsUnavailable() {
    Order order = order(PaymentStatus.PENDING, true);
    order.setId(42L);
    Instant expired = Instant.parse("2026-08-16T08:15:00Z");
    order.setExpiresAt(expired);
    List<Object> events = new ArrayList<>();
    CatalogClient catalog =
        proxy(
            CatalogClient.class,
            (instance, method, args) -> {
              throw new AssertionError("No synchronous stock adjustment");
            });
    OrderService service =
        new OrderService(repositoryFor(order), null, catalog, null, null, events::add);
    assertTrue(service.expireVnpayOrder(42L, expired));
    assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
    assertTrue(events.stream().anyMatch(StockReleaseEvent.class::isInstance));
  }

  @Test
  void expiresOrderWithoutCallingCatalogWhenStockWasNotReserved() {
    Instant expiration = Instant.parse("2026-08-16T08:15:00Z");
    Order order = order(PaymentStatus.PENDING, false);
    order.setId(42L);
    order.setExpiresAt(expiration);
    List<Object> events = new ArrayList<>();
    OrderService service =
        new OrderService(repositoryFor(order), null, null, null, null, events::add);

    assertTrue(service.expireVnpayOrder(42L, expiration));

    assertEquals(2, events.size());

    assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
    assertEquals(PaymentStatus.CANCELLED, order.getPaymentStatus());
  }

  @Test
  void cancelOrderPublishesPiiFreeEventWithQuantitiesPerProduct() {
    Order order = order(PaymentStatus.PENDING, false);
    order.setId(42L);
    order.setPaymentMethod(PaymentMethod.COD);
    order.setOrderStatus(OrderStatus.CONFIRMED);
    OrderItem sameProductOtherVariant = new OrderItem();
    sameProductOtherVariant.setProductId(3L);
    sameProductOtherVariant.setVariantId(8L);
    sameProductOtherVariant.setQuantity(1);
    OrderItem otherProduct = new OrderItem();
    otherProduct.setProductId(8L);
    otherProduct.setVariantId(9L);
    otherProduct.setQuantity(4);
    order.setOrderItems(
        List.of(order.getOrderItems().iterator().next(), sameProductOtherVariant, otherProduct));
    List<Object> events = new ArrayList<>();
    OrderService service =
        new OrderService(repositoryFor(order), null, null, null, null, events::add);

    service.cancelOrder(42L);

    assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
    assertEquals(
        List.of(new OrderCancelledEvent(42L, 10L, List.of(item(3L, 3), item(8L, 4)))),
        events.stream().filter(OrderCancelledEvent.class::isInstance).toList());
    assertFalse(
        Arrays.stream(OrderCancelledEvent.class.getRecordComponents())
            .anyMatch(component -> component.getName().toLowerCase().contains("email")));
  }

  @Test
  void cancellingPaidOrderRequiresReviewAndPreservesPayment() {
    Order order = order(PaymentStatus.COMPLETED, true);
    order.setId(42L);
    order.setOrderStatus(OrderStatus.CONFIRMED);
    var service = new OrderService(repositoryFor(order), null, null, null, null, event -> {});
    service.cancelOrder(42L);
    assertEquals(PaymentStatus.COMPLETED, order.getPaymentStatus());
    assertTrue(order.isPaymentReviewRequired());
  }

  @Test
  void cannotDeleteCancelledVnpayOrderWithPotentialLateCharge() {
    Order order = order(PaymentStatus.CANCELLED, false);
    order.setId(42L);
    order.setOrderStatus(OrderStatus.CANCELLED);
    var service = new OrderService(repositoryFor(order), null, null, null, null, event -> {});
    assertThrows(DomainException.class, () -> service.deleteOrder(42L));
  }

  @Test
  void rejectedCancellationPublishesNothing() {
    Order order = order(PaymentStatus.CANCELLED, false);
    order.setId(42L);
    order.setOrderStatus(OrderStatus.CANCELLED);
    List<Object> events = new ArrayList<>();
    OrderService service =
        new OrderService(repositoryFor(order), null, null, null, null, events::add);

    assertThrows(DomainException.class, () -> service.cancelOrder(42L));

    assertTrue(events.isEmpty());
  }

  @Test
  void stockFailurePublishesCancellationOnlyOnce() {
    Order order = order(PaymentStatus.PENDING, false);
    order.setId(42L);
    order.setPaymentMethod(PaymentMethod.COD);
    List<Object> events = new ArrayList<>();
    OrderService service =
        new OrderService(repositoryFor(order), null, null, null, null, events::add);

    service.handleStockResult(new StockResultEvent(42L, 10L, false, "out of stock"));
    service.handleStockResult(new StockResultEvent(42L, 10L, false, "out of stock"));

    assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
    assertEquals(
        List.of(new OrderCancelledEvent(42L, 10L, List.of(item(3L, 2)))),
        events.stream().filter(OrderCancelledEvent.class::isInstance).toList());
  }

  @Test
  void stockSuccessPublishesNoCancellation() {
    Order order = order(PaymentStatus.PENDING, false);
    order.setId(42L);
    order.setPaymentMethod(PaymentMethod.VNPAY);
    List<Object> events = new ArrayList<>();
    OrderService service =
        new OrderService(repositoryFor(order), null, null, null, null, events::add);

    service.handleStockResult(new StockResultEvent(42L, 10L, true, null));

    assertTrue(events.stream().noneMatch(OrderCancelledEvent.class::isInstance));
  }

  private static OrderCancelledEvent.Item item(Long productId, int quantity) {
    return new OrderCancelledEvent.Item(productId, quantity);
  }

  @Test
  void identifiesLateSuccessWithoutRevivingCancelledOrder() {
    Order order = order(PaymentStatus.CANCELLED, false);
    order.setId(42L);
    order.setOrderStatus(OrderStatus.CANCELLED);
    OrderService service = new OrderService(repositoryFor(order), null, null, null, null, null);

    service.updatePaymentStatus(42L, PaymentStatus.COMPLETED);

    assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
    assertEquals(PaymentStatus.COMPLETED, order.getPaymentStatus());
    assertTrue(order.isPaymentReviewRequired());
  }

  @Test
  void projectsReviewOnlyPaymentEventWithoutRepeatingConfirmationOrClearingReview() {
    Order order = order(PaymentStatus.COMPLETED, true);
    order.setId(42L);
    order.setOrderStatus(OrderStatus.CONFIRMED);
    List<Object> events = new ArrayList<>();
    OrderService service =
        new OrderService(repositoryFor(order), null, null, null, null, events::add);
    service.updatePaymentStatus(42L, PaymentStatus.COMPLETED, 10L, true);
    assertTrue(order.isPaymentReviewRequired());
    service.updatePaymentStatus(42L, PaymentStatus.COMPLETED, 9L, false);
    service.updatePaymentStatus(42L, PaymentStatus.COMPLETED, 11L, false);
    service.updatePaymentStatus(42L, PaymentStatus.COMPLETED, 12L, null);
    assertTrue(order.isPaymentReviewRequired());
    assertEquals(12L, order.getPaymentEventVersion());
    assertEquals(OrderStatus.CONFIRMED, order.getOrderStatus());
    assertTrue(events.isEmpty());
  }

  @Test
  void staleReviewEventCannotOverrideNewerProjection() {
    Order order = order(PaymentStatus.COMPLETED, true);
    order.setId(42L);
    order.setOrderStatus(OrderStatus.CONFIRMED);
    order.setPaymentEventVersion(10L);
    OrderService service = new OrderService(repositoryFor(order), null, null, null, null, null);
    service.updatePaymentStatus(42L, PaymentStatus.COMPLETED, 9L, true);
    assertFalse(order.isPaymentReviewRequired());
  }

  private static OrderRepository repositoryFor(Order order) {
    return proxy(
        OrderRepository.class,
        (instance, method, arguments) ->
            switch (method.getName()) {
              case "findByIdForUpdate" -> Optional.of(order);
              case "save" -> arguments[0];
              default -> throw new UnsupportedOperationException(method.getName());
            });
  }

  @Test
  void placeOrderIdempotentReturnsExistingOrderOnPreCheckHit() {
    Order existingOrder = new Order();
    existingOrder.setId(99L);
    existingOrder.setUserId(10L);
    existingOrder.setIdempotencyKey("key-123");

    OrderRepository repo =
        proxy(
            OrderRepository.class,
            (proxy, method, args) -> {
              if ("findByUserIdAndIdempotencyKey".equals(method.getName())) {
                return Optional.of(existingOrder);
              }
              throw new UnsupportedOperationException(method.getName());
            });

    OrderService orderService = new OrderService(repo, null, null, null, null, null);
    List<Order> result =
        orderService.placeOrderIdempotent(
            1L, 10L, "user@test.com", PaymentMethod.COD, List.of(1L), 0L, 100L, "key-123");

    assertEquals(1, result.size());
    assertEquals(99L, result.getFirst().getId());
  }

  @Test
  void keyCannotBeReusedWithDifferentRequest() {
    Order existing = new Order();
    existing.setRequestFingerprint(
        OrderService.fingerprint(1L, PaymentMethod.COD, List.of(1L), 1, 100));
    assertThrows(
        DomainException.class,
        () ->
            OrderService.checkFingerprint(
                existing, OrderService.fingerprint(1L, PaymentMethod.VNPAY, List.of(1L), 1, 100)));
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Order order(PaymentStatus paymentStatus, boolean stockReserved) {
    OrderItem item = new OrderItem();
    item.setProductId(3L);
    item.setVariantId(7L);
    item.setSize("M");
    item.setQuantity(2);
    Order order = new Order();
    order.setUserId(10L);
    order.setPaymentMethod(PaymentMethod.VNPAY);
    order.setPaymentStatus(paymentStatus);
    order.setOrderStatus(OrderStatus.PENDING);
    order.setStockReserved(stockReserved);
    order.setOrderItems(List.of(item));
    return order;
  }
}
