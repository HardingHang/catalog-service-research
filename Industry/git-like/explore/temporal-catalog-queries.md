[TOC]
# 方向二：Temporal Catalog Queries（Catalog-wide 时间查询）

> 本文档专门阐述 Catalog-wide 时间查询的理论缺口、Catalog-wide Temporal Snapshot 形式化定义、多表时间一致性隔离级别，以及 Catalog-level Time Travel 查询语法与实现路径。
>
> 生成日期：2026-05-15

---

## 1. 核心问题

时态数据库理论成熟于单表级别（Jensen & Snodgrass, 1999/2018, VLDBJ; SQL:2011），但 **Catalog-wide 的时间查询没有理论定义**。"`SELECT * FROM catalog AT TIMESTAMP ...`" 的语义是什么？多张表如何在"同一时刻"保持一致？

当前行业对"时间旅行"的理解停留在**单表级别**——Iceberg 的 `FOR SYSTEM_TIME AS OF`、Delta 的 `VERSION AS OF`。最近的研究（irHINT @ SIGMOD 2026, LIT+ @ VLDB Journal 2026）也只在单表/文档索引层面优化时间查询性能。

**关键盲区：没有人定义"Catalog-wide temporal snapshot"的语义**。一个 Lakehouse 中有数百张表，每张表独立演化。如果用户想查询"2024 年 Q1 结束时整个 sales namespace 的状态"，分别查询每张表会得到一个**逻辑上不一致的组合**——orders 表可能包含了 3 月 31 日的新订单，而 customers 表可能还停留在 3 月 30 日的状态（因为 ETL 延迟）。

---

## 2. 理论基础：从单表 Transaction-Time 到 Catalog-wide Snapshot

### 2.1 SQL:2011 Transaction-Time 的局限

SQL:2011 standardized the data model for **transaction-time (system-versioned) tables** (Kulkarni & Michels, SIGMOD Record 2012)，其核心语义是：

- 每张表自动维护 `SYSTEM_TIME` 周期（`PERIOD FOR SYSTEM_TIME (row_start, row_end)`）
- 数据库自动记录每行数据在数据库中"有效"的时间区间
- 查询语法：`SELECT * FROM table FOR SYSTEM_TIME AS OF TIMESTAMP '...'`

**但 SQL:2011 的 transaction-time 是单表内的概念**。标准没有回答：
1. 当一次事务修改了多张表时，这些表的 `SYSTEM_TIME` 如何保持一致？
2. 查询 `"FOR SYSTEM_TIME AS OF"` 多张表时，如何保证读到的是"同一事务"提交的状态？
3. 是否存在"Catalog-wide"的 transaction-time 周期？

### 2.2 Snapshot Isolation 与 Temporal 的关系

Snapshot isolation 由 **Berenson et al. (1995)** 在 *A Critique of ANSI SQL Isolation Levels* 中提出，本质上是**基于 MVCC 的乐观并发控制机制**——事务启动时获取数据库的一个"快照"，读写互不阻塞。SQL:2011 **并未将 Snapshot Isolation 标准化**，它仍是实现特定的并发机制（SQL Server、PostgreSQL、SAP ASE 各自实现）。

关键洞察：**transaction-time 表和 snapshot isolation 在概念上通过多版本机制关联**：
- Transaction-time 表需要保留行的历史版本
- Snapshot isolation 也需要保留多版本供读事务使用
- 但两者解决的问题不同：前者回答"数据何时存在于数据库中"，后者回答"事务能否读到一致的状态"

**对 Catalog 的启示**：如果我们想定义 Catalog-wide 的时间查询，需要将这两个概念**从单表提升到 Catalog 级别**。

### 2.3 分布式系统中的 Consistent Cut（一致割）

Lamport (1978) 提出的 **Consistent Cut** 是分布式系统快照的理论基础：

> 一个全局状态是一个 Consistent Cut，当且仅当：如果事件 e 属于该状态，且存在另一事件 e' 满足 e' → e（happens-before），则 e' 也必须属于该状态。

