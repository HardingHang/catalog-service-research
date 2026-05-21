# 多维聚簇键选择 & 增量聚簇策略：业界调研

## 1. 引入

LanceDB 当前已完成多维聚簇算法的实现层（Hilbert、Z-order、XMCK、OTree 等），接下来需要回答两个更"上游"的问题：

1. **聚簇键从哪来？** 用户手动指定？系统自动选择？还是两者结合？
2. **增量数据如何聚簇？** 全量重写的成本随数据增长而线性上升，有没有更经济的增量策略？

本文调研业界在这两个方向上的实践，覆盖 ClickHouse、Snowflake、Databricks (Delta Lake)、Oracle、Apache Iceberg/Dremio、Apache Hudi、Qbeast 等系统。

---

## 2. 多维聚簇键选择

> 聚簇键的选择对布局质量的影响远大于算法本身的选择。

如果选错列，即使 Hilbert 也不会比随机的查询表现好多少。如果选对列，即使简单的 direct sort 也能把查询范围压缩到极少数文件。可以说，**"选什么列做聚簇"是布局优化的第一性问题**。

本节按"自动化程度"对业界系统进行分类。

### 2.1 全手动指定

用户在建表时显式指定聚簇/排序键，系统不做任何干预或推荐。

> **一个具体例子**：假设有一张 10 亿行的订单表 `orders(customer_id, order_date, amount, region, product_id)`，最频繁的查询是 `WHERE customer_id = ? AND order_date BETWEEN ? AND ?`。
>
> - 如果聚簇键选 `(customer_id, order_date)`：同一个客户的所有订单在物理上连续，查询只需读很窄的一段数据，跳过 99%+ 的文件。
> - 如果聚簇键选 `(amount, product_id)`：数据按金额和产品分簇，但查询从来不按这些条件过滤，聚簇形同虚设，每次查询依然全扫。
> - 如果聚簇键选 `(order_date, customer_id)`：数据按日期排序，但 90% 的查询按 customer_id 过滤。由于首列是日期而不是客户，同一个客户的订单会分散在多个文件中，跳读效果大打折扣。
>
> 这个例子说明两个要点：(1) 选什么列比选什么算法重要得多；(2) 列的顺序几乎和列的选择同等重要。

#### 2.1.1 ClickHouse

ClickHouse 的 `ORDER BY` 既是物理排序键也是稀疏索引的构建依据，必须由用户在 DDL 中显式指定。社区总结的选键经验非常成熟：

**列选择原则（按优先级）**：
1. 选 WHERE / GROUP BY / JOIN 中最高频的列
2. 低基数列在前，高基数列在后（保证压缩效率和索引粒度）
3. 通常 3-5 列足够，避免索引膨胀
4. 层级关系的列按"根→叶"排列（如 `continent, country, city`）

**列顺序原则**：
- 第一列最关键——对数据裁剪影响最大
- 低基数在前→数据连续存储，压缩比高
- 高基数在后→用于最终精确排序和去重

```sql
-- 典型实践
CREATE TABLE events (
    ...
) ENGINE = MergeTree()
ORDER BY (tenant_id, site_id, user_id, timestamp)
PRIMARY KEY (tenant_id, site_id);  -- 前缀即可
```

**验证手段**：`EXPLAIN indexes=1` 确认查询是否使用稀疏索引。

**键变更成本**：ClickHouse 的 `ORDER BY` 在建表时确定，**无法直接修改**。要变更排序键必须重建表（`CREATE TABLE ... AS SELECT ... ORDER BY new_cols`），期间需要全量重写数据，对大型表面言代价极高。这也是手动指定模式的通用短板——选错的沉没成本很大。

**优点**：用户完全可控，经验可迁移，无黑盒行为。
**缺点**：完全依赖用户经验，选错代价高（需重建表），无自适应能力。

#### 2.1.2 当前 LanceDB

LanceDB 的当前模式与 ClickHouse 一样——**纯手动**：

```python
db.create_table("my_table", data,
    cluster_by=["col1", "col2"],       # 用户指定
    cluster_algorithm="hilbert"         # 用户选择
)
```

聚簇键在创建表时写入 schema metadata (`lancedb.cluster.config`)，之后**不可变更**。算法方面做了 1D→direct、2-4D→hilbert 的自动选择，但**列的自动选择完全没有**。

**键变更成本**：当前版本不支持聚簇键变更。由于聚簇配置存储在 schema metadata 中且创建后不可修改，用户若想更换聚簇键，只能**新建一张表**（指定新的 `cluster_by`），然后将旧表数据导入新表并执行 `cluster()`。这个流程等价于全量重写，且需要迁移所有下游引用，在实际生产中几乎不可行。

---

### 2.2 自动选择

系统根据数据特征或查询负载自动决定聚簇键。但需要注意的是，**"自动选择"和"自动变更"是两层不同的能力**：

- **自动选择**：系统在初始建表或用户触发时，自动推荐/决定聚簇键，不需要用户手动指定
- **自动变更**：系统在运行过程中，持续监测并自动更换聚簇键，无需用户介入

目前业界只有 Databricks 同时具备这两层能力，其余系统要么只做初始自动选择，要么只推荐不自动应用。

**冷启动问题**：自动选择面临一个共同的困境——新建空表时既没有数据，也没有查询负载，选择依据为空。各系统对此的处理方式有本质差异，直接影响新表的初始行为。

#### 2.2.1 Databricks: CLUSTER BY AUTO + 预测优化 (Predictive Optimization)

这是目前自动化程度最高的方案。Databricks Runtime 15.4+ 引入 `CLUSTER BY AUTO`：

```sql
CREATE TABLE my_table CLUSTER BY AUTO;

ALTER TABLE my_table CLUSTER BY AUTO;  -- 也可对已有表启用
```

**工作原理**：

| 环节 | 机制 |
|------|------|
| **键选择** | Delta Lake 分析表的数据分布和历史查询模式，自动选择最优的 1-4 个聚簇列 |
| **自适应重选** | 当数据分布或查询模式变化时，自动重新评估并更换聚簇键 |
| **增量执行** | 键变更后，新写入立即使用新键；旧数据通过后台 `OPTIMIZE` 逐步迁移 |
| **预测优化** | 使用服务端无服务器计算，自动判断何时需要执行 `OPTIMIZE` / `VACUUM` / `ANALYZE` |

