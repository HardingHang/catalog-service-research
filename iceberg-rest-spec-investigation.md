# Apache Iceberg REST Catalog Spec 调研

## A. 定位与设计目标

- **发起方**：Apache Iceberg 社区（ASF 顶级项目）
- **定位**：它不是一款具体产品，而是 **Lakehouse 表格式元数据访问的事实标准协议**。所有现代 Iceberg Catalog（Polaris、LakeKeeper、Nessie、Gravitino 等）都围绕该 Spec 做兼容或扩展。
- **核心目标**：
  1. 将元数据管理从计算引擎中解耦，使 Spark / Flink / Trino / DuckDB 等能通过统一协议访问 Iceberg 表。
  2. 用无状态的 REST 交互替代有状态的 HMS Thrift 连接，降低多引擎集成成本。
  3. 通过 Endpoint 广告机制（`config` 返回 `endpoints`）实现渐进式能力发现，允许服务器只实现子集。
- **非目标**：
  - 不负责数据发现/治理（血缘、数据质量、资产目录）——那是 DataHub / OpenMetadata 的范畴。
  - 不规定底层持久化实现（RDBMS / KV / 对象存储均可）。
  - 不解决跨表格式（Iceberg ↔ Delta）转换——这是 XTable / UniForm 的工作。

---

## B. 核心概念与元数据模型

Iceberg REST Catalog Spec 的元数据模型与 Iceberg 表格式规范完全对齐：

| 层级 | 说明 |
|---|---|
| **Catalog** | 顶层隔离单元，对应一个 REST 服务实例 |
| **Namespace** | 表的逻辑分组，支持层级嵌套（`["db", "schema"]`） |
| **Table** | Iceberg 表实体，包含 Schema、Partition Spec、Snapshot 等 |
| **View** | SQL 视图实体（Extension，非默认端点） |

Spec 本身**没有**将 Function / Model / Feature / Volume 定义为一等公民——这些属于 Unity Catalog、Gravitino 等上层扩展。但 Spec 在以下位置预留了扩展空间：
- `properties` 字段：Namespace / Table / View 均可携带任意 K-V 属性。
- `endpoints` 广告机制：服务器可声明自定义端点。
- `requirements` / `updates` 的抽象：事务提交请求采用结构化 JSON Patch 风格，便于新增更新类型。

---

## C. 架构与关键设计

### 三层交互模型

```
计算引擎 (Spark/Flink/Trino)
    ↓ REST/HTTP
REST Catalog Server
    ↓ 内部协议
底层元数据存储 (RDBMS / KV / OSS)
```

### 核心机制

1. **Config 先行**：客户端初始化时必须先调用 `GET /v1/config`，服务端返回 `defaults`、`overrides` 以及可选的 `endpoints` 列表。
2. **Endpoint 广告**：若服务端不返回 `endpoints`，客户端默认假定支持 14 个基础端点（Namespace + Table + Register + Rename + Metrics + Transaction）。其余能力（Views、Scan Planning、Credentials 等）必须显式声明。
3. **无状态协议**：除 Scan Planning 的异步任务状态外，每个请求都是独立的，天然支持水平扩展。
4. **乐观并发控制（OCC）**：表更新通过 `updateTable` 提交 `requirements` + `updates`，服务端校验 requirements 失败时返回 409/400。

### 存储优化调度架构

**Spec 本身不定义 Clustering / Compaction / Stats 的调度接口**。这些动作通常发生在：
- **引擎侧**：Spark 作业主动执行 `REWRITE DATA`、`OPTIMIZE`。
- **Catalog 侧扩展**：部分商业实现（如 Databricks）在 Catalog 层维护优化策略元数据，但不在 REST Spec 中暴露。

**启示**：若自研 Catalog 希望统一编排存储优化，需在 Spec 基础上定义私有扩展端点（如 `POST /v1/{prefix}/tables/{table}/optimize`）。

---

## D. 协议与接口

### 对外协议

| 协议 | 说明 |
|---|---|
| **REST/OpenAPI** | 核心协议，YAML 定义位于 `iceberg/open-api/rest-catalog-open-api.yaml` |
| **HMS Thrift** | Spec 本身不兼容 HMS；兼容性由底层存储层或桥接层解决 |
| **gRPC / JDBC** | 不在 Spec 范围内 |

### 认证协议

Spec 在 OpenAPI 层面定义了 `OAuth2 (clientCredentials, password)` 和 `Bearer` 安全方案。但：
- `POST /v1/oauth/tokens` 已被标记为 **DEPRECATED for REMOVAL**，不推荐实现。
- 实际生产环境多采用外部 IAM/OAuth2 + Bearer Token 注入。

