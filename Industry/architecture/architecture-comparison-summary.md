# Gravitino / Polaris / Unity Catalog 对比总结

来源：
- Industry\architecture\gravitino-research-report-final.md
- Industry\architecture\polaris-research-report-final.md
- Industry\architecture\unity-research-report-final.md

## 1. 对比结论先看

这三套系统都在解决“统一治理多种数据对象，并支持多个计算引擎接入”的问题，但实现重心并不相同：

- `Gravitino`：核心是通用元数据纳管平台，强在把多类型对象、多种表格式、多种引擎接入同一个 catalog/control API；它更关心“对象怎么统一登记、发现、管理，再分发给不同 provider 处理”。
- `Polaris`：核心是 `Iceberg Catalog`，多格式能力主要通过 `Generic Table` 和 Spark 侧适配扩展出来，Iceberg 仍是一等主线。
- `Unity Catalog`：核心是统一资产访问治理中心，强在把表、volume、function、model、external location、权限和临时凭据串成访问治理闭环；它更关心“资产是否被授权、从哪个位置访问、用什么临时凭据访问、通过什么协议给客户端看”。

如果只用一句话概括：

- `Gravitino` 统一的是“多对象、多格式的元数据入口和 provider 分发”。
- `Polaris` 主打的是“Iceberg 主线 + 非 Iceberg 扩展层”。
- `Unity Catalog` 统一的是“资产访问治理闭环：谁能访问、访问哪个位置、用什么临时凭据访问，以及通过哪套协议接口访问”。

---

## 2. 三者总体定位对比

| 维度 | Gravitino | Polaris | Unity Catalog |
|---|---|---|---|
| 主定位 | 通用元数据与治理控制面 | 面向 Iceberg 的 Catalog 服务 | 统一数据/AI 资产治理控制面 |
| 原生主线格式 | 无单一主线，按 provider 分派 | Iceberg | Delta |
| 多格式方式 | 统一控制面 + provider/adapter + 专用协议 | Iceberg 主线 + Generic Table + Spark 插件适配 | 统一资产模型 + 按客户端需要暴露不同协议接口 |
| AI / Model / Function 资产 | 有 `Model`、`Function` 等非表对象，偏通用元数据生命周期管理 | 不是核心主线，重点仍是 Iceberg table/view 和 Generic Table | 是资产模型的一部分，包含 function、registered model、model version、模型制品位置和模型版本临时凭据 |
| 联邦 catalog / 外部 catalog 接入 | 通过不同 provider 支持对已有资产的管理，如MySQL、iceberg catalog等 | 有明确的外部 catalog federation 入口，覆盖 `ICEBERG_REST`、`HADOOP`、`HIVE`、`BIGQUERY` 等连接类型，核心还是基于iceberg协议 | 本文语境下不是 catalog federation 主线，更偏 external location、storage credential 和多协议适配；商业 Databricks 体系中的 Lakehouse Federation 另算 |
| 多引擎方式 | Spark/Flink/Trino 各自原生扩展点接入 | Iceberg REST 通吃大多数引擎，Spark 负责多格式扩展 | Spark 原生 UC catalog，Trino/Iceberg 客户端走 Iceberg REST，Delta 客户端走 Delta REST |
| 统一的重点 | catalog/schema/object 与治理对象统一 | catalog/namespace/table-like 路径统一 | catalog/schema/asset + permissions + credentials 统一 |
| 典型边界 | 不统一所有格式原生协议与数据面语义 | 非 Iceberg 格式不是完整事务型 catalog | 不负责统一执行器，扫描/提交仍由引擎或格式生态负责 |

---

## 3. 多格式能力的本质差异

### 3.1 Gravitino

关键结论：

- 多格式是第一层设计目标，不依赖某一个单一表格式。
- 同时覆盖 `Hive`、`Iceberg`、`Paimon`、`Hudi`、`JDBC`、`Lance`，以及 `Fileset`、`Topic`、`Model`、`Function` 等非表对象。
- 统一的是对象层级、生命周期和治理能力；真正的数据面语义仍由各 provider 决定。

本质上，Gravitino 不是“某种格式的 catalog”，而是“一个能够纳管多类对象、并把操作分发到不同后端语义的统一控制面”。

### 3.2 Polaris

关键结论：

- Iceberg 是绝对主线，协议、事务、view、credentials、metrics 等完整能力都建立在 Iceberg REST 主线上。
- 非 Iceberg 格式通过 `Generic Table` 纳管，更多是“登记 + 治理 + 命名空间归属”，不是完整语义托管。
- Spark 插件是 Polaris 多格式能力最强、最完整的入口。

因此，Polaris 的多格式能力更准确的说法是：
“以 Iceberg 为中心，把其他表格式接入统一 catalog 与治理体系，但不把它们都做成和 Iceberg 等价的一等事务协议对象。”

### 3.3 Unity Catalog

关键结论：

