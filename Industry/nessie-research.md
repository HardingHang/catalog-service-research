# Project Nessie 调研报告（v3.0）

> 面向自研 Lakehouse Table Catalog 选型预研的 Tier 1 Deep-Dive 材料。基于 Nessie 官方文档、GitHub 源码（0.107.5-SNAPSHOT @ main，2026-04-13）、稳定发布 0.107.4（2026-03-09）、同层及跨层产品分析、以及 2024–2026 年社区讨论与第三方评测。
>
> 本文为独立完整报告，阅读无需依赖其他前置材料。

---

## 1. 项目定位

### 1.1 一句话结论

**Project Nessie 是一个面向 Data Lake / Lakehouse 的事务型元数据目录服务（Catalog），其独特之处在于把 Git 风格的语义引入数据湖元数据管理。**

具体能力：branch、tag、commit、merge、transplant（cherry-pick）、diff、history。Nessie 核心不存储数据文件本身，而是对表、视图、namespace 等元数据对象提供**版本控制、事务隔离、一致性发布和回滚**。数据文件仍由对象存储承载；Nessie 管理的是这些对象在某个逻辑引用上的可见版本。

官方首页标语：**"Transactional Catalog for Data Lakes with Git-like semantics"**。

### 1.2 发起方与时间线

Nessie 由 Dremio 联合创始人 **Jacques Nadeau**（Apache Arrow 创建者之一）与 **Tomer Shiran** 于 2020 年 5 月公开发布，团队此前已孵化约一年。核心 maintainer 来自 Dremio：Robert Stupp（@snazy）、Alexandre Dutra（@adutra，OAuth2 / REST 负责人）、Dmitri Bourlatchkov（@dimas-b）、Eduard Tudenhoefner（@nastra，Iceberg PMC）等，Maven POM developers 字段列出 17 人，绝大多数为 Dremio 雇员。

### 1.3 版本与源码状态

- 本地调研版本：`0.107.5-SNAPSHOT`（main，2026-04-13）
- 官方稳定线：**0.107.4（2026-03-09）**
- Java 基线：从 0.107.0 起强制 Java 17，推荐 Java 21

### 1.4 明确的非目标

| 非目标 | 原因 |
|---|---|
| 支持 Delta / Hudi / Paimon | 这些格式的元数据不满足"单一不可变指针"前提，详见第 12.2 节 |
| 业务数据目录（lineage / glossary / discovery）| 定位是运营元数据，上层产品负责 |
| 不复用 Git 底层实现 | 早期 JGit + DynamoDB 原型仅 ~20 commits/s，被整体重写 |
| Catalog 层数据授权 | 只做元数据授权，数据层交给 credential vending / S3 signing |
| Schema 演化 / Compaction / 血缘 | 交给 Iceberg 与外部工具 |

### 1.5 与 Dremio 商业产品及 Apache Polaris 的关系

- **Dremio Arctic**（2022，托管 SaaS）= 托管 Nessie + Iceberg + Optimization Service
- **Dremio Enterprise Catalog / Lakehouse Catalog**（2024-10 GA）底层仍基于 Nessie
- **战略方向**：Dremio 已公开表态要把 Nessie 能力并入 Apache Polaris；截至 2026-04 合并进度远慢于承诺，详见第 10 章

---

## 2. 业务场景

### 2.1 数据湖上的分支开发和隔离实验

在独立分支上做表结构调整、视图变更、数据回填或验证性修改，不影响主分支生产查询。分支是零成本的——不复制任何 Parquet 文件，只创建一个新的引用指针。

### 2.2 多表一致性发布

一组表/视图的元数据变更可以在同一个 commit 里提交，通过 merge 统一暴露到目标分支，实现原子发布——要么全部可见，要么全部不可见。这是 Nessie **最核心、最无可替代**的能力，详见第 5.4 节。

### 2.3 Write-Audit-Publish（WAP）发布流程

```
1. CREATE BRANCH qa_2026_04_17            # 零成本，不复制数据
2. Spark 向 qa 分支写入数据               # 生产用户不可见
3. 在 qa 分支上跑数据质量检查
4. 检查通过 → MERGE qa_2026_04_17 INTO main
5. 检查失败 → DROP BRANCH qa_2026_04_17  # 零成本废弃
```

本质是把软件工程的 Pull Request 工作流平移到数据管道，即 "CI/CD for Data"。

### 2.4 审计、回滚和差异分析

Nessie 提供 commit log、reflog、diff、entries 等能力，适合发布审计、事故回滚、版本差异分析。

### 2.5 多引擎共享 Catalog

通过原生 Nessie REST v2 或标准 Iceberg REST Catalog，Spark / Flink / Trino / pyiceberg 共享一套版本化元数据，引擎无需单独集成版本控制逻辑。

---

## 3. 核心技术点（综述）

- **Git-like 语义作用于元数据层**：版本化对象是表元数据指针，而不是数据文件
- **内容寻址与不可变对象**：每个 `Obj` 的 ID 由内容哈希导出，天然缓存友好，写入幂等
- **乐观并发控制 + 单 key CAS**：真正的原子操作只有一个——refs 表指针翻转，事务要求降至最低
- **双层索引**：incremental index（嵌入 CommitObj）+ reference index（周期物化），与 LSM-Tree compaction 思路同源
- **更严格的提交校验**：content-id 唯一性、expectedContent 匹配、payload 不可变——做的是 catalog consistency enforcement，不只是版本记录
- **服务层承担真实语义**：REST 层薄，CEL 鉴权、分页、hash 解析、异常映射集中在 service layer

---

## 4. 整体架构

### 4.1 架构总览

