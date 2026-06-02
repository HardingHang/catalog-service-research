/*
 * utils.c - Utility functions for pg_iceberg_catalog_demo
 */

#include "utils.h"
#include "utils/uuid.h"
#include "catalog/pg_type.h"
#include <time.h>

void
throw_namespace_not_found(const char *namespace)
{
    ereport(ERROR,
            (errcode(ERRCODE_INVALID_PARAMETER_VALUE),
             errmsg("Namespace not found: %s", namespace)));
}

void
throw_table_not_found(const char *namespace, const char *table_name)
{
    ereport(ERROR,
            (errcode(ERRCODE_INVALID_PARAMETER_VALUE),
             errmsg("Table not found: %s.%s", namespace, table_name)));
}

void
throw_table_already_exists(const char *namespace, const char *table_name)
{
    ereport(ERROR,
            (errcode(ERRCODE_UNIQUE_VIOLATION),
             errmsg("Table already exists: %s.%s", namespace, table_name)));
}

void
throw_commit_conflict(const char *namespace, const char *table_name, const char *expected)
{
    ereport(ERROR,
            (errcode(ERRCODE_T_R_SERIALIZATION_FAILURE),
             errmsg("Commit conflict for %s.%s: expected metadata_location %s, but current metadata_location has changed",
                    namespace, table_name, expected)));
}

char *
text_to_cstring_safe(text *t)
{
    if (t == NULL)
        return NULL;
    return text_to_cstring(t);
}

/*
 * Generate a random UUID string (version 4)
 */
char *
gen_uuid_string(void)
{
    static int initialized = 0;
    pg_uuid_t uuid;
    char *result;

    /* Initialize random seed once */
    if (!initialized)
    {
        srand((unsigned int)time(NULL));
        initialized = 1;
    }

    /* Generate random bytes for UUID */
    for (int i = 0; i < UUID_LEN; i++)
    {
        uuid.data[i] = (unsigned char)(rand() % 256);
    }

    /* Set UUID version 4 (random) - bits 4-7 of byte 6 */
    uuid.data[6] = (uuid.data[6] & 0x0F) | 0x40;
    /* Set UUID variant - bits 6-7 of byte 8 */
    uuid.data[8] = (uuid.data[8] & 0x3F) | 0x80;

    /* Format as string */
    result = (char *) palloc(37);
    snprintf(result, 37,
             "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
             uuid.data[0], uuid.data[1], uuid.data[2], uuid.data[3],
             uuid.data[4], uuid.data[5],
             uuid.data[6], uuid.data[7],
             uuid.data[8], uuid.data[9],
             uuid.data[10], uuid.data[11], uuid.data[12], uuid.data[13],
             uuid.data[14], uuid.data[15]);

    return result;
}