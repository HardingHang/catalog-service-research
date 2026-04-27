# Gravitino 多格式、多引擎支持调研报告

## 1. 调研范围

本文围绕以下 4 个问题展开：

1. Gravitino 支持哪些数据格式和元数据对象类型。
2. Gravitino 提供哪些 API 协议、对应哪些 REST 端点、不同协议之间的能力差异是什么。
3. 计算引擎如何接入 Gravitino，不同接入方式是否一致，以及统一 API 如何作用于不同格式对象。
4. 为实现多格式、多引擎支持，Gravitino 采用了什么架构策略，以及关键模块如何组织。

## 2. 执行摘要

从整体结构看，Gravitino 不是一个单独的 catalog service 端点，而是一套分层架构。

向上同时面对客户端、计算引擎和格式生态协议，向下再把统一元数据操作分发到不同格式实现和底层后端。

从摘要层面，可以先把它理解为四层：

```text
客户端 / 引擎
  -> 引擎接入层 / 专用协议层
  -> Gravitino 元数据统一控制
  -> 格式桥接与适配层
  -> 具体格式实现与后端
```

1. `客户端 / 引擎`
   - 这一层是请求发起方，包括应用程序、运维脚本，以及 `Spark`、`Flink`、`Trino` 等计算引擎。
   - 它们关心的是如何访问 catalog、执行 SQL、加载表和读取数据。
2. `引擎接入层 / 专用协议层`
   - 这一层负责把外部请求接入 Gravitino，包括 Spark connector，以及 `Iceberg REST API`、`Lance REST API` 这类专用协议入口。
   - 它解决的是“外部系统如何接进来”，而不是“元数据内部如何组织”。
3. `Gravitino 元数据统一控制`
   - 这一层是统一控制面，负责 `metalake`、`catalog`、`schema`、`table` 以及 `fileset`、`topic`、`model`、`function`、权限和治理对象的管理。
   - 它解决的是“如何用一套元数据模型统一管理不同对象”。
4. `格式桥接与适配层`
   - 这一层负责按 `Hive`、`Iceberg`、`Paimon`、`Hudi`、`JDBC`、`Lance` 等不同格式，把统一控制面的对象和操作翻译成具体实现能理解的调用。
   - 它解决的是“统一模型如何落到不同格式语义和后端接口”。
5. `具体格式实现与后端`
   - 这一层是真正保存和解释格式原生元数据与数据的地方，例如 Iceberg catalog backend、Lance dataset、Hive Metastore、JDBC backend，以及各自依赖的对象存储或文件系统。
   - 它解决的是“数据和格式原生元数据最终由谁存、谁解释、谁执行”。


#### 典型 SQL 示例

```sql
USE iceberg; -- 切换catalog
USE db; -- 切换schema

CREATE TABLE IF NOT EXISTS iceberg_scores (
  id INT,
  score INT
) USING iceberg;

INSERT INTO iceberg_scores VALUES (1, 95), (2, 88);

SELECT * FROM iceberg.db.iceberg_scores VERSION AS OF 123456789;
```

 SELECT 时发生了什么：

  1. Spark
      - 负责解析这条 SQL
      - 识别出这是一次 SELECT
      - 同时识别出 VERSION AS OF 123456789 是一条 time travel 查询
      - 生成执行计划
      
  2. Gravitino Spark connector
      - 负责把 iceberg.db.iceberg_scores 这个表标识解析到 Gravitino 管理的 catalog/schema/table
      - 通过统一元数据控制面找到这张表的元数据入口
      - 再把表加载过程桥接给底层 Iceberg catalog
      - Iceberg SparkCatalog / SparkTable
        - 真正理解 VERSION AS OF
        - 根据指定 snapshot/version 找到对应的 Iceberg 元数据版本
        - 读取对应的 metadata.json、manifest 等
        - 返回那个历史版本的数据视图

多格式支持能力：

```sql
// use hive catalog
USE hive;
CREATE DATABASE db;
USE db;
CREATE TABLE hive_students (id INT, name STRING);
INSERT INTO hive_students VALUES (1, 'Alice'), (2, 'Bob');

// use Iceberg catalog
USE iceberg;
USE db;
CREATE TABLE IF NOT EXISTS iceberg_scores (id INT, score INT) USING iceberg;
INSERT INTO iceberg_scores VALUES (1, 95), (2, 88);

// execute federation query between hive table and iceberg table
SELECT hs.name, is.score FROM hive.db.hive_students hs JOIN iceberg_scores is ON hs.id = is.id;
```

## 3. Gravitino 支持的数据格式与对象类型

