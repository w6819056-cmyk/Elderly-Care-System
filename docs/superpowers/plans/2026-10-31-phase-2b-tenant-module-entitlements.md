# Phase 2B Tenant Module Entitlements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver verified tenant-side license synchronization, headquarters module activation, per-module store assignments, and one server-side module access policy shared by menu and API decisions.

**Architecture:** Keep platform licensing authoritative for enterprise entitlements and total store quotas. Tenant `care-core` stores an immutable verified license snapshot plus local activation and assignment state; `tenant-app` performs signed synchronization and exposes HQ APIs; module descriptors and a tenant guard decide access from the same database-backed policy. The platform receives only instance/license protocol data, never tenant business or store assignment data.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring JDBC, Flyway, MySQL 8, H2 integration tests, React 18 + TypeScript + Vite.

## Global Constraints

- Each enterprise keeps its own tenant database; platform control data and tenant business data remain separate.
- Platform entitlements are signed with Ed25519 and bound to `orgNo`, deployment instance ID, and instance fingerprint.
- Tenant-to-platform communication is outbound HTTPS using the existing instance signature headers and nonce protection.
- Heartbeat interval is 6 hours; the signed offline grace is 7 days; module expiry has a 3-day wind-down period.
- Module store quotas are independent; store suspension/closure never releases an assignment; only explicit HQ revocation releases it.
- Trial entitlements cannot satisfy formal module dependencies.
- All new tenant schema is appended as Flyway migration V3 or the next unused version; never rewrite V1/V2 or add license tables to DROP-based demo initialization.
- Menus are presentation only; authorization is enforced in application services using the same policy query used to produce menu availability.
- Existing platform, tenant, architecture, and licensing tests must continue passing.

---

## File Map

| Area | Planned files | Responsibility |
| --- | --- | --- |
| License protocol | `backend/platform/platform-licensing/src/main/java/com/smartelder/platform/licensing/LicenseDocument.java`, `LicenseModule.java`, plus licensing DTO tests | Add backward-compatible monotonic `version` and stable identity/hash contract to signed documents; validate protocol fields. |
| Platform ACK/heartbeat | `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/commerce/LicenseIssueService.java`, `LicenseRefreshService.java`, platform migration if needed, `backend/apps/platform-app/.../InstanceController.java` | Return the current versioned document and platform time; ACK by version and SHA-256 digest idempotently. |
| Module API | `backend/libs/care-module-api/src/main/java/com/smartelder/module/api/ModuleDescriptor.java`, new operation/menu metadata types, and `.../ModuleDescriptorTest.java` | Declare module permissions, dependencies, menu metadata, resource-operation mapping, and wind-down commands. |
| Tenant schema | `backend/core/care-core/src/main/resources/db/migration/tenant/V3__tenant_module_entitlements.sql` | Store verified snapshot, entitlement projection, HQ activation, assignments, and sync audit data. Confirm next version before creating. |
| Tenant entitlement core | New `backend/core/care-core/src/main/java/com/smartelder/core/licensing/**` | License snapshot interfaces, validation state, time policy, HQ activation/assignment services, access query/guard, JDBC repositories, and focused unit/repository tests. |
| Tenant sync API/runtime | New `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/licensing/**` | Platform HTTP client, scheduled sync coordinator, instance request signing, activation bootstrap integration, HQ controllers, exception mapping and configuration. |
| Tenant UI | New `admin/src/pages/tenant/Modules.tsx`, API types/client additions, and route/menu wiring in `admin/src/App.tsx` and `admin/src/components/Layout.tsx` | HQ module list, license state, quota use, activation and store assignment/revocation controls. |
| Integration tests | New tenant module entitlement and controller tests under `backend/apps/tenant-app/src/test/java/com/smartelder/api/tenant/modules/**`; platform tests under existing enterprise package | Verify signed sync, rollback/digest rules, API permissions, quota concurrency, offline/expiry states and regression. |

