# Unity Catalog 项目分析

更新时间：2026-04-14

仓库：
- GitHub: https://github.com/unitycatalog/unitycatalog
- 本地路径：`/home/lei/data_ai/learning/codebase/unitycatalog`

当前分析基于本地仓库快照与公开资料交叉整理。当前本地代码版本处于 `0.5.0-SNAPSHOT` 开发线，不等同于最新稳定发布版。

## 目录

- 1. 项目定位
- 2. 业务场景
- 3. 核心技术点
- 4. 总体架构、总体源码链路与总体流程图
- 5. 按子模块分组的功能、源码链路与流程图
- 6. 源码级观察结论

## 1. 项目定位

Unity Catalog 是一个面向 Data + AI 资产的开源统一目录与治理平台。它试图解决的不只是“表元数据登记”，而是把数据资产、文件资产、函数资产、模型资产统一纳入一个开放的控制平面中，并通过标准 API、权限模型、临时凭证和多引擎适配，把治理能力真正延伸到执行链路里。

从仓库实现看，它同时覆盖：
- 元数据服务
- 认证与授权
- 临时存储凭证下发
- Spark / Delta / Iceberg 兼容接入
- Java / Python SDK
- CLI 与 UI
- Helm / Docker 部署
- 面向 Agent 的 AI Functions 能力

需要补充说明的是：当前仓库中的原生实现重点仍然是 Delta 与 Iceberg 相关的协议兼容、元数据管理与凭证下发。README 中提到的 Apache Hudi 支持，更多是通过 UniForm / Apache XTable 等外部转换链路间接接入，而不是当前代码库中存在独立的 Hudi 服务端实现。

### 1.1 当前开发阶段与技术重心

当前本地代码版本处于 `0.5.0-SNAPSHOT`，说明这份代码更接近开发前沿线，而不是稳定发布快照。

结合 roadmap、本地近期提交和模块设计，当前技术重心主要集中在：
- Managed Delta Tables
- 认证模块化
- AI Functions 生态
- 多引擎开放协议兼容

其中最核心的主线是 Managed Delta Tables，原因是：
- roadmap 明确将其列为 0.5 优先事项
- Spark connector 中存在大量 staging、table ID、credential vending 逻辑
- 最近提交多次出现 Delta REST Catalog、managed table、cross-version test

### 1.2 项目优势

- API-first，边界清晰
- 模块划分好，服务端与执行侧职责明确
- 数据与 AI 资产统一建模
- 多云凭证体系较完整
- Spark / Delta / Iceberg 方向具备较强技术深度
- AI 集成生态广，叙事完整

### 1.3 项目风险

- API 仍在演进，稳定性不是当前优先目标
- 执行引擎兼容与依赖冲突复杂，尤其体现在 Spark/Jackson/Delta 组合上
- AI Functions 的执行点在客户端，安全边界依赖调用方环境
- 多协议、多云、多模块并行推进，长期维护复杂度高

## 2. 业务场景

### 2.1 统一管理数据资产

适用场景：
- 企业内部需要统一管理 catalog / schema / table
- 同时存在 Delta、Parquet、CSV、JSON 等多种数据格式
- 不同计算引擎需要访问同一套元数据

业务目标：
- 给数据资产提供统一命名空间
- 统一元数据查询与生命周期管理
- 降低“每个引擎各维护一套 catalog”的重复成本

核心对象：
- Catalog
- Schema
- Table
- Metastore

### 2.2 统一管理非表类资产

适用场景：
- 需要治理文件类资产，如 PDF、图片、音频、视频、模型工件、配置文件
- 需要为数据科学或 AI 工作流提供结构化的资产入口

业务目标：
- 用 Volumes 管理非结构化文件
- 用 Models / ModelVersions 管理模型资产
- 用 Functions 管理可调用逻辑

核心对象：
- Volume
- Function
- RegisteredModel
- ModelVersion

### 2.3 数据访问治理与权限控制

适用场景：
- 企业需要按照用户、组、服务身份控制访问权限
- 需要让数据“默认安全”，而不是靠下游引擎自行兜底

业务目标：
- 对 catalog/schema/table/volume/model/function 做统一授权
- 通过服务端权限判断约束数据访问行为
- 兼容外部身份系统与标准认证流程

核心能力：
- Permission API
- SCIM2 用户接口
- OAuth token exchange
- JCasbin 授权模型

### 2.4 临时凭证下发与多云访问

适用场景：
- 底层数据在 S3、ADLS、GCS
- 不希望把长期 AK/SK 或固定密钥散落到各个执行端

业务目标：
- 由目录服务按对象和操作下发短期凭证
- 把存储访问控制和对象权限绑定
- 支持跨云存储

核心能力：
- Temporary table credentials
- Temporary volume credentials
- Temporary model version credentials
- Temporary path credentials

### 2.5 多引擎与开放表格式接入

适用场景：
- Spark、DuckDB、Iceberg REST 客户端等都需要消费同一套目录信息
- 需要对 Delta managed tables、Iceberg REST catalog 等协议进行兼容

业务目标：
- 把 Unity Catalog 变成开放协议下的控制平面
- 支持 managed/external table 的元数据和读写协调
- 让执行引擎通过 UC 获取位置、配置、凭证

核心能力：
- Spark connector
- Iceberg REST catalog service
- Delta REST catalog service
- Delta commits API

### 2.6 AI / Agent 工具化集成

适用场景：
- 需要把函数资产作为 LLM/Agent 的 tools 使用
- 需要把函数定义、执行和治理纳入统一平台

业务目标：
- 把函数当成一等资产登记到目录里
- 由 SDK 和集成层把函数暴露给 AI 框架
- 支持 OpenAI、Anthropic、LangChain、LlamaIndex 等生态

核心能力：
- `unitycatalog-ai`
- Function create/get/list/execute
- 各类 AI integration packages

## 3. 核心技术点

### 3.1 API-first 架构

项目以 OpenAPI 规格驱动服务端模型、客户端 SDK 和 API 文档生成。`api/all.yaml` 与 `api/delta.yaml` 是核心协议源头，Java/Python SDK、服务端模型和 Markdown API 文档都从这些规格生成。

价值：
- 服务端、客户端、文档的边界一致
- 降低多语言 SDK 的维护成本
- 让 API 兼容性问题更容易被显式管理

### 3.2 控制平面 + 执行平面分离

Unity Catalog 本质上更像控制平面，不直接承担大规模数据计算，而是负责：
- 元数据存储与查询
- 权限判断
- 存储凭证下发
- 为 Spark / Iceberg / Delta 等执行侧提供配置与访问入口

这意味着：
- 服务端负责“允许什么”
- 执行引擎负责“如何读写”
- 两者通过 API、凭证和开放协议协同

### 3.3 统一资产模型

Unity Catalog 并不只围绕表设计，而是用统一 namespace 和权限模型覆盖：
- tables
- volumes
- functions
- models

这使它更接近“数据与 AI 资产目录”，而不是传统 metastore。

### 3.4 授权与身份集成

服务端内建：
- OAuth token exchange
- issuer / audience 校验
- JWKS 验签
- SCIM2 用户能力
- JCasbin 授权

这说明身份与授权不是外围插件，而是核心系统组成部分。

### 3.5 临时凭证架构

项目对 AWS / Azure / GCP 都做了凭证下发支持。Unity Catalog 服务端在权限校验后下发短期凭证，执行端用这些凭证访问底层存储。

技术意义：
- 避免长期密钥扩散
- 把对象级权限映射到存储访问
- 支持跨云和更细粒度的安全控制

### 3.6 对 Delta / Iceberg 的协议级兼容

Unity Catalog 正在从“管理元数据”往“参与开放表格式控制协议”演进，尤其体现在：
- Iceberg REST Catalog API
- Delta REST Catalog API
- Delta managed tables
- Delta commits / staging / credential vending