**Spanner** (Google, OSDI 2012) 使用 **TrueTime** API（时钟不确定区间）实现外部一致性——提交事务时显式等待时钟不确定窗口（~7ms），确保全局顺序。**Hybrid Logical Clocks (HLC)** (Kulkarni et al., 2014) 则提出用逻辑时钟替代物理时钟，在相同的逻辑时钟值处采集的局部快照构成一个 Consistent Cut，无需精确同步物理时钟。

**对 Catalog 的启示**：Catalog 虽然不是分布式系统，但其"多表独立演化"的特征与分布式系统的"多节点独立更新"同构。因此，Consistent Cut 的概念可以直接迁移到 Catalog-wide temporal snapshot 的定义中。

---

## 3. Catalog-wide Temporal Snapshot 的形式化定义

### 3.1 定义

**Catalog-wide Temporal Snapshot** 是一个三元组 `(C, ccsn, S)`：

- `C`：Catalog 实例（包含一组表 `T = {t₁, t₂, ..., tₙ}`）
- `ccsn`：Catalog Commit Sequence Number（单调递增的整数）
- `S`：状态映射 `S: T → Snapshot`，其中 `S(tᵢ)` 是表 `tᵢ` 在 `ccsn` 时刻的最新 snapshot。此处 Snapshot 指该表对应的**表格式层不可变视图**（如 Iceberg 的 snapshot、Delta 的 version），由 Catalog 层通过 `CommitOp.value()` 指向的 `ContentValueObj` 确定。

**形式化约束（原子可见性条件）**：

> 对于 Catalog 中的任意两张表 `tᵢ, tⱼ`，如果存在 Nessie commit `cₖ`（对应 `ccsn = k`）同时修改了 `tᵢ` 和 `tⱼ`，则：
> - 当查询 `ccsn ≥ k` 时，`S(tᵢ)` 和 `S(tⱼ)` 必须**同时包含** `cₖ` 的完整效果
> - 当查询 `ccsn < k` 时，`S(tᵢ)` 和 `S(tⱼ)` 必须**同时不包含** `cₖ` 的效果
>
该约束是 **L2 事务级一致性** 的形式化表达，也是 **Consistent Cut** 在 Catalog 二元场景（涉及/不涉及某张表）下的特例。

**与 SQL:2011 transaction-time 的类比**：

| 概念 | SQL:2011（单表） | Catalog-wide（本方案） |
|------|-----------------|----------------------|
| 时间维度 | `SYSTEM_TIME`（系统维护） | `CATALOG_TIME`（由 CCSN 定义） |
| 时间单位 | 物理时间戳（可能有歧义） | 逻辑序列号（严格单调） |
| 版本保留 | 单表行级历史 | Catalog 级 commit 历史 |
| 一致性范围 | 单行/单表 | 跨表事务一致 |
| 查询语法 | `FOR SYSTEM_TIME AS OF` | `AT CCSN = n` |

### 3.2 为什么用 CCSN 而非物理时间戳

物理时间戳在 Catalog-wide 场景下存在三个根本问题：

1. **时钟回拨**：NTP 同步、闰秒调整可能导致时间戳非单调
2. **精度不足**：系统时钟精度（毫秒级）不足以区分高并发下的两次 commit
3. **因果与物理时间脱节**：物理时间上后发生的 commit 可能在逻辑上应该先被看到（例如跨时区 ETL 作业）

**CCSN 的优势**：
- 严格单调递增，天然构成全序关系
- 与物理时钟解耦，只反映 Catalog 的因果顺序
- 分配代价极低（单次 `AUTO_INCREMENT` 或 `SEQUENCE` 调用）

---

## 4. 多表时间一致性查询的隔离级别

### 4.1 从单表 Snapshot Isolation 到 Catalog-wide Isolation

Berenson et al. (1995) 定义 Snapshot Isolation 的核心性质：
> **SI-1**：事务读取的是数据库在事务开始时的快照。
> **SI-2**：事务的写集与任何并发事务的写集不相交（First-Committer-Wins）。

扩展到 Catalog-wide 场景，我们提出 **Catalog Snapshot Isolation (CSI)** 的三个层级：

