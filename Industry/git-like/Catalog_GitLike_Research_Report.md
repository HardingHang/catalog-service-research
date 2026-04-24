# Catalog 层 Git-like 能力专项调研报告

> 面向自研 Lakehouse Catalog 选型预研的专题材料。本报告聚焦 **"在 Catalog 层实现 Git-like 能力"** 这一主题：以 Project Nessie 为基准（v4 已有调研之延伸），横向对比 Apache Polaris、Apache Gravitino、Unity Catalog、Lakekeeper；纵向对比 Catalog 层之外的版本化路径（Iceberg 原生 branch、lakeFS、DuckLake）；分析如果要在主流 Iceberg REST Catalog 上叠加 Git-like 能力，需要付出的技术代价与工程权衡。传统 OLTP 数据库（Neon/Dolt 等）的路径仅作为背景对照，不展开。
>
> 调研范围：2024–2026-04 公开资料、官方文档、项目 ASF TLP 里程碑、源码引用（基于 project 内 Nessie v4 报告中的代码路径）。本报告假定读者已熟悉 Nessie 的基础概念（branch / commit / merge / transplant / metadata pointer），未涵盖部分回指 v4 报告。

---

## 1. 主题界定与核心命题

### 1.1 本报告要回答的四个问题

**Q1（Nessie 独特性的根因）**：Nessie 体量仅约 1400 个 Java 文件、十余人长期维护、只支持 Iceberg，却独享"catalog-wide Git-like"这一能力，其他主流 Catalog 为什么没有做、或做不到等价能力？

**Q2（主流 Catalog 的替代方案）**：Polaris / Gravitino / Unity Catalog / Lakekeeper 在没有 Nessie 那套版本引擎的情况下，怎么回应用户对"分支 / WAP / 回滚 / 一致性发布"的需求？它们的方案能覆盖 Nessie 的哪些场景，不能覆盖哪些？

**Q3（改造成本）**：如果要在一个主流 Iceberg REST Catalog（以 Polaris 为代表）上追加"Nessie 级别的 Git-like"能力，最小工程代价是什么？有没有可行的渐进式路线？

**Q4（跨层替代路径）**：如果放弃"Catalog 层实现 Git-like"这条路，Lakehouse 体系的其他层（L1 lakeFS、L2 Iceberg 原生 branch、L2+L3 DuckLake）各自能覆盖到什么边界？什么场景下应该改走那些层？

### 1.2 本报告的核心命题

> **"Catalog 层 Git-like"不是一个可以靠产品经理功能勾选加进去的能力，它是一个需要在"存储模型 / 一致性语义 / 多租户模型 / 协议扩展 / 数据文件 GC"五个维度同时做出特定取舍的架构选择。Nessie 选了一条极端内聚的路线——"单格式、单 repo、single-key CAS、元数据-only、扩展 IRC"——换来了 catalog-wide 原子性和 Git-like 语义。主流 Catalog 选了完全相反的路线，因此无法"增量"加上等价能力，必须以破坏现有设计为代价。**

下文章节顺序依此命题展开：第 2 章对五个维度逐一拆解 Nessie 的选择；第 3 章分析 Polaris / Gravitino / Unity Catalog / Lakekeeper 在这些维度上的相反选择；第 4 章估算在主流 Catalog 上反向移植 Nessie 能力的工程代价；第 5 章横向比较跨层方案；第 6 章给出自研 Catalog 的决策建议。

---

## 2. Nessie 为什么能做到 catalog-wide Git-like：五维拆解

v4 报告已详细描述 Nessie 的整体架构和存储模型。本章不重复这些内容，只抽取"**为什么这些选择让 Git-like 在 Catalog 层成立**"的因果链，为后续与其他 Catalog 的对比打基础。

### 2.1 维度一：存储模型——内容寻址 + 双表切分

Nessie 的存储被拆成两张表：`refs`（branch / tag 指针，可变，single-key CAS）和 `objs`（所有 CommitObj / ContentValueObj / IndexObj，内容寻址、全部不可变）。写路径先把所有不可变对象用幂等写入 `objs`（失败可重试，不需要事务），最后做一次 `refs` 表的 single-key CAS 翻转分支 HEAD。

这个结构的关键性质：**整个系统只有一个地方需要强原子性，就是 refs 表的 CAS**。其余所有写操作都是幂等的、可重入的、可以并行发起的。这意味着：

- 后端只需要支持 single-key CAS（DynamoDB conditional put、Bigtable CheckAndMutate、Cassandra LWT、PostgreSQL 的 UPDATE ... WHERE 都满足），不需要 multi-row ACID 事务。
- commit 链可以任意长，索引可以任意大——读路径通过 `IndexesLogic.buildCompleteIndex` 沿 `tail` 链组装，写路径只需原子翻指针。
- Git 风格的"一切皆不可变对象 + 指针重指向"语义天然成立，不需要额外的一致性协议。

**因果链**：内容寻址 → 对象写入幂等 → 唯一原子点是 refs CAS → Git-like commit 链可以用最简单的 KV 后端实现。

### 2.2 维度二：一致性语义——OCC + single-key CAS + Iceberg expectedContent

Nessie 的每个 `Put` 操作必须带 `expectedContent`，其中包含 Iceberg 表的 `snapshotId` / `schemaId` / `partitionSpecId` / `sortOrderId`。服务端在 CAS 之前做 OCC 校验：如果客户端基于的 Iceberg 状态与当前 branch 上的状态不一致，操作被拒绝，客户端需要基于新状态重试。

这里有两个关键细节：

第一，**OCC 的粒度是 ContentKey（约等于表）**。两个并发 commit 如果动的是不同的表，CAS 后胜者写入、败者看到指针变化后重试，重试时因为没有 key 冲突会成功——两者可线性化。这让 Nessie 在多分支并行写入场景（每个 ETL 管道一个 branch）具备非常高的吞吐上限。

第二，**Nessie 的 OCC 校验借用了 Iceberg 的不可变状态语义**。Iceberg metadata.json 本身是不可变的，每次变化都会生成新 metadata.json，Nessie 只需比对 snapshotId 等字段就能判断是否冲突。如果换成 Delta Lake 的 transaction log（_delta_log/00001.json、00002.json 追加写），Nessie 没有"single pointer"可以比对，OCC 模型必须重新设计。

**因果链**：Iceberg metadata 不可变 → Nessie 可以用 expectedContent 做服务端 OCC → 不需要悲观锁、不需要引擎会话状态 → Git-like 的多表并发提交天然成立。

### 2.3 维度三：多租户模型——单 repository，放弃多租户换取跨表原子

Nessie 一个 server 只承载一个逻辑 repository。所有表共享同一棵 commit 树、同一组 refs 指针。这是多表原子性得以成立的必要前提——多张表的 metadata pointer 共处同一个版本图，一次 CAS 可以同时翻转多个 ContentKey 的指针。

