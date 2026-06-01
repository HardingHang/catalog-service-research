# GaussVector Iceberg Catalog 元信息表设计

## 1. 文档说明

### 1.1 目标

本文档定义 GaussVector 在单 Catalog 场景下的 Iceberg Catalog 元信息表设计，目标是在首期商用范围内满足以下要求：

1. Catalog 正确性
2. 高频元数据读取性能
3. 提交一致性
4. 审计与排障
5. 异步清理
6. JDBC Catalog 兼容
7. extension 方式安装与升级

### 1.2 适用范围

本文档适用于：

1. 单 Catalog
2. PostgreSQL extension 部署形态
3. Iceberg 元信息以对象存储 `metadata.json` 为权威来源
4. Catalog 数据库存储“当前指针 + 高频缓存 + 审计恢复信息”

### 1.3 非目标

本文档不覆盖：

1. 多 Catalog / 多租户隔离设计
2. Manifest / Data File / Delete File 明细持久化
3. 完整治理平台、血缘系统和统一任务平台
4. 执行引擎内部优化细节

### 1.4 阅读路径

为便于评审，建议按以下顺序阅读：

1. 第 2-4 章：理解背景、约束、设计原则和首期范围
2. 第 5-6 章：理解整体分层、系统关系和物理表职责
3. 第 7-9 章：理解兼容视图、核心流程和一致性运维策略
4. 第 10-11 章：查看可执行 DDL 与 extension 安装升级要求

## 2. 背景与约束

Iceberg 的权威元信息位于对象存储中的 `metadata.json`。Catalog 数据库不应复制完整 Iceberg 元信息，而应稳定维护以下核心映射：

```text
namespace + table_name -> metadata_location
```

仅保存 `metadata_location` 可以满足最小正确性，但会在以下路径上造成额外对象存储访问和 JSON 解析成本：

1. `load_table`
2. `alter_table`
3. `flush_table`
4. 查询规划
5. 管理展示
6. 最近提交与恢复排查

因此，首期采用如下分层：

```text
核心表 + 高频缓存表 + 审计恢复表 + 异步清理表 + 兼容视图
```

## 3. 设计原则

### 3.1 单 Catalog 原则

首期仅支持单 Catalog：

1. 不设计 `catalog_instances`
2. 底表不存储 `catalog_name`
3. 兼容视图固定输出 `catalog_name = 'default'`

### 3.2 权威元数据单一原则

对象存储中的 `metadata.json` 是唯一权威来源。数据库中的缓存表、日志表和恢复表均为派生信息。

### 3.3 最小商用原则

首期只实现直接支撑商用上线的能力：

1. 当前元数据指针维护
2. 高频元数据缓存
3. CAS 提交
4. 操作审计
5. 元数据历史追踪
6. 异步清理
7. JDBC Catalog 兼容

### 3.4 缓存有边界原则

首期缓存：

1. Schema
2. Partition Spec
3. Sort Order
4. Snapshot 摘要
5. Metadata 文件历史

首期不缓存：

1. Manifest 明细
2. Data File 明细
3. Delete File 明细
4. 文件级统计明细

### 3.5 CAS 提交原则

所有表级提交均基于 `metadata_location` 执行 Compare-And-Swap：

```text
只有当前 metadata_location 等于 expected_metadata_location 时，才允许提交新版本
```

### 3.6 对象存储与数据库事务解耦原则

采用以下执行模型：

```text
1. 事务外生成 metadata / data / manifest
2. 事务内更新数据库指针与缓存
3. 失败后异步清理孤儿文件
```

### 3.7 `updated_at` 维护原则

`updated_at` 仅使用 `DEFAULT now()` 完成初始化，后续更新统一由应用在状态成功变更时显式写入 `updated_at = now()`。

首期不依赖数据库触发器自动维护；如后续需要把责任下沉到数据库，可在 extension 中统一补充触发器机制。

## 4. 首期范围

### 4.1 首期对象

| 类型 | 对象 | 是否首期实现 | 说明 |
| --- | --- | --- | --- |
| 核心对象 | `namespaces` | 是 | Namespace 元信息 |
| 核心对象 | `tables` | 是 | 表身份、当前指针、当前摘要 |
| 高频缓存 | `table_schemas` | 是 | Schema 历史缓存 |
| 高频缓存 | `partition_specs` | 是 | Partition Spec 历史缓存 |
| 高频缓存 | `sort_orders` | 是 | Sort Order 历史缓存 |
| 高频缓存 | `snapshots` | 是 | Snapshot 摘要缓存 |
| 恢复排障 | `metadata_log` | 建议 | Metadata 文件历史 |
| 审计 | `table_operation_log` | 是 | 操作日志 |
| 异步清理 | `purge_queue` | 默认是 | 默认的异步清理实现 |
| 兼容视图 | `iceberg_tables` | 是 | JDBC Catalog 兼容视图 |
| 兼容视图 | `iceberg_namespace_properties` | 是 | Namespace 属性兼容视图 |

### 4.2 暂不纳入首期

| 对象 | 原因 |
| --- | --- |
| `catalog_instances` | 单 Catalog 场景不需要 |
| `snapshot_log` | 首期查询诉求可由 `snapshots` 满足，但其“current 切换事件链”语义不等价，暂不单独持久化 |
| `background_jobs` | 首期后台任务以异步清理为主 |
| `operation_locks` | 首期优先采用 CAS 和数据库锁 |
| `manifest_files` | 数据量大，不适合作为 Catalog 持久层 |
| `data_files` | 数据量大，不适合作为 Catalog 持久层 |
| `delete_files` | 数据量大，不适合作为 Catalog 持久层 |

## 5. 逻辑模型

### 5.1 总体分层与系统关系

整体上，Catalog 元信息模型可以分为五层：

1. 核心映射层：维护 `namespace + table_name -> metadata_location`
2. 高频缓存层：缓存 Schema、Partition Spec、Sort Order、Snapshot 摘要
3. 恢复审计层：保存 metadata 历史和操作日志
4. 异步清理层：处理对象存储侧副作用
5. 兼容输出层：向 JDBC Catalog 暴露稳定视图

```text
namespaces
    |
    v
tables
  |
  +--> table_schemas
  +--> partition_specs
  +--> sort_orders
  +--> snapshots
  +--> metadata_log
  +--> table_operation_log
  +--> purge_queue

iceberg_catalog.iceberg_tables
iceberg_catalog.iceberg_namespace_properties
    \--> JDBC Catalog 兼容视图
```

### 5.2 核心表 `tables` 的职责

`tables` 负责维护：

