# Apache Polaris 调研报告

## A. 定位与设计目标

### 项目起源
- **发起方**：Snowflake（捐赠方）
- **捐赠路径**：2024年Snowflake将Polaris项目捐赠给Apache软件基金会（ASF），目前处于Apache孵化器项目状态
- **最新版本**：1.3.0-incubating（2026年1月发布）

### 初始痛点
- Snowflake需要开放、互操作的Iceberg REST Catalog标准
- 解决私有商业Catalog锁定问题，让用户能在多引擎间自由迁移

### 目标用户与典型场景
- **目标用户**：需要在多计算引擎（Spark、Flink、Trino等）间共享Iceberg表的企业
- **典型场景**：lakehouse架构的统一元数据管理、跨引擎数据访问、细粒度权限控制

### 设计目标
| 做 | 不做 |
|---|---|
| 完整实现Iceberg REST API | 不支持非Iceberg格式（Delta/Hudi/Paimon） |
| 多引擎互操作 | 不提供自己的查询引擎 |
| 细粒度RBAC + Credential Vending | 不做数据目录/治理类功能 |
| 开放、可审计、安全 | 不绑定特定云厂商 |

### 差异化主张
- **唯一来源**：ASF旗下唯一由商业公司（Snowflake）捐赠并维护的Iceberg REST Catalog
- **与Nessie对比**：Polaris更轻量，专注表元数据；Nessie强调Git-like版本控制
- **与Unity Catalog对比**：Polaris更聚焦Iceberg，Unity是更大一号的统一Catalog

---

## B. 核心概念与元数据模型

### 顶层抽象层次
```
Catalog
  └── Namespace (支持嵌套层级)
        └── Table
        └── View (Polaris Evolution工具支持)
```

### 元数据模型特点
- **Catalog类型**：
  - **Internal**：Polaris管理的Catalog
  - **External**：外部Catalog（如Snowflake、AWS Glue）仅在Polaris中注册元数据指针
- **Storage Configuration**：创建IAM实体用于连接云存储（S3/GCS/Azure）
- **Realm管理**：多Realm支持，逻辑隔离不同业务域

### 表格式支持
- **仅支持Apache Iceberg**（v1/v2）
- Delta、Hudi、Paimon支持在Issue #4121中被列为 enhancement需求
- 支持**Iceberg REST Federation**和**Hive Metastore Federation**

### 可扩展性
- 支持自定义entity/property（通过Entity级联）
- 策略框架（Policy Framework）支持定制化授权逻辑
- 支持OPA（Open Policy Agent）集成作为外部Policy Decision Point

---

## C. 架构与关键设计

### 分层结构
```
┌─────────────────────────────────────────┐
│  API Layer (REST API - OpenAPI Spec)   │
├─────────────────────────────────────────┤
│  polaris-core (实体定义 + 核心业务逻辑) │
├─────────────────────────────────────────┤
│  Runtime Modules                        │
│    ├── Admin Tool                       │
│    ├── Quarkus Server                   │
│    └── Service Packages                 │
├─────────────────────────────────────────┤
│  Persistence Modules (JDBC/MongoDB)    │
└─────────────────────────────────────────┘
```

### 存储层
| 组件 | 技术选型 |
|---|---|
| 元数据持久化 | JDBC (PostgreSQL/MySQL等) + MongoDB |
| 表数据存储 | S3/Ozone/MinIO/GCS/Azure/RustFS |

### 一致性模型
- **单表原子提交**：依赖Iceberg的乐观锁/ACID语义
- **跨表/跨Catalog事务**：**不支持**（差异点之一）
- 冲突解决：依赖Iceberg REST API的幂等性设计

### 扩展机制（可插拔）
- **Catalog Provider**：支持Internal/External/Snowflake/AWS Glue类型
- **Authenticator**：支持OAuth2、Kerberos、外置IdP（Keycloak）
- **Policy Engine**：支持内置RBAC + 外部OPA集成
- **Event Hook**：Issue #4227显示正在增加namespace CRUD事件持久化

