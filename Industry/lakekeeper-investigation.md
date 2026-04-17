# Lakekeeper 调研报告

## A. 定位与设计目标

- **发起方**：Vakamo 公司（德国创业公司），2024 年前后开源
- **定位**："Apache-Licensed, secure, fast and easy to use implementation of the Apache Iceberg REST Catalog specification based on apache/iceberg-rust"
- **核心目标**：
  1. 提供一个**单二进制、无 JVM、高性能**的 Iceberg REST Catalog 服务端实现。
  2. 内置企业级安全能力（OIDC 认证 + OpenFGA 细粒度授权）。
  3. 降低部署门槛：一个 all-in-one binary，配合 PostgreSQL 即可运行。
- **差异化主张**：
  - **Rust 原生**：对比 Java 系的 Polaris/Gravitino，启动快、内存占用低、无 GC 暂停。
  - **内置多租户**：Server → Project → Warehouse 的三层隔离模型。
  - **生产级安全开箱即用**：OpenFGA 集成不是可选项，而是核心设计的一部分。
  - **Credential Vending 完整**：S3（含 Remote Signing + Role Assumption）、Azure ADLS Gen2、GCS 均已在开源版支持。
- **非目标**：
  - 不支持多表格式（只支持 Iceberg）。
  - 不做数据发现/治理（无血缘、无数据质量）。
  - 不做 Git-like 版本控制（区别于 Nessie）。

---

## B. 核心概念与元数据模型

Lakekeeper 在标准 Iceberg REST Catalog 的 `Namespace → Table/View` 之上，增加了两层管理抽象：

```
Server
 └── Project（多租户隔离单元，Header: x-project-id）
      └── Warehouse（绑定一个 Storage Profile + Credential）
           └── Namespace
                └── Table / View
```

| 概念 | 作用 | 对应标准 Iceberg REST 的映射 |
|---|---|---|
| **Server** | 整个 Lakekeeper 实例，包含全局用户/角色/权限 | 对应一个 Catalog 服务 |
| **Project** | 逻辑上的租户/组织，一个 Server 可服务多个 Project | **扩展概念**，标准 Spec 无对应物 |
| **Warehouse** | 绑定一个对象存储位置（S3/Azure/GCS）和凭证 | **扩展概念**，对应标准中的 `{prefix}` |
| **Namespace** | 表/视图的层级命名空间 | 直接对应 Iceberg Namespace |
| **Table / View** | Iceberg 表/视图 | 直接对应 Iceberg Table/View |

**元数据可扩展性**：
- Namespace / Table / View 均支持 `properties` K-V 扩展。
- 无自定义 Entity 类型注册机制。
- **没有** Function / Model / Feature / Volume 等一等公民抽象。

---

## C. 架构与关键设计

### 技术栈

| 层级 | 选型 | 说明 |
|---|---|---|
| Web 框架 | `axum` 0.8 + `tokio` | 标准 Rust 异步栈 |
| Iceberg 核心 | 自维护 `iceberg-rust` fork | `Cargo.toml` 中指向 `github.com/lakekeeper/iceberg-rust` |
| 数据库 | PostgreSQL >= 15 | 元数据、权限、任务队列均落库 |
| ORM/DB | `sqlx` | 编译期查询检查，无额外 ORM 抽象 |
| 授权引擎 | OpenFGA（默认）/ AllowAll / 自定义 trait | `authz-openfga` crate 独立封装 |
| 消息/事件 | CloudEvents | 可选 NATS / Kafka 后端 |
| Secret 存储 | PostgreSQL / HashiCorp Vault kv2 | 存储 Warehouse 的 credential |
| 对象存储 | `aws-sdk-s3`, `gcloud-storage`, `azure_storage_*` | 支持 vended credentials 和 remote signing |

### 部署架构

- **无状态**：所有状态均存于 PostgreSQL，服务实例本身无本地状态，支持水平扩展。
- **单二进制**：`lakekeeper-bin` 包含 Web Server、DB 迁移、Healthcheck、Task Queue Worker 逻辑。
- **Kubernetes 原生**：提供 Helm Chart，Operator 在开发中。

### 一致性与事务

- 单表更新通过 PostgreSQL 事务 + 乐观并发控制（OCC）实现。
- **跨表事务**：实现了 `POST /catalog/v1/{prefix}/transactions/commit`，可原子提交多表更新。
- 无分布式事务协调器（2PC/Saga）语义。