背后的技术是 Databricks 2025 年专利中的 **transformer-based 键选择模型**（US Patent 20250156448A1，授权号 US 12,632,474 B2）。

**专利方案的核心流程**：

1. **特征提取**：从表 schema 中为每列提取特征——列名文本（如 `customer_id` 会被拆分为 `customer` 和 `id` 两个子词 token）、数据类型（int / string / timestamp 等编码为独立 token）、基数估计、是否 nullable 等，组装成数值向量。

2. **Transformer 推理**：将所有列的向量同时输入一个 BERT-like Transformer 模型。Transformer 的自注意力机制让它同时"看到"所有列，理解列间的上下文关系——比如 `user_id` 和 `timestamp` 同时出现时，模型会倾向于给两者高分，因为训练数据中这类组合高频出现在聚簇键里。

3. **逐列打分**：模型对每列输出一个 0~1 的概率值，表示该列适合做聚簇键的置信度。训练数据来自 Databricks 内部大量已聚簇表——标签就是各表实际使用的聚簇键，损失函数聚焦于每列的第一个 token。

4. **选 Top-4**：按得分排序，选 1-4 列输出。选几列取决于得分差距和内部阈值。

**专利 vs 产品**：专利文档为了突出新颖性，侧重描述了"从 schema 特征推断聚簇键"的部分（schema-only 路径以前没人做过）。但 Databricks 实际产品中，Predictive Optimization 还会额外收集查询负载（哪些列出现在 WHERE / JOIN 中、过滤频率等）作为模型的补充输入——schema + 负载两路信息共同决定最终得分。

**三个组件如何协作**：

```
CLUSTER BY AUTO  ← 用户看到的语法
    │
    ├── Transformer 模型  →  决定 WHAT：选哪几列做聚簇键
    ├── Liquid Clustering  →  决定 HOW：用 Hilbert+ZCube 组织物理文件
    └── Predictive Optimization →  决定 WHEN：何时触发，并收集负载喂给模型
```

Transformer 模型不是独立运行的——它被 Predictive Optimization 调用，作为 PO 内部的评分函数。PO 将 schema 特征和查询负载两路信息喂给模型，拿到每列的得分后，再结合成本评估决定是否应用。

**冷启动行为**：新建空表时负载侧为空，模型只能看 schema，得分不可靠。PO 判断"信息不足，暂不选键"——所以新表初始阶段不聚簇，数据以自然写入顺序存储。当表积累了一定的查询负载后，PO 重新调用模型，此时两路信息齐全，得分才有意义。

**是否自动变更**：会。PO 持续观测查询负载，周期性重新调用模型打分。当发现更优的列组合且预估收益大于迁移成本时，在下一次 `OPTIMIZE` 中自动切换。用户也可以通过 `OPTIMIZE FULL` 强制触发重评估。

**键变更成本**：极低。键切换是零拷贝的——新写入立即使用新键，旧数据由后台 `OPTIMIZE` 逐步按新键重组，无需全量重写，也不阻塞读写。这是目前业界键变更成本最低的方案。

**优点**：零运维，自动适应，减少人工决策负担。
**缺点**：仅限 Unity Catalog 托管表，需要 Premium 计划，自动选择未必对齐领域知识。

#### 2.2.2 Oracle: Auto Clustering Recommendation (SIGMOD 2024)

Oracle 在 2024 年 SIGMOD 发表了首个 **自动化聚簇推荐** 论文，并在 Oracle 23ai 中实现为 `DBMS_AUTO_CLUSTERING` 包：

```sql
-- 自动推荐聚簇方案
SELECT * FROM TABLE(DBMS_AUTO_CLUSTERING.RECOMMEND_CLUSTERING_METHOD('my_table'));

-- 验证推荐
SELECT * FROM TABLE(DBMS_AUTO_CLUSTERING.VERIFY_RECOMMENDATION(...));

-- 应用推荐
EXEC DBMS_AUTO_CLUSTERING.APPLY_RECOMMENDATION(...);
```

**推荐引擎的核心思路**：

1. **抓取 SQL 工作负载**：从 AWR (Automatic Workload Repository) 分析历史查询
2. **评估候选列组合**：对每个候选聚簇列组合，估算其减少的 I/O
3. **考虑 Zone Map 配合**：同步推荐 Zone Map 的创建位置，最大化聚簇+数据跳过的协同效果
4. **支持两种聚簇**：线性聚簇（排序）和 interleaved 聚簇（Z-order）

这是**基于工作量分析**的推荐，而不是基于数据分布——它关心的是"查询会怎么用这些列"，而非"这些列在数据上长什么样"。

**冷启动行为**：Oracle 依赖 AWR 中的历史查询数据来生成推荐，因此对于一个**没有查询负载的新表，`RECOMMEND_CLUSTERING_METHOD` 无法产出有意义的推荐**。这意味着 Oracle 的自动推荐本质上是面向"已有一定运行历史的表"的优化工具，而非建表时的决策辅助。新建表时用户仍然需要依靠经验手动选择聚簇键，待运行一段时间后再用推荐来验证或调整。

**是否自动变更**：不会。Oracle 的流程是推荐→用户验证→用户手动应用（`APPLY_RECOMMENDATION`），**不会在后台自动更改聚簇键**。系统只负责生成推荐方案，最终变更决策和执行仍由 DBA 控制。推荐可以随时通过 `RECOMMEND_CLUSTERING_METHOD` 重新生成（例如在负载变化后），但每次应用都需要人工确认。

**键变更成本**：中等。通过 `DBMS_AUTO_CLUSTERING.APPLY_RECOMMENDATION` 应用新聚簇方案后，Oracle 需要对表执行一次**全量聚簇重建**来匹配新的聚簇键（因为聚簇键定义变更意味着物理布局需要从头对齐）。不过推荐→验证→应用的三步流程降低了"选错"的概率，减少了反复变更的可能。

**优点**：生产级成熟度，基于实际负载而非静态数据特征。
**缺点**：依赖 Oracle 生态，方法论虽有参考价值但实现和 AWR 深度绑定。

#### 2.2.3 Qbeast: ColumnsToIndexSelector (基于相关性)

Qbeast 从 v0.6.0 (2024) 起引入了自动列选择器 `SparkColumnsToIndexSelector`：

```shell
--conf spark.qbeast.index.columnsToIndex.auto=true \
--conf spark.qbeast.index.columnsToIndex.auto.max=10
```

**选择策略**：

