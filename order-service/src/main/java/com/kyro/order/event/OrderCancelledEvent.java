package com.kyro.order.event;

import java.util.List;

/**
 * Published on {@code order.cancelled} once an order has moved to CANCELLED. Deliberately carries
 * no email or other personal data: consumers only need to know which purchase to retract.
 */
public record OrderCancelledEvent(Long orderId, Long userId, List<Item> items) {
  public record Item(Long productId, int quantity) {}
}
