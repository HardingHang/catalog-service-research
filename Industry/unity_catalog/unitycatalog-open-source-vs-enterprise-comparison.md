# Unity Catalog 开源版与 Databricks 企业版功能差异对比

更新时间：2026-04-19

## 1. 对比范围

本文中的“开源版 Unity Catalog”指：

- 本地项目文档 [unitycatalog-project-analysis.md](./unitycatalog-project-analysis.md)
- 开源仓库 `unitycatalog` 当前公开代码与文档

本文中的“企业版 Unity Catalog”指：

- Databricks 官方文档中描述的 Unity Catalog 产品能力

本文同时参考论文 *Unity Catalog: Open and Universal Governance for the Lakehouse and Beyond*，但需要特别注意：

- 论文描述的是 Unity Catalog 的目标架构、关键机制与 Databricks 产品能力边界
- 论文明确说明，文中部分功能“尚未在开源实现中提供”
- 因此不能把论文里的全部能力直接等同为开源版现状

## 2. 结论先行

- 两者的**架构方向是一致的**：都围绕统一对象模型、统一治理控制面、开放接口、多资产治理、credential vending、one-asset-per-path 等核心思想展开。
- 开源版当前更像一个**开放的基础治理内核**：对象模型、权限体系、外部位置、临时凭证、多引擎接入、模型注册、函数、基础 UI/CLI/SDK 都已经具备。
- Databricks 企业版则是在这个内核之上，提供了**更完整的产品化治理能力**：ABAC、row filters、column masks、Catalog Explorer、自动 lineage、audit/system tables、data quality monitoring、data classification、connections/federation、Delta Sharing、Marketplace、Clean Rooms、跨工作区模型治理等。
- 如果一句话概括差异：
  开源版已经能做“开放 catalog + 基础治理 + 引擎集成”，企业版做的是“完整的数据与 AI 治理平台”。

## 3. 为什么会有这种差异

论文本身已经给出边界说明：

- 论文指出，UC 的核心 API、server、client 自 2024 年 6 月起开源，并“当前支持 tables 和 ML models 等关键资产类型”。
- 论文同时明确写到：“Some functionality described here is not yet available in the UC open-source implementation.”
- 论文进一步说明，缺口可能来自三类原因：
  - 计划未来再开源
  - 依赖尚不存在的开放标准，例如 FGAC 所需的 trusted engine 标准
  - 依赖 Databricks 内部基础设施，例如缓存、托管控制面、系统化观测能力

所以，开源版与企业版不是“两个完全不同的产品”，而是“同一架构路线上的不同完成度层级”。

## 4. 一级分类总览

| 一级能力域 | 开源版 Unity Catalog | Databricks 企业版 Unity Catalog | 差异判断 |
| --- | --- | --- | --- |
| 核心对象模型与资产类型 | 已支持 tables / files / functions / AI models，多格式、多引擎、开放 API | 同方向，但对象体系更完整，包含 connections、shares、service credentials、clean rooms 等更多一等对象 | 企业版更完整 |
| 基础权限与身份体系 | 已有 Permission API、SCIM2、OAuth token exchange、JCasbin 授权 | 同方向，且与 Databricks 账户级身份、工作区、服务主体体系深度整合 | 企业版更产品化 |
| 细粒度治理 | 论文包含 FGAC/ABAC 设计，但开源 roadmap 中 row filters / column masks / ABAC 仍为 `❓` | 官方已提供 row filters、column masks、ABAC、governed tags | 企业版显著领先 |
| 存储治理与凭证 | 已有 external locations、storage credential、temporary credential vending | 额外提供 service credentials、外部访问显式权限、更多云侧治理策略 | 企业版显著领先 |
| 发现、搜索、血缘、审计 | 开源分析文档强调治理内核，但 roadmap 里 lineage 仍为 `❓`，未见等价系统表/产品化搜索能力 | Catalog Explorer、lineage graph、audit logs、system tables、data quality、data classification | 企业版显著领先 |
| 开放接口与生态接入 | 这是开源版强项：Hive metastore API、Iceberg REST API、Spark / Trino / DuckDB 等集成 | 企业版同样开放，但增加了 external access、connections、query federation、catalog federation 等托管能力 | 两者都强，但侧重点不同 |
| 对外共享与协作 | 未见内建 Marketplace / Clean Rooms / UC 原生 share 体系 | Delta Sharing、Marketplace、Clean Rooms、跨账户/跨云分享 | 企业版基本独有 |
| AI / ML 治理 | 已支持 models、functions、MLflow registry 对接 | 进一步支持 model lineage、Catalog Explorer UI、跨工作区访问、Delta Sharing 模型分享、Feature Store 等 | 企业版显著领先 |
| 部署与运维 | 自托管、Docker、数据库自配，开放但需要自己运维 | Databricks 托管控制面、内建缓存/系统表/服务化体验 | 取舍不同，不只是功能差异 |

