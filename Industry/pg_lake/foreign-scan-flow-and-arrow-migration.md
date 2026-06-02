# pg_lake ForeignScan 流程与 Arrow 数据转换方案

## 1. 当前 ForeignScan 流程总览

pg_lake 的 FDW 回调全部实现在 `pg_lake_table/src/fdw/pg_lake_table.c` 中（handler 注册见 line 536）。从查询计划到返回数据的完整链路分为六个阶段。

### 1.1 阶段一：规划 (Planning)

**入口**：`postgresGetForeignPlan` (line 1266) → 生成带占位符的 SQL 模板

```
postgresGetForeignPlan
  ├─ deparseSelectStmtForRel()           → 生成 SELECT ... FROM alias WHERE ...
  ├─ ParseQuery()                         → SQL 文本 → Query 树
  ├─ ReplacePgLakeTableWithReadTableFunc() → RTE_RELATION 替换为 RTE_FUNCTION
  │                                        → 函数名: __lake_read_table('schema.name', id)
  └─ PreparePGDuckSQLTemplate()           → Query 树 → SQL 文本 (存入 fdw_private)
```

**输出**：存储在 plan 中的 SQL 模板：
```sql
SELECT col1, col2 FROM __lake_read_table('public.my_table', 42) alias WHERE ...
```

### 1.2 阶段二：初始化 (Begin)

**入口**：`postgresBeginForeignScan` (line 1640)

```
postgresBeginForeignScan
  ├─ GetPGDuckConnection()    → 建立 libpq 连接到 pgduck_server (host=/tmp port=5332)
  ├─ CreatePgLakeScanSnapshot() → 读 Iceberg metadata，生成文件列表
  ├─ 创建 MemoryContext (batch_cxt, temp_cxt)
  └─ 构建 PgLakeScanState
```

**关键数据结构**：

```
PgLakeScanState                         PgLakeScanSnapshot
├─ query (char *)        ← SQL 模板    ├─ tableScans → PgLakeTableScan
├─ conn (PGDuckConnection*) ← libpq   │   ├─ relationId
├─ retrieved_attrs (List*)            │   ├─ fileScans → PgLakeFileScan
├─ scanSnapshot                        │   │   ├─ path, rowCount
├─ tuples[] / num_tuples               │   │   └─ deletedRowCount
└─ batch_cxt, temp_cxt                 │   └─ positionDeleteScans
                                       └─ ...
```

### 1.3 阶段三：发送查询 (Execute)

**入口**：`postgresIterateForeignScan` (line 1848) → `send_prepared_statement()` (line 3322)

只在首次调用时执行：

```
send_prepared_statement
  ├─ ReplaceReadTableFunctionCalls()  → 文本替换，__lake_read_table(...) → read_parquet(...)
  │    └─ BuildReadDataSourceQueryForTableScan()
  │         └─ ReadDataSourceQuery()  → 生成: SELECT ... FROM read_parquet([...paths...], ...)
  ├─ process_query_params()           → 参数值转文本
  └─ SendQueryWithParams()           → PQsendQueryParams() + PQsetSingleRowMode()
```

**最终发给 pgduck_server 的 SQL 示例**：
```sql
SELECT col1, col2
FROM (
  SELECT a, b AS col2
  FROM read_parquet(
    ['/data/file1.parquet', '/data/file2.parquet'],
    schema=map {0: {name: 'a', type: 'INTEGER'}, 1: {name: 'b', type: 'VARCHAR'}},
    filename='_pg_lake_filename',
    file_row_number=true
  )
  WHERE (_pg_lake_filename, file_row_number) NOT IN (
    SELECT (file_path, pos) FROM read_parquet('/data/delete.pos.parquet')
  )
) alias WHERE a > 10
```

### 1.4 阶段四：拉取结果 (Fetch)

**入口**：`postgresIterateForeignScan` → `fetch_more_data()` (line 3377)

```
fetch_more_data
  └─ for each row:
       ├─ WaitForResult()            → PQgetResult() 获取下一行 (PGresult*)
       └─ make_tuple_from_result_row() → PGresult 文本 → HeapTuple
```