#### 通俗类比

| 级别 | 类比 | 保证 |
|------|------|------|
| **L1 表级** | 会议室里每个人看自己的手表 | 每张表读到某个时间点的状态，但表之间不对齐 |
| **L2 事务级** | 开会前所有人对一次表 | 保证"某次 Catalog commit 涉及的所有表"状态一致 |
| **L3 严格级** | 对表后还确认没人迟到 | 不仅当前一致，所有历史依赖也一致（happens-before 闭包） |

---

#### L1：表级时间一致性（Table-Level Temporal Consistency）

**定义**：查询 `"AT CCSN = c"` 时，每张表 `tᵢ` 返回的状态是 `tᵢ` 在 `ccsn ≤ c` 时的最新 snapshot。每张表独立查询，不保证跨表一致性。

**一个具体的反例**：

假设你想查"2024 年 Q1 结束时 sales namespace 的状态"：

```sql
SELECT * FROM nessie.sales.orders AT TIMESTAMP '2024-03-31T23:59:59Z';
SELECT * FROM nessie.sales.customers AT TIMESTAMP '2024-03-31T23:59:59Z';
```

**L1 下会发生什么：**

| 表 | 物理时间戳映射到的 snapshot | 实际包含的数据 |
|---|---|---|
| `orders` | snapshot-105（创建于 3 月 31 日 23:50）| 截止到 23:50 的订单 |
| `customers` | snapshot-88（创建于 3 月 31 日 22:30）| 截止到 22:30 的客户 |

**问题**：ETL 作业可能在 23:50 更新了 orders，但在 22:30 之后就再也没更新过 customers。你用"同一个时间戳"查了两张表，但读到的不是"同一时刻的业务状态"——orders 里有一笔 23:45 的订单可能指向一个在该时刻尚未存在的客户（因为 customers 表还停留在 22:30）。

**这就是 L1 的核心缺陷：时间戳相同 ≠ 业务状态一致。**

**为什么还需要定义 L1？**

不是所有查询都需要跨表一致性：
1. **单表查询**：`SELECT count(*) FROM orders WHERE ...` 根本不涉及跨表，L1 完全够用
2. **探索性分析**：数据科学家只想看某张表上周的状态，不需要和其他表对齐
3. **向后兼容**：现有 Iceberg `FOR SYSTEM_TIME AS OF` 就是 L1，不能废除

**Nessie 现状**：Nessie 已经具备 L1。
- Nessie Catalog API：`GET /trees/main/contents/sales.orders?atTimestamp=xxx` 返回 Catalog 层在指定时间戳的表指针
- Iceberg REST：`table#timestamp` 语法由 Iceberg 表格式独立解析时间戳到 snapshot

---

#### L2：事务级时间一致性（Transaction-Level Temporal Consistency）

**定义**：查询 `"AT CCSN = c"` 时，对于任意在 `ccsn = k`（`k ≤ c`）的 Nessie commit 中同时修改的两张表，返回的状态必须同时包含（或同时不包含）该 commit 的效果。保证"一次 Catalog commit"的原子可见性，读取者不会看到"部分提交"的中间状态。

**形式化**：对于任意 commit `cₖ`（`k ≤ c`），若 `cₖ.ops = {op₁, op₂, ..., opₘ}` 涉及表集合 `Tₖ ⊆ T`，则：
```
∀ tᵢ, tⱼ ∈ Tₖ:  S(tᵢ) 包含 cₖ ⇔ S(tⱼ) 包含 cₖ
```

**直观例子**：

假设有一个 Nessie commit `abc123`（CCSN = 102，创建于 2024-03-31 23:59:59）同时做了两件事：
- 给 `sales.orders` 加了 100 条新订单记录
- 给 `sales.customers` 更新了 20 个客户的 VIP 等级

**L2 保证**：当你查询 `AT CCSN = 102` 时：
- `orders` 表能看到这 100 条新订单 **且** `customers` 表能看到 20 个 VIP 更新
- 或者两者都看不到（如果你查的是 CCSN = 101 及更早）

