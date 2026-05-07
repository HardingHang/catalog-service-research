# 开源 Catalog 后端 Data Model 现状分析

本文整理 `Unity Catalog OSS`、`Apache Gravitino`、`Apache Polaris` 三个开源 catalog 项目的后端 data model 设计，重点不是 API 层对象定义，而是这些对象在后端如何落库、如何组织关系、如何处理扩展字段、版本、权限，以及如何表达多格式和多 provider 差异。

详细字段级表清单见：

- `Industry/data-model/catalog-backend-storage-fields.md`

## 1. 如何阅读本文

这篇文档不是逐表抄 schema，而是要回答三个更重要的问题：

1. 这三个开源项目分别代表了哪一类后端 data model 路线。
2. 如果要自研 catalog service，哪些设计选择是关键分歧点。
3. 哪些能力值得借鉴，哪些实现只适合特定系统边界。

因此，阅读时建议重点看下面五个判断维度：

1. 资产对象是“各类型主表”，还是“统一主实体表”。
2. 元数据是“保存当前状态”，还是“把演进过程做成版本模型”。
3. 格式差异是主要放在 `catalog/provider` 层，还是主要放在资产对象层。
4. 权限、policy、credential 是附属能力，还是控制面核心模型。
5. 该项目更适合借鉴“对象模型”“版本化能力”“联邦接入”，还是“控制面关系建模”。

## 2. 执行摘要

如果只看结论，可以先记住下面三句话：

```text
Unity Catalog:
  resource-specific tables
  -> uc_catalogs / uc_schemas / uc_tables / uc_columns / ...

Gravitino:
  identity table + version table + provider boundary
  -> table_meta / table_version / table_column
  -> catalog.provider decides backend family

Polaris:
  generic entity table
  -> entities(type_code, sub_type_code, properties, internal_properties)
  -> grants / policy / auth via relation tables
```

对应的最短理解是：

- `Unity Catalog`：先按资源拆开，强调对象分表和结构清晰。
- `Gravitino`：先按身份和版本拆开，强调版本化和多 provider 联邦。
- `Polaris`：先把对象统一起来，再靠类型系统和关系建模区分，强调控制面能力。

如果换成更适合做方案判断的对比，可以压缩成下面这张表：

| 项目 | 一句话定位 | 资产模型 | 格式隔离 | 最值得借鉴 |
| --- | --- | --- | --- | --- |
| Unity Catalog OSS | 面向多资产目录的强类型分表模型 | 各类型主表 | 主要靠资产对象字段 | 对象层次清晰、查询直观、易排障 |
| Apache Gravitino | 面向联邦和演进的版本化元数据模型 | 各类型主表 + 版本表 | `catalog/provider` 先隔离，再由对象层补充 | provider 边界、schema evolution、版本化能力 |
| Apache Polaris | 面向控制面的统一实体模型 | 统一主实体表 | 主要靠 subtype 和 properties | RBAC/policy/control plane 关系建模 |

如果本文最后只保留两个判断结论，那么就是：

1. 在“资产模型”上，`Unity Catalog` 和 `Gravitino` 更偏各类型主表，`Polaris` 更偏统一主表。
2. 在“格式隔离”上，三者都不是只押单层方案；最稳妥的思路仍然是 provider 边界、资产类型、具体格式三层分工。

## 3. 三条典型路线

这三个项目本质上代表了三条不同的数据建模路线：

### 3.1 路线一：按资源类型拆表

代表项目：`Unity Catalog OSS`

核心思路：

- catalog、schema、table、column、volume、function、model 等对象分别建表
- 强语义字段直接进入对应对象表
- 扩展字段进入共享属性表

这种方案最像传统关系型元数据仓的设计，优点是结构直观、调试容易，缺点是新增对象类型通常要新增物理表。

### 3.2 路线二：身份表 + 版本表

代表项目：`Apache Gravitino`

核心思路：

