# Project Nessie 深度调研报告（0.107.x，2026-04-17）

> 本报告面向自研 Lakehouse Table Catalog 选型预研，是 Tier 1 Deep-Dive 材料。内容基于 Nessie 官方文档、GitHub 源码、Dremio / Snowflake / Apache Polaris 官方材料、以及 2024–2026 年社区讨论与第三方评测。阅读前假设读者已看过本地 `NESSIE_PROJECT_OVERVIEW.md`，本文对 overview 已覆盖内容不再重复，仅做增量深化。

---

## 一、A · 定位与设计目标

### 发起方与时间线

Nessie 由 **Dremio 联合创始人 Jacques Nadeau（Apache Arrow 创建者之一）与 Tomer Shiran** 于 2020 年 5 月公开发布（Jacques Nadeau《Introducing Project Nessie》，dremio.com/blog/introducing-project-nessie/），团队此前已孵化约一年。核心 maintainer 来自 Dremio：**Robert Stupp（@snazy，官方博客主笔）、Alexandre Dutra（@adutra，OAuth2/REST 负责人）、Dmitri Bourlatchkov（@dimas-b）、Eduard Tudenhoefner（@nastra，Iceberg PMC）、Laurent Goujon、Ajantha Bhat、Ryan Murray**，外加零散外部贡献者（如 Vayuj Rajan 贡献 MariaDB/MySQL 后端）。Maven POM developers 字段列出 17 人，绝大多数为 Dremio 雇员。

### 初始痛点与差异化主张

Nessie 要解决的核心痛点是 **数据湖缺少 Git 式版本控制**。Jacques Nadeau 原话：*"A set of distributed applications can each perform independent transactions and Git provides the semantics to safely and effectively merge them."* 官方首页标语至今是 **"Transactional Catalog for Data Lakes with Git-like semantics"**。Tomer Shiran 2024-07 在 Snowflake 博客总结差异化为四点：**catalog-level versioning、multi-engine support、multi-table transactions、Git for data**。

### 明确的非目标

Nessie 刻意 **不做通用元数据 Catalog**：

- **只托管 Iceberg 表与 Iceberg 视图两种一等公民 Content 类型**（Namespace 作为容器对象）。Dremio 官方文档原话："You can create and store Apache Iceberg tables and views in the Nessie catalog. **No other file or source types can be stored in the Nessie catalog.**"
- **不做业务数据目录**（lineage / business glossary / dataset discovery），这些能力由 Dremio Enterprise Catalog、Atlan、Collibra 等上层产品提供。
- **不复用 Git 底层实现**：早期用 JGit + DynamoDB 的原型仅能达到 ~20 commits/s，被整体抛弃重写。Dremio 博客原话："Nessie is Git-like because it isn't actually using Git under the hood (that would be orders of magnitude too slow)."
- 不在 Catalog 层做数据层授权——只做元数据授权，数据层交给对象存储（credential vending / S3 signing）。

### 与 Dremio 商业产品的关系

**Dremio Arctic**（2022 上线的托管 SaaS）= 托管 Nessie + Iceberg + Optimization Service（自动 compaction、清理）。**Dremio Enterprise Catalog / Lakehouse Catalog**（2024-10 GA）底层仍基于 Nessie，但从 2026 起逐步被重新定位为 **"Apache Polaris-powered Open Catalog"**（OpenSourceForU 2026-04 报道），表明 Dremio 产品线正在完成 Nessie → Polaris 的底座迁移。

---

## 二、B · 核心概念与元数据模型

### 顶层抽象

Nessie 借 Git 模型描述数据湖状态，核心是 **"命名引用（Reference）+ 不可变提交（Commit）+ 内容对象（Content）"** 三元组。

| 抽象 | 形态 |
|---|---|
| **Reference** | `Branch`（可变）、`Tag`（不可变）、`Detached`（脱离命名引用，直接用 commit hash 访问；CEL 中对应 `ref == 'DETACHED'`） |
| **Commit** | 目录级（catalog-wide）不可变节点，稳定 hash，承载一组 Operation；与 Git 不同，**Nessie 提交非合并时仅有一个 parent**（官方 guides/about 原话：*"One important difference of Nessie-merges is that Nessie-commits have only one parent"*） |
| **Operation** | `Put`（创建/更新 Content，必须附带 expected contents 做乐观校验）、`Delete`（仅携带 ContentKey）、`Unmodified`（不持久化，声明该 key 自 expectedHash 未变，用于提升到 Serializable） |
| **ContentKey** | 多段字符串（Namespace 段 + 对象名），例如 `sales.orders.raw` |
| **Namespace** | 自身也是一种 Content 类型，必须显式创建；可带 `location` 属性 |

### Content 子类型的实际支持

0.107.x 官方规范页（`develop/spec/`）明确列出的一等公民仅三种：**IcebergTable、IcebergView（仍标为 experimental）、Namespace**。代码中仍存在 `DeltaLakeTable`（deprecated，未再演进）与 UDF 类型（代码层存在，但文档不作正式说明）。对 Hudi、Paimon 无官方支持声明（0.107.0 在 #11973 为 Paimon 放宽了 Iceberg field ID=0 的限制，仅是**兼容性让步**而非原生支持）。

### 语义三要素

- **content-id**：UUID，对象终生不变，跨分支、跨重命名、跨 GC 均保持一致；是缓存与审计的稳定锚点，API 层**不提供按 id 查**的端点。
- **payload**：内容子类型的一字节编码（由 `ContentTypeBundle` 注册），全局唯一。
- **metadata-location**：指向 Iceberg `metadata.json` 的路径。Nessie 核心职责就是 **在 commit 点原子性地翻转这个指针**；Put 更新既有对象时必须给出 *expected on-reference state*（snapshot-id、schema-id、partition-spec-id、sort-order-id），否则服务端抛 `NessieConflictException`。

### 耦合程度与扩展机制

Nessie **与 Iceberg 强耦合**：0.107.x 官方兼容矩阵只覆盖 Iceberg 1.5.0，IcebergTable 模型对 Iceberg 有"所有 metadata JSON 必须保留，Iceberg 内建的 maintenance procedures（expire snapshots、rewrite manifests 等）不能直接运行"这一限制。扩展路径是 Java ServiceLoader：`META-INF/services/org.projectnessie.model.types.ContentTypeBundle`（客户端侧注册）与 `ContentSerializerBundle`（服务端序列化器），加上全局唯一 payload ID（通过 GitHub Issue 登记）。理论上可接入自定义表格式，**但代码内主干逻辑对 Iceberg 语义有隐式依赖**（on-reference state 字段命名、GC 对 Iceberg snapshot 的扫描等），真正替换成本不低。

---

## 三、C · 架构与关键设计（源码级）

> 此章是 overview 的核心增量。官方 `develop/kernel/` 页明确标注 `DatabaseAdapter` 章节已过时——**0.75 起旧 adapter 被 `Persist` SPI 全面替换**，这是理解新架构的关键。

### 3.1 分层结构（新存储模型）