这部分是项目最有技术门槛、也最接近核心壁垒的能力。

### 3.7 面向 AI 的 Function 体系

AI Core 不是独立小工具，而是在把 UC Functions 变成标准化、可治理、可执行、可集成到 Agent 的工具资产。

技术关键点：
- 函数注册与元数据管理
- 函数同步 / 异步调用
- wrapped function 打包
- 本地或 sandbox 执行模式
- AI framework integration

## 4. 总体架构、总体源码链路与总体流程图

这一章只放总体视角内容。也就是把“总体架构”“总体源码主链”“总体请求流”“总体流程图”集中在一起，先建立全局认知，再进入后面的子模块拆解。

### 4.1 高层架构图

```text
                       +----------------------+
                       |   Users / Clients    |
                       |----------------------|
                       | UI / CLI / SDK / AI  |
                       +----------+-----------+
                                  |
                                  v
        +--------------------------------------+      +---------------------------+
        |     Unity Catalog Server             |<-----| Config / Bootstrap Assets |
        |--------------------------------------|      |---------------------------|
        | Auth / Access / Metadata / Creds     |      | etc/conf / etc/db /      |
        | UC API / Control API / Iceberg /     |      | etc/data                  |
        | Delta REST Catalog                   |      +---------------------------+
        +---------+-------------------+--------+
                  |                   |
                  v                   v
+--------------------------------+   +----------------------+
| Metadata Persistence           |   | Auth / Policy Model  |
|--------------------------------|   |----------------------|
| Repositories / Hibernate / DB  |   | JCasbin / SCIM / JWT |
| Repository / DAO               |   +----------------------+
+---------------+----------------+
                |
                v
+---------------------------------------------+
| External Object Storage / Table Formats     |
|---------------------------------------------|
| S3 / ADLS / GCS / Delta / Iceberg / Files   |
+---------------------------------------------+
                ^
                |
+---------------------------------------------+
| Execution Engines / Frameworks              |
|---------------------------------------------|
| Spark / DuckDB / Iceberg clients / AI apps  |
+---------------------------------------------+
```

说明：
- UI、CLI、SDK、AI integrations 都是入口层
- Server 是核心控制平面
- 底层数据和模型工件在外部对象存储里
- Spark、DuckDB、Iceberg 客户端等通过协议和凭证接入
- `Repositories` 是服务端持久化层的聚合入口，位于 service 与底层 repository/DAO 之间
- `etc/conf`、`etc/db`、`etc/data` 属于运行时配置与本地演示支撑输入，因此在总体图中体现；`spec/protocols`、`tests`、`dev` 属于规范或支撑资源，不作为运行时组件放入该图

### 4.2 总体源码主链

#### 4.2.1 服务端总入口与请求进入链

核心入口：
- `io.unitycatalog.server.UnityCatalogServer`

源码主链：
1. `main()` 启动 `UnityCatalogServer.builder().port(...).build()`
2. `initializeServer()` 初始化 Hibernate，并构造 `Repositories` 作为 repository 聚合入口
3. `initializeServer()` 调用 `initializeAuthorizer()`，再通过 `addApiServices()` 挂载 UC/control API
4. `addApiServices()` 继续分派到 `addIcebergApiServices()` 与 `addDeltaApiServices()`
5. `addIcebergApiServices()` 初始化 `IcebergObjectMapper`、`MetadataService(new FileIOFactory(...))`、`TableConfigService(...)`
6. `addSecurityDecorators()` 在路由前挂认证与授权装饰器
7. 服务启动后接受 HTTP 请求

关键类与方法：
- `UnityCatalogServer.main`
- `UnityCatalogServer.initializeServer`
- `UnityCatalogServer.addApiServices`
- `UnityCatalogServer.addIcebergApiServices`
- `UnityCatalogServer.addDeltaApiServices`
- `UnityCatalogServer.addSecurityDecorators`

关键事实：
- UC 主 API 路径前缀是 `/api/2.1/unity-catalog/`
- control API 路径前缀是 `/api/1.0/unity-control/`
- Iceberg REST 和 Delta REST 都挂在同一服务端进程中
- Auth 和 Access control 通过 Armeria decorator 在业务 service 之前执行
- `etc/conf`、默认 H2 数据库和本地演示数据目录共同构成服务端启动时的重要环境输入

请求进入服务端的源码流程：

```text
HTTP request
  -> Armeria route match
  -> AuthDecorator
  -> UnityAccessDecorator
  -> Annotated Service method
  -> Repository/DAO
  -> HttpResponse.ofJson(...)
```

输入：
- HTTP method
- path/query/body
- bearer token 或 cookie

输出：
- JSON 响应
- 错误响应

#### 4.2.2 模块级关键链路总结

链路 A：普通 API 访问

```text
Request
  -> AuthDecorator
  -> UnityAccessDecorator
  -> Service method
  -> Repository
  -> DAO / DB
  -> Response
```

链路 B：外部身份接入

```text
External token
  -> AuthService.grantToken
  -> JWKS verify
  -> principal validation
  -> create internal access token
  -> later requests use AuthDecorator
```

链路 C：Managed Delta 建表

```text
Spark CREATE TABLE
  -> UCSingleCatalog.stageManagedDeltaTableAndGetProps
  -> createStagingTable
  -> temporary credentials
  -> UCProxy.createTable
  -> TableRepository.createTable
  -> commitStagingTable
  -> final table metadata persisted
```

链路 D：AI Function 执行

```text
Function name + params
  -> get_function metadata
  -> validate params
  -> reconstruct callable from source
  -> local execute or sandbox subprocess execute
  -> result
```

### 4.3 总体关键请求流

#### 4.3.1 元数据查询流

```text
Client/UI/CLI
  -> UC REST API
  -> AuthDecorator
  -> UnityAccessDecorator
  -> Service
  -> Repository/DAO
  -> DB
  -> 返回业务对象
```

输入：
- 认证信息
- 业务查询参数

输出：
- Catalog / Schema / Table 等元数据对象

#### 4.3.2 Token Exchange 认证流

```text
External identity token
  -> /api/1.0/unity-control/auth/tokens
  -> issuer/audience/JWKS 验签
  -> principal 校验
  -> 生成 UC access token
  -> 返回 token 或 cookie
```

输入：
- 外部 JWT
- issuer / audience 配置

输出：
- Unity Catalog access token

#### 4.3.3 Spark Managed Delta 建表流

```text
Spark SQL CREATE TABLE
  -> UCSingleCatalog
  -> UC createStagingTable
  -> UC 返回 staging location + table id
  -> UC 返回 temporary credentials
  -> Spark/Delta 使用 credential props 写入底层存储
  -> UC 管理表元数据
```

输入：
- Spark catalog 配置
- CREATE TABLE 语句与属性

输出：
- 受 UC 管理的 Delta table
- staging 位置、table ID、临时凭证

#### 4.3.4 AI Function 调用流

```text
AI App / Agent Framework
  -> ai/integrations/*
  -> ai/core client
  -> Python SDK / UC API
  -> 获取 FunctionInfo
  -> 本地或 sandbox 执行函数
  -> 返回 tool result
```

输入：
- function name
- parameters
- UC metadata

输出：
- 函数执行结果

注意：
- 执行点在客户端，不在服务端

### 4.4 总体流程图

这部分把上面的总体链路改写成 Mermaid 图式，便于在 Markdown 阅读器里直接看结构。

#### 4.4.1 总览：典型业务场景总体源码模块调用逻辑图

