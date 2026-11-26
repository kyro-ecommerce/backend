package com.kyro.catalog;

import static org.assertj.core.api.Assertions.*;

import com.kyro.reliability.Outbox;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@SpringBootTest(
    classes = StockReservationIntegrationTest.Config.class,
    properties = {
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
class StockReservationIntegrationTest {
  static final GenericContainer<?> PG =
      new GenericContainer<>("postgres:16-alpine")
          .withEnv("POSTGRES_PASSWORD", "test-only")
          .withExposedPorts(5432)
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

  static {
    PG.start();
  }

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres");
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "test-only");
  }

  @Configuration
  @EnableAutoConfiguration
  @EntityScan("com.kyro.catalog")
  @EnableJpaRepositories("com.kyro.catalog")
  @Import({StockReservationService.class, Outbox.class})
  static class Config {}

  @Autowired StockReservationService stock;
  @Autowired JdbcTemplate jdbc;

  List<Map<String, Object>> lines(int quantity) {
    return List.of(Map.of("variantId", 1L, "quantity", quantity));
  }

  int currentStock() {
    return jdbc.queryForObject("select stock from product_variant where id=1", Integer.class);
  }

  @BeforeEach
  void reset() {
    jdbc.execute("truncate processed_stock_reservation,outbox_events");
    jdbc.update(
        "update product_variant set stock=10,stock_version=0,active=true where id in (1,2)");
  }

  @Test
  void reserveAndReleaseAreIdempotentEvenWhenConcurrent() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> stock.reserve(1, lines(3)));
      var b = pool.submit(() -> stock.reserve(1, lines(3)));
      a.get();
      b.get();
      assertThat(currentStock()).isEqualTo(7);
      var c = pool.submit(() -> stock.release(1, lines(3)));
      var d = pool.submit(() -> stock.release(1, lines(3)));
      c.get();
      d.get();
      assertThat(currentStock()).isEqualTo(10);
      assertThat(
              jdbc.queryForObject(
                  "select stock_version from product_variant where id=1", Long.class))
          .isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "select stock_version from product_variant where id=2", Long.class))
          .isZero();
    }
  }

  @Test
  void cancelBeforeReserveNeverDeductsAndRejectionIsStable() {
    stock.release(2, lines(3));
    stock.reserve(2, lines(3));
    assertThat(currentStock()).isEqualTo(10);
    stock.reserve(3, lines(11));
    jdbc.update("update product_variant set stock=20 where id=1");
    stock.reserve(3, lines(11));
    assertThat(currentStock()).isEqualTo(20);
    assertThat(
            jdbc.queryForObject(
                "select status from processed_stock_reservation where order_id=3", String.class))
        .isEqualTo("REJECTED");
  }

  @Test
  void insufficientSecondLineDoesNotPartiallyReserveFirstLine() {
    stock.reserve(
        4,
        List.of(Map.of("variantId", 1L, "quantity", 2), Map.of("variantId", 2L, "quantity", 11)));
    assertThat(currentStock()).isEqualTo(10);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where routing_key='stock.changed'",
                Integer.class))
        .isZero();
  }

  @Test
  void outboxFailureRollsBackInventoryAndReservation() {
    jdbc.execute(
        "alter table outbox_events add constraint test_reject_outbox check (routing_key <>"
            + " 'stock.changed')");
    try {
      assertThatThrownBy(() -> stock.reserve(5, lines(2))).isInstanceOf(RuntimeException.class);
    } finally {
      jdbc.execute("alter table outbox_events drop constraint test_reject_outbox");
    }
    assertThat(currentStock()).isEqualTo(10);
    assertThat(
            jdbc.queryForObject("select count(*) from processed_stock_reservation", Integer.class))
        .isZero();
  }
}