1. 表逻辑身份
2. 当前 `metadata_location`
3. 最近一跳 `previous_metadata_location`
4. 当前 Schema / Snapshot / Partition Spec / Sort Order 的摘要指针
5. 可选的本地对象绑定信息

## 6. 物理表设计

本章按“核心表 -> 高频缓存表 -> 恢复审计表 -> 异步清理表”的顺序展开，便于从主链路逐步理解整套模型。每张表均给出职责、字段说明、表说明和必要性。完整可执行 DDL 见第 10 章。

### 6.1 `namespaces`

#### 职责

1. 保存 Namespace 基本信息
2. 保存 Namespace 扩展属性
3. 作为 `tables` 的父对象

#### 表定义

```sql
CREATE TABLE iceberg_catalog.namespaces (
    namespace       TEXT PRIMARY KEY,
    properties      JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### 字段说明

| 字段 | 类型 | 约束 | 必要性 | 使用场景 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `namespace` | `TEXT` | 主键，非空 | 必需 | Namespace 创建、删除、查表归属、兼容视图查询 | Namespace 的唯一逻辑名称，是后续所有表对象进行归属和定位的基础键。 |
| `properties` | `JSONB` | 非空，默认 `'{}'` | 建议 | Namespace 属性维护、默认策略读取、兼容视图展开 | 保存 Namespace 级扩展属性，如 `owner`、`comment`、`location` 等，首期不拆独立属性表。 |
| `created_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 建议 | 审计、排序展示、巡检报表 | 记录 Namespace 首次写入 Catalog 的时间。 |
| `updated_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 建议 | 属性变更审计、同步判断、运维排查 | 记录 Namespace 最近一次成功更新的时间。 |

#### 表说明

`namespaces` 是 Catalog 的顶层逻辑对象表，承载命名空间名称及其扩展属性。它既是表归属关系的父对象，也是 JDBC 兼容视图中 Namespace 属性展开的基础数据来源。

#### 必要性

该表首期必须实现，原因如下：

1. Catalog 需要显式管理 Namespace 生命周期。
2. `tables` 需要稳定的父级归属关系。
3. Namespace 级属性需要持久化存储和对外兼容输出。
4. 删除 Namespace 时需要依赖该表进行存在性和约束检查。

#### 维护说明

`updated_at` 由应用层在 Namespace 属性成功变更时显式更新；首期不依赖数据库触发器自动维护。

### 6.2 `tables`

#### 职责

1. 维护表逻辑身份
2. 维护当前 `metadata_location`
3. 维护当前摘要字段
4. 绑定本地 FDW 对象（可选）

#### 表定义

```sql
CREATE TABLE iceberg_catalog.tables (
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
```

#### 字段说明

| 字段 | 类型 | 约束 | 必要性 | 使用场景 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `relid` | `REGCLASS` | 唯一，可空 | 可选 | 本地 FDW 绑定、本地对象联动删除、内部对象映射 | 关联数据库内本地 relation。纯外部 Iceberg 表通常没有本地对象，因此允许为空。 |
| `namespace` | `TEXT` | 非空，外键 | 必需 | 表定位、按 Namespace 枚举、兼容视图输出 | 表所属 Namespace，与 `table_name` 共同构成 Catalog 逻辑主键。 |
| `table_name` | `TEXT` | 非空 | 必需 | 表定位、对外查询、管理界面展示 | Catalog 侧逻辑表名，是大多数查询和兼容层直接使用的命名字段。 |
| `table_uuid` | `UUID` | 非空，唯一 | 必需 | 注册表复用、跨重命名识别、稳定对象关联 | Iceberg 表稳定身份，必须来自 `metadata.json`，不能由数据库自行生成。 |
| `metadata_location` | `TEXT` | 非空 | 必需 | `load_table`、`commit_table`、缓存修复、CAS 比较 | 当前生效的 `metadata.json` 路径，是 Catalog 正确性的核心指针字段。 |
| `previous_metadata_location` | `TEXT` | 可空 | 建议 | 最近一跳回溯、快速排障、人工回退分析 | 保存最近一个已失效的 metadata 路径，便于快速查看变更前状态。 |
| `table_location` | `TEXT` | 可空 | 可选 | 管理展示、路径治理、清理辅助定位 | Iceberg 表根路径，主要用于展示和对象存储路径校验，不承担核心正确性职责。 |
| `current_schema_id` | `INT` | 可空 | 必需 | 当前列结构读取、`load_table` 响应、查询规划 | 指向当前生效的 Schema 版本，配合 `table_schemas` 快速获取列定义。 |
| `current_snapshot_id` | `BIGINT` | 可空 | 条件必需 | 当前版本展示、写入结果返回、最近版本定位 | 指向当前生效 Snapshot；空表或尚未形成 Snapshot 的表允许为空。 |
| `default_spec_id` | `INT` | 可空 | 必需 | Flush 写入、分区组织、查询规划 | 指向当前默认 Partition Spec，用于快速获取分区规则。 |
| `default_sort_order_id` | `INT` | 可空 | 必需 | 当前排序规则读取、后续优化扩展 | 指向当前默认 Sort Order，要求与 `sort_orders` 缓存保持一致。 |
| `last_sequence_number` | `BIGINT` | 可空 | 建议 | 提交顺序分析、增量边界判断、异常排查 | 记录最近一次 Iceberg 提交对应的 sequence number。 |
| `last_updated_ms` | `BIGINT` | 可空 | 建议 | 最近变更展示、同步判断、时序诊断 | 记录当前 metadata 的更新时间戳。 |
| `metadata_version` | `BIGINT` | 非空，默认 `0` | 建议 | 本地缓存失效判断、并发排查、内部运维观察 | 数据库侧辅助版本号，不属于 Iceberg 协议字段。 |
| `iceberg_type` | `VARCHAR(16)` | 非空，默认 `'TABLE'` | 建议 | 对象分类、管理展示、后续兼容扩展 | 标识当前对象类型，当前约束为 `TABLE` 或 `VIEW`。 |
| `properties` | `JSONB` | 非空，默认 `'{}'` | 建议 | Catalog 扩展属性、治理标记、内部控制参数 | 保存不适合拆成固定列的 Catalog 侧附加属性。 |
| `created_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 建议 | 审计、对象生命周期记录 | 记录 Catalog 中首次创建该表对象的时间。 |
| `updated_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 建议 | 审计、状态变更追踪、缓存重建记录 | 记录该表在 Catalog 中最近一次成功状态变更时间。 |

#### 表说明

`tables` 是整个元信息模型的核心表，负责保存表逻辑身份、当前 `metadata_location` 以及与当前版本相关的摘要字段。对外所有表级访问几乎都先命中该表，再决定是否读取缓存表或对象存储中的 `metadata.json`。

#### 必要性

该表首期必须实现，原因如下：

1. Catalog 正确性依赖 `namespace + table_name -> metadata_location` 的稳定映射。
2. `commit_table` 需要基于该表执行 CAS 提交。
3. `load_table` 需要从该表快速获取当前摘要字段。
4. 所有缓存表、审计表和清理表都需要通过 `table_uuid` 或逻辑表名与该表关联。

#### 设计说明

1. 主键采用 `(namespace, table_name)`，而不是 `relid`
2. `metadata_version` 是数据库侧辅助版本，不属于 Iceberg 协议字段
3. `previous_metadata_location` 是最近一跳快捷指针，不替代 `metadata_log`
4. 当前指针到缓存表的完整性约束在基础表创建后通过 `ALTER TABLE` 补充
5. `relid` 只是本地 relation 绑定信息，不作为权威身份；若本地 relation 被 `DROP`、重建或替换，应用必须同步清空或改写 `relid`，避免保留失效 OID

#### 维护说明

`updated_at` 由应用层在提交、重命名、属性修改和绑定关系变化成功后显式更新；首期不依赖数据库触发器自动维护。

### 6.3 高频缓存表

#### 6.3.1 `table_schemas`

```sql
CREATE TABLE iceberg_catalog.table_schemas (
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
```

| 字段 | 类型 | 约束 | 必要性 | 使用场景 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `table_uuid` | `UUID` | 主键组成，外键 | 必需 | 按表加载 Schema、缓存关联、级联删除 | 标识该 Schema 记录属于哪张表，是缓存表的稳定关联键。 |
| `schema_id` | `INT` | 主键组成 | 必需 | Schema 演进版本区分、当前 Schema 命中 | Iceberg Schema ID，用于区分不同版本的列结构。 |
| `schema_json` | `JSONB` | 非空 | 必需 | `load_table` 返回、查询规划、表结构展示 | 缓存完整 Schema 定义，避免重复解析 `metadata.json`。 |
| `schema_fingerprint` | `TEXT` | 可空 | 可选 | 结构变更比较、缓存去重 | Schema 摘要值，用于快速比较两版结构是否相同。 |
| `created_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 建议 | 缓存刷新追踪、运维排查 | 记录该 Schema 缓存行写入时间。 |

#### 表说明

`table_schemas` 保存 Iceberg Schema 历史缓存，是 `tables.current_schema_id` 指向的结构化内容承载表。该表的目标是避免每次读取当前列结构时都重新解析完整 `metadata.json`。

#### 必要性

该表首期必须实现，原因如下：

1. `load_table` 高频返回当前 Schema。
2. 查询规划、字段展示和结构对比都依赖完整 Schema 定义。
3. `alter_table` 需要做 Schema 变更判断和缓存刷新。

#### 6.3.2 `partition_specs`

```sql
CREATE TABLE iceberg_catalog.partition_specs (
    table_uuid      UUID NOT NULL,
    spec_id         INT NOT NULL,
    spec_json       JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_uuid, spec_id),
    FOREIGN KEY (table_uuid)
        REFERENCES iceberg_catalog.tables(table_uuid)
        ON DELETE CASCADE
);
```

| 字段 | 类型 | 约束 | 必要性 | 使用场景 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `table_uuid` | `UUID` | 主键组成，外键 | 必需 | 按表读取分区规则、缓存关联、级联删除 | 标识该 Partition Spec 记录属于哪张表。 |
| `spec_id` | `INT` | 主键组成 | 必需 | 分区规则版本区分、默认 Spec 命中 | Iceberg Partition Spec ID，用于区分不同分区规则版本。 |
| `spec_json` | `JSONB` | 非空 | 必需 | Flush 分区写入、查询规划、分区信息展示 | 缓存完整 Partition Spec 定义，包括分区列和转换函数。 |
| `created_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 建议 | 缓存刷新追踪、规则变更审计 | 记录该分区规则写入缓存的时间。 |

#### 表说明

`partition_specs` 保存分区规则历史缓存，是 `tables.default_spec_id` 指向的结构化内容承载表。该表主要服务于 Flush 写入和查询规划过程中的分区规则快速获取。

#### 必要性

该表首期必须实现，原因如下：

1. Flush 写入需要当前默认分区规则。
2. 查询规划可能需要读取分区定义。
3. 分区规则数据量小、变更频率低，缓存收益高且一致性成本低。

#### 6.3.3 `sort_orders`

```sql
CREATE TABLE iceberg_catalog.sort_orders (
    table_uuid      UUID NOT NULL,
    order_id        INT NOT NULL,
    order_json      JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_uuid, order_id),
    FOREIGN KEY (table_uuid)
        REFERENCES iceberg_catalog.tables(table_uuid)
        ON DELETE CASCADE
);
```

| 字段 | 类型 | 约束 | 必要性 | 使用场景 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `table_uuid` | `UUID` | 主键组成，外键 | 必需 | 按表读取排序规则、缓存关联、级联删除 | 标识该 Sort Order 属于哪张表。 |
| `order_id` | `INT` | 主键组成 | 必需 | 排序规则版本区分、默认 Sort Order 命中 | Iceberg Sort Order ID，用于区分不同排序策略。 |
| `order_json` | `JSONB` | 非空 | 必需 | 当前排序规则读取、优化扩展、规则展示 | 缓存完整 Sort Order 定义，包括排序字段、方向和空值顺序。 |
| `created_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 建议 | 缓存刷新追踪、规则变更审计 | 记录排序规则进入缓存的时间。 |

#### 表说明

`sort_orders` 保存排序规则历史缓存，是 `tables.default_sort_order_id` 指向的结构化内容承载表。首期虽然排序规则的直接收益低于 Schema 和 Partition Spec，但为了保证当前摘要字段完整性和后续扩展能力，仍纳入首期模型。

#### 必要性

该表首期建议按“必需”落地，原因如下：

1. 主表已经保存 `default_sort_order_id`，需要有稳定的目标表承接。
2. Sort Order 数据规模小，落地成本低。
3. 后续排序写入、聚簇优化和执行层扩展会直接依赖该表。

#### 6.3.4 `snapshots`

```sql
CREATE TABLE iceberg_catalog.snapshots (
    table_uuid          UUID NOT NULL,
    snapshot_id         BIGINT NOT NULL,
    parent_snapshot_id  BIGINT,
    sequence_number     BIGINT,
    timestamp_ms        BIGINT NOT NULL,
    manifest_list       TEXT NOT NULL,
    operation           TEXT,
    summary             JSONB,
    is_current          BOOLEAN NOT NULL DEFAULT false,
    cached_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_uuid, snapshot_id),
    FOREIGN KEY (table_uuid)
        REFERENCES iceberg_catalog.tables(table_uuid)
        ON DELETE CASCADE
);
```

| 字段 | 类型 | 约束 | 必要性 | 使用场景 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `table_uuid` | `UUID` | 主键组成，外键 | 必需 | 按表查询 Snapshot、缓存关联、级联删除 | 标识该 Snapshot 记录属于哪张表。 |
| `snapshot_id` | `BIGINT` | 主键组成 | 必需 | 当前版本定位、历史版本查询、操作日志关联 | Iceberg Snapshot 唯一标识。 |
| `parent_snapshot_id` | `BIGINT` | 可空 | 建议 | 版本链构建、问题排查、回溯分析 | 指向父 Snapshot，用于描述版本演进关系。 |
| `sequence_number` | `BIGINT` | 可空 | 建议 | 提交顺序分析、增量处理边界判断 | 对应 Iceberg sequence number。 |
| `timestamp_ms` | `BIGINT` | 非空 | 必需 | 最近提交排序、时间线展示、版本回溯 | Snapshot 的生成时间。 |
| `manifest_list` | `TEXT` | 非空 | 建议 | 诊断分析、文件级扩展预留 | 指向该 Snapshot 的 manifest list 文件路径。 |
| `operation` | `TEXT` | 可空 | 建议 | 写入类型展示、审计分析、故障排查 | 记录 Snapshot 对应的写入类型。 |
| `summary` | `JSONB` | 可空 | 建议 | 管理展示、写入统计、排障分析 | 缓存 Snapshot 摘要统计信息。 |
| `is_current` | `BOOLEAN` | 非空，默认 `false` | 必需 | 当前版本快速命中、唯一 current 约束 | 标记该 Snapshot 是否为当前生效版本，同一张表最多一条为 `true`。 |
| `cached_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 建议 | 缓存刷新追踪、运维诊断 | 记录该 Snapshot 缓存写入数据库的时间。 |

#### 表说明

`snapshots` 保存 Snapshot 摘要缓存，用于快速定位当前版本、展示最近提交历史以及关联提交结果。该表不是权威历史来源，而是数据库侧面向查询和运维的高价值缓存。

#### 必要性

该表首期必须实现，原因如下：

1. 当前 Snapshot 查询是高频路径。
2. 最近提交展示和写入结果回显都依赖 Snapshot 摘要。
3. 仅依赖 `metadata.json` 会显著增加对象存储访问与解析成本。

#### 说明

1. `snapshots` 是性能缓存表，不是权威历史来源
2. `snapshot_log` 与 `snapshots` 语义不等价；前者表示“某个 Snapshot 在何时成为 current”的事件链，后者保存 Snapshot 静态摘要与当前态
3. 首期查询重点是当前版本定位和最近提交展示，因此只落地 `snapshots`，暂不单独持久化 `snapshot_log`
4. 当需要支持回滚审计、时点回溯或分析 current Snapshot 切换历史时，再考虑引入 `snapshot_log`

### 6.4 恢复与审计表

#### 6.4.1 `metadata_log`

```sql
CREATE TABLE iceberg_catalog.metadata_log (
    table_uuid          UUID NOT NULL,
    metadata_file       TEXT NOT NULL,
    timestamp_ms        BIGINT NOT NULL,
    source_operation_id BIGINT,
    is_current          BOOLEAN NOT NULL DEFAULT false,
    cached_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_uuid, metadata_file),
    FOREIGN KEY (table_uuid)
        REFERENCES iceberg_catalog.tables(table_uuid)
        ON DELETE CASCADE
);
```

| 字段 | 类型 | 约束 | 必要性 | 使用场景 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `table_uuid` | `UUID` | 主键组成，外键 | 建议 | 按表查看 metadata 历史、恢复关联、级联删除 | 标识该 metadata 文件历史记录属于哪张表。 |
| `metadata_file` | `TEXT` | 主键组成 | 建议 | 历史定位、恢复候选选择、版本排查 | metadata 文件完整路径，是历史版本定位的直接依据。 |
| `timestamp_ms` | `BIGINT` | 非空 | 建议 | 历史排序、恢复分析、时间线展示 | 记录该 metadata 文件生成或生效的时间。 |
| `source_operation_id` | `BIGINT` | 可空 | 可选 | 操作日志关联、审计链路串联 | 指向触发该 metadata 文件写入的操作日志记录。 |
| `is_current` | `BOOLEAN` | 非空，默认 `false` | 建议 | 当前 metadata 快速命中、唯一 current 约束 | 标记该 metadata 文件是否是当前生效版本，同一张表最多一条为 `true`。 |
| `cached_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 可选 | 缓存刷新追踪、运维诊断 | 记录 metadata 历史行进入数据库的时间。 |

