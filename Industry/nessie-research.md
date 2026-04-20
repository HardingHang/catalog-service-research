# Project Nessie 调研报告

> 面向自研 Lakehouse Table Catalog 选型预研的 Tier 1 Deep-Dive 材料。内容基于 Nessie 官方文档、GitHub 源码（0.107.5-SNAPSHOT @ main，2026-04-13）、稳定发布 0.107.4（2026-03-09）、Dremio / Snowflake / Apache Polaris 官方材料、以及 2024–2026 年社区讨论与第三方评测。
>
> 本文为独立完整报告，阅读无需依赖其他前置材料。

---

## 1. 项目定位

### 1.1 一句话结论

**Project Nessie 是一个面向 Data Lake / Lakehouse 的事务型元数据目录服务（Catalog），其独特之处在于把 Git 风格的语义引入数据湖元数据管理。**

具体能力包括：branch、tag、commit、merge、transplant（cherry-pick）、diff、history。Nessie 的核心不存储数据文件本身，而是对表、视图、namespace 等元数据对象提供 **版本控制、事务隔离、一致性发布和回滚**。数据文件仍由对象存储（S3、GCS、ADLS）承载；Nessie 管理的是这些对象在某个逻辑引用上的可见版本。

从业务角度可概括为：

- 给数据湖目录加上 Git-like 语义；
- 给多表元数据变更加上事务边界；
- 给 Iceberg 等湖仓表格式提供版本化 catalog 能力。

官方首页标语至今是 **"Transactional Catalog for Data Lakes with Git-like semantics"**。

### 1.2 发起方与时间线

Nessie 由 **Dremio 联合创始人 Jacques Nadeau（Apache Arrow 创建者之一）与 Tomer Shiran** 于 2020 年 5 月公开发布（Jacques Nadeau《Introducing Project Nessie》，dremio.com/blog/introducing-project-nessie/），团队此前已孵化约一年。核心 maintainer 来自 Dremio：Robert Stupp（@snazy，官方博客主笔）、Alexandre Dutra（@adutra，OAuth2 / REST 负责人）、Dmitri Bourlatchkov（@dimas-b）、Eduard Tudenhoefner（@nastra，Iceberg PMC）、Laurent Goujon、Ajantha Bhat、Ryan Murray，外加零散外部贡献者（如 Vayuj Rajan 贡献 MariaDB / MySQL 后端）。Maven POM developers 字段列出 17 人，绝大多数为 Dremio 雇员。

Jacques Nadeau 对设计动机的原话：*"A set of distributed applications can each perform independent transactions and Git provides the semantics to safely and effectively merge them."*

### 1.3 版本与源码状态

- 本次调研基于的本地代码版本：`0.107.5-SNAPSHOT`，当前本地分支为 `main`（不是 `master`），最近一次本地提交时间为 2026-04-13。
- 官方当前稳定线：**0.107.4（2026-03-09 发布）**。
- Java 基线：从 0.107.0 起强制 Java 17，推荐 Java 21。

### 1.4 明确的非目标

理解 Nessie "不做什么" 与 "做什么" 同样重要：

- **只托管 Iceberg 表与 Iceberg 视图两种一等公民 Content 类型**（Namespace 作为容器对象）。Dremio 官方文档原话："You can create and store Apache Iceberg tables and views in the Nessie catalog. **No other file or source types can be stored in the Nessie catalog.**"
- **不做业务数据目录**（lineage / business glossary / dataset discovery），这些能力由 Dremio Enterprise Catalog、Atlan、Collibra 等上层产品提供。
- **不复用 Git 底层实现**。早期用 JGit + DynamoDB 的原型仅能达到 ~20 commits/s，被整体抛弃重写。Dremio 博客原话："Nessie is Git-like because it isn't actually using Git under the hood (that would be orders of magnitude too slow)."
- **不在 Catalog 层做数据层授权**——只做元数据授权，数据层交给对象存储（credential vending / S3 signing）。
- **不做 schema 演化**、不做 compaction、不做血缘——这些都交给 Iceberg 与外部工具。

### 1.5 与 Dremio 商业产品及 Apache Polaris 的关系

- **Dremio Arctic**（2022 上线的托管 SaaS）= 托管 Nessie + Iceberg + Optimization Service（自动 compaction、清理）。
- **Dremio Enterprise Catalog / Lakehouse Catalog**（2024-10 GA）底层仍基于 Nessie。
- **战略方向**：Dremio 已公开表态要把 Nessie 的能力并入 Apache Polaris。详见第 10 章（战略风险与 Roadmap）。

---

## 2. 业务场景

### 2.1 数据湖上的分支开发和隔离实验

数据团队可以在独立分支上做表结构调整、视图变更、数据重写、回填或验证性修改，而不影响主分支上的生产查询。

典型收益：

- 降低直接改生产目录的风险；
- 支持 what-if 实验；
- 多人并行开发互不干扰。

### 2.2 多表一致性发布

Nessie 的价值不只是单表版本控制，更重要的是一组表 / 视图元数据的原子发布。多个对象变更可以在同一个 commit 中提交，并通过 merge 统一暴露到目标分支。这是 Nessie **最核心、最无可替代** 的能力，详见第 5.4 节。

典型收益：

- 保证上下游表切换的一致性；
- 支持跨表 schema 演进；
- 适合维表、事实表、视图需要同时切换的场景。

### 2.3 Write-Audit-Publish 发布流程

Nessie 最典型的使用模式。传统流程里，Spark 直接写 prod 表、写完跑数据质量检查、检查失败用户已看到脏数据；Nessie 流程：

```
1. CREATE BRANCH qa_2026_04_17            # 零成本分支，不复制任何数据
2. Spark 向 qa 分支写入数据               # 生产用户完全看不到
3. 在 qa 分支上跑数据质量检查
4. 检查通过 → MERGE qa_2026_04_17 INTO main
5. 检查失败 → DROP BRANCH qa_2026_04_17   # 零成本废弃、生产零影响
```

这就是把软件工程的 Pull Request 工作流平移到数据管道，常被描述为 "CI/CD for Data"。

### 2.4 审计、回滚和差异分析

Nessie 提供 commit log、reference history、diff、entries 等能力，适合：

- 发布审计；
- 事故回滚；
- 版本差异分析；
- 问题定位。

### 2.5 多引擎共享 Catalog

Nessie 通过两条路径服务外部引擎：

- **原生 API**（Nessie REST v2）：Spark Extensions、Nessie CLI、Java 客户端。
- **Iceberg REST Catalog**：Spark / Flink / Trino / pyiceberg 等任何标准 Iceberg REST 客户端。

典型收益：

- 多计算引擎共享一套版本化元数据；
- 降低 catalog 接入复杂度；
- 提供统一的元数据治理边界。

### 2.6 生产治理与运维配套

主仓库中除核心版本存储外还包含：事件通知、垃圾回收、secrets 管理、对象存储接入、OIDC 认证、CEL 授权。Nessie 不再是单一版本引擎，而是朝着完整湖仓 catalog 平台演进。

---

## 3. 核心技术点

### 3.1 Git-like 语义作用于元数据层

Nessie 的版本控制对象是表、视图、namespace 等 content 元数据，而不是数据文件本身。这让它能以较低成本实现分支、提交、合并和回滚。

### 3.2 内容寻址与不可变对象

新存储模型中，每个对象（`Obj`）的 ID 由对象内容的哈希（32 字节）导出。带来两个重要效果：

- **对象天然不可变**；
- **缓存不需要复杂的分布式一致性协议**：相同内容必然产生相同 ID，跨实例天然一致。

这是性能和实现简洁性的关键基础，详见第 4.3 节。

### 3.3 乐观并发控制与单 key CAS

底层 `Persist` 接口要求引用更新和对象写入满足 CAS-like 语义。提交时如果 branch HEAD 变化，则当前提交会冲突并重试，而不是通过中心锁管理。**真正需要原子的操作只有一个——单 key CAS（refs 表指针翻转）**，把事务要求降到了 DynamoDB、Bigtable、Cassandra 等无事务分布式 KV 都能满足的最低门槛。详见第 4.4 节。

### 3.4 双层索引结构

新存储模型使用 **incremental index + reference index** 双层结构：