```
┌─────────────────────────────────────────────────┐
│  REST API Layer (servers/quarkus-server)        │
│   ├── Nessie API v2 (/api/v2)                   │
│   └── Iceberg REST  (/iceberg[/branch][|wh])    │
├─────────────────────────────────────────────────┤
│  Service Layer (servers/services)               │
│   TreeApi / ContentApi / NamespaceApi           │
│   + CEL Authorization、分页、hash 解析           │
├─────────────────────────────────────────────────┤
│  VersionStore (versioned/storage/versionstore)  │
│   VersionStoreImpl                              │
├─────────────────────────────────────────────────┤
│  Logic (versioned/storage/common/logic)         │
│   CommitLogicImpl / IndexesLogicImpl /          │
│   ReferenceLogicImpl / RepositoryLogicImpl /    │
│   MergeTransplantLogic                          │
├─────────────────────────────────────────────────┤
│  Persist SPI (versioned/storage/common/persist) │
│   Persist 接口 + Obj 类型体系 + ObjId           │
├─────────────────────────────────────────────────┤
│  Backend Adapters                               │
│   InMemory / RocksDB / JDBC2 / MongoDB2 /       │
│   DynamoDB2 / Cassandra2 / BigTable             │
└─────────────────────────────────────────────────┘
```

与 overview 的"分层示意"相比，重点是把 **Persist SPI** 抽离为一级抽象：`Persist` 本身对 Content 类型完全无感知，只处理内容寻址对象（Obj）的存取和 Reference 的 CAS 更新；Logic 层完成所有 commit/merge/index 语义；Service 层处理 API 契约与横切关切。这种分层在自研 Catalog 时非常值得借鉴。

### 3.2 存储模型：内容寻址 + 双表切分

Nessie 后端只需要两张逻辑表：

- **`refs`**：保存命名引用的当前 "tip" 指针（对应 `RefObj`）。
- **`objs`**：保存所有 immutable 对象（`CommitObj`、`ContentValueObj`、`IndexObj`、`IndexSegmentsObj`、`StringObj`、`UniqueIdObj` 等，均以 `ObjId` 为主键）。

Robert Stupp《Nessie cache improvements》博客明确了这种划分：*"Nessie stores information in its backing data store in two tables: the refs table … and the objs table … Objects in Nessie are (usually, see below) immutable."*

**内容寻址**：所有 `Obj` 的 `ObjId` 是其二进制表示的确定性 hash（代码中使用 32 字节），因此相同内容必然产生相同 ID，天然去重、天然可缓存、天然跨实例一致。这是为什么 objs 表可以 *无副作用地本地缓存*，而 refs 表因为要指向 "最新 tip" 才是可变的（所以 refs 缓存必须配分布式失效）。

### 3.3 CommitObj 结构

`CommitObj` 是整个版本链的核心节点，关键字段（基于官方 release notes 与 `develop/kernel/` 描述推断）：

- **`tail`**：父 commit 的 `ObjId` 数组；非 merge commit 只有一个 parent，merge commit 额外带 `secondaryParents`。
- **`commitSeq`**：commit 序号（便于按序扫描）。
- **`created`**：创建时间戳。
- **`message / headers`**：commit 消息与自定义 header（Iceberg REST 支持通过 `Nessie-Commit-Authors` / `Nessie-Commit-SignedOffBy` / `Nessie-Commit-Message` 注入）。
- **`commitType`**：NORMAL / INTERNAL / NAMED_REFERENCE / CHERRY_PICKED 等。
- **`incrementalIndex`**：本次 commit 相对父 commit 的 *增量 key 修改集合*，嵌入在 `CommitObj` 内（小体量时）。
- **`referenceIndex` + `referenceIndexStripes`**：当 incremental 太大时 *spill* 出去的完整 key→content 快照，分段存储在独立 `IndexObj` / `IndexSegmentsObj` 中。

> 这种 "每 commit 带增量 + 周期性物化完整索引" 的模式与 LSM-Tree / RocksDB 的思路同源：小写入走 in-commit 嵌入 index，累积到阈值再物化成可共享的 reference index；读路径通过链式合并 incremental + reference 得到完整视图。

### 3.4 双层索引机制

**分工**：

- **Incremental index**：嵌在 `CommitObj` 里的增量 key-diff，体量小、免去额外 IO。
- **Reference index**：完整的 key→content 快照，存在独立 `IndexObj` / `IndexSegmentsObj` 中，通过 `ObjId` 引用，允许多个 commit 共享同一段内容（拜内容寻址所赐）。

**Spill 触发条件**（由 `StoreConfig` 控制）：

- `maxIncrementalIndexSize`：单个 commit 里 incremental 序列化字节上限。
- `maxSerializedIndexSize`：任意 IndexObj 的体积上限，超过则切分为多段 `IndexSegmentsObj`。
- `maxReferenceStripesPerCommit`：单 commit 可挂多少条 stripe。
- `commitTimeoutMillis` / `commitRetries`：控制 CAS 重试窗口。

**读路径**：`IndexesLogicImpl.buildCompleteIndex` 从当前 commit 沿 `tail` 链向父节点合并 incremental，直到遇到一个挂了 reference index 的 commit 为止；再叠加 reference index 上方的所有 incremental，即得到完整 key→content 视图。`StoreIndex` 按 byte-ordered key 组织，支持二分查找、range scan、前缀扫描。`diff(refA, refB)` 的实现就是两棵 StoreIndex 的 merge-join。

### 3.5 一致性模型：CAS + 重试循环

Nessie 的并发模型完全是 **乐观锁**，官方 `develop/kernel/` 原话：*"All write operations do support retries. Retries happen, if a non-transactional CAS operation failed or a transactional DML operation ran into an 'integrity constraint violation'. Both the number of retries and total time for the operation are bounded. There is an (exponentially increasing) sleep time between two tries."*

**`Persist` 接口**（`versioned/storage/common/persist/Persist.java`）暴露的关键方法（基于博客与签名惯例推断）：

- `storeObj / storeObjs / writeMany / fetchObj / fetchObjs / scanAllObjects / deleteObj` —— 对 `objs` 表的读写，单对象写入必须幂等（相同 `ObjId` 写入视为 no-op）。
- `addReference / updateReferencePointer(expected, newPointer) / markReferenceAsDeleted / purgeReference` —— 对 `refs` 表的 CAS 语义更新。**`updateReferencePointer` 是整个系统唯一需要强原子的操作**。

**`CommitLogicImpl.doCommit` 的 CAS 循环（基于 0.69.2 release note 与博客推断）**：

```
for attempt in 0..commitRetries:
    ref_before     = persist.fetchReference(branchName)
    parent_commit  = persist.fetchObj(ref_before.pointer)  as CommitObj
    parent_index   = IndexesLogic.buildCompleteIndex(parent_commit)
    
    # 校验
    for each Put op:
        validate expectedContent matches parent_index[key]
        validate content-id 唯一性 / payload 不可变
    for each Unmodified op:
        validate key unchanged since expectedHash
    detect key 冲突(同一 key 在并发 commit 里出现)
    
    # 构建
    new_index       = apply_ops(parent_index, ops)
    new_commit_obj  = build_commit(parent=ref_before.pointer, ops, index)
    persist.storeObj(new_commit_obj)    # 幂等
    persist.storeObj(new_index_objs)    # 幂等
    persist.storeObj(new_content_objs)  # 幂等
    
    # CAS
    ok = persist.updateReferencePointer(branchName,
                                        expected=ref_before.pointer,
                                        new=new_commit_obj.id)
    if ok: return success
    else:  sleep(exp_backoff); continue   # 幂等写入的 obj 保留或稍后 GC
```