- 多格式能力分三层：表对象、volume 对象、AI 资产对象。
- 表格式的核心公开枚举包括 `DELTA`、`PARQUET`、`ORC`、`JSON`、`CSV`、`AVRO`、`TEXT`。
- `Lance` 不作为 table provider 一等建模，而是放在 `Volume` 语义下理解。
- `Iceberg` 在 UC 中更多体现为 Iceberg-compatible 接口，代表机制是 `Delta UniForm`。

AI 资产管理上，`Unity Catalog` 的优势不只是“有 Model 和 Function 对象”，而是这些对象进入了同一套资产治理链路：

- `Function`、`Registered Model`、`Model Version` 都挂在 catalog/schema 这套命名空间下。
- `Model Version` 不只记录模型名，还管理版本和模型制品位置。
- 模型制品访问可以走 `temporary-model-version-credentials`，和表、volume 的临时凭据机制保持一致。
- 因此 UC 更像把数据资产、函数资产、模型资产放进同一个权限和凭据中心；`Gravitino` 也有 `Model`、`Function` 元数据对象，但在本文调研范围内更偏通用元数据生命周期管理。

因此，Unity Catalog 的多格式策略不是“所有格式都进同一种表协议”，而是“所有资产先进入统一治理模型，再按对象类型和客户端需要暴露成 Core REST、Delta REST 或 Iceberg REST 等不同接口”。

---

## 4. API 与协议对比

| 维度 | Gravitino | Polaris | Unity Catalog |
|---|---|---|---|
| 统一控制面 API | `Gravitino REST/OpenAPI` | `Management API` + `Native Catalog API` | `Core REST API` |
| Iceberg 协议 | 有，`/iceberg/v1/...` | 有，且是主线 | 有，但当前更像 Iceberg-compatible 子集 |
| Delta 协议 | 无独立 Delta 协议主线 | 无 | 有，`/delta/v1/...` |
| Lance 协议 | 有，`/lance/v1/...` | 无独立 Lance 服务端，走 Generic Table 映射 | 无独立 Lance 协议，走 Volume |
| 治理对象 API | 强，涵盖 tag/policy/owner/permission/credential/statistics 等 | 有，重点是 role/grant/policy | 强，涵盖 permissions/credentials/external locations/models/functions |
| 协议策略 | 统一控制面 + 专用协议并存 | 标准 Iceberg REST 主线 + 原生扩展 API | Core REST 为中心，同一套资产按需要暴露成 Delta/Iceberg 等协议接口 |

关键差异：

- `Gravitino` 的协议面最像“控制面总入口 + 个别格式专用入口并存”。
- `Polaris` 的协议面最像“标准 Iceberg REST 为核心，Native API 只是补充扩展”。
- `Unity Catalog` 的协议面最像“统一资产控制面之上，为 Delta 和 Iceberg 等生态分别提供它们能理解的接口”。

---

## 5. 计算引擎接入方式对比

| 维度 | Gravitino | Polaris | Unity Catalog |
|---|---|---|---|
| Spark | connector/plugin，最适合分析主链路 | `SparkCatalog`，多格式能力最强入口 | `UCSingleCatalog`，与 Core REST 深度集成 |
| Flink | Catalog Store | 常见通过 Iceberg REST 接入 | 文档主线不是 UC 原生 Flink catalog，更多走 Iceberg 生态 |
| Trino | 动态 catalog 管理 | 常见通过 Iceberg REST 接入 | 常见通过 Iceberg REST 接入 |
| 其他客户端 | Iceberg/Lance 客户端可直连专用协议 | Doris、StarRocks、Dremio 等可走 Iceberg REST | DuckDB、Daft、CLI、SDK、UI 等通过各自协议接入 |
| 接入统一性 | 不统一，各引擎走各自扩展机制 | Iceberg 场景最统一；非 Iceberg 主要靠 Spark | 明显分层，不同客户端说不同协议 |

关键结论：

- `Gravitino` 是“多引擎原生接入点各不相同，但统一接到同一个控制面”。
- `Polaris` 是“Iceberg 引擎接入最统一，多格式扩展主要集中在 Spark”。
- `Unity Catalog` 是“控制面统一，但 wire protocol 不统一，靠协议适配服务不同生态”。

---

## 6. 控制面与治理统一到什么程度

| 维度 | Gravitino | Polaris | Unity Catalog |
|---|---|---|---|
| 统一命名层级 | `metalake -> catalog -> schema -> object` | `catalog -> namespace -> table-like` | `metastore -> catalog -> schema -> asset` |
| 控制面对象范围 | 最广，表、fileset、topic、model、function、治理对象都在统一入口下 | 以 table-like、role/grant/policy 和外部 catalog 连接为主 | 表、volume、function、registered model、model version、permissions、credentials、external locations |
| 表格式语义统一 | 只统一控制面对象，不统一所有格式的数据面协议和事务语义 | Iceberg 深度统一，Generic Table 轻量统一 | 统一表元数据和访问入口，但不同协议接口看到的能力不同 |
| 权限/策略治理 | 有 permission、policy、tag、owner 等治理对象，和 provider/协议的联动程度按类型分化 | 有 principal、role、grant、policy，重点服务 catalog/table-like 管理 | 权限模型和资产、存储位置、凭据绑定更紧 |
| 凭据与数据访问 | 有 credential 能力，但不是整套设计主轴 | 有 credentials，尤其 Iceberg 路径更完整 | temporary credentials 是核心能力之一，连接控制面授权和数据面访问 |