### 部署形态
- **单体模式**：默认，适合小规模部署
- **Kubernetes**：Helm Chart部署，支持生产级配置
- **多租户**：通过Realm实现逻辑隔离
- **Serverless**：不支持（差异化于云原生方案）

### 缓存策略
- 元数据读取：依赖Iceberg REST API标准
- 存储凭证：短期临时凭证（Credential Vending）

### 存储优化调度架构
- **设计定位**：Polaris 采用"**引擎侧自主**"策略，Catalog 层不统一编排存储优化
- **Compaction/Clustering**：完全由引擎侧（如 Spark、Flink）自行决定触发时机
  - Spark：通过 `OPTIMIZE` 命令触发 data rewriting
  - Flink：通过 Flink Table Store 的 compaction 机制
- **统计信息收集**：
  - Polaris 元数据中存储文件级统计（manifest 中的 Min-Max）
  - 但**不主动扫描或收集**列级 NDV、Bloom Filter 等高级统计
  - 统计信息由写入引擎在 commit 时写入 Iceberg metadata
- **数据布局优化**（Z-Ordering/Liquid Clustering）：由引擎侧执行，Catalog 仅存储元数据指针
- **结论**：Polaris 在存储优化上保持轻量，遵循"Catalog 只管元数据，不管数据文件"原则

---

## D. 协议与接口

### 对外协议
| 协议 | 兼容程度 |
|---|---|
| **Iceberg REST Catalog Spec** | ✅ 完整实现 |
| **HMS Thrift** | ❌ 不支持 |
| **自定义REST** | ✅ 基于OpenAPI规范 |
| **gRPC** | ❌ 不支持 |
| **JDBC** | ❌ 不支持 |

### SDK覆盖
| SDK | 状态 |
|---|---|
| Java | ✅ 原生支持 |
| Python (PyIceberg) | ✅ 兼容（Issue #4206显示有bug） |
| Go | ⚠️ 社区支持 |
| Rust | ⚠️ 社区支持 |

### 认证协议
- **OAuth2**：支持
- **OAuth2 with JWT**：支持
- **Kerberos**：不支持
- **PAT (Personal Access Token)**：支持
- **SigV4**：用于S3等对象存储访问

### Credential Vending
- ✅ Polaris核心特性
- 创建Storage Configuration时自动创建IAM角色
- 查询时下发临时凭证，无需直接暴露云存储密钥

---

## E. 功能矩阵

| 能力域 | 考察点 | Polaris支持情况 |
|---|---|---|
| **表格式支持** | Iceberg v1/v2 | ✅ 完整 |
| | Delta/Hudi/Paimon | ❌ 不支持（#4121 enhancement） |
| **Schema演化** | 列增删/重命名/类型提升 | ✅ REST API支持 |
| | Hidden Partitioning | ✅ |
| | Liquid Clustering | ⚠️ 取决于引擎 |
| **版本管理** | Time Travel | ✅ Iceberg原生支持 |
| | Snapshot隔离 | ✅ |
| | Branch/Tag | ❌ 不支持（vs Nessie） |
| | WAP模式 | ⚠️ 引擎侧实现 |
| **存储优化** | Clustering/Compaction | ❌ Catalog不统一编排，由引擎侧自主触发 |
| | 数据布局优化（Z-Ordering/Liquid） | ❌ 引擎侧执行 |
| | 统计信息收集 | ⚠️ 文件级manifest存储，不主动扫描列级NDV/Bloom |
| | Puffin文件 | ❌ 不支持 |
| **查询加速** | File Skipping | ✅ Iceberg内置 |
| | Metadata Index | ❌ 不支持 |
| | 结果缓存 | ❌ 不支持 |
| **权限治理** | RBAC | ✅ 内置 |
| | ABAC | ⚠️ 需OPA集成 |
| | 列级/行级权限 | ⚠️ 基础RBAC支持 |
| | Dynamic Masking | ❌ 不支持 |
| **Policy引擎** | 内置 | ✅ 基础RBAC |
| | 外部OPA | ✅ 集成支持 |
| **血缘** | 表级/列级 | ❌ 不支持（数据目录功能） |
| **审计** | 操作日志 | ✅ 基础 |
| **搜索发现** | 关键词/语义 | ❌ 不支持（数据目录功能） |
| **数据质量** | 规则/校验 | ❌ 不支持 |
| **视图** | View支持 | ✅ |
| **跨表事务** | 跨Catalog事务 | ❌ 不支持 |
| **AI/ML集成** | Feature Store | ❌ 不支持（数据目录功能） |
| | Model Registry | ❌ 不支持（数据目录功能） |
| | Vector Search | ❌ 不支持 |
| | MLflow集成 | ❌ 不支持 |
| | LangChain/Kubeflow | ❌ 不支持 |
| | 与 Iceberg REST 的 AI Extension | ⚠️ 社区讨论中 |
| **数据共享** | Delta Sharing | ❌ 不支持 |
| | Iceberg REST跨组织 | ✅ |