反过来看：**如果要支持"Catalog A 与 Catalog B 是两个隔离的版本树"这种多租户模型，那么跨 Catalog 的原子提交就必须引入分布式事务（2PC / Saga）**。这和 Nessie 的"single-key CAS 就够"的设计假设根本冲突。

结论：原子边界宽度与租户隔离粒度反向相关。Nessie 选择了原子性优先，把多租户让给"多实例部署"去解决——每个租户起一套 Nessie server + DB，接受运营成本上升。

这个取舍对 SaaS 场景是劣势，对单组织内部 DataOps 管道是优势（一切表都在同一版本空间，跨部门发布天然原子）。

### 2.4 维度四：数据层介入程度——只管元数据 pointer

Nessie 完全不碰 Parquet 数据文件。Iceberg 保证"写入后数据文件不再修改"，Nessie 只需维护"当前 branch 上哪个 metadata.json 是活的"。这让 server 本身无状态，任何对象存储都可以接入，运维链条极短。

代价集中在两处：
- **GC 困境**：因为不拥有文件树，`nessie-gc` 必须遍历所有 commit 历史的所有 live Iceberg snapshot，计算 live 文件集合，再与对象存储里实际存在的文件取差集。这个过程随 history 深度和 branch 数量线性增长，长生命周期 branch 会显著降低 GC 效率。
- **绕过风险**：不掌控对象存储凭据，用户拿到 S3 路径后可以绕过 Nessie 的 CEL 授权直读。credential vending 是后加能力，GCS/ADLS 仍 experimental。

注意：**"只管元数据"是 Git-like 在 Catalog 层得以成立的前提**。如果 Catalog 要接管数据文件（例如 lakeFS 那样的 CoW 文件树），那么版本化对象就是"文件内容"而不是"metadata pointer"，Catalog 本身就被降维成文件系统，原来的"表语义"需要另一层重建——这是另一条路线（见 5.2 节 lakeFS）。

### 2.5 维度五：协议层——扩展 IRC，承担兼容代价

Nessie 同时维护两套 API：原生 Nessie REST v2（暴露 branch / commit / merge / transplant / diff / history）和 Iceberg REST Catalog 兼容层（IRC spec 里没有 branch / commit 概念）。

IRC spec 的设计哲学是"catalog 无状态、引擎负责所有语义"；Nessie 的设计哲学是"catalog 持有完整版本历史、提供原子发布语义"。两种目标互斥。

Nessie 的妥协方案是**把分支名编码在 IRC URL 的末段**（`/iceberg/main`、`/iceberg/experiment|warehouse`）。标准 IRC 客户端通过 `prefix` 参数传 reference，而 Nessie 忽略 `prefix` 从 URL 末段解析——两种路径不相交，标准 IRC 客户端只能看到 URL 里写死的那个分支，看不到 commit log、diff、history。要用 Git-like 能力，用户必须回到 Nessie 原生 API / CLI。

这个代价在 2024–2026 年 IRC 事实标准化的大背景下尤其刺眼：Polaris TLP 成为工业界默认实现，越来越多工具以"纯 IRC 兼容"为第一目标，Nessie 的私有方言层变成生态接入的摩擦。这也是 Dremio 在 2024-10 公开表态"要把 Nessie 能力合进 Polaris"的根本动因。

### 2.6 五维一致性：Nessie 的命题与边界

五个选择并非孤立，它们共同服务于同一个命题：**"用最简单的存储原语（single-key CAS + 内容寻址 KV）在 Catalog 层实现 catalog-wide Git-like 语义，专注 Iceberg，不扩边界。"** 推论链：

1. 选 L3 Catalog 层实现版本化（而非 L1 文件、L2 表格式）——因为 L3 同时具备"表语义"和"跨表原子"两个性质。
2. 只支持 Iceberg——因为 Iceberg 不可变 metadata.json 是 single-key CAS 模型成立的必要前提。
3. 采用 OCC + single-key CAS——把后端原子性要求降到无事务 KV 可承担的最低门槛。
4. 单 repository——多表原子要求共享版本树，多版本树就必须引入分布式事务，破坏 CAS 假设。
5. 只管元数据 pointer——Iceberg 不变性让 Nessie 只需翻转指针即可完成语义，介入数据层会带来高耦合。
6. 扩展 IRC 标准——Git-like 语义在现有 IRC spec 里无法表达，必须作为私有方言存在。

这六个选择互相支撑，形成一个极度内聚的架构。它的代价也被同样精确地划定：非 Iceberg 格式无法支持、SaaS 多租户无法原生支持、行级版本化无法实现、标准 IRC 客户端兼容性有边界。

---

## 3. 主流 Catalog 在五个维度上的相反选择

本章逐一对比 Apache Polaris、Apache Gravitino、Unity Catalog OSS、Lakekeeper 在 Nessie 上述五个维度上做出了什么选择、为什么，以及由此决定它们各自无法提供 catalog-wide Git-like 的根本原因。

### 3.1 Apache Polaris：纯 IRC 实现，不做版本控制

**项目背景**：Snowflake 在 2024-06 宣布 Polaris，2024-07-30 以 Apache 2.0 开源并捐赠 ASF，2025 年 10 月发布 1.0，目前在 incubating，社区事实标准 Iceberg REST 实现。Dremio 在 2024-10 公开表态"要把 Polaris 作为默认 catalog，把 Nessie 合进 Polaris"，Nessie 长期路线是并入 Polaris。

**五维选择**：

| 维度 | Polaris 的选择 | 与 Nessie 对比 |
|---|---|---|
| 存储模型 | 关系型 JPA/ORM，主流后端 PostgreSQL；EclipseLink 可选 | 相反：依赖多行事务的 RDBMS |
| 一致性 | RDBMS 多行事务 + Iceberg 原子 metadata 替换 | 相反：行锁 / 事务 → 高冲突写更稳定，但抽象层更厚 |
| 多租户 | Catalog 为顶层租户单位，RBAC 细粒度、Principal / Role 模型 | 相反：原生多租户 |
| 数据层介入 | Credential Vending（S3/GCS/ADLS 完整）+ per-table Policy | 更深：down-scoped STS 凭据 |
| 协议 | 纯 IRC + 私有 Management API（`/api/management/v1/`） | 相反：不扩展 IRC，Git-like 能力不在 scope |

**版本控制能力现状**：Polaris 1.0 明确表示通过 Iceberg **表级** branch / tag + 表级快照历史来满足版本化需求，没有引入 catalog-wide branch。2025-10 发布的 1.0 release notes 中有 "Snapshot filtering" 特性——允许 IRC 客户端只 fetch 特定分支或 tag 对应的 metadata，减少 metadata 体积——这是表级 branch 的优化，不是 catalog-wide 的。