### 1.5 阶段五：文本→Tuple 转换 (关键瓶颈)

**入口**：`make_tuple_from_result_row()` (line 5462)

```
make_tuple_from_result_row
  └─ for each column:
       ├─ PQgetisnull(res, row, j)    → 判断 NULL
       ├─ PQgetvalue(res, row, j)     → 获取文本值 (char*)
       └─ InputFunctionCall()          → 文本 → Datum (类型输入函数)
            → heap_form_tuple()        → HeapTuple
            → ExecStoreHeapTuple()     → TupleTableSlot
```

**输入**：`PGresult*` 中的一行，**文本格式**
**输出**：一个 `HeapTuple`，填入 `TupleTableSlot`

**数据格式转换链**：
```
DuckDB DataChunk (int32*)
  → pg_ltoa() → "42" (文本)
  → PG wire protocol → FDW 侧收到 "42" (文本)
  → InputFunctionCall(int4in, "42") → Datum (int32 again)
```

### 1.6 阶段六：清理 (End)

**入口**：`postgresEndForeignScan` (line 1915)

```
postgresEndForeignScan
  ├─ ReleasePGDuckConnection() → PQfinish()
  └─ 清理 MemoryContext
```

---

## 2. 关键接口与输入输出总览

| 接口 | 输入 | 输出 | 所属模块 |
|------|------|------|---------|
| `GetForeignRelSize` | PlannerInfo, RelOptInfo | 设置 `rows`, `width` | pg_lake_table / fdw |
| `GetForeignPaths` | PlannerInfo, RelOptInfo | 添加 ForeignPath | pg_lake_table / fdw |
| `GetForeignPlan` | PlannerInfo, RelOptInfo, ForeignPath | **SQL 模板文本** (存 fdw_private) | pg_lake_table / fdw (deparse.c) |
| `BeginForeignScan` | ForeignScanState | **PgLakeScanState** (含 SQL, conn, snapshot) | pg_lake_table / fdw |
| `IterateForeignScan` | ForeignScanState | **TupleTableSlot** (每次一行) | pg_lake_table / fdw |
| `send_prepared_statement` | PgLakeScanState | 发送 SQL 到 pgduck_server | pg_lake_table / fdw → pg_lake_engine / pgduck |
| `fetch_more_data` | PgLakeScanState | 批量 Tuple 数组 + 数量 | pg_lake_table / fdw → pg_lake_engine / pgduck |
| `make_tuple_from_result_row` | PGresult*, row index | **HeapTuple** (单个) | pg_lake_table / fdw |

---

## 3. 改造方案：用 Arrow C Data Interface 替代 DuckDB 文本通道

### 3.1 总体思路

保留阶段一（Planning）和阶段二（Begin）的大部分逻辑，替换阶段三到五。不再是"生成 SQL → 发 DuckDB → 文本返回"，而是：

```
Planning (不变) → Begin (不变, 保留 snapshot)
  → Iceberg SDK 打开文件
  → ReadNext() → arrow::RecordBatch
  → ExportRecordBatch → ArrowArray + ArrowSchema
  → 按列读出 Datum, 组装 TupleTableSlot
```

#### 以一条查询为例走通全流程

用户查询：

```sql
SELECT name, age FROM users WHERE age > 18;
```

**Step 1 — Planning（`postgresGetForeignPlan`）**
FDW 解析查询树，提取出需要的列 `(name, age)` 和 filter 条件 `age > 18`。生成列映射信息存入 `fdw_private`。

**Step 2 — Begin（`postgresBeginForeignScan`），从表名到文件列表**

```
postgresBeginForeignScan
  └─ CreatePgLakeScanSnapshot(rteList)
       └─ CreateTableScanForRelation(relationId)
            │  IsInternalIcebergTable(relationId) → true
            │
            ▼
```

**当前实现**：一条 SQL 直接从内部 catalog 表拿文件列表，不解析 Iceberg metadata：

```sql
SELECT path, row_count, content FROM lake_table.files
WHERE table_name = 'users'::regclass AND content = 0;
-- → data-file-1.parquet (1000 rows), data-file-2.parquet (800 rows)
```