**不会出现**：`orders` 已经有新订单了，但 `customers` 的 VIP 等级还没更新——因为这两张表的变更是**同一个 Catalog commit 的原子操作**。

**适用场景**：审计查询、合规报告、ML 实验复现——需要确保读到的状态是"某次 commit 之后的完整状态"。

**Nessie 现状**：部分具备，取决于查询方式。

| 查询方式 | 是否具备 L2 | 原因 |
|----------|------------|------|
| 按 commit hash 查 | ✅ 具备 | 同一个 `CommitObj` 是不可变的，天然原子 |
| 按时间戳通过 Nessie Catalog API 查 | ✅ 具备 | 时间戳先解析为分支的 commit hash，再查表 |
| 通过 Iceberg REST `table#timestamp` 查 | ❌ 不具备 | 每张表独立解析时间戳，可能映射到不同 commit |
| 跨分支查询 | ❌ 不具备 | 两个分支的 commit 历史完全不同 |

**关键缺失**：Nessie 没有全局单调的 CCSN，只有分支内单调的 `seq`。用户无法表达"查 CCSN 102 时的整个 Catalog"，只能说"查 `main` 分支上某个 commit 时的状态"。

---

#### L3：严格时间一致性（Strict Temporal Consistency）

**定义**：查询 `"AT CCSN = c"` 时，返回的状态等价于"在 `ccsn = c` 的 commit 刚刚完成后立即读取 Catalog"所看到的状态。不仅保证原子可见性，还保证所有因果依赖都被满足——如果 commit `c₂` 依赖于 commit `c₁`（例如 `c₂` 修改了 `c₁` 创建的表），则 `c₁` 的效果必须可见。

**形式化（Consistent Cut 条件）**：
```
∀ commits cᵢ, cⱼ:  (cⱼ ∈ Snapshot(c)) ∧ (cᵢ → cⱼ) ⇒ (cᵢ ∈ Snapshot(c))
```
其中 `→` 表示 happens-before 关系（通过 Nessie 的 commit parent 链定义）。

**L2 与 L3 的区别**：

- **L2 保证"横截面完整"**：一次 commit 涉及的所有表齐步走，要么全有要么全无
- **L3 保证"纵截面合法"**：如果看到了"儿子"commit 的效果，就必须能看到"父亲""祖父"……整个因果链的效果

**一个 L3 才管得了的例子**：

假设 `main` 分支上连续发生了以下 commit：

| CCSN | Commit | 操作 |
|------|--------|------|
| 100 | A | 创建表 `orders` |
| 101 | B | 给 `orders` 加 `customer_id` 列 |
| 102 | C | 给 `orders` 加 `amount` 列 |

**L2 的视角**：查询 `AT CCSN = 102` 时，`orders` 表要么有 `amount` 列（看到 C），要么没有（查更早的）。不会出"有 `amount` 但没 `customer_id`"这种中间状态。

**L3 的视角**：查询 `AT CCSN = 102` 时，不仅 C 要完整可见，而且因为 C **依赖于** B（`amount` 列加在已有 `customer_id` 的表上），B 也必须完整可见；同理 B 依赖于 A，A 也必须可见。

换句话说，L3 保证你看到的 Catalog 状态是**一个合法的演化状态**，不存在"表已经存在、列却已经没了"这种违反因果律的情况。

**跨分支场景下 L3 更有意义**：

```
main:    A(CCSN 100) → C(CCSN 102)
                ↘
feature:         B(CCSN 101)
```

- A 创建了表
- B（feature 分支）在 A 的基础上加列
- C（main 分支）在 A 的基础上加索引
- 两个分支尚未 merge

某个全局查询要读取"CCSN 102 时的整个 Catalog 状态"：
- **L2 无法回答**：C 只改了 main 分支的表，B 在 feature 分支上。L2 只保证"一次 commit 内部的原子性"，不涉及跨分支的取舍。
- **L3 给出明确规则**：如果全局快照包含 C（CCSN 102），那么由于 A happens-before C，A 也必须包含；如果全局快照还选择包含 B（CCSN 101），那么 A 同样必须包含（因为 B 也 depends on A）。但不能出现"包含 B 却不包含 A"的状态。