#### 表说明

`metadata_log` 保存 metadata 文件历史缓存，用于追踪 metadata 版本演进、支持恢复分析和缓存重建。它对应的是一段可回溯历史，而不是主表上的最近一跳快捷指针。

#### 必要性

该表首期建议实现，原因如下：

1. `previous_metadata_location` 只能覆盖最近一跳变更。
2. 一旦需要恢复、排障或重建缓存，需要多版本 metadata 历史作为依据。
3. Metadata 文件历史对商用运维价值较高，而存储成本相对可控。

#### 说明

1. `metadata_log` 能推导历史上一版 metadata 文件
2. `previous_metadata_location` 仍然保留为最近一跳快捷指针

#### 6.4.2 `table_operation_log`

```sql
CREATE TABLE iceberg_catalog.table_operation_log (
    id                      BIGSERIAL PRIMARY KEY,
    namespace               TEXT NOT NULL,
    table_name              TEXT NOT NULL,
    table_uuid              UUID,
    operation_type          TEXT NOT NULL,
    operation_status        TEXT NOT NULL,
    old_metadata_location   TEXT,
    new_metadata_location   TEXT,
    snapshot_id             BIGINT,
    parent_snapshot_id      BIGINT,
    request_id              TEXT,
    operator_name           TEXT,
    client_type             TEXT,
    error_code              TEXT,
    error_message           TEXT,
    operation_detail        JSONB NOT NULL DEFAULT '{}',
    started_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at             TIMESTAMPTZ,
    CHECK (operation_status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);
```