```mermaid
flowchart TD
    A[业务入口<br/>用户 / SDK / CLI / Spark / AI App] --> B[入口层模块<br/>ui<br/>examples/cli<br/>clients/python<br/>clients/java<br/>connectors/spark<br/>ai/integrations/*]
    B --> C[统一 API/协议入口<br/>server/src/main/java/io/unitycatalog/server/UnityCatalogServer.java]
    N[运行时配置与本地支撑<br/>etc/conf<br/>etc/db<br/>etc/data] --> C
    C --> D[认证模块<br/>service/AuthDecorator.java<br/>service/AuthService.java]
    C --> E[授权模块<br/>auth/decorator/UnityAccessDecorator.java<br/>auth/JCasbinAuthorizer.java]
    C --> F[业务 Service 模块<br/>server/src/main/java/io/unitycatalog/server/service/*]
    F --> G[Repositories / Repository / DAO 持久化模块<br/>server/persist/Repositories.java<br/>server/persist/*<br/>server/persist/dao/*]
    F --> H[协议兼容模块<br/>service/IcebergRestCatalogService.java<br/>service/delta/DeltaRestCatalogService.java<br/>MetadataService / TableConfigService]
    F --> I[临时凭证模块<br/>service/credential/*<br/>Temporary*CredentialsService]
    G --> J[元数据存储<br/>Hibernate / DB]
    I --> K[外部对象存储与云凭证<br/>S3 / ADLS / GCS]
    H --> L[执行引擎与协议客户端<br/>Spark / Iceberg / Delta / DuckDB]
    B --> L
    B --> M[AI Function 执行侧<br/>ai/core/src/unitycatalog/ai/core/*]
    M --> C
    M --> K
```

解读：
- 所有上层入口最终都会汇聚到 `server`
- `server` 内部先过认证和授权装饰器，再进入业务 service
- `service` 一边读写元数据持久层，一边把能力暴露给协议层和凭证层
- Spark / AI 等运行时并不只是“调 API”，还会消费存储位置、临时凭证和函数定义
- `spec/protocols/ManagedTablesSpec.md` 是 managed tables 的协议规格来源，属于规范层输入，不是运行时调用节点，因此放在正文说明而不放进总体运行时图

#### 4.4.2 总览：按业务场景划分的总体调用图

```mermaid
flowchart LR
    A[场景 1<br/>普通元数据管理] --> A1[CLI / UI / SDK]
    A1 --> A2[UC REST API<br/>server/service/*]
    A2 --> A3[Repository / DAO]
    A3 --> A4[DB]

    B[场景 2<br/>认证与访问控制] --> B1[外部身份令牌]
    B1 --> B2[AuthService<br/>/api/1.0/unity-control/auth/tokens]
    B2 --> B3[内部 UC Token]
    B3 --> B4[AuthDecorator + UnityAccessDecorator]
    B4 --> B5[具体业务 Service]

    C[场景 3<br/>Spark Managed Delta 建表/读写] --> C1[connectors/spark/UCSingleCatalog.scala]
    C1 --> C2[StagingTable API + TemporaryCredentials API]
    C2 --> C3[TableService / StagingTableService]
    C3 --> C4[TableRepository / StagingTableRepository]
    C4 --> C5[DB + 外部存储路径]

    D[场景 4<br/>AI Function 注册与执行] --> D1[ai/core/client.py]
    D1 --> D2[FunctionsApi]
    D2 --> D3[FunctionService / FunctionRepository]
    D3 --> D4[DB 中的函数元数据]
    D1 --> D5[本地 / sandbox 执行器]
```

## 5. 按子模块分组的功能、源码链路与流程图

这一章按“每个子模块一组”的方式组织。每组内部固定按以下顺序展开：
- 功能与职责
- 源码链路
- 流程图

子模块分组如下：

| 子模块分组 | 主要目录 |
|---|---|
| `api` 与代码生成 | `api/`, `build.sbt`, `serverModels`, `controlModels`, `client`, `pythonClient`, `apiDocs` |
| `server` 总入口与请求分发 | `server/src/main/java/io/unitycatalog/server/` |
| `server` 认证与授权 | `server/service/Auth*`, `server/auth/*` |
| `server` 资源服务层与持久化层 | `server/service/*`, `server/persist/*`, `server/persist/dao/*` |
| `server` 凭证与协议兼容层 | `server/service/credential/*`, `Temporary*CredentialsService`, `DeltaRestCatalogService`, `IcebergRestCatalogService` |
| `clients/java` 与 `clients/python` | `clients/java`, `clients/python` |
| `ai/core` 与 `ai/integrations/*` | `ai/core`, `ai/integrations/*` |
| `connectors/spark` | `connectors/spark` |
| 支撑模块 | `examples/cli`, `ui`, `helm`, `docker`, `compose.yaml`, `docs`, `spec`, `etc`, `tests`, `integration-tests`, `dev` |

### 5.1 `api` 与代码生成

#### 5.1.1 功能与职责

路径：
- `api/all.yaml`
- `api/control.yaml`
- `api/delta.yaml`

功能：
- 定义 Unity Catalog 主 REST API
- 定义 control API
- 定义 Delta 相关 API
- 作为 Java client、Python client、server models、API docs 的生成源

输入：
- API 设计与对象模型定义
- 业务对象 schema
- Delta / control 协议扩展需求

输出：
- OpenAPI 规格文件
- 生成后的 API 文档
- 生成后的客户端 SDK 源码
- 生成后的服务端模型类

直接消费者：
- `clients/java`
- `clients/python`
- `serverModels`
- `controlModels`
- `apiDocs`

#### 5.1.2 源码链路

代码生成主链：

```text
api/all.yaml + api/control.yaml + api/delta.yaml
  -> build.sbt
  -> serverModels / controlModels / client / pythonClient / apiDocs
  -> server / clients/java / clients/python / docs
```

构建模块与产物映射：

| 构建模块 | 主要职责 | 主要输入 | 主要输出 |
|---|---|---|---|
| `controlApi` | 生成 control API Java client | `api/control.yaml` | Java API classes |
| `client` | 生成 Java client | `api/all.yaml`, `api/delta.yaml` | Java SDK |
| `pythonClient` | 生成 Python client | `api/all.yaml`, `api/delta.yaml` | Python SDK |
| `apiDocs` | 生成 Markdown API 文档 | OpenAPI specs | API docs |
| `server` | UC 核心服务 | 配置、模型、repository | server jar |
| `serverModels` | 生成服务端模型 | `all.yaml`, `delta.yaml` | server model classes |
| `controlModels` | 生成 control model | `control.yaml` | control model classes |
| `cli` | CLI 工具 | Java client、server test classes | CLI jar |
| `serverShaded` | Spark 测试所需 shading 产物 | server | shaded jar |
| `spark` | Spark connector | Java client、Spark API | Spark catalog plugin |
| `integrationTests` | 集成测试 | Spark module | integration test results |

#### 5.1.3 流程图

```mermaid
flowchart LR
    G1[api/all.yaml<br/>api/control.yaml<br/>api/delta.yaml] --> G2[build.sbt]
    G2 --> G3[serverModels]
    G2 --> G4[controlModels]
    G2 --> G5[client]
    G2 --> G6[pythonClient]
    G2 --> G7[apiDocs]
    G3 --> G8[server target models]
    G4 --> G9[control models]
    G5 --> G10[Java SDK]
    G6 --> G11[Python SDK]
    G7 --> G12[Markdown API docs]
```

### 5.2 `server` 总入口与请求分发

#### 5.2.1 功能与职责

路径：
- `server/src/main/java/io/unitycatalog/server`

功能：
- 启动 UC 核心服务
- 挂载 UC API、control API、Iceberg REST、Delta REST
- 负责元数据管理、权限校验、认证、临时凭证下发
- 负责与持久化层、云凭证、协议适配层协同

输入：
- HTTP 请求
- `etc/conf/server.properties` 配置
- OpenAPI 生成模型
- 数据库中的元数据与权限状态
- 外部身份令牌

