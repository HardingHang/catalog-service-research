# 湖仓表格目录（Lakehouse Table Catalog）调研方案

## 0. 背景与目的

本文档为自研 Catalog Service 的前期调研工作确定 **调研维度** 与 **候选产品清单**，输出物将作为后续需求分析、架构设计与功能开发的输入。

**调研聚焦方向**：湖仓表格目录（Lakehouse Table Catalog）——即为 Apache Iceberg / Delta Lake / Apache Hudi / Apache Paimon 等开放表格式提供 namespace、表、视图、事务、权限、血缘、**存储优化调度**、**AI Asset 治理**等元数据管理能力的服务层。

**不在调研范围**：
- 偏发现与治理的"数据目录（Data Catalog）"产品（如 DataHub、OpenMetadata、Atlas）；
- Kubernetes Service Catalog、API Catalog、电商商品目录等同名异义产品。

**作为基准对照**：Hive Metastore（HMS）虽属旧形态，但所有新 Catalog 都要回答"如何兼容 HMS"，故保留为参考。

---

## 1. 调研维度

维度按 "**为什么 → 是什么 → 怎么做 → 怎么演进**" 的脉络组织，共 12 项（A–L）。

### A. 定位与设计目标

- 项目起源：发起方（公司 / 个人）、成立时间、捐赠路径（是否进入 ASF / LF 等基金会）
- 初始痛点：诞生时要解决的核心问题
- 目标用户与典型场景
- **设计目标与非目标**——"不做什么"与"做什么"同等重要
- 差异化主张（与同类产品相比强调什么）

### B. 核心概念与元数据模型

- 顶层抽象层次：Catalog / Namespace / Schema / Database / Table / View / Function / **Model / Feature / Volume / Vector Store** 等
- 表格式支持范围（Iceberg / Delta / Hudi / Paimon / 普通文件）
- 元数据模型的可扩展性：是否支持自定义 entity、property、tag
- 对多源元数据的统一建模方式（联邦式 Catalog）
- Asset 以外的一等公民（如 Unity Catalog 的 Volume / Function / Model / **Feature Table**）

### C. 架构与关键设计

- 分层结构（API 层 / 业务层 / 存储层）
- 元数据持久化选型（RDBMS / KV / 对象存储 / 自研引擎）
- 一致性与事务模型：
  - 单表原子提交
  - **跨表 / 跨 Catalog 事务**（目前各家差异最大的点之一）
  - 并发冲突解决策略
- **存储优化调度架构**：Clustering / Compaction / Stats Collection 是引擎侧主动扫描还是 Catalog 侧统一编排？
- 扩展机制：Catalog Provider、Authenticator、Event Hook、Policy Engine 是否可插拔
- 部署形态：单体 / 分布式 / 多租户 / Serverless
- 缓存策略与元数据读写路径

### D. 协议与接口

- **对外协议**
  - Iceberg REST Catalog Spec 的兼容程度（已成事实标准，需逐条比对）
  - HMS Thrift 兼容性
  - 自定义 REST / gRPC / JDBC
- SDK 覆盖（Java / Python / Go / Rust）
- 认证协议：OAuth2、SigV4、Kerberos、PAT
- **Credential Vending**（对象存储临时凭证下发）设计

### E. 功能矩阵

这一维度粒度最细，建议直接做成打分表。核心能力点：

| 能力域 | 考察点 |
|---|---|
| **表格式支持** | Iceberg v1/v2/v3、Delta、Hudi、Paimon；跨表格式互操作（如 UniForm / XTable） |
| **Schema 演化** | 列增删、重命名、类型提升、Partition 演化（Hidden Partitioning / Liquid Clustering） |
| **版本管理** | Time travel、Snapshot 隔离、**Branch/Tag（Git-like）**、WAP（Write-Audit-Publish）模式 |
| **存储优化与调度** | **数据布局优化**（Liquid Clustering / Z-Ordering / Hilbert Curve / Bin-packing）、**Compaction 调度**（小文件合并策略与触发机制）、**统计信息收集**（文件级/列级 Min-Max / NDV / Bloom Filter / Partition Stats / Puffin 文件） |
| **查询加速** | 数据跳读（File/Partition Skipping）、Metadata Index、结果缓存 |
| **权限治理** | RBAC、ABAC、列级、行级、动态 Masking / Filtering |
| **Policy 引擎** | 内置 or 对接 OPA / Ranger |
| **血缘** | 表级 / 列级；采集方式（引擎上报 / SQL 解析 / OpenLineage 集成） |
| **审计** | 操作日志、合规导出 |
| **搜索与发现** | 关键词、语义、tag、glossary |
| **数据质量** | 规则定义、校验结果回写 |
| **视图与物化视图** | 是否为一等公民；MV 刷新调度元数据 |
| **跨表事务** | 是否支持、实现方式（Nessie 的 Multi-table commit / Gravitino 的分布式事务） |
| **AI/ML 集成** | **Feature Store 集成**（Feature Table 定义、Feature Serving API）、**Model Registry**（模型版本、Artifact 存储引用）、**Vector Search**（Vector Index / Embedding 管理）、与 MLflow / Kubeflow / LangChain 的集成深度 |
| **数据共享** | Delta Sharing / Iceberg REST Catalog 跨组织共享 |

