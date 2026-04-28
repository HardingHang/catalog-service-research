# Unity Catalog 多格式、多引擎、多 API 调研报告

本文基于当前仓库源码与文档、Unity Catalog 官方文档、Apache Iceberg REST Catalog 规范、Delta Lake UniForm 资料整理，目标是回答以下四个问题：

1. Unity Catalog 支持哪些数据格式与对象类型。
2. Unity Catalog 支持哪些 API 协议、有哪些 REST 端点、各协议覆盖哪些数据格式与能力。
3. 计算引擎如何接入 Unity Catalog，不同接入方式是否一致，以及 Unity Catalog 如何以统一控制面支撑不同格式对象。
4. 为实现多格式、多引擎、多 API，Unity Catalog 采用了什么策略、模块如何组织。

## 摘要：

### 架构速览

从架构上看，Unity Catalog 可以分成五层：

1. 客户端与计算引擎层：例如 Spark、Trino、DuckDB、CLI、SDK、UI。
2. 协议接入层：包括 Core REST、Iceberg REST Catalog、Delta REST、Control API。
3. 统一服务层：包括 `TableService`、`VolumeService`、`FunctionService`、`ModelService`、`PermissionService` 等。
4. 元数据与策略层：包括统一资产模型、命名空间模型、权限模型、存储凭据模型、多协议投影逻辑。
5. 外部存储与数据层：包括对象存储中的 Delta 表、Parquet/CSV/JSON 文件、Volume 目录、模型制品目录等。

### 摘要用例：用 Spark 看主流程，再看不同分支怎么分叉

假设用户在 Spark 中执行下面这条 SQL：

```sql
SELECT * FROM unity.main.sales.orders LIMIT 10;
```

```text
Spark SQL
  -> 通过 catalog 名称 `unity` 进入 Unity Catalog 接入链路
  -> [Spark / UCSingleCatalog] 解析多段对象名，并把 catalog/table 操作翻译成 UC API 调用
  -> [Unity Catalog] 先走统一控制面查询对象元数据，而不是直接读底层数据文件
```

然后，后半段会按照对象类型和协议能力分成不同分支。

#### Delta Table

```text
  -> [Spark / UCSingleCatalog] 调用 Core REST 查询表元数据
  -> [Unity Catalog] /api/2.1/unity-catalog/tables/{full_name}
  -> [Unity Catalog] TableService / TableRepository 返回 schema、provider、table_type、storage location、权限相关信息
  -> [Unity Catalog] managed table 由 UC 派生/控制存储位置；external table 由用户显式提供 storage location
  -> [Unity Catalog] /api/2.1/unity-catalog/temporary-table-credentials 下发对象存储临时凭据
  -> [第三方: Delta 生态] Spark 侧的 Delta reader / DeltaCatalog 负责理解 `_delta_log`、版本与扫描语义
  -> [第三方存储] Object Storage 中保存 Delta 数据目录、`_delta_log`、Parquet data files
```

#### Iceberg-compatible: Delta UniForm 

```text
  -> [Spark Iceberg / Trino / Iceberg 客户端] 不一定走 UCSingleCatalog，而是直接走标准 Iceberg REST Catalog API
  -> [Unity Catalog] /api/2.1/unity-catalog/iceberg/v1/...
  -> [Unity Catalog] IcebergRestCatalogService 根据统一表元数据组织 namespace/table 视图
  -> [Unity Catalog] 只有带 `uniformIcebergMetadataLocation` 的表，才会在这条链路中被看见
  -> [第三方: Iceberg 生态] Iceberg client / connector 负责理解 metadata、snapshot 与 scan planning
  -> [第三方存储] Object Storage 中保存 UniForm 暴露出来的 Iceberg metadata 入口，以及底层数据文件
```

#### Volume: Lance / JSON files / text files 等

```text
  -> [CLI / SDK / 应用] 通过 Core REST 访问 volume，而不是把它当成 Table provider
  -> [Unity Catalog] /api/2.1/unity-catalog/volumes/{name}
  -> [Unity Catalog] 只管理 volume 的路径、命名空间、权限、external location 关联关系
  -> [Unity Catalog] /api/2.1/unity-catalog/temporary-volume-credentials 下发目录访问凭据
  -> [第三方存储] Object Storage 中保存 Lance dataset 目录、JSON 文件目录、文本文件目录等
```

## 1. Unity Catalog 支持哪些数据格式？提供哪些 API 协议操作这些数据？

### 1.1 总览

Unity Catalog 在 OSS 仓库中的“多格式”能力可以拆成三层：

- 表格型数据：通过 `Table` 资产管理，核心格式包括 `DELTA`、`PARQUET`、`ORC`、`JSON`、`CSV`、`AVRO`、`TEXT`。
- 文件/目录型数据：通过 `Volume` 资产管理，适合 JSON 文件、文本文件、Lance datasets 等非表或弱结构化对象。
- AI 与其他资产：通过 `Function`、`Model` 等统一纳入 catalog/schema/asset 命名空间。

