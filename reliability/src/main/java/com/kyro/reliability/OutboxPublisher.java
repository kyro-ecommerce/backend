package com.kyro.reliability;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One worker per service deployment. At-least-once: consumers must be idempotent. */
@Component
@EnableScheduling
public class OutboxPublisher {
  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  private final JdbcTemplate jdbc;
  private final RabbitTemplate rabbit;
  private final int batchSize;
  private final AtomicBoolean running = new AtomicBoolean();

  public OutboxPublisher(JdbcTemplate jdbc, ConnectionFactory connectionFactory) {
    this(jdbc, connectionFactory, 25);
  }

  @Autowired
  public OutboxPublisher(
      JdbcTemplate jdbc,
      ConnectionFactory connectionFactory,
      @Value("${outbox.batch-size:25}") int batchSize) {
    this.jdbc = jdbc;
    this.batchSize = Math.max(1, batchSize);
    if (connectionFactory instanceof CachingConnectionFactory factory) {
      factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
      factory.setPublisherReturns(true);
    }
    this.rabbit = new RabbitTemplate(connectionFactory);
    this.rabbit.setMandatory(true);
  }

  @Scheduled(fixedDelayString = "${outbox.delay-ms:1000}")
  public void publishDue() {
    if (!running.compareAndSet(false, true)) return;
    try {
      var rows =
          jdbc.queryForList(
              "select * from outbox_events where status='PENDING' and next_attempt_at <= now()"
                  + " order by id limit ?",
              batchSize);
      for (var row : rows) {
        long id = ((Number) row.get("id")).longValue();
        try {
          MessageProperties properties = new MessageProperties();
          properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
          properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
          properties.setMessageId(row.get("event_id").toString());
          CorrelationData correlation = new CorrelationData(properties.getMessageId());
          rabbit.send(
              (String) row.get("exchange_name"),
              (String) row.get("routing_key"),
              new Message(
                  ((String) row.get("payload")).getBytes(StandardCharsets.UTF_8), properties),
              correlation);
          var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
          if (!confirm.isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException("Broker rejected or returned event");
          }
          jdbc.update(
              "update outbox_events set status='SENT',sent_at=now(),last_error=null where id=?",
              id);
        } catch (Exception error) {
          if (error instanceof InterruptedException) Thread.currentThread().interrupt();
          int attempts = ((Number) row.get("attempt_count")).intValue() + 1;
          String detail = error.getClass().getSimpleName() + ": " + error.getMessage();
          jdbc.update(
              "update outbox_events set status=?,attempt_count=?,next_attempt_at=now() + (? *"
                  + " interval '1 second'),last_error=? where id=?",
              attempts >= 10 ? "FAILED" : "PENDING",
              attempts,
              Math.min(300, 1 << Math.min(attempts, 8)),
              detail.substring(0, Math.min(detail.length(), 1000)),
              id);
          log.warn(
              "Outbox event {} delivery failed (attempt {})", row.get("event_id"), attempts, error);
        }
      }
    } finally {
      running.set(false);
    }
  }
}
