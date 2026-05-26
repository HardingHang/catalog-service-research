# 数据湖分支约束：差距、设计与创新方向

> 当前业界缺乏面向数据湖语义感知的分支约束系统。本文首先梳理六类分支约束技术的实现与能力边界，在此基础上识别六个关键空白，提出基于 Nessie 的语义感知分支约束设计方案，并分析投入产出与实施路径。另设独立章节讨论 LLM 在分支约束领域的应用。
>
> 文档版本：v4.0 / 2026-05-25

---

## 目录

- [1. 问题定义：为什么分支需要约束](#1-问题定义为什么分支需要约束)
- [2. 评价框架：约束深度的六个层次](#2-评价框架约束深度的六个层次)
- [3. 技术现状：业界六类分支约束实现](#3-技术现状业界六类分支约束实现)
  - [3.1 GitHub/GitLab Rulesets — 结构层约束的工业标杆](#31-githubgitlab-rulesets--结构层约束的工业标杆)
  - [3.2 OPA/Rego — 通用策略引擎与策略即代码范式](#32-oparego--通用策略引擎与策略即代码范式)
  - [3.3 lakeFS Hooks — 数据湖原生的 Pre/Post 事件校验](#33-lakefs-hooks--数据湖原生的-prepost-事件校验)
  - [3.4 SQL Migration Linters — Schema 变更的规则化校验](#34-sql-migration-linters--schema-变更的规则化校验)
  - [3.5 Bauplan Expectations — Git-for-Data 的运行时守门](#35-bauplan-expectations--git-for-data-的运行时守门)
  - [3.6 Nessie CEL Authorization — 数据湖的访问控制层](#36-nessie-cel-authorization--数据湖的访问控制层)
- [4. 差距分析：六个空白](#4-差距分析六个空白)
  - [4.1 空白 1：可扩展的语义级规则集](#41-空白-1可扩展的语义级规则集)
  - [4.2 空白 2：面向数据湖表语义的规则引擎](#42-空白-2面向数据湖表语义的规则引擎)
  - [4.3 空白 3：约束执行与版本控制操作脱节](#43-空白-3约束执行与版本控制操作脱节)
  - [4.4 空白 4：规则优先级与冲突解决](#44-空白-4规则优先级与冲突解决)
  - [4.5 空白 5：跨规则组合与复用](#45-空白-5跨规则组合与复用)
  - [4.6 空白 6：规则变更需要重启系统](#46-空白-6规则变更需要重启系统)
  - [4.7 空白依赖关系](#47-空白依赖关系)
- [5. 规则清单与对比](#5-规则清单与对比)
  - [5.1 GitHub/GitLab Rulesets](#51-githubgitlab-rulesets)
  - [5.2 OPA/Rego](#52-oparego)
  - [5.3 lakeFS Hooks](#53-lakefs-hooks)
  - [5.4 SQL Migration Linters](#54-sql-migration-linters)
  - [5.5 Nessie CEL Authorization](#55-nessie-cel-authorization)
  - [5.6 Bauplan Expectations](#56-bauplan-expectations)
  - [5.7 规则差异对比总表](#57-规则差异对比总表)
- [6. LLM 应用于分支约束](#6-llm-应用于分支约束)
  - [6.1 角色定位：元能力而非分支约束技术](#61-角色定位元能力而非分支约束技术)
  - [6.2 技术原理：编译时翻译，运行时无关](#62-技术原理编译时翻译运行时无关)
  - [6.3 生产级实现](#63-生产级实现)
  - [6.4 学术研究](#64-学术研究)
  - [6.5 Upwind 深度分析](#65-upwind-深度分析)
  - [6.6 NL→规则产物对比](#66-nl规则产物对比)
  - [6.7 核心结论与迁移路径](#67-核心结论与迁移路径)
- [7. 横向对比](#7-横向对比)
  - [7.1 约束能力对比矩阵](#71-约束能力对比矩阵)
  - [7.2 部署形态分类](#72-部署形态分类)
  - [7.3 热加载机制分类](#73-热加载机制分类)
  - [7.4 语义模型对比](#74-语义模型对比)
- [8. 系统设计：语义感知分支约束](#8-系统设计语义感知分支约束)
  - [8.1 设计目标矩阵](#81-设计目标矩阵)
  - [8.2 架构总览](#82-架构总览)
  - [8.3 规则定义层：可组合语义原语](#83-规则定义层可组合语义原语)
  - [8.4 规则定义层的五个创新扩展](#84-规则定义层的五个创新扩展)
  - [8.5 规则引擎层：优先级分流与冲突解决](#85-规则引擎层优先级分流与冲突解决)
  - [8.6 语义信息层：ContentSemantics SPI](#86-语义信息层contentsemantics-spi)
  - [8.7 集成点：CAS 路径上的检查点](#87-集成点cas-路径上的检查点)
  - [8.8 核心设计决策](#88-核心设计决策)
  - [8.9 规则热加载：配置即代码](#89-规则热加载配置即代码)
- [9. 投入产出与实施路径](#9-投入产出与实施路径)
  - [9.1 价值主张](#91-价值主张)
  - [9.2 实施阶段与工作量估算](#92-实施阶段与工作量估算)
  - [9.3 风险与缓解](#93-风险与缓解)
- [10. 参考文献](#10-参考文献)

---

## 1. 问题定义：为什么分支需要约束

Git 的分支模型极简到了极致——分支就是一个指向 commit 的指针，除此之外没有任何约束。Git 能这样"放任自由"，是因为**代码的语义在开发者脑子里**，不在版本控制系统的管辖范围。约束是 GitHub/GitLab 的 branch protection 外挂上去的，不是 Git 自身的。

数据湖的分支承载的语义完全不同：

- **环境语义**：`main` = 生产数据，`dev` = 开发环境。两者的合并方向应是单向且经过校验的。
- **数据契约语义**：`main` 上的表 schema 是生产契约。不兼容的 schema 变更合入 `main` 会破坏下游报表、ML 模型和 ETL 任务。
- **引用完整性语义**：`main` 上的 snapshot 被下游系统引用。回滚或删除这些 snapshot 需要被识别和阻断。

**分支约束的本质，是在分支操作（commit、merge、rebase、delete）的关键路径上插入可编程的判断逻辑，让危险的、不合规的变更在执行前被拦截。**

目前业界的分支约束技术集中在访问控制层——控制"谁能不能在哪个分支上提交"，但基本不管"提交的内容合不合规"。从"能不能提交"到"提交的内容安不安全"，存在一个系统性的技术空白。本文第 3 章梳理六类分支约束技术的现状，第 4 章基于现状识别六个空白，第 5 章集中呈现规则清单与对比，第 6 章独立讨论 LLM 在分支约束中的应用，第 7 章横向对比，第 8 章提出填补空白的设计方案。

---

## 2. 评价框架：约束深度的六个层次

在逐一分析各类技术之前，先建立一个统一的评价框架。任何分支约束技术都可以按"检查内容的深度"分为六个层次：

```
Level 0: 身份约束 — 你是谁？
Level 1: 操作约束 — 你能做什么操作？
Level 2: 对象约束 — 你能在哪个分支/路径上操作？
Level 3: 过程约束 — 在操作执行前需要满足什么前提？
Level 4: 结构约束 — 操作的内容在结构上是否合法？
Level 5: 语义约束 — 操作的内容在业务语义上是否合规？
```

这个框架贯穿全文：第 3 章用它将每类技术定位到具体层次，第 5 章在规则清单中标注每条规则的层次归属，第 4 章用它精确指出空白所在的层次区间。

---

## 3. 技术现状：业界六类分支约束实现

以下逐一分析六类技术的实现。每节按统一结构组织：**实现原理（含架构图示）→ 部署形态 → 能力边界**。各技术的完整规则清单集中在第 5 章。LLM 辅助规则生成作为元能力，独立在第 6 章讨论。

### 3.1 GitHub/GitLab Rulesets — 结构层约束的工业标杆

**实现原理**

GitHub 在 Git 的分支模型之上，通过 Rulesets（2024 年后推荐的规则体系，替代 legacy branch protection）提供分层约束 [1]。Rulesets 支持按分支模式（`main`、`release/*`）匹配规则，多个 ruleset 同时命中一个分支时，取**最严格的设置**（聚合原则）。每条 ruleset 可配置 required status checks，将 CI/CD 流水线中的 job 绑定为程序化 gate——检查内容自由，可以是单元测试、安全扫描、schema 校验。

```
                  ┌─────────────┐
                  │  User Push  │
                  │  / PR Open  │
                  └──────┬──────┘
                         │
                         ▼
              ┌──────────────────────┐
              │   Ruleset Matching   │
              │  (branch pattern:    │
              │   main, release/*)   │
              │  多个 ruleset 命中    │
              │   → 取最严格设置     │
              └──────────┬───────────┘
                         │
            ┌────────────┼────────────┐
            ▼            ▼            ▼
      ┌─────────┐ ┌──────────┐ ┌──────────┐
      │ Require │ │ Require  │ │ Require  │
      │   PR    │ │ Approvals│ │ Status   │
      │         │ │ (1-6+)   │ │ Checks   │
      └────┬────┘ └────┬─────┘ └────┬─────┘
           │           │            │
           └───────────┼────────────┘
                       │
                       ▼
              ┌─────────────────┐
              │  All Gates Pass? │
              └────────┬────────┘
                   ┌───┴───┐
                   ▼       ▼
              ┌──────┐ ┌──────┐
              │ Merge│ │Block │
              │Allow │ │Reject│
              └──────┘ └──────┘
```

**部署形态**

SaaS 平台托管。用户通过 GitHub 网页 UI 或 API 配置 Rulesets，GitHub 后台即时同步，无需关心部署和运维。

**能力边界**

约束粒度为 L1-L3（操作/对象/过程）。Rulesets 只检查分支操作的元数据——"谁"（角色）、"什么操作"（push/merge/delete）、"什么前提"（review + CI 通过）。它完全不关心操作的内容——PR 改了一行注释还是删除了整个模块，保护规则同等对待。CI status check 虽然可以运行任意检查逻辑，但它是异步的、在 merge 之前的独立流水线中执行，不是在版本控制引擎内部的 CAS 路径上同步执行。

> 完整规则清单见 [5.1](#51-githubgitlab-rulesets)。

---

### 3.2 OPA/Rego — 通用策略引擎与策略即代码范式

**实现原理**

Open Policy Agent（OPA）是 CNCF 毕业的通用策略引擎，核心思想是将策略决策从应用逻辑中解耦 [2]。应用通过 JSON input 发出查询，OPA 用 Rego（基于 Datalog 的声明式语言）编写的策略进行判定，返回 `{allow: true/false}`。策略独立于应用代码版本控制，支持热更新（Bundle 机制）。P99 决策延迟 10-20ms（Sidecar 模式）[3]。

```
                      ┌─────────────────────────┐
                      │     Policy Authoring     │
                      │  (Rego .rego files)      │
                      │  Git Repo / S3 / OCI     │
                      └────────────┬─────────────┘
                                   │ Bundle 拉取 (每 60s)
                                   ▼
┌──────────────┐  JSON Input   ┌──────────────┐
│  Application │  ───────────► │  OPA Engine  │
│  (Nessie)    │               │              │
│              │               │  ┌──────────┐│
│  构造 input:  │               │  │ Compiled ││
│  {op, ref,   │               │  │ Policies ││
│   role, path}│               │  │  (Rego   ││
│              │               │  │  → IR)   ││
│              │  ◄─────────── │  └──────────┘│
└──────────────┘  {allow: bool}└──────────────┘
                                   │
                            ┌──────┴──────┐
                            ▼              ▼
                      ┌─────────┐    ┌─────────┐
                      │  ALLOW  │    │  DENY   │
                      │ (200 OK)│    │(403 Forb│
                      └─────────┘    └─────────┘

        OPA 三种部署模式:
        ┌──────────────┬──────────────┬──────────────┐
        │   Sidecar    │  Go Library  │    WASM      │
        ├──────────────┼──────────────┼──────────────┤
        │ 独立 Go 进程  │ 进程内函数调用│ 进程内二进制   │
        │ HTTP API     │ 直接执行     │ WASM Runtime │
        │ ~1-5ms 延迟  │ ~μs 延迟     │ ~μs 延迟     │
        │ 跨语言       │ 仅 Go        │ 跨语言 ✓     │
        └──────────────┴──────────────┴──────────────┘
```

**部署形态**

OPA 支持三种部署模式，在延迟和部署复杂度之间提供不同取舍：

| 模式 | 部署方式 | 语言限制 | 延迟 | 适用场景 |
|------|---------|---------|------|---------|
| **Sidecar** | 独立 Go 进程，HTTP REST API | 无（任何语言通过 HTTP 调用） | ~1-5ms | 标准生产部署 |
| **Go Library** | 进程内编译，直接函数调用 | 仅 Go 语言 | ~μs | Go 项目的高性能需求 |
| **WASM** | 编译为 WebAssembly 二进制，进程内执行 | 跨语言（Java/Python/Go/任何支持 WASM 的语言） | ~μs | 非 Go 项目需要进程内低延迟 |

WASM（WebAssembly）是一种可移植的二进制指令格式，最初为浏览器设计，现已广泛用于服务端。OPA 将 Rego 策略编译成 WASM 二进制，应用通过 WASM 运行时在进程内执行策略评估，无需外部 HTTP 调用。对于 Nessie（Java 项目），如果要 OPA 进程内执行，WASM 是唯一选择。

**热加载：Bundle 机制**

OPA 的策略热加载通过 Bundle 机制实现 [4]，不依赖重启：

```
1. 策略开发
   用户编写 .rego 策略文件，推送到 Git 仓库或上传到 S3/OCI registry
         │
2. 周期性发现                                       ◄── 默认每 60s 轮询一次
   OPA 从配置的 Bundle 源下载策略文件压缩包
         │
3. 编译 + 验证
   OPA 解析所有 .rego 文件，编译为内部表示，检查语法和类型错误
         │
4. 原子替换
   ├── 编译成功 → 原子替换当前活跃策略集（旧策略 → 新策略）
   └── 编译失败 → 保留旧策略，记录错误日志，不影响当前服务
         │
5. 手动触发（可选）
   POST /v1/health → 立即触发策略重载（用于紧急规则变更）
```

关键特性：**错误降级**——编译失败不导致服务中断，而是保留旧策略继续运行并记录告警。对比 Nessie CEL 的 `@Singleton` + `@Startup` 一次性编译，OPA Bundle 做到了无需重启的规则热更新。

**能力边界**

OPA 是策略引擎而非分支约束系统。它的能力上限完全取决于应用传入的 JSON input——如果 Nessie 不传 schema 变更细节给 OPA（只传 `op`、`ref`、`path`、`role` 四个字符串），那么 OPA 只能判断"谁在哪个分支上做了什么操作"，停留在 L0-L2。要让 OPA 做 L4-L5 的语义判断，需要 Nessie 先把 `TableMetadata` 的变更语义注入到 JSON input 中。**语义约束的瓶颈不在策略引擎，在语义信息的供给。**

> 完整规则清单见 [5.2](#52-oparego)。

---

### 3.3 lakeFS Hooks — 数据湖原生的 Pre/Post 事件校验

**实现原理**

lakeFS 在分支操作事件节点上支持远程 webhook 形式的检查 [5][6]。支持 `pre-commit`、`post-commit`、`pre-merge`、`post-merge`、`pre-create-branch`、`post-create-branch` 六种事件。`pre-*` hook 执行期间分支被锁定。Hook 配置以 YAML 声明在仓库的 `_lakefs_actions/` 路径下——配置本身是版本化的（存储在 lakeFS 仓库中），修改 hook 配置的变更可追溯。

```
    ┌──────────────────────────────────────────────────────┐
    │                  lakeFS Repository                   │
    │                                                      │
    │  _lakefs_actions/hooks.yaml  ← 版本化的 Hook 配置    │
    │  ┌────────────────────────────────────────────────┐  │
    │  │ hooks:                                         │  │
    │  │   - id: schema-check                           │  │
    │  │     type: webhook                              │  │
    │  │     url: https://hooks.internal/schema-check    │  │
    │  │     events: [pre-merge, pre-commit]            │  │
    │  └────────────────────────────────────────────────┘  │
    └───────────────────────┬──────────────────────────────┘
                            │
              分支事件发生 (merge/commit/...)
                            │
                            ▼
              ┌─────────────────────────┐
              │   lakeFS: 锁定分支       │
              │   HTTP POST to webhook  │
              └────────────┬────────────┘
                           │
                           ▼
              ┌─────────────────────────┐
              │     Webhook Service     │
              │  (Python/Go, 独立部署)   │
              │                         │
              │  检查内容:               │
              │  - 文件格式 (Parquet)    │
              │  - Schema (PII 列)      │
              │  - 分区完整性            │
              │  - Commit 元数据         │
              └────────────┬────────────┘
                           │
              HTTP Response │
              ┌─────────────┴─────────────┐
              ▼                           ▼
       ┌─────────────┐            ┌─────────────┐
       │  200/2xx    │            │  non-2xx    │
       │  ✅ 通过    │            │  ❌ 拒绝    │
       └──────┬──────┘            └──────┬──────┘
              │                          │
              ▼                          ▼
    ┌──────────────────┐    ┌──────────────────────┐
    │ 继续操作          │    │ 操作被拒绝            │
    │ (解锁分支)        │    │ ⚠️ 超时 → 默认放行    │
    └──────────────────┘    │ ⚠️ revert → 不触发    │
                            └──────────────────────┘
```

**部署形态**

外部 HTTP webhook。每条校验规则是一个独立的 HTTP 服务，需要用 Python/Go 编写、部署和运维。lakeFS 在分支事件发生时 POST 到 webhook URL，webhook 的 HTTP 返回码决定"通过"或"失败"。

**关键局限：准入控制不可靠**

lakeFS Hooks 架构的交互方向是 **push 模式**（lakeFS 主动推送事件到 webhook），而非 pull 模式（应用主动查询约束引擎）。`pre-*` hook 的拦截流程如下：

- lakeFS 锁定分支 → 发起 HTTP POST → 等待 webhook 返回 → webhook 返回非 200 → lakeFS 拒绝操作

在正常情况下，`pre-*` hook **可以**阻止操作（返回非 200 则操作被拒绝）。但有两个设计缺陷让它无法作为可靠的硬性安全边界：

1. **webhook 超时默认放行**：如果 webhook 服务不可达或响应超时，lakeFS 的默认行为是**放行操作**（设计初衷是避免 webhook 故障阻塞所有正常操作）。这意味着 webhook 服务中断时，所有约束静默失效——等于规则形同虚设。
2. **Revert 操作绕过**：已知 bug（GitHub Issue #8615），revert 操作不触发任何 hook [8]，约束完全被绕过。

因此 lakeFS Hooks 更适合定位为**事件通知/审计触发器**：在 webhook 服务正常时提供有条件的拦截，但不能作为硬性安全边界依赖。

**能力边界**

三重限制：（1）**规则即代码**——新增规则 = 写服务 + 部署 + 运维，没有统一的规则定义语言；（2）**语义盲区**——lakeFS 的 diff 告诉你"哪些文件被增删改"，但它不懂 Iceberg `TableMetadata` 的子结构——不知道哪个变更对应 schema、哪个对应 partition spec，语义理解完全由 webhook 代码自己实现；（3）**准入不可靠**——正常时可拦截，但超时默认放行 + revert 绕过，不可作为硬性安全边界依赖。覆盖层次 L1-L4（取决于 webhook 实现质量）。

> 完整规则清单见 [5.3](#53-lakefs-hooks)。

---

### 3.4 SQL Migration Linters — Schema 变更的规则化校验

**实现原理**

这类工具校验的不是数据湖，而是传统数据库的 DDL 迁移脚本。但它们的规则组织模式——内置规则库 + 声明式配置 + CI 集成——有直接的参考价值。

**Squawk**[9]：用 PostgreSQL 原生解析器（`libpg_query`）将 SQL 解析为 AST，然后对 AST 应用 32 条安全规则。规则以 TOML 配置启用/关闭（`excluded_rules = [...]`），支持行内抑制（`-- squawk-ignore ban-drop-column`），集成到 GitHub Actions 在 PR 中自动运行。

**pgvet**[10]：将 13 条规则按 breaking / nullability / locking / idempotency 四个类别组织，默认全开。

**Atlas**[11]：从 linter 升级为完整的 Database DevOps 平台。在规则的层面上，支持 HCL（HashiCorp Configuration Language）策略语言自定义规则，并引入 dev database 在模拟环境中检测运行时问题（如锁冲突、性能退化），提供 SOC 2 Type II 合规审计能力。

```
  ┌─────────────────────────────────────────────────────────┐
  │                     CI Pipeline (GitHub Actions)          │
  │                                                          │
  │  ┌──────────────────┐     ┌──────────────────────────┐  │
  │  │ SQL Migration    │     │ Squawk / pgvet Binary    │  │
  │  │ (*.sql files)    │ ──► │                          │  │
  │  │                  │     │ ┌──────────────────────┐ │  │
  │  │ ALTER TABLE ...  │     │ │ libpg_query Parser   │ │  │
  │  │ DROP COLUMN ...  │     │ │ (PostgreSQL Native)  │ │  │
  │  │ ADD COLUMN ...   │     │ └──────────┬───────────┘ │  │
  │  └──────────────────┘     │            ▼             │  │
  │                           │ ┌──────────────────────┐ │  │
  │                           │ │ SQL AST              │ │  │
  │                           │ │ (结构化语法树)         │ │  │
  │                           │ └──────────┬───────────┘ │  │
  │                           │            ▼             │  │
  │                           │ ┌──────────────────────┐ │  │
  │                           │ │ Rule Matching        │ │  │
  │                           │ │ (Squawk: 32 rules,   │ │  │
  │                           │ │  pgvet: 13 rules)    │ │  │
  │                           │ │ 配置: .squawk.toml   │ │  │
  │                           │ │ excluded_rules=[...] │ │  │
  │                           │ └──────────┬───────────┘ │  │
  │                           └────────────┼─────────────┘  │
  │                                        │                │
  │                          ┌─────────────┴─────────────┐  │
  │                          ▼                           ▼  │
  │                    ┌──────────┐               ┌──────────┐
  │                    │ ✅ PASS  │               │ ❌ FAIL  │
  │                    │ 继续 CI   │               │ 阻断 PR  │
  │                    └──────────┘               └──────────┘  │
  └─────────────────────────────────────────────────────────┘

  Atlas 额外能力:
  ┌──────────────────────────────────────────────────────────┐
  │  HCL Policy → Dev DB (模拟) → 运行时检测 (锁冲突/性能)    │
  │  → SOC 2 Type II 审计                                    │
  └──────────────────────────────────────────────────────────┘
```

**部署形态**

| 工具 | 部署形态 | 说明 |
|------|---------|------|
| Squawk | CI 二进制 | 在 GitHub Actions 流水线中运行 Rust 编译的静态二进制 |
| pgvet | CI 二进制 | 类似 Squawk，作为独立的 CI 检查步骤运行 |
| Atlas | CI + Dev DB | 除 CI 检查外，还需连接开发数据库进行运行时模拟 |

**能力边界**

两个层面：（1）**领域限定**——分析 `.sql` 文件而非 Iceberg 表语义，不理解 `TableMetadata` 的五大子结构；（2）**规则集封闭**（Squawk/pgvet）——规则编译在 Rust 二进制里，用户只能开关已有规则，无法定义新规则。如果 DBA 需要"禁止在 `prod` schema 中删除任何带有 `pii_` 前缀的列"，必须 fork Squawk，写 Rust，重新编译。Atlas 支持 HCL 自定义策略，规则集开放，但面向的是数据库 DDL 操作而非 Iceberg 表语义。覆盖层次 L4（SQL 结构语义），但属于不同领域。

> 完整规则清单见 [5.4](#54-sql-migration-linters)。

---

### 3.5 Bauplan Expectations — Git-for-Data 的运行时守门

**实现原理**

Bauplan 在 pipeline 执行模型内部嵌入了约束检查 [17][18]。管道运行创建临时事务分支，各 step 的输出写入临时分支，expectation 步骤执行数据质量检查，通过后原子合并到 main，失败则保留临时分支用于排错。

Bauplan 用 Alloy 形式化验证其分支语义，发现了一个关键反例 [18]：用户 A 的管道在半路失败，部分表已物化、部分未物化；用户 B fork 了该临时分支并合入 main，导致 main 暴露了不完整的管道结果。由此设计了守卫规则：禁止从临时/运行时分支创建新分支，只有成功的管道才能被合并。

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    Bauplan Pipeline                          │
  │                                                              │
  │  Step 1            Step 2            Expectation             │
  │  (读取数据)         (数据变换)         (约束检查)               │
  │                                                              │
  │  ┌─────────┐      ┌─────────┐      ┌──────────────────┐     │
  │  │ Source  │ ───► │Transform│ ───► │ expect_* checks  │     │
  │  │ Branch  │      │  Step   │      │                  │     │
  │  │ (main)  │      │         │      │ column_no_nulls  │     │
  │  └─────────┘      └────┬────┘      │ values_in_set    │     │
  │                        │           │ row_count_between│     │
  │                        ▼           └────────┬─────────┘     │
  │                   ┌─────────┐              │                │
  │                   │  Temp   │◄─────────────┘                │
  │                   │ Branch  │  Step 输出写入临时分支          │
  │                   └────┬────┘                               │
  │                        │                                    │
  │              ┌─────────┴─────────┐                          │
  │              ▼                   ▼                          │
  │     ┌──────────────┐    ┌──────────────┐                    │
  │     │ ✅ All Pass  │    │  ❌ Failed   │                    │
  │     │              │    │              │                    │
  │     │ 原子合并到    │    │ 保留临时分支  │                    │
  │     │ main 分支     │    │ 用于排错     │                    │
  │     └──────────────┘    └──────┬───────┘                    │
  │                                │                            │
  └────────────────────────────────┼────────────────────────────┘
                                   │
                          ┌────────┴────────┐
                          │ 形式化验证 (Alloy) │
                          │ ┌──────────────┐ │
                          │ │ 反例发现:      │ │
                          │ │ 用户 B fork   │ │
                          │ │ 半完成临时分支 │ │
                          │ │ →绕过检查合入 │ │
                          │ │ main          │ │
                          │ ├──────────────┤ │
                          │ │ 修复: 禁止 fork│ │
                          │ │ 临时分支      │ │
                          │ └──────────────┘ │
                          └──────────────────┘
```

**部署形态**

运行时内嵌。约束检查嵌入在 Bauplan 的 pipeline 执行模型中，与运行时强耦合。

**能力边界**

约束检查嵌入在管道执行模型中，与 Bauplan 的运行时强耦合。它不是用户可主动定义的、独立于管道的约束规则——你不能在 Bauplan 中声明"main 分支上禁止任何 OverwriteFiles 操作"。约束是系统内置的"管道安全边界"，灵活性受限于 expectation 框架。覆盖层次 L3（过程约束）、L5（数据质量语义），但不可用户扩展。

> 完整规则清单见 [5.6](#56-bauplan-expectations)。

---

### 3.6 Nessie CEL Authorization — 数据湖的访问控制层

**实现原理**

Nessie 使用 CEL（Common Expression Language）定义访问控制规则 [19]。当前可用 4 个变量——`op`（操作类型）、`role`（用户角色）、`ref`（分支名）、`path`（内容键字符串）——全部是字符串类型。规则在 `application.properties` 中定义，双层判定模型：ref 维度和 path 维度的规则逻辑叠加，任一 deny 则最终 deny。

```
                    ┌──────────────────────────────────┐
                    │     application.properties       │
                    │                                  │
                    │ nessie.server.authorization      │
                    │   .rules.allow_all=              │
                    │     "op in [..."]                │
                    │   .rules.allow_commits=          │
                    │     "op=='COMMIT_..."            │
                    │   .rules.allow_...=...           │
                    └──────────────┬───────────────────┘
                                   │ 启动时一次性加载
                                   ▼
                    ┌──────────────────────────────────┐
                    │ QuarkusNessieAuthorizationConfig  │
                    │ (@ConfigMapping)                 │
                    │ 读取 Map<String,String> rules     │
                    └──────────────┬───────────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────────┐
                    │ CompiledAuthorizationRules        │
                    │ (@Singleton + @Startup)           │
                    │                                  │
                    │ ┌──────────────────────────────┐ │
                    │ │ ImmutableMap<String, Script> │ │
                    │ │ 一次性编译所有 CEL 表达式       │ │
                    │ │ 编译后不再变化 (无热加载)       │ │
                    │ └──────────────┬───────────────┘ │
                    └────────────────┼──────────────────┘
                                     │
                        每次 API 请求 │
                                     ▼
                    ┌──────────────────────────────────┐
                    │ CelBatchAccessChecker             │
                    │ canPerformOp() → anyMatch()      │
                    │                                  │
                    │ 遍历所有规则:                      │
                    │  每个规则评估 4 个变量:             │
                    │  ┌──────┬──────────────────────┐ │
                    │  │ op   │ VIEW_REFERENCE       │ │
                    │  │ role │ test_user            │ │
                    │  │ ref  │ main                 │ │
                    │  │ path │ ns.table             │ │
                    │  └──────┴──────────────────────┘ │
                    └──────────────┬───────────────────┘
                                   │
                         ref 维度   │   path 维度
                         anyMatch   │   anyMatch
                            │       │      │
                            └───────┼──────┘
                                    ▼
                          ┌─────────────────┐
                          │ 任一 deny → DENY │
                          │ 全部 allow→ALLOW│
                          └─────────────────┘
```

**部署形态**

内部配置。规则定义在 `application.properties`（或 Quarkus 配置源）中：

- `QuarkusNessieAuthorizationConfig`（Quarkus `@ConfigMapping`）读取 `nessie.server.authorization.rules.*`
- `CompiledAuthorizationRules`（`@Singleton` + `@Startup`）在启动时一次性编译所有 CEL 表达式到 `ImmutableMap<String, Script>`
- `CelBatchAccessChecker` 每次请求时通过 `canPerformOp()` 迭代所有规则，使用 `anyMatch()` 判定

**热加载：无**

Nessie CEL 是六类技术中**唯一需要重启**的。`CompiledAuthorizationRules` 的 `@Singleton` + `@Startup` 注解意味着规则在启动时一次性编译到 `ImmutableMap`，之后没有任何文件监听、配置变更回调或定时重载机制。修改 `application.properties` 后必须重启 Nessie 服务。

**能力边界**

两个层面：（1）**语义感知为零**——四个字符串变量只能描述"谁在哪个分支/路径上做了什么操作"（L0-L2），规则引擎完全不知道这个 commit 修改了什么子结构、schema 变更是向后兼容还是破坏性的、操作是 AppendFiles 还是 OverwriteFiles；（2）**规则变更必须重启**。

> 完整规则清单见 [5.5](#55-nessie-cel-authorization)。

---

## 4. 差距分析：六个空白

第 3 章梳理了六类技术的实现与能力边界。本章基于这些分析，逐一识别六个关键空白，明确指出"哪些技术最接近"以及"为什么它们仍然不够"。完整的规则清单与对比见第 5 章，LLM 元能力讨论见第 6 章，横向对比（含部署形态、热加载机制、语义模型）见第 7 章。

### 4.1 空白 1：可扩展的语义级规则集

**最接近的技术**：Squawk（32 条 SQL lint 规则，TOML 配置开关）、Atlas（HCL 自定义策略）。

当前最接近的规则集是 Squawk 的 32 条规则——`ban-drop-column`、`disallow-required-column` 等，每一条都是编译在 Rust 二进制里的、不可拆分的原子化检查。你能做的只是启用或关闭它。如果 DBA 需要"禁止在 `prod` schema 中删除 PII 列"，这条规则在 Squawk 中不存在，也无法通过 TOML 配置定义。Atlas 的 HCL 策略稍微前进一步——允许自定义规则——但它面向的是关系型数据库的 DDL 操作（`drop_table`、`add_column`），不是 Iceberg 的子结构变更。

**真正的差距不是"声明式 vs 命令式"**（TOML、YAML、HCL 都是声明式），而是**规则集是封闭的还是开放的**。如第 5.7 节对比表所示，六类技术中规则可扩展的只有 OPA、lakeFS（写代码）、Atlas、Nessie CEL——但后三者的扩展或面向错误领域（Atlas→DB，Nessie CEL→只有字符串），或扩展成本过高（lakeFS→写服务+部署）。Squawk 的规则是原子化的检查逻辑，无法拆成"检查目标 + 操作类型 + 严重级别"再和其他原语重组。缺失的是这一层抽象：

```
引擎暴露的可组合原语（固定，约 10 个）      用户可组合出的规则（开放）
──────────────────────────────          ──────────────────────
change.field                            "main 分支不能删除列"
change.action                           "prod-* 分支禁止 Overwrite"
change.column.pii                       "PII 列不能作为分区键"
ref                                      "仅允许 add_column 和 widen_type"
scope                                    ...（理论上无限组合）
```

类比 SQL：Squawk 相当于给你 32 个写死的查询模板，你只能选模板填参数。可扩展规则集相当于给你 `SELECT ... WHERE ... FROM ...` 的语言能力，你能拼出任意查询。

**可组合原语之上的五个创新方向**

可组合原语解决了"规则集从封闭到开放"的问题，但在此之上，还有五个创新方向可以进一步提升规则集的表达力和可运维性：

**1. 规则仿真 / 干跑模式**

规则启用前，对历史 N 个 commit 回放规则判定，输出影响面报告："如果这条规则在 30 天前就存在，它会拦截 12 次 merge，其中 3 次需要调整规则范围"。这解决了运维的最大心理障碍——人们不敢启用严格规则是因为不知道它会阻断什么。现有系统（Squawk、OPA、lakeFS Hooks）均不提供仿真能力。

**2. 时效性规则**

规则可以带时间窗口和生命周期：
- 时间窗口："发布冻结期（1 月 1-15 日）禁止 schema 变更"
- 阶段铺开：新规则先以 WARN 模式运行 N 天，观察误报率后自动升级为 ERROR
- TTL 过期："本次紧急冻结规则有效期 48h，到期自动失效"

现有系统均不支持——Squawk 的规则是静态 TOML 开关，lakeFS hooks 是静态 YAML 配置，OPA 的 Bundle 只是做了"版本控制"但不支持时间语义。

**3. 理由覆盖（Break-Glass Override）**

当前所有约束系统的判定都是二值的——REJECT（阻断）或 ALLOW（放行）。这在理论上清晰，但生产环境中规则的作者和执行者不是同一个人，作者无法预见所有例外。DBA 写了"禁止删除 PII 列"，但 oncall 工程师凌晨发现 `email` 列被上游写入了信用卡号，必须立即删除。她只有两个选择：关闭整条规则（留下安全窗口）或等待 DBA 修改规则。

**方案：三元判定 + 结构化理由**

```
判定结果：
  ALLOW          — 规则不命中，放行
  REJECT         — 命中，且规则标记为 HARD，不可覆盖
  OVERRIDE       — 命中，但规则标记为 SOFT，可通过提供理由覆盖

工作流：
1. 规则引擎判定 → OVERRIDE
2. 返回给用户：操作被 [规则名] 阻止，但该规则允许理由覆盖
3. 用户在 commit/merge 中附带理由：
   {
     "override": {
       "rule_id": "pii-no-drop",
       "reason": "Emergency: PII data leaked into email column, must purge.
                  Incident: INC-2024-0521, approved by: @dba-lead",
       "ticket": "INC-2024-0521"
     }
   }
4. 引擎记录理由到 commit meta，放行操作
5. 审计系统可查询全部 override 记录
```

**安全机制（防止滥用）**：

| 机制 | 说明 |
|------|------|
| 频次限制 | 同一规则 24h 内最多 override N 次，超过后自动升级为 HARD |
| 审批门槛 | HARD 规则需指定审批者，SOFT 规则允许自签 |
| 自动复审 | 所有 override commit 自动标记 `constraint-override` 标签，每日/每周生成复审报告 |
| 异常告警 | override 频次超过基线 → 通知规则作者（"你的规则 7 天内被覆盖了 12 次，可能需要调整"） |
| 事后追责 | override 理由永久保存在 commit history 中 |

这个设计借鉴了三个成熟领域：PagerDuty 的 alert suppression（可 silence 但必须写理由）、AWS IAM 的 break-glass roles（紧急获取 admin 权限，全部操作记录 CloudTrail）、Kubernetes 的 Pod Disruption Budget（设立"自愿中断"上限，超过则变为 HARD 阻断）。

**现有系统的 bypass 能力均为"关掉规则"（改变规则本身），而非"在规则生效前提下提供理由穿透"（只影响单次操作，安全态势不变）。**

**4. 跨表约束**

当前六类技术（见第 5.7 节对比表）全部是单实体判定——检查"这个 commit 是否修改了这个表"。但数据湖场景中，表之间存在 schema 依赖：

- "删除表 A 的列 X 时，表 B 中引用 X 的视图必须同步更新"
- "namespace 内所有 Iceberg 表的 `partition_spec` 必须一致"
- "任何 `shipments` 表的外键列在 `customers` 表中必须存在对应主键"

跨表约束需要规则引擎在一次判定中访问多个 content key 的 `SemanticDiff`。现有系统无此能力。

**5. 规则组合代数**

超越简单的模板叠加，建立规则间的形式化组合（对比如第 5.7 节表中无任何技术提供此能力）：
- **交集（∩）**：`ruleC = ruleA ∩ ruleB` —— 同时满足两条规则才触发
- **并集（∪）**：`ruleC = ruleA ∪ ruleB` —— 任一规则命中即触发
- **继承（extends）**：`prod-safety extends base-safety` —— 子规则集继承父规则集的全部规则，可追加、可覆盖

OPA 有 `import` 模块化（接近继承），GitHub Rulesets 有层叠（接近并集），但都没有形式化的组合算子和语法。规则组合代数使约束系统从"一堆独立规则"升级为"有结构的规则体系"。

---

### 4.2 空白 2：面向数据湖表语义的规则引擎

**最接近的技术**：Nessie CEL（4 个字符串变量）、OPA/Rego（通用 JSON input）、Squawk（SQL AST 解析）。

从第 5.7 节的对比表可以清晰看到断层：Squawk 有 SQL AST 语义感知但不懂 Iceberg；OPA 有无限输入扩展性但 Nessie 当前只传 4 个字符串；Nessie CEL 有低延迟同步执行但语义感知为零。

Nessie CEL 能回答"admin_user 能否在 main 分支上提交"，但**完全无法**回答"这次提交是否删除了 email 列"、"这次合并是否包含 OverwriteFiles 操作"。OPA 的 Rego 可以表达任意复杂的逻辑——但前提是应用传入了足够丰富的 JSON input。

**空白精确定义**：没有一个规则引擎的 input 是**结构化的 Iceberg 表变更语义**——"这次 merge 在 `schema` 子结构上新增了列 `email`（STRING），在 `properties` 上修改了 `owner`，在 `snapshots` 上执行了 `OverwriteFiles`。"这个 input 必须在 Nessie 内部生成（因为它持有 Iceberg catalog 的上下文），然后注入到规则引擎。

---

### 4.3 空白 3：约束执行与版本控制操作脱节

**最接近的技术**：GitHub Rulesets（merge 前检查）、lakeFS Hooks（pre-merge webhook）、Nessie CEL（每次 API 调用）。

三种执行模式各有独立的脱节问题：

- **GitHub Rulesets**：在 Git 层工作，不懂 Iceberg。CI status check 可以做任意检查，但它是异步的、独立流水线——不是在 merge 操作的关键路径上同步阻断。
- **lakeFS Hooks**：在文件层工作，diff 输出是"哪些文件变了"。webhook 要自己解析文件内容才知道这是 schema 变更还是 partition spec 变更。且不可靠（超时放行 + revert 绕过）。
- **Nessie CEL**：在 API 调用时同步执行（延迟极低），但它只看到字符串，不知道这次 API 调用的内容语义。

**空白精确定义**：缺少一个在 Nessie commit/merge 的 CAS（Compare-And-Swap）路径上、同步执行的、能消费结构化语义 diff 的检查点。这个检查点必须在 CAS 之前（避免无效的 CAS 竞争），且在语义 diff 计算之后（保证完整信息可用）。目前没有任何系统在这个精确位置上工作。

---

### 4.4 空白 4：规则优先级与冲突解决

**最接近的技术**：GitHub Rulesets（聚合取最严格）、Squawk（所有规则权重相同）。

GitHub Rulesets 的"聚合取最严格"只适用于布尔型规则——"要不要 review"、"要不要 status check"。多个 ruleset 同时要求 review 时取最大值，逻辑上无冲突。

但语义约束规则不是布尔型的。两条规则可能对同一个变更给出不同判定：规则 A 说"这是 add_column，允许"，规则 B 说"新增的列是 PII 列，且分区键包含 PII 列，拒绝"。两者都对，但需要合并为一个明确的结论。

如第 5.4 节所示，Squawk 的所有规则权重相同——`ban-drop-column` 和 `prefer-identity` 违反的后果完全一样（CI 失败）。但实际场景中，删除生产列（P1 破坏性操作）和没使用 IDENTITY 列（P4 风格问题）显然不应同等对待。

**空白精确定义**：缺少**操作优先级（P0-P4）**与**规则严重性（ERROR/WARN/INFO）**的分层判定。操作优先级是变更本身的属性（删除列天然比新增列危险），严重性是规则定义者赋予的判定强度——两者正交但需要合并决策。如第 5.7 节表所示，六类技术均不支持优先级分流。

---

### 4.5 空白 5：跨规则组合与复用

**最接近的技术**：OPA/Rego（import 模块）、GitHub Rulesets（ruleset 层叠）。

第 5.7 节的对比表已展示：OPA 有 import 模块化能力，GitHub Rulesets 有 ruleset 层叠能力，其余四类技术均无规则复用机制。但 OPA 的 constraining input 太通用（任意 JSON），没有面向数据湖约束的领域模板。GitHub Rulesets 的层叠模型虽好，但只能层叠约 15 种固定配置项。

**空白精确定义**：面向数据湖的约束规则应该有策略模板的概念——"所有 `prod-*` 分支共享一套安全基线（禁止 drop_column、禁止 OverwriteFiles），同时每个分支可以叠加特有的规则（`prod-eu` 额外禁止跨区域数据传输相关的分区操作）。" 这类似于 GitHub Rulesets 的层叠，但规则内容是语义级的，且规则自身可由用户定义（空白 1 的可组合原语）。

---

### 4.6 空白 6：规则变更需要重启系统

**最接近的技术**：OPA（Bundle 热更新）、lakeFS（配置即代码）、Nessie CEL（当前最薄弱）。

第 5.7 节对比表和 7.3 节已详述：六类技术中，**Nessie CEL 是唯一需要重启的**。`CompiledAuthorizationRules`（`@Singleton` + `@Startup`）在启动时一次性编译所有 CEL 表达式到 `ImmutableMap`，之后没有任何重载路径。

其他五类技术代表了三种免重启模式：

| 模式 | 代表 | 机制 |
|------|------|------|
| **配置即代码** | lakeFS（`_lakefs_actions/`）、Squawk（`.squawk.toml`） | 规则文件在仓库中，引擎每次执行时读取最新版本 |
| **Bundle 热加载** | OPA | 周期性/事件驱动地从 Git/S3 拉取规则 bundle，自动重新编译 |
| **平台托管** | GitHub Rulesets、Bauplan | SaaS 后台即时同步，用户无需关心部署 |

**空白精确定义**：数据湖分支约束需要"配置即代码"式的规则热加载——规则存储在 Nessie 仓库中，变更即 commit，即时生效，无需重启，且自带版本历史。这三种模式中，"配置即代码"对 Nessie 最自然——因为 Nessie 本身就是一个版本控制系统。

---

### 4.7 空白依赖关系

六个空白构成一个连锁依赖链，解释了为什么这不是六个独立的小问题而是一个系统性断层：

```
可扩展规则集 + 五个创新（空白 1）
    │ 需要
    ▼
面向表语义的规则引擎（空白 2）
    │ 需要
    ▼
在 CAS 路径上的语义检查点（空白 3）
    │ 需要配套
    ├──────────────────────────────┐
    ▼                              ▼
优先级与冲突解决（空白 4）    规则复用机制（空白 5）
    │                              │
    └──────────┬───────────────────┘
               ▼
        免重启热加载（空白 6）
```

**空白 2 是关键瓶颈**：只要规则引擎的 input 还是字符串，空白 1（可扩展规则集）没有可组合的原语可用，空白 4（优先级）没有变更语义可分级，空白 5（复用）没有领域模板可共享。一旦有了能消费 `TableMetadata` 变更语义的规则引擎，其余五个空白就有了解的基础。

---

## 5. 规则清单与对比

第 3 章在分析六类技术时，重点放在了实现原理、部署形态和能力边界上。本章将各技术的规则清单集中呈现，并按统一维度进行对比分析。

### 5.1 GitHub/GitLab Rulesets

GitHub Rulesets 提供约 15 种固定配置项，按约束目标分为四类：

| 类别 | 规则项 | 约束层次 | 说明 |
|------|--------|:---:|------|
| **分支操作** | Restrict deletions | L1-L2 | 禁止删除匹配分支 |
| | Restrict creations | L1-L2 | 限制谁能创建匹配分支 |
| | Restrict updates | L1-L2 | 限制谁能强制更新分支 |
| | Restrict force pushes | L1 | 禁止 force push |
| | Lock branch | L1-L2 | 只读锁定分支 |
| **合并控制** | Require pull request | L3 | 必须通过 PR 合并 |
| | Require approvals (1-6+) | L3 | 需要指定数量的 review 批准 |
| | Dismiss stale reviews | L3 | 新 commit 后旧 review 失效 |
| | Require review from code owners | L3 | CODEOWNERS 文件指定的审查者必须批准 |
| | Require conversation resolution | L3 | PR 内所有讨论线程必须解决 |
| **CI 门禁** | Require status checks | L3 | 绑定 CI/CD job 为必须通过的 gate |
| | Require deployment | L3 | 要求特定环境部署成功 |
| **元数据** | Require signed commits | L1 | 所有 commit 必须 GPG 签名 |
| | Require linear history | L3 | 禁止 merge commit，只允许 rebase |
| | Block force pushes | L1 | 禁止 force push（同 Restrict force pushes） |

**规则特征**：
- 规则粒度：分支级别。所有规则以分支名 pattern（`main`、`release/*`）为匹配键
- 规则扩展性：封闭。用户只能启用/禁用/配置参数，不能定义新规则类型
- 规则复用：支持 ruleset 层叠（多个 ruleset 命中同一分支时，取最严格设置）
- 语义感知：无。不关心 PR 改了什么内容

---

### 5.2 OPA/Rego

OPA 本身不提供预置规则——它是空白的策略引擎，能力取决于 Rego 语言提供的**内置函数**和用户编写的 `.rego` 策略文件。

**Rego 内置函数清单**

| 类别 | 内置函数 | 说明 | 约束层次 |
|------|---------|------|:---:|
| **字符串匹配** | `glob.match(pattern, str)` | Glob 模式匹配（如 `prod-*`） | L2 |
| | `re_match(pattern, str)` | 正则表达式匹配 | L2 |
| | `startswith(str, prefix)` | 前缀匹配 | L2 |
| | `contains(str, substr)` | 子串匹配 | L2 |
| | `sprintf(format, args...)` | 字符串格式化 | — |
| **集合操作** | `intersection(set1, set2)` | 集合交集 | L2-L4 |
| | `union(set1, set2)` | 集合并集 | L2-L4 |
| | `count(set)` | 集合元素计数 | — |
| | `{x \| x in items; condition(x)}` | 集合推导式（comprehension） | L4-L5 |
| **遍历判定** | `every x in items { condition(x) }` | 全称量化：所有元素满足条件 | L4-L5 |
| | `some x in items; condition(x)` | 存在量化：存在满足条件的元素 | L4-L5 |
| **类型与聚合** | `is_string(x)`, `is_number(x)`, `is_array(x)` | 运行时类型判断 | — |
| | `type_name(x)` | 返回类型名称 | — |
| | `concat(", ", arr)`, `max(arr)`, `min(arr)` | 数组操作 | — |
| | `split(str, sep)`, `trim(str)` | 字符串操作 | — |
| **模块化** | `import data.shared.helpers` | 跨 `.rego` 文件共享 helper 函数 | — |
| | `import data.rules.common` | 导入共用规则库 | L4-L5 |

**规则特征**：
- 规则粒度：任意 JSON 输入粒度——从"检查分支名"（L2）到"检查 schema 变更细节"（L5），完全取决于应用传入的 input
- 规则扩展性：完全开放。用户用 Rego 编写任意复杂度的策略逻辑，OPA 不做任何限制
- 规则复用：支持 `import` 模块化和数据引用（`data.rules.common`）
- 语义感知：取决于 input——目前 Nessie 只传 4 个字符串，语义感知为零

---

### 5.3 lakeFS Hooks

lakeFS 提供六种事件类型作为 hook 触发点，并提供开源参考实现 `treeverse/lakeFS-hooks` [7]，包含四个预置 webhook。

**事件类型**

| 事件 | 时机 | 是否可阻止 | 约束层次 |
|------|------|:---:|:---:|
| `pre-commit` | commit 创建前 | 有条件（正常返回非 200 则拒绝，超时放行） | L3-L4 |
| `post-commit` | commit 创建后 | 否（仅通知） | — |
| `pre-merge` | merge 执行前 | 有条件（正常返回非 200 则拒绝，超时放行） | L3-L4 |
| `post-merge` | merge 执行后 | 否（仅通知） | — |
| `pre-create-branch` | 分支创建前 | 有条件 | L3 |
| `post-create-branch` | 分支创建后 | 否（仅通知） | — |

**预置 webhook 清单（来自 treeverse/lakeFS-hooks）**

| webhook | 功能 | 检查内容 | 规则粒度 | 约束层次 |
|---------|------|---------|:---:|:---:|
| **格式校验** | 检查文件格式是否符合预期 | 验证 Parquet/ORC/CSV 文件头，禁止不符合规范的文件 | 文件级 | L4 |
| **Schema 校验** | 检查 schema 中是否有敏感列 | 预定义敏感列名列表（email/phone/ssn/credit_card 等），命中则拒绝 | 列级 | L4 |
| **分区完整性** | 确保分区键不为空 | 扫描分区数据，检查是否存在 NULL 分区值 | 分区级 | L5 |
| **Commit 元数据** | 要求 commit 包含结构化信息 | 检查 commit message 格式（如 `[JIRA-123]` 前缀），不合规则拒绝 | 元数据级 | L3 |

**规则特征**：
- 规则粒度：文件级（webhook 收到的 diff 是文件变更列表，不是结构化语义）
- 规则扩展性：开放但成本高——每条新规则 = 新 webhook 服务（Python/Go 编写 + 部署 + 运维 + 监控）
- 规则复用：无。每个 webhook 是独立服务，无法共享逻辑
- 语义感知：取决于 webhook 实现——内置 4 个 webhook 的语义感知限于文件格式和列名匹配

---

### 5.4 SQL Migration Linters

#### Squawk 完整规则清单（32 条）

Squawk 的 32 条规则按操作类型分为五类 [9]：

| 类别 | 规则 ID | 说明 | 约束层次 |
|------|---------|------|:---:|
| **列变更** | `ban-drop-column` | 禁止删除列 | L4 |
| | `disallow-required-column` | 禁止添加 NOT NULL 且无 DEFAULT 的列 | L4 |
| | `disallow-column-type-change` | 禁止修改列类型（如 INT→BIGINT） | L4 |
| | `disallow-varchar-column` | 建议使用 text 代替 varchar(n) | L4 |
| | `disallow-unbounded-varchar` | 禁止无长度限制的 varchar | L4 |
| | `prefer-identity-column` | 建议使用 IDENTITY 列而非 SERIAL | L4 |
| | `prefer-bigint-over-int` | 建议主键使用 BIGINT | L4 |
| | `prefer-timestamptz` | 建议使用 timestamptz 而非 timestamp | L4 |
| | `require-column-comment` | 要求新列有 COMMENT | L4 |
| | `ban-unique-constraint` | 禁止添加 UNIQUE 约束（需显式审核） | L4 |
| | `require-cause-for-drop-column` | 删除列需要注释说明原因 | L3-L4 |
| **表变更** | `ban-drop-table` | 禁止删除表 | L4 |
| | `require-cause-for-drop-table` | 删除表需要注释说明原因 | L3-L4 |
| | `disallow-rename-table` | 禁止重命名表 | L4 |
| | `disallow-table-recreation` | 禁止在同一个迁移中 DROP + CREATE 同名表 | L4 |
| | `disallow-unlogged-table` | 禁止 UNLOGGED 表（不写 WAL，无法复制） | L4 |
| | `prefer-generic-index-name` | 建议使用通用索引名 | L4 |
| | `prefer-concurrent-index-creation` | 建议使用 CONCURRENTLY 创建索引 | L4 |
| **数据完整性** | `adding-field-with-default` | 添加带 DEFAULT 的列时警告（可能锁表） | L4 |
| | `adding-not-null-field` | 添加 NOT NULL 列时警告（需提供 DEFAULT） | L4 |
| | `ban-dropping-not-null` | 禁止移除 NOT NULL 约束 | L4 |
| | `require-cause-for-dropping-not-null` | 移除 NOT NULL 需注释说明 | L3-L4 |
| | `constraint-missing-not-valid` | 添加约束时必须使用 NOT VALID（避免全表扫描） | L4 |
| **事务与锁** | `disallow-lock-locking` | 禁止显式 LOCK TABLE 语句 | L4 |
| | `disallow-untrusted-extensions` | 禁止安装不受信的扩展 | L4 |
| | `require-concurrent-index-creation` | 索引创建必须使用 CONCURRENTLY | L4 |
| **安全性** | `ban-drop-database` | 禁止删除数据库 | L4 |
| | `require-citext-extension` | 大小写不敏感搜索需 citext 扩展 | L4 |
| | `disallow-add-column-with-default` | 禁止添加带 DEFAULT 的列（在旧版 PG 中可能重写全表） | L4 |
| | `prefer-robust-constraint-naming` | 建议使用明确的约束名 | L4 |
| | `disallow-timestamptz-without-timezone` | 禁止 timestamp without timezone | L4 |
| | `disallow-serial-type` | 禁止 SERIAL 类型 | L4 |

#### pgvet 规则清单（13 条）

pgvet 将规则按四个维度分类 [10]：

| 类别 | 规则 | 说明 |
|------|------|------|
| **Breaking** | `AddColumnDefaults` | 添加带 DEFAULT 的列 |
| | `AddColumnNotNull` | 添加 NOT NULL 列 |
| | `DropColumn` | 删除列 |
| | `RenameColumn` | 重命名列 |
| | `ChangeColumnType` | 修改列类型 |
| **Nullability** | `AddColumnNullable` | 添加可空列 |
| | `DropColumnNotNull` | 移除非空约束 |
| | `SetColumnNotNull` | 设置列为 NOT NULL |
| **Locking** | `CreateIndex` | 创建索引（可能锁表） |
| | `AddForeignKey` | 添加外键（可能锁表） |
| **Idempotency** | `AddColumnIfNotExists` | 使用 IF NOT EXISTS |
| | `CreateTableIfNotExists` | 使用 IF NOT EXISTS |
| | `DropColumnIfExists` | 使用 IF EXISTS |

#### Atlas 策略类别

Atlas [11] 使用 HCL 策略语言，支持自定义策略，内置策略类别包括：

| 类别 | 检查内容 | 约束层次 |
|------|---------|:---:|
| **Schema 变更** | 禁止 DROP TABLE/COLUMN，限制数据类型变更 | L4 |
| **数据完整性** | NOT NULL、DEFAULT 值、外键约束校验 | L4 |
| **命名规范** | 表名/列名/索引名/约束名必须符合命名规则 | L4 |
| **字符集与排序** | 禁止混用字符集和排序规则 | L4 |
| **存储引擎** | 限制使用特定存储引擎（如禁止 MyISAM） | L4 |
| **分区策略** | 分区键合法性，分区数量上限 | L4-L5 |
| **索引规范** | 索引列数量上限，前缀索引长度限制 | L4 |

**规则特征**（Squawk/pgvet）：
- 规则粒度：SQL 语句级，使用 PostgreSQL 原生解析器生成 AST 后做模式匹配
- 规则扩展性：**封闭**——规则编译在 Rust 二进制中，用户只能 TOML 排除列表启用/禁用，无法定义新规则
- 规则复用：无。每条规则是独立的 Rust 函数，不可组合
- 语义感知：SQL 语义（理解 DDL 的类型系统），但不理解 Iceberg 表语义

**规则特征**（Atlas）：
- 规则粒度：DB Schema 级，通过 dev database 模拟检测运行时问题
- 规则扩展性：**开放**——HCL 策略语言允许自定义新规则
- 规则复用：支持策略库（policy library）共享
- 语义感知：SQL 语义 + 运行时行为（锁、性能），但不理解 Iceberg 表语义

---

### 5.5 Nessie CEL Authorization

Nessie CEL 规则通过 4 个变量组合表达，规则无固定数量上限（`nessie.server.authorization.rules.*` 下的任意条目）。

**可用变量**

| 变量 | 类型 | 说明 | 可判定层次 |
|------|------|------|:---:|
| `op` | string | 操作类型（VIEW_REFERENCE, CREATE_ENTITY, UPDATE_ENTITY, DELETE_ENTITY, COMMIT_CHANGE_AGAINST_REFERENCE 等） | L1 |
| `role` | string | 用户角色（从认证系统获取） | L0 |
| `ref` | string | 分支名（如 `main`、`allowedBranch`） | L2 |
| `path` | string | 内容键字符串（如 `allowed-ns.table1`） | L2 |

**实际规则示例**

以下规则来自 Nessie 源码中的测试配置 `NessieAuthorizationTestProfile` [20]：

| 规则 ID | CEL 表达式 | 效果 | 约束层次 |
|---------|-----------|------|:---:|
| `allow_all` | `op in ['VIEW_REFERENCE', 'CREATE_REFERENCE', ...] && role == 'admin_user'` | 管理员有全部操作权限 | L0-L2 |
| `allow_branch_listing` | `op == 'VIEW_REFERENCE' && role.startsWith('test_user') && ref.matches('.*')` | 普通用户可查看所有分支 | L0-L2 |
| `allow_commits` | `op == 'COMMIT_CHANGE_AGAINST_REFERENCE' && role.startsWith('test_user') && ref.startsWith('allowedBranch')` | 普通用户只能在特定分支提交 | L0-L2 |
| `allow_create_not_read_entity` | `op in ['VIEW_REFERENCE', 'CREATE_ENTITY', 'UPDATE_ENTITY'] && role == 'test_user2' && path.startsWith('allowed-') && ref.startsWith('allowedBranch')` | 特定角色可创建但不能读取 | L0-L2 |
| `allow_no_create_entity` | `op in ['VIEW_REFERENCE', 'READ_ENTITY_VALUE', 'DELETE_ENTITY'] && role == 'test_user4' && path.startsWith('allowed-') && ref.startsWith('allowedBranch')` | 特定角色可读可删不能创建 | L0-L2 |
| `delete_branch_disallowed` | `op in ['VIEW_REFERENCE', 'CREATE_REFERENCE'] && role == 'delete_branch_disallowed_user' && ref in ['testDeleteBranchDisallowed', 'main']` | 特定用户只能查看和创建但不能删除分支 | L0-L2 |

**规则特征**：
- 规则粒度：分支名 + 内容键前缀 + 操作类型 + 用户角色——四个字符串维度的交叉判定
- 规则扩展性：开放——可定义任意数量 CEL 表达式，表达式语法（`in`, `startsWith`, `matches` 等）灵活
- 规则复用：无。每条规则独立，无双层判定之外的组织结构
- 语义感知：零——完全不知道操作的内容语义（不知道改了什么表、改了什么子结构）
- 热加载：**无**——启动时一次性编译，修改必须重启

---

### 5.6 Bauplan Expectations

Bauplan 的约束规则是系统内置的，围绕 pipeline 执行模型和数据质量设计。

**内置 Expectations**

| Expectation | 功能 | 约束层次 |
|------------|------|:---:|
| `expect_column_no_nulls` | 指定列不允许 NULL 值 | L5 |
| `expect_column_values_in_set` | 列值必须在预定义集合中（如枚举值校验） | L5 |
| `expect_column_values_to_be_between` | 列值在数值范围内 | L5 |
| `expect_column_values_to_match_regex` | 列值匹配正则表达式 | L5 |
| `expect_table_row_count_to_be_between` | 表行数在范围内 | L5 |
| `expect_column_pair_a_gt_b` | 列 A 的值大于列 B | L5 |

**管道安全规则**

| 规则 | 说明 | 约束层次 |
|------|------|:---:|
| 禁止 fork 临时分支 | 不允许从管道运行时创建的临时分支 fork 新分支 | L3 |
| 仅成功管道可合入 | 只有全部 expectation 通过的分支才能 merge 到 main | L3 |
| 临时分支隔离 | 同一管道的多个 step 输出写入独立的临时分支，互不干扰 | L3 |

**规则特征**：
- 规则粒度：表级（数据质量 expectation）、管道级（安全规则）
- 规则扩展性：**封闭**——用户只能使用内置 expectation，不能自定义新的约束类型
- 规则复用：无。expectation 在 pipeline YAML 中内联定义
- 语义感知：Pipeline 语义——理解临时分支、管道状态、expectation 通过/失败

---

### 5.7 规则差异对比总表

将六类技术的规则体系按 8 个维度横向对比：

| 维度 | GitHub Rulesets | OPA/Rego | lakeFS Hooks | Squawk | Atlas | Bauplan | Nessie CEL |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **规则数量** | ~15 固定项 | 无上限（取决于 .rego 文件） | 4 预置 + 可扩展（需写服务） | 32 条固定 | 无上限（HCL 自定义） | ~9 内置（6 expectation + 3 安全） | 无上限（CEL 表达式） |
| **规则粒度** | 分支级 | 任意（取决于 JSON input） | 文件级/列名级 | SQL 语句级 | DB Schema 级 | 表级/管道级 | 分支+路径+操作级 |
| **规则可扩展** | 否 | 是（Rego） | 是（写代码，成本高） | 否（TOML 开关） | 是（HCL 策略） | 否（内置框架） | 是（CEL 表达式） |
| **规则分类维度** | 分支操作 / 合并控制 / CI 门禁 / 元数据 | 用户自定义（无内置分类） | 格式 / Schema / 分区 / 元数据 | 列变更 / 表变更 / 完整性 / 锁 / 安全 | 用户自定义（无内置分类） | 数据质量 / 管道安全 | 角色 / 操作 / 分支 / 路径 |
| **覆盖层次** | L1-L3 | L0-L5* | L1-L4 | L3-L4 | L4-L5 | L3, L5 | L0-L2 |
| **语义感知** | 无 | 取决于 input* | 取决于 webhook 实现 | SQL AST 语义 | SQL + 运行时语义 | Pipeline + 数据质量语义 | 无（4 字符串） |
| **规则复用** | Ruleset 层叠 | import 模块化 | 无 | 无 | 策略库 | 无 | 无 |
| **热加载** | 是（SaaS 即时） | 是（Bundle） | 是（配置即代码） | 是（文件读取） | 是 | 是 | **否** |

> *\* OPA 的层次和语义感知完全取决于应用传入的 JSON input。当前 Nessie 只传 4 个字符串给 OPA，因此实际效果等同于 L0-L2、无语义感知。*

**关键差距总览**：

```
                    规则数量    粒度        可扩展    语义感知    复用      热加载
GitHub Rulesets      ~15      分支级       ✗          ✗        层叠      ✓
OPA/Rego             ∞        任意         ✓          ⚠*       模块化    ✓
lakeFS Hooks         4+       文件级       ⚠(成本高)  ⚠        ✗        ✓
Squawk               32       SQL语句级    ✗          SQL       ✗        ✓
Atlas                ∞        Schema级     ✓          SQL+运行  策略库    ✓
Bauplan              ~9       表/管道级    ✗          管道      ✗        ✓
Nessie CEL           ∞        4字符串      ✓          ✗         ✗        ✗
──────────────────────────────────────────────────────────────────────────
目标 (Ch8)           ∞        语义原语级   ✓          Iceberg   组合代数  ✓
```

> *\* OPA 的语义感知标记为 ⚠ 因为：理论可达 L5，但实际取决于 input。当前 Nessie→OPA input 仅 4 字符串，语义感知为零。*

---

## 6. LLM 应用于分支约束

第 3 章分析了六类分支约束技术的实现原理与能力边界，第 5 章呈现了完整规则清单与差异对比。本章讨论的 LLM 辅助规则生成**不是第七类分支约束技术**，而是作用于上述六类技术的**元能力**——LLM 本身不执行约束判定，它在编译时（compile-time）将自然语言翻译为目标引擎可执行的结构化规则，运行时（runtime）的约束评估完全由目标引擎完成，LLM 不参与。

### 6.1 角色定位：元能力而非分支约束技术

LLM 在分支约束体系中的位置：

```
                        LLM (元能力层)
                    NL → 结构化规则的编译器
                    ┌─────────────────────┐
                    │ Natural Language     │
                    │ "禁止在 main 上      │
                    │  删除任何 PII 列"    │
                    └─────────┬───────────┘
                              │ compile-time
                              ▼
                    ┌─────────────────────┐
                    │  结构化规则产物       │
                    │  Rego / YAML / CEL  │
                    └─────────┬───────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
    ┌──────────┐      ┌────────────┐      ┌──────────┐
    │   OPA    │      │  GitHub    │      │  Nessie  │
    │  (Rego)  │      │  Rulesets  │      │   CEL    │
    │          │      │  (YAML)    │      │          │
    └──────────┘      └────────────┘      └──────────┘
          ↑                   ↑                   ↑
          │                   │                   │
    运行时评估 (没有 LLM 参与，延迟不受 LLM 影响)
```

关键认知：LLM 类比于"编译器"——编译器把高级语言翻译成机器码，LLM 把自然语言翻译成 Rego/YAML/CEL。编译后的产物独立运行，编译器不在运行时参与。

### 6.2 技术原理：编译时翻译，运行时无关

2024-2025 年出现的新路径：用 LLM 将自然语言**编译**为结构化规则（Rego/YAML/CEL），编译发生在规则定义阶段（compile-time），运行时执行的是编译后的结构化规则，不经过 LLM。因此延迟和可靠性由目标规则引擎决定，与 LLM 调用无关。

```
                          Compile-time (离线)                    Runtime (在线)
  ┌─────────────────────────────────────────┐     ┌─────────────────────────┐
  │                                         │     │                         │
  │  "禁止在 main 分支上                     │     │   Nessie API Request    │
  │   删除任何 PII 列"                       │     │   (commit/merge)        │
  │                                         │     │         │               │
  │       ┌──────────────────────────────┐  │     │         ▼               │
  │       │        LLM Compiler          │  │     │  ┌──────────────────┐   │
  │       │  (GPT-4o / Claude / ...)    │  │     │  │  Rule Engine     │   │
  │       │                              │  │     │  │                  │   │
  │       │  NL → 结构化规则               │  │     │  │  执行编译后的规则  │   │
  │       │  (Rego / YAML / CEL)         │  │     │  │  (不经过 LLM)    │   │
  │       └──────────────┬───────────────┘  │     │  │                  │   │
  │                      │                  │     │  │  P99 < 5ms       │   │
  │                      ▼                  │     │  └──────────────────┘   │
  │  ┌──────────────────────────────────┐   │     │                         │
  │  │  编译产物 (静态规则文件)            │   │     │                         │
  │  │                                  │   │     │                         │
  │  │  Upwind     → Rego  → OPA        │   │     │                         │
  │  │  Watchflow  → YAML  → GitHub     │   │     │                         │
  │  │  OPA Gen    → Rego  → OPA        │   │     │                         │
  │  │  ARPaCCino  → Rego  → OPA        │   │     │                         │
  │  │  P2T        → Rules → 内部引擎    │   │     │                         │
  │  └──────────────────────────────────┘   │     │                         │
  └─────────────────────────────────────────┘     └─────────────────────────┘

  ⚠ 产物上限 = 目标引擎的语义理解能力
     引擎只能处理字符串 → NL 生成的规则也只能处理字符串
     引擎能消费 SemanticDiff → NL 生成的规则可达 L5 语义级别
```

### 6.3 生产级实现

| 系统 | 转换方向 | 说明 |
|------|---------|------|
| **Upwind** [12] | NL → Rego | 已发布的生产功能（CNAPP 平台）。用户在 SaaS 平台 UI 中以自然语言描述云安全需求，LLM 编译为 Rego 策略，自动推送到客户 K8s 集群中的 OPA Gatekeeper，即时生效 |
| **Watchflow** [13] | NL → YAML | 将自然语言描述映射为 GitHub Rulesets YAML 配置 |
| **OPA Generator** [14] | NL → Rego | 基于 MCP（Model Context Protocol）Agent + GPT-4o，覆盖文档检索→代码生成→Lint→测试→部署的完整生命周期 |

### 6.4 学术研究

- **ARPaCCino** [15]（ADBIS 2025）：用 Agentic-RAG 生成 Rego 策略。关键发现是 RAG + 工具链校验使较小 LLM 也能生成正确的规则，说明检索增强比模型尺寸更重要。
- **P2T** [16]（AAAI-26）：将 EU AI Act、HIPAA 等法律文档转化为可执行规则，实测违规率从 34% 降至 5%。

### 6.5 Upwind 深度分析

Upwind 是 NL→Rego 方向中最成熟的生产级代表，以下从约束对象和部署形态两个维度详细分析。

**部署形态：SaaS + OPA 运行时双层**

| 工具 | 部署形态 | 详细说明 |
|------|---------|---------|
| **Upwind** | **SaaS 平台 + OPA 运行时双层** | (1) 用户在 Upwind SaaS 平台 UI 输入自然语言；(2) LLM 在云端编译为 Rego；(3) 编译产物自动推送到客户 K8s 集群中的 OPA Gatekeeper；(4) 每次 kubectl 请求由 OPA Gatekeeper 就地评估。用户无需维护 Rego 文件或部署脚本。 |
| **Watchflow** | 工具链集成 | LLM 生成 GitHub Rulesets YAML，用户导入 GitHub 仓库 |
| **OPA Generator** | MCP Agent 工具链 | 本地工具链：文档检索 → 生成 → Lint → 测试 → 部署，覆盖完整生命周期 |
| **ARPaCCino / P2T** | 学术原型 | 研究阶段，侧重生成质量而非部署自动化 |

**约束对象**

Upwind 的约束领域是**云原生工作负载安全**，与本文讨论的数据湖分支约束完全不同：

```
Upwind 的约束对象 (云安全)          本文的目标约束对象 (数据湖分支)
─────────────────────────────      ─────────────────────────────
Kubernetes 资源:                    数据湖分支操作:
  - Pod, Deployment, Service          - Commit, Merge, Transplant
  - ConfigMap, Secret                 
  - Ingress, NetworkPolicy           Iceberg 表语义:
容器镜像:                              - Schema (列变更)
  - 镜像来源 (registry)                - PartitionSpec (分区变更)
  - 镜像签名验证                       - Snapshots (数据操作)
  - CVE 漏洞策略                       - Properties (属性变更)
云基础设施:                            - SortOrder (排序变更)
  - AWS/Azure/GCP 资源配置
  - IAM 策略                        约束目标:
网络策略:                              - 禁止破坏性 schema 变更
  - Pod-to-Pod 通信                    - 禁止 OverwriteFiles
  - Egress 限制                        - PII 列保护
                                       - 跨表一致性
```

**代码对比：K8s 安全策略 vs 数据湖分支约束**

```rego
# Upwind 生成的 Rego (K8s 安全)
package k8s.security

deny[msg] {
    input.request.kind.kind == "Pod"
    container := input.request.object.spec.containers[_]
    container.securityContext.runAsUser == 0
    msg := sprintf("禁止以 root 用户运行容器: %v", [container.name])
}

# 本文目标的 Rego (数据湖分支约束)
package nessie.constraints

deny[msg] {
    input.operation == "merge"
    input.targetRef == "main"
    change := input.semanticDiff.changes[_]
    change.field == "schema"
    change.action == "drop"
    change.column.pii == true
    msg := sprintf("禁止在 main 上删除 PII 列: %v", [change.column.name])
}
```

两者的 Rego 语法相同，OPA 运行时相同——差异仅在于 **input schema 不同**（Kubernetes Admission Review vs SemanticDiff）和 **prompt 模板不同**（云安全场景 vs 数据湖约束场景）。

### 6.6 NL→规则产物对比

各工具的输入、输出、约束对象和部署形态对比：

| 工具 | 输入 | 输出格式 | 目标引擎 | 约束对象 | 部署形态 |
|------|------|---------|---------|---------|---------|
| **Upwind** [12] | 自然语言安全需求 | Rego 策略文件 | OPA Gatekeeper | K8s 工作负载、容器镜像、云基础设施、网络策略 | SaaS + OPA 运行时双层 |
| **Watchflow** [13] | 自然语言分支策略 | GitHub Rulesets YAML | GitHub Rulesets | Git 分支（通用） | 工具链集成 |
| **OPA Generator** [14] | 自然语言 + 文档检索 | Rego 策略 + Lint + 测试 | OPA | 取决于 prompt 模板（通用策略引擎） | MCP Agent 工具链 |
| **ARPaCCino** [15] | 自然语言 + RAG 检索 | Rego 策略 | OPA | 取决于 prompt 模板（通用） | 学术原型 |
| **P2T** [16] | 法律文档（EU AI Act, HIPAA） | 可执行规则 | 内部引擎 | 法律合规（数据隐私、AI 伦理） | 学术原型 |

### 6.7 核心结论与迁移路径

**三个核心结论**：

1. **NL→Rego 管线已被 Upwind 在生产中验证**，不是学术原型。用户自然语言输入 → LLM 编译 → 结构化规则 → 自动部署 → 运行时引擎评估——这条链路在云安全领域已闭环。

2. **产物上限 = 目标引擎的语义理解能力**。用 NL 生成 Nessie CEL 规则，永远只能到 L2（四个字符串），因为 CEL 引擎本身不懂语义。这是引擎瓶颈而非 LLM 瓶颈。

3. **迁移到数据湖只需换 prompt 模板和部署目标**。将 Upwind 模式从云安全迁移到数据湖分支约束，需要改变的：
   - **prompt 模板**：从描述 K8s API 的 input schema 改为描述 `SemanticDiff` schema 和语义原语（第 8.3 节定义的约 10 个原语）
   - **部署目标**：从 OPA Gatekeeper（K8s Admission Controller）改为 OPA Sidecar 连接 Nessie（或 OPA WASM 嵌入 Nessie 进程）
   - **不需要改变的**：LLM 编译能力、OPA 运行时、Rego 语法、Bundle 热加载机制

---

## 7. 横向对比

### 7.1 约束能力对比矩阵

将以上六类技术按关键维度汇总：

| 属性 | GitHub Rulesets | OPA/Rego | lakeFS Hooks | Squawk | Atlas | Bauplan | Nessie CEL |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **约束对象** | Git 分支 | 通用 | 数据湖分支 | SQL DDL | DB Schema | Pipeline | Nessie 分支 |
| **覆盖层次** | L1-L3 | L0-L5* | L1-L4 | L4 | L4-L5 | L3, L5 | L0-L2 |
| **规则表达** | GUI/YAML | Rego | Webhook 代码 | TOML 排除表 | HCL 策略 | Python 函数 | CEL 表达式 |
| **规则可扩展** | 否（固定项） | 是 | 是（写代码） | 否（32 条固定） | 是（自定义策略） | 否（内置框架） | 是（写 CEL） |
| **声明式** | 是 | 是 | 否（代码逻辑） | 半声明式 | 是 | 否 | 是 |
| **规则复用** | 是（层叠） | 是（import） | 否 | 否 | 是（策略库） | 否 | 否 |
| **语义感知** | 否 | 取决于 input | 取决于 webhook | SQL 语义 | SQL 语义 | Pipeline 语义 | 否（4 字符串） |
| **准入控制** | 间接（CI gate） | 是（pull 查询） | 有条件（超时放行） | 间接（CI 检查） | 间接（CI 检查） | 是 | 是 |
| **免重启热加载** | 是 | 是（Bundle） | 是（配置即代码） | 是 | 是 | 是 | **否** |

> *\* OPA 的层次取决于应用传入的 input，理论上可达 L5，但 Nessie 当前只传 4 个字符串给它。*

### 7.2 部署形态分类

六类技术体现了七种不同的部署模式，各有适用场景：

| 部署形态 | 代表技术 | 部署复杂度 | 延迟 | 约束粒度 | 适用场景 |
|---------|---------|:---:|------|---------|---------|
| **SaaS 平台托管** | GitHub Rulesets | 最低 | 即时 | 固定配置项 | 通用 Git 仓库，不需要自定义检查逻辑 |
| **外部组件（HTTP）** | OPA Sidecar | 中 | 1-5ms | 任意（取决于 input） | 需要通用策略引擎，能接受额外部署运维 |
| **外部组件（进程内）** | OPA WASM | 低 | ~μs | 任意（取决于 input） | 需要低延迟 + 跨语言嵌入 + 无外部进程 |
| **Webhook 外部服务** | lakeFS Hooks | 高 | 取决于 webhook | 任意（取决于代码） | 事件通知/审计，不要求硬性准入控制 |
| **CI 二进制** | Squawk, pgvet | 低 | 秒级 | SQL DDL 语句 | SQL schema linter，CI 流水线集成 |
| **CI + Dev DB** | Atlas | 中 | 秒-分钟 | DB Schema + 运行时 | 需要运行时模拟检测的 DevOps 平台 |
| **运行时内嵌** | Bauplan | 低（平台内置） | 即时 | 内置框架 | pipeline 与版本控制深度耦合的场景 |
| **内部配置** | Nessie CEL | 最低 | ~μs | 4 字符串 | 简单的 RBAC 访问控制，不需语义感知 |

### 7.3 热加载机制分类

六类技术的规则热加载分属四种模式：

| 模式 | 代表技术 | 机制 | 生效延迟 | 是否需要重启 |
|------|---------|------|:---:|:---:|
| **配置即代码** | lakeFS (`_lakefs_actions/`)、Squawk (`.squawk.toml`) | 规则文件存储在仓库中，引擎每次读取或从 HEAD 加载最新版本 | 即时（下次检查时） | 否 |
| **Bundle 热加载** | OPA | 周期性/事件驱动地从 Git/S3 拉取规则 bundle → 编译验证 → 原子替换活跃策略集；编译失败保留旧策略 | 默认 60s（可配置） | 否 |
| **平台托管** | GitHub Rulesets、Bauplan | SaaS 后台即时同步，用户无需关心部署细节 | 即时 | 否 |
| **无热加载** | Nessie CEL | `@Singleton` + `@Startup` 一次性编译到 `ImmutableMap`，无重载路径 | N/A | **是** |

**核心发现**：在六类技术中，Nessie CEL 是唯一需要重启的。其他五类技术都通过不同路径实现了规则变更的即时生效——对数据湖分支约束系统而言，"配置即代码"是最自然的路径（因为 Nessie 本身就是一个版本控制系统）。

---

### 7.4 语义模型对比

第 5.7 节的规则差异对比总表从八个维度对比了六类技术的规则体系，但止步于"是什么"——规则数量、粒度、可扩展性等表层的量化指标。一个更深层的问题是：**为什么不同技术的规则体系差异如此之大？**

答案在于各技术的**语义模型**——约束引擎能感知到什么实体、能观测到什么操作、能检查什么属性、能理解什么关系。语义模型定义了引擎的"世界图景"，直接决定了规则表达力的天花板。第 5.7 节表中"规则粒度"和"语义感知"两列触及了这个概念，但未系统展开。本节为每类技术建立完整的语义模型画像，并做横向对比。

#### 7.4.1 各技术语义模型

**GitHub Rulesets — Git 协作元数据模型**

GitHub Rulesets 的语义模型完全围绕 Git 仓库的**协作流程元数据**构建，对仓库内容（代码）本身没有感知。

| 维度 | 内容 |
|------|------|
| **感知世界** | Git 仓库的协作工作流——分支、PR、Code Review、CI/CD 流水线，完全不管仓库里存的是什么代码 |
| **核心实体** | `Branch`（分支名 + pattern）、`User`（角色/团队）、`PullRequest`（PR 状态）、`StatusCheck`（CI job 状态）、`Deployment`（环境部署状态） |
| **可观测操作** | `push`、`force_push`、`merge`、`delete_branch`、`create_branch` |
| **可检查属性** | 分支名是否匹配 pattern、是否有 open PR、approval 数量是否达标、CODEOWNERS 是否批准、status check 是否通过、commit 是否签名 |
| **关系感知** | 分支 ↔ Ruleset（多对多，pattern 匹配 + 层叠聚合）、PR ↔ Branch（目标分支+源分支）、User ↔ Team |
| **语义盲区** | 完全不感知仓库内容——PR 改了一行注释还是删了整个模块，对 Rulesets 来说没有区别。也不感知跨仓库依赖 |

**OPA/Rego — 空白画布模型**

OPA 的语义模型极为特殊：它本身**没有固定语义模型**。它是一个空白的策略引擎，其语义模型完全由应用在 JSON input 中传递的数据结构定义。

| 维度 | 内容 |
|------|------|
| **感知世界** | 无预设世界——应用通过 JSON input 告诉 OPA"这个世界长什么样" |
| **核心实体** | 无内置实体——`input` JSON 的每个字段都是实体。当前 Nessie→OPA 的 input 仅含 `op`、`role`、`ref`、`path` 四个字符串 |
| **可观测操作** | 无内置操作——`input.operation` 的值完全由应用定义 |
| **可检查属性** | 无内置属性——通过 Rego 内置函数（`glob.match`、`re_match`、集合推导等）对 input JSON 的任意字段做计算 |
| **关系感知** | 无内置关系——但 Rego 的 `every`/`some` 遍历 + 集合推导 + `import` 模块化可以表达任意复杂的关系逻辑 |
| **语义盲区** | OPA 本身没有盲区——盲区完全在应用侧。Nessie 只传 4 个字符串 → OPA 的语义模型坍缩为 L0-L2；若 Nessie 传入完整 SemanticDiff → OPA 可达 L5。**语义模型的瓶颈不在 OPA，而在应用的 input 构造** |

> OPA 的语义模型可类比 SQL：SQL 本身不内置任何表结构，查询能力取决于 `FROM` 子句中引用的表有什么列。OPA 的 Rego 也一样——查询能力取决于 `input` JSON 里有什么字段。

**lakeFS Hooks — 文件系统差分模型**

lakeFS Hooks 的语义模型围绕**类文件系统的版本控制**构建。它将数据湖操作建模为"文件的增删改"，钩子接收的是文件级别的差异信息。

| 维度 | 内容 |
|------|------|
| **感知世界** | 类 Git 的数据湖仓库——分支、commit、文件路径、文件内容（字节流） |
| **核心实体** | `Repository`、`Branch`、`Commit`、`Object`（文件/对象）、`Diff`（文件变更列表：added/removed/changed） |
| **可观测操作** | `pre-commit`、`post-commit`、`pre-merge`、`post-merge`、`pre-create-branch`、`post-create-branch` |
| **可检查属性** | 文件路径（字符串匹配）、文件格式（魔数/文件头）、文件内容（由 webhook 自行解析）、commit 元数据（message、author） |
| **关系感知** | 分支 ↔ Commit（父子链）、Commit ↔ Diff（变更文件列表）。无跨分支关系感知 |
| **语义盲区** | 关键盲区：**不理解文件格式的子结构语义**。lakeFS 知道"文件 `ns1/table1/metadata.json` 被修改了"，但它不知道这个修改对应 Iceberg TableMetadata 的 `schema` 子结构还是 `partitionSpec` 子结构。解析 Iceberg 元数据的语义完全由 webhook 代码自行实现。此外：无跨表关系感知、无 schema 演进方向感知（向前兼容 vs 破坏性） |

**Squawk / pgvet — SQL DDL 类型系统模型**

Squawk 和 pgvet 的语义模型建立在 PostgreSQL 的 **DDL 类型系统**之上。它们用 PostgreSQL 原生解析器将 SQL 文本转化为 AST，然后对 AST 做模式匹配。

| 维度 | 内容 |
|------|------|
| **感知世界** | PostgreSQL 数据库的 DDL 操作——表结构变更、列变更、约束变更、索引变更 |
| **核心实体** | `Table`、`Column`（name + type + constraints）、`Index`、`Constraint`（NOT NULL / UNIQUE / FK / CHECK）、`Schema`、`Database`、`Extension` |
| **可观测操作** | `CREATE_TABLE`、`DROP_TABLE`、`ALTER_TABLE`、`ADD_COLUMN`、`DROP_COLUMN`、`ALTER_COLUMN_TYPE`、`ADD_CONSTRAINT`、`DROP_CONSTRAINT`、`CREATE_INDEX`、`DROP_INDEX`、`LOCK_TABLE` |
| **可检查属性** | 列名、列类型（INT/BIGINT/VARCHAR/TEXT/TIMESTAMP）、是否 NOT NULL、是否有 DEFAULT、是否 SERIAL/IDENTITY、约束名、索引名、索引是否 CONCURRENTLY、迁移是否包含注释说明 |
| **关系感知** | 列 ↔ 表（归属）、约束 ↔ 列/表（依赖）、迁移脚本内操作顺序。无跨表外键完整性感知（不连接数据库做运行时验证） |
| **语义盲区** | 两个关键盲区：（1）**领域限定**——理解 SQL DDL 类型系统但不理解 Iceberg TableMetadata 的五大子结构（schema / partitionSpec / sortOrder / snapshots / properties），不知道什么是 OverwriteFiles；（2）**静态分析**——基于 SQL 文本分析，不连接数据库，无法感知运行时状态（锁持有者、并发写入者、实际数据量） |

**Atlas — SQL 语义 + 运行时行为模型**

Atlas 在 Squawk 的 DDL 类型系统之上叠加了**运行时行为感知**——通过 Dev Database 在实际数据库引擎中模拟迁移并观察副作用。

| 维度 | 内容 |
|------|------|
| **感知世界** | 数据库 Schema 的全生命周期管理——从 DDL 编写、模拟执行、到生产部署 |
| **核心实体** | 同 Squawk（Table、Column、Index、Constraint）+ `Migration`（版本化变更单元）+ `DevDB`（模拟环境） |
| **可观测操作** | 同 Squawk 的 DDL 操作 + `MIGRATE`（执行迁移）、`SIMULATE`（模拟运行） |
| **可检查属性** | 同 Squawk 的 DDL 属性 + **运行时行为**：锁持有时间、全表扫描风险、性能退化、复制延迟影响 |
| **关系感知** | 同 Squawk + 迁移版本链（`migration_history`）、策略 ↔ 目标数据库的绑定 |
| **语义盲区** | （1）**领域限定**——和 Squawk 相同，不理解 Iceberg 表语义；（2）**Dev DB 差异**——Dev DB 的数据量和并发模式与生产可能不同，模拟结果不等于生产行为；（3）**无版本控制感知**——不理解分支/merge/rebase 概念，约束在 CI 流水线中执行而非在版本控制引擎内部 |

**Bauplan Expectations — Pipeline 执行 + 数据质量模型**

Bauplan 的语义模型紧密耦合其 **Pipeline 执行引擎**，约束围绕两个维度：Pipeline 的运行时状态安全和表级数据质量。

| 维度 | 内容 |
|------|------|
| **感知世界** | Pipeline 编排的数据处理工作流——步骤（step）、临时分支（temp branch）、数据质量检查、成功/失败状态 |
| **核心实体** | `Pipeline`、`Step`、`TempBranch`（管道运行时创建的隔离分支）、`MainBranch`、`Table`、`Column`、`Expectation`（数据质量断言） |
| **可观测操作** | `step_execute`、`expectation_check`、`merge_to_main`、`fork_branch` |
| **可检查属性** | 列级：NULL 约束、值域范围、正则匹配；表级：行数范围；列间：大小关系（column_a > column_b）；管道级：是否所有 step 成功、是否所有 expectation 通过 |
| **关系感知** | Pipeline → Step → TempBranch 的归属链、TempBranch 的隔离性（禁止被 fork）、跨 step 的数据依赖 |
| **语义盲区** | （1）**不可用户扩展**——内置 ~9 种约束，用户不能自定义新的 expectation 类型；（2）**无 schema 结构感知**——expectation 不区分 schema 变更和数据变更，只检查数据值；（3）**平台绑定**——语义模型和 Bauplan 运行时强耦合，无法脱离 Bauplan 独立使用 |

**Nessie CEL — 四字符串身份访问模型**

Nessie CEL 的语义模型由四个字符串变量定义，是所有六类技术中语义模型最薄的。

| 维度 | 内容 |
|------|------|
| **感知世界** | Nessie API 的访问控制层——谁（role）在哪个分支（ref）上对哪个内容键（path）做了什么操作（op） |
| **核心实体** | `Role`（字符串）、`Branch`（ref 字符串）、`ContentKey`（path 字符串）、`Operation`（op 枚举字符串） |
| **可观测操作** | `VIEW_REFERENCE`、`CREATE_REFERENCE`、`DELETE_REFERENCE`、`VIEW_REFLOG`、`LIST_COMMITLOG`、`READ_ENTRIES`、`READ_CONTENT_KEY`、`COMMIT_CHANGE_AGAINST_REFERENCE`、`ASSIGN_REFERENCE_TO_HASH`、`CREATE_ENTITY`、`UPDATE_ENTITY`、`READ_ENTITY_VALUE`、`DELETE_ENTITY` |
| **可检查属性** | 分支名的字符串匹配（`ref.startsWith('allowedBranch')`）、内容键的字符串前缀匹配（`path.startsWith('allowed-')`）、角色字符串匹配（`role == 'admin_user'`）、操作类型集合归属（`op in [...]`） |
| **关系感知** | 无。四个变量各自独立判定，无实体间关系 |
| **语义盲区** | **全面语义盲区**——完全不知道操作的内容语义：（1）不知道 commit 修改了什么表、什么子结构；（2）不知道 schema 变更是 add_column 还是 drop_column；（3）不知道数据操作是 AppendFiles 还是 OverwriteFiles；（4）不知道任何 Iceberg TableMetadata 的子结构。语义模型停留在 L0-L2（身份→操作→对象），不进入 L3+（过程→结构→语义） |

#### 7.4.2 语义模型横向对比总表

将六类技术的语义模型按六个维度横向对比：

| 维度 | GitHub Rulesets | OPA/Rego | lakeFS Hooks | Squawk | Atlas | Bauplan | Nessie CEL |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **感知领域** | Git 协作元数据 | 无预设（由 input 定义） | 文件系统差分 | SQL DDL 类型系统 | SQL DDL + 运行时行为 | Pipeline + 数据质量 | API 访问控制 |
| **实体丰富度** | 5 类（分支/用户/PR/检查/部署） | 0 内置（∞ 可注入） | 5 类（仓库/分支/提交/对象/差异） | 7+ 类（表/列/索引/约束/模式/数据库/扩展） | 7+ + 迁移 + DevDB | 6 类（管道/步骤/临时分支/主分支/表/列） | 4 个字符串（角色/分支/路径/操作） |
| **可观测操作** | 5 种 Git 操作 | 无内置 | 6 种分支事件 | 12+ 种 DDL 操作 | 12+ DDL + 迁移 + 模拟 | 4 种管道操作 | 13 种 API 操作 |
| **属性空间** | 分支名/审批数/检查状态/签名 | 无内置（取决于 input） | 文件路径/格式/内容字节/元数据 | 列名/类型/约束/索引属性 | + 锁/性能/复制延迟 | 列值/行数/列间关系 | 4 个字符串匹配 |
| **关系感知** | 分支↔规则集、用户↔团队 | 无内置（Rego 可表达任意关系） | 分支↔提交链、提交↔差异 | 列↔表、约束↔列、操作顺序 | + 迁移版本链、策略↔数据库 | 管道→步骤→临时分支 | 无 |
| **可表达约束层次** | L0-L3 | L0-L5* | L1-L4 | L3-L4 | L4-L5 | L3, L5 | L0-L2 |

> *\* OPA 的理论上限取决于 input。当前 Nessie→OPA 仅传 4 字符串，实际仅达 L0-L2。*

#### 7.4.3 语义模型光谱：从"薄"到"厚"

将六类技术按语义模型的"厚度"——即能感知的实体、属性、关系的丰富程度——排列为一条光谱：

```
语义模型厚度光谱（薄 → 厚）

Nessie CEL    GitHub RS    lakeFS      OPA*         Bauplan     Squawk      Atlas      目标(Nessie)
    │             │           │           │            │           │           │            │
    4字符串      元数据      文件差分    任意JSON    Pipeline     SQL DDL    SQL+运行时  Iceberg语义
    L0-L2        L1-L3       L1-L4      L0-L5*      L3,L5       L3-L4      L4-L5       L0-L5
    ←── 只知"谁" ──────────────────────→ 知"改了啥" ──→ 知"结构" ──→ 知"运行时" ──→ 知"语义" ──→
```

关键观察：

1. **Nessie CEL 在最左端**——四个字符串变量，无任何内容语义感知。这是六类技术中最薄的语义模型，也是第 4 章空白 2 的直接体现。

2. **OPA 的位置是"浮动"的**——它在这个光谱上的位置完全由应用的 input 决定。当前 Nessie→OPA 的 input 只有 4 个字符串 → OPA 实际落在 Nessie CEL 旁边；如果 Nessie 传入完整 SemanticDiff → OPA 可以直接跳到最右端。

3. **Squawk/Atlas 在右端但领域不匹配**——它们的语义模型很"厚"（理解 SQL DDL 类型系统 + 运行时行为），但面向的是关系型数据库的 DDL 操作，不是 Iceberg 表语义。语义模型的"厚度"和"领域相关性"是两回事。

4. **没有一个技术理解 Iceberg 表语义**——光谱最右端是空白。没有任何现有技术的语义模型能感知 Iceberg `TableMetadata` 的五大子结构（schema / partitionSpec / sortOrder / snapshots / properties）及其变更语义。

#### 7.4.4 语义模型与规则表达力的因果关系

回到第 5.7 节的问题：**为什么不同技术的规则差异如此之大？** 答案现在很清楚了——语义模型直接决定了规则能"看到"什么、从而能"约束"什么：

```
语义模型          →    可表达的规则类型        →    规则差异的根源
────────────────────────────────────────────────────────────────
Nessie CEL:            "admin_user 能在 main    只能做身份+操作+
  op, role, ref, path   上 COMMIT 吗？"           对象的三维交叉判定

GitHub Rulesets:       "合并到 main 需要         只能约束协作流程，
  Branch, PR, Review    2 人 approve 吗？"        不能约束提交内容

lakeFS Hooks:          "这次 merge 修改了         能检查文件级变更，
  Diff, File path       Parquet 文件头吗？"        但不理解表语义

Squawk:                "添加的列类型必须是         能检查 SQL DDL 的
  Table, Column, Type   BIGINT 而非 INT 吗？"      结构合法性

Atlas:                 "这个 DDL 在生产上          能检查 DDL + 运行时
  Migration, DevDB      会锁表多久？"              行为影响

Bauplan:               "表 X 的 email 列           能检查数据质量和
  Pipeline, Expectation 有 NULL 值吗？"            管道状态安全

目标 (Nessie 扩展):     "merge 到 main 时，        能检查 Iceberg 表
  Iceberg TableMetadata 是否删除了 PII 列？"       语义级变更合规性
  + SemanticDiff
```

**核心结论**：规则的差异不是语法层面的（Rego vs CEL vs YAML），而是**语义模型层面的**——引擎的"世界图景"有多大，能表达的规则空间就有多大。Nessie CEL 只能"看到"四个字符串 → 只能写出四字符串交叉判定的规则。目标系统需要"看到" Iceberg 表语义 → 才能写出"禁止删除 PII 列"这样的 L5 语义规则。这个因果关系链条是：**语义模型的厚度 → 原语集的丰富度 → 规则表达力 → 约束深度**。

这条链也解释了第 4.2 节为什么将"面向数据湖表语义的规则引擎"定位为**关键瓶颈空白**——只要语义模型不扩展，第 5.7 节表中的其他维度（规则数量、可扩展性、规则复用）的提升都无法突破 L2 天花板。

---

## 8. 系统设计：语义感知分支约束

### 8.1 设计目标矩阵

| # | 目标 | 对应空白 | 对应技术现状 |
|---|------|:---:|------|
| G1 | 用户可用语义原语拼装新约束，无需改引擎代码 | 空白 1 | Squawk 做不到 |
| G1+ | 支持规则仿真、时效性规则、理由覆盖、跨表约束、规则组合 | 空白 1 扩展 | 全做不到 |
| G2 | 规则引擎消费 Iceberg `TableMetadata` 变更语义 | 空白 2 | Nessie CEL 做不到 |
| G3 | 检查点精准嵌入 Nessie commit/merge 的 CAS 路径 | 空白 3 | lakeFS/GitHub 做不到 |
| G4 | 支持规则严重性分级与操作优先级分流 | 空白 4 | Squawk/GitHub 做不到 |
| G5 | 支持策略模板：多分支共享 + 单分支叠加 | 空白 5 | OPA 通用但缺领域模板 |
| G6 | 规则存储在 Nessie 仓库中，变更即时生效 | 空白 6 | Nessie CEL 做不到 |
| G7 | LLM 编译管道：自然语言 → 语义规则原语 → 结构化规则（配合第 6 章所述元能力） | 规则编写效率 | Upwind 已实现但面向云安全 |

### 8.2 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                    约束规则定义层（G1, G1+, G5, G7）          │
│                                                              │
│  语义原语 + 规则模板 DSL + 五个创新扩展 + LLM 编译管道          │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ rule "prod-schema-safety" {                         │    │
│  │   target: ref = "main"                              │    │
│  │   scope: merge                                      │    │
│  │   predicate: change.field == "schema"               │    │
│  │            && change.action == "drop_column"         │    │
│  │   action: REJECT                                    │    │
│  │   severity: ERROR                                   │    │
│  │   override: HARD           ← 三元判定               │    │
│  │   validity: {              ← 时效性规则              │    │
│  │     ttl: "2026-12-31"                               │    │
│  │     rollout: { warn: "7d", auto_promote: true }     │    │
│  │   }                                                 │    │
│  │ }                                                   │    │
│  │                                                     │    │
│  │ rule "cross-table-lockstep" {  ← 跨表约束           │    │
│  │   scope: merge                                      │    │
│  │   tables: ["customers", "orders"]                   │    │
│  │   predicate: synchronized(customers.fk_columns,      │    │
│  │                            orders.pk_columns)        │    │
│  │   action: REJECT                                    │    │
│  │ }                                                   │    │
│  │                                                     │    │
│  │ template "prod-safety" {     ← 规则组合代数          │    │
│  │   extends: "base-safety"                            │    │
│  │   rules: [no-drop-column, no-overwrite, pii-guard]  │    │
│  │ }                                                   │    │
│  │                                                     │    │
│  │ # LLM 编译管道 (G7, 配合第5章)                       │    │
│  │ NL: "禁止删除 PII 列"                                │    │
│  │   → LLM → 规则模板 (prompt 含语义原语 schema)         │    │
│  │   → 结构化规则 → _nessie_constraints 仓库            │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  规则存储：Nessie 仓库 _nessie_constraints/main（G6）         │
│  仿真工具：nessie constraint simulate 命令（G1+）            │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                    规则引擎层（G2, G4）                        │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              ConstraintRuleEngine                    │    │
│  │                                                     │    │
│  │  evaluate(branch, semanticDiff)                      │    │
│  │    → { passed: Rule[], violated: Violation[],        │    │
│  │        overridden: Override[] }                      │    │
│  │                                                     │    │
│  │  执行逻辑：                                            │    │
│  │  1. 接收 ContentSemantics 产出的 SemanticDiff         │    │
│  │  2. 从 _nessie_constraints 加载当前分支的规则集        │    │
│  │  3. 过滤 target 匹配当前 branch 的规则                 │    │
│  │  4. 对每条规则的 predicate 求值                        │    │
│  │  5. 按优先级分流：                                     │    │
│  │     P0（存在性）+ ERROR + HARD → 直接阻断              │    │
│  │     P1（破坏性）+ ERROR + SOFT → 允许 OVERRIDE         │    │
│  │     WARN → 发出警告但不阻断                            │    │
│  │  6. 聚合结果                                           │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                    语义信息提供层（G2）                        │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │          ContentSemantics SPI（已有，需扩展）          │    │
│  │  ┌─────────────────────────────────────────────┐   │    │
│  │  │ IcebergContentSemantics                       │   │    │
│  │  │                                              │   │    │
│  │  │ diff(base, source, target) → SemanticDiff {  │   │    │
│  │  │   changes: [                                  │   │    │
│  │  │     {field: "schema", action: "add_column",   │   │    │
│  │  │      column: "email", type: "STRING"},        │   │    │
│  │  │     {field: "snapshots",                      │   │    │
│  │  │      operation: "AppendFiles",                │   │    │
│  │  │      files: ["file-a1.parquet"]}              │   │    │
│  │  │   ]                                           │   │    │
│  │  │ }                                             │   │    │
│  │  └─────────────────────────────────────────────┘   │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                    集成点：Nessie 写入路径（G3）                │
│                                                              │
│  1. Commit → CommitLogicImpl.individualCommit()              │
│     约束检查在 CAS 之前                                       │
│  2. Merge  → MergeLogic.merge()                              │
│     约束检查在 merge 提交新建之前                              │
│  3. Transplant → Transplant.merge()                          │
│     同 merge 逻辑                                            │
└─────────────────────────────────────────────────────────────┘
```

### 8.3 规则定义层：可组合语义原语

实现 G1。定义一组最小正交的语义原语，用户通过组合它们表达任意约束。以下是按第 5 章规则清单分析后提炼的最小正交原语集：

**一类原语：变更定位**（描述"在哪里发生了什么"）

| 原语 | 类型 | 取值 | 说明 |
|------|------|------|------|
| `change.field` | enum | `schema`, `partitionSpec`, `properties`, `snapshots`, `sortOrder` | Iceberg TableMetadata 的五大子结构 |
| `change.action` | enum | `add`, `drop`, `modify`, `replace` | 对该子结构的操作类型 |
| `change.operation` | enum | `AppendFiles`, `OverwriteFiles`, `DeleteFiles`, `RewriteFiles` | 仅 field=snapshots 时生效 |

**二类原语：对象属性**（描述被操作对象的特征）

| 原语 | 类型 | 说明 |
|------|------|------|
| `change.column.name` | string | 被操作的列名 |
| `change.column.type` | string | Iceberg 类型（INTEGER, STRING, etc.） |
| `change.column.pii` | boolean | 是否为 PII 列（外部元数据分类） |
| `change.property.key` | string | 被修改的 property 键 |

**三类原语：约束范围**

| 原语 | 类型 | 说明 |
|------|------|------|
| `ref` | string | 目标分支名，支持 glob（`main`、`prod-*`） |
| `scope` | enum | 约束生效的操作范围：`commit`、`merge`、`both` |

**组合示例**：

```
"禁止在 prod-* 分支上删除 PII 列" →
  ref matches "prod-*"
  && scope in [commit, merge]
  && change.field == "schema"
  && change.action == "drop"
  && change.column.pii == true
  → action: REJECT, severity: ERROR, override: HARD

"warn 所有 OverwriteFiles 操作" →
  ref = "*" && scope = merge
  && change.field == "snapshots"
  && change.operation == "OverwriteFiles"
  → action: WARN, severity: WARNING
```

这套原语共约 10 个，覆盖 Iceberg 表变更的主要语义维度。对比 Squawk 的 32 条不可拆分的规则（第 5.4 节），10 个原语可以组合出远多于 32 种约束，而且覆盖的是 Iceberg 语义而非 SQL 语义。配合第 6 章的 LLM 编译管道，用户可用自然语言描述约束，LLM 自动翻译为基于这些原语的规则模板。

### 8.4 规则定义层的五个创新扩展

实现 G1+。在可组合原语之上，为规则 DSL 增加以下能力：

**1. 规则仿真 / 干跑（`nessie constraint simulate`）**

```
CLI:
  $ nessie constraint simulate \
      --rule-file rules/constraints.yml \
      --branch main \
      --since "30 days ago"

输出：
  扫描 128 个 commit，命中 15 个
  ┌────────────────────────────────────────────┬──────────┐
  │ Rule                                       │ Matches  │
  ├────────────────────────────────────────────┼──────────┤
  │ prod-schema-safety (REJECT)                │ 3        │
  │ pii-partition-check (REJECT)               │ 0        │
  │ warn-on-overwrite (WARN)                   │ 12       │
  └────────────────────────────────────────────┴──────────┘

  按规则 prod-schema-safety：
  如果该规则在 30 天前生效，会拦截以下 3 次操作：
    - commit abc123: drop_column(email) on ns1.users
    - commit def456: drop_column(phone) on ns2.contacts
    - commit ghi789: drop_column(ssn) on ns3.employees
```

**2. 时效性规则**

```yaml
rule "release-freeze" {
  target: ref = "main"
  scope: merge
  predicate: change.field == "schema"
  action: REJECT
  validity: {
    time_window: "2026-06-01..2026-06-15"  # 发布冻结期
    ttl: null                                # 到期失效
  }
}

rule "new-rule-gradual" {
  target: ref matches "prod-*"
  scope: commit
  predicate: change.operation == "OverwriteFiles"
  validity: {
    rollout: {
      initial_severity: WARN     # 先以 WARN 模式运行
      duration: "7d"             # 观察 7 天
      auto_promote: true         # 7天后自动升级为 ERROR
      max_warn_count: 50         # 如果 WARN 超过 50 次则不自动升级
    }
  }
}
```

**3. 理由覆盖（三元判定）**

```
rule "pii-no-drop" {
  predicate: change.action == "drop" && change.column.pii == true
  action: REJECT
  override: SOFT           # 允许理由覆盖

  override_constraints: {
    max_per_day: 3         # 每天最多 override 3 次
    require_approver: false # SOFT 允许自签
    auto_review: "weekly"  # 每周自动生成 override 复审报告
  }
}

rule "prod-drop-table" {
  predicate: change.action == "drop_table"
  action: REJECT
  override: HARD           # 不可覆盖，必须修改规则本身

  override_constraints: {
    require_approver: true # HARD 必须审批者批准
  }
}
```

**4. 跨表约束**

```
rule "fk-integrity" {
  scope: merge
  tables: {from: "orders", to: "customers"}
  predicate: synchronized(
    from.drop_columns ∩ to.primary_keys == ∅,
    "orders 表中被引用的 customer 外键列必须存在于 customers 主键中"
  )
  action: REJECT
}

rule "namespace-partition-consistency" {
  scope: merge
  namespace: "prod/*"
  predicate: all_tables_have_same("partitionSpec.fields")
  action: WARN
}
```

**5. 规则组合代数**

```
template "base-safety" {
  rules: [require-commit-meta]
}

template "prod-safety" extends "base-safety" {
  rules: [
    no-drop-column,      # 继承 base-safety 的 require-commit-meta
    no-overwrite,        # 追加 prod 特有规则
    pii-guard
  ]
}

template "prod-eu" extends "prod-safety" {
  rules: [
    no-cross-region-transfer   # 继承 prod-safety 的全部规则
  ]                             # 追加 EU 地区特有规则
  override: {
    pii-guard.override: HARD    # EU 地区将 PII 规则升级为 HARD
  }
}
```

### 8.5 规则引擎层：优先级分流与冲突解决

实现 G4。每条规则对命中的变更返回 `{action, severity, override}` 判定。当多条规则命中同一个变更时，按以下算法分流：

```
输入: SemanticDiff, RuleSet
输出: Violation[]

1. 对每个 change ∈ SemanticDiff.changes:
   a. 确定操作优先级（change 的内在属性）:
      P0: 存在性操作（drop_table, drop_namespace）   — 不可逆
      P1: 破坏性操作（drop_column, OverwriteFiles）   — 数据可能丢失
      P2: 结构变更（add_column, rename_column）       — 影响下游 schema
      P3: 元数据变更（modify_properties）              — 影响运维配置
      P4: 信息追加（AppendFiles, add_snapshot）       — 通常安全

   b. 收集命中该 change 的所有规则，取最严格的 severity:
      ERROR > WARN > INFO

   c. 如果存在 ERROR 判定:
      ├── 全部为 SOFT override → 返回 OVERRIDE，等待用户提供理由
      ├── 存在 HARD override → REJECT，生成 Violation
      └── 用户提供有效理由 → ALLOW_WITH_OVERRIDE（记录到 commit meta）

   d. 如果仅存在 WARN 判定 → WARN, 生成 Violation（不阻断）

2. 聚合，按 severity 降序 + 操作优先级降序排列

3. 存在任何 REJECT → 操作被阻断，返回全部 Violation
   存在 OVERRIDE → 操作被挂起，等待用户提供理由
   仅存在 WARN → 操作通过，但返回 WARN 列表供日志记录
```

关键设计：操作优先级是 change 的内在属性（由语义 diff 确定，不是规则赋予的），严重性是规则定义者赋予的判定强度，override 模式是规则的可配置安全阀——三者正交但合并决策。

### 8.6 语义信息层：ContentSemantics SPI

实现 G2。Nessie 已有 `ContentSemantics` SPI（负责解析 Iceberg `TableMetadata` 并产出结构化的 `SemanticDiff`）。约束系统不直接依赖 Iceberg 库——它消费 SPI 产出的 `SemanticDiff`，保持与存储格式解耦。

`SemanticDiff` 核心结构：

```
SemanticDiff {
  changes: [
    {
      field: "schema", action: "add_column",
      column: {name: "email", type: "STRING"},
      contentKey: "ns1.table1"
    },
    {
      field: "snapshots", operation: "AppendFiles",
      files: ["file-a1.parquet"],
      contentKey: "ns1.table1"
    }
  ]
}
```

需扩展：（1）在 `contentKey` 上关联 PII 分类（从外部元数据服务或 Nessie content 属性获取），使 `change.column.pii` 原语可用；（2）支持一次 `diff()` 调用产出多个 content key 的 `SemanticDiff`（为跨表约束提供输入）。

### 8.7 集成点：CAS 路径上的检查点

实现 G3。约束检查插入在三个位置，均在各层 CAS 重试循环之前：

```
Commit:  CommitLogicImpl.individualCommit()
          → 1. 计算 baseContent→targetContent 的 SemanticDiff
          → 2. ConstraintRuleEngine.evaluate(branch, semanticDiff)
          → 3. REJECT → 抛 ConstraintViolationException（不进 CAS）
          → 4. OVERRIDE → 检查是否带有效理由 → 是则放行，否则挂起
          → 5. WARN/ALLOW → 继续 CAS 逻辑

Merge:   MergeLogic.merge()
          → 1. 计算 sourceBranch→targetBranch 的 SemanticDiff
          → 2. ConstraintRuleEngine.evaluate(targetBranch, semanticDiff)
          → 3. REJECT → 抛 ConstraintViolationException
          → 4. OVERRIDE → 检查理由
          → 5. 通过 → 创建 merge commit

Transplant: 同 Merge
```

放在 CAS 之前的原因：CAS 涉及存储层原子写，代价较高。先做约束检查，失败直接返回，不浪费 CAS 尝试。

### 8.8 核心设计决策

**决策 1：规则 DSL — 在 CEL 之上封装规则模板。** CEL 已是 Nessie 依赖，但 CEL 不擅长表达结构化语义（它原生是字符串比较）。推荐在 CEL 之上封装规则模板（类似 Squawk 的"规则 ID → CEL 表达式"映射，见第 5.4 节）：常见场景提供预置规则 ID（`schema.no-drop-column`、`snapshot.no-overwrite`），内部映射为 CEL 表达式；高级场景允许写原始 CEL 直接引用 `SemanticDiff`。配合第 6 章的自然语言编译管道，用户可用自然语言描述约束，LLM 生成对应规则模板。

**决策 2：规则存储 — 双轨制。** 版本化模式：规则存储在 Nessie 仓库的 `_nessie_constraints/main` 上，规则变更即 commit（可 review、可 revert）。轻量模式：规则在 `application.properties` 中定义（兼容当前 CEL 模式）。关键区别：版本化模式支持免重启热加载。

**决策 3：阻断时机 — 三种 scope 可选。** `merge`（默认，最低开销）、`both`（commit + merge，PII 等敏感规则推荐）、`commit`（仅提交时，开发分支自检用）。

**决策 4：OVERRIDE 审计 — 强制记录。** 所有通过理由覆盖的操作，其理由文本强制写入 commit meta（`_nessie_constraint_override` 字段）。不可事后修改。每日/每周自动生成 override 复审报告，通知规则作者和平台管理员。

**决策 5：LLM 编译管道集成（G7）。** LLM 编译管道是独立的工具链组件，不嵌入规则引擎运行时。用户通过 `nessie constraint generate` CLI 或 CI 流水线触发 NL→规则编译，产物直接写入 `_nessie_constraints` 仓库。管道完全在离线阶段运行，对引擎运行时零影响。

### 8.9 规则热加载：配置即代码

实现 G6。从第 7.3 节的三种免重启模式中，选择"配置即代码"作为 Nessie 的适配路径：

```
规则变更流程（版本化模式）:

用户提交规则变更
    │
    ▼
Nessie commit 到 _nessie_constraints/main
    │
    ▼
ConstraintRuleEngine 下次 evaluate() 时:
  → 读取 _nessie_constraints/main 的最新规则
  → 重新编译 CEL 表达式
  → 缓存编译结果（LRU, TTL 60s）
    │
    ▼
新规则即时生效，无需重启
```

**设计要点**：规则每次 `evaluate()` 时读取，带 60s TTL 短期缓存（平衡延迟和性能）；如果 `_nessie_constraints/main` 不存在或无法读取，回退到最近有效缓存并记录 WARN；兼容轻量模式——如果 `application.properties` 中有规则，作为 fallback 基线。时效性规则的 TTL 过期自动从活跃规则集中移除。

---

## 9. 投入产出与实施路径

### 9.1 价值主张

**1. 真实的产业痛点。** lakeFS 需要 webhook 服务、Bauplan 内嵌 expectations、Databricks 需要 Unity Catalog 的 schema enforcement——每个数据湖平台都在造自己的"约束"轮子，但都是碎片化的。lakeFS 约束文件格式、Bauplan 约束数据质量、Unity Catalog 约束 schema 结构。"受保护的分支 + 声明式语义规则 + 可组合原语 + 五个创新扩展 + LLM 编译管道 + 自动阻断"这一组合在数据湖领域是明确缺失的。

**2. Nessie 的独特定位。** Nessie 是唯一同时处于 Iceberg catalog 层和版本控制层的系统——既通过 ContentSemantics SPI 理解表语义，又在 CAS 路径上控制合并流程。lakeFS 在文件层看不到 Iceberg 语义，SQL lint 工具不懂版本控制。这一交叉定位使 Nessie 能以最低成本填补第 4 章识别的六个空白。

**3. 已有基础设施。** ContentSemantics SPI 已在解析 Iceberg 语义变更 [21]，CEL 规则引擎已在生产验证。Upwind 已证明 NL→Rego 生产可用（第 6 章）。新系统的核心工作是组合和扩展已有组件，而非从零建造。

### 9.2 实施阶段与工作量估算

| 阶段 | 内容 | 估时 | 依赖 |
|------|------|:---:|------|
| **Phase 1: 语义引擎** | 扩展 ContentSemantics SPI 产出标准化 `SemanticDiff`；定义原语类型系统；多 content key diff 支持 | 2-3 周 | — |
| **Phase 2: 规则引擎** | 实现 `ConstraintRuleEngine`；封装 CEL 规则模板（预置 10-15 条）；优先级 P0-P4 × ERROR/WARN/INFO × HARD/SOFT 三元判定；在 commit/merge 路径插入检查点 | 3-4 周 | Phase 1 |
| **Phase 3: 规则存储 + 创新扩展** | 规则版本化存储（`_nessie_constraints`）；缓存 + TTL 热加载；时效性规则（时间窗口 + 阶段铺开）；仿真 CLI（`nessie constraint simulate`） | 3-4 周 | Phase 2 |
| **Phase 4: LLM 编译 + 测试文档** | 集成 LLM 编译管道（`nessie constraint generate` CLI，prompt 模板含语义原语 schema）；跨表约束 POC；单元/集成测试；性能基准；用户文档 | 2-3 周 | Phase 2 |

**总估时**：10-14 周（单人全职）或 6-8 周（两人并行）。

创新扩展按优先级分期实现：

| 优先级 | 创新扩展 | 实现阶段 | 理由 |
|:---:|---------|---------|------|
| P0 | 优先级 + 三元判定 | Phase 2 | 引擎核心，不可分割 |
| P0 | 理由覆盖 | Phase 2 | 与三元判定同时实现 |
| P1 | 时效性规则 | Phase 3 | 依赖规则存储 + TTL |
| P1 | 规则仿真 | Phase 3 | 独立 CLI 工具，不阻塞核心引擎 |
| P1 | LLM 编译管道 | Phase 4 | 独立工具链，可并行开发 |
| P2 | 规则组合代数 | Phase 3+ | 语法糖 + 模板系统，可在 DSL 稳定后追加 |
| P2 | 跨表约束 | Phase 4 (POC) | 需要多 content key SemanticDiff，先做概念验证 |

### 9.3 风险与缓解

| 风险 | 缓解 |
|------|------|
| **规则膨胀**（语义约束空间 ~30+ 条规则） | Phase 2 预置 10-15 条核心规则覆盖 80% 场景；每条规则评估开销在 μs 级，可基准验证 |
| **SemanticDiff API 稳定性**（用户自定义规则依赖它） | Phase 2 优先提供预置规则 ID 而非暴露原始 `SemanticDiff`；原始 CEL 标记为 advanced/experimental |
| **性能影响**（merge 路径增加检查延迟） | 语义检查开销预估 1-5ms；设置超时上限（500ms），超时降级为 WARN 而非 REJECT |
| **规则缓存一致性**（TTL 意味着最多 60s 延迟） | 可接受（OPA Bundle 默认也是 60s）；紧急变更提供手动 flush-cache 命令 |
| **OVERRIDE 滥用**（频繁使用理由覆盖绕过约束） | 频次限制 + 自动复审报告 + 异常告警；HARD 规则不可覆盖 |
| **LLM 生成质量**（自然语言→规则可能产生非预期结果） | 编译产物强制经过仿真模式（`--dry-run`）验证，人工确认后生效 |
| **合入范围过大**（试图做通用数据质量平台） | ASE 2024 [22] 证明窄范围工具的静默错误率远低于通用工具（3% vs 50%）；Phase 1-2 只约束 Iceberg 表结构/数据变更操作 |

---

## 10. 参考文献

1. **GitHub Rulesets Documentation.** https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets

2. **Open Policy Agent Documentation.** https://www.openpolicyagent.org/docs/latest/

3. **OPA Performance Benchmarks.** https://www.openpolicyagent.org/docs/latest/policy-performance/

4. **OPA Bundle Documentation.** https://www.openpolicyagent.org/docs/latest/management-bundles/

5. **lakeFS Hooks Documentation.** https://docs.lakefs.io/latest/howto/hooks/webhooks/

6. **Implementing lakeFS Hooks for Write-Audit-Publish for Data.** https://www.lakefs.io/blog/lakefs-hooks/ — lakeFS blog, 2025.02

7. **treeverse/lakeFS-hooks — Reference Webhook Implementation.** https://github.com/treeverse/lakeFS-hooks

8. **lakeFS Issue #8615 — Revert does not trigger hooks.** https://github.com/treeverse/lakeFS/issues/8615

9. **Squawk — PostgreSQL SQL Linter.** https://github.com/sbdchd/squawk

10. **pgvet — PostgreSQL Migration Linter.** https://github.com/quickwi/pgvet

11. **Atlas — Database DevOps Platform.** https://atlasgo.io/

12. **Upwind — LLM-based Rego Support.** https://www.upwind.io/

13. **Watchflow — NL to GitHub Rulesets.** https://watchflow.ai/

14. **OPA Generator — MCP Agent + GPT-4o for Rego.** https://github.com/open-policy-agent/opa-generator

15. **ARPaCCino — Agentic-RAG for Rego Generation (ADBIS 2025).** https://link.springer.com/chapter/10.1007/978-3-031-82207-0_19

16. **P2T — Legal Documents to Executable Rules (AAAI-26).** https://arxiv.org/abs/2506.08663

17. **Bauplan Documentation — Data Quality with Expectations.** https://www.bauplanlabs.com/docs/expectations

18. **Bauplan — Formal Verification of Branch Semantics with Alloy.** https://www.bauplanlabs.com/blog/formal-verification/

19. **Nessie CEL Authorization Documentation.** https://projectnessie.org/docs/nessie-latest/security/authorization/

20. **NessieAuthorizationTestProfile — CEL Rule Examples.** `servers/quarkus-server/src/testFixtures/java/org/projectnessie/server/authz/NessieAuthorizationTestProfile.java`

21. **Nessie Semantic Awareness Design.** `nessie-semantic-awareness-innovation.md`

22. **ASE 2024 — Narrow vs. General-Purpose Static Analysis Tools.** Proceedings of the 39th IEEE/ACM International Conference on Automated Software Engineering

---

> **文档版本**：v4.0 / 2026-05-25
>
> **与 v3.x 的核心变化**：
> - **章节重组**：将差距分析（原 v3 第 7 章）移至技术现状（第 3 章）之后，成为第 4 章。阅读流从"现状→规则→LLM→对比→空白"改为"现状→空白→规则→LLM→对比"——说清现状后立即指出差距
> - **新增语义模型对比**（7.4 节）：逐一分析六类技术的语义模型（感知世界、核心实体、可观测操作、可检查属性、关系感知、语义盲区），含横向对比总表和语义模型厚度光谱
> - **揭示规则差异根源**：语义模型厚度 → 原语集丰富度 → 规则表达力 → 约束深度的因果关系链
>
> v3.x 系列（v3.0–v3.4）的变更历史见 `nessie-branch-constraint-landscape-v3.md`。
