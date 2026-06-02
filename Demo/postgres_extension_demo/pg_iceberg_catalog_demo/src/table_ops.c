/*
 * table_ops.c - Table operations (list, register, drop, unregister, rename)
 */

#include "postgres.h"
#include "fmgr.h"
#include "funcapi.h"
#include "executor/spi.h"
#include "utils/builtins.h"
#include "utils/jsonb.h"
#include "catalog/pg_type.h"

#include "utils.h"

PG_FUNCTION_INFO_V1(iceberg_list_tables);
PG_FUNCTION_INFO_V1(iceberg_register_table);
PG_FUNCTION_INFO_V1(iceberg_drop_table);
PG_FUNCTION_INFO_V1(iceberg_unregister_table);
PG_FUNCTION_INFO_V1(iceberg_rename_table);

/* SQL strings */
static const char *list_tables_sql =
    "SELECT namespace::text, table_name::text, table_uuid::text, metadata_location::text "
    "FROM iceberg_catalog.tables WHERE namespace = $1 ORDER BY table_name";

static const char *check_ns_sql =
    "SELECT 1 FROM iceberg_catalog.namespaces WHERE namespace = $1";

static const char *check_table_sql =
    "SELECT 1 FROM iceberg_catalog.tables WHERE namespace = $1 AND table_name = $2";

static const char *insert_register_sql =
    "INSERT INTO iceberg_catalog.tables("
    "catalog_name, namespace, table_name, table_uuid, metadata_location, "
    "previous_metadata_location, table_location, iceberg_type, properties, metadata_json"
    ") VALUES ('default', $1, $2, gen_random_uuid(), $3, NULL, "
    "COALESCE($4->>'location', NULL), 'TABLE', "
    "COALESCE($4->'properties', '{}'::jsonb), COALESCE($4, '{}'::jsonb))";

static const char *select_after_insert_sql =
    "SELECT namespace::text, table_name::text, table_uuid::text, metadata_location::text, metadata_json::text "
    "FROM iceberg_catalog.tables WHERE namespace = $1 AND table_name = $2";

static const char *select_metadata_sql =
    "SELECT metadata_location FROM iceberg_catalog.tables "
    "WHERE namespace = $1 AND table_name = $2";

static const char *delete_table_sql =
    "DELETE FROM iceberg_catalog.tables WHERE namespace = $1 AND table_name = $2";

static const char *insert_purge_sql =
    "INSERT INTO iceberg_catalog.purge_queue(namespace, table_name, metadata_location) "
    "VALUES ($1, $2, $3)";

static const char *rename_table_sql =
    "UPDATE iceberg_catalog.tables SET namespace = $1, table_name = $2, updated_at = now() "
    "WHERE namespace = $3 AND table_name = $4";

/*
 * iceberg_list_tables - List tables in a namespace
 */