其中，`Iceberg` 在当前仓库中主要体现为协议访问层，而不是 `DataSourceFormat` 枚举中的一个表格式；`UniForm` 则是把 Delta 表同时暴露为 Iceberg 可读元数据的策略。

### 1.2 对象类型、格式、协议、典型操作

| 一级对象 | 二级对象/格式 | 典型存储形态 | 可用 API 协议 | 典型操作 |
| --- | --- | --- | --- | --- |
| 表格数据 | Delta Lake | Delta log + parquet data files | Core REST、Delta REST、Iceberg REST（仅 UniForm/Uniform 可见时） | 建表、列元数据管理、读表、删表、凭据下发、Delta commit/metrics |
| 表格数据 | Parquet | Parquet 文件 | Core REST | 建表、列元数据管理、读表路径解析、凭据下发 |
| 表格数据 | ORC | ORC 文件 | Core REST | 建表、列元数据管理、读表路径解析、凭据下发 |
| 表格数据 | JSON | JSON 文件 | Core REST | 建表、元数据管理、路径与凭据下发 |
| 表格数据 | CSV | CSV 文件 | Core REST | 建表、元数据管理、路径与凭据下发 |
| 表格数据 | AVRO | Avro 文件 | Core REST | 建表、元数据管理、路径与凭据下发 |
| 表格数据 | TEXT | 文本文件 | Core REST | 建表、元数据管理、路径与凭据下发 |
| 文件/目录数据 | JSON files、text files、**Lance** datasets 等 | 对象存储目录或本地目录 | Core REST | 注册 volume、列目录、读文件、获取临时访问凭据 |
| AI 资产 | UDF / Function | Python 等函数资产 | Core REST、AI toolkit 集成 | 注册函数、查询函数元数据、执行集成 |
| AI 资产 | Registered Model / Model Version | 模型元数据与模型制品路径 | Core REST、temporary-model-version-credentials | 模型注册、版本管理、模型制品凭据下发 |

### 1.3 Lance

Lance 需要单独说明，因为它很容易被误解成“表格式枚举的一员”。当前 Unity Catalog OSS 对 Lance 的支持方式是：

- 在官方文档中明确把 `Lance datasets` 归类到 `Volume` 的适用场景。
- 也就是说，UC 管理的是 Lance 数据集所在目录/对象路径、权限和凭据，而不是在 `Table.data_source_format` 里把 Lance 当成一个与 `DELTA/PARQUET/CSV` 并列的 provider。
- 对 Lance 的典型用法更接近“统一治理对象存储中的数据集目录”，而不是“通过 UC 原生 Lance Catalog 协议读写”。

### 1.4 元数据管理

```
[Metastore]
    |
    +-- 1..N [Catalog]
              |
              +-- 1..N [Schema]
                        |
                        +-- 0..N [Table]
                        |         |- id
                        |         |- name
                        |         |- data_source_format
                        |         |- storage_location
                        |         |- columns[*]
                        |         |- properties[*]
                        |         \- delta/uniform metadata
                        |
                        +-- 0..N [Volume]
                        |         |- id
                        |         |- name
                        |         |- volume_type
                        |         \- storage_location
                        |
                        +-- 0..N [Function]
                        |         |- id
                        |         |- name
                        |         |- input_params[*]
                        |         |- return_params
                        |         \- routine_definition
                        |
                        +-- 0..N [RegisteredModel]
                                  |- id
                                  |- name
                                  |- storage_location
                                  |- max_version_number
                                  \- 1..N [ModelVersion]
                                           |- version
                                           |- source
                                           \- status

[All Assets]
    |- owner / created_at / updated_at
    |- catalog.schema.asset namespace
    |- permissions / securable key
    \- repository-based persistence
```

### 1.5 Uniform

- UC 自己保存的是“统一表元数据 + 一个 Uniform metadata 入口指针”。
- Iceberg-compatible metadata 不直接塞进 UC 通用表字段里，而是通过 location 引用。
- 底层数据事实仍然是 Delta 表及其数据目录，UniForm 只是额外挂出一个 Iceberg 生态可读的元数据入口。

```sql
  CREATE TABLE t(c1 INT)
  USING DELTA
  TBLPROPERTIES (
    'delta.enableIcebergCompatV2' = 'true',
    'delta.universalFormat.enabledFormats' = 'iceberg'
  );
```

```text
[UC Table Metadata Record]
  |- table_id
  |- full_name
  |- data_source_format = DELTA
  |- storage_location = s3://bucket/path/table
  |- columns / partitions / properties
  \- uniformIcebergMetadataLocation = s3://bucket/path/table/.../metadata.json
                 |
                 | points to
                 v
[Iceberg-compatible Metadata] 	-- delta 表更新提交后异步生成
  |- metadata.json
  |- snapshot list / schema / partition spec
  |- manifest list
  \- manifests
                 |
                 | describes
                 v
[Underlying Delta Table Data]
  |- _delta_log
  \- parquet data files
```

