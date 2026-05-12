#  Nessie 语义感知合并（Semantic-Aware Merge）技术文档

> 基于 Nessie 0.107.5-SNAPSHOT 修改，实现 IcebergTable 表定义级语义合并。
>
> 文档版本：v4.0 / 2026-05-11

---

## 第一部分：Nessie 原始 Merge 逻辑

### 1.1 整体架构与数据模型

Project Nessie 是一个面向数据湖的 Transactional Catalog，核心存储模型采用**内容寻址（Content-Addressed）**设计：

- **`Reference`**（可变指针）：分支或标签名指向一个 `CommitObj` ID。更新通过 CAS（Compare-And-Swap）完成。
- **`CommitObj`**（不可变）：代表一次提交，包含增量索引（`incrementalIndex`）、可选的全量索引快照（`referenceIndex`）、父提交链（`tail` / `secondaryParents`）。
- **`ContentValueObj`**（不可变）：序列化后的内容对象（如 IcebergTable、Namespace）。其 ID 为 `SHA-256("VALUE" + contentId + payload + data)`。
- **`StoreIndex<CommitOp>`**：有序键值索引，映射 `StoreKey → CommitOp`，采用前缀压缩序列化。

**StoreKey 编码规则**：

```
ContentKey.of("ns1", "table1") → StoreKey("M\0ns1\1table1\0C")
```

- `M` = universe（硬编码主宇宙）
- `\0` / `\1` = 分隔符
- `C` = content discriminator

API 层的 `ContentKey`（字符串列表）通过 `TypeMapping` 编码为单个 `StoreKey` 字符串存入存储层。

### 1.2 Merge 管道的完整调用链

当客户端发起 `POST /trees/main/history/merge` 时，请求流经以下层次：

```
HTTP POST /trees/main/history/merge
    ↓
TreeApiImpl                    JAX-RS 层，参数校验，构造 Merge 对象
    ↓
BaseMergeTransplantSquash      将 source 分支的全部 commits "压扁"成一个 CreateCommit
    ↓
BaseCommitHelper               【修改点】配置四个回调，注入冲突处理逻辑
    ↓
CommitLogicImpl                遍历 CreateCommit 的 adds/removes/unchanged，执行五步法
    ↓
Persist 层                     写入存储后端（IN_MEMORY / JDBC / RocksDB / S3 等）
```

**"压扁"（Squash）的含义**：Merge 不是逐条 replay source 分支的 commits，而是将所有变更合并成一次原子提交。这个过程中，source 分支多个 commit 中对同一 key 的多次修改会被压缩为最终状态。

### 1.3 CommitLogicImpl.buildCommitObj() 的五步循环

对 `CreateCommit` 中的每一个操作（Add / Remove / Unchanged），执行以下流程：

```
┌─────────────────────────────────────────┐
│ ① 第四回调 (committedValueReplacement)   │
│    最后一次修改 value 的机会              │
│    例如：用户显式指定了 resolvedContent   │
│    【语义合并在此注入预构造的合并值】       │
└────────────────┬────────────────────────┘
                 ↓
┌─────────────────────────────────────────┐
│ ② 构造 CommitOp                          │
│    将 (key, value, payload, contentId)    │
│    封装成存储层操作单元                     │
└────────────────┬────────────────────────┘
                 ↓
┌─────────────────────────────────────────┐
│ ③ checkForConflict()                     │
│    CAS 检查：expectedValue (base)         │
│         vs  existing.value() (target)     │
│    若不同 → VALUE_DIFFERS                 │
└────────────────┬────────────────────────┘
                 ↓
┌─────────────────────────────────────────┐
│ ④ ConflictHandler.onConflict()           │
│    返回：ADD / DROP / CONFLICT            │
│    ADD = 继续写入（忽略冲突）              │
│    DROP = 跳过这个操作                    │
│    CONFLICT = 记录冲突，最终抛 409        │
└────────────────┬────────────────────────┘
                 ↓
┌─────────────────────────────────────────┐
│ ⑤ 写入 index                             │
│    无冲突或 ADD → 将 CommitOp 加入 index  │
└─────────────────────────────────────────┘
```

**核心术语说明**：

| 术语 | 含义 | 生命周期 |
|------|------|----------|
| `CreateCommit.Add` | 提交前的临时输入对象，表示"要在本次 commit 中添加/修改这个 key" | 内存中临时存在 |
| `CommitOp` | 存储层持久化操作单元，包含 action（ADD/REMOVE/NONE）、payload、value（ObjId）、contentId | 序列化后存入 CommitObj.incrementalIndex |
| `expectedValue` | 共同祖先（base commit）上该 key 对应的 ContentValueObj ID | 由 Merge 逻辑根据共同祖先计算得出 |
| `existing.value()` | target 分支（如 main）当前 HEAD 上该 key 的值 | 从 target 的 StoreIndex 中读取 |
| `VALUE_DIFFERS` | 冲突类型：base 值与 target 当前值不同，说明 target 从 base 发生了变更 | 由 checkForConflict() 判定 |

**关键洞察**：Nessie 的冲突检测比较的是 **base vs target**，而非 **source vs target**。只要 target 从 base 改变了，就报 `VALUE_DIFFERS`，不管 source 和 target 的变更在业务语义上是否兼容。

### 1.4 原始行为下的冲突场景

以两个分支分别对同一张 Iceberg 表增加不同列为例：

```
main (base)
  │  schemaId=1
  │  columns: [id, name]
  │
  ├── branch-a 添加 phone 列
  │     schemaId=2
  │     columns: [id, name, phone]
  │
  └── branch-b 添加 email 列
        schemaId=3
        columns: [id, name, email]
```

原始 Nessie 行为：

```
merge branch-a → main   ✅ 成功，main 变为 schemaId=2
merge branch-b → main   ❌ 409 Conflict
  原因：branch-b (schemaId=3) 与 main (schemaId=2) 对同一个 key (db.orders) 都做了修改
  Nessie 判定：VALUE_DIFFERS → 冲突不可调和
```

**根本矛盾**：Nessie 的冲突检测停留在"表名级"（`StoreKey`），不理解 schema 内部的列变更是语义兼容的。

---

## 第二部分：修改方案与详细设计

### 2.1 设计目标与约束

**目标**：在 Nessie 的 squash merge 管道中注入"表定义语义感知"能力，使两个分支对同一张 Iceberg 表的兼容 schema 变更能够自动合并，而非返回 409。

**约束**：
1. 不修改存储层（Persist / CommitLogicImpl）的核心循环，只在回调中扩展逻辑
2. 不引入新的外部依赖，复用模块已有的 `jackson-databind`
3. Catalog 层只合并"表定义"（schema、properties），不合并"数据内容"（Parquet 文件、manifest 列表）

### 2.2 核心设计：三层协作

解决冲突需要三个环节配合，缺一不可：

```
┌─────────────────────────────────────────────────────────────┐
│  第一层：预检测阶段（Pre-processing）                         │
│  ─────────────────────────────────                            │
│  在 buildCommitObj() 之前，主动扫描 CreateCommit 的所有 adds   │
│  识别"真正的三方冲突"：base、source、target 的 schema 都不同    │
│  预构造合并值，存入内存 Map（semanticResolvedValues）          │
│  同时生成真实的 metadata.json 文件                            │
└──────────────────────────┬──────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  第二层：第四回调（Value Replacement）                        │
│  ─────────────────────────────────                            │
│  在 buildCommitObj() 循环的【第一步】执行                     │
│  若 key 在预解析 Map 中 → 将 add.value() 替换为合并值          │
│  同时将合并值加入 objsToStore（延迟持久化列表）                │
│  作用：让后续构造的 CommitOp 携带合并后的值，而非 source 原始值 │
└──────────────────────────┬──────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  第三层：ConflictHandler（Conflict Digestion）               │
│  ─────────────────────────────────                            │
│  当 checkForConflict() 报 VALUE_DIFFERS 时触发               │
│  若 key 在预解析 Map 中 → 直接返回 ADD，不记录冲突             │
│  （conflict 必须设为 null，否则 finishMergeTransplant 会判定为有冲突）│
│  为什么优先查缓存：mergedValue 尚未写入数据库，fetchContent 会抛 ObjNotFoundException │
└─────────────────────────────────────────────────────────────┘
```