这里的关键设计决策：**所有重 IO 先做（storeObj 幂等写入），最后只通过一次原子 CAS 更新 refs 表** —— 这让整个系统对 KV 存储的原子性要求降至最低（只需 single-key CAS），可以运行在 DynamoDB、Bigtable、Cassandra、MongoDB 等无事务的分布式 KV 上。CAS 失败时先前写入的 obj 会变成孤儿，由后台 cleanup 清理，这是**用"写放大换一致性模型简单性"的典型取舍**。

### 3.6 多表原子提交的原理

因为 Nessie 的提交粒度是 *"一个 `CommitObj` 携带任意多个 Operation + 一次 `updateReferencePointer` CAS"*，多个 Iceberg 表的变更天然可以打包进单个 commit：

- **原子性**：CAS 要么成功（所有 Put/Delete 一齐生效），要么失败（全部回滚，孤儿 obj 后台清理）。
- **隔离级别**：读取锁定到具体 hash（显式或解析当时的 HEAD）→ 天然 Snapshot Isolation；搭配 `Unmodified` operation 声明读集，就能提升到 Serializable。官方 Transactions 指南明确暴露三种级别（Read Committed / Repeatable Read / Serialized），**全部乐观、不支持悲观锁**。
- **冲突检测粒度**：ContentKey（≈表）级。两个 commit 都动同一 key 则后者 CAS 失败并重试；动不同 key 的话两者都能线性化成功。

### 3.7 服务层职责

`servers/services` 目录下的 `TreeApi` / `ContentApi` 把 HTTP 请求翻译成 `VersionStore` 调用。横切关切：

- **CEL 授权**：每个 API action 解析出 `op / role / ref / path / contentType / api / actions` 变量，按 `nessie.server.authorization.rules.*` 规则求值。
- **分页**：`pagingToken` 编码扫描游标。
- **Hash 解析**：支持 `branch`、`branch@hash`、`branch#timestamp` 三种坐标（**`HEAD~1` 形态的相对 hash 未正式文档化**）。
- **请求上下文传播**：MDC + OpenTelemetry trace 上下文。

### 3.8 部署形态

单一 Quarkus 应用 `nessie-quarkus:0.107.4`（Java 17 基线，推荐 21），两个端口：**19120**（API）、**9000**（management/metrics/cache-invalidation）。无状态，可水平扩展；多实例共享后端 DB，refs cache 通过 management 端口互相广播失效消息（但 **reference caching 仍是 experimental，默认关闭**）。

---

## 四、D · 协议与接口

### 4.1 REST API v2 已成主线

0.107.x 以 `/api/v2` 为稳定推荐（OpenAPI `nessie-openapi-0.107.4.yaml`，specVersion 2.2.0），v1 仍保留但文档与示例一律倾向 v2。端点集覆盖 `tree`（引用与 commit log）、`content`（按 key 读写）、`diff`、`namespace`、`config`、`reflog`。

### 4.2 Iceberg REST Catalog 兼容策略

这是 Nessie 2024 年以来最大的接口演进。官方 `guides/iceberg-rest/` 给出两种路径：

- **基础前缀**：`http://<host>:19120/iceberg`
- **指定分支/Tag**：在路径末段编码 —— `http://<host>:19120/iceberg/main`、`/iceberg/experiments`
- **指定 warehouse**：用管道符 `|` —— `/iceberg/experiments|sales`（分支在前、warehouse 在后）

**关键偏差**：Iceberg REST 标准用 `prefix` 参数传 reference，但 **Nessie 实现不要求客户端设置 prefix**（尤其 pyiceberg 设置 prefix 无效），分支信息编码在 URI 最后一段。这导致 *标准客户端只能看到默认分支（通常是 main）*，要切分支必须在 URI 里指定。Spark 配置示例：

```
spark.sql.catalog.nessie.type=rest
spark.sql.catalog.nessie.uri=http://127.0.0.1:19120/iceberg/dev/
spark.sql.catalog.nessie=org.apache.iceberg.spark.SparkCatalog
```

Nessie 的 commit log 概念在 Iceberg REST 标准里没有对应 —— 它通过 HTTP header（`Nessie-Commit-Authors`、`Nessie-Commit-Message`）注入元数据，但无法通过标准 IRC 客户端查 commit 历史，必须降级使用原生 `/api/v2` 或 Nessie CLI。

### 4.3 客户端与认证

- **Java**：`org.projectnessie.nessie:nessie-client`（配 BOM）。
- **Python**：pynessie 已被 Java CLI（`nessie-cli`）取代；新 Python 用户推荐用 pyiceberg 直连 Iceberg REST。
- **CLI**：`nessie-cli-0.107.4`（Docker `ghcr.io/projectnessie/nessie-cli:0.107.4`），SQL-ish 方言（`CONNECT`、`CREATE BRANCH`、`SHOW LOG`、`MERGE`）。
- **Spark SQL 扩展**：`nessie-spark-extensions-3.5_2.12:0.107.4`。
- **认证**：默认关闭；支持 `Bearer`、`OAUTH2`（client_credentials、password、authorization_code、device_code、token_exchange 多流程），AWS 签名亦可。**Nessie 自己的 OAuth2 客户端能力超过当前 Iceberg REST 客户端**（后者只支持 Bearer + client-id/secret）。Token exchange / impersonation 在 0.106 大幅重构，0.107.x 仍标为 beta。

### 4.4 Credential Vending

Iceberg REST 模式下，Nessie 把对象存储凭据下推给计算引擎，两种机制：

- **Vended / down-scoped credentials**：Nessie 调 STS（AWS / GCP / MinIO）签发短时凭据随 `FileIO` 返回；只在 `loadTable` 时一次往返。
- **S3 request signing**：每个 S3 请求 Nessie 代签；无需 STS，但延迟高。⚠ 对 Iceberg Java < 1.5.0 不工作；0.105.x 曾出现对批量删除失效的 bug（#11493）。

配置分层：`nessie.catalog.service.s3.default-options.*` + `nessie.catalog.service.s3.buckets.<name>.*`；凭据缓存默认 15 分钟 TTL，支持 Vault、AWS/GCP Secrets Manager、Azure Key Vault。**GCS 与 ADLS 仍标为 experimental**，ADLS 只能在 filesystem 粒度控制权限。

---

## 五、E · 功能矩阵

### 5.1 表格式支持