**为什么不做 catalog-wide Git-like**：Polaris 的设计前提是"纯 IRC 兼容 + SaaS 多租户 + per-table RBAC"，这三个目标分别对应到"后端多行事务 / 多 catalog 隔离 / 数据层凭据控制"三个与 Nessie 相反的架构选择。如果要加 catalog-wide branch，就必须为每个 catalog 维护一棵独立 commit 树，然后回答"跨 catalog 原子提交如何做"——这是 Nessie 通过"单 repo"回避的问题，Polaris 无法回避。

**Nessie → Polaris 合并的现实难度**：Dremio 的表态至今（2026-04）仍未落地。现实障碍包括：Polaris 的数据模型（Catalog → Namespace → Table 的关系型结构）与 Nessie 的 commit 链 + refs 指针根本不同；Polaris 的多租户 + per-catalog RBAC 与 Nessie 的单 repo 互斥；IRC spec 本身不支持 branch / commit / merge。一个可能的路径是 JB Onofré 暗示的"先把 branch/merge 语义并入 Iceberg REST Spec v2，再由 Polaris 实现"——这条路在 2026-04 看不到具体时间表。

**结论**：Polaris 是 Nessie 的 **架构对立面** 在同一生态位上的完整实现。两者合并不是"加一个功能"，而是"合并两种架构"，难度极高。

### 3.2 Apache Gravitino：联邦 Catalog-of-Catalogs，完全不在同一层

**项目背景**：Gravitino（前身 Datastrato，2024 进 ASF 孵化，2025-05 毕业为 TLP）。定位是"geo-distributed, federated metadata lake"——把 Hive Metastore、Iceberg REST、Kafka Schema Registry、MySQL/PostgreSQL JDBC、ML Model Registry 等异构元数据源统一成一个 Metalake 层，提供单一 REST API 面向上游引擎。

**五维选择**：

| 维度 | Gravitino 的选择 | 与 Nessie 对比 |
|---|---|---|
| 存储模型 | 关系型 RDBMS（MySQL/PostgreSQL/H2），存 Metalake → Catalog → Schema → Entity 的层次结构 | 相反：不做版本化存储 |
| 一致性 | 依赖上游 catalog 的一致性模型（Iceberg 用 IRC 的原子替换，HMS 用其自身机制） | 完全不同：自己不承担一致性语义 |
| 多租户 | Metalake 为顶层租户，每 Metalake 下可容纳多个异构 Catalog | 相反：多层级租户 |
| 数据层介入 | 不介入，完全委托给上游 Catalog | 与 Nessie 类似，但走不同路径 |
| 协议 | 原生 Gravitino REST + 透出 Iceberg REST 兼容层（作为可选）+ Trino connector | 双协议，但两层都不含 Git-like |

**抽象层差异的根本性**：Gravitino 的核心价值主张是"统一**异构元数据源**"，它的抽象层比 Nessie / Polaris 高一层——它不管某张 Iceberg 表的 metadata pointer 是怎么更新的（这是下游 IRC 的职责），它只管"这张表在哪个 Catalog 里、叫什么名字、schema 长什么样"。因此 Gravitino 不可能做 catalog-wide 原子提交——它根本不持有事务语义。

**Gravitino 与 Nessie / Polaris 的关系**：Gravitino 可以把 Nessie 或 Polaris 当作一个"下游 Iceberg REST catalog"注册进来——这时 Nessie 的版本语义仍然有效，但只在那个"子 catalog"范围内生效，Gravitino 层不会透传 branch / commit 能力。

**版本控制能力现状**：Gravitino 1.2（2026-03）release notes 关注点是 Delta Lake 支持、Trino 多版本支持、多集群 Fileset、UDF 管理。没有任何 branch / commit / merge 相关的 roadmap。这符合 Gravitino 的定位——联邦层不应该重新实现下游 catalog 已有或没有的能力。

**结论**：Gravitino 与 Nessie 不在同一赛道，两者是互补关系而非竞争关系。要评估"Gravitino 上能不能做 Git-like"是问错了问题——合理的问题是"Gravitino 下面接的某个 catalog 支不支持 Git-like"。

### 3.3 Unity Catalog OSS：多模态治理优先，版本控制交给 Delta Lake

**项目背景**：Databricks 在 2024-06 开源 Unity Catalog OSS（Apache 2.0），作为 Databricks 商业版 Unity Catalog 的开源子集。核心抽象：Catalog → Schema → Table/Volume/Function/Model/Vector Index，三层命名空间 + 多种一等公民资产。

**五维选择**：

| 维度 | Unity Catalog OSS 的选择 | 与 Nessie 对比 |
|---|---|---|
| 存储模型 | 关系型（默认 H2 / SQLite，生产 PostgreSQL） | 相反 |
| 一致性 | RDBMS 事务 + Delta Lake 的 \_delta\_log 原子提交 | 不同路径：版本化能力来自 Delta，不来自 Catalog |
| 多租户 | Metastore → Catalog → Schema 三级，每级可 RBAC | 相反：原生多租户 |
| 数据层介入 | 商业版深度（Credential Vending + Delta Sharing），OSS 版弱 | 商业版远深于 Nessie，OSS 版接近 |
| 协议 | 私有 REST（Unity Catalog API）+ IRC 兼容层（OSS 0.2 起） | 双协议，但都不含 catalog-wide branch |

**版本控制能力现状**：

- **表级 time travel**：Delta Lake 原生支持 `VERSION AS OF` 和 `TIMESTAMP AS OF`；Iceberg 表也支持（通过 UniForm / 直接 Iceberg）。这是**表级**的版本控制，不是 catalog-wide 的。
- **Schema 演化版本**：Unity Catalog 追踪 schema 变化历史，每次 DDL 生成一个 schema 版本。这是模式版本化，不是数据版本化。
- **Model 版本**：MLflow 集成，每次注册同名模型生成一个新版本。这是资产级版本化。
- **没有 catalog-wide branch / commit / merge**：OSS 和商业版都没有。

**为什么不做**：Databricks 的战略是"一等公民资产丰富、多模态治理一体化"——Volume、Function、Model、Vector Index 都是一等公民。这个架构的扩展方向是"**更多资产类型**"而不是"**更强事务语义**"。catalog-wide branch 需要把所有资产类型都纳入版本树，复杂度爆炸。Databricks 的答案是：数据集版本控制交给 Delta Lake 表级 branch（通过 UniForm 的统一抽象对 Iceberg 也生效）、模型版本交给 MLflow、实验版本交给 DVC——不在 Catalog 层强行聚合。

**结论**：Unity Catalog OSS 是"多模态治理"路线的代表，它在资产抽象丰富度上胜过 Nessie / Polaris，但在事务语义深度上不及。两者是不同优化目标的产物，不能简单排名。