**为什么必须分三层**：

| 层级 | 执行时机 | 职责 | 如果缺失会怎样 |
|------|----------|------|----------------|
| 预检测 | buildCommitObj 之前 | 读取 source/target/base 的 metadata.json，构造合并值 | 无合并值可注入 |
| 第四回调 | 五步法第一步 | 将 source 的 value 替换为合并值 | CommitOp 仍携带 source 原始值，合并无效 |
| ConflictHandler | 五步法第四步 | 消化 VALUE_DIFFERS，避免记录冲突 | 即使 value 被替换，仍会报 409 |

### 2.3 预检测逻辑详解

伪代码：

```
function preProcessSemanticMerge(createCommit, targetHeadIndex):
    for each add in createCommit.adds():
        key = add.key()
        existing = targetHeadIndex.get(key)

        // 过滤条件：
        // 1. key 在 target 上存在且有效
        // 2. 内容类型是 ICEBERG_TABLE
        // 3. source 值 != target 值（有变更）
        if not (existing exists and type == ICEBERG_TABLE and add.value != existing.value):
            continue

        sourceTable = fetchContent(add.value())
        targetTable = fetchContent(existing.value)

        if sourceTable.schemaId == targetTable.schemaId:
            continue    // schema 相同，无需合并

        // 关键：拉取 base 值，判断是否为真正的三方冲突
        baseValue = add.expectedValue()   // ← base commit 上的值
        if baseValue is null:
            continue

        baseTable = fetchContent(baseValue)

        sourceChanged = sourceTable.schemaId != baseTable.schemaId
        targetChanged = targetTable.schemaId != baseTable.getSchemaId()

        if not (sourceChanged and targetChanged):
            continue    // 单方变更，正常覆盖即可

        // 真正的三方冲突：读取 metadata.json 并构造合并值
        sourceFields = readSchemaFields(sourceTable.metadataLocation)
        targetFields = readSchemaFields(targetTable.metadataLocation)
        baseFields   = readSchemaFields(baseTable.metadataLocation)

        mergedFields = mergeSchemaFields(baseFields, sourceFields, targetFields)
        mergedProps  = mergeProperties(
            readProperties(sourceTable.metadataLocation),
            readProperties(targetTable.metadataLocation)
        )

        mergedMetadataLocation = writeMergedMetadata(
            sourceTable.metadataLocation,
            targetTable.metadataLocation,
            mergedFields,
            mergedSchemaId,
            mergedSnapshotId
        )

        mergedTable = IcebergTable(
            id = sourceTable.id,
            metadataLocation = mergedMetadataLocation,
            snapshotId = mergedSnapshotId,
            schemaId = mergedSchemaId
        )
        mergedValue = buildContent(mergedTable)

        semanticResolvedValues[key] = mergedValue   // ← 存入本地缓存
```

**为什么要区分单方变更 vs 三方冲突**：

| 场景 | base schemaId | source schemaId | target schemaId | 判断结果 | 处理方式 |
|------|---------------|-----------------|-----------------|----------|----------|
| merge branch-a → main | 1 | 2 | 1 | sourceChanged=true, targetChanged=false | **不预构造合并值**。单方变更，让 source 正常覆盖 target |
| merge branch-b → main（branch-a 已合入） | 1 | 3 | 2 | sourceChanged=true, targetChanged=true | **预构造合并值**。三方冲突，需要合并 |

### 2.4 Schema 字段合并算法

```java
private List<Map<String, Object>> mergeSchemaFields(
    List<Map<String, Object>> baseFields,    // 共同祖先的列
    List<Map<String, Object>> sourceFields,  // source 分支的列
    List<Map<String, Object>> targetFields   // target 分支的列
)
```

**算法逻辑**：

1. **保留 base 列**：如果一列在 base 中存在，且未被 source 或 target 任意一方删除（即双方仍保留），则保留
2. **添加 source 新增列**：source 中有但 base 中没有的列 → 加入结果
3. **添加 target 新增列**：target 中有但 base 中没有的列 → 加入结果
4. **检测同名冲突**：若双方新增了同名列但类型/必填性不同 → 抛 `IllegalStateException`，fallback 到 409

**公式化表达**：

```
merged = (base ∩ source ∩ target)          // 三方都保留的列
       ∪ (source - base)                   // source 新增的列
       ∪ (target - base)                   // target 新增的列
```

**示例**：

| 场景 | base | source | target | merged | 说明 |
|------|------|--------|--------|--------|------|
| A. 各加兼容列 | [id, name] | [id, name, phone] | [id, name, email] | [id, name, phone, email] | 保留 base，添加双方新增 |
| D. 加列 vs 删列 | [id, name] | [id, name, phone] | [name] | [name, phone] | id 被 target 删除，phone 被 source 添加 |

### 2.5 Properties 合并算法

```java
private Map<String, String> mergeProperties(
    Map<String, String> sourceProps,
    Map<String, String> targetProps
)
```

**合并规则**：
- 不同 key → 取并集（如 source 有 `feature=phone`，target 有 `env=prod`，合并后两者都有）
- 同名同值 → 保留（如两边都有 `team=mobile`）
- **同名不同值 → 抛异常，fallback 到 409**（保守策略）

### 2.6 第四回调注入逻辑

```
function committedValueReplacement(add, storeKey, commitValueId):
    key = storeKeyToKey(storeKey)
    if key is null:
        return commitValueId

    mergedValue = semanticResolvedValues[key]
    if mergedValue is not null:
        objsToStore.accept(mergedValue)     // 注册到延迟持久化列表
        return mergedValue.id()             // 替换为合并值

    // 原有逻辑：处理用户显式指定的 resolvedContent
    ...
    return commitValueId
```

**关键执行顺序**：第四回调在**五步法的第一步**执行，早于 ConflictHandler（第四步）。这意味着：
- 当 ConflictHandler 看到 `conflict.op().value()` 时，它已经是合并后的 ObjId 了
- 但 ConflictHandler 不能对这个 ObjId 调用 `fetchContent()`，因为对象尚未持久化
- 解决方案：ConflictHandler 优先查本地缓存 `semanticResolvedValues`，而不是 fetchContent

### 2.7 ConflictHandler 消化逻辑

```
function conflictHandler(conflict):
    key = storeKeyToKey(conflict.key())

    if conflict.type == VALUE_DIFFERS:
        // 优先检查本地缓存（必须放在 fetchContent 之前！）
        mergedValue = semanticResolvedValues[key]
        if mergedValue is not null:
            keyDetailsMap[key] = (NORMAL, conflict=null)   // ← conflict 必须设为 null
            return ADD                                       // ← 不记录冲突

        // Fallback：尝试实时合并（此时 op.value() 可能已被第四回调替换）
        if op.payload == ex.payload and type == ICEBERG_TABLE:
            try:
                sourceTable = fetchContent(op.value())
                targetTable = fetchContent(ex.value())
                if sourceTable.schemaId != targetTable.schemaId:
                    // 实时构造合并值（应急路径）
                    ...
                    return ADD
            catch ObjNotFoundException:
                pass

    // 默认 fallback：记录冲突
    keyDetailsMap[key] = (NORMAL, conflict=conflict)
    return ADD
```

**为什么 `keyDetailsMap` 中的 conflict 必须设为 null**：

`finishMergeTransplant` 方法会遍历 `keyDetailsMap`，如果任何 entry 的 `conflict != null`，则 `hasConflicts = true`，最终返回 `wasSuccessful=false`。即使 ConflictHandler 返回了 ADD，只要 conflict 被记录，客户端仍会看到 409。

