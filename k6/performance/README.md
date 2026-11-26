# k6 Performance & Resilience Scenarios

This directory contains reproducible [k6](https://k6.io/) load scenarios targeting inter-service communication paths in the Kyro backend:

| Scenario | Task Command | Target Path & Metrics |
| --- | --- | --- |
| Synchronous OpenFeign | `task perf:feign` | Gateway → Order → Auth/Cart/Catalog (`synchronous_path_latency` p95, `technical_success_rate`, `accepted_requests`) |
| Asynchronous RabbitMQ | `task perf:rabbitmq` | Signed VNPay callback → Payment outbox → RabbitMQ → Order projection (`rabbitmq_propagation_latency` p95, queue drain) |
| Failed Payment Handling | `task perf:payment-failure` | Rejected VNPay callback propagation ensuring both Payment and Order payment status transition to `FAILED` |

Scenarios use isolated SQL fixtures (`fixture.sql` / `cleanup.sql`) and locally signed JWTs (`JWT_SECRET`). Checkout tests place `VNPAY` orders in `PENDING` state so inventory reservation is exercised without triggering external SMTP traffic.

> [!CAUTION]
> Run these scripts **only against disposable local/test databases**. Never execute `fixture.sql` or `cleanup.sql` against environments with live outboxes, active queues, or real order data.

## Usage

```bash
task run
task perf:feign
task perf:rabbitmq
task perf:reset
```

Override target arrival rate, duration, or measured run count via environment variables:

```bash
PERF_RATE=50 PERF_DURATION=60s task perf:feign
PERF_RUNS=3 PERF_RATE=50 PERF_DURATION=60s task perf:rabbitmq
```

Raw outputs are written under `k6/results/` (gitignored). Allow JVM warm-up, Eureka registration, and connection pools to stabilize after a fresh `docker compose` boot before recording steady-state measurements.