判断上可以这样理解：

- `Gravitino`：最强调“统一通用元数据控制面对象模型”。
- `Polaris`：最强调“统一目录与访问控制，但表语义分 Iceberg 与 Generic 两条线”。
- `Unity Catalog`：最强调“统一资产、统一权限、统一凭据，再把同一资产通过不同协议接口暴露给不同客户端”。

这里需要区分“治理对象覆盖面”和“资产访问治理闭环”。前者看系统有没有治理对象，后者看这些治理对象是否和真实数据访问链路绑定在一起：

- `Gravitino` 不是没有统一治理能力，它有 `tag`、`policy`、`owner`、`permission`、`credential`、`statistics` 等治理对象。
- 但如果把统一治理理解成“资产权限、存储位置、临时凭据、协议访问、写入生命周期之间的闭环”，`Unity Catalog` 更完整。

更具体地说，`Unity Catalog` 的治理强，不是强在“元数据对象更多”，而是强在访问链路更完整：

| 治理问题 | Gravitino | Polaris | Unity Catalog |
|---|---|---|---|
| 谁能访问这个对象 | 有 permission、owner、policy 等治理对象 | 有 principal、role、grant、policy，重点围绕 catalog、namespace、table-like 对象 | 有统一 permission 模型，并围绕 securable asset 管理 |
| 对象在哪个存储位置 | 更多依赖 catalog/object 元数据和 provider 配置 | 通过 storage、connection、external catalog 配置参与治理，整体服务 Iceberg/catalog 主线 | external location、storage location 是核心治理对象 |
| 授权后怎么安全访问底层存储 | 有 credential 能力，但不是所有对象访问路径的主轴 | 有 credentials，Iceberg REST 路径更完整；Generic Table 路径更偏登记和治理 | temporary credentials 是核心链路，覆盖 table、volume、model version、path |
| 写入时如何控制路径和凭据 | 不统一接管各格式写入、提交和事务语义 | Iceberg 表生命周期和 commit 路径较完整；非 Iceberg 更多是 Generic Table 登记和 Spark 适配 | 对 managed/external、staging table、读写凭据有更明确的控制路径 |
| AI/模型制品怎么治理 | 有 Model、Function 元数据对象 | 不是核心主线 | 有 registered model、model version、模型制品位置和模型版本临时凭据 |
| 是否对所有格式都更深 | 否，Gravitino 在 Lance 等格式上可以更直接 | 否，Polaris 最深的是 Iceberg 主线，非 Iceberg 主要靠 Generic Table 和 Spark 适配 | 否，UC 更偏目录、权限、凭据治理，不一定提供最深格式协议 |

- 如果比较的是多类型元数据统一入口和特定格式 provider/protocol 接入深度，`Gravitino` 很强；

- 如果比较的是 Iceberg catalog 生命周期、commit 和标准多引擎接入，`Polaris` 很强；

- 如果比较的是从资产登记到授权、存储位置、临时凭据、客户端访问的治理闭环，`Unity Catalog` 更强。

---

## 7. 三者对 Lance 的处理差异

这是三份调研里一个很有代表性的分歧点。

| 系统 | Lance 的定位 | 含义 |
|---|---|---|
| Gravitino | 有独立 `Lance REST API`，也是 provider 之一 | 对 Lance 的支持最直接，既有统一控制面，也有专用协议面 |
| Polaris | 通过 Generic Table 登记，主要靠 Lance Namespace 映射 | Polaris 记录的是 Lance 表登记信息，不接管 Lance 全部内部语义 |
| Unity Catalog | 放在 `Volume` 语义下 | 管的是目录、权限和凭据，不把 Lance 作为 table provider 一等建模 |

这个差异很重要，因为它直接说明三者对“非主流格式”的治理深度不同：

- `Gravitino` 最深。
- `Polaris` 次之，偏登记与接入。
- `Unity Catalog` 最偏目录资产治理。

---

## 8. 核心能力边界

### 8.1 Gravitino 的边界

- 强项是统一元数据控制面和多对象治理。
- 不试图把所有格式的数据面协议都做成一套。
- 多协议并存是其设计组成部分，不是过渡态。

### 8.2 Polaris 的边界

- 强项是 Iceberg 生态标准接入和治理。
- Generic Table 更像扩展层，不应被理解成“通用事务型 catalog 协议”。
- 若要验证多格式统一入口，Spark 是最佳落点；若要验证标准多引擎接入，Iceberg REST 是最佳落点。

### 8.3 Unity Catalog 的边界

- 强项是统一资产治理、权限、凭据、external location 和多协议适配。
- 不负责替代引擎执行器，也不把所有格式都变成统一底层协议。
- Iceberg 兼容更多依赖 `UniForm` 这类跨协议暴露机制，而不是原生把所有表都做成 Iceberg。