Datum
iceberg_list_tables(PG_FUNCTION_ARGS)
{
    text *p_namespace = PG_GETARG_TEXT_PP(0);
    char *ns_text;
    int spi_ret;
    SPIPlanPtr plan;
    Datum values[1];
    Oid argtypes[1];
    TupleDesc result_tupdesc;
    FuncCallContext *funcctx;
    int call_cntr;
    int max_calls;
    HeapTuple spi_tuple;
    Datum result_datum;
    HeapTuple result_tuple;
    char **str_values;

    ns_text = text_to_cstring(p_namespace);

    if (SRF_IS_FIRSTCALL())
    {
        funcctx = SRF_FIRSTCALL_INIT();

        spi_ret = SPI_connect();
        if (spi_ret != SPI_OK_CONNECT)
            elog(ERROR, "SPI_connect failed: %d", spi_ret);

        argtypes[0] = TEXTOID;
        plan = SPI_prepare(list_tables_sql, 1, argtypes);
        values[0] = CStringGetTextDatum(ns_text);

        spi_ret = SPI_execute_plan(plan, values, " ", false, 0);
        if (spi_ret != SPI_OK_SELECT)
            elog(ERROR, "SPI_execute_plan failed: %d", spi_ret);

        funcctx->max_calls = SPI_processed;
        funcctx->user_fctx = SPI_tuptable;

        get_call_result_type(fcinfo, NULL, &result_tupdesc);
        BlessTupleDesc(result_tupdesc);
        funcctx->attinmeta = TupleDescGetAttInMetadata(result_tupdesc);

        SPI_finish();
    }

    funcctx = SRF_PERCALL_SETUP();
    call_cntr = funcctx->call_cntr;
    max_calls = funcctx->max_calls;

    if (call_cntr < max_calls)
    {
        SPI_tuptable = (SPITupleTable *) funcctx->user_fctx;
        spi_tuple = SPI_tuptable->vals[call_cntr];

        str_values = (char **) palloc(sizeof(char *) * 4);
        str_values[0] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 1);
        str_values[1] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 2);
        str_values[2] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 3);
        str_values[3] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 4);

        result_tuple = BuildTupleFromCStrings(funcctx->attinmeta, str_values);
        result_datum = HeapTupleGetDatum(result_tuple);

        for (int i = 0; i < 4; i++)
            pfree(str_values[i]);
        pfree(str_values);

        SRF_RETURN_NEXT(funcctx, result_datum);
    }
    else
    {
        SRF_RETURN_DONE(funcctx);
    }
}

/*
 * iceberg_register_table - Register an existing Iceberg table
 */
Datum
iceberg_register_table(PG_FUNCTION_ARGS)
{
    text *p_namespace = PG_GETARG_TEXT_PP(0);
    text *p_table_name = PG_GETARG_TEXT_PP(1);
    text *p_metadata_location = PG_GETARG_TEXT_PP(2);
    Jsonb *p_metadata_json = PG_ARGISNULL(3) ? NULL : PG_GETARG_JSONB_P(3);

    char *ns_text;
    char *tbl_text;
    char *metadata_loc_text;

    int spi_ret;
    SPIPlanPtr plan;
    Datum values[4];
    char nulls[4];
    Oid argtypes[4];

    TupleDesc result_tupdesc;
    AttInMetadata *attinmeta;
    HeapTuple result_tuple, spi_tuple;
    Datum result_datum;
    char **str_values;

    ns_text = text_to_cstring(p_namespace);
    tbl_text = text_to_cstring(p_table_name);
    metadata_loc_text = text_to_cstring(p_metadata_location);

    spi_ret = SPI_connect();
    if (spi_ret != SPI_OK_CONNECT)
        elog(ERROR, "SPI_connect failed: %d", spi_ret);

    /* Check namespace exists */
    argtypes[0] = TEXTOID;
    plan = SPI_prepare(check_ns_sql, 1, argtypes);
    values[0] = CStringGetTextDatum(ns_text);
    spi_ret = SPI_execute_plan(plan, values, " ", false, 0);
    if (spi_ret != SPI_OK_SELECT)
        elog(ERROR, "SPI_execute_plan failed for check: %d", spi_ret);
    if (SPI_processed == 0)
    {
        SPI_finish();
        throw_namespace_not_found(ns_text);
    }

    /* Check table doesn't exist */
    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;
    plan = SPI_prepare(check_table_sql, 2, argtypes);
    values[0] = CStringGetTextDatum(ns_text);
    values[1] = CStringGetTextDatum(tbl_text);
    spi_ret = SPI_execute_plan(plan, values, "  ", false, 0);
    if (spi_ret != SPI_OK_SELECT)
        elog(ERROR, "SPI_execute_plan failed for check: %d", spi_ret);
    if (SPI_processed > 0)
    {
        SPI_finish();
        throw_table_already_exists(ns_text, tbl_text);
    }

    /* Insert */
    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;
    argtypes[2] = TEXTOID;
    argtypes[3] = JSONBOID;

    plan = SPI_prepare(insert_register_sql, 4, argtypes);
    values[0] = CStringGetTextDatum(ns_text);
    values[1] = CStringGetTextDatum(tbl_text);
    values[2] = CStringGetTextDatum(metadata_loc_text);
    values[3] = p_metadata_json ? PointerGetDatum(p_metadata_json) : (Datum)NULL;

    nulls[0] = ' ';
    nulls[1] = ' ';
    nulls[2] = ' ';
    nulls[3] = p_metadata_json ? ' ' : 'n';

    spi_ret = SPI_execute_plan(plan, values, nulls, false, 0);
    if (spi_ret != SPI_OK_INSERT)
        elog(ERROR, "SPI_execute_plan failed for INSERT: %d", spi_ret);

    /* Return result */
    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;
    plan = SPI_prepare(select_after_insert_sql, 2, argtypes);
    values[0] = CStringGetTextDatum(ns_text);
    values[1] = CStringGetTextDatum(tbl_text);
    spi_ret = SPI_execute_plan(plan, values, "  ", false, 0);

    get_call_result_type(fcinfo, NULL, &result_tupdesc);
    BlessTupleDesc(result_tupdesc);
    attinmeta = TupleDescGetAttInMetadata(result_tupdesc);

    spi_tuple = SPI_tuptable->vals[0];
    str_values = (char **) palloc(sizeof(char *) * 5);
    for (int i = 0; i < 5; i++)
        str_values[i] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, i + 1);

    result_tuple = BuildTupleFromCStrings(attinmeta, str_values);
    result_datum = HeapTupleGetDatum(result_tuple);

    for (int i = 0; i < 5; i++)
        pfree(str_values[i]);
    pfree(str_values);

    SPI_finish();
    PG_RETURN_DATUM(result_datum);
}

