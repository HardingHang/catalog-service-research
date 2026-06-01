# iceberg_fdw 构建与验证说明（Phase 1）

本扩展为 **openGauss/GaussVector extension（C++）**，对应 doc 3 上半层 routine 骨架。
源码与设计依据见 `Industry/fdw-iceberg/3. ...md`。

## 代码布局（doc 3 §9.1）

```
iceberg_fdw/
  iceberg_fdw.control
  iceberg_fdw--1.0.sql          # handler/validator 声明 + CREATE FDW
  Makefile / CMakeLists.txt     # 源码树内（in-tree）contrib 构建
  src/
    iceberg_fdw.h               # 共享声明：回调原型 + fdw_private 索引
    iceberg_fdw.cpp             # handler/validator + FdwRoutine 注册 + Explain
    plan/plan_callbacks.cpp     # GetForeignRelSize/Paths/Plan（mock FileScanTask）
    plan/fdw_private.cpp        # fdw_private 编码
    exec/scan_state.h           # IcebergScanState
    exec/scan_callbacks.cpp     # Begin/Iterate/ReScan/End（mock tuple）
    exec/vec_scan_callbacks.cpp # VecIterateForeignScan（mock VectorBatch）
    ddl/validate_table_def.cpp  # ValidateTableDef 占位
  test/routine_skeleton_test.sql
```

## 构建路径

### A. 源码树内（design canonical，`Makefile`）

openGauss contrib 扩展原生走**源码树内构建**（`Makefile` 内
`include $(top_builddir)/src/Makefile.global`）。

1. 将本目录拷为 openGauss 源码树的 `contrib/iceberg_fdw/`；
2. 在已 `configure` 并构建过的源码树根执行 `make -C contrib/iceberg_fdw install`；
3. 在 gaussdb 实例中运行 `test/routine_skeleton_test.sql`。

### B. out-of-tree PGXS（本机 docker 验证，已实跑通过，`Makefile.pgxs`）

一键脚本：`bash test/docker/run_acceptance.sh`（基于
`opengauss/opengauss:5.0.0` + g++）。手工要点与三个坑：

1. **缺头补齐**：openGauss 安装的 `include/postgresql/server` 不完整（如
   `storage/file/fio_device_com.h` 缺失，经 `knl_variable.h`/`htup.h` 链触发）。
   用**同版本 v5.0.0 源码** `src/include` 以 `cp -rn`（no-clobber）补齐，
   不覆盖已安装/生成的头。
2. **`-fPIC`**：openGauss 把 `-fPIE` 放进 `CPPFLAGS`（内核以 PIE 构建），
   编译命令里 `CPPFLAGS` 在最后会盖掉 `-fPIC`，与 `-shared` 冲突。
   `Makefile.pgxs` 在 `include $(PGXS)` 后 `override CPPFLAGS += -fPIC` 使其最后生效。
3. **`PG_MODULE_MAGIC`**：`iceberg_fdw.cpp` 必须有 `PG_MODULE_MAGIC;`，否则
   `CREATE EXTENSION` 报 "missing magic block"。

> **openGauss 5.0.0 平台限制**：不支持 `DROP EXTENSION`（报 "EXTENSION is not
> yet supported"）。故验收脚本在全新数据库 `icetest` 内执行并整库 DROP，
> 不依赖 `DROP EXTENSION`。

> 落地 GaussVector 时按 doc 3 §2.4 复核内核头：`FdwRoutine` 字段集、
> `VecIterateForeignScan` 签名、`VectorBatch/ScalarVector` 布局、
> `make_foreignscan` 的 `outer_plan` 等。本版基于 openGauss master/v5.0.0 头编写，
> 关键 API（`make_foreignscan`/`create_foreignscan_path`/`VecIterateForeignScan`/
> `ExecStoreTuple`）两版一致。

## Phase 1 验收（doc 3 §10）

见 `test/routine_skeleton_test.sql`，实跑输出见 `test/routine_skeleton_test.out`。
**已在 openGauss 5.0.0 全部通过**：
- 行式 `count(*) = 3`（N = `ICEBERG_MOCK_ROWS`），列类型正确（a=1,2,3 / b='mock'）；✓
- `EXPLAIN` 含 `Foreign Scan on ft` 节点；✓
- 非法 OPTION 报 `invalid option "nope"`（带合法 key hint）；✓
- ReScan：相关子查询稳定返回 3/3/3；✓
- 向量执行器（`try_vector_engine_strategy='force'`）同样返回 3 行；✓

> **向量路径说明**：当前 plan 为 `Row Adapter → Vector Aggregate → Vector Adapter
> → Foreign Scan`，即执行器跑**行式** `IterateForeignScan` 再由 Vector Adapter
> 适配 row→vector。`VecIterateForeignScan` 已注册并编译通过，但要真正命中它需要
> 生成 `VecForeignScan` 计划节点（列存/ORC 式外表，取决于向量执行器对 FDW 类型的
> 判定）——这属 GaussVector 内核相关，留待 Phase 3 在真实内核上对齐（doc 3 §2.4/§10）。