| 表格式 | 状态 |
|---|---|
| Iceberg v1 / v2 | 完整支持（v2 是事实默认） |
| Iceberg v3 | 0.107.x 官方兼容矩阵仅声明 Iceberg 1.5.0；**v3 支持未正式宣告** |
| Delta Lake | 代码中 `DeltaLakeTable` 存在但已 deprecated / 未再演进；第三方评测（lakeFS、Conduktor）统一结论"Nessie only supports Iceberg" |
| Hudi / Paimon | 官方无支持声明；0.107.0 的 #11973 仅为 Paimon 放宽字段 ID=0 限制 |

### 5.2 Schema 演化

Nessie **不做 schema 演化**，完全依赖 Iceberg 自身；schema-id 作为 on-reference-state 字段由 Iceberg 决定并记入 Put。

### 5.3 版本管理语义

- **branch**：可变，持续推进 HEAD。
- **tag**：不可变（一旦指向某 commit 不可更改），通过 `ASSIGN` 重新定位需专门权限。
- **commit**：hash 为 `ObjId` 的十六进制表示；支持自定义 author / signedOff / message。
- **merge**：官方 Java API 允许不同策略（fast-forward / regular），但 **客户端主要走 replay 模式**（官方 about 页原话："Nessie-merge operations technically work a bit different: the changes in branch to be merged are replayed on top of the target branch"），因此 Nessie commit 没有传统意义上的 merge commit（单 parent），merge 后对目标分支追加若干 replayed commits。
- **transplant (cherry-pick)**：从任意 reference 挑选 commit 序列移植到目标。
- **diff**：两个 reference 间 ContentKey 级差异。
- **history**：`LIST COMMIT LOG`，CEL 过滤，支持翻页。
- **WAP**：官方推荐用法就是 branch 写 + branch 审计 + merge 回 main；没有单独的 WAP 关键词。
- **Time travel**：三种坐标 —— commit hash / branch 或 tag 名 / 时间戳（Iceberg REST 反引号包裹："nessie.ns.\`tbl#2024-07-01T00:00:00Z\`"）。**相对 hash 语法未正式文档化**。
- **并发模型**：全面乐观，`expectedHash` 冲突抛 `NessieConflictException`。

### 5.4 非版本化功能的边界

| 领域 | Nessie 现状 |
|---|---|
| Compaction | **不做**；需外部引擎执行，但 Iceberg 自带的 expire-snapshots 等过程必须通过 Nessie 的 GC 或管理工具，不能直接运行 |
| GC（孤儿文件） | 独立工具 `nessie-gc`，mark-and-sweep 三阶段（identify → expire → delete，支持 deferred-delete）；只对 Iceberg 有效 |
| 元数据缓存 | 本地 Caffeine 缓存 objs 表；refs cache experimental、默认关闭 |
| 权限 | CEL-based RBAC，支持 20+ 种 `op`（VIEW_REFERENCE、READ_CONTENT_KEY、COMMIT_CHANGE_AGAINST_REFERENCE、UPDATE_ENTITY、CATALOG_CREATE_ENTITY 等）；**无行列级、无数据层拦截**（绕过 Nessie 直读 S3 无法阻止） |
| Policy 引擎 | **内置无**；可通过 CEL 规则实现基础策略 |
| 血缘 | **无**，非 Nessie 职责 |
| 审计 | commit log 即审计；`VIEW_REFLOG` 权限下可查 reference 级 reflog |
| Iceberg View | 作为 `IcebergView` Content 并入同一 commit 模型（版本化视图）；仍标 experimental |
| AI/ML 集成 | 弱；无 MLflow/Kubeflow/Feast connector，只能用 Java SDK 把训练输入锁到某 commit hash |
| 跨组织数据共享 | 无原生协议；典型路径是只读 tag + CEL 规则 + 只读 credential vending |

### 5.5 多表原子提交（重点）

这是 Nessie **最核心、最无可替代**的能力，值得单独剖析。

**语义**：一次 Nessie commit 可携带任意数量、任意表/视图/命名空间上的 Operation，作为不可分割的单元写入。要么全部生效，要么全部拒绝 —— 这超出传统 Iceberg 单表事务边界。

**原子性实现**：CAS 模型（见 3.5）保证 `refs` 表指针一次性翻转。服务端执行顺序：
1. 校验所有 Put 的 *expected on-reference state* 与当前一致；
2. 校验所有 Unmodified 对应 key 自 expectedHash 未变；
3. 构建新 `CommitObj` 并先写 objs（幂等）；
4. 对分支 HEAD 做 CAS 推进。

**隔离级别**：客户端读锁定到具体 hash → 天然 Snapshot Isolation；加 Unmodified 声明读集 → 提升到 Serializable。

**与 Iceberg 单表事务的差异**：Iceberg 原生事务以表的 metadata-location CAS 为锚，单表粒度；跨表一致性要外部协调。Nessie 把每个 Iceberg 单表事务封装成一个 Put operation，**一次 commit 装入多个 Put，原子边界从表扩到整 catalog**，可以实现"把数据从 table1 移到 table2，两表同时可见或同时失败"。代价是 Iceberg 自身的 snapshot 历史被 Nessie commit 历史接管 —— **`expire_snapshots`、`rewrite_manifests` 等 Iceberg 维护过程不能直接跑**，要走 Nessie 管理工具或 GC。

**落地限制**（这点需特别提醒用户）：虽然服务端能接受多表 operation 合并提交，**但主流计算引擎（Spark / Flink / Trino）仍以每次 DML 作为单表事务提交**。要真正在单 commit 里打包多表 Put，途径有二：(a) Nessie Java 客户端直接调 API；(b) 在 branch 上做多次单表 commit 后 fast-forward merge 回 main ——后者是工程上常用的"近似多表事务"（代价是下游看到一批 commit 同时到达而非单一 commit）。

---

## 六、F · 非功能特性

### 性能

官方 `guides/nessie_vs_git/` 给出设计目标：**10 万张 Iceberg 表 × 每 5 分钟一次 commit ≈ 333 commits/s**；README 声称"supporting millions of tables referencing exabytes of data with 1000s of operations per second"。早期 JGit+DynamoDB 原型仅 20 commits/s，因此被重写。Dremio 博客提到实现用了 151-way striped lock + 乐观锁。

**瓶颈**：后端 DB。官方 configuration 页原话："Relational databases are generally slower and tend to become a bottleneck when concurrent Nessie commits against the same branch happen." JDBC fetch-size 直到 0.107.0 才从"全量"改为默认 100（否则大 history 扫描易 OOM）。大 commit（数百 operations）与深 history 会触发更多 incremental→reference index spill，测试路径在 `perftest/gatling` / `perftest/simulations` / `versioned/persist/bench`。

### 可用性与扩展性

Nessie server **无状态**，Helm chart 部署多副本 Deployment；HA 靠后端 DB（BigTable / DynamoDB / MongoDB / Cassandra / JDBC）。水平扩展的隐患是 refs cache experimental —— 多实例部署需配 `cache-invalidations.service-names`，否则读到 stale HEAD。

### 可观测性

