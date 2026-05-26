# P1 Polaris Gateway 开发与验收 Memo

目录：`Demo/p1_polaris_gateway/`
设计依据：`Industry/git-like/5. Polaris_GitLike_REST_Gateway_P1_Research_And_Design.md`

---

## 0. 当前结论

### 0.1 总体状态

本 memo 记录 main 分支截至 MVP Phase 4 的状态。已完成：

1. Gateway 项目骨架、OpenAPI、DB schema、runtime compose。
2. Version Store repositories：refs、commits、indexes、content values。
3. Bootstrap + Iceberg REST list/load 读路径。
4. Branch create + 单表 commit MVP。

main 分支此状态不包含 Phase 5/6：多表 transaction commit、branch diff、publish、outbox materialization、drift report 尚未作为 main 当前能力记录。

MVP Phase 4 自动化验收：

```powershell
Set-Location Demo/p1_polaris_gateway/gateway-service
.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.commit.*" --rerun-tasks
.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.store.*" --tests "*.api.*" --tests "*.commit.*" --rerun-tasks
```

结果：

| Suite | Tests | Failures | Errors |
|---|---:|---:|---:|
| `RefRepositoryIT` | 9 | 0 | 0 |
| `LoadTableIT` | 8 | 0 | 0 |
| `CommitIT` | 4 | 0 | 0 |

MVP Phase 4 live E2E 结论：

- bootstrap main：PASS
- create `dev_a`：PASS
- commit to `dev_a`：PASS
- `main` snapshot 保持 `-1`
- `dev_a` snapshot 前进到 `1`
- DB 中存在 bootstrap commit、table commit、commit op、audit events、idempotency cache

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

1. **Phase 5/6 尚未进入 main 当前能力**：多表 transaction commit、branch diff、publish、materialization、drift report 仍是后续工作。
2. **Gateway 仍是 dev placeholder 运行模式**：compose 中的 gateway 服务用于挂载 workspace 和预置环境变量，尚未替换为正式 Quarkus runtime 镜像。
3. **Polaris in-memory 状态**：`polaris.persistence.type=in-memory`，重启后需要重新创建 catalog、namespace、table，并重新 bootstrap。
4. **Catalog storage 配置必须完整**：Polaris catalog 需要 `stsEndpoint: http://minio:9000` 和 `pathStyleAccess: true`。缺 `stsEndpoint` 会打到公网 STS；缺 `pathStyleAccess` 会走虚拟托管式 DNS 并在 Docker Desktop 上失败。
5. **MinIO AssumeRole 兼容性**：MinIO latest 当前实测接受 Polaris 使用的 AssumeRole 链路；升级 MinIO 时需要复验。
6. **Version Store 绕写限制**：客户端直接调用 Polaris 写表不会进入 Gateway Version Store；P1 设计要求写路径经 Gateway。
7. **Phase 4 幂等并发限制**：`idempotency_keys` 已支持完成态 replay；相同 idempotency key 并发首次写入时仍可能因唯一约束冲突暴露为 500，留待后续细化。
8. **S3 写入与 DB 事务边界**：单表 commit 先写 metadata 文件再写 DB/CAS；若 DB 阶段失败，S3 上可能留下未引用 metadata 文件。Phase 4 通过错误可见和后续 GC 口径处理，不在本阶段实现补偿删除。

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

实现 refs、commits、indexes 的基础存储能力，支持 branch 创建、CAS 更新、分页查询、软删除和 compaction 阈值判断。

### 2.2 产出

- `store/StoreConflictException.java`
- `store/RefRepository.java`
- `store/CommitRepository.java`
- `store/IndexRepository.java`
- `test/store/RefRepositoryIT.java`
- `test/resources/docker-java.properties`

### 2.3 验收

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.store.*"
```

结果：`RefRepositoryIT` 9/9 通过。

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

Testcontainers 在 Docker Desktop 新版本上启动 PostgreSQL 容器失败，表现为 Docker provider strategy 返回 HTTP 400，Testcontainers 无法解析 DockerInfo。

根因：`docker-java` 默认客户端 API version 太旧，低于本机 Docker daemon 最低要求。本项目实际解析到 `docker-java-api:3.4.2`，但默认 API 仍需显式覆盖。

修复：新增 `src/test/resources/docker-java.properties`：

```properties
api.version=1.44
```

该修复最小侵入，不升级 Quarkus/Testcontainers BOM。

---

## 3. Phase 3：Iceberg REST 读路径 + Bootstrap

### 3.1 目标

实现从 Polaris bootstrap 到 Gateway Version Store，再从 Gateway Iceberg REST list/load 的主读路径。验收口径为 bootstrap + main ref 读路径。

### 3.2 产出

- `store/ContentValueRepository.java`
- `ref/RefResolver.java`
- `polaris/PolarisClient.java`
- `polaris/BootstrapService.java`
- `test/store/PostgreSQLTestResource.java`
- `test/api/LoadTableIT.java`

更新：

- `application.properties`：启用 datasource health、Flyway migrate-at-start、`quarkus.http.host=0.0.0.0`、容器内 Polaris URL。
- `IndexRepository.java`：新增 namespace/table list 查询。
- `CatalogManagementResource.java`：实现 `POST /api/v1/catalogs/{catalog}/bootstrap`。
- `IcebergNamespaceResource.java`：实现 namespaces list/load。
- `IcebergTableResource.java`：实现 tables list/load。
- `runtime/docker-compose.yml`：从 RustFS/STS mock 切换到 MinIO 链路。

### 3.3 验收

自动化测试：

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.store.*" --tests "*.api.*"
```

