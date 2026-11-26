CREATE TABLE payment_order_cancellations (
 order_id BIGINT PRIMARY KEY,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
