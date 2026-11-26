ALTER TABLE order_item ADD COLUMN cart_item_id BIGINT;
ALTER TABLE orders ADD COLUMN payment_event_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN payment_review_required BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN request_fingerprint VARCHAR(64);
