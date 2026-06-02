# Iceberg SDK 扫描层替换

用 Apache Iceberg SDK（Java，JNI） + Arrow C Data Interface 替换 pgduck_server（DuckDB）的数据扫描层。整个调用链是：

```
SELECT * FROM iceberg_table
  → FDW 拦截 (use_iceberg_sdk=true)
  → 加载 SDK .so 符号
  → metadata location
  → JNI → JVM → Iceberg SDK
  → Hadoop S3A → MinIO → 读 metadata + Parquet
  → Arrow C Data Interface → ArrowBatchToHeapTuples()
  → HeapTuple → PG 结果
```

## JNI 调用

**[iceberg-core]**  iceberg-java-SDK

**[iceberg-bridge]** 新建桥接类：c/c++ 通过此类调用 JNI 接口

**[arrow-c-data]**  新引入库：将 java 的 arrow 结构转为 c/c++ 接口

```
C/C++ 进程                         JVM 进程
═════════                         ════════

1. 启动 JVM
   JNI_CreateJavaVM()  ─────────────→  加载 fat jar (包含所有java依赖项)

2. 打开表
   NativeIcebergReader.openTable	  → new IcebergArrowBridge()			[iceberg-bridge]
                                      → VectorizedTableScanIterable(scan)	[iceberg-core]

3. 逐批读取
   readNextBatch()    ─────────────→  ArrowReader → Parquet 向量化读取 		[iceberg-core]
                                      Data.exportVector() per column		[arrow-c-data]
                                      ← 返回 ArrowArray* / ArrowSchema* 指针

4. Arrow C Data Interface import（零拷贝）
     →  解析 ArrowSchema format string 确定数据类型
     →  直接读 ArrowArray buffer 裸指针（共享同一块堆外内存）

5. 处理数据
    → 计算 / arrow → tuple / 过滤 / 聚合 ……

6. 释放
   releaseBatch()     ─────────────→  释放导出引用
   close()            ─────────────→  关闭读取器
   DestroyJavaVM()    ─────────────→  关闭 JVM
```

## 架构概览

```
pg_lake_iceberg_sdk/        (新扩展)
├── jvm_manager.c           JVM 生命周期（每 backend 一个，懒加载）
├── jni_reader.c            NativeIcebergReader JNI 封装
├── scan_bridge.c           S3 凭证注入 + JNI → Arrow → HeapTuple 编排
├── arrow_to_datum.c        Arrow C Data Interface → PG Datum 类型转换
└── test_funcs.c            SQL 测试函数 (pg_lake_iceberg_sdk_test_read)

pg_lake_table/src/fdw/pg_lake_table.c  (修改)
├── postgresBeginForeignScan  SDK 路径：跳过 GetPGDuckConnection，设 arrowScan
├── send_prepared_statement   SDK 路径：直接返回
├── fetch_more_data          SDK 路径：读 Arrow batch → HeapTuple
├── postgresEndForeignScan   SDK 清理
└── resolve_sdk_symbols      dlopen + dlsym 运行时加载 SDK 符号

pg_lake_table/src/ddl/create_table.c   (修改)
└── ErrorIfLocationIsNotEmpty  本地路径跳过检查

pg_lake_table/src/fdw/option.c        (修改)
└── pg_lake_iceberg validator   允许本地路径
```

## 关键代码修改

### 1. JVM 类路径（jvm_manager.c）

```c
// 只加载 fat jar（814MB，已内嵌 hadoop-aws + AWS SDK）
snprintf(classpath, sizeof(classpath), "-Djava.class.path=%s", jarPath);

JavaVMOption options[5];
options[0] = classpath;
options[1] = "--add-opens=java.base/java.nio=ALL-UNNAMED";
options[2] = "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED";
options[3] = "--add-opens=java.base/java.lang=ALL-UNNAMED";
options[4] = "-XX:+UseSerialGC";
```

### 2. S3 凭证注入（scan_bridge.c）

```json
{"catalogProps":{
    "warehouse":"s3a://pglake-test",
    "fs.s3.impl":"org.apache.hadoop.fs.s3a.S3AFileSystem",   // 注册 s3:// scheme
    "fs.s3a.access.key":"minioadmin",
    "fs.s3a.secret.key":"minioadmin",
    "fs.s3a.endpoint":"http://localhost:9000",
    "fs.s3a.path.style.access":"true"
},"tableId":"weixl/public/smoke_test/25825"}
```

### 3. FDW 扫描循环（pg_lake_table.c）

