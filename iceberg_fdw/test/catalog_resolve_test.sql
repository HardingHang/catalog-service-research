-- iceberg_fdw Phase 2 验收：Catalog 解析接入 + OPTIONS 校验
-- 依据 doc 3 §10 Phase 2 验收清单。
-- 在全新库 icetest2 内执行（openGauss 不支持 DROP EXTENSION，见 BUILD.md）。
-- catalog 基座 DDL 由 sql/iceberg_catalog_base.sql 提供（docker 路径 /tmp/iceberg_fdw）。

DROP DATABASE IF EXISTS icetest2;
CREATE DATABASE icetest2;
\c icetest2

CREATE EXTENSION iceberg_fdw;
CREATE SERVER s FOREIGN DATA WRAPPER iceberg_fdw OPTIONS (catalog_kind 'pg_native');
CREATE FOREIGN TABLE ft(a int, b text) SERVER s
    OPTIONS (namespace 'sales', table_name 'orders');

-- 控制面状态底座（按 doc 3 §4/§7 由控制面提供，这里手工装配）
\i /tmp/iceberg_fdw/sql/iceberg_catalog_base.sql

-- 手工写一行表身份（模拟未来控制面产出；openGauss 无 gen_random_uuid，用字面 UUID）
INSERT INTO iceberg_catalog.namespaces(namespace) VALUES ('sales');
INSERT INTO iceberg_catalog.tables(namespace, table_name, table_uuid, metadata_location, current_schema_id)
  VALUES ('sales', 'orders', '11111111-1111-1111-1111-111111111111'::uuid,
          's3://bucket/sales/orders/metadata/v3.metadata.json', 0);

\echo === 1. 规划期解析到 metadata_location（EXPLAIN VERBOSE 可见）===
EXPLAIN (VERBOSE, COSTS OFF) SELECT * FROM ft;

\echo === 2. 非法 OPTION（期望报 invalid option "nope"）===
CREATE FOREIGN TABLE bad(a int) SERVER s OPTIONS (nope 'x');

\echo === 3. 缺必需 OPTION（期望报 requires options namespace and table_name）===
CREATE FOREIGN TABLE bad2(a int) SERVER s OPTIONS (namespace 'sales');

\echo === 4. 指向不存在的表（期望规划期报 not found in catalog，而非崩溃）===
CREATE FOREIGN TABLE ft_missing(a int) SERVER s
    OPTIONS (namespace 'sales', table_name 'no_such_table');
EXPLAIN (COSTS OFF) SELECT * FROM ft_missing;

\c postgres
DROP DATABASE icetest2;