- 对象身份和对象内容分开存储
- `*_meta` 保存 identity、层级、生命周期、当前版本指针
- `*_version` 保存 comment、properties、format、location 等可变内容
- 对列、参数等细粒度结构再拆明细表

这种方案更适合 schema evolution、多 provider 联邦和元数据审计，但查询和一致性维护成本更高。

### 3.3 路线三：统一实体表 + 类型系统

代表项目：`Apache Polaris`

核心思路：

- catalog、namespace、table-like、principal、role、policy 等统一进入 `entities`
- 用 `type_code` 和 `sub_type_code` 区分对象种类
- 用关系表处理授权、policy 绑定、认证等横切能力

这种方案扩展性强、控制面建模自然，但结构化查询体验弱于强类型拆表方案。

## 4. Unity Catalog OSS：资源分表模型

### 4.1 后端存储模型

Unity Catalog OSS 的后端存储是典型的“按资源类型拆表”模式。每类核心对象都有独立 DAO 表，层级关系也比较直接。

典型结构如下：

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
uc_permissions
uc_storage_credentials
uc_external_locations
uc_staging_tables
uc_delta_commits
```

典型外键关系包括：

- `uc_schemas.catalog_id -> uc_catalogs.id`
- `uc_tables.schema_id -> uc_schemas.id`
- `uc_columns.table_id -> uc_tables.id`
- `uc_volumes.schema_id -> uc_schemas.id`
- `uc_functions.schema_id -> uc_schemas.id`
- `uc_registered_models.schema_id -> uc_schemas.id`
- `uc_model_versions.registered_model_id -> uc_registered_models.id`

### 4.2 资产模型设计

如果只从“资产模型”角度看，Unity Catalog 是明显的“各类型主表”设计。

典型例子包括：

- `uc_tables`
- `uc_volumes`
- `uc_functions`
- `uc_registered_models`

这说明它会针对不同资产类型分别设计主表，把该类型的核心字段直接放进对应表中。通用但弱语义的附加属性，再通过 `uc_properties` 扩展。

因此它的归类应当是：

```text
各类型主表
+ 共享属性扩展表
```

### 4.3 表对象落库方式

表对象主要落在 `uc_tables`，列对象主要落在 `uc_columns`。

`uc_tables` 承担的字段大致分为几类：

- 身份字段：`id`、`name`、`schema_id`
- 类型字段：`type`、`data_source_format`
- 存储定位：`url`
- 结构摘要：`column_count`
- Iceberg 兼容信息：`uniform_iceberg_metadata_location` 等
- 审计字段：`owner`、`created_at`、`updated_at`

`uc_columns` 则保存列级结构，例如：

- `ordinal_position`
- `type_text`
- `type_json`
- `type_name`
- `nullable`
- `partition_index`

### 4.4 格式隔离方式

Unity Catalog 不是在 `catalog` 层先把 Delta、Iceberg、Parquet、CSV 等格式拆开，也不是按格式分别建不同主表。

它的核心模式是：

- 表对象统一落在 `uc_tables`
- 通过表记录中的字段表达具体格式差异

典型字段包括：

- `type`
- `data_source_format`
- `url`
- `uniform_iceberg_metadata_location`

可以理解为：

```text
catalog/schema/table 先统一建模
+ 具体格式由 table 记录中的字段表达
```

所以 Unity Catalog 的格式隔离主要发生在资产对象层，而不是 `catalog` 边界层。

### 4.5 多格式元数据边界

Unity Catalog 并不会把 Delta log、Iceberg manifest 这类格式原生元数据完整复制进 catalog DB。典型方式是：

```text
UC DB:
  保存 table identity、format、location、columns、properties

Object Storage:
  保存 Delta log / Iceberg metadata / data files
