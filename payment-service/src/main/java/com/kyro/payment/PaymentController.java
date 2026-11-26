package com.kyro.payment;

import com.kyro.exceptions.DomainException;
import com.kyro.payment.client.OrderClient;
import com.kyro.payment.dto.PaymentDTO;
import feign.FeignException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller exposing endpoints for creating payments and handling VNPay callbacks. Reads
 * authenticated user state from gateway-injected headers.
 */
@RestController
@RequestMapping("${api.prefix}")
public class PaymentController {

  private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

  private final PaymentService paymentService;
  private final OrderClient orderClient;
  private final PaymentRepository paymentRepository;

  public PaymentController(
      PaymentService paymentService, OrderClient orderClient, PaymentRepository paymentRepository) {
    this.paymentService = paymentService;
    this.orderClient = orderClient;
    this.paymentRepository = paymentRepository;
  }

  /** Creates a VNPay checkout URL for an order. */
  @PostMapping("/orders/{orderId}/payments")
  public ResponseEntity<Map<String, Object>> createPayment(
      @RequestHeader("X-User-Id") Long userId,
      @RequestHeader(value = "X-User-Roles", required = false) String roles,
      @PathVariable Long orderId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {

    OrderClient.OrderResponse order;
    try {
      order = orderClient.getOrderById(orderId);
    } catch (FeignException e) {
      log.error("Lỗi khi lấy thông tin đơn hàng từ order-service cho orderId {}: ", orderId, e);
      if (e.status() == HttpStatus.NOT_FOUND.value()) {
        throw new DomainException(
            HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Không tìm thấy đơn hàng với ID: " + orderId);
      }
      throw new DomainException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "DEPENDENCY_UNAVAILABLE",
          "Không thể truy cập dịch vụ đơn hàng");
    }
    if (order == null) {
      throw new DomainException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng với ID: " + orderId);
    }
    verifyOwnership(userId, roles, order);

    // Create payment URL
    String paymentUrl = paymentService.createPayment(orderId, key);

    return ResponseEntity.ok(
        Map.of(
            "success", true, "message", "Tạo URL thanh toán thành công", "paymentUrl", paymentUrl));
  }

  /** Handles VNPay callback to record transaction outcome and update order-service. */
  @GetMapping("/payment-providers/vnpay/callback")
  public ResponseEntity<Map<String, Object>> vnpayCallback(
      @RequestParam Map<String, String> params) {
    if (params.get("vnp_TxnRef") == null || params.get("vnp_TxnRef").isEmpty()) {
      throw new IllegalArgumentException("Thiếu mã giao dịch vnp_TxnRef");
    }

    var result = paymentService.processPaymentCallback(params);
    Payment payment = result.payment();
    Map<String, Object> response = new HashMap<>();
    response.put("success", result.success());
    response.put("message", result.success() ? "Thanh toán thành công" : "Payment failed");
    response.put("orderId", payment.getOrderId());
    response.put("paymentId", payment.getId());
    response.put("transactionId", result.transactionId());
    response.put("responseCode", params.get("vnp_ResponseCode"));

    return ResponseEntity.ok(response);
  }

  /** Gets payment details by order ID. */
  @GetMapping("/orders/{orderId}/payment")
  public ResponseEntity<PaymentDTO> getPaymentByOrderId(
      @RequestHeader("X-User-Id") Long userId,
      @RequestHeader(value = "X-User-Roles", required = false) String roles,
      @PathVariable Long orderId) {

    // Fetch order details to verify ownership
    OrderClient.OrderResponse order = orderClient.getOrderById(orderId);
    if (order == null) {
      throw new DomainException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng");
    }
    verifyOwnership(userId, roles, order);

    Payment payment =
        paymentRepository
            .findByOrderId(orderId)
            .orElseThrow(
                () ->
                    new DomainException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy thông tin thanh toán"));

    return ResponseEntity.ok(paymentService.toDto(payment));
  }

  @GetMapping("/payment-providers/vnpay/ipn")
  public Map<String, String> ipn(@RequestParam Map<String, String> params) {
    try {
      var result = paymentService.processPaymentCallback(params);
      return Map.of(
          "RspCode",
          result.duplicate() ? "02" : "00",
          "Message",
          result.duplicate() ? "Order already confirmed" : "Confirm Success");
    } catch (DomainException error) {
      return Map.of("RspCode", "01", "Message", "Order not found");
    } catch (IllegalArgumentException error) {
      String code =
          error.getMessage() != null && error.getMessage().contains("Số tiền") ? "04" : "97";
      return Map.of("RspCode", code, "Message", "Invalid callback");
    } catch (RuntimeException error) {
      log.error("IPN processing failed; provider should retry", error);
      return Map.of("RspCode", "99", "Message", "Unknown error");
    }
  }

  @GetMapping("/orders/{orderId}/payment/transactions")
  public Object transactions(
      @RequestHeader("X-User-Id") Long userId,
      @RequestHeader(value = "X-User-Roles", required = false) String roles,
      @PathVariable Long orderId) {
    verifyOwnership(userId, roles, orderClient.getOrderById(orderId));
    return paymentService.transactions(orderId);
  }

  @GetMapping("/admin/payments/reviews")
  public Object reviews(@RequestHeader("X-User-Roles") String roles) {
    if (!isAdmin(roles)) throw new DomainException(HttpStatus.FORBIDDEN, "Admin required");
    return paymentRepository.findByReviewRequiredTrue().stream()
        .map(paymentService::toDto)
        .toList();
  }

  @GetMapping("/admin/payments/reviews/{id}")
  public Object review(@RequestHeader("X-User-Roles") String roles, @PathVariable Long id) {
    if (!isAdmin(roles)) throw new DomainException(HttpStatus.FORBIDDEN, "Admin required");
    Payment payment =
        paymentRepository
            .findById(id)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "Payment not found"));
    return Map.of(
        "payment",
        paymentService.toDto(payment),
        "transactions",
        paymentService.transactions(payment.getOrderId()));
  }

  private static boolean isAdmin(String roles) {
    return roles != null
        && Arrays.stream(roles.split(","))
            .map(String::trim)
            .anyMatch(role -> "ADMIN".equals(role) || "ROLE_ADMIN".equals(role));
  }

  private static void verifyOwnership(Long userId, String roles, OrderClient.OrderResponse order) {
    if (!isAdmin(roles) && !userId.equals(order.userId())) {
      throw new DomainException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập đơn hàng này");
    }
  }
}
