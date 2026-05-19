# Polaris Branch Overlay P0 Runtime

这个目录提供 P0 branch overlay demo 的本地可运行基线环境。

## Components

- `apache/polaris:1.4.1`：Polaris REST service 和 management service。
- `rustfs/rustfs:1.0.0-alpha.81`：本地 S3-compatible object storage。
- `amazon/aws-cli:2.34.9`：创建测试 bucket `bucket123`。
- `alpine/curl:8.17.0`：初始化 `quickstart_catalog`、principal、role 和 grants。
- `apache/spark:3.5.8-java17-python3`：可选 Spark SQL client profile，用于 table-level Iceberg 测试。

## Start Polaris

```powershell
docker compose -f Demo/branch_on_polaris/runtime/docker-compose.yml up -d
```

health check：

```powershell
curl http://localhost:8182/q/health
```

常用 endpoint：

- Polaris Iceberg REST：`http://localhost:8181/api/catalog`
- Polaris management API：`http://localhost:8181/api/management/v1`
- Polaris health：`http://localhost:8182/q/health`
- RustFS S3 API：`http://localhost:9000`
- RustFS UI：`http://localhost:9001`

catalog storage endpoint 配置为 `http://rustfs:9000`，因为 Spark SQL client 运行在同一个 Docker Compose network 内。宿主机访问 RustFS 仍使用 `http://localhost:9000`。

demo credentials：

- Realm：`POLARIS`
- Catalog：`quickstart_catalog`
- Root client：`root`
- Root secret：`s3cr3t`
- RustFS access key：`polaris_root`
- RustFS secret key：`polaris_pass`

## Runtime Layout

```text
runtime/
  docker-compose.yml
  workflows/run-p0-overlay.ps1       端到端 P0 编排
  bin/invoke-spark-sql.ps1           临时 Spark SQL client
  bin/collect-table-state.ps1        采集 Iceberg ref state JSON
  bin/test-runtime-smoke.ps1         Polaris REST smoke test
  bin/spark-catalog-config.ps1       Spark/Iceberg catalog 公共配置
  spark/01-init-main-tables.sql      创建 main 基准表
  spark/02-check-branches.sql        读取 main/dev_a/dev_b
  spark/03-advance-branches.sql      分别推进 dev_a/dev_b
  tools/collect_iceberg_ref_state.py 容器内 PySpark state 采集器
```

## Run Full P0 Overlay

一键执行 P0 闭环：

```powershell
powershell -ExecutionPolicy Bypass -File Demo/branch_on_polaris/runtime/workflows/run-p0-overlay.ps1
```

这个脚本会完成以下步骤：

1. 启动 Polaris/RustFS runtime。
2. 在 main 上创建 `sales.orders` 与 `sales.customers` 基准表。
3. 从真实 Iceberg `refs` metadata table 采集 main 的 `snapshot_id`，写入 `runtime/generated/state/main-base.json`。
4. 调用 `Demo/branch_on_polaris/controller/branch_controller.py` 创建 `dev_a` 与 `dev_b` 的 logical branch manifest，并生成 `CREATE BRANCH` SQL。
5. 执行生成的 SQL，创建 Iceberg 表级 branch。
6. 分别推进 `dev_a` 和 `dev_b`，采集 branch head state，执行 controller `diff`。
7. 对 `dev_a` 做 `publish-ff` 预检，执行生成的 fast-forward SQL。
8. 用 `dev_a` 发布后的 main state 验证 `dev_b` 发布会因 main 漂移而被拒绝。

生成文件位于 `runtime/generated/`，包括 branch manifest、采集的 state JSON 和 controller 生成的 SQL。

如果 runtime 已经启动，可跳过 `docker compose up -d`：

```powershell
powershell -ExecutionPolicy Bypass -File Demo/branch_on_polaris/runtime/workflows/run-p0-overlay.ps1 -SkipComposeUp
```

## Optional Spark SQL Shell

```powershell
docker compose -f Demo/branch_on_polaris/runtime/docker-compose.yml --profile spark run --rm spark-sql
```

创建 P0 branch demo 基准表：

```powershell
powershell -ExecutionPolicy Bypass -File Demo/branch_on_polaris/runtime/bin/invoke-spark-sql.ps1 `
  -SqlFile Demo/branch_on_polaris/runtime/spark/01-init-main-tables.sql
```

检查 main 和 branch 读取。需要先通过 `run-p0-overlay.ps1`，或手工执行 controller 生成的 `CREATE BRANCH` SQL：

```powershell
powershell -ExecutionPolicy Bypass -File Demo/branch_on_polaris/runtime/bin/invoke-spark-sql.ps1 `
  -SqlFile Demo/branch_on_polaris/runtime/spark/02-check-branches.sql
```

分别推进 `dev_a` 和 `dev_b`。同样需要 branch 已创建：

```powershell
powershell -ExecutionPolicy Bypass -File Demo/branch_on_polaris/runtime/bin/invoke-spark-sql.ps1 `
  -SqlFile Demo/branch_on_polaris/runtime/spark/03-advance-branches.sql
```

Spark profile 是 P0 runtime 层：创建 Iceberg table，执行 controller 生成的 branch SQL，读取 branch ref，并采集真实 table state JSON 供 `Demo/branch_on_polaris/controller/branch_controller.py` 使用。

手工采集某个 ref 的 state JSON：

```powershell
powershell -ExecutionPolicy Bypass -File Demo/branch_on_polaris/runtime/bin/collect-table-state.ps1 `
  -Tables sales.orders,sales.customers `
  -Ref main `
  -Output Demo/branch_on_polaris/runtime/generated/state/main.json
```

采集器读取 `sales.orders.refs`、`sales.customers.refs` 等 Iceberg metadata table。P0 发布保护只比较 ref 级 `snapshot_id`；`metadata_location` 如果能采集到，会作为诊断字段输出，但不会用于判断 main 是否漂移。

注意：Spark SQL catalog alias 是 `polaris`，Polaris 内部 catalog/warehouse 名是 `quickstart_catalog`。controller manifest 的 `catalog` 应使用 Spark SQL alias `polaris`，这样生成的 SQL 才是 `ALTER TABLE polaris.sales.orders ...` 和 `CALL polaris.system.fast_forward(...)`。

## Runtime Boundary

当前 runtime 使用 Polaris 默认 `in-memory` persistence，没有外部数据库。它适合验证 P0 workflow，但容器重建后 Polaris 控制面状态会丢失。生产化或 P1/P2 需要补持久化数据库、sidecar 状态库或 Polaris 内置 version-store。

## Stop

```powershell
docker compose -f Demo/branch_on_polaris/runtime/docker-compose.yml down
```
