# Apache Gravitino 调研报告

## 1. 一句话结论

Apache Gravitino 的核心定位不是“又一个单格式 Catalog”，而是一个 **federated metadata lake / catalog-of-catalogs**：
它试图把多种元数据源、资产类型和地域分布统一到一个控制面里，再通过统一对象模型、统一 API、统一权限治理能力向上暴露。

如果自研项目的目标是 **统一管理 Iceberg + HMS + JDBC + Files + Model 等多类资产与多套 catalog**，Gravitino 很有参考价值；
如果目标只是做一个 **尽可能薄、尽可能原生的 Iceberg Catalog**，那么 Gravitino 的抽象层会偏厚。

---

## 2. A. 定位与设计目标

### 2.1 项目起源与治理

- Apache Gravitino 目前是 **ASF（Apache Software Foundation）项目**，主仓库为 `apache/gravitino`。
- GitHub 仓库创建时间为 **2023-04-23**。
- 项目主页将其描述为：
  - **high-performance**
  - **geo-distributed**
  - **federated metadata lake**
- 仓库说明强调其目标是构建“开放数据目录 / 元数据湖”，统一管理不同来源、不同类型、不同地域的数据与 AI 资产元数据。

### 2.2 初始痛点

从 README 和 overview 文档看，Gravitino 要解决的核心问题是：

1. 企业里存在多套异构 metadata source，彼此割裂；
2. 传统数据目录偏“采集/同步”，而不是“直接管理”；
3. 数据资产与 AI 资产往往分属不同系统，缺少统一控制面；
4. 多地域、多云部署下，元数据治理缺少统一视图；
5. 权限、审计、发现等治理能力在不同系统里重复建设。

### 2.3 目标用户与典型场景

适合的典型场景：

- 多 catalog / 多引擎 / 多地域的元数据统一治理；
- 湖仓 + 数仓 + 文件 + AI 资产的统一元数据入口；
- 希望把权限、审计、发现下沉到统一控制面的组织；
- 希望通过 Trino / Spark / Flink / Iceberg REST 等方式统一接入元数据的团队。

### 2.4 设计目标与非目标

**设计目标：**

- 统一元数据对象模型；
- 统一 REST/API 访问；
- 统一治理（access control / auditing / discovery）；
- 支持联邦式、多源、跨地域元数据视图；
- 兼容多引擎访问；
- 逐步纳入 AI 资产管理。

**隐含非目标：**

- 它不是纯粹的 data plane 系统；
- 它不是只为 Iceberg 设计的最薄 catalog；
- 它不等同于通用跨系统全局事务协调器；
- 它不是完整 ML 平台，AI 侧当前更多是元数据建模与登记能力。

---

## 3. B. 核心概念与元数据模型

### 3.1 顶层抽象

Gravitino 的核心对象模型包括：

- **Metalake**：元数据容器 / 租户边界；
- **Catalog**：某一类元数据源的集合；
- **Schema**：二级命名空间；
- **Table**：关系型/表格类对象；
- **Fileset**：文件集合对象；
- **Model**：模型元数据对象；
- **Topic**：消息队列主题对象。

这说明它不是传统的 `catalog.schema.table` 单一路线，而是把“表以外的资产”也提升为一等对象。

### 3.2 统一建模思路

Gravitino 的思路是：

- 不同 source/type 的元数据先映射到统一对象模型；
- 再由统一 API、统一权限模型、统一治理层向上提供服务；
- 底层仍通过 connector 连接具体元数据源。

这类建模适合“联邦 catalog”，但天然会遇到“公共抽象”和“后端差异语义”之间的张力。

### 3.3 表格式与资产类型支持

从官方文档和 Iceberg REST 文档看，Gravitino Server 的 managed table type 可覆盖：

- JDBC
- Hive
- Iceberg
- Hudi
- Paimon
- 以及 fileset / model / topic 等非表对象

其中要注意：

- **Iceberg REST Server** 是其面向 Iceberg 生态的重要兼容面；
- 但 **Gravitino Server 的范围明显大于 Iceberg REST Catalog**。