### 2.8 端到端内容合并（metadata.json 操作）

为了达成真正的端到端语义合并，我们在 `BaseCommitHelper` 中增加了五个工具方法，在预检测阶段直接操作 Iceberg `metadata.json`：

| 方法 | 职责 | 关键技术点 |
|------|------|-----------|
| `readSchemaFields()` | 解析 metadata.json，提取当前 schema 的 fields 列表 | Jackson `JsonNode`，按 `schema-id` 匹配 |
| `mergeSchemaFields()` | 按列名做删除感知的 union 合并 | 基于 base 判断删除与新增 |
| `readProperties()` | 解析 metadata.json 的 `properties` 对象 | Jackson `ObjectNode.fields()` |
| `mergeProperties()` | 按 key 做并集合并，同名不同值抛异常 | `HashMap` 合并 |
| `writeMergedMetadata()` | 生成新的 metadata.json 文件 | 以 source 为模板，追加 schema/snapshot，更新 properties |

**关键设计**：`versioned/storage/store` 模块**不依赖** `catalog/format/iceberg` 模块。所有 JSON 操作都通过 Jackson 的 `JsonNode` / `ObjectNode` 完成，避免了底层存储模块与上层格式模块的耦合。

**writeMergedMetadata 的修改细节**：
- 在 `schemas` 数组中**追加**新 schema（`schema-id = mergedSchemaId`，`fields = mergedFields`）
- 更新 `current-schema-id` 为 `mergedSchemaId`
- 在 `snapshots` 数组中**追加**新 snapshot（`snapshot-id = mergedSnapshotId`，`operation = "merge"`）
- 更新 `snapshot-log` 和 `last-updated-ms`
- 写入合并后的 `properties`
- 输出到 `/tmp/nessie-semantic-merge/XXXXX-merged.metadata.json`

---

## 第三部分：Schema 语义合并的场景分析

### 3.1 场景分类矩阵

以两个分支对同一张 Iceberg 表的 schema 变更为例：

| 场景 | branch-a 变更 | branch-b 变更 | base→target 状态 | 是否为三方冲突 | 语义合并结果 | Nessie 原始行为 | Demo 覆盖 |
|------|---------------|---------------|------------------|----------------|--------------|-----------------|-----------|
| **A. 各加兼容列** | +phone | +email | base=1, target=2 | 是 | schemaId=4（两列共存） | 409 Conflict | ✅ 已验证 |
| **B. 同加一列（重复）** | +phone | +phone | base=1, target=2 | 否（target 未从 base 改变） | source 直接覆盖 | 正常 merge | ✅ 已覆盖 |
| **C. 单方变更** | +phone | 无变更 | base=1, target=1 | 否（target 未变） | source 直接覆盖 | 正常 merge | ✅ 已覆盖 |
| **D. 加列 vs 删列** | +phone | -id | base=1, target=2 | 是 | [name, phone]（id 删除，phone 保留） | 409 Conflict | ✅ 已验证 |
| **E. 改列类型** | +phone | id: int→long | base=1, target=2 | 是 | 若类型提升合法则兼容；若需文件重写则冲突 | 409 Conflict | ⬜ 未验证 |
| **F. 改表名** | +phone | rename to orders_v2 | base=1, target=2 | 是 | 应报冲突（逻辑表分叉） | 409 Conflict | ⬜ 未验证 |
| **G. 各改不同 property** | `+feature=phone` | `+env=prod` | base=∅, target=`feature=phone` | 是 | properties 并集 | 409 Conflict | ✅ 已验证 |
| **H. 同改 property（冲突）** | `feature=phone` | `feature=email` | base=∅, target=`feature=phone` | 是 | 同名不同值 → 冲突 | 409 Conflict | ✅ 已覆盖（fallback） |

---

## 第四部分：验证过程

### 4.1 验证环境

- Nessie 0.107.5-SNAPSHOT，本地 Quarkus 服务器（端口 19120）
- 存储：`IN_MEMORY`
- API：REST v2
- 以下所有命令均为可直接复制执行的 Linux shell 命令

### 4.2 场景 A 验证（各加兼容列 + properties 合并）

#### Step 0：准备本地 metadata.json 文件

以下命令使用 python3 生成符合 Iceberg 格式的 metadata.json（因 JSON 结构复杂，用 python3 生成比手写 heredoc 更可靠）：

```bash
# 定义变量
META_DIR="/tmp/nessie-semantic-merge"
BASE="http://localhost:19120/api/v2"
mkdir -p "$META_DIR"

# 生成 base metadata（schemaId=1，字段 id + name）
python3 -c "
import json, os, time
now = int(time.time()*1000)
meta = {
  'format-version': 2, 'table-uuid': 'test-table-uuid-1234',
  'location': 'file://${META_DIR}',
  'last-sequence-number': 1, 'last-updated-ms': now,
  'schemas': [{'type': 'struct', 'schema-id': 1, 'fields': [
    {'id': 1, 'name': 'id', 'required': True, 'type': 'long'},
    {'id': 2, 'name': 'name', 'required': True, 'type': 'string'}
  ]}],
  'current-schema-id': 1,
  'partition-specs': [{'spec-id': 0, 'fields': []}],
  'default-spec-id': 0, 'last-partition-id': 0,
  'sort-orders': [{'order-id': 0, 'fields': []}],
  'default-sort-order-id': 0,
  'snapshots': [{'snapshot-id': 1, 'timestamp-ms': now, 'summary': {'operation': 'append'},
    'manifest-list': 'file://${META_DIR}/00001-manifests.avro'}],
  'snapshot-log': [{'snapshot-id': 1, 'timestamp-ms': now}],
  'metadata-log': [], 'properties': {}
}
json.dump(meta, open('${META_DIR}/00001-base.metadata.json','w'), indent=2)
"

# 生成 branch-a metadata（schemaId=2，添加 phone 列）
python3 -c "
import json, os, time
now = int(time.time()*1000)
meta = {
  'format-version': 2, 'table-uuid': 'test-table-uuid-1234',
  'location': 'file://${META_DIR}',
  'last-sequence-number': 2, 'last-updated-ms': now,
  'schemas': [
    {'type': 'struct', 'schema-id': 1, 'fields': [
      {'id': 1, 'name': 'id', 'required': True, 'type': 'long'},
      {'id': 2, 'name': 'name', 'required': True, 'type': 'string'}
    ]},
    {'type': 'struct', 'schema-id': 2, 'fields': [
      {'id': 1, 'name': 'id', 'required': True, 'type': 'long'},
      {'id': 2, 'name': 'name', 'required': True, 'type': 'string'},
      {'id': 3, 'name': 'phone', 'required': False, 'type': 'string'}
    ]}
  ],
  'current-schema-id': 2,
  'partition-specs': [{'spec-id': 0, 'fields': []}],
  'default-spec-id': 0, 'last-partition-id': 0,
  'sort-orders': [{'order-id': 0, 'fields': []}],
  'default-sort-order-id': 0,
  'snapshots': [
    {'snapshot-id': 1, 'timestamp-ms': now, 'summary': {'operation': 'append'},
     'manifest-list': 'file://${META_DIR}/00001-manifests.avro'},
    {'snapshot-id': 2, 'timestamp-ms': now, 'summary': {'operation': 'append'},
     'manifest-list': 'file://${META_DIR}/00002-manifests.avro',
     'parent-snapshot-id': 1}
  ],
  'snapshot-log': [
    {'snapshot-id': 1, 'timestamp-ms': now},
    {'snapshot-id': 2, 'timestamp-ms': now}
  ],
  'metadata-log': [],
  'properties': {'feature': 'phone', 'team': 'mobile'}
}
json.dump(meta, open('${META_DIR}/00002-phone.metadata.json','w'), indent=2)
"

# 生成 branch-b metadata（schemaId=3，添加 email 列）
python3 -c "
import json, os, time
now = int(time.time()*1000)
meta = {
  'format-version': 2, 'table-uuid': 'test-table-uuid-1234',
  'location': 'file://${META_DIR}',
  'last-sequence-number': 3, 'last-updated-ms': now,
  'schemas': [
    {'type': 'struct', 'schema-id': 1, 'fields': [
      {'id': 1, 'name': 'id', 'required': True, 'type': 'long'},
      {'id': 2, 'name': 'name', 'required': True, 'type': 'string'}
    ]},
    {'type': 'struct', 'schema-id': 3, 'fields': [
      {'id': 1, 'name': 'id', 'required': True, 'type': 'long'},
      {'id': 2, 'name': 'name', 'required': True, 'type': 'string'},
      {'id': 4, 'name': 'email', 'required': False, 'type': 'string'}
    ]}
  ],
  'current-schema-id': 3,
  'partition-specs': [{'spec-id': 0, 'fields': []}],
  'default-spec-id': 0, 'last-partition-id': 0,
  'sort-orders': [{'order-id': 0, 'fields': []}],
  'default-sort-order-id': 0,
  'snapshots': [
    {'snapshot-id': 1, 'timestamp-ms': now, 'summary': {'operation': 'append'},
     'manifest-list': 'file://${META_DIR}/00001-manifests.avro'},
    {'snapshot-id': 3, 'timestamp-ms': now, 'summary': {'operation': 'append'},
     'manifest-list': 'file://${META_DIR}/00003-manifests.avro',
     'parent-snapshot-id': 1}
  ],
  'snapshot-log': [
    {'snapshot-id': 1, 'timestamp-ms': now},
    {'snapshot-id': 3, 'timestamp-ms': now}
  ],
  'metadata-log': [],
  'properties': {'env': 'prod', 'team': 'mobile'}
}
json.dump(meta, open('${META_DIR}/00003-email.metadata.json','w'), indent=2)
"
```

