package com.kyro.order.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kyro.enums.PaymentMethod;
import com.kyro.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

class StockResultEventListenerTest {

  @Test
  void confirmsCodOrCompletedPaymentOnly() {
    assertTrue(StockResultEventListener.canConfirm(PaymentMethod.COD, PaymentStatus.PENDING));
    assertFalse(StockResultEventListener.canConfirm(PaymentMethod.VNPAY, PaymentStatus.PENDING));
    assertTrue(StockResultEventListener.canConfirm(PaymentMethod.VNPAY, PaymentStatus.COMPLETED));
  }
}
