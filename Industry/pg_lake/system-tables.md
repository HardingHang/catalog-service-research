# pg_lake 系统表

本文档描述 pg_lake 中各 extension 创建的系统表（catalog tables）——它们的结构、用途、外键关系，以及在 extension 升级过程中的处理策略。

## 1. 概览

pg_lake 在 4 个 extension 的 base SQL 中共定义了 14 张系统表：

| Extension | Schema | 表数量 |
|-----------|--------|--------|
| pg_extension_base | `extension_base` | 1 |
| pg_lake_engine | `lake_engine` | 2 |
| pg_lake_iceberg | `lake_iceberg` | 3 |
| pg_lake_table | `lake_table` | 8 |

此外 `pg_lake_iceberg` 还在 `pg_catalog` 中创建了 2 个用户可见视图。

所有系统表均在 extension 的 base SQL 文件中通过 `CREATE TABLE` 一次性建立，升级脚本中从不新建表，也不增加或删除列。

## 2. 各表详细定义

### 2.1 extension_base.workers

所属：`pg_extension_base--1.6.sql`

```sql
CREATE TABLE extension_base.workers (
    worker_id serial not null
        CONSTRAINT worker_id_unique UNIQUE,
    worker_name text not null
        CONSTRAINT workers_pk PRIMARY KEY
        CONSTRAINT name_length CHECK (char_length(worker_name) <= 255),
    extension_name name not null,
    entry_point_schema name not null,
    entry_point_function name not null
);
```

**用途**：后台 worker 注册表。其他 extension 在 base SQL 中通过 `extension_base.register_worker()` 将自身的后台任务注册到此表。`pg_extension_base` 的 BaseWorkerLauncher 读取此表来启动所有已注册的 worker。

**数据写入方**：各 extension 的 base SQL（静态注册），以及 `extension_base.register_worker()` / `deregister_worker()` 函数。

**升级历史**：
- 1.5→1.6：`crunchy_base.workers` 重命名为 `extension_base.workers`
- 2.x→3.0（重命名期）：`UPDATE` 更新 `worker_name`、`entry_point_schema`、`extension_name` 以匹配新的命名

### 2.2 lake_engine.deletion_queue

所属：`pg_lake_engine--3.0.sql`

```sql
CREATE TABLE lake_engine.deletion_queue (
    path text PRIMARY KEY,
    table_name regclass,
    orphaned_at timestamptz,
    retry_count int DEFAULT 0,
    is_prefix bool DEFAULT false
);
```

**用途**：文件删除队列。当数据文件不再被任何快照引用时，其路径被加入此队列。后台 vacuum worker 根据 `lake_engine.orphaned_file_retention_period` 决定何时真正删除对象存储中的文件。

**数据写入方**：vacuum / commit 流程通过 C 代码 SPI 写入。

**升级历史**：
- 2.4→3.0：schema 从 `crunchy_query_engine` 重命名为 `lake_engine`
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`

### 2.3 lake_engine.in_progress_files

所属：`pg_lake_engine--3.0.sql`

```sql
CREATE TABLE lake_engine.in_progress_files (
    path text PRIMARY KEY,
    operation_id int8,
    is_prefix bool
);
```

**用途**：跟踪活跃事务正在写入的文件。事务成功提交后，对应的记录由同一事务删除。如果事务回滚，vacuum 负责清理对象存储中的这些文件并从此表移除记录。

**数据写入方**：写入事务的 C 代码。

**升级历史**：
- 2.4→3.0：schema 重命名
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`

### 2.4 lake_iceberg.namespace_properties

所属：`pg_lake_iceberg--3.0.sql`

```sql
CREATE TABLE lake_iceberg.namespace_properties (
    catalog_name varchar(255) NOT NULL,      -- Iceberg catalog 名称，如 'default', 'polaris'
    namespace varchar(255) NOT NULL,          -- 命名空间路径，如 'db.schema'
    property_key varchar(255),                -- 属性键
    property_value varchar(1000),             -- 属性值
    PRIMARY KEY (catalog_name, namespace, property_key)
);
```

**用途**：存储 Iceberg catalog 中各命名空间的属性（key-value 对）。

**数据写入方**：catalog 操作（如设置/修改 namespace 属性）通过 C 代码写入。