- 每个 commit 在 `CommitObj` 内嵌一份增量索引（体量小）；
- 累积到阈值后 spill 成独立的 `IndexObj`（完整快照）；
- 读路径沿 commit 父链合并 incremental + reference index 得到完整 key→content 视图。

这种模式与 LSM-Tree / RocksDB 的 compaction 思想同源，在大量 key 的情况下仍能保持较好的 point lookup、range scan 和 diff 性能。详见第 4.3 节。

### 3.5 更严格的提交校验

相比旧模型，新模型对内容变更做了更严格的校验：

- 新内容不能带已有 content-id；
- 更新必须带 `expectedContent`；
- 更新不能改变 payload 和 content-id。

Nessie 不只是记录版本，而是在做 **catalog consistency enforcement**。

### 3.6 服务层承担真实语义编排

REST 资源层相对薄，真正复杂的逻辑在 service layer。服务层负责：

- 鉴权（CEL-based）；
- 过滤；
- 分页；
- hash 解析；
- 异常映射；
- 请求上下文封装。

### 3.7 多后端存储适配

主线支持的版本存储类型：

- `IN_MEMORY`、`ROCKSDB`（单机嵌入式）；
- `JDBC2`（PG / MySQL / MariaDB / H2）；
- `MONGODB2`、`DYNAMODB2`、`CASSANDRA2`、`BIGTABLE`（分布式 KV / 云 DB）。

后缀 `2` 标识 0.75 版本后的新 Persist 模型，旧的 `MONGODB / CASSANDRA / JDBC / DYNAMO`（DatabaseAdapter 时代）已 deprecated。

### 3.8 平台化扩展能力

主仓库已包含以下平台化能力：

- Iceberg REST Catalog；
- Events（事件通知）；
- GC（孤儿文件回收）；
- Secrets（凭据管理）；
- Object store integration；
- Tasks（后台任务）。

这使它接近一个可生产落地的元数据服务平台，而不只是一个研究性版本控制内核。

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
                    │
                    ▼
┌──────────────────────────────────────────────────┐
│ Service Layer (servers/services)                 │
│   TreeApiImpl / ContentApiImpl /                 │
│   DiffApiImpl / NamespaceApiImpl                 │
│   + CEL Authorization、分页、hash 解析            │
└───────────────────┬──────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────┐
│ Version Semantics Layer                          │
│   (versioned/storage/versionstore)               │
│   VersionStoreImpl                               │
└───────────────────┬──────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────┐
│ Storage Logic Layer                              │
│   (versioned/storage/common/logic)               │
│   CommitLogicImpl / IndexesLogicImpl /           │
│   ReferenceLogicImpl / RepositoryLogicImpl /     │
│   MergeTransplantLogic                           │
└───────────────────┬──────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────┐
│ Persist SPI                                      │
│   (versioned/storage/common/persist)             │
│   Persist 接口 + Obj 类型体系 + ObjId            │
└───────────────────┬──────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────┐
│ Storage Backends                                 │
│   JDBC2 / MongoDB2 / DynamoDB2 / Cassandra2 /    │
│   BigTable / RocksDB / InMemory                  │
└──────────────────────────────────────────────────┘

Service Layer 还会对接：
  ├─ Platform Capabilities (Events / GC / Secrets / Tasks / AuthN / AuthZ)
  └─ Object Storage (S3 / GCS / ADLS) — 读取/写入 Iceberg metadata JSON
```

### 4.2 模块视图

```
API 定义与接入
  api/model                 # API 契约与 HTTP 接口
  api/client                # Java 客户端
  servers/rest-services     # JAX-RS / Quarkus REST 资源
  catalog/service/rest      # Iceberg REST Catalog endpoint

服务编排
  servers/services          # 业务编排、鉴权、过滤、分页
  servers/store             # 版本存储适配
  servers/quarkus-server    # Quarkus 运行时装配

版本语义与存储
  versioned/spi                       # 版本语义接口
  versioned/storage/common            # 新存储模型核心抽象（Persist、Obj、Logic）
  versioned/storage/versionstore      # VersionStoreImpl
  versioned/storage/{inmemory,rocksdb,jdbc2,
                     mongodb2,dynamodb2,
                     cassandra2,bigtable}  # 具体后端
  versioned/transfer                  # 导出/导入工具

Catalog 与 Iceberg 集成
  catalog/model             # Catalog 侧模型
  catalog/service/impl      # Catalog 服务编排
  catalog/service/rest      # Iceberg REST 资源
  catalog/files             # 对象存储访问抽象
  catalog/secrets           # 凭据管理
  catalog/format/iceberg    # Iceberg 元数据格式处理

平台能力
  events                    # 版本事件通知
  gc                        # 孤儿文件回收
  tasks                     # 后台任务
  servers/quarkus-authn     # 身份认证
  servers/quarkus-authz     # CEL 授权
```

### 4.3 存储模型：内容寻址 + 双表切分（重点）

Nessie 后端在逻辑上只需要两张表：

```
┌──────────────────────────────────────────────┐
│ refs 表（可变，single-key CAS）              │
│   main        → ObjId(abc123...)             │
│   experiment  → ObjId(def456...)             │
│   tag/v1.0    → ObjId(ghi789...)             │
└──────────────────────────────────────────────┘

┌──────────────────────────────────────────────┐
│ objs 表（全部不可变，内容寻址）              │
│   ObjId(abc123) → CommitObj{...}             │
│   ObjId(def456) → CommitObj{...}             │
│   ObjId(xyz...) → ContentValueObj{...}       │
│   ObjId(uvw...) → IndexObj{...}              │
│   ObjId(...)    → IndexSegmentsObj{...}      │
│   ObjId(...)    → StringObj / UniqueIdObj    │
└──────────────────────────────────────────────┘
```

Robert Stupp《Nessie cache improvements》博客明确了这种划分：*"Nessie stores information in its backing data store in two tables: the refs table … and the objs table … Objects in Nessie are (usually, see below) immutable."*

**内容寻址带来的架构好处**：

| 特性 | 好处 |
|---|---|
| `objs` 全部不可变 + 内容寻址 | 可以**无限制本地缓存**——跨实例 ID 天然一致，不存在缓存失效 |
| `objs` 写入幂等 | 不需要事务，不需要 CAS，重复写入视为 no-op |
| `refs` 只需要 **单 key CAS** | 事务要求降到最低——DynamoDB / Bigtable / Cassandra / MongoDB 等无事务 KV 都能做 |

**CommitObj 关键字段**（基于官方 release notes 与 `develop/kernel/` 描述）：

- `tail`：父 commit 的 `ObjId` 数组（非 merge commit 只有一个 parent）；
- `commitSeq`：commit 序号；
- `created`：创建时间戳；
- `message / headers`：commit 消息与自定义 header（Iceberg REST 支持通过 `Nessie-Commit-Authors`、`Nessie-Commit-SignedOffBy`、`Nessie-Commit-Message` 注入）；
- `commitType`：NORMAL / INTERNAL / NAMED_REFERENCE / CHERRY_PICKED；
- `incrementalIndex`：本次 commit 相对父 commit 的 **增量 key 修改集合**，嵌入在 `CommitObj` 内；
- `referenceIndex` + `referenceIndexStripes`：当 incremental 太大时 **spill** 出去的完整 key→content 快照。

**双层索引机制**：

- **Incremental index** 嵌在 `CommitObj` 里；
- **Reference index** 存在独立 `IndexObj` / `IndexSegmentsObj` 中，允许多个 commit 共享同一段内容（拜内容寻址所赐）。
- Spill 由 `StoreConfig` 控制：`maxIncrementalIndexSize`、`maxSerializedIndexSize`、`maxReferenceStripesPerCommit`。
- 读路径：`IndexesLogicImpl.buildCompleteIndex` 沿 `tail` 链合并 incremental，直到遇到挂了 reference index 的 commit 为止。`StoreIndex` 按 byte-ordered key 组织，支持二分查找、range scan、前缀扫描。`diff(refA, refB)` 就是两棵 StoreIndex 的 merge-join。

这种"每 commit 带增量 + 周期性物化完整索引"的模式与 LSM-Tree 同源：小写入走嵌入 index，累积后物化成可共享的 reference index。本质上是 **LSM-Tree 的 compaction 思想在 Git 式版本链上的应用**。

### 4.4 提交流程：乐观并发控制 + CAS 重试（重点）

Nessie 的并发模型完全是 **乐观锁**。官方 `develop/kernel/` 原话：*"All write operations do support retries. Retries happen, if a non-transactional CAS operation failed or a transactional DML operation ran into an 'integrity constraint violation'. Both the number of retries and total time for the operation are bounded. There is an (exponentially increasing) sleep time between two tries."*

**`Persist` 接口**暴露的关键方法（`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/persist/Persist.java`）：

- `storeObj / storeObjs / fetchObj / fetchObjs / scanAllObjects / deleteObj`——`objs` 表读写，单对象写入必须幂等；
- `addReference / updateReferencePointer(expected, newPointer) / markReferenceAsDeleted / purgeReference`——`refs` 表的 CAS 语义更新。**`updateReferencePointer` 是整个系统唯一需要强原子的操作**。

**`CommitLogicImpl.doCommit` 的 CAS 循环**（伪代码）：

```
for attempt in 0..commitRetries:
    ref_before     = persist.fetchReference(branchName)
    parent_commit  = persist.fetchObj(ref_before.pointer)   # CommitObj
    parent_index   = IndexesLogic.buildCompleteIndex(parent_commit)

    # 1. 校验
    for each Put op:
        validate expectedContent matches parent_index[key]
        validate content-id 唯一性 / payload 不可变
    for each Unmodified op:
        validate key unchanged since expectedHash
    detect key 冲突（同一 key 在并发 commit 里出现）

    # 2. 构建并先写入不可变对象（幂等）
    new_index       = apply_ops(parent_index, ops)
    new_commit_obj  = build_commit(parent=ref_before.pointer, ops, index)
    persist.storeObj(new_commit_obj)      # 幂等
    persist.storeObj(new_index_objs)      # 幂等
    persist.storeObj(new_content_objs)    # 幂等

    # 3. CAS
    ok = persist.updateReferencePointer(branchName,
                                        expected=ref_before.pointer,
                                        new=new_commit_obj.id)
    if ok: return success
    else:  sleep(exp_backoff); continue   # 孤儿 obj 保留，后台 GC
