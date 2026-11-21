package com.kyro.order.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.kyro.order.config.RabbitMQConfig;
import com.kyro.order.event.OrderCancelledEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class OrderCancelledEventPublisherTest {

  private static final OrderCancelledEvent EVENT =
      new OrderCancelledEvent(42L, 10L, List.of(new OrderCancelledEvent.Item(3L, 2)));

  @Test
  void publishesToOrderExchangeWithCancelledRoutingKey() {
    List<Object[]> sent = new ArrayList<>();
    RabbitTemplate template =
        new RabbitTemplate() {
          @Override
          public void convertAndSend(String exchange, String routingKey, Object message) {
            sent.add(new Object[] {exchange, routingKey, message});
          }
        };

    new OrderCancelledEventPublisher(template).publish(EVENT);

    assertEquals(1, sent.size());
    assertEquals(RabbitMQConfig.ORDER_EXCHANGE, sent.getFirst()[0]);
    assertEquals("order.cancelled", sent.getFirst()[1]);
    assertSame(EVENT, sent.getFirst()[2]);
  }

  @Test
  void brokerFailureIsLoggedNotPropagated() {
    RabbitTemplate template =
        new RabbitTemplate() {
          @Override
          public void convertAndSend(String exchange, String routingKey, Object message) {
            throw new AmqpException("broker down");
          }
        };

    assertDoesNotThrow(() -> new OrderCancelledEventPublisher(template).publish(EVENT));
  }
}
