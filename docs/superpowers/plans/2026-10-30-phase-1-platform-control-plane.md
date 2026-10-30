# 阶段 1：平台控制面最小闭环实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `platform-app` 中交付可运行、可验证的平台控制面，完成“平台登录 -> 创建企业 -> 激活实例 -> 配置模块/套餐 -> 创建销售订单 -> 模拟支付 -> 签发授权 -> 企业心跳 -> 平台审计”的最小闭环。

**Architecture:** 平台控制面采用模块化单体：`platform-core` 保存平台身份、企业、实例、商品、订单、授权和审计实现；`platform-licensing` 只提供 Ed25519 授权文档的签名与验签契约；`platform-app` 暴露 HTTP API。平台控制库使用 Flyway 版本化迁移；平台端永远不读取企业业务库。租户实例使用激活时登记的 Ed25519 公钥对心跳和授权拉取请求签名。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Spring MVC、Spring Security、Spring JDBC (`JdbcClient`)、Flyway、H2（测试）、MySQL 8（生产）、JUnit 5、MockMvc、Jackson、Ed25519。

## Global Constraints

- 平台 API 前缀固定为 `/api/platform/v1`。
- 平台令牌受众固定为 `PLATFORM`；不得接受 `TENANT_STAFF` 或 `TENANT_CUSTOMER` 令牌。
- 平台库表统一使用 `pf_` 前缀。
- 对外主键使用 `public_id`；数据库内部主键使用 `BIGINT`。
- 金额使用 `DECIMAL(12,2)` 和 `BigDecimal`，JSON 中金额输出为字符串。
- 所有时间以 UTC 存储，接口输出带时区的 ISO 8601 时间。
- 所有错误使用 `ApiResponse` 包络和稳定错误码。
- 所有写接口必须返回正确的 HTTP 状态；创建返回 201，状态冲突返回 409，业务校验失败返回 422。
- 订单创建、支付模拟、激活和授权签发必须支持幂等；相同幂等键和相同请求返回首次结果，不同请求体返回 `IDEMPOTENCY_CONFLICT`。
- 许可证必须使用非对称签名；平台私钥不得写入代码仓库。测试使用固定测试密钥，生产通过环境变量提供。
- 模块依赖只认可 `FORMAL` 或 `PLATFORM_GRANT` 授权，试用授权不能作为依赖。
- 到期模块先进入 3 天 `WIND_DOWN`，随后进入 `READ_ONLY`；永久模块不因时间到期，但仍可被平台暂停或撤销。
- 平台库不得出现企业客户、员工、业务订单、支付、健康或小程序 `openid` 数据。
- 现有 33 个后端测试和原 22 个养老核心链路测试必须持续通过。
- 禁止使用 bash；本机命令统一使用 PowerShell。
- Git 分支使用 `codex/` 前缀；本阶段分支为 `codex/phase-1-platform-control-plane`。

---

## 文件结构

```text
backend/
  platform/
    platform-licensing/
      src/main/java/com/smartelder/platform/licensing/
        LicenseDocument.java
        LicenseModule.java
        LicenseSignature.java
        LicenseSigner.java
        LicenseVerifier.java
        LicenseKeyPair.java
      src/test/java/com/smartelder/platform/licensing/
        LicenseSignerTest.java
    platform-core/
      src/main/java/com/smartelder/platform/core/
        PlatformCoreConfiguration.java
        api/                        公共平台服务接口和 DTO
        auth/                       平台账号、角色、JWT
        enterprise/                 企业、激活码、实例
        catalog/                    模块、版本、依赖、价格
        commerce/                   套餐、订单、支付、授权
        common/                     异常、ID、审计、幂等
        internal/                   JDBC 仓储和内部实现
      src/main/resources/db/migration/platform/
        V1__platform_control_plane.sql
        V2__platform_seed_permissions.sql
      src/test/java/com/smartelder/platform/core/
        ...
  apps/platform-app/
    src/main/java/com/smartelder/platform/
      PlatformApplication.java
      PlatformConfiguration.java
      api/auth/
      api/enterprise/
      api/catalog/
      api/commerce/
      api/ops/
    src/main/resources/
      application.yml
      application-dev.yml
    src/test/resources/
      application-test.yml
    src/test/java/com/smartelder/platform/api/
      PlatformControlPlaneIntegrationTest.java
docs/
  platform/接口清单.md
  platform/迁移说明.md
scripts/
  smoke-platform.ps1
```

**职责边界：**

- `platform-licensing` 不依赖数据库、Spring MVC 或业务对象，只负责授权文档模型、规范化签名和验签。
- `platform-core` 的服务只能访问平台控制库 `pf_*` 表。
- `platform-app` 的 Controller 只做 HTTP 映射、参数校验和调用服务，不直接写 SQL。
- 平台账号和租户实例认证使用不同认证路径，不能互相复用令牌。

---

## Task 1：建立平台控制库与认证错误契约