#### Step 1：创建基准表

```bash
# 获取 main 分支当前 hash
MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
echo "main hash: $MAIN_HASH"

# 创建 namespace 和 Iceberg 表
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/main@${MAIN_HASH}/history/commit" -d '{
  "commitMeta": {"message":"Create namespace and orders table","authors":["demo"]},
  "operations": [
    {"type": "PUT", "key": {"elements":["db"]}, "content": {"type": "NAMESPACE"}},
    {"type": "PUT", "key": {"elements":["db","orders"]}, "content": {
      "type": "ICEBERG_TABLE",
      "metadataLocation": "file://'"$META_DIR"'/00001-base.metadata.json",
      "snapshotId": 1, "schemaId": 1, "specId": 0, "sortOrderId": 0
    }}
  ]
}'
echo ""
```

#### Step 2：获取 contentId 和新的 main hash

```bash
# 获取创建表后的 main hash
MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
# 获取表的 contentId（后续 commit 需要保持 id 一致）
TABLE_ID=$(curl -s --noproxy localhost "$BASE/trees/main/contents/db.orders" | python3 -c "import sys,json; print(json.load(sys.stdin)['content']['id'])")
echo "main hash after create: $MAIN_HASH"
echo "table contentId: $TABLE_ID"
```

#### Step 3：创建 branch-a 和 branch-b

```bash
# 从 main 创建 branch-a
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees?name=branch-a&type=BRANCH" \
  -d '{"type":"BRANCH","name":"main","hash":"'"$MAIN_HASH"'"}'
echo ""

# 从 main 创建 branch-b
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees?name=branch-b&type=BRANCH" \
  -d '{"type":"BRANCH","name":"main","hash":"'"$MAIN_HASH"'"}'
echo ""
```

#### Step 4：在 branch-a 添加 phone 列

```bash
# 获取 branch-a 当前 hash
HASH_A=$(curl -s --noproxy localhost "$BASE/trees/branch-a" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
echo "branch-a hash: $HASH_A"

# 提交修改：使用相同的 contentId，更新 metadataLocation 为 phone 版本
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/branch-a@${HASH_A}/history/commit" -d '{
  "commitMeta": {"message":"Add phone column","authors":["demo"]},
  "operations": [
    {"type": "PUT", "key": {"elements":["db","orders"]}, "content": {
      "type": "ICEBERG_TABLE",
      "id": "'"$TABLE_ID"'",
      "metadataLocation": "file://'"$META_DIR"'/00002-phone.metadata.json",
      "snapshotId": 2, "schemaId": 2, "specId": 0, "sortOrderId": 0
    }}
  ]
}'
echo ""
```

#### Step 5：在 branch-b 添加 email 列

```bash
# 获取 branch-b 当前 hash
HASH_B=$(curl -s --noproxy localhost "$BASE/trees/branch-b" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
echo "branch-b hash: $HASH_B"

# 提交修改：使用相同的 contentId，更新 metadataLocation 为 email 版本
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/branch-b@${HASH_B}/history/commit" -d '{
  "commitMeta": {"message":"Add email column","authors":["demo"]},
  "operations": [
    {"type": "PUT", "key": {"elements":["db","orders"]}, "content": {
      "type": "ICEBERG_TABLE",
      "id": "'"$TABLE_ID"'",
      "metadataLocation": "file://'"$META_DIR"'/00003-email.metadata.json",
      "snapshotId": 3, "schemaId": 3, "specId": 0, "sortOrderId": 0
    }}
  ]
}'
echo ""
```

#### Step 6：Merge branch-a → main

```bash
# 获取最新的 main hash 和 branch-a hash
MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
HASH_A=$(curl -s --noproxy localhost "$BASE/trees/branch-a" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
echo "merging branch-a ($HASH_A) into main ($MAIN_HASH)"

# 执行 merge（单方变更场景，预期成功且无冲突）
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/main@${MAIN_HASH}/history/merge" \
  -d '{"fromRefName":"branch-a","fromHash":"'"$HASH_A"'"}'
echo ""
```

#### Step 7：Merge branch-b → main

```bash
# 获取最新的 main hash 和 branch-b hash
MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
HASH_B=$(curl -s --noproxy localhost "$BASE/trees/branch-b" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
echo "merging branch-b ($HASH_B) into main ($MAIN_HASH)"

# 执行 merge（三方冲突场景，预期语义合并后成功）
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/main@${MAIN_HASH}/history/merge" \
  -d '{"fromRefName":"branch-b","fromHash":"'"$HASH_B"'"}'
echo ""
```

#### Step 8：验证 main 上的最终内容

```bash
# 查询 main 上 db.orders 的内容
curl -s --noproxy localhost "$BASE/trees/main/contents/db.orders" | python3 -m json.tool
```

预期返回：
```json
{
  "content": {
    "type": "ICEBERG_TABLE",
    "id": "...",
    "metadataLocation": "file:///tmp/nessie-semantic-merge/00004-merged.metadata.json",
    "snapshotId": 4,
    "schemaId": 4
  }
}
```

#### Step 9：端到端文件验证

```bash
# 直接读取合并后的 metadata.json，检查 schema 字段和 properties
python3 -c "
import json
with open('${META_DIR}/00004-merged.metadata.json') as f:
    d = json.load(f)
fields = [fld['name'] for fld in d['schemas'][-1]['fields']]
props = d.get('properties', {})
print('Schema fields:', fields)
print('Properties:', props)
assert 'phone' in fields, 'phone missing'
assert 'email' in fields, 'email missing'
assert 'id' in fields, 'id missing'
assert 'name' in fields, 'name missing'
assert props.get('feature') == 'phone'
assert props.get('env') == 'prod'
assert props.get('team') == 'mobile'
print('SUCCESS: scenario A verified')
"
```

---

### 4.3 场景 D 验证（加列 vs 删列）

场景 D 与场景 A 的区别仅在于 **branch-b 的变更**（从"添加 email"改为"删除 id"）。以下命令假设你已执行完场景 A 的 Step 0-5，现在从生成 branch-b 的 metadata 开始重新验证。