-  UniForm 读取调用链路图

```text
[Iceberg Client / Trino / Spark Iceberg]
                |
                v
IcebergRestCatalogService.loadTable()
                |
                v
TableRepository.getTableUniformMetadataLocation()
                |
                +--> read UC Table record
                +--> extract uniformIcebergMetadataLocation
                |
                v
MetadataService.readTableMetadata(location)
                |
                +--> open metadata.json
                +--> parse Iceberg-compatible metadata
                |
                v
TableConfigService.getTableConfig()
                |
                v
return Iceberg TableMetadata + config
                |
                v
[Iceberg Client executes planning / scan]
```



## 2. Unity Catalog API 协议

### 2.1 协议全景

当前仓库可以归纳出四类 API 面：

| 协议面 | 基础路径 | 作用 | 典型消费者 |
| --- | --- | --- | --- |
| Core REST API | `/api/2.1/unity-catalog` | UC 通用控制面 API，管理 catalog/schema/table/volume/function/model/credential 等资产 | UI、CLI、Java/Python SDK、Spark 连接器内部客户端、Daft 等 |
| Iceberg REST Catalog API | `/api/2.1/unity-catalog/iceberg/v1` | 为 Iceberg 客户端暴露 REST catalog 子集 | Trino、Spark Iceberg Catalog、其他 Iceberg REST 客户端 |
| Delta REST Catalog API | `/api/2.1/unity-catalog/delta/v1` | 面向 Delta 客户端的 REST catalog API | Delta Spark、Delta Kernel、未来 Delta-native 客户端 |
| Control API / SCIM / Auth | `/api/1.0/unity-control` | 用户、认证、令牌、SCIM | 管理面、认证集成 |

### 2.2 Core REST API 主要端点

下面是 `api/all.yaml` 中最重要的资源组与端点：

| 资源组 | 端点 | 主要方法 | 用途 |
| --- | --- | --- | --- |
| Catalogs | `/catalogs`、`/catalogs/{name}` | `POST/GET/PATCH/DELETE` | catalog 的增删改查 |
| Schemas | `/schemas`、`/schemas/{full_name}` | `POST/GET/PATCH/DELETE` | schema 的增删改查 |
| Tables | `/tables`、`/tables/{full_name}` | `POST/GET/DELETE` | 表的创建、列表、元数据获取、删除 |
| Staging Tables | `/staging-tables` | `POST` | 为受管 Delta 表创建 staging table |
| Volumes | `/volumes`、`/volumes/{name}` | `POST/GET/PATCH/DELETE` | volume 元数据管理 |
| Functions | `/functions`、`/functions/{full_name}` | `POST/GET/DELETE` | 函数资产管理 |
| Models | `/models`、`/models/{full_name}` | `POST/GET/PATCH/DELETE` | 注册模型管理 |
| Model Versions | `/models/versions`、`/models/{full_name}/versions`……         | `POST/GET/PATCH/DELETE` | 模型版本与制品生命周期 |
| Permissions | `/permissions/{securable_type}/{full_name}` | `GET/PATCH` | 权限查看与更新 |
| Credentials | `/credentials`、`/credentials/{name}` | `POST/GET/PATCH/DELETE` | 存储凭据管理 |
| External Locations | `/external-locations`、`/external-locations/{name}` | `POST/GET/PATCH/DELETE` | 外部位置管理 |
| Temporary Credentials | `/temporary-table-credentials`、`/temporary-volume-credentials`、`/temporary-model-version-credentials`…… | `POST` | 数据面访问临时凭据下发 |
| Metastore Summary | `/metastore_summary` | `GET` | metastore 摘要 |
| Delta Preview Commits | `/delta/preview/commits` | `GET/POST` | Delta commit 预览与提交相关接口 |

Core REST 的特点是：

- 面向统一控制面。
- 覆盖的对象最广，不仅有表，也有 volume、function、model、permissions。
- 对表来说，它重点管理的是元数据、命名空间、位置、格式、凭据，而不是替代引擎自己的扫描器/执行器。

### 2.3 Iceberg REST Catalog 端点

`IcebergRestCatalogService` 当前暴露的是 Iceberg REST Catalog 的一个**子集**：