本章结论：Gravitino 的支持范围必须同时从“对象类型”和“provider/格式”两个维度理解，统一 REST 覆盖对象最广，但专用协议只覆盖特定格式。

### 3.1 两个观察维度

Gravitino 的“支持范围”不能只按一种口径描述，至少要分两个维度：

1. 元数据对象类型：例如 `table`、`fileset`、`topic`、`model` 等。
2. 表格式或存储 provider：例如 `Hive`、`Iceberg`、`Paimon`、`Hudi`、基于 `JDBC` 的关系型系统、`Lance` 等。

### 3.2 对象类型分层总览

| 大类 | 代表对象或 provider | 主要访问路径 |
|---|---|---|
| 关系型与湖仓表对象 | Hive、Iceberg、Paimon、Hudi、JDBC catalogs、Lance | `Gravitino REST/OpenAPI`；Iceberg 走 `Iceberg REST`；Lance 走 `Lance REST` |
| 文件类元数据 | Fileset | `Gravitino REST/OpenAPI` |
| 消息类元数据 | Topic、Kafka-backed messaging catalogs | `Gravitino REST/OpenAPI` |
| 函数元数据 | Function | `Gravitino REST/OpenAPI` |
| 模型类元数据 | Model、version、alias、URI | `Gravitino REST/OpenAPI` |
| 治理与安全对象 | Tag、policy、owner、permission、credential、statistics | `Gravitino REST/OpenAPI` |

以iceberg、lance为例，Gravitino元数据组织如下：

```
  +--------------------------------------------------+
  | GravitinoTable                                   |
  +--------------------------------------------------+
  | name: String                                     |
  | namespace: catalog.schema                        |
  | columns: Column[]                                |
  | properties: Map<String, String>                  |
  | format: "iceberg" | "lance" | ...                |
  | location: String                                 |
  | owner/tag/policy/permission/...                  |
  +--------------------------------------------------+
  | load()                                           |
  | alter()                                          |
  | drop()                                           |
  +--------------------------------------------------+
                   |  dispatch by format
                   |
          +--------+--------+
          |                 |
  | IcebergTableMeta  |   | LanceDatasetMeta     |
  +-------------------+   +----------------------+
  | metadataLocation  |   | datasetLocation      |
  | currentSnapshotId |   | version              |
  | schema            |   | schema               |
  | partitionSpec     |   | column metadata      |
  | sortOrder         |   | index metadata       |
  | manifests         |   | storage options      |
  +-------------------+   +----------------------+
  | scan()            |   | scan()               |
  | timeTravel()      |   | vectorSearch()       |
  | snapshotMgmt()    |   | datasetVersioning()  |
  +-------------------+   +----------------------+
```

## 4. Gravitino 支持的 API 协议

本章结论：三套 API 的角色不同，统一 REST 负责广义元数据与治理，`Iceberg REST` 和 `Lance REST` 负责各自生态的协议兼容。

### 4.1 协议总览

| 协议 | 定位 | 主要覆盖范围 | 端点风格 |
|---|---|---|---|
| Gravitino REST/OpenAPI | 统一元数据与治理控制面 | 覆盖面最广，管理所有类型数据对象 | `/api/...` |
| Iceberg REST API | Iceberg 原生 catalog 协议兼容面 | Iceberg namespace、table、view、config | `/iceberg/v1/...` |
| Lance REST API | Lance 原生 namespace/table 协议面 | Lance namespace 和 table | `/lance/v1/...` |

### 4.2 Gravitino REST/OpenAPI

#### 定位

这是 Gravitino Server 的统一 API 面，也是当前仓库中唯一一套同时覆盖关系型、湖仓、`fileset`、`messaging`、`model` 和治理对象的服务接口。

#### 主要端点族

根据 `docs/open-api/openapi.yaml`，主要端点族包括：

1. 健康检查
   - `/api/health`
   - `/api/health/live`
   - `/api/health/ready`
2. Metalake
   - `/api/metalakes`
   - `/api/metalakes/{name}`
