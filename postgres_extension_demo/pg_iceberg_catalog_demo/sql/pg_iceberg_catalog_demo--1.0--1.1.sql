-- pg_iceberg_catalog_demo--1.0--1.1.sql
-- Upgrade to version 1.1: Replace core functions with C implementation

-- ============================================
-- Drop PL/pgSQL versions and create C versions
-- ============================================

-- commit_table: C implementation (CAS commit)
DROP FUNCTION IF EXISTS iceberg_catalog.commit_table(text, text, text, text, jsonb);

CREATE FUNCTION iceberg_catalog.commit_table(
    p_namespace TEXT,
    p_table_name TEXT,
    p_expected_metadata_location TEXT,
    p_new_metadata_location TEXT,
    p_new_metadata_json JSONB DEFAULT NULL
)
RETURNS TABLE (
    namespace TEXT,
    table_name TEXT,
    table_uuid UUID,
    metadata_location TEXT,
    metadata_json JSONB
)
AS 'pg_iceberg_catalog_demo', 'iceberg_commit_table'
LANGUAGE C STRICT;

COMMENT ON FUNCTION iceberg_catalog.commit_table(text, text, text, text, jsonb) IS
'C implementation of CAS commit for Iceberg table metadata_location';

-- create_table: C implementation
DROP FUNCTION IF EXISTS iceberg_catalog.create_table(text, text, jsonb, jsonb, jsonb, text);

CREATE FUNCTION iceberg_catalog.create_table(
    p_namespace TEXT,
    p_table_name TEXT,
    p_schema_json JSONB,
    p_partition_spec JSONB DEFAULT NULL,
    p_properties JSONB DEFAULT '{}'::jsonb,
    p_location TEXT DEFAULT NULL
)
RETURNS TABLE (
    namespace TEXT,
    table_name TEXT,
    table_uuid UUID,
    metadata_location TEXT,
    metadata_json JSONB
)
AS 'pg_iceberg_catalog_demo', 'iceberg_create_table'
LANGUAGE C;

COMMENT ON FUNCTION iceberg_catalog.create_table(text, text, jsonb, jsonb, jsonb, text) IS
'C implementation of Iceberg table creation';

-- load_table: C implementation
DROP FUNCTION IF EXISTS iceberg_catalog.load_table(text, text);

CREATE FUNCTION iceberg_catalog.load_table(
    p_namespace TEXT,
    p_table_name TEXT
)
RETURNS TABLE (
    namespace TEXT,
    table_name TEXT,
    table_uuid UUID,
    metadata_location TEXT,
    metadata_json JSONB
)
AS 'pg_iceberg_catalog_demo', 'iceberg_load_table'
LANGUAGE C STRICT;

COMMENT ON FUNCTION iceberg_catalog.load_table(text, text) IS
'C implementation of Iceberg table loading';