# P1 Polaris Gateway 开发与验收 Memo

目录：`Demo/p1_polaris_gateway/`
设计依据：`Industry/git-like/5. Polaris_GitLike_REST_Gateway_P1_Research_And_Design.md`

---

## 0. 当前结论

### 0.1 总体状态

P1 demo 已完成 Phase 1-6 的核心闭环：

1. Gateway 项目骨架、OpenAPI、DB schema、runtime compose。
2. Version Store repositories：refs、commits、indexes、content values。
3. Bootstrap + Iceberg REST list/load 读路径。
4. 单表 commit MVP。
5. 多表 transaction commit + diff。
6. Publish fast-forward + outbox materialization + drift report。

最新自动化验收：

```powershell
Set-Location Demo/p1_polaris_gateway/gateway-service
.\gradlew.bat --no-daemon --no-configuration-cache test `
  --tests "*.store.*" `
  --tests "*.api.*" `
  --tests "*.commit.*" `
  --tests "*.diff.*" `
  --tests "*.publish.*" `
  --rerun-tasks
```

结果：`BUILD SUCCESSFUL`，29/29 通过。

| Suite | Tests | Failures | Errors |
|---|---:|---:|---:|
| `LoadTableIT` | 9 | 0 | 0 |
| `CommitIT` | 4 | 0 | 0 |
| `DiffIT` | 3 | 0 | 0 |
| `PublishIT` | 4 | 0 | 0 |
| `RefRepositoryIT` | 9 | 0 | 0 |

最新 live E2E：compose + 宿主机 Gateway，使用合法当前 `timestamp-ms`：

- bootstrap main：PASS
- create `dev_a`：PASS
- commit to `dev_a`：PASS
- publish-plan：`publishable=true`
- publish：main ref fast-forward 到 `dev_a` head
- materialization worker：Polaris table snapshot 前进到 Gateway main snapshot
- drift report：`DRIFT_COUNT=0`

关键输出：

```text
SNAP_AFTER_WORKER polaris=101
DRIFT_COUNT=0 ITEMS=
MATERIALIZATION_E2E_PASS
```

### 0.2 本地运行约定

开发 / 本地验收推荐宿主机运行 Gateway，compose 只运行依赖服务：

```powershell
Set-Location Demo/p1_polaris_gateway/runtime
docker compose up -d postgres minio bucket-setup polaris

Set-Location ../gateway-service
$env:POLARIS_CATALOG_URL = "http://localhost:8281"
$env:GATEWAY_S3_ENDPOINT = "http://localhost:9100"
$env:GATEWAY_S3_ACCESS_KEY_ID = "polaris_root"
$env:GATEWAY_S3_SECRET_ACCESS_KEY = "polaris_pass"
$env:GATEWAY_S3_REGION = "us-east-1"
.\gradlew.bat --no-configuration-cache quarkusDev
```

无宿主机 Gradle 环境 / CI fallback：

```bash
docker compose up -d
docker exec p1-gateway-placeholder ./gradlew quarkusDev --project-cache-dir /tmp/gradle-project-cache
```

### 0.3 当前已知限制

1. **Polaris in-memory 状态**：`polaris.persistence.type=in-memory`，重启后需重新创建 catalog、namespace、table，再调用 Gateway bootstrap。
2. **Catalog storage 配置必须完整**：Polaris catalog 需要 `stsEndpoint: http://minio:9000` 和 `pathStyleAccess: true`。缺 `stsEndpoint` 会打到公网 STS；缺 `pathStyleAccess` 会走虚拟托管式 DNS（如 `bucket123.minio`）并在 Docker Desktop 上失败。
3. **MinIO AssumeRole 兼容性**：MinIO latest 当前实测接受 Polaris 使用的 AssumeRole 链路，但 MinIO 官方主要承诺 `GetSessionToken` 和 OIDC ARWI。升级 MinIO 时需要复验。
4. **Version Store 绕写限制**：客户端直接调用 Polaris 写表不会进入 Gateway Version Store；P1 设计要求写路径经 Gateway。
5. **Publish 幂等限制**：`PublishService` 接收 `Idempotency-Key`，但 P1 尚未写入 `idempotency_keys` 表，重复 publish 不是幂等 replay。当前依赖 expected heads + CAS 防止错误推进。
6. **Lock 硬化限制**：`branch_locks` 已有 fencing token 和 TTL，但 P1 仍以 CAS 作为最终并发安全边界；锁的可观测性、恢复流程和更严格的事务边界留待后续硬化。
7. **snapshot timestamp-ms 约束**：Polaris 校验 `add-snapshot.timestamp-ms` 必须晚于表 metadata log 最新条目。Spark/Iceberg 正常写入会使用当前时间；若测试或异常重放提交过期 timestamp，materialization 会收到 Polaris 400，outbox 重试直至 `FAILED`，drift 不清零。P1 将其视为客户端输入约束，不在 Gateway 写路径额外维护 metadata log timestamp。
8. **Outbox 可观测性**：当前可通过 DB 和 drift report 判断 materialization 状态，但没有单独的 outbox/status API。后续可补管理端点。

