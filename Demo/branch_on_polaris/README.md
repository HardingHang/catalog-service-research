# Polaris Branch Overlay Demo

这个目录是 P0 branch overlay demo 的完整工作区。它验证的是：不修改 Polaris core，能否用外置 controller 编排 Iceberg 表级 branch，跑通最小 logical branch 工作流。

## Layout

```text
Demo/branch_on_polaris/
  controller/   外置 P0 controller，维护 logical branch manifest，生成 SQL
  runtime/      Docker/Spark/RustFS/Polaris 运行环境与一键工作流
  docs/         架构图和 demo 说明资产
```

外部源码 checkout 已统一放在仓库根目录 `src_ref/`，例如 `src_ref/polaris`、`src_ref/iceberg`、`src_ref/nessie`。这些目录不参与 P0 runtime 启动，也不纳入本仓库版本管理；当前 runtime 仍使用 `apache/polaris:1.4.1` 镜像。

## Run P0

```powershell
powershell -ExecutionPolicy Bypass -File Demo/branch_on_polaris/runtime/workflows/run-p0-overlay.ps1
```

如果 Polaris/RustFS runtime 已经启动：

```powershell
powershell -ExecutionPolicy Bypass -File Demo/branch_on_polaris/runtime/workflows/run-p0-overlay.ps1 -SkipComposeUp
```

生成文件在 `Demo/branch_on_polaris/runtime/generated/`，包括 branch manifest、state JSON 和 controller 输出的 SQL。

## Boundaries

- P0 branch 是 logical branch，不是 Polaris 内置 ref。
- P0 复用 Iceberg 表级 branch；Polaris 仍只负责 Catalog、权限、REST 访问和 storage credential。
- P0 publish 是逐表 fast-forward，不是 catalog-wide 原子 merge。
- 当前 runtime 使用 Polaris 默认 in-memory persistence，只适合本地验证。