#### BeginForeignScan —— SDK 路径初始化

```c
// pg_lake_table.c:1887
fsstate->useIcebergSDK = false;
fsstate->arrowScan = NULL;
if (PgLakeTableUseIcebergSDK && !isUpdateDelete && fsplan->scan.scanrelid > 0)
{
    resolve_sdk_symbols();  // dlopen + dlsym 加载 pg_lake_iceberg_sdk.so
    if (sdk_syms_ok)
    {
        char *mp = GetIcebergMetadataLocation(rte->relid, false);
        if (mp)
        {
            fsstate->arrowScan = PgLakeArrowScanInitByMd_fp(mp, fsstate->tupdesc);
            if (fsstate->arrowScan)
                fsstate->useIcebergSDK = true;
        }
    }
}
```

SDK 路径完全跳过 `GetPGDuckConnection()`，`conn` 保持 NULL。

#### 调用链

`postgresIterateForeignScan` 是 FDW 的核心扫描循环，PG 执行器逐行调用它直到返回空 slot（EOF）：

```
postgresIterateForeignScan(node)
  │
  ├─ prepared_statement_sent == false? (首次调用 / ReScan 后)
  │   ├─ [DuckDB] send_prepared_statement(node)  → 向 pgduck_server 发送查询
  │   └─ [SDK]    直接标记 prepared_statement_sent = true
  │
  ├─ next_tuple >= num_tuples? (当前批次耗尽)
  │   ├─ eof_reached == false?
  │   │   └─ fetch_more_data(node)
  │   │       ├─ [SDK] PgLakeArrowScanNextBatch_fp() → JNI → Arrow → HeapTuple[]
  │   │       │   ├─ IcebergJNIReaderReadNextBatch()      [jni_reader.c]
  │   │       │   │   └─ NativeIcebergReader.readNextBatch() → nRows, Arrow ptrs
  │   │       │   ├─ ArrowBatchToHeapTuples()              [arrow_to_datum.c]
  │   │       │   │   └─ for each row: ArrowColumnToDatum() → heap_form_tuple()
  │   │       │   └─ IcebergJNIReaderReleaseBatch()        [jni_reader.c]
  │   │       │
  │   │       └─ [DuckDB] WaitForResult() → PGresult → HeapTuple[]
  │   │
  │   └─ 仍无数据 → ExecClearTuple(slot) 返回 EOF
  │
  └─ ExecStoreHeapTuple(fsstate->tuples[next_tuple++], slot)
```

#### fetch_more_data

```c
// pg_lake_table.c:3481 — SDK 路径 (完整分支)
if (fsstate->useIcebergSDK && fsstate->arrowScan && PgLakeArrowScanNextBatch_fp)
{
    MemoryContext oldcontext = MemoryContextSwitchTo(fsstate->batch_cxt);
    int numrows;
    fsstate->tuples = PgLakeArrowScanNextBatch_fp(fsstate->arrowScan, &numrows);
    fsstate->num_tuples = numrows;
    fsstate->next_tuple = 0;
    fsstate->eof_reached = (numrows == 0);
    MemoryContextSwitchTo(oldcontext);
    return;
}
// 否则走 DuckDB 路径: WaitForResult() + PQntuples() + 逐行 text→Datum 转换
```

SDK 路径关键差异：一次返回整个 batch 的 HeapTuple（非逐行拉取）；tuples 分配在 `batch_cxt` 中；`numrows == 0` 即 EOF。

#### Batch → HeapTuple 转换

`arrow_to_datum.c` 中逐行逐列转换，假设 Arrow 列顺序与 tupdesc 属性顺序一致：

```c
for (int r = 0; r < numRows; r++) {
    Datum *values = palloc(nCols * sizeof(Datum));
    bool  *isnull = palloc(nCols * sizeof(bool));
    for (int c = 0; c < nCols; c++)
        ArrowColumnToDatum(c_array->children[c], c_schema->children[c],
                           r, &values[c], &isnull[c]);
    tuples[r] = heap_form_tuple(tupdesc, values, isnull);
}
```

#### ReScan

```c
// pg_lake_table.c:1968
if (fsstate->useIcebergSDK && fsstate->arrowScan && PgLakeArrowScanDestroy_fp)
{
    PgLakeArrowScanDestroy_fp(fsstate->arrowScan);
    fsstate->arrowScan = NULL;
    fsstate->prepared_statement_sent = false;
    return;
}
```