---

## 1. Phase 1：项目骨架

### 1.1 目标

建立可编译、可启动、可观测的 Gateway 服务骨架，并确保未实现 API 显式返回 `501 PHASE_1_PLACEHOLDER`。

### 1.2 产出

- `gateway-service/`
  - Gradle Wrapper 8.14.5。
  - Quarkus app 配置：`application.properties`。
  - OpenAPI：`openapi.yaml`。
  - Flyway migration：`V1__init.sql`，10 张核心表。
  - Iceberg REST 占位资源：`IcebergConfigResource`、`IcebergNamespaceResource`、`IcebergTableResource`、`IcebergOperationsResource`。
  - 管理 API 占位资源：`BranchManagementResource`、`CatalogManagementResource`、`CommitManagementResource`。
- `runtime/docker-compose.yml`：postgres、object storage、polaris、gateway placeholder。
- `runtime/smoke/smoke-phase1.ps1`。
- `.gitignore`。

### 1.3 验收

基础验收：

| 验收项 | 结果 |
|---|---|
| `.\gradlew.bat compileJava` | PASS |
| `V1__init.sql` 10 张核心表 | PASS |
| `GET /q/health` | `UP` |
| `GET /q/openapi` | 18 paths |
| `docker compose up -d && docker compose ps` | PASS |

### 1.4 修复记录

- `settings.gradle.kts`：Maven Central 放在本地 `.m2` 前，避免本地不完整 jar 遮蔽远端依赖。
- `smoke-phase1.ps1`：OpenAPI paths 计数改为 `Measure-Object`。

---

## 2. Phase 2：Version Store Repository

### 2.1 目标

实现 refs、commits、indexes 的基本存储能力，支持 branch 创建、CAS 更新、分页查询、软删除和 compaction 阈值判断。

### 2.2 产出

- `store/StoreConflictException.java`
- `store/RefRepository.java`
- `store/CommitRepository.java`
- `store/IndexRepository.java`
- `test/store/RefRepositoryIT.java`
- `test/resources/docker-java.properties`

### 2.3 验收

自动化验收：

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.store.*"
```

结果：`BUILD SUCCESSFUL`，`RefRepositoryIT` 9/9 通过。

覆盖场景：

- `createBranch_success`
- `createBranch_duplicateName`
- `casUpdate_succeeds`
- `casUpdate_conflictsOnMismatch`
- `listBranches_excludesDeleted`
- `insertAndFindCommit_success`
- `insertAndFindIndex_success`
- `shouldCompact_atTwentyIncrementalIndexes`
- `shouldCompact_whenOpsExceedThreshold`

### 2.4 修复记录

Testcontainers 在 Docker Desktop 29.4.3 上首次失败：`docker-java` 默认 API version 1.24 低于 daemon 最低 1.40，`/info` 返回 400。

最小修复：新增 `src/test/resources/docker-java.properties`：

```properties
api.version=1.44
```

说明：本项目实际解析到 `docker-java-api:3.4.2`；Testcontainers 1.20.6 原本请求 3.4.1，是 Quarkus BOM conflict resolution 提升到 3.4.2。该问题与上游 testcontainers-java #11212/#11235 同类，Testcontainers 2.0.2 已通过默认 API 1.44 修复。

---

## 3. Phase 3：Iceberg REST 读路径 + Bootstrap

### 3.1 目标

从 Polaris bootstrap main ref，并通过 Gateway Version Store 提供 Iceberg REST list/load 读路径。

### 3.2 产出

- `store/ContentValueRepository.java`
- `ref/RefResolver.java`
- `polaris/PolarisClient.java`
- `polaris/BootstrapService.java`
- `test/store/PostgreSQLTestResource.java`
- `test/api/LoadTableIT.java`

更新：

- `CatalogManagementResource.bootstrap`
- `IcebergNamespaceResource` 的 namespace list/load。
- `IcebergTableResource` 的 table list/load。
- `IndexRepository.listNamespaces`、`IndexRepository.listByNamespace`。
- `runtime/docker-compose.yml` 从 RustFS/STS mock 切换为 MinIO。

### 3.3 验收

自动化验收：

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.store.*" --tests "*.api.*"
```