The legacy `admin` application currently serves the original single-tenant workflow. Before adding the HQ modules route, keep the new page behind the `TENANT_STAFF` login/API contract and do not reuse legacy customer/admin tokens. If the current shell cannot isolate those sessions, add an explicit tenant route shell in `admin/src/tenant/**` rather than mixing credentials.

## Task 1: Extend the Signed License Protocol

**Files:**
- Modify: `backend/platform/platform-licensing/src/main/java/com/smartelder/platform/licensing/LicenseDocument.java`
- Modify: `backend/platform/platform-licensing/src/main/java/com/smartelder/platform/licensing/LicenseCanonicalizer.java`
- Modify: `backend/platform/platform-licensing/src/main/java/com/smartelder/platform/licensing/LicenseSigner.java` and verifier implementations only if API changes are needed
- Test: `backend/platform/platform-licensing/src/test/java/com/smartelder/platform/licensing/LicenseSignerTest.java`
- Test: platform license issuance and heartbeat integration tests under `backend/apps/platform-app/src/test/java/com/smartelder/platform/api/enterprise/`

**Interfaces:**
- Preserve existing fields and constructors where practical; introduce a signed `long version` field sourced from the platform instance license version.
- Add a canonical serialized payload method that excludes only `signature.value`, preserving `signature.algorithm` and `keyId` as today.
- Define the digest as lowercase hexadecimal SHA-256 of the exact canonical UTF-8 payload that is signed, and use this same definition in both platform and tenant code.
- Reject negative versions, invalid module quotas/statuses, duplicate module codes, and malformed time ranges before signing or accepting.

- [ ] **Step 1: Add failing protocol tests** proving the version is signed, mutation of version/module/quota invalidates verification, duplicate module codes are rejected, and the canonical digest is stable regardless of JSON property order.
- [ ] **Step 2: Run the focused test** with `mvn -pl platform/platform-licensing -am -Dtest=LicenseSignerTest -Dsurefire.failIfNoSpecifiedTests=false test`; verify the new assertions fail for the missing contract.
- [ ] **Step 3: Implement version and digest support** in the license model/canonicalizer and feed the monotonically increasing instance license version from the platform issuer.
- [ ] **Step 4: Verify platform output** by running `mvn -pl apps/platform-app -am -Dtest=EnterpriseActivationIntegrationTest,HeartbeatLicenseIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`; assert heartbeat and direct pull return the same version and digest.
- [ ] **Step 5: Commit** as `feat(licensing): version signed license snapshots`.

## Task 2: Make Platform License ACK Digest-Aware

**Files:**
- Modify: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/commerce/LicenseRefreshService.java`
- Modify: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/enterprise/InstanceController.java`
- Modify: platform schema only if ACK persistence does not already retain the payload digest
- Test: `backend/apps/platform-app/src/test/java/com/smartelder/platform/api/enterprise/HeartbeatLicenseIntegrationTest.java`

**Interfaces:**
- Extend `LicenseAcknowledgementRequest` with `version` and `sha256`; accept omitted `sha256` only during a compatibility window for already deployed clients, while all new clients send it.
- ACK validates that the version belongs to the authenticated instance and digest equals that exact issued document; repeated identical ACK returns the same success result.
- Heartbeat and `GET /api/platform/v1/instances/{publicId}/license` expose a stable version/digest pair without changing the existing instance authentication headers.

- [ ] **Step 1: Add failing integration cases** for correct digest, repeated ACK, wrong digest, future version, and ACK for another instance.
- [ ] **Step 2: Run `mvn -pl apps/platform-app -am -Dtest=HeartbeatLicenseIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`** and verify the new assertions fail.
- [ ] **Step 3: Implement validation and compatibility behavior** in platform service/controller; never mark an unissued or mismatched license acknowledged.
- [ ] **Step 4: Rerun platform heartbeat and activation integration tests** and confirm old tests that ACK by version still pass during compatibility.
- [ ] **Step 5: Commit** as `feat(platform): acknowledge license digest`.