输出：
- REST 响应
- 元数据状态变更
- 短期凭证
- 授权结果
- 协议兼容层响应

部署与本地运行相关的支撑目录也值得单独说明：
- `etc/conf` 放服务端配置、Hibernate 配置与密钥材料
- 默认本地运行使用 H2，`etc/conf/hibernate.properties` 指向 `etc/db/h2db`
- `etc/db` 还提供 MySQL 与 PostgreSQL 的示例配置，说明服务端并不只面向 H2
- `etc/data` 提供本地演示用的 managed / external tables、volumes 等预置数据，便于快速跑通样例与协议兼容链路

内部子模块：
- Service layer
- Repository / DAO layer
- Auth / Security layer
- Exception handling
- Credential vendors
- Iceberg / Delta service adapters

#### 5.2.2 源码链路

这部分是总体入口链在 `server` 模块视角下的展开。

核心类：
- `io.unitycatalog.server.UnityCatalogServer`

调用链：
1. `main()` 启动 `UnityCatalogServer.builder().port(...).build()`
2. `initializeServer()` 初始化 Hibernate，并构造 `Repositories` 作为 repository 聚合入口
3. `initializeServer()` 调用 `initializeAuthorizer()`，再通过 `addApiServices()` 挂载 UC/control API
4. `addApiServices()` 继续分派到 `addIcebergApiServices()` 与 `addDeltaApiServices()`
5. `addIcebergApiServices()` 初始化 `IcebergObjectMapper`、`MetadataService(new FileIOFactory(...))`、`TableConfigService(...)`
6. `addSecurityDecorators()` 在路由前挂认证与授权装饰器
7. 服务启动后接受 HTTP 请求

请求进入服务端的运行链：

```text
HTTP request
  -> Armeria route match
  -> AuthDecorator
  -> UnityAccessDecorator
  -> Annotated Service method
  -> Repository/DAO
  -> HttpResponse.ofJson(...)
```

关键类与方法：
- `UnityCatalogServer.main`
- `UnityCatalogServer.initializeServer`
- `UnityCatalogServer.addApiServices`
- `UnityCatalogServer.addIcebergApiServices`
- `UnityCatalogServer.addDeltaApiServices`
- `UnityCatalogServer.addSecurityDecorators`

#### 5.2.3 流程图

服务端总体模块图：

```mermaid
flowchart TD
    S0[UnityCatalogServer<br/>server/UnityCatalogServer.java] --> S1[addApiServices]
    S0 --> S2[addIcebergApiServices]
    S0 --> S3[addDeltaApiServices]
    S0 --> S4[addSecurityDecorators]

    S4 --> S5[AuthDecorator<br/>server/service/AuthDecorator.java]
    S4 --> S6[UnityAccessDecorator<br/>server/auth/decorator/UnityAccessDecorator.java]

    S1 --> S7[CatalogService]
    S1 --> S8[SchemaService]
    S1 --> S9[TableService]
    S1 --> S10[VolumeService]
    S1 --> S11[FunctionService]
    S1 --> S12[ModelService]
    S1 --> S13[PermissionService]
    S1 --> S14[CredentialService]
    S1 --> S15[ExternalLocationService]
    S1 --> S16[Temporary*CredentialsService]
    S1 --> S17[StagingTableService]
    S1 --> S18[AuthService]
    S1 --> S19[Scim2UserService / Scim2SelfService]

    S7 --> P1[persist/CatalogRepository.java]
    S8 --> P2[persist/SchemaRepository.java]
    S9 --> P3[persist/TableRepository.java]
    S10 --> P4[persist/VolumeRepository.java]
    S11 --> P5[persist/FunctionRepository.java]
    S12 --> P6[persist/ModelRepository.java]
    S15 --> P7[persist/ExternalLocationRepository.java]
    S17 --> P8[persist/StagingTableRepository.java]

    P1 --> D0[persist/dao/*]
    P2 --> D0
    P3 --> D0
    P4 --> D0
    P5 --> D0
    P6 --> D0
    P7 --> D0
    P8 --> D0
    D0 --> DB[(DB / Hibernate)]
```

服务端请求处理链图：

```mermaid
flowchart TD
    R1[HTTP Request] --> R2[Armeria Route Match<br/>UnityCatalogServer annotatedService]
    R2 --> R3[AuthDecorator.serve<br/>server/service/AuthDecorator.java]
    R3 --> R4[解析 Bearer/Cookie<br/>校验内部 JWT<br/>加载用户]
    R4 --> R5[UnityAccessDecorator.serve<br/>server/auth/decorator/UnityAccessDecorator.java]
    R5 --> R6[读取 @AuthorizeExpression<br/>@AuthorizeResourceKey<br/>@AuthorizeKey]
    R6 --> R7[KeyMapper.mapResourceKeys<br/>repositories.getKeyMapper]
    R7 --> R8[UnityAccessEvaluator.evaluate]
    R8 --> R9[具体 Service 方法<br/>如 CatalogService/TableService]
    R9 --> R10[Repository]
    R10 --> R11[DAO / TransactionManager]
    R11 --> R12[(DB)]
    R12 --> R13[HttpResponse.ofJson]
```

### 5.3 `server` 认证与授权

#### 5.3.1 功能与职责

代表组件：
- `AuthService`
- `AuthDecorator`
- `UnityAccessDecorator`
- `SecurityContext`
- `SecurityConfiguration`
- `JCasbinAuthorizer`
- `AllowingAuthorizer`

功能：
- 认证请求
- 从 JWT / cookie 提取身份
- 校验 issuer / audience / JWKS
- 在路由层实施认证和授权装饰器
- 计算资源访问权限

输入：
- OAuth token exchange 请求
- 访问令牌
- 用户信息
- policy 数据
- server security config

输出：
- UC access token
- cookie
- allow / deny 判定
- 认证失败 / 授权失败响应

#### 5.3.2 源码链路

高层认证流：

```text
External identity token
  -> /api/1.0/unity-control/auth/tokens
  -> issuer/audience/JWKS 验签
  -> principal 校验
  -> 生成 UC access token
  -> 返回 token 或 cookie
```

认证换 token 链：

1. 客户端把外部身份令牌提交给 `AuthService.grantToken()`
2. 服务端校验 grant type、requested token type、subject token type
3. 检查 `server.allowed-issuers` 与 `server.audiences`
4. 用 `JwksOperations.verifierForIssuerAndKey(...)` 按 issuer / keyId / alg 获取 verifier
5. 验证 JWT 签名与 audience
6. 调用 `verifyPrincipal()`，确认 subject 对应的用户在 UC 中存在且为 `ENABLED`
7. 通过 `securityContext.createAccessToken(decodedJWT)` 创建 UC 内部 token
8. 返回 JSON access token，或者按请求扩展写入 cookie

源码角色拆分：
- `AuthService.grantToken()`：编排整个 token exchange 过程
- `JwksOperations`：对接 JWKS / OIDC discovery
- `SecurityContext`：生成 UC 内部 access token
- `UserRepository`：验证用户状态

运行时鉴权链：

核心类：
- `AuthDecorator`
- `UnityAccessDecorator`
- `AuthorizedService`

`AuthDecorator` 做什么：
- 从 `Authorization: Bearer ...` 或 `UC_TOKEN` cookie 中读取 UC access token
- 解码 JWT
- 检查 issuer 必须是内部 issuer `INTERNAL`
- 使用内部 JWKS 验签
- 根据 subject 查询 `UserRepository`
- 确认用户存在且状态为 `ENABLED`
- 把 `DecodedJWT` 放入 `ServiceRequestContext` 属性中

`AuthDecorator` 调用链：

```text
HTTP request
  -> AuthDecorator.serve()
  -> read header/cookie
  -> JWT.decode(...)
  -> verify internal token signature
  -> load user by email
  -> ctx.setAttr(DECODED_JWT_ATTR, decodedJWT)
  -> delegate.serve(...)
```