3. Catalog 与 Schema
   - `/api/metalakes/{metalake}/catalogs`
   - `/api/metalakes/{metalake}/catalogs/{catalog}
4. Table 与 Partition
   - `/api/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/tables`
   - `/api/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/tables/{table}`
5. Fileset
   - `/api/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/filesets`
   - `/api/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/filesets/{fileset}`
6. Topic
   - `/api/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/topics`
   - `/api/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/topics/{topic}`
7. Model
   - `/api/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/models`
   - `/api/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/models/{model}`
9. 治理与安全
   - `tags`
   - `policies`
   - `roles`
   - `owners`
   - `permissions`
   - `credentials`
   - `statistics`

#### 支持范围与边界

这套协议在元数据控制面上是“格式无关”的，但并不意味着所有对象具备相同的数据面语义。

典型差异包括：

1. `table` 可以被统一表示为元数据对象，但真实 IO 等行为根据类型由 provider 决定。
2. `fileset` 是文件集合元数据，不是表协议。
3. `function` 在统一 API 中有正式端点，支持 `list / register / get / alter / drop`，但它属于函数元数据管理，不属于表协议。
4. `topic` 和 `model` 也有自己的生命周期管理逻辑，不能和表对象等同理解。
5. 当前统一 REST OpenAPI 中没有公开 `views` 端点，因此不能把它描述为“统一 REST 已正式支持 view 管理”。

#### 使用示例

通过统一 API 创建 iceberg 表：

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "name":"scores",
    "comment":"example table",
    "columns":[
      {"name":"id","type":{"type":"integer"},"nullable":false},
      {"name":"score","type":{"type":"integer"},"nullable":true}
    ],
    "properties":{}
  }' \
  http://localhost:8090/api/metalakes/test/catalogs/iceberg/schemas/db/tables
```

通过统一 API 创建 lance 表：

```bash
 curl -X POST \
    -H "Content-Type: application/json" \
    -d '{
      "name": "lance_table",
      "comment": "Example Lance table created by unified API",
      "columns": [
        { "name": "id", "type": "integer", "comment": "Primary identifier", "nullable": false },
        { "name": "embedding", "type": "array", "elementType": "float", "nullable": true }
      ],
      "properties": {
        "format": "lance",
        "location": "s3://bucket/lance/schema/lance_table",
        "external": "true",
        "lance.creation-mode": "CREATE"
      }
    }' \
  http://localhost:8090/api/metalakes/test/catalogs/generic_lakehouse_lance_catalog/schemas/schema/tables
```

### 4.3 Iceberg REST API

#### 定位

Gravitino 的 Iceberg REST Server 遵循 Apache Iceberg REST Catalog 规范，本质上是一个 Iceberg 协议兼容服务。

#### 主要端点族

文档中给出的服务基地址为：

```text
http://$host:$port/iceberg/
```

结合文档示例和 Iceberg 规范，可以归纳出主要端点族：

1. Config
   - `/iceberg/v1/config`
2. Namespace
   - `/iceberg/v1/{catalog}/namespaces/...`
3. Table
   - `/iceberg/v1/{catalog}/namespaces/{ns}/tables/...`
4. View
   - `/iceberg/v1/{catalog}/namespaces/{ns}/views/...`

#### 支持的格式与对象

这套协议只面向 Iceberg。

| 对象 | 是否支持 |
|---|---|
| Iceberg namespace | 是 |
| Iceberg table | 是 |
| Iceberg view | 是，但不是 100% 完整实现 |
| Fileset | 否 |
| Topic | 否 |
| Model | 否 |
| 非 Iceberg 表 | 否 |

#### 功能边界

仓库文档说明：

1. 已支持大多数 namespace、table、view 接口。
2. 当前尚未实现：
   - multi table transaction
   - pagination
   - register view

还强调了以下能力：

1. 支持 Hive、JDBC、REST 等后端 catalog。
2. 支持面向 `S3`、`GCS`、`OSS`、`ADLS` 的 credential vending。
3. 支持 `OAuth2` 与 `HTTPS`。
4. 作为附属服务运行时支持访问控制。
5. 提供 metrics、cache、event listener 等机制。

#### 使用示例

在某个 namespace 下创建 Iceberg 表：

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "name":"scores",
    "schema":{
      "type":"struct",
      "schema-id":0,
      "fields":[
        {"id":1,"name":"id","required":true,"type":"int"},
        {"id":2,"name":"score","required":false,"type":"int"}
      ]
    }
  }' \
  http://localhost:9001/iceberg/v1/catalog1/namespaces/default/tables
```

```
  统一 API 路径
    -> Gravitino REST/OpenAPI
    -> 通用表管理模型
    -> Gravitino dispatcher
    -> IcebergCatalogOperations
    -> Iceberg catalog backend
    -> Iceberg metadata / data files
    -> Gravitino 统一控制面视图


  Iceberg REST 路径
    -> Iceberg REST API
    -> Iceberg 原生协议模型
    -> Iceberg REST dispatchers
    -> CatalogWrapperForREST
    -> Iceberg catalog backend
    -> Iceberg metadata / data files
