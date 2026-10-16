package com.kyro.catalog.messaging;

import com.kyro.catalog.ProductService;
import com.kyro.catalog.config.RabbitMQConfig;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {

  private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

  private final ProductService productService;
  private final RabbitTemplate rabbitTemplate;

  public OrderEventListener(ProductService productService, RabbitTemplate rabbitTemplate) {
    this.productService = productService;
    this.rabbitTemplate = rabbitTemplate;
  }

  @RabbitListener(queues = RabbitMQConfig.CATALOG_ORDER_QUEUE)
  public void handleOrderCreated(Map<String, Object> event) {
    Long orderId =
        event.get("orderId") != null ? Long.valueOf(event.get("orderId").toString()) : null;
    Long userId = event.get("userId") != null ? Long.valueOf(event.get("userId").toString()) : null;
    log.debug("Received OrderCreatedEvent for Order ID #{}, User ID #{}", orderId, userId);

    if (orderId == null) {
      return;
    }

    try {
      List<Map<String, Object>> items = (List<Map<String, Object>>) event.get("items");
      if (items != null) {
        productService.reserveStock(orderId, items);
      }

      // Publish stock.reserved success event
      Map<String, Object> successEvent =
          Map.of(
              "orderId",
              orderId,
              "userId",
              userId != null ? userId : 0L,
              "items",
              items,
              "success",
              true,
              "message",
              "Stock successfully reserved");
      rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, "stock.reserved", successEvent);
      log.debug("Published stock.reserved event for Order ID #{}", orderId);

    } catch (Exception e) {
      log.error("Failed to decrease stock for Order ID #{}: {}", orderId, e.getMessage(), e);

      // Publish stock.failed compensation event
      Map<String, Object> failedEvent =
          Map.of(
              "orderId",
              orderId,
              "userId",
              userId != null ? userId : 0L,
              "success",
              false,
              "message",
              e.getMessage() != null ? e.getMessage() : "Insufficient stock");
      rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, "stock.failed", failedEvent);
      log.debug("Published stock.failed event for Order ID #{}", orderId);
    }
  }
}