```
┌──────────────────────────────────────────────────┐
│ Clients                                          │
│   Spark / Hive / Flink / Trino / CLI             │
│   REST / Iceberg REST Clients (pyiceberg 等)     │
└───────────────────┬──────────────────────────────┘
                    │ HTTP / Iceberg REST
                    ▼
┌──────────────────────────────────────────────────┐
│ API Layer                                        │
│   Nessie REST v1/v2 (/api/v2)                    │
│   Iceberg REST (/iceberg[/branch][|warehouse])   │
└───────────────────┬──────────────────────────────┘
                    ▼
┌──────────────────────────────────────────────────┐
│ Service Layer  (CEL AuthZ / 分页 / hash 解析)     │
└───────────────────┬──────────────────────────────┘
                    ▼
┌──────────────────────────────────────────────────┐
│ Version Semantics Layer  (VersionStoreImpl)      │
└───────────────────┬──────────────────────────────┘
                    ▼
┌──────────────────────────────────────────────────┐
│ Storage Logic Layer                              │
│   CommitLogicImpl / IndexesLogicImpl /           │
│   ReferenceLogicImpl / MergeTransplantLogic      │
└───────────────────┬──────────────────────────────┘
                    ▼
┌──────────────────────────────────────────────────┐
│ Persist SPI  (Obj 体系 + ObjId + CAS 接口)       │
└───────────────────┬──────────────────────────────┘
                    ▼
┌──────────────────────────────────────────────────┐
│ Storage Backends                                 │
│   JDBC2 / MongoDB2 / DynamoDB2 / Cassandra2 /    │
│   BigTable / RocksDB / InMemory                  │
└──────────────────────────────────────────────────┘

Service Layer 另外对接：
  Platform Capabilities (Events / GC / Secrets / Tasks / AuthN / AuthZ)
  Object Storage (S3 / GCS / ADLS)
```

### 4.2 模块视图

```
API 定义与接入
  api/model、api/client、servers/rest-services、catalog/service/rest

服务编排
  servers/services（业务编排、鉴权、分页）
  servers/store、servers/quarkus-server

版本语义与存储
  versioned/spi                       # 版本语义接口
  versioned/storage/common            # Persist SPI + Logic + Obj 类型
  versioned/storage/versionstore      # VersionStoreImpl
  versioned/storage/{inmemory,rocksdb,jdbc2,
                     mongodb2,dynamodb2,cassandra2,bigtable}
  versioned/transfer                  # 导出/导入

Catalog 与 Iceberg 集成
  catalog/model、catalog/service/impl、catalog/service/rest
  catalog/files、catalog/secrets、catalog/format/iceberg

平台能力
  events / gc / tasks
  servers/quarkus-authn、servers/quarkus-authz
```

### 4.3 存储模型：内容寻址 + 双表切分

Nessie 后端逻辑上只需两张表：

```
refs 表（可变，single-key CAS）          objs 表（全部不可变，内容寻址）
  main       → ObjId(abc123...)            ObjId(abc123) → CommitObj
  experiment → ObjId(def456...)            ObjId(xyz...) → ContentValueObj
  tag/v1.0   → ObjId(ghi789...)            ObjId(uvw...) → IndexObj
                                           ObjId(...)    → IndexSegmentsObj
```

`objs` 全部不可变 + 内容寻址 → 无限制本地缓存，写入幂等，跨实例天然一致。  
`refs` 只需 **single-key CAS** → 事务要求降至最低，DynamoDB / Bigtable / Cassandra 均可。

**CommitObj 关键字段**：`tail`（父 commit ObjId，单 parent）、`commitSeq`、`created`、`message/headers`、`commitType`、`incrementalIndex`（增量 key 修改，嵌入本体）、`referenceIndex` + `referenceIndexStripes`（超出阈值时 spill 为独立 IndexObj）。

**双层索引**：incremental index 嵌在 CommitObj 里；reference index 存在独立 IndexObj / IndexSegmentsObj 中，多 commit 可共享同一段。读路径 `IndexesLogicImpl.buildCompleteIndex` 沿 `tail` 链合并 incremental 直到遇到物化 reference index。`diff(refA, refB)` 是两棵 StoreIndex 的 merge-join。

Spill 由 `StoreConfig` 控制：`maxIncrementalIndexSize`、`maxSerializedIndexSize`、`maxReferenceStripesPerCommit`。

### 4.4 提交流程：OCC + CAS 重试

```
for attempt in 0..commitRetries:
    ref_before    = persist.fetchReference(branchName)
    parent_index  = IndexesLogic.buildCompleteIndex(ref_before.pointer)

    # 校验：expectedContent、content-id、Unmodified 声明
    for each op: validate(op, parent_index)

    # 先写不可变对象（幂等）
    persist.storeObj(new_commit_obj / new_index_objs / new_content_objs)

    # 最后一次原子 CAS
    ok = persist.updateReferencePointer(branchName,
                                        expected=ref_before.pointer,
                                        new=new_commit_obj.id)
    if ok: return success
    else:  sleep(exp_backoff); continue   # 孤儿 obj 后台 GC
```

重 IO 全部放在 CAS 之前且幂等 → CAS 失败不需要回滚。真正的原子性只依赖一次 single-key CAS。孤儿对象由 `cleanup-repository` 后台清理。

### 4.5 数据流：一次多表元数据变更

```
Engine  →  REST Resource  →  ServiceImpl（CEL 鉴权、hash 解析）
        →  VersionStoreImpl  →  CommitLogic（CAS 循环）
        →  Persist → Backend DB

同时：CatalogServiceImpl → Object Storage（读写 Iceberg metadata.json）
提交后：VersionStoreImpl → EventService（COMMIT / CONTENT_* / REFERENCE_* 事件）
```

---

## 5. 核心语义与能力

### 5.1 顶层抽象

| 抽象 | 语义 |
|---|---|
| **Reference** | `Branch`（可变 HEAD）、`Tag`（不可变指针）、`Detached`（直接按 hash 访问）|
| **Commit** | catalog 级不可变节点，承载一组 Operation；**所有 commit 只有单 parent**（包括 merge 后的 replay commit）|
| **Operation** | `Put`（创建/更新，必须带 expectedContent）、`Delete`（仅携带 ContentKey）、`Unmodified`（声明未变，提升到 Serializable 隔离）|
| **ContentKey** | 多段字符串（Namespace 段 + 对象名），如 `sales.orders.raw` |
| **Namespace** | 自身也是 Content 类型，必须显式创建 |

### 5.2 Content 子类型

0.107.x 正式一等公民：**IcebergTable、Namespace**。**IcebergView** 仍标 experimental。`DeltaLakeTable` 已 deprecated，Hudi / Paimon 无官方支持。

