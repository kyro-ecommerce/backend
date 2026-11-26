package com.kyro.reliability;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class OutboxIntegrationTest {
  @Test
  void rollbackReturnRecoveryAndStableRedelivery() throws Exception {
    try (var postgres =
            new GenericContainer<>("postgres:16-alpine")
                .withEnv("POSTGRES_PASSWORD", "isolated-test")
                .withExposedPorts(5432)
                .waitingFor(
                    Wait.forLogMessage(
                        ".*database system is ready to accept connections.*\\n", 2));
        var broker =
            new GenericContainer<>("rabbitmq:3.13-alpine")
                .withExposedPorts(5672)
                .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1))
                .withStartupTimeout(Duration.ofMinutes(2))) {
      postgres.start();
      broker.start();
      var ds =
          new DriverManagerDataSource(
              "jdbc:postgresql://"
                  + postgres.getHost()
                  + ":"
                  + postgres.getMappedPort(5432)
                  + "/postgres",
              "postgres",
              "isolated-test");
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute(
          new ClassPathResource("outbox-schema.sql").getContentAsString(StandardCharsets.UTF_8));
      var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
      var outbox = new Outbox(jdbc, new ObjectMapper());
      assertThatThrownBy(
              () ->
                  tx.execute(
                      status -> {
                        outbox.append("order-exchange", "order.cancelled", 1, Map.of("orderId", 1));
                        throw new IllegalStateException("business rollback");
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isZero();
      var cf = new CachingConnectionFactory(broker.getHost(), broker.getMappedPort(5672));
      try {
        var publisher = new OutboxPublisher(jdbc, cf);
        var admin = new RabbitAdmin(cf);
        var exchange = new TopicExchange("order-exchange");
        admin.declareExchange(exchange);
        tx.execute(
            status -> outbox.append("order-exchange", "order.cancelled", 1, Map.of("orderId", 1)));
        publisher.publishDue();
        assertThat(jdbc.queryForObject("select status from outbox_events", String.class))
            .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select attempt_count from outbox_events", Integer.class))
            .isEqualTo(1);
        var queue = new Queue("test.order.cancelled");
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("order.cancelled"));
        jdbc.update("update outbox_events set next_attempt_at=now()");
        publisher.publishDue();
        assertThat(jdbc.queryForObject("select status from outbox_events", String.class))
            .isEqualTo("SENT");
        var rabbit = new RabbitTemplate(cf);
        var first = rabbit.receive(queue.getName(), 5000);
        assertThat(first).isNotNull();
        // Simulate crash after broker confirm, before the SENT update.
        jdbc.update("update outbox_events set status='PENDING',next_attempt_at=now()");
        publisher.publishDue();
        var duplicate = rabbit.receive(queue.getName(), 5000);
        assertThat(duplicate).isNotNull();
        assertThat(duplicate.getMessageProperties().getMessageId())
            .isEqualTo(first.getMessageProperties().getMessageId());
        assertThat(duplicate.getBody()).isEqualTo(first.getBody());
      } finally {
        cf.destroy();
      }
    }
  }
}