```

这个示例对应的是 `Iceberg REST API` 路径，而不是统一 `REST/OpenAPI` 路径。它的关键特征是：

1. 请求先按 Iceberg 原生协议模型解析。
2. 服务端进入 `IcebergTableOperations -> IcebergTableOperationExecutor -> CatalogWrapperForREST`。
3. 最终直接调用 `Iceberg catalog backend` 创建表，并写入 Iceberg 原生元数据，例如 `metadata.json`。
4. 这条路径默认不会回到 Gravitino 统一 `createTable` 链路，因此不会像统一 API 建表那样在同一步骤中写入统一 `table_meta`。
5. 如果后续再通过统一 API 或 Spark connector 的 `loadTable` 路径访问这张表，Gravitino 可以再把它导入为统一视图；但这属于后续导入，不属于这次 `Iceberg REST` 建表动作本身。

### 4.4 Lance REST API

#### 定位

Lance REST 是仓库中的另一套专用服务。它不像统一 Gravitino API 那样覆盖广泛元数据对象，而是聚焦 `Lance namespace` 与 `Lance table` 的生命周期操作。

#### 主要端点族

根据 `lance/lance-rest-server` 模块中的资源类，可归纳为两大类端点：

1. Namespace 端点，前缀为 `/lance/v1/namespace`
   - `GET /lance/v1/namespace/{id}/list`
   - `POST /lance/v1/namespace/{id}/describe·
2. Table 端点，前缀为 `/lance/v1/table/{id}`
   - `POST /lance/v1/table/{id}/describe`
   - `POST /lance/v1/table/{id}/create`

#### 支持的格式与对象

这套协议只面向 Lance namespace 和 Lance table。

| 对象 | 是否支持 |
|---|---|
| Lance namespace | 是 |
| Lance table | 是 |
| 关系型表 | 否 |
| Fileset | 否 |
| Topic | 否 |
| Model | 否 |

#### 功能边界

Lance REST API 明显比统一 Gravitino API 更窄，但保留了 Lance 自身的生命周期特征。

从资源类和文档可以看出几个关键点：

1. `create-empty` 只保存表元数据和 location，不直接写入 Lance 存储。
2. `register` 用于把已存在的 Lance 表位置注册到元数据中。
3. `create` 支持通过 Arrow stream 内容创建表。
4. `alter_columns` 当前有明确限制，仅支持列重命名。
5. 当以 Gravitino 作为后端时，namespace 层级仍受其 `catalog -> schema -> table` 模型约束。

#### 使用示例

创建 Lance 表：

```bash
  curl -X POST \
    "http://localhost:9101/lance/v1/table/lance_catalog%24schema%24table03/create" \
    -H "Content-Type: application/vnd.apache.arrow.stream" \
    -H "x-lance-table-location: /tmp/lance_catalog/schema/table03" \
    -H "x-lance-table-properties: {}" \
    --data-binary "@${ARROW_FILE}"
```

```bash
  统一 API ----------------------\
                                 \
                                  -> GenericCatalogOperations
  Lance REST API -> Lance bridge /    -> LanceTableOperations
                                       -> Dataset.create(...)
                                       -> Gravitino metadata store
```

这个示例对应的是 `Lance REST API` 路径。它和 `Iceberg REST API` 的关键差异在于，这条路径会桥接回 Gravitino 的通用建表接口：
1. 请求先进入 `Lance REST` 资源类，解析 Arrow stream、location 和 Lance 专用属性。
2. 然后进入 `GravitinoLanceTableOperations`，并调用 `catalog.asTableCatalog().createTable(...)`。
3. 后续进入 `GenericCatalogOperations -> LanceTableOperations`。
4. `LanceTableOperations` 会先执行 `Dataset.create(...)` 创建底层 Lance dataset，再调用 `super.createTable(...)` 写入 Gravitino 统一表元数据。
5. 因此，这条 `Lance REST` 建表路径不仅创建 Lance 原生对象，也会在同一步骤中写入统一 `table_meta`。

## 5. 不同协议对比

本章结论：统一 REST 覆盖面最广，但不能替代 `Iceberg REST` 和 `Lance REST` 的格式原生语义；三者是分层互补，而不是一套兼容另一套。

### 5.1 统一了什么 / 没统一什么