**语义三要素**：content-id（UUID，终生不变）、payload（1 字节编码，全局唯一）、metadata-location（Iceberg metadata.json 路径，Nessie 的核心职责就是在 commit 点原子翻转这个指针）。

Put 更新既有对象时必须给出 *expected on-reference state*（snapshotId、schemaId、partitionSpecId、sortOrderId），否则抛 `NessieConflictException`。

### 5.3 版本管理语义

- **branch / tag / transplant / diff / history** 均支持
- **merge**：Java API 支持多种策略，但主要走 **replay 模式**——把源 branch 的 commit 按序重放到目标 branch，所有 commit 保持单 parent，不产生 Git 意义上的 merge commit
- **time travel**：commit hash / branch-tag 名 / 时间戳（`tbl#2024-07-01T00:00:00Z`）三种坐标
- **并发模型**：全面乐观，冲突抛 `NessieConflictException`

### 5.4 多表原子提交（核心能力）

```
commit {
  branch: main
  operations: [
    Put(key="sales.orders",      newMetadataLoc="s3://.../v43.json"),
    Put(key="sales.customers",   newMetadataLoc="s3://.../v17.json"),
    Delete(key="sales.orders_legacy"),
    Put(key="marketing.events",  newMetadataLoc="s3://.../v88.json"),
  ]
}
```

CAS 保证四个操作要么全部可见，要么全部拒绝。

**隔离级别**：读锁定到具体 hash → Snapshot Isolation；加 `Unmodified` 声明读集 → Serializable。官方明确暴露三种级别（Read Committed / Repeatable Read / Serialized），**全部乐观、不支持悲观锁**。

**冲突粒度**：ContentKey（≈表）级。两个 commit 动同一 key → 后者 CAS 失败重试；动不同 key → 两者均可线性化成功。

**引擎侧限制**：Spark / Flink / Trino 默认每次 DML 仍按单表事务提交。真正利用多表原子 commit 的途径：(a) Nessie Java SDK 直接调 API；(b) branch + fast-forward merge 变通。

---

## 6. 协议与接口

### 6.1 Nessie REST API v2

OpenAPI `nessie-openapi-0.107.4.yaml`，specVersion 2.2.0。端点集：`tree`（引用与 commit log）、`content`、`diff`、`namespace`、`config`、`reflog`。

### 6.2 Iceberg REST Catalog 兼容

分支名编码在 URI 末段：`/iceberg/main`、`/iceberg/experiment`、`/iceberg/experiment|sales`（`branch|warehouse`）。  
⚠ 标准 IRC 客户端无法通过 `prefix` 参数切换分支；commit log 概念在标准里无对应，只能通过 HTTP header 注入元数据。

### 6.3 客户端与认证

- Java：`nessie-client`（BOM 管理）；Python：已废弃 pynessie，推荐 pyiceberg 直连 Iceberg REST；CLI：`nessie-cli-0.107.4`（SQL-ish REPL）；Spark SQL 扩展：`nessie-spark-extensions-3.5_2.12:0.107.4`
- 认证：Bearer / OAUTH2（多 flow）/ AWS SigV4；token exchange / impersonation 0.107 beta

### 6.4 Credential Vending

两种机制：**Vended credentials**（STS 签发短时凭据随 loadTable 返回）和 **S3 request signing**（每请求代签，延迟高）。GCS / ADLS 仍 experimental，ADLS 只能 filesystem 粒度控制。

---

## 7. 关键模块说明

| 层次 | 关键组件 | 职责 |
|---|---|---|
| API 层 | `RestV2TreeResource`、`IcebergApiV1TableResource` 等 | 协议解析、参数验证 |
| Service 层 | `TreeApiImpl`、`ContentApiImpl`、`DiffApiImpl`、`NamespaceApiImpl` | CEL 鉴权、分页、hash 解析、异常映射 |
| Version Store | `VersionStoreImpl` | 组合 Logic 层，实现 branch/tag/commit/merge/diff/history 语义 |
| Logic 层 | `CommitLogicImpl`、`IndexesLogicImpl`、`ReferenceLogicImpl`、`MergeTransplantLogic` | commit CAS 循环、索引构建与合并、ref 管理、merge/transplant |
| Persist SPI | `Persist.java`、`ObjId.java`、`Obj.java` | 对象读写（幂等）+ 引用 CAS 抽象边界 |
| 后端 adapters | `jdbc2`、`mongodb2`、`dynamodb2`、`cassandra2`、`bigtable`、`rocksdb`、`inmemory` | 实现幂等写 + CAS + range scan |
| Catalog 层 | `CatalogServiceImpl`、`catalog/format/iceberg`、`catalog/files`、`catalog/secrets` | Iceberg metadata 处理、对象存储访问、凭据管理 |
| 平台能力 | `events`、`gc`、`tasks`、`quarkus-authn/authz` | 事件通知、孤儿文件回收、后台任务、认证授权 |

---

## 8. 非功能特性

### 8.1 性能

官方设计目标：10 万张表 × 每 5 分钟一次 commit ≈ **333 commits/s**；README 声称支持 "1000s of operations per second"。

**瓶颈**：后端 DB（JDBC 在同 branch 高并发时最易阻塞）；JDBC fetch-size 0.107.0 才改为默认 100；大 commit（数百 operation）和深 history 会触发更多 index spill。测试路径：`perftest/`、`versioned/persist/bench`。

### 8.2 可用性与扩展性

Quarkus 单体无状态，Helm chart 多副本，HA 靠后端 DB。  
⚠ reference caching 至今 disabled by default，多实例需配 `cache-invalidations.service-names`，否则可能读到 stale HEAD。

### 8.3 可观测性

Micrometer metrics（`/q/metrics`，Prometheus）、OpenTelemetry tracing、Grafana 模板（`grafana/nessie.json`）。

### 8.4 安全

TLS 走 Quarkus / Ingress 终结；静态加密由后端 DB 提供；**只做元数据层授权，绕过 Nessie 直读 S3 无法阻止**。

### 8.5 多租户

**单 server = 单 repository**，无内置多租户。多租户 = 多实例部署，运维成本高，不适合 SaaS 场景（根因在第 12.1 节分析）。

---

## 9. 生态与运维

### 9.1 计算引擎集成

