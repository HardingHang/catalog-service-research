# Open Source Catalog Backend Data Model Current State

本文整理 Unity Catalog OSS、Apache Gravitino、Apache Polaris 三个开源 catalog 项目的后端存储 data model 现状和实现方式。

重点不是 API 层的对象定义，而是这些对象最终在后端如何落库、如何组织关系、如何处理扩展字段、版本、权限和多格式/多 provider 差异。

详细字段级表清单见：

- `Industry/data-model/catalog-backend-storage-fields.md`

## 1. 总体对比

| 项目 | 后端存储模型风格 | 核心特点 | 适合借鉴的点 |
| --- | --- | --- | --- |
| Unity Catalog OSS | 按资源类型拆表 | Catalog/Schema/Table/Column/Volume/Function/Model 各有明确 DAO 表，属性用通用 KV 表扩展 | 简单清晰，适合多资产目录第一版 |
| Apache Gravitino | 元数据主表 + version 表 + provider 联邦 | `metalake -> catalog -> schema -> object`，catalog 绑定 provider，表/文件集/topic/model 等采用身份表和版本表拆分 | 适合多源联邦、多 provider、元数据演进 |
| Apache Polaris | 统一实体表 + 类型系统 + 关系表 | catalog、namespace、table-like、principal、role、policy 都存在 `entities`，通过 type/subtype 区分 | 适合 Iceberg REST catalog、统一权限、policy 建模 |

三者的根本差异：

```text
Unity Catalog:
  resource-specific tables
  -> uc_catalogs / uc_schemas / uc_tables / uc_columns / ...

Gravitino:
  identity table + version table
  -> table_meta / table_version / table_column
  -> provider adapter connects to Hive/Iceberg/JDBC/etc.

Polaris:
  generic entity table
  -> entities(type_code, sub_type_code, properties, internal_properties)
  -> grants/policy/auth separated as relation tables
```

## 2. Unity Catalog OSS

### 2.1 存储模型现状

Unity Catalog OSS 的后端存储是典型的“资源对象表”模型。每类核心对象都有独立 DAO 表：

```text
uc_catalogs
  -> uc_schemas
      -> uc_tables
          -> uc_columns
      -> uc_volumes
      -> uc_functions
          -> uc_function_params
      -> uc_registered_models
          -> uc_model_versions

uc_properties
uc_storage_credentials
uc_external_locations
uc_permissions
uc_staging_tables
uc_delta_commits
```

对象层级是明确外键关系：

- `uc_schemas.catalog_id -> uc_catalogs.id`
- `uc_tables.schema_id -> uc_schemas.id`
- `uc_columns.table_id -> uc_tables.id`
- `uc_volumes.schema_id -> uc_schemas.id`
- `uc_functions.schema_id -> uc_schemas.id`
- `uc_registered_models.schema_id -> uc_schemas.id`
- `uc_model_versions.registered_model_id -> uc_registered_models.id`

### 2.2 表对象如何落库

表主记录在 `uc_tables`：

| 字段类别 | 代表字段 | 说明 |
| --- | --- | --- |
| 身份 | `id`, `name`, `schema_id` | 表 ID、表名、所属 schema |
| 类型 | `type`, `data_source_format` | MANAGED/EXTERNAL，DELTA/PARQUET/CSV 等 |
| 存储位置 | `url` | 表根路径或外部路径 |
| 字段摘要 | `column_count` | 字段数量缓存 |
| UniForm/Iceberg | `uniform_iceberg_metadata_location`, `uniform_iceberg_converted_delta_version`, `uniform_iceberg_converted_delta_timestamp` | Iceberg 兼容元数据入口 |
| 审计 | `owner`, `created_at`, `created_by`, `updated_at`, `updated_by` | 管理信息 |

字段存在 `uc_columns`，每列一行，保留：

- `ordinal_position`
- `type_text`
- `type_json`
- `type_name`
- precision/scale/interval
- `nullable`
- `partition_index`

扩展属性不直接塞进 `uc_tables`，而是进入 `uc_properties`：

```text
entity_id
entity_type
property_key
property_value
```

这让任意对象都可以挂 KV 属性。

