# 数据目录方案统一研究对比报告（Gravitino / Polaris / Unity Catalog）

更新时间：2026-04-23

---

## 1. 报告目标与统一口径

### 1.1 目标

1. 将 `gravitino-research-report-final`、`polaris-research-report-final`、`unity-research-report-final` 的表达风格统一到同一结构。
2. 提炼三份报告中的关键结果，形成可直接决策的横向对比。
3. 输出一份可独立阅读的新文档，支持技术选型与迁移路线评审。

### 1.2 统一分析维度

1. 多格式支持与架构实现
2. API 协议策略与一致性
3. 计算引擎适配与迁移成本（重点 Spark）
4. 风险与落地建议

---

## 2. 执行摘要（先看本节）

1. **Iceberg 主路径成熟度**：三者都把 Iceberg 作为跨引擎主通道；Gravitino 与 Polaris 更偏向“先统一管理规则（目录、权限、鉴权），再分别适配不同格式”，Unity 更偏向“在同一个网关下并行提供 Core/Iceberg/Delta 等多套协议接口”。
2. **Lance 定位差异明显**：Gravitino 有 Lance 专用 REST + Generic 路由；Polaris 以 Generic Table 映射接入；Unity 当前主要是 Volume 治理路径，未见 Lance 原生表协议服务。
3. **统一 API 语义边界一致**：三者都不是“跨格式语义完全等价”，统一的是入口/治理，不是各格式数据面能力深度。
4. **迁移策略共同点**：Iceberg REST 路径改造最小、风险最低；非 Iceberg（尤其 Lance）需要专门链路与额外一致性验证。

---

## 3. 三套方案对比

### 3.1 Gravitino

### 3.1.1 架构定位

1. 双轨实现：`Iceberg 原生路径` + `Lance Generic 路径`。
2. 服务层并行：`Iceberg REST Server`、`Lance REST Server`、`Unified REST/OpenAPI`。
3. 控制面统一，但格式实现非对称。

### 3.1.2 API 策略与一致性

1. Iceberg REST 能力覆盖较完整（含 namespace/table/view 主能力），但存在明确未实现项。
2. Lance REST 聚焦 namespace/table 生命周期，语义边界更窄。
3. 统一 API 是统一入口，不是统一语义层。

### 3.1.3 引擎与迁移

1. Spark 两条路径：Gravitino Spark Connector 或 Iceberg RESTCatalog。
2. Iceberg 作业切换到 Gravitino Iceberg REST 成本最低。
3. Lance 主导场景迁移成本高，需专项一致性验证。

### 3.1.4 风险结论

1. 误把“统一 API”当“跨格式等价 API”。
2. 低估 Lance 在 create/alter/drop 协同链路的边界与验证成本。

### 3.2 Polaris

### 3.2.1 架构定位

1. 一等公民能力是 Iceberg REST Catalog。
2. Generic Table 是非 Iceberg 格式的轻量元数据层（Create/Load/List/Drop）。
3. Lance 主要体现为 Generic Table 映射集成，而非内建 Lance 原生目录协议服务端。

### 3.2.2 API 策略与一致性

1. 协议族包括 Iceberg REST、Polaris Catalog API（含 Generic）、Management API、OAuth。
2. 统一性主要在 namespace、认证鉴权、RBAC、运行时装配。
3. Iceberg 深语义（commit/transaction/view/metrics/credentials）与 Generic 不对等。

### 3.2.3 引擎与迁移

1. Spark：Iceberg REST 直连成本低；Polaris Spark 插件路径支持更多格式但限制更多。
2. Flink/Trino 在 Iceberg 路径相对成熟；Lance/Generic 的跨引擎一致能力相对弱。
3. 迁移建议为 Iceberg 先行，Generic/Lance 第二阶段接入。

### 3.2.4 风险结论

1. 规范字段与运行时落地存在差距（如 Generic 凭证相关字段）。
2. 若对外口径不区分“控制面统一 vs 数据面不等价”，易造成预期偏差。

### 3.3 Unity Catalog

### 3.3.1 架构定位

1. 统一网关下并挂 Core REST、Iceberg REST、Delta REST/preview commits。
2. 支持表数据、文件数据与 AI 资产治理。
3. Iceberg 通过协议适配层暴露；Lance 当前主要在 Volume 治理路径。