## Task 3: Add Tenant Entitlement Schema and Domain Contracts

**Files:**
- Create: next unused tenant migration, expected `backend/core/care-core/src/main/resources/db/migration/tenant/V3__tenant_module_entitlements.sql`
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/licensing/TenantLicenseService.java`
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/licensing/TenantLicenseRepository.java`
- Create: license state/policy types in `backend/core/care-core/src/main/java/com/smartelder/core/licensing/`
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/licensing/internal/JdbcTenantLicenseRepository.java`
- Test: new `TenantLicenseMigrationTest`, repository test and state policy unit test in the corresponding `care-core/src/test/java/com/smartelder/core/licensing/` package

**Interfaces:**
- `TenantLicenseService.acceptVerifiedSnapshot(VerifiedLicense snapshot, Instant platformTime, String traceId)` atomically stores raw canonical payload, digest, version, sync anchor and entitlement projection; repository transaction completes before API code sends ACK.
- `TenantLicenseService.currentStatus(Instant now)` returns instance binding, version, digest, last successful sync, offline mode and module entitlements without exposing the raw license/private material to HQ DTOs.
- `ModuleEntitlement` contains `moduleCode`, `licenseType`, `licenseStatus`, `startsAt`, nullable `expiresAt`, `perpetual`, `storeQuota`, and `licenseVersion`.
- Assignment records have a stable public ID, module code, store ID, assignment/revocation actor and timestamps, and optional idempotency key; one row per module/store pair is retained through revocation.

- [ ] **Step 1: Write failing migration and repository tests** for empty database migration, snapshot atomicity, idempotent same digest/version, rejection of lower version, uniqueness of module/store assignment, and retained revoked history.
- [ ] **Step 2: Run `mvn -pl core/care-core -am -Dtest=TenantLicenseMigrationTest,TenantLicenseRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`** and confirm failures are caused by the absent schema/repository behavior.
- [ ] **Step 3: Add the append-only Flyway migration** for `lic_license_snapshot`, `lic_module_entitlement`, `core_enterprise_module`, `core_store_module_assignment`, and bounded `lic_sync_attempt` audit records. Use uniqueness and checks to enforce the invariants the database can enforce.
- [ ] **Step 4: Implement repository transactions** for accepting a strictly newer verified snapshot and projecting all signed modules together; same version/same digest is idempotent; same version/different digest and lower versions fail without modifying prior data.
- [ ] **Step 5: Run focused care-core tests plus `TenantMigrationTest`** and commit as `feat(tenant): persist verified module entitlements`.

## Task 4: Implement Tenant Signature Verification and Sync Coordinator

**Files:**
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/licensing/TenantLicenseVerifier.java`
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/licensing/PlatformLicenseClient.java`
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/licensing/InstanceRequestSigner.java`
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/licensing/LicenseSyncCoordinator.java`
- Modify: `backend/apps/tenant-app/src/main/java/com/smartelder/ElderlyCareApplication.java` or a dedicated configuration class for scheduling and beans
- Modify: `backend/apps/tenant-app/src/main/resources/application.yml` for platform URL, instance identity, key paths/public key IDs, connect/read timeout and heartbeat settings
- Test: verifier, request signing and coordinator tests in `backend/apps/tenant-app/src/test/java/com/smartelder/api/tenant/licensing/`

**Interfaces:**
- `TenantLicenseVerifier.verify(String rawJson, TrustedKeySet keys, ExpectedInstance expected)` returns `VerifiedLicense(document, canonicalPayload, sha256)` or a stable typed failure; it checks Ed25519, `keyId`, `orgNo`, instance ID/fingerprint, schema, timestamps, unique modules and quota bounds.
- `PlatformLicenseClient.heartbeat(long version, Instant clientTime, String digest)` and `fetchLicense()` use the existing instance signed request protocol. No inbound platform callback is added.
- `LicenseSyncCoordinator.syncNow()` verifies first, commits the snapshot second, ACKs last; on network failure it retains the previous snapshot and schedules capped exponential backoff with jitter.
- A successful platform response stores platform time as the time anchor. A signed enterprise/instance suspension response immediately updates control status; timeout and 5xx never do.

- [ ] **Step 1: Write failing tests** for valid/mutated signatures, unknown key ID, instance/org mismatch, duplicate modules, expiry/not-before validation, same-version replay, rollback, same-version digest conflict, ACK only after commit, and timeout preserving the previous snapshot.
- [ ] **Step 2: Run focused verifier/coordinator tests** and confirm expected failures before implementation.
- [ ] **Step 3: Implement pure verification first** using the existing `LicenseVerifier` and canonicalizer contract; configure public keys as deployment-provided trust material and never accept a key delivered only in an unverified response.
- [ ] **Step 4: Implement signed client and synchronization ordering** with injected `Clock`, bounded HTTP timeouts, no secret logging, and ACK version+digest after repository commit.
- [ ] **Step 5: Add scheduled 6-hour sync plus immediate startup/reconnect sync** with bounded exponential backoff; persist the platform time anchor and detect suspicious wall-clock rollback after restart.
- [ ] **Step 6: Run verifier/coordinator tests and tenant context load test**; commit as `feat(tenant): synchronize signed platform licenses`.

## Task 5: Add HQ Activation, Quota Assignment, and Audit APIs

**Files:**
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/licensing/TenantModuleService.java`
- Modify: `backend/core/care-core/src/main/java/com/smartelder/core/licensing/TenantLicenseRepository.java` and JDBC implementation
- Create: `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/hq/HqModuleController.java`
- Modify: `backend/apps/tenant-app/src/main/java/com/smartelder/common/ErrorCode.java` and exception mapping
- Test: `backend/core/care-core/src/test/java/com/smartelder/core/licensing/TenantModuleServiceTest.java`
- Test: `backend/apps/tenant-app/src/test/java/com/smartelder/api/tenant/modules/HqModuleControllerIntegrationTest.java`