## 5. 二级详细对比

### 5.1 核心对象模型与资产类型

开源版已具备：

- 仓库 README 明确将开源 UC 定义为 “Open, Multimodal Catalog for Data & AI”。
- README 明确列出：
  - 支持 Delta Lake、Iceberg、Hudi via UniForm、Parquet、JSON、CSV 等格式
  - 支持 tables、files、functions、AI models
  - 兼容 Hive Metastore API 与 Iceberg REST Catalog API
- `quickstart.md` 已给出通过 MLflow 管理 registered model 的流程，说明“模型”不是概念占位，而是实际支持的对象类型。

企业版增强点：

- 官方对象模型文档把 `tables / views / volumes / functions / models` 作为三层命名空间内资产。
- 同时把 `storage credentials / external locations / connections / shares` 明确列为 metastore 级 securable objects。
- 企业版还进一步把 clean room 等协作对象纳入 Unity Catalog 体系。

差异判断：

- 在“基础资产类型”上，开源版已经不是传统 metastore，而是数据与 AI 资产目录。
- 但在“企业级对象体系完整度”上，Databricks 产品版明显更宽，尤其是 metastore 级治理对象更丰富。

### 5.2 权限模型、身份集成与治理控制面

开源版已具备：

- 本地分析文档已经总结出开源版具备：
  - Permission API
  - SCIM2 用户接口
  - OAuth token exchange
  - JCasbin 授权模型
- 这说明开源版的授权与身份不是外围插件，而是内置核心能力。

论文中的统一治理设计：

- 论文把 UC 定义为统一治理控制面，而不是仅仅管理 table metadata。
- 论文明确把以下内容定义为所有资产类型都应共享的核心能力：
  - ownership / privilege grants
  - tag assignment
  - ABAC rules
  - fine-grained access control policies
  - credential governance
  - audit logging

企业版增强点：

- 企业版不只是“有授权 API”，而是把身份、工作区、跨工作区对象访问、Catalog Explorer、system tables、sharing、clean rooms 等都接到统一控制面之下。
- 这使得企业版的治理不只是“权限判断”，而是“权限 + 发现 + 审计 + 协作 + 运营”的统一产品面。

差异判断：

- 开源版已经具有治理控制面的雏形和骨架。
- 企业版则把这个骨架扩展成了完整的平台级治理面。

### 5.3 细粒度治理：ABAC、row filters、column masks

这是两者最明显的差异之一。

开源版现状：

- 论文在设计上明确包含 FGAC 与 ABAC。
- 但论文也明确说明，文中的一部分功能尚未在开源实现中提供。
- 开源仓库 `roadmap.md` 中，以下能力仍然是 `❓`：
  - Row level filters
  - Column level masks
  - ABAC
  - Lineage

企业版现状：

- 官方文档已提供：
  - ABAC
  - governed tags
  - row filters
  - column masks
- ABAC 官方文档说明，企业版使用 `governed tags + policies + UDFs` 来做动态属性访问控制。
- `row filters` 用于按行过滤可见数据。
- `column masks` 用于按身份、角色或属性对列值做脱敏。