### Credential Vending

- **Endpoint**：`GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials`
- **作用**：下发对象存储临时凭证（如 AWS STS、Azure SAS）。
- **状态**：属于 Extension Endpoint，参考实现中 `CatalogHandlers` **未实现**该逻辑，需由具体服务端自行补充。

### Remote Signing

- **Endpoint**：`POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/sign`
- **作用**：服务端远程签名对象存储请求（如 S3 预签名 URL）。
- **状态**：出现在 OpenAPI YAML 中，但 Java 客户端 `Endpoint.java` 中**尚未包含**该常量，说明生态支持尚不成熟。

---

## E. 功能矩阵（Endpoint 级能力清单）

### E.1 配置与认证

| Method | Path | 性质 | 说明 |
|---|---|---|---|
| `GET` | `/v1/config` | **默认/必选** | 所有客户端初始化入口；返回 defaults、overrides、endpoints |
| `POST` | `/v1/oauth/tokens` | **已废弃** | 不推荐使用 |

### E.2 Namespace 管理

| Method | Path | 性质 | 说明 |
|---|---|---|---|
| `GET` | `/v1/{prefix}/namespaces` | 默认 | 列出顶层或子 Namespace |
| `POST` | `/v1/{prefix}/namespaces` | 默认 | 创建 Namespace |
| `GET` | `/v1/{prefix}/namespaces/{namespace}` | 默认 | 加载 Namespace 元数据 |
| `HEAD` | `/v1/{prefix}/namespaces/{namespace}` | 默认 | 检查存在性 |
| `DELETE` | `/v1/{prefix}/namespaces/{namespace}` | 默认 | 删除（要求为空） |
| `POST` | `/v1/{prefix}/namespaces/{namespace}/properties` | 默认 | 设置/删除属性；**服务端可选实现** |

### E.3 Table 管理

| Method | Path | 性质 | 说明 |
|---|---|---|---|
| `GET` | `/v1/{prefix}/namespaces/{namespace}/tables` | 默认 | 列出表 |
| `POST` | `/v1/{prefix}/namespaces/{namespace}/tables` | 默认 | 创建表 |
| `GET` | `/v1/{prefix}/namespaces/{namespace}/tables/{table}` | 默认 | 加载表（返回 metadata-location、metadata、config） |
| `POST` | `/v1/{prefix}/namespaces/{namespace}/tables/{table}` | 默认 | 更新表（提交 requirements + updates） |
| `DELETE` | `/v1/{prefix}/namespaces/{namespace}/tables/{table}` | 默认 | 删除表 |
| `HEAD` | `/v1/{prefix}/namespaces/{namespace}/tables/{table}` | 默认 | 检查存在性 |
| `POST` | `/v1/{prefix}/namespaces/{namespace}/register` | 默认 | 用已有 metadata file location 注册表 |
| `POST` | `/v1/{prefix}/tables/rename` | 默认 | 重命名表 |
| `POST` | `/v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics` | 默认 | 上报表级指标（服务端可为 no-op） |

### E.4 跨表事务（重要扩展点）

| Method | Path | 性质 | 说明 |
|---|---|---|---|
| `POST` | `/v1/{prefix}/transactions/commit` | 默认 | **原子提交多个表的更新**。这是目前 Spec 中唯一支持"跨表事务"的端点。请求体为 `CommitTransactionRequest`，包含多个 `TableCommit`。 |

**限制**：仅支持多表的原子元数据提交，不提供两阶段提交或分布式事务协调器语义。

### E.5 View 管理（Extension）

| Method | Path | 性质 | 说明 |
|---|---|---|---|
| `GET` | `/v1/{prefix}/namespaces/{namespace}/views` | 扩展 | 列出视图 |
| `POST` | `/v1/{prefix}/namespaces/{namespace}/views` | 扩展 | 创建视图 |
| `GET` | `/v1/{prefix}/namespaces/{namespace}/views/{view}` | 扩展 | 加载视图 |
| `POST` | `/v1/{prefix}/namespaces/{namespace}/views/{view}` | 扩展 | 更新/替换视图 |
| `DELETE` | `/v1/{prefix}/namespaces/{namespace}/views/{view}` | 扩展 | 删除视图 |
| `HEAD` | `/v1/{prefix}/namespaces/{namespace}/views/{view}` | 扩展 | 检查存在性 |
| `POST` | `/v1/{prefix}/views/rename` | 扩展 | 重命名视图 |
| `POST` | `/v1/{prefix}/namespaces/{namespace}/register-view` | 扩展 | 注册视图 |