`UnityAccessDecorator` 做什么：
- 找到当前请求最终命中的 annotated service method
- 读取方法上的 `@AuthorizeExpression`、`@AuthorizeResourceKey`、`@AuthorizeKey`
- 从 path / query / body 中提取资源 key 和普通参数
- 把资源 key 映射成真正的资源 ID
- 用 `UnityAccessEvaluator` 执行表达式求值
- 不通过时抛 `PERMISSION_DENIED`

`UnityAccessDecorator` 调用链：

```text
Authenticated request
  -> UnityAccessDecorator.serve()
  -> findServiceMethod(...)
  -> findAuthorizeExpression(...)
  -> findAuthorizeKeys(...)
  -> authorizeByRequest(...)
  -> keyMapper.mapResourceKeys(...)
  -> evaluator.evaluate(...)
  -> allow or deny
```

重点机制：
- `SYSTEM` source：系统级资源，如 metastore
- `PARAM` source：path / query 参数
- `PAYLOAD` source：request body 中的字段
- `keyMapper.mapResourceKeys(...)`：把 catalog / schema / table 名称、路径等解析成真实 UUID
- `ExternalLocationUtils.DATA_SECURABLE_TYPES`：用于判断路径是否与现有 data securable 冲突

`AuthorizedService` 的作用：
- `initializeBasicAuthorization(resourceId)`
- `initializeHierarchicalAuthorization(resourceId, parentId)`
- `removeAuthorizations(resourceId)`
- `removeHierarchicalAuthorizations(resourceId, parentId)`

这意味着资源创建后的授权初始化不是在 decorator 完成的，而是在具体业务 service 成功执行后，由 service 主动把新资源挂到权限层级中。

#### 5.3.3 流程图

```mermaid
flowchart LR
    A1[外部身份令牌] --> A2[AuthService.grantToken<br/>server/service/AuthService.java]
    A2 --> A3[JwksOperations verifier]
    A3 --> A4[issuer / audience 校验]
    A4 --> A5[UserRepository.getUserByEmail]
    A5 --> A6[SecurityContext.createAccessToken]
    A6 --> A7[UC Internal Access Token]
    A7 --> A8[后续业务请求]
    A8 --> A9[AuthDecorator]
    A9 --> A10[UnityAccessDecorator]
    A10 --> A11[业务 Service]
```

### 5.4 `server` 资源服务层与持久化层

#### 5.4.1 功能与职责

代表服务：
- `CatalogService`
- `SchemaService`
- `TableService`
- `VolumeService`
- `FunctionService`
- `ModelService`
- `PermissionService`
- `CredentialService`
- `ExternalLocationService`
- `MetastoreService`
- `DeltaCommitsService`
- `Temporary*CredentialsService`
- `Scim2UserService`
- `Scim2SelfService`

代表持久化组件：
- `Repositories`
- `CatalogRepository`
- `SchemaRepository`
- `TableRepository`
- `VolumeRepository`
- `FunctionRepository`
- `ModelRepository`
- `CredentialRepository`
- `ExternalLocationRepository`
- `MetastoreRepository`
- `UserRepository`
- `PropertyRepository`
- `DeltaCommitRepository`
- `dao/*`

补充说明：
- `Repositories` 是服务端 repository 的聚合与共享入口，负责把各类 repository 实例组织成统一依赖集合
- `PropertyRepository` 提供 catalog / schema / table 等对象 properties 的通用查询与辅助更新能力，被多个 repository 复用

功能：
- 承接 HTTP API
- 做参数校验、业务编排、权限控制入口
- 执行元数据读写
- 把服务层对象映射到数据库实体与 DAO
- 管理分页、事务、外部位置解析等存储侧逻辑

输入：
- JSON 请求体
- query / path 参数
- 当前用户身份
- 当前授权状态
- Hibernate session / transaction

输出：
- 业务对象响应
- 错误码与异常响应
- 持久化后的实体
- 查询结果
- 事务完成状态

#### 5.4.2 源码链路

高层元数据查询流：

```text
Client/UI/CLI
  -> UC REST API
  -> AuthDecorator
  -> UnityAccessDecorator
  -> Service
  -> Repository/DAO
  -> DB
  -> 返回业务对象
```

以 Catalog 创建为例：

核心类：
- `CatalogService`
- `CatalogRepository`

调用链：

```text
POST /catalogs
  -> AuthDecorator
  -> UnityAccessDecorator
  -> CatalogService.createCatalog()
  -> CatalogRepository.addCatalog(...)
  -> initializeBasicAuthorization(catalogId)
  -> HttpResponse.ofJson(catalogInfo)
```

`CatalogService.createCatalog()` 的职责：
- 用注解表达 catalog 创建的授权规则
- 调用 repository 完成持久化
- 调用 `initializeBasicAuthorization()` 给当前 principal 授予 OWNER

以 Table 创建为例：

核心类：
- `TableService`
- `TableRepository`
- `SchemaRepository`
- `CatalogRepository`
- `MetastoreRepository`

调用链：

```text
POST /tables
  -> AuthDecorator
  -> UnityAccessDecorator
  -> TableService.createTable()
  -> TableRepository.createTable(...)
  -> SchemaRepository.getSchema(...)
  -> initializeHierarchicalAuthorization(tableId, schemaId)
  -> HttpResponse.ofJson(tableInfo)
```

`TableService.createTable()` 的职责：
- 使用复杂的 `@AuthorizeExpression` 约束 catalog / schema / table / external location 权限
- 在 repository 创建成功后，将 table 权限节点挂到 schema 下面

`TableRepository.createTable()` 的职责：
- 校验 SQL object name
- 标准化 storage location
- 检查同名表是否已存在
- 按 table type 分流

分支一：`EXTERNAL`
- 验证不与 managed storage 冲突
- 生成新的 table UUID

分支二：`MANAGED`
- 检查 managed table feature 是否开启
- 强制要求 `DataSourceFormat.DELTA`
- 调用 `StagingTableRepository.commitStagingTable(...)`
- 把 staging table 的 UUID 作为最终 table ID

最后：
- 组装 `TableInfo`
- 转换为 `TableInfoDAO`
- 持久化 columns / properties / table record

#### 5.4.3 流程图

Catalog 模块内部调用图：

```mermaid
flowchart TD
    C1[POST /catalogs] --> C2[CatalogService.createCatalog<br/>service/CatalogService.java]
    C2 --> C3[@AuthorizeExpression<br/>metastore + external location 权限]
    C3 --> C4[CatalogRepository.addCatalog<br/>persist/CatalogRepository.java]
    C4 --> C5[DAO 持久化<br/>persist/dao/CatalogInfoDAO.java]
    C5 --> C6[(DB)]
    C4 --> C7[返回 CatalogInfo]
    C7 --> C8[AuthorizedService.initializeBasicAuthorization]
    C8 --> C9[JCasbinAuthorizer.grantAuthorization]
    C9 --> C10[HttpResponse.ofJson]
```

Table 模块内部调用图：

```mermaid
flowchart TD
    T1[POST /tables] --> T2[TableService.createTable<br/>service/TableService.java]
    T2 --> T3[@AuthorizeExpression<br/>catalog/schema/table/external location]
    T3 --> T4[TableRepository.createTable<br/>persist/TableRepository.java]
    T4 --> T5{TableType}
    T5 -->|EXTERNAL| T6[validateNotOverlapWithManagedStorage]
    T5 -->|MANAGED| T7[commitStagingTable]
    T6 --> T8[构造 TableInfo]
    T7 --> T8
    T8 --> T9[TableInfoDAO + PropertyDAO + ColumnDAO]
    T9 --> T10[(DB)]
    T10 --> T11[返回 TableInfo]
    T11 --> T12[SchemaRepository.getSchema]
    T12 --> T13[initializeHierarchicalAuthorization]
    T13 --> T14[authorizer.addHierarchyChild]
    T14 --> T15[HttpResponse.ofJson]
```