需要注意的时间边界：

- 截至 2026-04-19，Databricks 官方文档中 ABAC 仍标记为 `Public Preview`。
- 这说明它是企业版正式文档中的产品能力，但仍处在预览阶段，而不是一个已经完全普适 GA 的功能。

差异判断：

- 开源版在细粒度治理上目前更接近“设计与 roadmap 已明确，但实现尚不完整”。
- 企业版已经把这一层做成了可用的产品能力，这是两者最大的功能鸿沟之一。

### 5.4 存储治理、external location 与凭证体系

开源版已具备：

- CLI 文档明确提供 `External Location Management`。
- 文档说明 external location 用于把数据实体的存储位置映射到 credentials，覆盖 external / managed tables、volumes 等。
- CLI 文档还说明：
  - OSS server 在访问 S3 路径时会 vend temporary credentials
  - volume 的外部位置也会依赖 external location 与 temporary credential

开源版当前缺口：

- `CredentialService.java` 明确写明：
  - `service credential and CREATE_SERVICE_CREDENTIAL are not supported`
- 也就是说，开源版当前主要是 storage credential / external location / temp credential 体系，而没有企业版那种独立的 service credential 体系。
- 另外，GCP 相关代码里还显式抛出：
  `Storage credential/external location for GCP is not supported yet.`
  这说明开源版的多云凭证完备度也并不完全对齐企业版。

企业版增强点：

- 官方文档区分了：
  - storage credentials
  - service credentials
  - external locations
  - connections
- `service credential` 在企业版里是独立 securable object，用于 Databricks 访问 AWS Glue、AWS Secrets Manager 等外部云服务。
- 企业版还提供更细的外部访问权限，例如：
  - `EXTERNAL USE SCHEMA`
  - `EXTERNAL USE LOCATION`
- 企业版外部访问文档还强调，这些权限必须显式授予，以避免数据外流。

差异判断：

- 两者都支持“凭证下发”这个核心思想。
- 但开源版更偏向“对象存储访问治理”。
- 企业版则扩展到了“对象存储 + 云服务 + 外部引擎访问 + 更细显式权限”的完整体系。

### 5.5 发现、搜索、血缘、审计、系统表、数据质量

这是企业版相对开源版最强的第二个差异面。

论文中的目标：

- 论文在引言里把“lack of support for data discovery”列为现有 catalog 的关键痛点之一。
- 论文同时把 `audit logging` 与 `lineage tracking` 定义为 UC 核心功能的一部分。

开源版现状：

- 本地分析文档和源码主要强调的是协议、权限、credential vending、对象模型、managed table 路径。
- 开源 roadmap 里 `Lineage` 仍为 `❓`。
- 我没有在开源仓库公开文档中找到与企业版等价的：
  - Catalog Explorer 搜索与发现体验
  - lineage graph
  - `system.access.audit`
  - `system.access.table_lineage`
  - `system.access.column_lineage`
  - data quality monitoring
  - data classification

这里有一条明确说明：

- 这是基于当前公开仓库和文档的判断。
- 更准确地说，应表述为“我没有在开源公开实现中找到与企业版等价的产品化能力证据”，而不是绝对断言“永远没有”。

企业版现状：

- 官方 Unity Catalog 总览页把以下能力直接列为 key pillars：
  - Data discovery
  - Automated lineage tracking
  - Auditing
  - Data quality monitoring
  - Secure data sharing
- Catalog Explorer 官方文档显示，企业版提供：
  - 数据与 AI 资产浏览
  - sample preview
  - entity relationships
  - external locations 管理
  - 对象权限管理
  - AI-generated comments
  - Genie Code lineage / insights
- Lineage 官方文档显示，企业版支持：
  - Catalog Explorer lineage graph
  - column-level lineage
  - job lineage
  - dashboard lineage
  - lineage system tables
