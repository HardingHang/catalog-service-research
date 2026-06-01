/* -------------------------------------------------------------------------
 *
 * vec_scan_callbacks.cpp
 *      向量化扫描入口（doc 3 §5.7）：VecIterateForeignScan。
 *
 * tuple / vectorbatch 两路共享 BeginForeignScan 建立的扫描态（IcebergScanState
 * 经 node->fdw_state）。本版（Phase 1）造一个 m_rows=N 的 mock VectorBatch：
 * 定长 by-value 列填常量并置 NOTNULL，其余列置 NULL，验证向量算子能消费。
 * 下半层换成 ArrowColumnToScalarVector（doc 2 §6.4）。
 *
 * 注意：VectorBatch 形状/容量需按 GaussVector 向量执行器最终复核（doc 3 §2.4）；
 * 本版基于 openGauss vectorbatch.h（BatchMaxSize=1000，V_NULL_MASK/V_NOTNULL_MASK）。
 *
 * -------------------------------------------------------------------------
 */
#include "postgres.h"
#include "knl/knl_variable.h"

#include "catalog/pg_type.h"
#include "utils/palloc.h"
#include "utils/rel.h"
#include "vecexecutor/vectorbatch.h"

#include "iceberg_fdw.h"
#include "exec/scan_state.h"

/*
 * mock_fixed_scalar
 *      为向量列造常量值。仅覆盖定长 by-value 类型；其余（含 varlen）置 NULL，
 *      留待下半层用 m_buf/AddVar 正确承载（doc 2 §6.4）。
 */
static ScalarValue mock_fixed_scalar(Form_pg_attribute attr, int rowidx, bool* isnull)
{
    *isnull = false;

    if (!attr->attisdropped) {
        switch (attr->atttypid) {
            case INT2OID:
                return (ScalarValue)Int16GetDatum((int16)(rowidx + 1));
            case INT4OID:
                return (ScalarValue)Int32GetDatum(rowidx + 1);
            case INT8OID:
                return (ScalarValue)Int64GetDatum((int64)(rowidx + 1));
            case FLOAT8OID:
                return (ScalarValue)Float8GetDatum((float8)(rowidx + 1));
            case BOOLOID:
                return (ScalarValue)BoolGetDatum((rowidx % 2) == 0);
            default:
                break;
        }
    }

    *isnull = true;
    return (ScalarValue)0;
}

/*
 * icebergVecIterateForeignScan
 *      返回一个 VectorBatch*；耗尽返回空批（m_rows=0）。
 *      复用 node->fdw_state（IcebergScanState），用 eof_reached 标志单批 mock。
 */
VectorBatch* icebergVecIterateForeignScan(VecForeignScanState* node)
{
    IcebergScanState* festate = (IcebergScanState*)node->fdw_state;
    TupleDesc tupdesc = festate->tupdesc;
    int natts = tupdesc->natts;
    VectorBatch* batch = NULL;

    /* 回收上一批后在 batch_cxt 内重建，返回的批存活到下一次调用 */
    MemoryContextReset(festate->batch_cxt);
    batch = New(festate->batch_cxt) VectorBatch(festate->batch_cxt, tupdesc);

    if (festate->eof_reached) {
        batch->m_rows = 0; /* EOF：空批 */
        return batch;
    }

    int n = ICEBERG_MOCK_ROWS;
    for (int c = 0; c < natts; c++) {
        ScalarVector* vec = &batch->m_arr[c];
        Form_pg_attribute attr = &tupdesc->attrs[c];
        for (int r = 0; r < n; r++) {
            bool isnull = false;
            ScalarValue v = mock_fixed_scalar(attr, r, &isnull);
            vec->m_vals[r] = v;
            vec->m_flag[r] = isnull ? V_NULL_MASK : V_NOTNULL_MASK;
        }
        vec->m_rows = n;
    }
    batch->m_rows = n;
    festate->eof_reached = true; /* 单批 mock */

    return batch;
}