结果：

- `LoadTableIT` 8/8
- `RefRepositoryIT` 9/9

live E2E：

| 验收项 | 结果 |
|---|---|
| Polaris createTable | metadata 写入 `s3://bucket123/test-catalog/sales/orders/metadata/` |
| Gateway bootstrap | 200，返回 `commitId` |
| listNamespaces | `{"namespaces":[["sales"]]}` |
| listTables | `{"identifiers":[{"namespace":["sales"],"name":"orders"}]}` |
| loadTable | metadata-location + 完整 metadata JSON |

结论：Phase 3 PASS，口径为 bootstrap + main ref 读路径。branch 创建和非 main ref 读路径进入 Phase 4。

### 3.4 修复记录

1. listNamespaces / listTables 对未知 ref 曾返回 200 空列表，与 loadTable 的 404 不一致。修复为未知 ref 统一返回 404，并新增测试覆盖。
2. Quarkus dev mode 默认绑定 loopback，Docker 端口转发不可达。修复为 `quarkus.http.host=0.0.0.0`。
3. Gateway 容器内使用 `localhost` 访问 Polaris 会指向自身。修复为容器内使用 `http://polaris:8181`，宿主机开发通过环境变量覆盖为 `http://localhost:8281`。
4. RustFS 无法接受外部 STS mock 颁发的 session token。修复为使用 MinIO，由同一 backend 颁发并校验 token。
5. Polaris createTable 需要完整 storage config：`stsEndpoint` 和 `pathStyleAccess` 都必须设置。
6. `CatalogManagementResource` 类级 `@Consumes(APPLICATION_JSON)` 曾导致无 body bootstrap 请求返回 415。修复为只在需要 body 的方法上声明 `@Consumes`。

---

## 4. Phase 4：单表 Commit MVP

### 4.1 目标

支持 Iceberg 单表 commit：requirements 校验、metadata 写入、Version Store commit/index/CAS/audit/idempotency，并支持创建 branch 后在 branch 上提交。

### 4.2 产出

新增：

- `commit/CommitConflictException.java`
- `commit/IcebergRequirementsValidator.java`
- `commit/IcebergMetadataWriter.java`
- `commit/CommitService.java`
- `test/commit/CommitIT.java`

更新：

- `IcebergTableResource.commitTable`：从 placeholder 改为注入 `CommitService`，处理 `Idempotency-Key` / `X-Request-ID`，将 requirement/CAS 冲突映射为 409。
- `BranchManagementResource.createBranch`：从 placeholder 改为调用 `RefRepository.createBranch`，解析 source ref head，写 audit event，返回 201。
- `build.gradle.kts`：新增 Iceberg core/common/aws 相关依赖，补充 `iceberg-aws-bundle` 以满足 AWS SDK v2 runtime classes。
- `application.properties`：补齐 `gateway.s3.endpoint`、access key、secret key、region。

### 4.3 验收

自动化测试：

```powershell
.\gradlew.bat test --tests "*.commit.*"
```

结果：`CommitIT` 4/4 通过。

覆盖场景：

- `commitSucceeds_branchHasNewSnapshot`
- `requirementFailure_returnsConflict`
- `idempotencyReplay_sameResponse`
- `concurrentCommit_atLeastOneSucceeds`

回归测试：

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.store.*" --tests "*.api.*" --tests "*.commit.*" --rerun-tasks
```

结果：

- `RefRepositoryIT` 9/9
- `LoadTableIT` 8/8
- `CommitIT` 4/4

live E2E：

| 验收项 | 结果 |
|---|---|
| Gateway health | `UP` |
| bootstrap | 返回 bootstrap commit |
| create `dev_a` | 201，`dev_a` head 指向 bootstrap commit |
| commit to `dev_a` | 成功写出新 metadata |
| loadTable main | snapshot 保持 `-1` |
| loadTable dev_a | snapshot 前进到 `1` |
| DB refs | `main` 指向 bootstrap commit，`dev_a` 指向 table commit |
| audit events | 包含 `CREATE_BRANCH` 与 `TABLE_COMMIT` |
| idempotency cache | 包含提交请求的 `COMPLETED` 响应缓存 |

### 4.4 修复记录

1. `IcebergMetadataWriter` 真实调用 Iceberg `S3FileIO` 时缺少 AWS SDK bundle，commit 失败于 `NoClassDefFoundError: software/amazon/awssdk/services/s3/model/S3Exception`。修复：添加 `org.apache.iceberg:iceberg-aws-bundle`。
2. 补齐 bundle 后，真实 S3 写入仍缺少 region，commit 失败于 `SdkClientException: Unable to load region`。修复：新增 `gateway.s3.region=us-east-1`，并在 `IcebergMetadataWriter` 中设置 `client.region`。
3. Docker Desktop 未启动时，Testcontainers 回归会失败于找不到 Docker environment。该失败归类为环境前置问题，不是业务回归。

---

## 5. 后续任务

MVP Phase 4 之后的下一批任务：

- 实现 `IcebergOperationsResource` 的 multi-table transaction commit。
- 实现 commit ops / index comparison 双路径 diff。
- 增加 diff 测试矩阵：changed、added、deleted、renamed。
- 设计并实现 publish fast-forward、outbox materialization、drift report。
- 将 gateway placeholder 替换为正式 runtime 镜像或明确保留 dev-only profile。
