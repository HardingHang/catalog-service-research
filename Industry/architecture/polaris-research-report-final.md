# Polaris 多格式、多协议、多引擎支持调研报告

本文面向内部开发人员分享，重点解释 Polaris 是什么、支持什么、怎么接、能力边界在哪里。内容同时参考了当前仓库源码与官方文档，尽量避免展开过多源码细节。

调研基线：
- 仓库：当前工作区 `1.4.0-SNAPSHOT`
- 外部资料：Apache Polaris 官方文档（`in-dev/unreleased`）与官方 API 规范
- 结论口径：优先采用“当前仓库已实现 + 官方文档已说明”的交集；对实验性或能力不对等的部分单独标注

---

## 1. 先讲结论

Polaris 的主定位，仍然是一个面向 Apache Iceberg 的 Catalog 服务。它的核心能力来自：
- 标准 Iceberg REST Catalog API
- Polaris 自己补充的 Native Catalog API（Generic Table，Policy…）
- 统一的 catalog / namespace / table-like / auth / storage 控制面

“多格式支持”在 Polaris 里不是一个单层概念，而是能力叠加：
- 第一层：原生一等公民格式是 Iceberg
- 第二层：通过 Generic Table API，把非 Iceberg 表格式纳入统一治理
- 第三层：通过 Spark 插件，把 Delta、Hudi、Paimon 等格式接到 Polaris 控制面

### 示例：

```sql
CREATE TABLE polaris.sales.orders_iceberg (
  order_id BIGINT,
  status STRING
)
USING iceberg;

-- 接入 polaris 插件
CREATE TABLE polaris.sales.orders_delta (
  order_id BIGINT,
  status STRING
)
USING delta
LOCATION 's3://demo/sales/orders_delta';
```

这两条 SQL 表面上都像“Spark 在同一个 catalog 里建表”，但内部路径不同。

#### 主流程对比

```text
共同前半段：
Spark SQL
  -> 通过 catalog 名称 `polaris` 进入 Polaris 接入链路

Iceberg:
  -> [Spark / 引擎内置 Iceberg 客户端] 直接走标准 Iceberg REST Catalog API
  -> [Polaris] /api/catalog/v1/...
  -> [Polaris] Polaris 深度处理 Iceberg 目录语义
  -> [Polaris] 表提交、view、credentials、metrics 等能力都在 Polaris 主线上
  -> [第三方存储] Object Storage 中保存 Iceberg metadata / manifest / data files

Generic Table (Delta):
  -> [Polaris] SparkCatalog 识别 `provider=delta`
  -> [Polaris] DeltaHelper 装配 DeltaCatalog
  -> [第三方: Delta 生态] DeltaCatalog 负责 Delta 自身建表语义和 _delta_log 初始化
  -> [Polaris] Polaris 通过 Generic Table API 做目录登记
  -> [Polaris] /api/catalog/polaris/v1/.../generic-tables
  -> [第三方存储] Object Storage 中保存 Delta 数据目录和 _delta_log
```

## 2. Polaris 支持的数据格式

先区分“Polaris 原生支持”与“通过适配/抽象支持”。

### 2.1 总览表

#### A. Polaris 直接理解的数据对象

| 对象类型 | 是否原生理解其语义 | 主要操作方式 |
| --- | --- | --- |
| Iceberg Table | 是 | `/api/catalog/v1/.../tables...` |
| Iceberg View | 是 | `/api/catalog/v1/.../views...` |
| Generic Table | 部分理解，仅理解通用元数据字段 | `/api/catalog/polaris/v1/.../generic-tables...` |
| Policy | 是，治理对象 | `/api/catalog/polaris/v1/.../policies...` |

#### B. Generic Table 能承载的格式

| 格式 | 是否有 Polaris 原生事务语义 | 是否可通过 Generic Table 登记 | 备注 |
| --- | --- | --- | --- |
| Delta | 否 | 是 | 当前最明确的非 Iceberg 主用例 |
| Hudi | 否 | 是 | 主要在 Spark 插件侧适配 |
| Paimon | 否 | 是 | 主要在 Spark 插件侧适配 |
| Lance | 否 | 是 | 当前证据主要来自官方博客/集成文档，走 Generic Table 映射 |
| CSV / JSON / Parquet / ORC / Avro 等 | 否 | 是 | 更多是目录登记，不是完整表协议 |