**适用场景**：法律取证、金融审计、根因分析——需要严格证明"在时刻 c，系统状态完整且一致"。

**Nessie 现状**：单分支查询天然具备 L3（因为 commit DAG 的 `tail` 记录了所有祖先），但跨分支/全局查询不具备（因为没有全局序列号，也没有"全局快照"的概念）。

---

### 4.2 与 SQL:2011 隔离级别的映射

| Catalog-wide 隔离级别 | 等价 SQL:2011 概念 | 一致性保证范围 |
|----------------------|-------------------|--------------|
| L1 表级 | `FOR SYSTEM_TIME AS OF`（单表） | 每张表独立 |
| L2 事务级 | 无直接等价（跨表原子可见性） | 一次 Catalog commit 涉及的所有表 |
| L3 严格 | Serializable + Consistent Cut | 所有因果相关的表 |

### 4.3 不满足 L1 的情况

L1 是时间旅行的"地板"——如果连 L1 都做不到，就谈不上 L2 和 L3。以下情况不满足 L1：

| 情况 | 表现 | 典型系统/场景 |
|------|------|--------------|
| **无时间旅行能力** | 不支持按时间戳查历史状态 | 传统 Hive Metastore、无 temporal 扩展的关系数据库 |
| **Metadata GC 导致非确定性** | 旧 snapshot 被清理后，同样的时间旅行查询报错 | Iceberg `expire_snapshots` 后查已过期 snapshot |
| **时间戳映射歧义** | 同一时间戳对应多个 snapshot，选择规则不确定 | 缺乏 deterministic tie-breaking 的自研系统 |
| **并发干扰** | 查询过程中数据被修改，读到半新半旧状态 | 无 MVCC 隔离的早期数据湖实现 |

在 Nessie 语境下，L1 可能失效的边缘场景：
- **Iceberg snapshot 被 expire**：Nessie 的表指针还在，但 Iceberg 无法解析该 snapshot
- **Nessie `CommitObj` 被 GC**：`nessie-gc` 清理旧 `objs` 后，时间戳查询无法回溯
- **查询时间戳早于表创建时间**：行为未定义（可能返回空结果、报错、或返回最新状态）

---

## 5. Catalog-level Time Travel 查询语法设计

### 5.1 设计原则

1. **向后兼容**：新语法不破坏现有 `table#timestamp` 和 `AT TIMESTAMP` 的语义
2. **层级显式**：用户显式选择 L1/L2/L3 一致性级别（默认 L2）
3. **语法简洁**：尽量复用 Nessie 现有的 `AT` 子句模式
4. **与 Iceberg 解耦**：Catalog-level time travel 独立于 Iceberg 表级 time travel

### 5.2 语法草案

#### 基础查询：按 CCSN（推荐）

```sql
-- L2 事务级一致性（默认）：查询 CCSN = 1000 时的完整 Catalog 状态
SELECT * FROM nessie.sales.orders
JOIN nessie.sales.customers
AT CCSN = 1000;

-- 显式指定一致性级别
SELECT * FROM nessie.sales.orders
AT CCSN = 1000 WITH CONSISTENCY LEVEL TRANSACTIONAL;  -- L2（默认）

SELECT * FROM nessie.sales.orders
AT CCSN = 1000 WITH CONSISTENCY LEVEL STRICT;          -- L3
```

> **注意**：当查询只涉及**单张表**时，L1 与 L2 等价（因为单张表的查询天然不涉及跨表一致性问题）。此时 `AT CCSN = 1000` 无论声明为 L1 还是 L2，结果都相同。


#### 兼容查询：按时间戳（回退到 L1）

```sql
-- 按物理时间戳查询（向后兼容，等价于当前 Nessie 语法）
-- 注意：物理时间戳可能对应多个 CCSN，取最近的一个
SELECT * FROM nessie.sales.orders
AT TIMESTAMP '2024-03-31T23:59:59Z';

-- 显式声明只要求表级一致性（L1）
SELECT * FROM nessie.sales.orders
AT TIMESTAMP '2024-03-31T23:59:59Z' WITH CONSISTENCY LEVEL TABLE;
```