**评分**：Polaris在Iceberg REST Catalog功能上相对完整，但在AI/ML资产治理、跨表事务、高级权限控制方面存在明显空白。

---

## F. 非功能特性

### 性能
- **元数据QPS**：无公开基准数据
- **超大规模场景**：无生产规模案例（风险点）
- **REST Catalog高并发**：Issue #4206显示在purging tables时有并发异常

### 可用性
- **HA方案**：未公开
- **Failover RTO/RPO**：未公开

### 可扩展性
- **水平扩展**：未提供分布式部署模式
- **状态管理**：Realm级别隔离

### 可观测性
- ✅ **Prometheus + Jaeger** 开箱即用
- 指标：基础JVM/HTTP指标
- Tracing：OpenTelemetry集成

### 安全
| 特性 | 支持 |
|---|---|
| 传输加密 (TLS) | ✅ |
| 静态加密 | 取决于后端 |
| 密钥管理 | 外部IAM |
| 审计日志 | ✅ 基础 |
| SOC2/GDPR | 未声明 |

### 多租户隔离
- **逻辑隔离**：✅ Realm级别
- **物理隔离**：❌ 单体架构
- **Quota控制**：未公开

---

## G. 生态与集成

### 计算引擎
| 引擎 | 兼容性 |
|---|---|
| Apache Spark | ✅ 官方支持 |
| Apache Flink | ✅ |
| Trino | ✅ |
| StarRocks | ✅ |
| Apache Doris | ✅ |
| Dremio OSS | ✅ |
| Presto | ⚠️ 需验证 |
| Snowflake | ⚠️ External Catalog模式 |
| DuckDB | ⚠️ 需REST客户端 |
| ClickHouse | ⚠️ 需验证 |
| Hive | ✅ HMS Federation |

### AI/ML框架
| 框架 | 集成 |
|---|---|
| MLflow | ❌ |
| Kubeflow | ❌ |
| Ray | ❌ |
| LangChain | ❌ |
| Hugging Face | ❌ |

### 云厂商
| 云 | 状态 |
|---|---|
| AWS | ✅ S3 + IAM |
| Azure | ✅ ADLS Gen2 |
| GCP | ✅ GCS |
| 阿里云 | ❌ |
| 腾讯云 | ❌ |
| 华为云 | ❌ |

### 存储
- AWS S3 ✅
- Apache Ozone ✅
- MinIO ✅
- Google Cloud Storage ✅
- Azure Blob Storage ✅
- Ceph ✅
- RustFS ✅

### BI工具
| 工具 | 集成 |
|---|---|
| Apache Superset | ⚠️ 通过 Trino/StarRocks 间接支持 |
| Tableau | ⚠️ 通过 JDBC/REST 间接支持 |
| Power BI | ⚠️ 通过 SQL 引擎间接支持 |
| Grafana | ⚠️ 监控面板 |

