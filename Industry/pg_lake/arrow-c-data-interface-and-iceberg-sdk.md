# Arrow C Data Interface 与 Iceberg SDK

## 1. Arrow C Data Interface 概述

### 背景

在 lakehouse 架构中，PostgreSQL FDW 和外部计算引擎（DuckDB、Iceberg SDK）之间需要交换列式数据。传统做法是行列转换 + 文本序列化，CPU 开销巨大。Arrow C Data Interface 用两个纯 C 结构体实现了零拷贝的列式数据传递，成为跨系统数据交换的事实标准。

### 核心理念

- **零依赖**：不需要链接 `libarrow.so`（数百 MB），只需要两个 `.h` 文件里的结构体定义
- **零拷贝**：传递的是内存 buffer 指针，数据不经过序列化/反序列化
- **C ABI**：任何 C/C++/Rust/Go 项目都能直接对接

支持 Arrow C Data Interface 的项目包括 DuckDB、DataFusion、Velox、ClickHouse、Polars、libiceberg 等。

## 2. 数据结构

Arrow C Data Interface 仅定义两个结构体。

### ArrowSchema — 描述数据 Schema

```c
struct ArrowSchema {
  const char* format;    // 类型描述字符串: "i"(int32), "l"(int64), "d"(float64),
                         // "u"(utf8), "z"(变长binary), "n"(null)
                         // 嵌套: "+l"(list<T>), "+s"(struct)

  const char* name;      // 列名
  const char* metadata;  // key=value 扁平字符串，Arrow 格式
  int64_t     flags;     // ARROW_FLAG_NULLABLE(0x01) 等

  int64_t            n_children;   // 子字段数量
  struct ArrowSchema** children;   // 子 schema 数组（struct 的列、list 的元素类型）
  struct ArrowSchema*  dictionary; // 字典编码的字典 schema

  // 释放回调
  void (*release)(struct ArrowSchema*);
  void* private_data;
};
```

### ArrowArray — 持有实际数据

```c
struct ArrowArray {
  int64_t length;       // 行数
  int64_t null_count;   // null 个数（-1 表示未知）
  int64_t offset;       // 逻辑起始偏移（零拷贝 slice 用）

  int64_t      n_buffers;   // 缓冲区数量
  const void** buffers;     // 原始内存指针数组

  int64_t            n_children;   // 子数组数量
  struct ArrowArray** children;    // 子数组（struct 的每列、list 的元素）
  struct ArrowArray*  dictionary;  // 字典数组

  // 释放回调
  void (*release)(struct ArrowArray*);
  void* private_data;
};
```

### 示例

表 `person(name utf8, age int32)` 有 3 行：

```
name: ["Alice", "Bob", null]
age:  [25,      30,    null]
```

ArrowSchema:

```
{
  format: "+s",
  n_children: 2,
  children[0]: {name: "name", format: "u"}    // utf8
  children[1]: {name: "age",  format: "i"}    // int32
}
```

ArrowArray:

```
{
  length: 3,
  children[0]: {                          // name 列
    n_buffers: 3,
    buffers[0] → bitmap: [1, 1, 0]       // 第2行 null
    buffers[1] → offsets: [0, 5, 8, 8]   // "Alice"=5B, "Bob"=3B
    buffers[2] → data: "AliceBob"
  },
  children[1]: {                          // age 列
    n_buffers: 2,
    buffers[0] → bitmap: [1, 1, 0]
    buffers[1] → data: [25, 30, -1]      // -1 是不关心的值（null 位置）
  }
}
```

读取 age 列：

```c
int32_t* data  = (int32_t*)array->children[1]->buffers[1];
uint8_t* valid = (uint8_t*)array->children[1]->buffers[0];

for (int i = 0; i < array->length; i++) {
    if (arrow_bit_is_set(valid, i))
        printf("age=%d\n", data[array->offset + i]);
    else
        printf("age=NULL\n");
}
```

## 3. Iceberg SDK 对 Arrow 的支持

所有主流 Iceberg SDK 都支持读取数据返回 Arrow RecordBatch。

### 3.1 C++ libiceberg（Apache 官方）

读取路径直接返回 `arrow::RecordBatch`：

```cpp
#include <iceberg/iceberg.h>

auto table = iceberg::Table::Load(dataset_path, options);

auto reader = table->NewScan()
    .Select({"col1", "col2"})              // 列裁剪
    .Filter(iceberg::EqualTo("col1", 42))  // 谓词下推
    .BuildScan();

// 逐批读取
arrow::RecordBatch batch;
while (reader->ReadNext(&batch)) {
    // 处理 batch
}
```

**导出为 Arrow C Data Interface** 给 FDW 使用：

```cpp
// arrow::RecordBatch → C Data Interface 结构体
ArrowArray  c_array;
ArrowSchema c_schema;
arrow::ExportRecordBatch(batch, &c_array, &c_schema);

// 此时 FDW 的 C 代码可以直接读 c_array 和 c_schema
// 不需要链接任何 Arrow C++ 库
```

