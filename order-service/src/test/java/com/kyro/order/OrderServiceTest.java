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
import com.kyro.order.event.OrderConfirmedEvent;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    OrderConfirmedEvent event = (OrderConfirmedEvent) events.getFirst();
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
    OrderService service = new OrderService(repository, null, catalog, null, null, null);

    assertTrue(service.expireVnpayOrder(42L, expiration));

    assertTrue(adjusted[0]);
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
  void stockFailureLeavesOrderPendingForRetry() {
    Instant expiration = Instant.parse("2026-08-16T08:15:00Z");
    Order order = order(PaymentStatus.PENDING, true);
    order.setId(42L);
    order.setExpiresAt(expiration);
    order.getOrderItems().iterator().next().setVariantId(7L);
    OrderRepository repository = repositoryFor(order);
    CatalogClient catalog =
        proxy(
            CatalogClient.class,
            (instance, method, arguments) -> {
              throw new IllegalStateException("catalog unavailable");
            });
    OrderService service = new OrderService(repository, null, catalog, null, null, null);

    assertThrows(IllegalStateException.class, () -> service.expireVnpayOrder(42L, expiration));

    assertEquals(OrderStatus.PENDING, order.getOrderStatus());
    assertEquals(PaymentStatus.PENDING, order.getPaymentStatus());
    assertTrue(order.isStockReserved());
  }

  @Test
  void expiresOrderWithoutCallingCatalogWhenStockWasNotReserved() {
    Instant expiration = Instant.parse("2026-08-16T08:15:00Z");
    Order order = order(PaymentStatus.PENDING, false);
    order.setId(42L);
    order.setExpiresAt(expiration);
    OrderService service = new OrderService(repositoryFor(order), null, null, null, null, null);

    assertTrue(service.expireVnpayOrder(42L, expiration));

    assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
    assertEquals(PaymentStatus.CANCELLED, order.getPaymentStatus());
  }

  @Test
  void identifiesLateSuccessWithoutRevivingCancelledOrder() {
    Order order = order(PaymentStatus.CANCELLED, false);
    order.setId(42L);
    order.setOrderStatus(OrderStatus.CANCELLED);
    OrderService service = new OrderService(repositoryFor(order), null, null, null, null, null);

    service.updatePaymentStatus(42L, PaymentStatus.COMPLETED);

    assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
    assertEquals(PaymentStatus.CANCELLED, order.getPaymentStatus());
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
  void placeOrderIdempotentHandlesRaceConditionGracefully() {
    Order winnerOrder = new Order();
    winnerOrder.setId(101L);
    winnerOrder.setUserId(10L);
    winnerOrder.setIdempotencyKey("key-race");

    var callCount = new int[] {0};
    OrderRepository repo =
        proxy(
            OrderRepository.class,
            (proxy, method, args) -> {
              if ("findByUserIdAndIdempotencyKey".equals(method.getName())) {
                callCount[0]++;
                // 1st call (pre-check): return empty
                // 2nd call (after race exception): return winnerOrder
                return callCount[0] == 1 ? Optional.empty() : Optional.of(winnerOrder);
              }
              throw new UnsupportedOperationException(method.getName());
            });

    org.springframework.transaction.support.TransactionTemplate txTemplate =
        new org.springframework.transaction.support.TransactionTemplate() {
          @Override
          @SuppressWarnings("unchecked")
          public <T> T execute(
              org.springframework.transaction.support.TransactionCallback<T> action) {
            throw new org.springframework.dao.DataIntegrityViolationException(
                "duplicate key value violates unique constraint uq_orders_user_idempotency_key");
          }
        };

    OrderService orderService = new OrderService(repo, null, null, null, null, null, txTemplate);
    List<Order> result =
        orderService.placeOrderIdempotent(
            1L, 10L, "user@test.com", PaymentMethod.COD, List.of(1L), 0L, 100L, "key-race");

    assertEquals(1, result.size());
    assertEquals(101L, result.getFirst().getId());
    assertEquals(2, callCount[0]);
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Order order(PaymentStatus paymentStatus, boolean stockReserved) {
    OrderItem item = new OrderItem();
    item.setProductId(3L);
    item.setSize("M");
    item.setQuantity(2);
    Order order = new Order();
    order.setPaymentMethod(PaymentMethod.VNPAY);
    order.setPaymentStatus(paymentStatus);
    order.setOrderStatus(OrderStatus.PENDING);
    order.setStockReserved(stockReserved);
    order.setOrderItems(java.util.List.of(item));
    return order;
  }
}