### 2.3 多格式如何处理

Unity Catalog 并不把 Delta/Iceberg/Hudi 的完整事务元数据复制进 catalog DB。

典型模式：

```text
UC DB:
  table id/name/schema/type/format/location/columns/properties

Object Storage:
  Delta _delta_log
  Iceberg metadata.json / manifests
  Parquet/CSV/JSON data files
```

对于 Delta 表：

- UC 保存 `data_source_format=DELTA` 和 `url`。
- Delta log 仍在对象存储。
- managed Delta 建表会经过 `uc_staging_tables` 辅助提交。
- `uc_delta_commits` 保存 UC Delta REST/managed Delta 所需的 commit 辅助状态。

对于 Iceberg 兼容：

- UC 表记录中保留 `uniform_iceberg_metadata_location`。
- Iceberg 客户端通过 Iceberg REST API 拿到 metadata location。
- 真正 snapshot/manifest 语义仍由 Iceberg metadata 文件解释。

### 2.4 权限与凭据

Unity Catalog 使用独立控制面表管理权限和存储访问：

- `uc_permissions`：principal 对 securable object 的 privilege。
- `uc_storage_credentials`：存储凭据。
- `uc_external_locations`：外部路径和 credential 绑定。

也就是说，数据对象和访问控制不是存在同一张表里，而是分开建模。

### 2.5 设计评价

优点：

- 表结构直观，容易理解和查询。
- 多资产支持自然：Table、Volume、Function、Model 都是一等对象。
- `uc_properties` 提供扩展能力。
- 和 REST/OpenAPI 模型映射简单。

不足：

- 版本化元数据能力较弱。
- provider 联邦能力不如 Gravitino。
- 多对象统一权限和 policy 的抽象不如 Polaris 紧凑。

适合场景：

- 自研 catalog 的第一阶段。
- 目标是多资产目录，而不是复杂联邦 metastore。
- 希望 DB schema 清楚、运维和调试简单。

## 3. Apache Gravitino

### 3.1 存储模型现状

Gravitino 的顶层不是 catalog，而是 `metalake`。

```text
metalake_meta
  -> catalog_meta
      -> schema_meta
          -> table_meta
              -> table_version
              -> table_column
          -> fileset_meta / fileset_version
          -> topic_meta / topic_version
          -> model_meta / model_version
          -> function_meta / function_version
```

同时有治理和安全对象：

```text
tag_meta
tag_metadata_object_rel
policy_meta
policy_metadata_object_rel
role_meta
user_meta
group_meta
user_role_rel
group_role_rel
securable_object
owner_meta
statistic_meta
credential_meta
```

### 3.2 Catalog 与 provider

Gravitino 的 `catalog_meta` 比 UC 的 catalog 更“重”，因为它直接决定后续对象由哪个 provider 管理：

```text
catalog_meta:
  catalog_id
  catalog_name
  metalake_id
  type
  provider
  properties
```

典型 provider：

- `hive`
- `lakehouse-iceberg`
- `lakehouse-paimon`
- `lakehouse-hudi`
- `jdbc-mysql`
- `jdbc-postgresql`
- `jdbc-doris`
- `jdbc-starrocks`
- `lakehouse-generic`

这意味着 Gravitino 的后端 DB 保存的是统一控制面元数据，而底层真实表元数据可能仍在 Hive Metastore、Iceberg catalog、JDBC database 或对象存储里。

### 3.3 表对象如何落库

Gravitino 的表不是单表记录，而是拆成三层：

```text
table_meta
  表身份、命名空间、当前版本指针

table_version
  某一版本的表属性、格式、分区、排序、分布、索引、comment

table_column
  某一表版本下的列定义和列变更
```

`table_meta` 代表“这张表是谁”：

| 字段类别 | 代表字段 | 说明 |
| --- | --- | --- |
| 身份 | `table_id`, `table_name` | 表 ID 和名称 |
| 归属 | `metalake_id`, `catalog_id`, `schema_id` | 命名空间路径 |
| 版本指针 | `current_version`, `last_version` | 当前版本和最后版本 |
| 生命周期 | `deleted_at` | 软删除时间 |
| 审计 | `audit_info` | JSON 审计信息 |