| 维度 | 已统一 | 未统一 |
|---|---|---|
| 对象层级 | `metalake -> catalog -> schema -> object` | 不同格式内部更细的专用层级语义 |
| 元数据生命周期 | 统一的创建、加载、更新、删除、查询框架 | 各格式专用对象的完整生命周期细节 |
| 治理能力 | `tag`、`policy`、`owner`、`permission`、`statistics`、`credential` | 格式原生协议中的能力协商或客户端专用语义 |
| 表对象抽象 | 可统一表示为 Gravitino 管理的表元数据对象 | 不同格式的数据面语义、原生 request/response 结构 |
| 引擎接入思路 | 都以 Gravitino 为控制面入口进行集成 | 各引擎使用的 SPI、catalog 机制、运行时接口并不相同 |
| 专用协议 | 都纳入 Gravitino 的整体架构中 | `Iceberg REST` 和 `Lance REST` 不会被统一 REST 完全替代 |

更直接地说，Gravitino 统一的是“控制面”，没有统一的是“所有格式的原生协议和所有引擎的原生运行时语义”。

### 5.2 各自独有能力

| API | 该 API 独有或最有代表性的能力 |
|---|---|
| Gravitino REST/OpenAPI | `metalake` 管理、catalog/provider 管理、`fileset`、`topic`、`function`、`model`、`tag`、`policy`、`owner`、`permission`、`credential`、`statistics` 等统一治理能力 |
| Iceberg REST | `Iceberg /v1/config`、Iceberg 原生 namespace/table/view 请求与响应模型、Iceberg 客户端直接兼容、view 专用 REST 端点、面向 Iceberg 生态的 credential vending 与协议级能力声明 |
| Lance REST | Lance namespace/table 原生端点、`create-empty`、`register`、`deregister`、基于 Arrow stream 的创建路径、`drop_columns` / `alter_columns` 等 Lance 特有表生命周期能力 |

### 5.3 共有能力

严格来说，这三套 API 的“共有能力”只存在于它们共同覆盖的那一小部分对象生命周期上，而不是端点完全一致。

| 能力 | Gravitino REST/OpenAPI | Iceberg REST | Lance REST |
|---|---|---|---|
| 容器级对象列举 | 是，面向 metalake/catalog/schema 等层级 | 是，面向 namespace | 是，面向 namespace |
| 表或表状对象创建 | 是，面向统一 table 元数据 | 是，面向 Iceberg table | 是，面向 Lance table |
| 表或表状对象加载/描述 | 是 | 是 | 是 |
| 表或表状对象删除 | 是 | 是 | 是 |
| 存在性检查 | 一般通过统一对象查询路径实现 | 是 | 是 |
| 专用 view 生命周期 | 否，统一 REST 当前无公开 view 端点 | 是 | 否 |
| 专用 governance 生命周期 | 是 | 否 | 否 |

## 6. 计算引擎接入 Gravitino

本章结论：多引擎都能接入 Gravitino 统一 API，但接入机制不统一；`Spark` 最能体现“统一控制面 + provider 原生数据面”的组合方式。

### 6.1 多引擎接入总览

| 引擎 | 接入机制 | 实际连接到什么 | 说明 |
|---|---|---|---|
| Spark | DataSourceV2 connector + plugin | Gravitino 统一元数据面 + 对应格式的 Spark catalog 实现 | 最适合做深入分析 |
| Flink | Catalog Store | 由 Gravitino 管理并加载到 Flink 的 catalog | 更偏 catalog 级集成 |
| Trino | 动态 catalog 管理 | 将 Gravitino catalog 转换为 Trino runtime catalog | 更偏 catalog 同步与装载 |

### 6.2 Spark connector 详细分析

#### 定位

Spark connector 是理解 Gravitino 多格式策略最好的例子，因为它并没有试图替代底层 provider 的数据面逻辑，而是把两部分组合起来：

1. Gravitino 的元数据操作。
2. Spark 原生或对应格式的 catalog/IO 实现。

#### 配置路径

`spark-connector` 模块中的核心配置类声明了以下关键参数：

1. `spark.sql.gravitino.uri`
2. `spark.sql.gravitino.metalake`
3. `spark.sql.gravitino.enableIcebergSupport`
4. `spark.sql.gravitino.enablePaimonSupport`
5. `spark.sql.gravitino.client.*`

仓库文档还要求启用：

1. `spark.plugins=org.apache.gravitino.spark.connector.plugin.GravitinoSparkPlugin`
2. `spark.sql.gravitino.uri=...`
3. `spark.sql.gravitino.metalake=...`

```bash
./bin/spark-sql \
  --conf spark.plugins="org.apache.gravitino.spark.connector.plugin.GravitinoSparkPlugin" \
  --conf spark.sql.gravitino.uri=http://127.0.0.1:8090 \
  --conf spark.sql.gravitino.metalake=test \
  --conf spark.sql.gravitino.enableIcebergSupport=true \
  --conf spark.sql.gravitino.enablePaimonSupport=true
```