Micrometer 指标走 Quarkus `/q/metrics`（9000 端口），Prometheus 格式；支持 `nessie.metrics.tags.*` 自定义维度。OpenTelemetry tracing 走 Quarkus OTel extension。官方提供 `grafana/nessie.json` 起步模板（期望 `service`、`instance` 两个标签）。关键缓存指标：`cache=nessie-objects` tag 下的 hits/misses 与 evictions.cause。

### 安全

TLS 走 Quarkus 标配（生产常用 Ingress 终结），支持 OIDC / Bearer / AWS v4 签名。静态加密由后端 DB 提供。

### 多租户

**单 Nessie server ≠ 多 catalog**：一个 server 对应一个 Nessie repository，不是 Polaris 那种 multi-tenant 架构。多租户只能通过 (a) 跑多套 Nessie 实例、(b) 把 branch 当"环境"用、(c) 多 warehouse（仍共享版本树）。

---

## 七、G · 生态与集成

| 引擎 | 集成方式 |
|---|---|
| **Spark 3.3/3.4/3.5** | `nessie-spark-extensions`，SQL 扩展支持 `CREATE BRANCH`、`USE REFERENCE`、`MERGE BRANCH`、`SHOW LOG`；可走老 `NessieCatalog` 或新 Iceberg REST（`type=rest`） |
| **Flink 1.16/1.17/1.18** | 通过 Iceberg 的 NessieCatalog；版本矩阵与 Iceberg 1.5.0 绑定 |
| **Trino** | 官方推荐走 **Iceberg REST**（`connector.name=iceberg`、`iceberg.catalog.type=rest`、`iceberg.rest-catalog.uri=http://nessie:19120/iceberg/`） |
| **Presto** | 0.277–0.281，走 Iceberg connector |
| **Dremio** | 原生一等公民，Dremio Cloud 内建 Nessie |
| **Hive** | 官方矩阵标 `n/a`，弱化已久 |
| **Impala** | 无官方集成 |

**迁移路径**：官方 `iceberg-catalog-migrator` 子项目支持从 HIVE/GLUE/HADOOP/REST/JDBC/DREMIO 搬迁到 Nessie（仅搬 metadata pointer，不复制数据）。反向迁移困难，HMS/Glue 无法表达 branch/commit。

**AI/ML 框架**：无原生 connector，弱项。**云厂商**：Helm 支持 EKS/GKE/AKS；后端与云耦合（AWS→DynamoDB2、GCP→BigTable、Azure→Postgres/MongoDB），warehouse 支持 S3（含 OCI/MinIO/R2，0.106.x 加了 per-bucket `chunked-encoding-enabled`）、ADLS Gen2、GCS。

---

## 八、H · 运维与落地成本

### 后端选型

| 类型 | 场景 |
|---|---|
| `IN_MEMORY` | 仅测试 |
| `ROCKSDB` | 单节点嵌入式，边缘/Dev；无 HA |
| `JDBC2` | PG / MySQL / MariaDB / H2；**并发同 branch 易瓶颈**，0.107 fetch-size 默认 100 |
| `MONGODB2` | 社区常用成熟路径 |
| `DYNAMODB2` | AWS 首选 |
| `CASSANDRA2` | Cassandra / ScyllaDB |
| `BIGTABLE` | GCP 首选，大规模 repo 性能最好 |

**带 `2` 后缀的都是 0.75 后的新 Persist 模型**，旧 `MONGODB/CASSANDRA/JDBC/DYNAMO`（DatabaseAdapter 时代）已 deprecated，须用 Server Admin Tool 迁移。

### 部署与镜像

- **Helm**：`charts.projectnessie.org`，`helm install my-nessie nessie/nessie --version 0.107.4`。
- **Docker**：`ghcr.io/projectnessie/nessie:0.107.4` 与 `quay.io/projectnessie/nessie:0.107.4` —— 官方已停用 docker.io。CLI/GC/Admin 各有独立镜像。Java 17 最低、21 推荐，0.107.x 起 Java 11 彻底下线。

### 升级：DatabaseAdapter → Persist 的硬迁移

**不能原地升级**。官方 `guides/migration/` 流程：
1. 停机；
2. 源 < 0.75：用当年的 `nessie-quarkus-cli` 导出 zip；源 ≥ 0.75：用新 `nessie-server-admin-tool`；
3. `java -jar nessie-server-admin-tool-0.107.4-runner.jar export --path /tmp/export.zip`；
4. 建目标 DB、配好连接，`... import --path /tmp/export.zip [--commit-batch-size N]`；import 末尾自动做 commit-log optimization（回填 key-lists 与 commit-parent 列表，大 repo 性能关键）；
5. 启动新 server。

**副作用**：旧 global-state commit 会被转写为 on-reference-state，可能产生多份 on-reference-state 记录；commit seq 与 created 保留，内部索引由目标端重建。

### 运维工具

- **`nessie-cli`**（0.83.2 起替代 pynessie）：SQL-ish REPL，支持 `CONNECT TO`、`CREATE BRANCH`、`REVERT CONTENT`（0.99 加入）等。
- **`nessie-server-admin-tool`**（原 `nessie-quarkus-cli`）：子命令 `info`、`export`、`import`、`cleanup-repository`（bloom filter 识别孤儿对象，1e6 对象容量默认，需 `--obj-count` 调）、`cut-history`（0.99 引入，合规场景断开父链）、`erase-repository`、`check-content`、`delete-catalog-tasks`。
- **`nessie-gc`**：Iceberg 数据文件 mark-and-sweep；策略含 `num-commits`、时间 cutoff；支持 deferred-delete；`--expiry-parallelism` 默认 4 并发；**只支持 Iceberg**。

### 文档

projectnessie.org 按 release 固化 URL（`/nessie-0-107-4/...`），guides 覆盖 Docker / K8s / Minikube / Keycloak / Grafana / 反向代理 / TLS / 管理；**空白**：中文/本地化为零；性能基准与容量规划缺少官方数字；best practices 单薄；K8s Operator 未 GA；Iceberg REST 与原生 API 两套路径混用易混淆初学者。

---

## 九、I · 社区与治理

### 是否进入 ASF？

**截至 2026-04-17，Project Nessie 未进入 Apache 孵化、不是 TLP**，仍独立托管在 projectnessie GitHub org。Apache 2.0 license，但非 ASF 项目。佐证是 Nessie 核心人员（JB Onofré、Robert Stupp、Alexandre Dutra、Dmitri Bourlatchkov、Eduard Tudenhoefner）的主战场已转到 Polaris：

- **Apache Polaris 2024-08-09 进孵化**，**2026-02-18 晋升 TLP**（Snowflake 2026-02-19 博客、Polaris 官方 graduation 博客、Dremio 新闻稿），**首任 PMC Chair 为 Dremio 的 JB Onofré（ASF Board 成员）**。

### 主导地位与贡献活跃度

Dremio 占据近乎全部核心 maintainer。发布节奏（来自 projectnessie.org/releases 完整日期）：

