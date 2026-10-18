package com.kyro.order;

import com.kyro.exceptions.DomainException;
import com.kyro.order.dto.CreateOrderRequest;
import com.kyro.order.dto.OrderDTO;
import com.kyro.order.dto.PageResponse;
import com.kyro.order.dto.UpdateOrderStatusRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Controller exposing endpoints for customer order lifecycle operations. Reads authenticated user
 * state from gateway-injected headers.
 */
@RestController
@RequestMapping("${api.prefix}/orders")
public class OrderController {
  private static final Logger log = LoggerFactory.getLogger(OrderController.class);

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  /** Gets order history for the logged-in user. */
  @GetMapping
  @Transactional(readOnly = true)
  public ResponseEntity<PageResponse<OrderDTO>> getUserOrders(
      @RequestHeader("X-User-Id") Long userId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String paymentMethod,
      @RequestParam(required = false) String paymentStatus,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate endDate,
      @RequestParam(required = false) Integer minTotal,
      @RequestParam(required = false) Integer maxTotal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) List<String> sort) {
    OrderFilter filter =
        OrderFilter.from(
            userId,
            null,
            status,
            paymentMethod,
            paymentStatus,
            startDate,
            endDate,
            minTotal,
            maxTotal);
    return ResponseEntity.ok(
        PageResponse.from(
            orderService
                .findOrders(filter, OrderService.orderPageable(page, size, sort))
                .map(OrderDTO::new)));
  }

  /** Places a new order from current cart items. */
  @PostMapping
  public ResponseEntity<Map<String, Object>> createOrder(
      @RequestHeader("X-User-Id") Long userId,
      @RequestHeader("X-User-Email") String userEmail,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateOrderRequest request) {

    List<Order> orders =
        orderService.placeOrderIdempotent(
            request.addressId(),
            userId,
            userEmail,
            request.paymentMethod(),
            request.cartItemIds(),
            request.cartVersion(),
            request.expectedTotalDiscountedPrice(),
            idempotencyKey);

    List<OrderDTO> orderDTOs = orders.stream().map(OrderDTO::new).collect(Collectors.toList());

    Map<String, Object> response = new HashMap<>();
    response.put("orders", orderDTOs);
    response.put("totalOrdersCreated", orders.size());
    response.put(
        "totalAmountForAllOrders",
        orders.stream()
            .mapToLong(
                order ->
                    order.getTotalDiscountedPrice() != null ? order.getTotalDiscountedPrice() : 0)
            .sum());
    response.put("message", "Đã tạo thành công " + orders.size() + " đơn hàng.");

    log.debug("Successfully created {} order(s) for user ID {}", orders.size(), userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /** Finds an order by its ID. */
  @GetMapping("/{id}")
  @Transactional(readOnly = true)
  public ResponseEntity<OrderDTO> findOrderById(
      @PathVariable("id") Long orderId, @RequestHeader("X-User-Id") Long userId) {
    Order order = orderService.findOrderById(orderId);
    if (!order.getUserId().equals(userId)) {
      throw new DomainException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem đơn hàng này.");
    }
    OrderDTO orderDTO = new OrderDTO(order);
    return new ResponseEntity<>(orderDTO, HttpStatus.OK);
  }

  /** Cancels an order. */
  @PatchMapping("/{id}/status")
  public ResponseEntity<OrderDTO> cancelOrder(
      @PathVariable("id") Long orderId,
      @RequestHeader("X-User-Id") Long userId,
      @Valid @RequestBody UpdateOrderStatusRequest request) {

    Order order = orderService.findOrderById(orderId);
    if (!order.getUserId().equals(userId)) {
      throw new DomainException(HttpStatus.FORBIDDEN, "Bạn không có quyền hủy đơn hàng này.");
    }

    if (request.status() != com.kyro.enums.OrderStatus.CANCELLED) {
      throw new DomainException(HttpStatus.BAD_REQUEST, "Khách hàng chỉ có thể hủy đơn hàng.");
    }
    Order cancelledOrder = orderService.updateOrderStatus(orderId, request.status());
    OrderDTO orderDTO = new OrderDTO(cancelledOrder);
    return ResponseEntity.ok(orderDTO);
  }
}