#### C. 外部 Catalog / Metastore 连接类型

| 连接类型 | 配置入口 | 用途 |
| --- | --- | --- |
| `ICEBERG_REST` | Management API `connectionConfigInfo` | 联邦远端 Iceberg REST Catalog |
| `HADOOP` | Management API `connectionConfigInfo` | 联邦 Hadoop Catalog |
| `HIVE` | Management API `connectionConfigInfo` | 联邦 Hive Metastore |
| `BIGQUERY` | Management API `connectionConfigInfo` | 联邦 BigQuery Metastore Catalog |

### 2.2 Lance 格式应该怎样理解

Lance 需要单独说明，因为它和 Delta/Hudi/Paimon 的接入证据层级不完全一样。

当前仓库和官方站点给出的更准确结论是：
- Polaris 已给出 Lance 集成方案，但主仓里没有看到独立的 Lance 协议服务端实现
- 现阶段的接入方式是：由 Lance Namespace 实现把 Lance 操作映射到 Polaris Generic Table API
- Polaris 存的是“Lance 表登记信息”，而不是接管 Lance 全部内部元数据演进逻辑

可以把它理解成下面这条链路：

```text
Lance Client / LanceDB / Lance Spark / Lance Trino
  -> Lance Namespace Polaris implementation
  -> Polaris Generic Table API
  -> 在 Polaris 中登记为 generic table
     format = lance
     base-location = <table root>
     properties.table_type = lance
```

这条链路的设计含义是：
- Polaris 负责统一 catalog、namespace、RBAC、策略绑定、对象位置登记
- Lance 自身的格式语义、提交路径、版本演进仍主要由 Lance 生态负责

- Lance Namespace 把 Lance 的 namespace/table 操作翻译为Polaris 的 namespace API 和 Generic Table API 调用。
### 2.3 内部对象模型

从内部对象模型看，Polaris 不是直接为每一种格式都建一套完全独立的顶层体系，而是先抽象出统一的实体层，再在子类型上分化：

```text
PolarisEntity
  |
  +-- CatalogEntity
  +-- NamespaceEntity
  +-- TableLikeEntity
  |     |
  |     +-- IcebergTableLikeEntity
  |     |     +-- subtype: ICEBERG_TABLE
  |     |     +-- subtype: ICEBERG_VIEW
  |     |
  |     +-- GenericTableEntity
  |           +-- subtype: GENERIC_TABLE
  |
  +-- PrincipalEntity
  +-- PrincipalRoleEntity
  +-- CatalogRoleEntity
  +-- PolicyEntity
```

这套设计的关键价值是：

- `catalog -> namespace -> table-like` 的路径解析可以统一
- RBAC 不必为每种表格式单独发明一套模型
- Generic Table、Iceberg Table、Iceberg View 可以共享同一层对象树
- 只有真正涉及表格式语义时，才进入 Iceberg / Generic 的分支
---

## 3. Polaris 的哪些 API 协议

从协议视角，Polaris 可以分成 4 组 API。

### 3.1 协议总览

| 协议组 | 基路径 | 主要对象 | 作用 |
| --- | --- | --- | --- |
| Management API | `/api/management/v1` | catalog、principal、role、grant | 管理控制面 |
| Iceberg REST Catalog API | `/api/catalog/v1` | namespace、table、view、commit、credentials | 标准 Iceberg 目录协议 |
| Polaris Native Catalog API | `/api/catalog/polaris/v1` | generic table、policy | Polaris 自定义扩展能力 |
| OAuth Token Endpoint | `/api/catalog/v1/oauth/tokens` | token | 目录侧 token 获取接口，规范里已标记 deprecated |

### 3.2 Management API

主要用于管理元数据对象和权限模型，不直接做表读写。

#### 代表端点

