/*
 * load_table.c - Load Iceberg table implementation
 */

#include "postgres.h"
#include "fmgr.h"
#include "funcapi.h"
#include "executor/spi.h"
#include "utils/builtins.h"
#include "catalog/pg_type.h"

#include "utils.h"

PG_FUNCTION_INFO_V1(iceberg_load_table);

/* SQL query string */
static const char *select_sql =
    "SELECT namespace::text, table_name::text, table_uuid::text, "
    "metadata_location::text, metadata_json::text "
    "FROM iceberg_catalog.tables "
    "WHERE namespace = $1 AND table_name = $2";

/*
 * iceberg_load_table - Load an Iceberg table's metadata
 */
Datum
iceberg_load_table(PG_FUNCTION_ARGS)
{
    text *p_namespace = PG_GETARG_TEXT_PP(0);
    text *p_table_name = PG_GETARG_TEXT_PP(1);

    char *ns_text;
    char *tbl_text;

    int spi_ret;
    SPIPlanPtr plan;
    Datum values[2];
    Oid argtypes[2];

    TupleDesc result_tupdesc;
    AttInMetadata *attinmeta;
    HeapTuple result_tuple;
    Datum result_datum;
    HeapTuple spi_tuple;
    char **str_values;

    /* Convert parameters */
    ns_text = text_to_cstring(p_namespace);
    tbl_text = text_to_cstring(p_table_name);

    /* Connect to SPI */
    spi_ret = SPI_connect();
    if (spi_ret != SPI_OK_CONNECT)
        elog(ERROR, "SPI_connect failed: %d", spi_ret);

    /* Query the table */
    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;

    plan = SPI_prepare(select_sql, 2, argtypes);
    if (plan == NULL)
        elog(ERROR, "SPI_prepare failed for SELECT");

    values[0] = CStringGetTextDatum(ns_text);
    values[1] = CStringGetTextDatum(tbl_text);

    spi_ret = SPI_execute_plan(plan, values, "  ", false, 0);
    if (spi_ret != SPI_OK_SELECT)
        elog(ERROR, "SPI_execute_plan failed: %d", spi_ret);

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