| 字段 | 类型 | 约束 | 必要性 | 使用场景 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `id` | `BIGSERIAL` | 主键 | 必需 | 审计查询、问题定位、跨表关联 | 操作日志唯一标识。 |
| `namespace` | `TEXT` | 非空 | 必需 | 按逻辑对象查询历史、管理侧过滤 | 操作目标所在 Namespace。 |
| `table_name` | `TEXT` | 非空 | 必需 | 按逻辑对象查询历史、管理侧过滤 | 操作目标表名。 |
| `table_uuid` | `UUID` | 可空 | 建议 | 跨重命名稳定关联、历史串联 | 稳定对象标识，避免仅靠逻辑名称关联。 |
| `operation_type` | `TEXT` | 非空 | 必需 | 审计分类、统计报表、告警过滤 | 表级操作类型。 |
| `operation_status` | `TEXT` | 非空 | 必需 | 失败识别、重试判断、运行态追踪 | 记录操作当前或最终状态。 |
| `old_metadata_location` | `TEXT` | 可空 | 建议 | 变更前后对比、恢复分析、排障 | 操作前的 metadata 路径。 |
| `new_metadata_location` | `TEXT` | 可空 | 建议 | 变更结果追踪、提交结果分析、排障 | 操作目标或结果对应的 metadata 路径。 |
| `snapshot_id` | `BIGINT` | 可空 | 建议 | 提交结果关联、版本问题分析 | 本次操作涉及或产生的目标 Snapshot。 |
| `parent_snapshot_id` | `BIGINT` | 可空 | 可选 | 版本链排障、提交链分析 | 新 Snapshot 的父版本。 |
| `request_id` | `TEXT` | 可空 | 建议 | 跨服务链路追踪、日志串联 | 请求链路标识。 |
| `operator_name` | `TEXT` | 可空 | 建议 | 用户审计、责任定位 | 操作执行者。 |
| `client_type` | `TEXT` | 可空 | 建议 | 来源统计、兼容层排障 | 调用来源，如 JDBC、REST、内部任务。 |
| `error_code` | `TEXT` | 可空 | 建议 | 故障分类、告警聚合 | 结构化错误码。 |
| `error_message` | `TEXT` | 可空 | 建议 | 人工排障、失败原因展示 | 失败详细说明。 |
| `operation_detail` | `JSONB` | 非空，默认 `'{}'` | 建议 | 扩展上下文保存、回填信息记录、清理对象记录 | 保存不适合拆成固定列的扩展上下文。 |
| `started_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 必需 | 时间线排序、耗时统计、超时判断 | 操作开始时间。 |
| `finished_at` | `TIMESTAMPTZ` | 可空 | 建议 | 完成时间记录、耗时统计、运行中识别 | 操作完成时间。 |

#### 表说明

`table_operation_log` 是表级操作审计表，负责记录谁在什么时间对哪张表执行了什么操作，以及该操作是否成功、涉及哪些 metadata 和 Snapshot 变更。它是排障、审计、统计和升级验收的重要依据。

#### 必要性

该表首期必须实现，原因如下：

1. 商用环境需要可审计。
2. 异常提交、Flush 失败和回填失败都需要结构化日志支撑定位。
3. `metadata_log`、`purge_queue` 等表都可能需要与操作日志建立关联。

#### 建议操作类型

```text
CREATE_NAMESPACE
DROP_NAMESPACE
CREATE_TABLE
REGISTER_TABLE
LOAD_TABLE
COMMIT_TABLE
ALTER_TABLE
FLUSH_TABLE
RENAME_TABLE
DROP_TABLE
UNREGISTER_TABLE
REFRESH_CACHE
PURGE_TABLE
ORPHAN_CLEANUP
```

### 6.5 异步清理表

#### `purge_queue`

```sql
CREATE TABLE iceberg_catalog.purge_queue (
    id                  BIGSERIAL PRIMARY KEY,
    namespace           TEXT NOT NULL,
    table_name          TEXT NOT NULL,
    table_uuid          UUID,
    metadata_location   TEXT,
    purge_payload       JSONB NOT NULL DEFAULT '{}',
    purge_type          TEXT NOT NULL DEFAULT 'DROP_TABLE',
    status              TEXT NOT NULL DEFAULT 'PENDING',
    retry_count         INT NOT NULL DEFAULT 0,
    max_retry_count     INT NOT NULL DEFAULT 3,
    last_error          TEXT,
    worker_id           TEXT,
    locked_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at        TIMESTAMPTZ,
    CHECK (
        metadata_location IS NOT NULL
        OR purge_payload <> '{}'::JSONB
    ),
    CHECK (purge_type IN ('DROP_TABLE', 'ORPHAN_CLEANUP')),
    CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);
