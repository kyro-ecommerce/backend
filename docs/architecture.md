# Architecture & Distributed Workflow Guide

This document describes the inter-service communication boundaries, transactional messaging guarantees, checkout/inventory state machines, multi-attempt payment reconciliation, and operational runbooks implemented in the Kyro backend.

---

## 1. Edge Security & Internal Trust Boundary

- **API Gateway (`api-gateway`)** is the single public entry point (`:8080`). `AuthenticationFilter` validates access-token claims (`id`, `roles`, `jti`, `exp`), rejects refresh tokens on API routes, strips any client-supplied `X-User-*` headers, and injects verified `X-User-Id`, `X-User-Email`, `X-User-Roles`, and `X-Internal-Token` headers downstream.
- **Internal Boundary (`InternalTokenFilter`)** runs inside downstream business services (`order-service`, `payment-service`, `catalog-service`, `cart-service`, `auth-service`). Any request targeting `/api/v1/internal/**`, `/api/v1/orders/**`, `/api/v1/admin/**`, or carrying `X-User-Id` / `X-User-Roles` headers without a matching `X-Internal-Token` is rejected with `401 Unauthorized`. `order-service` does not publish a host port in Docker Compose.
- **AI Route Bridging (`AiIdentityFilter`)** forwards verified user identity to the external AI Recommendation Service while blocking external browser mutations against internal AI ingestion routes.

---

## 2. Service Communication Boundaries

Synchronous HTTP (OpenFeign) is restricted to read/validation queries required before accepting a request, executed **outside** database write transactions to avoid connection-pool starvation. Cross-service state transitions use asynchronous RabbitMQ events backed by transactional outboxes.

### Synchronous OpenFeign Clients

| Caller | Target Service | Endpoint(s) | Purpose |
| --- | --- | --- | --- |
| `cart-service` | `catalog-service` | `GET /api/v1/internal/products/{id}`<br/>`POST /api/v1/internal/products/lookup` | Validate active SKU, stock, and current pricing for cart items |
| `order-service` | `cart-service` | `POST /api/v1/internal/carts/{userId}/selection` | Snapshot selected cart items, cart version, and server-calculated totals prior to order TX |
| `order-service` | `auth-service` | `GET /api/v1/internal/users/{userId}/addresses/{addressId}` | Verify shipping address ownership and snapshot recipient details prior to order TX |
| `payment-service` | `order-service` | `GET /api/v1/internal/orders/{id}` | Verify order ownership, payable amount, expiration, and current status before link creation or reconciliation |
| `catalog-service` | `order-service` | `GET /api/v1/internal/orders/has-purchased`<br/>`GET /api/v1/internal/orders/top-selling`<br/>`GET /api/v1/internal/orders/product-revenue` | Verify delivered purchases for product reviews and aggregate admin catalog metrics |
| `catalog-service` | `auth-service` | `GET /api/v1/internal/users/{userId}` | Resolve reviewer display metadata |

### Asynchronous RabbitMQ Topology

Core exchanges, durable queues, and dead-letter bindings (`kyro.dlx`) are declared centrally by `IntegrationTopology` in the shared `reliability` module so queues exist before producers emit traffic.

| Exchange | Routing Key | Queue(s) | Consumer & Responsibility |
| --- | --- | --- | --- |
| `order-exchange` | `order.created` | `catalog-order-created-queue`<br/>`ai.integration.events.v2` | `catalog-service` reserves variant stock; AI service tracks order state |
| `order-exchange` | `stock.reserved`<br/>`stock.failed` | `order-stock-result-queue` | `order-service` confirms COD orders, awaits VNPay completion, or cancels failed reservations |
| `order-exchange` | `stock.release` | `catalog-stock-release-queue` | `catalog-service` idempotently restores reserved inventory on cancellation/expiration |
| `order-exchange` | `order.confirmed` | `cart-order-confirmed-queue` | `cart-service` removes purchased cart quantities once an order is confirmed |
| `order-exchange` | `order.cancelled` | `payment-order-cancelled-queue`<br/>`ai.integration.events.v2` | `payment-service` records cancellation tombstones for late-payment review; AI service retracts unfulfilled signals |
| `order-exchange` | `order.delivered` | `catalog-order-delivered-queue`<br/>`ai.integration.events.v2` | `catalog-service` increments `quantity_sold`; AI service records verified `PURCHASE` interactions |
| `payment-exchange` | `payment.status.updated` | `order-payment-status-queue` | `order-service` projects payment status/version and triggers VNPay order confirmation |
| `product.events` | `product.created`<br/>`product.updated`<br/>`product.deleted`<br/>`stock.changed` | `ai.integration.events.v2` | AI service updates product embeddings/metadata snapshots and per-variant stock versions |
| `notification-exchange` | `auth.otp`<br/>`order.confirmed.email` | Notification queues | `notification-service` sends OTP and order confirmation emails (best-effort `AFTER_COMMIT`) |

---

## 3. Transactional Outbox & Delivery Semantics

`order-service`, `catalog-service`, and `payment-service` persist domain mutations and `outbox_events` rows atomically within the same PostgreSQL transaction (`Outbox.append`, `Propagation.MANDATORY`).