销毁旧 reader，重置 `prepared_statement_sent`。下一次 `BeginForeignScan` 重新打开表（ReScan 会触发 `BeginForeignScan`）。

#### EndForeignScan

```c
// pg_lake_table.c:2003
if (fsstate->arrowScan && PgLakeArrowScanDestroy_fp)
{
    PgLakeArrowScanDestroy_fp(fsstate->arrowScan);
    fsstate->arrowScan = NULL;
}
// conn 为 NULL, ReleasePGDuckConnection 安全跳过
```

#### 批次状态（PgLakeScanState 关键字段）

| 字段 | SDK 路径 | DuckDB 路径 |
|---|---|---|
| `useIcebergSDK` | true | false |
| `arrowScan` | 非 NULL | NULL |
| `conn` | NULL (跳过) | 有效 libpq 连接 |
| `tuples` | `PgLakeArrowScanNextBatch` 分配 | `WaitForResult` + 逐行构造 |
| `num_tuples` | Arrow batch 行数 | `PQntuples(res)` |
| `next_tuple` | 递增，>= num_tuples 触发 fetch | 同 |
| `eof_reached` | `numrows == 0` 时置 true | `PGRES_TUPLES_OK` 且 0 行 |

### 4. Arrow → Datum 类型转换（arrow_to_datum.c）

| Arrow Format | PG Type | 转换方法 |
|---|---|---|
| `c` (int8) | CHAR | `CharGetDatum` |
| `s` (int16) | INT2 | `Int16GetDatum` |
| `i` (int32) | INT4 | `Int32GetDatum` |
| `l` (int64) | INT8 | `Int64GetDatum` |
| `f` (float) | FLOAT4 | `Float4GetDatum` |
| `g` (double) | FLOAT8 | `Float8GetDatum` |
| `b` (bool) | BOOL | `BoolGetDatum` |
| `u` (utf8) | TEXT | `CStringGetTextDatum` |
| `z` (binary) | BYTEA | `PointerGetDatum` |
| `tdD` (date32) | DATE | `DateADTGetDatum` |
| `ttm` (time64) | TIME | `TimeADTGetDatum` |
| `tss:UTC` (timestamp) | TIMESTAMPTZ | `TimestampTzGetDatum` |
| `tss:` (timestamp) | TIMESTAMP | `TimestampGetDatum` |

## SDK 路径 vs DuckDB 路径 区别

### S3 认证方式

两者完全独立，互不通用：

| | DuckDB 路径 | SDK 路径 |
|---|---|---|
| 认证机制 | DuckDB Secret | Hadoop Configuration |
| 配置方式 | `CREATE SECRET ...` SQL 语句 | JSON config 传入 JNI |
| 存储位置 | pgduck_server 内存 | JVM 系统属性 / `Configuration.set()` |
| 格式 | `TYPE S3, KEY_ID '...', SECRET '...'` | `fs.s3a.access.key`, `fs.s3a.endpoint` 等 |

DuckDB 用自己的 secret 机制处理 S3 认证，SDK 用 Hadoop S3A FileSystem 的配置属性。两个体系完全不通，所以 SDK 路径必须自己注入 MinIO 凭证。

### 文件路径解析

**DuckDB 路径**不需要解析 warehouse/tableId。pg_lake_iceberg 已经把 Iceberg 元数据（snapshot → manifest → manifest entry → data file）全部解析好了，DuckDB 只被喂最终的文件路径：

```
read_parquet('s3://.../data/90/908615c9-.../data_0.parquet')
read_parquet('s3://.../data/37/374d62ff-.../data_0.parquet')
...
```

DuckDB 不需要知道 Iceberg 的表结构——它只管逐文件读 Parquet。

**SDK 路径（HadoopTables）**相反——SDK 内部自己去找 metadata、解析 snapshot、找 manifest、读文件：

**metadata 直读**——直接把完整的 `metadata_location` 传给 Java，用 `TableMetadataParser.read()` 加载指定 metadata 文件。

```
→ SDK 读 v1.metadata.json  
→ SDK 解析 snapshot → manifest list → manifest → data files
→ SDK 读 Parquet → Arrow
```

也就是说 SDK 做了一遍 pg_lake_iceberg 已经做过的事。但好处是：Iceberg SDK 库本身具备完整的 Iceberg 语义支持（schema evolution、partition pruning、position/equality delete files 等），数据以 Arrow 列式格式返回，不需要文本转换。不过当前桥接使用 `VectorizedTableScanIterable`，该便利类会拒绝有 delete file 的表（抛 `UnsupportedOperationException`），这些语义能力需要切到更底层的 API 才能真正用上。