```

**关键工程决策**：

1. **所有重 IO（写 CommitObj、ContentValueObj、IndexObj）都放在 CAS 之前，且全部幂等**。CAS 失败重试时之前写入的 obj 直接复用，不需要回滚。
2. **真正的原子性只依赖一次 single-key CAS**。这是 Nessie 能跑在 DynamoDB / Bigtable 这类无事务 KV 上的关键。
3. **失败 CAS 留下的孤儿对象**由后台 `cleanup-repository` 工具扫描清理。这是典型的 **"空间换一致性简单性"** 取舍。

这和 PostgreSQL 的 MVCC 很像——都是"先写新版本、再原子切换可见性、后台回收老版本"。只是 Nessie 把"切换可见性"压缩成了一次 KV 的 CAS，门槛被拉到最低。

### 4.5 数据流转：一次多表元数据变更

```
Client / Engine
    │  1. HTTP 请求（Nessie REST 或 Iceberg REST）
    ▼
REST Resource
    │  2. 参数解析、构造上下文
    ▼
TreeApiImpl / CatalogServiceImpl
    │  3. 鉴权（CEL）、hash 解析、校验
    ▼
VersionStoreImpl
    │  4. 语义操作下沉（commit / merge / transplant / getContent）
    ▼
CommitLogic / ReferenceLogic / IndexesLogic
    │  5. CAS 引用更新、不可变对象写入
    ▼
Persist → Version Store Backend (JDBC2 / MongoDB2 / DynamoDB2 / ...)

与此同时（catalog 操作）：
  CatalogServiceImpl → Object Storage
      读取 / 写入 Iceberg metadata.json

提交完成后：
  VersionStoreImpl → EventService
      发出 COMMIT / REFERENCE_* / CONTENT_* 事件

最后：Service → REST → Client
  返回 CommitResponse / MergeResponse / ContentResponse
```

---

## 5. 核心语义与能力

### 5.1 顶层抽象

Nessie 借 Git 模型描述数据湖状态，核心是 **"命名引用（Reference）+ 不可变提交（Commit）+ 内容对象（Content）"** 三元组：

| 抽象 | 形态 |
|---|---|
| **Reference** | `Branch`（可变）、`Tag`（不可变）、`Detached`（脱离命名引用，直接用 commit hash 访问）|
| **Commit** | 目录级（catalog-wide）不可变节点，稳定 hash，承载一组 Operation；与 Git 不同，**Nessie 非合并 commit 仅有一个 parent**（官方 guides/about 原话："One important difference of Nessie-merges is that Nessie-commits have only one parent"）|
| **Operation** | `Put`（创建 / 更新 Content，必须附带 expected content 做乐观校验）、`Delete`（仅携带 ContentKey）、`Unmodified`（不持久化，声明该 key 自 expectedHash 未变，用于提升到 Serializable）|
| **ContentKey** | 多段字符串（Namespace 段 + 对象名），例如 `sales.orders.raw`|
| **Namespace** | 自身也是一种 Content 类型，必须显式创建；可带 `location` 属性 |

### 5.2 Content 子类型与耦合程度

0.107.x 官方规范（`develop/spec/`）明确列出的一等公民仅三种：**IcebergTable、IcebergView（仍标为 experimental）、Namespace**。

- 代码中仍存在 `DeltaLakeTable`（deprecated，未再演进）与 UDF 类型（代码层存在、文档不作正式说明）。
- 对 Hudi、Paimon 无官方支持声明（0.107.0 在 #11973 为 Paimon 放宽了 Iceberg field ID=0 的限制，仅是**兼容性让步**而非原生支持）。

**语义三要素**：

- **content-id**：UUID，对象终生不变，跨分支、跨重命名、跨 GC 保持一致；是缓存与审计的稳定锚点。
- **payload**：内容子类型的一字节编码（由 `ContentTypeBundle` 注册），全局唯一。
- **metadata-location**：指向 Iceberg `metadata.json` 的路径。Nessie 核心职责就是**在 commit 点原子性地翻转这个指针**；Put 更新既有对象时必须给出 *expected on-reference state*（snapshot-id、schema-id、partition-spec-id、sort-order-id），否则服务端抛 `NessieConflictException`。

**耦合程度**：Nessie **与 Iceberg 强耦合**，0.107.x 官方兼容矩阵只覆盖 Iceberg 1.5.0。扩展路径是 Java ServiceLoader（`ContentTypeBundle` + `ContentSerializerBundle` + 全局唯一 payload ID 通过 GitHub Issue 登记），但代码内主干逻辑对 Iceberg 语义有隐式依赖（on-reference state 字段命名、GC 对 Iceberg snapshot 的扫描等），真正替换成本不低。

### 5.3 版本管理语义

- **branch**：可变，持续推进 HEAD。
- **tag**：不可变（一旦指向某 commit 不可更改），通过 `ASSIGN` 重新定位需专门权限。
- **commit**：hash 为 `ObjId` 的十六进制表示；支持自定义 author / signedOff / message。
- **merge**：Java API 允许多种策略（fast-forward / regular），但**客户端主要走 replay 模式**——官方 about 页原话："Nessie-merge operations technically work a bit different: the changes in branch to be merged are replayed on top of the target branch"。因此 Nessie commit 没有传统意义上的 merge commit（单 parent），merge 后对目标分支追加若干 replayed commits。这是 Nessie 与 Git 最容易被误解的差异点。
- **transplant（cherry-pick）**：从任意 reference 挑选 commit 序列移植到目标。
- **diff**：两个 reference 间 ContentKey 级差异。Nessie 的 diff **不做行级 diff**，行级 diff 是 Iceberg 或上层工具的事。
- **history**：commit log，支持 CEL 过滤、翻页。
- **WAP**：官方推荐用法就是 branch 写 + branch 审计 + merge 回 main，没有单独的 WAP 关键词。
- **Time travel**：三种坐标——commit hash / branch 或 tag 名 / 时间戳（Iceberg REST 反引号包裹：`nessie.ns.\`tbl#2024-07-01T00:00:00Z\``）。
- **并发模型**：全面乐观，`expectedHash` 冲突抛 `NessieConflictException`。

### 5.4 多表原子提交（杀手级能力）

