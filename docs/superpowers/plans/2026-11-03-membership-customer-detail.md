# 总部客户详情会员摘要实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在总部客户详情中通过外置会员模块显示会员摘要，并保持模块可选、授权受检和门店数据边界不变。

**Architecture:** 在 `care-module-api` 定义总部客户详情扩展提供者接口。企业宿主的独立端点先复用客户管理的数据范围检查，再校验模块运行状态、许可证、有效期限和员工权限，最后聚合已加载模块提供的数据。总部前端独立加载该摘要；门店不调用该端点。

**Tech Stack:** Java 17、Spring Boot、Spring JDBC、JUnit 5、H2、React 18、TypeScript、Vitest、Testing Library、Vite。

## Global Constraints

- 平台系统与企业系统使用独立数据库；本任务只使用隔离测试库，不改预览企业授权数据。
- 企业客户数据始终按 `TenantAccessContext` 的总部/区域/门店数据范围隔离。
- 总部会员摘要要求 `core.customer.view` 与 `membership:member:read`。
- 门店继续通过客户主动出示编号查询最小会员身份，不扩展其客户详情数据。
- 模块不可用时基础客户管理仍正常工作，不能将模块代码或表写入基础客户表。

---

### Task 1: 宿主会员摘要扩展端点

**Files:**
- Create: `backend/libs/care-module-api/src/main/java/com/smartelder/module/api/CustomerManagementFeatureProvider.java`
- Modify: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/portal/CustomerManagementService.java`
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/portal/CustomerManagementFeatureService.java`
- Modify: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/portal/CustomerManagementController.java`
- Test: `backend/apps/tenant-app/src/test/java/com/smartelder/api/tenant/TenantIamIntegrationTest.java`

**Interfaces:**
- `CustomerManagementFeatureProvider.moduleCode(): String`
- `CustomerManagementFeatureProvider.requiredPermission(): String`
- `CustomerManagementFeatureProvider.get(String customerPublicId): Object`
- `CustomerManagementService.requireVisible(TenantAccessContext actor, String publicId): void`
- `CustomerManagementFeatureService.features(TenantAccessContext actor, String customerPublicId): List<Feature>`
- `GET /api/tenant/v1/customers/{id}/module-features` returns the standard API envelope whose data is `{ items: [{ moduleCode, data }] }`.

- [ ] **Step 1: Write failing integration tests** for authorized HQ returns a registered module feature; missing module permission omits that module; out-of-scope customer returns 404 without invoking provider; STORE receives 403.
- [ ] **Step 2: Run only the new tests** with `mvn -f backend/pom.xml -pl apps/tenant-app -am -Dtest=TenantIamIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` and confirm failure is caused by the absent endpoint/behavior.
- [ ] **Step 3: Add the API interface and customer visibility guard**, extracting the existing exact visibility query so detail and extension endpoints share it.
- [ ] **Step 4: Implement provider aggregation** over loaded modules only; require HQ, base customer permission, module entitlement active and in date, enterprise module enabled, active platform license, and each provider's declared permission. Isolate a provider failure from other providers.
- [ ] **Step 5: Add the endpoint and rerun the focused integration test**; confirm the new cases pass and existing customer scope tests remain green.

### Task 2: Register the membership module feature

**Files:**
- Modify: `backend/modules/membership/membership-impl/src/main/java/com/smartelder/modules/membership/MembershipConfiguration.java`
- Modify: `backend/modules/membership/membership-impl/src/test/java/com/smartelder/modules/membership/MembershipServiceTest.java`
- Modify: `backend/modules/membership/membership-impl/pom.xml`

**Interfaces:**
- Membership provider reports module code `membership`, permission `membership:member:read`, and delegates to `MembershipService.card(customerPublicId)`.

- [ ] **Step 1: Add a failing provider contract test** asserting module code, permission, and that `get(customerId)` returns the real service's membership card.
- [ ] **Step 2: Run the focused membership test** using `mvn -f backend/pom.xml -pl modules/membership/membership-impl -am -Dtest=MembershipServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` and verify the expected provider is absent.
- [ ] **Step 3: Register the provider bean** through the new API without changing membership tables or customer storage.
- [ ] **Step 4: Rerun membership module tests and package compilation**.

### Task 3: Display the summary in HQ customer detail

**Files:**
- Modify: `admin-tenant/src/api/customerManagement.ts`
- Modify: `admin-tenant/src/pages/CustomerManagementPage.tsx`
- Modify: `admin-tenant/src/pages/CustomerManagementPage.test.tsx`
- Modify: `admin-tenant/src/pages/customer-management.css`

**Interfaces:**
- `managedCustomers.moduleFeatures(id): Promise<CustomerModuleFeature[]>`
- `CustomerModuleFeature = { moduleCode: string; data: unknown }`
- Membership response fields follow the existing card: `member`, optional `levelName`, `benefits`, and `status`.

- [ ] **Step 1: Write failing HQ UI tests** for showing membership data, hiding the section for an empty module response, and keeping the base detail visible when the extension request fails and can be retried.
- [ ] **Step 2: Run `npm --prefix admin-tenant test -- --run src/pages/CustomerManagementPage.test.tsx`** and confirm the new UI assertions fail for missing extension behavior.
- [ ] **Step 3: Fetch module features separately from customer detail** only when the principal is HQ; render membership status/level/benefits only when module code and member data are present.
- [ ] **Step 4: Add isolated extension loading, error, and retry states** without replacing existing customer detail or bookings.
- [ ] **Step 5: Rerun the focused UI tests and inspect the changed component for HQ/STORE branching**.

### Task 4: Regression and build verification

**Files:**
- No additional source files unless a regression exposes a defect.

- [ ] Run tenant backend tests: `mvn -f backend/pom.xml -pl apps/tenant-app -am test`.
- [ ] Run membership module tests: `mvn -f backend/pom.xml -pl modules/membership/membership-impl -am test`.
- [ ] Run all tenant frontend tests: `npm --prefix admin-tenant test -- --run`.
- [ ] Run tenant frontend production build: `npm --prefix admin-tenant run build`.
- [ ] Inspect `git diff --check` and ensure only task files plus pre-existing workspace changes appear in status; do not stage or commit unrelated work.
- [ ] Verify the running enterprise health and relevant pages without changing preview data; do not start the destructive `dev` profile.

## Self-Review

- Scope covers the extension API, host authorization, the module provider, HQ UI, and their verification.
- No database migration is needed; the feature reads existing module-owned data via the provider.
- Out-of-scope customer checks precede provider invocation, preventing membership probing.
- Existing provider output includes only membership card fields already used by the consumer view.
- Preview authorization restore and platform server startup are separate operational actions, not part of this implementation plan.