### 3.4 Lakekeeper：Rust 实现的纯 IRC，极简设计

**项目背景**：Lakekeeper 是一个 Rust 实现的 Iceberg REST Catalog，代码量小、设计干净、OpenFGA 集成。定位是"标准 IRC + per-tenant 授权 + 高性能"，不做版本控制。

**五维选择**：与 Polaris 高度相似——RDBMS 事务（PostgreSQL 强依赖）、Project → Warehouse 多租户模型、Credential Vending（S3 生产可用，GCS/ADLS experimental）、纯 IRC + `/management/v1/` 私有管理 API。差异主要在实现语言、代码体量、鉴权模型（OpenFGA）。

**版本控制能力**：同 Polaris——支持 Iceberg 表级 branch、IRC `commitTransaction`（同一引擎会话内多表原子），没有 catalog-wide branch。

**与 Nessie 的对照价值**：Lakekeeper 用最小代码实现了"标准 IRC + 授权"——这是 Nessie 原生 API 2.0 之外的另一条极简路线，两者的对比可以清楚看到"纯 IRC 实现"和"扩展 IRC 实现"的工程规模差异（Lakekeeper 数千行 Rust，Nessie 数万行 Java 核心）。额外代码量几乎全部花在了 Git-like 语义的实现上。

### 3.5 横向对比矩阵

| 维度 | Nessie | Polaris | Gravitino | Unity Catalog OSS | Lakekeeper |
|---|---|---|---|---|---|
| 存储模型 | 内容寻址 + 双表 KV | RDBMS + JPA | RDBMS 层次实体 | RDBMS 资产模型 | RDBMS |
| 一致性原语 | single-key CAS | 多行事务 | 委托上游 | 多行事务 + Delta 日志 | 多行事务 |
| 多租户模型 | 单 repo（无多租户） | Catalog 级多租户 | Metalake 级多租户 | Catalog 级多租户 | Project 级多租户 |
| 数据层介入 | 不介入（GC 独立工具） | Credential Vending + Policy | 不介入 | 商业版深，OSS 浅 | Credential Vending |
| 协议 | Nessie v2 + 扩展 IRC | 纯 IRC | Gravitino REST + IRC 兼容层 | UC API + IRC 兼容层 | 纯 IRC |
| 格式支持 | Iceberg（View experimental） | Iceberg（Delta/CSV/Parquet beta） | Iceberg/Hudi/Delta/HMS/JDBC/Kafka | Delta 一等 + Iceberg（UniForm） | Iceberg |
| **Catalog-wide branch** | **有** | 无 | 无 | 无 | 无 |
| **Catalog-wide atomic commit** | **有** | 单引擎会话内（IRC `commitTransaction`） | 无 | 单引擎会话内 | 单引擎会话内 |
| **多表 WAP（跨时间、跨管道）** | **有** | 无 | 无 | 无 | 无 |
| 活跃度（2026-04）| Dremio 维护 + 长期合并 Polaris 计划 | Snowflake + Dremio 合力，ASF 事实标准 | ASF TLP，Uber/Apple 等背书 | Databricks，OSS 仍早期 | 独立团队 |

### 3.6 小结：Nessie 的独特性不是"功能优势"而是"架构路径差异"

Nessie 与其他四者的差异不是"有没有某个功能"，而是"整个架构栈在 Ops 场景 vs 治理场景之间做了根本不同的取舍"。Nessie 优化的是"单组织内部的 DataOps 管道"——多表原子发布、WAP、分支隔离实验、CI/CD for Data。其他四者优化的是"多组织 / 多租户的治理与互操作"——RBAC、Credential Vending、多格式联邦、资产多样性。

这也是为什么"Dremio 要把 Nessie 合进 Polaris"这件事说了 18 个月还没落地——不是技术难度大到做不了，而是两种架构的目标函数互相冲突，任何合并方案都要先回答"哪个方向让步"。

---

## 4. 在主流 Catalog 上反向移植 Git-like 的技术代价

本章回答 Q3：如果自研 Catalog 以 Polaris / Lakekeeper 等"主流 IRC 实现"为起点，想要叠加"接近 Nessie"的 Git-like 能力，工程代价是什么？有没有渐进式路径？

### 4.1 要复制到什么程度

先明确"Git-like"的能力分层。按难度递增：

| 层级 | 能力 | 难度评估 |
|---|---|---|
| L1 基础 | 单表 time travel、snapshot 隔离 | ✅ Iceberg 原生具备，Catalog 不需做 |
| L2 表级 branch | 单张表的 branch / tag / WAP / fast-forward | ✅ Iceberg 原生具备，Catalog 只需透出元数据 |
| L3 单引擎跨表原子 | IRC `commitTransaction`——一次引擎请求内的多表原子 | 🟡 IRC spec 已支持，Polaris / Lakekeeper 已实现 |
| L4 跨管道多表原子发布 | 多个独立引擎 session、跨时间的多表变更原子对下游可见 | ❌ 需要 catalog-wide branch 模型 |
| L5 Catalog-wide 版本历史 | 整个 catalog 层面的 commit log、diff、reflog、history | ❌ 需要版本化存储模型 |
| L6 Catalog-wide merge / transplant | 三方 merge、cherry-pick、冲突检测 | ❌ 需要 merge 算法 + 冲突解决器 |

前三层是主流 Catalog 已经提供或容易补齐的（L3 的 `commitTransaction` 在 IRC spec 里有明确定义）。真正需要改造的是 **L4–L6**，这也是 Nessie 的核心价值区间。

### 4.2 改造的五个工程子项

要在 Polaris 风格的 Catalog 上实现 L4–L6，需要同时改造五处：

#### 4.2.1 存储模型改造——从"实体关系表"到"版本化对象图"

Polaris 目前的存储是典型的 JPA 实体：`Catalog` / `Namespace` / `IcebergTable` 等实体存在若干关系表里，每次 update 就是 RDBMS `UPDATE ... WHERE id=?`。要引入 Git-like，必须额外维护：

- **一个 commit log**：每次 branch 上的 update 对应一个不可变 commit 记录，包含 parent commit id、author、timestamp、变更集。
- **一组 branch / tag 指针**：branch 指向 commit id，可以原子翻转。
- **变更集索引**：能快速回答"在 commit X 到 commit Y 之间哪些 ContentKey 变了"。

这至少需要新增 2–3 张表，并把原来的"update 实体"改造为"append commit + 翻转 branch 指针"两步。如果保留现有实体表（为兼容），就要双写；如果不保留，所有现有 API 需要重写。这是 **人月级别** 的改造。

#### 4.2.2 一致性语义改造——从"多行事务"到"OCC + expectedContent 校验"

