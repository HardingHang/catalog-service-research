/* -------------------------------------------------------------------------
 *
 * validate_table_def.cpp
 *      ValidateTableDef DDL 期钩子占位（doc 3 §5.9，table create 接入点）。
 *
 * 本版只做最小校验：放行 CREATE/ALTER FOREIGN TABLE，不阻断合法外表创建。
 * namespace 不经此回调（控制面纯 Catalog 操作）。
 *
 * [接入点] 支持 table create 时，在此校验 Iceberg 外表 OPTIONS 组合
 * （namespace 必须存在、location 合法等），并与控制面 iceberg_create_table 协同。
 *
 * 模板来源：openGauss contrib/file_fdw/file_fdw.cpp fileValidateTableDef。
 *
 * -------------------------------------------------------------------------
 */
#include "postgres.h"
#include "knl/knl_variable.h"

#include "nodes/nodes.h"
#include "nodes/parsenodes.h"

#include "iceberg_fdw.h"

void icebergValidateTableDef(Node* obj)
{
    if (obj == NULL)
        return;

    switch (nodeTag(obj)) {
        case T_CreateForeignTableStmt:
            /* 本版放行；OPTIONS 合法性已由 validator 把关 */
            break;
        case T_AlterTableStmt:
            /* 本版不限制 ALTER；Phase 3+ 收紧 */
            break;
        default:
            break;
    }
}
