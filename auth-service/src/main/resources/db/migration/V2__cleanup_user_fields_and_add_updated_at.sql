-- Dọn dẹp các field rác/thừa không dùng trong mô hình B2C
ALTER TABLE users
  DROP COLUMN IF EXISTS shop_name,
  DROP COLUMN IF EXISTS shop_description,
  DROP COLUMN IF EXISTS business_type,
  DROP COLUMN IF EXISTS website;

-- Bổ sung updated_at cho bảng users
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS updated_at timestamp(6) DEFAULT CURRENT_TIMESTAMP;

UPDATE users
SET updated_at = COALESCE(created_at, CURRENT_TIMESTAMP)
WHERE updated_at IS NULL;
