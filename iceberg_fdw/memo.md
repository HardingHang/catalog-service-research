# Iceberg FDW 开发与验收 Memo

目录：`iceberg_fdw/`
设计依据：`Industry/fdw-iceberg/3. Iceberg_FDW_Upper_Layer_Routine_Structure_And_SQL_Binding_Design.md`
形态：openGauss / GaussVector extension（C++），本版只做**上半层 routine 骨架**。

---

## 0. 当前结论

### 0.1 总体状态

本 memo 记录 main 分支截至 **Phase 1** 的状态。已完成：

1. extension 骨架：`iceberg_fdw.control` / `--1.0.sql` / `CREATE FDW`。
2. `FdwRoutine` 注册：6 个扫描回调 + `VecIterateForeignScan` + `ExplainForeignScan` + `ValidateTableDef`。
3. 规划三回调：`GetForeignRelSize/Paths/Plan`，`GetForeignPlan` 写死 mock FileScanTask 并编码 `fdw_private`。
4. 行式扫描生命周期：`Begin/Iterate/ReScan/End` + 批缓存 mock tuple。
5. 向量化入口：`VecIterateForeignScan` 造 mock VectorBatch（已注册编译，命中条件见 0.3）。
6. validator OPTIONS 校验、`ValidateTableDef` 占位。

main 此状态**不含** Phase 2（Catalog 解析接入）、Phase 3（向量形状对齐 + ReScan 完善 + ValidateTableDef 实化）、下半层 Arrow 转换、控制面 SQL。

Phase 1 验收：在 **openGauss 5.0.0** 实跑 `test/routine_skeleton_test.sql` 全部通过（输出见 `test/routine_skeleton_test.out`）。

| 验收项 | 结果 |
|---|---|
| 行式 `count(*)`（N=`ICEBERG_MOCK_ROWS`=3） | 3 ✓ |
| 列取值 a/b | `1,2,3` / `mock` ✓ |
| `EXPLAIN` 含 `Foreign Scan on ft` | ✓ |
| ReScan（相关子查询） | 稳定 3/3/3 ✓ |
| 非法 OPTION | `ERROR: invalid option "nope"` + hint ✓ |
| 向量执行器（`try_vector_engine_strategy='force'`） | 返回 3 ✓ |

### 0.2 本地运行约定

验证用 **out-of-tree PGXS**（省去数小时 openGauss 源码全量构建），一键脚本：

```bash
bash iceberg_fdw/test/docker/run_acceptance.sh
```

脚本流程：构建带 g++ 的镜像 `iceberg-fdw-build:5.0.0`（基于 `opengauss/opengauss:5.0.0`）→ 启动 gaussdb 容器 `igtest` → 用同版本 v5.0.0 源码补齐缺失内部头 → `make -f Makefile.pgxs install` → 跑验收 SQL。

design canonical 构建是**源码树内**（`Makefile`，拷为 `contrib/iceberg_fdw/` 后 `make install`）；落地真目标 GaussVector 时按 doc 3 §2.4 复核内核头。openGauss 源码克隆在 `src_ref/opengauss-server`（gitignore 下，sparse）。

### 0.3 当前已知限制

1. **验证目标是社区 openGauss 5.0.0，非 GaussVector**：关键 API（`make_foreignscan`/`create_foreignscan_path`/`VecIterateForeignScan`/`ExecStoreTuple`/`VectorBatch`）已核对 master 与 v5.0.0 一致；落地 GaussVector 仍需按 §2.4 复核内核头（字段集、向量批布局、签名）。
2. **向量路径未真正命中 `VecIterateForeignScan`**：当前 plan 为 `Row Adapter → Vector Aggregate → Vector Adapter → Foreign Scan`，执行器跑**行式** `IterateForeignScan` 再 row→vector 适配。命中专用向量入口需生成 `VecForeignScan` 节点（列存/ORC 式外表，取决于向量执行器对 FDW 类型判定）——留 Phase 3 在真实内核对齐。
3. **openGauss 5.0.0 不支持 `DROP EXTENSION`**（报 "EXTENSION is not yet supported"）：验收脚本改在全新库 `icetest` 内执行后整库 DROP，不依赖 `DROP EXTENSION`。
4. **执行阶段全为 mock**：`GetForeignPlan` 写死 FileScanTask，`Iterate/VecIterate` 返回常量桩数据，不接 Catalog、不读 Arrow。Phase 2 接 Catalog，下半层换 Arrow。
5. **谓词不下推**：`GetForeignRelSize` 行数固定估算，谓词全归 local 由执行器重查。

---

## 1. Phase 1：extension 骨架 + FdwRoutine 注册 + 扫描骨架（mock）

### 1.1 目标

把 Iceberg FDW 上半层 routine 在 openGauss extension 形态下接通：注册 `FdwRoutine`、实现规划三回调与扫描生命周期骨架，`SELECT 外表` 能走完整条 routine 接线（执行阶段先返回桩数据）。

