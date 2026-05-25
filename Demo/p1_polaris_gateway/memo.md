# P1 Polaris Gateway 开发与验收 Memo

日期：2026-05-21 ~ 2026-05-22  
目录：`Demo/p1_polaris_gateway/`  
设计依据：`Industry/git-like/5. Polaris_GitLike_REST_Gateway_P1_Research_And_Design.md`

---

## 1. Phase 1：项目骨架

### 1.1 产出

- `gateway-service/`
  - `settings.gradle.kts`、`gradle.properties`、`build.gradle.kts`
  - Gradle Wrapper 8.14.5：`gradlew`、`gradlew.bat`、`gradle/wrapper/*`
  - `src/main/resources/application.properties`
  - `src/main/resources/openapi.yaml`
  - `src/main/resources/db/migration/V1__init.sql`（10 张核心表）
  - Iceberg REST 占位资源：`IcebergConfigResource`、`IcebergNamespaceResource`、`IcebergTableResource`、`IcebergOperationsResource`
  - P1 管理 API 占位资源：`BranchManagementResource`、`CatalogManagementResource`、`CommitManagementResource`
- `runtime/docker-compose.yml`（postgres、rustfs、polaris、gateway placeholder）
- `runtime/smoke/smoke-phase1.ps1`
- `.gitignore`

行为约定：未实现 API 明确返回 `501 PHASE_1_PLACEHOLDER`，避免静默成功。

### 1.2 验收

#### 1.2.1 验收结果（2026-05-21）

| 验收项 | 命令 | 结果 |
|--------|------|------|
| 编译通过 | `.\gradlew.bat compileJava` | ✅ |
| 10 张核心表 | `(Select-String -Path V1__init.sql -Pattern '^CREATE TABLE').Count` | ✅ 10 |
| Health UP | `GET /q/health` | ✅ UP |
| OpenAPI 18 条路径 | `GET /q/openapi`（统计 paths 数） | ✅ 18 |
| Compose 4 服务启动 | `docker compose up -d && docker compose ps` | ✅ |

#### 1.2.2 修复项

- `settings.gradle.kts`：将 Maven Central 放在本地 `.m2` 前，防止本地不完整仓库遮蔽缺失 jar。
- `smoke-phase1.ps1`：PowerShell 直接访问 `$openapi.paths.PSObject.Properties.Count` 输出异常，改用 `($openapi.paths.PSObject.Properties | Measure-Object).Count`。

---

## 2. Phase 2：Version Store Repository

### 2.1 产出

新增文件：

- `store/StoreConflictException.java`
- `store/RefRepository.java`（create branch / get / list with pagination / CAS update head / soft delete）
- `store/CommitRepository.java`（insert / find by id）
- `store/IndexRepository.java`（insert/find index；insert/find content index；compaction 阈值：20 incremental indexes 或 ops > 1000）
- `test/store/RefRepositoryIT.java`（9 个 Testcontainers 集成测试）
- `test/resources/docker-java.properties`（`api.version=1.44`，见修复项）

`build.gradle.kts` 变更：Testcontainers BOM 1.20.6；添加 `flyway-core`、`flyway-database-postgresql`、`postgresql`、`testcontainers:junit-jupiter`、`testcontainers:postgresql` 依赖；Windows 测试任务设置 `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` + `DOCKER_API_VERSION=1.54`。

### 2.2 验收

#### 2.2.1 验收结果（2026-05-21）

```
.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.store.*"
BUILD SUCCESSFUL in 35s  —  tests="9" skipped="0" failures="0" errors="0"
```

测试列表：`createBranch_success`、`createBranch_duplicateName`、`casUpdate_succeeds`、`casUpdate_conflictsOnMismatch`、`listBranches_excludesDeleted`、`insertAndFindCommit_success`、`insertAndFindIndex_success`、`shouldCompact_atTwentyIncrementalIndexes`、`shouldCompact_whenOpsExceedThreshold`

备用路径（`P1_EXTERNAL_POSTGRES_JDBC_URL` env var）保留于 `RefRepositoryIT.java`，供 CI 无 Docker 环境使用。

#### 2.2.2 修复项：Testcontainers Docker API 版本问题

**现象**：两个 Docker provider strategy 均返回 HTTP 400，响应体为空 DockerInfo JSON，Testcontainers 无法启动 PostgreSQL 容器。