```

| 字段 | 类型 | 约束 | 必要性 | 使用场景 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `id` | `BIGSERIAL` | 主键 | 必需 | 任务调度、重试控制、运维查询 | 清理任务唯一标识。 |
| `namespace` | `TEXT` | 非空 | 必需 | 按逻辑对象检索任务、管理侧过滤 | 清理目标所属 Namespace。 |
| `table_name` | `TEXT` | 非空 | 必需 | 按逻辑对象检索任务、管理侧过滤 | 清理目标表名。 |
| `table_uuid` | `UUID` | 可空 | 建议 | 稳定对象关联、跨重命名任务追踪 | 目标表的稳定身份标识。 |
| `metadata_location` | `TEXT` | 可空 | 建议 | metadata 版本关联、清理结果分析 | 当失败场景已生成 metadata 文件时，标识关联版本。 |
| `purge_payload` | `JSONB` | 非空，默认 `'{}'` | 必需 | 文件列表清理、对象前缀清理、失败阶段记录 | 结构化清理载荷，是 worker 实际执行的主要输入。 |
| `purge_type` | `TEXT` | 非空，默认 `'DROP_TABLE'` | 必需 | 任务分流、执行策略选择 | 区分整表删除清理和普通孤儿文件回收。 |
| `status` | `TEXT` | 非空，默认 `'PENDING'` | 必需 | 调度、重试、监控 | 记录任务生命周期状态。 |
| `retry_count` | `INT` | 非空，默认 `0` | 必需 | 重试次数控制、异常任务识别 | 记录当前任务已经尝试执行的次数。 |
| `max_retry_count` | `INT` | 非空，默认 `3` | 建议 | 重试上限配置、告警阈值控制 | 定义可接受的最大重试次数。 |
| `last_error` | `TEXT` | 可空 | 建议 | 故障排查、失败原因展示 | 最近一次失败错误信息。 |
| `worker_id` | `TEXT` | 可空 | 建议 | 多 Worker 调度、节点归属识别 | 当前处理任务的 worker 或节点标识。 |
| `locked_at` | `TIMESTAMPTZ` | 可空 | 建议 | 抢锁超时判断、卡死任务识别 | 记录任务被锁定的时间。 |
| `created_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 必需 | 排序、延迟统计、超时判断 | 任务创建时间。 |
| `updated_at` | `TIMESTAMPTZ` | 非空，默认 `now()` | 建议 | 状态变化追踪、无进展识别 | 任务最近状态变化时间。 |
| `processed_at` | `TIMESTAMPTZ` | 可空 | 建议 | 完成时间记录、端到端时延统计 | 任务最终处理完成时间。 |

