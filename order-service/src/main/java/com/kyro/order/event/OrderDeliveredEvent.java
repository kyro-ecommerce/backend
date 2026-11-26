package com.kyro.order.event;

import java.util.List;

public record OrderDeliveredEvent(Long orderId, Long userId, List<Item> items) {
  public record Item(Long productId, int quantity) {}
}
