#!/usr/bin/env python3
"""Minimal Polaris branch-overlay demo controller.

The controller intentionally avoids Polaris internals. It records logical branch
state in a local manifest, compares table state snapshots, and emits Spark SQL
that uses Iceberg table branches.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


# Runtime workflows pass an explicit manifest under runtime/generated. This
# default keeps the standalone controller usable from the repository root.
DEFAULT_MANIFEST = Path("Demo/branch_on_polaris/controller/branches.json")

# P0 publish safety is ref-based. Iceberg can rewrite table metadata when a
# non-main branch advances, so metadata_location is useful diagnostic context
# but not a safe proxy for "main has moved".
REQUIRED_STATE_FIELDS = ("snapshot_id",)
PUBLISH_GUARD_FIELDS = ("snapshot_id",)


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return data


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, indent=2, ensure_ascii=False, sort_keys=True)
        handle.write("\n")


def empty_manifest(catalog: str) -> dict[str, Any]:
    return {
        "catalog": catalog,
        "created_at": utc_now(),
        "branches": {},
    }


def load_manifest(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise FileNotFoundError(f"Manifest not found: {path}. Run init first.")
    manifest = load_json(path)
    manifest.setdefault("branches", {})
    return manifest


def load_state(path: Path) -> dict[str, dict[str, Any]]:
    data = load_json(path)
    tables = data.get("tables")
    if not isinstance(tables, dict):
        raise ValueError(f"{path} must contain a 'tables' object")
    normalized: dict[str, dict[str, Any]] = {}
    for table, state in tables.items():
        if not isinstance(table, str) or not table:
            raise ValueError(f"{path} contains an invalid table identifier: {table!r}")
        if not isinstance(state, dict):
            raise ValueError(f"{path} state for {table} must be an object")
        missing = [field for field in REQUIRED_STATE_FIELDS if field not in state]
        if missing:
            raise ValueError(f"{path} state for {table} is missing: {', '.join(missing)}")
        normalized[table] = {key: stringify(value) for key, value in state.items()}
    return normalized


def stringify(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return str(value) if isinstance(value, (int, float, bool)) else value
    return value


def parse_tables(raw: str) -> list[str]:
    tables = [item.strip() for item in raw.split(",") if item.strip()]
    if not tables:
        raise ValueError("--tables must contain at least one table")
    seen: set[str] = set()
    deduped: list[str] = []
    for table in tables:
        if table in seen:
            continue
        seen.add(table)
        deduped.append(table)
    return deduped


def table_sql_identifier(catalog: str, table: str) -> str:
    parts = table.split(".")
    if len(parts) >= 3:
        return ".".join(parts)
    return f"{catalog}.{table}"


def table_arg_for_procedure(table: str) -> str:
    parts = table.split(".")
    if len(parts) >= 3:
        return ".".join(parts[-2:])
    return table


def print_sql(sql: list[str], output: Path | None = None) -> None:
    if output:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text("\n".join(sql) + "\n", encoding="utf-8")
        print(f"Wrote SQL: {output}")
        return
    for stmt in sql:
        print(stmt)


def states_equal(left: dict[str, Any], right: dict[str, Any]) -> bool:
    return all(left.get(field) == right.get(field) for field in PUBLISH_GUARD_FIELDS)


def state_summary(state: dict[str, Any]) -> str:
    snapshot = state.get("snapshot_id", "<missing>")
    metadata = state.get("metadata_location", "<missing>")
    schema = state.get("schema_id")
    schema_part = f", schema={schema}" if schema is not None else ""
    return f"snapshot={snapshot}, metadata={metadata}{schema_part}"


def command_init(args: argparse.Namespace) -> int:
    manifest_path = Path(args.manifest)
    if manifest_path.exists() and not args.force:
        print(f"Manifest already exists: {manifest_path}", file=sys.stderr)
        return 1
    write_json(manifest_path, empty_manifest(args.catalog))
    print(f"Initialized branch manifest for catalog {args.catalog}: {manifest_path}")
    return 0


def command_create(args: argparse.Namespace) -> int:
    manifest_path = Path(args.manifest)
    manifest = load_manifest(manifest_path)
    catalog = manifest["catalog"]
    branch = args.branch
    if branch in manifest["branches"] and not args.replace:
        print(f"Branch already exists: {branch}. Use --replace to overwrite.", file=sys.stderr)
        return 1

    state = load_state(Path(args.state_file))
    tables = parse_tables(args.tables)
    missing = [table for table in tables if table not in state]
    if missing:
        print(f"State file is missing tables: {', '.join(missing)}", file=sys.stderr)
        return 1

    branch_record = {
        "created_at": utc_now(),
        "tables": {},
    }
    for table in tables:
        branch_record["tables"][table] = {
            "branch_ref": branch,
            "base": copy.deepcopy(state[table]),
        }
    manifest["branches"][branch] = branch_record
    write_json(manifest_path, manifest)

    sql = [f"ALTER TABLE {table_sql_identifier(catalog, table)} CREATE BRANCH {branch};" for table in tables]
    print(f"Created logical branch record: {branch}")
    print_sql(sql, Path(args.sql_out) if args.sql_out else None)
    return 0


def command_diff(args: argparse.Namespace) -> int:
    manifest = load_manifest(Path(args.manifest))
    branch = manifest["branches"].get(args.branch)
    if not branch:
        print(f"Branch not found: {args.branch}", file=sys.stderr)
        return 1
    head_state = load_state(Path(args.state_file))

    changed = False
    for table, entry in branch["tables"].items():
        base = entry["base"]
        head = head_state.get(table)
        print(f"TABLE {table}")
        print(f"  base: {state_summary(base)}")
        if not head:
            print("  head: <missing>")
            print("  status: MISSING_HEAD_STATE")
            changed = True
            continue
        print(f"  head: {state_summary(head)}")
        if states_equal(base, head):
            print("  status: UNCHANGED")
            continue
        changed = True
        changed_fields = sorted(
            field
            for field in set(base.keys()) | set(head.keys())
            if base.get(field) != head.get(field)
        )
        print(f"  status: CHANGED ({', '.join(changed_fields)})")
    return 0 if changed else 0


def command_publish_ff(args: argparse.Namespace) -> int:
    manifest = load_manifest(Path(args.manifest))
    catalog = manifest["catalog"]
    branch = manifest["branches"].get(args.branch)
    if not branch:
        print(f"Branch not found: {args.branch}", file=sys.stderr)
        return 1
    main_state = load_state(Path(args.state_file))

    conflicts: list[str] = []
    sql: list[str] = []
    for table, entry in branch["tables"].items():
        base = entry["base"]
        current = main_state.get(table)
        if not current:
            conflicts.append(f"{table}: missing current main state")
            continue
        if not states_equal(base, current):
            conflicts.append(
                f"{table}: base [{state_summary(base)}] != main [{state_summary(current)}]"
            )
            continue
        table_arg = table_arg_for_procedure(table)
        sql.append(f"CALL {catalog}.system.fast_forward('{table_arg}', 'main', '{args.branch}');")

    if conflicts:
        print("Publish preflight failed with conflicts:", file=sys.stderr)
        for conflict in conflicts:
            print(f"  - {conflict}", file=sys.stderr)
        return 2

    print("Publish preflight passed. Generated fast-forward SQL:")
    print_sql(sql, Path(args.sql_out) if args.sql_out else None)
    return 0


def command_drop(args: argparse.Namespace) -> int:
    manifest_path = Path(args.manifest)
    manifest = load_manifest(manifest_path)
    catalog = manifest["catalog"]
    branch = manifest["branches"].get(args.branch)
    if not branch:
        print(f"Branch not found: {args.branch}", file=sys.stderr)
        return 1
    tables = list(branch["tables"].keys())
    del manifest["branches"][args.branch]
    write_json(manifest_path, manifest)

    sql = [f"ALTER TABLE {table_sql_identifier(catalog, table)} DROP BRANCH {args.branch};" for table in tables]
    print(f"Dropped logical branch record: {args.branch}")
    print_sql(sql, Path(args.sql_out) if args.sql_out else None)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Polaris branch overlay demo controller")
    parser.add_argument(
        "--manifest",
        default=str(DEFAULT_MANIFEST),
        help=f"Branch manifest path. Default: {DEFAULT_MANIFEST}",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    init = subparsers.add_parser("init", help="Initialize a branch manifest")
    init.add_argument("--catalog", required=True, help="Polaris catalog name")
    init.add_argument("--force", action="store_true", help="Overwrite an existing manifest")
    init.set_defaults(func=command_init)

    create = subparsers.add_parser("create", help="Create a logical branch record")
    create.add_argument("branch", help="Logical branch name")
    create.add_argument("--tables", required=True, help="Comma-separated table identifiers")
    create.add_argument("--state-file", required=True, help="Base table state JSON")
    create.add_argument("--replace", action="store_true", help="Replace an existing branch record")
    create.add_argument("--sql-out", help="Optional path to write generated SQL")
    create.set_defaults(func=command_create)

    diff = subparsers.add_parser("diff", help="Compare branch head state with recorded base")
    diff.add_argument("branch", help="Logical branch name")
    diff.add_argument("--state-file", required=True, help="Branch head table state JSON")
    diff.set_defaults(func=command_diff)

    publish = subparsers.add_parser("publish-ff", help="Preflight and generate fast-forward SQL")
    publish.add_argument("branch", help="Logical branch name")
    publish.add_argument("--state-file", required=True, help="Current main table state JSON")
    publish.add_argument("--sql-out", help="Optional path to write generated SQL")
    publish.set_defaults(func=command_publish_ff)

    drop = subparsers.add_parser("drop", help="Drop a logical branch record")
    drop.add_argument("branch", help="Logical branch name")
    drop.add_argument("--sql-out", help="Optional path to write generated SQL")
    drop.set_defaults(func=command_drop)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except (FileNotFoundError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
