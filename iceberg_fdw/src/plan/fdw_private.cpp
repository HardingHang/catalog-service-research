/* -------------------------------------------------------------------------
 *
 * fdw_private.cpp
 *      fdw_private 编码（doc 3 §6.1）：规划→执行的桥。
 *
 * 按 IcebergScanPrivateIndex 顺序装配一个 List，元素均为可被 copyObject
 * 序列化的节点（子 List / IntList / NIL）。解码侧在执行回调里用
 * list_nth(fdw_private, IcebergPriv*) 取回。
 *
 * -------------------------------------------------------------------------
 */
#include "postgres.h"
#include "nodes/pg_list.h"

#include "iceberg_fdw.h"

/*
 * IcebergBuildScanPrivate
 *      按 IcebergScanPrivateIndex 顺序组装 fdw_private。
 *      各项允许为 NIL（如本版 residualQuals / fieldIdMap）。
 */
List* IcebergBuildScanPrivate(List* fileScanTasks, List* retrievedAttrs, List* residualQuals, List* fieldIdMap)
{
    List* priv = NIL;

    /* 顺序必须与 enum IcebergScanPrivateIndex 严格一致 */
    priv = lappend(priv, fileScanTasks);  /* IcebergPrivFileScanTasks */
    priv = lappend(priv, retrievedAttrs); /* IcebergPrivRetrievedAttrs */
    priv = lappend(priv, residualQuals);  /* IcebergPrivResidualQuals */
    priv = lappend(priv, fieldIdMap);     /* IcebergPrivFieldIdMap */

    Assert(list_length(priv) == IcebergPrivNum);
    return priv;
}