### E.6 Scan Planning（Extension）

| Method | Path | 性质 | 说明 |
|---|---|---|---|
| `POST` | `/v1/{prefix}/namespaces/{namespace}/tables/{table}/plan` | 扩展 | 提交扫描计划请求 |
| `GET` | `/v1/{prefix}/namespaces/{namespace}/tables/{table}/plan/{plan-id}` | 扩展 | 拉取计划结果（同步/异步） |
| `DELETE` | `/v1/{prefix}/namespaces/{namespace}/tables/{table}/plan/{plan-id}` | 扩展 | 取消计划 |
| `POST` | `/v1/{prefix}/namespaces/{namespace}/tables/{table}/tasks` | 扩展 | 获取扫描任务分页结果 |

**设计细节**：
- 客户端通过 `config` 中的 `planning-mode`（或自身配置）决定采用 `LOCAL` 还是 `SERVER` 模式。
- `SERVER` 模式下，服务端负责执行 Snapshot 选择、Filter 下推、File Scan Task 生成，可显著减少客户端与元数据存储的交互次数（对远程 Catalog 场景意义重大）。
- Java 参考实现 `CatalogHandlers` 中包含了完整的内存状态管理（`InMemoryPlanningState`），说明该扩展已有成熟的服务端参考实现。

### E.7 凭证下发与远程签名（Extension）

| Method | Path | 性质 | 说明 |
|---|---|---|---|
| `GET` | `/v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials` | 扩展 | 获取对象存储临时凭证；支持 `planId` 和 `referenced-by` 参数 |
| `POST` | `/v1/{prefix}/namespaces/{namespace}/tables/{table}/sign` | 扩展 | 远程签名请求；生态支持度低 |

---

## F. 非功能特性

| 特性 | 说明 |
|---|---|
| **性能** | Spec 本身不保证性能；但 Scan Planning 的 `SERVER` 模式是专门为降低高延迟网络下的元数据加载而设计的 |
| **可用性** | 无状态设计天然支持多实例负载均衡；失败可重试 |
| **扩展性** | Endpoint 广告 + properties 扩展使协议具有良好的前向兼容能力 |
| **安全** | 依赖传输层 TLS + Bearer Token；无内置加密/审计定义 |
| **多租户** | 通过 `prefix` 路径参数实现逻辑隔离（如 `/v1/tenant-1/namespaces/...`） |

---

## G. 生态与集成

### 客户端支持

| 引擎/客户端 | REST Catalog 支持情况 |
|---|---|
| **Spark** | 内置 `spark.sql.catalog.*.type=rest`；完全支持 |
| **Flink** | 通过 `catalog-impl=org.apache.iceberg.flink.FlinkCatalog` + REST 配置支持 |
| **Trino / Starburst** | 原生支持 Iceberg REST Catalog Connector |
| **Presto** | 社区版支持 |
| **DuckDB** | 通过 `iceberg` 扩展支持 REST Catalog |
| **Doris / StarRocks** | 已支持或正在支持 REST Catalog 接入 |

### AI/ML 集成

Spec **未直接定义** Model / Feature 等 AI Asset。但以下机制为上层集成提供了基础：
- `properties` 可存储 Feature Store 元数据（如 `feature-group`、`version`）。
- `View` 可作为 Feature View 的载体（通过 SQL 定义特征逻辑）。
- 跨组织共享可通过 Iceberg REST 协议 + Credential Vending 实现，这比 Delta Sharing 更轻量。

**启示**：若自研 Catalog 需支持 AI Asset，可在 Namespace/Table 的 properties 中定义约定，或在 Spec 端点基础上增加私有端点（如 `/v1/{prefix}/features`、`/v1/{prefix}/models`）。

---

## H. 运维与落地成本

| 维度 | 说明 |
|---|---|
| **部署依赖** | 仅需一个可暴露 HTTP 的服务；无强制数据库/消息队列要求（取决于具体实现） |
| **参考实现门槛** | Java `CatalogHandlers` 已覆盖 90% 服务端逻辑，基于它二次开发成本较低 |
| **升级策略** | Spec 通过 `endpoints` 和 `format-version` 实现前向兼容，客户端可先适配新端点 |
| **文档** | OpenAPI YAML 即文档；官方 Docs 的 REST Catalog 章节相对简略 |

---

## I. 社区与治理