### 编排工具
| 工具 | 集成 |
|---|---|
| Airflow | ⚠️ 社区 |
| Dagster | ⚠️ 社区 |

### 迁移路径
- **HMS兼容**：✅ HMS Federation扩展
- **AWS Glue**：✅ External Catalog支持

---

## H. 运维与落地成本

### 部署依赖
| 组件 | 要求 |
|---|---|
| Java | 21+ |
| Docker | 27+ |
| 数据库 | PostgreSQL 13+ / MySQL 8+ / MongoDB |
| 对象存储 | S3/Ozone/MinIO等 |
| Kubernetes | 可选（Helm） |

### 资源消耗
- **JVM堆内存**：可配置
- **磁盘**：元数据存储（较小）
- **无公开基线数据**

### 升级策略
- **滚动升级**：未声明
- **元数据Schema迁移**：无重大变更记录

### 文档完备度
| 类型 | 状态 |
|---|---|
| Getting Started | ✅ 完整 |
| Operator Guide | ✅ |
| API Reference | ✅ OpenAPI |
| Troubleshooting | ⚠️ 有限 |

### 故障排查
- 基础日志 + Prometheus指标
- Jaeger分布式追踪

---

## I. 社区与治理

### 基金会归属
- **Apache Software Foundation - Incubator项目**
- 治理模型：ASF标准PMC + Committer

### 背后商业公司
- **Snowflake**（捐赠方）
- 商业利益：推动开放标准，减少用户锁定

### 社区活跃度
| 指标 | 数据 |
|---|---|
| GitHub Stars | 1.9k+ |
| Forks | 422+ |
| Watchers | 89+ |
| Contributors | 50+（累计） |
| 最新版本 | 1.3.0-incubating (2026-01) |
| 月均 commits | ⚠️ 需从 GitHub 分析 |

### 发布节奏
- 版本号：0.x → 1.x演进中
- 2024年捐赠，2026年已到1.3.0
- 约每季度一个大版本

### Issue/PR响应
- GitHub Issues活跃（255+ open issues）
- Slack社区频道
- 邮件列表
- **平均响应时间**：无公开数据，从 Issue #4206 等看维护者响应较及时

### RFC/Design流程
- ✅ GitHub Discussions 用于设计讨论
- ✅ GitHub Issues 支持 RFC 标签
- ⚠️ 无正式 RFC 文档规范（如 Gravitino 的 ADR 流程）
- **社区争议点**：主要集中在是否支持 Delta/Hudi 等非 Iceberg 格式（Issue #4121）

---

## J. License与商业化

### 开源协议
- **Apache License Version 2.0** ✅
- 无附加商业条款（不同于BSL/Elastic License）

### 托管版/企业版
- **无商业版**：纯开源
- Snowflake使用自己的商业版本（代码同源但独立维护）
- ⚠️ **与 Snowflake 商业版的关系**：Polaris 是 Snowflake 推动的开放标准，但 Snowflake 内部使用的是高度定制化的闭源版本，不等于 Polaris 的企业版

### Fork 与二次开发
- ✅ 代码可完全 fork，无功能阉割
- ⚠️ **Snowflake 定制能力**：Snowflake 可能在其商业版本中做了额外的云集成和性能优化，但这部分未开源
- ⚠️ **Trademark 限制**：使用 "Apache Polaris" 名称需遵守 ASF Trademark 政策

### 可Fork性评估
| 因素 | 评估 |
|---|---|
| 核心模块耦合度 | 低（模块化设计） |
| 云厂商依赖 | 可配置 |
| 商业功能剥离难度 | 低（无明显阉割位） |
| 代码自洽程度 | 高 |

### Trademark
- "Apache Polaris"是ASF商标
- 引擎logo（Dremio®、StarRocks等）是各自商标

---

## K. RoadMap与趋势

### 官方Roadmap
- 无公开正式Roadmap文档
- 通过GitHub Issues和Discussion了解方向