Polaris 的 `commitTransaction` 在一次 IRC 请求内通过 RDBMS 事务保证多表原子；Nessie 的多表原子是"任意一次 Nessie commit 都是 catalog-wide 原子"。前者每次原子边界是单个 HTTP 请求，后者是整个 commit chain。

要让 Polaris 支持"跨 session 多表原子发布"，必须把原子边界从"HTTP 请求"拓展到"branch merge 操作"。技术上需要：

- 引入 branch 概念：每次 DML 都要指定目标 branch（对标准 IRC 客户端不可见）。
- 引入 expectedContent 校验：每次 Put 要带前置状态，服务端做 OCC。
- merge 时需要处理"目标 branch 在源 branch fork 之后的新 commit"——这是真正意义上的 Git merge，需要 commit replay 或 3-way merge 算法。

这里的挑战不是代码量，而是**语义变化**：Polaris 的 RBAC 模型、审计日志、监控指标全部建立在"每次变更 = 一个 HTTP commit"之上，引入 branch 后需要重新定义"审计在哪一层进行""通知订阅者看到哪个视图的变化"。

#### 4.2.3 多租户模型改造——解决原子性与隔离性的冲突

Polaris 的核心租户单位是 Catalog。不同 Catalog 之间是隔离的，用户和权限各自独立。如果给每个 Catalog 加 branch，那么"跨 Catalog 的原子 merge"就变成了需求——而这是 Nessie 通过"单 repo"回避的问题。

有三条可能的路：

- **路线 A：每个 Catalog 内部有 branch，跨 Catalog 无原子**。实现简单，但丧失了"一个企业数据湖整体原子发布"的 Nessie 核心场景。
- **路线 B：引入跨 Catalog 的分布式事务（2PC / Saga）**。保留 Catalog 隔离，但破坏了 single-key CAS 的简洁性——后端必须支持真正的多节点事务，放弃 DynamoDB / Cassandra 等 KV 后端的可能性。
- **路线 C：引入"Catalog Group"概念作为新的 repo 单位**。在 Group 内原子，Group 间隔离。这等于把 Nessie 的"单 repo"模式复刻一遍，只是换个名字。用户理解成本高。

三条路都有显著代价。现有 Polaris 的多租户用户（Snowflake、Dremio、其他 vendor）不会轻易接受任何一条——这也是 Nessie 合并进 Polaris 拖了 18 个月的深层原因。

#### 4.2.4 协议扩展——在 IRC 之外设计 branch API

IRC spec 当前没有 branch / commit / merge 原语。要对外暴露 Git-like 能力，有两种选择：

- **等待 IRC v2**：JB Onofré 暗示 branch/merge 可能先进入 Iceberg REST Spec 再进入 Polaris。时间表完全不透明，spec 社区对"catalog 持有有状态版本历史"的增加存在根本争议。
- **私有扩展**：在 `/api/management/v2/branches/` 或类似路径下做自己的 branch API，像 Nessie 一样把分支名编进 IRC URL（`/iceberg/v1/<branch>/...`）。这在 2026 年选择跟 Nessie 一样的私有方言——意味着继承 Nessie 已经面对的兼容性问题（标准 IRC 客户端切不了分支、commit log 访问不了）。

IRC 标准化的主旋律是"catalog 越轻越好、语义让给引擎"，这条主旋律与 Git-like 的"catalog 持有完整语义"互斥。在可预见的未来（2–3 年），私有扩展几乎是唯一现实选择。

#### 4.2.5 GC 与存储治理改造

Nessie 的 `nessie-gc` 需要遍历所有 commit 历史的所有 live Iceberg snapshot 来计算 live 文件集合。Polaris 目前没有 catalog-wide GC（Iceberg 的 `expire_snapshots` 是表级）。加入 branch 后必须要有等价机制——否则长生命周期 branch 会导致大量孤儿文件积累。

实现 `polaris-gc` 等价物的难度与 Nessie 同级，且因为 Polaris 在某些部署下做了 credential vending，GC 工具还需要有权限扫描对象存储——这又牵出"GC 进程的权限模型"等子问题。

### 4.3 渐进式路线的可行性

把前述 5 个子项放到时间轴上，可能的渐进路线：

**阶段 1（0-6 个月）：叠加表级 branch 的聚合能力**
- 透出 Iceberg 原生 branch 能力（已有），增加 catalog 层的聚合 API：批量创建多表 branch、批量 fast-forward。
- 这层只是"脚本化包装"，没有真正的 catalog-wide commit log。
- 能解决部分 WAP 场景：一个管道在多表 branch 上写完，用 batch fast-forward 近似模拟发布。
- **局限**：不是原子的。两次 fast-forward 之间下游能看到部分状态；ETL 失败回滚需要手工处理。

**阶段 2（6-18 个月）：引入 catalog-wide branch 指针 + commit log**
- 新建 versioning 子模块，存 branch 指针、commit 链、变更集。
- 让 IRC 写路径变成"先写到 branch → branch head 指向新 commit"。
- 支持手动 merge（fast-forward only）。
- **局限**：仍然没有真正的 3-way merge；并发写同一 branch 需要 OCC 重试；RBAC 要围绕 branch 重新设计。

**阶段 3（18-36 个月）：真正的 Git-like**
- 实现 commit replay merge、cherry-pick、冲突检测。
- GC 工具、审计视图、通知系统全部改造。
- **局限**：此时代码复杂度接近 Nessie 本身，十几人团队维护多年才能稳定——这也正是 Nessie 的规模。

### 4.4 "买 vs 造"：工程经济学视角

用数字粗略估算：Nessie 当前核心代码（不含平台能力和 integrations）约 1400 个 Java 文件、十余人维护约 6 年。这是已经做成了、社区成熟了、后端适配齐全了的基线。从零构建等价能力需要的投入只会更多。

从 fork + 深度二次开发的视角：
- **Fork Nessie**：继承整套版本引擎，但要回答"与 Polaris 生态的长期兼容如何处理"——Nessie 本身正在被合并的方向性下，fork 承担的技术债会增加。
- **Fork Polaris + 移植 Nessie 内核**：继承标准 IRC + 多租户 + RBAC，但需要做 4.2 节列出的 5 项改造——这恰好是 Dremio 宣称要做、18 个月没做完的工作。
- **Fork Polaris + 叠加阶段 1/2**：可行，但只能覆盖 Nessie 部分场景，WAP 和 catalog-wide 原子这两个 Nessie 独有能力难以在短期内做到位。

**建议**：如果 Git-like 是自研 Catalog 的核心差异化，fork Nessie 更直接，回答"未来如何向 Polaris 生态迁移"作为独立问题。如果 Git-like 只是"希望有"而非"必须有"，基于 Polaris / Lakekeeper 做阶段 1 的增强就足够覆盖 80% 的 WAP 场景。

---

## 5. 跨层 Git-like 替代路径比较

