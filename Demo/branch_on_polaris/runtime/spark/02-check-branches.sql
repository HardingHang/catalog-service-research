-- Read main and both table-level branches. This script assumes dev_a/dev_b
-- already exist, either through the P0 workflow or controller-generated SQL.
SELECT * FROM sales.orders ORDER BY order_id;
SELECT * FROM sales.customers ORDER BY customer_id;

SELECT * FROM sales.orders VERSION AS OF 'dev_a' ORDER BY order_id;
SELECT * FROM sales.customers VERSION AS OF 'dev_a' ORDER BY customer_id;

SELECT * FROM sales.orders VERSION AS OF 'dev_b' ORDER BY order_id;
SELECT * FROM sales.customers VERSION AS OF 'dev_b' ORDER BY customer_id;
