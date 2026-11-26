package com.kyro.order.messaging;

import com.kyro.order.config.RabbitMQConfig;
import com.kyro.order.event.CartConfirmedEvent;
import com.kyro.order.event.OrderCancelledEvent;
import com.kyro.order.event.StockReleaseEvent;
import com.kyro.reliability.Outbox;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCancelledEventPublisher {
  private final Outbox outbox;

  public OrderCancelledEventPublisher(Outbox outbox) {
    this.outbox = outbox;
  }

  @EventListener
  public void publish(OrderCancelledEvent event) {
    outbox.append(
        RabbitMQConfig.ORDER_EXCHANGE,
        RabbitMQConfig.ORDER_CANCELLED_ROUTING_KEY,
        event.orderId(),
        event);
  }

  @EventListener
  public void release(StockReleaseEvent event) {
    outbox.append("order-exchange", "stock.release", event.orderId(), event);
  }

  @EventListener
  public void confirmed(CartConfirmedEvent event) {
    outbox.append("order-exchange", "order.confirmed", event.orderId(), event);
  }
}
