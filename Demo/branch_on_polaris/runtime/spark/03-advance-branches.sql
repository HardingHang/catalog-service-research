-- Advance different physical Iceberg table branches independently.
-- dev_a changes only orders; dev_b changes only customers.
INSERT INTO sales.orders.branch_dev_a VALUES
  (3, 10, 220.00, DATE '2026-05-20');

INSERT INTO sales.customers.branch_dev_b VALUES
  (30, 'Carol', 'growth');

SELECT * FROM sales.orders ORDER BY order_id;
SELECT * FROM sales.orders VERSION AS OF 'dev_a' ORDER BY order_id;
SELECT * FROM sales.orders VERSION AS OF 'dev_b' ORDER BY order_id;

SELECT * FROM sales.customers ORDER BY customer_id;
SELECT * FROM sales.customers VERSION AS OF 'dev_a' ORDER BY customer_id;
SELECT * FROM sales.customers VERSION AS OF 'dev_b' ORDER BY customer_id;
