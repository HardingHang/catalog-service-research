/* -------------------------------------------------------------------------
 *
 * scan_callbacks.cpp
 *      行式扫描生命周期（doc 3 §5.5-5.8）：Begin / Iterate / ReScan / End。
 *
 * 本版（Phase 1）：reader 为桩；fetch_more_data 按外表 tupdesc 造 N 行
 * 常量 mock tuple，验证「批缓存 + 逐行吐 slot」控制流（doc 2 §4.2.2）与
 * EOF / 内存生命周期。下半层把 fetch_more_data 换成
 * IcebergReaderNextBatch → ArrowColumnToDatums（doc 2 §6.3）。
 *
 * -------------------------------------------------------------------------
 */
#include "postgres.h"
#include "knl/knl_variable.h"

#include "access/htup.h"
#include "catalog/pg_type.h"
#include "executor/executor.h"
#include "executor/tuptable.h"
#include "nodes/pg_list.h"
#include "storage/buf/buf.h"
#include "utils/builtins.h"
#include "utils/memutils.h"
#include "utils/rel.h"

#include "iceberg_fdw.h"
#include "exec/scan_state.h"

/*
 * mock_datum_for_attr
 *      为一个外表列造常量 mock 值（骨架阶段）。覆盖验收常见类型，
 *      其余类型置 NULL。下半层由 ArrowColumnToDatums 取代。
 */
static Datum mock_datum_for_attr(Form_pg_attribute attr, int rowidx, bool* isnull)
{
    *isnull = false;

    if (attr->attisdropped) {
        *isnull = true;
        return (Datum)0;
    }

    switch (attr->atttypid) {
        case INT2OID:
            return Int16GetDatum((int16)(rowidx + 1));
        case INT4OID:
            return Int32GetDatum(rowidx + 1);
        case INT8OID:
            return Int64GetDatum((int64)(rowidx + 1));
        case FLOAT4OID:
            return Float4GetDatum((float4)(rowidx + 1));
        case FLOAT8OID:
            return Float8GetDatum((float8)(rowidx + 1));
        case BOOLOID:
            return BoolGetDatum((rowidx % 2) == 0);
        case TEXTOID:
        case VARCHAROID:
        case BPCHAROID:
            return CStringGetTextDatum("mock");
        default:
            *isnull = true;
            return (Datum)0;
    }
}

/*
 * fetch_more_data
 *      取下一批。骨架阶段只产出单批 N 行 mock tuple；产出后置
 *      eof_reached，下一次取批返回空（num_tuples=0）即 EOF。
 */
static void fetch_more_data(IcebergScanState* festate)
{
    MemoryContext oldcxt;

    /* 回收上一批，整批一次性释放（doc 3 §6.2 batch_cxt 语义） */
    MemoryContextReset(festate->batch_cxt);
    oldcxt = MemoryContextSwitchTo(festate->batch_cxt);

    if (festate->eof_reached) {
        festate->num_tuples = 0;
    } else {
        int n = ICEBERG_MOCK_ROWS;
        int natts = festate->tupdesc->natts;
        Datum* values = (Datum*)palloc(natts * sizeof(Datum));
        bool* isnull = (bool*)palloc(natts * sizeof(bool));

        festate->tuples = (HeapTuple*)palloc(n * sizeof(HeapTuple));
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < natts; c++)
                values[c] = mock_datum_for_attr(&festate->tupdesc->attrs[c], r, &isnull[c]);
            festate->tuples[r] = heap_form_tuple(festate->tupdesc, values, isnull);
        }
        festate->num_tuples = n;
        festate->eof_reached = true; /* 单批 mock */
    }

    festate->next_tuple = 0;
    (void)MemoryContextSwitchTo(oldcxt);
}

/*
 * icebergBeginForeignScan
 *      解码 fdw_private；建 batch_cxt/temp_cxt；初始化批游标。
 *      EXPLAIN(no ANALYZE) 时不开资源，fdw_state 保持 NULL。
 */