写入时 pg_lake 把文件信息写入了 `lake_table.files`，读时直接查表，跳过了 metadata.json。这带来了几个问题：

- **自维护 Avro 解析**：pg_lake 自己实现了 manifest 和 manifest list 的 Avro 读写逻辑（`read_manifest.c`、`write_manifest.c`），随着 Iceberg spec 演进需要持续跟进
- **缓存一致性问题**：`lake_table.files` 是 metadata.json 的快照缓存。如果外部工具直接修改了 metadata.json（如 Spark 写的 compaction），缓存就和实际状态不一致
- **表维护：**
  - lake_table.files — 新数据文件 + delete 文件路径、行数
  - lake_table.data_file_column_stats — 每列在每个文件上的 min/max/null_count
  - lake_table.data_file_partition_values — 每个文件的 partition 值
  - lake_table.deletion_file_map — delete 文件关联到哪些 data 文件
  - lake_table.row_id_mappings — 行号→文件映射，UPDATE/DELETE 用
  - lake_iceberg.tables_internal — 每次 commit 产生新 snapshot 时更新 metadata_location


**改造后**：Iceberg SDK 接管，走标准 Iceberg 解析链。先查 `lake_iceberg.tables_internal` 拿到 metadata.json 地址：

```sql
SELECT metadata_location FROM lake_iceberg.tables_internal
WHERE table_name = 'users'::regclass;
-- → s3://bucket/path/metadata/v3.metadata.json
```

然后 SDK 内部按 Iceberg 规范展开：metadata.json → snapshot → manifest list → manifests → data files。FDW 侧不再关心这个过程，只拿到最终的文件列表：

```
                  metadata.json 解析                           
  ┌─────────────────────────────────────┐
  │ current-snapshot-id: 3              │
  │ snapshots: [{id:3, manifest-list:   │
  │   s3://.../snap-3.avro}]            │
  │ schemas: [fields: [                 │
  │   {id:1, name:"id",   type:"int"},  │
  │   {id:2, name:"name", type:"str"},  │
  │   {id:3, name:"age",  type:"int"}   │
  │ ]]                                  │
  └──────────────┬──────────────────────┘
                 ▼
  ┌─────────────────────────────────────┐
  │ snap-3.avro (manifest list)         │
  │  → manifest-1.avro                  │
  │  → manifest-2.avro                  │
  │  → manifest-3.avro (delete)         │
  └──────────────┬──────────────────────┘
                 ▼
  ┌─────────────────────────────────────┐
  │ manifest-1.avro:                    │
  │   data-file-1.parquet (1000 rows,   │
  │     age: [5,20])                    │
  │ manifest-2.avro:                    │
  │   data-file-2.parquet (800 rows,    │
  │     age: [10,17])                   │
  │ manifest-3.avro:                    │
  │   delete-file-1.parquet             │
  └─────────────────────────────────────┘
```

**重构点**：表的类型判断（`IsInternalIcebergTable`）、metadata 路径获取（查 `lake_iceberg.tables_internal`）保持不变。变化的是 metadata.json 之后的解析链——当前 pg_lake 自己解析 Avro，改造后由 Iceberg SDK 统一完成。

**Step 3 — 打开 Iceberg 表（新增，替代 `send_prepared_statement`）**

Step 2 得到了 `metadata_location` 和 `PgLakeScanSnapshot`。当前实现是把 snapshot 中的文件列表拼进 `read_parquet([...])` SQL 发给 DuckDB。改造后不再需要这一步——把 `metadata_location` 直接交给 SDK，文件裁剪和扫描规划全部由 SDK 内部完成：

```cpp
// 用 Iceberg SDK 打开表，SDK 内部完成 manifest 解析 + 文件裁剪
auto table = iceberg::Table::Load(metadata_path, file_io);
auto reader = table->NewScan()
    .UseSnapshot(3)                    // 从 metadata 拿到的 snapshot
    .Select({"name", "age"})           // 列裁剪 → 只读两列
    .Filter(iceberg::GreaterThan("age", iceberg::Literal(18)))  // 谓词下推
    .BuildScan();
```