#### 调用链路图

下面这张图描述的是 `Spark 通过 Gravitino Spark connector 接入` 的主链路。

```text
[Spark]
  Spark SQL / DataFrame API
    -> Spark Catalog V2 interfaces
    -> 调用已注册的 catalog/table 接口
    -> GravitinoIcebergCatalog
    -> BaseCatalog 通用桥接逻辑
       -> 把 Spark 的 Identifier / StructType / Transform / TableChange
          转成 Gravitino 元数据模型
       -> 分成两条调用线：

          A. 元数据控制面
             -> Gravitino catalog client
             -> Gravitino REST/OpenAPI
             -> Gravitino Server 中的 Catalog / Schema / Table 元数据管理

          B. Iceberg 数据面
             -> Iceberg SparkCatalog
             -> Iceberg SparkTable
             -> scan/read/write、time travel、procedure 等原生 Iceberg 语义

       -> BaseCatalog 把两条线的结果组装成 SparkIcebergTable
    -> SparkIcebergTable
       -> 用 GravitinoTableInfoHelper 向 Spark 返回 schema / properties / partitioning
       -> 把 scan/read 等数据面能力委托给底层 Iceberg SparkTable
    -> Spark 拿到 SparkIcebergTable 后继续执行查询、插入、优化等流程
```

这条链路里使用的是：

1. `Gravitino REST/OpenAPI`
   - 负责访问 Gravitino Server，处理元数据和治理信息。
2.  `SparkIcebergTable` 并不是一个“只带 Gravitino 元数据的壳”，而是一个复合对象
   - 把 Gravitino 元数据暴露给 Spark
      - `name()`
      - `schema()`
      - `properties()`
      - `partitioning()`
      - 这些信息由 `GravitinoTableInfoHelper` 根据 Gravitino table 元数据生成
   - 把真实数据面行为委托给底层 Iceberg `SparkTable`
      - 例如 `newScanBuilder(...)` 直接委托到底层 Iceberg `SparkTable`

### 6.3 Flink 总览

Flink connector 使用的是 `Flink Catalog Store` 机制，而不是 Spark 那套 `DataSourceV2` 路径。

仓库文档说明：

1. 它实现了 Flink Catalog Store。
2. 它支持 Hive、Iceberg、Paimon 和 JDBC catalogs。
3. 它可以把 Gravitino-backed store 与内存 session catalog store 组合使用。

因此，Flink 的接入更偏向“catalog 装载与解析”，而不是“统一 SQL 运行时 API”。

代表性配置：

```yaml
table.catalog-store.kind: gravitino
table.catalog-store.gravitino.gravitino.metalake: metalake_demo
table.catalog-store.gravitino.gravitino.uri: http://localhost:8090
```

### 6.4 Trino 总览

Trino connector 使用的是 Trino 的动态 catalog 管理机制。

仓库文档说明：

1. Trino 会从 Gravitino 加载 catalogs。
2. connector 会生成 `CREATE CATALOG` 语句并在 Trino 内执行。
3. 当 Gravitino 中的元数据变化后，Trino 会在文档说明的延迟窗口后完成更新。

因此，Trino 的接入重点是把 Gravitino catalog 同步成 Trino 运行时 catalog，而不是暴露一套新的 Gravitino 原生 SQL 执行模型。

代表性配置：

```properties
connector.name=gravitino
gravitino.metalake=test
gravitino.uri=http://localhost:8090
```

## 7. 不同 API 接入方式


| 模式 | 例子 | 引擎或客户端期望的能力 |
|---|---|---|
| 统一元数据 connector | Spark connector、Flink connector、Trino connector | 通过各自引擎扩展点接入 Gravitino |
| 原生格式协议 | Iceberg 客户端或支持 Iceberg 的引擎 | 直接要求 Iceberg 协议兼容 |
| 原生格式协议 | 支持 Lance 的客户端或引擎 | 直接要求 Lance namespace/table 协议 |

## 8. 一套统一 API 如何操作不同格式的对象

从仓库实现来看，Gravitino 的统一方式不是“让所有表和对象行为完全一样”，而是“统一元数据控制面，再把具体行为分发到具体格式实现”。

### 8.1 统一点在哪里

真正被统一的是：

1. 统一的对象标识和命名体系。
2. 统一的层级结构：`metalake -> catalog -> schema -> object`。
3. 统一的元数据生命周期概念。
4. 统一的治理面：标签、权限、owner、policy、statistics、credential 等。

