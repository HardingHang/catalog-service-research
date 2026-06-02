/*
 * commit_table.c - CAS commit implementation for Iceberg tables
 */

#include "postgres.h"
#include "fmgr.h"
#include "funcapi.h"
#include "executor/spi.h"
#include "utils/builtins.h"
#include "utils/jsonb.h"
#include "utils/syscache.h"
#include "catalog/pg_type.h"

#include "utils.h"

PG_FUNCTION_INFO_V1(iceberg_commit_table);

/* SQL query strings */
static const char *update_sql =
    "UPDATE iceberg_catalog.tables SET "
    "previous_metadata_location = metadata_location, "
    "metadata_location = $1, "
    "metadata_json = COALESCE($2, metadata_json), "
    "updated_at = now() "
    "WHERE namespace = $3 AND table_name = $4 "
    "AND metadata_location = $5";

static const char *check_sql =
    "SELECT 1 FROM iceberg_catalog.tables "
    "WHERE namespace = $1 AND table_name = $2";

static const char *select_sql =
    "SELECT namespace::text, table_name::text, table_uuid::text, "
    "metadata_location::text, metadata_json::text "
    "FROM iceberg_catalog.tables "
    "WHERE namespace = $1 AND table_name = $2";

/*
 * iceberg_commit_table - CAS update of metadata_location
 */
Datum
iceberg_commit_table(PG_FUNCTION_ARGS)
{
    text *p_namespace = PG_GETARG_TEXT_PP(0);
    text *p_table_name = PG_GETARG_TEXT_PP(1);
    text *p_expected_location = PG_GETARG_TEXT_PP(2);
    text *p_new_location = PG_GETARG_TEXT_PP(3);
    Jsonb *p_new_json = PG_ARGISNULL(4) ? NULL : PG_GETARG_JSONB_P(4);

    char *ns_text;
    char *tbl_text;
    char *expected_text;
    char *new_loc_text;

    int spi_ret;
    int rows_updated;
    SPIPlanPtr plan;
    Datum values[5];
    char nulls[5];
    Oid argtypes[5];

    TupleDesc result_tupdesc;
    AttInMetadata *attinmeta;
    HeapTuple result_tuple;
    Datum result_datum;
    HeapTuple spi_tuple;
    char **str_values;

    /* Convert text parameters to C strings */
    ns_text = text_to_cstring(p_namespace);
    tbl_text = text_to_cstring(p_table_name);
    expected_text = text_to_cstring(p_expected_location);
    new_loc_text = text_to_cstring(p_new_location);

    /* Connect to SPI */
    spi_ret = SPI_connect();
    if (spi_ret != SPI_OK_CONNECT)
        elog(ERROR, "SPI_connect failed: %d", spi_ret);

    /* Step 1: Execute CAS UPDATE */
    argtypes[0] = TEXTOID;
    argtypes[1] = JSONBOID;
    argtypes[2] = TEXTOID;
    argtypes[3] = TEXTOID;
    argtypes[4] = TEXTOID;

    plan = SPI_prepare(update_sql, 5, argtypes);
    if (plan == NULL)
        elog(ERROR, "SPI_prepare failed for UPDATE");

    values[0] = CStringGetTextDatum(new_loc_text);
    values[1] = p_new_json ? PointerGetDatum(p_new_json) : (Datum)NULL;
    values[2] = CStringGetTextDatum(ns_text);
    values[3] = CStringGetTextDatum(tbl_text);
    values[4] = CStringGetTextDatum(expected_text);

    nulls[0] = ' ';
    nulls[1] = p_new_json ? ' ' : 'n';
    nulls[2] = ' ';
    nulls[3] = ' ';
    nulls[4] = ' ';

    spi_ret = SPI_execute_plan(plan, values, nulls, false, 0);
    if (spi_ret != SPI_OK_UPDATE)
        elog(ERROR, "SPI_execute_plan failed for UPDATE: %d", spi_ret);

    rows_updated = SPI_processed;

    if (rows_updated == 0)
    {
        /* Check if table exists */
        argtypes[0] = TEXTOID;
        argtypes[1] = TEXTOID;

        plan = SPI_prepare(check_sql, 2, argtypes);
        if (plan == NULL)
            elog(ERROR, "SPI_prepare failed for check");

        values[0] = CStringGetTextDatum(ns_text);
        values[1] = CStringGetTextDatum(tbl_text);

        spi_ret = SPI_execute_plan(plan, values, "  ", false, 0);
        if (spi_ret != SPI_OK_SELECT)
            elog(ERROR, "SPI_execute_plan failed for check: %d", spi_ret);

        SPI_finish();

        if (SPI_processed == 0)
            throw_table_not_found(ns_text, tbl_text);
        else
            throw_commit_conflict(ns_text, tbl_text, expected_text);
    }

    /* Step 2: Fetch the updated row for return */
    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;

    plan = SPI_prepare(select_sql, 2, argtypes);
    if (plan == NULL)
        elog(ERROR, "SPI_prepare failed for SELECT");

    values[0] = CStringGetTextDatum(ns_text);
    values[1] = CStringGetTextDatum(tbl_text);

    spi_ret = SPI_execute_plan(plan, values, "  ", false, 0);
    if (spi_ret != SPI_OK_SELECT)
        elog(ERROR, "SPI_execute_plan failed for SELECT: %d", spi_ret);

    if (SPI_processed == 0)
    {
        SPI_finish();
        throw_table_not_found(ns_text, tbl_text);
    }

    /* Build return tuple */
    get_call_result_type(fcinfo, NULL, &result_tupdesc);
    BlessTupleDesc(result_tupdesc);
    attinmeta = TupleDescGetAttInMetadata(result_tupdesc);

    spi_tuple = SPI_tuptable->vals[0];
    str_values = (char **) palloc(sizeof(char *) * 5);

    str_values[0] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 1);
    str_values[1] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 2);
    str_values[2] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 3);
    str_values[3] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 4);
    str_values[4] = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 5);

    result_tuple = BuildTupleFromCStrings(attinmeta, str_values);
    result_datum = HeapTupleGetDatum(result_tuple);

    pfree(str_values[0]);
    pfree(str_values[1]);
    pfree(str_values[2]);
    pfree(str_values[3]);
    pfree(str_values[4]);
    pfree(str_values);

    SPI_finish();

    PG_RETURN_DATUM(result_datum);
}