1. 将 Timestamp 列转为 Unix 时间戳
2. 将 String 列通过 `StringIndexer` 数值化
3. 组装每列的特征向量
4. 计算**列之间的相关性矩阵**
5. 选择**平均相关性最低的 Top N 列**作为索引列

核心理念：**相关性越低的列，在多维空间中提供的正交维度越多，索引效果越好**。如果两列高度相关（如 `age` 和 `birth_year`），同时索引它们带来的增量收益有限。

这是一个**纯数据驱动的自动选择**策略，完全不依赖查询负载。简单、确定性高，但也可能选择查询中不常用的列。

**冷启动行为**：与其他系统不同，Qbeast 的列选择发生在**首次写入时**（而非建表时），因此它有真实数据可以分析——选择器对首批写入的行计算列间相关性，然后据此构建 OTree 索引。但问题在于：第一批数据可能不具代表性（例如只覆盖了部分值域），而且完全没有查询负载信息，可能选出"数据上正交但查询中没人用"的列。

**是否自动变更**：不会。`columnsToIndex.auto=true` 只在**索引首次构建时**（建表或首次写入）自动选择列，之后索引列的配置就固定了，不会在运行过程中因数据分布变化而自动更换。如果后续需要换列，必须手动触发索引重建。

**键变更成本**：中等偏高。Qbeast 的索引列在写入时定义（通过 `columnsToIndex` 参数），变更索引列需要**重建整个 OTree 索引**——因为 cube 的空间划分依赖于索引列维度的数量。变更索引列需要**全量重读和重写**所有数据（按新维度重新计算 cube 归属），本质上是一次全量重建。自动列选择器 (`columnsToIndex.auto=true`) 可以在一定程度上避免手工选错后反复重建的问题。

**优点**：简单可解释，不依赖查询负载，开箱即用。
**缺点**：忽略了查询侧信息，可能选出"数据上好"但"查询上没用"的列。

---

### 2.3 混合模式

系统提供辅助工具帮助用户做决策，但最终选择权在用户手中。目前业界最典型的代表是 Snowflake。

#### 2.3.1 Snowflake：手动选择 + 系统验证

Snowflake 的模式可以概括为"人选键，系统验证，系统执行"。整个流程分三步：

**第一步：用户手动指定聚簇键**

```sql
CREATE TABLE orders CLUSTER BY (customer_id, order_date);
-- 或后续指定
ALTER TABLE orders CLUSTER BY (customer_id, order_date);
```

Snowflake 使用**线性排序**（字典序），列顺序决定物理排序层级——先按 `customer_id` 排，相同 `customer_id` 内再按 `order_date` 排。因此首列的选择至关重要。

**第二步：用量化指标验证选择效果**

这是 Snowflake 区别于纯手动系统的关键能力。在建表或变更聚簇键**之前**，用户可以先用 `SYSTEM$CLUSTERING_INFORMATION` 评估候选列在当前物理存储上的聚簇质量：

```sql
SELECT SYSTEM$CLUSTERING_INFORMATION('orders', '(customer_id, order_date)');
```

返回的关键指标：

| 指标 | 含义 | 怎么判断 |
|------|------|---------|
| `average_depth` | 数据在任意值点被多少个 micro-partition 覆盖 | 越接近 1 越好；接近 `total_partition_count` 说明完全未聚簇 |
| `average_overlaps` | 每个 micro-partition 与其他 micro-partition 的值域重叠度 | 越低越好 |
| `partition_depth_histogram` | depth 的分布直方图 | 看是否有长尾 |

**`average_depth` 怎么算的**：每个 micro-partition 在聚簇键上有一个 min/max 范围。把所有 micro-partition 的范围画在数轴上，然后用扫描线从左扫到右——在数轴上的每一点，数一数有多少个 micro-partition 的范围覆盖了这一点，取整条数轴上的平均值。

```
micro-partition A: [0 ───── 40]
micro-partition B:       [20 ───── 60]
micro-partition C:             [35 ── 50]

数轴: 0──────────20─────────35──40──50───60
深度:     1          2          3     2     1

average_depth = (1×20 + 2×15 + 3×5 + 2×10 + 1×10) / 60 = 1.58
```

如果三个 micro-partition 完全按顺序排列不重叠（A: 0-20, B: 20-40, C: 40-60），depth 处处为 1，`average_depth = 1`。如果三者范围完全相同（都覆盖 0-60），depth 处处为 3，`average_depth = 3`。

**举个例子**：一张 1000 个 micro-partition 的表，对 `(customer_id, order_date)` 运行 `CLUSTERING_INFORMATION`，如果 `average_depth = 950`（接近 1000），说明数据在这些列上完全随机分布——启用聚簇将带来巨大收益。如果 `average_depth = 3.2`，说明数据已经相当有序，聚簇收益有限。

**第三步：Auto Clustering 负责执行**

用户一旦定义了聚簇键，Snowflake 的 Auto Clustering 服务就开始在后台工作——它用的是在 3.3 节详述的分层增量策略。用户不需要（也不能）干预具体何时、如何聚簇。

**选键经验**：
- 首列选**基数最低**且**最常出现在 WHERE 中**的列——首列决定了最粗粒度的数据分组
- 高基数列可以用表达式降低有效基数：`CLUSTER BY (TRUNC(customer_id, -2))` 把 customer_id 每 100 个归为一组
- 表不够大（< 1 TB）不建议启用聚簇——micro-partition 的自动 pruning 已经够用，聚簇的额外成本不划算

**键变更成本**：低。`ALTER TABLE ... CLUSTER BY (new_cols)` 在线变更，不锁表。变更后 Auto Clustering 在后台逐步将数据按新键重新聚簇，是一个渐进收敛的过程。也可以通过 `ALTER TABLE ... DROP CLUSTERING KEY` 完全移除。

**优点**：决策权在用户，系统提供量化验证工具，灰度友好。
**缺点**：仍需人工判断，`CLUSTERING_INFORMATION` 只反映当前状态而非未来效果。

> 注：Apache Hudi 的键选择本质上也是纯手动模式（通过 `sort.columns` 参数指定），只是它同时支持 Z-order 和 Hilbert 两种多列排序策略。因为没有任何自动化推荐或验证工具，它更接近 2.1 中的手动系，此处不再单独展开。

---

### 2.4 对比总结

