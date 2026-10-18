package com.kyro.catalog.messaging;

import com.kyro.catalog.ProductService;
import com.kyro.catalog.config.RabbitMQConfig;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedEventListener {

  private static final Logger log = LoggerFactory.getLogger(OrderCreatedEventListener.class);

  private final ProductService productService;
  private final RabbitTemplate rabbitTemplate;

  public OrderCreatedEventListener(ProductService productService, RabbitTemplate rabbitTemplate) {
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
      log.error("Poison pill detected: OrderCreatedEvent missing orderId: {}", event);
      throw new AmqpRejectAndDontRequeueException(
          "Invalid OrderCreatedEvent payload: orderId is null");
    }

    List<Map<String, Object>> items = (List<Map<String, Object>>) event.get("items");
    if (items == null || items.isEmpty()) {
      log.error("Poison pill detected: OrderCreatedEvent missing items for Order ID #{}", orderId);
      throw new AmqpRejectAndDontRequeueException(
          "Invalid OrderCreatedEvent payload: items is null or empty for order " + orderId);
    }

    try {
      productService.reserveStock(orderId, items);

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

    } catch (IllegalStateException | EntityNotFoundException | IllegalArgumentException e) {
      // Tier 1: Business failure (insufficient stock, inactive variant, variant not found)
      // Do not retry, do not send to DLQ; trigger Saga compensation
      log.warn(
          "Business failure while reserving stock for Order ID #{}: {}", orderId, e.getMessage());

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

    } catch (AmqpRejectAndDontRequeueException e) {
      throw e;
    } catch (Exception e) {
      // Tier 2: System / Transient failure (DB connection drop, transient lock error, etc.)
      // Rethrow to allow Spring AMQP retry policy (3 attempts) then DLQ routing
      log.error(
          "Transient system failure while reserving stock for Order ID #{}: {}",
          orderId,
          e.getMessage(),
          e);
      throw e;
    }
  }
}