结果：`LoadTableIT` 8/8，`RefRepositoryIT` 9/9。

live E2E（postgres + minio + bucket-setup + polaris）：

| 验收项 | 结果 |
|---|---|
| Polaris createTable `sales.orders` | PASS，metadata 写入 MinIO |
| Gateway bootstrap | PASS，返回 commitId |
| listNamespaces main | `sales` |
| listTables main | `orders` |
| loadTable main | metadata-location + metadata JSON |

阶段结论：PASS（bootstrap + main ref 读路径）。

### 3.4 修复记录

- 未知 ref：`listNamespaces` / `listTables` 原先返回 200 空列表，统一修复为 404。
- Quarkus dev mode：添加 `quarkus.http.host=0.0.0.0`，避免容器端口转发不可达。
- Polaris URL：容器路径使用 `http://polaris:8181`；宿主机 dev 通过 `POLARIS_CATALOG_URL=http://localhost:8281` 覆盖。
- RustFS → MinIO：RustFS 拒绝非自身颁发的 session token；STS mock 只能骗过 token 获取，无法骗过 S3 token 校验。MinIO 能闭环颁发和校验 session token。
- Polaris createTable 必需 `stsEndpoint` + `pathStyleAccess=true`，否则分别会访问公网 STS 或走虚拟托管式 DNS。
- Windows Docker 挂载卷中 Gradle project cache 文件锁失败，容器内 fallback 使用 `--project-cache-dir /tmp/gradle-project-cache`。

---

## 4. Phase 4：单表 Commit MVP

### 4.1 目标

支持 Iceberg 单表 commit：requirements 校验、metadata 写入、Version Store commit/index/CAS/audit/idempotency。

### 4.2 产出

- `audit/AuditService.java`
- `commit/IcebergRequirementsValidator.java`
- `commit/IcebergMetadataWriter.java`
- `commit/CommitService.java`
- `test/commit/CommitIT.java`

更新：

- `IcebergTableResource.commitTable`
- `BranchManagementResource.createBranch`
- `application.properties` 的 `gateway.s3.*`
- `build.gradle.kts` 的 `quarkus-junit5-mockito`、`iceberg-aws-bundle`

### 4.3 验收

自动化验收：

```powershell
.\gradlew.bat test --tests "*.commit.*"
```

结果：`CommitIT` 4/4。

覆盖场景：

- `commitSucceeds_branchHasNewSnapshot`
- `mainUnchangedAfterBranchCommit`
- `idempotencyReplay_sameResponse`
- `concurrentCommit_atLeastOneSucceeds`

Codex 复验：

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache test `
  --tests "*.store.*" `
  --tests "*.api.*" `
  --tests "*.commit.*" `
  --rerun-tasks
