# Nessie 语义感知：Git-like 数据版本控制的技术创新

> 从理论基础到完整技术方案，系统阐述如何让 Nessie 从"管理字节"升级到"理解语义"。
>
> 文档版本：v3.1 / 2026-05-19

---

## 目录

- [1. 引言：Git-for-Data 的十字路口](#1-引言git-for-data-的十字路口)
- [2. 什么是语义感知](#2-什么是语义感知)
- [3. 理论基础](#3-理论基础)
  - [3.1 地址空间理论：什么时候可以安全合并](#31-地址空间理论什么时候可以安全合并)
  - [3.2 操作差分模型：版本控制的另一种范式](#32-操作差分模型版本控制的另一种范式)
    - [完整的 Iceberg 操作分类](#完整的-iceberg-操作分类)
    - [Snapshot 操作类型的合并语义差异](#snapshot-操作类型的合并语义差异)
    - [操作优先级分类（P0–P4）](#操作优先级分类p0p4)
  - [3.3 Patch 理论：合并总是成功的数学保证](#33-patch-理论合并总是成功的数学保证)
  - [3.4 形式化验证：用穷举代替直觉](#34-形式化验证用穷举代替直觉)
  - [3.5 实证研究：简单即正确](#35-实证研究简单即正确)
  - [3.6 理论基础小结](#36-理论基础小结)
- [4. 核心创新：地址空间细化](#4-核心创新地址空间细化)
- [5. 语义冲突检测：完整场景分析](#5-语义冲突检测完整场景分析)
  - [5.1 场景全景矩阵](#51-场景全景矩阵)
  - [5.2 典型场景详解](#52-典型场景详解)
  - [5.3 冲突判定流程](#53-冲突判定流程)
- [6. 语义合并：层次化策略](#6-语义合并层次化策略)
- [7. 解锁的其他能力](#7-解锁的其他能力)
  - [7.1 语义 Diff](#71-语义-diff)
  - [7.2 语义查询](#72-语义查询)
  - [7.3 语义校验](#73-语义校验)
  - [7.4 语义 Lineage](#74-语义-lineage)
  - [7.5 语义 GC](#75-语义-gc)
- [8. 架构演进：加能力不加耦合](#8-架构演进加能力不加耦合)
- [9. 实施路线图](#9-实施路线图)
- [10. 参考文献](#10-参考文献)

---

## 1. 引言：Git-for-Data 的十字路口

### 现状

Project Nessie 为 Apache Iceberg 数据湖提供了 Git-like 的版本控制能力——分支（branch）、提交（commit）、标签（tag）、合并（merge）。用户可以在自己的分支上修改表结构、写入数据，验证通过后合并到主分支。

目前 Nessie 的社区实践已经达到相当规模：
- Bauplan 在生产中管理**数百万个分支**，是已知最大的 Git-for-Data 部署
- AI agent 使用 Nessie 分支作为**安全沙箱**，在隔离环境中操作数据
- Nessie 的 Git-like 能力正被贡献到 Apache Polaris（incubating），推动社区统一

### 瓶颈

但 Nessie 当前有一个根本局限：**它把内容视为不透明字节序列**。一张 Iceberg 表的 schema、properties、partition spec 等具有明确语义的子结构，在 Nessie 存储层看来就是一个不可分割的 blob。

这导致了一个看似技术实则哲学的问题：

> 分支 A 修改了表 `customer_events` 的 schema（新增一列 `email`），分支 B 修改了同一张表的 properties（改了 `owner` 属性值）。Nessie 判定这两个修改**冲突**。

但实际上这两个修改是**正交的**——它们修改的是表的不同子结构，可以安全合并。Nessie 之所以判定冲突，不是因为它"看到了真正的冲突"，而是因为它**根本看不清**——它只知道 blob 变了，不知道变了什么。

---

## 2. 什么是语义感知

### 定义

> **语义感知**：版本控制系统理解它所管理内容的**内部结构**、**变更意图**和**语义约束**，并能在版本控制操作（diff、merge、query、validate）中利用这些信息，做出比"字节相等"更高层次的判断。

### 三维模型

语义感知不是一个"有"或"没有"的开关，而是一个三维频谱：

```
                    盲 (Blind)                    感知 (Aware)
                    ──────────                    ──────────────

维度一：结构感知      不透明字节序列                 知道内容由哪些子结构组成、
                    所有操作在 blob 级别            子结构可独立寻址

维度二：意图感知      只知道最终状态变了              知道"做了什么操作"——
                    从状态差异推断变更              加列、改类型、删属性、重命名

维度三：约束感知      不知道变更是否合法              知道 schema 兼容性规则、
                    接受一切 commit                在非法变更时拒绝
```

**图解——从盲到感知的三个层次：**

```
┌─────────────────────────────────────────────────────────────────┐
│                        盲 (当前 Nessie)                          │
│                                                                  │
│   ContentValueObj                                                │
│   ┌──────────────────────────────────────────┐                   │
│   │  B3 8A 1F 6C ... (不透明字节序列)           │  ← 只有哈希    │
│   └──────────────────────────────────────────┘                   │
│                                                                  │
│   操作能力：知道"这个 blob 变了"，不知道"哪里变了"、"怎么变的"      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      结构感知 (Level 1)                           │
│                                                                  │
│   ContentValueObj                                                │
│   ┌──────────────────────────────────────────────────────────┐   │
│   │  schema:        [user_id INT, name STRING, email STRING]  │   │
│   │  partitionSpec: [month(user_date)]                       │   │
│   │  properties:    {owner: "team-a", retention: "30d"}     │   │
│   │  snapshots:     [snap-1, snap-2, snap-3]                 │   │
│   └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│   操作能力：知道每个子结构是否被修改，修改了什么                      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      意图感知 (Level 2)                           │
│                                                                  │
│   Commit 变更记录                                                 │
│   ┌──────────────────────────────────────────────────────────┐   │
│   │  + addColumn("email", STRING, nullable=true)              │   │
│   │  ~ updateProperty("owner", "team-a" → "team-b")           │   │
│   │  ~ widenColumn("user_id", INT → BIGINT)                   │   │
│   │  + cherrypickSnapshot("snap-3")                           │   │
│   └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│   操作能力：知道"做了什么"，可以区分"加列再删列"和"重命名列"        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      约束感知 (Level 3)                           │
│                                                                  │
│   校验规则                                                       │
│   ┌──────────────────────────────────────────────────────────┐   │
│   │  ✓ 新增 nullable 列：向后兼容，可通过                        │   │
│   │  ✓ INT → BIGINT：类型拓宽，向后兼容，可通过                   │   │
│   │  ✗ 删除列：向后不兼容，需人工确认                             │   │
│   │  ✗ STRING → INT：类型不兼容，拒绝                            │   │
│   │  ⚠ user_id 列被标记为 PII，新建表引用此列需加密分区           │   │
│   └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│   操作能力：不仅知道"变了什么"，还知道"这个变更合不合法"            │
└─────────────────────────────────────────────────────────────────┘
```

### 每一层解锁的能力

| | 结构感知 | 意图感知 | 约束感知 |
|---|:---:|:---:|:---:|
| **Diff** | 字段级差异 | 操作级差异 | 兼容性评估 |
| **Conflict Detection** | 子结构级冲突 | 操作级冲突 | 约束违反检测 |
| **Merge** | 字段级自动合并 | 操作转换合并 | 合并结果校验 |
| **Query** | 按子结构过滤 | 按操作类型过滤 | 按合规性过滤 |
| **Lineage** | 子结构级追溯 | 操作链追溯 | 影响分析 |
| **GC** | 子结构级回收 | — | 完整性验证 |
| **Validation** | 结构合法性 | — | 所有约束检查 |

---

## 3. 理论基础

语义感知不是凭空想象的概念。它有五个稳固的理论基座，每一个都来自独立的学术研究脉络。

### 3.1 地址空间理论：什么时候可以安全合并

#### 来源

Swierstra 和 Löh 在 *The Semantics of Version Control*（Onward! 2014）中，用**分离逻辑（Separation Logic）**为版本控制建立了形式化基础。

#### 核心思想

把版本控制的仓库看作一个**地址空间**——每个地址存一个值。一次修改就是一个 patch（补丁），它读取某些地址（前置条件），修改某些地址（修改域）。

用 Hoare 三元组表示：

```
{P}  c  {Q}

P = 前置条件（patch 执行前，仓库必须满足的状态）
c = 操作命令（具体的修改）
Q = 后置条件（patch 执行后的状态）
```

每一个 patch 有两个关键属性：

| 符号 | 含义 | 通俗理解 |
|------|------|---------|
| `mod(c)` | patch c 的**修改域** | "我改了哪些地址" |
| `addr(P)` | 断言 P 的**地址域** | "我的前置条件关心哪些地址的值" |

#### 独立 Patch 定理

两个 patch `c1` 和 `c2` 是独立的（可以任意顺序安全合并），当且仅当：

```
mod(c1) ∩ addr(P2 ∧ Q2) = ∅  且  mod(c2) ∩ addr(P1 ∧ Q1) = ∅
```

翻译：**c1 写的东西 c2 不在乎，c2 写的东西 c1 不在乎。**

#### 在 Nessie 中的应用

```
当前 Nessie（盲）：
  地址空间：每个 StoreKey 是一个原子地址
  修改域：StoreKey("customer_events") ← 整张表

  Branch A: mod(A) = {StoreKey("customer_events")}
  Branch B: mod(B) = {StoreKey("customer_events")}
  mod(A) ∩ mod(B) ≠ ∅ → 冲突！

语义感知 Nessie：
  地址空间：StoreKey 内部有子地址
  修改域：{schema}、{properties}、{partition}、{snapshots}

  Branch A: mod(A) = {schema}        ← 只加了一列
  Branch B: mod(B) = {properties}     ← 只改了一个属性
  mod(A) ∩ mod(B) = ∅ → 独立！可以安全合并
```

**地址空间理论回答的是"什么叫冲突"这个最根本的问题。** 它告诉我们：冲突不是"双方都碰了同一个东西"，而是"双方碰的地址空间有重叠"。

---

### 3.2 操作差分模型：版本控制的另一种范式

#### 背景：两种版本控制范式

所有版本控制系统，不管版本的是代码还是数据，底层只有两种范式：

```
状态快照模型（State-based）                   操作差分模型（Operation-based）
──────────────────────────                   ──────────────────────────────

每次 commit 存储完整的最终状态。               每次 commit 存储操作序列。
                                              状态由操作序列推导。

    Commit 1: [a, b]                              Commit 1: +a, +b
    Commit 2: [a, b, c]                           Commit 2: +c
    Commit 3: [a, b, d]                           Commit 3: -c, +d

代表：Git, Nessie, lakeFS                      代表：Darcs, Pijul, Baseline
```

```
状态快照模型的工作方式：

    Commit 1           Commit 2           Commit 3
    ┌─────────┐       ┌─────────┐       ┌─────────┐
    │ 完整状态 │  ──►  │ 完整状态 │  ──►  │ 完整状态 │
    │ [a, b]  │       │ [a,b,c] │       │ [a,b,d] │
    └─────────┘       └─────────┘       └─────────┘

    Diff: 比较两个快照 → [b→c→d]，但不知道是"删了 c 加了 d"
          还是"把 c 改成了 d"


操作差分模型的工作方式：

    Commit 1           Commit 2           Commit 3
    ┌─────────┐       ┌─────────┐       ┌─────────┐
    │ +a      │  ──►  │ +c      │  ──►  │ -c      │
    │ +b      │       │         │       │ +d      │
    └─────────┘       └─────────┘       └─────────┘

    Diff: 回溯操作序列 → "先加了 a,b，然后加了 c，然后删了 c 加了 d"
          意图清晰，不可混淆
```

**关键区别**：状态是从操作推导的（操作 → 状态，唯一），但操作不能从状态唯一推导（状态 → 操作，存在歧义）。

例如，状态从 `[a, b]` 变成 `[a, x, c]`：
- 可能 1：删除 `b`，新增 `x`，新增 `c`
- 可能 2：重命名 `b` 为 `x`，新增 `c`
- 可能 3：修改 `b` 的类型为与 `x` 相同，新增 `c`

这三种可能对合并的影响完全不同，但状态模型**丢失了意图信息**，无法区分。

#### Baseline 的操作差分模型

Edwards 和 Petricek 在 *Baseline: Operation-Based Evolution and Versioning of Data*（arXiv:2512.09762, 2025.12）中，首次将操作差分模型系统性地引入数据版本控制领域。

**Baseline 模型的核心创新**：

```
传统状态快照（如 Nessie）：           Baseline 操作差分：

存储内容                           存储操作
┌──────────────────────┐            ┌──────────────────────┐
│ TableMetadata {       │            │ addColumn("email")    │
│   schema: [id, name,  │            │ renameColumn(         │
│            email],    │            │   "user_name","name") │
│   properties: {...}   │            │ widenColumn(         │
│ }                     │            │   "id", INT→BIGINT)  │
└──────────────────────┘            └──────────────────────┘

冲突检测：比较最终状态 blobs      冲突检测：操作转换（OT）
                                     → 两个 addColumn 不同列 → 可交换
                                     → 两个 renameColumn 同列 → 需确认
                                     → addColumn + widenColumn → 无冲突

合并：三向状态比较                   合并：操作序列拼接 + 转换
  需要读取 base, source, target       操作天然记录了变更意图
  三个完整状态                         不需要 base
```

**操作差分在 Nessie 中的潜在位置**：

Nessie 架构中已有的 `CommitOp`（`Put`/`Delete`）可以扩展为语义操作：

```
当前 CommitOp                    扩展后的语义 CommitOp
─────────────────               ─────────────────────────
Put(ContentKey, ContentValue)    AddColumn(ContentKey, columnDef)
Delete(ContentKey)               DropColumn(ContentKey, columnName)
                                 RenameColumn(ContentKey, old, new)
                                 WidenColumn(ContentKey, name, from, to)
                                 UpdateProperty(ContentKey, key, from, to)
                                 CherrypickSnapshot(ContentKey, snapshotId)
```

#### 完整的 Iceberg 操作分类

上述 `CommitOp` 扩展只覆盖了 Schema 级别的操作。完整的 Iceberg 表操作包括以下层次：

**表级操作（Table-Level Operations）**——改变表的存在性：

| 操作 | 含义 | 合并语义 | 冲突风险 |
|------|------|---------|---------|
| `CreateTable` | 新建表 | 双方都创建同名表 → 冲突 | 高 |
| `DropTable` | 删除表 | 一方删除，一方修改 → 冲突 | 高 |
| `RenameTable` | 重命名表 | 双方重命名为不同名 → 冲突 | 高 |
| `ReplaceTable` | 替换表（全量覆盖） | 与任何修改冲突 | 最高 |
| `RegisterTable` | 注册已有数据目录为表 | 类似 CreateTable | 中 |

**Schema 级操作**——改变表结构定义：

| 操作 | 含义 | 合并粒度 |
|------|------|---------|
| `AddColumn` | 新增列 | 列级（按列 ID/名称） |
| `DropColumn` | 删除列 | 列级 |
| `RenameColumn` | 重命名列 | 列级（Iceberg 列 ID 不变） |
| `UpdateColumn` | 修改列类型/注释/可空性 | 列级 |
| `ReorderColumns` | 重排列顺序 | Schema 级 |

**Properties 级操作**——改变表属性：

| 操作 | 含义 | 合并粒度 |
|------|------|---------|
| `SetProperty` | 设置/更新单个属性 | Key 级 |
| `RemoveProperty` | 删除属性 | Key 级 |
| `SetProperties` | 批量设置属性 | 需要展开为 key 级 |

**PartitionSpec 级操作**——改变分区策略：

| 操作 | 含义 | 合并粒度 |
|------|------|---------|
| `AddPartitionField` | 新增分区字段 | 字段级 |
| `RemovePartitionField` | 删除分区字段 | 字段级 |
| `ReplacePartitionSpec` | 完全替换分区策略 | Spec 级（高风险） |

**SortOrder 级操作**——改变排序策略：

| 操作 | 含义 | 合并粒度 |
|------|------|---------|
| `AddSortField` | 新增排序字段 | 字段级 |
| `ReplaceSortOrder` | 完全替换排序策略 | Order 级 |

**Snapshot 产生操作**——写入数据，产生新的 Snapshot：

| 操作 | 底层语义 | Iceberg 引擎操作 |
|------|---------|-----------------|
| `AppendFiles` | INSERT — 追加数据文件 | `AppendFiles` / `MERGE ... INSERT` |
| `OverwriteFiles` | OVERWRITE — 覆盖数据文件 | `OverwriteFiles` / `INSERT OVERWRITE` |
| `DeleteFiles` | DELETE — 删除数据文件 | `DeleteFiles` / `MERGE ... DELETE` |
| `RowDelta` | MERGE/UPSERT — 行级增删改 | `RowDelta` / `MERGE` |
| `RewriteFiles` | COMPACTION — 合并小文件 | `RewriteFiles` / `OPTIMIZE` |
| `ReplacePartitions` | PARTITION_OVERWRITE — 替换整个分区 | `ReplacePartitions` |
| `AddDeleteFiles` | DELETE — 添加 position/equality delete | `AddDeleteFiles` / `DELETE FROM` |

**Snapshot 生命周期操作**——管理已存在的 Snapshot：

| 操作 | 含义 | 是否产生数据变更 |
|------|------|:---:|
| `ExpireSnapshots` | 清理过期快照 | 否（回收） |
| `RollbackToSnapshot` | 将当前表回滚到历史快照 | 否（指针移动） |
| `CherrypickSnapshot` | 从其他分支搬迁快照 | 否（引用复制） |
| `SetSnapshotRef` | 设置快照引用（branch/tag） | 否 |
| `SetCurrentSnapshot` | 切换当前快照 | 否 |

#### Snapshot 操作类型的合并语义差异

这是整个操作差分模型中**最关键但最容易被忽略**的部分。不同类型的 Snapshot 产生操作，其合并语义完全不同：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Snapshot 操作类型的合并行为对比                               │
│                                                                              │
│  操作类型          │  可交换？  │  丢失风险？ │  合并策略          │  风险     │
│  ─────────────────┼──────────┼───────────┼──────────────────┼───────── │
│  AppendFiles       │  ✓ 是     │  无         │  快照序列拼接       │  低       │
│  RewriteFiles      │  ✓ 是     │  无         │  应视为透明/忽略    │  低       │
│  ExpireSnapshots   │  ✓ 是     │  无         │  取并集            │  低       │
│  DeleteFiles       │  ⚠ 有条件  │  中         │  需检查文件重叠      │  中       │
│  RowDelta          │  ⚠ 有条件  │  中         │  需检查行级冲突      │  中       │
│  AddDeleteFiles    │  ⚠ 有条件  │  中         │  同 DeleteFiles    │  中       │
│  OverwriteFiles    │  ✗ 否     │  高         │  需人工确认         │  高       │
│  ReplacePartitions │  ✗ 否     │  高         │  需人工确认         │  高       │
│  RollbackToSnapshot│  ✗ 否     │  最高       │  需人工确认         │  最高     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

**关键洞察——为什么 RewriteFiles 应被视为透明操作**：

```
Compaction（RewriteFiles）的本质：
  
  修改前：file-A.parquet (rows 1-10) + file-B.parquet (rows 11-20)
  修改后：file-C.parquet (rows 1-20)  ← 数据完全不变，只是文件合并了
  
  如果 Nessie 检测到 RewriteFiles：
  ├── 表状态实际没有变化（相同的数据，不同的文件组织形式）
  ├── 在合并判定中应视为 "无实质性变更"
  └── 不应阻止其他分支的合并
```

**AppendFiles 与 OverwriteFiles 的本质区别**：

```
场景：Branch A 和 Branch B 都向同一分区写入了数据

Branch A: AppendFiles → 写入 file-a1.parquet
Branch B: AppendFiles → 写入 file-b1.parquet

合并结果：snapshots 包含 {file-a1, file-b1}  ← 追加操作可交换 ✓


Branch A: OverwriteFiles → 用 file-a1.parquet 替换分区内容
Branch B: AppendFiles   → 写入 file-b1.parquet

合并结果：如果自动合并，OverwriteFiles 会覆盖 AppendFiles 的结果
         ← B 的数据丢失！必须人工确认 ✗
```

**RowDelta 的条件可交换性**：

```
RowDelta 的语义是行级 UPSERT/MERGE（根据主键）。两个 RowDelta 的合并取决于：

  双方修改的行 ID 集合是否重叠？
  ├── 不重叠（操作不同分区的数据）→ ✓ 可交换
  └── 重叠（同一行被双方修改）  → ✗ 需要冲突检测
  
  但 Nessie 在 catalog 层不访问数据文件内容，无法判断行级重叠。
  因此 RowDelta × RowDelta 默认策略为 ⚠ 有条件冲突，
  需检查修改的 partition/file 范围。
```

#### 操作优先级分类（P0–P4）

不同操作对数据安全和一致性的影响不同，在合并决策中应有不同的优先级：

```
P0 — 存在性操作（最高优先级，不可覆盖）
  ├── CreateTable: 表的存在是其他所有操作的前提
  ├── DropTable: 删除表意味着所有子结构消失
  ├── RenameTable: 影响所有下游引用
  └── ReplaceTable: 完全替换表身份
  
  合并策略：涉及 P0 操作的冲突永远需要人工确认


P1 — 破坏性操作（高优先级，通常不可自动合并）
  ├── OverwriteFiles: 覆盖数据，可能丢失其他分支的数据
  ├── RollbackToSnapshot: 回退到历史状态，丢弃中间所有变更
  └── ReplacePartitions: 替换整个分区的数据
  
  合并策略：默认拒绝自动合并，需人工确认


P2 — 结构性替换操作（中优先级，谨慎处理）
  ├── ReplacePartitionSpec: 分区策略变更，影响物理布局
  └── ReplaceSortOrder: 排序策略变更
  ├── ReorderColumns: 列顺序变更（Iceberg 中列 ID 不变则影响较小）
  
  合并策略：仅当另一方未修改同一子结构时可自动合并


P3 — 增量操作（低优先级，可自动合并）
  ├── AppendFiles: 追加数据，天然可交换
  ├── AddColumn: 新增列，不破坏现有数据
  ├── SetProperty: 设置属性
  ├── AddPartitionField: 新增分区字段
  └── AddSortField: 新增排序字段
  
  合并策略：在地址空间不重叠的情况下自动合并


P4 — 透明/清理操作（最低优先级，可被覆盖）
  ├── RewriteFiles: 文件重组织，数据不变
  ├── ExpireSnapshots: 清理过期快照
  └── CherrypickSnapshot: 快照引用复制
  
  合并策略：总是可自动合并，冲突时优先保留其他分支的修改
```

**优先级在合并决策中的应用**：

```
当两边的操作优先级不同时，按以下规则判定：

  1. 低优先级操作遇到高优先级操作 → 高优先级操作胜出，低优先级自动让步
     例：Branch A 做 RewriteFiles (P4), Branch B 做 AppendFiles (P3)
          → 合并结果取 Branch B，RewriteFiles 的安全窗口已过期，需要在合并后重新执行
  
  2. 同优先级操作 → 按现有子结构级别冲突检测规则判断
     例：双方都是 AppendFiles (P3) → 不重叠则独立 ✓
  
  3. 存在性操作 (P0) 与任何操作冲突 → 永远人工确认，永不自动合并
     例：Branch A 做 DropTable, Branch B 做 AddColumn → ✗ 强制人工确认
```

---

### 3.3 Patch 理论：合并总是成功的数学保证

#### 来源

Pijul（由 Pierre-Étienne Meunier 创建的 patch-based 版本控制系统）和 Grove（POPL 2025 的结构编辑器演算）共同推进了一个数学思想：**如果版本历史的底层表示是交换幺半群（commutative monoid），那么合并总是安全的。**

#### Pijul 的贡献：Graggle 结构

Pijul 用类别论（Category Theory）定义了版本控制的核心数据结 "Graggle"：

```
patch: Graggle → Graggle     ← 一个 patch 将一个 graggle 变成另一个 graggle
merge: Graggle × Graggle → Graggle  ← merge 是两个 graggle 的二元运算，是 total function

关键性质——结合律：
merge(g1, merge(g2, g3)) = merge(merge(g1, g2), g3)
```

结合律的含义是：**合并的顺序不影响最终结果**。三人同时工作，无论谁先合并谁，最终状态一致。这在 Git 中是不成立的——Git 的 merge 顺序可能影响结果。

#### Grove 的贡献：结构编辑器的 CmRDT（POPL 2025）

Grove 将这个思想推向极致：在结构编辑器中，**所有编辑操作都是可交换的**（commutative），仓库天然就是一个 **CmRDT**（Commutative Replicated Data Type）——不需要 diff 算法，不需要三向合并，操作天生不冲突。

```
传统 Git 流程：                       Grove 流程：

用户编辑                             用户编辑
   │                                    │
   ▼                                    ▼
保存文件                             操作直接提交为 patch
   │                                    │
   ▼                                    ▼
git add / git commit                patch 自动可交换
   │                                    │
   ▼                                    ▼
git diff (猜测发生了什么)            不需要 diff
   │                                    │
   ▼                                    ▼
git merge (三向合并)                不需要 merge（永远不会冲突）
   │
   ▼
可能冲突，需要手动解决
```

#### 对 Nessie 的启示

Nessie 不需要成为 Pijul 或 CmRDT，但可以**吸收操作可交换的思想**：将当前 `CommitOp` 的 `Put` 操作拆解为更细粒度的语义操作，当两个分支上的操作修改的是不同子结构时，它们天然可交换——不需要三向比较。

---

### 3.4 形式化验证：用穷举代替直觉

#### 来源

Bauplan 团队（Ciro Greco & Jacopo Tagliabue）在 2025 年 7 月发表了 *Git-for-Data Semantics: Safe Branching & Merging at Scale*，首次用 **Alloy**（轻量级模型检验器）为 Git-for-Data 建立了形式化模型。

#### Alloy 是什么

Alloy 是一个**穷举式验证工具**。它和单元测试的根本区别：

```
单元测试：
  "我用 3 个例子测了，都过了"  → 3/∞ 路径已验证

Alloy：
  "我在限定范围内穷举了所有可能的状态组合，
   每一个组合都满足我的约束"     → 100% 限定范围已验证
```

#### Bauplan 的 Alloy 模型发现了什么

Bauplan 定义了一个简化版的 Git-for-Data 模型，包含 Branch、Commit、Table、Snapshot 四个实体，以及 create 和 merge 两个操作。Alloy 在穷举所有可能的状态序列后发现：

**发现 1：单人和多人需要不同的 merge 策略**

```
单人场景：
  main → apo（一个分支，改了一张表）
  merge 时直接把 main 指针移到 apo（fast-forward）→ 正确

多人场景：
  main → apo（改了 table_x）
       → big（改了 table_y）
  如果 merge main←apo 时用 fast-forward → big 的改动可能丢失
  必须创建新的 merge commit → 同时记录两边的变更
```

**发现 2：冲突必须是语义概念**

Alloy 模型中的冲突定义为 `diff[p, b.commit] & diff[p, c]` ——两个分支从共同祖先以来修改的**表集合**是否有交集。他们自己承认这个粒度仍然是"表级"的：

> 当前我们比较的是完整的 `Snapshot`（不透明快照）。如果细化为 schema 和 data 分别比较，可以得到更精确的结果。

这正是语义感知要做的——把 Alloy 模型中的粒度从"表"细化到"子结构"。

#### 定义关键不变量

形式化建模的真正价值是定义和验证**系统不变量**——无论操作序列如何，这些性质永远成立：

| 不变量 | 含义 | 在 Nessie 中的对应 |
|--------|------|-------------------|
| Branch 一致性 | 分支指针始终指向一个有效 commit | Reference 的 integrity |
| Commit 可达性 | 任何 commit 可以通过 parent 链追溯到初始 commit | CommitObj DAG 的连通性 |
| Merge 正确性 | Merge 后的 commit 包含了所有源分支的变更 | 合并后索引的完整性 |
| 冲突安全 | 不会静默丢失任何一方的变更 | 冲突检测不遗漏 |

---

### 3.5 实证研究：简单即正确

#### 来源

Schesch 等人在 ASE 2024（IEEE/ACM 自动化软件工程会议）上发表了对 **6045 个真实 Java 合并场景**的大规模实证研究。

#### 关键数据

| 工具 | 正确率 | 未处理率（报冲突让人类解决） | 错误率（静默失败） |
|------|--------|---------------------------|-------------------|
| Git Merge (默认) | 46% | 51% | **3%** |
| Spork (AST 全结构化) | 54% | 35% | **11%** |
| IntelliMerge (图合并) | 24% | 26% | **50%** |
| **Imports** (只处理 import 语句) | **49%** | 49% | **3%** |

#### 三条工程原则

**原则 1：错误合并比冲突更危险。**
IntelliMerge 虽然"解决"了 74% 的场景（24% 正确 + 50% 错误），但其中一半是**静默错误的**——合并结果编译通过了，逻辑却是错的。在数据场景中，一个静默错误的 schema 合并可能导致下游管道崩溃。**不确定时，宁可报冲突让人类判断。**

**原则 2：简单、有针对性的工具胜过复杂的通用工具。**
"Imports"工具只处理 Java import 语句的合并——一个极窄的场景——但正确率（49%）接近全 AST 合并的 Spork（54%），且错误率（3%）远低于 Spork（11%）。**不要试图一次性解决所有合并问题。为每种子结构设计专用规则，叠加起来效果最好。**

**原则 3：上下文决定合并正确性。**
同一段冲突代码，出现在文件的不同位置，合并策略可能不同。对 Nessie 这意味着——**schema 和 properties 的合并策略应该不同**，即使它们属于同一张表。

---

### 3.6 理论基础小结

```
                         ┌───────────────────────────┐
                         │     地址空间理论             │
                         │     (Separation Logic)      │
                         │                             │
                         │  回答："什么是冲突？"         │
                         │  冲突 = 修改域相交           │
                         └─────────────┬───────────────┘
                                       │
              ┌────────────────────────┼────────────────────────┐
              │                        │                        │
              ▼                        ▼                        ▼
┌─────────────────────────┐ ┌─────────────────────────┐ ┌─────────────────────────┐
│    操作差分模型           │ │     Patch 理论            │ │     形式化验证            │
│    (Baseline 2025)       │ │    (Pijul / Grove)       │ │     (Alloy / TLA+)       │
│                          │ │                          │ │                          │
│ 回答："版本历史应该       │ │ 回答："合并如何总是        │ │ 回答："如何证明系统        │
│ 记录什么？"               │ │ 正确的？"                 │ │ 在所有路径下正确？"        │
│                          │ │                          │ │                          │
│ 记录操作，而非状态快照     │ │ 用数学结构保证合并安全     │ │ 穷举所有状态组合进行验证    │
└─────────────────────────┘ └─────────────────────────┘ └─────────────────────────┘
                                       │
                                       ▼
                          ┌─────────────────────────┐
                          │     实证研究              │
                          │     (ASE 2024)            │
                          │                          │
                          │  回答："实际工程中什么      │
                          │  策略最有效？"             │
                          │                          │
                          │  简单专用 > 复杂通用       │
                          │  保守策略 > 激进自动       │
                          └─────────────────────────┘
```

---

## 4. 核心创新：地址空间细化

### 创新本质

语义感知的技术创新核心就一个：**把版本控制系统的地址空间从"表级"细化到"子结构级"**。

这不是一个算法优化——这是一个**分辨率提升**。类比显微镜从 100x 到 1000x：不是看到了新东西，而是能分辨以前看不清的东西。

### 当前 vs 语义感知后的地址空间

```
当前 Nessie——表级地址空间：

    地址空间
    ┌──────────────────────────────────────┐
    │                                      │
    │  StoreKey("customer_events")         │  ← 唯一地址
    │    └── 值 = 整个 TableMetadata blob  │  ← 不可分割
    │                                      │
    │  StoreKey("order_summary")            │  ← 唯一地址
    │    └── 值 = 整个 TableMetadata blob  │  ← 不可分割
    │                                      │
    │  冲突 = 同一 StoreKey 被双方修改       │
    └──────────────────────────────────────┘


语义感知 Nessie——子结构级地址空间：

    地址空间
    ┌──────────────────────────────────────────────────────┐
    │                                                       │
    │  StoreKey("customer_events")                          │
    │    ├── .schema                                        │  ← 独立子地址
    │    │     ├── column[0]: id       (name, type, meta)   │
    │    │     ├── column[1]: name     (name, type, meta)   │
    │    │     └── column[2]: email    (name, type, meta)   │
    │    ├── .partitionSpec                                  │  ← 独立子地址
    │    │     └── field[0]: month(date)                    │
    │    ├── .properties                                     │  ← 独立子地址
    │    │     ├── key: "owner"      → "team-a"             │
    │    │     └── key: "retention"  → "30d"                │
    │    ├── .snapshots                                      │  ← 独立子地址
    │    │     ├── snap-1 (2026-01-15)                      │
    │    │     └── snap-2 (2026-05-18)                      │
    │    └── .snapshotLog                                    │  ← 独立子地址
    │          ├── entry: snap-1                            │
    │          └── entry: snap-2                            │
    │                                                       │
    │  冲突 = 同一子地址被双方修改                            │
    └──────────────────────────────────────────────────────┘
```

### 效果示意

```
场景：Branch A 新增 email 列，Branch B 修改 owner 属性

当前（表级地址空间）：
    ┌──────────────────────────────────┐
    │  StoreKey("customer_events")      │
    │                                  │
    │  Branch A 修改了它                │
    │  Branch B 修改了它                │
    │      ↓                           │
    │  mod(A) ∩ mod(B) ≠ ∅  →  冲突！  │
    └──────────────────────────────────┘

语义感知（子结构级地址空间）：
    ┌──────────────────────────────────┐
    │  .schema       ← Branch A 修改   │
    │  .properties   ← Branch B 修改   │
    │  .partition    ← 都没动          │
    │  .snapshots    ← 都没动          │
    │      ↓                           │
    │  mod(A) = {.schema}              │
    │  mod(B) = {.properties}          │
    │  mod(A) ∩ mod(B) = ∅  →  可合并！│
    └──────────────────────────────────┘
```

---

## 5. 语义冲突检测：完整场景分析

地址空间细化之后，可以对变更场景做系统性的分类。以下以 Iceberg 表的 `TableMetadata` 为例，穷举所有可能的语义变更组合。

### 5.1 场景全景矩阵

**子结构维度** × **操作维度** = 冲突判定矩阵。

```
┌────────────────────────────────────────────────────────────────────────────────────┐
│                              语义冲突检测全景矩阵                                     │
│                                                                                     │
│  行 = Branch A 的操作    列 = Branch B 的操作    单元格 = 冲突判定                     │
│                                                                                     │
│  ✓ = 独立（可自动合并）    ✗ = 冲突（需人工确认）    ⚠ = 有条件冲突（取决于具体值）      │
│                                                                                     │
├──────────────────┬──────────────────┬──────────────────┬──────────────────┬─────────┤
│  Branch A ↓  B→  │ 修改 Schema      │ 修改 Properties  │ 修改 PartitionSpec│修改     │
│                  │                  │                  │                  │Snapshot  │
├──────────────────┼──────────────────┼──────────────────┼──────────────────┼─────────┤
│ 修改 Schema      │ ⚠ 看具体列        │ ✓                │ ⚠               │ ✓       │
│                  │                  │                  │                  │         │
├──────────────────┼──────────────────┼──────────────────┼──────────────────┼─────────┤
│ 修改 Properties  │ ✓                │ ⚠ 看具体 key      │ ✓               │ ✓       │
│                  │                  │                  │                  │         │
├──────────────────┼──────────────────┼──────────────────┼──────────────────┼─────────┤
│ 修改             │ ⚠               │ ✓                │ ✗               │ ⚠      │
│ PartitionSpec    │                  │                  │                  │         │
├──────────────────┼──────────────────┼──────────────────┼──────────────────┼─────────┤
│ 修改 Snapshot    │ ✓                │ ✓                │ ⚠               │ ⚠      │
│                  │                  │                  │                  │         │
└──────────────────┴──────────────────┴──────────────────┴──────────────────┴─────────┘
```

### 5.2 典型场景详解

#### Schema × Schema（同一子结构被双方修改）

这是最需要细粒度分析的场景。不是所有 schema 修改都冲突。

```
场景 1a：双方新增不同的列 —— ✓ 独立

    Base:          [id: INT, name: STRING]
    Branch A:      [id: INT, name: STRING, email: STRING]    ← +email
    Branch B:      [id: INT, name: STRING, phone: STRING]    ← +phone

    分析：mod(A) ∩ mod(B) = {新增列集合} = ∅ （email ≠ phone）
    判定：独立，可合并 → [id, name, email, phone]

    原理：set-union。新增的列不重叠。


场景 1b：双方新增同一列，类型相同 —— ✓ 独立

    Base:          [id: INT, name: STRING]
    Branch A:      [id: INT, name: STRING, email: STRING]
    Branch B:      [id: INT, name: STRING, email: STRING]

    分析：虽然 email 列被双方都操作了，但结果一致
    判定：独立，合并后 → [id, name, email: STRING]


场景 1c：双方新增同一列，类型不同 —— ✗ 冲突

    Base:          [id: INT, name: STRING]
    Branch A:      [id: INT, name: STRING, email: STRING]
    Branch B:      [id: INT, name: STRING, email: VARCHAR(100)]

    分析：同一地址被赋予不同值
    判定：冲突。系统不知道哪一种类型是正确的
    解决：人工选择 STRING 或 VARCHAR(100)


场景 1d：一方新增列，另一方修改已有列的类型 —— ⚠ 有条件

    Base:          [id: INT, name: STRING]
    Branch A:      [id: INT, name: STRING, email: STRING]    ← +email
    Branch B:      [id: BIGINT, name: STRING]                 ← id 拓宽

    分析：mod(A) = {email}，mod(B) = {id}，不重叠
    判定：独立，可合并 → [id: BIGINT, name: STRING, email: STRING]
    前提：id 拓宽是向后兼容的


场景 1e：双方修改同一列的类型 —— ✗ 冲突

    Base:          [id: INT, name: STRING]
    Branch A:      [id: BIGINT, name: STRING]       ← INT→BIGINT
    Branch B:      [id: STRING, name: STRING]       ← INT→STRING

    分析：同一地址被赋予不同值
    判定：冲突


场景 1f：一方重命名列，另一方修改同一列的类型 —— ⚠ 有条件

    Base:          [id: INT, user_name: STRING]
    Branch A:      [id: INT, name: STRING]          ← 重命名 user_name→name
    Branch B:      [id: INT, user_name: VARCHAR]    ← 拓宽类型

    分析：重命名列 = user_name 列的 name 属性变更 + 类型未变
          B 修改的是 user_name 的类型属性
          两个修改域不重叠（一个改 name，一个改 type）
    判定：独立，可合并 → [id: INT, name: VARCHAR]
    前提：A 的重命名通过列 ID 而非名称识别，Iceberg 正是如此


场景 1g：一方删列，另一方修改同一列 —— ✗ 冲突

    Base:          [id: INT, name: STRING, email: STRING]
    Branch A:      [id: INT, name: STRING]                ← 删除 email
    Branch B:      [id: INT, name: STRING, email: BIGINT] ← 修改 email

    分析：email 列被一方删除，另一方修改
    判定：冲突。双方对 email 列的意图矛盾
```

**Schema 冲突判定原则总结**：

```
Schema 冲突判定决策树

双方都改了 Schema？
  │
  ├── 否 → ✓ 无冲突
  │
  └── 是 → 变更涉及了同一列？
            │
            ├── 否 → ✓ 独立（set-union）
            │
            └── 是 → 同一列上的变更兼容？
                      │
                      ├── 双方都只是新增（列 ID 不同）→ ✓
                      ├── 一新增一修改（列 ID 不同）→ ✓
                      ├── 一重命名一修改（不同的列属性）→ ✓ (Iceberg 列 ID 一致)
                      ├── 变更结果相同 → ✓
                      └── 变更结果不同 → ✗ 冲突
```

#### Properties × Properties

```
场景 2a：双方修改不同的 key —— ✓ 独立

    Base:          {owner: "team-a", region: "us-east"}
    Branch A:      {owner: "team-b", region: "us-east"}   ← 改 owner
    Branch B:      {owner: "team-a", region: "eu-west"}   ← 改 region

    分析：mod(A) = {owner}，mod(B) = {region}，不重叠
    判定：独立，合并 → {owner: "team-b", region: "eu-west"}


场景 2b：双方修改同一 key，值不同 —— ✗ 冲突

    Base:          {owner: "team-a"}
    Branch A:      {owner: "team-b"}
    Branch B:      {owner: "team-c"}

    分析：同一 key 被赋予不同值
    判定：冲突。系统不知道哪个值正确


场景 2c：一方修改，另一方新增 —— ✓ 独立

    Base:          {owner: "team-a"}
    Branch A:      {owner: "team-b"}                      ← 改 owner
    Branch B:      {owner: "team-a", retention: "30d"}    ← 新增 retention

    分析：mod(A) = {owner}，mod(B) = {retention.new}
    判定：独立，合并 → {owner: "team-b", retention: "30d"}


场景 2d：一方删除 key，另一方修改同一 key —— ✗ 冲突

    Base:          {owner: "team-a", region: "us-east"}
    Branch A:      {owner: "team-a"}                      ← 删除 region
    Branch B:      {owner: "team-a", region: "eu-west"}   ← 改 region

    分析：一方删除，一方修改，意图矛盾
    判定：冲突


场景 2e：双方新增同一 key，值相同 —— ✓ 独立
         双方新增同一 key，值不同 —— ✗ 冲突
         （与 Schema 场景 1b/1c 同理）
```

#### PartitionSpec × PartitionSpec

```
场景 3a：双方都修改了 PartitionSpec —— ✗ 冲突

    分析：分区策略变更影响表的物理布局，语义复杂
          两个不同的分区变更几乎不可能自动合并
    判定：冲突，需人工确认
    原因：分区变更的风险远高于 schema 变更，
          错误的自动合并可能导致全表数据重写


场景 3b：一方修改，另一方未改 —— ✓ 独立

    分析：mod 不重叠
    判定：取修改方
```

#### Snapshot × Snapshot

同一张表的 snapshot 变更，底层可能是完全不同的操作类型，合并语义各异。

```
场景 4a：双方都是 AppendFiles —— ✓ 独立

    Branch A: INSERT INTO → snap-3 (file-a1.parquet)
    Branch B: INSERT INTO → snap-4 (file-b1.parquet)

    分析：追加操作可交换。数据写入不同文件，无重叠。
    Iceberg 的 snapshot 隔离保证数据文件层面天然不冲突。
    判定：独立，按时间顺序合并 snapshot 序列


场景 4b：一方 AppendFiles，一方 OverwriteFiles —— ✗ 冲突

    Branch A: INSERT INTO → snap-3 (追加 file-a1.parquet)
    Branch B: INSERT OVERWRITE → snap-4 (覆盖分区为 file-b1.parquet)

    分析：OverwriteFiles 会覆盖分区的所有数据文件，
          自动合并会导致 Branch A 追加的数据丢失。
    判定：冲突，需人工确认——要么先合并 A 再执行 B 的覆盖，
          要么放弃覆盖改用追加。


场景 4c：一方 AppendFiles，一方 DeleteFiles —— ⚠ 有条件

    Branch A: INSERT INTO → snap-3 (追加 file-a1.parquet)
    Branch B: DELETE FROM → snap-4 (条件删除，产生 delete file)

    分析：Append 和 Delete 操作的数据文件不重叠则独立。
          但需检查 Delete 操作的条件是否覆盖了 Append 写入的行。
          在 catalog 层无法判断行级重叠，只能检查 partition/file 范围。
    判定：默认独立，但标记为建议人工复核。
          如果 delete 条件是全表 → 升级为冲突。


场景 4d：一方 RewriteFiles (Compaction)，一方任何 Snapshot 操作 —— ✓ 独立

    Branch A: OPTIMIZE → Compaction snap-3 (文件合并，数据不变)
    Branch B: INSERT INTO → snap-4 (追加 file-b1.parquet)

    分析：RewiteFiles 是透明操作，不改变数据内容。
          但 Compaction 的物理文件可能被 B 的变更间接影响。
    处理：合并时取 B 的 Snapshot + 在合并后重新执行 Compaction。
          RewriteFiles 的安全窗口已过期，需要重新执行。


场景 4e：一方 RollbackToSnapshot，一方任何修改 —— ✗ 冲突

    Branch A: Rollback to snap-1（回退到历史快照，丢弃 snap-2, snap-3）
    Branch B: INSERT INTO → snap-4 (在 snap-3 基础上追加)

    分析：Rollback 是破坏性操作，其语义是"放弃中间所有变更"。
          B 的提交建立在将被回退的快照之上。
    判定：冲突，需人工确认——回退可能导致 B 的变更基于已过期的表状态。
          如果确认接受回退，B 的变更需要重新 apply 到回退后的状态。


场景 4f：双方 ExpireSnapshots —— ✓ 独立

    Branch A: Expire snapshot-1（过期旧快照）
    Branch B: Expire snapshot-2（过期另一个旧快照）

    分析：Expire 是清理操作，不产生新数据。
          双方 expire 不同的快照 → 取并集。
    判定：独立。


场景 4g：一方 CherrypickSnapshot，另一方未动 snapshot —— ✓ 独立

    Branch A: Cherrypick snap-3 from other-branch
    Branch B: 未修改 snapshot

    分析：Cherrypick 是引用复制操作，不产生新的数据文件。
    判定：独立，合并后保留 cherrypick 结果。


场景 4h：双方 Cherrypick 了不同的外部 snapshot —— ✓ 独立

    分析：两个 cherrypick 引入的是不同来源的 snapshot，数据文件不重叠。
    判定：独立，合并后包含两个 cherrypick 的结果。
```

**Snapshot 操作类型快速判定表**：

```
                    另一方 →
    ↓ 本方操作        AppendFiles  DeleteFiles  OverwriteFiles  RewriteFiles  Rollback  ExpireSnap
    ─────────────────────────────────────────────────────────────────────────────────────────────
    AppendFiles       ✓            ⚠            ✗              ✓*            ✗         ✓
    DeleteFiles       ⚠            ⚠            ✗              ✓*            ✗         ✓
    OverwriteFiles    ✗            ✗            ✗              ✓*            ✗         ✓
    RewriteFiles      ✓*           ✓*           ✓*             ✓             ✓*        ✓
    Rollback          ✗            ✗            ✗              ✓*            ✗         ✓
    ExpireSnapshots   ✓            ✓            ✓              ✓             ✓         ✓
    Cherrypick        ✓            ✓            ✓              ✓             ✗         ✓

    ✓ = 可自动合并   ⚠ = 有条件冲突   ✗ = 冲突需人工确认
    ✓* = 透明操作的合并后需重新执行 RewriteFiles
```

#### 跨子结构场景（自动合并）

```
场景 5a：Schema + Properties

    Branch A: 新增 email 列（改 .schema）
    Branch B: 修改 owner 属性（改 .properties）

    mod(A) = {.schema}，mod(B) = {.properties}
    mod(A) ∩ mod(B) = ∅  →  ✓ 自动合并


场景 5b：Schema + Snapshots

    Branch A: 新增 phone 列（改 .schema, P3 增量操作）
    Branch B: AppendFiles 写入新数据（改 .snapshots, P3 增量操作）

    分析：不同子结构，且都是 P3 增量操作
    ✓ 自动合并（schema 取 A，snapshots 取 B）


场景 5c：Properties + Snapshots

    Branch A: 修改 retention 属性（改 .properties, P3 增量操作）
    Branch B: AppendFiles 写入新数据（改 .snapshots, P3 增量操作）

    ✓ 自动合并


场景 5d：Schema + PartitionSpec

    Branch A: 新增 email 列（改 .schema, P3 增量操作）
    Branch B: 修改分区策略（改 .partitionSpec, P2 结构替换操作）

    ⚠ 有条件：如果 email 列被作为新的分区键 → 需要关联分析
          如果 email 列与分区无关 → ✓ 独立


场景 5e：Schema + OverwriteFiles

    Branch A: 新增 email 列（改 .schema, P3 增量操作）
    Branch B: INSERT OVERWRITE 覆盖分区数据（改 .snapshots, P1 破坏性操作）

    分析：不同子结构，在地址空间理论下不冲突。
          但 P1 操作具有高风险——B 的覆盖操作可能影响 A 新增列所在分区的数据完整性。
          这种情况在语义感知下仍然判定为不同子结构 ✓ 自动合并，
          但系统应发出 P1 操作警告供人工复核。

    判定：schema 层面 ✓ 自动合并；snapshot 层面标记 P1 警告。


场景 5f：Properties + RollbackToSnapshot

    Branch A: 修改 owner 属性（改 .properties, P3 增量操作）
    Branch B: Rollback to snap-1（P1 破坏性操作——回退到 snap-1 时的表状态）

    分析：虽然不同子结构，但 Rollback 的语义是"整个表回退到 snap-1 的状态"。
          这意味着 B 会撤销 snap-1 之后的所有变更——包括可能在其他分支上的变更。
          Rollback 是**跨子结构破坏性操作**，其影响范围不限于 snapshot 子结构。

    判定：✗ 冲突。Rollback 的破坏性是表级的，不能仅凭子结构独立性自动合并。


场景 5g：DropTable + 任何修改

    Branch A: DropTable（P0 存在性操作）
    Branch B: AddColumn（P3 增量操作）

    分析：DropTable 是 P0 存在性操作，其语义是"表不应该存在"。
          B 的 AddColumn 建立在表存在的前提下。
    判定：✗ 冲突。P0 操作永远需要人工确认。
```

### 5.3 冲突判定流程

完整的语义冲突检测包含两层判断——子结构层和操作类型层：

```
                    两个分支对同一 ContentKey 的修改
                                  │
                                  ▼
                    ┌─────────────────────────┐
                    │ Step 1: 解析语义结构        │
                    │                          │
                    │ 将两个版本的                 │
                    │ TableMetadata 按子结构展开   │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │ Step 2: 逐子结构匹配        │
                    │                          │
                    │ 对每个子结构：               │
                    │ 该子结构是否被双方都修改了？    │
                    └────────────┬────────────┘
                                 │
                    ┌────────────┼────────────┐
                    │            │            │
                    ▼            ▼            ▼
               都没改/      仅一方修改      双方都修改
               只一方改         │              │
                  │            │              ▼
                  │            │   ┌─────────────────────────┐
                  │            │   │ Step 3: 识别操作类型       │
                  │            │   │  (3.2.3 节 P0-P4 分类)    │
                  │            │   │                          │
                  │            │   │ 快照操作：Append/Overwrite/│
                  │            │   │   Rewrite/Rollback/Delete? │
                  │            │   │ Schema 操作：Column/Type/  │
                  │            │   │   Rename?                │
                  │            │   └──────────┬──────────────┘
                  │            │              │
                  │            │   ┌──────────┼──────────┐
                  │            │   │          │          │
                  │            │   ▼          ▼          ▼
                  │            │ P0/P1       P2         P3/P4
                  │            │ 存在性/破坏性  结构替换      增量/透明
                  │            │   │          │          │
                  │            │   ▼          ▼          ▼
                  │            │ ✗ 强制      进入 Step 4  ✓ 自动
                  │            │ 人工确认                （低风险）
                  │            │              │
                  │            │   ┌─────────────────────────┐
                  │            │   │ Step 4: 子结构内冲突检测    │
                  │            │   │ (仅 P2/P3 同优先级操作)     │
                  │            │   │                          │
                  │            │   │ Schema: 同列被不兼容修改？    │
                  │            │   │ Props:  同 key 不同值？      │
                  │            │   │ Part:   总是冲突            │
                  │            │   │ Snap:   同文件引用？         │
                  │            │   │         Overwrite×Append?  │
                  │            │   └──────────┬──────────────┘
                  │            │              │
                  │            │   ┌──────────┼──────────┐
                  │            │   │          │          │
                  │            │   ▼          ▼          ▼
                  │            │ 同一子结构   不同子结构    不兼容
                  │            │ 兼容变更      │          │
                  │            │   │          │          │
                  │            ▼   ▼          ▼          ▼
                  │         ✓ 无冲突   ✓ 无冲突   ✓ 无冲突   ✗ 冲突
                  │
                  ▼
               ✓ 无冲突
```

**关键新增逻辑——操作类型优先级的提前分流**：

在进入子结构内详细比较（Step 4）之前，先在 Step 3 按操作优先级分流：
- **P0/P1 操作**（存在性/破坏性）→ 直接要求人工确认，不允许自动合并
- **P2 操作**（结构替换）→ 进入 Step 4 详细比较
- **P3 操作**（增量）→ 跨子结构直接通过，同子结构进入 Step 4
- **P4 操作**（透明/清理）→ 直接通过，自动让步给其他操作

这样可以避免在危险操作上进行精细的子结构比较——再精细的比较也无法消除操作本身的破坏性。

---

## 6. 语义合并：层次化策略

冲突检测（5.2）告诉我们哪些场景可以自动合并。合并策略矩阵告诉我们**怎么合并**。

### 合并策略矩阵

| 子结构 | 无冲突条件 | 自动合并策略 | 合并复杂度 | 风险等级 |
|--------|-----------|-------------|-----------|---------|
| **Schema** | 双方新增列不重叠、同列变更结果一致 | **Set-Union**：合并双方的列集合 | 低 | 低 |
| **Schema** | 仅一方修改 | 取修改方 | 低 | 低 |
| **Schema** | 一方重命名，一方修改（列 ID 相同） | 合并列属性 | 中 | 中 |
| **Properties** | 键集合不相交 | **Key-Union**：合并键值对 | 低 | 低 |
| **Properties** | 同名键同值 | 保留 | 低 | 低 |
| **PartitionSpec** | 仅一方修改 | 取修改方 | 低 | 中 |
| **Snapshots** | 双方都是 AppendFiles | 按时间顺序合并 snapshot 序列 | 中 | 低 |
| **Snapshots** | 一方 AppendFiles，一方 DeleteFiles | 合并 snapshot 序列 + 标记人工复核 | 中 | 中 |
| **Snapshots** | 仅一方有 AppendFiles | **Cherry-Pick**：搬迁到目标 | 中 | 低 |
| **Snapshots** | 一方 RewriteFiles（Compaction） | 丢弃 RewriteFiles，取对方 + 标记需重新执行 Compaction | 中 | 低 |
| **Snapshots** | 一方 RollbackToSnapshot | **不可自动合并** —— 回退操作具有表级破坏性 | — | 最高 |
| **Snapshots** | 一方 OverwriteFiles | **不可自动合并** —— 覆盖操作会丢失另一方的数据 | — | 高 |
| **任何子结构** | 双方都修改，且不兼容 | **回退 409 冲突**，不猜测 | — | — |
| **任何子结构** | 涉及 P0 存在性操作（CreateTable/DropTable） | **强制人工确认**，永不自动合并 | — | 最高 |

### 合并的执行模型

```
                    Base Commit (共同祖先)
                    ┌─────────────────────────┐
                    │ TableMetadata (Version 0)│
                    │  schema:    [id, name]   │
                    │  props:     {owner: "A"} │
                    │  snaps:     [s1, s2]     │
                    └───────────┬─────────────┘
                                │
                ┌───────────────┴───────────────┐
                │                               │
                ▼                               ▼
        Source Commit                    Target Commit
        (Branch A)                       (Branch B → main)
        ┌─────────────────────┐         ┌─────────────────────┐
        │  schema:  [id,name, │         │  schema:  [id, name] │
        │            email]   │         │  props:   {owner:"B"}│
        │  props:   {owner:"A"}│        │  snaps:   [s1,s2,s3] │
        │  snaps:   [s1,s2]   │         └──────────┬──────────┘
        └──────────┬──────────┘                    │
                   │                               │
                   └───────────┬───────────────────┘
                               │
                               ▼
                    ┌─────────────────────────┐
                    │  语义合并引擎             │
                    │                          │
                    │  .schema:                │
                    │    source 新增 email      │
                    │    target 未改            │
                    │    → 取 source           │
                    │                          │
                    │  .properties:            │
                    │    source 未改            │
                    │    target 修改 owner      │
                    │    → 保留 target         │
                    │                          │
                    │  .snapshots:             │
                    │    source 未改            │
                    │    target 新增 s3        │
                    │    → 保留 target         │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │  Merge Commit            │
                    │                          │
                    │  schema: [id,name,email] │  ← 来自 source
                    │  props:  {owner:"B"}     │  ← 来自 target
                    │  snaps:  [s1,s2,s3]     │  ← 来自 target
                    └─────────────────────────┘
```

---

## 7. 解锁的其他能力

地址空间细化不仅改善了 merge 行为，它是一把钥匙——打开了一系列新能力。

### 7.1 语义 Diff

**当前**：Nessie 只能告诉你"这个 ContentValueObj 的 hash 从 X 变成了 Y"。

**语义感知后**：

```
┌─────────────────────────────────────────────────────────────┐
│  Diff: customer_events  (commit abc123 → commit def456)     │
│                                                              │
│  Schema:                                                     │
│    + email: STRING (nullable)         ← 新增列               │
│    ~ user_id: INT → BIGINT            ← 类型拓宽             │
│                                                              │
│  Properties:                                                 │
│    ~ owner: "team-a" → "team-b"                              │
│    + retention_hours: "168"                                  │
│                                                              │
│  PartitionSpec: (无变更)                                     │
│                                                              │
│  Snapshots:                                                  │
│    + snapshot-3: 2026-05-18T10:30:00Z                        │
│      ├── 新增 12 个数据文件                                   │
│      └── 删除 3 个数据文件 (compaction)                       │
│                                                              │
│  兼容性评估：向后兼容 ✓                                       │
│  下游影响：churn_predictor 模型读取 user_id 列，需重新训练      │
└─────────────────────────────────────────────────────────────┘
```

**技术分层**：

- **Level 1: 元数据 Diff** — Nessie 直接实现，只比较 `TableMetadata` 结构，不需要访问数据文件。这是语义 Diff 的**低成本切入路径**。
- **Level 2: 数据 Diff** — 与 Iceberg 协同，通过 manifest 文件比较数据文件级别的增删。需要访问对象存储。

**参考系统**：
- DataDios SmartDiff（Poduri & Tailor, 2025.08）：Schema 感知 + LLM 标注差异原因，将根因分析从 10 小时降到 12 分钟
- Dolt `DOLT_DIFF()` + `--skinny`（2025.09）：只显示变更列，自动隐藏未变更上下文

---

### 7.2 语义查询

**当前**：只能在 Nessie 中按 branch/tag/commit hash 导航版本历史。

**语义感知后**：

```
查询 1：列级追踪
  "email 列是哪个 commit 引入的？之后被修改过几次？"

查询 2：属性变更审计
  "retention_hours 属性何时从 72 改为 168？谁改的？"

查询 3：跨表影响分析
  "最近一周内，哪些 commit 修改了包含 user_id 列的表？"

查询 4：时间窗口 + 语义过滤
  "2026 Q1 期间所有删除了列的 commit"
```

**实现方式**：离线语义索引。Nessie 的 GC 或其他批处理任务遍历 commit DAG，反序列化 `TableMetadata`，构建列/属性级别的变更索引。这是一个**纯读**路径，不触碰写入关键路径。

---

### 7.3 语义校验

**当前**：Nessie 不做任何内容校验。可以 commit 一个 schema 不兼容的表变更。

**语义感知后**：在 commit 和 merge 的关键操作点进行校验。

```
校验规则体系

┌─────────────────────────────────────────────────────────────┐
│ Schema 兼容性规则                                            │
├─────────────────────────────────────────────────────────────┤
│ ✓ 新增 nullable 列                              → 自动通过   │
│ ✓ 类型拓宽 (INT→BIGINT, FLOAT→DOUBLE)            → 自动通过   │
│ ⚠ 新增 NOT NULL 列 (需有默认值)                   → 人工确认  │
│ ⚠ 重命名列 (Iceberg 列 ID 不变，但下游可能依赖列名)  → 人工确认  │
│ ✗ 删除列                                        → 拒绝      │
│ ✗ 类型收窄 (BIGINT→INT, STRING→VARCHAR)           → 拒绝      │
├─────────────────────────────────────────────────────────────┤
│ 业务规则（用户自定义）                                        │
├─────────────────────────────────────────────────────────────┤
│ ⚠ 标记为 PII 的列不能出现在非加密分区                            │
│ ⚠ 列名必须符合团队命名规范 regex                                │
│ ⚠ 删除列只能在 major version 标签时进行                         │
└─────────────────────────────────────────────────────────────┘
```

**参考系统**：
- Flyway Community Drift Check（Redgate, 2024）：检测数据库 schema 与版本控制定义之间的漂移
- Squawk（2025）：PostgreSQL migration 静态分析 linter

---

### 7.4 语义 Lineage

**当前**：Nessie 的版本历史是 commit 级别的——知道"commit X 修改了表 Y"。

**语义感知后**：Lineage 细化到列级/属性级。

```
当前（表级 Lineage）：
  commit abc123 (alice, 2026-05-18)
  └── 修改了 customer_events 表


语义 Lineage（列级/属性级）：
  commit abc123 (alice, 2026-05-18)
  ├── schema.email: 新增列，类型 STRING，nullable
  ├── schema.user_id: 类型变更 INT → BIGINT
  ├── properties.owner: team-a → team-b
  └── snapshots: 新增 snapshot-3（12 data files, 3 removed）

  上游来源：
  └── email 列来源于 user_profile.email（ETL 任务 etl_daily_v2）

  下游影响：
  ├── 报表 dashboard_revenue 读取 email 列 → 向后兼容，安全 ✓
  └── ML 模型 churn_predictor 读取 user_id(BIGINT) → 需重新训练 ⚠
```

**参考系统**：
- LIMA（SIGMOD 2021，持续影响至 2025）：ML 系统的细粒度 Lineage DAG
- TableVault（arXiv:2508.06814, 2025）：人-AI 协作的 Lineage 感知元数据治理

---

### 7.5 语义 GC

**当前**：Nessie GC 通过 mark-and-sweep 标记所有被 reference 可达的 `ContentValueObj` 为存活。

**语义感知后**：可以做更精准的垃圾回收。

```
当前 GC：
  标记策略：所有 reference 可达的 ContentValueObj → 存活
  问题：一张表有 10 个 snapshot，分支只需要最近 3 个，
        但 GC 无法知道哪些 snapshot 可以部分回收

语义 GC：
  标记策略：按 snapshot 粒度标记
  ├── 每个 snapshot 的 manifest 列表独立标记
  ├── 仅被过期 snapshot 引用的数据文件 → 可回收
  ├── 不再被任何分支引用的 schema/partition spec 版本 → 可回收
  └── 完整性验证：在 mark 阶段验证存活对象的 Iceberg 元数据完整性
```

---

## 8. 架构演进：加能力不加耦合

### 核心原则

Nessie 存储层的核心契约是**格式无关性**——`CommitLogicImpl` 不依赖 Iceberg 库，只操作抽象的 `ContentValueObj`。语义感知不能破坏这个契约。

架构方案：**SPI（Service Provider Interface）插件化**。

### SPI 架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Nessie 服务端                           │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              ContentSemantics SPI                     │   │
│  │              (格式无关接口，定义在 versioned/spi/)        │   │
│  │                                                       │   │
│  │  diff(base, source, target) → SemanticDiff            │   │
│  │  detectConflict(srcDiff, tgtDiff) → ConflictResult     │   │
│  │  merge(base, source, target) → MergedContent           │   │
│  │  validate(content) → ValidationResult                 │   │
│  │  getSubstructureTypes() → List<SubstructureType>      │   │
│  └──────────────────────┬───────────────────────────────┘   │
│                         │                                    │
│                         │ 插件化加载                          │
│                         │                                    │
│         ┌───────────────┼───────────────┐                    │
│         │               │               │                    │
│         ▼               ▼               ▼                    │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐             │
│  │ Iceberg    │  │ Delta      │  │ Hudi       │             │
│  │ Semantics  │  │ Semantics  │  │ Semantics  │             │
│  │            │  │            │  │            │             │
│  │ 使用       │  │ (未来)      │  │ (未来)      │             │
│  │ Iceberg    │  │            │  │            │             │
│  │ 库已有 API │  │            │  │            │             │
│  └────────────┘  └────────────┘  └────────────┘             │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Nessie 存储层（完全不受影响）               │   │
│  │                                                       │   │
│  │  CommitLogicImpl → 仍然操作 ContentValueObj              │   │
│  │  StoreIndex → 仍然操作 StoreKey → CommitOp              │   │
│  │  Persist → 仍然是抽象的 key-value 存储                   │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

**SPI 的关键设计决策**：

| 决策 | 理由 |
|------|------|
| SPI 接口由 Nessie 定义 | 保持格式无关性，Nessie 不依赖 Iceberg 库 |
| 实现放在 Nessie 的 iceberg 模块 | 利用已有的 Iceberg 依赖，不引入新耦合 |
| SPI 方法是无状态的纯函数 | 不依赖存储状态，可被并行调用 |
| 合并失败的实现了可以忽略具体语义的合并 | 先手动解决冲突，再回退到现有的 `VALUE_DIFFERS` |

**Iceberg 实现为什么简单**：Iceberg 库已经提供了所有需要的 API：

| SPI 需要的能力 | Iceberg 已有 API |
|---------------|-----------------|
| Schema 比较 | `Schema.sameSchema()` |
| Schema 合并 | `SchemaUpdate.unionByNameWith()` |
| Snapshot 搬迁 | `SnapshotManager.cherrypick()` |
| TableMetadata 字段级访问 | 所有 getter 方法 |
| Schema 兼容性检查 | Iceberg schema evolution 规则 |

**Nessie 要做的不是发明这些算法，而是在正确的时机调用它们。**

---

## 9. 实施路线图

### 阶段一：语义 Diff（3–6 月）

**目标**：让 Nessie 能"看见"内容语义。这是最安全的第一步——不碰写入路径。

```
任务 1.1：ContentSemantics SPI 定义
  ├── 定义 Diff API
  ├── 定义语义结果数据结构（SemanticDiff）
  └── 放在 versioned/spi/ 中，零代码依赖

任务 1.2：Iceberg 语义实现
  ├── 在 nessie-iceberg 模块中实现 IcebergContentSemantics
  ├── 利用 Iceberg 已有的 TableMetadata API
  └── 单元测试覆盖所有 scenario

任务 1.3：Diff REST API
  ├── GET /api/v2/diffs/{fromRef}/...{toRef}
  ├── 参数: type=SEMANTIC (vs 默认 BLOB)
  └── 返回字段级差异
```

### 阶段二：语义冲突检测 + 部分自动合并（6–12 月）

**目标**：在 merge 路径中引入语义，但保持保守策略（不确定时回退）。

```
任务 2.1：语义冲突检测器
  ├── 实现 5.2 中的场景判定逻辑
  ├── 跨子结构场景自动标记为独立
  └── 同一子结构不兼容场景回退 VALUE_DIFFERS

任务 2.2：自动化安全合并
  ├── Schema set-union（双方新增不重叠列）
  ├── Properties key-union（双方修改不同 key）
  ├── Snapshots cherry-pick（单方有新增）
  └── 不满足条件时回退 409

任务 2.3：Pre-merge 语义校验（可选）
  ├── Schema 向后兼容性检查
  ├── 自定义规则引擎
  └── 校验失败 → 阻止 merge
```

### 阶段三：语义索引与运营（12 月+）

**目标**：利用累积的语义信息做查询、Lineage、GC 优化。

```
任务 3.1：语义索引服务
  ├── 监听 commit 事件
  ├── 异步维护列级/属性级变更索引
  └── 支持语义查询 API

任务 3.2：语义 Lineage
  ├── 列级变更追踪
  ├── 影响分析（谁用这个列）
  └── 与 dbt/Airflow 集成

任务 3.3：语义 GC 增强
  ├── Snapshot 级标记
  ├── 过期 Snapshot 部分回收
  └── 完整性验证
```

### 路线图总览

```
现在                         6个月                  12个月                  18个月+

Nessie 0.x                 Phase 1                  Phase 2                 Phase 3
(Blob 级操作)               (语义 Diff)              (语义冲突检测+合并)       (语义索引+运营)
    │                          │                        │                       │
    │                          │                        │                       │
    ├─ 只能判断                ├─ 可以看见               ├─ 可以判断              ├─ 可以追溯
    │  "变了没"                │  "变了什么"              │  "有没有冲突"           │  "谁在什么时间
    │                          │                        │  "能不能合并"           │   做了什么操作"
    │                          │                        │                        │
    ▼                          ▼                        ▼                       ▼
┌─────────┐              ┌───────────┐            ┌───────────┐           ┌───────────┐
│ 结构感知 │  ──────────► │  意图感知   │ ────────► │  约束感知   │ ────────► │  知识图谱  │
│          │              │           │            │           │           │           │
│ SPI 定义 │              │ 操作 Diff  │            │ 规则引擎   │           │ Lineage   │
└─────────┘              └───────────┘            └───────────┘           └───────────┘
```

---

## 10. 参考文献

### 形式化基础

1. **The Semantics of Version Control** — Swierstra, Löh. Onward! 2014.
   - 用 Separation Logic 为版本控制建立形式化基础：地址空间理论、Frame Rule、独立 Patch 定义

2. **Git-for-Data Semantics: Safe Branching & Merging at Scale (Part 1)** — Greco, Tagliabue. Bauplan Blog, 2025.07.
   - https://www.bauplanlabs.com/post/git-for-data-formal-semantics-of-branching-merging-and-rollbacks-part-1
   - 首次用 Alloy 模型检验器穷举验证 Git-for-Data 原语，开源模型代码在 https://github.com/BauplanLabs/git_for_data

3. **Baseline: Operation-Based Evolution and Versioning of Data** — Edwards, Petricek. arXiv:2512.09762, 2025.12.
   - https://arxiv.org/abs/2512.09762
   - 提出"操作差分"模型：用高语义操作（addColumn、renameTable）代替状态快照进行版本控制

4. **Schema Evolution in Interactive Programming Systems** — Edwards, Petricek, van der Storm, Litt. The Art, Science, and Engineering of Programming, Vol. 9, Issue 1, 2025.
   - https://doi.org/10.22152/programming-journal.org/2025/9/2
   - 定义了 Schema Evolution 领域的 8 个挑战问题

### Patch 理论与协作编辑

5. **Pijul** — Meunier. https://pijul.org/
   - 基于 Patch 理论的分布式版本控制系统。核心贡献：用范畴论定义版本控制语义（Graggle 结构），merge 是 total function

6. **Grove: A Bidirectionally Typed Collaborative Structure Editor Calculus** — POPL 2025.
   - https://dl.acm.org/doi/10.1145/3704909
   - 结构编辑器的 CmRDT：所有编辑可交换，无需 diff 和 merge 算法

7. **Approaches to Conflict-free Replicated Data Types** — Almeida. ACM Computing Surveys, 2024/2025.
   - https://dl.acm.org/doi/10.1145/3695249
   - 全面对比四种 CRDT 方法：State-based、Operation-based、Pure Op-based、Delta-state

### 合并工具实证研究

8. **Evaluation of Version Control Merge Tools** — Schesch et al. ASE 2024.
   - 6045 个真实 Java 合并场景的大规模实证研究
   - 核心结论：简单专用工具正确率与复杂工具相当，但错误率低一个数量级

9. **Revisiting the Conflict-Resolving Problem from a Semantic Perspective** — ASE 2024.
   - 从语义视角重新定义合并冲突问题

10. **Semantic Conflict Detection via Static Analysis** — ICSE 2024.
    - 用轻量级静态分析检测"文本合并通过但运行时崩溃"的语义冲突

11. **LastMerge: A Language-Agnostic Structured Merge Tool** — 2024/2025.
    - https://arxiv.org/abs/2507.19687
    - Tree-sitter 支持 350+ 语言，证明通用结构化合并可以替代语言特定工具

12. **Mergiraf: A Syntax-Aware Merge Driver for Git** — Delpeuch, 2024.
    - https://mergiraf.org
    - 基于 Tree-sitter 的语法感知 Git merge driver，支持 merge/rebase/cherry-pick

### 数据 Diff 与 Lineage

13. **DataDios SmartDiff: Illuminating Patterns of Divergence** — Poduri, Tailor. arXiv:2509.00293, 2025.08.
    - https://arxiv.org/abs/2509.00293
    - Schema 感知 + LLM 标注差异原因，将根因分析从 10 小时降到 12 分钟

14. **LIMA: Fine-grained Lineage Tracing and Reuse in ML Systems** — Phani, Rath, Boehm. SIGMOD 2021（持续影响至 2025）.
    - ML 系统的细粒度 Lineage DAG

### 存储引擎与数据库

15. **Dolt Prolly Tree Merge (~1000× Speedup)** — DoltHub Blog, 2025.07.
    - https://www.dolthub.com/blog/2025-07-16-announcing-fast-merge/
    - 树节点级三向合并算法，合并时间与修改量成正比

16. **MatrixOne: Version Control System for Data** — Gou et al. arXiv:2604.03927, 2026.04.
    - https://arxiv.org/abs/2604.03927
    - 数据库原生分支/diff/merge，MVCC + CoW 实现秒级 SQL 级 Diff

17. **ForkBase: Immutable, Tamper-evident Storage Substrate for Branchable Applications** — Lin et al. ICDE 2020.
    - https://arxiv.org/abs/2004.07585
    - 将 Git 的 fork/merge/versioning 内置到存储引擎层

### 形式验证

18. **Verifying Semantic Conflict-Freedom in Three-Way Program Merges** — Sousa, Dillig, Lahiri. 2018.
    - SafeMerge: 用组合验证算法证明三向合并的语义冲突自由，在 52 个真实 merge 中发现 11 个未被文本工具检测的违规

### Nessie 社区

19. **Nessie #2513: Content aware merge operations** — Robert Stupp, 2021.
    - https://github.com/projectnessie/nessie/issues/2513
    - 首次提出"语义感知合并"需求

20. **Nessie #6592: Prepare for content-aware merges** — 2023–2025.
    - https://github.com/projectnessie/nessie/issues/6592
    - MergeBehavior.APPLY + 交互式冲突解决 API

21. **Open Source Polaris Announcement** — Project Nessie Blog, 2024.08.
    - https://projectnessie.org/blog/2024/08/02/open-source-polaris-announcement/
    - Nessie 的 Git-like 能力将贡献到 Apache Polaris

22. **Reproducible Data Science over Data Lakes** — Tagliabue, Greco. SIGMOD DEEM 2024.
    - https://arxiv.org/abs/2404.13682
    - Nessie 作为学术对象的首篇论文
