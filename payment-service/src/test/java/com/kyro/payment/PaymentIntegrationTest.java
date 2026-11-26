package com.kyro.payment;

import static org.assertj.core.api.Assertions.*;

import com.kyro.payment.client.OrderClient;
import com.kyro.reliability.Outbox;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@SpringBootTest(
    classes = PaymentIntegrationTest.Config.class,
    properties = {
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "spring.jpa.hibernate.ddl-auto=validate",
      "vnpay.tmnCode=TESTCODE",
      "vnpay.hashSecret=test-secret",
      "vnpay.url=https://sandbox.test/pay",
      "vnpay.returnUrl=https://shop.test/return"
    })
class PaymentIntegrationTest {
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
  static void database(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres");
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "test-only");
  }

  @Configuration
  @EnableAutoConfiguration
  @EntityScan("com.kyro.payment")
  @EnableJpaRepositories("com.kyro.payment")
  @Import({PaymentService.class, Outbox.class})
  static class Config {
    @Bean
    OrderClient orders() {
      return id ->
          new OrderClient.OrderResponse(
              id,
              "KYRO-TEST",
              1L,
              100L,
              "PENDING",
              "VNPAY",
              id == 88 ? "CANCELLED" : "PENDING",
              Instant.now().plusSeconds(900));
    }
  }

  @Autowired PaymentService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired PaymentRepository payments;

  String reference(String url) {
    return Arrays.stream(url.split("[?&]"))
        .filter(s -> s.startsWith("vnp_TxnRef="))
        .findFirst()
        .orElseThrow()
        .substring(11);
  }

  Map<String, String> callback(String reference, boolean success, String gateway) throws Exception {
    var p = new TreeMap<String, String>();
    p.put("vnp_TmnCode", "TESTCODE");
    p.put("vnp_TxnRef", reference);
    p.put("vnp_Amount", "10000");
    p.put("vnp_ResponseCode", success ? "00" : "24");
    p.put("vnp_TransactionStatus", success ? "00" : "02");
    p.put("vnp_TransactionNo", gateway);
    var mac = Mac.getInstance("HmacSHA512");
    mac.init(new SecretKeySpec("test-secret".getBytes(), "HmacSHA512"));
    String data =
        p.entrySet().stream()
            .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .reduce((a, b) -> a + "&" + b)
            .orElseThrow();
    p.put("vnp_SecureHash", HexFormat.of().formatHex(mac.doFinal(data.getBytes())));
    return p;
  }

  @Test
  void oldLinkSuccessDuplicateChargeAndLateFailure() throws Exception {
    String a = reference(service.createPayment(42L, "first"));
    String b = reference(service.createPayment(42L, "second"));
    assertThat(a).isNotEqualTo(b);
    assertThat(reference(service.createPayment(42L, "second"))).isEqualTo(b);
    service.processPaymentCallback(callback(a, false, "0"));
    assertThat(payments.findByOrderId(42L).orElseThrow().getPaymentStatus().name())
        .isEqualTo("PENDING");
    service.processPaymentCallback(callback(a, true, "charge-A"));
    service.processPaymentCallback(callback(b, true, "charge-B"));
    service.processPaymentCallback(callback(a, false, "0"));
    var aggregate = payments.findByOrderId(42L).orElseThrow();
    assertThat(aggregate.getPaymentStatus().name()).isEqualTo("COMPLETED");
    assertThat(aggregate.isReviewRequired()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payment_transactions where payment_id=? and"
                    + " transaction_status='SUCCESS'",
                Integer.class,
                aggregate.getId()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where payload like '%COMPLETED%' and"
                    + " aggregate_id='42'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id='42' and"
                    + " payload::jsonb->>'reviewRequired'='true'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentFirstAttemptWithSameKeyCreatesOneUrl() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<String> create =
          () -> {
            gate.await();
            return service.createPayment(77L, "same-intent");
          };
      var a = workers.submit(create);
      var b = workers.submit(create);
      gate.countDown();
      assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
      assertThat(service.transactions(77L)).hasSize(1);
    }
  }

  @Test
  void upgradesV1HistoryWithoutLosingOldReference() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres",
            "postgres",
            "test-only");
    Flyway.configure().dataSource(ds).schemas("legacy").target("1").load().migrate();
    jdbc.update(
        "insert into legacy.payment_details(order_id,total_amount,transaction_id,payment_status)"
            + " values (90,100,'old-reference','COMPLETED')");
    Flyway.configure().dataSource(ds).schemas("legacy").load().migrate();
    assertThat(
            jdbc.queryForObject(
                "select transaction_reference from legacy.payment_transactions", String.class))
        .isEqualTo("old-reference");
    assertThat(
            jdbc.queryForObject("select winning_transaction_id from legacy.payments", Long.class))
        .isNotNull();
  }
}