### 3.3.2 API 策略与一致性

1. Core API 强在治理与对象管理（catalog/schema/table/volume/function/model）。
2. Iceberg REST 属于兼容子集，读路径更强。
3. Lance 原生表 API / Lance REST 在当前公开实现中未发现。

### 3.3.3 引擎与迁移

1. Spark 是一等公民（`UCSingleCatalog` + 文档链路完整）。
2. Trino 通过 Iceberg REST 接入。
3. Flink 在仓库内缺少专属连接器，工程验证成本较高。

### 3.3.4 风险结论

1. 将 Volume 治理误解为 Lance 表级协议能力。
2. 忽略 Iceberg 适配的子集边界与 Uniform 可见性约束。

---

## 4. 关键结果横向对比

### 4.1 架构与协议对比

| 维度 | Gravitino | Polaris | Unity Catalog |
|---|---|---|---|
| Iceberg 实现定位 | 原生独立模块 + Iceberg REST Server | 一等公民（Iceberg REST Catalog） | 协议适配层（Iceberg REST 子集） |
| Lance 实现定位 | Generic 路由 + Lance REST Server | Generic Table 映射接入 | Volume 治理接入（公开实现未见 Lance 原生表协议） |
| 统一 API 含义 | 统一入口层 | 统一控制面 | 统一网关 + 多协议并挂 |
| 跨格式语义等价 | 否 | 否 | 否 |

### 4.2 API 一致性与能力深度对比

| 维度 | Gravitino | Polaris | Unity Catalog |
|---|---|---|---|
| Namespace/Table 基础治理 | 强 | 强 | 强 |
| Iceberg 深语义（事务/视图等） | 较强（有未实现项） | 强（部分端点未实现） | 中（兼容子集，读路径更强） |
| Generic/非 Iceberg 深语义 | 中（按 format 分支） | 中偏弱（轻量 CRUD） | 中偏弱（主要治理层） |
| Lance 原生协议面 | 有专用 REST（边界明确） | 未见核心内建服务端 | 当前公开实现未见 |

MCP 支持

| 特性 | Unity Catalog | Apache Gravitino | Apache Polaris |
|------|---------------|------------------|----------------|
| 核心侧重 | 治理与执行。重点在于让 Agent 调用函数和受控访问。 | 连接与聚合。重点在于将异构数据源统一暴露给 AI。 | 标准与开放。重点在于 Iceberg 生态的 AI 互操作性。 |
| 典型工具 | Unity Catalog MCP Server / Agent Bricks | mcp-server-gravitino (基于 FastMCP) | polaris-mcp-server |
| 优势场景 | 企业级内部治理、闭环 AI 任务执行。 | 存在多种数据库、数据湖，需要统一语义层。 | 纯粹的 Iceberg 开放架构。 |

### 4.3 Spark 迁移路径对比

| 维度 | Gravitino | Polaris | Unity Catalog |
|---|---|---|---|
| Spark Iceberg 最小改造路径 | Iceberg RESTCatalog | Iceberg RESTCatalog | Iceberg REST/或 UC Spark Catalog（取决于路径） |
| Spark 平台化路径 | Gravitino Spark Connector | Polaris Spark 插件 | UCSingleCatalog |
| 非 Iceberg 场景复杂度 | 中到高（尤其 Lance） | 中到高（插件能力边界） | 中到高（Lance 需 Volume + 外部客户端） |

### 4.4 迁移风险等级（相对）

| 场景 | Gravitino | Polaris | Unity Catalog |
|---|---|---|---|
| 现有 Spark Iceberg 作业迁移 | 低 | 低 | 低到中 |
| 多引擎统一目录（Iceberg 为主） | 中 | 中 | 中 |
| Lance 主导并追求与 Iceberg 等价 | 高 | 高 | 高 |

---

## 5. 统一结论

1. 三套方案都验证了同一事实：**控制面统一可行，数据面语义完全统一并不成立**。
2. 若目标是“低风险快速迁移”，应优先走 Iceberg REST 主路径。
3. Lance 相关能力在三者中都不是“零成本等价替换”场景，需独立规划一致性测试与运维策略。


---

## 6. 来源文档

1. `gravitino-research-report-final.md`
2. `polaris-research-report-final.md`
3. `unity-research-report-final.md`