| 端点 | 方法 | 作用 |
| --- | --- | --- |
| `/iceberg/v1/config` | `GET` | 返回配置、prefix、支持端点集合 |
| `/iceberg/v1/catalogs/{catalog}/namespaces` | `GET` | 列 schema，映射为 Iceberg namespace |
| `/iceberg/v1/catalogs/{catalog}/namespaces/{namespace}` | `GET` | 获取 namespace 元数据 |
| `/iceberg/v1/catalogs/{catalog}/namespaces/{namespace}/tables` | `GET` | 列表当前 namespace 中的表 |
| `/iceberg/v1/catalogs/{catalog}/namespaces/{namespace}/tables/{table}` | `HEAD` | 判断表是否存在 |
| `/iceberg/v1/catalogs/{catalog}/namespaces/{namespace}/tables/{table}` | `GET` | 加载表元数据与 storage config |
| `/iceberg/v1/catalogs/{catalog}/namespaces/{namespace}/views/{view}` | `GET` | 视图加载占位，目前返回 **not found** |
| `/iceberg/v1/catalogs/{catalog}/namespaces/{namespace}/tables/{table}/metrics` | `POST` | metrics 上报 |

这个协议面的关键特征：

- 不是完整 UC 控制面，只是兼容 Iceberg 客户端需要的 catalog 协议面。
- 当前实现主要服务于“可被 Iceberg 看见的 UC 表”。
- 在当前仓库实现中，只有带 `uniformIcebergMetadataLocation` 的表才会出现在此 API 下。

### 2.4 Delta REST Catalog 端点

`api/delta.yaml` 与 `DeltaRestCatalogService` 显示，Delta REST API 设计的端点包括：

| 端点 | 方法 | 作用 |
| --- | --- | --- |
| `/delta/v1/config` | `GET` | 返回协议版本与支持端点 |
| `/delta/v1/catalogs/{catalog}/schemas/{schema}/staging-tables` | `POST` | 创建 staging table，用于受管 Delta 表 |
| `/delta/v1/catalogs/{catalog}/schemas/{schema}/tables` | `POST/GET` | 创建 Delta 表、列举表 |
| `/delta/v1/catalogs/{catalog}/schemas/{schema}/tables/{table}` | `GET/POST/DELETE/HEAD` | 加载、更新、删除、存在性检查 |
| `/delta/v1/catalogs/{catalog}/schemas/{schema}/tables/{table}/rename` | `POST` | 表重命名 |
| `/delta/v1/catalogs/{catalog}/schemas/{schema}/tables/{table}/credentials` | `GET` | 获取表级临时凭据 |
| `/delta/v1/catalogs/{catalog}/schemas/{schema}/tables/{table}/metrics` | `POST` | 上报 metrics |
| `/delta/v1/staging-tables/{table_id}/credentials` | `GET` | 获取 staging table 凭据 |
| `/delta/v1/temporary-path-credentials` | `GET` | 获取临时路径凭据 |

这个协议面的关键特征：

- 明确面向 Delta 客户端，而非 Iceberg 客户端。
- 比 Core REST 更接近 Delta-native catalog/commit 协议。
- 比 Iceberg REST 更强调 staging table、写入凭据、Delta 客户端生命周期。
- 当前服务类源码里直接实现的最核心接口是 `/delta/v1/config` 与 `loadTable`，其余端点在协议设计中已列出，属于 Delta REST 面的一部分。

### 2.5 Control API / SCIM / Auth 端点

`api/control.yaml` 暴露了管理与认证相关端点：

| 端点 | 方法 | 作用 |
| --- | --- | --- |
| `/scim2/Users`、`/scim2/Users/{id}` | `POST/GET/PATCH/DELETE` | 用户管理 |
| `/scim2/Me` | `GET` | 当前用户 |
| `/auth/tokens` | `POST` | OAuth2 获取 token |
| `/auth/logout` | `POST` | 注销 |

### 2.6 不同协议支持的数据格式与能力差异

| 协议 | 主要对象 | 支持的数据格式/对象 | 能力边界 | 最适合的场景 |
| --- | --- | --- | --- | --- |
| Core REST | 全部 UC 资产 | Delta、Parquet、ORC、JSON、CSV、AVRO、TEXT、Volume、Lance 数据集目录、Function、Model | 管控制面最强，但不直接替代引擎执行扫描/计算 | 管理统一元数据、命名空间、权限、外部位置、凭据 |
| Iceberg REST | Iceberg-compatible table view | 主要是可通过 Iceberg 视角暴露的表；当前仓库中最典型是 Delta UniForm 表 | 是协议子集，当前未实现通用写表控制面 | 让 Trino / Spark Iceberg / Iceberg REST 客户端读取 UC 中可见表 |
| Delta REST | Delta 表 | Delta 表，尤其是受管/外部 Delta 表 | Delta 专用，面向 Delta 客户端协议 | Delta-native 客户端创建、加载、写入、拿凭据 |
| Control API | 用户与认证 | 与数据格式无关 | 仅管理面 | 认证、SCIM、token 流程 |

### 2.7 使用示例

#### 示例 1：Core REST 创建一张 Delta 表

```bash
curl -X POST "http://localhost:8080/api/2.1/unity-catalog/tables" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my_new_table",
    "catalog_name": "unity",
    "schema_name": "default",
    "table_type": "EXTERNAL",
    "data_source_format": "DELTA",
    "storage_location": "s3://my-bucket/path/to/table"
  }'
```