SDK 内部利用 manifest 中的文件级 stats（min/max）跳过不包含满足 `age > 18` 的行的文件：

```
Manifest-1: data-file-1.parquet  min(age)=5,  max(age)=20  ← 可能匹配，保留
Manifest-2: data-file-2.parquet  min(age)=10, max(age)=17  ← max(17)<=18，跳过
```

**Step 4 — 逐批读取（`fetch_more_data`，改造后）**

```cpp
arrow::RecordBatch batch;
while (reader->ReadNext(&batch)) {
    // 每个 batch 约 64K 行，只含两列
    // batch.schema() → {name: utf8, age: int32}

    // 导出为 C Data Interface
    ArrowArray  c_array;
    ArrowSchema c_schema;
    arrow::ExportRecordBatch(batch, &c_array, &c_schema);
    // c_schema: "+s" → children: [{name:"name", format:"u"}, {name:"age", format:"i"}]
    // c_array:  children[0] = name 列 buffer, children[1] = age 列 buffer
}
```

**Step 5 — ArrrowArray → TupleTableSlot**

```c
// 遍历 batch 中的每一行
for (int64_t row = 0; row < c_array.length; row++) {
    // name 列 → Datum
    uint8_t *name_valid = c_array.children[0]->buffers[0];
    int32_t *name_offs  = c_array.children[0]->buffers[1];
    char    *name_data  = c_array.children[0]->buffers[2];
    if (arrow_bit_is_set(name_valid, row))
        values[0] = CStringGetTextDatum(name_data + name_offs[row]);
    else
        isnull[0] = true;

    // age 列 → Datum
    int32_t *age_data  = c_array.children[1]->buffers[1];
    uint8_t *age_valid = c_array.children[1]->buffers[0];
    if (arrow_bit_is_set(age_valid, row))
        values[1] = Int32GetDatum(age_data[row]);
    else
        isnull[1] = true;

    // 组装 Tuple
    HeapTuple tuple = heap_form_tuple(tupdesc, values, isnull);
    ExecStoreHeapTuple(tuple, slot, false);

    // 上层 executor 拿到 slot，返回给用户:
    //  name  | age
    //  Alice | 25
    //  Bob   | 30
}

// 用完释放
c_array.release(&c_array);
c_schema.release(&c_schema);
```

**类型映射表**：Arrow format 字符串 → PG Datum 的转换规则：

| Arrow format | Arrow C type | PG type | 转换方式 |
|---|---|---|---|
| `b` (bool) | `int8_t` | BOOL | `BoolGetDatum()` |
| `c` (int8) | `int8_t` | CHAR | `CharGetDatum()` |
| `s` (int16) | `int16_t` | INT2 | `Int16GetDatum()` |
| `i` (int32) | `int32_t` | INT4 | `Int32GetDatum()` |
| `l` (int64) | `int64_t` | INT8 | `Int64GetDatum()` |
| `f` (float32) | `float` | FLOAT4 | `Float4GetDatum()` |
| `g` (float64) | `double` | FLOAT8 | `Float8GetDatum()` |
| `u` (utf8) | `offset+data` 数组 | TEXT/VARCHAR | `CStringGetTextDatum()`，需 `palloc` |
| `z` (binary) | `offset+data` 数组 | BYTEA | `PointerGetDatum()`，需 `palloc` |
| `tdD` (date32) | `int32_t` | DATE | `DateADTGetDatum()` |
| `ttm` (time64) | `int64_t` | TIME | `TimeADTGetDatum()` |
| `tss:UTC` (timestamp) | `int64_t` | TIMESTAMPTZ | `TimestampTzGetDatum()` |
| `tss:` (notz) | `int64_t` | TIMESTAMP | `TimestampGetDatum()` |
| `+l` (list<T>) | 嵌套 ArrowArray | ARRAY[] | 递归展开子 array，逐元素转换 |
| `+s` (struct) | 嵌套 ArrowArray | COMPOSITE | 递归展开子字段 |