```

因此 UC 数据库更多存的是“控制面元数据”和“入口指针”，而不是底层表格式的完整事务日志。

### 4.6 权限与访问控制设计

Unity Catalog 使用独立控制面表来表达权限和存储访问：

- `uc_permissions`
- `uc_storage_credentials`
- `uc_external_locations`

这说明它并没有把权限或 credential 直接揉进表对象主表，而是采用分离建模。

### 4.7 方案评价

优点：

- 表结构清晰，容易理解和排障
- 多资产支持天然，table、volume、function、model 都是一等对象
- `uc_properties` 提供了适度扩展能力
- 与 REST / OpenAPI 模型映射简单

不足：

- 元数据版本化能力较弱
- 多 provider 联邦能力不如 Gravitino
- 统一 RBAC / policy 抽象不如 Polaris 强

更适合：

- 自研 catalog 的第一阶段
- 多资产目录场景
- 希望 DB schema 清晰、易维护、易调试的团队

## 5. Apache Gravitino：版本化联邦模型

### 5.1 后端存储模型

Gravitino 的顶层不是 `catalog`，而是 `metalake`。其对象层级更像：

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

同时，它还有一组治理和安全相关对象：

- `tag_meta`
- `tag_metadata_object_rel`
- `policy_meta`
- `policy_metadata_object_rel`
- `role_meta`
- `user_meta`
- `group_meta`
- `user_role_rel`
- `group_role_rel`
- `securable_object`
- `owner_meta`
- `statistic_meta`
- `credential_meta`

### 5.2 资产模型设计

从资产模型角度看，Gravitino 本质上仍然是“各类型主表”，但不是 UC 那种单层强类型主表，而是“身份主表 + 版本表 + 明细表”。

以表对象为例：

```text
table_meta
  + table_version
  + table_column
```

以其他资产为例：

- `fileset_meta + fileset_version`
- `topic_meta + topic_version`
- `model_meta + model_version`
- `function_meta + function_version`

因此它更准确的归类应当是：

```text
各类型主表
+ 版本表
+ 明细表
```

### 5.3 Catalog 层的 Provider 语义

Gravitino 的 `catalog_meta` 比很多系统里的 catalog 更“重”，因为它不仅表示命名空间边界，还明确绑定 provider。

典型字段包括：

- `catalog_id`
- `catalog_name`
- `metalake_id`
- `type`
- `provider`
- `properties`

常见 provider 示例：

- `hive`
- `lakehouse-iceberg`
- `lakehouse-paimon`
- `lakehouse-hudi`
- `jdbc-mysql`
- `jdbc-postgresql`

这意味着 Gravitino 的 catalog 本身就承担了“接入哪类 backend”的职责。

### 5.4 表对象落库方式

Gravitino 的表对象不是一张表解决，而是拆成三层：

`table_meta` 负责：

- identity
- 所属 metalake / catalog / schema
- `current_version`
- `last_version`
- 生命周期和审计信息

`table_version` 负责：

- `format`
- `properties`
- `partitioning`
- `distribution`
- `sort_orders`
- `indexes`
- `comment`
- `version`

`table_column` 负责：

- 列 identity
- 列名、位置、类型
- 是否 nullable
- default value
- 列变更操作类型
- 所属 `table_version`

### 5.5 格式隔离方式

Gravitino 是三者里最明显采用“两层隔离”思路的。

第一层在 `catalog_meta`：

- `provider` 决定对象由哪类 backend 管理

第二层在具体对象，尤其是 `table_version`：

- `format`
- `properties`

因此它的模式更接近：

```text
catalog 层先区分 provider / backend
+ table/version 层继续表达具体格式和元数据差异
```

如果问题是“是否通过上层 catalog 层就明确不同格式边界”，三者中最接近这个做法的是 Gravitino，但它仍然没有把所有格式差异都上推到 catalog 层，而是保留了对象层表达能力。

### 5.6 写入与更新路径

创建表时，典型流程是：

```text
createTable
  -> resolve metalake / catalog / schema
  -> insert table_meta
  -> insert table_version(version=1)
  -> insert table_column(version=1)
  -> call provider-specific backend if needed
