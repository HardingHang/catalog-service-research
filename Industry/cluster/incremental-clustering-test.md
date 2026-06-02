# 增量聚簇测试报告

**日期**: 2026-05-21  
**分支**: wxl/clustering  
**测试环境**: Linux 6.6.87.2-microsoft-standard-WSL2, Rust debug build  
**聚簇算法**: Hilbert (默认, 2D 列)  
**底层机制**: `Operation::Rewrite`（原子 swap fragment，不 append+delete）

---

## 1. 测试概览

| 类别 | 通过 | 失败 | 说明 |
|------|------|------|------|
| 单元测试 | 6/6 | 0 | cluster_incremental 相关 |
| 全量 cluster 测试 | 64/64 | 0 | 含 stats 持久化回归 |
| Benchmark | 3/3 | 0 | 效率对比、No-op、数据完整性 |

---

## 2. Benchmark 结果

```
10 base fragments (50000/frag):   500000 rows,  10 frags, overlap=0.000
+5 overlapping (50000/frag):     750000 rows,  15 frags, overlap=0.381

  Full cluster:                  750000 rows,    5.35s
  Incremental cluster:           550000 rows,    3.99s
  ─────────────────────────────────────────
  Rows ratio (incr/full):       73.3%
  Clean rows skipped:           200000 (26.7%)
  After incremental:             750000 rows,  15 frags, overlap=0.162
```

**分析**：
- 增量处理 550K 行（11 dirty fragment），跳过 4 个 clean fragment（200K 行）
- **增量 1.34x 快于全量**（3.99s vs 5.35s）
- 单次 Rewrite 提交，无 delete 扫描开销

---

## 3. 单元测试

| 测试 | 场景 | 结果 |
|------|------|------|
| `test_optimize_cluster_incremental_single_fragment` | 单 fragment，no-op | PASS |
| `test_cluster_incremental_noop_when_low_overlap` | overlap < 0.3，no-op | PASS |
| `test_cluster_incremental_reduces_overlap` | full cluster → append → incr fix | PASS |
| `test_cluster_incremental_preserves_data_integrity` | 部分重叠数据，验证无丢失 | PASS |
| `test_cluster_incremental_convergence` | 3 轮 append+incr，overlap 单调递减 | PASS |
| `test_cluster_incremental_mixed_workload` | 5 轮交错操作，数据完整 | PASS |
| `test_frag_stats_persist_and_load` | stats 序列化→持久化→读取→overlap 计算 | PASS |

---

## 4. 结论

| 指标 | 结果 |
|------|------|
| 功能正确性 | 7 增量测试 + 57 回归 = **64/64 通过** |
| 数据完整性 | 750K 行验证无丢失无重复 |
| **增量效率** | **3.99s vs 5.35s（1.34x faster）** |
| dirty ratio 73% | 跳过 200K clean rows (27%) |
| No-op | overlap ≤ 0.3 时 0 rows 处理 |
| Stats 持久化 | cluster 后写入 metadata，后续 quality 零扫描 |