**从 C Data Interface 导入** RecordBatch 回 C++ 侧：

```cpp
// C Data Interface → arrow::RecordBatch
arrow::ImportRecordBatch(&c_array, &c_schema, &batch);
```

`ExportRecordBatch` / `ImportRecordBatch` 是 Arrow C++ 库内置的函数，负责：
- 管理 `release` 回调的正确设置
- 嵌套类型（struct、list、map）的递归导出/导入
- 内存所有权的转移

### 3.2 Python PyIceberg

```python
table = catalog.load_table("db.t")
arrow_table = table.scan(
    row_filter="col1 = 42",
    selected_fields=("col1", "col2"),
).to_arrow()  # 一行代码 → pyarrow.Table（底层是 RecordBatch 集合）
```

### 3.3 Java iceberg-arrow 模块

```java
// Iceberg Java 的 iceberg-arrow 模块
// Iceberg 列存 → Arrow 向量格式
VectorSchemaRoot root = ArrowVectorSchemaRoot.create(schema, allocator);
ArrowWriter writer = new ArrowWriter(schema);
reader.readAll(writer);  // 逐批产生 Arrow RecordBatch
```

### 3.4 以 pg_lake 为例：数据行列转换的全流程

pg_lake 通过 DuckDB 代理读取数据湖文件。FDW（pg_lake 扩展）和 DuckDB（pgduck_server）是**两个独立进程**，之间通过 PG wire protocol 通信。下面聚焦数据在各环节的**行列格式变化**。

#### 3.4.1 当前数据流中的格式转换

```
                        pg_lake (FDW)                          pgduck_server (DuckDB)
                        ────────────                          ────────────────────────
                    ① 生成 SQL 文本 ────────────────────────────► ② 解析 & 执行
                           ▲                                             │
                           │                                   ③ 结果: DataChunk (列式, ~2048行/批)
                           │                                             │
                           │                                  ④ 逐行转文本 (type_conversion.c)
                           │                                             │
                    ⑥ 解析文本, 填充行式 Tuple ◄─────────── ⑤ PG wire protocol (DataRow / CopyData)
                           │
                           ▼
                    返回上层 Executor
```

**⑤ PG wire protocol 的作用**：FDW 和 DuckDB 在不同进程中，需要一个标准协议传输数据。pgduck_server 实现了 PG wire protocol，所以它可以直接用 `DataRow` 消息（每行一条，每列计数文本）或 `CopyData` 消息（CSV 格式批量打包，每 1MB 一批，`\N` 表示 NULL）把结果送回 FDW 侧。

pgduck_server 之所以选 PG wire protocol，是因为它天然与 PostgreSQL 兼容——FDW 不需要额外的网络库，直接用 PostgreSQL 的 libpq 内部接口就能收数据。但代价是：**协议要求数据是行式文本**，所以必须在步骤 ④ 把列式 DataChunk 逐行转成文本。

关键转换点在 **④**：DuckDB 列式 `DataChunk` → 逐行逐列 `to_text()` → 文本字符串。每行每列经历：读 `int32_t*` 调 `pg_ltoa()` → `"42"`，读 `bool*` → `"true"`，读 STRUCT 递归格式化为 `"(42,Bob)"`，等等。

到 FDW 侧时（⑥），文本又被解析回内存表示填入 `TupleTableSlot`。整条链路是 **列式 → 文本 → 行式**，两次格式转换，加上一次进程间通信。

#### 3.4.2 改用 Arrow C Data Interface 后

Arrow 路径的前提是 FDW 和计算引擎**在同一进程内**（FDW 直接链接 libiceberg 或 DuckDB 动态库），不再需要 PG wire protocol：

```
                        pg_lake (FDW)                         libiceberg / DuckDB
                        ────────────                         ──────────────────
                    ① 调用 SDK API ───────────────────────────► ② 执行扫描

                               ◄─────── ③ ArrowArray (列式, 零拷贝, 进程内传指针)

                        ④ 直接读 buffer 指针, 按列填充 TupleTableSlot
                               │
                               ▼
                         返回上层 Executor
```

数据流变成 **列式 → 列式 → 行式**。没有进程边界，没有 wire protocol，没有文本中间层。DuckDB 本身就提供 `duckdb_query_arrow()` API 直接返回 Arrow 格式，libiceberg 则是 `RecordBatch` → `ExportRecordBatch`。

#### 3.4.3 关键差异

```
当前 (两进程 + 文本路径):
  DataChunk(int32*) → pg_ltoa() → "42" → wire protocol → FDW 解析 → Datum

Arrow 路径 (同进程 + 零拷贝):
  DataChunk(int32*) → ArrowArray buffer(int32*) → FDW 直接读 → Datum
```

同样一个 int32，文本路径每次要 itoa + 字符串传输 + 解析回整数；Arrow 路径在同一个进程里传 buffer 指针，读出来就是 `int32_t` 直接赋值到 Datum。