| 引擎 | 集成方式 |
|---|---|
| Spark 3.3/3.4/3.5 | `nessie-spark-extensions`；可走原生 NessieCatalog 或 Iceberg REST |
| Flink 1.16/1.17/1.18 | Iceberg NessieCatalog；绑定 Iceberg 1.5.0 |
| Trino | 推荐 Iceberg REST（`connector.name=iceberg`、`iceberg.catalog.type=rest`）|
| Dremio | 原生一等公民 |
| Presto / Hive | 支持但弱化 |

**AI/ML 框架**：无原生集成，弱项。

### 9.2 后端选型

| 类型 | 场景 |
|---|---|
| `IN_MEMORY` | 仅测试 |
| `ROCKSDB` | 单节点嵌入式，无 HA |
| `JDBC2` | PG / MySQL / MariaDB；高并发同 branch 易瓶颈 |
| `MONGODB2` | 社区常用 |
| `DYNAMODB2` | AWS 首选 |
| `CASSANDRA2` | ScyllaDB 亦可 |
| `BIGTABLE` | GCP 首选，大规模性能最好 |

带 `2` 后缀均为 0.75 后新 Persist 模型；旧 DatabaseAdapter 已 deprecated，需迁移。

### 9.3 部署

- Docker：`ghcr.io/projectnessie/nessie:0.107.4`（amd64 / arm64 / ppc64le / s390x），已停用 docker.io
- Helm：`charts.projectnessie.org`
- 端口：19120（API）、9000（management / metrics / cache-invalidation）

### 9.4 升级（DatabaseAdapter → Persist 迁移）

**不能原地升级**，须 export → 建新库 → import。`nessie-server-admin-tool` 子命令：`export`、`import`（末尾自动做 commit-log optimization）、`cleanup-repository`、`cut-history`（0.99，合规断链）、`check-content`。

### 9.5 运维工具

- **`nessie-cli`**：SQL-ish REPL，`CREATE BRANCH`、`SHOW LOG`、`MERGE`、`REVERT CONTENT`
- **`nessie-gc`**：Iceberg 数据文件 mark-and-sweep，`--expiry-parallelism` 默认 4；**仅支持 Iceberg**

---

## 10. 社区、License 与战略

### 10.1 治理现状

**Nessie 未进入 ASF**，仍独立托管在 projectnessie GitHub org。Apache 2.0 license。对比：Polaris 于 2026-02-18 晋升 ASF TLP，首任 PMC Chair 为 Dremio 的 JB Onofré。核心 maintainer 主战场已转向 Polaris。

### 10.2 发布节奏

| 版本 | 日期 |
|---|---|
| 0.100.0 | 2024-11-12 |
| 0.104.0 | 2025-05-06 |
| 0.105.0 | 2025-09-03 |
| 0.106.0 | 2025-12-05 |
| 0.107.0 | 2026-01-28 |
| 0.107.4 | 2026-03-09 |

0.100 → 0.107 跨 15 个月，minor 版本间隔从 1 月延至 3–4 月，节奏明显放缓。无公开 1.0 Roadmap。

### 10.3 License 与可 Fork 性

Apache 2.0，无 Dremio 商业产品硬依赖，技术上可 fork。模块清晰（client / model / server / catalog-service / quarkus / gc / cli / spark-extensions），但治理路线控制权在 Dremio。

### 10.4 与 Apache Polaris 的关系（最关键风险）

Dremio 多位高管公开表态最终将 Nessie 能力并入 Polaris 并退场。**截至 2026-04，Git-like 能力尚未进入 Polaris**——Polaris 1.3/1.4 规划重点是 Ranger 授权、credential vending、catalog federation，Polaris 仍基于 JPA 关系型存储，无 Nessie commit kernel。

JB Onofré 暗示 branch/merge 语义可能先进 Iceberg REST Spec 再进 Polaris。

**结论**：12–24 个月内 Nessie 仍可用；之后需迁移到 Polaris 或自行维护 fork。

---

## 11. 已知缺陷与局限

### 11.1 性能瓶颈

同 branch 高并发 commit 是乐观锁串行，JDBC 最易阻塞；`cleanup-repository` 大 index 上有性能历史 bug（0.104.x 修复）；bloom filter 默认 1e6 对象，超大 repo 需手工调 `--obj-count`；0.107.3 修 commit-log 提前终止（#12135）。

### 11.2 Experimental / 半成品

- Reference caching：disabled by default，多实例下有读 stale HEAD 风险
- Iceberg View：experimental
- GCS / ADLS credential vending：experimental
- K8s Operator：未 GA
- Token exchange / impersonation：beta

### 11.3 Iceberg REST 兼容边界

- 标准 IRC 客户端不能用 `prefix` 参数切换分支
- commit log 在 IRC 标准里无对应
- #10215：Iceberg REST + Bearer auth 冻结（0.101.3 + Spark 3.5.4）
- #11493：S3 request signing 批量删除失效（0.105.x）
- 0.105.5–0.106.0：反向代理 bug（0.107.0 修复）

### 11.4 典型 GitHub Issues

#10235（GC NoSuchKey）、#9097（dropped table files 未清理）、#10748（CLI + AWS creds）、#10809（ADLS HTTPS 文档不一致）、#11145（JDK 24 toolchain）。Backlog 三大主题：GC 清理不干净、REST + auth 边角、大 repo 性能。

### 11.5 用户反馈

e6data / Conduktor / RisingWave 2025 归类为 "advanced but niche"，推荐新项目默认用 Polaris / Lakekeeper。生产级成功案例公开披露偏少，博客演示多为 dev 环境。认知成本：用户需同时理解 Iceberg snapshot 与 Nessie commit 两层历史，以及 replay 式 merge 与 Git 三方 merge 的差异。

---

## 12. 横纵向对比分析：Nessie 设计决策的原理、权衡与适用场景

本章从六个关键维度，分析 Nessie 做出某个设计选择时可能的考量和原理，并与同层（Catalog）和上下层（存储层/表格式层/数据库层）产品横向对比，给出权衡与适用场景判断。

### 12.1 版本控制的层次选择

#### 湖仓的版本控制可以在哪几层实现

现代湖仓体系的版本控制能力分布在五层，每层有不同粒度和语义边界：