这是 Nessie **最核心、最无可替代** 的能力，值得单独剖析。

**语义**：一次 Nessie commit 可携带任意数量、任意表 / 视图 / namespace 上的 Operation，作为不可分割的单元写入。要么全部生效，要么全部拒绝——超出传统 Iceberg 单表事务边界。

```
commit {
  branch: main
  operations: [
    Put(key="sales.orders",      newMetadataLoc="s3://.../v43.json"),
    Put(key="sales.customers",   newMetadataLoc="s3://.../v17.json"),
    Delete(key="sales.orders_legacy"),
    Put(key="marketing.events",  newMetadataLoc="s3://.../v88.json"),
  ]
  message: "Migrate legacy orders + update dependent marts"
}
```

**原子性实现**：CAS 模型（见 4.4 节）保证 `refs` 表指针一次性翻转。服务端执行顺序：
1. 校验所有 Put 的 *expected on-reference state* 与当前一致；
2. 校验所有 Unmodified 对应 key 自 expectedHash 未变；
3. 构建新 `CommitObj` 并先写 objs（幂等）；
4. 对分支 HEAD 做 CAS 推进。

**隔离级别**：客户端读锁定到具体 hash → 天然 Snapshot Isolation；加 Unmodified 声明读集 → 提升到 Serializable。官方 Transactions 指南明确暴露三种级别（Read Committed / Repeatable Read / Serialized），**全部乐观、不支持悲观锁**。

**冲突检测粒度**：ContentKey（≈表）级。两个 commit 都动同一 key 则后者 CAS 失败重试；动不同 key 则两者都能线性化成功。

**与 Iceberg 单表事务的差异**：Iceberg 原生事务以表的 metadata-location CAS 为锚，单表粒度；跨表一致性要外部协调。Nessie 把每个 Iceberg 单表事务封装成一个 Put operation，**一次 commit 装入多个 Put，原子边界从表扩到整 catalog**。代价是 Iceberg 自身的 snapshot 历史被 Nessie commit 历史接管——`expire_snapshots`、`rewrite_manifests` 等 Iceberg 维护过程不能直接跑，要走 Nessie 管理工具或 GC。

**落地限制（非常重要）**：虽然服务端能接受多表 operation 合并提交，**但主流计算引擎（Spark / Flink / Trino）仍以每次 DML 作为单表事务提交**。要真正在单 commit 里打包多表 Put，途径有二：

- (a) Nessie Java 客户端直接调 API；
- (b) 在 branch 上做多次单表 commit 后 fast-forward merge 回 main——这是工程上常用的"近似多表事务"，代价是下游看到一批 commit 同时到达而非单一 commit。

---

## 6. 协议与接口

### 6.1 Nessie REST API v2

0.107.x 以 `/api/v2` 为稳定推荐（OpenAPI `nessie-openapi-0.107.4.yaml`，specVersion 2.2.0），v1 仍保留但文档与示例一律倾向 v2。端点集覆盖 `tree`（引用与 commit log）、`content`（按 key 读写）、`diff`、`namespace`、`config`、`reflog`。

### 6.2 Iceberg REST Catalog 兼容策略

这是 Nessie 2024 年以来最大的接口演进。官方 `guides/iceberg-rest/` 给出两种路径：

- **基础前缀**：`http://<host>:19120/iceberg`
- **指定分支 / Tag**：在路径末段编码——`http://<host>:19120/iceberg/main`、`/iceberg/experiments`
- **指定 warehouse**：用管道符 `|`——`/iceberg/experiments|sales`（分支在前、warehouse 在后）

**关键偏差**：Iceberg REST 标准用 `prefix` 参数传 reference，但 **Nessie 实现不要求客户端设置 prefix**（尤其 pyiceberg 设置 prefix 无效），分支信息编码在 URI 最后一段。这导致*标准客户端只能看到默认分支*（通常是 main），要切分支必须在 URI 里指定。

Spark 配置示例：

```
spark.sql.catalog.nessie.type=rest
spark.sql.catalog.nessie.uri=http://127.0.0.1:19120/iceberg/dev/
spark.sql.catalog.nessie=org.apache.iceberg.spark.SparkCatalog
```

Nessie 的 commit log 概念在 Iceberg REST 标准里没有对应——它通过 HTTP header（`Nessie-Commit-Authors`、`Nessie-Commit-Message`）注入元数据，但无法通过标准 IRC 客户端查 commit 历史，必须降级使用原生 `/api/v2` 或 Nessie CLI。

### 6.3 客户端与认证

- **Java**：`org.projectnessie.nessie:nessie-client`（配 BOM）。
- **Python**：pynessie 已被 Java CLI（`nessie-cli`）取代；新 Python 用户推荐用 pyiceberg 直连 Iceberg REST。
- **CLI**：`nessie-cli-0.107.4`（Docker `ghcr.io/projectnessie/nessie-cli:0.107.4`），SQL-ish 方言（`CONNECT`、`CREATE BRANCH`、`SHOW LOG`、`MERGE`）。
- **Spark SQL 扩展**：`nessie-spark-extensions-3.5_2.12:0.107.4`。
- **认证**：默认关闭；支持 `Bearer`、`OAUTH2`（client_credentials、password、authorization_code、device_code、token_exchange 多流程），AWS 签名亦可。**Nessie 自己的 OAuth2 客户端能力超过当前 Iceberg REST 客户端**（后者只支持 Bearer + client-id/secret）。Token exchange / impersonation 在 0.106 大幅重构，0.107.x 仍标为 beta。

### 6.4 Credential Vending

Iceberg REST 模式下，Nessie 把对象存储凭据下推给计算引擎，两种机制：

- **Vended / down-scoped credentials**：Nessie 调 STS（AWS / GCP / MinIO）签发短时凭据随 `FileIO` 返回；只在 `loadTable` 时一次往返。
- **S3 request signing**：每个 S3 请求 Nessie 代签；无需 STS，但延迟高。⚠ 对 Iceberg Java < 1.5.0 不工作；0.105.x 曾出现对批量删除失效的 bug（#11493）。

配置分层：`nessie.catalog.service.s3.default-options.*` + `nessie.catalog.service.s3.buckets.<name>.*`；凭据缓存默认 15 分钟 TTL，支持 Vault、AWS / GCP Secrets Manager、Azure Key Vault。**GCS 与 ADLS 仍标为 experimental**，ADLS 只能在 filesystem 粒度控制权限。

---

## 7. 关键模块说明

本节按核心模块列出职责与输入输出，便于从代码结构理解系统。

### 7.1 API 层

| 模块 | 职责 | 代表组件 |
|---|---|---|
| `api/model` | 定义公开 API 的模型对象与接口；承载 v1 / v2 参数对象和响应对象 | `EntriesResponse`、`LogResponse`、`MergeResponse`、`CommitResponse` |
| `api/client` | Java 客户端封装，builder 风格调用 | Java Client API |
| `servers/rest-services` | JAX-RS / Quarkus REST 资源入口，协议边界处理 | `RestV2TreeResource`、`RestContentResource`、`RestDiffResource`、`RestNamespaceResource` |
| `catalog/service/rest` | Iceberg REST Catalog 端点 | `IcebergApiV1TableResource`、`IcebergApiV1NamespaceResource`、`IcebergApiV1ViewResource`、`IcebergApiV1S3SignResource` |

### 7.2 服务层

| 模块 | 职责 | 代表组件 |
|---|---|---|
| `servers/services` | 业务语义编排、CEL 鉴权、分页、哈希解析、异常转换、请求上下文传播 | `TreeApiImpl`、`ContentApiImpl`、`DiffApiImpl`、`NamespaceApiImpl`、`ConfigApiImpl` |

**CEL 授权**：每个 API action 解析出 `op / role / ref / path / contentType / api / actions` 变量，按 `nessie.server.authorization.rules.*` 规则求值。支持 20+ 种 `op`（VIEW_REFERENCE、READ_CONTENT_KEY、COMMIT_CHANGE_AGAINST_REFERENCE、UPDATE_ENTITY、CATALOG_CREATE_ENTITY 等）。

### 7.3 版本语义层