### 8.2 操作模式

以表对象为例，典型流程是：

1. 引擎或客户端发起一个表操作。
2. connector 或 server 先把引擎原生类型转换为 Gravitino 的元数据类型。
3. Gravitino 元数据 API 创建、加载或更新表对象。
4. 对应格式的 catalog 或 wrapper 继续执行真正的数据面语义和 IO 操作。

这在 Spark connector 的 `BaseCatalog.createTable`、`BaseCatalog.loadTable` 等路径中体现得很明确：元数据操作和 provider-specific table 加载是组合关系，而不是替代关系。

## 9. 为实现多格式、多引擎支持，Gravitino 采用了什么策略

本章结论：Gravitino 的核心策略是把控制面统一起来，同时保留格式和引擎各自的原生实现边界，而不是强行做成单一实现。

可以概括为四点：

1. 一个统一元数据控制面。
2. 多种格式后端实现。
3. 多个专用协议入口。
4. 多个引擎接入实现。

## 10. 模块图

本章结论：从模块关系看，Gravitino 采用的是“客户端/引擎 -> 协议或 connector -> 控制面 -> 格式实现 -> 后端”的层次结构。

### 10.1 总模块图

```text
+--------------------------------------------------------------+
| 客户端与计算引擎                                              |
|  - Gravitino API clients                                     |
|  - Spark                                                     |
|  - Flink                                                     |
|  - Trino                                                     |
|  - Iceberg clients                                           |
|  - Lance clients                                             |
+-------------------------+------------------------------------+
                          |
                          v
+--------------------------------------------------------------+
| 协议与引擎接入层                                              |
|  - Gravitino REST/OpenAPI server                             |
|  - Iceberg REST server                                       |
|  - Lance REST server                                         |
|  - Spark connector/plugin                                    |
|  - Flink connector                                           |
|  - Trino connector                                           |
+-------------------------+------------------------------------+
                          |
                          v
+--------------------------------------------------------------+
| Gravitino 控制面                                              |
|  - metalake/catalog/schema/object 模型                       |
|  - 元数据生命周期 API                                         |
|  - tag/policy/owner/permission/statistics/credential 逻辑    |
+-------------------------+------------------------------------+
                          |
                          v
+--------------------------------------------------------------+
| Provider / Adapter 层（格式语义分派与后端桥接）               |
|  - Provider: 按 Hive/Iceberg/Paimon/Hudi/JDBC/Lance 分派语义 |
|  - Adapter: 统一模型 <-> 真实格式后端 API / 引擎对象转换     |
|  - 包含 provider-specific logic、catalog wrapper、table wrapper |
+-------------------------+------------------------------------+
                          |
                          v
+--------------------------------------------------------------+
| 元数据/数据后端                                                |
|  - Hive Metastore                                            |
|  - JDBC databases                                            |
|  - Iceberg catalog backends                                  |
|  - Object stores / HDFS                                      |
|  - Lance table storage                                       |
+--------------------------------------------------------------+
```

这里的 `Provider / Adapter` 是报告中的抽象分层，不是仓库中的单一模块名。其职责可以拆成两部分：

1. `Provider`
   - 根据 catalog 的 `type/provider` 判断当前对象属于哪种格式语义，例如 `Hive`、`Iceberg`、`Paimon`、`Hudi`、`JDBC`、`Lance`。
   - 决定该对象支持哪些能力边界，以及后续应当走哪套后端实现。
   - 保留格式特有语义，例如 Iceberg 的 view 语义、Lance 的 `register` / `deregister` / `create-empty`。
2. `Adapter / Wrapper`
   - 把 Gravitino 的统一控制面模型翻译为具体后端、协议或引擎可以理解的对象和调用。
   - 典型形式包括 catalog wrapper、table wrapper、Spark connector 中的 provider-specific catalog/table。
   - 负责把统一的 namespace/table/schema 操作转换成后端 API 调用，并把返回结果包装成上层接口需要的对象。

可以把总路径概括为：

```text
统一控制面
  -> Provider 判断当前对象应按哪种格式语义处理
  -> Adapter / Wrapper 把统一操作转换成具体后端调用
  -> 最终落到 Hive Metastore / Iceberg catalog / JDBC / Lance storage
```

### 10.2 关键功能模块图：Spark 路径

```text
Spark SQL
  -> GravitinoSparkPlugin
  -> Spark 中注册的 Gravitino catalogs
  -> BaseCatalog
     -> Gravitino catalog client 处理元数据
     -> provider 对应 Spark catalog 处理 IO
        -> HiveTableCatalog / SparkCatalog / JDBC catalog / Paimon catalog
  -> Spark table wrapper 组合两类能力
```