### 5.5 `server` 凭证与协议兼容层

#### 5.5.1 功能与职责

代表组件：
- `CloudCredentialVendor`
- `StorageCredentialVendor`
- `AwsCredentialVendor`
- `AzureCredentialVendor`
- `GcpCredentialVendor`
- `IcebergRestCatalogService`
- `DeltaRestCatalogService`
- `DeltaCommitsService`
- `MetadataService`
- `TableConfigService`

功能：
- 为 table、volume、model version、path 生成短期凭证
- 适配 AWS / Azure / GCP 的不同凭证语义
- 向 Iceberg REST Catalog 客户端暴露兼容接口
- 向 Delta REST Catalog / managed tables 工作流暴露兼容接口
- 处理 Delta commit 受理、版本递增校验、backfill、metadata/config 响应等协议动作

输入：
- 目标对象标识
- 操作类型，如 `READ`、`READ_WRITE`
- 外部位置与存储配置
- 服务端权限结果
- Iceberg / Delta 协议请求
- 表元数据

输出：
- 云厂商特定的临时凭证对象
- 存储访问配置
- 协议兼容响应
- 表配置
- metadata payload
- commit 相关响应

#### 5.5.2 源码链路

这一部分重点看服务端侧的 staging、commit、credential vending 和协议服务。

服务端 staging table 创建链：

核心类：
- `StagingTableService`
- `StagingTableRepository`

调用链：

```text
Spark -> createStagingTable API
  -> StagingTableService.createStagingTable()
  -> StagingTableRepository.createStagingTable(...)
  -> initializeHierarchicalAuthorization(stagingTableId, schemaId)
  -> 返回 StagingTableInfo
```

`StagingTableRepository.createStagingTable()` 关键动作：
1. 检查 managed table feature
2. 校验 table name
3. 生成 `stagingTableId`
4. 查找 catalog / schema DAO
5. 通过 `ExternalLocationUtils.getManagedStorageLocation(...)` 找到父 managed storage
6. 通过 `ExternalLocationUtils.getManagedLocationForTable(...)` 生成 staging path
7. 检查同名表与 staging location 冲突
8. 持久化 `StagingTableDAO`

服务端最终 commit staging table：

```text
TableRepository.createTable(...)
  -> if MANAGED:
       check feature enabled
       require DELTA format
       repositories.getStagingTableRepository().commitStagingTable(...)
       use stagingTableId as final tableId
```

`StagingTableRepository.commitStagingTable()` 关键检查：
- staging table 是否存在
- caller 是否为 staging table owner
- staging table 是否已经提交

通过后：
- `stageCommitted = true`
- 写入 commit 时间
- 返回 committed staging table DAO

临时凭证参与的位置：

```text
Temporary*CredentialsService
  -> resolve target object + operation
  -> authorize request
  -> choose Aws/Azure/Gcp credential vendor
  -> generate short-lived creds
  -> return storage access properties
```

协议兼容层的典型角色：
- `IcebergRestCatalogService`：给 Iceberg REST 客户端提供 catalog 兼容接口
- `DeltaRestCatalogService`：给 Delta REST / managed table path 提供控制平面接口
- `DeltaCommitsService`：暴露 Delta commit / getCommits REST 接口；具体的版本校验、backfill、UniForm 元数据更新等受理语义主要落在 `DeltaCommitRepository` 与 managed table 协议实现中

#### 5.5.3 流程图

凭证与协议兼容层总体图：

```mermaid
flowchart LR
    P1[Iceberg / Delta / Temporary Credentials API] --> P2[协议服务或 Credentials Service]
    P2 --> P3[Repository / DAO]
    P2 --> P4[Aws/Azure/Gcp Credential Vendor]
    P3 --> P5[(DB)]
    P4 --> P6[S3 / ADLS / GCS]
```

服务端 staging / commit 逻辑图：

```mermaid
flowchart TD
    G1[StagingTable API] --> G2[StagingTableService.createStagingTable]
    G2 --> G3[StagingTableRepository.createStagingTable]
    G3 --> G4[getCatalogAndSchemaDaoOrThrow]
    G4 --> G5[getManagedStorageLocation]
    G5 --> G6[getManagedLocationForTable]
    G6 --> G7[validateIfAlreadyExists]
    G7 --> G8[persist StagingTableDAO]
    G8 --> G9[(DB: staging table)]

    H1[CreateTable API] --> H2[TableService.createTable]
    H2 --> H3[TableRepository.createTable]
    H3 --> H4{MANAGED?}
    H4 -->|yes| H5[commitStagingTable]
    H5 --> H6[校验 staging table 存在/owner/未提交]
    H6 --> H7[stageCommitted=true]
    H7 --> H8[使用 stagingTableId 作为 final tableId]
    H8 --> H9[persist TableInfoDAO]
    H9 --> H10[(DB: final table)]
```

这部分工作流可结合 `spec/protocols/ManagedTablesSpec.md` 一起理解。该规格文件定义了 staged commit、ratified commit、版本递增校验、backfill 通知，以及 UniForm / Iceberg 元数据随 commit 一并提交和更新的约束，是 managed Delta tables 协议语义的重要依据。

### 5.6 `clients/java` 与 `clients/python`

#### 5.6.1 功能与职责

`clients/java`

构建模块：
- `client`

功能：
- 生成 Java SDK
- 提供对 UC API 和 Delta API 的 Java 客户端访问

输入：
- `api/all.yaml`
- `api/delta.yaml`

输出：
- Java SDK 源码与打包产物
- 供 CLI、Spark、外部 Java 应用调用的 client classes

直接消费者：
- `cli`
- `spark`
- 外部 Java 应用

`clients/python`

构建模块：
- `pythonClient`

功能：
- 生成 Python SDK
- 提供异步 Python client
- 与 `unitycatalog-ai` 等共享 `unitycatalog.*` namespace

输入：
- `api/all.yaml`
- `api/delta.yaml`

输出：
- Python SDK
- PyPI 可分发包

直接消费者：
- Python 应用
- `ai/core`
- 各类 AI integration packages

#### 5.6.2 源码链路

客户端生成与消费主链：

```text
api/*.yaml
  -> build.sbt
  -> client / pythonClient
  -> Java SDK / Python SDK
  -> CLI / Spark / Python apps / AI core
```

Java 侧典型消费链：

```text
api/all.yaml + api/delta.yaml
  -> client
  -> Java SDK
  -> examples/cli 或 connectors/spark
```

Python 侧典型消费链：

```text
api/all.yaml + api/delta.yaml
  -> pythonClient
  -> Python SDK
  -> ai/core 或外部 Python 应用
```

#### 5.6.3 流程图

```mermaid
flowchart LR
    A[api/all.yaml + api/delta.yaml] --> B[build.sbt]
    B --> C[client]
    B --> D[pythonClient]
    C --> E[Java SDK]
    D --> F[Python SDK]
    E --> G[CLI / Spark / Java apps]
    F --> H[AI Core / Python apps]
```

### 5.7 `ai/core` 与 `ai/integrations/*`

#### 5.7.1 功能与职责

`ai/core`

功能：
- 提供 Unity Catalog Functions 的高层 Python API
- 支持函数创建、读取、列举、执行
- 支持同步 / 异步调用
- 支持 wrapped functions
- 把 UC functions 作为 Agent tools 暴露给上层框架

输入：
- Python callable
- Function metadata
- UC API client
- catalog / schema / function 标识
- 函数执行参数

输出：
- `FunctionInfo`
- 执行结果
- 对 AI 集成层可复用的 toolkit / client 能力

