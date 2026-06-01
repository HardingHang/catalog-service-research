/* -------------------------------------------------------------------------
 *
 * scan_state.h
 *      IcebergScanState：行式 + 向量化共用的扫描态（doc 3 §6.2）。
 *
 * 精简自 pg_lake PgLakeScanState：去掉 DuckDB 连接与文本回填字段，
 * 预留 SDK reader 句柄与批缓存游标。本版 reader 为桩，下半层接 Arrow。
 *
 * -------------------------------------------------------------------------
 */
#ifndef ICEBERG_SCAN_STATE_H
#define ICEBERG_SCAN_STATE_H

#include "postgres.h"
#include "access/htup.h"
#include "nodes/pg_list.h"
#include "utils/memutils.h"
#include "utils/rel.h"

typedef struct IcebergScanState {
    Relation rel;
    TupleDesc tupdesc;
    List* fileScanTasks;  /* 解码自 fdw_private（IcebergPrivFileScanTasks） */
    List* retrievedAttrs; /* 投影列属性号 */
    int* fieldIdMap;      /* field_id → 属性号（本版 NULL，schema evolution 用） */

    void* reader; /* SDK Parquet reader 句柄（骨架阶段为桩） */

    /* 批缓存游标（行式，doc 2 §4.2.2 控制流） */
    HeapTuple* tuples;
    int num_tuples;
    int next_tuple;
    bool eof_reached;

    MemoryContext batch_cxt; /* 每批 reset 一次性回收 */
    MemoryContext temp_cxt;
} IcebergScanState;

#endif /* ICEBERG_SCAN_STATE_H */
