-- iceberg_catalog 读侧基座（pg_native 后端）
-- 对齐 GaussVector_Iceberg_Catalog_Design.md §6.1/§6.2/§6.3.1。
--
-- 说明：这是**控制面**状态底座，按 doc 3 §4/§7 由控制面提供，不由 FDW
-- extension 创建（数据面只读）。本文件供 GaussVector 控制面或本机验证按需
-- 装配；Phase 2 的 IcebergCatalogResolveTable 只读 namespaces/tables。

CREATE SCHEMA IF NOT EXISTS iceberg_catalog;

CREATE TABLE IF NOT EXISTS iceberg_catalog.namespaces (
    namespace       TEXT PRIMARY KEY,
    properties      JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS iceberg_catalog.tables (
    relid                       REGCLASS,
    namespace                   TEXT NOT NULL,
    table_name                  TEXT NOT NULL,
    table_uuid                  UUID NOT NULL,
    metadata_location           TEXT NOT NULL,
    previous_metadata_location  TEXT,
    table_location              TEXT,
    current_schema_id           INT,
    current_snapshot_id         BIGINT,
    default_spec_id             INT,
    default_sort_order_id       INT,
    last_sequence_number        BIGINT,
    last_updated_ms             BIGINT,
    metadata_version            BIGINT NOT NULL DEFAULT 0,
    iceberg_type                VARCHAR(16) NOT NULL DEFAULT 'TABLE',
    properties                  JSONB NOT NULL DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (namespace, table_name),
    UNIQUE (table_uuid),
    UNIQUE (relid),
    FOREIGN KEY (namespace)
        REFERENCES iceberg_catalog.namespaces(namespace)
        ON DELETE RESTRICT,
    CHECK (iceberg_type IN ('TABLE', 'VIEW'))
);

CREATE TABLE IF NOT EXISTS iceberg_catalog.table_schemas (
    table_uuid          UUID NOT NULL,
    schema_id           INT NOT NULL,
    schema_json         JSONB NOT NULL,
    schema_fingerprint  TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_uuid, schema_id),
    FOREIGN KEY (table_uuid)
        REFERENCES iceberg_catalog.tables(table_uuid)
        ON DELETE CASCADE
);
