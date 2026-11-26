package com.kyro.order.dto;

import com.kyro.order.Order;

/** Detailed Order DTO decoupled from auth domain packages. */
public class OrderDetailDTO extends OrderDTO {
  private Long userId;
  private boolean paymentReviewRequired;

  public boolean isPaymentReviewRequired() {
    return paymentReviewRequired;
  }

  public OrderDetailDTO() {}

  public OrderDetailDTO(Order order) {
    super(order);
    this.userId = order.getUserId();
    this.paymentReviewRequired = order.isPaymentReviewRequired();
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }
}