| 端点 | 作用 |
| --- | --- |
| `GET /catalogs` | 列出 catalog |
| `POST /catalogs` | 创建 catalog |
| `GET /catalogs/{catalogName}` | 查看 catalog |
| `PUT /catalogs/{catalogName}` | 更新 catalog |
| `DELETE /catalogs/{catalogName}` | 删除 catalog |
| `GET /principals` / `POST /principals` | 管理 principal |
| `GET /principals/{principalName}` / `PUT` / `DELETE` | 管理单个 principal |
| `POST /principals/{principalName}/rotate` | 轮换凭证 |
| `POST /principals/{principalName}/reset` | 重置凭证 |
| `GET /principal-roles` / `POST /principal-roles` | 管理 principal role |
| `GET /catalogs/{catalogName}/catalog-roles` / `POST` | 管理 catalog role |
| `GET /catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants` / `PUT` | 查看和授予权限 |

#### 功能特点

- 面向管理员和控制面系统
- 负责 catalog 类型、存储类型、外部连接、principal、role、grant
- 外部 catalog 的联邦接入，也是通过这一层配置

#### 使用示例

```bash
curl -X POST http://localhost:8181/api/management/v1/catalogs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "demo",
    "type": "INTERNAL",
    "storageConfigInfo": {
      "storageType": "S3",
      "roleArn": "arn:aws:iam::123456789012:role/polaris-demo",
      "region": "us-east-1"
    },
    "properties": {
      "default-base-location": "s3://demo/warehouse/"
    }
  }'
```

### 3.3 Iceberg REST Catalog API

这是 Polaris 最重要的协议组，也是大多数计算引擎接入 Polaris 的标准入口。

#### 代表端点

| 端点 | 作用 |
| --- | --- |
| `GET /v1/config` | 读取服务端配置与 endpoint 能力声明 |
| `POST /v1/oauth/tokens` | 获取 token，规范已标记 deprecated |
| `GET/POST /v1/{prefix}/namespaces` | 列出/创建 namespace |
| `GET/DELETE /v1/{prefix}/namespaces/{namespace}` | 查看/删除 namespace |
| `POST /v1/{prefix}/namespaces/{namespace}/properties` | 更新 namespace 属性 |
| `GET/POST /v1/{prefix}/namespaces/{namespace}/tables` | 列表/创建 Iceberg table |
| `POST /v1/{prefix}/namespaces/{namespace}/register` | 注册已有表 |
| `GET/DELETE/POST /v1/{prefix}/namespaces/{namespace}/tables/{table}` | 读表、删表、提交表更新 |
| `GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials` | 获取表访问凭证 |
| `POST /v1/{prefix}/tables/rename` | 重命名表 |
| `POST /v1/{prefix}/transactions/commit` | 多表事务提交 |
| `GET/POST /v1/{prefix}/namespaces/{namespace}/views` | 列表/创建 view |
| `GET/DELETE/POST /v1/{prefix}/namespaces/{namespace}/views/{view}` | 读 view、删 view、提交 view 更新 |
| `POST /v1/{prefix}/views/rename` | 重命名 view |
| `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics` | 上报 Iceberg metrics |
| `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/notifications` | 发送表通知 |

#### 功能特点

- 标准化程度最高
- 支持 namespace、table、view、commit、credentials、metrics
- 多引擎接入时最通用
- 这是 Polaris 作为 Iceberg Catalog 的核心协议

#### 使用示例

读取服务能力：

```bash
curl http://localhost:8181/api/catalog/v1/config?warehouse=demo
```

创建 namespace：

```bash
curl -X POST http://localhost:8181/api/catalog/v1/demo/namespaces \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": ["sales"]
  }'
```

### 3.4 Polaris Native Catalog API

这是 Polaris 在标准 Iceberg REST 之外补充的协议层，目前重点包括 Generic Table 和 Policy。

#### A. Generic Table API

| 端点 | 作用 |
| --- | --- |
| `GET /polaris/v1/{prefix}/namespaces/{namespace}/generic-tables` | 列出 generic table |
| `POST /polaris/v1/{prefix}/namespaces/{namespace}/generic-tables` | 创建 generic table |
| `GET /polaris/v1/{prefix}/namespaces/{namespace}/generic-tables/{generic-table}` | 读取 generic table |
| `DELETE /polaris/v1/{prefix}/namespaces/{namespace}/generic-tables/{generic-table}` | 删除 generic table |

使用示例：