定长类型直接 `memcpy` 到 Datum（PG Datum 8 字节，足够容纳）。变长类型（utf8、binary）需 `palloc` 拷贝，因为 Arrow buffer 释放后不再可用。嵌套类型递归遍历子 `ArrowArray` 逐层展开。

**对比：Arrow 路径 vs 文本路径的类型转换开销**：

```
当前 (文本路径):
  DuckDB int32  →  pg_ltoa(42)  →  "42"  →  InputFunctionCall(int4in, "42")  →  Datum(42)
        ↑              ↑                    ↑                         ↑
   二进制数据    itoa 格式化     wire protocol 传输    PG 解析字符串回整数

Arrow 路径:
  ArrowArray int32  →  Int32GetDatum(42)
        ↑                    ↑
   二进制数据          直接赋值，无函数调用
```

**流程对比**：

```
改造前:
  PG Planner ────────────────────────── pg_lake_table / deparse
    → SQL 模板                           pg_lake_table / deparse
    → 文本替换 read_parquet(...)         pg_lake_table / transform_query_to_duckdb
    → libpq 发送                        pg_lake_engine / client
    → pgduck_server                     独立进程
    → DuckDB 解析 SQL                    DuckDB
    → DuckDB 读 metadata/Parquet         DuckDB
    → DataChunk                          DuckDB (内部列式)
    → to_text 逐行转文本                 pgduck_server / type_conversion
    → wire protocol                      PG wire protocol
    → libpq 接收                         pg_lake_engine / client
    → PQgetvalue → InputFunctionCall    pg_lake_table / fdw
    → Datum → Tuple                      pg_lake_table / fdw

改造后:
  PG Planner ────────────────────────── pg_lake_table (保留)
    → 列映射 (fdw_private)               pg_lake_table (保留)
    → 查 tables_internal 拿 metadata_path pg_lake_iceberg / catalog (保留)
    → 读 metadata.json                  Iceberg C++ SDK
    → manifest pruning                   Iceberg C++ SDK
    → 读 Parquet 文件                    Iceberg C++ SDK (Arrow C++)
    → arrow::RecordBatch                 Arrow C++ 库
    → ExportRecordBatch → ArrowArray     Arrow C++ 库 (C Data Interface)
    → 按列读 buffer → Datum              pg_lake_table / arrow (新增)
    → heap_form_tuple → Tuple            pg_lake_table / fdw (保留)
```