`table_version` 代表“这个版本的表长什么样”：

| 字段类别 | 代表字段 |
| --- | --- |
| 版本 | `table_id`, `version`, `last_version` |
| 格式 | `format` |
| 扩展属性 | `properties` |
| 结构能力 | `partitioning`, `distribution`, `sort_orders`, `indexes` |
| 描述 | `comment` |
| 生命周期 | `deleted_at` |

`table_column` 代表“列级版本变化”：

| 字段类别 | 代表字段 |
| --- | --- |
| 身份 | `id`, `column_id`, `column_name` |
| 归属 | `table_id`, `schema_id`, `catalog_id`, `metalake_id` |
| 版本 | `table_version` |
| 类型 | `column_type` |
| 位置 | `column_position` |
| 属性 | `nullable`, `auto_increment`, `default_value`, `column_comment` |
| 操作 | `column_op_type` |
| 生命周期 | `deleted_at` |

### 3.4 写入和更新路径

创建表时：

```text
createTable
  -> resolve metalake/catalog/schema
  -> insert table_meta
  -> insert table_version(version=1)
  -> insert table_column(version=1)
  -> call provider-specific backend if needed
```

更新表时：

```text
alterTable
  -> load old table_meta/current_version
  -> compare table properties/columns
  -> update table_meta current_version/last_version
  -> insert new table_version if table-level metadata changed
  -> insert/update/delete table_column rows if columns changed
```

删除表时：

```text
dropTable
  -> soft delete table_meta
  -> soft delete related version/column records
  -> provider decides whether/how to drop underlying physical metadata/data
```

### 3.5 非表资产

Gravitino 对 fileset/topic/model/function 等对象也采用类似思路：

```text
*_meta:
  identity + namespace + current_version

*_version:
  mutable content + properties + comment/location/etc.
```

这说明 Gravitino 的通用策略是：

- identity 和 lifecycle 单独存。
- mutable metadata 版本化存。
- provider-specific 或结构化字段通过 JSON 保存。

### 3.6 权限、标签、策略

Gravitino 将治理对象作为一等元数据：

- `tag_meta` 保存 tag 定义。
- `tag_metadata_object_rel` 保存 tag 和对象的绑定。
- `policy_meta` 保存 policy 定义。
- `policy_metadata_object_rel` 保存 policy 和对象的绑定。
- `role_meta`、`user_meta`、`group_meta`、`user_role_rel`、`group_role_rel` 支撑 RBAC。
- `securable_object` 抽象可授权对象。
- `owner_meta` 单独保存 owner 关系。

### 3.7 设计评价

优点：

- 适合复杂 provider 联邦。
- 表元数据演进能力强。
- 版本化模型适合 schema evolution、列变更、审计。
- 可以统一管理 table/fileset/topic/model/function/tag/policy。

不足：

- DB schema 复杂。
- 查询一张完整表需要 join `table_meta/table_version/table_column`。
- 需要严格处理 current version、last version、soft delete 一致性。
- 统一模型与 provider 原生模型之间需要大量 adapter。

适合场景：

- 多 catalog、多 provider、多云/多源联邦。
- 需要 metadata lake，而不是单一 Iceberg catalog。
- 需要将治理对象、标签、策略、权限纳入同一元数据体系。

## 4. Apache Polaris

### 4.1 存储模型现状

Polaris 的物理表数量较少，核心是 `entities`。

```text
entities
grant_records
principal_authentication_data
policy_mapping_record
events
idempotency_records
scan_metrics_report
scan_metrics_report_roles
commit_metrics_report
commit_metrics_report_roles
version
```

几乎所有核心业务对象都存在 `entities`：

- catalog
- namespace
- table-like
- Iceberg table
- Iceberg view
- generic table
- principal
- principal role
- catalog role
- policy
- task
- file

### 4.2 `entities` 统一实体表

`entities` 是 Polaris 最关键的表：