| 版本 | 日期 |
|---|---|
| 0.100.0 | 2024-11-12 |
| 0.101.0 | 2024-12-06 |
| 0.102.0 | 2025-01-21 |
| 0.103.0 | 2025-02-18 |
| 0.104.0 | 2025-05-06 |
| 0.105.0 | 2025-09-03 |
| 0.106.0 | 2025-12-05 |
| 0.107.0 | 2026-01-28 |
| 0.107.4 | 2026-03-09 |

从 0.100 到 0.107 跨度 **15 个月**，**minor 版本间隔从早期 1 月延至 2025 年 3–4 月**，节奏明显放缓。对比 Polaris 孵化 18 个月关 2,800+ PR、~100 contributors，Nessie 社区活跃度已相形见绌。

**无公开 1.0 Roadmap**；无独立 `rfcs/` 目录；技术决策分散在 GitHub Issues 与 `site/in-dev`。

---

## 十、J · License 与商业化

- **License**：Apache 2.0，所有模块一致。
- **商业化主体**：仅 Dremio（Arctic / Enterprise Catalog / Lakehouse Catalog）。未见第二家规模化托管 SaaS；Bauplan、lakeFS、Conduktor、e6data、Atlan 只是在自家产品里列为支持的 Iceberg 目录。
- **Fork-ability**：模块清晰（nessie-client / model / server / catalog-service / quarkus / gc / cli / spark-extensions），**无 Dremio 商业产品硬依赖**，技术上可 fork；但治理上几乎全部 maintainer 来自 Dremio，路线控制权不在社区。
- **Trademark**：无独立 trademark 政策页；"Project Nessie" 未见 USPTO 正式注册；Dremio 商业产品名 "Arctic" 是 Dremio 商标。

---

## 十一、K · Roadmap 与趋势

### 2025–2026 重点演进

1. **Iceberg REST 原生支持** 持续打磨（2024 起实验，2025 全年修 bug）。
2. **后端多样化**：MariaDB / MySQL（Vayuj Rajan 贡献）；S3 chunked-encoding per-bucket（OCI 等）。
3. **OAuth2 / 安全强化**：token exchange / impersonation（0.106 重构、0.107 beta）。
4. **`cut-history` admin 命令**（0.99，#10048），深历史合规场景可断链。
5. **Paimon 兼容**：0.107.0 放宽 Iceberg 字段 ID=0（#11973）。
6. **Java 17 基线**：0.107.0 起强制，跟随 Iceberg 决策。
7. **没有**新存储模型 v2、没有 1.0 规划；重构基本收敛。

### 与 Apache Polaris 的关系（最关键）

**官方路线是"能力并入 Polaris"，而非并排独立**：

- Snowflake 2024-07 博客："Dremio excited to help bring the various functions and capabilities of Nessie into the Polaris project."
- Dremio VP Product James Rowland-Jones 2024-10："Our goal is to merge the capabilities of Project Nessie into Apache Polaris to create a single, unified catalog."
- Dremio CMO Read Maloney 2024-10 对 SiliconANGLE："we will merge Nessie into Polaris … at which time **Project Nessie will be retired**."
- Nessie 官方博客（Robert Stupp 2024-08-02）措辞更温和："intent is to contribute Nessie's capabilities, like Catalog Level Versioning, Git-like semantics, multi-table transaction semantics to Polaris."

**实际进度（截至 2026-04）**：

- **Git-like branch/merge/commit 尚未进入 Polaris**。Polaris 1.3.0-incubating（2026-01-09）与 1.4.0 规划重点是 Ranger 授权、credential vending（Azure/GCS）、catalog federation、metrics reporting —— 都不是 Git 式版本控制。
- Polaris 仍基于关系型元数据存储（JPA），没有合并 Nessie 的 commit kernel。
- 人员层面高度重合，但代码层没有大型 PR 把 Nessie 的 commit kernel / 151-way striped lock 搬过来。
- JB Onofré 2024-09 BigDataWire 采访强调 Polaris 要避免偏离 Iceberg REST spec，暗示 **branch/merge 语义可能先进 Iceberg Spec 再进 Polaris**。

**结论**：方向 = 最终合并；节奏 = 远比 2024 预期慢；**Nessie 在 2026 年仍作为独立项目维护**（0.107.x 规律修补即证据），但 Dremio 资源重心已倾 Polaris。

---

## 十二、L · 已知缺陷与局限

### 性能瓶颈

- 同 branch 高并发 commit 本质是乐观锁串行；JDBC 后端尤其易阻塞（官方配置文档原话）；`cleanup-repository` 在大 index 上曾有性能 bug（0.104.x 专门修复）。
- bloom filter 默认 1e6 对象规划，超大 repo 需 `--obj-count` 手工调。
- 0.107.3 修了 `commit-log` 提前终止 bug（#12135）。
- **最舒服 workload = 多浅分支 + 低并发写**；**最痛 workload = 深 history + 单 branch 高频写**。

### Experimental / 半成品地带

- **Reference caching** 至今 disabled by default：博客原话 *"would be fine to enable it, but only if only a single Nessie instance accesses the repository"*；多实例下若没配 `cache-invalidations.service-names` 会读到 stale HEAD。
- **Iceberg View** 仍 experimental。
- **GCS / ADLS credential vending** 仍 experimental。
- **K8s Operator** 未 GA。
- **Token exchange / impersonation** 0.106 重构后 beta。

### 与 Iceberg REST 标准客户端的兼容边界

- 标准 IRC 客户端不能通过 `prefix` 参数切换 Nessie 分支，只能把分支名编码在 URI 末段（pyiceberg 设置 prefix 无效）。
- Nessie 的 commit log 概念在 IRC 标准里无对应，commit 元数据只能通过 HTTP header 写入。
- **#10215**：Iceberg REST + Bearer auth 冻结（0.101.3 + Spark 3.5.4）。
- **0.105.5–0.106.0** 反向代理场景 bug（0.107.0 才修）。
- **S3 request signing** 对 Iceberg Java < 1.5.0 不工作；0.105.x 曾对批量删除失效（#11493）。

### GitHub Issues 反复出现的问题

- **#10235**（GC expire 在 drop 表上 NoSuchKey，0.101.3）、**#9097**（dropped table files 未被清理）、**#10748**（CLI 0.103.3 + AWS creds 连不上）、**#10809**（ADLS over HTTPS 文档与实现不一致）、**#11145**（OpenJDK 24 toolchain 找不到）、**#11971 / #11849 / #11828 / #11767 / #11759 / #11665 / #11579**（2025-11~2026-01 新开 batch，覆盖 Quarkus 日志、K8s service-name resolution、renovate 依赖治理 #5255 长期 open）。
- **Backlog 三大主题**：GC 清理不干净、REST+auth 组合边角、大 repo 性能。
- 维护者（`snazy`/`adutra`）响应快但 backlog 持续累积。

### 第三方评测反馈

- **lakeFS**（竞品）：强调 Nessie 只支持 Iceberg，不做数据文件版本。
- **RisingWave / Conduktor / e6data 2025**：归类为 "advanced but niche"；REST Catalog（Polaris/Lakekeeper）推荐为新项目默认；Nessie 价值锁定在"需 branching + 多表原子 commit"的团队。
- **Nexla**：认知成本高，需要团队重新设计分支策略。
- **Reddit / dev.to**：branching 体验好，但 merge 冲突处理的 UX 原始（CLI 报错不直观）。
- **生产级成功案例公开披露偏少**；Medium 上 Iceberg+Nessie 博客多是 dev 环境演示。

