/*
 * namespace.c - Namespace operations (create, drop, list)
 */

#include "postgres.h"
#include "fmgr.h"
#include "funcapi.h"
#include "executor/spi.h"
#include "utils/builtins.h"
#include "utils/jsonb.h"
#include "catalog/pg_type.h"

#include "utils.h"

PG_FUNCTION_INFO_V1(iceberg_create_namespace);
PG_FUNCTION_INFO_V1(iceberg_drop_namespace);
PG_FUNCTION_INFO_V1(iceberg_list_namespaces);

/* SQL strings */
static const char *insert_namespace_sql =
    "INSERT INTO iceberg_catalog.namespaces(namespace, properties) "
    "VALUES ($1, COALESCE($2, '{}'::jsonb))";

static const char *select_namespace_sql =
    "SELECT namespace::text, properties::text FROM iceberg_catalog.namespaces "
    "WHERE namespace = $1";

static const char *check_namespace_sql =
    "SELECT 1 FROM iceberg_catalog.namespaces WHERE namespace = $1";

static const char *check_tables_sql =
    "SELECT count(*) FROM iceberg_catalog.tables WHERE namespace = $1";

static const char *delete_namespace_sql =
    "DELETE FROM iceberg_catalog.namespaces WHERE namespace = $1";

static const char *list_namespace_sql =
    "SELECT namespace::text, properties::text FROM iceberg_catalog.namespaces ORDER BY namespace";

/*
 * iceberg_create_namespace - Create a namespace
 */
Datum
iceberg_create_namespace(PG_FUNCTION_ARGS)
{
    text *p_namespace = PG_GETARG_TEXT_PP(0);
    Jsonb *p_properties = PG_ARGISNULL(1) ? NULL : PG_GETARG_JSONB_P(1);

    char *ns_text;
    int spi_ret;
    SPIPlanPtr plan;
    Datum values[2];
    char nulls[2];
    Oid argtypes[2];

    TupleDesc result_tupdesc;
    AttInMetadata *attinmeta;
    HeapTuple result_tuple, spi_tuple;
    Datum result_datum;
    char **str_values;

    ns_text = text_to_cstring(p_namespace);

    spi_ret = SPI_connect();
    if (spi_ret != SPI_OK_CONNECT)
        elog(ERROR, "SPI_connect failed: %d", spi_ret);

    /* Check if namespace already exists */
    argtypes[0] = TEXTOID;
    plan = SPI_prepare(check_namespace_sql, 1, argtypes);
    values[0] = CStringGetTextDatum(ns_text);
    spi_ret = SPI_execute_plan(plan, values, " ", false, 0);
    if (spi_ret != SPI_OK_SELECT)
        elog(ERROR, "SPI_execute_plan failed for check: %d", spi_ret);

    if (SPI_processed > 0)
    {
        SPI_finish();
        ereport(ERROR,
                (errcode(ERRCODE_UNIQUE_VIOLATION),
                 errmsg("Namespace already exists: %s", ns_text)));
    }

    /* Insert */
    argtypes[0] = TEXTOID;
    argtypes[1] = JSONBOID;
    plan = SPI_prepare(insert_namespace_sql, 2, argtypes);
    values[0] = CStringGetTextDatum(ns_text);
    values[1] = p_properties ? PointerGetDatum(p_properties) : (Datum)NULL;
    nulls[0] = ' ';
    nulls[1] = p_properties ? ' ' : 'n';

    spi_ret = SPI_execute_plan(plan, values, nulls, false, 0);
    if (spi_ret != SPI_OK_INSERT)
        elog(ERROR, "SPI_execute_plan failed for INSERT: %d", spi_ret);

    /* Return the created namespace */
    argtypes[0] = TEXTOID;
    plan = SPI_prepare(select_namespace_sql, 1, argtypes);
    values[0] = CStringGetTextDatum(ns_text);
    spi_ret = SPI_execute_plan(plan, values, " ", false, 0);
    if (spi_ret != SPI_OK_SELECT || SPI_processed == 0)
        elog(ERROR, "Failed to select created namespace");

    get_call_result_type(fcinfo, NULL, &result_tupdesc);
    BlessTupleDesc(result_tupdesc);
    attinmeta = TupleDescGetAttInMetadata(result_tupdesc);

    spi_tuple = SPI_tuptable->vals[0];
    str_values = (char **) palloc(sizeof(char *) * 2);
    str_values[0] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 1);
    str_values[1] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 2);

    result_tuple = BuildTupleFromCStrings(attinmeta, str_values);
    result_datum = HeapTupleGetDatum(result_tuple);

    pfree(str_values[0]);
    pfree(str_values[1]);
    pfree(str_values);

    SPI_finish();
    PG_RETURN_DATUM(result_datum);
}