| 字段类别 | 代表字段 | 说明 |
| --- | --- | --- |
| 租户隔离 | `realm_id` | realm/multi-tenancy 分区 |
| 身份 | `catalog_id`, `id`, `name` | catalog scope 下的 entity id 和 name |
| 层级 | `parent_id` | 父实体 |
| 类型 | `type_code`, `sub_type_code` | entity 大类和子类 |
| 版本 | `entity_version` | 实体版本 |
| 生命周期 | `create_timestamp`, `drop_timestamp`, `purge_timestamp`, `to_purge_timestamp`, `last_update_timestamp` | 生命周期时间 |
| 扩展属性 | `properties`, `internal_properties` | JSON 属性 |
| 权限版本 | `grant_records_version` | 授权变更版本 |
| 位置索引 | `location_without_scheme` | location 查询优化 |

主键：

```text
(realm_id, id)
```

路径唯一约束：

```text
(realm_id, catalog_id, parent_id, type_code, name)
```

这说明 Polaris 的对象路径不是靠字符串 full name 直接存，而是靠 parent-child entity tree。

### 4.3 类型系统

`type_code` 表示大类：

```text
ROOT
PRINCIPAL
PRINCIPAL_ROLE
CATALOG
CATALOG_ROLE
NAMESPACE
TABLE_LIKE
TASK
FILE
POLICY
```

`sub_type_code` 表示细分类型，例如：

```text
TABLE_LIKE:
  ICEBERG_TABLE
  ICEBERG_VIEW
  GENERIC_TABLE
```

因此表和视图并没有独立物理表，而是：

```text
entities.type_code = TABLE_LIKE
entities.sub_type_code = ICEBERG_TABLE / ICEBERG_VIEW / GENERIC_TABLE
```

Iceberg metadata location、base location、storage config 等存入 `properties` 或 `internal_properties`。

### 4.4 权限模型

Polaris 的权限关系存在 `grant_records`：

```text
realm_id
securable_catalog_id
securable_id
grantee_catalog_id
grantee_id
privilege_code
```

这张表表达：

```text
grantee entity
  has privilege
on securable entity
```

grantee 可以是：

- principal
- principal role
- catalog role

securable 可以是：

- catalog
- namespace
- table-like
- policy

Polaris 的 RBAC 是双层结构：

```text
Principal
  -> PrincipalRole
      -> CatalogRole
          -> Privilege on Catalog/Namespace/Table/View/Policy
```

这种设计把“平台级身份聚合”和“catalog 内权限边界”分开。

### 4.5 Policy 与认证

Policy 不嵌入 table 或 namespace 记录，而是：

```text
policy entity:
  entities.type_code = POLICY

binding:
  policy_mapping_record
```

`policy_mapping_record` 字段：

```text
realm_id
target_catalog_id
target_id
policy_type_code
policy_catalog_id
policy_id
parameters
```

principal 认证信息不放在 `entities`，而是单独表：

```text
principal_authentication_data:
  realm_id
  principal_id
  principal_client_id
  main_secret_hash
  secondary_secret_hash
  secret_salt
```

### 4.6 事件、幂等和 metrics

Polaris 的 persistence 不只存 catalog 对象，还存控制面运行状态：

- `events`：事件记录。
- `idempotency_records`：REST 幂等控制。
- `scan_metrics_report`：扫描 metrics。
- `commit_metrics_report`：提交 metrics。
- `*_roles`：metrics report 和 role 的关系。

这说明 Polaris 的后端存储已经不只是“元数据目录”，而是 catalog service 的控制面状态库。

### 4.7 设计评价

优点：

- 所有对象统一 entity tree，扩展类型成本低。
- 权限关系和 policy 关系非常通用。
- 非常适合 Iceberg REST catalog 的 namespace/table/view 模型。
- `grant_records_version` 有利于权限缓存和并发控制。

不足：

- 对关系型数据库查询不如资源专表直观。
- 业务字段大量在 JSON 中，结构化查询弱。
- 不适合天然表达 UC 那种宽多资产模型，除非继续扩展 entity subtype。
- 对非 Iceberg 格式主要是 generic table，不是完整原生元数据管理。

适合场景：

- Iceberg REST catalog。
- 希望 catalog、namespace、table、role、policy 都走统一实体树。
- 权限、credential vending、幂等、事件等控制面能力是重点。