- **基金会**：Apache Software Foundation（顶级项目）
- **主导公司**：无单一商业公司主导，Apple、Netflix、Databricks、Tabular 等多家公司共同维护
- **活跃度**：极高。REST Catalog Spec 的 OpenAPI YAML 和 Java 客户端随 Iceberg 核心版本同步发布
- **RFC 流程**：Iceberg 社区采用 GitHub Issue + 社区投票机制；重大 Spec 变更需通过社区讨论

---

## J. License 与商业化

- **协议**：Apache 2.0
- **可 fork 性**：**极高**
  - `core` 模块的 `org.apache.iceberg.rest` 包结构清晰，与 Iceberg 表格式核心代码耦合度中等（主要依赖 `TableMetadata`、`TableOperations` 等核心类）。
  - `CatalogHandlers` 提供了完整的服务端 handler 参考实现，可直接复用。
  - 若需剥离，主要成本在于需同时携带 `core` 模块中的数据模型类（`TableMetadata`、`Snapshot`、`PartitionSpec` 等）。
- **商业版差异**：无官方商业版；Tabular 等公司提供托管 REST Catalog 服务，但不闭源核心协议。

---

## K. RoadMap 与趋势

- **近期热点**：View Spec 已进入 Spec 并在 1.4+ 版本中成熟；Scan Planning 服务端模式持续优化。
- **未来方向**：
  1. **Credential Vending 标准化**：目前各实现差异较大，社区可能会收紧标准。
  2. **Puffin 文件集成**：统计信息（NDV、Bloom Filter）的 Catalog 层索引可能成为新扩展点。
  3. **REST Catalog 与 OpenLineage**：表级血缘的上报规范可能在 Spec 外围形成约定（不会进入核心 Spec）。

---

## L. 已知缺陷与局限

1. **废弃的 OAuth 端点**：`POST /v1/oauth/tokens` 仍留在 OpenAPI 中，但已标记废弃，造成新实现者困惑。
2. **Remote Signing 生态空白**：OpenAPI 定义了 `/sign`，但 Java 客户端尚无对应常量，主流引擎也未调用该端点。
3. **跨表事务弱**：`commitTransaction` 仅支持多表原子元数据提交，没有回滚语义，也不能跨 Catalog 实例。
4. **无 Namespace 层级权限**：Spec 未定义权限模型；RBAC 完全交给服务端实现（Polaris、Unity Catalog 在此分化）。
5. **AI/ML Asset 无原生抽象**：所有扩展必须通过 properties 或私有端点完成。

---

## 设计启示（用于自研 Catalog 的决策收敛）

基于本调研，针对你尚未确定的自研 Catalog 关键决策，建议如下：

| 待定决策 | 启示 |
|---|---|
| **后端存储选型** | Spec 不约束存储；但需支持 Iceberg 的元数据模型（表、Snapshot、Partition Spec）。若追求快速落地，可基于 Iceberg Java `core` 的 `TableOperations` 接口做二次封装。 |
| **是否做多租户** | 逻辑隔离（`prefix`）已有标准路径；物理隔离需自行设计。 |
| **是否做 Git-like** | Spec 本身没有 Branch/Tag 语义（除了 Iceberg 表的 Snapshot）。若想做 Nessie 式的 Git-like，需在 Spec 之上增加一层版本控制抽象，改造成本高。 |
| **是否做跨表事务** | Spec 已提供 `commitTransaction` 端点，可覆盖"多表同时提交"的基础场景。若需更强一致性（如 Saga / 2PC），必须自研扩展协议。 |
| **是否将 AI Asset 作为一等公民** | Spec 不支持。建议方案：**不改动核心 Spec**，而是在 Namespace/Table properties 中定义 Schema 约定，并增加私有 Extension Endpoints（如 `/features`）供内部系统调用。 |
| **Clustering/Compaction 是否放在 Catalog 层** | Spec 未定义。若希望 Catalog 统一调度，可借鉴 Polaris/Unity Catalog 的做法：在 Catalog 层维护"优化策略表"，但实际的 Spark/Flink 作业由外部调度器（Airflow/DolphinScheduler）触发。 |
| **自研 vs. 基于开源修改** | 若目标是**兼容 Iceberg 生态**，强烈建议基于 Iceberg `core` + `CatalogHandlers` 做二次开发（可 fork），而非从零手写 REST 协议。参考 LakeKeeper（从零自研 Rust 版）的代码量虽小，但需完整复刻 Iceberg 元数据模型的所有边界条件，隐性成本极高。 |

---

*文档版本：v1.0 / 2026-04-16*
*信息来源：Apache Iceberg GitHub (main branch) OpenAPI YAML、Java RESTCatalog/RESTSessionCatalog/CatalogHandlers/Endpoint 源码*
