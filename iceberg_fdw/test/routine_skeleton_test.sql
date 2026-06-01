-- iceberg_fdw Phase 1 验收：SELECT 外表走通 routine（mock 数据）
-- 依据 doc 3 §10 Phase 1 验收清单。N = ICEBERG_MOCK_ROWS = 3。
-- 已在 openGauss 5.0.0 实跑通过（见 routine_skeleton_test.out）。
--
-- 注意：openGauss 5.0.0 不支持 DROP EXTENSION（报 "EXTENSION is not yet
-- supported"），故本测试在一个全新数据库 icetest 内执行以保证干净状态，
-- 结束后整库 DROP，不依赖 DROP EXTENSION。

DROP DATABASE IF EXISTS icetest;
CREATE DATABASE icetest;
\c icetest

CREATE EXTENSION iceberg_fdw;

CREATE SERVER s FOREIGN DATA WRAPPER iceberg_fdw OPTIONS (catalog_kind 'pg_native');

CREATE FOREIGN TABLE ft(a int, b text) SERVER s
    OPTIONS (namespace 'sales', table_name 'orders');

\echo === 1. 行式 count（期望 = 3）===
SELECT count(*) FROM ft;

\echo === 1b. 行式取列（期望 3 行，a 非空 int，b 非空 text）===
SELECT a, b FROM ft ORDER BY a;

\echo === 2. EXPLAIN（期望含 Foreign Scan 节点）===
EXPLAIN (COSTS OFF) SELECT * FROM ft;

\echo === 3. ReScan：相关子查询（期望 3 行，每行 3）===
SELECT (SELECT count(*) FROM ft) FROM generate_series(1, 3);

\echo === 4. 非法 OPTION（期望报 invalid option "nope"）===
CREATE FOREIGN TABLE bad(a int) SERVER s OPTIONS (nope 'x');

\echo === 5. 向量执行器（期望同样返回 3）===
SET try_vector_engine_strategy = 'force';
SELECT count(*) FROM ft;
SELECT a, b FROM ft ORDER BY a;
RESET try_vector_engine_strategy;

\c postgres
DROP DATABASE icetest;