### 近期发展动态（从Issue推断）
| Issue | 方向 |
|---|---|
| #4121 | **Delta REST Catalog API支持** |
| #4227 | Namespace CRUD事件持久化 |
| #4200 | createTableStaged幂等性支持 |
| #4112 | Per-Realm授权配置 |
| #4107 | HMS federation集成测试 |

### 竞品响应
- 对Gravitino的联邦式架构暂无直接响应
- 对Unity Catalog的AI Asset热潮保持观望
- **差异化方向**：坚持 Iceberg REST 标准优先，不追求大而全

### 社区讨论中的争议点
| 话题 | 争议 |
|---|---|
| #4121 Delta/Hudi/Paimon 支持 | 部分用户认为应该支持，团队坚持 Iceberg 优先 |
| 单体 vs 微服务架构 | 有声音希望改用微服务，但维护者认为单体更简单 |
| Storage Credential Vending | 阿里云/腾讯云用户希望扩展到更多云厂商 |

### 与竞品的相互影响
- **vs Gravitino**：两者路线不同，Polaris 专注 Iceberg REST，Gravitino 走联邦路线
- **vs Unity Catalog**：Polaris 的开放性更受社区好评，但功能宽度差距明显
- **vs Nessie**：Polaris 不做 Git-like 版本控制，差异化竞争

---

## L. 已知缺陷与局限

### 关键Bug（从GitHub Issues）
| Issue | 描述 | 严重度 |
|---|---|---|
| #4219 | listGrantsForCatalogRole返回CATALOG_ROLE实体类型错误 | 中 |
| #4206 | PyIceberg purge tables时抛出ContextNotActiveException | 中 |
| #4180 | Federated HMS catalog测试用例缺失 | 低 |
| #4156 | External Catalog 权限继承逻辑不清晰 | 中 |
| #4138 | 多 Realm 间元数据可见性问题 | 中 |
| #4119 | OAuth2 token 刷新竞态条件 | 高 |

### 设计局限
1. **不支持跨表/跨Catalog事务**（vs Nessie的Multi-table commit）
2. **不支持Branch/Tag Git-like版本控制**（vs Nessie核心特性）
3. **不支持AI/ML资产治理**（vs Unity Catalog）
4. **不支持非Iceberg格式**（Delta/Hudi/Paimon）
5. **不支持列级/行级动态Masking**
6. **不支持结果缓存**
7. **单体架构无HA方案**
8. **不支持存储优化调度**（Compaction/Clustering 由引擎侧自主）
9. **不提供列级统计信息收集**（NDV/Bloom Filter）
10. **不提供 AI/ML 扩展点**（Feature Store/Model Registry）

### 生产规模风险
- 项目较新（2024年捐赠ASF）
- 公开生产案例有限
- 无大规模（1000+表）场景验证

---

## 设计启示

基于Polaris调研，对自研Catalog项目的启示：

### 应该借鉴
1. **Iceberg REST优先策略**：Polaris完整实现REST Spec，验证了该路线的技术可行性
2. **Credential Vending模式**：安全地委托存储访问权限，值得参考
3. **Realm多租户隔离**：逻辑隔离方案轻量且有效
4. **模块化架构**：Core/API/Runtime/Persistence分离，便于定制

### 应该避免
1. **不做大而全**：Polaris聚焦Iceberg，避免过度扩展
2. **不重复造轮子**：HMS/OPA等集成优于自建

### 待决策点映射
| 决策问题 | Polaris答案 | 自研参考 |
|---|---|---|
| 是否做Git-like版本控制 | 否 | 可参考Nessie |
| 是否做跨表事务 | 否 | 需自己决策 |
| 是否做AI Asset一等公民 | 否 | 需调研Unity |
| 存储优化放哪层 | 引擎侧 | 需决策 |
| 是否基于REST Spec | 是 | **强烈建议** |
| 是否做BI工具直接集成 | 否 | Polaris通过引擎间接支持 |
| 是否做存储层优化调度 | 否 | 引擎侧自主原则 |
| 是否支持多云厂商存储 | ⚠️ 阿里/腾讯/华为云不支持 | 自研需考虑 |

