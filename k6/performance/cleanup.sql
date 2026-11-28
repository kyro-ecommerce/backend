-- Only for disposable performance databases. Never reuse fixture IDs against live outboxes/queues.
\set ON_ERROR_STOP on

\connect kyro_auth
DELETE FROM address WHERE user_id BETWEEN 100000 AND 149999;
DELETE FROM users WHERE id BETWEEN 100000 AND 149999;

\connect kyro_cart
DELETE FROM processed_cart_events WHERE order_id >= 999999;
DELETE FROM carts WHERE user_id BETWEEN 100000 AND 149999;

\connect kyro_order
BEGIN;
CREATE TEMP TABLE perf_orders AS
SELECT id, order_address_id FROM orders WHERE user_id BETWEEN 100000 AND 149999 OR id >= 999999;
DELETE FROM outbox_events
WHERE aggregate_id ~ '^[0-9]+$'
  AND (aggregate_id::bigint >= 999999 OR aggregate_id::bigint IN (SELECT id FROM perf_orders));
DELETE FROM order_item WHERE order_id IN (SELECT id FROM perf_orders);
DELETE FROM orders WHERE id IN (SELECT id FROM perf_orders);
DELETE FROM order_address WHERE id IN (SELECT order_address_id FROM perf_orders WHERE order_address_id IS NOT NULL);
SELECT setval(pg_get_serial_sequence('orders', 'id'), greatest(coalesce((SELECT max(id) FROM orders), 1), 1));
COMMIT;

\connect kyro_payment
BEGIN;
CREATE INDEX IF NOT EXISTS idx_perf_payments_winning_tx ON payments(winning_transaction_id);
DELETE FROM outbox_events WHERE aggregate_id ~ '^[0-9]+$' AND aggregate_id::bigint >= 999999;
UPDATE payments SET winning_transaction_id=NULL WHERE order_id >= 999999 AND winning_transaction_id IS NOT NULL;
DELETE FROM payment_transactions WHERE payment_id IN (SELECT id FROM payments WHERE order_id >= 999999);
DELETE FROM payment_order_cancellations WHERE order_id >= 999999;
DELETE FROM payments WHERE order_id >= 999999;
DROP INDEX IF EXISTS idx_perf_payments_winning_tx;
SELECT setval(pg_get_serial_sequence('payments', 'id'), greatest(coalesce((SELECT max(id) FROM payments), 1), 1));
COMMIT;

\connect kyro_catalog
BEGIN;
DELETE FROM processed_stock_reservation WHERE order_id >= 999999;
DELETE FROM outbox_events
WHERE (routing_key IN ('stock.reserved','stock.failed') AND aggregate_id ~ '^[0-9]+$' AND aggregate_id::bigint >= 999999)
   OR (routing_key = 'stock.changed' AND aggregate_id IN ('1','4','6','8','10','12','14','16','18','20'));
UPDATE product_variant SET stock = CASE id
  WHEN 1 THEN 25 WHEN 4 THEN 29 WHEN 6 THEN 19 WHEN 8 THEN 15 WHEN 10 THEN 8
  WHEN 12 THEN 20 WHEN 14 THEN 9 WHEN 16 THEN 50 WHEN 18 THEN 59 WHEN 20 THEN 28 END
WHERE id IN (1,4,6,8,10,12,14,16,18,20);
UPDATE product SET quantity_sold = CASE id
  WHEN 1 THEN 25 WHEN 2 THEN 18 WHEN 3 THEN 12 WHEN 4 THEN 16 WHEN 5 THEN 7
  WHEN 6 THEN 11 WHEN 7 THEN 13 WHEN 8 THEN 9 WHEN 9 THEN 15 WHEN 10 THEN 22 END
WHERE id BETWEEN 1 AND 10;
COMMIT;