void icebergBeginForeignScan(ForeignScanState* node, int eflags)
{
    ForeignScan* plan = NULL;
    List* fdw_private = NIL;
    IcebergScanState* festate = NULL;

    if (eflags & EXEC_FLAG_EXPLAIN_ONLY)
        return;

    festate = (IcebergScanState*)palloc0(sizeof(IcebergScanState));
    festate->rel = node->ss.ss_currentRelation;
    festate->tupdesc = RelationGetDescr(festate->rel);

    /* 解码规划期编码的 fdw_private（doc 3 §6.1） */
    plan = (ForeignScan*)node->ss.ps.plan;
    fdw_private = plan->fdw_private;
    festate->fileScanTasks = (List*)list_nth(fdw_private, IcebergPrivFileScanTasks);
    festate->retrievedAttrs = (List*)list_nth(fdw_private, IcebergPrivRetrievedAttrs);
    festate->fieldIdMap = NULL; /* 本版不用 */

    festate->reader = NULL; /* 桩 reader */
    festate->tuples = NULL;
    festate->num_tuples = 0;
    festate->next_tuple = 0;
    festate->eof_reached = false;

    festate->batch_cxt = AllocSetContextCreate(CurrentMemoryContext,
        "iceberg_fdw batch context",
        ALLOCSET_DEFAULT_MINSIZE,
        ALLOCSET_DEFAULT_INITSIZE,
        ALLOCSET_DEFAULT_MAXSIZE);
    festate->temp_cxt = AllocSetContextCreate(CurrentMemoryContext,
        "iceberg_fdw temp context",
        ALLOCSET_DEFAULT_MINSIZE,
        ALLOCSET_DEFAULT_INITSIZE,
        ALLOCSET_DEFAULT_MAXSIZE);

    node->fdw_state = (void*)festate;
}

/*
 * icebergIterateForeignScan
 *      「批缓存 + 逐行吐 slot」：批内 next_tuple++；耗尽则 fetch_more_data；
 *      仍无数据则 ExecClearTuple 返回空 slot（EOF）。
 */
TupleTableSlot* icebergIterateForeignScan(ForeignScanState* node)
{
    IcebergScanState* festate = (IcebergScanState*)node->fdw_state;
    TupleTableSlot* slot = node->ss.ss_ScanTupleSlot;

    if (festate == NULL)
        return ExecClearTuple(slot); /* EXPLAIN-only */

    if (festate->next_tuple >= festate->num_tuples) {
        fetch_more_data(festate);
        if (festate->num_tuples == 0)
            return ExecClearTuple(slot); /* EOF */
    }

    (void)ExecStoreTuple(festate->tuples[festate->next_tuple++], slot, InvalidBuffer, false);
    return slot;
}

/*
 * icebergReScanForeignScan
 *      重置游标重扫（doc 3 §5.8）。本版做最小实现以支持子计划重扫。
 */
void icebergReScanForeignScan(ForeignScanState* node)
{
    IcebergScanState* festate = (IcebergScanState*)node->fdw_state;

    if (festate == NULL)
        return;

    MemoryContextReset(festate->batch_cxt);
    festate->tuples = NULL;
    festate->num_tuples = 0;
    festate->next_tuple = 0;
    festate->eof_reached = false;
}

/*
 * icebergEndForeignScan
 *      关 reader、释放 batch/temp 上下文。桩阶段同样释放避免被误判泄漏。
 */
void icebergEndForeignScan(ForeignScanState* node)
{
    IcebergScanState* festate = (IcebergScanState*)node->fdw_state;

    if (festate == NULL)
        return; /* EXPLAIN-only */

    /* reader 本版为桩，无需关闭；下半层在此关闭 SDK reader */
    if (festate->batch_cxt != NULL)
        MemoryContextDelete(festate->batch_cxt);
    if (festate->temp_cxt != NULL)
        MemoryContextDelete(festate->temp_cxt);
}