| 系统 | 键选择模式 | 原理 | 是否自动变更键 | 变更时机 | 键变更成本 |
|------|-----------|------|--------------|---------|-----------|
| **ClickHouse** | 纯手动 | 用户依据 EXPLAIN 和基数分析手动选 ORDER BY 列 | 否 | — | 需重建表 |
| **LanceDB (当前)** | 纯手动 | 建表时手动指定 cluster_by，写入 schema metadata 后不可改 | 否 | — | 不可变更 |
| **Snowflake** | 手动 + 系统验证 | 手动指定 CLUSTER BY，用 CLUSTERING_INFORMATION 验证效果 | 否 | — | 可 ALTER 在线变更 |
| **Hudi** | 手动 | 通过写入参数 sort.columns 指定，支持 Z-order/Hilbert 多列 | 否 | — | 改参数，下次 clustering 生效 |
| **Qbeast** | 自动选择（可选） | 首次写入时计算列间相关性，选最正交的列作为索引维度 | 否 | 仅首次构建时选择，之后固定 | 需全量重建索引 |
| **Oracle** | 自动推荐 + 人工确认 | 分析 AWR 历史 SQL 负载，估算候选列的 I/O 收益后推荐 | 否（推荐自动，应用手动） | 用户手动触发 APPLY_RECOMMENDATION | 需全量重建 |
| **Databricks** | 全自动 | PO 调用 Transformer 模型，综合 schema + 查询负载两路输入为每列打分 | 会 | PO 周期性重新打分，收益>成本时触发 | 零拷贝，新写用新键，旧数据逐步迁移 |

**关键发现**：

1. **纯手动是当前主流，但自动化是大趋势**（Databricks、Oracle 都在向 zero-touch 方向演进）
2. **工作负载驱动 > 数据驱动**：仅从数据分布出发选列是不够的（Qbeast 的方法），更优的方案是 Oracle/Databricks 那样分析实际查询模式
3. **可变更的聚簇键是刚需**：LanceDB 当前聚簇键不可变更的限制将越来越成为瓶颈
4. **验证手段是手动模式的必要补充**：Snowflake 的 `CLUSTERING_INFORMATION` 是一个很好的参考——在真正执行昂贵的重聚簇之前，让用户知道"这组列到底效果如何"

---

## 3. 增量数据聚簇策略

> 全量重写聚簇的成本与数据量成正比。当表增长到 TB 甚至 PB 级时，每次写入都重排全表是不可接受的。

这就引出了增量聚簇的核心问题：**如何以远低于全量重写的成本，将新数据的聚簇质量维持在可接受的水平？**

下面每种方案都从三个维度来审视：**用什么算法**（底层排序/分块机制）、**初始结构怎么定**（第一条数据进来时如何布局）、**增量怎么维护**（后续数据如何融入已有布局）。

### 3.1 全量重写（当前主流 base case）

**代表**：当前 LanceDB、早期 Hudi、手动 Spark/Iceberg 重写。

```python
# LanceDB 当前模式
table.cluster()  # 全表重写，类似 OPTIMIZE FULL
```

执行流程：
1. 读全表数据
2. 按聚簇算法全局排序或分块
3. 重写所有文件到**新文件**（不是原地修改旧文件）
4. 重建所有索引
5. **原子替换旧版本**：新文件写完后，在 manifest 中把"当前版本"的指针从旧文件切换到新文件。这一操作是原子的——要么全部生效，要么全部不生效，不会出现"一半新一半旧"的中间态。旧文件在替换后不再被引用，后续由 compaction 或 vacuum 清理。

**关于读写阻塞**：全量重写期间，LanceDB 的读操作仍然可以访问旧版本的数据（因为旧文件还在）。写操作则视实现而定：如果在 manifest 层面加了写锁，新写入会等待聚簇完成；如果允许乐观并发，新写入会基于旧版本继续追加，但可能和聚簇产生冲突需要重试。

**优点**：最简单，布局质量最优（全局排序无碎片），适合小表或低频重写。
**缺点**：O(N) 成本随数据量线性增长，对于持续写入的生产表不可持续。

**增量模式 `cluster(full=False)` 已在接口层预留，但当前返回 `NotSupported`**。

---

### 3.2 Liquid Clustering（Delta Lake，GA 2024）

Liquid Clustering 是目前增量聚簇领域最完整的实现方案，也是**这次调研最重要的参考对象**。

#### 底层算法：Hilbert Curve + ZCube

Liquid Clustering 底层使用 **Hilbert 曲线**将多维聚簇键映射为 1D Hilbert 索引，这点和传统的 Hilbert 聚簇相同。区别在于它不直接在全局按 Hilbert 索引排序全表数据，而是引入了一个中间层——**ZCube**。

**ZCube 是什么**：一个 ZCube 是 Hilbert 曲线上**一段连续区间**对应的数据逻辑分组，目标大小约 **100 GB**。每个 ZCube 包含若干 Parquet 文件，文件内的行按 Hilbert 索引排序，同一 ZCube 内的数据在聚簇键上取值相近。

**ZCube 元数据持久化**：每个 ZCube 的 ID 范围、min/max 统计、包含的文件列表、是否为"脏"（有新数据写入）的状态，都被记录在 Delta 事务日志中。这是实现增量的关键——系统知道哪些 ZCube 需要重写，哪些已经聚簇良好。

**为什么比纯 Hilbert 更适合增量**：纯 Hilbert 全局排序后，新写入的任意一行都可能打破全局顺序，导致大量文件"变脏"。而 ZCube 将 Hilbert 曲线分段，新数据只影响落在其 Hilbert 区间内的那个 ZCube，其他 ZCube 保持不变。增量的粒度从"全表"降低到"个别 ZCube"。

**ZCube 怎么划分**：ZCube **不是**按值域范围预先定义的——它是 OPTIMIZE 执行时**按数据量动态切分**的结果。流程如下：

```
1. 读所有"脏"文件的数据行
       ↓
2. 每行计算 Hilbert 索引值
       ↓
3. 全局按 Hilbert 索引排序
       ↓
4. 在排序后的数据流上按累计大小切分：
   当累计到 ~100 GB 时切一刀，形成一个 ZCube
       ↓
5. 每个 ZCube 内部再切成 ~1 GB 的 Parquet 文件
       ↓
6. 在 Delta 日志中记录每个 ZCube 的 ID 和 Hilbert 范围
```

