package com.kyro.order.messaging;

import com.kyro.enums.PaymentStatus;
import com.kyro.order.OrderService;
import com.kyro.order.config.RabbitMQConfig;
import com.kyro.order.event.PaymentStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentStatusEventListener {

  private static final Logger log = LoggerFactory.getLogger(PaymentStatusEventListener.class);

  private final OrderService orderService;

  public PaymentStatusEventListener(OrderService orderService) {
    this.orderService = orderService;
  }

  @RabbitListener(queues = RabbitMQConfig.PAYMENT_STATUS_QUEUE)
  public void handle(PaymentStatusChangedEvent event) {
    try {
      orderService.updatePaymentStatus(
          event.orderId(),
          toPaymentStatus(event.status()),
          event.version(),
          event.reviewRequired());
      log.info("Applied payment status {} to order {}", event.status(), event.orderId());
    } catch (IllegalArgumentException e) {
      log.error("Ignored invalid payment status {} for order {}", event.status(), event.orderId());
    }
  }

  static PaymentStatus toPaymentStatus(String status) {
    return PaymentStatus.valueOf(status);
  }
}