```
L5 编排与应用层   DVC（lakeFS 旗下）、MLflow        ML 数据集版本、实验版本
L4 计算引擎层     Spark / Flink / Trino             无（引擎无状态）
L3 Catalog 层    ← Nessie 所在层                    catalog-wide 多表 metadata pointer
L2 表格式层       Iceberg snapshot/branch/tag        单表 snapshot + 表内 branch
L1 存储层         lakeFS（Parquet 文件树）            文件路径树 + Neon（WAL level for OLTP）
L0 数据库内核层   Dolt / DoltgreSQL、Neon             行级 + schema 版本（OLTP/协作型）
```

#### 代表产品对比

| 维度 | lakeFS（L1）| Iceberg 原生 branch（L2）| Nessie（L3）| Neon（L0 OLTP）| Dolt（L0 OLAP-不适合）|
|---|---|---|---|---|---|
| 版本化粒度 | 文件路径树（格式无关）| 单张表的 snapshot | 整个 catalog 的多表 metadata pointer | 整个 Postgres DB | SQL 表（行级）|
| 跨表原子提交 | ✅ 文件树级 | ❌ | ✅ metadata pointer 级 | ✅ ACID | ✅ Prolly Tree |
| 真正的 merge | ✅ 三方 | ❌ 仅 fast-forward | 🟡 replay 单 parent | ❌ | ✅ 行级三方 |
| 行级 diff | ❌ | ❌（需引擎计算）| ❌ | ✅ | ✅ |
| 格式无关 | ✅ | ❌ Iceberg 专属 | ❌ Iceberg 专属 | ✅ Postgres 数据 | ✅ |
| PB 级适用 | ✅ | ✅ | ✅ | ❌ | ❌（写放大 1.1x）|
| 额外组件 | 需部署 lakeFS server | **零**（Iceberg 原生）| 需部署 Nessie server | 托管 PG | 需替换 MySQL/PG |
| WAP 支持 | ✅ | 🟡 单表 | ✅ catalog 范围 | ❌ | 🟡 |

DuckLake（2026-04-13 v1.0，MIT）代表另一个思路——将表格式 + catalog **合并**进 SQL 数据库，metadata 存 PostgreSQL / SQLite，Parquet 存对象存储。支持多表事务 + time travel，但 v1.0 **无 branch/tag**，多引擎生态（Spark/Trino）仍早期。

#### Nessie 选择 L3 的原理与权衡

**选这一层的合理性**：
- L1（文件层）格式无关但语义抽象太低——lakeFS diff 给出文件增删，无法直接告知"哪张表的 schema 变了"，需上层重建表语义
- L2（表格式层）有表语义，但单表为边界——无法跨表原子提交
- L3（Catalog 层）恰好：版本化对象是"表 metadata pointer"（有表语义），版本边界是整个 catalog（有跨表原子性）

**固有代价**：
- 版本语义依赖下层表格式的**不变性保证**（Iceberg 满足，Delta/Hudi 不满足，故 Nessie 只支持 Iceberg）
- 无行级 diff / 行级 merge，catalog 层无法承担 PB 数据的计算密集型操作
- merge 语义只能做到 metadata key 级冲突检测，行级冲突留给引擎

**适用场景**：
- 纯 Iceberg + 需要多表一致性发布 + WAP + DataOps CI/CD → **Nessie**
- 混合格式 / 含非结构化数据 → **lakeFS**
- 只需单表 WAP / snapshot 隔离 → **Iceberg 原生 branch/tag**（零组件代价）
- OLTP 数据库隔离环境（每 PR 一个 DB）→ **Neon**

---

### 12.2 格式绑定：为什么只适配 Iceberg

#### 原理

Nessie 的核心操作是"在一次原子 CAS 里把多张表的 `metadata-location` 从 N 切换到 N+1"。这个操作能成立，有必要前提：**表格式在两个版本之间的过渡是单一不可变文件的替换，而不是原地修改或多文件追加**。

Iceberg 满足这个条件：每次写生成新的不可变 `metadata.json`，旧文件永不修改。Nessie 只需原子更新"当前指向哪个 metadata.json"这个指针。

其他格式：
- **Delta Lake**：transaction log 是追加写（`_delta_log/00001.json`……），无单一指针，Nessie 的"swap one pointer"无法干净映射
- **Hudi**：有 Timeline 状态机（多文件），且自带 MVCC，外部 catalog 介入会造成语义重叠
- **Paimon**：快照模型与 Iceberg 接近，但 2020 年 Nessie 发起时尚不成熟，且 Dremio 本身只处理 Iceberg

另一个约束：Nessie 的 `expectedContent` 校验机制依赖 Iceberg 专有字段（`snapshotId`、`schemaId`、`partitionSpecId`、`sortOrderId`）实现 OCC 乐观并发——其他格式需从头设计等价模型。

#### 横向对比

| 产品 | 格式支持 | 原因 |
|---|---|---|
| **Nessie** | Iceberg 专属 | 指针模型依赖不可变 metadata JSON |
| Polaris | Iceberg 专属（+ Federation 实验）| 同定位，强调 Iceberg REST 标准 |
| Unity Catalog | Delta（一等公民）+ Iceberg（UniForm 兼容）| Databricks 产品战略，Delta 先行 |
| Gravitino | Iceberg + Hive + JDBC + File + Paimon + Kafka（联邦）| 联邦 Catalog-of-Catalogs 定位，抽象层厚 |
| Lakekeeper | Iceberg 专属 | 同样只做 Iceberg，但无版本控制 |
| lakeFS | **格式无关**（文件树版本化）| 存储层抽象，不关心上层格式 |

#### 权衡

| 视角 | 利 | 弊 |
|---|---|---|
| 实现简洁性 | 单一格式假设让 OCC 模型极其简洁 | 排除混合格式场景 |
| 市场覆盖 | Iceberg 是 2025 年事实分析格式标准 | Delta Lake 遗留资产仍大量存在 |
| 竞争格局 | Polaris 同样 Iceberg 专用，两者正面竞争 | 格式无关者（lakeFS / Gravitino）可覆盖更广 |
| 可扩展性 | 后续支持 Paimon 成本独立 | Paimon 支持需重新设计 OCC 校验字段 |

