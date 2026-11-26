package com.kyro.order.messaging;

import com.kyro.order.config.RabbitMQConfig;
import com.kyro.order.event.OrderDeliveredEvent;
import com.kyro.reliability.Outbox;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrderDeliveredEventPublisher {
  private final Outbox outbox;

  public OrderDeliveredEventPublisher(Outbox outbox) {
    this.outbox = outbox;
  }

  @EventListener
  public void publish(OrderDeliveredEvent event) {
    outbox.append(
        RabbitMQConfig.ORDER_EXCHANGE,
        RabbitMQConfig.ORDER_DELIVERED_ROUTING_KEY,
        event.orderId(),
        event);
  }
}