### 存储优化调度架构

Lakekeeper 内置了一个**任务队列系统**（`service::tasks`），目前包含三类内置任务：

1. **Tabular Expiration Queue**：软删除表/视图的定期过期检查。
2. **Tabular Purge Queue**：物理清理已过期对象的元数据。
3. **Task Log Cleanup Queue**：清理历史任务日志。

**启示**：这是 Catalog 层对"元数据生命周期管理"的轻量调度，但**不涉及数据布局优化**（Clustering / Compaction）。若需要类似 Databricks Liquid Clustering 的能力，仍需在外部引擎（Spark）中执行。

---

## D. 协议与接口

### Iceberg REST Catalog API

Lakekeeper 完整实现了 Iceberg REST Catalog Spec 的**默认端点**和大部分**扩展端点**：

| 类别 | 覆盖情况 |
|---|---|
| Config / Namespace / Table / View / Metrics | 全部实现 |
| `transactions/commit` | **已实现** |
| Credential Vending (`/credentials`) | **已实现**，S3 / Azure / GCS |
| Remote Signing (`/sign`) | **已实现**（S3 Request Signing） |
| **Scan Planning** (`/plan`, `/tasks`) | **未实现**（代码中标记为 `unimplemented`） |

> 源码参考：`crates/lakekeeper/src/api/endpoints.rs:126-135`
> ```rust
> impl CatalogV1Endpoint {
>     pub fn unimplemented(self) -> bool {
>         matches!(
>             self,
>             CatalogV1Endpoint::PlanTableScan
>                 | CatalogV1Endpoint::FetchPlanningResult
>                 | CatalogV1Endpoint::CancelPlanning
>                 | CatalogV1Endpoint::FetchScanTasks
>         )
>     }
> }
> ```

### 管理 API（Lakekeeper 私有扩展）

Lakekeeper 在标准 REST Catalog 之外，提供了一套 `/management/v1/` API，覆盖：

- **Server 管理**：Bootstrap、Server Info
- **Project 管理**：CRUD、重命名、统计
- **Warehouse 管理**：CRUD、Storage Profile / Credential 更新、激活/停用、软删除策略
- **用户/角色管理**：基于 OIDC token 的 User 自动发现，Role 的 CRUD
- **授权检查**：`batch-check-actions` 端点支持批量权限预检
- **任务管理**：任务队列配置、任务列表、任务控制（取消/停止/立即执行）
- **搜索**：`search-tabular` 支持按名称模糊搜索表/视图

### 认证协议

- **OIDC**：通过 `LAKEKEEPER__OPENID_PROVIDER_URI` 配置，自动拉取 JWKS 验证 JWT。
- **Kubernetes Auth**：同时支持 K8s ServiceAccount Token 认证（通过 `limes-rs` 库）。
- **OAuth2**：废弃的 `POST /oauth/tokens` 端点未实现（与 Spec 一致）。

---

## E. 功能矩阵

| 能力域 | Lakekeeper 支持情况 | 备注 |
|---|---|---|
| **表格式支持** | Iceberg v1/v2/v3 | 仅 Iceberg，依赖自维护的 iceberg-rust fork |
| **Schema 演化** | 支持 | 复用 iceberg-rust 能力 |
| **版本管理** | Time travel / Snapshot 隔离 / WAP | 标准 Iceberg 能力 |
| **Branch/Tag（Git-like）** | ❌ 不支持 | 无 Nessie 式版本控制 |
| **存储优化与调度** | ⚠️ 仅限元数据清理任务（过期/清理） | 无 Clustering / Compaction 调度 |
| **查询加速** | ❌ 不支持 Scan Planning | SERVER 模式 scan planning 端点未实现 |
| **权限治理** | ✅ RBAC + ABAC（OpenFGA） | 细粒度到表/视图/Namespace 级 |
| **Policy 引擎** | ⚠️ OpenFGA / AllowAll / 自定义 Authorizer trait | 非 OPA/Ranger |
| **血缘** | ❌ 不支持 | |
| **审计** | ⚠️ 操作日志通过事件系统（CloudEvents）外发 | 无内置审计存储 |
| **搜索与发现** | ✅ `search-tabular` | 管理 API 提供，非标准 REST Catalog 能力 |
| **数据质量** | ❌ 不支持 | |
| **视图与物化视图** | ✅ View 为一等公民 | Iceberg View Spec 完整支持 |
| **跨表事务** | ✅ `commitTransaction` | 多表原子提交 |
| **AI/ML 集成** | ❌ 无原生抽象 | 无 Feature/Model/Vector |
| **数据共享** | ⚠️ 通过 Credential Vending 实现跨账户读取 | 无 Delta Sharing 协议 |