**适用场景**：新建湖仓且以 Iceberg 为主格式——Nessie（或 Polaris）。混合格式遗留仓库——需要 lakeFS 或 Gravitino 的联邦方案，或只用 Nessie 覆盖 Iceberg 部分。

---

### 12.3 一致性机制：内容寻址 + OCC vs 数据库事务

#### 原理

Nessie 把一致性问题分解为两个子问题：
1. **不可变内容的写入**（CommitObj、ContentValueObj、IndexObj）：内容寻址 + 幂等写，无事务需求，任意 KV 后端均可
2. **可变指针的切换**（`refs` 表的 HEAD）：单 key CAS，这是唯一需要原子的操作

CAS 失败时 OCC 指数退避重试；先写入的不可变 obj 不需要回滚（幂等）；孤儿 obj 后台 GC 清理。

这比传统 RDBMS "多行事务"的要求低得多，使得 DynamoDB / Bigtable / Cassandra 等无事务分布式 KV 都能做后端。

#### 横向对比

| 产品 | 一致性机制 | 后端要求 | 孤儿清理 |
|---|---|---|---|
| **Nessie** | 内容寻址 + OCC + single-key CAS | 任意支持 CAS 的 KV | 需 `cleanup-repository` |
| Polaris | JPA/ORM + RDBMS 多行事务 | PostgreSQL（强依赖）| 无需额外清理 |
| Lakekeeper | PostgreSQL ACID 事务 | PostgreSQL（强依赖）| 无需额外清理 |
| Unity Catalog（OSS）| RDBMS 事务 | SQLite / H2 | 无需额外清理 |
| lakeFS | Prolly Tree + CAS（类似 Nessie 原理）| PostgreSQL / DynamoDB 等 | CoW 不产生孤儿 |

#### 权衡

| 视角 | OCC + single-key CAS（Nessie）| RDBMS 事务（Polaris / Lakekeeper）|
|---|---|---|
| **后端多样性** | ✅ 7 种后端，含无事务 KV | ❌ 强依赖 RDBMS |
| **单 branch 高并发写** | ❌ 重试风暴（`Reference hash is out of date`）| ✅ 行锁序列化，无重试风暴 |
| **运维复杂度** | GC 需额外工具；孤儿 obj 累积 | 无孤儿问题 |
| **吞吐上限** | 高（无全局锁，多 branch 并发写互不影响）| 受 RDBMS 单点限制 |
| **水平扩展** | 容易（refs cache 解决后即可）| 需 RDBMS HA 方案 |

**适用场景**：多 branch 并行写（如多个 ETL 同时向不同 branch 写入）→ OCC 模型优势明显。单 branch 高频写（如金融交易流水、广告日志）→ RDBMS 事务更稳定。

---

### 12.4 单 Repository 模型：多表原子性 vs 多租户

#### 原理

Nessie 中**多表原子提交**的实现依赖于：所有表的 metadata pointer 共处同一个版本树（commit 链）上，一次 CAS 可以同时翻转多个 key 的指针。

如果要支持多租户（多个独立的 catalog），就需要多个版本树。但跨版本树的原子提交需要分布式事务（2PC / Saga），这会破坏 Nessie "single-key CAS 就够了"的核心设计假设。

换言之，**Nessie 的多表原子性和多租户是互斥的**——把原子边界扩大（覆盖更多表）就必须缩小租户隔离边界（单个 repository），反之亦然。Nessie 选择了原子性。

#### 横向对比

| 产品 | 多租户模型 | 隔离粒度 | 多表原子提交 |
|---|---|---|---|
| **Nessie** | Server → Repository（唯一）| 实例级别 | ✅ catalog-wide |
| Polaris | Server → Catalog → Principal | Catalog 级 | 🟡（Iceberg REST `commitTransaction`）|
| Unity Catalog | Metastore → Catalog → Schema | Catalog 级 | 🟡（同上）|
| Lakekeeper | Server → Project → Warehouse | Project 级 | ✅（`commitTransaction`）|
| Gravitino | Metalake → Catalog（联邦）| Metalake 级 | ❌（联邦边界难协调）|

> 注：Polaris / Lakekeeper 实现了 Iceberg REST `commitTransaction`，可在一次请求内原子提交多张表的 Iceberg 操作，语义上覆盖了"一次引擎 DML 会话内的多表一致性"；但无法覆盖"多次引擎会话跨时间的原子发布"，这才是 Nessie branch+merge 工作流的独特场景。

#### 权衡

| 视角 | Nessie（单 repo）| Polaris / Lakekeeper（多租户）|
|---|---|---|
| **多表原子边界** | ✅ catalog-wide | 🟡 单引擎会话内 |
| **多租户**（SaaS / 多组织）| ❌ 多实例代替 | ✅ 内置 |
| **版本分支隔离**（实验/生产）| ✅ 天然 | ❌ 无 |
| **运营成本** | 高（每租户一套 server + DB）| 低（共享 server）|

**适用场景**：单一组织 / 单一数据湖需要强原子发布 + 分支隔离 → Nessie。多组织 / SaaS 多租户 + 标准治理 → Polaris / Lakekeeper。

---

### 12.5 只做元数据 vs 存储层深度整合

#### 原理

Nessie 完全不碰 Parquet 数据文件，只管 metadata pointer 的版本——数据的不变性"外包"给 Iceberg 的不可变 snapshot 模型。这让 Nessie 本身无状态（除后端 DB 元数据），任何对象存储可接入，运维简单。

代价：GC（孤儿文件清理）需要单独的 `nessie-gc` 工具，无法自动感知底层存储状态；credential vending 是后加能力，GCS/ADLS 仍 experimental。

#### 横向对比

| 产品 | 数据层介入程度 | 影响 |
|---|---|---|
| **Nessie** | 不介入，只管 metadata pointer | GC 需额外工具，credential vending 晚加入 |
| Lakekeeper | credential vending（S3 成熟，GCS/ADLS experimental）| 对存储有 IAM 控制，但不管数据文件内容 |
| Polaris | credential vending + Policy（per-table 数据访问策略）| 数据访问管控相对完整 |
| Unity Catalog（商业版）| Delta Sharing + credential vending + 数据访问控制 | 最深，但开源版弱化 |
| lakeFS（L1）| 完全掌控文件树，CoW 分支，路径侵入性 | 数据层控制最强，但应用需修改路径 |