```bash
curl -X POST http://localhost:8181/api/catalog/polaris/v1/demo/namespaces/sales/generic-tables \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "orders_delta",
    "format": "delta",
    "base-location": "s3://demo/sales/orders_delta",
    "doc": "delta example",
    "properties": {
      "table_type": "delta"
    }
  }'
  
curl -X POST "http://localhost:8181/api/catalog/polaris/v1/demo/namespaces/ml/generic-tables" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "name": "quora_questions",
      "format": "lance",
      "base-location": "s3://demo/ml/quora_questions.lance",
      "doc": "Lance table registered in Polaris",
      "properties": {
        "table_type": "lance"
      }
}'
```

#### B. Policy API

| 端点 | 作用 |
| --- | --- |
| `POST /polaris/v1/{prefix}/namespaces/{namespace}/policies` | 创建 policy |
| `GET /polaris/v1/{prefix}/namespaces/{namespace}/policies` | 列出 policy |
| `GET/PUT/DELETE /polaris/v1/{prefix}/namespaces/{namespace}/policies/{policy-name}` | 读/改/删 policy |
| `POST/DELETE /polaris/v1/{prefix}/namespaces/{namespace}/policies/{policy-name}/mappings` | attach/detach policy |
| `GET /polaris/v1/{prefix}/applicable-policies` | 查询某对象的生效 policy |

#### 功能特点

- 面向 Polaris 自己的治理扩展
- Generic Table 用于纳管非 Iceberg 表对象
- Policy 用于做治理、维护和继承规则

### 3.5 OAuth Token Endpoint

| 端点 | 作用 | 备注 |
| --- | --- | --- |
| `POST /v1/oauth/tokens` | 获取目录访问 token | 规范中已标记 deprecated for removal |

这意味着内部系统设计时，不应把它当成长期稳定的唯一鉴权入口；更合理的理解是：
- 它是 Polaris/Iceberg 生态兼容层的一部分
- 但长期方案应关注标准 OAuth/OIDC 体系与外部身份系统集成

### 3.6 不同 API 协议支持的数据对象与差异

| 协议 | 支持的数据对象 | 核心能力 | 与其他协议的差异 |
| --- | --- | --- | --- |
| Management API | catalog、principal、role、grant、external connection | 管理和治理 | 不直接进行表数据目录操作 |
| Iceberg REST Catalog API | Iceberg namespace/table/view | 标准目录协议、事务、凭证、视图 | 能力最完整、最标准 |
| Generic Table API | non-Iceberg table 的轻量元数据 | create/load/list/drop | 不提供完整事务与 schema 语义 |
| Policy API | policy 与映射关系 | 定义治理规则 | 面向治理，不面向表读写 |
| Notification API | table notification | 事件通知 | 辅助能力，不是主目录协议 |

---

## 4. 计算引擎如何接入 Polaris？

### 4.1 最常见的接入方式

Polaris 面向计算引擎，主要有 3 条接入路径。

| 接入路径 | 适用对象 | 说明 |
| --- | --- | --- |
| 标准 Iceberg REST 接入 | Spark、Flink、Trino、Doris、StarRocks、Dremio 等 | 这是最主流方式 |
| Polaris Spark Client | Spark | 既能管 Iceberg，也能接入 non-Iceberg generic table |
| Federation 间接接入 | 远端 Iceberg REST / Hive / Hadoop / BigQuery | Polaris 作为统一门面，远端系统仍是元数据真源 |

### 4.2 标准 Iceberg REST 接入

这条链路最简单：

```text
引擎 -> Iceberg REST 客户端 -> Polaris /api/catalog/v1/... -> Polaris 元数据与鉴权层
```

优点：
- 标准
- 多引擎通用
- 与 Iceberg 生态契合最好

典型场景：
- Flink / Trino / Spark / StarRocks 等以 Iceberg Catalog 的方式接 Polaris

### 4.3 Spark 插件接入

Polaris 为 Spark 提供了单独的 `org.apache.polaris.spark.SparkCatalog`。它会根据表格式做分流：

```text
Spark SQL
  -> org.apache.polaris.spark.SparkCatalog
      -> Iceberg 表：转给 Iceberg SparkCatalog
      -> Delta 表：转给 Polaris Generic Table + Delta helper
      -> Hudi 表：转给 Polaris Generic Table + Hudi helper
      -> Paimon 表：转给 Polaris Generic Table + Paimon helper
      -> 其他 generic 格式：走 Polaris Generic Table 基础路径
```

