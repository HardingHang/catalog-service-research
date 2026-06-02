/*
 * create_table.c - Create Iceberg table implementation
 */

#include "postgres.h"
#include "fmgr.h"
#include "funcapi.h"
#include "executor/spi.h"
#include "utils/builtins.h"
#include "utils/jsonb.h"
#include "catalog/pg_type.h"

#include "utils.h"
#include <time.h>

PG_FUNCTION_INFO_V1(iceberg_create_table);

/*
 * iceberg_create_table - Create a new Iceberg table
 */
Datum
iceberg_create_table(PG_FUNCTION_ARGS)
{
    text *p_namespace = PG_GETARG_TEXT_PP(0);
    text *p_table_name = PG_GETARG_TEXT_PP(1);
    Jsonb *p_schema_json = PG_GETARG_JSONB_P(2);
    Jsonb *p_partition_spec = PG_ARGISNULL(3) ? NULL : PG_GETARG_JSONB_P(3);
    Jsonb *p_properties = PG_ARGISNULL(4) ? NULL : PG_GETARG_JSONB_P(4);
    text *p_location = PG_ARGISNULL(5) ? NULL : PG_GETARG_TEXT_PP(5);

    char *ns_text;
    char *tbl_text;
    char *location_text;
    char *uuid_str;
    char *metadata_location;
    char *metadata_json_str;
    Jsonb *empty_props;

    int spi_ret;
    SPIPlanPtr plan;
    Datum values[7];
    char nulls[7];
    Oid argtypes[7];

    TupleDesc result_tupdesc;
    AttInMetadata *attinmeta;
    HeapTuple result_tuple;
    Datum result_datum;
    HeapTuple spi_tuple;
    char **str_values;

    /* SQL query strings */
    const char *check_ns_sql = "SELECT 1 FROM iceberg_catalog.namespaces WHERE namespace = $1";
    const char *check_table_sql = "SELECT 1 FROM iceberg_catalog.tables WHERE namespace = $1 AND table_name = $2";
    const char *build_json_sql =
        "SELECT jsonb_build_object("
        "'format-version', 2, "
        "'table-uuid', $1, "
        "'location', $2, "
        "'last-sequence-number', 0, "
        "'last-updated-ms', floor(extract(epoch from clock_timestamp()) * 1000)::bigint, "
        "'schemas', jsonb_build_array($3), "
        "'current-schema-id', 0, "
        "'partition-specs', CASE WHEN $4 IS NULL THEN '[]'::jsonb ELSE jsonb_build_array($4) END, "
        "'default-spec-id', 0, "
        "'properties', COALESCE($5, '{}'::jsonb), "
        "'snapshots', '[]'::jsonb, "
        "'snapshot-log', '[]'::jsonb, "
        "'metadata-log', '[]'::jsonb, "
        "'refs', '{}'::jsonb"
        ")::text";
    const char *insert_sql =
        "INSERT INTO iceberg_catalog.tables("
        "catalog_name, namespace, table_name, table_uuid, metadata_location, "
        "previous_metadata_location, table_location, iceberg_type, properties, metadata_json"
        ") VALUES ('default', $1, $2, $3::uuid, $4, NULL, $5, 'TABLE', $6, $7::jsonb)";
    const char *select_sql =
        "SELECT namespace::text, table_name::text, table_uuid::text, "
        "metadata_location::text, metadata_json::text "
        "FROM iceberg_catalog.tables "
        "WHERE namespace = $1 AND table_name = $2";

    /* Initialize random seed */
    srand((unsigned int)time(NULL));

    /* Convert parameters */
    ns_text = text_to_cstring(p_namespace);
    tbl_text = text_to_cstring(p_table_name);

    /* Create empty jsonb for default properties */
    empty_props = DatumGetJsonbP(DirectFunctionCall1(jsonb_in, CStringGetDatum("{}")));

    /* Connect to SPI */
    spi_ret = SPI_connect();
    if (spi_ret != SPI_OK_CONNECT)
        elog(ERROR, "SPI_connect failed: %d", spi_ret);

    /* Step 1: Check namespace exists */
    argtypes[0] = TEXTOID;
    plan = SPI_prepare(check_ns_sql, 1, argtypes);
    if (plan == NULL)
        elog(ERROR, "SPI_prepare failed for namespace check");

    values[0] = CStringGetTextDatum(ns_text);
    spi_ret = SPI_execute_plan(plan, values, " ", false, 0);
    if (spi_ret != SPI_OK_SELECT)
        elog(ERROR, "SPI_execute_plan failed for namespace check: %d", spi_ret);

    if (SPI_processed == 0)
    {
        SPI_finish();
        throw_namespace_not_found(ns_text);
    }

    /* Step 1.5: Check table doesn't already exist */
    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;
    plan = SPI_prepare(check_table_sql, 2, argtypes);
    if (plan == NULL)
        elog(ERROR, "SPI_prepare failed for table check");

    values[0] = CStringGetTextDatum(ns_text);
    values[1] = CStringGetTextDatum(tbl_text);
    spi_ret = SPI_execute_plan(plan, values, "  ", false, 0);
    if (spi_ret != SPI_OK_SELECT)
        elog(ERROR, "SPI_execute_plan failed for table check: %d", spi_ret);

    if (SPI_processed > 0)
    {
        SPI_finish();
        throw_table_already_exists(ns_text, tbl_text);
    }

    /* Step 2: Generate UUID */
    uuid_str = gen_uuid_string();

    /* Step 3: Build location */
    if (p_location)
        location_text = text_to_cstring(p_location);
    else
        location_text = psprintf("s3://demo-bucket/%s/%s", ns_text, tbl_text);

    /* Step 4: Build metadata_location */
    metadata_location = psprintf("%s/metadata/00000-%s.metadata.json",
                                  location_text,
                                  uuid_str);

    /* Step 5: Build metadata_json via SQL */
    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;
    argtypes[2] = JSONBOID;
    argtypes[3] = JSONBOID;
    argtypes[4] = JSONBOID;

    plan = SPI_prepare(build_json_sql, 5, argtypes);

    values[0] = CStringGetTextDatum(uuid_str);
    values[1] = CStringGetTextDatum(location_text);
    values[2] = PointerGetDatum(p_schema_json);
    values[3] = p_partition_spec ? PointerGetDatum(p_partition_spec) : (Datum)NULL;
    values[4] = p_properties ? PointerGetDatum(p_properties) : (Datum)NULL;

    nulls[0] = ' ';
    nulls[1] = ' ';
    nulls[2] = ' ';
    nulls[3] = p_partition_spec ? ' ' : 'n';
    nulls[4] = p_properties ? ' ' : 'n';

    spi_ret = SPI_execute_plan(plan, values, nulls, false, 0);
    if (spi_ret != SPI_OK_SELECT)
        elog(ERROR, "SPI_execute_plan failed for json build: %d", spi_ret);

    if (SPI_processed == 0)
        elog(ERROR, "No result from metadata_json build");

    spi_tuple = SPI_tuptable->vals[0];
    metadata_json_str = SPI_getvalue(spi_tuple, SPI_tuptable->tupdesc, 1);

    /* Step 6: Insert into tables */
    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;
    argtypes[2] = TEXTOID;
    argtypes[3] = TEXTOID;
    argtypes[4] = TEXTOID;
    argtypes[5] = JSONBOID;
    argtypes[6] = TEXTOID;

    plan = SPI_prepare(insert_sql, 7, argtypes);

    values[0] = CStringGetTextDatum(ns_text);
    values[1] = CStringGetTextDatum(tbl_text);
    values[2] = CStringGetTextDatum(uuid_str);
    values[3] = CStringGetTextDatum(metadata_location);
    values[4] = CStringGetTextDatum(location_text);
    values[5] = p_properties ? PointerGetDatum(p_properties) : PointerGetDatum(empty_props);
    values[6] = CStringGetTextDatum(metadata_json_str);

    for (int i = 0; i < 7; i++)
        nulls[i] = ' ';

    spi_ret = SPI_execute_plan(plan, values, nulls, false, 0);

    if (spi_ret != SPI_OK_INSERT)
        elog(ERROR, "SPI_execute_plan failed for INSERT: %d, SPI_result: %d", spi_ret, SPI_result);

    /* Step 7: Return the created row */
    argtypes[0] = TEXTOID;
    argtypes[1] = TEXTOID;

    plan = SPI_prepare(select_sql, 2, argtypes);
    values[0] = CStringGetTextDatum(ns_text);
    values[1] = CStringGetTextDatum(tbl_text);

    spi_ret = SPI_execute_plan(plan, values, "  ", false, 0);
    if (spi_ret != SPI_OK_SELECT || SPI_processed == 0)
        elog(ERROR, "Failed to select created table");

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