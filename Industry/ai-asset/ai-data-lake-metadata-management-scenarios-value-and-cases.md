# AI 数据湖中的元数据管理：应用场景、核心价值与企业案例

## 1. 背景与问题界定

随着企业数据规模持续增长，以及 AI 应用、RAG、Agent 和智能化工作流逐步进入生产环境，传统“面向表和字段”的数据目录已难以覆盖现代数据资产治理需求。企业需要的已不只是一个供人工检索的目录系统，而是一套能够支撑 **统一发现、统一理解、统一追溯、统一治理与统一审计** 的元数据管理体系。

在传统数仓或湖仓场景中，元数据管理主要围绕 `table`、`view`、`column` 等结构化对象展开，核心目标是回答以下问题：

- 有哪些数据表
- 数据表位于哪里
- 谁可以访问这些数据表
- 表与表之间的上下游依赖关系是什么

但在 AI 时代，企业需要被统一管理的对象已经显著扩展，典型包括：

- `volume/fileset`：知识文档、训练数据集、评测集、模型工件等文件型资产
- `feature / feature_set`：推荐、风控、搜索、画像等场景中的特征定义
- `model`：训练模型、推理模型、Embedding 模型及其多版本产物
- `function / tool`：数据库查询工具、检索工具、外部 API 工具、插件能力等可执行对象
- `prompt / workflow / agent`：Prompt 模板、编排流程、企业级智能体
- `dashboard / metric / logical dataset`：业务分析面向最终用户的语义与展示对象

因此，本文所讨论的元数据管理，不再局限于“表目录管理”，而是指面向 **数据、文件、模型、特征、工具、Agent 以及业务语义对象** 的统一资产管理与治理能力。

本文重点回答三个问题：

1. 企业在什么场景下会实际使用元数据管理
2. 统一元数据管理的核心业务价值是什么
3. 为什么在 AI 时代必须将非表资产纳入 Catalog

---

## 2. 元数据管理的主要应用场景

结合公开企业案例，当前元数据管理在企业中的实际使用场景可概括为以下七类。

### 2.1 资产发现与可理解性

**场景说明：**
- 数据表、指标、文档、模型、Agent 等对象需要可搜索、可浏览、可解释
- 字段、指标、数据集需要附带业务语义、Owner、责任团队、使用说明等上下文
- 元数据平台往往还承担 single source of truth 的角色，以减少多系统各自编目带来的口径冲突

