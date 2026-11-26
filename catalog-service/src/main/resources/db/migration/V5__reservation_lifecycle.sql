ALTER TABLE product_variant ADD COLUMN stock_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE processed_stock_reservation ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'RESERVED';
ALTER TABLE processed_stock_reservation ADD COLUMN items TEXT;
ALTER TABLE processed_stock_reservation ADD CONSTRAINT stock_reservation_status CHECK (status IN ('RESERVED','REJECTED','RELEASED'));

ALTER TABLE product ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0;