### 3.4 AI/ML 资产建模

README 明确写了 **AI Asset Management (WIP)**，overview 也写到“unify data and AI assets”。

当前更可确认的能力是：

- 有 **Model catalog**；
- 支持 **model** 与 **model version** 的注册、列出、删除、链接等操作；
- 更偏向“模型资产元数据管理”，而不是完整的 model serving / feature serving 平台。

所以从调研角度应将其视为：

- **AI 资产纳入统一元数据控制面的早期能力**，
- 而非已经成熟覆盖 Feature Store / Model Registry / Vector Store 全栈。

---

## 4. C. 架构与关键设计

### 4.1 分层结构

官方 overview 给出的架构层次包括：

- **Functionality Layer**：元数据管理、治理能力；
- **Interface Layer**：标准 REST API，未来计划支持 Thrift / JDBC；
- **Core Object Model**：统一元数据抽象；
- **Connection Layer**：连接 Hive、MySQL、PostgreSQL 等不同 metadata source 的 connector。

### 4.2 Control Plane 定位

Gravitino 更像 **统一 metadata control plane**：

- 负责对象抽象、治理、访问控制、审计、发现；
- 不直接替代底层数据系统；
- 通过 connector “直连”底层 source，而不是靠离线同步采集。

这点和很多“data catalog”产品不同：

- 它强调 **direct metadata management**；
- “changes in Gravitino directly reflect in the underlying systems, and vice versa”。

### 4.3 联邦 / Catalog-of-Catalogs 设计

它的核心价值就在这里：

- 一个 Gravitino 可统一承接多种 catalog backend；
- 用户从上层看到的是统一模型与统一访问面；
- 底层仍可能是 Hive、JDBC、文件系统、模型系统等异构后端。

这对自研项目的启示是：

- 若目标是统一 control plane，Gravitino 的分层值得借鉴；
- 若目标是极致简洁与高性能的单用途 catalog，联邦抽象会带来复杂度与性能代价。

### 4.4 统一治理架构图（文字版）

```text
                +-----------------------------------+
                |            Client / Engine        |
                | Spark / Trino / Flink / Python    |
                | Iceberg REST Client / UI / API    |
                +-----------------+-----------------+
                                  |
                                  v
                +-----------------------------------+
                |       Interface Layer             |
                | Gravitino REST API / Iceberg REST |
                +-----------------+-----------------+
                                  |
                                  v
                +-----------------------------------+
                |    Governance / Control Plane     |
                | AuthN / AuthZ / Audit / Discovery |
                | Namespace / Policy / Credential   |
                +-----------------+-----------------+
                                  |
                                  v
                +-----------------------------------+
                |       Core Object Model           |
                | Metalake / Catalog / Schema       |
                | Table / Fileset / Model / Topic   |
                +-----------------+-----------------+
                                  |
                    +-------------+-------------+
                    |             |             |
                    v             v             v
         +----------------+ +-------------+ +----------------+
         | Hive Connector | | JDBC Conn.  | | Files/Model/...|
         +--------+-------+ +------+------+ +--------+-------+
                  |                |                  |
                  v                v                  v
         +----------------+ +-------------+ +----------------+
         | HMS / Hive Met.| | MySQL/PG... | | S3/HDFS/Model  |
         | Iceberg/Hudi...| | relational  | | systems/Topic  |
         +----------------+ +-------------+ +----------------+
```

### 4.5 一个请求是怎么走的

以“客户端访问某张 Iceberg 表”为例，典型路径可以概括为：

1. 客户端通过 **Gravitino REST** 或 **Iceberg REST** 发请求；
2. 接口层完成协议适配，把请求映射到统一对象模型；
3. 治理层执行认证、鉴权、审计、策略检查；
4. 核心模型层把目标对象解析为某个 `metalake/catalog/schema/table`；
5. 连接层根据 catalog 类型把请求路由到具体 backend；
6. backend 返回底层元数据，必要时由 Gravitino 做统一封装；
7. 若涉及对象存储访问，还可能经由 credential vending 下发临时访问凭证；
8. 最终结果再按客户端所使用的协议返回。