注意：
- 函数执行在调用方环境中发生，不在 UC 服务器中远程沙箱执行
- 默认可采用 sandbox 模式限制 CPU、内存、超时和禁用模块

`ai/integrations/*`

代表模块：
- `openai`
- `anthropic`
- `langchain`
- `llama_index`
- `autogen`
- `crewai`
- `litellm`
- `gemini`
- `dspy`

功能：
- 把 `ai/core` 的函数能力适配到不同 LLM / agent 框架
- 降低 UC functions 被各家 agent runtime 消费的接入成本

#### 5.7.2 源码链路

高层 AI Function 调用流：

```text
AI App / Agent Framework
  -> ai/integrations/*
  -> ai/core client
  -> Python SDK / UC API
  -> 获取 FunctionInfo
  -> 本地或 sandbox 执行函数
  -> 返回 tool result
```

入口角色：
- `UnitycatalogFunctionClient`
- `BaseFunctionClient`
- `UnitycatalogClient`
- `FunctionsApi`
- `execution_utils.load_function_from_string`
- `executor.local_subprocess.run_in_sandbox_subprocess`

创建函数链：

```text
Python callable
  -> UnitycatalogFunctionClient.create_python_function(...)
  -> generate_function_info(...)
  -> create_function_async(...)
  -> FunctionsApi.create_function(...)
  -> UC server FunctionService / FunctionRepository
```

关键步骤：
1. `create_python_function_async()` 校验传入对象可调用
2. `generate_function_info(func)` 从 Python 函数提取 callable name、routine definition、return type、parameter info、comment/docstring
3. 拼接完整函数名 `<catalog>.<schema>.<callable_name>`
4. 调用 `create_function_async(...)`
5. `create_function_async(...)` 再把这些信息组装成 `CreateFunction`
6. 最终通过 `self.uc.functions_client.create_function(...)` 调服务端 API

获取函数元数据链：

```text
execute_function(function_name, params)
  -> BaseFunctionClient.execute_function(...)
  -> get_function(function_name)
  -> FunctionsApi.get_function(...)
```

`BaseFunctionClient.execute_function()` 先做：
1. `get_function(...)` 拉取函数元数据
2. `validate_input_params(...)` 根据 UC 中登记的参数定义校验调用参数
3. 最后进入 `_execute_uc_function(...)`

从 `FunctionInfo` 还原为可执行 Python 函数：

关键方法：
- `UnitycatalogFunctionClient._prepare_function_and_params()`
- `get_callable_definition(function_info)`
- `exec(python_function, self.func_cache)`

源码流程：
1. 先处理参数默认值 `process_function_parameter_defaults(...)`
2. 查本地 cache
3. 如果未命中：
   - 从 `FunctionInfo` 中提取 Python callable definition
   - 用 `exec(...)` 把源码装载到 `func_cache` 命名空间
   - 再对函数做 `lru_cache()` 包装
4. 返回函数对象与参数

执行链：local 模式

```text
execute_function(...)
  -> _execute_uc_function(...)
  -> _prepare_function_and_params(...)
  -> if execution_mode == LOCAL:
       func(**parameters)
  -> FunctionExecutionResult
```

执行链：sandbox 模式

```text
execute_function(...)
  -> _execute_uc_function(...)
  -> run_in_sandbox(...)
  -> run_in_sandbox_subprocess(...)
  -> generate temporary runner script
  -> subprocess.run([sys.executable, script_path, b64_params], ...)
  -> JSON result
  -> FunctionExecutionResult
```

`run_in_sandbox_subprocess()` 关键动作：
1. 取函数源码
2. 生成 runner script
3. 在脚本中设置资源限制：CPU limit、virtual memory limit、timeout
4. 把参数用 `cloudpickle + base64` 序列化
5. 用子进程执行
6. 子进程把结果输出为 JSON
7. 父进程解析 JSON，返回 success / error

直接获取源码或 callable 的链：

```text
get_function_source()
  -> get_function()
  -> dynamically_construct_python_function(function_info)

get_function_as_callable()
  -> get_function_source()
  -> load_function_from_string(...)
```

#### 5.7.3 流程图

AI Function 总体调用图：

```mermaid
flowchart TD
    A1[AI App / Notebook / Agent] --> A2[UnitycatalogFunctionClient<br/>ai/core/client.py]
    A2 --> A3[FunctionsApi<br/>clients/python generated SDK]
    A3 --> A4[FunctionService<br/>server/service/FunctionService.java]
    A4 --> A5[FunctionRepository<br/>server/persist/FunctionRepository.java]
    A5 --> A6[(DB: function metadata)]
    A2 --> A7[prepare function and params]
    A7 --> A8[get callable definition]
    A8 --> A9[rebuild callable in client runtime]
    A9 --> A10{ExecutionMode}
    A10 -->|local| A11[本地 Python 进程执行]
    A10 -->|sandbox| A12[local_subprocess.py 子进程执行]
    A11 --> A13[FunctionExecutionResult]
    A12 --> A13
```

AI Function 创建链细化图：

```mermaid
flowchart LR
    F1[Python callable] --> F2[generate_function_info<br/>ai/core/utils/callable_utils_oss.py]
    F2 --> F3[routine_definition + parameters + return type]
    F3 --> F4[UnitycatalogFunctionClient.create_function_async]
    F4 --> F5[CreateFunction / CreateFunctionRequest]
    F5 --> F6[self.uc.functions_client.create_function]
    F6 --> F7[server/service/FunctionService]
    F7 --> F8[persist/FunctionRepository]
    F8 --> F9[(DB)]
```

AI Function 执行链细化图：

```mermaid
flowchart TD
    E1[execute function request] --> E2[BaseFunctionClient.execute_function<br/>ai/core/base.py]
    E2 --> E3[get function metadata]
    E3 --> E4[FunctionsApi.get_function]
    E4 --> E5[FunctionInfo]
    E5 --> E6[validate input params]
    E6 --> E7[execute uc function]
    E7 --> E8[prepare function and params]
    E8 --> E9[get callable definition]
    E9 --> E10[load callable into func cache]
    E10 --> E11{ExecutionMode}
    E11 -->|LOCAL| E12[local runtime execution]
    E11 -->|SANDBOX| E13[run in sandbox]
    E13 --> E14[executor/local_subprocess.py]
    E14 --> E15[生成 runner script]
    E15 --> E16[subprocess.run]
    E16 --> E17[JSON result]
    E12 --> E18[FunctionExecutionResult]
    E17 --> E18
```

### 5.8 `connectors/spark`

#### 5.8.1 功能与职责

构建模块：
- `spark`

功能：
- 实现 Spark Catalog 插件
- 通过 UC API 管理 namespaces 与 tables
- 对 managed Delta table 执行 staging、table ID 传递、凭证下发
- 支持 credential renew 与 credential-scoped filesystem

输入：
- Spark catalog 配置
- UC endpoint
- token provider
- 表定义与操作请求
- temporary credentials API 返回

输出：
- Spark 可识别的 catalog / table 行为
- 表创建 / 替换 / 读取路径
- Hadoop / FS credential properties

关键意义：
- 它让 UC 真正进入 Spark 执行链路，而不是只停留在元数据登记层

#### 5.8.2 源码链路

高层调用链：

```text
Spark CREATE TABLE
  -> UCSingleCatalog.createTable(...)
  -> stageManagedDeltaTableAndGetProps(...)
  -> tablesApi.createStagingTable(...)
  -> temporaryCredentialsApi.generateTemporaryTableCredentials(...)
  -> delegate.createTable(...)
  -> UCProxy.createTable(...)
  -> tablesApi.createTable(...)
  -> server TableService / TableRepository
```

`UCSingleCatalog.createTable()` 对 managed Delta table 的处理：
1. 判断这是 UC managed Delta create path
2. 调用 `validateManagedDeltaCreateProperties()`
3. 调用 `stageManagedDeltaTableAndGetProps()`