- System tables 官方文档显示，企业版提供：
  - `system.access.audit`
  - `system.access.table_lineage`
  - `system.access.column_lineage`
  - `system.data_quality_monitoring.table_results`
  - `system.data_classification.results`
  - 以及 clean room events 等更多运营表
- Data quality monitoring 官方文档显示，企业版支持：
  - anomaly detection
  - freshness / completeness 监控
  - data profiling
  - 甚至可以监控 GenAI app、ML model、serving endpoint 的推理表
- Data classification 官方文档显示，企业版支持：
  - 使用 AI agent 自动识别和标注敏感数据
  - 与 governed tags / ABAC 联动

差异判断：

- 开源版更偏“治理内核”。
- 企业版已经具备“治理运营平台”属性。
- 如果用户要的是 discoverability、searchability、lineage、audit、quality、classification 一整套平台体验，当前只能从企业版获得。

### 5.6 开放接口、多引擎接入与 Federation

这一项不能简单说企业版更强，因为开源版在“开放生态”上本身就很有优势。

开源版优势：

- README 直接强调 open source API and implementation。
- README 明确兼容：
  - Hive metastore API
  - Iceberg REST catalog API
- 仓库文档明确给出多引擎集成：
  - Spark
  - Trino
  - DuckDB
- 本地分析文档也把“多引擎开放协议兼容”列为当前项目主线之一。

企业版增强点：

- 企业版不仅提供开放 API，也提供“如何把外部系统接进来”的产品化抽象。
- 官方 `Unity Catalog connections` 文档把 connection 定义为 metastore 级 securable object，并支持：
  - managed ingestion connections
  - query federation connections
  - catalog federation connections
  - JDBC connections
  - HTTP connections
- 企业版 query federation / catalog federation 明确支持把外部数据库、Hive Metastore、AWS Glue、Snowflake Horizon Catalog 等接入为 foreign catalog 或联邦访问面。
- 企业版还提供 external data access：
  - Unity REST API
  - Iceberg REST catalog
  - `EXTERNAL USE SCHEMA`
  - 对部分 Iceberg reader 的 credential vending

差异判断：

- 开源版的强项是“开放、兼容、自托管、适合作为标准 catalog 内核去接不同引擎”。
- 企业版的强项是“在开放基础上，把 federation / external access / security / managed experience 做成了完整产品”。
- 如果从开放协议角度看，开源版并不弱。
- 如果从联邦治理产品能力看，企业版明显更强。

### 5.7 Delta Sharing、Marketplace、Clean Rooms

这是企业版几乎碾压式领先的能力域。

需要先澄清一个常见误区：

- `Delta Sharing` 本身是开源协议/项目。
- 但“Databricks Unity Catalog 企业版中的分享、Marketplace、Clean Rooms 能力”并不等于“开源 Unity Catalog 仓库已经内建同等产品能力”。

开源版现状：

- 我没有在开源 Unity Catalog 仓库公开文档中找到等价的：
  - Unity Catalog 原生 share / provider / recipient 完整对象体系
  - Marketplace
  - Clean Rooms
- 因此这里的判断是：
  - 开源 Unity Catalog 本身不等于 Databricks 的共享协作产品面
  - 开源世界里可以通过 Delta Sharing 开源项目解决一部分分享问题，但那是相邻项目，不是当前 OSS UC 仓库对企业版能力的直接等价实现

企业版现状：

- Delta Sharing 官方文档说明，企业版支持：
  - Databricks-to-Databricks sharing
  - open sharing
  - share / provider / recipient 对象
  - volume / model / notebook sharing
  - sharing auditing and usage tracking
- Marketplace 官方文档说明，Databricks Marketplace 基于 Delta Sharing，可共享：
  - datasets
  - notebooks
  - AI models
  - MCP servers
- Clean Rooms 官方文档说明，企业版支持：
  - clean room securable object
  - 基于 Delta Sharing 的 no-trust 协作
  - serverless compute
  - notebook 审批
  - output tables
  - clean room events system table

差异判断：