---

## F. 非功能特性

| 特性 | 表现 |
|---|---|
| **性能** | Rust 原生 + 无状态设计，元数据读写延迟低；但缺少 Scan Planning SERVER 模式优化 |
| **可用性** | 无状态，可水平扩展；PostgreSQL 为单点依赖（需自行做 PG HA） |
| **可扩展性** | 通过 `Authorizer` trait 和 `ContractVerification` trait 可插拔扩展 |
| **可观测性** | 内置 Prometheus 指标、JSON 结构化日志、Tracing（通过 `tracing` crate） |
| **安全** | TLS、JWT 签名验证、OpenFGA 细粒度授权、Credential Vending |
| **多租户** | Project 级逻辑隔离 + Warehouse 级存储隔离；无物理隔离 |

---

## G. 生态与集成

### 已验证的客户端/引擎

| 引擎 | 状态 |
|---|---|
| Spark | 已测试 |
| PyIceberg | 已测试 |
| Trino | 已测试 |
| StarRocks | 已测试 |
| Flink / DuckDB / Doris | 理论上兼容 Iceberg REST Catalog 即兼容，未明确列出 |

### 云厂商集成

- **AWS**：S3 + IAM Role Assumption + Session Tags + vended credentials
- **Azure**：ADLS Gen2
- **GCP**：Google Cloud Storage
- **OneLake**：未支持

### 迁移路径

- **HMS 兼容**：Lakekeeper **不是 HMS 兼容**的，它是纯 REST Catalog。从 HMS 迁移需要客户端引擎切换 Catalog 类型。
- **Glue 兼容**：无直接 Glue Data Catalog API 兼容层。

---

## H. 运维与落地成本

| 维度 | 说明 |
|---|---|
| **部署依赖** | PostgreSQL >= 15（必须）；可选：OpenFGA、NATS/Kafka、Vault |
| **资源消耗** | 单二进制，内存占用远低于 JVM 方案；官方无明确基线 |
| **升级策略** | 内置 `Migrate` 子命令，将 DB schema 迁移嵌入二进制；支持滚动升级 |
| **故障排查** | 提供 `Healthcheck`、`WaitForDB` 子命令；JSON 结构化日志 |
| **文档** | 官网 docs.lakekeeper.io；README 覆盖 Docker Compose 快速启动；但深度架构文档偏少 |

---

## I. 社区与治理

- **基金会**：未进入 ASF/LF，由 Vakamo 公司主导
- **背后商业公司**：Vakamo（提供 Enterprise Support 和 Lakekeeper+ 商业版）
- **GitHub 活跃度**：
  - 仓库地址：https://github.com/lakekeeper/lakekeeper
  - 作为一个 2024 年左右诞生的新项目，Commits 和 Releases 节奏较快（观察到的版本迭代到 0.11.x 级别）
- **RFC / Design Doc**：主要通过 GitHub Issues 和 PR 讨论，未见公开的 RFC 流程
- **Contributor 生态**：目前主要由 Vakamo 员工贡献，外部贡献者规模尚小

---

## J. License 与商业化

- **开源协议**：Apache 2.0
- **商业版**：Lakekeeper+（闭源企业版）
  - Cedar 授权引擎仅在 Lakekeeper+ 中提供
  - 其他核心功能（OpenFGA、Credential Vending、多租户）均在开源版
- **可 fork 性评估**：**高**
  - 代码结构清晰：`lakekeeper-bin`（入口）、`lakekeeper`（核心服务）、`iceberg-ext`（REST 扩展）、`authz-openfga`（授权）、`io`（存储访问）
  - 模块间耦合度中等，核心逻辑依赖自维护的 `iceberg-rust` fork
  - 若 fork，需要同时维护或替换该 iceberg-rust fork
  - 无强云厂商绑定，剥离外部依赖（OpenFGA/Kafka/Vault）均可通过 trait 实现替换

