# Polaris Branch Overlay Demo Controller

这是 Polaris branch-overlay demo 的最小 P0 controller。它不修改 Polaris，也不直接调用 Polaris API。它维护本地 branch manifest，比较 Iceberg table state JSON，并输出用于 Iceberg table branch 的 Spark SQL。

该目录只包含控制面逻辑和离线样例。真实 Polaris/RustFS/Spark 运行链路在 `Demo/branch_on_polaris/runtime` 中。

目标运行链路如下：

```text
Polaris + RustFS + Spark/Iceberg
  -> 采集 table state JSON
  -> branch_controller.py create
  -> 写入 Iceberg table branch
  -> 采集 branch state JSON
  -> branch_controller.py diff
  -> 采集当前 main state JSON
  -> branch_controller.py publish-ff
```

## State File Format

controller 读取如下 JSON：

```json
{
  "tables": {
    "sales.orders": {
      "snapshot_id": "1001",
      "metadata_location": "s3://warehouse/sales/orders/metadata/v1.metadata.json",
      "schema_id": "0"
    }
  }
}
```

`snapshot_id` 是 `publish-ff` preflight 的必填字段。`metadata_location`、`schema_id` 和 `schema` 是可选字段，但对 `diff` 输出有帮助。

真实 Iceberg table branch 写入会更新表 metadata 文件，即使 main ref 没有前进。因此 P0 runtime 的发布预检只用 ref 的 `snapshot_id` 判断 main 是否漂移；`metadata_location` 在这里仅作为诊断信息，不作为发布保护条件。

## Example

```powershell
python .\Demo\branch_on_polaris\controller\branch_controller.py init --catalog polaris

python .\Demo\branch_on_polaris\controller\branch_controller.py create dev1 `
  --tables sales.orders,sales.customers `
  --state-file .\Demo\branch_on_polaris\controller\examples\base-state.json

python .\Demo\branch_on_polaris\controller\branch_controller.py diff dev1 `
  --state-file .\Demo\branch_on_polaris\controller\examples\branch-head-state.json

python .\Demo\branch_on_polaris\controller\branch_controller.py publish-ff dev1 `
  --state-file .\Demo\branch_on_polaris\controller\examples\base-state.json
```

冲突验证：

```powershell
python .\Demo\branch_on_polaris\controller\branch_controller.py publish-ff dev1 `
  --state-file .\Demo\branch_on_polaris\controller\examples\drifted-main-state.json
```

冲突命令会以 exit code `2` 退出。

## Generated SQL

创建 branch：

```sql
ALTER TABLE polaris.sales.orders CREATE BRANCH dev1;
ALTER TABLE polaris.sales.customers CREATE BRANCH dev1;
```

发布 branch：

```sql
CALL polaris.system.fast_forward('sales.orders', 'main', 'dev1');
CALL polaris.system.fast_forward('sales.customers', 'main', 'dev1');
```

## Boundary

这个 P0 controller 只验证 workflow。它不是 catalog-wide atomic merge 实现。真正的 catalog-wide branch 需要在 Polaris 内部引入 ref-aware version store，或升级为能力更强的 sidecar service。