关键是第 4 步：ZCube 的边界是**由数据量决定的**，不是由聚簇键的取值区间决定的。如果某段 Hilbert 区间数据稠密，可能出现多个 ZCube 覆盖相近的值域；如果某段稀疏，一个 ZCube 就覆盖很大范围。

#### 初始结构

新建表时，数据按**自然写入顺序**存储。当写入数据量达到阈值（64 MB–4 GB，取决于聚簇键数量）后，系统开始计算 Hilbert 索引并分配到对应 ZCube。如果使用 `CLUSTER BY AUTO`，初始阶段甚至不确定聚簇键，数据完全按插入顺序存放，直到积累足够的查询负载后才开始选择键并建立 ZCube 结构。

#### 写时聚簇（Write-time）

```sql
-- 写入时自动聚簇到对应的 ZCube
INSERT INTO my_table SELECT ...;  -- 自动, 无需额外操作
```

触发条件：写入数据量达到阈值（64 MB–4 GB，取决于聚簇键数量）。

支持的操作：`INSERT INTO`, `CTAS`, `RTAS`, `COPY INTO`, `spark.write.mode("append")`。

**新数据具体怎么写**（到达阈值时）：

1. 计算每行新数据的 Hilbert 索引
2. 根据 Hilbert 值判断每行应该落入哪个已有的 ZCube
3. 在目标 ZCube 内**新建 Parquet 文件**写入该批数据（不修改已有文件）
4. 标记该 ZCube 为"脏"——下次 `OPTIMIZE` 会合并此 ZCube 内的新旧文件

如果写入量低于阈值，数据直接以**追加新文件**的方式写入（不做 Hilbert 路由），文件被标记为"无 ZCube"，等待后续 `OPTIMIZE` 统一处理。

**新数据不会直接写入已聚簇完成的文件**——Parquet 文件是不可变的，已经写好的文件永远不会被原地修改。聚簇时创建的是新文件，旧文件在替换后被清理。

这使得新数据在写入时就被安置到合适的文件位置，而不是等到后续重写。

#### 写后优化（Post-write OPTIMIZE）

```sql
OPTIMIZE my_table;           -- 增量: 只重写"漂移"的文件
OPTIMIZE my_table FULL;      -- 全量: 重写所有文件（等价于传统的全表聚簇）
OPTIMIZE my_table FULL
  WHERE date > '2025-01-01'; -- 部分: 只重写指定范围的文件 (DBR 18.1+)
```

**"增量"的含义**：`OPTIMIZE` 从 Delta 日志中读取每个 ZCube 的状态。对于标记为"脏"的 ZCube（有新数据写入导致其数据在 Hilbert 曲线上不连续或有重叠），只重写该 ZCube 内的文件；对于已处于聚簇良好状态的 ZCube，直接跳过。

#### 流式写入支持

```python
# Structured Streaming + Liquid Clustering
(spark.readStream
    .option("spark.databricks.delta.liquid.eagerClustering.streaming.enabled", "true")
    ...
)
```

流式作业每 5 个微批次触发一次聚簇，避免微批次过于频繁触发重写。

#### 可变更的聚簇键

```sql
ALTER TABLE my_table CLUSTER BY (new_col1, new_col2);
```

变更是**零拷贝**的：新写入直接使用新键，旧数据通过后续 `OPTIMIZE` 逐步按新键重组。

#### 对并发读写的影响

当 ZCube#2 正在被 OPTIMIZE 重写时（file_C, file_D → 新文件 file_F, file_G），并发操作的行为很简单：

**读**：始终看到 OPTIMIZE 之前的旧文件（file_C, file_D）。因为 OPTIMIZE 创建的是新文件，提交前旧文件仍是当前快照的一部分。查询不受任何影响。

**写到其他 ZCube**：正常提交。比如向 ZCube#1 INSERT 一行（写 file_H），file_H 和 OPTIMIZE 的 file_F/file_G 是不同文件，互不干扰。

**写到同一 ZCube**：提交时发生乐观锁冲突，后提交的一方自动重试。比如 ZCube#2 正在被重写时，另一个 INSERT 也往 ZCube#2 写入了 file_I——INSERT 先提交成功，OPTIMIZE 提交时发现 ZCube#2 的文件列表已经变了（多了 file_I），重试整个 OPTIMIZE 过程。

所以结论就一句话：**不管写还是读，都不会被阻塞等锁。但要往正在被聚簇的 ZCube 里写，会触发重试。**

#### 已知限制

- 2025 年 PyCon DE 有报告指出在特定增量处理场景下 Liquid Clustering 可能出现**严重性能退化**（尤其是需要维护逻辑会话边界的场景）
- 不支持流式表 / 物化视图
- **不完全解耦**：即使 Liquid Clustering 重写了数据，可能仍然会不必要地重写更多文件？重写的粒度主要基于文件组的"纯度评估"——它会跳过纯度达标的文件，但如果某组内有少数文件纯度差，可能整组都会被重写。

---

### 3.3 Snowflake Auto Clustering（后台增量）

Snowflake 的 Auto Clustering 采用了一种**LSM-tree-like 的分层增量策略**。

#### 底层算法

Snowflake 使用**线性排序**（按聚簇键的字典序），而非 Z-order 或 Hilbert。它只支持单列或多列的线性排序——列顺序决定了物理排序层级（先按 col1 排，再按 col2 排，依此类推）。这也是为什么 Snowflake 的选键文档强调"首列最关键"。

#### micro-partition 是什么

Snowflake 的最小存储单元是 **micro-partition**（微分区），不是传统数据库中"按某列值划分"的逻辑分区。它是一个**物理存储块**，特点：

- 大小在 50–500 MB（未压缩）之间，列式存储
- **不可变**：写入后不能修改，UPDATE/DELETE 会创建新的 micro-partition
- 每个 micro-partition 自动收集 per-column 的 min/max、distinct count 等元数据
- 查询时通过 min/max 做 partition pruning，自动跳过不相关的 micro-partition

可以把 micro-partition 理解为"自带统计信息的不可变数据文件块"，在概念上更接近 LanceDB 的 fragment 或 Parquet 文件，而非 MySQL 的 partition。

#### 初始结构

新表的数据以**自然写入顺序**存入 micro-partition，此时没有任何聚簇——不同 micro-partition 在聚簇键上的值范围可能高度重叠。只有当用户显式定义 `CLUSTER BY` 并启用 Auto Clustering 后，系统才开始从 Level 0 逐步重组这些 micro-partition。

#### 架构