**升级历史**：
- 2.4→3.0：schema 从 `crunchy_iceberg` 重命名为 `lake_iceberg`
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`；GRANT SELECT TO public

### 2.5 lake_iceberg.tables_internal

所属：`pg_lake_iceberg--3.0.sql`

```sql
CREATE TABLE lake_iceberg.tables_internal (
    table_name regclass NOT NULL,              -- 对应 pg_class 中表的引用，表被 DROP 时级联删除
    metadata_location varchar(1000),           -- 当前 Iceberg metadata 文件路径
    previous_metadata_location varchar(1000),  -- 上一个 metadata 文件路径，用于元数据回滚
    read_only bool DEFAULT false,              -- 表是否为只读
    has_custom_location BOOL DEFAULT true,      -- 是否使用了自定义存储位置（非默认 warehouse 路径）
    default_spec_id INT DEFAULT 0,             -- 默认分区规格 ID
    PRIMARY KEY (table_name)
);
```

**用途**：记录本服务器上创建的 Iceberg 表。使用 `regclass` 类型直接引用 `pg_class`，支持通过 OID 追踪表的生命周期——表被 DROP 时对应记录级联删除。

**数据写入方**：`CREATE TABLE ... USING iceberg` 的执行路径。

**升级历史**：
- 2.4→3.0：schema 重命名
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`；GRANT SELECT TO public

### 2.6 lake_iceberg.tables_external

所属：`pg_lake_iceberg--3.0.sql`

```sql
CREATE TABLE lake_iceberg.tables_external (
    catalog_name varchar(255) NOT NULL,        -- Iceberg catalog 名称
    table_namespace varchar(255) NOT NULL,     -- 表所属的命名空间
    table_name varchar(255) NOT NULL,          -- 表名
    metadata_location varchar(1000),           -- 当前 Iceberg metadata 文件路径
    previous_metadata_location varchar(1000),  -- 上一个 metadata 文件路径，用于元数据回滚
    PRIMARY KEY (catalog_name, table_namespace, table_name)
);
```

**用途**：记录由外部工具创建、仅使用本服务器作为 catalog 的 Iceberg 表。与 `tables_internal` 的区别在于它没有 `regclass` 引用（外部表在本服务器上没有对应的 PostgreSQL relation）。

**设计原因**（来自源码注释）：
> two tables enables us to use "regclass" for the internal ones

将内部表和外部表分开，可以：
- 内部表使用 `regclass` 实现与 PostgreSQL 表生命周期的联动（DROP TABLE 级联删除记录）
- 外部表通过 `(catalog_name, table_namespace, table_name)` 三元组标识
- 避免在单表中混合 `regclass` 和纯文本引用带来的 NULL 语义问题

**数据写入方**：外部 catalog 同步操作。

**升级历史**：
- 2.4→3.0：schema 重命名
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`；GRANT SELECT TO public

### 2.7 lake_table.files

所属：`pg_lake_table--3.0.sql`

```sql
CREATE TABLE lake_table.files (
    id bigserial UNIQUE NOT NULL,
    table_name regclass not null,
    path text not null,
    row_count bigint not null default -1,
    updated_time timestamptz not null default now(),
    file_size bigint not null CHECK (file_size >= 0),
    content int not null default 0,
    deleted_row_count bigint not null default 0,
    first_row_id bigint,
    PRIMARY KEY (table_name, path)
);
```

**用途**：pg_lake 最核心的系统表，是所有数据文件注册的单一真相来源。每一行代表属于某个 Iceberg 表的一个数据文件或删除文件。

关键列：
- `content`: 0 = 数据文件(data), 1 = 删除文件(delete)，对应 Iceberg 规范的 `DataContentType`
- `first_row_id`: 与 `row_id_mappings` 协同，记录该文件中行 ID 范围的起始值（见 Iceberg v3 Row Lineage）
- `id`: `bigserial`，被 `row_id_mappings.file_id` 和 `data_file_partition_values.id` 引用

**数据写入方**：INSERT/COPY 执行路径写入新文件记录；vacuum/compaction 删除过期文件记录。

**升级历史**：
- 2.4-1→3.0：从 `lake_table.data_files` 重命名为 `lake_table.files`
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`
- 3.3→3.4：设置 autovacuum 参数 `(analyze_scale_factor=0.05, analyze_threshold=500)`

### 2.8 lake_table.deletion_file_map

所属：`pg_lake_table--3.0.sql`

```sql
CREATE TABLE lake_table.deletion_file_map (
    table_name regclass not null,
    path text not null,
    deleted_from text not null,
    PRIMARY KEY (table_name, deleted_from, path),
    CONSTRAINT path_fk
        FOREIGN KEY (table_name, deleted_from)
        REFERENCES lake_table.files(table_name, path)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT deleted_from_fk
        FOREIGN KEY (table_name, path)
        REFERENCES lake_table.files (table_name, path)
        ON DELETE CASCADE
);
```

