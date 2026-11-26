package com.kyro.payment;

import com.google.gson.Gson;
import com.kyro.enums.PaymentStatus;
import com.kyro.exceptions.DomainException;
import com.kyro.payment.client.OrderClient;
import com.kyro.payment.dto.PaymentDTO;
import com.kyro.reliability.Outbox;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Service for VNPay payment gateway integration. Communicates with order-service using Feign
 * clients.
 */
@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
  private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final DateTimeFormatter VNPAY_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(VIETNAM_ZONE);

  @Value("${vnpay.tmnCode}")
  private String vnp_TmnCode;

  @Value("${vnpay.hashSecret}")
  private String vnp_HashSecret;

  @Value("${vnpay.url}")
  private String vnp_PayUrl;

  @Value("${vnpay.returnUrl}")
  private String vnp_Returnurl;

  private final OrderClient orderClient;
  private final PaymentRepository payments;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Outbox outbox;

  public PaymentService(
      OrderClient orderClient,
      PaymentRepository payments,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      Outbox outbox) {
    this.orderClient = orderClient;
    this.payments = payments;
    this.jdbc = jdbc;
    this.tx = transactions == null ? null : new TransactionTemplate(transactions);
    this.outbox = outbox;
  }

  public String createPayment(Long orderId) {
    return createPayment(orderId, null);
  }

  public String createPayment(Long orderId, String idempotencyKey) {
    // HTTP validation must finish before acquiring database locks.
    var order = orderClient.getOrderById(orderId);
    if (order == null)
      throw new DomainException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Không tìm thấy đơn hàng");
    if (!"VNPAY".equals(order.paymentMethod())
        || !"PENDING".equals(order.orderStatus())
        || "COMPLETED".equals(order.paymentStatus())
        || isExpired(order.expiresAt(), Instant.now()))
      throw new DomainException(
          HttpStatus.CONFLICT, "INVALID_PAYMENT_STATE", "Đơn hàng không thể thanh toán");
    long amount = order.totalDiscountedPrice() == null ? 0 : order.totalDiscountedPrice();
    if (amount <= 0) throw new IllegalArgumentException("Payment amount must be positive");
    Math.multiplyExact(amount, 100L);
    String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
    if (key != null && key.length() > 100)
      throw new IllegalArgumentException("Idempotency-Key too long");
    return tx.execute(
        status -> {
          // ON CONFLICT serializes first creation; row lock serializes later attempts and
          // callbacks.
          jdbc.update(
              "insert into"
                  + " payments(order_id,payment_method,payment_status,total_amount,created_at,updated_at)"
                  + " values (?,'VNPAY','PENDING',?,now(),now()) on conflict (order_id) do nothing",
              orderId,
              amount);
          Payment payment = payments.findByOrderIdForUpdate(orderId).orElseThrow();
          if (payment.getPaymentStatus() == PaymentStatus.COMPLETED)
            throw new DomainException(
                HttpStatus.CONFLICT, "PAYMENT_ALREADY_COMPLETED", "Đơn đã thanh toán");
          if (payment.getTotalAmount() != amount)
            throw new IllegalStateException("Order amount changed after payment creation");
          if (key != null) {
            var prior =
                jdbc.queryForList(
                    "select payment_url from payment_transactions where payment_id=? and"
                        + " idempotency_key=?",
                    payment.getId(),
                    key);
            if (!prior.isEmpty()) return (String) prior.getFirst().get("payment_url");
          }
          String reference = orderId + "_" + UUID.randomUUID().toString().replace("-", "");
          String url = checkoutUrl(order, reference, amount);
          jdbc.update(
              "insert into"
                  + " payment_transactions(payment_id,transaction_reference,amount,transaction_status,payment_url,idempotency_key)"
                  + " values (?,?,?,'PENDING',?,?)",
              payment.getId(),
              reference,
              amount,
              url,
              key);
          payment.setPaymentStatus(PaymentStatus.PENDING);
          payments.save(payment);
          publish(payment);
          return url;
        });
  }

  private String checkoutUrl(OrderClient.OrderResponse order, String reference, long amount) {
    Map<String, String> params = new TreeMap<>();
    params.put("vnp_Version", "2.1.0");
    params.put("vnp_Command", "pay");
    params.put("vnp_TmnCode", vnp_TmnCode);
    params.put("vnp_Amount", Long.toString(Math.multiplyExact(amount, 100L)));
    params.put("vnp_CurrCode", "VND");
    params.put("vnp_TxnRef", reference);
    params.put("vnp_OrderInfo", "Thanh toan don hang " + order.orderCode());
    params.put("vnp_OrderType", "other");
    params.put("vnp_Locale", "vn");
    params.put("vnp_ReturnUrl", vnp_Returnurl);
    params.put("vnp_IpAddr", getIpAddress());
    params.put("vnp_CreateDate", formatVnpayDate(Instant.now()));
    params.put("vnp_ExpireDate", formatVnpayDate(order.expiresAt()));
    String query = callbackHashData(params);
    return vnp_PayUrl + "?" + query + "&vnp_SecureHash=" + hmacSHA512(vnp_HashSecret, query);
  }

  public record CallbackResult(
      Payment payment, String transactionId, boolean success, boolean duplicate) {}

  public CallbackResult processPaymentCallback(Map<String, String> params) {
    validateSignature(params);
    String reference = required(params, "vnp_TxnRef");
    var rows =
        jdbc.queryForList(
            "select p.order_id from payment_transactions t join payments p on p.id=t.payment_id"
                + " where t.transaction_reference=?",
            reference);
    if (rows.isEmpty())
      throw new DomainException(
          HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Không tìm thấy giao dịch");
    long orderId = ((Number) rows.getFirst().get("order_id")).longValue();
    String review = null;
    try {
      var order = orderClient.getOrderById(orderId);
      if (order == null || "CANCELLED".equals(order.orderStatus()))
        review = "SUCCESS_AFTER_ORDER_CANCELLED";
    } catch (RuntimeException error) {
      review = "ORDER_STATE_UNVERIFIED";
    }
    String reviewReason = review;
    return tx.execute(
        status -> {
          Payment payment = payments.findByOrderIdForUpdate(orderId).orElseThrow();
          var attempt =
              jdbc.queryForMap(
                  "select * from payment_transactions where transaction_reference=? for update",
                  reference);
          validateCallback(params, payment);
          boolean success =
              "00".equals(params.get("vnp_ResponseCode"))
                  && "00".equals(params.get("vnp_TransactionStatus"));
          String old = (String) attempt.get("transaction_status");
          boolean cancelled =
              jdbc.queryForObject(
                      "select count(*) from payment_order_cancellations where order_id=?",
                      Integer.class,
                      orderId)
                  > 0;
          boolean duplicate = "SUCCESS".equals(old) || (!success && "FAILED".equals(old));
          long id = ((Number) attempt.get("id")).longValue();
          if (!"SUCCESS".equals(old)) {
            String gatewayNumber = params.get("vnp_TransactionNo");
            if (!success
                || gatewayNumber == null
                || gatewayNumber.isBlank()
                || "0".equals(gatewayNumber)) gatewayNumber = null;
            jdbc.update(
                "update payment_transactions set"
                    + " transaction_status=?,gateway_transaction_no=?,response_code=?,payment_log=?,updated_at=now()"
                    + " where id=?",
                success ? "SUCCESS" : "FAILED",
                gatewayNumber,
                params.get("vnp_ResponseCode"),
                new Gson().toJson(params),
                id);
          }
          long successes =
              jdbc.queryForObject(
                  "select count(*) from payment_transactions where payment_id=? and"
                      + " transaction_status='SUCCESS'",
                  Long.class,
                  payment.getId());
          long pending =
              jdbc.queryForObject(
                  "select count(*) from payment_transactions where payment_id=? and"
                      + " transaction_status='PENDING'",
                  Long.class,
                  payment.getId());
          PaymentStatus previous = payment.getPaymentStatus();
          boolean previouslyRequiredReview = payment.isReviewRequired();
          payment.setPaymentStatus(
              successes > 0
                  ? PaymentStatus.COMPLETED
                  : pending > 0 ? PaymentStatus.PENDING : PaymentStatus.FAILED);
          if (success && payment.getWinningTransactionId() == null) {
            payment.setWinningTransactionId(id);
            payment.setPaymentDate(LocalDateTime.now());
          }
          if (successes > 1 || success && (reviewReason != null || cancelled)) {
            payment.setReviewRequired(true);
            payment.setReviewReason(
                successes > 1
                    ? "MULTIPLE_SUCCESSFUL_CHARGES"
                    : (cancelled ? "SUCCESS_AFTER_ORDER_CANCELLED" : reviewReason));
          }
          payments.save(payment);
          if (previous != payment.getPaymentStatus()
              || previouslyRequiredReview != payment.isReviewRequired()) publish(payment);
          return new CallbackResult(
              payment, reference, success || "SUCCESS".equals(old), duplicate);
        });
  }

  private void publish(Payment payment) {
    outbox.append(
        "payment-exchange",
        "payment.status.updated",
        payment.getOrderId(),
        Map.of(
            "orderId",
            payment.getOrderId(),
            "status",
            payment.getPaymentStatus().name(),
            "reviewRequired",
            payment.isReviewRequired()));
  }

  public PaymentDTO toDto(Payment payment) {
    var references =
        jdbc.queryForList(
            "select transaction_reference from payment_transactions where payment_id=? order by"
                + " (id=coalesce(?,0)) desc,id desc limit 1",
            String.class,
            payment.getId(),
            payment.getWinningTransactionId());
    return new PaymentDTO(payment, references.isEmpty() ? null : references.getFirst());
  }

  public List<Map<String, Object>> transactions(Long orderId) {
    return jdbc.queryForList(
        "select"
            + " t.id,t.transaction_reference,t.gateway_provider,t.amount,t.transaction_status,t.response_code,t.created_at,t.updated_at"
            + " from payment_transactions t join payments p on p.id=t.payment_id where p.order_id=?"
            + " order by t.id",
        orderId);
  }

  void validateCallback(Map<String, String> params, Payment payment) {
    if (!vnp_TmnCode.equals(required(params, "vnp_TmnCode"))) {
      throw new IllegalArgumentException("Mã website VNPay không hợp lệ");
    }
    required(params, "vnp_ResponseCode");
    required(params, "vnp_TransactionStatus");
    long amount;
    try {
      amount = Long.parseLong(required(params, "vnp_Amount"));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Số tiền VNPay không hợp lệ");
    }
    if (amount != Math.multiplyExact(payment.getTotalAmount(), 100L)) {
      throw new IllegalArgumentException("Số tiền VNPay không khớp đơn hàng");
    }
  }

  void validateSignature(Map<String, String> params) {
    String provided = required(params, "vnp_SecureHash").toLowerCase(Locale.ROOT);
    String expected = hmacSHA512(vnp_HashSecret, callbackHashData(params));
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        provided.getBytes(StandardCharsets.US_ASCII))) {
      throw new IllegalArgumentException("Chữ ký VNPay không hợp lệ");
    }
  }

  private String callbackHashData(Map<String, String> params) {
    return params.entrySet().stream()
        .filter(e -> e.getKey().startsWith("vnp_"))
        .filter(e -> !"vnp_SecureHash".equals(e.getKey()))
        .filter(e -> !"vnp_SecureHashType".equals(e.getKey()))
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .sorted(Map.Entry.comparingByKey())
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .reduce((left, right) -> left + "&" + right)
        .orElse("");
  }

  private static String required(Map<String, String> params, String name) {
    String value = params.get(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Thiếu " + name);
    return value;
  }

  static PaymentStatus resolvedStatus(PaymentStatus current, boolean completed) {
    if (current == PaymentStatus.COMPLETED) return current;
    return completed ? PaymentStatus.COMPLETED : PaymentStatus.FAILED;
  }

  static boolean isExpired(Instant expiresAt, Instant now) {
    return expiresAt == null || !now.isBefore(expiresAt);
  }

  static String formatVnpayDate(Instant instant) {
    return VNPAY_DATE_FORMAT.format(instant);
  }

  private String getIpAddress() {
    try {
      HttpServletRequest request =
          ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
      String ipAddress = request.getHeader("X-FORWARDED-FOR");
      if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
        ipAddress = request.getRemoteAddr();
      }
      if (ipAddress != null && ipAddress.contains(",")) {
        ipAddress = ipAddress.split(",")[0].trim();
      }
      if (ipAddress == null
          || ipAddress.isEmpty()
          || "0:0:0:0:0:0:0:1".equals(ipAddress)
          || "::1".equals(ipAddress)) {
        ipAddress = "127.0.0.1";
      }
      return ipAddress;
    } catch (Exception e) {
      return "127.0.0.1";
    }
  }

  private String hmacSHA512(String key, String data) {
    try {
      Mac sha512_HMAC = Mac.getInstance("HmacSHA512");
      SecretKeySpec secret_key =
          new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
      sha512_HMAC.init(secret_key);
      byte[] hash = sha512_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("Không thể tạo chữ ký VNPay", ex);
    }
  }
}