---


## 总结

### 评分标准说明

| 评分维度 | 评分 | 评分依据 |
|---|---|---|
| Iceberg REST 标准合规 | ⭐⭐⭐⭐⭐ | 完整实现 Iceberg REST Catalog Spec v1，是 ASF 官方孵化项目，协议兼容性最高 |
| 多引擎互操作 | ⭐⭐⭐⭐ | 支持 Spark、Flink、Trino、StarRocks、Doris 等主流引擎，但 HMS Thrift 不支持 |
| 安全/权限 | ⭐⭐⭐ | 内置 RBAC + OPA 集成 + Credential Vending，但缺少列级动态 Masking、行级过滤 |
| AI/ML 支持 | ⭐ | 完全不支持（Feature Store、Model Registry、Vector Search 均为空白） |
| 跨表事务 | ⭐ | 仅支持单表原子提交，不支持跨表/跨 Catalog 事务（vs Nessie 的 Multi-table commit） |
| 社区成熟度 | ⭐⭐⭐ | ASF 孵化项目，Snowflake 背景，但 Stars 1.9k、Contributors 50+，规模较小 |
| 生产成熟度 | ⭐⭐ | 2024 年捐赠，仍在 1.x 版本，公开生产案例有限，无大规模验证 |

### 评分细则

| 维度 | 5分 | 4分 | 3分 | 2分 | 1分 |
|---|---|---|---|---|---|
| **Iceberg REST 标准合规** | 完整实现所有必选 API | 95%+ API 实现 | 80%+ 实现，核心功能完整 | 60%+ 实现 | <60% 或非兼容实现 |
| **多引擎互操作** | 8+ 主流引擎官方支持 | 5-7 引擎官方支持 | 3-4 引擎支持 | 1-2 引擎支持 | 单一引擎或测试阶段 |
| **安全/权限** | RBAC+ABAC+列级+行级+动态Masking+审计全 | RBAC+OPA+列级/行级部分 | RBAC+OPA 集成 | 基础 RBAC | 无权限模型或仅白名单 |
| **AI/ML 支持** | Feature Store + Model Registry + Vector + ML 框架全集成 | 2 项主要功能 | 1 项主要功能或实验性 | 社区讨论中 | 明确不支持 |
| **跨表事务** | 分布式跨 Catalog 事务 | 跨表单 Catalog 事务 | 单表 + 基础事务 | 单表乐观锁 | 无事务模型 |
| **社区成熟度** | 10k+ Stars，活跃 PMC，成熟生态 | 5k+ Stars，定期发布 | 1k+ Stars，社区活跃 | <1k Stars，小社区 | 边缘项目 |
| **生产成熟度** | 100+ 公开生产案例，HA/DR 完善 | 数十案例，HA 可用 | 数个案例，功能稳定 | 早期案例 | 实验室阶段 |

### 综合评价

**Polaris 当前定位**：一个**聚焦 Iceberg REST 标准**的轻量级 Catalog，在协议合规性和开放性上表现出色，但功能宽度和成熟度有限。

**优势**
1. **协议优先**：完整实现 Iceberg REST Spec，为多引擎互操作提供标准接口
2. **开放透明**：Apache 2.0 协议，无商业锁定，代码可完全 fork
3. **安全设计**：Credential Vending + RBAC + OPA 集成，安全性较高
4. **架构简洁**：单体架构易于部署和运维，Realm 多租户隔离轻量有效

**劣势**
1. **功能单一**：仅支持 Iceberg，AI/ML 资产、跨表事务、血缘等能力空白
2. **规模有限**：项目较新，Stars 1.9k，无大规模生产验证
3. **云厂商局限**：阿里云、腾讯云、华为云等国内云厂商不支持
4. **社区争议**：对 Delta/Hudi 支持有分歧，单体 vs 微服务架构有不同声音