| 模块 | 职责 | 代表组件 |
|---|---|---|
| `versioned/spi` | 版本语义接口与公共模型，提供 `VersionStore` 语义边界 | `VersionStore` 接口、提交 / 合并 / 引用等结果对象 |
| `versioned/storage/versionstore` | 实现 branch / tag / commit / merge / transplant / diff / history 等核心能力 | `VersionStoreImpl` |

### 7.4 存储逻辑层

| 模块 | 职责 | 代表组件 |
|---|---|---|
| `versioned/storage/common/logic` | commit 逻辑、reference 逻辑、index 逻辑、repository 元信息、consistency 校验 | `CommitLogicImpl`、`ReferenceLogicImpl`、`IndexesLogicImpl`、`RepositoryLogicImpl`、`MergeTransplantLogic` |

### 7.5 持久化抽象与后端

| 模块 | 职责 |
|---|---|
| `versioned/storage/common/persist` | 统一低层对象 / 引用存储抽象（`Persist` 接口），Obj 类型体系、ObjId |
| `versioned/storage/{inmemory, rocksdb, jdbc2, mongodb2, dynamodb2, cassandra2, bigtable}` | 不同数据库后端实现，提供对象与引用的原子操作 |

### 7.6 Catalog 与 Iceberg 集成层

| 模块 | 职责 |
|---|---|
| `catalog/service/impl` | Catalog 服务编排：计算 entity storage location、提取 snapshot、对接 warehouse |
| `catalog/format/iceberg` | Iceberg 元数据格式处理：metadata JSON、manifest、snapshot、metrics |
| `catalog/files` | 对象存储访问抽象：storage URI、文件 IO、签名、异常映射 |
| `catalog/secrets` | 凭据管理：Vault、AWS Secrets Manager、Azure Key Vault、GCS、SmallRye |
| `catalog/model` | Catalog 侧模型 |

### 7.7 平台能力层

| 模块 | 职责 | 输入 / 输出 |
|---|---|---|
| `events` | 监听版本存储结果对象并投递事件 | 输入：`CommitResult`、`MergeResult`、`TransplantResult`、`Reference*Result`。输出：COMMIT / CONTENT_STORED / CONTENT_REMOVED / REFERENCE_CREATED / REFERENCE_UPDATED / REFERENCE_DELETED |
| `gc` | 清理 Nessie 仓库中的孤儿数据文件，周期性识别 live content 与 live files，清扫对象存储 | 输入：Nessie server API、JDBC、对象文件列表。输出：live set、deferred deletes、清理结果。**仅支持 Iceberg** |
| `tasks` | 后台任务承载：导入、异步处理、实体快照等 | 异步请求 → 任务状态 |
| `servers/quarkus-server` | Quarkus 装配层与运行时入口：认证、授权、catalog、version store、后端数据源 | 配置文件 / 环境变量 → 可运行服务 |

---

## 8. 非功能特性

### 8.1 性能

官方 `guides/nessie_vs_git/` 给出设计目标：**10 万张 Iceberg 表 × 每 5 分钟一次 commit ≈ 333 commits/s**；README 声称"supporting millions of tables referencing exabytes of data with 1000s of operations per second"。早期 JGit + DynamoDB 原型仅 20 commits/s，因此被重写。Dremio 博客提到实现用了 151-way striped lock + 乐观锁。

**瓶颈**：后端 DB。官方 configuration 页原话："Relational databases are generally slower and tend to become a bottleneck when concurrent Nessie commits against the same branch happen." JDBC fetch-size 直到 0.107.0 才从"全量"改为默认 100（否则大 history 扫描易 OOM）。大 commit（数百 operations）与深 history 会触发更多 incremental → reference index spill，测试路径在 `perftest/gatling`、`perftest/simulations`、`versioned/persist/bench`。

**最舒服 workload = 多浅分支 + 低并发写**；**最痛 workload = 深 history + 单 branch 高频写**。

### 8.2 可用性与扩展性

Nessie server **无状态**，Helm chart 部署多副本 Deployment；HA 靠后端 DB（BigTable / DynamoDB / MongoDB / Cassandra / JDBC）。水平扩展的隐患是 refs cache experimental——多实例部署需配 `cache-invalidations.service-names`，否则读到 stale HEAD。

### 8.3 可观测性

- Micrometer 指标走 Quarkus `/q/metrics`（9000 端口），Prometheus 格式；支持 `nessie.metrics.tags.*` 自定义维度。
- OpenTelemetry tracing 走 Quarkus OTel extension。
- 官方提供 `grafana/nessie.json` 起步模板（期望 `service`、`instance` 两个标签）。
- 关键缓存指标：`cache=nessie-objects` tag 下的 hits / misses 与 evictions.cause。

### 8.4 安全

- TLS 走 Quarkus 标配（生产常用 Ingress 终结）；
- 支持 OIDC / Bearer / AWS v4 签名；
- 静态加密由后端 DB 提供；
- **仅做元数据层授权，不做数据层强制**——绕过 Nessie 直读 S3 无法阻止。

### 8.5 多租户

**单 Nessie server ≠ 多 catalog**：一个 server 对应一个 Nessie repository，不是 Polaris 那种 multi-tenant 架构。多租户只能通过：

- (a) 跑多套 Nessie 实例；
- (b) 把 branch 当"环境"用；
- (c) 多 warehouse（仍共享版本树）。

这是 Nessie 相对 Polaris / Unity Catalog / Lakekeeper 的明确劣势。

---

## 9. 生态与运维

### 9.1 计算引擎集成

| 引擎 | 集成方式 |
|---|---|
| **Spark 3.3 / 3.4 / 3.5** | `nessie-spark-extensions`，SQL 扩展支持 `CREATE BRANCH`、`USE REFERENCE`、`MERGE BRANCH`、`SHOW LOG`；可走老 `NessieCatalog` 或新 Iceberg REST（`type=rest`）|
| **Flink 1.16 / 1.17 / 1.18** | 通过 Iceberg 的 NessieCatalog；版本矩阵与 Iceberg 1.5.0 绑定 |
| **Trino** | 官方推荐走 **Iceberg REST**（`connector.name=iceberg`、`iceberg.catalog.type=rest`、`iceberg.rest-catalog.uri=http://nessie:19120/iceberg/`）|
| **Presto** | 0.277–0.281，走 Iceberg connector |
| **Dremio** | 原生一等公民，Dremio Cloud 内建 Nessie |
| **Hive** | 官方矩阵标 `n/a`，弱化已久 |
| **Impala** | 无官方集成 |

**AI / ML 框架**：无原生 connector，弱项。可通过 Java SDK 把训练输入锁到某 commit hash，但没有 MLflow / Kubeflow / Feast 等集成。

### 9.2 迁移路径

官方 `iceberg-catalog-migrator` 子项目支持从 **HIVE / GLUE / HADOOP / REST / JDBC / DREMIO** 搬迁到 Nessie（仅搬 metadata pointer，不复制数据）。反向迁移困难，HMS / Glue 无法表达 branch / commit。

### 9.3 后端选型

| 类型 | 场景 |
|---|---|
| `IN_MEMORY` | 仅测试 |
| `ROCKSDB` | 单节点嵌入式，边缘 / Dev；无 HA |
| `JDBC2` | PG / MySQL / MariaDB / H2；**并发同 branch 易瓶颈**，0.107 fetch-size 默认 100 |
| `MONGODB2` | 社区常用成熟路径 |
| `DYNAMODB2` | AWS 首选 |
| `CASSANDRA2` | Cassandra / ScyllaDB |
| `BIGTABLE` | GCP 首选，大规模 repo 性能最好 |

### 9.4 部署与镜像

- **Helm**：`charts.projectnessie.org`，`helm install my-nessie nessie/nessie --version 0.107.4`。
- **Docker**：`ghcr.io/projectnessie/nessie:0.107.4`、`quay.io/projectnessie/nessie:0.107.4`——官方已停用 docker.io。CLI / GC / Admin 各有独立镜像。多平台支持 amd64 / arm64 / ppc64le / s390x。
- **Java**：最低 17，推荐 21。0.107.x 起 Java 11 彻底下线。
- **端口**：19120（API）、9000（management / metrics / cache-invalidation）。
- **云厂商**：Helm 支持 EKS / GKE / AKS；后端与云耦合（AWS → DynamoDB2、GCP → BigTable、Azure → Postgres / MongoDB），warehouse 支持 S3（含 OCI / MinIO / R2，0.106.x 加了 per-bucket `chunked-encoding-enabled`）、ADLS Gen2、GCS。