这条路径说明，Gravitino 的统一治理并不是“把所有元数据搬进自己内部重写一遍”，而是：

- 用统一控制面承接身份、权限、审计、对象视图；
- 用 connector 把真实读写和具体元数据语义落到不同后端。

因此它本质上是 **治理统一 + 访问统一**，而不是 **物理存储统一 + 语义完全同构**。

### 4.6 一致性与事务模型


这是评估时需要特别谨慎的点。

从官方 Iceberg REST 文档看，它列出了 **multi table transaction** 能力；但从整体架构看，应区分两层：

1. **Iceberg REST / 特定 backend 能力层**：
   - 某些能力可在 Iceberg 场景成立；
2. **统一联邦抽象层**：
   - 不应直接推断为“跨所有异构 catalog 的全局事务协调器”。

因此较稳妥的判断是：

- 它在 Iceberg REST 侧已把多表事务作为目标能力点之一；
- 但 **跨异构 source 的统一全局 ACID 事务** 不是当前应默认假设的能力。

### 4.7 缓存与读写路径

Iceberg REST 文档提到：

- 提供 **pluggable cache system**；
- 缓存表元数据时，会回到 catalog backend 校验 metadata location 以保证正确性。

这说明其思路不是“盲缓存”，而是尽量在缓存收益和元数据正确性之间平衡。

### 4.8 部署形态

可确认的部署方式包括：

- 独立 Gravitino Server；
- Docker Compose Playground；
- 单独的 **Iceberg REST Server** 分发包；
- 单独的 **Lance REST Server** 分发包；
- Trino connector 分发包。

说明它不是一个“嵌入式库”，而是偏服务化部署。

---

## 5. D. 协议与接口

### 5.1 对外协议

已明确支持或规划中的接口：

- **Gravitino unified REST API**；
- **Iceberg REST API spec**（作为原生 Iceberg REST catalog service）；
- Lance REST catalog service；
- 文档中写明未来计划支持 **Thrift** 与 **JDBC** 接口。

### 5.2 Iceberg REST 的关系

这是 Gravitino 调研里最重要的边界之一。

Gravitino 文档明确区分：

- **Gravitino Iceberg REST server**：接口遵循 Iceberg REST API，管理对象是 Iceberg table only；
- **Gravitino server**：接口是 Gravitino unified interfaces，管理对象范围更大，涵盖 JDBC/Hive/Iceberg/Hudi/Paimon 等。

所以：

- Gravitino **兼容并提供 Iceberg REST**；
- 但 **Gravitino 不等于 Iceberg REST Catalog**；
- Iceberg REST 只是其北向兼容面之一。

### 5.3 Credential Vending 与数据访问

Iceberg REST 文档里这块能力比较完整：

- 支持 **credential vending**；
- 支持 `vended-credentials` 与 `remote-signing` 形式的数据访问模式；
- 支持 S3 / GCS / OSS / ADLS；
- 支持 Basic auth、OAuth2、HTTPS；
- Hive backend 可走 Kerberos。

这对湖仓 Catalog 很关键，因为它说明 Gravitino 不只是 metadata API，还考虑了对象存储访问凭证下发问题。

### 5.4 SDK / 客户端生态

从文档表述看，其访问面至少覆盖：

- REST API；
- Spark；
- Trino；
- Flink；
- Python clients；
- Daft；
- Iceberg REST clients。

但与成熟商业产品相比，SDK 的完整度与语言覆盖仍需结合实际源码和 API 使用体验进一步验证。

---

## 6. E. 功能矩阵观察

### 6.1 强项

**1）联邦元数据统一建模**
- 这是 Gravitino 最强的差异点；
- 能把 Hive / JDBC / 文件 / 模型 / Topic 等拉到统一控制面。

