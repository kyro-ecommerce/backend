package com.kyro.payment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kyro.enums.PaymentStatus;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentServiceTest {

  private static final String SECRET = "test-secret";
  private PaymentService service;

  @BeforeEach
  void setUp() {
    service = new PaymentService(null, null, null, null, null);
    ReflectionTestUtils.setField(service, "vnp_TmnCode", "TESTCODE");
    ReflectionTestUtils.setField(service, "vnp_HashSecret", SECRET);
  }

  @Test
  void validatesSignedCallbackAndAmount() {
    Payment payment = payment();
    Map<String, String> params = signedParams();

    assertDoesNotThrow(() -> service.validateSignature(params));
    assertDoesNotThrow(() -> service.validateCallback(params, payment));

    params.put("vnp_Amount", "9999");
    params.put("vnp_SecureHash", sign(params));
    assertThrows(IllegalArgumentException.class, () -> service.validateCallback(params, payment));
  }

  @Test
  void rejectsInvalidSignatureAndNeverRegressesCompletedPayment() {
    Map<String, String> params = signedParams();
    params.put("vnp_SecureHash", "bad-signature");

    assertThrows(IllegalArgumentException.class, () -> service.validateSignature(params));
    assertEquals(
        PaymentStatus.COMPLETED, PaymentService.resolvedStatus(PaymentStatus.FAILED, true));
    assertEquals(
        PaymentStatus.COMPLETED, PaymentService.resolvedStatus(PaymentStatus.COMPLETED, false));
  }

  @Test
  void formatsOrderExpirationInVietnamTimezone() {
    Instant expiration = Instant.parse("2026-08-16T08:15:30Z");

    assertEquals("20260816151530", PaymentService.formatVnpayDate(expiration));
    assertEquals(false, PaymentService.isExpired(expiration, expiration.minusSeconds(1)));
    assertEquals(true, PaymentService.isExpired(expiration, expiration));
  }

  private static Payment payment() {
    Payment payment = new Payment();
    payment.setTotalAmount(100);
    return payment;
  }

  private static Map<String, String> signedParams() {
    Map<String, String> params = new HashMap<>();
    params.put("vnp_TmnCode", "TESTCODE");
    params.put("vnp_TxnRef", "42_ref");
    params.put("vnp_Amount", "10000");
    params.put("vnp_ResponseCode", "00");
    params.put("vnp_TransactionStatus", "00");
    params.put("vnp_SecureHash", sign(params));
    return params;
  }

  private static String sign(Map<String, String> params) {
    try {
      StringBuilder data = new StringBuilder();
      for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
        if ("vnp_SecureHash".equals(entry.getKey())) continue;
        if (data.length() > 0) data.append('&');
        data.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
        data.append('=').append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
      }
      Mac mac = Mac.getInstance("HmacSHA512");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
      return HexFormat.of()
          .formatHex(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