**用途**：建立删除文件到其作用的数据文件的映射。两个外键都引用 `lake_table.files`：
- `deleted_from_fk`：确保表中记录的删除文件路径本身在 `files` 表中注册
- `path_fk`：确保被引用的数据文件存在

**数据写入方**：写入删除文件（position delete / equality delete）时建立映射。

**升级历史**：
- 2.4-1→3.0：schema 重命名
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`

### 2.9 lake_table.field_id_mappings

所属：`pg_lake_table--3.0.sql`

```sql
CREATE TABLE lake_table.field_id_mappings (
    table_name regclass not null,
    field_id int not null,
    pg_attnum smallint NOT NULL,
    parent_field_id int,
    initial_default text,
    write_default text,
    field_pg_type regtype not null,
    field_pg_typemod int not null,
    CONSTRAINT unique_attribute UNIQUE (table_name, field_id),
    CONSTRAINT table_name_fk FOREIGN KEY (table_name)
        REFERENCES lake_iceberg.tables_internal (table_name)
        ON DELETE CASCADE,
    CONSTRAINT enforce_field_hierarchy FOREIGN KEY (table_name, parent_field_id)
        REFERENCES lake_table.field_id_mappings (table_name, field_id)
);
```

**用途**：建立 Iceberg field ID 到 PostgreSQL attribute number 的双向映射。这是 Iceberg 规范与 PostgreSQL 类型系统之间的桥梁——Iceberg schema 使用整数 field ID 标识列，PostgreSQL 使用 attnum，此表维护二者对应关系。

关键设计：
- `parent_field_id` 自引用外键：表达嵌套字段的层级关系（如 struct 内部的子字段），顶级字段的 `parent_field_id` 为 NULL
- `initial_default` 和 `write_default`：分别记录表的初始默认值和当前写默认值，仅对顶级字段填充
- 级联删除：表被 DROP 时，通过 `tables_internal` 外键自动清理

**数据写入方**：`CREATE TABLE ... USING iceberg` 时根据 Iceberg schema 写入。

**升级历史**：
- 2.4-1→3.0：schema 重命名
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`

### 2.10 lake_table.data_file_column_stats

所属：`pg_lake_table--3.0.sql`

```sql
CREATE TABLE lake_table.data_file_column_stats (
    table_name regclass not null,
    path text not null,
    field_id bigint not null,
    lower_bound text,
    upper_bound text,
    foreign key (table_name, path) references lake_table.files (table_name, path)
        on delete cascade,
    primary key (table_name, field_id, path)
);
```

**用途**：存储每个数据文件中每列的 min/max 统计信息，用于查询时的文件级剪枝（file pruning）。`lower_bound` 和 `upper_bound` 以文本形式存储，实际比较时根据字段类型解析。

**数据写入方**：写入/compaction 后从 Parquet metadata 或 Iceberg manifest 中提取并写入。

**升级历史**：
- 2.4-1→3.0：schema 重命名
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`
- 3.3→3.4：设置 autovacuum 参数

### 2.11 lake_table.row_id_mappings

所属：`pg_lake_table--3.0.sql`

```sql
CREATE TABLE lake_table.row_id_mappings (
    table_name regclass not null,
    row_id_range int8range not null,
    file_id bigint not null,
    file_row_number bigint not null,
    foreign key (file_id) references lake_table.files (id)
        on delete cascade,
    CHECK (lower_inc(row_id_range) AND NOT upper_inc(row_id_range))
);
```

**用途**：以压缩形式存储 row ID 到 (文件, 行号) 的映射。使用 int8range 将连续的 row ID 范围映射到文件中起始行号，避免为每一行单独存储一条记录。这是实现 Iceberg v3 Row Lineage 的关键组件。

关键约束（创建后追加）：
```sql
ALTER TABLE lake_table.row_id_mappings
  ADD CONSTRAINT row_id_mapping_no_overlap
  EXCLUDE USING gist (
    table_name WITH =,
    row_id_range WITH &&
  );
```
GiST 排他约束保证同一张表的 row ID 范围不会重叠。

额外的索引：
```sql
CREATE INDEX ON lake_table.row_id_mappings (file_id);
```
用于加速级联删除（文件被删除时找到并删除对应的 row ID 映射）。

**数据写入方**：写入事务分配 row ID 范围时写入。

**升级历史**：
- 2.4-1→3.0：schema 重命名
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`