### 学习曲线与认知成本

- 用户需同时理解 Iceberg snapshot 与 Nessie commit 两层历史。
- Nessie merge 语义是 replay（非 Git 那种合并 commit），容易被熟悉 Git 的用户误解。
- Iceberg REST 与原生 API 两套路径共存、warehouse × reference 的 URL 组合容易绕晕。
- **ML 生态、Delta/Hudi 支持、跨组织数据共享 都弱**，不适合想要"一揽子 Lakehouse governance"的团队。

---

## 十三、设计启示（对自研 Catalog 的决策参考）

这份报告的最终价值是回答一个问题：**自研 Lakehouse Catalog 是否值得、能否从 Nessie 借鉴哪些设计？**

### 启示一：Git-for-Data 是高价值但高成本的差异点

Nessie 的 Git-like 能力确实独一无二（Polaris/Unity/Gravitino 至今都没做），**但到 2026 年仍没有被业界主流选为默认模式**。原因三点：

1. **场景窄**：真正需要跨表原子事务 + 分支合并的团队是做 DataOps/CI-for-Data 的数据平台团队，占比有限。
2. **引擎层生态弱**：Spark/Flink/Trino 默认提交粒度仍是单表，要真正用多表原子 commit 必须用 Nessie Java SDK 或"branch + fast-forward merge"变通手法。
3. **语义复杂**：Nessie merge 是 replay 不是传统 Git merge commit（单 parent），冲突处理粒度是 ContentKey 级（而非 row/column 级），用户认知成本高。

**决策建议**：如果自研 Catalog 的目标用户不是专门做 DataOps/ML 实验管理/合规审计的团队，**不建议把 Git-for-Data 做成 Tier-1 能力**；作为可选插件或只保留 branch/tag 这类浅层能力即可。Iceberg 1.x 已原生支持表级 branch/tag，很多"分支"诉求可以在 Iceberg 层解决，Catalog 层做 *catalog-wide 原子事务* 的 ROI 最高。

### 启示二：内容寻址 + 双表存储模型值得复刻

`refs` + `objs` 两张表的切分是 Nessie 真正的架构亮点：

- **immutable `objs` 可无限制本地缓存**（objectID = 内容 hash，跨实例天然一致），缓存命中率高、水平扩展简单。
- **可变 `refs` 的原子更新只需 single-key CAS**，把事务要求降到 DynamoDB/Bigtable/Cassandra 都能满足的最低门槛。
- **KV 与 RDB 共用同一逻辑层**（Persist SPI），后端切换成本低。

**决策建议**：自研 Catalog 若走"Iceberg REST + 扩展能力"方向，建议 **元数据层也按 immutable-objects + mutable-pointers 切分**，不论后端选 Postgres 还是 DynamoDB，都能享受这套模型的优势。具体落地：

- Object ID = BLAKE3 / SHA-256 / xxhash（Nessie 用 32 字节定长 ID）。
- 表里只有 `id → blob`，所有 CRUD 幂等（重复写入不冲突）。
- 所有可变指针（当前 snapshot、分支 HEAD 等）集中到一张 refs 表，用 CAS 单点更新。

### 启示三：乐观并发的适用边界要画清

Nessie 全乐观锁、CAS + 重试循环，在**多分支浅 commit** 场景优雅；在**单 branch 高并发深 history** 场景就是典型的 ABA 陷阱（commit 序列化 + 重试风暴）。用户遇到 Spark 高并发 compaction 时会看到大量 `Reference hash is out of date` 警告（见 Google Groups 讨论）。

**决策建议**：自研 Catalog 的 hot path（单表高并发写）应当提供 **可选的悲观锁或分区并发**；单纯靠乐观 CAS 无法满足金融/广告这种"同一张表多 writer 同时提交"的稳态负载。Nessie 的做法是"靠 Iceberg 客户端的 retry 吸收"，这在规模上会劣化长尾延迟，不建议完全照搬。

### 启示四：Persist SPI 的分层抽象值得学习

Nessie 把 "对象读写 + 指针 CAS" 的 SPI (`Persist`) 与业务逻辑（`CommitLogic`/`IndexesLogic`/`ReferenceLogic`）彻底分离，使得增加新后端（RocksDB、BigTable、JDBC2）只需实现 ~10 个方法，业务逻辑零改动。这种分层在自研 Catalog 里是 **低成本多后端支持的最佳实践**，建议直接借鉴：

- 上层 Logic 完全用 `ObjId` 和不可变 Obj 对话，不知道底层是 KV 还是 RDB；
- 后端 adapter 只要实现幂等写 + 单 key CAS + range scan 三项；
- 测试用 InMemory 后端可跑全量语义测试，CI 成本低。

### 启示五：战略风险不可忽视

**Dremio 已公开表态最终把 Nessie 能力并入 Polaris**，2026-04 合并进度虽慢于预期，但方向明确。选型 Nessie 意味着：

- 12–24 个月仍安全（0.107.x 持续发布）；
- 之后大概率要迁到 Polaris 或承担独立维护成本；
- **除非自研 Catalog 打算永久 fork 并承接 Nessie 核心**，否则不建议把生产关键路径押在 Nessie 长期演进上。

**替代路径评估**：

- 若只要 Iceberg REST + 多租户 + 治理 → Polaris TLP；
- 若要 Git-for-Data + 短期确定性 → Nessie 0.107.x 仍是唯一选择，但准备好迁移预案；
- 若做自研 → 借鉴 Nessie 的 Persist/双层索引/CAS 模型 + Polaris 的 多租户/授权/Spec 对齐，是最优组合。

### 启示六："不做什么"的定力值得学习

Nessie 坚持只托管 Iceberg 一等公民、不做血缘、不做业务目录、不做数据层授权、不做 compaction —— 这种**窄而深**的产品哲学让核心代码量保持可控（1,438 个 Java 文件），核心 team 十余人能长期维护。自研 Catalog 面对需求压力时，尤其要守住"只做元数据事务 + 可选权限"的边界，把 lineage、policy、discovery、ML 特征等能力交给上层平台。

---

## 十四、参考资料

### 官方资料