#### 示例 2：通过 Iceberg REST 让 Trino 读取 UniForm 表

```properties
connector.name=iceberg
iceberg.catalog.type=rest
iceberg.rest-catalog.uri=http://127.0.0.1:8080/api/2.1/unity-catalog/iceberg
iceberg.rest-catalog.security=OAUTH2
iceberg.rest-catalog.oauth2.token=not_used
```

```sql
SELECT * FROM iceberg."unity.default".my_new_table;
```

#### 示例 3：Delta 客户端通过 Delta REST 获取配置

```http
GET /api/2.1/unity-catalog/delta/v1/config?catalog=unity&protocol-versions=1.0
```

服务端会返回协议版本和它支持的 Delta REST 端点集合。

#### 示例 4：Volume 管理 Lance 数据集目录

```bash
curl -X POST "http://localhost:8080/api/2.1/unity-catalog/volumes" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my_lance_dataset",
    "catalog_name": "unity",
    "schema_name": "default",
    "volume_type": "EXTERNAL",
    "storage_location": "s3://bucket/lance/my_dataset/"
  }'
```

之后客户端可以结合 `temporary-volume-credentials` 或对象存储凭据访问 Lance 数据集目录。

## 3. 计算引擎接入

### 3.1 引擎接入矩阵

| 引擎/工具 | 接入方式 | 主要协议/API | 读写对象 | 特点 |
| --- | --- | --- | --- | --- |
| Spark | `io.unitycatalog.spark.UCSingleCatalog` | Core REST + temporary credentials，必要时联动 DeltaCatalog | Delta 和 file-based table formats | 仓库内一方连接器，最深度集成 |
| Spark + Iceberg | `org.apache.iceberg.rest.RESTCatalog` | Iceberg REST | UniForm/可见 Iceberg 表 | 适合只支持 Iceberg catalog 的访问链路 |
| Trino | Iceberg REST catalog | Iceberg REST | 典型是 UniForm 表 | 用标准 Iceberg connector 接入 |
| DuckDB | `uc_catalog` 扩展 | UC 扩展协议 + Delta 扩展 | Delta 表 | 通过 DuckDB 扩展直接附加 UC catalog |
| Daft | `daft.unity_catalog.UnityCatalog` | Core REST 风格控制面 + Delta Lake 读取 | Delta 表 | 先 load table 元数据，再交给 Daft/Delta Lake reader |
| CLI / SDK / UI | 直接调用 UC REST | Core REST | 全部 UC 资产 | 通用管理面 |
| 面向文件/对象的应用 | Core REST + temporary-volume-credentials | Core REST | Volume、Lance 数据集目录、文件对象 | 先拿目录/凭据，再用各自的存储或数据集库访问 |

### 3.2 不同 API 接入方式并不一样

不一样，差异主要在下面三点：

- 面向的客户端生态不同。
  - Spark 原生连接器更像“UC 专属 catalog plugin”。
  - Trino 走 Iceberg REST，是“标准协议兼容”。
  - Delta 客户端走 Delta REST，是“Delta 专用 catalog 协议”。
- 返回的数据形态不同。
  - Core REST 返回 UC 自己的 `CatalogInfo/SchemaInfo/TableInfo/...` 模型。
  - Iceberg REST 返回 Iceberg `TableMetadata + config`。
  - Delta REST 返回 Delta catalog 模型与临时凭据。
- 数据面执行方不同。
  - UC 负责控制面、授权与凭据。
  - 真正的扫描、过滤、执行仍由 Spark、Trino、DuckDB、Daft 或文件库完成。

### 3.3 统一 API 的核心思路

Unity Catalog 的统一性，更多体现在“统一控制面”而不是“所有引擎都调用同一个 wire protocol”。核心方法是：

1. 用统一对象模型管理资产。
   - catalog / schema / table / volume / function / model 都挂在同一层级命名空间中。

2. 用统一元数据服务管理位置、格式、权限和依赖关系。
   - 对表记录 `data_source_format`、`storage_location`、列信息、属性。
   - 对 volume 记录 `storage_location`、`volume_type`。
   - 对模型和函数记录制品位置与元数据。

3. 用统一凭据下发机制把控制面连接到数据面。
   - `temporary-table-credentials`
   - `temporary-volume-credentials`
   - `temporary-model-version-credentials`
   - `temporary-path-credentials`

4. 在协议层做适配，而不是强迫所有客户端都说同一种协议。
   - Spark 走 UC connector。
   - Trino/Spark Iceberg 走 Iceberg REST。
   - Delta-native 客户端走 Delta REST。

### 3.4 典型示例

#### 示例 A：Spark 通过同一个 UC Catalog 访问不同表格式

Spark 连接器配置示例：