### F. 非功能特性

- **性能**：元数据读写 QPS、超大 Partition / Snapshot 场景下的退化行为、**REST Catalog 高并发清单加载延迟**
- 可用性：HA 方案、failover RTO/RPO
- 可扩展性：水平扩展方式、状态管理
- 可观测性：指标、日志、tracing 的开箱程度
- 安全：传输加密、静态加密、密钥管理、审计合规（SOC2 / GDPR）
- 多租户隔离粒度：物理隔离 / 逻辑隔离 / Quota

### G. 生态与集成

- **计算引擎**：Spark、Flink、Trino、Presto、Doris、StarRocks、DuckDB、Snowflake、ClickHouse
- **AI/ML 框架**：MLflow、Kubeflow、Ray、LangChain、LlamaIndex、Hugging Face
- BI 工具：Tableau、Power BI、Superset
- 编排：Airflow、Dagster
- 云厂商：AWS / Azure / GCP / 阿里云 / 腾讯云 / 华为云
- **迁移路径**：与 HMS / Glue 的互操作、灰度迁移方案

### H. 运维与落地成本

- 部署依赖（数据库、消息队列、对象存储、K8s）
- 资源消耗基线
- 升级策略：滚动升级、元数据 schema 迁移
- 故障排查工具与手册
- 文档完备度：Getting Started / Operator Guide / API Reference / Troubleshooting

### I. 社区与治理

- 基金会归属或治理模型
- 背后商业公司及其利益诉求（重要，影响路线图中立性）
- Contributor 活跃度（近 6–12 个月）
- 发布节奏与版本策略
- Issue / PR 平均响应时间
- RFC / Design Doc 流程是否公开（能否提前窥见未来方向）

### J. License 与商业化

- 开源协议（Apache 2.0 / BSL / Elastic / AGPL）——这对**二次开发并对外提供服务**的合规性影响巨大
- 是否有托管版 / 企业版
- 开源版与商业版的功能边界（哪些是"阉割位"）
- **可 fork 性评估**：核心模块耦合度、剥离商业功能或云依赖的难度、代码自洽程度
- 商用条款、Trademark 政策

### K. RoadMap 与趋势

- 官方 Roadmap 文档
- 近 6 个月的 RFC / Proposal / Design Doc
- 社区讨论中的争议点（争议点常预示未来方向，比"已决定做什么"信息量更大）
- 竞品之间的相互响应（A 出了什么 feature，B 是否跟进）

### L. 已知缺陷与局限

信息来源优先级：GitHub issue（label: bug / limitation）> 用户博客 / 事故复盘 > Slack / Discord > 官方 FAQ。

这一项通常比官方文档更能反映真实落地情况。

---

## 2. 候选产品清单

按调研优先级分三档。整体策略：**事实标准 + 主流新秀 + 差异化路线 + 基准对照**。

### Tier 1：必调研（5 个）

#### 1. Apache Iceberg REST Catalog Spec + 参考实现

- **为什么必看**：它不是一个产品，而是 **事实标准协议**。其他所有 Iceberg 生态 Catalog 都在围绕这个 spec 做实现或兼容。不吃透它，后面产品间的"兼容性"对比无从谈起。
- 关键资料：`iceberg/open-api/rest-catalog-open-api.yaml`、JdbcCatalog / RESTCatalog / HiveCatalog 参考实现源码
- 调研产出：**一份 REST Spec 逐 endpoint 的能力清单**，作为后续产品对比的 checklist

#### 2. Apache Polaris（Snowflake 捐赠给 ASF）

- **定位**：面向 Iceberg 的开放 REST Catalog，强调开放互操作与细粒度访问控制
- 看点：多引擎互操作设计、RBAC 模型、Credential Vending、是否保持与 Snowflake 商业版的同源代码
- 风险点：项目较新，生产规模案例有限

#### 3. Unity Catalog（Databricks 开源版本）

- **定位**：统一 Table / Volume / Function / Model / AI Asset 的多模态 Catalog
- 看点：**一等公民抽象最丰富**（尤其关注 AI/ML 资产建模）；Delta Sharing 集成；与 Databricks 商业版的差异
- 风险点：OSS 版与商业版功能差距较大，需看清边界；治理模式不是基金会

#### 4. Apache Gravitino（原 Datastrato，已进 ASF）

- **定位**：**联邦式**元数据湖仓，不只管 Iceberg，也统一 HMS、JDBC、File、Model 等多种 Catalog
- 看点：多源统一建模、Catalog-of-Catalogs 架构、与 Iceberg REST Catalog 的关系
- 风险点：抽象层较厚，性能与复杂度需验证

#### 5. Project Nessie（Dremio）