### 10.3 关键功能模块图：Iceberg REST 路径

```text
Iceberg client 或 engine
  -> Gravitino Iceberg REST server
  -> Iceberg namespace/table/view operation dispatchers
  -> Catalog wrapper for REST
  -> Iceberg backend catalog
     -> memory / Hive / JDBC / 上游 REST backend
```

### 10.4 关键功能模块图：Lance REST 路径

```text
Lance client 或 engine
  -> Gravitino Lance REST server
  -> LanceNamespaceOperations / LanceTableOperations
  -> NamespaceWrapper
  -> GravitinoLanceNamespaceWrapper
  -> Gravitino server metadata plane
  -> Lance-specific table operations and storage coordination
```

## 11. 直接回答

本章结论：前文的分析可以直接收敛为四个回答，其中统一控制面、专用协议面和引擎接入面的边界必须同时看。

### 11.1 Gravitino 支持哪些数据格式？提供哪些 API 协议操作这些数据？

Gravitino 支持两大类内容：

1. 表格式或 provider：`Hive`、`Iceberg`、`Paimon`、`Hudi`、基于 `JDBC` 的关系型 catalogs、`Lance`。
2. 非表对象：`Fileset`、`Topic`、`Model`、`Function`，以及治理类对象。

协议映射关系是：

1. 覆盖面最广的是 `Gravitino REST/OpenAPI`。
2. `Iceberg REST` 只覆盖 Iceberg 生态对象。
3. `Lance REST` 只覆盖 Lance 生态对象。

### 11.2 Gravitino 支持哪些 API 协议，有哪些 REST 端点？不同协议支持哪些格式？功能有什么区别？

当前仓库中应重点关注三类协议：

1. `Gravitino REST/OpenAPI`
2. `Iceberg REST API`
3. `Lance REST API`

它们的区别是：

1. `Gravitino REST/OpenAPI` 覆盖对象最广，适合统一元数据和治理，并且当前确实支持 `function` 的 `list / register / get / alter / drop`。
2. `Iceberg REST` 面向 Iceberg 原生协议兼容，能力集中在 namespace/table/view/config。
3. `Lance REST` 面向 Lance namespace/table 生命周期，能力更窄但更贴近 Lance 生态。
4. 三者并非互相完全可替代，统一 API 不能完整兼容 Iceberg 和 Lance 的专用协议能力。

### 11.3 计算引擎如何接入 Gravitino？不同 API 接入方式一样吗？怎么通过一套统一 API 对不同格式对象进行操作？

不一样。

1. `Spark` 通过 connector/plugin 接入，是最典型的深入样例。
2. `Flink` 通过 Catalog Store 接入。
3. `Trino` 通过动态 catalog 管理接入。
4. 支持 Iceberg 的工具可以直接走 `Iceberg REST`。
5. 支持 Lance 的工具可以直接走 `Lance REST`。

统一 API 真正统一的是元数据控制面。具体到表操作，通常是：

1. 先用统一 API 完成 catalog/schema/table 的元数据定位和生命周期管理。
2. 再把真正的数据面语义委托给对应格式的 provider、wrapper 或引擎原生 catalog。

### 11.4 为了实现多格式、多引擎支持，Gravitino 采用了什么策略？具体怎么实现的？

核心策略是：

1. 把元数据和治理能力集中在统一控制面。
2. 把格式特有语义保留在 provider adapter 中。
3. 在生态需要的地方提供专用协议 facade，例如 `Iceberg REST` 和 `Lance REST`。
4. 在引擎侧通过各自原生扩展点接入，而不是强制一套统一运行时 API。

## 12. 结论

本章结论：Gravitino 的价值不在于消灭所有格式差异，而在于用统一控制面组织多格式、多协议和多引擎。

最关键的判断是：

Gravitino 的目标不是消灭格式差异，而是把元数据控制、治理、catalog 管理收敛到统一控制面，同时在必要时继续说各个生态的“原生语言”。

因此：

1. 统一 `REST/OpenAPI` 很宽，但它统一的主要是控制面。
2. `Iceberg` 和 `Lance` 分别保留了专用 REST 协议面。
3. `Spark`、`Flink`、`Trino` 则通过各自不同的引擎扩展机制接入。

如果要理解当前仓库里“一个元数据权威源如何支撑多格式、多引擎访问”，`Spark` 是最适合深入分析的代表路径。
