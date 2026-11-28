# k6 Performance & Resilience Scenarios

This directory contains reproducible [k6](https://k6.io/) load scenarios targeting inter-service communication paths, transactional outboxes, and Saga consistency in the Kyro backend:

| Scenario | Task Command | Target Path & Metrics |
| --- | --- | --- |
| Synchronous OpenFeign | `task perf:feign` | Gateway → Order → Auth & Cart → Catalog (`synchronous_path_latency` p95 ≤ 500 ms, `technical_success_rate` ≥ 99%, `dropped_iterations` = 0, outbox/queue drain < 5s) |
| Synchronous Stress Ramp | `task perf:feign:ramp` | Stepped `ramping-arrival-rate` (`50 → 350 RPS`) locating knee & breaking points (`feign-ramp-stages.csv`) |
| Asynchronous RabbitMQ | `task perf:rabbitmq` | Signed VNPay callback → Payment Feign (`Order`) → `outbox_events` → RabbitMQ → Order projection (`rabbitmq_propagation_latency` p95 ≤ 2000 ms, queue & outbox drain < 5s) |
| RabbitMQ Stress Ramp | `task perf:rabbitmq:ramp` | Stepped `ramping-arrival-rate` (`25 → 200 RPS`, override `STAGES` up to `400 RPS`) locating outbox/broker breaking point |
| Failed Payment Handling | `task perf:payment-failure` | Rejected VNPay callback (`vnp_ResponseCode=24`) propagation ensuring both Payment and Order payment status transition to `FAILED` |
| End-to-End COD Saga & Idempotency | `task perf:e2e-saga` | `COD` order with `Idempotency-Key` (10% replay check) → `order.created` → Catalog `stock.reserved` → Order `CONFIRMED` → `order.confirmed` → Cart cleanup (`processed_cart_events` + Redis eviction) |

Scenarios use isolated SQL fixtures (`fixture.sql` / `cleanup.sql`, user IDs `100000..149999`, payment fixture orders `9000000..9049999`) and locally signed access JWTs (`JWT_SECRET`, including `jti` required by `AuthenticationFilter`).

> [!CAUTION]
> Run these scripts **only against disposable local/test databases**. Never execute `fixture.sql` or `cleanup.sql` against environments with live outboxes, active queues, or real order data.

## Performance Stack Override (`compose.perf.yml`)

Default `OutboxPublisher` settings (`outbox.delay-ms=1000`, `outbox.batch-size=25`) cap single-service outbox throughput at ≤ 25 events/s, and default `TRACING_SAMPLING_PROBABILITY=1.0` sends 100% of spans to Zipkin. Use `compose.perf.yml` when running benchmarks so outbox polling scales (`OUTBOX_DELAY_MS=100`, `OUTBOX_BATCH_SIZE=200`), Zipkin uses production-style sampling (`TRACING_SAMPLING_PROBABILITY=0.1`), and `notification-service` skips live SMTP (`APP_MAIL_ENABLED=false`) during `COD` / `order.confirmed` tests:

```bash
docker compose -f compose.yml -f compose.perf.yml up -d --build --wait
```

## 3-Step Methodology & Usage

1. **Warm-up**: 20s at 25–50 RPS (JIT C2 compilation, HikariCP pools, Redis/RabbitMQ channels).
2. **Stress Ramp**: Open-model `ramping-arrival-rate` to locate knee and breaking points:
   ```bash
   task perf:feign:ramp
   task perf:rabbitmq:ramp
   ```
3. **3× Steady-State Verification**: 60s × 3 independent `constant-arrival-rate` runs with full SQL reset (`fixture.sql`), outbox (`PENDING=0, FAILED=0`), and DLQ (`*.dlq=0`) gates:
   ```bash
   PERF_WARMUP_RATE=50 PERF_WARMUP_DURATION=20s PERF_RATE=120 PERF_DURATION=60s task perf:feign
   PERF_WARMUP_RATE=50 PERF_WARMUP_DURATION=20s PERF_RATE=200 PERF_DURATION=60s task perf:rabbitmq
   PERF_WARMUP_RATE=25 PERF_WARMUP_DURATION=20s PERF_RATE=50 PERF_DURATION=60s task perf:e2e-saga
   PERF_RATE=25 PERF_DURATION=30s task perf:payment-failure
   task perf:reset
   ```

Raw outputs are written under `k6/results/` (gitignored).
