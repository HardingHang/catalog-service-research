# P1 Polaris Gateway Phase 1-2 开发与验收 Memo

日期：2026-05-21  
目录：`Demo/p1_polaris_gateway/`  
设计依据：`Industry/git-like/5. Polaris_GitLike_REST_Gateway_P1_Research_And_Design.md`

## 1. 当前结论

- Phase 1 已完成并验收通过：Quarkus/Gradle 项目骨架、OpenAPI、Flyway schema、runtime compose 均已落地并亲自复验。
- Phase 2 已完成并验收通过：原始 Testcontainers 路径已修复，9 个测试全部通过，无需外部 PostgreSQL 绕过。
- **Phase 2 Testcontainers 修复**：根因是 docker-java 3.4.2（Testcontainers 1.20.6 内置）默认使用 API version 1.24，而 Docker 29.x 要求最低 1.44，导致 HTTP 400。修复方式：添加 `src/test/resources/docker-java.properties`，内容为 `api.version=1.44`。这是已知上游问题（testcontainers-java #11212/#11235），在 Testcontainers 2.0.2 中已正式修复；当前 1.20.6 通过 properties 文件一行配置绕过。
- Phase 2 可进入 Phase 3，前置条件全部满足。

## 2. Phase 1 产出

新增 P1 demo 目录：

- `gateway-service/`
  - `settings.gradle.kts`
  - `gradle.properties`
  - `build.gradle.kts`
  - Gradle Wrapper 8.14.5：`gradlew`、`gradlew.bat`、`gradle/wrapper/*`
  - `src/main/resources/application.properties`
  - `src/main/resources/openapi.yaml`
  - `src/main/resources/db/migration/V1__init.sql`
  - Iceberg REST 占位资源：
    - `IcebergConfigResource.java`
    - `IcebergNamespaceResource.java`
    - `IcebergTableResource.java`
    - `IcebergOperationsResource.java`
  - P1 管理 API 占位资源：
    - `BranchManagementResource.java`
    - `CatalogManagementResource.java`
    - `CommitManagementResource.java`
- `runtime/`
  - `docker-compose.yml`
  - `smoke/smoke-phase1.ps1`
- `.gitignore`

Phase 1 行为说明：

- `IcebergConfigResource` 返回最小 config。
- 其余未实现 API 明确返回 `501 PHASE_1_PLACEHOLDER`，避免静默成功。
- `application.properties` 中暂时设置 `quarkus.datasource.health.enabled=false`，使 Phase 1 骨架可在没有 DB 连接时通过 `/q/health` smoke。
- `runtime/docker-compose.yml` 包含 PostgreSQL 16、RustFS、Polaris 1.4.1、Gateway placeholder 服务。这里的 Gateway compose 服务只是占位健康检查，不代表真实 Quarkus Gateway 已在容器内运行。

## 3. Phase 1 验收过程与结果

执行过的验收：

```powershell
cd Demo/p1_polaris_gateway/gateway-service
.\gradlew.bat compileJava
```

结果：通过。

```powershell
(Select-String -Path src/main/resources/db/migration/V1__init.sql -Pattern '^CREATE TABLE').Count
```

结果：`10`，符合设计要求的 10 张核心表。

```powershell
.\gradlew.bat quarkusDev
Invoke-RestMethod http://localhost:8080/q/health
Invoke-RestMethod http://localhost:8080/q/openapi
```

结果：

- health：`UP`
- OpenAPI path count：`18`

```powershell
cd Demo/p1_polaris_gateway/runtime
docker compose up -d
docker compose ps
```

结果：P1 runtime 相关服务可启动，核心服务 running/healthy。之后已执行 `docker compose down` 清理。

Phase 1 局部修正：

- `settings.gradle.kts` 中将 Maven Central 放在本地 `src_ref/.m2` 前面，因为本地仓库不完整，会遮蔽缺失 jar。
- `smoke-phase1.ps1` 中 OpenAPI path 计数使用：

```powershell
($openapi.paths.PSObject.Properties | Measure-Object).Count
```

原因：PowerShell 直接访问 `.PSObject.Properties.Count` 时输出异常，可能得到重复的 `1`。

## 4. Phase 2 产出

新增 Version Store Repository：

- `gateway-service/src/main/java/io/polaris/gateway/store/StoreConflictException.java`
- `gateway-service/src/main/java/io/polaris/gateway/store/RefRepository.java`
- `gateway-service/src/main/java/io/polaris/gateway/store/CommitRepository.java`
- `gateway-service/src/main/java/io/polaris/gateway/store/IndexRepository.java`

新增集成测试：

- `gateway-service/src/test/java/io/polaris/gateway/store/RefRepositoryIT.java`