```cpp
// pg_lake_table/src/fdw/pg_lake_table.c
// 打开 Iceberg 表，配置列裁剪和谓词下推，准备好 reader 等待迭代
static void
postgresBeginForeignScan(ForeignScanState *node, int eflags) {
    PgLakeScanState *fsstate = (PgLakeScanState *)node->fdw_state;
    Relation rel = node->ss.ss_currentRelation;
    TupleDesc tupdesc = RelationGetDescr(rel);
    Oid relid = RelationGetRelid(rel);

    /*
     * metadata_path 获取
     *   来源: pg_lake_iceberg (保留)
     *   查询 lake_iceberg.tables_internal 拿到 metadata.json 的路径
     */
    char *metadata_path = GetIcebergMetadataLocation(relid, false);

    /*
     * IcebergFileIOCreate
     *   来源: Iceberg C++ SDK
     *   初始化对象存储访问 (S3 endpoint + 认证凭据)
     */
    IcebergFileIO *file_io = IcebergFileIOCreate(
        s3_endpoint, s3_access_key, s3_secret_key);

    /*
     * IcebergTableLoad
     *   来源: Iceberg C++ SDK
     *   输入: metadata.json 路径 + FileIO
     *   输出: IcebergTable 句柄 (内部完成 metadata 解析、snapshot 定位)
     */
    IcebergTable *table = IcebergTableLoad(metadata_path, file_io,
                                           ICEBERG_TABLE_LOAD_NO_VALIDATION);

    /*
     * NewScan + Select + Filter
     *   来源: Iceberg C++ SDK
     *   列裁剪: attnames 来自 plan 阶段收集的所需列名
     *   谓词下推: where_clause 来自 plan 阶段的 filter 表达式
     *   返回值是配置好的 IcebergScan，绑定了 snapshot
     */
    IcebergScan *scan = IcebergTableNewScan(table);
    IcebergScanUseSnapshotId(scan, IcebergTableCurrentSnapshotId(table));
    IcebergScanSelect(scan, attname_count, attnames);
    IcebergScanFilter(scan, where_clause);

    /*
     * build_column_map
     *   来源: pg_lake_table
     *   输入: PG TupleDesc + Iceberg schema
     *   输出: attnum → Arrow column index 映射数组
     *   PG 列 "name" 对应 Iceberg field id=2，在 Arrow schema 中位置为 0
     */
    IcebergSchema iceberg_schema = IcebergTableCurrentSchema(table);
    fsstate->arrowColMap = build_column_map(tupdesc, iceberg_schema);

    /*
     * IcebergScanToArrowReader
     *   来源: Iceberg C++ SDK / Arrow C++ 库
     *   输入: 配置好的 IcebergScan
     *   输出: arrow::RecordBatchReader*, 存为 opaque pointer
     *   后续 IterateForeignScan 通过它逐批读取
     */
    fsstate->icebergReader = IcebergScanToArrowReader(scan);

    /* MemoryContext 初始化 */
    fsstate->batch_cxt = AllocSetContextCreate(CurrentMemoryContext,
        "pg_lake batch context", ALLOCSET_DEFAULT_SIZES);
    fsstate->temp_cxt = AllocSetContextCreate(CurrentMemoryContext,
        "pg_lake temp context", ALLOCSET_DEFAULT_SIZES);
    fsstate->prepared_statement_sent = false;
    fsstate->eof_reached = false;
}
```


```cpp
// pg_lake_table/src/fdw/pg_lake_table.c
// 从 Iceberg SDK 逐批拉取 Arrow 列式数据，按列转 Datum 组装 HeapTuple，逐行返回给上层 executor。
static TupleTableSlot *
postgresIterateForeignScan(ForeignScanState *node) {
    /* fdw_state 在 BeginForeignScan 时初始化，持有 reader、缓存、列映射 */
    PgLakeScanState *fsstate = (PgLakeScanState *)node->fdw_state;
    /* 返回给上层 executor 的列容器 */
    TupleTableSlot  *slot    = node->ss.ss_ScanTupleSlot;
    /* 表的列定义，natts=列数，用于遍历和 heap_form_tuple */
    TupleDesc        tupdesc = node->ss.ss_currentRelation->rd_att;

    /* 缓存耗尽 (所有 tuple 已取完) 且未 EOF 时，拉取下一批 */
    if (fsstate->next_tuple >= fsstate->num_tuples && !fsstate->eof_reached) {
        MemoryContext oldctx = MemoryContextSwitchTo(fsstate->batch_cxt);
        fsstate->tuples     = NULL;
        fsstate->num_tuples = 0;
        fsstate->next_tuple = 0;

        /*
         * ReadNext + ExportRecordBatch
         *   来源: Iceberg C++ SDK / Arrow C++ 库
         *   输入: icebergReader (BeginForeignScan 时创建)
         *   输出: ArrowArray + ArrowSchema (纯 C 结构体, 列式 buffer 指针)
         *   列裁剪 & 谓词下推已在 BeginForeignScan 阶段配置到 reader 中
         */
        ArrowArray  c_array;
        ArrowSchema c_schema;
        {
            auto *reader = static_cast<arrow::RecordBatchReader *>(
                fsstate->icebergReader);
            std::shared_ptr<arrow::RecordBatch> batch;
            arrow::Status status = reader->ReadNext(&batch);
            if (!status.ok() || batch == nullptr) {
                fsstate->eof_reached = true;
                MemoryContextSwitchTo(oldctx);
                goto check_available;
            }
            arrow::ExportRecordBatch(*batch, &c_array, &c_schema);
        }

        /*
         * arrow_column_to_datum
         *   来源: pg_lake_table
         *   输入: ArrowArray 单列 buffer + ArrowSchema format 字符串
         *   输出: PG Datum + isnull
         *   定长类型直接读 buffer 赋值, 变长类型 palloc + memcpy
         */
        int64_t    nrows  = c_array.length;
        HeapTuple *tuples = palloc0(nrows * sizeof(HeapTuple));
        for (int64_t row = 0; row < nrows; row++) {
            Datum  values[MaxTupleAttributeNumber];
            bool   isnull[MaxTupleAttributeNumber];
            for (int attno = 0; attno < tupdesc->natts; attno++) {
                int arrow_col = fsstate->arrowColMap[attno];
                arrow_column_to_datum(&c_array.children[arrow_col],
                                      &c_schema.children[arrow_col],
                                      row, &values[attno], &isnull[attno]);
            }
            /*
             * heap_form_tuple
             *   来源: PG 内核
             *   输入: Datum 数组 + tupdesc
             *   输出: HeapTuple (行式)
             */
            tuples[row] = heap_form_tuple(tupdesc, values, isnull);
        }

        /*
         * release 回调
         *   来源: Arrow C Data Interface 规范
         *   由 ExportRecordBatch 设置, 释放 Arrow C++ 侧的内存
         */
        c_array.release(&c_array);
        c_schema.release(&c_schema);

        fsstate->tuples     = tuples;
        fsstate->num_tuples = nrows;
        MemoryContextSwitchTo(oldctx);
    }

check_available:
    if (fsstate->next_tuple >= fsstate->num_tuples)
        return ExecClearTuple(slot);

    /*
     * ExecStoreHeapTuple
     *   来源: PG 内核
     *   将 HeapTuple 装入 TupleTableSlot, 返回给上层 executor
     */
    ExecStoreHeapTuple(fsstate->tuples[fsstate->next_tuple++], slot, false);
    return slot;
}
```