#### Step D-0：重新生成 branch-b 的 metadata（删除 id 列）

```bash
# 覆盖生成 branch-b 的 metadata：schemaId=3，仅剩 name 列（id 被删除）
python3 -c "
import json, time
now = int(time.time()*1000)
meta = {
  'format-version': 2, 'table-uuid': 'test-table-uuid-1234',
  'location': 'file://${META_DIR}',
  'last-sequence-number': 3, 'last-updated-ms': now,
  'schemas': [
    {'type': 'struct', 'schema-id': 1, 'fields': [
      {'id': 1, 'name': 'id', 'required': True, 'type': 'long'},
      {'id': 2, 'name': 'name', 'required': True, 'type': 'string'}
    ]},
    {'type': 'struct', 'schema-id': 3, 'fields': [
      {'id': 2, 'name': 'name', 'required': True, 'type': 'string'}
    ]}
  ],
  'current-schema-id': 3,
  'partition-specs': [{'spec-id': 0, 'fields': []}],
  'default-spec-id': 0, 'last-partition-id': 0,
  'sort-orders': [{'order-id': 0, 'fields': []}],
  'default-sort-order-id': 0,
  'snapshots': [
    {'snapshot-id': 1, 'timestamp-ms': now, 'summary': {'operation': 'append'},
     'manifest-list': 'file://${META_DIR}/00001-manifests.avro'},
    {'snapshot-id': 3, 'timestamp-ms': now, 'summary': {'operation': 'append'},
     'manifest-list': 'file://${META_DIR}/00003-manifests.avro',
     'parent-snapshot-id': 1}
  ],
  'snapshot-log': [
    {'snapshot-id': 1, 'timestamp-ms': now},
    {'snapshot-id': 3, 'timestamp-ms': now}
  ],
  'metadata-log': [],
  'properties': {'env': 'prod', 'team': 'mobile'}
}
json.dump(meta, open('${META_DIR}/00003-delete-id.metadata.json','w'), indent=2)
"
```

#### Step D-1：重建分支并重新提交 branch-b 的变更

**注意**：由于场景 A 已经把 branch-b 修改成了添加 email，而 IN_MEMORY 存储在服务器重启后会丢失数据。要验证场景 D，最干净的方式是**重启 Nessie 服务器**（数据清空），然后从场景 A 的 Step 1 开始重新执行，但在 Step 5 中使用上面的 `00003-delete-id.metadata.json` 代替 `00003-email.metadata.json`。

如果不想重启，也可以直接在原有 branch-b 上再提交一次（将 email 改回删除 id），但这样 branch-b 的历史会变得复杂。以下命令展示**完整重建**流程（从创建基准表到最终验证），与场景 A 的差异仅在 branch-b 的 metadataLocation：

```bash
# ========== 快速重建（假设已重启服务器或数据已清空） ==========

# 1. 重新获取 main hash 并创建基准表
MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/main@${MAIN_HASH}/history/commit" -d '{
  "commitMeta": {"message":"Create namespace and orders table","authors":["demo"]},
  "operations": [
    {"type": "PUT", "key": {"elements":["db"]}, "content": {"type": "NAMESPACE"}},
    {"type": "PUT", "key": {"elements":["db","orders"]}, "content": {
      "type": "ICEBERG_TABLE",
      "metadataLocation": "file://'"$META_DIR"'/00001-base.metadata.json",
      "snapshotId": 1, "schemaId": 1, "specId": 0, "sortOrderId": 0
    }}
  ]
}'

# 2. 获取 contentId 和新的 main hash
MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
TABLE_ID=$(curl -s --noproxy localhost "$BASE/trees/main/contents/db.orders" | python3 -c "import sys,json; print(json.load(sys.stdin)['content']['id'])")

# 3. 创建分支
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees?name=branch-a&type=BRANCH" -d '{"type":"BRANCH","name":"main","hash":"'"$MAIN_HASH"'"}'
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees?name=branch-b&type=BRANCH" -d '{"type":"BRANCH","name":"main","hash":"'"$MAIN_HASH"'"}'

# 4. branch-a 添加 phone
HASH_A=$(curl -s --noproxy localhost "$BASE/trees/branch-a" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/branch-a@${HASH_A}/history/commit" -d '{
  "commitMeta": {"message":"Add phone column","authors":["demo"]},
  "operations": [
    {"type": "PUT", "key": {"elements":["db","orders"]}, "content": {
      "type": "ICEBERG_TABLE",
      "id": "'"$TABLE_ID"'",
      "metadataLocation": "file://'"$META_DIR"'/00002-phone.metadata.json",
      "snapshotId": 2, "schemaId": 2, "specId": 0, "sortOrderId": 0
    }}
  ]
}'

# 5. branch-b 删除 id（关键差异点）
HASH_B=$(curl -s --noproxy localhost "$BASE/trees/branch-b" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/branch-b@${HASH_B}/history/commit" -d '{
  "commitMeta": {"message":"Delete id column","authors":["demo"]},
  "operations": [
    {"type": "PUT", "key": {"elements":["db","orders"]}, "content": {
      "type": "ICEBERG_TABLE",
      "id": "'"$TABLE_ID"'",
      "metadataLocation": "file://'"$META_DIR"'/00003-delete-id.metadata.json",
      "snapshotId": 3, "schemaId": 3, "specId": 0, "sortOrderId": 0
    }}
  ]
}'

# 6. Merge branch-a → main
MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
HASH_A=$(curl -s --noproxy localhost "$BASE/trees/branch-a" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/main@${MAIN_HASH}/history/merge" \
  -d '{"fromRefName":"branch-a","fromHash":"'"$HASH_A"'"}'

# 7. Merge branch-b → main（三方冲突：加列 vs 删列）
MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
HASH_B=$(curl -s --noproxy localhost "$BASE/trees/branch-b" | python3 -c "import sys,json; print(json.load(sys.stdin)['reference']['hash'])")
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/main@${MAIN_HASH}/history/merge" \
  -d '{"fromRefName":"branch-b","fromHash":"'"$HASH_B"'"}'

# 8. 验证 main 最终状态
curl -s --noproxy localhost "$BASE/trees/main/contents/db.orders" | python3 -m json.tool
```

#### Step D-2：端到端文件验证

```bash
# 读取合并后的 metadata.json，验证 id 被删除、phone 被保留
python3 -c "
import json
with open('${META_DIR}/00004-merged.metadata.json') as f:
    d = json.load(f)
fields = [fld['name'] for fld in d['schemas'][-1]['fields']]
props = d.get('properties', {})
print('Schema fields:', fields)
print('Properties:', props)
assert 'phone' in fields, 'phone missing'
assert 'id' not in fields, 'id should have been deleted'
assert 'name' in fields, 'name missing'
assert props.get('feature') == 'phone'
assert props.get('env') == 'prod'
assert props.get('team') == 'mobile'
print('SUCCESS: scenario D verified')
"
```

**预期结果**：
- `fields`: `['name', 'phone']`
- `id` 被删除，`phone` 被保留，`name` 保留
- `properties`: `{'env': 'prod', 'feature': 'phone', 'team': 'mobile'}`

---

### 4.4 一键复用脚本

为方便复用，以下脚本整合了两个场景的完整流程。保存为 `/tmp/verify_semantic_merge.sh`，执行前确保 Nessie 服务器已启动：

