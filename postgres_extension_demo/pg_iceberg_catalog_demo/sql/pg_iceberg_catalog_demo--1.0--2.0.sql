-- pg_iceberg_catalog_demo--1.0--2.0.sql
-- Upgrade to version 2.0: Replace ALL functions with C implementation

-- ============================================
-- Replace ALL PL/pgSQL functions with C versions
-- ============================================

-- Namespace functions

DROP FUNCTION IF EXISTS iceberg_catalog.create_namespace(text, jsonb);
CREATE FUNCTION iceberg_catalog.create_namespace(
    p_namespace TEXT,
    p_properties JSONB DEFAULT '{}'::jsonb
)
RETURNS TABLE (
    namespace TEXT,
    properties JSONB
)
AS 'pg_iceberg_catalog_demo', 'iceberg_create_namespace'
LANGUAGE C;
COMMENT ON FUNCTION iceberg_catalog.create_namespace(text, jsonb) IS
'C implementation of namespace creation';

DROP FUNCTION IF EXISTS iceberg_catalog.drop_namespace(text);
CREATE FUNCTION iceberg_catalog.drop_namespace(
    p_namespace TEXT
)
RETURNS BOOLEAN
AS 'pg_iceberg_catalog_demo', 'iceberg_drop_namespace'
LANGUAGE C STRICT;
COMMENT ON FUNCTION iceberg_catalog.drop_namespace(text) IS
'C implementation of namespace drop (must be empty)';

DROP FUNCTION IF EXISTS iceberg_catalog.list_namespaces();
CREATE FUNCTION iceberg_catalog.list_namespaces()
RETURNS TABLE (
    namespace TEXT,
    properties JSONB
)
AS 'pg_iceberg_catalog_demo', 'iceberg_list_namespaces'
LANGUAGE C;
COMMENT ON FUNCTION iceberg_catalog.list_namespaces() IS
'C implementation of namespace listing';

-- Table core functions (already in C from v1.1)

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

-- Table operations

DROP FUNCTION IF EXISTS iceberg_catalog.list_tables(text);
CREATE FUNCTION iceberg_catalog.list_tables(
    p_namespace TEXT
)
RETURNS TABLE (
    namespace TEXT,
    table_name TEXT,
    table_uuid UUID,
    metadata_location TEXT
)
AS 'pg_iceberg_catalog_demo', 'iceberg_list_tables'
LANGUAGE C STRICT;
COMMENT ON FUNCTION iceberg_catalog.list_tables(text) IS
'C implementation of table listing in a namespace';

DROP FUNCTION IF EXISTS iceberg_catalog.register_table(text, text, text, jsonb);
CREATE FUNCTION iceberg_catalog.register_table(
    p_namespace TEXT,
    p_table_name TEXT,
    p_metadata_location TEXT,
    p_metadata_json JSONB DEFAULT '{}'::jsonb
)
RETURNS TABLE (
    namespace TEXT,
    table_name TEXT,
    table_uuid UUID,
    metadata_location TEXT,
    metadata_json JSONB
)
AS 'pg_iceberg_catalog_demo', 'iceberg_register_table'
LANGUAGE C;
COMMENT ON FUNCTION iceberg_catalog.register_table(text, text, text, jsonb) IS
'C implementation of registering an existing Iceberg table';

DROP FUNCTION IF EXISTS iceberg_catalog.drop_table(text, text, boolean);
CREATE FUNCTION iceberg_catalog.drop_table(
    p_namespace TEXT,
    p_table_name TEXT,
    p_purge BOOLEAN DEFAULT false
)
RETURNS BOOLEAN
AS 'pg_iceberg_catalog_demo', 'iceberg_drop_table'
LANGUAGE C;
COMMENT ON FUNCTION iceberg_catalog.drop_table(text, text, boolean) IS
'C implementation of table drop with optional purge queue';

DROP FUNCTION IF EXISTS iceberg_catalog.unregister_table(text, text);
CREATE FUNCTION iceberg_catalog.unregister_table(
    p_namespace TEXT,
    p_table_name TEXT
)
RETURNS BOOLEAN
AS 'pg_iceberg_catalog_demo', 'iceberg_unregister_table'
LANGUAGE C STRICT;
COMMENT ON FUNCTION iceberg_catalog.unregister_table(text, text) IS
'C implementation of unregistering a table (no purge)';

DROP FUNCTION IF EXISTS iceberg_catalog.rename_table(text, text, text, text);
CREATE FUNCTION iceberg_catalog.rename_table(
    p_old_namespace TEXT,
    p_old_table_name TEXT,
    p_new_namespace TEXT,
    p_new_table_name TEXT
)
RETURNS BOOLEAN
AS 'pg_iceberg_catalog_demo', 'iceberg_rename_table'
LANGUAGE C STRICT;
COMMENT ON FUNCTION iceberg_catalog.rename_table(text, text, text, text) IS
'C implementation of table rename';

-- alter_table stays as wrapper (calls commit_table)
DROP FUNCTION IF EXISTS iceberg_catalog.alter_table(text, text, text, text, jsonb, jsonb);
CREATE OR REPLACE FUNCTION iceberg_catalog.alter_table(
    p_namespace TEXT,
    p_table_name TEXT,
    p_expected_metadata_location TEXT,
    p_new_metadata_location TEXT,
    p_updates_json JSONB DEFAULT '{}'::jsonb,
    p_new_metadata_json JSONB DEFAULT NULL
)
RETURNS TABLE (
    namespace TEXT,
    table_name TEXT,
    table_uuid UUID,
    metadata_location TEXT,
    metadata_json JSONB
)
LANGUAGE sql
AS $$
    SELECT * FROM iceberg_catalog.commit_table(
        p_namespace,
        p_table_name,
        p_expected_metadata_location,
        p_new_metadata_location,
        COALESCE(p_new_metadata_json, p_updates_json)
    );
$$;
COMMENT ON FUNCTION iceberg_catalog.alter_table(text, text, text, text, jsonb, jsonb) IS
'SQL wrapper for commit_table (table metadata updates)';