#### 表说明

`purge_queue` 是默认的异步清理实现表，用于承载对象存储副作用治理任务。它的输入可以是 metadata 路径，也可以是结构化 `purge_payload`，从而覆盖 drop purge、CAS 冲突、Flush 失败和孤儿文件回收等场景。

#### 必要性

该表在“未引入外部统一任务系统”的前提下首期必须实现，原因如下：

1. 对象存储写入与数据库事务已经解耦，必须有异步清理能力兜底。
2. 失败场景会遗留 metadata、manifest、Parquet 等孤儿文件。
3. 商用环境要求清理任务可持久化、可重试、可追踪。

#### 说明

1. 首期必须具备异步清理能力
2. `purge_queue` 是默认实现，不是唯一实现方式
3. 如后续接入统一任务系统，可替换该物理表，但必须保留“可持久化、可重试、可追踪”的能力

#### 维护说明

`updated_at` 由应用或 worker 在任务状态迁移、重试计数变更、加锁和完成处理时显式更新；首期不依赖数据库触发器自动维护。

## 7. JDBC Catalog 兼容视图

### 7.1 `iceberg_tables`

```sql
CREATE OR REPLACE VIEW iceberg_catalog.iceberg_tables AS
SELECT
    'default'::TEXT AS catalog_name,
    namespace AS table_namespace,
    table_name,
    metadata_location,
    previous_metadata_location
FROM iceberg_catalog.tables;
```

| 输出列 | 来源 | 说明 |
| --- | --- | --- |
| `catalog_name` | 常量 `'default'` | 单 Catalog 兼容输出 |
| `table_namespace` | `tables.namespace` | Namespace |
| `table_name` | `tables.table_name` | 表名 |
| `metadata_location` | `tables.metadata_location` | 当前 metadata 文件 |
| `previous_metadata_location` | `tables.previous_metadata_location` | 上一版 metadata 文件 |

### 7.2 `iceberg_namespace_properties`

```sql
CREATE OR REPLACE VIEW iceberg_catalog.iceberg_namespace_properties AS
SELECT
    'default'::TEXT AS catalog_name,
    namespace,
    key AS property_key,
    value AS property_value
FROM iceberg_catalog.namespaces,
     LATERAL jsonb_each_text(properties) AS props(key, value);
```

| 输出列 | 来源 | 说明 |
| --- | --- | --- |
| `catalog_name` | 常量 `'default'` | 单 Catalog 兼容输出 |
| `namespace` | `namespaces.namespace` | Namespace |
| `property_key` | `jsonb_each_text(properties).key` | 属性键 |
| `property_value` | `jsonb_each_text(properties).value` | 属性值 |

## 8. 核心操作流程

本章按表生命周期顺序说明主要流程，重点关注对象存储写入、数据库事务更新、缓存刷新和异步清理之间的边界。

### 8.1 `create_table`

1. 校验 Namespace
2. 事务外生成初始 `metadata.json`
3. 解析 Schema、Spec、Sort Order、Snapshot
4. 事务内写入 `tables`、缓存表、日志表
5. 需要时绑定本地对象

### 8.2 `register_table`

1. 输入现有 `metadata_location`
2. 读取并校验 `metadata.json`
3. 解析 `table_uuid`、Schema、Spec、Sort Order、Snapshot、Metadata 历史
4. 事务内写入 `tables`、缓存表和日志表

关键要求：

```text
register_table 必须复用 metadata.json 中已有的 table_uuid
```

### 8.3 `load_table`

1. 查询 `tables`
2. 获取当前 `metadata_location` 与摘要字段
3. 优先从缓存表读取 Schema、Spec、Snapshot
4. 缓存缺失或校验失败时回退读取 `metadata.json`

### 8.4 `commit_table`

1. 读取当前 `metadata_location = L1`
2. 事务外生成新 metadata 文件 `L2`
3. 解析新 metadata
4. 事务内基于 `L1` 执行 CAS 更新
5. 成功后刷新摘要字段与缓存表
6. 写入操作日志
7. 失败时写入异步清理任务

核心 SQL：

```sql
UPDATE iceberg_catalog.tables
SET metadata_location = :new_metadata_location,
    previous_metadata_location = :expected_metadata_location,
    current_schema_id = :current_schema_id,
    current_snapshot_id = :current_snapshot_id,
    default_spec_id = :default_spec_id,
    default_sort_order_id = :default_sort_order_id,
    last_sequence_number = :last_sequence_number,
    last_updated_ms = :last_updated_ms,
    metadata_version = metadata_version + 1,
    updated_at = now()
WHERE namespace = :namespace
  AND table_name = :table_name
  AND metadata_location = :expected_metadata_location;
```

### 8.5 `alter_table`

1. 读取当前 metadata
2. 事务外生成新 metadata
3. 事务内执行与 `commit_table` 相同的 CAS 与缓存刷新
4. 需要时同步本地对象结构

### 8.6 `flush_table`

1. 从 `tables` 读取当前摘要
2. 从 `partition_specs` 获取当前分区规则
3. 事务外写入 Parquet / manifest / metadata
4. 事务内执行 CAS 提交
5. 写入新的 `snapshots`、`metadata_log`、`table_operation_log`
6. 失败时写入 `purge_queue`

### 8.7 `drop_table`

当 `purge = false` 时：

1. 删除 Catalog 记录
2. 删除本地对象绑定
3. 保留对象存储文件

当 `purge = true` 时：

1. 读取当前 `tables`、`snapshots`、`metadata_log` 等记录，预先构造 `purge_payload`
2. 写入异步清理任务
3. 删除 Catalog 记录
4. 删除本地对象绑定

关键要求：

```text
drop purge 必须在删除 Catalog 记录前收集清理所需信息；不能依赖 CASCADE 删除后的残留数据再构造 purge_payload
```