**典型案例：**
- Gorgias：[OpenMetadata](https://open-metadata.org/case-study/gorgias?utm_source=chatgpt.com)
- Hurb：[DataHub](https://datahub.com/customer-stories/hurb/)

**简要说明：**
- Gorgias 的实践表明，统一元数据平台可显著提升数据资产的搜索、文档化与语义补充能力，帮助分析人员更快找到并理解可用数据。
- Hurb 将 DataHub 作为统一可信源，用于解决多平台分散编目所导致的元数据不一致问题。

### 2.2 血缘分析与变更影响评估

**场景说明：**
- 需要统一追踪表、字段、任务、模型、Agent 等对象之间的依赖关系
- 需要在变更发布前评估影响范围，在故障发生后快速完成问题溯源
- 需要进一步识别无效链路、废弃资产和重复数据产品，以支撑平台清理与成本治理

**典型案例：**
- Funding Circle：[DataHub](https://datahub.com/customer-stories/funding-circle-turns-around-their-metadata-management/?utm_source=chatgpt.com)
- Deutsche Telekom：[DataHub](https://datahub.com/customer-stories/deutsche-telekom/)

**简要说明：**
- Funding Circle 通过统一血缘视图追踪跨系统依赖关系，降低了变更时的不可见风险。
- Deutsche Telekom 将识别 `dead pipelines` 与 `unused assets` 作为治理目标之一，说明元数据管理的价值不仅在于“看见资产”，也在于“优化资产”。

### 2.3 训练集与模型可追溯

**场景说明：**
- 需要统一管理训练集版本、标注版本、模型训练链路和评测结果
- 需要支持模型复现、模型审计以及模型来源解释
- 在 AI 场景下，元数据平台还可能进一步作为 LLM 或 Agent 的可信上下文来源

**典型案例：**
- NW（能源行业）：[OpenMetadata](https://open-metadata.org/case-study/nw?utm_source=chatgpt.com)

**简要说明：**
- NW 的实践表明，模型相关对象不应仅停留在训练平台内部，而需要与数据集、规则和治理信息一并进入统一目录，才能支撑复现、审计与后续智能治理。

### 2.4 非表资产治理

**场景说明：**
- 需要将对象存储、文档、日志、向量索引、Prompt、Workflow、Agent 等非表对象纳入统一管理
- 需要让文件型和对象型资产具备可发现、可分层、可授权、可追踪的能力
- 需要从 `table-centric` 的目录模型演进为 `asset-centric` 的统一资产模型

**典型案例：**
- Forter：[OpenMetadata](https://open-metadata.org/case-study/forter?utm_source=chatgpt.com)
- Apple：[DataHub](https://datahub.com/customer-stories/apple/)

**简要说明：**
- Forter 的案例说明，对象存储中的 `bucket`、`path` 和关键文件集如果不被正式建模，Catalog 将天然是不完整的。
- Apple 的案例则进一步说明，在 AI/ML 场景中，模型、异构数据源和策略元数据本身就是一等资产，已无法用传统表模型完整表达。

### 2.5 数据访问申请与审批工作流

**场景说明：**
- 元数据平台不仅承担“发现资产”的职责，也承担“申请访问、审批、自动授权、审计留痕”的统一入口职责
- 需要支持字段级、行级的细粒度访问控制
- 需要将访问治理与敏感数据治理、审计治理打通

**典型案例：**
- MediaMarktSaturn：[DataHub](https://datahub.com/customer-stories/mediamarktsaturn/)

**简要说明：**
- MediaMarktSaturn 在 `30+` 数据域、`7.5 PB` 数据规模和每月 `8 million` 查询量的背景下，将数据发现与访问申请整合到统一元数据平台，证明 Catalog 可以直接承接访问治理闭环。

### 2.6 指标口径统一与 Dashboard 纳管

**场景说明：**
- 需要将 dashboard、指标定义、敏感性标签、Owner 信息和底层数据源血缘统一纳管
- 需要解决“同一指标在不同团队中定义不一致”的问题
- 需要让业务用户看到的不只是报表本身，还包括其上下文与可信来源

**典型案例：**
- South32：[Microsoft](https://www.microsoft.com/en/customers/story/24642-south32-microsoft-purview-data-governance)

**简要说明：**
- South32 的经验表明，只有将 dashboard、指标定义与底层数据血缘一起纳入 Catalog，才能真正减少重复建设，并形成可复用的 trusted source。

### 2.7 逻辑数据集与数据产品治理

**场景说明：**
- 当同一份数据被复制到多个环境、多个物理表或多个平台时，需要高于物理表的统一业务抽象
- 需要用统一对象表达业务含义、分类、权限与跨环境映射关系
- 需要支撑 replicated tables 背后的统一业务视图

**典型案例：**
- Visa：[DataHub](https://datahub.com/customer-stories/visa/)

**简要说明：**
- Visa 的场景说明，企业真正需要治理的往往不是单张物理表，而是跨环境复制后的同一业务对象，因此需要 `Logical Dataset` 和 `Business Attributes` 这样的高层抽象。

---

## 3. 典型企业案例分析

下面选取六个公开案例，重点分析其真实痛点、元数据管理需求以及对统一治理和非表资产纳管的启示。

### 3.1 Airtel：超大规模数据湖向 data mesh 演进

**出处：** [DataHub](https://datahub.com/customer-stories/airtel/)

**公开可验证的痛点：**
- 集中式数据湖规模达到 `30+ PB`
- 每日作业数超过 `10,000`
- 集中式模式开始拖慢创新速度，并提升治理执行难度

**为什么推动统一元数据管理：**
- 数据域下沉到各团队后，如果没有统一 Catalog，数据产品很容易重新变成孤岛
- 因此需要在 data mesh 之上保留统一发现、统一质量、统一安全和统一血缘能力

**启示：**
- 统一元数据管理不是集中式数据湖时代的附属组件，而是大规模分域治理继续成立的前提条件

### 3.2 Deutsche Telekom：数据发现、语义解释与责任定位效率不足

**出处：** [DataHub](https://datahub.com/customer-stories/deutsche-telekom/)

**公开可验证的痛点：**
- 每月管理 `hundreds of terabytes` 数据
- 管理 `thousands of datasets`
- 覆盖 `11` 个国家
- 数据团队长期承受大量重复问询，涉及来源、含义、Owner、访问与 dashboard 映射等问题

**为什么推动统一元数据管理：**
- 问题根源不在于表数量本身，而在于资产发现、业务语义、Owner 信息和血缘信息分散在不同系统中
- 需要统一目录、业务 glossary、domain 组织和端到端 lineage

**启示：**
- 统一元数据管理解决的是“找数据、懂数据、找责任人、判断影响”的全链路问题
- 该案例还显示，元数据已被直接作为 GenAI analytics platform 的 LLM context 使用，说明 Catalog 正在成为 AI 的上下文层

### 3.3 Forter：对象存储资产如果不纳管，Catalog 就天然不完整

**出处：** [OpenMetadata](https://open-metadata.org/case-study/forter)

**公开可验证的痛点：**
- 数据横跨 Snowflake、Amazon S3 和关系型数据库等多类系统
- 企业难以持续回答“有哪些数据、应该如何建模、如何治理”
- S3 对象规模巨大，不能依赖逐文件枚举实现治理

**为什么推动统一元数据管理：**
- 只管理表资产时，S3 中的大量关键对象在目录中几乎不可见
- 需要将 `bucket`、`path`、文件集等对象纳入统一视图，并进行分层与聚合

**启示：**
- 非表资产纳管的价值不在于“目录更全”，而在于对象存储中的核心资产终于可以被正式治理

### 3.4 Apple：AI / ML 资产类型异构，传统表模型无法承载

**出处：** [DataHub](https://datahub.com/customer-stories/apple/)

**公开可验证的痛点：**
- AI/ML 环境持续快速演进
- 资产横跨 custom models、异构数据存储和复杂流水线
- 原有平台过于 rigid，难以适应不同 shape 的 entity 和快速变化的资产类型

**为什么推动统一元数据管理：**
- 需要统一表达模型、数据集、训练链路、策略、访问状态与 lineage 事件
- 需要可扩展的统一资产模型，而不是继续把所有对象附着在表模型之上

**启示：**
- AI 时代的 Catalog 必须从 `table-centric` 演进为 `asset-centric`

### 3.5 Pinterest：AI 分析 Agent 需要可信上下文，而不仅是表结构

**出处：** [Pinterest Engineering](https://medium.com/pinterest-engineering/unified-context-intent-embeddings-for-scalable-text-to-sql-793635e60aac)

**公开可验证的痛点：**
- 内部有 `100,000+ analytical tables`
- 服务 `2,500+ analytical users`
- 简单关键词匹配和表摘要已无法支撑可靠的分析推荐与 SQL 生成

**为什么推动统一元数据管理：**
- Agent 需要的不只是 schema，而是历史查询模式、join 关系、过滤方式、聚合逻辑以及治理信号
- 文中明确提到 `table tiers`、`freshness`、`documentation quality` 等治理元数据直接影响 AI 排序与推荐结果

**启示：**
- 在 AI 场景中，元数据层正在从“治理辅助系统”演进为“AI 能力底座”

### 3.6 Visa：复制数据与逻辑数据集治理问题会被规模迅速放大

**出处：** [DataHub](https://datahub.com/customer-stories/visa/)

**公开可验证的痛点：**
- 原有自建元数据平台维护成本持续升高
- 需要管理从 `thousands` 到 `hundreds of thousands of datasets` 的规模
- 需要处理 `millions of columns`
- 同一份数据常被复制到多个物理环境，用户难以判断该使用哪一份

**为什么推动统一元数据管理：**
- 仅看物理表已经无法表达业务对象，必须引入 `Logical Dataset` 等统一抽象
- 需要统一维护 classifications、definitions、access policies，并将业务元数据维护从纯工程视角中解耦

**启示：**
- 统一元数据管理应能够把“物理复制的数据”和“业务上同一个数据产品”关联起来

### 3.7 一个简化判断

如果一个对象具备以下多数特征，就不应继续被当作“附属文件”或“配置项”，而应进入统一 Catalog：

- 需要被搜索和复用
- 需要明确 Owner 和责任边界
- 需要版本管理
- 需要权限与审批
- 需要表达依赖关系
- 需要审计与回滚

这也是为什么在 AI 时代，统一元数据管理的对象会从 `table` 扩展到 `volume/fileset`、`feature_set`、`model`、`tool`、`prompt`、`agent` 等更广泛的资产集合。

---

## 4. 典型案例对比

| 企业 / 项目 | 业务背景 | 公开可验证的痛点 | 为什么需要统一元数据管理 | 非表资产纳管价值 | 链接 |
| --- | --- | --- | --- | --- | --- |
| **Airtel** | 超大规模电信数据湖，向 data mesh 演进 | 集中式数据湖达到 `30+ PB`，每天 `10,000+` 作业，集中模式开始拖慢创新并加大治理难度 | 分域治理后仍需统一发现、统一质量、统一安全与统一血缘 | 数据产品、质量断言、治理标签等对象必须进入统一目录 | [链接](https://datahub.com/customer-stories/airtel/) |
| **Deutsche Telekom** | 跨国家、跨系统的数据分析平台 | 每月 `hundreds of terabytes`、`thousands of datasets`、覆盖 `11` 国，重复咨询占用大量数据团队时间 | 需要统一目录、glossary、domain、端到端 lineage，解决“找数据、懂数据、找责任人、判影响” | 元数据直接作为 GenAI analytics platform 的 LLM context | [链接](https://datahub.com/customer-stories/deutsche-telekom/) |
| **Forter** | Snowflake + S3 + 关系库并存的风控数据环境 | 多系统并存下难以持续理解、建模和治理数据；S3 规模过大，不能逐文件枚举 | 需要将不同系统中的数据资产纳入同一元数据视图 | `bucket`、`path`、文件集等对象必须作为一等资产建模 | [链接](https://open-metadata.org/case-study/forter) |
| **Apple** | 快速演进的 AI / ML 元数据环境 | AI 资产横跨 custom models、异构数据源和复杂流水线；原有平台难以适配异构 entity shapes | 需要统一表达模型、数据集、训练链路、策略、访问状态和 lineage 事件 | custom entity types、custom aspects 证明 ML 资产不能再被附属于表模型 | [链接](https://datahub.com/customer-stories/apple/) |
| **Pinterest** | 大规模分析与 Text-to-SQL / Analytics Agent | `100,000+ analytical tables`、`2,500+ analytical users`；关键词匹配与摘要已不足以支撑可靠分析 | 需要将历史查询模式、语义信息和治理信号统一为可信上下文 | `table tiers`、`freshness`、`documentation quality` 等治理元数据直接影响 AI 结果质量 | [链接](https://medium.com/pinterest-engineering/unified-context-intent-embeddings-for-scalable-text-to-sql-793635e60aac) |
| **Visa** | 海量数据集、海量列、跨环境复制的全球支付网络 | 自建平台维护成本高；需要治理 `thousands` 至 `hundreds of thousands of datasets` 与 `millions of columns`；复制数据造成使用困惑 | 需要统一维护 classifications、definitions、access policies，并用逻辑数据集跨环境表达业务含义 | `Logical Dataset`、`Business Attributes` 等非表抽象使复制数据与业务语义能够统一治理 | [链接](https://datahub.com/customer-stories/visa/) |

---

## 5. 核心价值归纳

基于 Airtel、Deutsche Telekom、Forter、Apple、Pinterest、Visa 等公开案例，可以将统一元数据管理的核心价值归纳为以下六点。

1. **统一元数据管理首先解决的是规模失控，而不只是“找表”问题。**  
   Airtel 的 `30+ PB` 与 `10,000+` 每日作业、Deutsche Telekom 的 `thousands of datasets` 都说明，随着规模上升，治理、发现和协作效率会迅速成为主要矛盾。

2. **统一目录的核心价值在于形成“单一可信入口”。**  
   Deutsche Telekom、Visa 等案例说明，企业用户真正痛苦的往往不是没有数据，而是不知道该看哪份数据、该找谁、该不该信。

3. **如果只管理表资产，Catalog 很快就会变成不完整目录。**  
   Forter 与 Apple 的案例已经表明，真实企业资产远不止表，还包括对象存储、文件集、模型、训练链路、策略和访问状态等。

4. **AI 场景需要的不是更多表，而是更强的上下文元数据。**  
   Pinterest 的案例表明，在大规模分析和 Agent 场景下，语义、质量、文档与治理信号会直接影响 AI 系统的结果质量。

5. **统一元数据管理的重要收益之一，是让影响分析和变更治理真正可执行。**  
   Deutsche Telekom 与 Visa 的案例都说明，企业需要的不是静态目录，而是可以判断“改了什么、影响谁、该回滚哪一版”的治理系统。

6. **非表资产纳管的本质，是把 AI 时代的关键对象提升为一等资产。**  
   从 Forter 的对象存储对象，到 Apple 的自定义 ML 资产，再到 Pinterest 的治理信号，这些对象都不应继续被视为路径、配置或附属信息。

---

## 6. 多 Agent 场景下的应用场景

目前公开企业案例中，明确完整披露“多 Agent + 统一元数据管理”的终端生产案例仍然相对有限；但从微软 Foundry、Databricks、DataHub 以及少量企业 AI 实践可以看出，这一方向已经非常清晰。

更准确地说，公开证据已足以说明两点：

1. 单 Agent 场景已经开始直接依赖统一元数据作为可信上下文
2. 多 Agent 场景下，这种需求只会进一步增强，因为多个 Agent 必须共享同一套定义、权限、血缘与质量信号

### 6.1 主 Agent 与专家 Agent 协同分析

**场景说明：**
- 主 Agent 接收任务并完成路由
- 专家 Agent 分别承担数据检索、指标解释、合规检查、文档问答等工作

**为什么需要统一元数据管理：**
- 主 Agent 需要知道每个子 Agent 的能力边界、可访问资产与适用问题
- 各子 Agent 需要共享同一套业务定义、指标口径、血缘与可信数据清单
- 主 Agent 需要判断子 Agent 返回结果所引用的数据、模型或文档是否可信、是否最新、是否具备授权基础

**公开出处：**
- 微软 Foundry 的 Connected Agents：  
  [Microsoft Learn](https://learn.microsoft.com/en-us/azure/foundry-classic/agents/how-to/connected-agents?pivots=python)

**简要说明：**
- 该类架构本质上要求一个共享上下文层，否则主 Agent 只能路由任务，而无法保证不同子 Agent 基于同一套企业事实工作。

### 6.2 Agent-to-Agent 调用中的权限控制与身份透传

**场景说明：**
- 一个 Agent 调用另一个 Agent 查询数据、执行操作或访问外部系统
- 系统需要同时处理“能否调用”“以谁身份调用”“权限是否越界”等问题

**为什么需要统一元数据管理：**
- 需要统一记录 Agent 到 Agent、Agent 到工具、Agent 到数据资产之间的授权关系
- 需要表达共享身份、按用户身份透传等不同权限模型
- 需要追踪一次 Agent 调用实际访问了哪些资产、使用了哪些连接、继承了哪些权限

**公开出处：**
- 微软 Foundry 的 A2A authentication：  
  [Microsoft Learn](https://learn.microsoft.com/en-us/azure/foundry/agents/concepts/agent-to-agent-authentication)

**简要说明：**
- 这说明多 Agent 场景下的元数据管理已不只是“找数据”，而是在承接 Agent 协作过程中的身份、权限与可审计性。

### 6.3 多 Agent 共用统一上下文图谱

**场景说明：**
- 多个 Agent 面向不同业务域或不同任务类型工作
- 它们都需要访问统一的企业上下文层，如数据定义、血缘、Owner、质量、政策、文档和运维手册

**为什么需要统一元数据管理：**
- Agent 无法像人一样浏览 Catalog UI，必须通过 API、MCP、SDK 读取可机器解释的上下文
- 如果每个 Agent 自行拼装上下文，就会出现定义漂移、信任标准不一致与重复建设
- 统一元数据图谱可以保证所有 Agent 共享同一个 trusted source

**公开出处：**
- DataHub 对 context catalog 的定义：  
  [DataHub](https://datahub.com/blog/what-is-context-catalog/)

**简要说明：**
- 在多 Agent 场景中，统一元数据平台的角色正在从“面向人的目录”演变为“面向所有 Agent 的上下文总线”。

### 6.4 数据、模型、工具与 Agent 的统一治理

**场景说明：**
- 企业同时运行多个数据 Agent、分析 Agent、RAG Agent 和自动化执行 Agent
- 治理对象不再是单个 Agent，而是整个 Agent 网络

**为什么需要统一元数据管理：**
- 需要统一发现数据、模型、工具与 Agent
- 需要统一施加治理策略，而不是把权限和策略散落在不同系统
- 需要控制 data sprawl 与 AI sprawl，避免重复接入、重复封装和重复构建语义层

**公开出处：**
- Databricks Unity Catalog 2026 官方会议信息：  
  [Databricks](https://www.databricks.com/dataaisummit/session/unity-catalog-101-unified-governance-ai-agents-apps-and-data)

**简要说明：**
- 这一趋势说明，统一元数据治理的对象正在从“数据资产”扩展为“数据 + 模型 + 工具 + Agent”的整体。

### 6.5 单 Agent 已在使用统一元数据，向多 Agent 扩展是自然演进

**场景说明：**
- 虽然很多企业尚未公开完整多 Agent 编排案例，但它们已经在将统一元数据直接供给 AI Agent 或 AI analytics platform 使用

**为什么这很重要：**
- 这意味着统一元数据已经进入 AI runtime，而不再只是后台治理系统
- 组织从单 Agent 扩展到多 Agent 时，最先复用的往往就是这层上下文与治理底座

**公开出处：**
- Deutsche Telekom：其 GenAI analytics platform 使用 DataHub metadata 作为 LLM context  
  [DataHub](https://datahub.com/customer-stories/deutsche-telekom/)
- Gorgias：使用统一元数据平台支撑 Slack-based AI discovery agents  
  [OpenMetadata](https://open-metadata.org/case-study/gorgias)

**简要说明：**
- 严格说，这些尚不等同于完整的多 Agent 编排案例；但它们已经证明，Agent 直接消费统一元数据作为共享上下文底座，正在成为企业中的真实做法。

### 6.6 小结

如果将多 Agent 系统视为“多个智能体围绕企业事实进行协作”的系统，那么统一元数据管理的价值将从传统的“让人更容易找数据”，进一步升级为：

- 让多个 Agent 使用同一套企业定义
- 让多个 Agent 在统一权限边界内协作
- 让多 Agent 的调用链、依赖链和影响范围可追踪
- 让数据、模型、工具和 Agent 一起进入统一治理平面

因此，在多 Agent 场景下，统一元数据平台更像是一个 **共享上下文层、授权层和追踪层**，而不再只是传统意义上的数据目录。

---

## 7. 结论

从公开企业案例看，统一元数据管理的核心价值并不是“把表登记得更完整”，而是为企业建立一个覆盖数据、文件、模型、特征、工具、Dashboard、逻辑数据集与 Agent 的统一资产视图。

在这一视图之上，企业才能在数据规模增长、AI 应用增加、系统日益异构的背景下，继续实现以下目标：

- 资产可发现
- 语义可理解
- 依赖可追溯
- 变更可评估
- 权限可治理
- 风险可审计

进一步看，AI 时代的元数据平台正在从“数据目录”演进为“统一治理控制面”。其治理对象不再局限于表和字段，而是扩展到对象存储、文件集、模型、特征、工具、Prompt、Workflow 与 Agent。  
这也是本文各类企业案例共同指向的结论：**只有将结构化资产、非结构化资产和 AI 原生资产纳入统一元数据体系，企业才能真正支撑面向 AI 的数据治理与资产治理。**