### 2.12 lake_table.partition_specs

所属：`pg_lake_table--3.0.sql`

```sql
CREATE TABLE lake_table.partition_specs (
    table_name regclass,
    spec_id INT,
    CONSTRAINT unique_partition_spec_id UNIQUE (table_name, spec_id),
    CONSTRAINT table_name_fk FOREIGN KEY (table_name)
        REFERENCES lake_iceberg.tables_internal (table_name)
        ON DELETE CASCADE
);
```

**用途**：记录 Iceberg 表的分区规格（partition spec）。Iceberg 表可以有多个 partition spec（通过 spec_id 区分），支持分区演进。

**数据写入方**：`CREATE TABLE` 时指定分区，或后续 `ALTER TABLE` 修改分区规格。

**升级历史**：
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`

### 2.13 lake_table.partition_fields

所属：`pg_lake_table--3.0.sql`

```sql
CREATE TABLE lake_table.partition_fields (
    table_name regclass,
    spec_id INT,
    source_field_id INT,
    partition_field_id INT,
    partition_field_name TEXT,
    transform_name TEXT,
    CONSTRAINT unique_partition_field_id UNIQUE (table_name, partition_field_id),
    CONSTRAINT enforce_partition_field_existence FOREIGN KEY (table_name, source_field_id)
        REFERENCES lake_table.field_id_mappings (table_name, field_id),
    CONSTRAINT spec_id_fkey FOREIGN KEY (table_name, spec_id)
        REFERENCES lake_table.partition_specs (table_name, spec_id),
    CONSTRAINT table_name_fk FOREIGN KEY (table_name)
        REFERENCES lake_iceberg.tables_internal (table_name)
        ON DELETE CASCADE
);
```

**用途**：记录分区规格中的每个分区字段，包括源字段、transform 类型（如 `month`、`bucket[1000]`）以及 Iceberg 自动生成的分区字段名（如 `created_at_day`）。

关键约束链：`partition_fields` → `partition_specs` + `field_id_mappings` → `tables_internal`。三层外键确保分区字段引用的源字段和分区规格都存在。

**数据写入方**：与 `partition_specs` 同步写入。

**升级历史**：
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`

### 2.14 lake_table.data_file_partition_values

所属：`pg_lake_table--3.0.sql`

```sql
CREATE TABLE lake_table.data_file_partition_values (
    table_name regclass NOT NULL,
    id bigint NOT NULL,
    partition_field_id INT NOT NULL,
    value text,
    CONSTRAINT unique_partition_field_value UNIQUE (table_name, id, partition_field_id),
    CONSTRAINT data_file_id_p_fk FOREIGN KEY (id)
        REFERENCES lake_table.files (id) ON DELETE CASCADE,
    CONSTRAINT table_name_fk FOREIGN KEY (table_name)
        REFERENCES lake_iceberg.tables_internal (table_name) ON DELETE CASCADE,
    CONSTRAINT partition_field_fk FOREIGN KEY (table_name, partition_field_id)
        REFERENCES lake_table.partition_fields (table_name, partition_field_id)
);
```

**用途**：存储每个数据文件在各分区字段上的值。与 `data_file_column_stats` 配合使用，支持分区剪枝（partition pruning）——查询时可根据分区条件过滤掉不相关的文件。

`value` 以文本形式存储分区值，实际比较时根据分区字段类型解析。

**数据写入方**：写入/compaction 时从 Iceberg manifest 中提取分区值并写入。

**升级历史**：
- 3.2→3.3：设置 `REPLICA IDENTITY FULL`
- 3.3→3.4：设置 autovacuum 参数

## 3. 外键关系图

```
lake_iceberg.tables_internal (table_name regclass PK)
    ↓ (ON DELETE CASCADE)
    ├── lake_table.field_id_mappings (table_name)
    │       ↓ (ON DELETE CASCADE)
    │       └── lake_table.partition_fields (table_name, source_field_id)
    │               ↓
    │               └── lake_table.data_file_partition_values (table_name, partition_field_id)
    ├── lake_table.partition_specs (table_name)
    │       ↓
    │       └── lake_table.partition_fields (table_name, spec_id)
    └── lake_table.data_file_partition_values (table_name)

lake_table.files (id bigserial UNIQUE, (table_name, path) PK)
    ↓ (ON DELETE CASCADE)
    ├── lake_table.deletion_file_map (双重外键: deleted_from + path)
    ├── lake_table.data_file_column_stats (table_name, path)
    ├── lake_table.row_id_mappings (file_id → files.id)
    └── lake_table.data_file_partition_values (id → files.id)

extension_base.workers (独立，与其他表无外键关系)

lake_engine.deletion_queue (独立)
lake_engine.in_progress_files (独立)
lake_iceberg.namespace_properties (独立)
```