数据组织为多个层级（level），层级越高代表该数据被聚簇的次数越多：

```
Level 0: 新写入的 micro-partitions (未或轻度聚簇)
Level 1: 经过一次聚簇的 micro-partitions
Level 2: 经过两次聚簇的 micro-partitions
...
```

#### 工作流程

1. **Partition-Selection Job**：
   - 优先选择低层级（较新数据）和高 `average_depth`（重叠严重）的 micro-partitions
   - 将重叠的 micro-partitions 分组为固定大小的批次

2. **Clustering-Execution Job**：
   - 对每个批次按聚簇键**线性排序**并重写
   - 重写后的 micro-partitions 升级到下一个层级
   - 使用乐观锁：如果并发 DML 修改了任何原 micro-partition，该批次回滚重试

3. **写放大控制**：
   - 最高层级的 micro-partitions 不会被再次聚簇，除非表增长到触发更高层级的阈值
   - 这确保了每个 micro-partition 被重写的次数有理论上限

#### 关键特性

- **后台、非阻塞**：使用 Snowflake 自有计算资源，不占用用户 warehouse
- **全自动**：启用后无需任何人工干预
- **计量计费**：按聚簇消耗的 credit 单独计费
- **可暂停/恢复**：`ALTER TABLE ... SUSPEND/RESUME RECLUSTER`

**对并发读写的影响**：Auto Clustering **不阻塞**读写。新写入的数据以自然顺序写入 Level 0 的新 micro-partition；读操作持续访问当前版本的 micro-partition；聚簇重写操作创建新 micro-partition 并原子切换。使用**乐观锁**处理并发 DML 冲突——如果聚簇批次中的某个 micro-partition 被并发 DML 修改，该批次回滚并在下一轮重试。

---

### 3.4 Dremio / Apache Iceberg：迭代式增量聚簇

Dremio 在 Iceberg 上实现的聚簇采用了一种基于 **Clustering Depth** 指标的迭代策略。

#### 底层算法

使用 **Z-order 空间填充曲线**，将多列值通过位交错映射为 1D Z-order 值，再按 Z-order 排序数据。

#### 初始结构与新数据写入

初始数据按写入的自然顺序存储，文件之间在 Z-order 取值范围上高度重叠。新数据以**自然顺序直接追加新文件**，写入时不做任何 Z-order 计算或排序——所有聚簇工作留给后续的 `OPTIMIZE`（手动触发或调度执行）。用户或调度器手动触发 `OPTIMIZE` 后开始第一轮迭代，从完全不聚簇的状态逐步收敛。

**对并发读写的影响**：Dremio 的实现中，`OPTIMIZE` 创建新文件并通过 Iceberg 的快照机制切换，读操作不阻塞。写操作与 `OPTIMIZE` 的并发取决于 Iceberg 的乐观并发控制——如果 `OPTIMIZE` 先提交，并发写入可能需要基于新快照重试。

#### Clustering Depth：为什么一个 Z-order 点被多个文件覆盖

这是理解"聚簇质量"的关键概念。Z-order 将多个维度的坐标压缩成 1D 值——这是一个**有损映射**：原始多维空间中不同的点可能映射到相同或相近的 Z-order 值，而多维空间中邻近的点映射后也可能被拉远。

当数据按写入顺序（而非全局 Z-order）存入多个文件时，每个文件会包含各种不同 Z-order 值的数据行。因此在 Z-order 轴上的任意一点，可能有 N 个文件都包含落在该点附近的数据——这个 N 就是该点的 **clustering depth**。

```
Z-order 轴:  |---文件A---|
                 |---文件B---|
          |---文件C---|
                  ^
            该点被 A、B、C 三个文件覆盖，depth = 3
```

- **depth = 1**：完美聚簇，每个 Z-order 区间最多一个文件
- **depth = 文件总数**：完全未聚簇，查询需要扫描所有文件

增量写入会加剧 depth 的增长：每次新写入的数据文件通常覆盖较宽的 Z-order 范围，与已有文件大量重叠。迭代聚簇的目的就是通过反复合并重叠文件，将 depth 压回目标值。

#### 迭代过程

1. 计算当前表的 Z-order 范围和每个文件的范围
2. 识别**重叠最多的文件组**
3. 选择可管理大小的文件集合（受 `max-files-per-iteration` 和 `max-size-per-iteration` 约束）
4. 重写这些文件（按 Z-order 排序合并）
5. 重新计算 depth
6. 如果不满足目标 depth，继续下一轮迭代

**收敛特征**：
- 早期迭代 depth 下降显著（几十 → 个位数）
- 后期收敛到接近个位数时速度明显放缓
- 用户可以设定目标 depth 作为终止条件

#### 与 Snowflake 的对比

| 维度 | Snowflake | Dremio/Iceberg |
|------|-----------|----------------|
| **粒度** | micro-partition 级 | 文件级 |
| **排序策略** | 全局排序 | Z-order 排序合并 |
| **可观测性** | `SYSTEM$CLUSTERING_DEPTH` | Clustering Depth 指标 |
| **触发方式** | 后台自动 | 手动或调度触发 |
| **开销与加速** | 递增积分；加速稳定 | 前期最快，后期收敛减速 |

---

### 3.5 Apache Hudi：分区过滤 + 聚簇策略

#### 底层算法

Hudi 支持三种聚簇排序策略：**线性排序**（默认，按排序列字典序）、**Z-order** 和 **Hilbert**，由 `hoodie.clustering.layout.optimize.strategy` 控制。这意味着用户可以在增量聚类的同时选择使用空间填充曲线来改善多列过滤场景下的数据局部性。

#### 初始结构与新数据写入

Hudi 的聚簇是通过写时参数指定的，不是建表 DDL。新建表时如果没有配置 clustering，数据按写入的自然顺序存储。新数据始终以自然顺序写入新文件，**写入时不做任何聚簇计算**。首次配置 clustering 参数后，由异步 clustering 任务（可 inline 或 schedule 执行）在后台读取未聚簇文件、排序重写。后续 clustering 可以按分区过滤增量执行。

**对并发读写的影响**：Hudi 的 clustering 是**异步表服务**，通过 `REPLACE` 提交操作来替换旧文件组，读取始终访问当前快照。写入（upsert/insert）与 clustering 并发执行——Hudi 使用乐观并发控制，如果 clustering 先提交，并发写入方需要基于新快照重试。Hudi 1.2.0 路线图中计划引入**非阻塞聚类**（聚类期间不阻塞 upsert/delete）。