## 5. 三种设计路线的取舍

### 5.1 按对象拆表

代表：Unity Catalog。

```text
catalogs
schemas
tables
columns
volumes
functions
models
```

适合：

- 对象类型明确。
- 希望查询简单。
- 早期快速落地。
- 面向业务/治理人员排障。

不适合：

- 元数据版本特别复杂。
- 多 provider 差异特别大。
- 希望任意对象都用同一套 entity service 管理。

### 5.2 身份表 + 版本表

代表：Gravitino。

```text
table_meta
table_version
table_column
```

适合：

- 元数据演进频繁。
- 要支持 schema evolution。
- 要保存历史版本或至少保存版本化变更结构。
- 要联邦多 provider。

不适合：

- 第一阶段只想做简单 catalog。
- 团队不想维护复杂 join 和版本一致性。

### 5.3 统一实体表

代表：Polaris。

```text
entities(type_code, sub_type_code, properties)
grant_records
policy_mapping_record
```

适合：

- 对象层级统一。
- 权限和 policy 抽象优先。
- 多对象类型共享生命周期、授权、缓存逻辑。
- Iceberg catalog 场景。

不适合：

- 需要大量结构化查询每类资产专属字段。
- 非 Iceberg 多资产语义特别丰富。

## 6. 对自研 Catalog Service 的建议

如果目标是自研 lakehouse/data/AI catalog，建议不要直接照搬某一个项目，而是组合借鉴。

### 6.1 推荐第一阶段模型

```text
catalog
schema
asset
table_metadata
table_version
table_column_version
property
credential
external_location
principal
role
grant
policy
policy_binding
audit_log
```

### 6.2 设计原则

1. Catalog/Schema/Table 基础对象可以先用 UC 风格，清晰可查。
2. Table metadata 建议预留 Gravitino 风格的 version 表，不要把所有表结构都覆盖更新到一行。
3. 权限关系建议参考 Polaris，使用独立 grant 表，不要把权限塞进对象 JSON。
4. Policy 也建议对象化，使用 binding 表绑定到 catalog/schema/table/model 等对象。
5. 扩展属性可以用两层：
   - 强语义字段进主表或版本表。
   - 弱语义字段进 `properties` 或 `property` 表。
6. 不要把 Delta log、Iceberg manifest、Paimon snapshot 等格式原生元数据完整复制进 catalog DB。catalog DB 应保存入口指针、治理属性和控制面状态。

### 6.3 推荐表结构草案

```text
catalog
  id, name, type, provider, storage_root, properties, audit

schema
  id, catalog_id, name, storage_location, properties, audit

asset
  id, catalog_id, schema_id, name, asset_type, owner, lifecycle, audit

table_metadata
  asset_id, format, table_type, location, current_version, last_version

table_version
  asset_id, version, schema_json, partitioning_json, sort_json, properties_json, metadata_location

table_column_version
  asset_id, version, column_id, name, type_json, position, nullable, default_value, op_type

property
  entity_id, entity_type, key, value

principal / role / grant
  principal and RBAC model

policy / policy_binding
  policy definition and target binding

credential / external_location
  storage access control
```

这个组合基本对应：

```text
UC:
  clear asset tables

Gravitino:
  table metadata versioning

Polaris:
  grant and policy relationship modeling
```

## 7. 结论

三个项目代表了三条不同路线：

- Unity Catalog：多资产 catalog，后端表清晰，适合统一治理产品。
- Gravitino：联邦 metadata lake，后端版本化和 provider adapter 强，适合多源元数据平台。
- Polaris：Iceberg-first catalog，统一 entity + RBAC + policy 强，适合开放 Iceberg REST catalog 服务。

如果要设计自己的后端 data model，建议用以下判断：

| 你的目标 | 优先借鉴 |
| --- | --- |
| 快速做出可用 catalog | Unity Catalog |
| 做多 provider 联邦 | Gravitino |
| 做 Iceberg REST + 权限 + credential vending | Polaris |
| 做 AI/非表资产 | Unity Catalog + Gravitino |
| 做表结构演进和审计 | Gravitino |
| 做统一授权和 policy | Polaris |