**Files:**
- Create: `backend/platform/platform-core/src/main/resources/db/migration/platform/V1__platform_control_plane.sql`
- Create: `backend/platform/platform-core/src/main/resources/db/migration/platform/V2__platform_seed_permissions.sql`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/common/PlatformApiException.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/common/PublicIds.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/common/PlatformClock.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/common/PlatformAuditService.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/common/IdempotencyService.java`
- Modify: `backend/apps/platform-app/pom.xml`
- Modify: `backend/platform/platform-core/pom.xml`
- Modify: `backend/apps/platform-app/src/main/resources/application.yml`
- Create: `backend/apps/platform-app/src/test/resources/application-test.yml`
- Test: `backend/platform/platform-core/src/test/java/com/smartelder/platform/core/common/PublicIdsTest.java`

**Interfaces:**
- Produces: `public class PlatformApiException extends RuntimeException`, carrying `ErrorCode code`.
- Produces: `PublicIds.newId(String prefix)` returning a prefixed 26-character Crockford Base32 ULID, for example `ent_01J...`.
- Produces: `PlatformClock` with `Instant now()` and `OffsetDateTime utcNow()` using injected `Clock`.
- Produces: `PlatformAuditService.record(Long actorUserId, String actorPublicId, String action, String targetType, String targetPublicId, String detailJson, String traceId)`.
- Produces: `IdempotencyService.execute(String scope, String key, String method, String path, String requestBody, Supplier<T> action, Class<T> resultType)`.
- Consumes: `ApiResponse`, `ErrorCode`, `TraceIdFilter` from `care-common`.

- [ ] **Step 1: Add migration and dependencies**

Add platform-core dependencies on `care-common`, `spring-context`, `spring-jdbc`, `spring-tx`, `spring-security-crypto`, Jackson, Flyway and test dependencies. Add `platform-app` dependencies on `spring-boot-starter-validation`, `spring-boot-starter-security`, Flyway, MySQL runtime and H2 test. Keep `platform-licensing` independent.

`V1__platform_control_plane.sql` creates these tables with primary keys, unique business keys and optimistic `version` columns where mutable:

```sql
pf_admin_user(id BIGINT PK, public_id VARCHAR(40) UNIQUE, username VARCHAR(64) UNIQUE,
  password_hash VARCHAR(100), display_name VARCHAR(100), status VARCHAR(20),
  failed_login_count INT, locked_until TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP, version BIGINT)
pf_admin_role(id BIGINT PK, public_id VARCHAR(40) UNIQUE, code VARCHAR(64) UNIQUE,
  name VARCHAR(100), built_in BOOLEAN, created_at TIMESTAMP, updated_at TIMESTAMP, version BIGINT)
pf_admin_permission(id BIGINT PK, code VARCHAR(100) UNIQUE, name VARCHAR(100), module_code VARCHAR(40))
pf_admin_role_permission(role_id BIGINT, permission_id BIGINT, PRIMARY KEY(role_id, permission_id))
pf_admin_user_role(user_id BIGINT, role_id BIGINT, PRIMARY KEY(user_id, role_id))
pf_enterprise(id BIGINT PK, public_id VARCHAR(40) UNIQUE, org_no VARCHAR(40) UNIQUE,
  name VARCHAR(200), contact_name VARCHAR(100), contact_phone VARCHAR(40), status VARCHAR(20),
  created_at TIMESTAMP, updated_at TIMESTAMP, version BIGINT)
pf_activation_code(id BIGINT PK, public_id VARCHAR(40) UNIQUE, enterprise_id BIGINT,
  code_hash VARCHAR(128) UNIQUE, expires_at TIMESTAMP, used_at TIMESTAMP, used_instance_id BIGINT,
  attempt_count INT, max_attempts INT, created_by BIGINT, created_at TIMESTAMP)
pf_deployment_instance(id BIGINT PK, public_id VARCHAR(40) UNIQUE, enterprise_id BIGINT,
  installation_id VARCHAR(100), tenant_database_id VARCHAR(100), public_key_pem CLOB,
  fingerprint VARCHAR(160), status VARCHAR(20), last_heartbeat_at TIMESTAMP,
  license_version BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP, version BIGINT)
pf_module_definition(id BIGINT PK, code VARCHAR(40) UNIQUE, name VARCHAR(100),
  description VARCHAR(1000), menu_order INT, sale_status VARCHAR(20), trial_enabled BOOLEAN,
  created_at TIMESTAMP, updated_at TIMESTAMP, version BIGINT)
pf_module_version(id BIGINT PK, module_id BIGINT, version VARCHAR(30),
  min_core_version VARCHAR(30), created_at TIMESTAMP, UNIQUE(module_id, version))
pf_module_dependency(id BIGINT PK, module_id BIGINT, dependency_module_id BIGINT,
  dependency_type VARCHAR(20), minimum_version VARCHAR(30), UNIQUE(module_id, dependency_module_id))
pf_module_price(id BIGINT PK, module_id BIGINT, billing_mode VARCHAR(20), duration_days INT,
  amount DECIMAL(12,2), currency VARCHAR(10), store_quota INT, trial_days INT,
  sale_status VARCHAR(20), UNIQUE(module_id, billing_mode, duration_days))
pf_plan(id BIGINT PK, public_id VARCHAR(40) UNIQUE, code VARCHAR(40) UNIQUE, name VARCHAR(100),
  description VARCHAR(1000), amount DECIMAL(12,2), minimum_amount DECIMAL(12,2),
  currency VARCHAR(10), sale_status VARCHAR(20), created_at TIMESTAMP, updated_at TIMESTAMP, version BIGINT)
pf_plan_module(id BIGINT PK, plan_id BIGINT, module_id BIGINT, billing_mode VARCHAR(20),
  duration_days INT, store_quota INT, sort_order INT, UNIQUE(plan_id, module_id))
pf_subscription_order(id BIGINT PK, public_id VARCHAR(40) UNIQUE, order_no VARCHAR(40) UNIQUE,
  enterprise_id BIGINT, plan_id BIGINT, status VARCHAR(30), currency VARCHAR(10),
  original_amount DECIMAL(12,2), deduction_amount DECIMAL(12,2), payable_amount DECIMAL(12,2),
  request_hash VARCHAR(64), created_by BIGINT, paid_at TIMESTAMP, fulfilled_at TIMESTAMP,
  created_at TIMESTAMP, updated_at TIMESTAMP, version BIGINT)