#### 相对时间查询

```sql
-- 查询 "3 个 commit 之前" 的状态
SELECT * FROM nessie.sales.orders
AT CCSN ~3 FROM main;  -- main 分支的倒数第 3 个 commit 的 CCSN

-- 查询 "1 小时前" 的状态（映射到最近 CCSN）
SELECT * FROM nessie.sales.orders
AT TIMESTAMP NOW() - INTERVAL '1 HOUR';
```

#### 批量/元数据查询

```sql
-- 查询某个 CCSN 下整个 namespace 的表清单
SHOW TABLES IN nessie.sales AT CCSN = 1000;

-- 查询两张表在 CCSN 1000 和 1050 之间的差异
DIFF nessie.sales.orders BETWEEN CCSN 1000 AND 1050;
```

### 5.3 REST API 设计

在 Nessie REST API v2 中扩展现有端点：

```http
# 获取指定 CCSN 下的表内容（L2 默认）
GET /api/v2/trees/main/contents/sales.orders?atCCSN=1000

# 获取指定 CCSN 下的 namespace 条目列表
GET /api/v2/trees/main/entries?sales&atCCSN=1000&depth=2

# 获取 commit log，返回每条 commit 的 CCSN
GET /api/v2/trees/main/log?atCCSN=1000&limit=50

# Diff 两张表在两个 CCSN 之间的变化
GET /api/v2/trees/main/diff?sales.orders&fromCCSN=1000&toCCSN=1050
```

响应中增加 `ccsn` 字段：

```json
{
  "content": {
    "type": "ICEBERG_TABLE",
    "id": "...",
    "metadataLocation": "..."
  },
  "ccsn": 1000,
  "commitHash": "748586fa...",
  "consistencyLevel": "TRANSACTIONAL"
}
```

---

## 6. 实现路径

### 6.1 第一阶段：CCSN 基础设施（1–2 个月）

**目标**：在 Nessie 核心中引入 CCSN，实现 L2 级查询。

1. **Schema 变更**：
   - `CommitObj` 增加 `long catalogSequenceNumber` 字段
   - `Reference` 元数据增加 `lastAssignedCCSN`（用于快速分配）
   - 向后兼容：旧 commit 的 CCSN = 0，查询时回退到物理时间戳

2. **分配逻辑**：
   ```java
   // CommitLogicImpl.doCommit() 中（新增 allocateNextCCSN 方法）
   long ccsn = persist.allocateNextCCSN();  // 底层数据库原子递增
   commitObjBuilder.catalogSequenceNumber(ccsn);
   ```

3. **查询路由**：
   - `TreeResource.getContent()`、`TreeResource.getEntries()` 增加 `atCCSN` 参数
   - 在 `IndexesLogic` 中，通过 `StoreIndex` 的 commit 链快速定位 `ccsn ≤ target` 的最新 `CommitOp`

### 6.2 第二阶段：一致性级别与语法扩展（2–3 个月）

**目标**：实现 L1/L2/L3 三级一致性，扩展 SQL/REST 语法。

1. **一致性引擎**：
   - `TemporalQueryPlanner`：根据用户指定的 `CONSISTENCY LEVEL` 选择查询策略
   - L1：每张表独立解析（复用现有逻辑）
   - L2：通过 CCSN 统一截断（默认）
   - L3：额外验证 happens-before 闭包（单分支场景下 L2 已隐含 L3；跨分支/全局查询场景下需显式验证 Consistent Cut 条件）

2. **SQL 扩展**：
   - Nessie Spark SQL 扩展中增加 `AT CCSN` 和 `WITH CONSISTENCY LEVEL` 语法
   - 与 Iceberg 的 `FOR SYSTEM_TIME AS OF` 并存，用户按需选择

### 6.3 第三阶段：性能优化与索引（3–6 个月）

**目标**：百万级表场景下 `AT CCSN` 查询 P99 < 100ms。