- Project Nessie 官网：`https://projectnessie.org/`
- 发布列表：`https://projectnessie.org/releases/`
- Commit Kernel 架构页（含 DatabaseAdapter 过时提示）：`https://projectnessie.org/develop/kernel/`
- 0.107.4 着陆页：`https://projectnessie.org/nessie-latest/`
- 服务器配置：`https://projectnessie.org/nessie-latest/configuration/`
- Iceberg REST 指南：`https://projectnessie.org/guides/iceberg-rest/`
- Nessie vs Git（性能设计目标）：`https://projectnessie.org/guides/nessie_vs_git/`
- 关于 Nessie：`https://projectnessie.org/guides/about/`
- 迁移指南：`https://projectnessie.org/guides/migration/`
- Admin Tool 导入导出：`https://projectnessie.org/nessie-0-107-4/export_import/`
- 管理服务（GC）：`https://projectnessie.org/guides/management/`
- 官方博客：`https://projectnessie.org/blog/`
- Cache improvements 博客（2024-06）：`https://projectnessie.org/blog/2024/06/05/nessie-cache-improvements/`
- Polaris announcement 博客（2024-08）：`https://projectnessie.org/blog/2024/08/02/open-source-polaris-announcement/`
- Repository configs 扩展：`https://projectnessie.org/develop/repository-configs/`
- Spark SQL 扩展文档：`https://projectnessie.org/nessie-latest/spark-sql/`
- Trino 集成文档：`https://projectnessie.org/nessie-latest/trino/`

### 源码路径（GitHub projectnessie/nessie，main 分支）

- 仓库入口：`https://github.com/projectnessie/nessie`
- Persist SPI：`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/persist/`（`Persist.java`、`ObjId.java`、`Obj.java`）
- Logic 层：`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/logic/`（`CommitLogicImpl.java`、`IndexesLogicImpl.java`、`ReferenceLogicImpl.java`、`RepositoryLogicImpl.java`、`MergeTransplantLogic*`）
- Obj 类型：`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/objtypes/`（`CommitObj`、`ContentValueObj`、`IndexObj`、`IndexSegmentsObj`、`RefObj`、`StringObj`、`UniqueIdObj`）
- 索引实现：`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/indexes/`（`StoreIndex`、`StoreIndexElement`）
- VersionStore：`versioned/storage/versionstore/src/main/java/org/projectnessie/versioned/storage/versionstore/VersionStoreImpl.java`
- 后端 adapters：`versioned/storage/{inmemory,rocksdb,jdbc2,mongodb2,dynamodb2,cassandra2,bigtable}`
- Service 层：`servers/services/`
- Catalog（Iceberg REST）：`catalog/service/`、`catalog/files/`、`catalog/secrets/`
- Quarkus server：`servers/quarkus-server/`
- Spark 扩展：`nessie-integrations/nessie-spark-extensions-*`
- Admin/GC/CLI：`tools/server-admin/`、`gc/`、`cli/`
- 官方 site 源：`site/in-dev/`（含 develop、guides 文档）

### 社区讨论与 Issue

- GitHub Issues：`https://github.com/projectnessie/nessie/issues`
- Zulip：`https://projectnessie.zulipchat.com`
- Google Group：`https://groups.google.com/g/projectnessie`
- 典型 issue：`#10235`（GC NoSuchKey）、`#9097`（GC 未清理）、`#10748`（CLI + AWS）、`#10215`（Bearer auth 冻结）、`#10809`（ADLS HTTPS）、`#11145`（JDK 24）、`#11493`（S3 signing 批量删除）、`#11973`（Paimon field ID 0）、`#12135`（commit-log 截止）、`#5255`（renovate 长期 open）

### Dremio / Snowflake / Polaris 相关

- Dremio Introducing Project Nessie（2020-05）：`https://www.dremio.com/blog/introducing-project-nessie/`
- Dremio Project Nessie 深度介绍：`https://www.dremio.com/blog/project-nessie-transactional-catalog-for-data-lakes-with-git-like-semantics/`
- Dremio What is Nessie Catalog Versioning：`https://www.dremio.com/blog/what-is-nessie-catalog-versioning-and-git-for-data/`
- Dremio Nessie Ecosystem：`https://www.dremio.com/blog/the-nessie-ecosystem-and-the-reach-of-git-for-data-for-apache-iceberg/`
- Dremio Polaris TLP 博客：`https://www.dremio.com/blog/apache-polaris-graduates-to-a-top-level-apache-project/`
- Dremio Arctic 文档：`https://docs.dremio.com/cloud/arctic/`
- Dremio Nessie 数据源文档：`https://docs.dremio.com/current/data-sources/lakehouse-catalogs/nessie/`
- Snowflake Polaris 开源博客（2024-07）：`https://www.snowflake.com/en/blog/polaris-catalog-open-source/`
- Snowflake Polaris TLP 博客（2026-02）：`https://www.snowflake.com/en/blog/apache-polaris-top-level-project/`
- Apache Polaris 官网：`https://polaris.apache.org/`
- Polaris 孵化 clutch：`https://incubator.apache.org/clutch/polaris.html`
- Iceberg Catalog Migrator：`https://www.dremio.com/blog/introducing-the-apache-iceberg-catalog-migration-tool/`

### 第三方分析

- BigDATAwire "Polaris Catalog to be Merged with Nessie"（2024-07）：`https://www.bigdatawire.com/2024/07/30/polaris-catalog-to-be-merged-with-nessie-now-available-on-github/`
- BigDATAwire Dremio Hybrid Catalog（2024-10）：`https://www.hpcwire.com/bigdatawire/2024/10/29/dremio-goes-hybrid-with-nessie-based-metadata-catalog/`
- SiliconANGLE Dremio Polaris Support（2024-10）：`https://siliconangle.com/2024/10/29/dremio-throws-support-polaris-data-catalog-expands-deployment-options-iceberg-lakehouse/`
- e6data Iceberg Catalogs 2025：`https://www.e6data.com/blog/iceberg-catalogs-2025-emerging-catalogs-modern-metadata-management`
- RisingWave Catalog Comparison：`https://risingwave.com/blog/iceberg-catalog-comparison-guide/`
- Conduktor Iceberg Catalog Management：`https://www.conduktor.io/glossary/iceberg-catalog-management-hive-glue-and-nessie`
- lakeFS Nessie Catalog 对比：`https://lakefs.io/blog/nessie-catalog/`
- Alex Merced Apache Data Lakehouse Weekly（2026-04）：`https://dev.to/alexmercedcoder/apache-data-lakehouse-weekly-april-3-9-2026-k5l`
- OpenSourceForU Dremio Polaris（2026-04）：`https://www.opensourceforu.com/2026/04/dremio-strengthens-open-data-standards-with-iceberg-v3-and-polaris/`

---

## 信息置信度与未解疑点

**高置信**：0.107.x 发布日期；Polaris TLP 里程碑；Nessie 未进入 ASF；License = Apache 2.0；后端类型与迁移流程；CEL 授权模型；Iceberg REST 端点路径；多表 commit 原子性机制；双层索引与 CAS 重试循环。

**中置信（基于博客 + release notes 推断）**：`CommitObj` / `Persist` 的具体字段与方法签名——web_fetch 对 GitHub raw 源码文件无权限，未能逐行验证；151-way striped lock 的具体实现细节；spill 阈值的默认值。

**未解疑点（本报告未能确认）**：Iceberg v3 支持状态；UDF Content 类型的稳定状态；Hudi 官方兼容计划；`HEAD~1` 相对 hash 语法；Nessie commit kernel 被合并到 Polaris 的具体技术路线图与时间表。建议在正式选型决策前，通过 Zulip 或 GitHub Discussion 向 maintainer 直接确认。