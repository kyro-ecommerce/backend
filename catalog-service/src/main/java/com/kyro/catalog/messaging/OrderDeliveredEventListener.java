package com.kyro.catalog.messaging;

import com.kyro.catalog.config.RabbitMQConfig;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderDeliveredEventListener {
  private final JdbcTemplate jdbc;

  public OrderDeliveredEventListener(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  @RabbitListener(queues = RabbitMQConfig.CATALOG_ORDER_DELIVERED_QUEUE)
  public void handle(Map<String, Object> event) {
    Long orderId = Long.valueOf(event.get("orderId").toString());
    if (jdbc.update(
            "INSERT INTO processed_order_delivery(order_id) VALUES (?) ON CONFLICT DO NOTHING",
            orderId)
        == 0) return;
    for (Map<String, Object> item :
        (List<Map<String, Object>>) event.getOrDefault("items", List.of())) {
      jdbc.update(
          "UPDATE product SET quantity_sold = quantity_sold + ?, lifecycle_revision ="
              + " lifecycle_revision + 1 WHERE id = ?",
          Integer.valueOf(item.get("quantity").toString()),
          Long.valueOf(item.get("productId").toString()));
    }
  }
}
