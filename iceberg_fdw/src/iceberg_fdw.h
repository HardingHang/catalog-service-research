/* -------------------------------------------------------------------------
 *
 * iceberg_fdw.h
 *      Iceberg FDW 上半层共享声明：回调原型、fdw_private 编码索引、骨架常量。
 *
 * 设计依据：doc 3 §2.3（注册）、§5（各回调）、§6.1（fdw_private 编码）。
 * 本版（Phase 1）执行阶段返回桩数据，规划阶段写死 mock FileScanTask。
 *
 * -------------------------------------------------------------------------
 */
#ifndef ICEBERG_FDW_H
#define ICEBERG_FDW_H

#include "postgres.h"
#include "foreign/fdwapi.h"
#include "nodes/relation.h"
#include "nodes/execnodes.h"
#include "nodes/plannodes.h"
#include "vecexecutor/vecnodes.h"

/* 骨架阶段：mock 扫描返回的行数（doc 3 §5.6/§5.7，验收里的 N） */
#define ICEBERG_MOCK_ROWS 3

/*
 * fdw_private 编码索引（doc 3 §6.1）。规划→执行的桥，沿用 pg_lake
 * FdwScanPrivateIndex 模式，但把 DuckDB SQL 项替换为 FileScanTask 列表。
 */
enum IcebergScanPrivateIndex {
    IcebergPrivFileScanTasks,  /* FileScanTask 列表：文件路径 + 投影 field_id + 残差谓词 */
    IcebergPrivRetrievedAttrs, /* 投影列属性号 List<int> */
    IcebergPrivResidualQuals,  /* 执行期重过滤的 local 谓词 */
    IcebergPrivFieldIdMap,     /* field_id → 外表属性号映射（schema evolution 对齐） */
    IcebergPrivNum             /* 计数哨兵 */
};

/* ---- 规划三回调（plan/plan_callbacks.cpp，doc 3 §5.2-5.4） ---- */
extern void icebergGetForeignRelSize(PlannerInfo* root, RelOptInfo* baserel, Oid foreigntableid);
extern void icebergGetForeignPaths(PlannerInfo* root, RelOptInfo* baserel, Oid foreigntableid);
extern ForeignScan* icebergGetForeignPlan(PlannerInfo* root, RelOptInfo* baserel, Oid foreigntableid,
    ForeignPath* best_path, List* tlist, List* scan_clauses, Plan* outer_plan);

/* ---- fdw_private 编解码（plan/fdw_private.cpp，doc 3 §6.1） ---- */
extern List* IcebergBuildScanPrivate(List* fileScanTasks, List* retrievedAttrs, List* residualQuals, List* fieldIdMap);

/* ---- 行式扫描回调（exec/scan_callbacks.cpp，doc 3 §5.5-5.8） ---- */
extern void icebergBeginForeignScan(ForeignScanState* node, int eflags);
extern TupleTableSlot* icebergIterateForeignScan(ForeignScanState* node);
extern void icebergReScanForeignScan(ForeignScanState* node);
extern void icebergEndForeignScan(ForeignScanState* node);

/* ---- 向量化扫描回调（exec/vec_scan_callbacks.cpp，doc 3 §5.7） ---- */
extern VectorBatch* icebergVecIterateForeignScan(VecForeignScanState* node);

/* ---- EXPLAIN / DDL 校验（iceberg_fdw.cpp / ddl/validate_table_def.cpp） ---- */
extern void icebergExplainForeignScan(ForeignScanState* node, struct ExplainState* es);
extern void icebergValidateTableDef(Node* obj);

#endif /* ICEBERG_FDW_H */
