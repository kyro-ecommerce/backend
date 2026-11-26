package com.kyro.order;

import com.kyro.enums.OrderStatus;
import com.kyro.enums.PaymentMethod;
import com.kyro.enums.PaymentStatus;
import com.kyro.exceptions.DomainException;
import com.kyro.order.client.CartClient;
import com.kyro.order.client.CatalogClient;
import com.kyro.order.client.UserClient;
import com.kyro.order.dto.OrderDTO;
import com.kyro.order.dto.OrderDetailDTO;
import com.kyro.order.dto.ProductRevenueResponse;
import com.kyro.order.dto.TopSellingProductResponse;
import com.kyro.order.event.CartConfirmedEvent;
import com.kyro.order.event.OrderCancelledEvent;
import com.kyro.order.event.OrderConfirmedEvent;
import com.kyro.order.event.OrderCreatedEvent;
import com.kyro.order.event.OrderDeliveredEvent;
import com.kyro.order.event.StockReleaseEvent;
import com.kyro.order.event.StockResultEvent;
import feign.FeignException;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.HexFormat;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service class managing ordering workflow, integrated with external catalog, cart, auth
 * microservices and RabbitMQ.
 */
@Service
public class OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);
  static final Duration VNPAY_TTL = Duration.ofMinutes(15);

  private final OrderRepository orderRepository;
  private final OrderItemRepository orderItemRepository;
  private final CatalogClient catalogClient;
  private final CartClient cartClient;
  private final UserClient userClient;
  private final ApplicationEventPublisher eventPublisher;
  private final TransactionTemplate transactionTemplate;

  @Autowired
  public OrderService(
      OrderRepository orderRepository,
      OrderItemRepository orderItemRepository,
      CatalogClient catalogClient,
      CartClient cartClient,
      UserClient userClient,
      ApplicationEventPublisher eventPublisher,
      TransactionTemplate transactionTemplate) {
    this.orderRepository = orderRepository;
    this.orderItemRepository = orderItemRepository;
    this.catalogClient = catalogClient;
    this.cartClient = cartClient;
    this.userClient = userClient;
    this.eventPublisher = eventPublisher;
    this.transactionTemplate = transactionTemplate;
  }

  public OrderService(
      OrderRepository orderRepository,
      OrderItemRepository orderItemRepository,
      CatalogClient catalogClient,
      CartClient cartClient,
      UserClient userClient,
      ApplicationEventPublisher eventPublisher) {
    this(
        orderRepository,
        orderItemRepository,
        catalogClient,
        cartClient,
        userClient,
        eventPublisher,
        null);
  }

  public OrderDTO convertToDto(Order order) {
    return new OrderDTO(order);
  }

  public Order findOrderById(Long orderId) {
    return orderRepository
        .findById(orderId)
        .orElseThrow(
            () -> {
              log.warn("Order not found with ID: {}", orderId);
              return new DomainException(
                  HttpStatus.NOT_FOUND,
                  "ORDER_NOT_FOUND",
                  "Không tìm thấy đơn hàng với ID: " + orderId);
            });
  }

  @Transactional(readOnly = true)
  public Page<Order> findOrders(OrderFilter filter, Pageable pageable) {
    validateOrderFilter(filter);
    String search = clean(filter.search());
    LocalDateTime start = filter.startDate() == null ? null : filter.startDate().atStartOfDay();
    LocalDateTime end =
        filter.endDate() == null ? null : filter.endDate().atTime(23, 59, 59, 999_999_999);

    Specification<Order> specification =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          if (filter.userId() != null) {
            predicates.add(cb.equal(root.get("userId"), filter.userId()));
          }
          if (search != null) {
            String pattern = "%" + search.toLowerCase(Locale.ROOT) + "%";
            var address = root.join("shippingAddress", JoinType.LEFT);
            var itemSubquery = query.subquery(Integer.class);
            var item = itemSubquery.from(OrderItem.class);
            itemSubquery
                .select(cb.literal(1))
                .where(
                    cb.equal(item.get("order"), root),
                    cb.like(cb.lower(item.get("productName")), pattern));
            predicates.add(
                cb.or(
                    cb.like(cb.lower(root.get("orderCode")), pattern),
                    cb.like(cb.lower(root.get("userEmail")), pattern),
                    cb.like(cb.lower(address.get("fullName")), pattern),
                    cb.like(address.get("phoneNumber"), "%" + search + "%"),
                    cb.exists(itemSubquery)));
          }
          if (filter.status() != null) {
            predicates.add(cb.equal(root.get("orderStatus"), filter.status()));
          }
          if (filter.paymentMethod() != null) {
            predicates.add(cb.equal(root.get("paymentMethod"), filter.paymentMethod()));
          }
          if (filter.paymentStatus() != null) {
            predicates.add(cb.equal(root.get("paymentStatus"), filter.paymentStatus()));
          }
          if (start != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("orderDate"), start));
          }
          if (end != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("orderDate"), end));
          }
          if (filter.minTotal() != null) {
            predicates.add(cb.ge(root.get("totalDiscountedPrice"), filter.minTotal()));
          }
          if (filter.maxTotal() != null) {
            predicates.add(cb.le(root.get("totalDiscountedPrice"), filter.maxTotal()));
          }
          return cb.and(predicates.toArray(Predicate[]::new));
        };
    return orderRepository.findAll(specification, pageable);
  }

  static Pageable orderPageable(int page, int size, List<String> sortValues) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 100");
    }
    Set<String> fields =
        Set.of(
            "id", "orderCode", "orderDate", "deliveryDate", "totalDiscountedPrice", "totalItems");
    List<String> sortTokens = sortTokens(sortValues);
    List<Sort.Order> orders = new ArrayList<>();
    for (int index = 0; index < sortTokens.size(); index += 2) {
      String property = sortTokens.get(index);
      if (!fields.contains(property)) {
        throw new IllegalArgumentException("Unsupported order sort: " + property);
      }
      orders.add(new Sort.Order(Sort.Direction.fromString(sortTokens.get(index + 1)), property));
    }
    if (orders.isEmpty()) {
      orders.add(Sort.Order.desc("orderDate"));
    }
    if (orders.stream().noneMatch(order -> order.getProperty().equals("id"))) {
      orders.add(new Sort.Order(orders.get(0).getDirection(), "id"));
    }
    return PageRequest.of(page, size, Sort.by(orders));
  }

  private static List<String> sortTokens(List<String> sortValues) {
    if (sortValues == null) {
      return List.of();
    }
    List<String> tokens =
        sortValues.stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList();
    if (tokens.size() % 2 != 0) {
      throw new IllegalArgumentException("sort must use field,direction pairs");
    }
    return tokens;
  }

  private static void validateOrderFilter(OrderFilter filter) {
    if (filter.startDate() != null
        && filter.endDate() != null
        && filter.startDate().isAfter(filter.endDate())) {
      throw new IllegalArgumentException("startDate must not be after endDate");
    }
    if (filter.minTotal() != null && filter.minTotal() < 0
        || filter.maxTotal() != null && filter.maxTotal() < 0
        || filter.minTotal() != null
            && filter.maxTotal() != null
            && filter.minTotal() > filter.maxTotal()) {
      throw new IllegalArgumentException("Invalid order total range");
    }
    if (clean(filter.search()) != null && clean(filter.search()).length() > 100) {
      throw new IllegalArgumentException("search must not exceed 100 characters");
    }
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public boolean hasPurchasedAndDelivered(Long userId, Long productId) {
    return orderItemRepository.existsByOrderUserIdAndStatusAndProductId(
        userId, OrderStatus.DELIVERED, productId);
  }

  public List<Order> placeOrderIdempotent(
      Long addressId,
      Long userId,
      String userEmail,
      PaymentMethod paymentMethod,
      List<Long> cartItemIds,
      long cartVersion,
      long expectedTotalDiscountedPrice,
      String idempotencyKey) {
    String normalizedKey = clean(idempotencyKey);
    if (normalizedKey != null && normalizedKey.length() > 100)
      throw new IllegalArgumentException("Idempotency-Key too long");
    String fingerprint =
        fingerprint(
            addressId, paymentMethod, cartItemIds, cartVersion, expectedTotalDiscountedPrice);
    if (normalizedKey != null) {
      Optional<Order> existing =
          orderRepository.findByUserIdAndIdempotencyKey(userId, normalizedKey);
      if (existing.isPresent()) {
        log.info("Idempotent replay hit (pre-check) for user {} key {}", userId, normalizedKey);
        checkFingerprint(existing.get(), fingerprint);
        return List.of(existing.get());
      }
    }

    // Perform synchronous Feign I/O validation OUTSIDE the DB transaction to avoid holding
    // HikariCP connections across network calls (Connection Pool Starvation anti-pattern).
    ValidatedOrderContext context =
        validateAndFetchOrderContext(
            addressId, userId, cartItemIds, cartVersion, expectedTotalDiscountedPrice);

    if (transactionTemplate != null) {
      try {
        return transactionTemplate.execute(
            status ->
                persistOrderAndPublishEvent(
                    userId, userEmail, paymentMethod, normalizedKey, fingerprint, context));
      } catch (DataIntegrityViolationException ex) {
        if (normalizedKey != null) {
          Optional<Order> concurrentWinner =
              orderRepository.findByUserIdAndIdempotencyKey(userId, normalizedKey);
          if (concurrentWinner.isPresent()) {
            log.info(
                "Idempotent replay hit (concurrent race) for user {} key {}",
                userId,
                normalizedKey);
            checkFingerprint(concurrentWinner.get(), fingerprint);
            return List.of(concurrentWinner.get());
          }
        }
        throw ex;
      }
    } else {
      return persistOrderAndPublishEvent(
          userId, userEmail, paymentMethod, normalizedKey, fingerprint, context);
    }
  }

  @Transactional
  public List<Order> placeOrder(
      Long addressId,
      Long userId,
      String userEmail,
      PaymentMethod paymentMethod,
      List<Long> cartItemIds,
      long cartVersion,
      long expectedTotalDiscountedPrice) {
    return placeOrderInternal(
        addressId,
        userId,
        userEmail,
        paymentMethod,
        cartItemIds,
        cartVersion,
        expectedTotalDiscountedPrice,
        null);
  }

  @Transactional
  public List<Order> placeOrderInternal(
      Long addressId,
      Long userId,
      String userEmail,
      PaymentMethod paymentMethod,
      List<Long> cartItemIds,
      long cartVersion,
      long expectedTotalDiscountedPrice,
      String idempotencyKey) {
    ValidatedOrderContext context =
        validateAndFetchOrderContext(
            addressId, userId, cartItemIds, cartVersion, expectedTotalDiscountedPrice);
    return persistOrderAndPublishEvent(
        userId,
        userEmail,
        paymentMethod,
        idempotencyKey,
        fingerprint(
            addressId, paymentMethod, cartItemIds, cartVersion, expectedTotalDiscountedPrice),
        context);
  }

  static String fingerprint(
      Long addressId, PaymentMethod method, List<Long> items, long version, long total) {
    String canonical =
        addressId
            + ":"
            + (method == null ? PaymentMethod.COD : method)
            + ":"
            + items.stream().sorted().toList()
            + ":"
            + version
            + ":"
            + total;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  static void checkFingerprint(Order order, String fingerprint) {
    if (order.getRequestFingerprint() != null && !order.getRequestFingerprint().equals(fingerprint))
      throw new DomainException(
          HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Idempotency-Key đã dùng với nội dung khác");
  }

  record ValidatedOrderContext(
      CartClient.CartResponse cart, UserClient.AddressResponse addressResponse) {}

  private ValidatedOrderContext validateAndFetchOrderContext(
      Long addressId,
      Long userId,
      List<Long> cartItemIds,
      long cartVersion,
      long expectedTotalDiscountedPrice) {
    if (userId == null) {
      log.error("User ID is null when placing order.");
      throw new IllegalArgumentException("Thông tin người dùng không hợp lệ.");
    }
    if (addressId == null) {
      log.error("Address ID is null when placing order for user: {}", userId);
      throw new IllegalArgumentException("Địa chỉ giao hàng không được để trống.");
    }

    if (new HashSet<>(cartItemIds).size() != cartItemIds.size()) {
      throw new DomainException(HttpStatus.BAD_REQUEST, "Cart item bị trùng.");
    }
    CartClient.CartResponse cart;
    try {
      cart = cartClient.getSelection(userId, new CartClient.CartSelectionRequest(cartItemIds));
    } catch (Exception e) {
      FeignException feignEx =
          e instanceof FeignException fe
              ? fe
              : (e.getCause() instanceof FeignException causeFe ? causeFe : null);
      if (feignEx != null) {
        if (feignEx.status() == 503) {
          throw new DomainException(
              HttpStatus.SERVICE_UNAVAILABLE,
              "CATALOG_UNAVAILABLE",
              "Không thể xác minh sản phẩm.");
        }
        throw new DomainException(
            HttpStatus.CONFLICT, "CART_CHANGED", "Giỏ hàng đã thay đổi, vui lòng tải lại.");
      }
      throw e;
    }
    if (cart == null || cart.items() == null || cart.items().isEmpty()) {
      log.warn("Attempted to place order with an empty cart for user ID: {}", userId);
      throw new DomainException(
          HttpStatus.CONFLICT,
          "EMPTY_CART",
          "Giỏ hàng của bạn đang trống. Vui lòng thêm sản phẩm vào giỏ hàng trước khi đặt hàng.");
    }
    if (cart.version() != cartVersion || cart.totalSalePrice() != expectedTotalDiscountedPrice) {
      throw new DomainException(
          HttpStatus.CONFLICT,
          "CART_CHANGED",
          "Giỏ hàng hoặc giá sản phẩm đã thay đổi, vui lòng xác nhận lại.");
    }

    // Fetch Shipping Address from auth-service via FeignClient
    UserClient.AddressResponse addrResp = userClient.getAddressById(userId, addressId);
    if (addrResp == null) {
      log.warn("Address not found with ID: {} for user ID: {}", addressId, userId);
      throw new DomainException(
          HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND", "Địa chỉ giao hàng không hợp lệ.");
    }
    return new ValidatedOrderContext(cart, addrResp);
  }

  private List<Order> persistOrderAndPublishEvent(
      Long userId,
      String userEmail,
      PaymentMethod paymentMethod,
      String idempotencyKey,
      String fingerprint,
      ValidatedOrderContext context) {
    CartClient.CartResponse cart = context.cart();
    UserClient.AddressResponse addrResp = context.addressResponse();

    OrderAddress shippingAddress = new OrderAddress();
    shippingAddress.setFullName(addrResp.fullName());
    shippingAddress.setProvince(addrResp.province());
    shippingAddress.setDistrict(addrResp.district());
    shippingAddress.setWard(addrResp.ward());
    shippingAddress.setStreet(addrResp.street());
    shippingAddress.setNote(addrResp.note());
    shippingAddress.setPhoneNumber(addrResp.phoneNumber());

    List<Order> createdOrders = new ArrayList<>();

    // Calculate pricing
    long totalOriginalPrice = 0;
    long totalDiscountedPrice = 0;
    int totalItemsCount = 0;

    for (CartClient.CartItemResponse item : cart.items()) {
      totalOriginalPrice += item.price() * item.quantity();
      totalDiscountedPrice += item.salePrice() * item.quantity();
      totalItemsCount += item.quantity();
    }
    long totalDiscount = totalOriginalPrice - totalDiscountedPrice;

    // Create Order Entity
    Order order = new Order();
    order.setUserId(userId);
    order.setUserEmail(userEmail);
    order.setOrderDate(LocalDateTime.now());
    order.setShippingAddress(shippingAddress);
    order.setOrderStatus(OrderStatus.PENDING);
    order.setPaymentStatus(PaymentStatus.PENDING);
    order.setStockReserved(false);
    order.setIdempotencyKey(idempotencyKey);
    order.setRequestFingerprint(fingerprint);
    PaymentMethod selectedPaymentMethod = paymentMethod != null ? paymentMethod : PaymentMethod.COD;
    order.setPaymentMethod(selectedPaymentMethod);
    order.setExpiresAt(expirationFor(selectedPaymentMethod, Instant.now()));

    order.setOriginalPrice(totalOriginalPrice);
    order.setTotalItems(totalItemsCount);
    order.setDiscount(totalDiscount);
    order.setTotalDiscountedPrice(totalDiscountedPrice);

    Order savedOrderIntermediate = orderRepository.save(order);
    log.debug("Saved intermediate order ID: {}", savedOrderIntermediate.getId());

    List<OrderItem> orderItems = new ArrayList<>();
    List<OrderCreatedEvent.OrderItemEvent> eventItems = new ArrayList<>();

    for (CartClient.CartItemResponse cartItem : cart.items()) {
      OrderItem orderItem = new OrderItem();
      orderItem.setOrder(savedOrderIntermediate);
      orderItem.setProductId(cartItem.productId());
      orderItem.setVariantId(cartItem.variantId());
      orderItem.setCartItemId(cartItem.id());
      orderItem.setSku(cartItem.sku());
      orderItem.setProductName(cartItem.productName());
      orderItem.setProductImageUrl(cartItem.productImageUrl());
      orderItem.setQuantity(cartItem.quantity());
      orderItem.setPrice(cartItem.price());
      orderItem.setVariantName(cartItem.variantName());
      orderItem.setDiscountPercent(cartItem.discountPercent());
      orderItem.setDiscountedPrice(cartItem.salePrice());
      orderItem.setDeliveryDate(LocalDateTime.now().plusDays(7));
      orderItems.add(orderItem);

      eventItems.add(
          new OrderCreatedEvent.OrderItemEvent(
              cartItem.id(),
              cartItem.productId(),
              cartItem.variantId(),
              cartItem.quantity(),
              cartItem.price()));
    }

    savedOrderIntermediate.setOrderItems(orderItems);
    Order finalSavedOrder = orderRepository.save(savedOrderIntermediate);
    createdOrders.add(finalSavedOrder);
    log.debug("Successfully created order ID: {} with status PENDING", finalSavedOrder.getId());

    eventPublisher.publishEvent(
        new OrderCreatedEvent(finalSavedOrder.getId(), userId, userEmail, eventItems));

    return createdOrders;
  }

  @Transactional
  public Order confirmedOrder(Long orderId) {
    Order order = findOrderByIdForUpdate(orderId);
    if (order.getOrderStatus() != OrderStatus.PENDING) {
      throw invalidOrderState(
          "Đơn hàng không thể xác nhận ở trạng thái hiện tại (" + order.getOrderStatus() + ")");
    }
    if (!confirmOrderIfReady(order)) {
      throw invalidOrderState("Đơn hàng chưa hoàn tất giữ hàng hoặc thanh toán.");
    }
    log.info("Order ID {} confirmed.", orderId);
    Order savedOrder = orderRepository.save(order);
    publishOrderConfirmation(savedOrder);
    return savedOrder;
  }

  @Transactional
  public Order shippedOrder(Long orderId) {
    Order order = findOrderByIdForUpdate(orderId);
    if (order.getOrderStatus() != OrderStatus.CONFIRMED) {
      throw invalidOrderState(
          "Đơn hàng phải được xác nhận trước khi gửi (trạng thái hiện tại: "
              + order.getOrderStatus()
              + ")");
    }
    order.setOrderStatus(OrderStatus.SHIPPED);
    log.info("Order ID {} shipped.", orderId);
    return orderRepository.save(order);
  }

  @Transactional
  public Order deliveredOrder(Long orderId) {
    Order order = findOrderByIdForUpdate(orderId);
    if (order.getOrderStatus() != OrderStatus.SHIPPED) {
      throw invalidOrderState(
          "Đơn hàng phải được gửi trước khi giao (trạng thái hiện tại: "
              + order.getOrderStatus()
              + ")");
    }
    order.setOrderStatus(OrderStatus.DELIVERED);
    order.setPaymentStatus(PaymentStatus.COMPLETED);
    order.setDeliveryDate(LocalDateTime.now());
    Map<Long, Integer> quantities = new LinkedHashMap<>();
    order
        .getOrderItems()
        .forEach(item -> quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum));
    eventPublisher.publishEvent(
        new OrderDeliveredEvent(
            orderId,
            order.getUserId(),
            quantities.entrySet().stream()
                .map(entry -> new OrderDeliveredEvent.Item(entry.getKey(), entry.getValue()))
                .toList()));
    log.info("Order ID {} delivered.", orderId);
    return orderRepository.save(order);
  }

  @Transactional
  public Order cancelOrder(Long orderId) {
    Order order = findOrderByIdForUpdate(orderId);
    if (order.getOrderStatus() == OrderStatus.SHIPPED
        || order.getOrderStatus() == OrderStatus.DELIVERED
        || order.getOrderStatus() == OrderStatus.CANCELLED) {
      throw invalidOrderState("Không thể hủy đơn hàng ở trạng thái " + order.getOrderStatus());
    }

    order.setOrderStatus(OrderStatus.CANCELLED);

    boolean paidVnpay =
        order.getPaymentMethod() == PaymentMethod.VNPAY
            && order.getPaymentStatus() == PaymentStatus.COMPLETED;
    applyCancellationPaymentStatus(order);
    if (paidVnpay) {
      order.setPaymentReviewRequired(true);
      log.warn("Order ID {} cancelled after VNPAY completion; manual refund is required.", orderId);
    } else {
      log.info("Order ID {} cancelled. Payment status set to CANCELLED.", orderId);
    }

    Order saved = orderRepository.save(order);
    publishOrderCancelled(saved);
    return saved;
  }

  @Transactional
  public Order updateOrderStatus(Long orderId, OrderStatus newStatus) {
    Order order = findOrderById(orderId);
    if (order.getOrderStatus() == newStatus) {
      return order;
    }
    return switch (newStatus) {
      case CONFIRMED -> confirmedOrder(orderId);
      case SHIPPED -> shippedOrder(orderId);
      case DELIVERED -> deliveredOrder(orderId);
      case CANCELLED -> cancelOrder(orderId);
      case PENDING -> throw invalidOrderState("Không thể chuyển đơn hàng về PENDING.");
    };
  }

  @Transactional(readOnly = true)
  public List<Order> getAllOrders() {
    return orderRepository.findAll();
  }

  @Transactional
  public void deleteOrder(Long orderId) {
    Order order = findOrderByIdForUpdate(orderId);
    if (order.getOrderStatus() != OrderStatus.CANCELLED
        || order.getPaymentMethod() == PaymentMethod.VNPAY) {
      throw new DomainException(
          HttpStatus.CONFLICT,
          "Chỉ có thể xóa đơn COD đã hủy. Đơn VNPAY cần giữ lại để đối soát thanh toán đến muộn.");
    }
    orderRepository.delete(order);
    log.info("Order ID {} deleted successfully.", orderId);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getOrderStatistics(LocalDate start, LocalDate end) {
    LocalDateTime startDateTime = start != null ? start.atStartOfDay() : null;
    LocalDateTime endDateTime = end != null ? end.atTime(23, 59, 59) : null;

    long totalOrders =
        orderRepository.countOrdersByStatusAndDateRange(null, startDateTime, endDateTime);
    long pendingOrders =
        orderRepository.countOrdersByStatusAndDateRange(
            OrderStatus.PENDING, startDateTime, endDateTime);
    long confirmedOrders =
        orderRepository.countOrdersByStatusAndDateRange(
            OrderStatus.CONFIRMED, startDateTime, endDateTime);
    long deliveredOrders =
        orderRepository.countOrdersByStatusAndDateRange(
            OrderStatus.DELIVERED, startDateTime, endDateTime);
    long cancelledOrders =
        orderRepository.countOrdersByStatusAndDateRange(
            OrderStatus.CANCELLED, startDateTime, endDateTime);

    Double totalRevenue = orderRepository.sumRevenueByDateRange(startDateTime, endDateTime);
    totalRevenue = totalRevenue != null ? totalRevenue : 0.0;

    double averageOrderValue = deliveredOrders > 0 ? totalRevenue / deliveredOrders : 0;

    Map<String, Object> result = new HashMap<>();
    result.put("totalOrders", totalOrders);
    result.put("pendingOrders", pendingOrders);
    result.put("confirmedOrders", confirmedOrders);
    result.put("completedOrders", deliveredOrders);
    result.put("cancelledOrders", cancelledOrders);
    result.put("totalRevenue", totalRevenue);
    result.put("averageOrderValue", averageOrderValue);

    return result;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> getDailyRevenue(LocalDate start, LocalDate end) {
    LocalDateTime startDateTime = start != null ? start.atStartOfDay() : null;
    LocalDateTime endDateTime = end != null ? end.atTime(23, 59, 59) : null;

    List<Object[]> rows = orderRepository.findDailyRevenueByDateRange(startDateTime, endDateTime);
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object[] row : rows) {
      String dateStr = row[0] != null ? row[0].toString() : "";
      Number revenueNum = (Number) row[1];
      result.add(
          Map.of("name", dateStr, "revenue", revenueNum != null ? revenueNum.doubleValue() : 0.0));
    }
    return result;
  }

  @Transactional(readOnly = true)
  public List<OrderDetailDTO> getAllOrdersByJF() {
    // Find all orders sorting by date descending
    List<Order> orders = orderRepository.findAllByOrderByOrderDateDesc();
    return orders.stream().map(OrderDetailDTO::new).collect(Collectors.toList());
  }

  @Transactional(readOnly = true)
  public List<TopSellingProductResponse> getTopSellingProducts(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("Top-selling limit must be positive");
    }
    return orderItemRepository.findTopSellingProducts(
        OrderStatus.DELIVERED, PageRequest.of(0, limit));
  }

  @Transactional(readOnly = true)
  public List<ProductRevenueResponse> getProductRevenue() {
    return orderItemRepository.findProductRevenue(OrderStatus.DELIVERED);
  }

  @Transactional
  public void updatePaymentStatus(Long orderId, PaymentStatus status) {
    updatePaymentStatus(orderId, status, null);
  }

  @Transactional
  public void updatePaymentStatus(Long orderId, PaymentStatus status, Long version) {
    updatePaymentStatus(orderId, status, version, null);
  }

  @Transactional
  public void updatePaymentStatus(
      Long orderId, PaymentStatus status, Long version, Boolean reviewRequired) {
    Order order = findOrderByIdForUpdate(orderId);
    if (version != null && version <= order.getPaymentEventVersion()) return;
    if (version != null) order.setPaymentEventVersion(version);
    boolean wasPending = order.getOrderStatus() == OrderStatus.PENDING;
    if (Boolean.TRUE.equals(reviewRequired) || isLateCompletedPayment(order, status)) {
      order.setPaymentReviewRequired(true);
    }
    if (order.getPaymentStatus() == PaymentStatus.COMPLETED && status != PaymentStatus.COMPLETED) {
      log.info("Ignored payment status regression {} for completed order {}", status, orderId);
      return;
    }
    applyPaymentStatus(order, status);
    orderRepository.save(order);
    publishOrderConfirmationIfNewlyConfirmed(wasPending, order);
    log.info("Successfully updated payment status for order ID {} to {}", orderId, status);
  }

  static void applyPaymentStatus(Order order, PaymentStatus status) {
    order.setPaymentStatus(status);
    confirmOrderIfReady(order);
  }

  static boolean isLateCompletedPayment(Order order, PaymentStatus status) {
    return order.getOrderStatus() == OrderStatus.CANCELLED && status == PaymentStatus.COMPLETED;
  }

  static Instant expirationFor(PaymentMethod paymentMethod, Instant createdAt) {
    return paymentMethod == PaymentMethod.VNPAY ? createdAt.plus(VNPAY_TTL) : null;
  }

  @Transactional
  public boolean expireVnpayOrder(Long orderId, Instant cutoff) {
    Order order = findOrderByIdForUpdate(orderId);
    if (!isExpiredVnpayOrder(order, cutoff)) {
      return false;
    }
    order.setOrderStatus(OrderStatus.CANCELLED);
    order.setPaymentStatus(PaymentStatus.CANCELLED);
    orderRepository.save(order);
    publishOrderCancelled(order);
    log.info("Cancelled expired unpaid VNPAY order {}.", orderId);
    return true;
  }

  static boolean isExpiredVnpayOrder(Order order, Instant cutoff) {
    return order.getOrderStatus() == OrderStatus.PENDING
        && order.getPaymentMethod() == PaymentMethod.VNPAY
        && order.getPaymentStatus() != PaymentStatus.COMPLETED
        && order.getExpiresAt() != null
        && !order.getExpiresAt().isAfter(cutoff);
  }

  static void applyCancellationPaymentStatus(Order order) {
    if (order.getPaymentMethod() != PaymentMethod.VNPAY
        || order.getPaymentStatus() != PaymentStatus.COMPLETED) {
      order.setPaymentStatus(PaymentStatus.CANCELLED);
    }
  }

  @Transactional
  public void handleStockResult(StockResultEvent event) {
    Order order = findOrderByIdForUpdate(event.orderId());
    boolean wasPending = order.getOrderStatus() == OrderStatus.PENDING;
    applyStockResult(order, event.success());
    orderRepository.save(order);
    publishOrderConfirmationIfNewlyConfirmed(wasPending, order);
    if (isNewlyCancelled(wasPending, order)) {
      publishOrderCancelled(order);
    }
  }

  static void applyStockResult(Order order, boolean success) {
    if (order.getOrderStatus() != OrderStatus.PENDING) {
      return;
    }
    if (!success) {
      order.setStockReserved(false);
      order.setOrderStatus(OrderStatus.CANCELLED);
      applyCancellationPaymentStatus(order);
      return;
    }
    order.setStockReserved(true);
    confirmOrderIfReady(order);
  }

  static boolean confirmOrderIfReady(Order order) {
    if (order.getOrderStatus() != OrderStatus.PENDING
        || !order.isStockReserved()
        || (order.getPaymentMethod() == PaymentMethod.VNPAY
            && order.getPaymentStatus() != PaymentStatus.COMPLETED)) {
      return false;
    }
    order.setOrderStatus(OrderStatus.CONFIRMED);
    return true;
  }

  private void publishOrderConfirmationIfNewlyConfirmed(boolean wasPending, Order order) {
    if (isNewlyConfirmed(wasPending, order)) {
      publishOrderConfirmation(order);
    }
  }

  static boolean isNewlyCancelled(boolean wasPending, Order order) {
    return wasPending && order.getOrderStatus() == OrderStatus.CANCELLED;
  }

  /** Builds the PII-free cancellation event: product quantities summed across variants. */
  static OrderCancelledEvent orderCancelledEvent(Order order) {
    Map<Long, Integer> quantities = new LinkedHashMap<>();
    order
        .getOrderItems()
        .forEach(item -> quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum));
    return new OrderCancelledEvent(
        order.getId(),
        order.getUserId(),
        quantities.entrySet().stream()
            .map(entry -> new OrderCancelledEvent.Item(entry.getKey(), entry.getValue()))
            .toList());
  }

  private void publishOrderCancelled(Order order) {
    eventPublisher.publishEvent(orderCancelledEvent(order));
    eventPublisher.publishEvent(
        new StockReleaseEvent(
            order.getId(),
            order.getOrderItems().stream()
                .map(
                    i ->
                        Map.<String, Object>of(
                            "variantId", i.getVariantId(), "quantity", i.getQuantity()))
                .toList()));
    order.setStockReserved(false);
  }

  static boolean isNewlyConfirmed(boolean wasPending, Order order) {
    return wasPending && order.getOrderStatus() == OrderStatus.CONFIRMED;
  }

  private void publishOrderConfirmation(Order order) {
    eventPublisher.publishEvent(
        new CartConfirmedEvent(
            order.getId(),
            order.getUserId(),
            order.getOrderItems().stream()
                .filter(i -> i.getCartItemId() != null)
                .map(
                    i ->
                        Map.<String, Object>of(
                            "cartItemId", i.getCartItemId(), "quantity", i.getQuantity()))
                .toList()));
    OrderAddress address = order.getShippingAddress();
    Map<String, Object> addressMap = new HashMap<>();
    addressMap.put("fullName", address.getFullName());
    addressMap.put("phoneNumber", address.getPhoneNumber());
    addressMap.put("street", address.getStreet());
    addressMap.put("ward", address.getWard());
    addressMap.put("district", address.getDistrict());
    addressMap.put("province", address.getProvince());

    List<Map<String, Object>> items =
        order.getOrderItems().stream()
            .map(
                item -> {
                  Map<String, Object> value = new HashMap<>();
                  value.put("productName", item.getProductName());
                  value.put("variantName", item.getVariantName());
                  value.put("sku", item.getSku());
                  value.put("quantity", item.getQuantity());
                  value.put("price", item.getPrice());
                  value.put("discountedPrice", item.getDiscountedPrice());
                  value.put("productImageUrl", item.getProductImageUrl());
                  return value;
                })
            .toList();

    Map<String, Object> orderMap = new HashMap<>();
    orderMap.put("id", order.getId());
    orderMap.put("orderCode", order.getOrderCode());
    orderMap.put("status", order.getOrderStatus().name());
    orderMap.put("recipientName", address.getFullName());
    orderMap.put(
        "orderDate", order.getOrderDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
    orderMap.put("paymentMethod", order.getPaymentMethod().name());
    orderMap.put("totalAmount", order.getTotalDiscountedPrice());
    orderMap.put("shippingAddress", addressMap);
    orderMap.put("items", items);

    Map<String, Object> payload = new HashMap<>();
    payload.put("email", order.getUserEmail());
    payload.put("order", orderMap);
    eventPublisher.publishEvent(new OrderConfirmedEvent(payload));
  }

  private Order findOrderByIdForUpdate(Long orderId) {
    return orderRepository
        .findByIdForUpdate(orderId)
        .orElseThrow(
            () ->
                new DomainException(
                    HttpStatus.NOT_FOUND,
                    "ORDER_NOT_FOUND",
                    "Không tìm thấy đơn hàng với ID: " + orderId));
  }

  private static DomainException invalidOrderState(String message) {
    return new DomainException(HttpStatus.CONFLICT, "INVALID_ORDER_STATE", message);
  }
}