```
输入: s3://pglake-test/weixl/public/smoke_test/25825/metadata/00005-xxx.json
                                                      ← 直接用这个，不需要解析
  ↓ 转成 s3a://
  ↓ 注入 S3 凭证
  ↓ 
config JSON: {"metadataPath":"s3a://pglake-test/.../00005-xxx.json", "fs.s3a.xxx":...}
  ↓
Java: TableMetadataParser.read(io, metadataPath)
  ↓ 得到 TableMetadata → BaseTable → 正常读数据
```

C 侧代码（`pg_lake_table.c`）：
```c
char *mp = GetIcebergMetadataLocation(rte->relid, false);
// mp = "s3://pglake-test/weixl/public/t/12345/metadata/00005-xxx.json"
fsstate->arrowScan = PgLakeArrowScanInitByMd_fp(mp, fsstate->tupdesc);
```

Java 侧（`NativeIcebergReader.java`）：
```java
if (config.metadataPath != null && !config.metadataPath.isEmpty()) {
    HadoopFileIO io = new HadoopFileIO(hadoopConf);
    TableMetadata metadata = TableMetadataParser.read(io, config.metadataPath);
    StaticTableOperations ops = new StaticTableOperations(metadata, io);
    table = new BaseTable(ops, config.metadataPath);
}
```

每次 SELECT 都从 `iceberg_tables.metadata_location` 取当前最新路径（这个值由 pg_lake_iceberg 在每次 DML 后自动更新），所以读写一致性自动保证。

DuckDB 路径与 SDK 路径在文件定位上的本质区别：

| | DuckDB 路径 | SDK 路径 |
|---|---|---|
| 元数据解析 | pg_lake_iceberg 完成 | Iceberg SDK 完成 |
| 传给引擎的 | 完整 parquet 文件路径列表 | metadata_location |
| 引擎做什么 | 逐文件读 Parquet | 自己走完整 Iceberg 读流程 |

### SDK 能力边界

#### 已接入的能力（当前实现）

| 能力 | 实现方式 |
|---|---|
| **Iceberg 表全量扫描（无 delete file）** | `VectorizedTableScanIterable` 有 MOR 表时抛 `UnsupportedOperationException`，因此目前只能读取纯 append 或 COW 后的表 |
| **metadata 直读** | 传入 `metadata_location`，`TableMetadataParser.read()` 直接加载指定 metadata 文件，不依赖 `version-hint.text` |
| **S3 文件系统访问** | Hadoop S3A FileSystem（fat jar 内嵌），通过 JSON config 注入 endpoint / access key / secret key |
| **Arrow 列式输出** | iceberg-arrow 模块以 Arrow 格式读取 Parquet，通过 `Data.exportVector()` 导出，C 侧零拷贝访问堆外内存 |

#### 尚未接入的能力（Iceberg SDK 本身具备对应 API）

| 未接入的能力 | Iceberg SDK 对应的 API | 现状 |
|---|---|---|
| **delete files 处理 (MOR)** | GenericReader 读出行数据后转 Arrow，在 Java 侧自动获得 MOR 能力，但会丧失向量化路径的性能优势 | 有 MOR 表时抛 `UnsupportedOperationException`，需切换到 `planFiles()` 路径 |
| **列裁剪** | `TableScan.select(columns)` — 只读需要的列，减少 Parquet IO | 返回所有列 |
| **谓词下推** | `TableScan.filter(FilterExpression)` — 利用分区统计跳过不匹配文件 | 未传任何约束给 JNI reader |
| **Decimal 类型** | Arrow `'d'` (decimal128)，16 字节整数 + precision/scale | `arrow_to_datum.c` 中标记 TODO，返回 NULL |
| **嵌套类型 (list/struct/map)** | Arrow `'+'` 格式，可递归解析子结构 | 返回 NULL + WARNING |
| **动态凭证** | Hadoop `Configuration.set()` — JNI 侧可运行时注入任意配置 | 硬编码 `minioadmin/minioadmin@localhost:9000` |
| **非 S3 存储 (Azure/GCS)** | Hadoop 已内置 `wasb://` / `gs://` FileSystem 实现，fat jar 中已有依赖 | 只注册了 `s3://` / `s3a://` |
| **写入** | `OutputFileFactory` → `DataWriter` → `RowDelta` commit — SDK 具备完整写链 | 整个项目写入架构基于 DuckDB，未接入 SDK 写 |