**适合场景**：纯 Iceberg 生态、需求简单（仅表元数据 + 权限）、多引擎共享

**不适合场景**：需要 AI/ML 资产治理、跨表事务、Git-like 版本控制、超大规模生产

---

*调研时间：2026-04-17*

---

## 参考资料

### 官方资料
| 类型 | 链接 |
|---|---|
| 官网 | https://polaris.apache.org/ |
| GitHub 仓库 | https://github.com/apache/polaris |
| 官方文档 | https://polaris.apache.org/docs/ |
| Operator Guide | https://polaris.apache.org/docs/operator-guide/ |
| OpenAPI Spec | https://github.com/apache/polaris/blob/main/polaris-openapi.yaml |
| ASF 孵化器状态 | https://incubator.apache.org/projects/polaris.html |

### 版本与发布
| 类型 | 链接 |
|---|---|
| GitHub Releases | https://github.com/apache/polaris/releases |
| CHANGELOG | https://github.com/apache/polaris/blob/main/CHANGELOG.md |
| 版本对比 | https://github.com/apache/polaris/compare |

### 社区与讨论
| 类型 | 链接 |
|---|---|
| GitHub Issues | https://github.com/apache/polaris/issues |
| GitHub Discussions | https://github.com/apache/polaris/discussions |
| Slack 频道 | https://polarincubator.slack.com/ |
| Apache 邮件列表 | https://lists.apache.org/list.html?polaris@apache.org |

### 关键 Issue 参考
| Issue | 链接 |
|---|---|
| #4121 Delta/Hudi/Paimon 支持讨论 | https://github.com/apache/polaris/issues/4121 |
| #4227 Namespace CRUD 事件持久化 | https://github.com/apache/polaris/issues/4227 |
| #4206 PyIceberg purge tables bug | https://github.com/apache/polaris/issues/4206 |
| #4219 CATALOG_ROLE 实体类型错误 | https://github.com/apache/polaris/issues/4219 |
| #4200 createTableStaged 幂等性 | https://github.com/apache/polaris/issues/4200 |
| #4112 Per-Realm 授权配置 | https://github.com/apache/polaris/issues/4112 |
| #4107 HMS Federation 集成测试 | https://github.com/apache/polaris/issues/4107 |
| #4156 External Catalog 权限继承 | https://github.com/apache/polaris/issues/4156 |
| #4138 多 Realm 元数据可见性 | https://github.com/apache/polaris/issues/4138 |
| #4119 OAuth2 token 刷新竞态 | https://github.com/apache/polaris/issues/4119 |

### 部署与生态
| 类型 | 链接 |
|---|---|
| Helm Chart | https://github.com/apache/polaris/tree/main/polaris-helm |
| Docker 镜像 | https://hub.docker.com/r/apache/polaris |
| 与 Spark 集成 | https://github.com/apache/polaris#apache-spark |
| 与 Flink 集成 | https://github.com/apache/polaris#apache-flink |
| 与 Trino 集成 | https://github.com/apache/polaris#trino |

### 相关标准与竞品
| 类型 | 链接 |
|---|---|
| Iceberg REST Catalog Spec | https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml |
| Apache Iceberg 官网 | https://iceberg.apache.org/ |
| Apache Gravitino | https://gravitino.apache.org/ |
| Project Nessie | https://projectnessie.org/ |
| Unity Catalog OSS | https://github.com/unitycatalog/unitycatalog |
| LakeKeeper | https://github.com/Lakekeeper/lakekeeper |

### 第三方博客与演讲
| 类型 | 链接 |
|---|---|
| Snowflake 官方博客 (Polaris 发布) | https://www.snowflake.com/blog/polaris-open-source-iceberg-catalog/ |
| ASF 博客 (Polaris 捐赠公告) | https://blogs.apache.org/ |
| Data + AI Summit 演讲 | https://www.databricks.com/data-ai-summit |

---

*信息来源：Apache Polaris官方文档、GitHub仓库、ASF孵化器状态*