这也是 Polaris “一套 Spark Catalog 管多格式”的关键实现思路。

典型启动示例：

```bash
bin/spark-shell \
  --packages org.apache.polaris:polaris-spark-3.5_2.12:<version>,org.apache.iceberg:iceberg-aws-bundle:1.10.0,io.delta:delta-spark_2.12:3.3.1 \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,io.delta.sql.DeltaSparkSessionExtension \
  --conf spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog \
  --conf spark.sql.catalog.polaris=org.apache.polaris.spark.SparkCatalog \
  --conf spark.sql.catalog.polaris.uri=http://localhost:8181/api/catalog \
  --conf spark.sql.catalog.polaris.credential='root:secret' \
  --conf spark.sql.catalog.polaris.warehouse=demo \
  --conf spark.sql.catalog.polaris.scope='PRINCIPAL_ROLE:ALL' \
  --conf spark.sql.catalog.polaris.token-refresh-enabled=true
```

```java
  CREATE TABLE ... USING delta LOCATION 's3://...'

  Spark SQL
    -> SparkCatalog.createTable(...)
       -> 识别 provider=delta
       -> DeltaHelper.loadDeltaCatalog(polarisSparkCatalog)
          -> 动态创建 DeltaCatalog
          -> setDelegateCatalog(polarisSparkCatalog)
          -> 反射设 isUnityCatalog=true
       -> DeltaCatalog.createTable(...)
          -> 创建 Delta 目录和 _delta_log
          -> 需要 catalog 登记时调用 delegate
             -> PolarisSparkCatalog
                -> PolarisRESTCatalog
                   -> Polaris Generic Table API
                      -> 服务端保存 GenericTableEntity
    -> 返回 Spark Table 对象
```

### 4.4 典型例子

#### 例子 1：Flink/Trino 访问 Iceberg 表

```text
Flink/Trino
  -> Iceberg REST
  -> Polaris /api/catalog/v1/demo/...
  -> 读写 Iceberg table
```

#### 例子 2：Spark 管理 Delta 表

```text
Spark SQL
  -> Polaris SparkCatalog
  -> 识别 provider=delta
  -> 调用 Generic Table API 登记表对象
  -> Delta 自身元数据读写由 Delta 生态完成
```

#### 例子 3：Polaris 代理外部 Hive Metastore

```text
引擎
  -> Polaris
  -> External Catalog(connectionType=HIVE)
  -> Hive Metastore
```

这个场景下 Polaris 提供的是统一访问入口与治理层，而不是替代 HMS 成为最终元数据真源。

---

## 5. 模块图

### 5.1 总模块图

```text
+-------------------------------------------------------------------+
|                    Compute Engines / Clients                      |
| Spark | Flink | Trino | Doris | StarRocks | Dremio | REST         |
+-----------------------------------+-------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------+
|                        Polaris Unified Entry                      |
|                                                                   |
|  Management API: /api/management/v1                               |
|  Catalog API:    /api/catalog                                      |
|                                                                   |
|  Shared control plane:                                            |
|    catalog / namespace / table-like / auth / RBAC / policy        |
+-----------------------------------+-------------------------------+
                                    |
                    +---------------+---------------+
                    |                               |
                    v                               v
+----------------------------------+  +--------------------------------------+
| Iceberg Mainline                 |  | Generic Table Mainline               |
|                                  |  |                                      |
| API: /api/catalog/v1/...         |  | API: /api/catalog/polaris/v1/...    |
| Protocol: Iceberg REST           |  | Protocol: Polaris Native API         |
| Object: Iceberg table / view     |  | Object: Generic table                |
| Semantics owned by Polaris:      |  | Semantics owned by Polaris:          |
|   create/load/drop/rename        |  |   create/load/list/drop              |
|   commit / views / metrics       |  |   format/base-location/properties    |
|   credentials / transactions     |  |                                      |
+----------------+-----------------+  +-------------------+------------------+
                 |                                       |
                 v                                       v
+----------------------------------+  +--------------------------------------+
| Polaris persistence              |  | Polaris persistence                   |
| IcebergTableLikeEntity / View    |  | GenericTableEntity                   |
+----------------+-----------------+  +-------------------+------------------+
                 |                                       |
                 v                                       v
+----------------------------------+  +--------------------------------------+
| Object storage + credentials     |  | External format runtime / metadata   |
| Iceberg metadata / manifest /    |  | Delta _delta_log / Hudi timeline /   |
| data files                       |  | Paimon metadata / Lance metadata     |
+----------------+-----------------+  +-------------------+------------------+
                 |                                       |
                 +-------------------+-------------------+
                                     |
                                     v
+-------------------------------------------------------------------+
|                  Federation / External Catalogs                   |
| Iceberg REST | Hadoop | Hive | BigQuery                           |
| Note: federation is another ingress mode, not the same as         |
| native Iceberg / Generic mainline semantics                       |
+--------------------------------------------------------------+
```