### 9.5 升级：DatabaseAdapter → Persist 的硬迁移

**不能原地升级**。官方 `guides/migration/` 流程：

1. 停机；
2. 源 < 0.75：用当年的 `nessie-quarkus-cli` 导出 zip；源 ≥ 0.75：用新 `nessie-server-admin-tool`；
3. `java -jar nessie-server-admin-tool-0.107.4-runner.jar export --path /tmp/export.zip`；
4. 建目标 DB、配好连接，`... import --path /tmp/export.zip [--commit-batch-size N]`；import 末尾自动做 commit-log optimization（回填 key-lists 与 commit-parent 列表，大 repo 性能关键）；
5. 启动新 server。

**副作用**：旧 global-state commit 会被转写为 on-reference-state，可能产生多份 on-reference-state 记录；commit seq 与 created 保留，内部索引由目标端重建。

### 9.6 运维工具

- **`nessie-cli`**（0.83.2 起替代 pynessie）：SQL-ish REPL，支持 `CONNECT TO`、`CREATE BRANCH`、`REVERT CONTENT`（0.99 加入）等。
- **`nessie-server-admin-tool`**（原 `nessie-quarkus-cli`）：子命令 `info`、`export`、`import`、`cleanup-repository`（bloom filter 识别孤儿对象，1e6 对象容量默认，需 `--obj-count` 调）、`cut-history`（0.99 引入，合规场景断开父链）、`erase-repository`、`check-content`、`delete-catalog-tasks`。
- **`nessie-gc`**：Iceberg 数据文件 mark-and-sweep；策略含 `num-commits`、时间 cutoff；支持 deferred-delete；`--expiry-parallelism` 默认 4 并发；**仅支持 Iceberg**。

### 9.7 文档

projectnessie.org 按 release 固化 URL（`/nessie-0-107-4/...`），guides 覆盖 Docker / K8s / Minikube / Keycloak / Grafana / 反向代理 / TLS / 管理。**空白**：中文 / 本地化为零；性能基准与容量规划缺少官方数字；best practices 单薄；K8s Operator 未 GA；Iceberg REST 与原生 API 两套路径混用易混淆初学者。

---

## 10. 社区、License 与战略风险

### 10.1 基金会归属

**截至 2026-04-17，Project Nessie 未进入 Apache 孵化、不是 TLP**，仍独立托管在 projectnessie GitHub org。Apache 2.0 license，但非 ASF 项目。

佐证是 Nessie 核心人员（JB Onofré、Robert Stupp、Alexandre Dutra、Dmitri Bourlatchkov、Eduard Tudenhoefner）的主战场已转到 Polaris：

- **Apache Polaris 2024-08-09 进孵化**；
- **2026-02-18 晋升 TLP**（Snowflake 2026-02-19 博客、Polaris 官方 graduation 博客、Dremio 新闻稿）；
- **首任 PMC Chair 为 Dremio 的 JB Onofré（ASF Board 成员）**。

### 10.2 主导地位与贡献活跃度

Dremio 占据近乎全部核心 maintainer。发布节奏（来自 projectnessie.org/releases）：

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
| 0.107.1 | 2026-02-04 |
| 0.107.2 | 2026-02-09 |
| 0.107.3 | 2026-02-25 |
| 0.107.4 | 2026-03-09 |

从 0.100 到 0.107 跨度 **15 个月**，**minor 版本间隔从早期 1 月延至 2025 年 3–4 月**，节奏明显放缓。对比 Polaris 孵化 18 个月关 2,800+ PR、~100 contributors，Nessie 社区活跃度已相形见绌。**无公开 1.0 Roadmap**；无独立 `rfcs/` 目录；技术决策分散在 GitHub Issues 与 `site/in-dev`。

### 10.3 License 与可 Fork 性

- **License**：Apache 2.0，所有模块一致。
- **商业化主体**：仅 Dremio（Arctic / Enterprise Catalog / Lakehouse Catalog）。未见第二家规模化托管 SaaS；Bauplan、lakeFS、Conduktor、e6data、Atlan 只是在自家产品里列为支持的 Iceberg 目录。
- **Fork-ability**：模块清晰（nessie-client / model / server / catalog-service / quarkus / gc / cli / spark-extensions），**无 Dremio 商业产品硬依赖**，技术上可 fork；但治理上几乎全部 maintainer 来自 Dremio，路线控制权不在社区。
- **Trademark**：无独立 trademark 政策页；"Project Nessie" 未见 USPTO 正式注册；Dremio 商业产品名 "Arctic" 是 Dremio 商标。

### 10.4 2025–2026 重点演进

1. **Iceberg REST 原生支持** 持续打磨（2024 起实验，2025 全年修 bug）。
2. **后端多样化**：MariaDB / MySQL（Vayuj Rajan 贡献）；S3 chunked-encoding per-bucket（OCI 等）。
3. **OAuth2 / 安全强化**：token exchange / impersonation（0.106 重构、0.107 beta）。
4. **`cut-history` admin 命令**（0.99，#10048），深历史合规场景可断链。
5. **Paimon 兼容**：0.107.0 放宽 Iceberg 字段 ID=0（#11973）。
6. **Java 17 基线**：0.107.0 起强制，跟随 Iceberg 决策。
7. **没有新存储模型 v2，没有 1.0 规划**；重构基本收敛。

### 10.5 与 Apache Polaris 的关系（最关键风险）

**官方路线是"能力并入 Polaris"，而非并排独立**：

- Snowflake 2024-07 博客："Dremio excited to help bring the various functions and capabilities of Nessie into the Polaris project."
- Dremio VP Product James Rowland-Jones 2024-10："Our goal is to merge the capabilities of Project Nessie into Apache Polaris to create a single, unified catalog."
- Dremio CMO Read Maloney 2024-10 对 SiliconANGLE："we will merge Nessie into Polaris … at which time **Project Nessie will be retired**."
- Nessie 官方博客（Robert Stupp 2024-08-02）措辞更温和："intent is to contribute Nessie's capabilities, like Catalog Level Versioning, Git-like semantics, multi-table transaction semantics to Polaris."

**实际进度（截至 2026-04）**：

- **Git-like branch / merge / commit 尚未进入 Polaris**。Polaris 1.3.0-incubating（2026-01-09）与 1.4.0 规划重点是 Ranger 授权、credential vending（Azure / GCS）、catalog federation、metrics reporting——都不是 Git 式版本控制。
- Polaris 仍基于关系型元数据存储（JPA），没有合并 Nessie 的 commit kernel。
- 人员层面高度重合，但代码层没有大型 PR 把 Nessie 的 commit kernel / 151-way striped lock 搬过来。
- JB Onofré 2024-09 BigDataWire 采访强调 Polaris 要避免偏离 Iceberg REST spec，暗示 **branch / merge 语义可能先进 Iceberg Spec 再进 Polaris**。

**结论**：方向 = 最终合并；节奏 = 远比 2024 预期慢；**Nessie 在 2026 年仍作为独立项目维护**（0.107.x 规律修补即证据），但 Dremio 资源重心已倾 Polaris。

---

## 11. 已知缺陷与局限

### 11.1 性能瓶颈

- 同 branch 高并发 commit 本质是乐观锁串行；JDBC 后端尤其易阻塞（官方配置文档原话）；`cleanup-repository` 在大 index 上曾有性能 bug（0.104.x 专门修复）。
- bloom filter 默认 1e6 对象规划，超大 repo 需 `--obj-count` 手工调。
- 0.107.3 修了 `commit-log` 提前终止 bug（#12135）。

### 11.2 Experimental / 半成品地带

- **Reference caching** 至今 disabled by default：博客原话 *"would be fine to enable it, but only if only a single Nessie instance accesses the repository"*；多实例下若没配 `cache-invalidations.service-names` 会读到 stale HEAD。
- **Iceberg View** 仍 experimental。
- **GCS / ADLS credential vending** 仍 experimental。
- **K8s Operator** 未 GA。
- **Token exchange / impersonation** 0.106 重构后 beta。

### 11.3 与 Iceberg REST 标准客户端的兼容边界