- 对外共享、商业化分发、跨组织隐私协作，是企业版非常明确的差异化壁垒。
- 这部分不能简单理解成“企业版把开源功能包装了一下”，而是包含一整套共享协议整合、平台身份、安全审计、serverless 执行与 UI 运营能力。

### 5.8 AI / ML 与模型治理

开源版已具备：

- README 明确把 `functions` 和 `AI models` 列为支持对象。
- `quickstart.md` 已提供用 MLflow 将 Unity Catalog 作为 model registry backend 的流程。
- 本地分析文档也把项目定位为“数据与 AI 资产目录”，而不是纯表目录。

论文中的设计方向：

- 论文明确把 `AI models` 作为 UC 需要原生支持的资产类型之一。
- 论文还指出，开源部分“当前支持 key asset types such as tables and ML models”。

企业版增强点：

- 官方模型生命周期文档显示，企业版支持：
  - Catalog Explorer 中查看 model
  - model lineage
  - 跨 workspace 访问 model
  - Delta Sharing 模型分享
- Feature Store 官方文档显示，企业版进一步把：
  - feature governance
  - feature lineage
  - cross-workspace feature sharing
  - serving
  - monitoring
 统一纳入平台体验。

差异判断：

- 开源版已经覆盖“模型注册与目录治理”的基础面。
- 企业版覆盖的是“模型全生命周期治理 + 共享 + lineage + feature platform + serving/monitoring 邻接能力”的更大范围。

### 5.9 部署形态、控制面与运维责任

开源版特征：

- README 和部署文档显示，开源版支持：
  - Docker Compose
  - tarball 部署
  - 自配 MySQL / PostgreSQL 等后端数据库
- 这意味着开源版非常适合：
  - 本地研究
  - 二次开发
  - 私有控制
  - 自托管集成

企业版特征：

- 论文明确把 Databricks 产品中的 Unity Catalog service 放在 Databricks-managed control plane 中。
- 很多企业版能力还依赖内部基础设施或托管能力，例如：
  - control plane 托管
  - 缓存体系
  - system tables
  - serverless compute
  - managed federation / clean rooms / classification / monitoring

差异判断：

- 这不是单纯的“功能多几个按钮”。
- 而是“开源可控、自托管”与“企业托管、平台化能力更强”之间的典型取舍。

## 6. 哪些能力属于“开源已有基础版，企业版更完整”

这类能力最容易被误判成“开源和企业完全一样”。

- 对象模型与统一命名空间
- 权限与授权骨架
- external location / storage credential / temporary credential vending
- 模型对象与 MLflow registry 基础集成
- 开放 API 与多引擎接入
- 数据与 AI 资产统一 catalog 的整体方向

更准确的理解应该是：

- 开源版已经把“平台地基”铺出来了。
- 企业版则在这个地基上继续叠加了更多企业治理和产品化楼层。

## 7. 哪些能力更接近“企业版独有”或“企业版明显领先”

- ABAC / governed tags
- row filters / column masks
- Catalog Explorer 的完整产品面
- 自动 lineage graph + system tables
- 审计系统表与运营视角
- data quality monitoring
- data classification
- service credentials
- connections / query federation / catalog federation 的完整产品抽象
- Delta Sharing 与 Unity Catalog 的深度整合
- Marketplace
- Clean Rooms
- 跨工作区模型治理、模型分享、Feature Store 深度集成

## 8. 对选型的实际含义

如果你的目标是下面这些场景，开源版已经很有价值：

- 想研究 UC 架构与协议设计
- 想要一个开放、可自托管的 catalog 内核
- 想对接 Spark / Trino / DuckDB / Iceberg REST / Hive API
- 想做基础的数据与 AI 资产目录
- 想自己掌控 server、DB、鉴权与二次开发

如果你的目标是下面这些场景，企业版优势会非常明显：

- 需要企业级 discoverability、lineage、audit、system tables
- 需要统一的 ABAC / tags / row-level / column-level 治理
- 需要跨组织数据分享、商业化分发或 Clean Room 协作
- 需要 Catalog Explorer、质量监控、敏感数据分类、模型跨工作区治理
- 需要把 catalog 真正作为企业数据平台治理控制面来运营