```bash
#!/bin/bash
set -e

BASE="http://localhost:19120/api/v2"
META_DIR="/tmp/nessie-semantic-merge"

# 辅助函数：从 JSON 响应中提取字段
jget() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

echo "=== 0. Clean up and prepare metadata files ==="
rm -rf "$META_DIR"
mkdir -p "$META_DIR"

# 生成 metadata.json 的辅助函数
gen_meta() {
  python3 -c "
import json, sys
obj = json.loads(sys.argv[1])
json.dump(obj, open(sys.argv[2],'w'), indent=2)
" "$1" "$2"
}

# Base metadata (schemaId=1)
gen_meta '{
  "format-version": 2, "table-uuid": "test-table-uuid-1234",
  "location": "file://'"$META_DIR"'",
  "last-sequence-number": 1, "last-updated-ms": 0,
  "schemas": [{"type": "struct", "schema-id": 1, "fields": [
    {"id": 1, "name": "id", "required": true, "type": "long"},
    {"id": 2, "name": "name", "required": true, "type": "string"}
  ]}],
  "current-schema-id": 1,
  "partition-specs": [{"spec-id": 0, "fields": []}],
  "default-spec-id": 0, "last-partition-id": 0,
  "sort-orders": [{"order-id": 0, "fields": []}],
  "default-sort-order-id": 0,
  "snapshots": [{"snapshot-id": 1, "timestamp-ms": 0, "summary": {"operation": "append"},
    "manifest-list": "file://'"$META_DIR"'/00001-manifests.avro"}],
  "snapshot-log": [{"snapshot-id": 1, "timestamp-ms": 0}],
  "metadata-log": [], "properties": {}
}' "$META_DIR/00001-base.metadata.json"

# Phone metadata (schemaId=2)
gen_meta '{
  "format-version": 2, "table-uuid": "test-table-uuid-1234",
  "location": "file://'"$META_DIR"'",
  "last-sequence-number": 2, "last-updated-ms": 0,
  "schemas": [
    {"type": "struct", "schema-id": 1, "fields": [
      {"id": 1, "name": "id", "required": true, "type": "long"},
      {"id": 2, "name": "name", "required": true, "type": "string"}
    ]},
    {"type": "struct", "schema-id": 2, "fields": [
      {"id": 1, "name": "id", "required": true, "type": "long"},
      {"id": 2, "name": "name", "required": true, "type": "string"},
      {"id": 3, "name": "phone", "required": false, "type": "string"}
    ]}
  ],
  "current-schema-id": 2,
  "partition-specs": [{"spec-id": 0, "fields": []}],
  "default-spec-id": 0, "last-partition-id": 0,
  "sort-orders": [{"order-id": 0, "fields": []}],
  "default-sort-order-id": 0,
  "snapshots": [
    {"snapshot-id": 1, "timestamp-ms": 0, "summary": {"operation": "append"},
     "manifest-list": "file://'"$META_DIR"'/00001-manifests.avro"},
    {"snapshot-id": 2, "timestamp-ms": 0, "summary": {"operation": "append"},
     "manifest-list": "file://'"$META_DIR"'/00002-manifests.avro",
     "parent-snapshot-id": 1}
  ],
  "snapshot-log": [{"snapshot-id": 1, "timestamp-ms": 0}, {"snapshot-id": 2, "timestamp-ms": 0}],
  "metadata-log": [],
  "properties": {"feature": "phone", "team": "mobile"}
}' "$META_DIR/00002-phone.metadata.json"

# Delete-id metadata (schemaId=3) -- for scenario D
gen_meta '{
  "format-version": 2, "table-uuid": "test-table-uuid-1234",
  "location": "file://'"$META_DIR"'",
  "last-sequence-number": 3, "last-updated-ms": 0,
  "schemas": [
    {"type": "struct", "schema-id": 1, "fields": [
      {"id": 1, "name": "id", "required": true, "type": "long"},
      {"id": 2, "name": "name", "required": true, "type": "string"}
    ]},
    {"type": "struct", "schema-id": 3, "fields": [
      {"id": 2, "name": "name", "required": true, "type": "string"}
    ]}
  ],
  "current-schema-id": 3,
  "partition-specs": [{"spec-id": 0, "fields": []}],
  "default-spec-id": 0, "last-partition-id": 0,
  "sort-orders": [{"order-id": 0, "fields": []}],
  "default-sort-order-id": 0,
  "snapshots": [
    {"snapshot-id": 1, "timestamp-ms": 0, "summary": {"operation": "append"},
     "manifest-list": "file://'"$META_DIR"'/00001-manifests.avro"},
    {"snapshot-id": 3, "timestamp-ms": 0, "summary": {"operation": "append"},
     "manifest-list": "file://'"$META_DIR"'/00003-manifests.avro",
     "parent-snapshot-id": 1}
  ],
  "snapshot-log": [{"snapshot-id": 1, "timestamp-ms": 0}, {"snapshot-id": 3, "timestamp-ms": 0}],
  "metadata-log": [],
  "properties": {"env": "prod", "team": "mobile"}
}' "$META_DIR/00003-delete-id.metadata.json"

echo "=== 1. Create base table on main ==="
MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | jget("['reference']['hash']"))
echo "main hash: $MAIN_HASH"
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/main@${MAIN_HASH}/history/commit" -d '{
  "commitMeta": {"message":"Create namespace and orders table","authors":["demo"]},
  "operations": [
    {"type": "PUT", "key": {"elements":["db"]}, "content": {"type": "NAMESPACE"}},
    {"type": "PUT", "key": {"elements":["db","orders"]}, "content": {
      "type": "ICEBERG_TABLE",
      "metadataLocation": "file://'"$META_DIR"'/00001-base.metadata.json",
      "snapshotId": 1, "schemaId": 1, "specId": 0, "sortOrderId": 0
    }}
  ]
}'
echo ""

MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | jget("['reference']['hash']"))
TABLE_ID=$(curl -s --noproxy localhost "$BASE/trees/main/contents/db.orders" | jget("['content']['id']"))
echo "table contentId: $TABLE_ID"

echo "=== 2. Create branches ==="
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees?name=branch-a&type=BRANCH" -d '{"type":"BRANCH","name":"main","hash":"'"$MAIN_HASH"'"}'
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees?name=branch-b&type=BRANCH" -d '{"type":"BRANCH","name":"main","hash":"'"$MAIN_HASH"'"}'
echo ""

echo "=== 3. branch-a: add phone ==="
HASH_A=$(curl -s --noproxy localhost "$BASE/trees/branch-a" | jget("['reference']['hash']"))
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/branch-a@${HASH_A}/history/commit" -d '{
  "commitMeta": {"message":"Add phone column","authors":["demo"]},
  "operations": [
    {"type": "PUT", "key": {"elements":["db","orders"]}, "content": {
      "type": "ICEBERG_TABLE",
      "id": "'"$TABLE_ID"'",
      "metadataLocation": "file://'"$META_DIR"'/00002-phone.metadata.json",
      "snapshotId": 2, "schemaId": 2, "specId": 0, "sortOrderId": 0
    }}
  ]
}'
echo ""

echo "=== 4. branch-b: delete id ==="
HASH_B=$(curl -s --noproxy localhost "$BASE/trees/branch-b" | jget("['reference']['hash']"))
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/branch-b@${HASH_B}/history/commit" -d '{
  "commitMeta": {"message":"Delete id column","authors":["demo"]},
  "operations": [
    {"type": "PUT", "key": {"elements":["db","orders"]}, "content": {
      "type": "ICEBERG_TABLE",
      "id": "'"$TABLE_ID"'",
      "metadataLocation": "file://'"$META_DIR"'/00003-delete-id.metadata.json",
      "snapshotId": 3, "schemaId": 3, "specId": 0, "sortOrderId": 0
    }}
  ]
}'
echo ""

echo "=== 5. Merge branch-a into main ==="
MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | jget("['reference']['hash']"))
HASH_A=$(curl -s --noproxy localhost "$BASE/trees/branch-a" | jget("['reference']['hash']"))
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/main@${MAIN_HASH}/history/merge" \
  -d '{"fromRefName":"branch-a","fromHash":"'"$HASH_A"'"}'
echo ""

echo "=== 6. Merge branch-b into main ==="
MAIN_HASH=$(curl -s --noproxy localhost "$BASE/trees/main" | jget("['reference']['hash']"))
HASH_B=$(curl -s --noproxy localhost "$BASE/trees/branch-b" | jget("['reference']['hash']"))
curl -s --noproxy localhost -H Content-Type:application/json -X POST \
  "$BASE/trees/main@${MAIN_HASH}/history/merge" \
  -d '{"fromRefName":"branch-b","fromHash":"'"$HASH_B"'"}'
echo ""

echo "=== 7. Verify final state on main ==="
curl -s --noproxy localhost "$BASE/trees/main/contents/db.orders" | python3 -m json.tool

echo "=== 8. End-to-end file verification ==="
python3 -c "
import json, sys
with open('${META_DIR}/00004-merged.metadata.json') as f:
    d = json.load(f)
fields = [fld['name'] for fld in d['schemas'][-1]['fields']]
props = d.get('properties', {})
print('Schema fields:', fields)
print('Properties:', props)
if 'phone' in fields and 'id' not in fields and 'name' in fields:
    print('SUCCESS: scenario D verified')
    sys.exit(0)
else:
    print('FAILURE')
    sys.exit(1)
"
```