#### 增量方式

```scala
// 增量模式：只对最近 7 天的分区做聚簇
.option("hoodie.clustering.plan.partition.filter.mode", "RECENT_DAYS")
.option("hoodie.clustering.plan.strategy.daybased.lookback.partitions", "7")

// 滚动模式：每天轮转处理不同的分区
.option("hoodie.clustering.plan.partition.filter.mode", "DAY_ROLLING")

// 或指定具体分区范围
.option("hoodie.clustering.plan.partition.filter.mode", "SELECTED_PARTITIONS")
.option("hoodie.clustering.plan.strategy.cluster.begin.partition", "2025-01-01")
.option("hoodie.clustering.plan.strategy.cluster.end.partition", "2025-01-31")
```

**本质**：并非真正的"增量聚簇算法"，而是"在全量聚簇算法上叠加了分区过滤"——只对需要重聚簇的分区执行，跳过稳定的冷分区。

Hudi 2026 路线图中计划引入：
- **非阻塞写入时的聚簇**（聚类期间不阻塞 upsert/delete）
- **监督式表服务规划**（自动判断何时需要聚簇）

---

### 3.6 Qbeast OTree：写入时自适应布局

#### 底层算法

使用 **OTree 算法**——将多维空间递归切分为 cubes（超立方体），而非依靠 Z-order 或 Hilbert 的空间填充曲线。每个 cube 有唯一的 ID 编码其在多维空间中的位置，数据写入时路由到对应 cube。

#### 初始结构

首次写入时，所有数据进入**根 cube**（包含整个数据空间）。当根 cube 数据量超过 `desiredCubeSize` 阈值后，自动分裂为 2^n 个子 cube（n = 索引列数）。后续子 cube 继续按相同规则分裂。初始完全没有排序步骤——cube 分裂是自适应且局部的。

#### 增量特点

#### 写入时自动分 cube

1. 每条数据写入时，根据其多维坐标计算所属的 cube
2. 如果 cube 的数据量超过阈值（`desiredCubeSize`，默认约 10 万行），cube 自动分裂为 2^n 个子 cube
3. 数据直接写入对应的子 cube 文件中

#### 增量场景的行为

- **新数据写入**：自动路由到正确的 cube，与已有 cube 的数据保持空间局部性
- **数据分布变化**：如果某些 cube 持续接收新数据而膨胀，它们会在下次写入时自动分裂
- **查询性能**：查询时通过 cube 级别的 min/max 统计做跳读

**优点**：
- 不需要后台聚簇任务—写入时自动完成
- 立方体级别并行性很好（不同分支互不影响）
- 自适应数据分布变化

**对并发读写的影响**：OTree 没有独立的 "OPTIMIZE" 操作——聚簇内嵌在写入路径中。写入时只修改目标 cube 的文件，不影响其他 cube，因此天然支持高并发写入。读写之间通过文件级别的快照隔离（Delta Lake 或 Iceberg 的事务机制）保证一致性。

**缺点**：
- 可能产生很多小文件（需要额外 compaction 作业合并）
- cube 的分裂决策只考虑了数据量而非查询负载
- 如果长时间写入导致cube总数膨胀太大，Weight 文件和 Revisions 机制的控制能力有待明确

---

### 3.7 对比总结

| 策略 | 代表系统 | 底层算法 | 新数据怎么写入 | OPTIMIZE 阻塞读写？ | 写入成本 | 读取质量 | 运维复杂度 | 成熟度 |
|------|---------|---------|-------------|:---:|---------|---------|-----------|--------|
| **全量重写** | LanceDB(当前) | Hilbert / Z-order / XMCK / OTree | 追加新 fragment，不排序 | 读不阻塞；写视锁策略 | O(N) per run | 最优 | 低（但不可持续） | 成熟 |
| **写时聚簇** | Delta Lake Liquid | Hilbert + ZCube 分段 | 计算 Hilbert 索引，路由到对应 ZCube 新建文件 | 否 | O(log N) per write | 良好 | 低（透明） | GA |
| **后台分层增量** | Snowflake | 线性排序（字典序） | 自然顺序写入新 micro-partition，写入时不排序 | 否 | 分摊到每次写入 | 良好（渐进收敛） | 低（全自动） | 成熟 |
| **迭代重叠合并** | Dremio/Iceberg | Z-order | 自然顺序写入新文件，写入时不排序 | Dremio 不阻塞；Iceberg Spark 看配置 | 可控（有上限） | 良好（渐进收敛） | 中（需调度） | 生产可用 |
| **分区级增量** | Hudi | 线性 / Z-order / Hilbert 可选 | 自然顺序写入，配置 clustering 参数后异步触发 | 异步 clustering 不阻塞 | 仅改写目标分区 | 分区内良好 | 中（需配置） | 成熟 |
| **写入时自适应** | Qbeast OTree | OTree (cube 分裂) | 计算多维坐标，路由到对应 cube 文件，超阈值触发 cube 分裂 | 不涉及 | O(log N) per write | 良好 | 低（透明） | 早期 |

> **关于 "OPTIMIZE 阻塞读写吗" 的通用答案**：大多数现代系统采用"写新文件 + 原子切换"的模式，因此**读操作通常不阻塞**——旧文件在切换前依然可读。写操作的阻塞情况取决于并发控制机制：Snowflake 用乐观锁、Databricks 用行级并发、Hudi 用异步表服务，都能做到不阻塞。只有手动全量重写在加写锁的情况下才会阻塞新写入。

---

## 4. 业界实践中的关键设计决策

### 4.1 聚簇质量度量

各系统都定义了可观测的聚簇质量指标，这对增量策略至关重要（需要知道"当前有多差"才能决定"要不要重写"）：

| 系统 | 指标 | 含义 |
|------|------|------|
| **Snowflake** | `clustering_depth` | 在聚簇键值域上任意点平均被多少个 micro-partition 覆盖。用扫描线法对每个 micro-partition 的 min/max 范围加权计算（见 2.3.1 详解） |
| **Snowflake** | `overlaps` | 每个 micro-partition 平均与多少个其他 micro-partition 的值域有交集 |
| **Dremio/Iceberg** | `clustering_depth` | 在 Z-order 轴上同样用扫描线法计算——每点的 depth = 覆盖该 Z-order 值的文件数。原理与 Snowflake 相同，只是数轴从聚簇键换成了 Z-order 值 |
| **Delta Lake** | ZCube 脏/净状态 | Delta 日志中记录每个 ZCube 是否需要重聚簇，`OPTIMIZE` 只处理"脏" ZCube |