pf_subscription_order_item(id BIGINT PK, order_id BIGINT, module_id BIGINT, module_code VARCHAR(40),
  module_name VARCHAR(100), billing_mode VARCHAR(20), duration_days INT, store_quota INT,
  standard_amount DECIMAL(12,2), deduction_amount DECIMAL(12,2), actual_amount DECIMAL(12,2),
  entitlement_result VARCHAR(30))
pf_payment_transaction(id BIGINT PK, public_id VARCHAR(40) UNIQUE, order_id BIGINT,
  channel VARCHAR(30), channel_transaction_no VARCHAR(100) UNIQUE, amount DECIMAL(12,2),
  status VARCHAR(20), paid_at TIMESTAMP, created_at TIMESTAMP)
pf_refund(id BIGINT PK, public_id VARCHAR(40) UNIQUE, order_id BIGINT,
  amount DECIMAL(12,2), reason VARCHAR(500), status VARCHAR(20), created_at TIMESTAMP)
pf_entitlement(id BIGINT PK, enterprise_id BIGINT, module_id BIGINT, license_type VARCHAR(20),
  status VARCHAR(20), starts_at TIMESTAMP, expires_at TIMESTAMP, perpetual BOOLEAN,
  store_quota INT, source_order_id BIGINT, version BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP,
  UNIQUE(enterprise_id, module_id))
pf_license(id BIGINT PK, public_id VARCHAR(40) UNIQUE, enterprise_id BIGINT, instance_id BIGINT,
  version BIGINT, schema_version INT, payload_json CLOB, signature VARCHAR(200),
  status VARCHAR(20), issued_at TIMESTAMP, acknowledged_at TIMESTAMP, UNIQUE(instance_id, version))
pf_heartbeat(id BIGINT PK, instance_id BIGINT, license_version BIGINT, status VARCHAR(30),
  platform_time TIMESTAMP, client_time TIMESTAMP, remote_address VARCHAR(100), created_at TIMESTAMP)
pf_support_grant(id BIGINT PK, public_id VARCHAR(40) UNIQUE, enterprise_id BIGINT,
  reason VARCHAR(500), scope_json CLOB, expires_at TIMESTAMP, status VARCHAR(20), created_at TIMESTAMP)
pf_outbox_event(id BIGINT PK, public_id VARCHAR(40) UNIQUE, aggregate_type VARCHAR(40),
  aggregate_id VARCHAR(40), event_type VARCHAR(100), payload_json CLOB, status VARCHAR(20),
  created_at TIMESTAMP, published_at TIMESTAMP)
pf_idempotency_record(id BIGINT PK, scope VARCHAR(100), idempotency_key VARCHAR(100),
  method VARCHAR(10), path VARCHAR(300), request_hash VARCHAR(64), status VARCHAR(20),
  response_json CLOB, expires_at TIMESTAMP, created_at TIMESTAMP, UNIQUE(scope, idempotency_key))
pf_audit_log(id BIGINT PK, actor_user_id BIGINT, actor_public_id VARCHAR(40), action VARCHAR(100),
  target_type VARCHAR(50), target_public_id VARCHAR(40), detail_json CLOB, trace_id VARCHAR(64),
  created_at TIMESTAMP)
```

`V2__platform_seed_permissions.sql` inserts these permission codes:

```text
platform.account.manage
platform.role.manage
platform.enterprise.view
platform.enterprise.manage
platform.module.view
platform.module.manage
platform.plan.view
platform.plan.manage
platform.order.view
platform.order.manage
platform.license.view
platform.license.manage
platform.audit.view
```

- [ ] **Step 2: Write failing ID test**

```java
@Test
void generatesPrefixedSortableIdentifier() {
    String first = PublicIds.newId("ent");
    String second = PublicIds.newId("ent");
    assertThat(first).matches("ent_[0-9A-HJKMNP-TV-Z]{26}");
    assertThat(second).matches("ent_[0-9A-HJKMNP-TV-Z]{26}");
    assertThat(first).isNotEqualTo(second);
    assertThat(second.substring(4)).isGreaterThan(first.substring(4));
}
```

- [ ] **Step 3: Run the test and verify failure**

Run: `mvn -f backend/pom.xml -pl platform/platform-core -am test -Dtest=PublicIdsTest`
Expected: compilation/test failure because `PublicIds` does not exist.

- [ ] **Step 4: Implement migration and common primitives**

Implement ULID as 48-bit timestamp in milliseconds plus 80 random bits, encoded with Crockford Base32. Use `SecureRandom`; same-millisecond monotonicity is not required. Implement idempotency with the database table and a JSON snapshot; hash the request body SHA-256 and reject conflicting reuse.

- [ ] **Step 5: Configure test database**

`application-test.yml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:platform_control;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
    username: sa
    password:
    driver-class-name: org.h2.Driver
  flyway:
    enabled: true
    locations: classpath:db/migration/platform
    encoding: UTF-8
server:
  port: 0
```

- [ ] **Step 6: Run tests**

Run: `mvn -f backend/pom.xml -pl platform/platform-core -am test`
Expected: all current tests pass and the new ID test passes.

- [ ] **Step 7: Commit**

```powershell
git add backend/pom.xml backend/platform backend/apps/platform-app
git commit -m "feat(platform): add control-plane schema and primitives"
```

---

## Task 2：平台登录、JWT、角色和权限

**Files:**
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/auth/PlatformPrincipal.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/auth/PlatformAuthService.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/auth/PlatformTokenService.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/auth/PlatformAdminRepository.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/auth/PlatformAuthController.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/PlatformSecurityConfiguration.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/PlatformJwtFilter.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/PlatformBootstrapAdmin.java`
- Test: `backend/platform/platform-core/src/test/java/com/smartelder/platform/core/auth/PlatformAuthServiceTest.java`
- Test: `backend/apps/platform-app/src/test/java/com/smartelder/platform/api/auth/PlatformAuthControllerTest.java`

