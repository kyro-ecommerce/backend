package com.kyro.cart.messaging;

import com.kyro.cart.config.RabbitMQConfig;
import com.kyro.cart.service.CartService;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class StockReservedEventListener {

  private static final Logger log = LoggerFactory.getLogger(StockReservedEventListener.class);

  private final CartService cartService;

  public StockReservedEventListener(CartService cartService) {
    this.cartService = cartService;
  }

  @RabbitListener(queues = RabbitMQConfig.CART_STOCK_RESERVED_QUEUE)
  public void handleStockReserved(Map<String, Object> event) {
    Long userId = event.get("userId") != null ? Long.valueOf(event.get("userId").toString()) : null;
    Long orderId =
        event.get("orderId") != null ? Long.valueOf(event.get("orderId").toString()) : null;

    if (userId != null) {
      Map<Long, Integer> quantities = extractQuantities(event.get("items"));
      if (orderId == null || quantities.isEmpty()) return;
      cartService.removePurchasedItems(userId, orderId, quantities);
      log.debug(
          "Removed purchased cart items for User ID #{} following stock reservation for Order ID"
              + " #{}",
          userId,
          orderId);
    }
  }

  static Map<Long, Integer> extractQuantities(Object rawItems) {
    if (!(rawItems instanceof java.util.List<?> items)) return Map.of();
    return items.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .filter(item -> item.get("cartItemId") != null && item.get("quantity") != null)
        .collect(
            Collectors.toMap(
                item -> Long.valueOf(item.get("cartItemId").toString()),
                item -> Integer.valueOf(item.get("quantity").toString())));
  }
}