## 9. 企业版和开源版的共同缺口

前面的对比强调了企业版明显领先的部分，但这不代表企业版已经“无缺口”。从论文、源码和官方文档交叉看，仍有一些能力是两边都没有完全解决，或者都没有形成一个通用、完备、开放的最终形态。

### 9.1 面向任意外部引擎的通用 FGAC 标准仍不完整

论文虽然把 `row filters`、`column masks`、`ABAC` 纳入了 UC 的核心治理设计，但也明确指出，某些能力在开源实现中尚未提供，而且有些能力依赖缺失的开放标准，例如：

- `a "trusted engine" standard for FGAC`

Databricks 企业版虽然已经提供了 row filters、column masks 和 ABAC，但从官方限制可以看出，这套能力还没有扩展成“任意开放接口 + 任意外部引擎都能一致执行”的通用治理标准。例如：

- 带有 `row filters` 或 `column masks` 的表，不能通过 `Iceberg REST catalog` 或 `Unity REST API` 访问
- path-based access 对带策略的表也不支持

因此，当前更准确的判断是：

- 企业版已经在 Databricks 自身受控执行环境中实现了 FGAC
- 但“跨所有外部引擎都统一可用的 FGAC 开放标准”仍然没有完全成型
- 开源版在这一层更是明显还未完成

### 9.2 真正全自动、全覆盖、无损的端到端 lineage 仍不存在

企业版已经具备自动 lineage、lineage graph 和 lineage system tables，但官方文档同时明确列出了很多边界：

- lineage 只有在“能够推断”的情况下才会产生记录
- lineage 只保留一年滚动窗口
- rename 后 lineage 不保留
- `RDD` 不被捕获
- global temp views 不被捕获
- checkpoint 场景不被捕获
- 一些 pipeline / UDF / path 引用场景下列级 lineage 不完整

对于 Databricks 外部系统的 lineage，企业版也没有“自动接入器/自动爬虫”：

- 官方 FAQ 明确写明：`No, external lineage is not captured automatically.`

这意味着：

- 开源版缺少企业版那套自动 lineage 产品面
- 企业版虽然更强，但离“全系统、全资产、全链路、全自动、零损失”的 lineage 仍有距离

### 9.3 统一开放接口下的全资产读写对称性仍不完整

从企业版官方 external access 文档看，当前开放接口仍然是分裂的：

- `Unity REST API` 主要支持 Delta 的直接读
- `Iceberg REST catalog` 支持 Iceberg 的读写，以及部分 Delta 的只读场景

同时还有更多细节限制：

- `Foreign Iceberg` 只能读不能写
- `Foreign Iceberg` 不支持 credential vending
- 某些治理能力和外部访问能力彼此之间也存在组合限制

这说明当前无论企业版还是开源版，都还没有实现一个真正意义上的：

- 单一开放接口
- 覆盖全部 UC 资产类型
- 覆盖全部表类型
- 同时保持完整治理语义
- 且具备完整读写对称性

企业版在这一方向上已经走得更远，但仍不是“任何对象都能通过统一开放协议等价读写”。

### 9.4 “可调的 catalog 级缓存控制面”没有公开产品化

你前面举的“性能加速、多级缓存”是一个很好的例子，但这里需要区分两层含义：

- 是否内部使用了缓存
- 是否对用户公开提供了可调的缓存控制面

论文已经明确说明，Databricks 版 UC 在性能层面有：

- in-memory caching
- service 侧与 compute engine 侧的多级缓存
- 临时凭证缓存
- 甚至借助内部缓存基础设施来提升可靠性

所以“企业版没有缓存”这个判断是不成立的。

但如果把问题换成：

- 用户能不能像调数据库那样，显式配置 UC 的多级缓存策略、失效策略、缓存层级、容量和刷新行为？

那么截至我本次检索到的官方文档：

