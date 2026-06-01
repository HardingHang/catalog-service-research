/* -------------------------------------------------------------------------
 *
 * catalog_resolve.cpp
 *      IcebergCatalogResolveTable（pg_native 后端）+ 外表身份/后端配置提取。
 *      doc 3 §7：数据面只读解析入口。
 *
 * pg_native：SPI 查 <catalog_schema>.tables 取 metadata_location / schema 摘要。
 * rest 后端本版未实现（报错占位）。
 *
 * -------------------------------------------------------------------------
 */
#include "postgres.h"

#include "catalog/pg_type.h"
#include "commands/defrem.h"
#include "executor/spi.h"
#include "foreign/foreign.h"
#include "lib/stringinfo.h"
#include "nodes/pg_list.h"
#include "utils/builtins.h"

#include "catalog/iceberg_catalog.h"

/* 从 DefElem 列表取一个 option 的字符串值，缺失返回 NULL */
static char* get_opt(List* options, const char* name)
{
    ListCell* lc = NULL;

    foreach (lc, options) {
        DefElem* def = (DefElem*)lfirst(lc);
        if (strcmp(def->defname, name) == 0)
            return defGetString(def);
    }
    return NULL;
}

void IcebergGetTableIdentity(Oid foreigntableid, char** ns, char** table_name, IcebergCatalog* cat)
{
    ForeignTable* table = GetForeignTable(foreigntableid);
    ForeignServer* server = GetForeignServer(table->serverid);
    char* kind = NULL;

    *ns = get_opt(table->options, "namespace");
    *table_name = get_opt(table->options, "table_name");
    if (*ns == NULL || *table_name == NULL)
        ereport(ERROR,
            (errcode(ERRCODE_FDW_DYNAMIC_PARAMETER_VALUE_NEEDED),
                errmsg("iceberg_fdw: foreign table requires options \"namespace\" and \"table_name\"")));

    kind = get_opt(server->options, "catalog_kind");
    cat->kind = (kind != NULL && strcmp(kind, "rest") == 0) ? ICEBERG_CATALOG_REST : ICEBERG_CATALOG_PG_NATIVE;

    cat->catalogSchema = get_opt(server->options, "catalog_schema");
    if (cat->catalogSchema == NULL)
        cat->catalogSchema = "iceberg_catalog"; /* 默认 schema */
}

IcebergResolved* IcebergCatalogResolveTable(IcebergCatalog* cat, const char* ns, const char* table_name)
{
    MemoryContext caller_cxt = CurrentMemoryContext;
    StringInfoData buf;
    Oid argtypes[2] = {TEXTOID, TEXTOID};
    Datum values[2];
    IcebergResolved* res = NULL;
    int ret;

    if (cat->kind == ICEBERG_CATALOG_REST)
        ereport(ERROR,
            (errcode(ERRCODE_FEATURE_NOT_SUPPORTED),
                errmsg("iceberg_fdw: rest catalog backend not implemented in this phase")));

    /* schema 名来自受信 server OPTION，仍用 quote_identifier 防注入/大小写 */
    initStringInfo(&buf);
    appendStringInfo(&buf,
        "SELECT metadata_location, current_schema_id, table_uuid::text "
        "FROM %s.tables WHERE namespace = $1 AND table_name = $2",
        quote_identifier(cat->catalogSchema));

    values[0] = CStringGetTextDatum(ns);
    values[1] = CStringGetTextDatum(table_name);

    if (SPI_connect() != SPI_OK_CONNECT)
        ereport(ERROR, (errmsg("iceberg_fdw: SPI_connect failed")));

    /* openGauss 签名比社区 PG 多 cursor_data（无默认值），传 NULL；parser 用默认 */
    ret = SPI_execute_with_args(buf.data, 2, argtypes, values, NULL, true, 1, NULL);
    if (ret != SPI_OK_SELECT) {
        SPI_finish();
        ereport(ERROR, (errmsg("iceberg_fdw: catalog query failed (SPI rc=%d)", ret)));
    }

    if (SPI_processed == 0) {
        SPI_finish();
        ereport(ERROR,
            (errcode(ERRCODE_UNDEFINED_OBJECT),
                errmsg("iceberg_fdw: table \"%s.%s\" not found in catalog", ns, table_name)));
    }

    {
        HeapTuple tup = SPI_tuptable->vals[0];
        TupleDesc td = SPI_tuptable->tupdesc;
        char* meta = SPI_getvalue(tup, td, 1);     /* metadata_location，schema 约束非空 */
        char* schemaId = SPI_getvalue(tup, td, 2); /* current_schema_id，可空 */
        char* uuid = SPI_getvalue(tup, td, 3);
        MemoryContext oldcxt = MemoryContextSwitchTo(caller_cxt); /* 结果须存活过 SPI_finish */

        res = (IcebergResolved*)palloc0(sizeof(IcebergResolved));
        res->metadataLocation = (meta != NULL) ? pstrdup(meta) : NULL;
        res->currentSchemaId = (schemaId != NULL) ? atoi(schemaId) : -1;
        res->tableUuid = (uuid != NULL) ? pstrdup(uuid) : NULL;

        (void)MemoryContextSwitchTo(oldcxt);
    }

    SPI_finish();

    if (res->metadataLocation == NULL)
        ereport(ERROR,
            (errcode(ERRCODE_FDW_ERROR),
                errmsg("iceberg_fdw: metadata_location is null for \"%s.%s\"", ns, table_name)));

    return res;
}