#### 权衡

| 视角 | 只管元数据（Nessie）| 深度整合存储（lakeFS / Unity 商业版）|
|---|---|---|
| **系统耦合度** | 低，存储可替换 | 高，存储路径/凭据与产品绑定 |
| **GC 完整性** | 需额外工具，有孤儿文件风险 | CoW 模型天然无孤儿（lakeFS）|
| **数据层强制访问控制** | ❌ 绕过 Nessie 可直读 S3 | ✅ 无法绕过 |
| **运维简单性** | 高（server 无状态）| 低（多组件配合）|

**适用场景**：追求系统简洁、对象存储可替换、数据访问控制可用 IAM 解决 → Nessie 的只管元数据路线。需要数据层强制访问控制（无法信任 IAM 边界）→ 需额外组件（Lakekeeper / Polaris / Unity Catalog 商业版）。

---

### 12.6 Git-like 语义 vs 标准 Iceberg REST

#### 原理与内在张力

Nessie 提供两套接口：原生 Nessie API v2（暴露 branch / commit / merge / transplant / diff / history）和 Iceberg REST Catalog Spec（工业标准，但无 branch / commit 概念）。两者之间存在根本性张力：Iceberg REST Spec 的设计目标是"catalog 无状态、引擎负责所有语义"，而 Nessie 的设计目标是"catalog 有完整版本历史"。

Nessie 的妥协方案：把分支名编码在 URI 末段（`/iceberg/main`），在路径层解决标准不支持的语义。代价是标准 IRC 客户端无法切换分支，commit log 无法通过 IRC 访问，用户需同时理解两套 API。

#### 横向对比

| 产品 | 接口策略 | 结果 |
|---|---|---|
| **Nessie** | 原生 API + IRC 兼容（URI 编码分支）| 功能丰富，标准客户端有兼容边界 |
| Polaris | 纯 IRC + 私有管理 API | 标准兼容最好，无 Git-like 能力 |
| Lakekeeper | 纯 IRC + `/management/v1/` 私有扩展 | 同上 |
| Unity Catalog | 私有 REST API（非 IRC 超集）| 有治理能力，标准兼容一般 |
| lakeFS（IRC 模式）| lakeFS 原生 API + IRC Catalog 层 | 可叠加在任何 Catalog 上，无需替换 Catalog |

#### 权衡

| 视角 | 扩展 Iceberg REST（Nessie）| 纯标准 IRC（Polaris / Lakekeeper）|
|---|---|---|
| **版本控制语义** | ✅ 完整（branch / merge / history）| ❌ 无 |
| **标准客户端兼容** | 🟡（URI 编码分支，部分客户端不支持）| ✅ 完整 |
| **生态集成成本** | 需要额外 Nessie SDK 或 CLI | 引擎原生支持 |
| **未来标准演进** | 面临被标准化（Iceberg REST v2 / Polaris 吸收）| 标准即未来 |

**趋势判断**：2024–2026 年 Iceberg REST 标准化加速，Polaris TLP 成为事实标准实现。Nessie 独有的版本语义在标准化进程中属于"超前的私有扩展"，迁移到纯 IRC 生态时这些能力会丢失。这是 Nessie 中长期最大的功能兼容风险。

---

### 12.7 小结：六个决策的逻辑一致性

以上六个选择并非孤立——它们共同服务于同一个核心命题：

> **用最简单的存储原语（single-key CAS + 内容寻址 KV），在 Catalog 层实现 catalog-wide 的 Git-like 版本语义，专注 Iceberg，不扩边界。**

每一个选择都是这个命题的推论：
- **版本控制在 L3** → 因为 L3 是"有表语义 + 可跨表原子"的最优层次
- **只支持 Iceberg** → 因为 Iceberg 的不可变 metadata JSON 是 single-key CAS 模型的必要前提
- **OCC + single-key CAS** → 因为这是使用无事务 KV 后端的最低原子性要求
- **单 repository** → 因为多表原子提交需要共享版本树，不能有多个隔离 repository
- **只管元数据** → 因为 Iceberg 不变性让"只翻转指针"就足够，介入数据层只会增加耦合
- **扩展 IRC 标准** → 因为 Git-like 语义无法在现有 IRC 标准内表达，只能作为方言层

这六个选择形成了一个自洽的设计体系。它的优点是极度内聚、实现简洁；它的局限是"非 Iceberg 的格式无法适配、非单组织的多租户场景无法适配、非元数据的行级版本化无法适配"，这三条边界正是 Nessie 与竞品/上下游的最大分野。

---

## 13. 设计启示（对自研 Catalog 的决策参考）

### 启示一：版本控制层次的决策是最优先的架构问题

三类诉求分属三层，不要在一层里同时解决：
- 单表历史查询 → Iceberg 原生 snapshot，catalog 层无需做事
- 多表一致性发布 → L3 Catalog 层（参考 Nessie）
- 整个仓库的隔离实验（含非结构化）→ L1 存储层（参考 lakeFS）

### 启示二：内容寻址 + 双表切分是最可移植的元数据版本控制范式

`immutable objs`（内容寻址 KV）+ `mutable refs`（single-key CAS）是 Nessie 和 lakeFS 的共同原语，与后端无关。自研 Catalog 若做版本控制，可直接复用这个范式，Object ID 用 BLAKE3 / SHA-256 均可。

### 启示三：Iceberg 专属是合理的起点，不是能力缺失

Iceberg 的不可变 metadata JSON 让 "swap one pointer" 模型极其简洁。阶段一专注 Iceberg，后续格式扩展成本独立，不影响核心设计。

### 启示四：merge 语义应限定在 metadata key 级别

在 PB 级 Parquet 数据上做三方 merge 不适合放在 catalog server 里。把 merge 限定在"metadata key 冲突检测"，行级冲突明确留给引擎处理，这是正确的层次切分。

### 启示五：OCC 的适用边界要提前画清

OCC + CAS 在多分支浅 commit 场景优雅；单 branch 高并发写（金融/广告场景）需要提供可选悲观锁或分区并发路径，不能只靠重试吸收。

### 启示六：多表原子提交 vs 多租户需要提前做出取舍