**根因**：`docker-java` 默认客户端 API version 1.24，低于 Docker Desktop 29.4.3 daemon 要求的最低 version 1.40，`/info` 请求被拒绝并返回 400。本项目实际解析到 `docker-java-api:3.4.2`（Quarkus BOM conflict resolution 将 Testcontainers 1.20.6 的 3.4.1 提升）；该问题与上游 testcontainers-java #11212/#11235 同类，Testcontainers 2.0.2 已通过将默认版本提升至 1.44 正式修复。

**修复**（最小侵入，不升级 BOM）：新增 `src/test/resources/docker-java.properties`：

```properties
api.version=1.44
```

---

## 3. Phase 3：Iceberg REST 读路径 + Bootstrap

### 3.1 产出

新增文件：

- `store/ContentValueRepository.java`（insert / findByValueId）
- `ref/RefResolver.java`（`X-Catalog-Ref` header 优先，默认 "main"）
- `polaris/PolarisClient.java`（authenticate / listNamespaces / listTables / loadTable）
- `polaris/BootstrapService.java`（`@Transactional`：获取 token → 拉取所有 namespace/table → loadTable → 写入 Version Store）
- `test/store/PostgreSQLTestResource.java`（`@QuarkusTestResource` Testcontainers PostgreSQL）
- `test/api/LoadTableIT.java`（8 个测试）

更新文件：

- `application.properties`：`datasource.health.enabled=true`、`flyway.migrate-at-start=true`、`quarkus.http.host=0.0.0.0`、`polaris.catalog.url=http://polaris:8181`
- `IndexRepository.java`：新增 `listNamespaces(indexId)`、`listByNamespace(indexId, namespace)`
- `CatalogManagementResource.java`：实现 `POST /api/v1/catalogs/{catalog}/bootstrap`
- `IcebergNamespaceResource.java`：实现 `GET /namespaces`、`GET /namespaces/{ns}`（未知 ref → 404）
- `IcebergTableResource.java`：实现 `GET /namespaces/{ns}/tables`、`GET /namespaces/{ns}/tables/{table}`（未知 ref → 404）
- `build.gradle.kts`：添加 `rest-assured` 测试依赖
- `runtime/docker-compose.yml`：移除 rustfs + sts-mock，新增 MinIO（见 3.2.3）

关键设计约定：

- load table 调用链：`RefRepository.getRef` → `CommitRepository.findById`（取 `indexId`）→ `IndexRepository.findContent` → `ContentValueRepository.findByValueId` → 返回 metadata-location + metadata JSON
- Bootstrap 存储链：`BootstrapService` 调用 Polaris `loadTable`，将完整 metadata JSON 存入 `catalog_content_values.metadata_summary_json`，避免 Phase 3 读路径重新访问 S3

### 3.2 验收

#### 3.2.1 验收结果

**集成测试（2026-05-21）**：

```
.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.store.*" --tests "*.api.*"
BUILD SUCCESSFUL
TEST LoadTableIT:     tests="8" skipped="0" failures="0" errors="0"
TEST RefRepositoryIT: tests="9" skipped="0" failures="0" errors="0"
```

LoadTableIT 覆盖：`loadTable_returnsMetadataLocation`、`loadTable_noRefHeader_usesMain`、`loadTable_unknownRef_returns404`、`loadTable_unknownTable_returns404`、`listTables_returnsTableInNamespace`、`listNamespaces_returnsNamespace`，以及新增的 2 个 unknown-ref 404 用例。

**端到端验收——空 catalog（2026-05-21，compose: postgres + rustfs + sts-mock + polaris）**：

| 验收项 | 结果 |
|--------|------|
| Gateway health `GET /q/health` | ✅ UP |
| Bootstrap 空 catalog `POST /api/v1/catalogs/test/bootstrap` | ✅ 200，commitId |
| Bootstrap 幂等性（第二次调用） | ✅ 409 |
| listNamespaces（main ref，无表） | ✅ `{"namespaces":[]}` |
| listNamespaces / listTables 未知 ref | ✅ 404（修复后） |
| Polaris createTable（真实 Iceberg 表） | ❌ RustFS 拒绝 SessionToken（blocking） |

**端到端验收——有真实 Iceberg 表（2026-05-22，compose: postgres + minio + bucket-setup + polaris，MinIO 修复后）**：