### 1.2 产出

```
iceberg_fdw/
  iceberg_fdw.control / iceberg_fdw--1.0.sql   # handler/validator 声明 + CREATE FDW
  Makefile / CMakeLists.txt                    # 源码树内（in-tree）构建
  Makefile.pgxs                                # out-of-tree PGXS（验证用）
  src/
    iceberg_fdw.h                              # 共享声明：回调原型 + fdw_private 索引
    iceberg_fdw.cpp                            # handler/validator + FdwRoutine 注册 + Explain + PG_MODULE_MAGIC
    plan/plan_callbacks.cpp                    # GetForeignRelSize/Paths/Plan（mock FileScanTask）
    plan/fdw_private.cpp                        # IcebergBuildScanPrivate 编码
    exec/scan_state.h                          # IcebergScanState
    exec/scan_callbacks.cpp                    # Begin/Iterate/ReScan/End（mock tuple）
    exec/vec_scan_callbacks.cpp                # VecIterateForeignScan（mock VectorBatch）
    ddl/validate_table_def.cpp                 # ValidateTableDef 占位
  test/
    routine_skeleton_test.sql / .out           # Phase 1 验收 + 实跑输出
    docker/Dockerfile.build / run_acceptance.sh # docker 验证镜像与一键脚本
  BUILD.md
```

源码参考：openGauss `contrib/file_fdw/file_fdw.cpp`（handler/validator 模板）、`src/include/foreign/fdwapi.h`（`FdwRoutine` 全量字段）、`src/include/vecexecutor/vectorbatch.h`（`VectorBatch/ScalarVector`）。

### 1.3 验收

```bash
bash iceberg_fdw/test/docker/run_acceptance.sh
# 等价：gsql -d postgres -p 5432 -f test/routine_skeleton_test.sql
```

结果见 0.1 表。实跑输出存档于 `test/routine_skeleton_test.out`。

### 1.4 修复记录（构建三坑 + 一平台限制）

1. **安装 include 不完整**：openGauss `include/postgresql/server` 缺 `storage/file/fio_device_com.h` 等内部头（经 `knl_variable.h`/`access/htup.h` 链触发 `fatal error: No such file`）。修复：用**同版本 v5.0.0 源码** `src/include` 以 `cp -rn`（no-clobber）补齐，不覆盖已安装/生成的头（避免 `pg_config.h` 等生成头被源码版盖掉）。
2. **`-fPIC` 被 `-fPIE` 覆盖**：openGauss 把 `-fPIE`（内核以 PIE 构建）放进 `CPPFLAGS`，而编译命令 `CPPFLAGS` 在最后，盖掉 `-fpic`，链接 `-shared` 报 `relocation R_X86_64_PC32 ... can not be used when making a shared object`。修复：`Makefile.pgxs` 在 `include $(PGXS)` 后 `override CPPFLAGS += -fPIC`，使其成为最后生效的代码生成标志。
3. **缺 `PG_MODULE_MAGIC`**：`CREATE EXTENSION` 报 `incompatible library: missing magic block`。修复：`iceberg_fdw.cpp` 加 `PG_MODULE_MAGIC;`。
4. **平台限制**：openGauss 5.0.0 不支持 `DROP EXTENSION`。验收改在全新库执行后整库 DROP（见 0.3）。

另：API 核对——`psprintf` 在该 openGauss 基线不存在，mock 文本改用常量 `CStringGetTextDatum("mock")`。

---

## 2. 后续任务

### Phase 2：Catalog 解析接入 + OPTIONS 校验

- `src/catalog/catalog.h` + `catalog_resolve.cpp`：`IcebergCatalogResolveTable`（pg_native：SPI 查 `iceberg_catalog.tables`）。
- `GetForeignPlan` 用解析结果替换写死的 FileScanTask；OPTIONS 取 `namespace.table` → `metadata_location`。
- 验收：手工 `INSERT` 一行表身份后，`EXPLAIN (VERBOSE)` 可见解析来源；不存在表报明确 NoSuchTable；非法 OPTION 报错。
- 依赖：`iceberg_catalog.tables`/`table_schemas` 系统表（GaussVector 设计 §6.2）。

### Phase 3：向量化路径对齐 + ReScan + ValidateTableDef 实化

- `VecIterateForeignScan` 形状/容量按 GaussVector 向量执行器复核后对齐；确认 `VecForeignScan` 节点命中条件。
- ReScan 子计划重扫边界验证。
- `ValidateTableDef` 最小校验占位 → 校验 Iceberg 外表 OPTIONS 组合。

### Phase 4（下一版边界，本版不做）

- 下半层：`Iterate/VecIterate` 的 mock 换 `ArrowColumnToDatums`/`ArrowColumnToScalarVector`（doc 2 §6.3/§6.4 + §8 类型映射）。
- 控制面：namespace / table 的 SQL 函数与 Catalog 写侧。