`stageManagedDeltaTableAndGetProps()` 具体做：
1. 构造 `CreateStagingTable`
2. 调用 `tablesApi.createStagingTable(...)`
3. 得到 `stagingLocation` 与 `stagingTableId`
4. 把这些信息写入 Spark 侧 table properties：
   - `PROP_LOCATION`
   - `UC_TABLE_ID_KEY`
   - `PROP_IS_MANAGED_LOCATION = true`
5. 调用 `temporaryCredentialsApi.generateTemporaryTableCredentials(...)`
6. 把返回凭证转为 Hadoop / FS credential properties
7. 交给 delegate catalog 继续执行真正建表

`UCProxy.createTable()` 如何把 Spark 元数据变成 UC API 请求：
1. 构造 `CreateTable`
2. 从 Spark schema 生成 `ColumnInfo` 列表
3. 从 properties 决定 `TableType`
4. 设置 `storageLocation`
5. 设置 `dataSourceFormat`
6. 过滤掉纯 V2 table properties
7. 调用 `tablesApi.createTable(createTable)`
8. 调用 `loadTable(ident)` 再把服务端视图加载回 Spark

`UCProxy.loadTable()` 如何为运行时读取补齐凭证：
1. `tablesApi.getTable(...)` 拉取元数据
2. 读取 `storageLocation` 与 `tableId`
3. 调用 `generateTemporaryTableCredentials(...)`
4. 用 `CredPropsUtil.createTableCredProps(...)` 转成 Spark / Hadoop 可消费的配置
5. 组装 `CatalogTable`
6. 返回 `V1Table`

说明：
- Spark 侧负责 staging、携带 table ID、消费临时凭证
- 服务端侧负责真正 commit staging table 与写入最终表元数据
- 服务端细化逻辑见 5.5.2

#### 5.8.3 流程图

Managed Delta 建表总体图：

```mermaid
flowchart TD
    M1[Spark SQL CREATE TABLE] --> M2[UCSingleCatalog.createTable<br/>connectors/spark/UCSingleCatalog.scala]
    M2 --> M3[stageManagedDeltaTableAndGetProps]
    M3 --> M4[TablesApi.createStagingTable]
    M4 --> M5[StagingTableService.createStagingTable]
    M5 --> M6[StagingTableRepository.createStagingTable]
    M6 --> M7[(DB: staging table)]
    M4 --> M8[返回 stagingLocation + stagingTableId]
    M8 --> M9[TemporaryCredentialsApi.generateTemporaryTableCredentials]
    M9 --> M10[TemporaryTableCredentialsService]
    M10 --> M11[service/credential/*]
    M11 --> M12[云临时凭证]
    M8 --> M13[delegate.createTable]
    M13 --> M14[UCProxy.createTable]
    M14 --> M15[TablesApi.createTable]
    M15 --> M16[TableService.createTable]
    M16 --> M17[TableRepository.createTable]
    M17 --> M18[StagingTableRepository.commitStagingTable]
    M18 --> M19[(DB: final table)]
```

Managed Delta 建表细化图：Spark 侧内部逻辑

```mermaid
flowchart TD
    S1[UCSingleCatalog.createTable] --> S2{is managed Delta?}
    S2 -->|yes| S3[validateManagedDeltaCreateProperties]
    S3 --> S4[stageManagedDeltaTableAndGetProps]
    S4 --> S5[CreateStagingTable request]
    S5 --> S6[tablesApi.createStagingTable]
    S6 --> S7[stagingLocation / stagingTableId]
    S7 --> S8[temporaryCredentialsApi.generateTemporaryTableCredentials]
    S8 --> S9[CredPropsUtil.createTableCredProps]
    S9 --> S10[把 location/tableId/cred props 写入 properties]
    S10 --> S11[delegate.createTable]
    S11 --> S12[UCProxy.createTable]
    S12 --> S13[CreateTable REST 请求]
    S13 --> S14[tablesApi.createTable]
    S14 --> S15[loadTable]
```

### 5.9 支撑模块：`examples/cli`、`ui`、`helm`、`docker` / `compose.yaml`、`docs`、`integration-tests`

#### 5.9.1 功能与职责

`examples/cli`

构建模块：
- `cli`

入口脚本：
- `bin/uc`

功能：
- 提供命令行访问入口
- 浏览 catalog / schema / table
- 读 Delta table 内容
- 执行管理类操作

`ui`

功能：
- 提供 Unity Catalog 的 Web UI
- 展示 catalog 资产内容与基础交互入口

`helm`

功能：
- 提供 Kubernetes 部署模板
- 包含 server 与 ui 的部署清单

`docker` / `compose.yaml`

功能：
- 提供本地或轻量环境部署入口
- 快速拉起 server + ui

`docs`

功能：
- 使用说明
- 部署文档
- AI 集成文档
- 服务器配置说明

`integration-tests`

功能：
- 对 Spark connector 和系统集成路径做验证
- 覆盖 Delta、对象存储、跨版本行为

#### 5.9.2 源码链路

访问型支撑链：

```text
CLI / UI
  -> Java SDK 或浏览器请求
  -> UC Server API
  -> Service / Repository
  -> DB 或对象存储
```

交付型支撑链：

```text
Helm values / Docker config
  -> server + ui 部署清单
  -> 本地环境或 Kubernetes 集群
  -> 可运行的 UC 环境
```

验证型支撑链：

```text
integration-tests
  -> Spark module / server 产物
  -> Delta / object storage / version matrix
  -> 集成测试结果
```

#### 5.9.3 流程图

```mermaid
flowchart TD
    A[CLI / UI] --> B[UC Server API]
    B --> C[Service / Repository]
    C --> D[(DB / Object Storage)]

    E[Helm / Docker / Compose] --> F[部署 server + ui]
    F --> G[本地环境或 Kubernetes 集群]

    H[Docs] --> I[用户与开发者]

    J[Integration Tests] --> K[Spark + Server + Storage]
    K --> L[兼容性与回归信号]
```

## 6. 源码级观察结论

### 6.1 从整体图看项目的三个重心

1. `server/`
- 负责控制平面核心逻辑
- 入口、认证、授权、元数据、凭证、协议兼容都在这里收敛

2. `connectors/spark/`
- 负责把控制平面嵌入实际数据执行链
- 尤其是 managed Delta 的 staging 与 credential vending

3. `ai/core/`
- 负责把函数资产变成 AI runtime 可执行工具
- 但执行仍然发生在客户端侧

### 6.2 服务端的真正中心不是 Controller，而是 Decorator + Repository

UC 的业务 service 看起来像常规 controller，但真正的控制点有两个：
- decorator 层负责“你能不能调”
- repository 层负责“对象到底怎么被创建和提交”

特别是 managed table 的核心状态转换，不是在 Spark 侧完成，而是在服务端 repository 中完成。

### 6.3 权限模型深度嵌入资源层级

资源权限不是简单 RBAC 判断，而是：
- principal
- 资源层级关系
- 外部位置路径映射
- 操作类型
- SpEL 表达式求值

这让它比“注解 + if 判断”更像一个小型策略执行系统。

### 6.4 Spark connector 是控制平面落地的关键执行桥

如果没有 `UCSingleCatalog` / `UCProxy` 这一层，UC 仍然只是目录服务。真正让它进入数据写入路径的是：
- staging table
- table ID
- temporary credentials
- load / create table 双向 API 往返

### 6.5 AI Functions 的执行边界非常重要

AI Functions 的核心不是“在 UC server 上执行函数”，而是：
- server 保存定义
- client 取回定义
- client 本地或子进程执行

这意味着：
- 治理中心在服务端
- 执行安全边界在客户端
- 生产安全性强依赖调用方部署方式
