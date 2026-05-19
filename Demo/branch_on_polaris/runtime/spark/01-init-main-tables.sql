-- Reset the demo namespace to a known main-branch state.
-- Branch creation is intentionally not in this file; the controller generates
-- CREATE BRANCH SQL after it records the base snapshot IDs.
CREATE NAMESPACE IF NOT EXISTS sales;

DROP TABLE IF EXISTS sales.orders;
DROP TABLE IF EXISTS sales.customers;

CREATE TABLE sales.orders (
  order_id BIGINT,
  customer_id BIGINT,
  amount DECIMAL(10, 2),
  order_date DATE
) USING iceberg;

CREATE TABLE sales.customers (
  customer_id BIGINT,
  name STRING,
  segment STRING
) USING iceberg;

INSERT INTO sales.orders VALUES
  (1, 10, 120.50, DATE '2026-05-19'),
  (2, 20, 80.00, DATE '2026-05-19');

INSERT INTO sales.customers VALUES
  (10, 'Alice', 'enterprise'),
  (20, 'Bob', 'starter');

SHOW TABLES IN sales;
