# 阶段 2A：企业组织与权限底座实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在企业自己的 `tenant-app` 和企业业务库中实现总部、门店、员工、企业角色/权限组、数据范围与独立员工认证，同时保持平台权限域和现有 22 项租户回归测试隔离。

**Architecture:** `care-core-api` 定义不依赖 Spring/JDBC 的访问上下文、权限和组织 DTO；`care-core` 实现组织、员工 IAM、数据范围与版本化迁移；`tenant-app` 提供员工认证和总部/门店 REST API。企业部署仍使用自己的业务库；旧 `sys_user` 及旧 `/api/**` 业务接口暂时兼容，新 `/api/tenant/v1/**` 只接受 `TENANT_STAFF` 令牌，并在每次请求时从当前企业库重新解析账号权限。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Spring JDBC/JdbcClient、Spring Security、JWT（jjwt 0.12.6）、Flyway、MySQL 8、H2、JUnit 5、MockMvc、Maven、PowerShell。

## Global Constraints

- 平台 API 前缀为 `/api/platform/v1`，租户 API 前缀为 `/api/tenant/v1`，企业客户 API 前缀为 `/api/client/v1`。
- 平台端不得依赖 `com.smartelder.core`、`com.smartelder.business` 或 `com.smartelder.module`。
- `-api` 模块不得依赖任何 `-impl` 或 `internal` 包。
- 模块之间只能依赖公开 API 包，不得直接依赖其他模块的 Entity、Mapper 或数据库表。
- 所有金额使用 `BigDecimal` 和数据库 `DECIMAL`；所有时间以 UTC 存储。
- 生产环境禁止 DROP 重建表；阶段 2A 新增表必须使用 Flyway 版本化迁移。
- 不使用微服务、运行时插件上传或动态脚本加载。
- 每个代码任务必须先运行失败测试，再写最小实现，最后运行模块测试和全仓回归。
- 企业业务库和平台控制库分离；平台控制面不得读取企业员工、客户或经营数据。
- 现有 22 项 `tenant-app` 核心链路测试必须持续通过。

---

## 文件与职责

```text
backend/libs/care-core-api/src/main/java/com/smartelder/core/api/
  access/       TenantAccessContext、DataScope、StoreScope
  organization/ StoreCommand、StoreView、StaffCommand、StaffView
  iam/          TenantPermission、Role/PermissionGroup 契约
backend/core/care-core/src/main/java/com/smartelder/core/
  organization/ 总部企业资料和门店用例、状态机、JDBC 仓储
  iam/          员工认证、角色、权限组、权限目录和数据范围
  internal/     仅 care-core 内部 JDBC 实现
backend/core/care-core/src/main/resources/db/migration/tenant/
  V1__tenant_organization_iam.sql
  V2__tenant_staff_data_scopes.sql
backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/
  auth/         租户员工登录和初始化
  hq/           总部企业、门店、员工、角色和权限组 API
  store/        门店工作台的门店上下文 API
backend/apps/tenant-app/src/test/java/com/smartelder/api/tenant/
  TenantOrganizationIntegrationTest、TenantIamIntegrationTest
docs/platform/企业租户组织与权限接口.md
docs/阶段2A验收记录.md
```

## Task 1：定义跨模块访问契约与测试迁移基线

**Files:**
- Create: `backend/libs/care-core-api/src/main/java/com/smartelder/core/api/access/DataScope.java`
- Create: `backend/libs/care-core-api/src/main/java/com/smartelder/core/api/access/TenantAccessContext.java`
- Create: `backend/libs/care-core-api/src/main/java/com/smartelder/core/api/iam/TenantPermission.java`
- Create: `backend/libs/care-core-api/src/test/java/com/smartelder/core/api/access/TenantAccessContextTest.java`
- Modify: `backend/libs/care-core-api/pom.xml`

**Interfaces:**
- `DataScope(String type, Set<Long> storeIds)` supports `ALL`, `STORE_GROUP`, `STORES`, `SELF`; `STORE_GROUP` is represented by resolved `storeIds` in the request context.
- `TenantAccessContext(long staffId, String staffPublicId, String staffType, Long homeStoreId, Set<String> roleCodes, Set<String> permissionCodes, DataScope dataScope)` is immutable and contains resolved permissions only.
- `TenantPermission(String code, String moduleCode, String name)` is a registered permission definition; tenants may assign definitions but cannot invent codes.

- [x] **Step 1: Write failing value-object tests**

Test immutable set copies, rejection of unknown data-scope types, STORE scope requiring exactly one `homeStoreId`, and rejection of empty permission codes.

