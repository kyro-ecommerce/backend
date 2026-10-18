package com.kyro.order.messaging;

import com.kyro.enums.PaymentMethod;
import com.kyro.enums.PaymentStatus;
import com.kyro.order.OrderService;
import com.kyro.order.config.RabbitMQConfig;
import com.kyro.order.event.StockResultEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class StockResultEventListener {

  private static final Logger log = LoggerFactory.getLogger(StockResultEventListener.class);

  private final OrderService orderService;

  public StockResultEventListener(OrderService orderService) {
    this.orderService = orderService;
  }

  @RabbitListener(queues = RabbitMQConfig.ORDER_STOCK_RESULT_QUEUE)
  public void handleStockResult(StockResultEvent event) {
    log.debug(
        "Received StockResultEvent for Order ID {}: success={}", event.orderId(), event.success());
    orderService.handleStockResult(event);
  }

  static boolean canConfirm(PaymentMethod paymentMethod, PaymentStatus paymentStatus) {
    return paymentMethod == PaymentMethod.COD || paymentStatus == PaymentStatus.COMPLETED;
  }
}