**Interfaces:**
- `TenantModuleService.listModules(TenantAccessContext)` returns local registered module descriptors joined with verified entitlements, activation, quota usage, dependency status and calculated state.
- `setActivation(String moduleCode, boolean enabled, TenantAccessContext actor, String traceId)` is idempotent and requires a valid formal or trial entitlement; formal dependencies require valid formal entitlement.
- `assignStore(String moduleCode, String storePublicId, String idempotencyKey, TenantAccessContext actor, String traceId)` verifies HQ permission, module enabled, entitlement status, dependency status, active store and remaining quota in one transaction.
- `revokeAssignment(String moduleCode, String storePublicId, TenantAccessContext actor, String traceId)` is idempotent, retains a revoked row, and frees one quota slot.
- Routes: `GET /api/tenant/v1/hq/modules`, `PUT /{moduleCode}/activation`, `GET /{moduleCode}/assignments`, `POST /{moduleCode}/assignments`, `DELETE /{moduleCode}/assignments/{storePublicId}`, and `GET /api/tenant/v1/hq/license/status`.
- Required permissions: `core.module.view`, `core.module.manage`, and `core.module.assignment.manage`; register these as core permissions during the migration.

- [ ] **Step 1: Write failing service/controller tests** for missing permission, trial dependency rejection, disabled module, inactive store, independent per-module quota, repeated assignment, duplicate request key, explicit revoke, and non-release on store suspension/closure.
- [ ] **Step 2: Add a concurrency repository test** launching more assignment requests than quota and assert committed active assignments never exceed the signed quota.
- [ ] **Step 3: Implement module service and migrations for permission definitions**; do not derive enterprise identity from request fields, and resolve every store public ID through current tenant organization data.
- [ ] **Step 4: Implement row locking/atomic quota enforcement** using a deterministic lock order and unique `(module_code, store_id)` constraint; retain assignment history and only release capacity on HQ revoke.
- [ ] **Step 5: Add HQ controllers, stable errors and audit writes** for activation, assignment, revoke, rejected sync/security events and quota reduction over-allocation.
- [ ] **Step 6: Run focused service/controller integration tests** and commit as `feat(tenant): manage module activation and store quotas`.