```bash
bin/spark-sql \
  --packages "io.delta:delta-spark_2.13:4.0.0,io.unitycatalog:unitycatalog-spark_2.13:0.3.0" \
  --conf "spark.sql.extensions=io.delta.sql.DeltaSparkSessionExtension" \
  --conf "spark.sql.catalog.unity=io.unitycatalog.spark.UCSingleCatalog" \
  --conf "spark.sql.catalog.unity.uri=http://localhost:8080" \
  --conf "spark.sql.catalog.unity.token=" \
  --conf "spark.sql.defaultCatalog=unity"
```

这条链路的本质是：

- `UCSingleCatalog` 通过 Core REST 获取表元数据。
- 通过 temporary credentials 获取读写存储所需凭据。
- 根据 `data_source_format` 把 UC 元数据映射到 Spark 的 provider 与 catalog 行为。
- 同一个 catalog 插件即可管理 Delta、Parquet、CSV、JSON 等表。

#### 示例 B：同一张 Delta UniForm 表，同时被 Delta 客户端和 Iceberg 客户端看到

- Delta 侧：仍按 Delta 表使用。
- Iceberg 侧：通过 `/iceberg/v1/...` 加载到对应 Iceberg 元数据。
- Unity Catalog 起到桥梁作用：表的命名空间、鉴权、位置、凭据仍由 UC 统一掌控。

#### 示例 C：Lance 数据集通过 Volume 接入

- 在 UC 中注册为一个 volume。
- UC 负责目录级元数据、权限和临时凭据。
- 客户端再使用 Lance 自己的读写库或对象存储接口操作数据。

这说明 UC 的“统一 API”并不意味着“统一文件格式解析器”，而是“统一治理 + 统一入口 + 协议适配”。

## 4. 为实现多格式、多引擎、多 API，Unity Catalog 采用了什么策略？如何实现？

### 4.1 总体策略

可以概括为四条：

| 策略 | 含义 | 实现方式 |
| --- | --- | --- |
| 统一对象模型 | 把表、volume、函数、模型等都放进同一 catalog/schema/asset 体系 | Core REST + repository + metadata model |
| 协议分层适配 | 不同引擎使用各自熟悉的协议访问 UC | Core REST、Iceberg REST、Delta REST、SCIM/Auth |
| 控制面与数据面分离 | UC 管元数据、权限和凭据，不负责所有执行引擎的数据扫描 | temporary credentials + engine-native readers |
| 跨格式互通 | 通过 UniForm 等机制把一种底层格式暴露给另一生态协议 | Delta UniForm -> Iceberg REST 可见 |

### 4.2 关键实现机制

#### 4.2.1 元数据统一存储

各协议最后都回到 repository/metadata 层：

- Core REST 直接读写 repository。
- Iceberg REST 通过 repository 找到表，再读取 `uniformIcebergMetadataLocation`。
- Delta REST 通过 repository 返回 Delta 表模型。

也就是说，协议不同，但底层元数据事实源是统一的。

#### 4.2.2 Spark 连接器中的格式适配

`UCSingleCatalog` 是理解“多格式 + 多引擎”的关键代码点：

- 它通过 `TablesApi`、`TemporaryCredentialsApi` 与 UC 通信。
- 它把 UC 的 `DataSourceFormat` 映射到 Spark 可理解的 provider。
- 对受管 Delta 表，它会先调用 `createStagingTable`，再申请 `READ_WRITE` 凭据。
- 对外部表，它会根据位置和格式生成 Spark 所需属性。

本质上，Spark 看到的是一个统一 catalog；而 catalog 内部又能根据 UC 元数据分发到不同表格式和不同存储位置。

#### 4.2.3 UniForm 的跨协议暴露

UniForm 解决的是“Delta 表如何被 Iceberg 生态读取”的问题：

- UC 仍把该对象作为统一表资产管理。
- 当表具备对应的 Iceberg metadata 暴露条件时，`IcebergRestCatalogService` 可以让它对 Iceberg 客户端可见。
- 这样就实现了“一套控制面，多套访问协议”。

#### 4.2.4 Managed / External Table 的存储控制机制

`managed table` 和 `external table` 是 UC 存储控制模型里的另一条关键主线：

- 对 `managed table`，UC 不只是记住“表在哪”，还会更主动地控制目录分配和写入生命周期。
- 对 `external table`，UC 更像“登记 + 授权 + 凭据下发 + 路径校验”的控制面。

从当前仓库实现可以提炼出下面这组结构差异：

| 方面 | Managed Table | External Table |
| --- | --- | --- |
| 路径来源 | 通常从 managed storage 规则派生 | 创建时显式提供 `storage_location` |
| 目录控制权 | 更强，由 UC/受管流程主导 | 较弱，底层目录通常已存在 |
| 写入辅助机制 | 典型会用到 `staging-tables`、`READ_WRITE` temporary credentials | 更多是基于现有路径发放读写凭据 |
| 与 external location 关系 | 可弱一些，重点在受管目录规则 | 很强，依赖 external location 与重叠校验 |
| 代表性格式场景 | managed Delta table | external Delta / Parquet / CSV / JSON 等 |