本章回答 Q4。Catalog 层不是实现 Git-like 的唯一位置。其他层各有其成立条件与边界。本章聚焦对 Catalog 决策有直接影响的三条路径：L1 lakeFS、L2 Iceberg 原生 branch、L2+L3 合并 DuckLake。传统 OLTP 数据库（Neon / Dolt）仅作背景对照。

### 5.1 L2：Iceberg 原生 branch / tag（零组件代价）

Iceberg 1.2 起原生支持**表级** branch 和 tag，写到 branch 的操作生成该 branch 上的新 snapshot，`fast_forward` 存储过程把 main 推进到 branch 的 head。WAP 有两种实现：早期的 `WAP.id` + cherry-pick、推荐的 `WAP.branch` + fast-forward。

**能力边界**：
- ✅ **单表 WAP**：完整。`SET spark.wap.branch = audit-branch; INSERT INTO ...; CALL fast_forward(...)` 三步即可。
- ✅ **零额外组件**：不需要 Nessie server、不需要 lakeFS；Iceberg 库 + 任意 Catalog（甚至 HMS / Glue）都能用。
- ❌ **跨表原子不支持**：每张表各自维护 snapshot 链，没有统一的 catalog-wide commit 绑定多表变更。
- ❌ **隔离视图不是 catalog 级**：在 Iceberg branch `experiment` 上改 `sales.orders`，在同一 branch 上读 `sales.customers`，两张表看到的不是同一时间点快照——没有 catalog 层的统一 reference。
- ❌ **catalog 级能力缺失**：Starburst 等引擎明确列出"不支持 catalog-level branching、不支持 renaming branches、不支持 cherry-pick"是当前表级 branch 的局限。

**适用场景**：**单表 WAP、单表 time travel、单表审计**——不需要多表一致性发布的纯单表场景。这覆盖了相当比例的实际需求，如果这就是业务全部需求，Iceberg 原生 branch 就足够，无需 Nessie。

**对自研 Catalog 的启示**：如果准备做 Polaris 风格的 Catalog，无论如何都应该透出 Iceberg 表级 branch 的 API——这是最便宜的 WAP 能力，几乎没有实现代价。它能覆盖 60-70% 的 WAP 场景，剩下的再评估是否要上 catalog-wide branch。

### 5.2 L1：lakeFS（文件树版本化，格式无关）

lakeFS 是"对象存储上的 Git"——把 S3 / GCS / ADLS 的文件树整体纳入版本控制，任何写入路径都经过 lakeFS 的 branch / commit 语义。底层引擎 Graveler 用 Prolly Tree（content-addressable 变长分块树）实现大规模文件树的 diff 和 merge，与 Nessie 的双表 KV 思路不同但在"内容寻址 + 不可变对象 + 可变指针"层面是姊妹结构。

**核心价值与 Catalog 层的不同**：
- **格式无关**：Parquet / JSON / 图片 / 模型 / 任意二进制都一视同仁被版本化。这让 lakeFS 可以覆盖"Iceberg 表 + 非结构化数据 + 模型文件"的统一多模态分支。
- **CoW 天然无孤儿**：lakeFS 拥有文件引用计数，GC 精确；Nessie 因为不拥有文件树，GC 必须通过扫描 live snapshot 间接计算。
- **路径侵入**：lakeFS 通过自定义协议 `s3://repo/branch/path/...` 让文件访问经过 lakeFS——这需要应用侧改配置，但也正是它能精确控制 GC 的前提。

**lakeFS + Iceberg REST Catalog（2025 年加入）**：lakeFS 在 2025 年发布了官方 Iceberg REST Catalog 实现。架构是两层：lakeFS 作为 L1 文件版本引擎不动，在其上叠加一层标准 IRC 接口，用 lakeFS 原语实现 IRC 的 commit / branch / merge 等操作。每张 Iceberg 表变成一个 lakeFS 对象（存储当前 metadata.json 的路径指针），Iceberg 写入经过 lakeFS 的事务引擎。

**与 Nessie 的对比**：

| 维度 | Nessie | lakeFS + IRC |
|---|---|---|
| 版本化对象 | Iceberg metadata pointer | 文件树（Iceberg metadata.json 作为普通文件） |
| 格式支持 | Iceberg 专属 | 格式无关（lakeFS 本体）+ Iceberg（IRC 层） |
| 表语义完整性 | 原生保留 | 通过 pointer 对象实现（pointer 是 lakeFS 文件） |
| 多表原子 | catalog-wide CAS | lakeFS commit 原子性，覆盖整个 repo |
| 标准 IRC 兼容 | 扩展方言（URL 编路径） | 完整兼容（2025 年发布） |
| 多模态（文件/模型）| 不支持 | 原生支持 |
| GC 精度 | 需扫描 live snapshot | CoW 天然精确 |
| 路径侵入 | 否（S3 路径照常） | 是（需走 lakeFS 路径） |
| 运维组件 | Nessie server + DB | lakeFS server + PG/DynamoDB + 对象存储配置 |

**对 Catalog 决策的直接意义**：如果业务场景里**既有 Iceberg 表又有非结构化数据（图片、模型、特征文件）需要同步版本化**，lakeFS 的多模态能力是 Nessie 不具备的，且 lakeFS 在 2025 年补齐了标准 IRC 兼容，意味着不再需要在"功能丰富 vs 标准兼容"之间取舍。纯 Iceberg 场景下 lakeFS 略重（多一层文件引擎），Nessie 更轻。

### 5.3 L2+L3 合并：DuckLake（新路径挑战 Catalog 存在前提）

DuckLake 在 2025-05 发布 manifesto，2026-04-13 发布 v1.0（MIT）。其思路是**把 catalog 和表格式合并进一个 SQL 数据库**——metadata（schema、snapshot 历史、文件索引）全部存进 PostgreSQL / SQLite / DuckDB，数据文件仍是对象存储上的 Parquet。

每次事务提交生成一个 `snapshot_id`，通过 SQL MVCC 保证多表原子性。time travel、行级 CDF（`ducklake_table_insertions` / `ducklake_table_deletions` 函数）都具备。

**版本控制能力现状**：
- ✅ **多表原子提交**：SQL 事务天然保证。
- ✅ **time travel**：基于 snapshot_id 或 timestamp。
- ❌ **v1.0 不支持 branch/tag**：明确的非目标，可能在后续版本加入。
- 🟡 **多引擎生态**：DuckDB 原生、Spark / Trino 支持仍早期。

**它挑战什么**：DuckLake 实际上在问"既然 metadata 反正要存进数据库才高效，为什么还需要 Catalog 服务作为独立组件？" 如果接受这个前提，catalog 层独立存在的必要性被部分动摇——它已经自带 SQL 数据库作为 catalog，不需要外部的 REST Catalog。