1. **CCSN 索引**：
   - 在 `StoreIndex<CommitOp>` 中维护 `ccsn → CommitObj ID` 的二级索引
   - 对频繁查询的 CCSN 范围进行预缓存
2. **Lazy Namespace Resolution**：
   - 查询 `SHOW TABLES AT CCSN = c` 时，不加载所有表的完整 metadata，只加载 namespace 结构和表名列表
   - 表级 metadata 按需延迟加载
3. **Diff 加速**：
   - `DIFF ... BETWEEN CCSN` 利用 `CommitLogic` 的 commit 链差异计算，避免全表扫描

---

## 7. 方案评估

| 维度 | 评分 | 理由 |
|------|------|------|
| 可行性 | 7/10 | CCSN 是轻量级概念，Nessie 架构上只需在 CommitObj 中增加一个整数字段；三级一致性级别中 L1/L2 可立即实现，L3 在单分支下由 L2 隐含、跨分支/全局查询需显式验证；与 Iceberg v3 的联动依赖引擎成熟度 |
| 创新价值 | 8/10 | **首次定义 Catalog-wide temporal snapshot 的形式化语义**（类比 SQL:2011 的 transaction-time 但扩展到 Catalog 级别）；将分布式系统的 Consistent Cut 概念引入数据 Catalog 查询，是跨领域迁移创新 |
| 商用价值 | 7/10 | 审计合规（SOX、GDPR）和可复现 ML 是真实痛点，企业愿为 governance 付费；适合作为 Governance Suite 增值功能；L3 严格一致性对金融/医疗行业的审计有刚性需求 |

---

## 8. 建议

- **短期（1–2 个月）**：在 Nessie 的 `CommitObj` 中引入 `catalogSequenceNumber` 字段，实现 `AT CCSN` 基础查询语法（L2 事务级一致性）。
- **中期（3–6 个月）**：扩展 SQL/REST API 支持 L1/L2/L3 三级一致性级别声明，完成性能优化（CCSN 索引、Lazy Loading）。
- **长期（6–12 个月）**：与 Iceberg v3 的 row lineage 结合，实现"行级时间旅行"与"Catalog 级时间旅行"的层次化查询体系；发表技术报告或 CIDR/SIGMOD Workshop 论文，推动 Catalog-wide temporal 语义的行业标准化。

---

## 9. 参考资料

### 学术论文
- **Jensen & Snodgrass**, *Temporal Databases*, VLDBJ 1999/2018 — 时态数据库理论基础
- **Kulkarni & Michels**, *Temporal Features in SQL:2011*, SIGMOD Record 2012 — SQL:2011 时态标准综述
- **Berenson et al.**, *A Critique of ANSI SQL Isolation Levels*, 1995 — Snapshot Isolation 原始定义
- **Lamport**, *Time, Clocks, and the Ordering of Events in a Distributed System*, CACM 1978 — Consistent Cut / Happens-before 理论基础
- **Corbett et al.**, *Spanner: Google's Globally-Distributed Database*, OSDI 2012 — TrueTime 与外部一致性
- **Kulkarni et al.**, *Logical Physical Clocks and Consistent Snapshots in Distributed Systems*, 2014 — Hybrid Logical Clocks
- **irHINT @ SIGMOD 2026** (Rauch & Bouros) — 层次化区间索引
- **LIT+ @ VLDB Journal 2026** (Christodoulou et al.) — 并发更新下的时态索引
- **Dignös et al.**, *Snapshot Semantics for Temporal Multiset Relations*, PVLDB 2019 — Snapshot-reducibility 与 change preservation

### 行业实践
- **Nessie API v2.1.0** — `AT TIMESTAMP` / `AT COMMIT` 语法与相对 hash 查询
- **Iceberg `FOR SYSTEM_TIME AS OF`** — 单表时间旅行实现
- **Halodoc Catalog Level Snapshot** — 应用级跨实体版本快照实践
- **SQL Server Temporal Tables** — 多表 `ValidFrom` 同步与一致性问题

---

*本文档用于深入研讨 Catalog-wide 时间查询的理论定义、隔离级别与语法设计。*