## 9. 一致性与运维策略

本章回答三个问题：

1. 数据库内部哪些对象需要在同一事务内保持一致
2. 数据库与对象存储之间如何保证最终一致
3. 缓存修复、历史保留和日常运维采用什么策略

### 9.1 数据库内部一致性

以下对象必须在同一事务内保持一致：

```text
tables.metadata_location
tables 当前摘要字段
table_schemas
partition_specs
sort_orders
snapshots
metadata_log
table_operation_log
```

### 9.2 数据库与对象存储一致性

不做分布式事务，采用：

```text
对象存储先写入
数据库 CAS 提交
失败后异步清理
```

### 9.3 缓存修复

当缓存与 `metadata.json` 不一致时：

1. 读取 `tables.metadata_location`
2. 重新加载 `metadata.json`
3. 重新解析 Schema、Spec、Sort Order、Snapshot、Metadata 历史
4. 事务内刷新缓存表
5. 写入 `table_operation_log`

### 9.4 容量控制

建议默认策略：

1. `snapshots`：每表保留最近 100 条，或最近 100 条 + 最近 30 天
2. `metadata_log`：保留最近 100 条或最近 90 天
3. `table_operation_log`：默认保留 180 天，关键表可保留 1 年

## 10. 可执行 DDL

以下 DDL 以 PostgreSQL 空库首次初始化为前提，可按顺序直接执行。兼容视图默认创建在 `iceberg_catalog` schema 下；如需暴露到 `public` 或 `pg_catalog`，应通过独立部署脚本处理。

```sql
CREATE SCHEMA IF NOT EXISTS iceberg_catalog;

CREATE TABLE iceberg_catalog.namespaces (
    namespace       TEXT PRIMARY KEY,
    properties      JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE iceberg_catalog.tables (
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

CREATE TABLE iceberg_catalog.table_schemas (
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

CREATE TABLE iceberg_catalog.partition_specs (
    table_uuid      UUID NOT NULL,
    spec_id         INT NOT NULL,
    spec_json       JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_uuid, spec_id),
    FOREIGN KEY (table_uuid)
        REFERENCES iceberg_catalog.tables(table_uuid)
        ON DELETE CASCADE
);

CREATE TABLE iceberg_catalog.sort_orders (
    table_uuid      UUID NOT NULL,
    order_id        INT NOT NULL,
    order_json      JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_uuid, order_id),
    FOREIGN KEY (table_uuid)
        REFERENCES iceberg_catalog.tables(table_uuid)
        ON DELETE CASCADE
);

CREATE TABLE iceberg_catalog.snapshots (
    table_uuid          UUID NOT NULL,
    snapshot_id         BIGINT NOT NULL,
    parent_snapshot_id  BIGINT,
    sequence_number     BIGINT,
    timestamp_ms        BIGINT NOT NULL,
    manifest_list       TEXT NOT NULL,
    operation           TEXT,
    summary             JSONB,
    is_current          BOOLEAN NOT NULL DEFAULT false,
    cached_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_uuid, snapshot_id),
    FOREIGN KEY (table_uuid)
        REFERENCES iceberg_catalog.tables(table_uuid)
        ON DELETE CASCADE
);

CREATE TABLE iceberg_catalog.table_operation_log (
    id                      BIGSERIAL PRIMARY KEY,
    namespace               TEXT NOT NULL,
    table_name              TEXT NOT NULL,
    table_uuid              UUID,
    operation_type          TEXT NOT NULL,
    operation_status        TEXT NOT NULL,
    old_metadata_location   TEXT,
    new_metadata_location   TEXT,
    snapshot_id             BIGINT,
    parent_snapshot_id      BIGINT,
    request_id              TEXT,
    operator_name           TEXT,
    client_type             TEXT,
    error_code              TEXT,
    error_message           TEXT,
    operation_detail        JSONB NOT NULL DEFAULT '{}',
    started_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at             TIMESTAMPTZ,
    CHECK (operation_status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE TABLE iceberg_catalog.metadata_log (
    table_uuid          UUID NOT NULL,
    metadata_file       TEXT NOT NULL,
    timestamp_ms        BIGINT NOT NULL,
    source_operation_id BIGINT,
    is_current          BOOLEAN NOT NULL DEFAULT false,
    cached_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_uuid, metadata_file),
    FOREIGN KEY (table_uuid)
        REFERENCES iceberg_catalog.tables(table_uuid)
        ON DELETE CASCADE
);

CREATE TABLE iceberg_catalog.purge_queue (
    id                  BIGSERIAL PRIMARY KEY,
    namespace           TEXT NOT NULL,
    table_name          TEXT NOT NULL,
    table_uuid          UUID,
    metadata_location   TEXT,
    purge_payload       JSONB NOT NULL DEFAULT '{}',
    purge_type          TEXT NOT NULL DEFAULT 'DROP_TABLE',
    status              TEXT NOT NULL DEFAULT 'PENDING',
    retry_count         INT NOT NULL DEFAULT 0,
    max_retry_count     INT NOT NULL DEFAULT 3,
    last_error          TEXT,
    worker_id           TEXT,
    locked_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at        TIMESTAMPTZ,
    CHECK (
        metadata_location IS NOT NULL
        OR purge_payload <> '{}'::JSONB
    ),
    CHECK (purge_type IN ('DROP_TABLE', 'ORPHAN_CLEANUP')),
    CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);

ALTER TABLE iceberg_catalog.tables
ADD CONSTRAINT fk_tables_current_schema
FOREIGN KEY (table_uuid, current_schema_id)
REFERENCES iceberg_catalog.table_schemas(table_uuid, schema_id)
DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE iceberg_catalog.tables
ADD CONSTRAINT fk_tables_default_spec
FOREIGN KEY (table_uuid, default_spec_id)
REFERENCES iceberg_catalog.partition_specs(table_uuid, spec_id)
DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE iceberg_catalog.tables
ADD CONSTRAINT fk_tables_default_sort_order
FOREIGN KEY (table_uuid, default_sort_order_id)
REFERENCES iceberg_catalog.sort_orders(table_uuid, order_id)
DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE iceberg_catalog.tables
ADD CONSTRAINT fk_tables_current_snapshot
FOREIGN KEY (table_uuid, current_snapshot_id)
REFERENCES iceberg_catalog.snapshots(table_uuid, snapshot_id)
DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE iceberg_catalog.metadata_log
ADD CONSTRAINT fk_metadata_log_source_operation
FOREIGN KEY (source_operation_id)
REFERENCES iceberg_catalog.table_operation_log(id)
ON DELETE SET NULL
DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX uq_snapshots_current
ON iceberg_catalog.snapshots(table_uuid)
WHERE is_current = true;

CREATE UNIQUE INDEX uq_metadata_log_current
ON iceberg_catalog.metadata_log(table_uuid)
WHERE is_current = true;

CREATE INDEX idx_tables_namespace
ON iceberg_catalog.tables(namespace);

CREATE INDEX idx_tables_uuid
ON iceberg_catalog.tables(table_uuid);

CREATE INDEX idx_snapshots_table_time
ON iceberg_catalog.snapshots(table_uuid, timestamp_ms DESC);

CREATE INDEX idx_metadata_log_table_time
ON iceberg_catalog.metadata_log(table_uuid, timestamp_ms DESC);

CREATE INDEX idx_table_operation_log_table_time
ON iceberg_catalog.table_operation_log(table_uuid, started_at DESC);

CREATE INDEX idx_table_operation_log_request
ON iceberg_catalog.table_operation_log(request_id);

CREATE INDEX idx_purge_queue_status
ON iceberg_catalog.purge_queue(status, created_at);

CREATE INDEX idx_purge_queue_table_uuid
ON iceberg_catalog.purge_queue(table_uuid);

CREATE OR REPLACE VIEW iceberg_catalog.iceberg_tables AS
SELECT
    'default'::TEXT AS catalog_name,
    namespace AS table_namespace,
    table_name,
    metadata_location,
    previous_metadata_location
FROM iceberg_catalog.tables;

CREATE OR REPLACE VIEW iceberg_catalog.iceberg_namespace_properties AS
SELECT
    'default'::TEXT AS catalog_name,
    namespace,
    key AS property_key,
    value AS property_value
FROM iceberg_catalog.namespaces,
     LATERAL jsonb_each_text(properties) AS props(key, value);
```

