-- 1. Chuẩn hóa tên cột khóa ngoại từ order_address -> order_address_id
ALTER TABLE orders RENAME COLUMN order_address TO order_address_id;

-- 2. Bổ sung created_at và updated_at cho orders
ALTER TABLE orders ADD COLUMN IF NOT EXISTS created_at timestamp(6) DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS updated_at timestamp(6) DEFAULT CURRENT_TIMESTAMP;

UPDATE orders
SET created_at = COALESCE(order_date, CURRENT_TIMESTAMP),
    updated_at = COALESCE(order_date, CURRENT_TIMESTAMP)
WHERE created_at IS NULL OR updated_at IS NULL;