### 5.2 Iceberg 表操作完整路径

先看 Iceberg，因为它是 Polaris 的主线能力。

```text
SQL / Engine
  -> Spark / Flink / Trino / Doris / StarRocks / Dremio
  -> Iceberg Catalog Client
  -> Polaris Catalog API
     /api/catalog/v1/...
  -> Polaris Iceberg REST Handlers
  -> 认证 / 鉴权 / 路径解析
     - catalog
     - namespace
     - iceberg table or iceberg view
  -> Iceberg 表元数据处理
     - create / load / drop / rename
     - commit
     - transactions/commit
     - views
     - metrics
     - credentials
  -> Polaris 持久层
     - IcebergTableLikeEntity / Iceberg View 对象
     - 相关目录元数据
  -> 对象存储访问控制
     - storage integration
     - credential vending
  -> Object Storage
     - Iceberg metadata files
     - manifest / manifest list
     - data files
```

这条路径的关键特点是：
- 引擎走的是标准 `Iceberg REST Catalog API`
- Polaris 深度参与表级元数据语义
- `commit`、`view`、`credentials`、`metrics` 都在这条协议线上

### 5.3 Generic 表操作完整路径

再看 Generic Table 路径。它的重点不是替代各表格式内部协议，而是把对象纳入 Polaris 统一控制面。

```text
SQL / Engine / Client
  -> SparkCatalog / 自定义客户端 / Lance Namespace 适配实现
  -> 按格式分流
     - Delta -> DeltaHelper -> DeltaCatalog
     - Hudi  -> HudiHelper  -> Hudi Catalog
     - Paimon -> PaimonHelper -> Paimon Catalog
     - Lance -> Lance Namespace Polaris implementation
     - 其他 generic 格式 -> 直接走 Polaris Generic Table 路径
  -> Polaris Catalog API
     /api/catalog/polaris/v1/.../generic-tables
  -> Polaris Native Handlers
  -> 认证 / 鉴权 / 路径解析
     - catalog
     - namespace
     - generic table
  -> Generic Table 元数据处理
     - create
     - load
     - list
     - drop
  -> Polaris 持久层
     - GenericTableEntity
     - format / base-location / properties
  -> 返回统一 catalog 对象给引擎
  -> 各格式自身生态继续管理内部元数据
     - Delta -> _delta_log
     - Hudi -> .hoodie timeline
     - Paimon -> paimon metadata
     - Lance -> lance table metadata
  -> Object Storage
     - 各格式自己的元数据文件
     - 各格式自己的数据文件
```

这条路径的关键特点是：
- Polaris 统一管理的是“对象登记、命名空间归属、权限治理、位置元数据”
- 各格式自己的事务日志和内部状态机仍由各自生态维护
- Generic Table API 当前核心能力是 `create/load/list/drop`

### 5.4 两条路径的分叉点

如果要用一句话解释 `Iceberg` 和 `Generic Table` 的分叉点，可以直接看这里：

```text
同样都是 catalog -> namespace -> table-like，
但一旦进入“表格式语义处理”阶段：

- Iceberg 路径：继续留在 Polaris 的 Iceberg REST 主线上
- Generic 路径：Polaris 只保留目录登记与治理控制，
  真正的格式内部元数据交回 Delta/Hudi/Paimon/Lance 等生态
```

也可以用一张极简对照图来记：

```text
Iceberg Table
  -> Polaris 深度托管
  -> REST catalog 语义完整
  -> commit / view / credentials / metrics 都在 Polaris 线上

Generic Table
  -> Polaris 轻量托管
  -> 统一 catalog entry
  -> 格式内部元数据不在 Polaris 内闭环
```