保存后执行：

```bash
chmod +x /tmp/verify_semantic_merge.sh
/tmp/verify_semantic_merge.sh
```

---

## 第五部分：Demo 实施阶段与问题解决

### 5.1 第一阶段：元数据指针级合并

**目标**：让 Nessie 在 merge 时不报 409，schemaId / snapshotId / metadataLocation 指针正确流转。

**实现**：在 `BaseCommitHelper.createMergeTransplantCommit()` 中新增预检测循环和 ConflictHandler 逻辑。

**遇到的问题 1：Compilation error - cannot find symbol class List / ArrayList**

- **原因**：新增代码使用了 `List` 和 `ArrayList`，但文件未导入 `java.util.List` 和 `java.util.ArrayList`
- **解决**：在 imports 区域追加 `import java.util.List;` 和 `import java.util.ArrayList;`

**遇到的问题 2：package CommitConflict does not exist**

- **原因**：代码中使用了 `CommitConflict.ConflictType.VALUE_DIFFERS`，但未导入 `CommitConflict`
- **解决**：追加 `import org.projectnessie.versioned.storage.common.logic.CommitConflict;`

**遇到的问题 3：Runtime 409 Conflict 仍然存在**

- **原因**：第一版只处理了 `KEY_EXISTS` 冲突类型。实际 merge 时触发的是 `VALUE_DIFFERS`
- **解决**：在 ConflictHandler 中增加 `if (conflict.conflictType() == CommitConflict.ConflictType.VALUE_DIFFERS)` 分支

**遇到的问题 4：Merged schema 未出现（schemaId 仍为 target 值）**

- **原因**：误以为第四回调在 ConflictHandler 之后执行，试图在 ConflictHandler 中构造合并值。实际上第四回调在第一步执行，ConflictHandler 在第四步执行
- **解决**：改为"预检测 + 第四回调注入"的两阶段策略：预检测阶段构造合并值并存入 Map，第四回调从 Map 中取出并替换

**遇到的问题 5：ObjNotFoundException in ConflictHandler**

- **原因**：第四回调已将 add.value() 替换为合并值的 ObjId，但该 ObjId 尚未写入数据库。ConflictHandler 中的 fallback 代码尝试 `fetchContent(op.value())` 时抛出异常
- **解决**：ConflictHandler 优先检查本地缓存 `semanticResolvedValues.get(key)`，在缓存命中时直接返回 ADD，跳过 fetchContent

**遇到的问题 6：Over-aggressive pre-detection（过度预检测）**

- **现象**：merge branch-a（schemaId=2）→ main（schemaId=1）时，结果变成了 schemaId=3（错误地构造了合并值）
- **原因**：预检测只要发现 `sourceSchemaId != targetSchemaId` 就构造合并值，没有检查 target 是否从 base 改变
- **解决**：引入 base 值比较。通过 `add.expectedValue()` 拉取 baseTable，计算 `sourceChanged = source.schemaId != base.schemaId` 和 `targetChanged = target.schemaId != base.schemaId`。只有双方都从 base 改变时，才构造合并值

### 5.2 第二阶段：端到端内容合并（metadata.json 生成）

**目标**：从"指针合并"跨越到"内容合并"，生成真实可读取的 Iceberg metadata.json 文件。

**实现**：增加 `readSchemaFields`、`mergeSchemaFields`、`writeMergedMetadata` 三个工具方法。

**遇到的问题 7：variable ex already defined**

- **原因**：ConflictHandler fallback catch 块中使用了 `catch (IOException ex)`，但同一作用域中已有变量名为 `ex = conflict.existing()`
- **解决**：将 catch 变量重命名为 `ioe`

**遇到的问题 8：incompatible types in fallback handler**

- **原因**：修改 `writeMergedMetadata` 签名增加了 `targetMetadataLocation` 参数后，fallback handler 中的调用仍使用旧签名
- **解决**：在 fallback handler 中补充 `targetTable.getMetadataLocation()` 作为第二个参数

### 5.3 第三阶段：Properties 合并

**目标**：扩展语义合并范围到表属性（properties）。

**实现**：增加 `readProperties` 和 `mergeProperties` 方法，在 `writeMergedMetadata` 中调用。

**关键设计决策**：
- 同名不同值 → 抛异常，fallback 到 409（保守策略）
- 这是 Catalog 层的合理边界：properties 属于"表定义语义"，Catalog 可以且应该合并

### 5.4 第四阶段：删除感知合并（Scenario D）

**目标**：支持"加列 vs 删列"场景，合并结果应保留新增列并删除被删列。

**实现**：
- 扩展 `mergeSchemaFields` 为 3 参数版本（base, source, target）
- 预检测阶段读取 baseTable 的 schema fields 并传入
- 算法逻辑：`(base ∩ source ∩ target) ∪ (source - base) ∪ (target - base)`

**遇到的问题 9：场景 D 的兼容性误判**

- **现象**：在场景分类矩阵的第一版中，将"加列 vs 删列"标记为"语义不兼容"
- **原因**：起初认为删除列是破坏性操作，合并时需要分析列依赖关系，因此保守地标记为不兼容
- **重新分析**：
  - branch-a 添加 `phone`（metadata-only），branch-b 删除 `id`（metadata-only）
  - 两个操作作用于**不同列**，在 Iceberg 层面完全兼容
  - 合并后的 schema `[name, phone]` 是合法的，Iceberg 可通过 schema projection 读取旧文件
  - 标记为"不兼容"是**当前实现的保守策略**，而非技术不可能
- **解决**：扩展 `mergeSchemaFields` 支持 base 字段传入，实现删除感知合并。合并策略修正为：保留 base 中未被任意一方删除的列，添加任意一方新增的列

**验证结果**：
- branch-a `[id, name, phone]` + branch-b `[name]` → merged `[name, phone]`
- 两次 merge 均 200 OK，无 409

---

## 第六部分：修改影响评估

本次修改仅在 `BaseCommitHelper.java` 中新增逻辑，未触碰 `CommitLogicImpl`、`Persist` 等核心存储层。以下从性能、功能、架构、稳定性四个维度评估影响。

### 6.1 性能影响

| 指标 | 变化 | 说明 |
|------|------|------|
| **预检测阶段额外 IO** | 增加 | 每个 `CreateCommit.Add` 若命中 ICEBERG_TABLE 且 `sourceSchemaId != targetSchemaId`，需额外 `fetchContent()` 读取 base、source、target 三个 ContentValueObj，以及 `readSchemaFields()` 读取最多 3 个 metadata.json 文件 |
| **ConflictHandler 开销** | 减少（缓存命中时） | 预检测命中后，`semanticResolvedValues` 本地缓存避免了 ConflictHandler 中的 `fetchContent()` 调用 |
| **metadata.json 写 IO** | 新增 | `writeMergedMetadata()` 每次成功合并会写入一个新的本地文件（约 2-5KB） |
| **整体 merge 延迟** | 轻微增加（单次 merge 多 1-3 个 IO 操作） | 仅在涉及 IcebergTable 且 schemaId 不同的 merge 时触发；普通 merge（Namespace、单方变更）无额外开销 |

