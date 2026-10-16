\set ON_ERROR_STOP on

\connect kyro_auth
BEGIN;
DELETE FROM address WHERE user_id BETWEEN 100000 AND 139999;
DELETE FROM users WHERE id BETWEEN 100000 AND 139999;
INSERT INTO users (id, active, is_banned, created_at, email, first_name, last_name, password, phone, role_id)
SELECT id, true, false, now(), 'perf-' || id || '@kyro.test', 'Perf', id::text,
       '$2a$10$XImHvDQ3vUx2nibLZQaWQOuI9GxOPMhTL1RUio9ppxlVGf6TLkxea', '0900000000', 2
FROM generate_series(100000, 139999) id;
INSERT INTO address (id, district, full_name, note, phone_number, province, street, ward, user_id)
SELECT id, 'Benchmark District', 'Performance User ' || id, '', '0900000000',
       'Benchmark Province', '1 Load Test Street', 'Benchmark Ward', id
FROM generate_series(100000, 139999) id;
COMMIT;

\connect kyro_cart
BEGIN;
DELETE FROM processed_cart_events WHERE order_id >= 999999;
DELETE FROM carts WHERE user_id BETWEEN 100000 AND 139999;
INSERT INTO carts (id, user_id, version, created_at, updated_at)
SELECT id, id, 0, now(), now() FROM generate_series(100000, 139999) id;
INSERT INTO cart_items
  (id, cart_id, product_id, variant_id, product_name, sku, variant_name, quantity, price, sale_price, discount_percent, created_at, updated_at)
SELECT id, id,
       1 + ((id - 100000) % 10),
       (ARRAY[1,4,6,8,10,12,14,16,18,20])[1 + ((id - 100000) % 10)],
       (ARRAY['iPhone 15 Pro Max','Xiaomi 13 Pro','OPPO Find X5 Pro','Laptop Acer Nitro 5 Eagle','Laptop ASUS ROG Strix SCAR 17','Laptop Dell XPS 13','Laptop HP Spectre x360 14','Sạc dự phòng Anker PowerCore III Elite','Sạc dự phòng Xiaomi Mi Power Bank 3','Tai nghe Sony WH-1000XM5'])[1 + ((id - 100000) % 10)],
       (ARRAY['KYRO-P1-V1','KYRO-P2-V4','KYRO-P3-V6','KYRO-P4-V8','KYRO-P5-V10','KYRO-P6-V12','KYRO-P7-V14','KYRO-P8-V16','KYRO-P9-V18','KYRO-P10-V20'])[1 + ((id - 100000) % 10)],
       (ARRAY['256GB','256GB','256GB','i5/8GB/512GB SSD/RTX 3050','Ryzen 9/32GB/2TB SSD/RTX 4090','i5/8GB/512GB SSD','i7/16GB/1TB SSD','25600mAh','20000mAh','Một cỡ'])[1 + ((id - 100000) % 10)],
       1,
       (ARRAY[34990000,22990000,20990000,21990000,69990000,32990000,3799000,1599000,699000,8990000])[1 + ((id - 100000) % 10)],
       (ARRAY[32890600,20001300,18051400,18911400,60191400,30020900,3495080,1295190,496290,7461700])[1 + ((id - 100000) % 10)],
       (ARRAY[6,13,14,14,14,9,8,19,29,17])[1 + ((id - 100000) % 10)],
       now(), now()
FROM generate_series(100000, 139999) id;
COMMIT;

\connect kyro_order
BEGIN;
CREATE TEMP TABLE perf_orders AS
SELECT id, order_address_id FROM orders WHERE user_id BETWEEN 100000 AND 139999 OR id >= 999999;
DELETE FROM order_item WHERE order_id IN (SELECT id FROM perf_orders);
DELETE FROM orders WHERE id IN (SELECT id FROM perf_orders);
DELETE FROM order_address WHERE id IN (SELECT order_address_id FROM perf_orders WHERE order_address_id IS NOT NULL);
INSERT INTO orders
  (id, order_code, order_date, order_status, original_price, discount, payment_method, payment_status, total_discounted_price, total_items, user_id, user_email)
SELECT 9000000 + i, 'PERF' || lpad(i::text, 12, '0'), now(), 'PENDING', 100, 0, 'VNPAY', 'PENDING', 100, 1,
       100000 + i, 'perf-' || (100000 + i) || '@kyro.test'
FROM generate_series(0, 39999) i;
SELECT setval(pg_get_serial_sequence('orders', 'id'), 999999, false);
COMMIT;

\connect kyro_payment
BEGIN;
DELETE FROM payment_details WHERE order_id >= 999999;
INSERT INTO payment_details
  (id, order_id, payment_method, payment_status, transaction_id, total_amount, created_at, updated_at)
SELECT 9000000 + i, 9000000 + i, 'VNPAY', 'PENDING', 'perf-' || (9000000 + i), 100, now(), now()
FROM generate_series(0, 39999) i;
SELECT setval(pg_get_serial_sequence('payment_details', 'id'), 999999, false);
COMMIT;

\connect kyro_catalog
BEGIN;
UPDATE product_variant SET stock = 100000 WHERE id IN (1,4,6,8,10,12,14,16,18,20);
COMMIT;