- [x] **Step 2: Run the focused test and confirm the expected failure**

Run: `mvn -f backend/pom.xml -pl libs/care-core-api test '-Dtest=TenantAccessContextTest'`  
Expected: compilation/test failure because the contracts do not exist.

- [x] **Step 3: Implement the immutable public contracts**

Use Java records, defensive `Set.copyOf`, explicit constructors and `IllegalArgumentException` for invalid domain values. Keep this module free of Spring, JDBC, servlet, Entity and Mapper dependencies.

- [x] **Step 4: Run the contract test and architecture test**

Run: `mvn -f backend/pom.xml -pl libs/care-core-api,tests/architecture-tests -am test`  
Expected: all selected tests pass and API packages still do not depend on `internal` implementations.

- [x] **Step 5: Commit the public contracts**

```powershell
git add backend/libs/care-core-api
git commit -m "feat(tenant): define organization and access contracts"
```

## Task 2：新增企业组织与 IAM Flyway 迁移

**Files:**
- Create: `backend/core/care-core/src/main/resources/db/migration/tenant/V1__tenant_organization_iam.sql`
- Create: `backend/core/care-core/src/test/java/com/smartelder/core/organization/TenantMigrationTest.java`
- Modify: `backend/core/care-core/pom.xml`
- Modify: `backend/apps/tenant-app/pom.xml`
- Modify: `backend/apps/tenant-app/src/main/resources/application.yml`
- Modify: `backend/apps/tenant-app/src/test/resources/application-test.yml`

**Interfaces:**
- Migration creates `core_enterprise`, `core_store`, `iam_staff`, `iam_permission_definition`, `iam_permission_group`, `iam_permission_group_item`, `iam_role`, `iam_role_permission_group`, `iam_role_permission`, `iam_staff_role`, `iam_staff_store_scope`, `iam_store_group`, `iam_store_group_member`, and `audit_tenant_log`.
- Every mutable entity has `version BIGINT`; externally used records have unique `public_id`; store and staff status constraints are enforced by service state machines and database unique keys.
- `core_enterprise` is one row per enterprise database; store rows are owned by that local enterprise and use `DRAFT|ACTIVE|SUSPENDED|CLOSED`.
- Seed only registered free-core permission definitions (`core.enterprise.view/manage`, `core.store.view/manage`, `iam.staff.view/manage`, `iam.role.view/manage`, `iam.permission-group.view/manage`).

- [x] **Step 1: Write a failing migration test on an empty H2 database**

Assert Flyway applies tenant migration v1 and required `core_*`, `iam_*`, and `audit_tenant_log` tables exist. Also assert permission seed values are unique.

- [x] **Step 2: Run the focused migration test and confirm it fails**

Run: `mvn -f backend/pom.xml -pl core/care-core -am test '-Dtest=TenantMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'`  
Expected: migration test fails because tenant migrations and Flyway wiring are absent.

- [x] **Step 3: Add Flyway dependencies and append-only migration**

Add `spring-jdbc` and Flyway to `care-core`; add Flyway to `tenant-app`; configure location `classpath:db/migration/tenant`. Use MySQL 8/H2 MySQL-mode syntax (`BIGINT AUTO_INCREMENT`, `TIMESTAMP(6)`, `BOOLEAN`), no `DROP`, and unique/index keys for staff username, optional phone, store number and public IDs.

- [x] **Step 4: Run migration test against H2 and an ephemeral MySQL schema**

Run the H2 test above. For MySQL, use a unique temporary schema and run the same `TenantMigrationTest` with an environment-selected JDBC URL; verify `flyway_schema_history` reaches v1 and all expected tables exist.

- [x] **Step 5: Commit migration and wiring**

```powershell
git add backend/core/care-core backend/apps/tenant-app
git commit -m "feat(tenant): add organization and iam schema"
```

## Task 3：实现总部企业资料、门店创建和生命周期