- 标准 IRC 客户端不能通过 `prefix` 参数切换 Nessie 分支，只能把分支名编码在 URI 末段（pyiceberg 设置 prefix 无效）。
- Nessie 的 commit log 概念在 IRC 标准里无对应，commit 元数据只能通过 HTTP header 写入。
- **#10215**：Iceberg REST + Bearer auth 冻结（0.101.3 + Spark 3.5.4）。
- **0.105.5–0.106.0** 反向代理场景 bug（0.107.0 才修）。
- **S3 request signing** 对 Iceberg Java < 1.5.0 不工作；0.105.x 曾对批量删除失效（#11493）。

### 11.4 GitHub Issues 反复出现的问题

- **#10235**（GC expire 在 drop 表上 NoSuchKey，0.101.3）、**#9097**（dropped table files 未被清理）、**#10748**（CLI 0.103.3 + AWS creds 连不上）、**#10809**（ADLS over HTTPS 文档与实现不一致）、**#11145**（OpenJDK 24 toolchain 找不到）、**#11971 / #11849 / #11828 / #11767 / #11759 / #11665 / #11579**（2025-11 ~ 2026-01 新开 batch）、**#5255**（renovate 长期 open）。
- **Backlog 三大主题**：GC 清理不干净、REST + auth 组合边角、大 repo 性能。
- 维护者（`snazy` / `adutra`）响应快但 backlog 持续累积。

### 11.5 第三方评测反馈

- **lakeFS**（竞品）：强调 Nessie 只支持 Iceberg，不做数据文件版本。
- **RisingWave / Conduktor / e6data 2025**：归类为 "advanced but niche"；REST Catalog（Polaris / Lakekeeper）推荐为新项目默认；Nessie 价值锁定在"需 branching + 多表原子 commit"的团队。
- **Nexla**：认知成本高，需要团队重新设计分支策略。
- **Reddit / dev.to**：branching 体验好，但 merge 冲突处理的 UX 原始（CLI 报错不直观）。
- **生产级成功案例公开披露偏少**；Medium 上 Iceberg + Nessie 博客多是 dev 环境演示。

### 11.6 学习曲线与认知成本

- 用户需同时理解 Iceberg snapshot 与 Nessie commit 两层历史。
- Nessie merge 语义是 replay（非 Git 那种合并 commit），容易被熟悉 Git 的用户误解。
- Iceberg REST 与原生 API 两套路径共存、warehouse × reference 的 URL 组合容易绕晕。
- **ML 生态、Delta / Hudi 支持、跨组织数据共享都弱**，不适合想要"一揽子 Lakehouse governance"的团队。

---

## 12. 设计优点与局限小结

### 12.1 设计优点

- **版本语义与底层存储彻底解耦**（Persist SPI），支持 7 种后端零改动；
- **元数据版本控制成本远低于数据文件版本控制**（不复制数据、只翻转指针）；
- **适合多表一致性发布**，这是整个生态里唯一原生支持 catalog-wide 原子提交的 Catalog；
- **支持 Git-like 工作流**（WAP、分支实验、审计回滚）；
- **内容寻址 + 双表切分**架构优雅，对 KV 后端的事务要求降至最低；
- **已具备较完整的生产化能力**（authn / authz / events / gc / secrets）。

### 12.2 设计局限

- 本质上仍是元数据控制面，不是数据面引擎；
- 高冲突写入仍依赖乐观并发和重试，单 branch 高频写是最痛点；
- 多节点环境下 reference caching 仍实验性；
- GC 和 catalog 扩展能力会引入额外部署复杂度；
- **无多租户、无行列级权限、无数据层强制**；
- **AI / ML 资产零支持**；
- **战略上面临并入 Polaris 的风险**（详见 10.5）。

---

## 13. 设计启示（对自研 Catalog 的决策参考）

### 启示一：Git-for-Data 是高价值但高成本的差异点

Nessie 的 Git-like 能力确实独一无二（Polaris / Unity / Gravitino 至今都没做），**但到 2026 年仍没有被业界主流选为默认模式**。原因三点：

1. **场景窄**：真正需要跨表原子事务 + 分支合并的团队是做 DataOps / CI-for-Data 的数据平台团队，占比有限。
2. **引擎层生态弱**：Spark / Flink / Trino 默认提交粒度仍是单表，要真正用多表原子 commit 必须用 Nessie Java SDK 或"branch + fast-forward merge"变通手法。
3. **语义复杂**：Nessie merge 是 replay 不是传统 Git merge commit（单 parent），冲突处理粒度是 ContentKey 级（而非 row / column 级），用户认知成本高。

**决策建议**：如果自研 Catalog 的目标用户不是专门做 DataOps / ML 实验管理 / 合规审计的团队，**不建议把 Git-for-Data 做成 Tier-1 能力**；作为可选插件或只保留 branch / tag 这类浅层能力即可。Iceberg 1.x 已原生支持表级 branch / tag，很多"分支"诉求可以在 Iceberg 层解决，Catalog 层做 *catalog-wide 原子事务* 的 ROI 最高。

### 启示二：内容寻址 + 双表存储模型值得复刻

`refs` + `objs` 两张表的切分是 Nessie 真正的架构亮点：

- **immutable `objs` 可无限制本地缓存**（objectID = 内容 hash，跨实例天然一致），缓存命中率高、水平扩展简单。
- **可变 `refs` 的原子更新只需 single-key CAS**，把事务要求降到 DynamoDB / Bigtable / Cassandra 都能满足的最低门槛。
- **KV 与 RDB 共用同一逻辑层**（Persist SPI），后端切换成本低。

**决策建议**：自研 Catalog 若走"Iceberg REST + 扩展能力"方向，建议**元数据层也按 immutable-objects + mutable-pointers 切分**。具体落地：

- Object ID = BLAKE3 / SHA-256 / xxhash（Nessie 用 32 字节定长 ID）；
- 表里只有 `id → blob`，所有 CRUD 幂等（重复写入不冲突）；
- 所有可变指针（当前 snapshot、分支 HEAD 等）集中到一张 refs 表，用 CAS 单点更新。

### 启示三：乐观并发的适用边界要画清

Nessie 全乐观锁、CAS + 重试循环，在**多分支浅 commit** 场景优雅；在**单 branch 高并发深 history** 场景就是典型的 ABA 陷阱（commit 序列化 + 重试风暴）。用户遇到 Spark 高并发 compaction 时会看到大量 `Reference hash is out of date` 警告。

**决策建议**：自研 Catalog 的 hot path（单表高并发写）应当提供**可选的悲观锁或分区并发**；单纯靠乐观 CAS 无法满足金融 / 广告这种"同一张表多 writer 同时提交"的稳态负载。

### 启示四：Persist SPI 的分层抽象值得学习

Nessie 把"对象读写 + 指针 CAS"的 SPI（`Persist`）与业务逻辑（`CommitLogic` / `IndexesLogic` / `ReferenceLogic`）彻底分离，使得增加新后端（RocksDB、BigTable、JDBC2）只需实现 ~10 个方法，业务逻辑零改动。这是**低成本多后端支持的最佳实践**：

- 上层 Logic 完全用 `ObjId` 和不可变 Obj 对话，不知道底层是 KV 还是 RDB；
- 后端 adapter 只要实现幂等写 + 单 key CAS + range scan 三项；
- 测试用 InMemory 后端可跑全量语义测试，CI 成本低。

### 启示五：战略风险不可忽视

**Dremio 已公开表态最终把 Nessie 能力并入 Polaris**，2026-04 合并进度虽慢于预期，但方向明确。选型 Nessie 意味着：

- 12–24 个月仍安全（0.107.x 持续发布）；
- 之后大概率要迁到 Polaris 或承担独立维护成本；
- **除非自研 Catalog 打算永久 fork 并承接 Nessie 核心，否则不建议把生产关键路径押在 Nessie 长期演进上**。

**替代路径评估**：

- 若只要 Iceberg REST + 多租户 + 治理 → Polaris TLP；
- 若要 Git-for-Data + 短期确定性 → Nessie 0.107.x 仍是唯一选择，但准备好迁移预案；
- 若做自研 → **借鉴 Nessie 的 Persist / 双层索引 / CAS 模型 + Polaris 的多租户 / 授权 / Spec 对齐**，是最优组合。

### 启示六："不做什么"的定力值得学习