| 验收项 | 命令/操作 | 结果 |
|--------|----------|------|
| Polaris createTable（namespace=sales，table=orders） | Polaris REST API | ✅ metadata 写入 `s3://bucket123/test-catalog/sales/orders/metadata/` |
| Gateway bootstrap | `POST /api/v1/catalogs/test/bootstrap` | ✅ 200，`commitId: c0-f941418d-...` |
| listNamespaces | `GET /iceberg/v1/test/namespaces?X-Catalog-Ref=main` | ✅ `{"namespaces":[["sales"]]}` |
| listTables | `GET /iceberg/v1/test/namespaces/sales/tables?X-Catalog-Ref=main` | ✅ `{"identifiers":[{"namespace":["sales"],"name":"orders"}]}` |
| loadTable | `GET /iceberg/v1/test/namespaces/sales/tables/orders?X-Catalog-Ref=main` | ✅ metadata-location + 完整 metadata JSON |

loadTable 响应摘要：
```json
{
  "metadata-location": "s3://bucket123/test-catalog/sales/orders/metadata/00000-29b5d719-....metadata.json",
  "metadata": {
    "table-uuid": "7b678a69-ce2e-4d67-9b8a-e4204b211874",
    "format-version": 2,
    "location": "s3://bucket123/test-catalog/sales/orders",
    "schemas": [{"fields": [{"id":1,"name":"id","type":"long"},{"id":2,"name":"amount","type":"double"}]}]
  }
}
```

**全链路验收结论：PASS（bootstrap + main ref 读路径口径）。**  
注：branch 创建（`POST /branches` → 501）和非 main ref 读路径不在本阶段验收范围，见 Phase 4 首批任务。

#### 3.2.2 修复项

**1. listNamespaces / listTables 对未知 ref 返回 200 空列表（与 loadTable 的 404 不一致）**

修复：对不存在的 ref 统一返回 404 `{"error":"ref not found: {ref}"}`；新增 2 个测试用例覆盖。

**2. Quarkus dev mode 仅绑定 loopback，Docker 端口转发不可达**

原因：Quarkus dev mode 默认 `quarkus.http.host=127.0.0.1`；Docker 端口转发映射容器 IP，不是 loopback，外部请求到达容器后被拒绝。  
修复：`application.properties` 添加 `quarkus.http.host=0.0.0.0`。

**3. PolarisClient 使用 localhost 而非 Docker 服务名**

原因：`polaris.catalog.url=http://localhost:8281`——Gateway 容器内 localhost 指向自身，不是 Polaris 容器。  
修复：改为 `polaris.catalog.url=http://polaris:8181`（compose 服务名 + 内部端口）。

#### 3.2.3 过程关键节点（E2E 解锁路径）

**节点 1：RustFS session token 拦截，STS Mock 尝试失败**

Polaris INTERNAL catalog 写表流程：`createTable → STS AssumeRole → subscoped credentials（含 SessionToken）→ 用 credentials 写 S3`。  
RustFS 1.0.0-alpha.81 校验 `X-Amz-Security-Token`，token 非自身颁发则拒绝（`invalid token2`）→ Broken pipe。

尝试：新增 `sts-mock.py`（Python SimpleHTTPServer，返回伪造的 AssumeRole XML）。结果：Polaris AssumeRole 调用成功，但 Polaris 持 STS Mock 返回的 credentials 写 S3 时，RustFS 仍然拒绝——STS Mock 只骗过了 Polaris 的 token 获取，S3 端校验 token 来源无法绕过。根本矛盾：session token 必须由 S3 backend 自身颁发才能通过自身校验。

**节点 2：MinIO 替换决策与 docker-compose.yml 重写**

MinIO 自身颁发并校验 session token，链路闭环。

compose 变更：
- 移除 `rustfs`、`sts-mock` 服务
- 新增 `minio/minio:latest`（端口 9100:9000 / 9101:9001，内置 console）
- `bucket-setup` 依赖改为 `minio: service_healthy`
- Polaris 环境变量改为 `AWS_ENDPOINT_URL_S3=http://minio:9000`、`AWS_ENDPOINT_URL_STS=http://minio:9000`

**节点 3：容器孤儿 + 端口冲突（compose 首次启动失败）**