### 3.2 各阶段变化详情

#### 阶段一 (Planning)：不变

`postgresGetForeignPlan` 生成的 SQL 模板不再用于执行，但保留其信息来推断需要哪些列、哪些 filter。也可以在 plan 阶段直接把列映射信息存入 `fdw_private`，跳过 SQL 生成。

#### 阶段二 (Begin)：小改

```
postgresBeginForeignScan
  ├─ ~~GetPGDuckConnection()~~             → 删除
  ├─ CreatePgLakeScanSnapshot()            → 删除（SDK 内部完成）
  ├─ 打开 Iceberg 表 (SDK API)             → 新增
  └─ 构建 ArrowReader (scan state)         → 新增
```

`PgLakeScanState` 新增字段：
```c
void          *icebergReader;     // Iceberg SDK reader handle
ArrowArray     currentBatch;      // 当前批次的 Arrow 数据
ArrowSchema    currentSchema;     // 当前批次的 schema
ColumnMapping *columnMap;         // PG列 → Arrow列 映射表
```

#### 阶段三 (发送查询)：完全替代

**原来**：`send_prepared_statement()` → 生成 SQL → libpq 发送

**改为**：不再需要。Iceberg SDK 在 `BeginForeignScan` 时已打开 reader，这里直接开始读数据。

#### 阶段四+五 (拉取+转换)：合并为 Arrow→Tuple 转换

```
fetch_more_data (改造后)
  └─ iceberg_reader->ReadNext(&batch)     → arrow::RecordBatch (列式)
       └─ ExportRecordBatch(batch, &c_array, &c_schema)
            └─ for each row in c_array.length:
                 ├─ 从各列 Array buffer 读值 → Datum
                 ├─ heap_form_tuple() → HeapTuple
                 └─ ExecStoreHeapTuple() → TupleTableSlot
```

**不再需要**：`WaitForResult()`、`PQgetvalue()`、`InputFunctionCall()`、文本到 Datum 的函数调用。

#### 阶段六 (End)：小改

```
postgresEndForeignScan
  ├─ ~~ReleasePGDuckConnection()~~   → 删除
  ├─ iceberg_reader->Close()         → 新增
  └─ 清理 MemoryContext               → 保留
```