Nessie 的选择说明二者在单 CAS 原语上不能兼得。如果自研场景是"单组织 + 强一致发布"，选 Nessie 路线；如果是"多租户 SaaS + 标准治理"，选 Polaris / Lakekeeper 路线。

### 启示七：DuckLake 值得作为潜在颠覆性方案追踪

DuckLake v1.0（2026-04-13）把 catalog 和表格式合并进 SQL 数据库，挑战了"Iceberg 需要独立 catalog server"的前提。目前 v1.0 无 branch/tag，Spark/Trino 生态早期，不影响现阶段选型，但 v1.x 进展值得关注。

### 启示八："不做什么"的定力和"不做 Git 底层"的清醒同样重要

Nessie 坚持窄而深（只管 Iceberg 元数据事务），让 1,438 个 Java 文件的代码库被十余人长期维护。自研 Catalog 在需求压力下要守住"只做元数据事务 + 可选权限"的边界，lineage / discovery / ML 特征等能力交给上层平台。

---

## 14. 参考资料

### 14.1 Project Nessie 官方

- 官网：https://projectnessie.org/
- GitHub：https://github.com/projectnessie/nessie
- 发布列表：https://projectnessie.org/releases/
- Commit Kernel 架构（含 DatabaseAdapter 过时提示）：https://projectnessie.org/develop/kernel/
- Nessie vs Git：https://projectnessie.org/guides/nessie_vs_git/
- Transactions Guide：https://projectnessie.org/guides/transactions/
- Iceberg REST 配置：https://projectnessie.org/guides/iceberg-rest/
- 迁移指南：https://projectnessie.org/guides/migration/
- Cache improvements 博客（2024-06，Robert Stupp）：https://projectnessie.org/blog/2024/06/05/nessie-cache-improvements/
- Polaris announcement 博客（2024-08）：https://projectnessie.org/blog/2024/08/02/open-source-polaris-announcement/

### 14.2 关键源码路径（main 分支）

- Persist SPI：`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/persist/`
- Logic 层：`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/logic/`
- Obj 类型：`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/objtypes/`
- VersionStore：`versioned/storage/versionstore/src/main/java/org/projectnessie/versioned/storage/versionstore/VersionStoreImpl.java`
- Service 层：`servers/services/src/main/java/org/projectnessie/services/impl/`
- Catalog（Iceberg REST）：`catalog/service/impl/`、`catalog/service/rest/`、`catalog/files/`、`catalog/secrets/`

### 14.3 社区讨论

- GitHub Issues：https://github.com/projectnessie/nessie/issues
- Zulip：https://projectnessie.zulipchat.com
- 典型 issue：#10235 / #9097 / #10748 / #10215 / #11493 / #12135

### 14.4 Dremio / Polaris / Snowflake 相关

- Dremio Introducing Project Nessie（2020-05）：https://www.dremio.com/blog/introducing-project-nessie/
- Dremio Data Lakehouse Versioning（Nessie vs Iceberg vs LakeFS）：https://www.dremio.com/blog/data-lakehouse-versioning-comparison-nessie-apache-iceberg-lakefs/
- Dremio Polaris TLP 博客：https://www.dremio.com/blog/apache-polaris-graduates-to-a-top-level-apache-project/
- Snowflake Polaris TLP 博客（2026-02）：https://www.snowflake.com/en/blog/apache-polaris-top-level-project/
- Apache Polaris 官网：https://polaris.apache.org/

### 14.5 跨层产品参考

- lakeFS 博客系列（版本控制 + Iceberg）：https://lakefs.io/blog/
- lakeFS vs Nessie：https://lakefs.io/blog/nessie-catalog/
- lakeFS Iceberg versioning：https://lakefs.io/blog/iceberg-versioning/
- lakeFS 架构（Graveler / Prolly Tree）：https://lakefs.io/blog/scalable-data-version-control-getting-the-best-of-both-worlds-with-lakefs/
- DuckLake v1.0 发布（2026-04-13）：https://ducklake.select/2026/04/13/ducklake-10/
- DuckLake Manifesto：https://ducklake.select/2025/05/27/ducklake-01/
- Neon 官方文档（branching）：https://neon.com/docs/introduction/branching
- Databricks Lakebase 公告（2025-06）：https://www.databricks.com/blog/announcing-lakebase-public-preview
- Databricks Neon 收购报道：https://venturebeat.com/data-infrastructure/the-1-billion-database-bet-what-databricks-neon-acquisition-means-for-your-ai-strategy
- Dolt GitHub：https://github.com/dolthub/dolt；DoltgreSQL：https://github.com/dolthub/doltgresql
- MotherDuck《Git for Data Applied》（2026-03）：https://motherduck.com/blog/git-for-data-part-2/

### 14.6 第三方分析

- e6data Iceberg Catalogs 2025：https://www.e6data.com/blog/iceberg-catalogs-2025-emerging-catalogs-modern-metadata-management
- Conduktor Iceberg Catalog Management：https://www.conduktor.io/glossary/iceberg-catalog-management-hive-glue-and-nessie
- BigDATAwire "Polaris to be Merged with Nessie"（2024-07）：https://www.bigdatawire.com/2024/07/30/polaris-catalog-to-be-merged-with-nessie-now-available-on-github/
- Capital One《Lakehouse Convergence: Delta Lake & Iceberg》：https://www.capitalone.com/tech/cloud/lakehouse-format-convergence-delta-lake-iceberg/

---

## 信息置信度与未解疑点

**高置信**：版本时间线；Polaris TLP 里程碑；License = Apache 2.0；双层索引机制；CAS 重试循环；格式绑定原因；多表原子提交语义；各产品多租户对比；lakeFS / Dolt / Neon / DuckLake 核心机制与 Nessie 差异。

**中置信**（基于博客 + release notes 推断）：`CommitObj` / `Persist` 的具体字段签名；151-way striped lock 细节；spill 阈值默认值。

**未解疑点**：Iceberg v3 支持状态；UDF Content 类型的稳定状态；DuckLake 多引擎生态成熟时间线；Nessie branch/merge 并入 Iceberg REST Spec 的可能性与时间表。

---

*文档版本：v3.0（横纵向对比增强版）/ 2026-04-17*