这组机制的重要性在于，它解释了为什么 UC 不只是“抽象表名”，还要介入：

- 路径生成
- staging 目录
- 临时读写凭据
- external location 合法性校验

也正因为有 `managed/external` 这层分化，UC 才能同时覆盖“强托管的 lakehouse 表”与“对已有对象存储数据做统一治理”这两类场景。

### 4.3 总模块结构图

下面这张图把原来的“总模块图”和“关键功能模块图”合并成一张更完整的总结构图。整体结构仍然保持“客户端 -> 协议接入 -> 统一服务 -> 元数据/权限/凭据 -> 外部存储与格式运行时”的主干，但在主干上额外标出关键分叉点。

```text
+------------------------------------------------------------------------------------------------------+
|                                       Client / Engine Layer                                          |
| Spark(UCSingleCatalog) | Spark Iceberg RESTCatalog | Trino | DuckDB | Daft | CLI | SDK | UI        |
+------------------------------------------------------+-----------------------------------------------+
                                                       |
                                                       v
+------------------------------------------------------------------------------------------------------+
|                                      Protocol / Access Layer                                         |
| Core REST                          | Iceberg REST                  | Delta REST       | Control API   |
| /catalogs /schemas /tables        | /iceberg/v1/...              | /delta/v1/...    | /auth /scim   |
| /volumes /functions /models       | namespace/loadTable/metrics  | config/load/...  | token/user    |
| /permissions /credentials         | Iceberg-compatible view      | Delta-specific   | auth mgmt     |
+------------------------------------------------------+-----------------------------------------------+
                                                       |
                                                       v
+------------------------------------------------------------------------------------------------------+
|                                      Unified Service Layer                                           |
| CatalogService | SchemaService | TableService | VolumeService | FunctionService | ModelService      |
| PermissionService | TemporaryTableCredentials | TemporaryVolumeCredentials | TemporaryModelCreds  |
| IcebergRestCatalogService | DeltaRestCatalogService                                               |
+------------------------------------------------------------------------------------------------------+
| Key branch:                                                                    |
|   TableService -> table metadata / schema / format / table_type                                     |
|   VolumeService -> directory-like assets                                                            |
|   FunctionService -> signature / routine definition                                                 |
|   ModelService -> registered model + model version                                                  |
+------------------------------------------------------+-----------------------------------------------+
                                                       |
                                                       v
+------------------------------------------------------------------------------------------------------+
|                                Metadata / Policy / Adapter Layer                                     |
| Repositories aggregate                                                                                |
|   CatalogRepository | SchemaRepository | TableRepository | VolumeRepository | FunctionRepository     |
|   ModelRepository                                                                                        |
|                                                                                                      |
| Shared governance                                                                                    |
|   Namespace model | Ownership/Audit | AuthZ(KeyMapper / AccessEvaluator / Authorizer)               |
|   ExternalLocationUtils | FileOperations | TransactionManager | NormalizedURL                        |
|                                                                                                      |
| Table-specific branches                                                                             |
|   table_type split: Managed Table | External Table                                                  |
|   format split: DELTA | PARQUET | ORC | JSON | CSV | AVRO | TEXT                                   |
|   protocol view split: Core TableInfo | Delta LoadTableResponse | Iceberg-compatible projection     |
|   UniForm split: with uniformIcebergMetadataLocation | without uniform projection                   |
|                                                                                                      |
| Protocol projection / format adaptation                                                              |
|   Core TableInfo projection | Delta load/commit projection | Iceberg metadata projection            |
|   UniForm pointer(uniformIcebergMetadataLocation) | Spark provider mapping                           |
|                                                                                                      |
| Credential vending                                                                                   |
|   temporary-table-credentials | temporary-volume-credentials | temporary-model-version-credentials   |
|   temporary-path-credentials                                                                         |
+------------------------------------------------------+-----------------------------------------------+
                                                       |
                           +---------------------------+-----------------------------+
                           |                           |                             |
                           v                           v                             v
+-----------------------------------+   +-----------------------------------+   +----------------------------------+
| UC Persistent Metadata            |   | External Storage / Data Layer     |   | Format Runtime / Engine Side     |
| *InfoDAO / ColumnInfoDAO /        |   | S3 / GCS / ADLS / Local / NFS     |   | Delta runtime / DeltaCatalog     |
| PropertyDAO / DB state            |   | Delta tables / file-based tables  |   | Iceberg client / RESTCatalog     |
| table records / volume records /  |   | volumes / Lance dataset dirs /    |   | Spark built-in data sources      |
| function records / model records  |   | model artifacts / uniform metadata|   | file/object libraries            |
+-----------------------------------+   +-----------------------------------+   +----------------------------------+
```

这张图里最关键的结构关系是：