```

结果：21/21 通过。

Live E2E：bootstrap → create `dev_a` → commit to `dev_a` → main unchanged / dev snapshot advanced，PASS。

### 4.4 修复记录

真实 MinIO/S3 写路径暴露两个 `CommitIT` 未覆盖问题：

1. 缺 AWS SDK bundle：`NoClassDefFoundError: software/amazon/awssdk/services/s3/model/S3Exception`。修复：添加 `org.apache.iceberg:iceberg-aws-bundle`。
2. 缺 S3 region：`SdkClientException: Unable to load region`。修复：添加 `gateway.s3.region=us-east-1`，`IcebergMetadataWriter` 设置 `client.region`。

依赖说明：`iceberg-aws` 与 `iceberg-aws-bundle` 都需要保留。Iceberg 1.10.1 本地 artifact 验证结果：

- `iceberg-aws` 包含 `org/apache/iceberg/aws/s3/S3FileIO.class`，不包含 AWS SDK `S3Exception`。
- `iceberg-aws-bundle` 包含 AWS SDK `software/amazon/awssdk/services/s3/model/S3Exception.class`，不包含未重定位的 `S3FileIO.class`。

---

## 5. Phase 5：多表事务 Commit + Diff

### 5.1 目标

支持 `POST /iceberg/v1/{prefix}/transactions/commit` 多表一次 catalog commit，并提供 branch diff。

### 5.2 产出

- `diff/DiffService.java`
- `test/diff/DiffIT.java`
- `CommitService.multiTableCommit`
- `IcebergOperationsResource.commitTransaction`
- `BranchManagementResource.diffBranch`
- `IndexRepository.resolveAllContent`、`listAllContentAtIndex`

### 5.3 验收

自动化验收：

```powershell
.\gradlew.bat test --tests "*.store.*" --tests "*.api.*" --tests "*.commit.*" --tests "*.diff.*"
```

结果：24/24 通过。

`DiffIT` 覆盖：

- `multiTableTransaction_singleCatalogCommit`
- `diff_showsChangedAddedDeletedRenamed`
- `diff_usesCommitOpsNotFullScan`

Live E2E 主路径：

- bootstrap main
- create `dev_a`
- transaction commit `orders + items`
- idempotency replay 返回相同 commitId
- main unchanged，dev snapshots advanced
- diff 返回两张表 `MODIFIED`

### 5.4 修复记录

Codex E2E 发现 incremental index 读路径缺陷：

1. multi-table commit 生成 i1（parent=i0，含 orders/items）。
2. 再单表 commit 生成 i2（parent=i1，仅含 orders）。
3. 原读路径只查 i2，导致 `sales.items` 在 `dev_a` 上 404，listTables 只剩 orders。

修复：

- `IndexRepository.findEffectiveContent(indexId, contentKey)`：沿 INCREMENTAL → FULL 链查找；遇到 `deleted=true` 短路为空。
- `IndexRepository.listEffectiveByNamespace(indexId, namespace)`。
- `IndexRepository.listEffectiveNamespaces(indexId)`。

Call sites 从单层 index 查询改为 effective 查询：

- `IcebergTableResource.loadTable`
- `IcebergTableResource.listTables`
- `IcebergNamespaceResource.listNamespaces`
- `IcebergNamespaceResource.loadNamespace`
- `CommitService.commit`
- `CommitService.multiTableCommit`

live E2E 复验：

```text
AFTER_ORDERS_ONLY dev.orders=102 dev.items=201 tables=orders,items
FINAL dev.orders=102 dev.items=202 main.orders=-1 main.items=-1
DIFF_COUNT=2 TYPES=sales.items:MODIFIED,sales.orders:MODIFIED
EFFECTIVE_INDEX_E2E_PASS
```

回归测试已固化为 `LoadTableIT.loadTable_unchangedTableVisibleAfterSecondIncrementalCommit`。

---

## 6. Phase 6：Publish + Polaris Materialization

### 6.1 目标

支持 branch fast-forward publish 到 target ref（默认 main），写 audit/outbox，并由 worker 物化到 Polaris；提供 drift report。

### 6.2 产出

- `publish/LockService.java`
- `publish/PublishPlanner.java`
- `publish/PublishService.java`
- `polaris/MaterializationWorker.java`
- `polaris/DriftChecker.java`
- `test/publish/PublishIT.java`
- `src/test/resources/application.properties`：测试禁用 scheduler，`materialization.schedule=off`。

更新：

- `build.gradle.kts`：添加 `quarkus-scheduler`。
- `PolarisClient.materializeTable`：真实 Polaris Iceberg REST commit，实现 `add-snapshot + set-snapshot-ref`，含 snapshot id 幂等跳过。
- `PolarisClient.getTableSnapshotId`：调用 Polaris loadTable 提取 `current-snapshot-id`。
- `BranchManagementResource.publishPlan` / `publish`。
- `CatalogManagementResource.driftReport`。

### 6.3 验收

自动化验收：

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache test `
  --tests "*.store.*" `
  --tests "*.api.*" `
  --tests "*.commit.*" `
  --tests "*.diff.*" `
  --tests "*.publish.*" `
  --rerun-tasks
```

结果：29/29 通过。

`PublishIT` 覆盖：

- `publishSucceeds_mainRefUpdated`
- `publishRejects_whenMainDrifted`
- `outboxRetry_completesAfterTransientFail`
- `driftReport_detectsMismatch`

Live E2E 最终复验（合法当前 `timestamp-ms`）：