## Task 6: Add Module Descriptor Metadata and Unified Access Guard

**Files:**
- Modify: `backend/libs/care-module-api/src/main/java/com/smartelder/module/api/ModuleDescriptor.java`
- Create: module menu/resource/operation metadata types under `backend/libs/care-module-api/src/main/java/com/smartelder/module/api/`
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/licensing/ModuleAccessQuery.java`
- Create: `backend/core/care-core/src/main/java/com/smartelder/core/licensing/ModuleAccessGuard.java`
- Create: module registry/configuration in `backend/apps/tenant-app/src/main/java/com/smartelder/api/tenant/licensing/`
- Modify: `backend/core/care-core/src/main/java/com/smartelder/core/iam/TenantAccessResolver.java` only to register module-owned permission definitions, preserving existing permission resolution
- Test: `ModuleDescriptorTest`, `ModuleAccessGuardTest`, and a tenant API integration test for unauthorized module routes

**Interfaces:**
- `ModuleDescriptor` adds immutable menu metadata, registered resource-to-module/operation mappings, formal dependencies and `windDownOperations`; existing constructors/tests remain source-compatible through an overload or migration of all call sites.
- `ModuleAccessQuery.evaluate(String moduleCode, String operation, Long storeId, Instant now)` returns `ModuleAccessDecision(state, allowed, reasonCode, licenseVersion)` and is the only read policy used by menu and API consumers.
- `ModuleAccessGuard.require(String moduleCode, String operation, TenantAccessContext context, Long storeId)` throws a stable module access exception unless the decision permits the operation.
- Access is the intersection of employee role permission, data scope, store status, signed entitlement, HQ activation, per-store assignment, offline grace, expiry and the registered operation policy.
- `core` remains license-exempt but still requires employee permission, data scope and valid store lifecycle for writes.

- [ ] **Step 1: Add failing contract tests** for duplicate/unregistered routes, invalid dependencies, explicit wind-down operations, and backwards-compatible descriptor construction.
- [ ] **Step 2: Add failing policy tests** for all entitlement states, HQ-vs-store access, manipulated store IDs, offline grace boundary, expiry boundary, trial/formal dependency, and free core behavior.
- [ ] **Step 3: Implement module registry and registration validation**; fail application startup on duplicate module code, duplicate route mapping, unknown dependency, or permission code collision.
- [ ] **Step 4: Implement database-backed `ModuleAccessQuery` and service-layer `ModuleAccessGuard`**; do not rely solely on Spring MVC annotations or frontend menu filtering.
- [ ] **Step 5: Wire the HQ module list/menu payload to the same query and add direct API bypass tests** proving a caller cannot access a hidden module endpoint or spoof `moduleCode`/`storeId`.
- [ ] **Step 6: Run `mvn -pl libs/care-module-api,apps/tenant-app -am -Dtest=ModuleDescriptorTest,ModuleAccessGuardTest -Dsurefire.failIfNoSpecifiedTests=false test` plus focused integration tests**; commit as `feat(tenant): enforce module access policy`.

## Task 7: Build the HQ Module Management Page

**Files:**
- Create: `admin/src/pages/tenant/Modules.tsx`
- Create or modify: `admin/src/api/tenantModules.ts` and `admin/src/api/client.ts`
- Modify: `admin/src/App.tsx`
- Modify: `admin/src/components/Layout.tsx` or a new tenant-specific shell under `admin/src/tenant/`
- Test: existing TypeScript/build checks; add component/API tests only if the repository already has a frontend test runner

**Interfaces:**
- Consume only the HQ module/status/assignment endpoints from Task 5 using the staff token issued by `/api/tenant/v1/auth/**`.
- Render module name, license source/state, expiry/perpetual indicator, enabled toggle, allocated/total store quota, assigned store list, and license synchronization status.
- Hide actions when the signed-in role lacks the relevant permission, while treating server responses as authoritative.
- On quota conflict, stale version or module dependency error, refresh module data and display the stable API message; do not optimistically leave a failed change enabled.

- [ ] **Step 1: Define typed API models** matching controller records and stable error responses; ensure tenant and legacy admin credentials use separate storage keys and request interceptors.
- [ ] **Step 2: Implement the HQ module list and license status** with loading, empty, error and offline/read-only states.
- [ ] **Step 3: Add activation toggle and store assignment/revocation dialog** with disabled states for missing entitlement, dependency, inactive store or exhausted quota.
- [ ] **Step 4: Add the tenant route/menu entry** only after tenant staff authentication; keep existing legacy routes unchanged.
- [ ] **Step 5: Run `npm run build` and `npx tsc --noEmit` from `admin/`**; verify the 2A and legacy screens still compile, then commit as `feat(admin): manage tenant module entitlements`.

## Task 8: Complete Offline, Expiry, Migration, and Full Regression Verification

**Files:**
- Modify/add focused tests across `backend/core/care-core/src/test/`, `backend/apps/tenant-app/src/test/`, `backend/apps/platform-app/src/test/`, and `backend/tests/architecture-tests/src/test/`
- Modify: `docs/superpowers/specs/2026-10-31-phase-2b-tenant-module-entitlement-design.md` only if implementation review identifies an approved contract clarification
- Modify: `docs/superpowers/plans/2026-10-30-modular-saas-delivery-roadmap.md` to mark 2B status after acceptance

**Interfaces:**
- No new production interfaces; this task closes the contract with end-to-end evidence.
- Test time-dependent state through injected `Clock`; do not wait in real time.

- [ ] **Step 1: Add boundary matrix tests** for exactly 7 days offline vs one instant beyond; exactly 3 days in wind-down vs one instant beyond; permanent, trial, suspended enterprise, revoked instance and platform recovery.
- [ ] **Step 2: Add sync recovery test** proving a failed heartbeat preserves the last good license, next successful heartbeat applies a newer version, and ACK includes that version's exact digest only after commit.
- [ ] **Step 3: Add full assignment lifecycle test**: allocate, suspend/close store without releasing, revoke from HQ, verify capacity released, then allocate another store.
- [ ] **Step 4: Run backend full verification** from `backend/`: `mvn test`; run tenant and platform test suites independently if the full output makes failures hard to isolate.
- [ ] **Step 5: Run frontend checks** from `admin/`: `npx tsc --noEmit` and `npm run build`.
- [ ] **Step 6: Inspect architecture boundaries and final diff**: platform code has no tenant business imports; tenant code does not connect to platform DB; no secrets are logged; no existing migration is modified; no DROP initialization includes new authorization tables.
- [ ] **Step 7: Update roadmap and record test evidence**; commit as `test: verify phase 2b module entitlement lifecycle`.

## Acceptance Checklist

- [ ] Invalid signatures, wrong tenant/instance binding, rollback, same-version digest conflict and malformed license cannot alter the last valid snapshot.
- [ ] Snapshot commit precedes ACK; ACK is version+digest-aware and idempotent.
- [ ] Network failure preserves entitlement; 7-day offline and 3-day expiry boundaries are exact and deterministic.
- [ ] HQ can activate/deactivate authorized modules and assign/revoke per-module store slots with concurrency-safe quota enforcement.
- [ ] Store suspension/closure and HQ module deactivation retain allocations; only HQ revocation releases the slot.
- [ ] Menus and backend operations use the same access decision; API and internal service paths cannot bypass it.
- [ ] Tenant employee authorization remains separate from platform authentication; platform receives no tenant business data.
- [ ] V3/next Flyway migration works on empty H2 and MySQL 8 databases; existing migrations and dev DROP scripts are untouched.
- [ ] Full backend tests, frontend TypeScript/build, and architecture tests pass.