**2）统一权限治理**
- 提供 unified access control；
- 支持 RBAC；
- 支持用户、组、角色；
- 支持 deny-by-default；
- 支持 privilege inheritance；
- 支持 ALLOW / DENY 条件；
- 支持把授权下推到底层 authorization plugin，文档明确提到可对接 Apache Ranger。

**3）Iceberg REST 能力较完整**
- 原生提供 Iceberg REST server；
- 支持多 catalog backend；
- 支持 credential vending；
- 支持 OAuth2 / HTTPS；
- 支持 audit log；
- 支持多存储后端。

**4）AI 资产统一入口方向明确**
- 已有 model catalog；
- README 明确将 AI asset management 作为目标方向。

### 6.2 相对弱项或未见成熟证据点

**1）不是 Git-like catalog**
- 不像 Nessie 那样以 branch/tag/merge 为第一设计中心；
- 如果自研方向强依赖 Git-like 数据版本控制，Gravitino 不是最典型参考对象。

**2）高级 AI/ML 资产能力仍偏早期**
- 未见官方文档明确覆盖 feature store、vector store、serving API、MLflow 级集成闭环；
- 当前更适合看作“模型元数据纳管”。

**3）跨异构源事务边界要谨慎**
- 不能把“多表事务”简单外推成“跨所有 catalog/source 的统一事务系统”。

**4）行列级策略能力需要进一步实测**
- 权限模型很丰富，但从公开文档更容易确认对象级/操作级授权；
- 行级过滤、列级 masking 并未在当前已核对材料中看到同等明确的成熟说明。

---

## 7. F. 非功能特性

### 7.1 性能

官方自我定位强调 **high-performance**，但当前公开资料里，我没有看到足够系统的 benchmark 数据用于直接量化对比 Polaris / Nessie / HMS。

因此更合理的判断是：

- 它在产品定位上强调性能；
- 但联邦抽象、统一鉴权、connector 层都意味着性能表现会高度依赖具体 backend、缓存配置和访问路径。

### 7.2 可用性与扩展性

- 文档明确写了 **geo-distribution support (WIP)**；
- 这说明“跨地域统一元数据视图”是路线方向，但不能把它视为已完全成熟、无争议的能力；
- 其 connector-based 架构天然适合横向扩展 metadata source 类型。

### 7.3 可观测性

- Iceberg REST 文档明确有 **audit log**；
- 作为控制面产品，审计是强项之一；
- 但关于 metrics / tracing 的成熟度，还需要进一步看部署文档和运维文档。

### 7.4 安全

可确认项包括：

- OAuth2；
- HTTPS；
- Basic auth；
- Kerberos（Hive backend）；
- RBAC；
- deny-by-default；
- privilege inheritance；
- ALLOW / DENY 语义；
- 可对接底层授权系统与 Ranger；
- authorization cache 以及自动失效。

但也要注意文档里的一个边界：

- access-control 文档明确写到，Gravitino 只对其支持的 securable objects 做 authorization；
- 并且“metadata authentication”表述存在边界，说明安全模型并不是“所有路径、所有后端都完全由 Gravitino 接管”。

---

## 8. G. 生态与集成

### 8.1 计算引擎

官方明确提到或文档覆盖到：

- Trino
- Spark
- Flink
- Daft

README 强调“multi-engine compatibility”，且无需改 SQL dialects 即可接入部分引擎场景。

### 8.2 后端/数据源

overview 和文档明确出现的元数据源/后端包括：

- Apache Hive
- MySQL
- PostgreSQL
- JDBC
- HDFS
- S3
- GCS
- OSS
- ADLS
- 以及 message queue / topic 相关对象

### 8.3 表格式与协议

可确认关联到：

- Iceberg
- Hudi
- Paimon
- Lance

其中 Iceberg 支持最明确、资料最完整。

### 8.4 安全与外部系统

文档明确提到：

- Apache Ranger 集成方向；
- OAuth2；
- Kerberos；
- 各类云存储 credential provider。

---

## 9. H. 运维与落地成本

### 9.1 部署依赖

落地上至少需要考虑：