- 不同客户端不会直接共享同一个 wire protocol，而是分别进入 `Core REST / Iceberg REST / Delta REST / Control API`。
- 中间的 `Unified Service Layer` 是统一控制面的核心，但这里也不是完全同质的：不同资产会分到 `Table / Volume / Function / Model` 不同服务。
- 再往下的 `Metadata / Policy / Adapter Layer` 是真正的“能力中枢”：
  - repository 管统一事实源
  - authz 管统一权限
  - credential vending 负责把控制面连接到数据面
  - protocol projection 负责把同一份表元数据投影成 Core/Delta/Iceberg 三种视图
- 对 `Table` 来说，这一层还有四个重要分叉：
  - `managed table / external table`
  - `DELTA / file-based formats`
  - `Core / Delta / Iceberg` 三种协议视图
  - `with UniForm / without UniForm`
- 最下层要明确分成两个世界：
  - `UC Persistent Metadata` 是 UC 自己维护的目录、表、权限等控制面事实
  - `External Storage / Data Layer` 和 `Format Runtime / Engine Side` 才是真正执行 Delta/Iceberg/file-based 语义与数据扫描的位置

因此，Unity Catalog 的总体结构并不是“单体 REST 服务 + 一层数据库”这么简单，而是：

- 上层多协议接入
- 中层统一服务与统一治理
- 下层同时连接“UC 自身元数据状态”和“外部对象存储/格式运行时”

## 5. 结论

### 5.1 核心结论

1. Unity Catalog OSS 已经形成“统一控制面 + 多协议适配层”的架构，而不是只提供单一 REST API。
2. 从表格式角度看，当前公开枚举的重点支持格式是 `DELTA`、`PARQUET`、`ORC`、`JSON`、`CSV`、`AVRO`、`TEXT`。
3. Lance 需要放在 `Volume` 语义下理解：UC 管理的是 Lance 数据集目录、权限和凭据，而不是把 Lance 暴露为表格式 provider。
4. Iceberg REST 与 Delta REST 是“协议兼容层”，它们让不同计算引擎使用自己熟悉的 catalog 协议访问 UC。
5. 多引擎支持的关键不是 UC 自己实现所有执行器，而是：
   - 统一维护元数据
   - 统一做鉴权
   - 统一下发临时凭据
   - 在协议层做 Spark / Iceberg / Delta 等适配
6. UniForm 是 UC 实现多格式、多引擎互通的代表性策略：同一张 Delta 表可以通过 Iceberg 生态被读取。

### 5.2 一句话概括

Unity Catalog 的“多格式、多引擎、多 API”能力，本质上不是“所有对象都被转换成同一种底层格式”，而是“用一套统一治理控制面，把不同格式对象、不同执行引擎、不同 catalog 协议收束到同一个元数据与凭据中心”。

## 6. 参考来源

### 6.1 当前仓库

- `README.md`
- `api/all.yaml`
- `api/control.yaml`
- `api/delta.yaml`
- `docs/usage/tables/formats.md`
- `docs/usage/tables/uniform.md`
- `docs/usage/volumes.md`
- `docs/usage/api/index.md`
- `docs/usage/api/tables.md`
- `docs/usage/api/volumes.md`
- `docs/integrations/unity-catalog-spark.md`
- `docs/integrations/unity-catalog-trino.md`
- `docs/integrations/unity-catalog-duckdb.md`
- `docs/integrations/unity-catalog-daft.md`
- `server/src/main/java/io/unitycatalog/server/UnityCatalogServer.java`
- `server/src/main/java/io/unitycatalog/server/service/TableService.java`
- `server/src/main/java/io/unitycatalog/server/service/VolumeService.java`
- `server/src/main/java/io/unitycatalog/server/service/FunctionService.java`
- `server/src/main/java/io/unitycatalog/server/service/ModelService.java`
- `server/src/main/java/io/unitycatalog/server/persist/Repositories.java`
- `server/src/main/java/io/unitycatalog/server/persist/TableRepository.java`
- `server/src/main/java/io/unitycatalog/server/persist/VolumeRepository.java`
- `server/src/main/java/io/unitycatalog/server/persist/FunctionRepository.java`
- `server/src/main/java/io/unitycatalog/server/persist/ModelRepository.java`
- `server/src/main/java/io/unitycatalog/server/service/IcebergRestCatalogService.java`
- `server/src/main/java/io/unitycatalog/server/service/delta/DeltaRestCatalogService.java`
- `connectors/spark/src/main/scala/io/unitycatalog/spark/UCSingleCatalog.scala`

### 6.2 外部资料

- Unity Catalog 官方文档主页：https://docs.unitycatalog.io/
- Unity Catalog Swagger Docs：https://docs.unitycatalog.io/swagger-docs/
- Apache Iceberg REST Catalog Spec：https://iceberg.apache.org/rest-catalog-spec/
- Delta Lake UniForm 文档/资料：https://docs.delta.io/ 和 https://delta.io/
- DuckDB Unity Catalog 扩展资料：https://duckdb.org/