```

更新表时，典型流程是：

```text
alterTable
  -> load old table_meta/current_version
  -> compare table metadata and columns
  -> update table_meta current_version/last_version
  -> insert new table_version if table-level metadata changed
  -> insert/update/delete table_column rows if columns changed
```

删除表时，典型流程是：

```text
dropTable
  -> soft delete table_meta
  -> soft delete related version/column records
  -> provider decides whether/how to drop physical metadata/data
```

### 5.7 非表类资产

Gravitino 对 `fileset`、`topic`、`model`、`function` 等非表资产也沿用了类似模式：

- `*_meta` 保存 identity 和当前版本指针
- `*_version` 保存可变内容和扩展属性

这说明其核心策略不是只对 table 特判，而是把“版本化元数据”作为统一设计原则。

### 5.8 治理与安全模型

Gravitino 把治理对象也作为一等元数据对象处理：

- tag
- policy
- role
- user
- group
- owner
- statistic
- credential

它们通过对象关系表和通用 object reference 体系接入主元数据模型。

### 5.9 方案评价

优点：

- 适合复杂 provider 联邦
- schema evolution 和历史版本表达能力强
- 能统一管理 table、fileset、topic、model、function 以及治理对象
- 适合做 metadata lake 或多源控制面

不足：

- DB schema 复杂度高
- 查询完整对象时 join 成本高
- `current_version`、`last_version`、soft delete 一致性维护要求高
- 统一模型和 provider 原生模型之间需要 adapter

更适合：

- 多 catalog、多 provider、多后端联邦平台
- 需要版本化元数据和审计能力的平台
- 希望把治理对象纳入统一元数据体系的场景

## 6. Apache Polaris：统一实体控制面模型

### 6.1 后端存储模型

Polaris 的物理表数量相对较少，核心是 `entities`。

典型表包括：

- `entities`
- `grant_records`
- `principal_authentication_data`
- `policy_mapping_record`
- `events`
- `idempotency_records`
- `scan_metrics_report`
- `scan_metrics_report_roles`
- `commit_metrics_report`
- `commit_metrics_report_roles`
- `version`

### 6.2 资产模型设计

从资产模型角度看，Polaris 是三者里最接近“通用主表”的方案。

大量对象统一落在 `entities`：

- catalog
- namespace
- table-like
- principal
- principal role
- catalog role
- policy
- task
- file

对象类别通过以下字段区分：

- `type_code`
- `sub_type_code`

因此它更接近：

```text
统一主实体表
+ 类型区分字段
+ 关系表
```

### 6.3 `entities` 的层级表达

`entities` 的关键字段大致包括：

- `realm_id`
- `catalog_id`
- `id`
- `parent_id`
- `name`
- `entity_version`
- `type_code`
- `sub_type_code`
- `properties`
- `internal_properties`
- `grant_records_version`
- `location_without_scheme`

主键通常是：

```text
(realm_id, id)
```

路径唯一性则依赖类似：

```text
(realm_id, catalog_id, parent_id, type_code, name)
```

这意味着 Polaris 的对象树主要通过 `parent_id` 表达，而不是靠单独的每类对象表。

### 6.4 格式隔离方式

Polaris 不走“不同格式不同表”的路线，也不强调在 `catalog` 层按格式隔离。

它的方式更接近：

- table / view 等对象统一进 `entities`
- 用 `type_code` / `sub_type_code` 区分对象类型
- 用 `properties` / `internal_properties` 承载更具体的格式信息

例如 table-like 对象可以细分为：

- `ICEBERG_TABLE`
- `ICEBERG_VIEW`
- `GENERIC_TABLE`

因此可以概括为：

```text
统一实体表
+ subtype 区分对象细类
+ properties 承载格式细节
```

### 6.5 权限模型设计

Polaris 的权限关系主要存放在 `grant_records`：

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

这种模式使 Polaris 很适合做统一 RBAC 和控制面权限管理。

### 6.6 Policy 与认证模型

Polaris 中 policy 也是实体对象：

- `entities.type_code = POLICY`

policy 与目标对象的绑定通过 `policy_mapping_record` 表达。

认证信息不放在 `entities`，而是放在：

- `principal_authentication_data`

这说明 Polaris 虽然采用统一实体表，但不会把所有内容都挤进一张表，而是把横切但安全敏感的内容拆到专门表。

### 6.7 事件、幂等与 Metrics

Polaris 的持久化层不仅存元数据，还存控制面运行状态：

- `events`
- `idempotency_records`
- `scan_metrics_report`
- `commit_metrics_report`

因此它更像完整 catalog service 的控制面状态库，而不仅仅是传统元数据仓。

### 6.8 方案评价

优点：

- 所有对象统一 entity tree，扩展新类型成本低
- RBAC、policy、control plane 关系建模自然
- 非常适合 Iceberg REST catalog 体系
- `grant_records_version` 有利于权限缓存和并发控制

不足：

- 对关系型数据库的结构化查询不如强类型分表直观
- 大量业务字段放在 JSON，分析型查询和结构校验较弱
- 对非 Iceberg 资产和多样化对象语义的天然表达能力不如 UC / Gravitino

更适合：

- Iceberg REST catalog
- 统一授权、policy、credential vending 是核心诉求的系统
- 控制面能力强于“广义多资产目录”诉求的场景

## 7. 三种方案的横向对比

这一节不再重复每个项目的细节，而是按几个最关键的判断维度横向看三者差异。这样更容易回答“如果我要设计自己的 catalog service，该借哪一类能力”。

### 7.1 资产模型对比：先拆对象，还是先统一对象

这一维度要回答的是：系统到底把 `table`、`model`、`fileset`、`function` 这些对象先拆成独立主表，还是先统一进一个主实体模型。

| 项目 | 核心做法 | 说明 |
| --- | --- | --- |
| Unity Catalog OSS | 各类型主表 | catalog、schema、table、column、volume、function、model 分别建表，扩展属性走共享表 |
| Apache Gravitino | 各类型主表 + 版本表 | 每类资产先有 `*_meta` 主表，再用 `*_version` 和明细表表达可变内容 |
| Apache Polaris | 统一主实体表 | catalog、namespace、table-like、principal、role、policy 等统一进 `entities` |

这说明：

- `Unity Catalog` 和 `Gravitino` 更偏“强类型对象建模”
- `Polaris` 更偏“统一实体建模”

如果更看重 SQL 直观、调试简单、对象语义清晰，前两者更容易落地；如果更看重统一对象体系和控制面抽象，Polaris 更有代表性。

### 7.2 格式隔离对比：差异放在 Catalog 层，还是对象层

这一维度要回答的是：Delta、Iceberg、Paimon、Hudi、JDBC 这类差异，是在 `catalog/provider` 层先划分边界，还是在具体资产对象层表达。

| 项目 | 核心做法 | 说明 |
| --- | --- | --- |
| Unity Catalog OSS | 主要在对象层表达 | 表对象统一落在 `uc_tables`，通过 `type`、`data_source_format` 等字段区分格式 |
| Apache Gravitino | 分两层表达 | `catalog/provider` 先区分 backend 家族，`table_version.format/properties` 再表达对象级差异 |
| Apache Polaris | 主要在统一实体模型中表达 | 通过 `sub_type_code`、`properties`、`internal_properties` 区分 table-like 对象差异 |

这说明：

- 只靠 `catalog` 层隔离格式，不够细
- 只靠一个统一 `type` 字段，也不足以表达复杂语义
- 更稳妥的方式通常是“provider 边界 + 对象级格式字段”两层结合

### 7.3 版本化能力对比：保存当前状态，还是把演进过程建模出来

这一维度要回答的是：系统是否把对象演进过程本身当成一等元数据来存。

| 项目 | 版本化强度 | 核心方式 | 含义 |
| --- | --- | --- | --- |
| Unity Catalog OSS | 较弱 | 主要保存当前状态 | 更像面向当前视图的元数据目录 |
| Apache Gravitino | 很强 | `*_meta + *_version + detail` | 把对象演进、schema 变化、历史内容都纳入模型 |
| Apache Polaris | 中等 | 实体版本字段 + 授权关系版本 | 更强调控制面并发、一致性和授权缓存，而不是完整对象历史 |

这说明：

- `Unity Catalog` 更偏“当前状态目录”
- `Gravitino` 更偏“版本化元数据系统”
- `Polaris` 的版本字段更多服务于控制面状态管理

### 7.4 权限与控制面对比：权限是附属能力，还是核心模型

这一维度要回答的是：权限、policy、认证、事件、指标这些能力，是附加在元数据旁边，还是成为整个系统设计中心。

| 项目 | 权限与控制面特点 | 整体判断 |
| --- | --- | --- |
| Unity Catalog OSS | 独立权限表、credential 表、external location 表 | 权限是重要能力，但仍然从属于资源目录模型 |
| Apache Gravitino | tag、policy、role、owner、statistic、credential 等治理对象都纳入统一体系 | 权限和治理能力与元数据体系联动较强 |
| Apache Polaris | RBAC、policy、auth、events、metrics 一体化最明显 | 控制面能力本身就是模型设计核心 |

这说明：

- `Unity Catalog` 更像“先把资源目录做好，再挂权限能力”
- `Gravitino` 更像“把治理对象和元数据对象一起纳入统一体系”
- `Polaris` 更像“从一开始就按 control plane 去设计模型”

### 7.5 可借鉴能力总结

如果从“自研系统应该借鉴什么”来收敛，三者最值得借鉴的点可以概括为：

| 项目 | 最值得借鉴的地方 |
| --- | --- |
| Unity Catalog OSS | 清晰的对象分层、强类型主表、多资产目录表达 |
| Apache Gravitino | provider 边界、版本化元数据、联邦适配能力 |
| Apache Polaris | 统一 RBAC/policy 建模、控制面关系建模、统一实体树 |

换句话说：

- 想把对象模型做清楚，优先看 `Unity Catalog`
- 想把版本化和多 provider 做强，优先看 `Gravitino`
- 想把授权、policy、control plane 做成核心能力，优先看 `Polaris`

## 8. 对自研 Catalog Service 的设计建议

如果目标是自研一个 lakehouse / data / AI catalog，这三个开源项目更适合作为“能力来源”，而不是直接照搬的模板。更稳妥的做法是先明确设计原则，再落推荐模型。

### 8.1 设计原则

第一阶段建议优先满足以下原则：

- 模型清晰，能让研发和运维快速理解对象关系
- 查询直观，常见问题可以直接用 SQL 排查
- 能承载多资产，而不是只围绕表对象设计
- 为 schema evolution 和元数据审计预留版本化能力
- 权限、policy、credential 独立建模，而不是塞进对象 JSON
- 格式隔离分层表达，而不是压到单一字段或单一层级

### 8.2 推荐的数据模型骨架

一个比较稳妥、也最容易迭代的中间方案可以是：

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

这个模型的核心思想不是“完全统一”，也不是“全部拆散”，而是三层分工：

- `catalog` 层负责 provider / backend 边界
- `asset` 层负责统一对象身份
- 资产明细层负责强语义字段和格式差异

### 8.3 推荐的职责分层

建议按下面方式分层：

`catalog`

- 表达 provider / backend family
- 挂接连接配置、凭据、接入方式
- 承担接入边界，而不是承载所有对象细节

`schema`

- 表达逻辑命名空间
- 维持 catalog 下的层级组织

`asset`

- 承载通用身份字段：`id`、`name`、`schema_id`、`asset_type`、`owner`、`lifecycle`
- 统一表达 table、view、model、fileset、topic 等对象的公共外壳

`table_metadata`

- 承载表对象专属的强语义字段：`format`、`table_type`、`location`、`current_version`

`table_version`

- 承载 schema、partitioning、sort order、metadata location、properties 等可变内容

`property`

- 承载弱语义扩展字段

`grant / policy / policy_binding`

- 独立表达授权和策略关系
- 不建议塞进 `asset.properties` 之类 JSON 字段里

### 8.4 这个方案分别借鉴了什么

这个组合式方案，本质上是在取三者最稳定、最通用的优点：

| 来源 | 借鉴点 |
| --- | --- |
| Unity Catalog OSS | 清晰的对象分层、多资产主表、面向排障的强类型结构 |
| Apache Gravitino | provider 边界、版本化元数据、对象演进能力 |
| Apache Polaris | grant / policy 关系建模、控制面对象独立建模 |

也就是说：

- 用 `Unity Catalog` 的方式把对象层次讲清楚
- 用 `Gravitino` 的方式把可变内容和演进过程建出来
- 用 `Polaris` 的方式把权限和策略从业务对象里拆出来

### 8.5 关于格式隔离的具体建议

如果要回答“格式隔离放哪一层最合理”，更建议采用分层表达，而不是只押一种：

第一层：`catalog` 层做 provider / backend 边界隔离

- Hive catalog
- Iceberg catalog
- Paimon catalog
- JDBC catalog

第二层：`asset` 层做对象类型区分

- `TABLE`
- `VIEW`
- `MODEL`
- `FILESET`
- `TOPIC`

第三层：资产明细层表达具体格式

- `ICEBERG`
- `DELTA`
- `PAIMON`
- `HUDI`
- `PARQUET`

推荐模式可以概括为：

```text
catalog.provider_type
  -> 决定 backend family