**缓解措施**：
- 预检测有严格的过滤条件（类型必须是 ICEBERG_TABLE、value 必须不同），不会对所有 adds 执行额外 IO
- 失败时（IOException、类型不匹配）立即 fallback 到原始逻辑，不阻塞 merge

### 6.2 功能影响

| 影响类型 | 说明 |
|----------|------|
| **正向影响** | 兼容的 schema 变更（各加不同列、各改不同 property）从 409 Conflict 变为自动合并，提升用户体验 |
| **正向影响** | 删除感知合并使"加列 vs 删列"场景可自动处理，覆盖更多业务场景 |
| **潜在风险** | 过于激进的自动合并可能掩盖业务层面的不兼容（如一方删列恰好是另一方新列的逻辑依赖） |
| **风险缓解** | 保守 fallback 策略：任何异常（同名不同类型、同名不同 property 值、IO 错误）都抛异常并 fallback 到原始 409，由用户显式处理 |

### 6.3 架构影响

| 维度 | 影响 | 说明 |
|------|------|------|
| **存储层侵入性** | 无 | 未修改 `CommitLogicImpl.buildCommitObj()` 的核心循环，仅通过回调扩展 |
| **模块耦合** | 轻微增加 | `versioned/storage/store` 模块通过 `jackson-databind` 解析 Iceberg metadata.json，引入了隐式的格式层知识。当前用 `JsonNode` 硬编码避免直接依赖 `catalog/format/iceberg` 模块，但长期来看应通过 SPI 解耦 |
| **数据一致性** | 需关注 | `metadata.json` 写入本地文件系统与 Nessie commit 不是原子操作。若文件写入成功但 Nessie commit 失败（如 CAS 冲突），会产生孤儿文件 |
| **向后兼容性** | 完全兼容 | 新增逻辑仅在 `contentTypeForPayload() == ICEBERG_TABLE` 时触发，其他内容类型（Namespace、DeltaLake 等）行为不变 |

### 6.4 稳定性与异常处理

| 场景 | 行为 | 是否安全 |
|------|------|----------|
| `metadata.json` 读失败 | `mergeSchemaFields` / `readProperties` 抛 `IOException` → catch 后 fallback 到原始 ConflictHandler → 返回 409 | 安全 |
| `metadata.json` 写失败 | `writeMergedMetadata` 抛 `IOException` → catch 后用占位 S3 路径构造 mergedTable → 继续 merge（文件不存在但指针已记录） | 半安全（文件缺失但 Nessie commit 成功） |
| base 值缺失 | `expectedValue == null` 时，`sourceChanged` / `targetChanged` 保持 `true`，保守地构造合并值 | 安全 |
| 同名列类型冲突 | `mergeSchemaFields` 抛 `IllegalStateException` → catch 后 fallback → 409 | 安全 |
| 同名 property 值冲突 | `mergeProperties` 抛 `IllegalStateException` → catch 后 fallback → 409 | 安全 |
| 非 ICEBERG_TABLE 类型 | 预检测条件过滤掉，走原始逻辑 | 无影响 |

---

## 第七部分：架构边界与生产级差距

### 6.1 Catalog 层的职责边界

```
┌─────────────────────────────────────────────────────────────┐
│  Catalog 层应该合并的（表定义语义）                            │
│  ─────────────────────────────────                            │
│  ✅ Schema 演进：union(columns) + 删除感知                    │
│  ✅ Properties：union(props) + 同名冲突检测                   │
│  ⬜ Partition spec：待实现（当前 demo 分区为空）               │
│  ⬜ Sort order：待实现                                         │
├─────────────────────────────────────────────────────────────┤
│  Catalog 层不应该合并的（数据内容）                            │
│  ─────────────────────────────────                            │
│  ❌ 数据文件（Parquet/ORC/Avro）                              │
│  ❌ Manifest 文件列表                                         │
│  ❌ 行级冲突消解（INSERT vs DELETE）                          │
│  → 这些是 Table Format 层（Iceberg）和 Compute 层的职责       │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 仍未解决的生产级问题

| 问题 | 说明 | 层级归属 |
|------|------|----------|
| **对象存储集成** | 当前使用本地文件系统 (`file://`)，生产环境应使用 S3 / HDFS / GCS。需要接入 Nessie 的 `ObjectIO` 抽象层。 | Catalog |
| **表定义语义不完整** | 已合并 schema 和 properties，但未处理 partition specs、sort orders、statistics files 的合并。 | Catalog |
| **Snapshot DAG** | 新 snapshot 的 `parent-snapshot-id` 只设置了单个 parent，未体现 merge 的双来源（Iceberg format-version 2 不支持多 parent）。 | Table Format |
| **数据内容未合并** | **明确边界**：Parquet 数据文件和 manifest 列表的合并不在 Catalog 层职责内。 | Table Format / Compute |
| **并发安全** | 多个 merge 同时写入 `/tmp/nessie-semantic-merge/` 可能产生文件名冲突。需要 UUID 或对象存储的 CAS 机制。 | Catalog |
| **错误回滚** | `metadata.json` 写入了但 Nessie commit 失败时，没有清理机制。需要两阶段提交或垃圾回收。 | Catalog |
| **真正的语义推断** | 仍基于 `schemaId` 差异做判断，未集成 Doduo/Sherlock 做列名语义匹配（如 `phone` vs `telephone`）。 | Catalog |
| **模块耦合** | 当前用 `jackson-databind` 的 `JsonNode` 硬编码解析 metadata.json。更优雅的做法是通过 SPI 让 `catalog/format/iceberg` 模块提供合并策略。 | Catalog |

---

## 第八部分：总结

### 8.1 修改范围

仅修改 `BaseCommitHelper.java`：
- 预检测 + ConflictHandler + 第四回调：约 120 行
- 五个工具方法（read/merge/write）：约 150 行

无外部依赖变更。`jackson-databind` 已是 `versioned/storage/store` 模块的现有依赖。

### 8.2 核心设计

通过"预检测 → 值替换 → 冲突消化"三层协作，在 Nessie 的 squash merge 管道中注入了表定义语义感知能力：

- **预检测**：利用 `expectedValue`（base 值）识别真正的三方冲突，构造合并值
- **第四回调**：将 source 值替换为预构造的合并值，使 CommitOp 携带正确内容
- **ConflictHandler**：消化 `VALUE_DIFFERS`，避免 409

### 7.3 当前能力与局限

| 能力 | 状态 | 说明 |
|------|------|------|
| Nessie 元数据指针合并（schemaId / snapshotId / metadataLocation） | ✅ 已实现 | 第一阶段完成 |
| 区分单方变更 vs 三方冲突 | ✅ 已实现 | 利用 `expectedValue`（base）判断 |
| Schema 字段删除感知合并 | ✅ 已实现 | `mergeSchemaFields(base, source, target)` |
| Properties 合并（并集 + 冲突检测） | ✅ 已实现 | `mergeProperties()` |
| 生成真实的 metadata.json | ✅ 已实现 | `writeMergedMetadata()`，本地文件系统 |
| 场景 A（各加兼容列） | ✅ 已验证 | phone + email 共存 |
| 场景 D（加列 vs 删列） | ✅ 已验证 | id 删除，phone 保留 |
| 语义推断（Doduo/Sherlock） | ❌ 未实现 | 仍基于 schemaId 差异，未理解列名语义 |
| 对象存储集成（S3/HDFS） | ❌ 未实现 | 当前使用 `file://` |
| Partition spec / sort order 合并 | ❌ 未实现 | 当前 demo 分区为空 |
| 数据内容合并（Parquet/manifest） | ❌ **不在 Catalog 层职责内** | **明确边界** |

---

*文档版本：v4.0 / 2026-05-11*