- **定位**：**Git-like** 数据版本控制 Catalog（branch、tag、merge、commit）
- 看点：Git 语义如何落到表元数据上、跨表事务实现、与 Iceberg REST 的兼容演进
- 风险点：Git-like 语义的心智成本、生态主要靠 Dremio 推动

### Tier 2：应调研（2 个）

#### 6. Hive Metastore（HMS）

- **为什么看**：所有新 Catalog 都要回答"如何兼容 HMS"。它是 **兼容性基准** 和 **反面教材**（Thrift 协议、单点瓶颈、扩展性差）的集合体
- 调研聚焦：协议、已知痛点、各家的替代方案

#### 7. LakeKeeper

- **为什么看**：Rust 实现的 Iceberg REST Catalog，代码量小、设计干净，是 **阅读 REST Catalog 实现** 的理想样本
- 调研聚焦：架构简洁性、性能、授权模型（集成 OpenFGA）

### Tier 3：参考对照（3 个，了解即可）

#### 8. AWS Glue Data Catalog

- 闭源但其 API 是云上事实标准之一；看其数据模型与收费模式即可

#### 9. Netflix Metacat

- 较老且活跃度低，但其"联邦 Catalog"思想早于 Gravitino，可作思想源流参考

#### 10. Apache Paimon（原 Flink Table Store）

- **补充理由**：作为新兴表格式，其 Catalog 层设计（如原生对接 Flink/Spark 的 Catalog API）对理解"表格式与 Catalog 的边界"有直接帮助，可作为功能边界对照
- 调研聚焦：Catalog API 设计、与 Iceberg REST Catalog 的能力差异

### 候选产品一览

| # | 产品 | 档位 | 技术路线 | 背后力量 | License |
|---|---|---|---|---|---|
| 1 | Iceberg REST Spec | T1 | 协议标准 | ASF / 社区 | Apache 2.0 |
| 2 | Apache Polaris | T1 | Iceberg REST | Snowflake → ASF | Apache 2.0 |
| 3 | Unity Catalog OSS | T1 | 多模态统一 | Databricks | Apache 2.0 |
| 4 | Apache Gravitino | T1 | 联邦元数据 | Datastrato → ASF | Apache 2.0 |
| 5 | Project Nessie | T1 | Git-like | Dremio | Apache 2.0 |
| 6 | Hive Metastore | T2 | 传统元数据 | ASF | Apache 2.0 |
| 7 | LakeKeeper | T2 | Iceberg REST (Rust) | 创业团队 | Apache 2.0 |
| 8 | AWS Glue | T3 | 云上标准 | AWS | 闭源 |
| 9 | Netflix Metacat | T3 | 联邦（早期） | Netflix | Apache 2.0 |
| 10 | Apache Paimon | T3 | 流批一体表格式 | ASF | Apache 2.0 |

> 注：上表中 License 信息基于公开资料，正式调研时需逐项核实，尤其关注 Trademark 与附加商业条款。

---

## 3. 调研方法与产出物

### 3.1 分层产出

1. **横向对比矩阵**——一张表，覆盖维度 E（功能）、G（生态）、J（License），快速筛选
2. **逐产品 Deep-Dive**——Tier 1 每个一份，覆盖 A–L 全维度；每份末尾增加 **"设计启示"** 小节，映射到自研项目的待定决策
3. **协议兼容性清单**——基于 Iceberg REST Spec 的 endpoint 级对照（含 AI/ML 扩展点预留）
4. **决策启示录**——从调研反推自研项目的设计取舍（特别关注：是否做 Git-like、是否做跨表事务、是否将 AI Asset 作为一等公民、存储优化调度是否放在 Catalog 层）

### 3.2 信息来源优先级

官方 Design Doc / RFC > 官方文档 > 源码 > 维护者博客 > Conference Talk > 第三方对比文章 > 二手资讯

### 3.3 建议时间盒

| 阶段 | 产出 | 建议时长 |
|---|---|---|
| Spec 吃透 | Iceberg REST Spec 能力清单 | 3–5 天 |
| 横向矩阵 | 10 个产品的维度 A、D、E、J 填充 | 1 周 |
| Tier 1 深挖 | 5 份 deep-dive | 2–3 周 |
| 综合与决策 | 决策启示录 + 自研项目设计初稿 | 1 周 |

**总计约 5–6 周**。如需压缩，可砍掉 Tier 2 / T3，并把 Tier 1 深挖合并为横向章节对比。

---

## 4. 调研约束与背景（已确认）

1. **允许对现有项目做 fork 或深度二次开发**——License 与代码可 fork 性权重提升。
2. **调研成果仅自用**——作为后续需求分析和设计的起点，产出形态以结构化 Markdown + 关键源码引用为主，无需对外包装。
3. **自研项目关键决策点尚未确定**——调研需承担"帮助收敛决策"的作用，每份 Deep-Dive 末尾需输出"设计启示"。

---

*文档版本：v1.0 / 2026-04-16*
