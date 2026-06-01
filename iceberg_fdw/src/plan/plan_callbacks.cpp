/* -------------------------------------------------------------------------
 *
 * plan_callbacks.cpp
 *      规划三回调（doc 3 §5.2-5.4）：GetForeignRelSize / GetForeignPaths /
 *      GetForeignPlan。
 *
 * 本版（Phase 1）：行数/代价给固定估算；GetForeignPlan 写死 1~2 个 mock
 * FileScanTask 并编码进 fdw_private（不接 Catalog）。Phase 2 把写死的
 * FileScanTask 换成 IcebergCatalogResolveTable 的解析结果。
 *
 * 模板来源：openGauss contrib/file_fdw/file_fdw.cpp。
 *
 * -------------------------------------------------------------------------
 */
#include "postgres.h"
#include "knl/knl_variable.h"

#include "nodes/makefuncs.h"
#include "nodes/pg_list.h"
#include "optimizer/cost.h"
#include "optimizer/pathnode.h"
#include "optimizer/planmain.h"
#include "optimizer/restrictinfo.h"

#include "iceberg_fdw.h"
#include "catalog/iceberg_catalog.h"

/*
 * icebergGetForeignRelSize
 *      填 baserel->rows。骨架阶段给固定估算（doc 3 §5.2）；谓词分类暂全归
 *      local（不下推），正确性优先。Phase 2 可顺带轻量 Catalog 查询估行。
 */
void icebergGetForeignRelSize(PlannerInfo* root, RelOptInfo* baserel, Oid foreigntableid)
{
    baserel->rows = ICEBERG_MOCK_ROWS;
}

/*
 * icebergGetForeignPaths
 *      生成唯一一条全表扫描 ForeignPath（doc 3 §5.3）。
 *      参数化/有序路径本版不做。
 */
void icebergGetForeignPaths(PlannerInfo* root, RelOptInfo* baserel, Oid foreigntableid)
{
    Cost startup_cost = 0;
    Cost total_cost = baserel->rows; /* 骨架阶段：占位代价 */

    add_path(root,
        baserel,
        (Path*)create_foreignscan_path(root,
            baserel,
            startup_cost,
            total_cost,
            NIL,    /* no pathkeys */
            NULL,   /* no required_outer */
            NULL,   /* no fdw_outerpath */
            NIL));  /* no path-level fdw_private */
}

/*
 * icebergGetForeignPlan（上半层重点，doc 3 §5.4）
 *      固化计划节点；本版写死 mock FileScanTask 列表并编码 fdw_private。
 *      注意 openGauss 签名含 Plan* outer_plan，且 make_foreignscan 的
 *      fdw_private 是第 5 参（与社区 PG 不同）。
 */
ForeignScan* icebergGetForeignPlan(PlannerInfo* root, RelOptInfo* baserel, Oid foreigntableid,
    ForeignPath* best_path, List* tlist, List* scan_clauses, Plan* outer_plan)
{
    Index scan_relid = baserel->relid;
    List* fileScanTasks = NIL;
    List* retrievedAttrs = NIL;
    List* fdw_private = NIL;
    AttrNumber attno;
    char* ns = NULL;
    char* tbl = NULL;
    IcebergCatalog cat;
    IcebergResolved* resolved = NULL;

    /* 无原生谓词求值能力：剥掉 RestrictInfo，全部交执行器重查（同 file_fdw） */
    scan_clauses = extract_actual_clauses(scan_clauses, false);

    /*
     * Phase 2：从 OPTIONS 取 namespace.table，经 Catalog 解析得 metadata_location。
     * 表不存在时 IcebergCatalogResolveTable 在此（规划期）报明确错误。
     * 当前 fileScanTasks 只承载解析到的 metadata 源；真实数据文件列表
     * （PlanFiles 解析 metadata.json）属下半层，下一版接入。
     */
    IcebergGetTableIdentity(foreigntableid, &ns, &tbl, &cat);
    resolved = IcebergCatalogResolveTable(&cat, ns, tbl);
    fileScanTasks = lappend(fileScanTasks, makeString(resolved->metadataLocation));

    /* 投影列：本版取全列（属性号 1..max_attr）。Phase 2 接真实投影裁剪 */
    for (attno = 1; attno <= baserel->max_attr; attno++)
        retrievedAttrs = lappend_int(retrievedAttrs, attno);

    /* 编码 fdw_private（residualQuals / fieldIdMap 本版留空） */
    fdw_private = IcebergBuildScanPrivate(fileScanTasks, retrievedAttrs, NIL, NIL);

    return make_foreignscan(tlist,
        scan_clauses,
        scan_relid,
        NIL,         /* fdw_exprs：无 */
        fdw_private, /* 第 5 参 */
        NIL,         /* fdw_scan_tlist */
        NIL,         /* fdw_recheck_quals */
        outer_plan,
        EXEC_ON_DATANODES);
}