```text
HEALTH=UP
BOOTSTRAP=c0-...
BRANCH=dev_a
SNAP_BEFORE gateway.main=-1 gateway.dev=-1 polaris=-1
SNAP_AFTER_DEV_COMMIT gateway.main=-1 gateway.dev=101 polaris=-1
PLAN publishable=True conflicts=0
PUBLISH=c-...
SNAP_AFTER_PUBLISH gateway.main=101
SNAP_AFTER_WORKER polaris=101
DRIFT_COUNT=0 ITEMS=
MATERIALIZATION_E2E_PASS
```

DB 复核：

```text
outbox_materializations: COMPLETED attempts=0
audit_events: CREATE_BRANCH / TABLE_COMMIT / PUBLISH_FAST_FORWARD
main and dev_a head: same published commit
```

### 6.4 修复记录

Codex 初次 live E2E 暴露 materialization gap：

- publish 成功后 Gateway main snapshot 变为 101。
- outbox 被标记 `COMPLETED`。
- Polaris snapshot 仍为 `-1`。
- drift report 显示 Gateway 与 Polaris snapshot 不一致。

根因：`PolarisClient.materializeTable()` 当时只完成 worker 流程，尚未调用 Polaris REST 推进目标表 snapshot。

修复：

1. `PolarisClient.materializeTable()` 改为真实 REST 调用：
   - 解析 `metadataSummaryJson`，提取 `current-snapshot-id` 和目标 snapshot。
   - 如果 Polaris 当前 snapshot 已匹配，直接返回。
   - 构造 UpdateTableRequest：`add-snapshot` + `set-snapshot-ref`。
   - `POST /api/catalog/v1/{catalog}/namespaces/{namespace}/tables/{table}`。
   - `current-snapshot-id == -1` 时跳过空表物化。
2. `PublishPlanner` 不再静默吞 diff 异常：异常写入 conflicts，格式为 `__diff_error: ...`。
3. `DriftChecker` 不再把 auth 失败伪装成无 drift；`polarisClient.authenticate()` 失败时异常向 HTTP 层传播。

### 6.5 实现说明

- Fast-forward 判断：从 branch head 向父链遍历，找到 target head 即 `publishable=true`。
- Publish 执行：acquire lock → re-read heads → 校验 expected heads → fast-forward plan → CAS target ref → audit → outbox → release lock。
- Materialization worker：读取 due outbox → RUNNING → 对 commit_ops 的 `PUT` 操作调用 Polaris materialize → 成功 `COMPLETED`；失败指数退避，最多 5 次后 `FAILED`。
- DriftChecker：对 Gateway main effective index 的每张表读取 `snapshot_id`，与 Polaris 当前 snapshot 对比，不一致则返回 drift item。

---

## 7. 操作备忘

### 7.1 常用验收命令

```powershell
Set-Location Demo/p1_polaris_gateway/gateway-service

# Phase 6 / full P1 regression
.\gradlew.bat --no-daemon --no-configuration-cache test `
  --tests "*.store.*" `
  --tests "*.api.*" `
  --tests "*.commit.*" `
  --tests "*.diff.*" `
  --tests "*.publish.*" `
  --rerun-tasks
```

### 7.2 Live E2E 前置

Polaris 是 in-memory，重启后需要重新 seed：

1. 创建 catalog，必须包含：
   - `default-base-location`
   - `storageConfigInfo.endpoint=http://minio:9000`
   - `storageConfigInfo.endpointInternal=http://minio:9000`
   - `storageConfigInfo.stsEndpoint=http://minio:9000`
   - `storageConfigInfo.pathStyleAccess=true`
2. 创建 namespace。
3. 创建 table。
4. 清空 Gateway Version Store。
5. Gateway bootstrap。

清空 Gateway DB：

```powershell
docker exec p1-gateway-postgres psql -U p1_gateway -d p1_gateway -c "TRUNCATE catalog_content_index, catalog_refs, audit_events, catalog_commit_ops, outbox_materializations, idempotency_keys, branch_locks, catalog_ref_indexes, catalog_commits, catalog_content_values CASCADE;"
```

### 7.3 Scratch 文件

`.gitignore` 已覆盖 `Demo/p1_polaris_gateway/runtime/`，避免 `seed-metadata.json`、`sts-mock.py` 等 runtime 排障文件误入库。已跟踪的 `runtime/docker-compose.yml` 与 `runtime/smoke/*` 不受影响。
