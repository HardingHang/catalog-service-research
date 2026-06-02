/*
 * utils.h - Utility functions for pg_iceberg_catalog_demo
 */

#ifndef PG_ICEBERG_UTILS_H
#define PG_ICEBERG_UTILS_H

#include "postgres.h"
#include "fmgr.h"
#include "utils/jsonb.h"
#include "utils/builtins.h"

/* Error throwing helpers */
void throw_namespace_not_found(const char *namespace);
void throw_table_not_found(const char *namespace, const char *table_name);
void throw_table_already_exists(const char *namespace, const char *table_name);
void throw_commit_conflict(const char *namespace, const char *table_name, const char *expected);

/* String helpers */
char *text_to_cstring_safe(text *t);
char *gen_uuid_string(void);

#endif /* PG_ICEBERG_UTILS_H */