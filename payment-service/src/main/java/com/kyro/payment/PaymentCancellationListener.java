package com.kyro.payment;

import java.util.Map;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentCancellationListener {
  private final JdbcTemplate jdbc;

  public PaymentCancellationListener(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  @RabbitListener(queues = "payment-order-cancelled-queue")
  public void cancelled(Map<String, Object> event) {
    long orderId = Long.parseLong(event.get("orderId").toString());
    jdbc.update(
        "insert into payment_order_cancellations(order_id) values (?) on conflict do nothing",
        orderId);
    jdbc.update(
        "update payments set"
            + " review_required=true,review_reason=coalesce(review_reason,'SUCCESS_AFTER_ORDER_CANCELLED')"
            + " where order_id=? and payment_status='COMPLETED'",
        orderId);
  }
}
