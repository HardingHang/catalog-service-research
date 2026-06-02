-- pg_iceberg_catalog_demo--1.0.sql
-- PostgreSQL Iceberg Catalog Demo Extension

-- ============================================
-- 5.2 Schema
-- ============================================
CREATE SCHEMA IF NOT EXISTS iceberg_catalog;

-- ============================================
-- 5.3 Namespace 表
-- ============================================
CREATE TABLE iceberg_catalog.namespaces (
    namespace       TEXT PRIMARY KEY,
    properties      JSONB DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

-- ============================================
-- 5.4 Table 元信息表
-- ============================================
CREATE TABLE iceberg_catalog.tables (
    id                          BIGSERIAL PRIMARY KEY,
    catalog_name                TEXT NOT NULL DEFAULT 'default',
    namespace                   TEXT NOT NULL,
    table_name                  TEXT NOT NULL,
    table_uuid                  UUID NOT NULL DEFAULT gen_random_uuid(),
    metadata_location           TEXT NOT NULL,
    previous_metadata_location  TEXT,
    table_location              TEXT,
    iceberg_type                VARCHAR(5) NOT NULL DEFAULT 'TABLE',
    properties                  JSONB DEFAULT '{}'::jsonb,
    metadata_json               JSONB DEFAULT '{}'::jsonb,
    created_at                  TIMESTAMPTZ DEFAULT now(),
    updated_at                  TIMESTAMPTZ DEFAULT now(),

    UNIQUE (catalog_name, namespace, table_name),
    UNIQUE (table_uuid),
    FOREIGN KEY (namespace)
        REFERENCES iceberg_catalog.namespaces(namespace)
        ON DELETE CASCADE
);

-- ============================================
-- 5.5 Purge 队列表
-- ============================================
CREATE TABLE iceberg_catalog.purge_queue (
    id                  BIGSERIAL PRIMARY KEY,
    namespace           TEXT NOT NULL,
    table_name          TEXT NOT NULL,
    metadata_location   TEXT NOT NULL,
    enqueued_at         TIMESTAMPTZ DEFAULT now(),
    processed_at        TIMESTAMPTZ
);

-- ============================================
-- 6.1 iceberg_tables 视图
-- ============================================
CREATE OR REPLACE VIEW iceberg_tables AS
SELECT
    catalog_name,
    namespace AS table_namespace,
    table_name,
    metadata_location,
    previous_metadata_location,
    iceberg_type
FROM iceberg_catalog.tables;

-- ============================================
-- 6.2 iceberg_namespace_properties 视图
-- ============================================
CREATE OR REPLACE VIEW iceberg_namespace_properties AS
SELECT
    'default'::TEXT AS catalog_name,
    namespace,
    key AS property_key,
    value AS property_value
FROM iceberg_catalog.namespaces,
     LATERAL jsonb_each_text(properties) AS props(key, value);

-- ============================================
-- 8.1 create_namespace
-- ============================================
CREATE OR REPLACE FUNCTION iceberg_catalog.create_namespace(
    p_namespace TEXT,
    p_properties JSONB DEFAULT '{}'::jsonb
)
RETURNS TABLE (
    namespace TEXT,
    properties JSONB
)
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO iceberg_catalog.namespaces(namespace, properties)
    VALUES (p_namespace, COALESCE(p_properties, '{}'::jsonb));

    RETURN QUERY
    SELECT n.namespace, n.properties
    FROM iceberg_catalog.namespaces n
    WHERE n.namespace = p_namespace;
EXCEPTION WHEN unique_violation THEN
    RAISE EXCEPTION 'Namespace already exists: %', p_namespace
        USING ERRCODE = 'unique_violation';
END;
$$;

-- ============================================
-- 8.2 drop_namespace
-- ============================================
CREATE OR REPLACE FUNCTION iceberg_catalog.drop_namespace(
    p_namespace TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
DECLARE
    v_table_count INT;
BEGIN
    SELECT count(*)
    INTO v_table_count
    FROM iceberg_catalog.tables
    WHERE namespace = p_namespace;

    IF v_table_count > 0 THEN
        RAISE EXCEPTION 'Namespace % is not empty', p_namespace;
    END IF;

    DELETE FROM iceberg_catalog.namespaces
    WHERE namespace = p_namespace;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Namespace not found: %', p_namespace;
    END IF;

    RETURN TRUE;
END;
$$;

-- ============================================
-- 8.3 list_namespaces
-- ============================================
CREATE OR REPLACE FUNCTION iceberg_catalog.list_namespaces()
RETURNS TABLE (
    namespace TEXT,
    properties JSONB
)
LANGUAGE sql
AS $$
    SELECT namespace, properties
    FROM iceberg_catalog.namespaces
    ORDER BY namespace;
$$;

-- ============================================
-- 8.4 create_table
-- ============================================
CREATE OR REPLACE FUNCTION iceberg_catalog.create_table(
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
LANGUAGE plpgsql
AS $$
DECLARE
    v_uuid UUID := gen_random_uuid();
    v_location TEXT;
    v_metadata_location TEXT;
    v_metadata_json JSONB;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM iceberg_catalog.namespaces n
        WHERE n.namespace = p_namespace
    ) THEN
        RAISE EXCEPTION 'Namespace not found: %', p_namespace;
    END IF;

    v_location := COALESCE(
        p_location,
        format('s3://demo-bucket/%s/%s', p_namespace, p_table_name)
    );

    v_metadata_location := format(
        '%s/metadata/00000-%s.metadata.json',
        v_location,
        replace(v_uuid::text, '-', '')
    );

    v_metadata_json := jsonb_build_object(
        'format-version', 2,
        'table-uuid', v_uuid::text,
        'location', v_location,
        'last-sequence-number', 0,
        'last-updated-ms', floor(extract(epoch from clock_timestamp()) * 1000)::bigint,
        'schemas', jsonb_build_array(p_schema_json),
        'current-schema-id', 0,
        'partition-specs',
            CASE
                WHEN p_partition_spec IS NULL THEN '[]'::jsonb
                ELSE jsonb_build_array(p_partition_spec)
            END,
        'default-spec-id', 0,
        'properties', COALESCE(p_properties, '{}'::jsonb),
        'snapshots', '[]'::jsonb,
        'snapshot-log', '[]'::jsonb,
        'metadata-log', '[]'::jsonb,
        'refs', '{}'::jsonb
    );

    INSERT INTO iceberg_catalog.tables(
        catalog_name,
        namespace,
        table_name,
        table_uuid,
        metadata_location,
        previous_metadata_location,
        table_location,
        iceberg_type,
        properties,
        metadata_json
    )
    VALUES (
        'default',
        p_namespace,
        p_table_name,
        v_uuid,
        v_metadata_location,
        NULL,
        v_location,
        'TABLE',
        COALESCE(p_properties, '{}'::jsonb),
        v_metadata_json
    );

    RETURN QUERY
    SELECT
        t.namespace,
        t.table_name,
        t.table_uuid,
        t.metadata_location,
        t.metadata_json
    FROM iceberg_catalog.tables t
    WHERE t.namespace = p_namespace
      AND t.table_name = p_table_name;

EXCEPTION WHEN unique_violation THEN
    RAISE EXCEPTION 'Table already exists: %.%', p_namespace, p_table_name
        USING ERRCODE = 'unique_violation';
END;
$$;

-- ============================================
-- 8.5 register_table
-- ============================================
CREATE OR REPLACE FUNCTION iceberg_catalog.register_table(
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
LANGUAGE plpgsql
AS $$
DECLARE
    v_uuid UUID;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM iceberg_catalog.namespaces n
        WHERE n.namespace = p_namespace
    ) THEN
        RAISE EXCEPTION 'Namespace not found: %', p_namespace;
    END IF;

    v_uuid := COALESCE(
        NULLIF(p_metadata_json->>'table-uuid', '')::uuid,
        gen_random_uuid()
    );

    INSERT INTO iceberg_catalog.tables(
        catalog_name,
        namespace,
        table_name,
        table_uuid,
        metadata_location,
        previous_metadata_location,
        table_location,
        iceberg_type,
        properties,
        metadata_json
    )
    VALUES (
        'default',
        p_namespace,
        p_table_name,
        v_uuid,
        p_metadata_location,
        NULL,
        p_metadata_json->>'location',
        'TABLE',
        COALESCE(p_metadata_json->'properties', '{}'::jsonb),
        COALESCE(p_metadata_json, '{}'::jsonb)
    );

    RETURN QUERY
    SELECT
        t.namespace,
        t.table_name,
        t.table_uuid,
        t.metadata_location,
        t.metadata_json
    FROM iceberg_catalog.tables t
    WHERE t.namespace = p_namespace
      AND t.table_name = p_table_name;

EXCEPTION WHEN unique_violation THEN
    RAISE EXCEPTION 'Table already exists: %.%', p_namespace, p_table_name
        USING ERRCODE = 'unique_violation';
END;
$$;

-- ============================================
-- 8.6 load_table
-- ============================================
CREATE OR REPLACE FUNCTION iceberg_catalog.load_table(
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
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT
        t.namespace,
        t.table_name,
        t.table_uuid,
        t.metadata_location,
        t.metadata_json
    FROM iceberg_catalog.tables t
    WHERE t.namespace = p_namespace
      AND t.table_name = p_table_name;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Table not found: %.%', p_namespace, p_table_name;
    END IF;
END;
$$;

-- ============================================
-- 8.7 list_tables
-- ============================================
CREATE OR REPLACE FUNCTION iceberg_catalog.list_tables(
    p_namespace TEXT
)
RETURNS TABLE (
    namespace TEXT,
    table_name TEXT,
    table_uuid UUID,
    metadata_location TEXT
)
LANGUAGE sql
AS $$
    SELECT
        namespace,
        table_name,
        table_uuid,
        metadata_location
    FROM iceberg_catalog.tables
    WHERE namespace = p_namespace
    ORDER BY table_name;
$$;

-- ============================================
-- 8.8 commit_table
-- ============================================
CREATE OR REPLACE FUNCTION iceberg_catalog.commit_table(
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
LANGUAGE plpgsql
AS $$
DECLARE
    v_rows INT;
BEGIN
    UPDATE iceberg_catalog.tables t
    SET
        previous_metadata_location = t.metadata_location,
        metadata_location = p_new_metadata_location,
        metadata_json = COALESCE(p_new_metadata_json, t.metadata_json),
        updated_at = now()
    WHERE t.namespace = p_namespace
      AND t.table_name = p_table_name
      AND t.metadata_location = p_expected_metadata_location;

    GET DIAGNOSTICS v_rows = ROW_COUNT;

    IF v_rows = 0 THEN
        IF NOT EXISTS (
            SELECT 1 FROM iceberg_catalog.tables t
            WHERE t.namespace = p_namespace
              AND t.table_name = p_table_name
        ) THEN
            RAISE EXCEPTION 'Table not found: %.%', p_namespace, p_table_name;
        ELSE
            RAISE EXCEPTION
                'Commit conflict for %.%: expected metadata_location %, but current metadata_location has changed',
                p_namespace, p_table_name, p_expected_metadata_location
                USING ERRCODE = 'serialization_failure';
        END IF;
    END IF;

    RETURN QUERY
    SELECT
        t.namespace,
        t.table_name,
        t.table_uuid,
        t.metadata_location,
        t.metadata_json
    FROM iceberg_catalog.tables t
    WHERE t.namespace = p_namespace
      AND t.table_name = p_table_name;
END;
$$;

-- ============================================
-- 8.9 alter_table
-- ============================================
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
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT *
    FROM iceberg_catalog.commit_table(
        p_namespace,
        p_table_name,
        p_expected_metadata_location,
        p_new_metadata_location,
        COALESCE(p_new_metadata_json, p_updates_json)
    );
END;
$$;

-- ============================================
-- 8.10 drop_table
-- ============================================
CREATE OR REPLACE FUNCTION iceberg_catalog.drop_table(
    p_namespace TEXT,
    p_table_name TEXT,
    p_purge BOOLEAN DEFAULT false
)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
DECLARE
    v_metadata_location TEXT;
BEGIN
    SELECT metadata_location
    INTO v_metadata_location
    FROM iceberg_catalog.tables
    WHERE namespace = p_namespace
      AND table_name = p_table_name;

    IF v_metadata_location IS NULL THEN
        RAISE EXCEPTION 'Table not found: %.%', p_namespace, p_table_name;
    END IF;

    DELETE FROM iceberg_catalog.tables
    WHERE namespace = p_namespace
      AND table_name = p_table_name;

    IF p_purge THEN
        INSERT INTO iceberg_catalog.purge_queue(namespace, table_name, metadata_location)
        VALUES (p_namespace, p_table_name, v_metadata_location);
    END IF;

    RETURN TRUE;
END;
$$;

-- ============================================
-- 8.11 unregister_table
-- ============================================
CREATE OR REPLACE FUNCTION iceberg_catalog.unregister_table(
    p_namespace TEXT,
    p_table_name TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM iceberg_catalog.tables
    WHERE namespace = p_namespace
      AND table_name = p_table_name;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Table not found: %.%', p_namespace, p_table_name;
    END IF;

    RETURN TRUE;
END;
$$;

-- ============================================
-- 8.12 rename_table
-- ============================================
CREATE OR REPLACE FUNCTION iceberg_catalog.rename_table(
    p_old_namespace TEXT,
    p_old_table_name TEXT,
    p_new_namespace TEXT,
    p_new_table_name TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
DECLARE
    v_rows INT;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM iceberg_catalog.namespaces
        WHERE namespace = p_new_namespace
    ) THEN
        RAISE EXCEPTION 'Target namespace not found: %', p_new_namespace;
    END IF;

    UPDATE iceberg_catalog.tables
    SET
        namespace = p_new_namespace,
        table_name = p_new_table_name,
        updated_at = now()
    WHERE namespace = p_old_namespace
      AND table_name = p_old_table_name;

    GET DIAGNOSTICS v_rows = ROW_COUNT;

    IF v_rows = 0 THEN
        RAISE EXCEPTION 'Table not found: %.%', p_old_namespace, p_old_table_name;
    END IF;

    RETURN TRUE;

EXCEPTION WHEN unique_violation THEN
    RAISE EXCEPTION 'Target table already exists: %.%', p_new_namespace, p_new_table_name;
END;
$$;