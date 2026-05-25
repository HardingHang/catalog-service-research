# 增量聚簇：设计文档

## 1. 概述

基于 [业界调研](./incremental-clustering-research.md)，实现的增量聚簇方案。

**核心思路**：用 `Operation::Rewrite` 在 manifest 中原子替换脏 fragment，不 append+delete。干净 fragment 完全不动——不读取、不重写、不扫描。

---

## 2. 实现：Phase 1 — 聚簇质量度量 + 统计持久化

### 2.1 动机

增量聚簇需要知道"哪些 fragment 重叠严重"，这依赖可靠的评估指标。Lance 的 fragment 元数据只含 `physical_rows` 和文件路径，没有 per-column min/max 统计。

### 2.2 实现

**指标定义**：overlap_ratio = fragment 值域 bounding box 在多维聚簇键空间上交叉的 pair 数 / 总 pair 数 C(N,2)。越低越好。

**计算示例**（4 个 fragment，聚簇键列为 x, y）：

```
frag 0:  x=[0, 100],   y=[0, 100]
frag 1:  x=[50, 150],  y=[50, 150]
frag 2:  x=[200, 300], y=[200, 300]
frag 3:  x=[250, 350], y=[250, 350]
```

判定规则：两个 fragment 在**所有维度**上都满足 `min_a ≤ max_b AND min_b ≤ max_a` 才算重叠。

| pair | x 交叉? | y 交叉? | 结果 |
|------|---------|---------|------|
| (0,1) | 0≤150 ∧ 50≤100 ✓ | 0≤150 ∧ 50≤100 ✓ | **重叠** |
| (0,2) | 0≤300 ∧ 200≤100 ✗ | — | 不重叠 |
| (0,3) | 0≤350 ∧ 250≤100 ✗ | — | 不重叠 |
| (1,2) | 50≤300 ∧ 200≤150 ✗ | — | 不重叠 |
| (1,3) | 50≤350 ∧ 250≤150 ✗ | — | 不重叠 |
| (2,3) | 200≤350 ∧ 250≤300 ✓ | 200≤350 ∧ 250≤300 ✓ | **重叠** |

overlapping_pairs = 2，total_pairs = C(4,2) = 6，**overlap_ratio = 2/6 ≈ 0.333**。

**统计收集**：首次 `clustering_quality()` 调用扫描每个 fragment 的聚簇键列计算 min/max。聚簇操作（full / incremental）结束后将 stats 持久化到 `schema.metadata["lancedb.cluster.frag_stats"]`。

**后续调用**：`clustering_quality()` 优先读 schema metadata 缓存，只扫描没有缓存的新 fragment。

### 2.3 API

```rust
// Rust
table.clustering_quality().await?   // → ClusteringQuality { overlap_ratio, fragment_ranges, ... }
```

```python
# Python
quality = await table.clustering_quality()
# → {"overlap_ratio": 0.38, "num_fragments": 15, ...}
```

### 2.4 关键文件

- `rust/lancedb/src/table/cluster/quality.rs` — 统计收集、序列化、overlap 计算

---

## 3. 实现：Phase 2 — Rewrite 增量聚簇

### 3.1 为什么不用 append+delete

Lance 的 fragment 不可变。``WriteMode::Append` 追加新 fragment 但旧的还在，必须 delete 去重。但 `Dataset::delete()` 内部 scanner 扫全表——即使只需要删几个 fragment，也要过一遍所有数据。增量路径扫全表三次（quality + dirty read + delete），比全量一次还多。

### 3.2 Rewrite 方案

Lance 已有 `Operation::Rewrite`（compaction 内部在用），可以直接在 manifest 里把旧 fragment 替换成新的，干净 fragment 原样保留。

```
cluster(full=False) 流程：

1. clustering_quality()  → 从 metadata 读缓存，只扫描新 fragment
2. select_dirtiest_fragments()  → 按 overlap 次数排序，取前 50 个
3. 单次 scanner 读所有脏 fragment 数据
4. 排序（与全量 cluster 同样的算法）
5. InsertBuilder::execute_uncommitted_stream()  → 写排序后数据到磁盘，不 commit
6. Dataset::commit(Operation::Rewrite { old: dirty, new: sorted })
   → 一次原子提交，manifest 中干净 fragment 不动，脏的被替换
7. 持久化新 fragment 的 stats 到 schema metadata
```

### 3.3 核心代码路径

```
execute_cluster_incremental()
  ├── quality::compute_overlap_ratio()
  ├── select_dirtiest_fragments()
  ├── Scanner::with_fragments(dirty) → 单次扫描所有脏数据
  ├── sort_batch_by_algorithm()
  ├── InsertBuilder::execute_uncommitted_stream() → 写新文件
  ├── Dataset::commit(Rewrite) → 原子替换
  └── quality::persist_frag_stats() → 更新缓存
```

### 3.4 API

```rust
// Rust
table.optimize(OptimizeAction::Cluster { full: false, target_rows_per_fragment: Some(50000) }).await?;
```

```python
# Python
await table.cluster(full=False, target_rows_per_fragment=50000)
```

```typescript
// TypeScript
await table.cluster({ full: false, targetRowsPerFragment: 50000 });
```

`full=False` 时：
- overlap_ratio ≤ 0.3 → no-op，返回 `rows_processed=0`
- 单 fragment → no-op
- 否则选中重叠最多的 ≤50 个脏 fragment，Rewrite 替换

### 3.5 关键文件

- `rust/lancedb/src/table/cluster/execute.rs` — `execute_cluster_incremental()`
- `rust/lancedb/src/table/optimize.rs` — 分流 `full`/`!full`

---

## 4. 关键设计决策

### 4.1 阈值选择

`OVERLAP_THRESHOLD = 0.3`，`MAX_DIRTY_FRAGMENTS = 50`。

阈值基于实验：overlap < 0.1 时数据已接近完美聚簇，0.3 是"值得重写"的界线。单次最多处理 50 个脏 fragment，控制重写规模。

### 4.2 Stats 持久化时机

在 Rewrite commit 之后持久化，此时新 fragment 的 ID 已确定。commit 之前持久化会导致 ID 不匹配。

### 4.3 为什么保留 clustering_quality() 扫描作为 fallback

`add()` 操作不自动持久化 stats——否则每次写入多一次 commit。所以 stats 持久化只在 cluster 操作中触发。`clustering_quality()` 优先读缓存，fallback 扫描未缓存的 fragment。

---

## 5. 已知限制

1. **dirty ratio 高时边际收益递减**：当前 benchmark 73% dirty，增量比全量快 34%。dirty ratio 越低收益越大。

2. **fragment 粒度**：overlap 基于 fragment 级 bounding box，不精确反映实际数据页级别的重叠。一个 fragment 内只要 min/max 交叉就算重叠，即使实际数据行不重叠。

3. **仅 local 表支持**：remote 表 `clustering_quality()` 和 `full=False` 均返回 `NotSupported`。

---

## 6. 测试覆盖

参见测试报告。

```bash
cargo test --features remote -p lancedb --lib -- cluster_incremental  # 7 个单元测试
cargo run --features remote --example incremental_cluster_bench -- --large  # 大规模 benchmark
```
