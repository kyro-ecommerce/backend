package com.kyro.catalog.messaging;

import com.kyro.catalog.StockReservationService;
import com.kyro.catalog.config.RabbitMQConfig;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedEventListener {
  private final StockReservationService stock;

  public OrderCreatedEventListener(StockReservationService stock) {
    this.stock = stock;
  }

  @RabbitListener(queues = RabbitMQConfig.CATALOG_ORDER_QUEUE)
  public void handleOrderCreated(Map<String, Object> event) {
    stock.reserve(
        Long.parseLong(event.get("orderId").toString()),
        (List<Map<String, Object>>) event.get("items"));
  }

  @RabbitListener(queues = "catalog-stock-release-queue")
  public void release(Map<String, Object> event) {
    stock.release(
        Long.parseLong(event.get("orderId").toString()),
        (List<Map<String, Object>>) event.get("items"));
  }
}
