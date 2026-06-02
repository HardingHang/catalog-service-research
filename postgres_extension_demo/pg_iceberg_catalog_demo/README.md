# pg_iceberg_catalog_demo

Demo extension for Iceberg catalog metadata and SQL function endpoints.

## Overview

This PostgreSQL extension provides a lightweight demo for Iceberg Catalog metadata management within PostgreSQL. It demonstrates:

1. Catalog metadata tables (namespaces, tables)
2. JDBC Catalog compatible views
3. REST Catalog endpoint functions
4. CAS (Compare-and-Swap) commit semantics

## Requirements

- PostgreSQL 15.x or 16.x
- pgcrypto extension (for `gen_random_uuid()`)

## Installation

### WSL Environment (Ubuntu 22.04+)

```bash
# 1. Install PostgreSQL and development packages
sudo apt update
sudo apt install -y postgresql postgresql-server-dev-all make gcc libpq-dev

# 2. Verify pg_config
pg_config --version

# 3. Navigate to extension directory
cd /mnt/d/project/postgres_extension_demo/pg_iceberg_catalog_demo

# 4. Build and install
make
sudo make install

# 5. Start PostgreSQL service (if not running)
sudo service postgresql start

# 6. Create extension
psql -d postgres -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"
psql -d postgres -c "CREATE EXTENSION IF NOT EXISTS pg_iceberg_catalog_demo;"
```

### Quick Validation

```bash
# Run smoke test
psql -d postgres -f sql/smoke_test.sql
```

## Features

### Tables

- `iceberg_catalog.namespaces` - Namespace metadata
- `iceberg_catalog.tables` - Table metadata with CAS support
- `iceberg_catalog.purge_queue` - Pending cleanup records

### Views

- `iceberg_tables` - JDBC Catalog compatible view
- `iceberg_namespace_properties` - Namespace properties as KV rows

### Functions

| Function | Description |
|----------|-------------|
| `create_namespace(p_namespace, p_properties)` | Create a namespace |
| `drop_namespace(p_namespace)` | Drop an empty namespace |
| `list_namespaces()` | List all namespaces |
| `create_table(...)` | Create a new Iceberg table |
| `register_table(...)` | Register an existing table |
| `load_table(p_namespace, p_table_name)` | Load table metadata |
| `list_tables(p_namespace)` | List tables in namespace |
| `commit_table(...)` | CAS commit with expected metadata_location |
| `alter_table(...)` | Table metadata updates |
| `drop_table(..., p_purge)` | Drop table with optional purge queue |
| `unregister_table(...)` | Remove table from catalog only |
| `rename_table(...)` | Rename a table |

## Usage Example

```sql
-- Create namespace
SELECT * FROM iceberg_catalog.create_namespace('sales', '{"owner":"team_a"}'::jsonb);

-- Create table
SELECT * FROM iceberg_catalog.create_table(
    'sales',
    'orders',
    '{"type":"struct","fields":[{"id":1,"name":"id","type":"long"}]}'::jsonb
);

-- Load table
SELECT * FROM iceberg_catalog.load_table('sales', 'orders');

-- Commit with CAS
SELECT * FROM iceberg_catalog.commit_table(
    'sales',
    'orders',
    's3://demo-bucket/sales/orders/metadata/v1.metadata.json',
    's3://demo-bucket/sales/orders/metadata/v2.metadata.json'
);

-- View compatible views
SELECT * FROM iceberg_tables;
SELECT * FROM iceberg_namespace_properties;
```

## License

Demo/educational use only.