/*
 * iceberg_drop_table - Drop a table (with optional purge)
 */
Datum
iceberg_drop_table(PG_FUNCTION_ARGS)
{
    text *p_namespace = PG_GETARG_TEXT_PP(0);
    text *p_table_name = PG_GETARG_TEXT_PP(1);
    bool p_purge = PG_GETARG_BOOL(2);

    char *ns_text;
    char *tbl_text;
    char *metadata_loc;
    int spi_ret;
    SPIPlanPtr plan;
    Datum values[3];
    Oid argtypes[3];
    HeapTuple spi_tuple;
    char *metadata_loc_str;

    ns_text = text_to_cstring(p_namespace);
    tbl_text = text_to_cstring(p_table_name);

    spi_ret = SPI_connect();
    if (spi_ret != SPI_OK_CONNECT)
        elog(ERROR, "SPI_connect failed: %d", spi_ret);

    /* Get metadata_location */
    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;
    plan = SPI_prepare(select_metadata_sql, 2, argtypes);
    values[0] = CStringGetTextDatum(ns_text);
    values[1] = CStringGetTextDatum(tbl_text);

    spi_ret = SPI_execute_plan(plan, values, "  ", false, 0);
    if (spi_ret != SPI_OK_SELECT || SPI_processed == 0)
    {
        SPI_finish();
        throw_table_not_found(ns_text, tbl_text);
    }

    spi_tuple = SPI_tuptable->vals[0];
    metadata_loc_str = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 1);
    metadata_loc = pstrdup(metadata_loc_str);
    pfree(metadata_loc_str);

    /* Delete table */
    plan = SPI_prepare(delete_table_sql, 2, argtypes);
    spi_ret = SPI_execute_plan(plan, values, "  ", false, 0);
    if (spi_ret != SPI_OK_DELETE)
        elog(ERROR, "SPI_execute_plan failed for DELETE: %d", spi_ret);

    /* Add to purge_queue if requested */
    if (p_purge)
    {
        argtypes[0] = TEXTOID;
        argtypes[1] = TEXTOID;
        argtypes[2] = TEXTOID;
        plan = SPI_prepare(insert_purge_sql, 3, argtypes);
        values[0] = CStringGetTextDatum(ns_text);
        values[1] = CStringGetTextDatum(tbl_text);
        values[2] = CStringGetTextDatum(metadata_loc);
        spi_ret = SPI_execute_plan(plan, values, "   ", false, 0);
        if (spi_ret != SPI_OK_INSERT)
            elog(ERROR, "SPI_execute_plan failed for purge: %d", spi_ret);
    }

    pfree(metadata_loc);
    SPI_finish();
    PG_RETURN_BOOL(true);
}