**对 Catalog 决策的意义**：短期（2026）不影响，DuckLake 多引擎生态仍早期，不具备替代 Polaris / Nessie 的成熟度。中期（2-3 年）如果它加入 branch/tag 且多引擎跟上，可能成为"Catalog 层简化"的重要压力。

### 5.4 L0 传统数据库（简要）

**Dolt**（MySQL 兼容，Prolly Tree 存储，行级 branch/merge）、**DoltgreSQL**（PostgreSQL 兼容）、**Neon**（Serverless PG，WAP copy-on-write）：

- 定位：OLTP / 协作数据集，与 Lakehouse 分析场景目标用户不重叠。
- Dolt 的 Prolly Tree 与 lakeFS 的 Graveler 出自同一技术谱系。
- Neon 的 branching 通过 WAL 层 copy-on-write 实现，面向"每 PR 一个 DB 副本"的开发场景。

**对 Catalog 决策**：本节仅作为参考，除非业务场景涉及"对 PB 级分析表做行级精确 merge"或"OLTP 事务性数据集"，否则这条路径不在 Lakehouse Catalog 选型的直接替代范围内。

### 5.5 跨层路径选择矩阵

| 需求 | 首选层 | 备选层 |
|---|---|---|
| 单表 WAP / time travel | L2 Iceberg 原生 branch（零组件） | Polaris / 任意 IRC Catalog |
| 单表高频写 + 审计日志 | L2 Iceberg 原生 | 任意 Catalog |
| 跨多张 Iceberg 表原子发布（单 session） | L3 任意 IRC 的 `commitTransaction`（Polaris / Lakekeeper 已支持） | - |
| 跨多张 Iceberg 表原子发布（跨 session / 跨时间） | L3 Nessie | L1 lakeFS + IRC |
| 多表 + 非结构化数据一体化分支 | L1 lakeFS | - |
| catalog 层 commit log / reflog / history / diff | L3 Nessie | L1 lakeFS |
| 混合格式（Iceberg + Delta + Hudi）统一分支 | L1 lakeFS | - |
| 小规模 SQL 导向 + 多引擎 | L2+L3 DuckLake（中期观察） | Iceberg + PG IRC |
| 行级 diff / 行级 merge | L0 Dolt（OLTP 场景） | - |

---

## 6. 对自研 Catalog 的决策建议

### 6.1 首先回答的三个问题

自研 Catalog 是否要做 Git-like，不要从"做还是不做"开始，要从以下三个问题开始：

**Q-A：WAP 是**场景之一还是核心场景？**
- 核心场景（DataOps 管道 + CI/CD for Data + 强一致发布）→ 必须做 catalog-wide branch，评估 fork Nessie / 深度改造 Polaris。
- 场景之一（偶尔需要，不是主流程）→ 做表级 branch 的聚合 API 足够，不进入 catalog-wide branch 的深水区。
- 不是场景 → 不做，避免 Git-like 带来的架构复杂度。

**Q-B：多租户是核心要求吗？**
- 核心（SaaS / 多组织 / per-tenant 隔离）→ Polaris / Lakekeeper 路线，Git-like 场景用 Iceberg 表级 branch + `commitTransaction` 覆盖。
- 单组织多部门 → Nessie 路线可行，多部门用 namespace 隔离。
- 内部单一平台 → Nessie 路线最直接。

**Q-C：数据版本化的对象范围？**
- 仅 Iceberg 表 → Nessie / Polaris。
- Iceberg 表 + 非结构化数据（模型、图片、特征文件）需统一版本化 → lakeFS。
- 混合表格式（Iceberg + Delta + Hudi）需统一分支 → lakeFS。
- 仅单表 → Iceberg 原生，不需要独立 Catalog 增强。

### 6.2 典型场景的推荐

| 场景画像 | 推荐主线 | 理由 |
|---|---|---|
| 单组织 + 核心 WAP + 纯 Iceberg + 内部 DataOps | fork Nessie，跟踪 Polaris 合并进展 | Nessie 的核心场景，短期无替代 |
| 多组织 SaaS + 纯 Iceberg + 偶尔 WAP | fork Polaris 或 Lakekeeper + 叠加阶段 1 表级 branch 聚合 | 主流路线，Git-like 缺口用 Iceberg 原生 + 聚合脚本覆盖 |
| 多模态数据（表 + 模型 + 非结构化）+ WAP | lakeFS（L1）+ Iceberg REST（2025 已发布）| lakeFS 的 2025 IRC 实现让这条路可行 |
| 小规模 + 多引擎 + 不需要分支 | DuckLake（观察期）或 Polaris | 若多引擎要求强则选 Polaris |
| 混合格式 + 大规模 + 不需要分支 | Gravitino（联邦）+ 各下游 IRC | 联邦是混合格式的正解 |

### 6.3 与 v4 报告的衔接

v4 报告第 13 章已给出 Nessie 本身的设计启示。本报告补充四条聚焦"catalog 层 Git-like 抉择"的启示：

**启示 A（主流 Catalog 不能轻易加 Git-like）**：Polaris / Lakekeeper / Unity 的架构与 Nessie 在五个维度上都是相反选择。在它们之上"加 Git-like"等同于重建部分架构栈，不是功能增量。Dremio 用 18 个月没合完是现实证据。

**启示 B（表级 branch 是低成本起点）**：无论选哪条主线，Iceberg 表级 branch 都应该透出。它覆盖 60-70% 的 WAP 场景，实现代价接近零，可以成为任何 Catalog 的标配能力。

**启示 C（IRC 标准化是约束，不是机会）**：2024–2026 年 IRC 成为事实标准，这个标准化过程本身对 catalog-wide Git-like 是压力而非助力——标准的哲学与 Nessie 的哲学互斥。期待 IRC v2 加入 branch/merge 原语是合理但长期（2-3 年）的预期。

**启示 D（lakeFS 的 IRC 模式改变了 L1 的对比结论）**：2025 年 lakeFS 发布 Iceberg REST Catalog 意味着 L1 路径不再需要在"功能 vs 标准"间取舍。如果业务场景涉及多模态数据，lakeFS 的综合性现在强于 Nessie。

---

## 7. 未解疑点与后续调研项

| 疑点 | 对决策的影响 | 推荐解决方式 |
|---|---|---|
| IRC v2 何时加入 branch/merge 原语？ | 直接影响"扩展 IRC 是不是长期正确路线" | 跟踪 Iceberg Improvement Proposal #10617 + PPMC 邮件列表；Zulip 向 JB Onofré 直接询问 |
| Polaris 吸收 Nessie commit kernel 的具体技术方案？ | 影响"fork Nessie 的长期技术债" | Polaris dev@ 邮件列表、Dremio blog；追踪 2026 Q3 前是否有 design doc |
| lakeFS IRC 的生产规模案例 | 影响 L1 替代路径的成熟度判断 | lakeFS 博客与 case studies；与 Treeverse 直接沟通 |
| DuckLake 多引擎（Spark/Trino）成熟时间 | 影响"L2+L3 合并路径"是否进入主流视野 | DuckLake GitHub + DuckDB 社区 |
| Nessie 在 Iceberg v3 的支持状态 | 影响对 Nessie 生态活跃度的判断 | Nessie Zulip + GitHub releases |
| Polaris "Generic Table" beta 的演进（Delta/CSV/Parquet）| 影响"Polaris 是否走向多格式" | Polaris 1.x release notes |