Nessie 坚持只托管 Iceberg 一等公民、不做血缘、不做业务目录、不做数据层授权、不做 compaction——这种**窄而深**的产品哲学让核心代码量保持可控（1,438 个 Java 文件），核心 team 十余人能长期维护。自研 Catalog 面对需求压力时，尤其要守住"只做元数据事务 + 可选权限"的边界，把 lineage、policy、discovery、ML 特征等能力交给上层平台。

---

## 14. 参考资料

### 14.1 官方资料

- Project Nessie 官网：https://projectnessie.org/
- GitHub 仓库：https://github.com/projectnessie/nessie
- 发布列表（完整日期）：https://projectnessie.org/releases/
- Nessie 0.107.4 着陆页：https://projectnessie.org/nessie-latest/
- Nessie Specification：https://projectnessie.org/develop/spec/
- Commit Kernel 架构（含 DatabaseAdapter 过时提示）：https://projectnessie.org/develop/kernel/
- Nessie vs Git（性能设计目标）：https://projectnessie.org/guides/nessie_vs_git/
- 关于 Nessie：https://projectnessie.org/guides/about/
- Transactions Guide：https://projectnessie.org/guides/transactions/
- Iceberg REST 配置：https://projectnessie.org/guides/iceberg-rest/
- 服务器配置：https://projectnessie.org/nessie-latest/configuration/
- 迁移指南：https://projectnessie.org/guides/migration/
- Admin Tool 导入导出：https://projectnessie.org/nessie-0-107-4/export_import/
- 管理服务（GC）：https://projectnessie.org/guides/management/
- Content Types：https://projectnessie.org/develop/content-types/
- Repository Configs：https://projectnessie.org/develop/repository-configs/
- Spark SQL 扩展：https://projectnessie.org/nessie-latest/spark-sql/
- Trino 集成：https://projectnessie.org/nessie-latest/trino/
- Cache improvements 博客（2024-06，Robert Stupp）：https://projectnessie.org/blog/2024/06/05/nessie-cache-improvements/
- Polaris announcement 博客（2024-08）：https://projectnessie.org/blog/2024/08/02/open-source-polaris-announcement/

### 14.2 关键源码路径（GitHub projectnessie/nessie，main 分支）

- Persist SPI：`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/persist/`（`Persist.java`、`ObjId.java`、`Obj.java`）
- Logic 层：`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/logic/`（`CommitLogicImpl.java`、`IndexesLogicImpl.java`、`ReferenceLogicImpl.java`、`RepositoryLogicImpl.java`、`MergeTransplantLogic*`）
- Obj 类型：`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/objtypes/`（`CommitObj`、`ContentValueObj`、`IndexObj`、`IndexSegmentsObj`、`RefObj`、`StringObj`、`UniqueIdObj`）
- 索引实现：`versioned/storage/common/src/main/java/org/projectnessie/versioned/storage/common/indexes/`（`StoreIndex`、`StoreIndexElement`）
- VersionStore：`versioned/storage/versionstore/src/main/java/org/projectnessie/versioned/storage/versionstore/VersionStoreImpl.java`
- 后端 adapters：`versioned/storage/{inmemory, rocksdb, jdbc2, mongodb2, dynamodb2, cassandra2, bigtable}/`
- Service 层：`servers/services/src/main/java/org/projectnessie/services/impl/`（`TreeApiImpl`、`ContentApiImpl`、`DiffApiImpl`、`NamespaceApiImpl`）
- REST 资源：`servers/rest-services/src/main/java/org/projectnessie/services/rest/`
- Catalog（Iceberg REST）：`catalog/service/impl/`、`catalog/service/rest/`、`catalog/files/`、`catalog/secrets/`、`catalog/format/iceberg/`
- Quarkus server：`servers/quarkus-server/`
- Spark 扩展：`nessie-integrations/nessie-spark-extensions-*/`
- Admin / GC / CLI：`tools/server-admin/`、`gc/`、`cli/`
- 官方 site 源：`site/in-dev/`（含 develop、guides 文档）

### 14.3 社区讨论与 Issue

- GitHub Issues：https://github.com/projectnessie/nessie/issues
- Zulip：https://projectnessie.zulipchat.com
- Google Group：https://groups.google.com/g/projectnessie
- 典型 issue：#10235（GC NoSuchKey）、#9097（GC 未清理）、#10748（CLI + AWS）、#10215（Bearer auth 冻结）、#10809（ADLS HTTPS）、#11145（JDK 24）、#11493（S3 signing 批量删除）、#11973（Paimon field ID 0）、#12135（commit-log 截止）、#5255（renovate 长期 open）

### 14.4 Dremio / Snowflake / Polaris 相关

- Dremio Introducing Project Nessie（2020-05）：https://www.dremio.com/blog/introducing-project-nessie/
- Dremio Project Nessie 深度介绍：https://www.dremio.com/blog/project-nessie-transactional-catalog-for-data-lakes-with-git-like-semantics/
- Dremio What is Nessie Catalog Versioning：https://www.dremio.com/blog/what-is-nessie-catalog-versioning-and-git-for-data/
- Dremio Nessie Ecosystem：https://www.dremio.com/blog/the-nessie-ecosystem-and-the-reach-of-git-for-data-for-apache-iceberg/
- Dremio Polaris TLP 博客：https://www.dremio.com/blog/apache-polaris-graduates-to-a-top-level-apache-project/
- Dremio Arctic 文档：https://docs.dremio.com/cloud/arctic/
- Dremio Nessie 数据源文档：https://docs.dremio.com/current/data-sources/lakehouse-catalogs/nessie/
- Snowflake Polaris 开源博客（2024-07）：https://www.snowflake.com/en/blog/polaris-catalog-open-source/
- Snowflake Polaris TLP 博客（2026-02）：https://www.snowflake.com/en/blog/apache-polaris-top-level-project/
- Apache Polaris 官网：https://polaris.apache.org/
- Iceberg Catalog Migrator：https://www.dremio.com/blog/introducing-the-apache-iceberg-catalog-migration-tool/

### 14.5 第三方分析

- BigDATAwire "Polaris Catalog to be Merged with Nessie"（2024-07）：https://www.bigdatawire.com/2024/07/30/polaris-catalog-to-be-merged-with-nessie-now-available-on-github/
- HPCwire Dremio Hybrid Catalog（2024-10）：https://www.hpcwire.com/bigdatawire/2024/10/29/dremio-goes-hybrid-with-nessie-based-metadata-catalog/
- SiliconANGLE Dremio Polaris Support（2024-10）：https://siliconangle.com/2024/10/29/dremio-throws-support-polaris-data-catalog-expands-deployment-options-iceberg-lakehouse/
- e6data Iceberg Catalogs 2025：https://www.e6data.com/blog/iceberg-catalogs-2025-emerging-catalogs-modern-metadata-management
- RisingWave Catalog Comparison：https://risingwave.com/blog/iceberg-catalog-comparison-guide/
- Conduktor Iceberg Catalog Management：https://www.conduktor.io/glossary/iceberg-catalog-management-hive-glue-and-nessie
- lakeFS Nessie Catalog 对比：https://lakefs.io/blog/nessie-catalog/
- OpenSourceForU Dremio Polaris（2026-04）：https://www.opensourceforu.com/2026/04/dremio-strengthens-open-data-standards-with-iceberg-v3-and-polaris/

---

## 信息置信度与未解疑点

**高置信**：0.107.x 发布日期；Polaris TLP 里程碑；Nessie 未进入 ASF；License = Apache 2.0；后端类型与迁移流程；CEL 授权模型；Iceberg REST 端点路径；多表 commit 原子性机制；双层索引与 CAS 重试循环；版本时间线。

**中置信（基于博客 + release notes 推断）**：`CommitObj` / `Persist` 的具体字段与方法签名——未能逐行验证 GitHub 源码；151-way striped lock 的具体实现细节；spill 阈值的默认值。

**未解疑点**：Iceberg v3 支持状态；UDF Content 类型的稳定状态；Hudi 官方兼容计划；`HEAD~1` 相对 hash 语法；Nessie commit kernel 被合并到 Polaris 的具体技术路线图与时间表。建议在正式选型决策前，通过 Zulip 或 GitHub Discussion 向 maintainer 直接确认。

---

*文档版本：v2.0（整合版）/ 2026-04-17*
