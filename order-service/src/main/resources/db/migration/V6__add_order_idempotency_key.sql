-- V6: Bổ sung HTTP Idempotency-Key cho orders
ALTER TABLE orders ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_user_idempotency_key
    ON orders (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