---

## K. RoadMap 与趋势

- **近期待完成**：Scan Planning SERVER 模式的实现（当前为 `unimplemented`）
- **Kubernetes Operator**：README 明确提及 "in development"
- **任务队列扩展**：当前只有元数据清理任务，未来可能扩展为更通用的后台任务调度平台
- **Cedar 授权**：作为商业版差异化功能，开源版不太可能下放

---

## L. 已知缺陷与局限

1. **Scan Planning 未实现**：对远程 Catalog 高延迟场景的性能优化缺失（`planTableScan`、`fetchPlanningResult` 等返回未实现）。
2. **仅支持 Iceberg**：无 Delta / Hudi / Paimon 兼容计划。
3. **HMS/Glue 不兼容**：纯 REST Catalog，迁移需改动客户端配置。
4. **生产验证案例有限**：作为新项目，大规模生产落地的公开案例较少。
5. **依赖自维护 iceberg-rust fork**：非上游 Apache 官方仓库，长期同步成本需关注。
6. **AI Asset 缺失**：无 Feature/Model/Vector 的原生支持。
7. **社区规模小**：主要由单一公司推动，长期中立性需观察。

---

## 设计启示（用于自研 Catalog 的决策收敛）

| 待定决策 | 启示 |
|---|---|
| **后端存储选型** | Lakekeeper 的"PostgreSQL 单存储 + 无状态服务"模式是一个**极简且可扩展**的参考。若自研目标不是联邦多源目录，这种"一库撑所有"的架构能大幅降低运维复杂度。 |
| **是否做多租户** | Lakekeeper 的 **Project → Warehouse** 两级隔离模型设计得非常实用：Project 对应租户/组织，Warehouse 对应存储环境（prod/test/dev）。若自研面向多团队/多环境，可直接借鉴该抽象。 |
| **是否做 Git-like** | Lakekeeper 明确不做 Git-like 分支，说明 Nessie 式版本控制对大部分用户是**过度设计**。若自研目标用户没有强数据科学实验管理需求，可跳过此能力。 |
| **是否做跨表事务** | Lakekeeper 完整实现了 `commitTransaction`，代码量和复杂度可控。若自研基于 Iceberg，建议同样实现该端点，这是多表 ETL 作业的刚需。 |
| **是否将 AI Asset 作为一等公民** | Lakekeeper 完全没有涉及。若自研需要支持，应在 Warehouse/Namespace properties 中定义 Schema 约定，并增加私有 Management API（类似 Lakekeeper 的 `/management/v1/`），而非改动 Iceberg REST Catalog 核心协议。 |
| **Clustering/Compaction 是否放在 Catalog 层** | Lakekeeper 选择在 Catalog 层仅做**元数据清理任务**（过期/物理删除），数据布局优化完全交给外部引擎。这是一个务实的边界划分。自研 Catalog 若不想做"重调度器"，可同样采用"Catalog 发事件 / 外部调度器消费"的轻量模式。 |
| **自研 vs. 基于开源修改** | **Lakekeeper 是目前所有候选产品中最适合 fork 二次开发的样本之一**：
  - 代码量小、模块清晰（Rust 的 trait 系统使扩展点非常干净）。
  - 已完整覆盖认证、授权、Credential Vending、多租户等自研 Catalog 的"基础设施"能力。
  - 若团队能接受 Rust 技术栈，基于 Lakekeeper fork 并扩展 Management API 是**比从零自研更快的路径**。
  - 风险在于需维护 iceberg-rust fork，以及 Scan Planning 尚未完成。 |
| **授权模型选型** | Lakekeeper 的 OpenFGA 集成展示了一种"将 FGA 作为核心依赖"的方案。若自研对细粒度授权有高要求，OpenFGA 是一个值得评估的选项；若需求简单，AllowAll + 自建 RBAC 可能更轻量。 |

---

*文档版本：v1.0 / 2026-04-17*
*信息来源：Lakekeeper GitHub 仓库源码（main branch）、README、Cargo.toml、核心 Rust 模块（api/endpoints.rs、service/authz/mod.rs、service/tasks/mod.rs、authz-openfga/src/lib.rs）*
