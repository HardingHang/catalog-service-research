#!/usr/bin/env python3
"""Collect Iceberg ref state from Spark SQL metadata tables.

The P0 controller only needs ref-level snapshot identity for publish preflight.
The latest table metadata file is included as diagnostic context, but it is not
used as the publish guard because Iceberg branch commits can advance table
metadata without moving the main ref.
"""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pyspark.sql import SparkSession


IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$")


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_tables(raw: str) -> list[str]:
    tables = [item.strip() for item in raw.split(",") if item.strip()]
    if not tables:
        raise ValueError("--tables must contain at least one table")
    for table in tables:
        if not IDENTIFIER.match(table):
            raise ValueError(f"Unsupported table identifier: {table!r}")
    return tables


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def collect_ref_state(spark: SparkSession, table: str, ref: str) -> dict[str, Any]:
    refs = spark.sql(
        f"SELECT snapshot_id FROM {table}.refs WHERE name = {sql_literal(ref)}"
    ).collect()
    if not refs:
        raise RuntimeError(f"Ref {ref!r} not found for table {table}")

    state: dict[str, Any] = {
        "ref": ref,
        "snapshot_id": str(refs[0]["snapshot_id"]),
    }

    try:
        metadata_rows = spark.sql(
            f"""
            SELECT file, latest_schema_id
            FROM {table}.metadata_log_entries
            ORDER BY timestamp DESC
            LIMIT 1
            """
        ).collect()
    except Exception as exc:  # Spark/Iceberg metadata table availability can vary by version.
        state["metadata_warning"] = str(exc).splitlines()[0]
    else:
        if metadata_rows:
            metadata = metadata_rows[0]
            state["metadata_location"] = metadata["file"]
            if metadata["latest_schema_id"] is not None:
                state["schema_id"] = str(metadata["latest_schema_id"])

    return state


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect Iceberg table ref state as JSON")
    parser.add_argument("--tables", required=True, help="Comma-separated table identifiers")
    parser.add_argument("--ref", default="main", help="Iceberg ref name. Default: main")
    parser.add_argument("--output", required=True, help="Output JSON path")
    args = parser.parse_args()

    tables = parse_tables(args.tables)
    spark = SparkSession.builder.appName("polaris-p0-collect-table-state").getOrCreate()
    try:
        payload = {
            "collected_at": utc_now(),
            "ref": args.ref,
            "tables": {
                table: collect_ref_state(spark, table, args.ref)
                for table in tables
            },
        }
    finally:
        spark.stop()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Wrote table state: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
