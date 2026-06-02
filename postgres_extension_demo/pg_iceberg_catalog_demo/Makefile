EXTENSION = pg_iceberg_catalog_demo
MODULE_big = pg_iceberg_catalog_demo
OBJS = src/pg_iceberg_catalog_demo.o \
       src/commit_table.o \
       src/create_table.o \
       src/load_table.o \
       src/namespace.o \
       src/table_ops.o \
       src/utils.o

DATA = sql/pg_iceberg_catalog_demo--1.0.sql \
       sql/pg_iceberg_catalog_demo--1.0--2.0.sql

PG_CONFIG = pg_config
PGXS := $(shell $(PG_CONFIG) --pgxs)
include $(PGXS)