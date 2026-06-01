/* iceberg_fdw/iceberg_fdw--1.0.sql */

-- complain if script is sourced in psql, rather than via CREATE EXTENSION
\echo Use "CREATE EXTENSION iceberg_fdw" to load this file. \quit

CREATE FUNCTION iceberg_fdw_handler()
RETURNS fdw_handler
AS 'MODULE_PATHNAME'
LANGUAGE C STRICT NOT FENCED;

CREATE FUNCTION iceberg_fdw_validator(text[], oid)
RETURNS void
AS 'MODULE_PATHNAME'
LANGUAGE C STRICT NOT FENCED;

CREATE FOREIGN DATA WRAPPER iceberg_fdw
  HANDLER iceberg_fdw_handler
  VALIDATOR iceberg_fdw_validator;