`build.gradle.kts` Phase 2 相关变化：

- Quarkus BOM 使用 `platform(...)`。
- Testcontainers BOM 固定为 `1.20.6`。
- 添加测试依赖：
  - `org.flywaydb:flyway-core`
  - `org.flywaydb:flyway-database-postgresql`
  - `org.postgresql:postgresql`
  - `org.testcontainers:junit-jupiter`
  - `org.testcontainers:postgresql`
- Windows 测试任务设置：

```kotlin
environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
environment("DOCKER_API_VERSION", "1.54")
```

Repository 当前能力：

- `RefRepository`
  - create branch
  - duplicate branch conflict
  - get ref
  - list branches with pagination
  - CAS update head
  - soft delete ref
- `CommitRepository`
  - insert commit
  - find by commit id
- `IndexRepository`
  - insert/find index
  - insert/find content index
  - compaction 阈值判断
    - incremental index 数量达到 20
    - commit ops 数量超过 1000

## 5. Phase 2 原始验收失败（已修复）

**原始失败现象**：`gradlew.bat test --tests "*.store.*"` 在 Testcontainers 启动 PostgreSQL 前失败，两个 Docker provider strategy 均返回 HTTP 400，响应体为空 DockerInfo JSON。

**根因**：docker-java 3.4.2（Testcontainers 1.20.6 内置）默认客户端 API version 为 1.24，Docker Desktop 29.4.3 要求最低 1.44，拒绝 `/info` 请求并返回 400。这是已知上游问题（testcontainers-java #11212, #11235），在 Testcontainers 2.0.2 中正式修复（设置默认 API version 为 1.44）。

**修复方式**（最小侵入，不升级 BOM）：新增 `src/test/resources/docker-java.properties`：

```properties
api.version=1.44
```

## 6. Phase 2 修复后验收结果

修复后验收命令（原始路径，无需任何环境变量或外部 PostgreSQL）：

```powershell
cd Demo/p1_polaris_gateway/gateway-service
.\gradlew.bat --no-daemon --no-configuration-cache test --tests "*.store.*"
```

验收结果（2026-05-21 实测）：

```text
BUILD SUCCESSFUL in 35s
tests="9" skipped="0" failures="0" errors="0"
```

Testcontainers 自动启动了 `postgres:16` 容器（动态端口 64091），Flyway migration 成功，全部 9 个测试通过：

- `createBranch_success`
- `createBranch_duplicateName`
- `casUpdate_succeeds`
- `casUpdate_conflictsOnMismatch`
- `listBranches_excludesDeleted`
- `insertAndFindCommit_success`
- `insertAndFindIndex_success`
- `shouldCompact_atTwentyIncrementalIndexes`
- `shouldCompact_whenOpsExceedThreshold`

外部 PostgreSQL 绕过路径（env var `P1_EXTERNAL_POSTGRES_JDBC_URL`）保留在 `RefRepositoryIT.java` 中，作为 CI 环境中无法启动 Docker 时的备用方案。

## 7. 当前风险与待决问题

1. Phase 1 compose 中 Gateway 仍是 placeholder，不是完整可运行 Gateway 容器。这符合 Phase 1 骨架验证，后续 Phase 需要替换为真实服务启动。

2. `application.properties` 中禁用了 datasource health（`quarkus.datasource.health.enabled=false`）、Flyway 自动 migrate（`quarkus.flyway.migrate-at-start=false`），用于 Phase 1 无 DB smoke。**Phase 3 开始时需同时改为 `true`**，使运行时自动连接 DB 并执行 migration。

3. Phase 2 只验证 Repository 和 schema，不代表 Iceberg REST commit/load 语义已完成。Phase 3 尚未开始。

4. Phase 3 需新增 `ContentValueRepository`（或在现有 Repository 中扩展），`BootstrapService` 和 `TableLoadResource` 均依赖 `catalog_content_values` 的读写。

5. 如果允许客户端绕过 Gateway 直接写 Polaris，Version Store 无法感知该提交；该限制来自设计文档，应在后续验收文档中继续明确。

## 8. Phase 3 前置检查清单

进入 Phase 3 前必须完成：

- [x] Phase 1 验收通过（compileJava / 10 张表 / 18 条 OpenAPI / health UP / compose 4 服务启动）
- [x] Phase 2 验收通过（9/9 Testcontainers 测试通过，原始路径，无绕过）
- [ ] `application.properties`：将 `quarkus.datasource.health.enabled` 和 `quarkus.flyway.migrate-at-start` 改为 `true`
- [ ] Phase 3 新增 `ContentValueRepository`（insert + findByRef）