rustfs 容器残留占用端口 9100，minio 绑定失败后留下无网络孤儿容器；再次 `compose up` 时 minio 仍无网络。  
修复：`docker stop / rm` 旧容器 + `docker compose down --remove-orphans`，再全新启动。

**节点 4：Polaris createTable 双根因诊断（198.18.1.89）**

现象：createTable 返回 `NoHttpResponseException`，6 次重试后失败。  
诊断：`cat /proc/net/tcp6` 抓取 createTable 执行时的远端连接，解码地址字段 `590112C6`（小端序）→ `198.18.1.89:9000`。`curl http://198.18.1.89:9000` → "Empty reply from server"。`198.18.1.89` 是 Docker Desktop on Windows 的 DNS NXDOMAIN fallback IP。

- **根因 A：`stsEndpoint: null`**——catalog 创建时未设置该字段，Polaris 调用真实 `sts.amazonaws.com`，容器内不可达。
- **根因 B：`pathStyleAccess: false`（默认）**——Polaris 构造虚拟托管式 URL `bucket123.minio:9000`，Docker Desktop DNS 无法解析 `bucket123.minio` → NXDOMAIN → fallback IP `198.18.1.89` → 空响应。

修复：通过 PUT 更新 catalog `storageConfigInfo`：

```json
{
  "stsEndpoint": "http://minio:9000",
  "pathStyleAccess": true
}
```

两个字段缺一不可：缺 `stsEndpoint` → 打到公网 STS；`pathStyleAccess: false` → 虚拟托管式 DNS 失败。

**节点 5：Gradle 文件锁问题（Windows 挂载卷）**

`/workspace/.gradle` 在 Docker Desktop VirtioFS 挂载上，`fcntl` 文件锁不可用 → `LockStateAccess Input/output error` → BUILD FAILED。  
修复：`./gradlew quarkusDev --project-cache-dir /tmp/gradle-project-cache`（project cache 移至容器原生 tmpfs）。

---

## 4. 当前状态与已知限制

1. **Polaris in-memory 状态**：`polaris.persistence.type=in-memory`，重启即失。每次 compose 重启后需重新通过 REST API 创建 catalog（含 `stsEndpoint: http://minio:9000` + `pathStyleAccess: true`）、namespace、table，再调用 Gateway bootstrap。

2. **MinIO AssumeRole 兼容性**：MinIO 官方仅承诺 `GetSessionToken` 和 OIDC ARWI，不承诺带 `roleArn` 的 `AssumeRole`。当前 MinIO latest 实测接受。升级 MinIO 版本时需关注此行为变化。

3. **Gateway 运行方式**：本地开发/测试推荐宿主机 `quarkusDev`；placeholder + `docker exec` 仅作为无宿主机 Gradle 环境/CI fallback。Phase 4 完成 commit MVP 后替换为真实 Quarkus 服务容器，届时移除 placeholder 的源码挂载。

4. **Version Store 绕写**：客户端直接调用 Polaris 写表不经 Gateway，Version Store 无法感知；该限制源自设计约定。

---

## 5. Phase 4：单表 Commit MVP

### 5.1 产出

新增文件：

- `audit/AuditService.java`（写 `audit_events`，jOOQ + DataSource，同 JTA 事务参与）
- `commit/IcebergRequirementsValidator.java`（覆盖 8 类 requirements：`assert-create`、`assert-table-uuid`、`assert-ref-snapshot-id`、`assert-last-assigned-field-id`、`assert-current-schema-id`、`assert-last-assigned-partition-id`、`assert-default-spec-id`、`assert-default-sort-order-id`）
- `commit/IcebergMetadataWriter.java`（S3FileIO 写 metadata JSON，由 `gateway.s3.*` 配置驱动；测试中通过 `@InjectMock` 桩替换）
- `commit/CommitService.java`（`@Transactional`：幂等键检查 → requirements 校验 → 元数据更新 → S3 写入（非 JTA）→ DB 事务（insert content\_value + commit + commit\_ops + incremental index + content\_index + CAS + audit + idempotency）→ CAS 失败 throw `CommitConflictException` 触发回滚）
- `test/commit/CommitIT.java`（4 个验收场景，`@InjectMock IcebergMetadataWriter`，PostgreSQL Testcontainers）

更新文件：

