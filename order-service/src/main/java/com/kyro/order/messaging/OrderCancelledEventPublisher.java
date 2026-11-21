package com.kyro.order.messaging;

import com.kyro.order.config.RabbitMQConfig;
import com.kyro.order.event.OrderCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrderCancelledEventPublisher {
  private static final Logger log = LoggerFactory.getLogger(OrderCancelledEventPublisher.class);
  private final RabbitTemplate rabbitTemplate;

  public OrderCancelledEventPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void publish(OrderCancelledEvent event) {
    try {
      rabbitTemplate.convertAndSend(
          RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.ORDER_CANCELLED_ROUTING_KEY, event);
    } catch (RuntimeException exception) {
      log.error("Failed to publish OrderCancelledEvent for order {}", event.orderId(), exception);
    }
  }
}