/*
 * iceberg_drop_namespace - Drop a namespace (must be empty)
 */
Datum
iceberg_drop_namespace(PG_FUNCTION_ARGS)
{
    text *p_namespace = PG_GETARG_TEXT_PP(0);
    char *ns_text;
    int spi_ret;
    SPIPlanPtr plan;
    Datum values[1];
    Oid argtypes[1];
    int table_count;
    HeapTuple spi_tuple;
    char *count_str;

    ns_text = text_to_cstring(p_namespace);

    spi_ret = SPI_connect();
    if (spi_ret != SPI_OK_CONNECT)
        elog(ERROR, "SPI_connect failed: %d", spi_ret);

    /* Check if namespace is empty */
    argtypes[0] = TEXTOID;
    plan = SPI_prepare(check_tables_sql, 1, argtypes);
    values[0] = CStringGetTextDatum(ns_text);
    spi_ret = SPI_execute_plan(plan, values, " ", false, 0);
    if (spi_ret != SPI_OK_SELECT)
        elog(ERROR, "SPI_execute_plan failed for check: %d", spi_ret);

    spi_tuple = SPI_tuptable->vals[0];
    count_str = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 1);
    table_count = atoi(count_str);
    pfree(count_str);

    if (table_count > 0)
    {
        SPI_finish();
        ereport(ERROR,
                (errcode(ERRCODE_INVALID_PARAMETER_VALUE),
                 errmsg("Namespace %s is not empty", ns_text)));
    }

    /* Delete namespace */
    plan = SPI_prepare(delete_namespace_sql, 1, argtypes);
    spi_ret = SPI_execute_plan(plan, values, " ", false, 0);

    if (spi_ret != SPI_OK_DELETE)
        elog(ERROR, "SPI_execute_plan failed for DELETE: %d", spi_ret);

    if (SPI_processed == 0)
    {
        SPI_finish();
        ereport(ERROR,
                (errcode(ERRCODE_INVALID_PARAMETER_VALUE),
                 errmsg("Namespace not found: %s", ns_text)));
    }

    SPI_finish();
    PG_RETURN_BOOL(true);
}

/*
 * iceberg_list_namespaces - List all namespaces
 */
Datum
iceberg_list_namespaces(PG_FUNCTION_ARGS)
{
    int spi_ret;
    SPIPlanPtr plan;
    TupleDesc result_tupdesc;
    FuncCallContext *funcctx;
    int call_cntr;
    int max_calls;
    HeapTuple spi_tuple;
    Datum result_datum;
    HeapTuple result_tuple;
    char **str_values;

    if (SRF_IS_FIRSTCALL())
    {
        funcctx = SRF_FIRSTCALL_INIT();

        spi_ret = SPI_connect();
        if (spi_ret != SPI_OK_CONNECT)
            elog(ERROR, "SPI_connect failed: %d", spi_ret);

        plan = SPI_prepare(list_namespace_sql, 0, NULL);
        spi_ret = SPI_execute_plan(plan, NULL, NULL, false, 0);
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

        str_values = (char **) palloc(sizeof(char *) * 2);
        str_values[0] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 1);
        str_values[1] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 2);

        result_tuple = BuildTupleFromCStrings(funcctx->attinmeta, str_values);
        result_datum = HeapTupleGetDatum(result_tuple);

        pfree(str_values[0]);
        pfree(str_values[1]);
        pfree(str_values);

        SRF_RETURN_NEXT(funcctx, result_datum);
    }
    else
    {
        SRF_RETURN_DONE(funcctx);
    }
}