- `build.gradle.kts`：添加 `quarkus-junit5-mockito` 测试依赖；真实 S3 写路径补充 `org.apache.iceberg:iceberg-aws-bundle`
- `application.properties`：新增 `gateway.s3.endpoint/access-key-id/secret-access-key/region`（指向 MinIO，region 默认 `us-east-1`）
- `api/iceberg/IcebergTableResource.java`：`commitTable` 从 placeholder 改为注入 `CommitService`，处理 `Idempotency-Key` / `X-Request-ID` header，catch `RequirementFailedException` + `CommitConflictException` → 409
- `api/management/BranchManagementResource.java`：`createBranch` 从 placeholder 改为调用 `RefRepository.createBranch`，resolves sourceRef head，写 audit event，返回 201

关键设计约定：

- CommitService 是 `@Transactional` 单方法；S3 写在 JTA 边界内但非 JTA 资源，CAS 失败回滚 DB，S3 文件变孤儿由 GC dry-run 处理（5.md §6.4）。
- `applyUpdates` 支持 `add-snapshot`、`set-snapshot-ref`、`set-current-schema`、`set-default-partition-spec`、`set-default-sort-order` 五类 action，未知 action 静默忽略。
- 幂等键存储于 `idempotency_keys` 表（status=COMPLETED + response\_payload），第二次请求查到 COMPLETED 直接返回缓存，不再调用 S3 write。
- 并发相同幂等键：二者同时 INSERT → 唯一约束冲突 → 先失败者 500（Phase 4 范围，Phase 5 可细化）。

### 5.2 验收

#### 5.2.1 验收结果（2026-05-22）

```
.\gradlew.bat test --tests "*.commit.*"
BUILD SUCCESSFUL

Suite: CommitIT | tests=4 failures=0 errors=0

[PASS] commitSucceeds_branchHasNewSnapshot
[PASS] mainUnchangedAfterBranchCommit
[PASS] idempotencyReplay_sameResponse
[PASS] concurrentCommit_atLeastOneSucceeds
```

回归测试：

```
.\gradlew.bat test --tests "*.store.*" --tests "*.api.*"
BUILD SUCCESSFUL

Suite: LoadTableIT    | tests=8  failures=0 errors=0
Suite: RefRepositoryIT| tests=9  failures=0 errors=0
```

全部 21 个测试通过（Phase 1-4 累计）。

#### 5.2.2 Codex 复验与运行时修复（2026-05-25）

Claude 完成 Phase 4 后，Codex 重新执行了测试与真实运行态验收。测试层面：

```
.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.commit.*" --rerun-tasks
BUILD SUCCESSFUL
CommitIT: tests=4 skipped=0 failures=0 errors=0

.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.store.*" --tests "*.api.*" --tests "*.commit.*" --rerun-tasks
BUILD SUCCESSFUL
LoadTableIT:     tests=8 skipped=0 failures=0 errors=0
CommitIT:        tests=4 skipped=0 failures=0 errors=0
RefRepositoryIT: tests=9 skipped=0 failures=0 errors=0
```

真实 E2E 路径（compose 启动 postgres + minio + bucket-setup + polaris，宿主机运行 Gateway `quarkusDev`，覆盖 `POLARIS_CATALOG_URL=http://localhost:8281` 与 `GATEWAY_S3_ENDPOINT=http://localhost:9100`）最初暴露了两个 `CommitIT` 未覆盖的问题：

1. `IcebergMetadataWriter` 真实调用 Iceberg `S3FileIO` 时缺少 AWS SDK bundle，commit 失败于 `NoClassDefFoundError: software/amazon/awssdk/services/s3/model/S3Exception`。修复：添加 `org.apache.iceberg:iceberg-aws-bundle`。
2. 补齐 bundle 后，真实 S3 写入仍缺少 region，commit 失败于 `SdkClientException: Unable to load region`。修复：新增 `gateway.s3.region=us-east-1`，并在 `IcebergMetadataWriter` 中设置 `client.region`。

依赖说明：`iceberg-aws` 与 `iceberg-aws-bundle` 当前都需要保留。Iceberg 1.10.1 本地 artifact 验证结果为：`iceberg-aws` 包含 `org/apache/iceberg/aws/s3/S3FileIO.class`，但不包含 AWS SDK `S3Exception`；`iceberg-aws-bundle` 包含 AWS SDK `software/amazon/awssdk/services/s3/model/S3Exception.class`，但不包含未重定位的 `S3FileIO.class`。因此它们不是重复的 `S3FileIO` 实现关系。