---

## 8. 参考资料

### 8.1 Nessie 基础（以 v4 报告为主，补充本次新增）

- 基础内容参考 project 内 Nessie_Research_Report_v4_final.md（本地文件 `/mnt/project/Nessie_Research_Report_v4_final.md`）
- Nessie Commit Kernel 架构：https://projectnessie.org/develop/kernel/
- Nessie Transactions Guide：https://projectnessie.org/guides/transactions/
- Nessie open-source Polaris announcement（2024-08）：https://projectnessie.org/blog/2024/08/02/open-source-polaris-announcement/
- SiliconANGLE Dremio 退场表态（2024-10）：https://siliconangle.com/2024/10/29/dremio-throws-support-polaris-data-catalog-expands-deployment-options-iceberg-lakehouse/

### 8.2 Apache Polaris

- Polaris 官网：https://polaris.apache.org/
- GitHub：https://github.com/apache/polaris
- 1.0 release blog（Snowflake，2025-10）：https://www.snowflake.com/en/engineering-blog/apache-polaris-1-0-release-open-source-catalog/
- Polaris incubating docs：https://polaris.apache.org/in-dev/unreleased/
- Polaris / Nessie 合并计划：https://www.bigdatawire.com/2024/07/30/polaris-catalog-to-be-merged-with-nessie-now-available-on-github/

### 8.3 Apache Gravitino

- 官网：https://gravitino.apache.org/
- GitHub：https://github.com/apache/gravitino
- 1.2.0 release notes（2026-03）：https://gravitino.apache.org/blog/gravitino-1-2-0-release-notes/
- Iceberg REST catalog service 文档：https://gravitino.apache.org/docs/0.6.0-incubating/iceberg-rest-service/
- Datastrato：Gravitino：Next-Gen REST Catalog for Iceberg：https://datastrato.ai/blog/gravitino-iceberg-rest-catalog-service/

### 8.4 Unity Catalog

- UC OSS 官方：https://www.unitycatalog.io/
- GitHub：https://github.com/unitycatalog/unitycatalog
- UC OSS 对比文章：https://celerdata.com/glossary/unity-catalog
- UC 对比分析（Marvik）：https://www.marvik.ai/blog/exploring-unity-catalog-oss-redefining-interoperability

### 8.5 Lakekeeper

- Lakekeeper 官网（Rust 实现 IRC）：https://lakekeeper.io/
- GitHub：https://github.com/lakekeeper/lakekeeper

### 8.6 Iceberg 原生 branch / tag / WAP / REST Spec

- Iceberg Branching and Tagging 文档：https://iceberg.apache.org/docs/latest/branching/
- Iceberg REST Catalog Spec：https://iceberg.apache.org/rest-catalog-spec/
- Iceberg Spec（整体）：https://iceberg.apache.org/spec/
- Iceberg Multi-Table Transaction Proposal #10617：https://github.com/apache/iceberg/issues/10617
- Dremio：Branch & Tag Apache Iceberg with Spark：https://www.dremio.com/blog/exploring-branch-tags-in-apache-iceberg-using-spark/
- Dremio：WAP with Iceberg Branching：https://www.dremio.com/blog/streamlining-data-quality-in-apache-iceberg-with-write-audit-publish-branching/
- Starburst：Iceberg Branching：https://www.starburst.io/blog/iceberg-branching-data-management/
- Iceberg consistency model（Jack Vanlightly）：https://jack-vanlightly.com/analyses/2024/8/5/apache-icebergs-consistency-model-part-2

### 8.7 lakeFS

- lakeFS 官网：https://lakefs.io/
- lakeFS Iceberg REST Catalog（2025）：https://lakefs.io/blog/lakefs-iceberg-rest-catalog/
- How we built lakeFS Iceberg Catalog：https://lakefs.io/blog/how-we-built-lakefs-iceberg-catalog/
- lakeFS Iceberg 文档：https://docs.lakefs.io/v1.66/integrations/iceberg/
- lakeFS Architecture（Graveler）：https://docs.lakefs.io/latest/understand/architecture/
- Iceberg Branching Best Practices：https://lakefs.io/blog/iceberg-branching/
- Git for Data：https://lakefs.io/blog/git-for-data/

### 8.8 DuckLake / OLTP 对照

- DuckLake v1.0（2026-04-13）：https://ducklake.select/2026/04/13/ducklake-10/
- DuckLake Manifesto（2025-05）：https://ducklake.select/2025/05/27/ducklake-01/
- Neon branching：https://neon.com/docs/introduction/branching
- Dolt：https://github.com/dolthub/dolt

### 8.9 第三方综合分析

- e6data Iceberg Catalogs 2025：https://www.e6data.com/blog/iceberg-catalogs-2025-emerging-catalogs-modern-metadata-management
- Conduktor Iceberg Catalog Management：https://www.conduktor.io/glossary/iceberg-catalog-management-hive-glue-and-nessie
- RisingWave 2026 Iceberg status：https://risingwave.com/blog/apache-iceberg-streaming-2026/
- ClickHouse Data Catalog overview：https://clickhouse.com/resources/engineering/data-catalog

---

## 信息置信度

**高置信**：Nessie 五维架构描述（来自 v4 报告已验证源码）；Polaris / Gravitino / Unity / Lakekeeper 各自定位与关键特征（官方文档与 release notes 确认）；lakeFS Iceberg REST Catalog 2025 发布（lakeFS 官方博客）；Iceberg 表级 branch 能力（Iceberg 官方文档）；Nessie 合并进 Polaris 的战略声明（Dremio / Snowflake 官方）。

**中置信**：4.2 节的改造工程代价估算（基于现有代码规模的外推，未对 Polaris 源码逐行分析）；4.3 节渐进路线的时间尺度（行业典型项目周期估计）。

**未解疑点**：IRC v2 branch/merge spec 的具体时间表；Polaris 吸收 Nessie commit kernel 的落地方案；lakeFS IRC 在大规模生产的成熟度证据；DuckLake 加入 branch 的可能性与节奏。建议在选型决策前通过邮件列表或社区直接确认。

---


*本报告聚焦 Catalog 层 Git-like 专题，与 Nessie v4 报告互补，读者建议两者对照阅读。*