### 4.2 写放大控制

增量聚簇的本质是用"多次小重写"替代"一次大重写"，因此**写放大控制**是核心设计考量：

- **Snowflake**：层级上限机制，一个 partition 最多被重写有限次数（到达最高层后停止），这也是分层最精巧的设计之一
- **Dremio**：每次迭代的文件数和总大小都有上限
- **Delta Lake**：只重写"漂移"的文件，跳过良好聚簇的文件
- **Hudi**：通过分区过滤限制范围，配合 `hoodie.clustering.plan.strategy.max.num.groups` 控制单次重写规模

### 4.3 聚簇键的演化性

数据使用模式会随时间变化。聚簇键能否**低成本变更**是一个关键评估维度：

| 系统 | 键变更方式 | 旧数据处理 |
|------|-----------|-----------|
| **LanceDB (当前)** | 不支持 | — |
| **ClickHouse** | 重建表 | 全量重写 |
| **Snowflake** | `ALTER TABLE ... CLUSTER BY (...)` | 后台逐步重聚簇 |
| **Delta Lake** | `ALTER TABLE ... CLUSTER BY (...)` | 新写入用新键，旧数据逐步重聚簇 |
| **Qbeast OTree** | 重建索引 | OTree 自适应调整 |

---

## 5. 对 LanceDB 的启示与建议

基于以上调研，以下是针对 LanceDB 后续发展的方向性建议。本节属于高层方向探讨，具体的实现计划和设计文档需要另行讨论。

### 5.1 聚簇键选择

**短期（可落地）**：
- 参考 Snowflake 模式：保持建表时手动指定作为主路径，但增加**聚簇质量评估函数**（类似 `SYSTEM$CLUSTERING_INFORMATION`），让用户在真正执行重聚簇前能评估候选列的效果
- 支持**聚簇键变更**（暂不自动迁移旧数据，只影响新写入）

**中期**：
- 参考 Qbeast 的思路，提供**数据驱动的自动推荐**：基于列间相关性 + 基数分析 + 值分布，给用户一个推荐列组合
- 允许用户在未指定聚簇键时自动选择一个合理的默认值

**远期**：
- 参考 Oracle / Databricks 的思路，如果未来有查询负载统计，可以进一步引入**工作负载驱动的推荐**

### 5.2 增量聚簇

**关键前提**：LanceDB 底层 Lance 的存储模型是 fragment-based（一组文件），每个 fragment 带有 per-column min/max 统计信息。这天然适合做增量聚簇——可以按 fragment 粒度评估"聚簇纯度"。

**短期（可落地）**：
- 参考 Hudi 的**分区级增量**思路：如果表有时间维度的列，允许用户指定只对部分数据做聚簇（例如最近 7 天的 fragment）。这不改变聚簇算法本身，只是在输入数据上加一层过滤。
- 为 `cluster(full=False)` 实现第一个可用版本：基于 fragment 级 min/max 统计信息，识别重叠严重的 fragment 组，选择性重写。

**中期**：
- 参考 Dremio 的**迭代式聚簇**：引入类似 Clustering Depth 的评估指标，每次重写只处理重叠最严重的少量 fragment，迭代收敛到目标质量。

**远期**：
- 参考 Delta Lake Liquid Clustering 或 Qbeast OTree 的思路，实现**写入时路由**——新数据在写入时就根据聚簇键路由到合适的 fragment，减少或消除写入后的聚簇需求。
- 配合 OTree 算法的 cube 结构，这自然支持写入时路由：新数据根据多维坐标找到目标 cube，超阈值时自动分裂。

### 5.3 注意事项

1. **聚簇质量度量必须先于增量策略**：增量策略需要知道"哪些 fragment 需要重写"，这依赖于可靠的聚簇质量评估。否则容易陷入"重写了很多但质量提升有限"或"该重写的反而没重写到"。

2. **LanceDB 的 fragment 不可变性**既是挑战也是优势：挑战在于 fragment 写完后不能修改，重写意味着创建新 fragment 并更新 manifest；优势在于这天然支持 MVCC 和 rollback。

3. **索引重建是隐性成本**：当前全量聚簇结束后会重建所有索引。增量模式下如何维护索引（每个新 fragment 独立建索引 vs 延迟合并重建）需要认真设计，否则增量聚簇节省的 I/O 可能被索引维护成本部分抵消。

---

## 6. 参考文献

- Databricks. *Liquid Clustering*. https://learn.microsoft.com/en-us/azure/databricks/delta/clustering
- Databricks. *Predictive Optimization for Unity Catalog*. https://learn.microsoft.com/en-us/azure/databricks/optimizations/predictive-optimization
- Snowflake Engineering Blog. *Automatic Clustering at Snowflake*. https://staging.snowflake.com/en/engineering-blog/automatic-clustering-at-snowflake/
- Oracle. *Automated Clustering Recommendation With Database Zone Maps*. SIGMOD 2024. https://dl.acm.org/doi/10.1145/3626246.3653397
- Qbeast. *OTree Algorithm*. https://docs.qbeast.io/qbeast-spark/latest/otree-algorithm
- Qbeast. *Columns To Index Selector*. https://docs.qbeast.io/qbeast-spark/latest/columns-to-index-selector
- Dremio. *Apache Iceberg Clustering Technical Blog*. https://www.dremio.com/blog/dremios-apache-iceberg-clustering-technical-blog/
- Apache Hudi. *Z-Order and Hilbert Space Filling Curves*. https://hudi.apache.org/blog/2021/12/29/hudi-zorder-and-hilbert-space-filling-curves/
- Apache Hudi. *Clustering RFC*. https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=173083619
- ClickHouse. *Choosing a Primary Key*. https://clickhouse.com/docs/best-practices/choosing-a-primary-key
- Databricks (Patent). *Clustering Key Selection Based on Machine-Learned Key Selection Models*. US 20250156448A1, 2025.
- Vanlightly, Jack. *Have your Iceberg Cubed, Not Sorted: Meet Qbeast, the OTree Spatial Index*. 2025. https://jack-vanlightly.com/blog/2025/11/19/have-your-iceberg-cubed-not-sorted-meet-qbeast-the-otree-spatial-index