- 我没有找到企业版对外公开的 `UC cache hierarchy / cache policy / cache invalidation` 产品控制面
- 开源版也没有形成等价的成熟对外能力

因此更准确的结论是：

- 双方都不是“没有缓存”
- 双方都没有把“catalog 级缓存控制面”作为清晰的公开产品能力暴露出来

这一点里，“没有公开能力证据”是事实；“内部一定完全没有”则不是事实。

## 10. 最终判断

最准确的说法不是：

- “开源版是企业版的精简版”

而是：

- “开源版已经开出了 UC 的基础架构、开放接口与核心治理骨架；企业版则把这个骨架扩展成了 Databricks 数据与 AI 平台上的完整治理产品。”

从论文、源码和官方文档三方交叉看，最重要的结论有三点：

1. 架构思想一致，差异主要在完成度与产品化深度。
2. 最大差距集中在细粒度治理、可观测性/可发现性、共享协作和企业平台运营能力。
3. 开源版的真正价值不是“复制企业版全部功能”，而是提供一个开放、可扩展、可自托管的统一 catalog 核心。

## 11. 参考来源

本地与开源材料：

- [开源分析文档](./unitycatalog-project-analysis.md)
- [开源仓库 README](../README.md)
- [开源 roadmap](../roadmap.md)
- [CLI 使用文档](./usage/cli.md)
- [模型 quickstart](./quickstart.md)
- [部署文档](./server/deployment.md)

论文：

- [Unity Catalog: Open and Universal Governance for the Lakehouse and Beyond](https://www.databricks.com/sites/default/files/2025-06/unity-catalog-open-universal-governance-lakehouse-beyond.pdf)

Databricks 官方文档：

- [What is Unity Catalog?](https://docs.databricks.com/aws/en/data-governance/unity-catalog/)
- [Unity Catalog attribute-based access control (ABAC)](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac)
- [Row filters and column masks](https://docs.databricks.com/gcp/en/data-governance/unity-catalog/filters-and-masks/)
- [What is Catalog Explorer?](https://docs.databricks.com/gcp/en/catalog-explorer/)
- [View data lineage using Unity Catalog](https://docs.databricks.com/gcp/en/data-governance/unity-catalog/data-lineage)
- [System tables reference](https://docs.databricks.com/aws/en/admin/system-tables)
- [Lineage system tables reference](https://docs.databricks.com/aws/en/admin/system-tables/lineage)
- [Data quality monitoring](https://docs.databricks.com/aws/en/data-governance/unity-catalog/data-quality-monitoring)
- [Data Classification](https://docs.databricks.com/gcp/en/data-governance/unity-catalog/data-classification)
- [View data lineage using Unity Catalog](https://docs.databricks.com/aws/en/data-governance/unity-catalog/data-lineage)
- [Bring your own data lineage](https://docs.databricks.com/aws/en/data-governance/unity-catalog/external-lineage)
- [Unity Catalog connections](https://docs.databricks.com/aws/en/connect/uc-connections)
- [Enable external data access to Unity Catalog](https://docs.databricks.com/aws/en/external-access/admin)
- [Access Databricks tables from Apache Iceberg clients](https://docs.databricks.com/aws/en/external-access/iceberg)
- [Create service credentials](https://docs.databricks.com/aws/en/connect/unity-catalog/cloud-services/service-credentials)
- [What is Delta Sharing?](https://docs.databricks.com/aws/en/delta-sharing)
- [What is Databricks Marketplace?](https://docs.databricks.com/aws/en/marketplace/)
- [What is Databricks Clean Rooms?](https://docs.databricks.com/aws/en/clean-rooms/)
- [Manage model lifecycle in Unity Catalog](https://docs.databricks.com/aws/en/machine-learning/manage-model-lifecycle)
- [Databricks Feature Store](https://docs.databricks.com/en/machine-learning/feature-store/index.html)
- [Unity Catalog managed tables in Databricks for Delta Lake and Apache Iceberg](https://docs.databricks.com/aws/en/tables/managed)