- 独立服务部署；
- 后端 metadata store / catalog backend；
- connector 配置；
- 权限配置；
- 对象存储 credential provider 配置；
- 若启用 Iceberg REST，则还需额外考虑 client-side / warehouse / backend 组合配置。

### 9.2 资源与复杂度

其收益和成本都很明显：

**收益：**
- 多源统一治理；
- 减少 catalog 碎片化；
- 为多引擎提供统一入口。

**成本：**
- 引入一个额外的 control plane；
- 引入 connector 与统一权限模型的运维复杂度；
- 联邦语义天然更复杂，排障路径也更长。

### 9.3 文档完备度

从仓库 docs 目录和 README 看，文档覆盖面较广，包含：

- overview
- catalog 文档
- security 文档
- Iceberg REST 文档
- connector 文档
- build / test / docker image 等运维类文档

这点对落地比较友好。

---

## 10. I. 社区与治理

截至本次调研时（2026-04-17）从 GitHub API 可见：

- 仓库：`apache/gravitino`
- 创建时间：**2023-04-23**
- 最近更新时间：**2026-04-16**
- Stars：**2922**
- Forks：**800**
- Open issues：**847**
- 默认分支：`main`

从这些指标看：

- 社区不是“无人维护”的冷项目；
- 近期仍然活跃；
- issue 数量也说明项目面较广、复杂度较高，成熟度与维护压力并存。

近期 release 也较密集，例如：

- `v1.2.0`：2026-03-13 发布
- `v1.1.1`：2026-04-01 发布

说明其发布节奏较积极。

---

## 11. J. License 与商业化

- 开源协议：**Apache License 2.0**
- 这对二次开发、私有部署、商用服务化都比较友好；
- 也符合本项目“允许 fork 或深度二次开发”的调研约束。

从公开材料看，当前更适合把它视为：

- 一个可 fork、可二开、可自建控制面的 ASF 基础设施项目；
- 而不是强商业限制或 source-available 路线产品。

---

## 12. K. Roadmap 与趋势

从 README、overview、最近 release 包和文档结构看，主线趋势比较清晰：

1. **继续扩展统一元数据控制面**
   - 覆盖更多 source / asset type；

2. **继续强化 Iceberg / REST 兼容能力**
   - Iceberg REST server 已是独立分发的重要组成；

3. **继续推进 AI 资产管理**
   - 目前是 WIP，但方向非常明确；

4. **继续增强跨地域 / 多云支持**
   - geo-distribution 已写入定位，但仍带 WIP 标记；

5. **继续丰富多引擎接入**
   - Trino、Spark、Flink、Daft 已进入公开叙事。

---

## 13. L. 已知缺陷与局限

### 13.1 联邦系统的共性局限

Gravitino 最大的价值，也是它最大的复杂度来源：

- 不同底层 source 的对象模型、DDL、事务语义、权限系统、错误模型都不同；
- Gravitino 通过统一抽象把它们拉平，但很难保证所有高级语义完全等价。

因此要警惕以下问题：

- 统一模型是否会丢失底层特性；
- 不同 backend 的行为是否真正一致；
- 权限语义是否完全可下推；
- 排障时到底是 Gravitino 层、connector 层，还是底层 source 层的问题。

### 13.2 Iceberg REST 授权存在部署边界

Iceberg REST 文档明确写到：

- **IRC authorization is not supported for standalone Iceberg REST server deployments**；
- 需要与 Gravitino server 配合的 dynamic configuration provider 场景才能做这类授权。

这说明：

- 某些治理能力依赖完整的 Gravitino 控制面；
- 单独部署 Iceberg REST server 并不能自动获得全部统一治理能力。

### 13.3 AI/ML 资产能力仍处早期

虽然方向正确，但当前公开能力更像：

- model / model version 元数据管理；
- 还不是成熟的 feature store / model serving / vector governance 平台。

### 13.4 事务能力边界需严格核实

虽然 Iceberg REST 文档列出 multi table transaction，但在做自研方案参考时仍应继续做源码级确认：