核心变化不仅是去掉了文本序列化，更是**去掉了跨进程 + wire protocol 的强制行列转换**。

### 方案对比

| 方案 | 读 Iceberg 的角色 | 返回给 FDW 的格式 | 数据交换方式 | 适用场景 |
|------|-------------------|-------------------|-------------|---------|
| Iceberg SDK 直读 | `libiceberg` C++ 库 | `RecordBatch` → `ArrowArray` | Arrow C Data Interface 零拷贝 | 轻量部署，仅需 Iceberg |
| DuckDB 代理读 (Arrow路径) | DuckDB Iceberg 扩展 | `duckdb_query_arrow()` → `ArrowArray` | Arrow C Data Interface 零拷贝 | 需要 SQL 引擎，追求性能 |
| pg_lake 当前实现 (文本路径) | DuckDB Iceberg 扩展 | DuckDB DataChunk → 逐行文本 → PG wire protocol | 行式文本序列化 | 现状，有优化空间 |

无论选择哪种方案，FDW 这一侧收到的都是同一个 `ArrowArray` 结构体。上层的类型映射、数据填充逻辑可以完全复用。

## 4. FDW 与 SDK 集成架构

### 整体数据流

```
┌───────────────────────────────────────────────────────────────────┐
│ PostgreSQL                                                         │
│  ┌─────────┐      Arrow C Data Interface       ┌──────────────┐  │
│  │  FDW     │ ◄──────────────────────────────── │  Iceberg SDK │  │
│  │ (C 扩展) │    ArrowSchema + ArrowArray       │  (C++ 库)    │  │
│  │          │                                   │              │  │
│  │ 遍历     │                                   │ 读取 Iceberg  │  │
│  │ buffers  │                                   │ 表/文件       │  │
│  │ 填充     │                                   │ 返回         │  │
│  │ Tuple    │                                   │ RecordBatch  │  │
│  │ Table    │                                   │              │  │
│  │ Slot     │                                   │              │  │
│  └─────────┘                                   └──────────────┘  │
│       │                                                │          │
│       │ 行式 tuple                                           │ object  │
│       ▼                                                ▼ storage  │
│  ┌─────────┐                                   ┌──────────────┐  │
│  │ Executor│                                   │ S3 / HDFS /   │  │
│  │ (上层)  │                                   │ Local FS      │  │
│  └─────────┘                                   └──────────────┘  │
└───────────────────────────────────────────────────────────────────┘
```

### 假设 PG 已有向量化引擎

如果把前提推到"PG 有了完整的向量化执行引擎"，FDW 的设计会变得非常简单。当前的行式接口对比：

```
当前 FDW 接口 (行式):
  IterateForeignScan() → 返回一个 TupleTableSlot
      ↑
  每次调用返回一行，上层按需拉取下一行

向量化 FDW 接口:
  IterateForeignScanBatch() → 返回一个 ArrowArray + ArrowSchema
      ↑
  每次调用返回一批列式数据（如 64K 行），上层列式算子直接消费
```

FDW 的核心代码退化为一个薄层：

```c
bool IterateForeignScanBatch(ForeignScanState *node,
                              ArrowArray *out_array,
                              ArrowSchema *out_schema) {
    // 1. 调用 Iceberg SDK，读下一批
    arrow::RecordBatch batch;
    if (!reader->ReadNext(&batch))
        return false;

    // 2. RecordBatch → Arrow C Data Interface，直接给上层
    arrow::ExportRecordBatch(batch, out_array, out_schema);
    return true;
}
```

不需要遍历 buffer、不需要填 `TupleTableSlot`、不需要类型分发。ArrowArray 本身就是执行引擎理解的格式，上层 Filter/Project/Agg 直接操作列 buffer。

类型映射也退化为一次性工作——在 `BeginForeignScan` 时把 FDW 列的 PG type OID 和 Arrow format 字符串做一次对照表，执行阶段不做任何转换。

要新增的就是两个调用约定：

1. **列式迭代回调** — FDW 提供 `IterateForeignScanBatch`，返回 `(ArrowArray, ArrowSchema)` 
2. **内存生命周期** — 上层算子用完一批后调用 `ArrowArray.release()`，FDW 通过这个回调回收 SDK 的内存

其余部分（谓词下推、列裁剪、路径规划）和现有 FDW 机制完全一致——这些本来就在计划阶段完成了，只是执行阶段从"逐行拉取"变成"逐批列式拉取"。

## 5. 参考资料

- [Apache Arrow C Data Interface 规范](https://arrow.apache.org/docs/format/CDataInterface.html)
- [Apache Iceberg C++ (libiceberg)](https://github.com/apache/iceberg-cpp)
- [Arrow C++ ExportRecordBatch 文档](https://arrow.apache.org/docs/cpp/api/c_abi.html)
- [PyIceberg 文档](https://py.iceberg.apache.org/)
- [DuckDB Arrow 接口](https://duckdb.org/docs/api/c/api#arrow-interface)
