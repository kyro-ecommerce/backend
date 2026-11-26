package com.kyro.payment.dto;

import com.kyro.enums.PaymentMethod;
import com.kyro.enums.PaymentStatus;
import com.kyro.payment.Payment;
import java.time.LocalDateTime;

public record PaymentDTO(
    Long id,
    Long orderId,
    PaymentMethod paymentMethod,
    PaymentStatus paymentStatus,
    long totalAmount,
    LocalDateTime paymentDate,
    String transactionId,
    Long winningTransactionId,
    boolean reviewRequired,
    String reviewReason,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {
  public PaymentDTO(Payment p, String reference) {
    this(
        p.getId(),
        p.getOrderId(),
        p.getPaymentMethod(),
        p.getPaymentStatus(),
        p.getTotalAmount(),
        p.getPaymentDate(),
        reference,
        p.getWinningTransactionId(),
        p.isReviewRequired(),
        p.getReviewReason(),
        p.getCreatedAt(),
        p.getUpdatedAt());
  }
}