- **Envelope Metadata**: Every outbox payload is enriched with a durable `eventId`, monotonic sequence `version`, and `occurredAt` timestamp.
- **Confirmed Publishing**: `OutboxPublisher` polls due `PENDING` rows outside business transactions and publishes persistent JSON messages using `CORRELATED` publisher confirms and `mandatory` returns. A row transitions to `SENT` only after broker ACK without an unroutable return.
- **Retry & Dead-Lettering**: Failed publishes back off exponentially (up to 300s) and transition to `FAILED` after 10 attempts while retaining the record for inspection. Consumer queues route rejected/exhausted messages to `<queue>.dlq` via `kyro.dlx`.
- **Single-Worker Assumption**: Each service runs a single `OutboxPublisher` worker without distributed row leases; delivery is **at-least-once**, and all downstream consumers enforce idempotency.

---

## 4. Checkout, Idempotency & Stock Reservation Saga

1. **Idempotent Order Placement**: `POST /api/v1/orders` accepts `Idempotency-Key` and stores a SHA-256 `request_fingerprint` alongside `(user_id, idempotency_key)`. Retrying the same key and payload returns the existing order; reusing a key with a different payload returns `409 Conflict`.
2. **Deterministic Stock Reservation (`StockReservationService`)**:
   - Variant rows are locked in ascending `variant_id` order (`TreeMap`) to prevent multi-item deadlocks.
   - `processed_stock_reservation` tracks per-order state (`RESERVED`, `REJECTED`, `RELEASED`) and reserved line quantities.
   - **Out-of-Order Cancellation Safety**: If `stock.release` arrives before `order.created`, Catalog writes a `RELEASED` tombstone so a delayed `order.created` event cannot deduct inventory. Repeated `stock.release` deliveries for the same `orderId` restore stock at most once.
   - Removing a variant from a product soft-disables it (`active = false`) rather than deleting the row, preserving referential integrity for historical orders and pending stock releases.
3. **Order Confirmation & Cart Cleanup**:
   - **COD**: Confirmed immediately when `stock.reserved` is processed, emitting `order.confirmed` to clear purchased cart items.
   - **VNPay**: Remains `PENDING` (retaining cart items if the customer abandons the payment tab) until both `stock.reserved` and `PaymentStatus.COMPLETED` are satisfied, then transitions to `CONFIRMED` and emits `order.confirmed`.
   - Cancellation is rejected once an order reaches `SHIPPED` or `DELIVERED`.

---

## 5. Multi-Attempt VNPay Payments & Reconciliation

Payment persistence separates aggregate order state from individual gateway attempts:

- **`payments`**: One row per `order_id` (`UNIQUE`), tracking `total_amount`, aggregate `payment_status`, `winning_transaction_id`, and reconciliation flags (`review_required`, `review_reason`).
- **`payment_transactions`**: One row per generated checkout link (`Idempotency-Key` scoped per payment), storing unique `transaction_reference` (`vnp_TxnRef`), `gateway_transaction_no` (partial unique index on valid provider numbers), response codes, and timestamps.

### Reconciliation Invariants
- Creating a retry payment link (`POST /api/v1/orders/{orderId}/payments`) never overwrites or invalidates earlier `payment_transactions` references; late callbacks on an older link still resolve cleanly.
- Both browser callbacks (`GET /api/v1/payment-providers/vnpay/callback`) and server-to-server IPNs (`GET /api/v1/payment-providers/vnpay/ipn`) share signature, merchant, and amount validation.
- A `COMPLETED` payment is never downgraded by a delayed failure from another attempt.
- **Anomaly Flagging (Manual Reconciliation)**:
  - **Multiple Successful Charges**: Both transactions are recorded as `COMPLETED`, the order is confirmed only once, and `review_required = true` (`MULTIPLE_SUCCESSFUL_CHARGES`) is propagated to both Payment and Order (`paymentReviewRequired`).
  - **Payment After Cancellation**: If a valid payment arrives after an order was cancelled/expired, the order remains `CANCELLED` (inventory is not re-deducted), financial truth is recorded as `COMPLETED`, and `review_required = true` (`SUCCESS_AFTER_ORDER_CANCELLED`) is raised for manual refund handling. Cancelled VNPay orders cannot be hard-deleted.
  - Administrators inspect flagged payments via `GET /api/v1/admin/payments/reviews` and `GET /api/v1/admin/payments/reviews/{id}`.

---

## 6. Catalog & Order Projections for AI Service

- **Product Snapshots & Stock Deltas**: Catalog emits full `ProductDTO` snapshots on `product.created` / `product.updated`, tombstones on `product.deleted`, and lightweight per-variant `(variantId, stock, stockVersion)` deltas on `stock.changed`. `ProductVariant.stockVersion` increments independently per SKU so inventory reservations do not contend on the parent `Product` row.
- **Verified Purchase Attribution**: `order.delivered` carries `orderId`, `userId`, `occurredAt`, `version`, and line items. The AI service serializes projections per aggregate in PostgreSQL and records `PURCHASE` interactions exclusively on `order.delivered`.

---

## 7. Operational Runbook

### Inspecting Outbox Health

Run against `kyro_order`, `kyro_catalog`, or `kyro_payment`:

```sql
SELECT status, COUNT(*) FROM outbox_events GROUP BY status;

SELECT id, event_id, routing_key, attempt_count, last_error, created_at
FROM outbox_events
WHERE status = 'FAILED'
ORDER BY id;
```

### Replaying Failed Outbox Events

After resolving the underlying broker or topology issue, reset specific `FAILED` rows to `PENDING` while preserving the original `event_id` (never mint a new `event_id` or truncate consumer idempotency tables):

```sql
UPDATE outbox_events
SET status = 'PENDING',
    attempt_count = 0,
    next_attempt_at = now(),
    last_error = NULL
WHERE event_id = '<verified-event-id>'
  AND status = 'FAILED';
```