#### 架构上不提供的能力

Iceberg SDK 定位是 Iceberg 表的数据读写引擎，以下能力不在其职责范围内：

| 能力 | 说明 |
|---|---|
| **SQL 计算引擎** | SDK 没有 SQL parser / optimizer / executor。WHERE / JOIN / aggregate 等计算由外部引擎（PG、DuckDB 等）完成 |
| **事务协调** | SDK 只负责单个表的读写，跨表事务、PG 事务边界由 PG + pg_lake_iceberg 管理 |
| **元数据持久化** | SDK 不维护 `iceberg_tables` 等 PG catalog，metadata_location 的追踪和更新由 pg_lake_iceberg 负责 |

## 构建与部署

### 依赖

```bash
# 系统依赖
sudo apt install openjdk-17-jdk libarrow-dev

# fat jar（814MB，包含 hadoop-aws + AWS SDK）
ls -lh /home/weixl/workspace/iceberg/arrow/build/libs/iceberg-arrow-1.12.0-SNAPSHOT-native-bridge.jar

# 安装到系统路径
sudo cp .../iceberg-arrow-1.12.0-SNAPSHOT-native-bridge.jar /usr/share/postgresql/18/
```

### 构建

```bash
# pg_lake_iceberg_sdk 扩展
make -C pg_lake_iceberg_sdk
sudo make -C pg_lake_iceberg_sdk install

# pg_lake_table（含 FDW 拦截）
make -C pg_lake_table
sudo make -C pg_lake_table install

# 重启 PG
sudo pg_ctlcluster 18 main restart
```

### 数据库初始化

```sql
CREATE EXTENSION pg_lake_iceberg_sdk;
```

## 运行测试

### 准备工作

1. **启动 pgduck_server**（非 SDK 路径仍需）：
```bash
sudo -u postgres pgduck_server --cache_dir /tmp/pg_lake_cache &
```

2. **启动 MinIO**：
```bash
docker run -d --name minio -p 9000:9000 \
  -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio server /data
```

3. **创建测试表**：
```sql
SET pg_lake_iceberg.default_location_prefix TO 's3://pglake-test';
CREATE TABLE smoke_test (id INT, name TEXT, val FLOAT) USING iceberg;
INSERT INTO smoke_test VALUES (1,'alpha',10.5),(2,'beta',20.3),(3,'gamma',30.7);
```

### 测试

```sql
SET pg_lake_iceberg.default_location_prefix TO 's3://pglake-test';

-- 建表 + INSERT（走 DuckDB）
CREATE TABLE smoke_test (id INT, name TEXT, val FLOAT) USING iceberg;
INSERT INTO smoke_test VALUES (1,'alpha',10.5),(2,'beta',20.3),(3,'gamma',30.7);

-- SDK 读取
SET pg_lake_table.use_iceberg_sdk = true;
SET pg_lake_table.enable_full_query_pushdown = false;
SELECT * FROM smoke_test ORDER BY id;
```

**结果**：
```
 id | name  | val  
----+-------+------
  1 | alpha | 10.5
  2 | beta  | 20.3
  3 | gamma | 30.7
(3 rows)
```

### 验证——读写一致性

```sql
-- DuckDB 写入
SET pg_lake_table.use_iceberg_sdk = false;
INSERT INTO smoke_test VALUES (4, 'delta', 40.0);
DELETE FROM smoke_test WHERE id = 2;

-- SDK 立即读取（元数据已自动更新）
SET pg_lake_table.use_iceberg_sdk = true;
SET pg_lake_table.enable_full_query_pushdown = false;
SELECT * FROM smoke_test ORDER BY id;
```

**结果**：
```
 id | name  | val  
----+-------+------
  1 | alpha | 10.5
  3 | gamma | 30.7
  4 | delta |   40
(3 rows)
```

INSERT 和 DELETE 立即对 SDK 可见——metadata 直读模式保证读写一致。

### 验证——对比 DuckDB 路径

```sql
SET pg_lake_table.use_iceberg_sdk = false;
SELECT * FROM smoke_test;
-- 结果应完全一致
```

### SQL 测试函数（本地 Iceberg 表）

```sql
SELECT * FROM pg_lake_iceberg_sdk_test_read(
    '/tmp/iceberg-e2e-warehouse', 'default/test_table');
-- 3 rows, 2 columns (id=1,2,3 name=alice,bob,carol)
```