- 范围是否只适用于特定 backend / 特定对象类型；
- 与联邦统一模型之间的语义边界在哪里；
- 是否能满足你们预期的“跨表 / 跨 catalog / 跨 source”事务需求。

---

## 14. 对自研 Catalog Service 的设计启示

### 14.1 值得借鉴的点

**1）Metalake / Catalog / Schema / Object 的分层**
- 对多租户、多域治理比较清晰；
- 比单层 catalog 更适合作为企业级控制面抽象。

**2）Catalog-of-Catalogs 思路**
- 如果你们内部本来就存在多套元数据系统，这种统一控制面思路很有价值；
- 尤其适合“先统一治理，再逐步收敛底层实现”的演进路线。

**3）统一权限模型**
- RBAC + 继承 + deny-by-default + ALLOW/DENY 的组合较完整；
- 很适合作为 control plane 权限系统参考。

**4）Credential Vending 进入 Catalog 设计范围**
- 这是现代湖仓 Catalog 的关键能力，不能只停留在 metadata CRUD 层。

**5）把 AI 资产纳入对象模型**
- 即便短期不实现完整 AI 平台，也应在对象模型上给 model / feature / vector 等留扩展位。

### 14.2 需要慎重的点

**1）是否真的要走联邦抽象**
- 联邦非常强大，但抽象层会显著增加复杂度；
- 如果你们短期只服务 Iceberg，未必值得一开始就做厚。

**2）事务语义不要超前承诺**
- “统一 control plane” 不等于“天然支持跨源事务”；
- 事务能力最好按对象类型和 backend 分级设计。

**3）AI 资产不要一开始做得过大**
- 可以先预留对象模型，再按真实需求逐步打开功能。

---

## 15. 结论

对本次调研目标而言，Apache Gravitino 最值得看的不是“它是不是最好的 Iceberg Catalog”，而是：

> **它如何把湖仓 Catalog 从单一表目录，提升为统一多源、多资产、多地域的元数据控制面。**

因此它最适合作为以下设计问题的参考样本：

- 是否需要统一 control plane；
- 是否要引入 metalake 级租户边界；
- 是否把 files / model / topic 等纳入一等对象；
- 权限模型要做到多统一、多可下推；
- Iceberg REST 应该作为“协议兼容面”还是“产品全部边界”。

如果后续要继续深挖，建议下一步直接读三块源码/文档：

1. `docs/overview.md`
2. `docs/iceberg-rest-service.md`
3. `docs/security/access-control.md`

再配合具体 connector 文档与 release note，补齐“实际落地能力”而不只是“抽象设计目标”。

---

## 16. 参考资料

### 官方文档与仓库

1. Apache Gravitino README  
   https://github.com/apache/gravitino/blob/main/README.md

2. Apache Gravitino Overview  
   https://github.com/apache/gravitino/blob/main/docs/overview.md

3. Apache Gravitino Documentation  
   https://gravitino.apache.org/docs/latest/

4. Iceberg REST catalog service  
   https://gravitino.apache.org/docs/latest/iceberg-rest-service/

5. Model catalog  
   https://gravitino.apache.org/docs/latest/model-catalog/

6. Access Control  
   https://gravitino.apache.org/docs/latest/security/access-control/

7. Apache Gravitino GitHub repository  
   https://github.com/apache/gravitino

8. Apache Gravitino Releases  
   https://github.com/apache/gravitino/releases

9. Apache Gravitino License  
   https://github.com/apache/gravitino/blob/main/LICENSE

10. Apache Software Foundation  
   https://www.apache.org/

### 本次核对到的关键事实

- README：federated metadata lake、direct metadata integration、AI Asset Management (WIP)、Iceberg REST、Lance REST、Trino connector
- overview：metalake / catalog / schema / table / fileset / model / topic，对应统一对象模型
- iceberg-rest-service：Iceberg REST 与 Gravitino server 的边界、多 backend、credential vending、OAuth2、HTTPS、Audit log、cache、standalone authorization 限制
- access-control：universal privilege model、RBAC、deny-by-default、inheritance、ALLOW/DENY、Ranger 集成方向