修复后真实 E2E 通过：

| 验收项 | 结果 |
|--------|------|
| Gateway health | `UP` |
| `POST /api/v1/catalogs/test/bootstrap` | 返回 bootstrap commit |
| `POST /api/v1/catalogs/test/branches` 创建 `dev_a` | 201，`dev_a` head 指向 bootstrap commit |
| commit 到 `dev_a` | 成功写出新 metadata：`s3://bucket123/test-catalog/sales/orders/metadata/d9199f4c-49c1-4904-af0d-ea1880d8fac8.metadata.json` |
| commit 后 loadTable | `main` snapshot 保持 `-1`，`dev_a` snapshot 变为 `1` |

运行态 DB 复核：

| 表 | 行数 |
|----|------|
| `catalog_refs` | 2 |
| `catalog_commits` | 2 |
| `catalog_content_values` | 2 |
| `catalog_content_index` | 2 |
| `catalog_commit_ops` | 1 |
| `audit_events` | 2 |
| `idempotency_keys` | 1 |

`catalog_refs` 中 `main` 指向 bootstrap commit，`dev_a` 指向 Phase 4 table commit；`audit_events` 包含 `CREATE_BRANCH` 与 `TABLE_COMMIT`；`idempotency_keys` 包含 `phase4-e2e-001` 的 `COMPLETED` 响应缓存。

补充说明：2026-05-25 复跑回归测试时首次失败于 Testcontainers 找不到 Docker environment，根因是 Docker Desktop daemon 未启动。启动 Docker Desktop 后，同一条 Gradle 命令通过，因此该失败归类为环境前置问题，不是业务回归。

#### 5.2.3 任务完成情况

- [x] 实现 `BranchManagementResource#createBranch`（`POST /api/v1/catalogs/{catalog}/branches`）
- [x] 实现 `IcebergRequirementsValidator`（覆盖 8 类 table requirements）
- [x] 实现 `IcebergMetadataWriter`（S3FileIO 写 metadata JSON，`gateway.s3.*` 配置，真实 MinIO 写入已通过 E2E）
- [x] 实现 `CommitService`（requirements 校验 → 写 metadata → DB 事务 + CAS + 幂等）
- [x] `IcebergTableResource.commitTable` 从 placeholder 改为调用 CommitService
- [x] 实现 `AuditService`（写 audit\_events）
- [x] 新增 `CommitIT`（4 个验收场景全 PASS）

注：`createBranch` 的真实 E2E 链路已在 2026-05-25 复验中覆盖：`POST /branches → 201`，随后 commit 到 `dev_a` 并验证 `main` 未变化、`dev_a` snapshot 前进。

---

## 6. Phase 5 首批任务（多表事务 + Diff）

**Gateway 运行约定（两套，按场景选择）**

*开发 / 本地测试（推荐）*：compose 只启动依赖服务，Gateway 在宿主机运行：
```powershell
docker compose up -d postgres minio bucket-setup polaris
$env:POLARIS_CATALOG_URL = "http://localhost:8281"
.\gradlew.bat quarkusDev
```

*无宿主机环境 / CI*：
```bash
docker compose up -d
docker exec p1-gateway-placeholder ./gradlew quarkusDev --project-cache-dir /tmp/gradle-project-cache
```

**Bootstrap 调用**（无需 Content-Type，`@Consumes` 已移至方法级）：
```bash
curl -s -X POST http://localhost:8080/api/v1/catalogs/test/bootstrap -w "\nHTTP:%{http_code}"
```

- [ ] 实现 `api/iceberg/IcebergOperationsResource`（`POST /v1/{prefix}/transactions/commit` 多表一次 catalog commit）
- [ ] 实现 `diff/DiffService.java`（commit ops 路径 ≤ 50 commits；index 对比路径 > 50 commits）
- [ ] 实现 `api/management/DiffResource.java`（`POST /api/v1/catalogs/{catalog}/branches/{branch}/diff`）
- [ ] 新增 `test/diff/DiffIT.java`（覆盖 `multiTableTransaction_singleCatalogCommit`、`diff_showsChangedAddedDeletedRenamed`、`diff_usesCommitOpsNotFullScan`）
- [ ] E2E 验收（5.md Phase 5 验收脚本）