核心级联删除路径：`DROP TABLE` → `tables_internal` 行被删除 → 级联删除 `field_id_mappings`、`partition_specs`、`partition_fields`、`data_file_partition_values` 中的相关行 → `files` 中该表的文件记录 → 级联删除 `deletion_file_map`、`data_file_column_stats`、`row_id_mappings`。

## 4. 运行时数据访问

系统表数据不走 SQL 升级脚本，而是由 extension 的 C 代码在运行时通过 SPI（Server Programming Interface）直接执行 DML。例如：

用户执行 `INSERT INTO iceberg_table` 后，C 代码在同一个事务中登记文件记录：

```c
// pg_lake_table/src/fdw/data_files_catalog_batch.c:235
char *query =
    "INSERT INTO lake_table.files "
    "(table_name, path, row_count, file_size, content, first_row_id) "
    "SELECT $1, path, row_count, file_size, content, first_row_id "
    "FROM unnest($2, $3, $4, $5, $6) "
    "RETURNING id, path";

SPI_execute_with_args(query, /* 6 个参数: OID + 5 个数组 */, ...);
```

创建 Iceberg 表时登记到 `tables_internal`：

```c
// pg_lake_iceberg/src/iceberg/catalog.c:48
appendStringInfo(query,
    "insert into lake_iceberg.tables_internal "
    "(table_name, metadata_location, has_custom_location) "
    "values ($1, $2, $3)");

SPI_execute_with_args(query, /* 3 个参数: OID, text, bool */, ...);
```

SPI 是 PostgreSQL 的 C 层 API，让 extension 代码能像执行普通 SQL 一样操作系统表，且自动处于当前事务中，享受完整的 ACID 保证。

## 5. 升级策略

### 5.1 核心原则：结构不可变

系统表的列定义一经 base SQL 中的 `CREATE TABLE` 确定，在后续所有版本升级中**从不修改**。整个项目中，升级脚本不存在以下操作：

- `CREATE TABLE` — 不新建系统表
- `ALTER TABLE ... ADD COLUMN` — 不增加列
- `ALTER TABLE ... DROP COLUMN` — 不删除列
- `INSERT INTO` / `DELETE FROM` — 不直接操作数据（仅重命名期例外，见下）

### 5.2 升级中实际允许的操作

仅三类非结构性的表级调整：

| 操作 | 出现版本 | 说明 |
|------|----------|------|
| `ALTER TABLE ... REPLICA IDENTITY FULL` | 3.2→3.3 | 为 12 张表设置，支持 logical replication 的 `FOR ALL TABLES` 发布 |
| `ALTER TABLE ... SET (autovacuum_*)` | 3.3→3.4 | 为 3 张表调优 autovacuum 参数 |
| `ALTER TABLE ... RENAME TO` | 2.x→3.0 | 一次性重命名（"de-crunchy"），仅此一次 |

升级中唯一的 `UPDATE` 操作出现在 2.x→3.0 重命名期：更新 `extension_base.workers` 中的 worker 注册信息以匹配新的 schema/extension 名称。这是历史遗留的重命名需求，不是常态。

### 5.3 pg_catalog 视图

`pg_lake_iceberg` 在 `pg_catalog` 中创建了两个用户可见视图：

- `pg_catalog.iceberg_tables` — 聚合 `tables_internal` 和 `tables_external` 的统一视图
- `pg_catalog.iceberg_namespace_properties` — 命名空间属性视图

视图在 base SQL 中的创建方式：
```sql
-- 先在 lake_iceberg schema 中创建
CREATE VIEW lake_iceberg.iceberg_tables AS ...;
-- 再移动到 pg_catalog
ALTER VIEW lake_iceberg.iceberg_tables SET SCHEMA pg_catalog;
```

升级中通过 `CREATE OR REPLACE VIEW pg_catalog.iceberg_tables AS ...` 更新视图定义（3.2→3.3）。此操作依赖 `allow_system_table_mods = true`，在 `pg_lake_iceberg` 的 `_PG_init()` 中仅在 `IsBinaryUpgrade` 为 true 时设置。
