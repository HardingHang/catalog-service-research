/* -------------------------------------------------------------------------
 *
 * iceberg_fdw.cpp
 *      Iceberg FDW 注册层：handler / validator + FdwRoutine 装配（doc 3 §2.3、§5.1）。
 *
 * 形态：openGauss extension，C++（extern "C" 导出 + PG_FUNCTION_INFO_V1）。
 * 模板来源：openGauss contrib/file_fdw/file_fdw.cpp。
 *
 * 本版 handler 赋齐 6 个扫描回调 + VecIterateForeignScan + ExplainForeignScan
 * + ValidateTableDef，其余字段保持 makeNode 初始化的 NULL。
 *
 * -------------------------------------------------------------------------
 */
#include "postgres.h"
#include "knl/knl_variable.h"

#include "access/reloptions.h"
#include "catalog/pg_foreign_table.h"
#include "catalog/pg_foreign_server.h"
#include "commands/defrem.h"
#include "commands/explain.h"
#include "foreign/fdwapi.h"
#include "foreign/foreign.h"
#include "lib/stringinfo.h"
#include "miscadmin.h"
#include "nodes/makefuncs.h"
#include "nodes/pg_list.h"
#include "nodes/value.h"
#include "utils/rel.h"

#include "iceberg_fdw.h"

PG_MODULE_MAGIC;

/*
 * 合法 OPTIONS 表（doc 3 §5.1）。
 *   server 级：catalog_kind(pg_native/rest)、catalog_schema、uri/prefix/warehouse
 *   table 级：namespace、table_name
 * 非法 key 报 ERRCODE_FDW_INVALID_OPTION_NAME。
 */
struct IcebergFdwOption {
    const char* optname;
    Oid optcontext; /* 该 option 允许出现的 catalog Oid */
};

static const struct IcebergFdwOption g_valid_options[] = {
    /* server 级 */
    {"catalog_kind", ForeignServerRelationId},
    {"catalog_schema", ForeignServerRelationId},
    {"uri", ForeignServerRelationId},
    {"prefix", ForeignServerRelationId},
    {"warehouse", ForeignServerRelationId},
    /* table 级 */
    {"namespace", ForeignTableRelationId},
    {"table_name", ForeignTableRelationId},
    /* sentinel */
    {NULL, InvalidOid}};

extern "C" Datum iceberg_fdw_handler(PG_FUNCTION_ARGS);
extern "C" Datum iceberg_fdw_validator(PG_FUNCTION_ARGS);

PG_FUNCTION_INFO_V1(iceberg_fdw_handler);
PG_FUNCTION_INFO_V1(iceberg_fdw_validator);

static bool is_valid_option(const char* option, Oid context);

/*
 * iceberg_fdw_handler
 *      返回装配好的 FdwRoutine。makeNode 保证未赋值字段为 NULL。
 */
Datum iceberg_fdw_handler(PG_FUNCTION_ARGS)
{
    FdwRoutine* routine = makeNode(FdwRoutine);

    /* 规划三回调（doc 3 §5.2-5.4） */
    routine->GetForeignRelSize = icebergGetForeignRelSize;
    routine->GetForeignPaths = icebergGetForeignPaths;
    routine->GetForeignPlan = icebergGetForeignPlan;

    /* 行式执行（doc 3 §5.5-5.8） */
    routine->BeginForeignScan = icebergBeginForeignScan;
    routine->IterateForeignScan = icebergIterateForeignScan;
    routine->ReScanForeignScan = icebergReScanForeignScan;
    routine->EndForeignScan = icebergEndForeignScan;

    /* 向量化执行：tuple / vectorbatch 双路并列（doc 3 §5.7） */
    routine->VecIterateForeignScan = icebergVecIterateForeignScan;

    /* EXPLAIN 与 DDL 校验接入点 */
    routine->ExplainForeignScan = icebergExplainForeignScan;
    routine->ValidateTableDef = icebergValidateTableDef;

    PG_RETURN_POINTER(routine);
}

/*
 * iceberg_fdw_validator
 *      校验 FDW / SERVER / FOREIGN TABLE 上的 OPTIONS（doc 3 §5.1）。
 *      非法 key 报 ERRCODE_FDW_INVALID_OPTION_NAME。
 */
Datum iceberg_fdw_validator(PG_FUNCTION_ARGS)
{
    List* options_list = untransformRelOptions(PG_GETARG_DATUM(0));
    Oid catalog = PG_GETARG_OID(1);
    ListCell* cell = NULL;
    bool has_namespace = false;
    bool has_table_name = false;

    foreach (cell, options_list) {
        DefElem* def = (DefElem*)lfirst(cell);

        if (strcmp(def->defname, "namespace") == 0)
            has_namespace = true;
        else if (strcmp(def->defname, "table_name") == 0)
            has_table_name = true;

        if (!is_valid_option(def->defname, catalog)) {
            const struct IcebergFdwOption* opt = NULL;
            StringInfoData buf;

            /* 收集当前 catalog 下的合法 key 作为 hint */
            initStringInfo(&buf);
            for (opt = g_valid_options; opt->optname; opt++) {
                if (catalog == opt->optcontext)
                    appendStringInfo(&buf, "%s%s", (buf.len > 0) ? ", " : "", opt->optname);
            }

            ereport(ERROR,
                (errcode(ERRCODE_FDW_INVALID_OPTION_NAME),
                    errmsg("invalid option \"%s\"", def->defname),
                    buf.len > 0 ? errhint("Valid options in this context are: %s", buf.data)
                                : errhint("There are no valid options in this context.")));
        }
    }

    /* 外表必需 namespace + table_name（doc 3 §5.1），缺则在 DDL 期即报错 */
    if (catalog == ForeignTableRelationId && (!has_namespace || !has_table_name))
        ereport(ERROR,
            (errcode(ERRCODE_FDW_DYNAMIC_PARAMETER_VALUE_NEEDED),
                errmsg("iceberg_fdw foreign table requires options \"namespace\" and \"table_name\"")));

    PG_RETURN_VOID();
}

/*
 * is_valid_option
 *      判断 option 是否在当前 catalog 上下文允许出现。
 */
static bool is_valid_option(const char* option, Oid context)
{
    const struct IcebergFdwOption* opt = NULL;

    for (opt = g_valid_options; opt->optname; opt++) {
        if (context == opt->optcontext && strcmp(opt->optname, option) == 0)
            return true;
    }
    return false;
}

/*
 * icebergExplainForeignScan
 *      EXPLAIN 附加输出（doc 3 §2.1）。从 fdw_private 解码并展示规划期
 *      解析到的 metadata_location（Phase 2 验收：计划中可见解析来源）。
 */
void icebergExplainForeignScan(ForeignScanState* node, struct ExplainState* es)
{
    ForeignScan* plan = (ForeignScan*)node->ss.ps.plan;
    List* fdw_private = plan->fdw_private;
    List* tasks = (List*)list_nth(fdw_private, IcebergPrivFileScanTasks);
    const char* meta = (tasks != NIL) ? strVal(linitial(tasks)) : "(none)";

    ExplainPropertyText("Iceberg metadata", meta, es);
}