asset.asset_type
  -> 决定对象种类

table_metadata.format / asset.subtype
  -> 决定具体表格式
```

这个分层比“所有格式都压到 catalog 层”或“所有格式都压到一个 `type` 字段”都更稳妥，因为它同时保留了：

- provider 级边界
- 对象级语义
- 格式级细节

## 9. 最终判断与建议

从后端 data model 设计角度看，这三个开源项目并不是同一种模型的不同实现，而是三条不同路线：

- `Unity Catalog OSS`：多资产目录导向，适合做清晰的资源分表模型
- `Apache Gravitino`：联邦元数据和版本化导向，适合多 provider、多源、多治理对象平台
- `Apache Polaris`：Iceberg-first 控制面导向，适合统一实体、统一权限、统一 policy 的 catalog service

如果只保留两个最关键的判断结论，可以收敛为：

### 9.1 资产模型如何选择

- `Unity Catalog`：各类型主表
- `Gravitino`：各类型主表
- `Polaris`：统一主表

结论是：

- 如果优先考虑对象语义清晰和工程可维护性，优先参考 `Unity Catalog` / `Gravitino`
- 如果优先考虑统一实体模型和控制面抽象，优先参考 `Polaris`

### 9.2 格式隔离如何分层

- `Unity Catalog`：主要靠资产对象字段表达格式
- `Gravitino`：`catalog/provider` 先隔离，再由对象层补充
- `Polaris`：主要靠 subtype 和 properties 表达

结论是：

- 只在 `catalog` 层隔离格式，不够细
- 只在对象层放一个 `type` 字段，也不够强
- 更合理的方式是 provider 边界、对象类型、具体格式三层分工

### 9.3 对自研系统最有价值的借鉴

如果目标不是复刻某个开源项目，而是设计一个长期可扩展的 catalog service，那么最值得组合借鉴的是：

- 用 `Unity Catalog` 的清晰分层承载多资产
- 用 `Gravitino` 的版本化能力承载对象演进
- 用 `Polaris` 的关系建模承载 grant、policy 和控制面能力

这也是本文最终的推荐方向。
