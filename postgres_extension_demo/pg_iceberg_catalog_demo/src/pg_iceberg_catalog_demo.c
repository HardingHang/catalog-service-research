/*
 * pg_iceberg_catalog_demo.c - Main entry point for extension
 */

#include "postgres.h"
#include "fmgr.h"
#include <time.h>

PG_MODULE_MAGIC;

void
_PG_init(void)
{
    srand((unsigned int)time(NULL));
}

void
_PG_fini(void)
{
}