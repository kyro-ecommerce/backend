-- 1. Bổ sung updated_at cho bảng product
ALTER TABLE product ADD COLUMN IF NOT EXISTS updated_at timestamp(6) DEFAULT CURRENT_TIMESTAMP;
UPDATE product SET updated_at = created_at WHERE updated_at IS NULL;

-- 2. Tạo bảng Idempotency cho Stock Reservation
CREATE TABLE processed_stock_reservation (
  order_id bigint PRIMARY KEY,
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