**Files:**
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/organization/TenantOrganizationService.java`
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/organization/internal/JdbcTenantOrganizationRepository.java`
- Create: `backend/core/care-core/src/test/java/com/smartelder/core/organization/TenantOrganizationServiceTest.java`
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/hq/HqOrganizationController.java`
- Create: `backend/apps/tenant-app/src/test/java/com/smartelder/api/tenant/TenantOrganizationIntegrationTest.java`

**Interfaces:**
- `TenantOrganizationService.getEnterprise()` returns local enterprise profile.
- `TenantOrganizationService.updateEnterprise(UpdateEnterpriseCommand, TenantAccessContext, String traceId)` requires `core.enterprise.manage`.
- `TenantOrganizationService.createStore(CreateStoreCommand, TenantAccessContext, String traceId)` requires `core.store.manage`, assigns a server-generated `storePublicId` and unique `storeNo`, initial state `DRAFT`, returns `StoreView`.
- `TenantOrganizationService.changeStoreStatus(String storePublicId, String targetStatus, TenantAccessContext, String traceId)` permits only `DRAFT->ACTIVE`, `ACTIVE->SUSPENDED|CLOSED`, `SUSPENDED->ACTIVE|CLOSED`; `CLOSED` is terminal.
- HTTP: `GET/PUT /api/tenant/v1/hq/enterprise`, `GET/POST /api/tenant/v1/hq/stores`, `PATCH /api/tenant/v1/hq/stores/{publicId}/status`.

- [x] **Step 1: Write failing unit tests for scope, validation and transitions**

Cover missing permission, duplicate store number, invalid status edge, terminal CLOSED, and a suspended store returned as readable but not operational.

- [x] **Step 2: Run the focused test and confirm the expected failure**

Run: `mvn -f backend/pom.xml -pl core/care-core -am test '-Dtest=TenantOrganizationServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'`.

- [x] **Step 3: Implement JDBC repository and transactional service**

Use parameterized `JdbcClient`, compare-and-set `version` updates, and record the audit row in the same transaction. Do not accept enterprise ID or assigned store ID as authority from the request body.

- [x] **Step 4: Write and run MockMvc integration tests**

Prove HQ staff with `core.store.manage` can create/activate a store, staff without it receives 403, and invalid state returns 409 envelope with traceId.

- [x] **Step 5: Commit the organization vertical slice**

```powershell
git add backend/core/care-core backend/apps/tenant-app
git commit -m "feat(tenant): add hq enterprise and store management"
```

## Task 4：建立员工认证、受众隔离与首次总部管理员

**Files:**
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/iam/TenantStaffAuthService.java`
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/iam/TenantStaffTokenService.java`
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/auth/TenantStaffPrincipal.java`
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/auth/TenantStaffJwtFilter.java`
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/auth/TenantStaffAuthController.java`
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/auth/TenantBootstrapRunner.java`
- Create: `backend/apps/tenant-app/src/test/java/com/smartelder/api/tenant/TenantStaffAuthIntegrationTest.java`
- Modify: `backend/apps/tenant-app/src/main/java/com/smartelder/security/SecurityConfig.java`
- Modify: `backend/apps/tenant-app/src/main/resources/application.yml`

**Interfaces:**
- `TenantStaffTokenService.issue(long staffId, Instant issuedAt)` issues JWT with `aud=TENANT_STAFF`, subject staff public ID, configured staff-only key and TTL.
- `TenantStaffTokenService.parse(String token)` rejects wrong audience, signature, expiry and malformed subject.
- `TenantStaffAuthService.login(String account, String password)` loads active `iam_staff`, checks BCrypt, returns token and current `TenantAccessContext`; unknown account and wrong password use the same 401 response.
- `/api/tenant/v1/auth/login` is public; all other `/api/tenant/v1/**` require `TENANT_STAFF` authentication. Existing `/api/auth/**` behavior remains intact.
- Bootstrap runs only when explicitly enabled and all required `TENANT_BOOTSTRAP_*` settings are present; it atomically creates the local enterprise row, base permissions, protected HQ owner role and first HQ owner. Existing installation is never overwritten.

- [x] **Step 1: Write failing token audience and login integration tests**

Cover valid staff token, platform/legacy token rejection on new APIs, disabled account denial after token issuance, wrong password, incomplete bootstrap configuration, and bootstrap idempotency.

- [x] **Step 2: Run tests and confirm failure before implementation**

Run: `mvn -f backend/pom.xml -pl apps/tenant-app -am test '-Dtest=TenantStaffAuthIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'`.

- [x] **Step 3: Implement dedicated staff token and database-backed identity resolution**

The token carries identity only; each HQ API resolves current status, roles, permission codes and data scope from the tenant database. Do not trust role, permissions, org ID or store IDs encoded by older clients.

- [x] **Step 4: Implement guarded one-time bootstrap and route-specific security**

Register the staff filter after the legacy JWT filter so a legacy token cannot masquerade as staff; expose the initial owner only through environment-gated bootstrap, never a public unauthenticated setup endpoint.

- [x] **Step 5: Run new auth tests plus all 22 legacy core-flow tests**

Run: `mvn -f backend/pom.xml -pl apps/tenant-app -am test`.

- [x] **Step 6: Commit staff authentication**

```powershell
git add backend/core/care-core backend/apps/tenant-app
git commit -m "feat(tenant): add isolated staff authentication"
```

## Task 5：实现权限目录、权限组、角色与员工分配

**Files:**
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/iam/TenantIamService.java`
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/iam/TenantAccessResolver.java`
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/iam/internal/JdbcTenantIamRepository.java`
- Create: `backend/core/care-core/src/test/java/com/smartelder/core/iam/TenantIamServiceTest.java`
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/hq/HqIamController.java`
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/store/StoreStaffController.java`
- Create: `backend/apps/tenant-app/src/test/java/com/smartelder/api/tenant/TenantIamIntegrationTest.java`

**Interfaces:**
- `TenantIamService.createPermissionGroup(CreatePermissionGroupCommand, TenantAccessContext, String)` rejects unknown permission codes and requires `iam.permission-group.manage`.
- `TenantIamService.createRole(CreateRoleCommand, TenantAccessContext, String)` stores role domain `HQ|STORE`, permission-group bindings and individual `ALLOW|DENY` overrides; protected bootstrap-owner role cannot be weakened or deleted.
- `TenantIamService.createStaff(CreateStaffCommand, TenantAccessContext, String)` enforces HQ staff have no home store, STORE staff have exactly one active home store, and only HQ principals with `iam.staff.manage` may create or assign roles.
- `TenantAccessResolver.resolve(long staffId)` returns active roles, current permission set, and data scope; inactive users or invalid scope bindings fail closed.
- HTTP: role and permission group CRUD under `/api/tenant/v1/hq/roles` and `/permission-groups`; staff CRUD and role assignment under `/api/tenant/v1/hq/staff`; current store staff APIs under `/api/tenant/v1/store/staff`.

- [x] **Step 1: Write failing tests for permission algebra and role-domain rules**

Test permission union, DENY overriding ALLOW, unknown permission rejection, STORE/HQ staff-role mismatch, protected owner role invariants, and immediate permission removal after DB change.

- [x] **Step 2: Run focused IAM tests and confirm expected failure**

Run: `mvn -f backend/pom.xml -pl core/care-core -am test '-Dtest=TenantIamServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'`.

- [x] **Step 3: Implement permission registry resolution and IAM service**

Use transactions for group membership replacement, role permission replacement, staff role assignment and audit records. Keep permission definitions code-owned/seeded; tenant writes can only reference those definitions.

- [x] **Step 4: Add HQ and store employee controllers**

Use `@PreAuthorize`/method authorization for static registered permissions, then re-resolve `TenantAccessContext` in the service. Never implement permission checking only in the UI.

- [x] **Step 5: Run IAM integration and auth regression tests**

Run: `mvn -f backend/pom.xml -pl apps/tenant-app -am test '-Dtest=TenantIamIntegrationTest,TenantStaffAuthIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'`.

- [x] **Step 6: Commit enterprise IAM**

```powershell
git add backend/core/care-core backend/apps/tenant-app
git commit -m "feat(tenant): add enterprise roles and permission groups"
```

## Task 6：实现门店分组与服务端数据范围裁剪

**Files:**
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/iam/TenantDataScopeService.java`
- Create: `backend/core/care-core/src/test/java/com/smartelder/core/iam/TenantDataScopeServiceTest.java`
- Modify: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/hq/HqOrganizationController.java`
- Modify: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/store/StoreStaffController.java`
- Modify: `backend/apps/tenant-app/src/test/java/com/smartelder/api/tenant/TenantIamIntegrationTest.java`

**Interfaces:**
- `TenantDataScopeService.resolve(TenantStaffPrincipal)` returns `TenantAccessContext` with scope type and resolved active store IDs.
- `TenantDataScopeService.requireStoreAccess(TenantAccessContext, long storeId)` throws `DATA_SCOPE_DENIED` when outside resolved scope and `STORE_INACTIVE` for write operations to suspended/closed stores.
- `ALL` resolves every tenant store; `STORES` resolves only explicit `iam_staff_store_scope` rows; `STORE_GROUP` resolves the current membership of selected groups; `SELF` has no cross-store IDs; STORE staff always resolve to their single home store regardless of role grants.
- Store group CRUD is HQ-only and cannot contain stores outside the tenant database.

- [x] **Step 1: Write failing scope-resolution tests**

Cover ALL, STORES, STORE_GROUP membership changes taking effect immediately, SELF, fixed STORE scope, forged store ID denial, and closed-store read versus write behavior.

- [x] **Step 2: Run the focused scope test and confirm failure**

Run: `mvn -f backend/pom.xml -pl core/care-core -am test '-Dtest=TenantDataScopeServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'`.

- [x] **Step 3: Implement resolver and guarded store group service**

Resolve IDs in SQL from tenant-owned tables, return immutable sorted IDs, and require the service to pass resolved IDs into each query rather than concatenate request parameters into SQL.

- [x] **Step 4: Add negative MockMvc tests for cross-store tampering**

Sign in as an HQ employee restricted to store A and a STORE employee at store A; submit store B IDs to list/detail/status operations and assert 403 `DATA_SCOPE_DENIED` or `STORE_ACCESS_DENIED` with no leaked row.

- [x] **Step 5: Run all tenant tests**

Run: `mvn -f backend/pom.xml -pl apps/tenant-app -am test`.

- [x] **Step 6: Commit data-scope enforcement**

```powershell
git add backend/core/care-core backend/apps/tenant-app
git commit -m "feat(tenant): enforce headquarters and store data scopes"
```

## Task 7：审计、错误响应、接口文档与本地验收

**Files:**
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/TenantApiExceptionHandler.java`
- Create: `docs/platform/企业租户组织与权限接口.md`
- Create: `docs/阶段2A验收记录.md`
- Modify: `backend/libs/care-common/src/main/java/com/smartelder/common/ErrorCode.java`
- Modify: `backend/apps/tenant-app/src/main/java/com/smartelder/security/SecurityConfig.java`
- Modify: `docs/superpowers/plans/2026-10-30-modular-saas-delivery-roadmap.md`
- Modify: `README.md`

**Interfaces:**
- New `/api/tenant/v1/**` failures use `ApiResponse` with stable code, null data, traceId and UTC timestamp; legacy APIs keep their existing `Result` contract.
- Audit actions include `ENTERPRISE_PROFILE_UPDATED`, `STORE_CREATED`, `STORE_STATUS_CHANGED`, `STAFF_CREATED`, `STAFF_DISABLED`, `ROLE_CREATED`, `ROLE_PERMISSIONS_REPLACED`, `PERMISSION_GROUP_CREATED`, `PERMISSION_GROUP_PERMISSIONS_REPLACED`, `STAFF_ROLES_REPLACED`, and `STAFF_SCOPE_REPLACED`.
- API document lists every new endpoint, required permission, request/response DTO, success status and relevant 401/403/409/422 errors.

- [x] **Step 1: Write failing response and audit assertions**

Verify malformed JSON, validation failures, permission denial, data-scope denial and unknown exception all use the v1 response envelope; assert trace ID preservation and that audit JSON never contains password or token fields.

- [x] **Step 2: Run the focused tests and verify the missing contract**

Run: `mvn -f backend/pom.xml -pl apps/tenant-app -am test '-Dtest=TenantApiExceptionHandlerTest,TenantIamIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'`.

- [x] **Step 3: Implement exception mapping and audit coverage**

Handle known tenant exceptions without exposing SQL/internal details; map unknown errors to `INTERNAL_ERROR`. Persist audit rows in the same transaction as the state change.

- [x] **Step 4: Document the employee login/bootstrap contract and every v1 endpoint**

Include environment variables for the one-time HQ owner bootstrap, Flyway location, migration command, scope semantics, state transitions and compatibility boundary with `/api/auth/**`.

- [x] **Step 5: Run full verification and migration tests**

Run: `mvn -f backend/pom.xml clean test` and `scripts/verify-all.ps1`; run MySQL 8 migration test against an isolated temporary schema and remove only that explicitly named schema after verification.

- [x] **Step 6: Write acceptance record and update roadmap**

Record actual test counts, H2/MySQL migration output, bootstrap configuration behavior, API smoke result, and known limits. Mark Phase 2 as in progress and 2A as complete only when all checks pass.

- [x] **Step 7: Commit the 2A delivery**

```powershell
git add backend docs README.md
git commit -m "feat(tenant): deliver organization and iam foundation"
```

---

## Completion Gate

1. New migration runs on empty H2 and MySQL 8 and is append-only.
2. HQ owner bootstrap is explicit, idempotent, and never logs its credential.
3. Staff roles and permission groups are editable only inside the enterprise tenant.
4. All tested role, store and data-scope attacks fail closed.
5. Tenant staff tokens are audience-separated from platform and legacy tokens.
6. New API error responses and tenant audit records meet the written contract.
7. Existing 22 core-flow tests, all new tests, architecture tests and frontend build pass.
8. Changes are committed locally on the phase 2A branch and are not pushed.