/*
 * iceberg_unregister_table - Unregister a table (no purge)
 */
Datum
iceberg_unregister_table(PG_FUNCTION_ARGS)
{
    text *p_namespace = PG_GETARG_TEXT_PP(0);
    text *p_table_name = PG_GETARG_TEXT_PP(1);

    char *ns_text;
    char *tbl_text;
    int spi_ret;
    SPIPlanPtr plan;
    Datum values[2];
    Oid argtypes[2];

    ns_text = text_to_cstring(p_namespace);
    tbl_text = text_to_cstring(p_table_name);

    spi_ret = SPI_connect();
    if (spi_ret != SPI_OK_CONNECT)
        elog(ERROR, "SPI_connect failed: %d", spi_ret);

    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;
    plan = SPI_prepare(delete_table_sql, 2, argtypes);
    values[0] = CStringGetTextDatum(ns_text);
    values[1] = CStringGetTextDatum(tbl_text);

    spi_ret = SPI_execute_plan(plan, values, "  ", false, 0);
    if (spi_ret != SPI_OK_DELETE)
        elog(ERROR, "SPI_execute_plan failed: %d", spi_ret);

    if (SPI_processed == 0)
    {
        SPI_finish();
        throw_table_not_found(ns_text, tbl_text);
    }

    SPI_finish();
    PG_RETURN_BOOL(true);
}

/*
 * iceberg_rename_table - Rename a table
 */
Datum
iceberg_rename_table(PG_FUNCTION_ARGS)
{
    text *p_old_namespace = PG_GETARG_TEXT_PP(0);
    text *p_old_table_name = PG_GETARG_TEXT_PP(1);
    text *p_new_namespace = PG_GETARG_TEXT_PP(2);
    text *p_new_table_name = PG_GETARG_TEXT_PP(3);

    char *old_ns;
    char *old_tbl;
    char *new_ns;
    char *new_tbl;

    int spi_ret;
    SPIPlanPtr plan;
    Datum values[4];
    Oid argtypes[4];

    old_ns = text_to_cstring(p_old_namespace);
    old_tbl = text_to_cstring(p_old_table_name);
    new_ns = text_to_cstring(p_new_namespace);
    new_tbl = text_to_cstring(p_new_table_name);

    spi_ret = SPI_connect();
    if (spi_ret != SPI_OK_CONNECT)
        elog(ERROR, "SPI_connect failed: %d", spi_ret);

    /* Check target namespace exists */
    argtypes[0] = TEXTOID;
    plan = SPI_prepare(check_ns_sql, 1, argtypes);
    values[0] = CStringGetTextDatum(new_ns);
    spi_ret = SPI_execute_plan(plan, values, " ", false, 0);
    if (spi_ret != SPI_OK_SELECT)
        elog(ERROR, "SPI_execute_plan failed for check: %d", spi_ret);
    if (SPI_processed == 0)
    {
        SPI_finish();
        ereport(ERROR,
                (errcode(ERRCODE_INVALID_PARAMETER_VALUE),
                 errmsg("Target namespace not found: %s", new_ns)));
    }

    /* Rename */
    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;
    argtypes[2] = TEXTOID;
    argtypes[3] = TEXTOID;
    plan = SPI_prepare(rename_table_sql, 4, argtypes);
    values[0] = CStringGetTextDatum(new_ns);
    values[1] = CStringGetTextDatum(new_tbl);
    values[2] = CStringGetTextDatum(old_ns);
    values[3] = CStringGetTextDatum(old_tbl);

    spi_ret = SPI_execute_plan(plan, values, "    ", false, 0);
    if (spi_ret != SPI_OK_UPDATE)
        elog(ERROR, "SPI_execute_plan failed for UPDATE: %d", spi_ret);

    if (SPI_processed == 0)
    {
        SPI_finish();
        throw_table_not_found(old_ns, old_tbl);
    }

    SPI_finish();
    PG_RETURN_BOOL(true);
}