---

## 6. 内部开发最需要记住的能力边界

### 6.1 Iceberg 是主线，Generic Table 是扩展层

如果目标是“标准、多引擎、事务语义完整”，优先走 Iceberg REST。

如果目标是“把非 Iceberg 对象纳入统一 catalog / auth / policy / storage 控制面”，可以走 Generic Table。

### 6.2 Generic Table 更像目录登记和治理抽象

它适合做：
- 非 Iceberg 表对象登记
- 统一 namespace/catalog 管理
- 统一权限和策略绑定

它不适合被误解成：
- 通用事务型 catalog 协议
- 对所有格式都提供与 Iceberg 相同的 commit / schema / snapshot 语义

### 6.3 Spark 是多格式能力最强的入口

当前仓库里，多格式接入最完整的证据主要在 Spark 插件：
- 冰山路径直接走 Iceberg
- Delta 有最明确的文档支持
- Hudi、Paimon 在源码和测试中已有适配分支

因此，如果内部要验证“Polaris 如何一套入口管理多格式表”，Spark 是最适合演示和验证的落点。

### 6.4 Federation 更适合统一门面，不是替代远端系统

External catalog 场景下：
- Polaris 提供统一认证、统一授权、统一入口
- 远端 Catalog/Metastore 仍然可能是元数据真源

因此架构上应把 Polaris 视为“统一控制面/门面层”，而不是总会“接管所有元数据语义”。

---

## 7. 参考资料

仓库内：
- [`README.md`](D:\user\weixl\workspace\deepresearch\polaris\README.md)
- [`polaris-core/README.md`](D:\user\weixl\workspace\deepresearch\polaris\polaris-core\README.md)
- [`spec/README.md`](D:\user\weixl\workspace\deepresearch\polaris\spec\README.md)
- [`spec/polaris-management-service.yml`](D:\user\weixl\workspace\deepresearch\polaris\spec\polaris-management-service.yml)
- [`spec/polaris-catalog-service.yaml`](D:\user\weixl\workspace\deepresearch\polaris\spec\polaris-catalog-service.yaml)
- [`spec/polaris-catalog-apis/generic-tables-api.yaml`](D:\user\weixl\workspace\deepresearch\polaris\spec\polaris-catalog-apis\generic-tables-api.yaml)
- [`plugins/spark/README.md`](D:\user\weixl\workspace\deepresearch\polaris\plugins\spark\README.md)
- [`plugins/spark/v3.5/spark/src/main/java/org/apache/polaris/spark/SparkCatalog.java`](D:\user\weixl\workspace\deepresearch\polaris\plugins\spark\v3.5\spark\src\main\java\org\apache\polaris\spark\SparkCatalog.java)
- [`plugins/spark/v3.5/spark/src/main/java/org/apache/polaris/spark/PolarisRESTCatalog.java`](D:\user\weixl\workspace\deepresearch\polaris\plugins\spark\v3.5\spark\src\main\java\org\apache\polaris\spark\PolarisRESTCatalog.java)
- [`plugins/spark/v3.5/spark/src/main/java/org/apache/polaris/spark/utils/PolarisCatalogUtils.java`](D:\user\weixl\workspace\deepresearch\polaris\plugins\spark\v3.5\spark\src\main\java\org\apache\polaris\spark\utils\PolarisCatalogUtils.java)
- [`site/content/blog/2026/01/06/lance-integration.md`](D:\user\weixl\workspace\deepresearch\polaris\site\content\blog\2026\01\06\lance-integration.md)

官方外部资料：
- Apache Polaris 文档首页：https://polaris.apache.org/in-dev/unreleased/
- Generic Table：https://polaris.apache.org/in-dev/unreleased/generic-table/
- Polaris Spark Client：https://polaris.apache.org/in-dev/unreleased/polaris-spark-client/
- Iceberg REST Federation：https://polaris.apache.org/in-dev/unreleased/federation/iceberg-rest-federation/
- Hive Metastore Federation：https://polaris.apache.org/in-dev/unreleased/federation/hive-metastore-federation
- Lance 集成博客：https://polaris.apache.org/blog/2026/01/06/lance-integration/