## 11. Extension 安装与升级设计

第 10 章解决“如何初始化这套对象”，本章解决“作为 PostgreSQL extension 如何安装、升级和商用发布”。

### 11.1 基本定位

这些对象不是 PostgreSQL 系统表，而是：

```text
extension 管理的普通业务元信息对象
```

因此：

1. `iceberg_catalog` 不是系统 schema
2. `iceberg_catalog.namespaces` 等对象不是系统表
3. 如果数据库中已存在同名对象，extension 安装或升级可能失败

### 11.2 首期安装策略

首期建议：

1. `iceberg_catalog` 视为 extension 保留 schema
2. 安装前目标数据库中不应存在同名核心对象
3. 首期不支持自动接管既有 `iceberg_catalog.*` 对象
4. 若存在同名对象，应终止安装并人工迁移或清理

### 11.3 版本模型

区分两类状态：

1. extension 版本：通过 extension 升级脚本管理
2. 回填状态：如有必要，可通过轻量状态表记录异步回填进度

### 11.4 升级脚本组织方式

采用 PostgreSQL extension 标准升级机制：

1. 首次安装脚本：`plugin_name--1.0.sql`
2. 升级脚本：`plugin_name--1.0--1.1.sql`
3. 升级命令：`ALTER EXTENSION plugin_name UPDATE TO '1.1';`

要求：

1. 每个脚本只覆盖一个版本跃迁
2. 升级脚本只做结构变更和轻量初始化
3. 大规模回填通过升级后异步任务完成

### 11.5 向后兼容原则

推荐顺序：

1. 先加新列、新表、新索引、新视图
2. 新代码兼容新旧结构
3. 异步回填历史数据
4. 验证稳定后再收紧约束或清理旧兼容逻辑

不建议在小版本升级中直接执行：

1. 删除核心列
2. 重命名兼容视图输出列
3. 在未完成数据清洗前增加严格非空约束

### 11.6 结构升级与数据回填分离

结构升级负责：

1. 新列
2. 新表
3. 新索引
4. 新约束
5. 新视图

异步回填负责：

1. 初始化 `is_current`
2. 回填 `table_location`
3. 补充 `purge_payload`
4. 修复历史缓存

### 11.7 商用升级策略

推荐采用：

```text
forward-fix first
```

即：

1. 不默认承诺自动 schema 回滚
2. 优先通过向后兼容 DDL、代码灰度和异步回填保证新版本可修复
3. 破坏性变更必须在维护窗口、备份完成、数据校验完成后执行

#### 升级前检查

至少检查：

1. 当前 extension 版本
2. `is_current` 是否存在脏数据
3. `tables.current_*` 是否存在悬空指针
4. `purge_queue` 是否存在严重积压
5. 是否存在未完成回填任务
6. 备份或快照是否已完成

#### 失败分级处理

1. **DDL 事务内失败**：依赖 PostgreSQL 事务回滚，保留旧版本继续运行
2. **DDL 成功但回填失败**：不回滚 schema，记录失败状态并重试回填
3. **升级后运行时失败**：优先回退应用代码或兼容读逻辑，通过前向修复恢复

#### 商用可接受基线

1. 升级前可备份
2. 升级中可检测
3. 升级后可验收
4. 失败后可前向修复
5. 破坏性变更有人工回退预案

## 12. 结论

### 12.1 方案收益

本方案在单 Catalog 场景下，以有限复杂度补齐了以下能力：

1. 当前元数据指针管理
2. 高频元数据缓存
3. Snapshot 查询加速
4. Metadata 历史追踪
5. 审计与排障
6. 异步清理
7. JDBC Catalog 兼容
8. extension 方式安装与升级

### 12.2 主要风险与应对

| 风险 | 应对 |
| --- | --- |
| `snapshots` 表膨胀 | 按数量或时间窗口裁剪 |
| 缓存不一致 | 以 `metadata.json` 为准并提供缓存修复 |
| 并发提交冲突 | `metadata_location` CAS |
| 对象存储孤儿文件 | 异步清理 |
| 升级失败 | 采用向后兼容 DDL + forward-fix first |
| 命名冲突 | `iceberg_catalog` 作为 extension 预留 schema |

### 12.3 推荐结论

建议首期采用本文定义的元信息表设计，并将其作为 PostgreSQL extension 管理的业务元信息 schema 落地。

首期建议实现：

```text
namespaces
tables
table_schemas
partition_specs
sort_orders
snapshots
metadata_log
table_operation_log
purge_queue
iceberg_catalog.iceberg_tables
iceberg_catalog.iceberg_namespace_properties
```

方案定位为：

```text
在单 Catalog 场景下，以最小商用复杂度满足 Iceberg Catalog 的正确性、性能、审计、恢复、清理、兼容和 extension 升级要求。
```