**Interfaces:**
- Produces: `record PlatformPrincipal(long userId, String publicId, String username, Set<String> roleCodes, Set<String> permissionCodes)`.
- Produces: `PlatformAuthService.login(String username, String password)` returning `LoginResult(String accessToken, OffsetDateTime expiresAt, PlatformPrincipal principal)`.
- Produces: `PlatformTokenService.issue(PlatformPrincipal principal, Instant now)` and `PlatformTokenService.parse(String token)`.
- Produces: `POST /api/platform/v1/auth/login`, body `{"username":"admin","password":"..."}`.
- Produces: `GET /api/platform/v1/auth/me`.
- Consumes: idempotency and audit infrastructure from Task 1.

- [ ] **Step 1: Write failing service and controller tests**

Service tests must prove:

```java
@Test
void rejectsWrongPasswordAndLocksAfterFiveFailures() { ... }

@Test
void loginLoadsRolesAndPermissionsFromDatabase() { ... }

@Test
void tokenAudienceMustBePlatform() { ... }
```

Controller test must expect `data.accessToken`, `data.expiresAt`, and `data.principal.permissionCodes`, then use the token on `/auth/me`.

- [ ] **Step 2: Run tests and verify they fail**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=PlatformAuthControllerTest`
Expected: failure because login endpoint does not exist.

- [ ] **Step 3: Implement authentication**

Use BCrypt through `PasswordEncoder`. Lock an account for 15 minutes after five consecutive failed logins. JWT claims must include `aud=PLATFORM`, `sub`, `roles`, `permissions`, `jti`, `iat`, `exp`. Reject tokens with another audience or a disabled user. Configure the secret with `${PLATFORM_JWT_SECRET}`; use a test-only secret in `application-test.yml`.

- [ ] **Step 4: Implement bootstrap administrator**

On startup, if no platform administrator exists, create username from `${PLATFORM_BOOTSTRAP_ADMIN_USERNAME:admin}` and password from `${PLATFORM_BOOTSTRAP_ADMIN_PASSWORD:ChangeMe-Platform-2026}`. Assign built-in role `PLATFORM_SUPER_ADMIN` and all seeded permissions. Log a warning when the default password is used. Never log the password or token.

- [ ] **Step 5: Secure endpoints**

Permit `/api/platform/v1/health`, `/api/platform/v1/auth/login`, and `/api/platform/v1/instances/activate` without a platform JWT. Require `PLATFORM` bearer tokens for all other `/api/platform/v1/**` endpoints. Return `UNAUTHORIZED` or `TOKEN_INVALID` in the unified envelope instead of Spring Security's default body.

- [ ] **Step 6: Run authentication tests**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=PlatformAuthControllerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/platform/platform-core/src backend/apps/platform-app/src
git commit -m "feat(platform): add platform authentication and RBAC"
```

---

## Task 3：平台账号、角色和权限管理 API

**Files:**
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/auth/PlatformAdminService.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/auth/PlatformAdminController.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/auth/PlatformRoleController.java`
- Test: `backend/apps/platform-app/src/test/java/com/smartelder/platform/api/auth/PlatformAdminControllerTest.java`

**Interfaces:**
- Produces: `GET /api/platform/v1/admins?page=1&pageSize=20`
- Produces: `POST /api/platform/v1/admins` body `{"username","displayName","password","roleCodes":["PLATFORM_OPERATOR"]}`.
- Produces: `PATCH /api/platform/v1/admins/{publicId}/status` body `{"status":"ACTIVE|DISABLED"}`.
- Produces: `GET /api/platform/v1/roles`
- Produces: `POST /api/platform/v1/roles` body `{"code","name","permissionCodes":[]}`.
- Produces: `PUT /api/platform/v1/roles/{publicId}/permissions` body `{"permissionCodes":[]}`.
- Produces: `POST /api/platform/v1/admins/{publicId}/roles` body `{"roleCodes":[]}`.
- Requires: `platform.account.manage` for admin writes, `platform.role.manage` for role writes.

- [ ] **Step 1: Write failing authorization tests**

Test that an operator without `platform.role.manage` receives HTTP 403 with code `PERMISSION_DENIED`, and a super administrator can create a role, assign permissions, create an admin and assign the role.

- [ ] **Step 2: Run tests to verify failure**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=PlatformAdminControllerTest`
Expected: 404 or missing endpoint failure.

- [ ] **Step 3: Implement services and controllers**

Validate username uniqueness, password length 12-72, role codes and permission codes. The built-in `PLATFORM_SUPER_ADMIN` role cannot be deleted or have permissions reduced by API. All writes audit actor, target and changed fields. Role assignment replaces the full role set in one transaction.

- [ ] **Step 4: Run tests**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=PlatformAdminControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/platform/platform-core/src backend/apps/platform-app/src
git commit -m "feat(platform): manage platform admins roles and permissions"
```

---

## Task 4：企业、激活码、实例绑定和实例签名

**Files:**
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/enterprise/EnterpriseService.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/enterprise/ActivationService.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/enterprise/InstanceService.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/enterprise/InstanceRequestVerifier.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/enterprise/EnterpriseController.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/enterprise/ActivationController.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/enterprise/InstanceController.java`
- Test: `backend/apps/platform-app/src/test/java/com/smartelder/platform/api/enterprise/EnterpriseActivationIntegrationTest.java`

**Interfaces:**
- Produces: `POST /api/platform/v1/enterprises` body `{"name","contactName","contactPhone"}` returning `publicId`, generated `orgNo`, `status=ACTIVE`.
- Produces: `GET /api/platform/v1/enterprises?page=1&pageSize=20&status=ACTIVE`.
- Produces: `PATCH /api/platform/v1/enterprises/{publicId}/status` body `{"status":"ACTIVE|SUSPENDED|CLOSED"}`.
- Produces: `POST /api/platform/v1/enterprises/{publicId}/activation-codes` body `{"ttlHours":24,"maxAttempts":5}` returning plaintext code exactly once and `expiresAt`.
- Produces: `POST /api/platform/v1/instances/activate` body `{"activationCode","installationId","tenantDatabaseId","publicKeyPem","fingerprint"}`.
- Produces: `GET /api/platform/v1/instances?enterpriseId=...`.
- Produces: `POST /api/platform/v1/instances/heartbeat`.
- Produces: `GET /api/platform/v1/instances/{publicId}/license`.
- Instance headers: `X-Instance-Id`, `X-Instance-Timestamp`, `X-Instance-Nonce`, `X-Instance-Signature`.

Canonical instance signature input is exactly:

```text
HTTP_METHOD + "\n"
+ REQUEST_PATH + "\n"
+ X-Instance-Timestamp + "\n"
+ X-Instance-Nonce + "\n"
+ lowercase_hex_sha256(raw_request_body)
```

- [ ] **Step 1: Write failing integration test**

The test creates an enterprise, generates an activation code, creates an Ed25519 key pair, activates, signs a heartbeat, receives license version 1, and proves a second activation with the same code returns `409 ACTIVATION_CODE_USED`. It also proves a tampered body/signature returns `403 INSTANCE_SIGNATURE_INVALID`.

- [ ] **Step 2: Run test and verify failure**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=EnterpriseActivationIntegrationTest`
Expected: failure because enterprise endpoints are missing.

- [ ] **Step 3: Implement enterprise lifecycle and activation**

Use a one-way hash for activation codes. The same code has a per-code attempt limit. Change enterprise status according to `ACTIVE -> SUSPENDED -> ACTIVE` and `ACTIVE|SUSPENDED -> CLOSED`; `CLOSED` is terminal. Activation requires an `ACTIVE` enterprise and an unused, unexpired code. Store the public key PEM and fingerprint, return `instancePublicId`, `licenseVersion=1`, and the signed license.

- [ ] **Step 4: Implement instance request verification**

Reject timestamps more than five minutes from platform time. Reject signatures failing Ed25519 verification. Use request body bytes captured by a content-caching filter so GET requests sign SHA-256 of empty bytes. Record every accepted heartbeat in `pf_heartbeat`.

- [ ] **Step 5: Run integration test**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=EnterpriseActivationIntegrationTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/platform backend/apps/platform-app
git commit -m "feat(platform): add enterprise activation and instance identity"
```

---

## Task 5：模块目录、版本、依赖、价格和菜单顺序

**Files:**
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/catalog/ModuleCatalogService.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/catalog/ModuleVersionService.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/catalog/ModuleCatalogController.java`
- Test: `backend/apps/platform-app/src/test/java/com/smartelder/platform/api/catalog/ModuleCatalogIntegrationTest.java`

**Interfaces:**
- Produces: `POST /api/platform/v1/modules` body:

```json
{
  "code": "institution",
  "name": "机构康养",
  "description": "床位、入住、护理和巡房",
  "menuOrder": 100,
  "trialEnabled": true,
  "dependencies": [{"moduleCode":"membership","type":"REQUIRED","minimumVersion":"1.0.0"}],
  "prices": [
    {"billingMode":"PERIOD","durationDays":365,"amount":"1980.00","currency":"CNY","storeQuota":20,"trialDays":7},
    {"billingMode":"PERPETUAL","durationDays":0,"amount":"4980.00","currency":"CNY","storeQuota":20}
  ]
}
```

- Produces: `GET /api/platform/v1/modules?status=ON_SALE` ordered by `menu_order ASC, code ASC`.
- Produces: `GET /api/platform/v1/modules/{code}` including versions, dependencies and prices.
- Produces: `PUT /api/platform/v1/modules/{code}/status` body `{"status":"DRAFT|ON_SALE|OFF_SALE"}`.
- Produces: `POST /api/platform/v1/modules/{code}/versions` body `{"version","minCoreVersion"}`.
- Produces: `PUT /api/platform/v1/modules/{code}/dependencies` body `{"dependencies":[...]}`.
- Produces: `PUT /api/platform/v1/modules/{code}/prices` body `{"prices":[...]}`.

- [ ] **Step 1: Write failing catalog tests**

Prove stable ordering, duplicate code rejection, self-dependency rejection, unknown dependency rejection, dependency-cycle rejection, one price per billing mode/period, and trial price must have `trialDays > 0`.

- [ ] **Step 2: Run tests to verify failure**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=ModuleCatalogIntegrationTest`
Expected: missing endpoint failure.

- [ ] **Step 3: Implement catalog transaction**

Create module, dependencies and prices in one transaction. A module code is immutable. Dependency cycles are rejected with `MODULE_DEPENDENCY_CYCLE`. Menu order must be positive and unique among `ON_SALE` modules; conflicts return `MENU_ORDER_CONFLICT`. Include `moduleCode`, `name`, `version`, `menuOrder`, dependency list and permission codes in the public catalog contract eventually consumed by the tenant frontend.

- [ ] **Step 4: Run tests**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=ModuleCatalogIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/platform/platform-core/src backend/apps/platform-app/src
git commit -m "feat(platform): add module catalog dependencies and pricing"
```

---

## Task 6：套餐、订单报价、依赖校验和幂等

**Files:**
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/commerce/PlanService.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/commerce/OrderQuoteService.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/commerce/SalesOrderService.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/commerce/PlanController.java`
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/commerce/OrderController.java`
- Test: `backend/platform/platform-core/src/test/java/com/smartelder/platform/core/commerce/OrderQuoteServiceTest.java`
- Test: `backend/apps/platform-app/src/test/java/com/smartelder/platform/api/commerce/OrderPurchaseIntegrationTest.java`

**Interfaces:**
- Produces: `POST /api/platform/v1/plans` body `{"code","name","description","amount","minimumAmount","items":[{"moduleCode","billingMode","durationDays","storeQuota"}]}`.
- Produces: `GET /api/platform/v1/plans?enterpriseId=...`, hiding a plan when every included module is permanently owned.
- Produces: `POST /api/platform/v1/orders/quote` body:

```json
{
  "enterpriseId": "ent_...",
  "planCode": "elderly-basic-suite",
  "items": [],
  "period": {"billingMode":"PERIOD","durationDays":365},
  "storeQuota": 20
}
```

Exactly one of `planCode` or non-empty `items` must be supplied.
- Produces: `POST /api/platform/v1/orders` with header `Idempotency-Key`.
- Produces: `POST /api/platform/v1/orders/{publicId}/pay-simulated` body `{"channel":"MOCK","channelTransactionNo":"mock_..."}`.
- Produces: `GET /api/platform/v1/orders/{publicId}`.
- Produces: `POST /api/platform/v1/orders/{publicId}/refund` body `{"reason"}`.

- [ ] **Step 1: Write failing pricing tests**

Use fixed clock `2026-10-30T00:00:00Z`. Test these cases with exact expected values:

```text
A not owned, price 500, one year -> payable 500
A active until 2027-01-01, renew one year -> new expiry 2028-01-01
A perpetual -> standalone purchase rejected with MODULE_ALREADY_PERPETUAL
Plan A+B price 1000; A owned permanently, B not owned -> payable 500; only B is extended
Plan A+B where A and B are both permanent -> plan hidden and quote rejected
Missing required dependency -> 422 MODULE_DEPENDENCY_MISSING
Trial dependency only -> 422 MODULE_DEPENDENCY_MISSING
Same idempotency key + same body -> same order ID, no duplicate order
Same idempotency key + different body -> 409 IDEMPOTENCY_CONFLICT
```

- [ ] **Step 2: Run tests to verify failure**

Run: `mvn -f backend/pom.xml -pl platform/platform-core -am test -Dtest=OrderQuoteServiceTest`
Expected: compilation failure because quote service does not exist.

- [ ] **Step 3: Implement quote and order snapshot**

Calculate in `BigDecimal` with scale 2 and `RoundingMode.UNNECESSARY`. A plan quote deducts each already owned module's standard period price from the plan price, never below `minimumAmount`, and never deducts more than the plan price. Plan entitlement effects include only modules not already permanent. A standalone renewal of an active period entitlement starts from its current `expires_at`, not payment time. Order items store immutable names, prices and quotas.

- [ ] **Step 4: Implement order state machine**

Allowed transitions:

```text
DRAFT -> PENDING_PAYMENT -> PAID -> FULFILLING -> FULFILLED
PENDING_PAYMENT -> PAYMENT_FAILED
PAID|FULFILLING -> MANUAL_REVIEW
PAID|FULFILLED -> REFUND_PENDING -> REFUNDED
PENDING_PAYMENT -> CANCELLED
```

Illegal transitions return `ORDER_STATE_CONFLICT` and must not write partial changes.

- [ ] **Step 5: Run tests**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=OrderPurchaseIntegrationTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/platform/platform-core/src backend/apps/platform-app/src
git commit -m "feat(platform): add plans order pricing and idempotency"
```

---

## Task 7：Ed25519 授权签名、授权生效和永久授权

**Files:**
- Create: `backend/platform/platform-licensing/src/main/java/com/smartelder/platform/licensing/LicenseDocument.java`
- Create: `backend/platform/platform-licensing/src/main/java/com/smartelder/platform/licensing/LicenseModule.java`
- Create: `backend/platform/platform-licensing/src/main/java/com/smartelder/platform/licensing/LicenseSignature.java`
- Create: `backend/platform/platform-licensing/src/main/java/com/smartelder/platform/licensing/LicenseKeyPair.java`
- Create: `backend/platform/platform-licensing/src/main/java/com/smartelder/platform/licensing/LicenseSigner.java`
- Create: `backend/platform/platform-licensing/src/main/java/com/smartelder/platform/licensing/LicenseVerifier.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/commerce/EntitlementService.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/commerce/LicenseIssueService.java`
- Test: `backend/platform/platform-licensing/src/test/java/com/smartelder/platform/licensing/LicenseSignerTest.java`
- Test: `backend/platform/platform-core/src/test/java/com/smartelder/platform/core/commerce/EntitlementServiceTest.java`

**Interfaces:**
- Produces: `LicenseSigner.sign(LicenseDocument document)` returning `LicenseDocument` with `signature`.
- Produces: `LicenseVerifier.verify(LicenseDocument document)` returning boolean.
- Produces: `LicenseKeyPair.generate()`, `fromPrivateKeyPem(String)`, `publicKeyPem()`.
- Produces: `LicenseDocument` fields from design section 8.3: `schemaVersion`, `licenseId`, `orgNo`, `deploymentInstanceId`, `instanceFingerprint`, `issuedAt`, `notBefore`, `expiresAt`, `offlineGraceDays`, `heartbeat`, `modules`, `signature`.
- Produces: `EntitlementService.applyPaidOrder(long orderId)` and `EntitlementService.deriveStatus(Entitlement entitlement, Instant now)`.
- Produces: `LicenseIssueService.issueForInstance(long instanceId, String traceId)` returning `IssuedLicense(long version, String payloadJson, String signature, String licenseId)`.

- [ ] **Step 1: Write failing signer tests**

```java
@Test
void signsAndVerifiesCanonicalPayload() { ... }

@Test
void rejectsAnyPayloadMutation() { ... }

@Test
void rejectsDifferentPublicKey() { ... }
```

- [ ] **Step 2: Run tests to verify failure**

Run: `mvn -f backend/pom.xml -pl platform/platform-licensing -am test -Dtest=LicenseSignerTest`
Expected: compilation failure.

- [ ] **Step 3: Implement canonical signing**

Serialize the payload without `signature.value` using Jackson with sorted properties, stable date formatting and no pretty printing. Sign UTF-8 bytes with Ed25519. Persist algorithm and `keyId`. Verification reconstructs the same canonical bytes and returns false for mutation, wrong key or malformed signature. Do not leak raw cryptography exceptions from controllers.

- [ ] **Step 4: Implement entitlement effects**

Order fulfillment updates each entitlement in one transaction. For a new module, set start to fulfillment time. For an active period entitlement, add purchased days to existing expiry. For perpetual purchase, set `perpetual=true` and `expires_at=null`. A platform grant has `license_type=PLATFORM_GRANT`; a trial has `license_type=TRIAL` and cannot satisfy dependencies. Each change increments entitlement version and issues a new license version.

- [ ] **Step 5: Implement license state derivation**

Derive module license status from the fixed clock:

```text
perpetual -> ACTIVE
now <= expires_at -> ACTIVE
expires_at < now <= expires_at + 3 days -> WIND_DOWN
now > expires_at + 3 days -> READ_ONLY
enterprise/instance/module suspended -> SUSPENDED
```

- [ ] **Step 6: Run tests**

Run: `mvn -f backend/pom.xml -pl platform/platform-licensing,platform/platform-core -am test`
Expected: PASS for all licensing and entitlement tests.

- [ ] **Step 7: Commit**

```powershell
git add backend/platform/platform-licensing backend/platform/platform-core
git commit -m "feat(platform): sign licenses and apply entitlements"
```

---

## Task 8：心跳、宽限期、授权拉取和补偿状态

**Files:**
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/enterprise/HeartbeatService.java`
- Create: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/enterprise/LicenseRefreshService.java`
- Modify: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/enterprise/InstanceController.java`
- Test: `backend/apps/platform-app/src/test/java/com/smartelder/platform/api/enterprise/HeartbeatLicenseIntegrationTest.java`

**Interfaces:**
- Produces: `HeartbeatResponse(platformTime, instanceStatus, licenseVersion, licensePayload, nextHeartbeatAt)`.
- Produces: `POST /api/platform/v1/instances/heartbeat`, returning the current license only when the cached version is behind.
- Produces: `GET /api/platform/v1/instances/{publicId}/license` returning the latest signed license.
- Produces: `POST /api/platform/v1/instances/{publicId}/ack-license` body `{"version":2}` updating `acknowledged_at` idempotently.

- [ ] **Step 1: Write failing heartbeat tests**

Prove a valid heartbeat records `pf_heartbeat`, returns platform time and returns the same license version twice without issuing duplicate licenses. Prove an enterprise suspension marks the instance suspended and returns HTTP 409 `ENTERPRISE_SUSPENDED`; a valid heartbeat after reactivation succeeds. Prove license acknowledgement is idempotent.

- [ ] **Step 2: Run tests to verify failure**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=HeartbeatLicenseIntegrationTest`
Expected: missing endpoint or missing service failure.

- [ ] **Step 3: Implement heartbeat and compensation**

Heartbeat verifies instance signature, updates last heartbeat, computes license state at platform time, records a heartbeat row, and returns the latest license. If the enterprise is suspended/closed or instance revoked, return a stable error and do not issue new rights. Do not delete historical heartbeat or license rows. Write `license.refresh.requested` to outbox when rights change. Expose `POST /api/platform/v1/orders/{publicId}/retry-fulfillment`; it is idempotent and moves to `MANUAL_REVIEW` after five failed attempts.

- [ ] **Step 4: Run tests**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=HeartbeatLicenseIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/platform/platform-core/src backend/apps/platform-app/src
git commit -m "feat(platform): add heartbeat license refresh and compensation"
```

---

## Task 9：审计、统一异常和平台 API 文档

**Files:**
- Create: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/PlatformExceptionHandler.java`
- Create: `docs/platform/接口清单.md`
- Create: `docs/platform/平台控制面错误码.md`
- Create: `docs/platform/迁移说明.md`
- Modify: `backend/libs/care-common/src/main/java/com/smartelder/common/ErrorCode.java`
- Test: `backend/apps/platform-app/src/test/java/com/smartelder/platform/api/PlatformExceptionHandlerTest.java`

**Interfaces:**
- Produces: every error response in the `ApiResponse` envelope with `code`, `message`, `data=null`, `traceId`, `timestamp`.
- Produces: stable codes required by phase 1: `TOKEN_EXPIRED`, `TOKEN_INVALID`, `PERMISSION_DENIED`, `ACTIVATION_CODE_INVALID`, `ACTIVATION_CODE_USED`, `INSTANCE_SIGNATURE_INVALID`, `INSTANCE_NOT_FOUND`, `ENTERPRISE_SUSPENDED`, `MENU_ORDER_CONFLICT`, `MODULE_DEPENDENCY_CYCLE`, `MODULE_ALREADY_PERPETUAL`, `ORDER_STATE_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `LICENSE_SIGNATURE_INVALID`.

- [ ] **Step 1: Write failing exception tests**

Test malformed JSON -> 400 `INVALID_REQUEST`; missing permission -> 403 `PERMISSION_DENIED`; invalid state -> 409; dependency failure -> 422; unknown exception -> 500 `INTERNAL_ERROR` with no stack trace in response. Each response must preserve `X-Trace-Id`.

- [ ] **Step 2: Run tests to verify failure**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=PlatformExceptionHandlerTest`
Expected: failure or incomplete error body.

- [ ] **Step 3: Implement handler and audit coverage**

Audit all successful and failed critical operations: login (without password), enterprise create/status change, activation code creation/redeem, module/price mutation, plan mutation, order create/pay/refund, license issue/refresh, role changes. Include actor, action, target, trace ID, timestamp and non-sensitive detail JSON. Never write passwords, tokens, activation plaintext, private keys or full payment callback payloads.

- [ ] **Step 4: Write API docs**

The API document lists every endpoint from Tasks 2-8 with method, path, required permission, request JSON, success status, error codes and idempotency requirement. The migration document states Flyway locations, backup requirement, forward-only policy and test/apply commands.

- [ ] **Step 5: Run tests**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=PlatformExceptionHandlerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/apps/platform-app/src backend/libs/care-common/src docs/platform
git commit -m "docs(platform): document control-plane APIs and errors"
```

---

## Task 10：端到端验收、冒烟脚本和路线图更新

**Files:**
- Create: `scripts/smoke-platform.ps1`
- Modify: `docs/superpowers/plans/2026-10-30-modular-saas-delivery-roadmap.md`
- Create: `docs/阶段1验收记录.md`
- Modify: `README.md`
- Test: `backend/apps/platform-app/src/test/java/com/smartelder/platform/api/PlatformControlPlaneIntegrationTest.java`

**Interfaces:**
- Produces: script flow `health -> login -> create enterprise -> activation code -> activate -> create module -> create plan -> quote -> order -> simulated payment -> license -> heartbeat -> audit`.
- Verifies: no endpoint returns a 200 envelope for authentication, permission or business-rule failure.

- [ ] **Step 1: Write failing full-flow integration test**

Use MockMvc and H2. Generate an Ed25519 client key pair in the test. Assert final order `FULFILLED`, entitlement `FORMAL`, license version at least 2, heartbeat recorded, and audit rows for payment and license issue.

- [ ] **Step 2: Run test to verify failure**

Run: `mvn -f backend/pom.xml -pl apps/platform-app -am test -Dtest=PlatformControlPlaneIntegrationTest`
Expected: failure until preceding tasks are integrated.

- [ ] **Step 3: Implement smoke script**

PowerShell uses `Invoke-RestMethod`, stops on non-success status, prints each numbered step, and exits non-zero on mismatch. It accepts `-BaseUrl` defaulting to `http://localhost:8081`, `-AdminUsername`, and `-AdminPassword`. It must not print the platform JWT or generated activation code.

- [ ] **Step 4: Run full verification**

Run:

```powershell
.\scripts\verify-all.ps1
mvn -f backend/pom.xml -pl apps/platform-app -am package -DskipTests
```

Start the platform JAR with H2 test profile or configured local MySQL, then run:

```powershell
.\scripts\smoke-platform.ps1 -BaseUrl http://localhost:8081 -AdminUsername admin -AdminPassword ChangeMe-Platform-2026
```

Expected: all checks pass, smoke script prints all steps green, and no secret is printed.

- [ ] **Step 5: Update roadmap and acceptance record**

Mark Phase 1 complete only after the commands actually pass. Record exact test counts, H2/MySQL migration results, JAR startup result, smoke output, known limitations and next phase. Do not write “should pass”.

- [ ] **Step 6: Commit**

```powershell
git add scripts/smoke-platform.ps1 docs README.md
git commit -m "test(platform): verify platform control-plane end to end"
```

---

## 验收矩阵

| 验收项 | 必须验证 |
| --- | --- |
| 平台登录 | 正确密码登录成功，错误密码锁定，跨受众令牌被拒绝 |
| 平台 RBAC | 无权限返回 403，超级管理员可创建角色并授权 |
| 企业生命周期 | 创建企业、生成一次性激活码、激活后码失效 |
| 实例身份 | Ed25519 签名心跳通过，篡改请求失败 |
| 模块目录 | 菜单顺序、版本、依赖、价格和循环依赖校验 |
| 套餐和订单 | 抵扣、依赖、永久、续费叠加、幂等和状态机 |
| 授权 | 签名可验证，篡改失败，实例和指纹绑定 |
| 到期状态 | ACTIVE -> WIND_DOWN -> READ_ONLY，永久不因时间过期 |
| 心跳 | 保存心跳、返回最新授权、确认版本幂等 |
| 审计 | 关键操作都有记录且不含密码、令牌、激活码明文 |
| 架构 | 平台不依赖企业业务实现，平台库不含企业业务数据 |
| 回归 | 全仓测试通过，原 22 个核心链路测试不减少 |

## 执行命令

```powershell
mvn -f backend/pom.xml clean test
.\scripts\verify-all.ps1
mvn -f backend/pom.xml -pl apps/platform-app -am package -DskipTests
java -jar backend/apps/platform-app/target/platform-app-2.0.0-SNAPSHOT.jar
.\scripts\smoke-platform.ps1
```

## 完成定义

1. 所有新增接口有成功、失败、权限和幂等测试。
2. H2 空库可以从 Flyway 迁移完整启动。
3. MySQL 8 空库迁移通过；已有库只追加迁移，不修改 V1/V2。
4. 全仓 Maven 测试通过，原有核心链路测试全部通过。
5. 平台 JAR 实际启动，健康接口和冒烟脚本实际通过。
6. 平台控制库中不存在企业客户、员工、业务订单、支付流水或健康数据。
7. 文档记录实际测试数量、命令、结果和已知限制。
8. 阶段 1 验收记录提交到 Git，但不推送远程仓库，除非用户明确要求。
