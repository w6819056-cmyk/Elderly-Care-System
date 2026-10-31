# Two-System Admin Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add independently buildable platform and tenant management applications to the same repository, with isolated sessions, server-verified identity, and permission-aware shells.

**Architecture:** Keep the platform and tenant systems as distinct build roots in the existing monorepo. The platform app talks only to `platform-app`; the tenant HQ/store workspace talks only to the customer-deployed `tenant-app`. Keep the current `admin/` and mini program operational while the new management shells are introduced; user-facing brand clients and the update agent remain later enterprise-system sub-applications.

**Tech Stack:** React 18, TypeScript, React Router 6, Vite, Vitest, Testing Library, existing Spring Boot platform and tenant APIs.

## Global Constraints

- One repository contains two complete system projects with separate build commands, environment configuration, deployable artifacts, API targets, and database connections.
- Platform management runs on platform infrastructure; tenant management, tenant services, and enterprise business data run on customer infrastructure.
- Platform and tenant authentication, token storage, request clients, and logout behavior remain isolated; neither app uses legacy `ec_token` or `ec_user` storage keys.
- Resolve identity and current permissions from the corresponding `/me` endpoint on application startup; backend authorization remains authoritative.
- Tenant HQ and STORE staff share the tenant employee authentication service but are routed by `staffType`; store data scope is never inferred from client-controlled values.
- Tenant module navigation comes from `GET /api/tenant/v1/modules/menus`; a missing or failed response does not expose module routes.
- User brand website and mini program are code-owned by the enterprise system project but are not implemented in this management-shell increment.
- The platform owns version catalog and signed release artifacts; the customer-side enterprise service validates a release and waits for HQ confirmation before local upgrade. No remote shell or silent customer-server mutation is introduced.
- Preserve existing `admin/` behavior and run its TypeScript/production build as regression checks.

---

## File Map

| Area | Files | Responsibility |
| --- | --- | --- |
| Platform browser app | `admin-platform/package.json`, lockfile, `index.html`, `vite.config.ts`, `tsconfig.json`, `src/main.tsx`, `src/App.tsx`, `src/api/**`, `src/auth/**`, `src/components/**`, `src/pages/**`, `src/styles.css` | Independent platform login, `/me` session bootstrap, permission-aware route shell, platform home and error states. |
| Tenant browser app | `admin-tenant/package.json`, lockfile, `index.html`, `vite.config.ts`, `tsconfig.json`, `src/main.tsx`, `src/App.tsx`, `src/api/**`, `src/auth/**`, `src/components/**`, `src/pages/**`, `src/styles.css` | Independent tenant employee session, HQ/STORE workspace selection, permission shell, server module menu, and existing HQ module management UI. |
| Frontend contract tests | `admin-platform/src/**/*.test.ts(x)`, `admin-tenant/src/**/*.test.ts(x)` | Verify token isolation, API status behavior, session bootstrap, role routing, and permission filtering. |
| Verification | `scripts/verify-all.ps1`, `README.md`, `docs/部署说明.md` | Add independent build/test commands and local run ports without changing customer production host assumptions. |

## Task 1: Scaffold Independent Browser Builds

**Files:**
- Create: `admin-platform/package.json`, `admin-platform/index.html`, `admin-platform/vite.config.ts`, `admin-platform/tsconfig.json`, `admin-platform/src/main.tsx`, `admin-platform/src/App.tsx`, `admin-platform/src/styles.css`
- Create: `admin-tenant/package.json`, `admin-tenant/index.html`, `admin-tenant/vite.config.ts`, `admin-tenant/tsconfig.json`, `admin-tenant/src/main.tsx`, `admin-tenant/src/App.tsx`, `admin-tenant/src/styles.css`
- Modify: `scripts/verify-all.ps1`

**Interfaces:**
- Platform dev server runs on `5174` and proxies `/api` to `http://localhost:8081`.
- Tenant dev server runs on `5175` and proxies `/api` to `http://localhost:8082`.
- Each app has independent `dev`, `typecheck`, `test`, and `build` scripts and produces its own `dist/` directory.
- Each app uses its own `.env.example` and `VITE_API_BASE_URL`; no production URL is hard-coded.

- [ ] **Step 1: Create independent package manifests and entry documents** using React 18, React DOM, React Router 6, TypeScript, Vite, Vitest, jsdom, and Testing Library. Keep package versions aligned with `admin/package.json`; do not make `admin/` a workspace dependency.
- [ ] **Step 2: Add minimal router roots and a smoke test** in each app proving `/login` renders and unknown routes resolve to the app's not-found route.
- [ ] **Step 3: Run each app's initial tests and builds** from its own directory; verify failures are isolated to that package and outputs are `admin-platform/dist` and `admin-tenant/dist`.
- [ ] **Step 4: Add both app checks to `scripts/verify-all.ps1`** while preserving the existing backend and `admin/` checks.
- [ ] **Step 5: Commit** as `build: scaffold independent platform and tenant apps`.

## Task 2: Implement Platform Authentication and Shell

**Files:**
- Create: `admin-platform/src/api/session.ts`
- Create: `admin-platform/src/api/client.ts`
- Create: `admin-platform/src/auth/AuthProvider.tsx`
- Create: `admin-platform/src/auth/RequirePlatformPermission.tsx`
- Create: `admin-platform/src/navigation.ts`
- Create: `admin-platform/src/components/PlatformLayout.tsx`
- Create: `admin-platform/src/pages/PlatformLoginPage.tsx`
- Create: `admin-platform/src/pages/PlatformHomePage.tsx`
- Create: `admin-platform/src/pages/ForbiddenPage.tsx`
- Test: corresponding `session.test.ts`, `client.test.ts`, `AuthProvider.test.tsx`, and route tests.

**Interfaces:**
- `PlatformPrincipal` mirrors `GET /api/platform/v1/auth/me`: `userId`, `publicId`, `username`, `roleCodes`, and `permissionCodes`.
- `login(username, password)` calls `POST /api/platform/v1/auth/login` and stores the returned `accessToken`, `expiresAt`, and principal only under `platform.*` keys.
- `loadCurrentPrincipal()` calls `GET /api/platform/v1/auth/me`; a failed/expired credential clears only platform keys.
- `request<T>(path, init)` sends only the platform token, parses the current `ApiResponse` envelope, clears this session on `401`, and preserves it on `403`.
- Platform navigation entries carry `path`, `label`, and `requiredPermission`; a route and its navigation entry use the same permission definition.
- Only real routes implemented in this increment appear in the navigation. Later platform feature pages register their routes and current permission codes independently.

- [ ] **Step 1: Add failing session tests** proving a platform session writes only `platform.*` keys, clearing it leaves `tenant.*` and legacy `ec_*` keys intact, and `loadCurrentPrincipal` replaces cached permissions with `/me` permissions.
- [ ] **Step 2: Add failing API client tests** proving the platform bearer token is attached, platform envelope errors are surfaced, `401` clears only platform keys, and `403` keeps the platform session.
- [ ] **Step 3: Run focused tests** with `npm test -- --run src/api/session.test.ts src/api/client.test.ts`; confirm expected failures before implementation.
- [ ] **Step 4: Implement platform API and auth provider** against `POST /api/platform/v1/auth/login` and `GET /api/platform/v1/auth/me`; call `/me` once at app startup before rendering protected routes.
- [ ] **Step 5: Add failing route tests** for anonymous redirect, authenticated home rendering, permission-filtered navigation, direct URL denial, and recovery after `/me` returns `401`.
- [ ] **Step 6: Implement the platform shell** with login, protected home, permission-filtered route registry, loading, offline/error, forbidden, and not-found states. Do not show sample enterprises, fake counts, or unimplemented business links.
- [ ] **Step 7: Run platform unit/UI tests, `npm run typecheck`, and `npm run build`**; commit as `feat(platform-ui): add authenticated platform shell`.

## Task 3: Implement Tenant HQ/Store Authentication and Shell

**Files:**
- Create: `admin-tenant/src/api/session.ts`
- Create: `admin-tenant/src/api/client.ts`
- Create: `admin-tenant/src/api/tenant.ts`
- Create: `admin-tenant/src/auth/AuthProvider.tsx`
- Create: `admin-tenant/src/auth/RequireTenantWorkspace.tsx`
- Create: `admin-tenant/src/navigation.ts`
- Create: `admin-tenant/src/components/TenantLayout.tsx`
- Create: `admin-tenant/src/pages/TenantLoginPage.tsx`
- Create: `admin-tenant/src/pages/TenantHomePage.tsx`
- Create: `admin-tenant/src/pages/TenantModulesPage.tsx`
- Create: `admin-tenant/src/pages/ModuleRoutePage.tsx`
- Create: `admin-tenant/src/pages/ForbiddenPage.tsx`
- Test: corresponding `session.test.ts`, `client.test.ts`, auth/route tests, and `TenantModulesPage.test.tsx`.

**Interfaces:**
- `TenantStaffPrincipal` mirrors `GET /api/tenant/v1/auth/me`, including `staffType: 'HQ' | 'STORE'`, `homeStoreId`, and `accessContext.permissionCodes`.
- `login(account, password)` calls `POST /api/tenant/v1/auth/login` and stores token/principal only under `tenant.*` keys.
- `loadCurrentPrincipal()` calls `GET /api/tenant/v1/auth/me`; the server-refreshed principal replaces cached identity and permissions.
- Tenant `request<T>` sends only the tenant token, parses `ApiResponse`, clears only tenant session on `401`, and preserves it on `403`.
- HQ and STORE routes are selected from `staffType`; HQ module management requires `core.module.view`; action buttons also check `core.module.manage` and `core.module.assignment.manage`.
- Dynamic module navigation uses `GET /api/tenant/v1/modules/menus`; registered module route metadata must match the returned module `route`, and menu load failure exposes no module route.

- [ ] **Step 1: Add failing tenant session/API tests** proving tenant storage cannot read, attach, or clear platform/legacy keys; verify `401` clears tenant session while `403` does not.
- [ ] **Step 2: Add failing auth/route tests** for `/me` refresh, HQ redirect to `/hq`, STORE redirect to `/store`, STORE denial of `/hq/**`, and HQ denial of `/store/**` when the route requires store role.
- [ ] **Step 3: Run focused tenant tests** with `npm test -- --run src/api/session.test.ts src/api/client.test.ts`; confirm expected failures.
- [ ] **Step 4: Implement tenant auth and API client** using the tenant v1 endpoints; do not copy or import `admin/src/api/client.ts` or `admin/src/api/tenantModules.ts`.
- [ ] **Step 5: Add failing menu tests** for server-returned menus, permission-gated core routes, module menu failure, and a URL not present in the authorized route registry.
- [ ] **Step 6: Implement the tenant shell** with separate `/hq/**` and `/store/**` layouts, server module menus, a truthful empty home state, module route fallback, and forbidden/not-found/error states.
- [ ] **Step 7: Port the existing HQ module authorization screen into `TenantModulesPage`** using typed calls for `/hq/modules`, `/hq/module-stores`, `/hq/license/status`, assignments, activation, assign, and revoke. Keep the existing `admin/` page intact during this migration.
- [ ] **Step 8: Run tenant UI tests, `npm run typecheck`, and `npm run build`**; commit as `feat(tenant-ui): add isolated HQ and store shell`.

## Task 4: Document Run and Deployment Boundaries

**Files:**
- Modify: `README.md`
- Modify: `docs/部署说明.md`

**Interfaces:**
- Document `npm --prefix admin-platform run dev`, `npm --prefix admin-tenant run dev`, their default local URLs, API proxies, and independent build commands.
- Production deployment configuration specifies the platform API URL for platform UI and the customer-owned tenant API URL for tenant UI; no customer hostname is checked into source control.
- Document that the enterprise website/mini-program code belongs to the enterprise system project and uses the platform unified domain/AppID, while enterprise API/business data stays with the customer's tenant system.
- Document that platform version publication does not execute customer-side commands; the tenant service validates signed artifacts and waits for HQ confirmation before a local upgrade.

- [ ] **Step 1: Add the two dev/build commands and origin/API responsibilities** to the README and deployment guide; replace outdated single-app URLs only where they describe the new modular SaaS management consoles, and keep legacy workflow instructions clearly labeled.
- [ ] **Step 2: Document the two system project boundary**: platform hosts its own console/API/control DB; customer hosts tenant console/API/business DB; brand website/mini program source belongs to the enterprise system project while public entry uses platform domain/AppID; platform versions are verified client-side and applied only after HQ confirmation.
- [ ] **Step 3: Run `scripts/verify-all.ps1`** and confirm old app, both new apps, backend integration tests, and architecture tests pass together.
- [ ] **Step 4: Inspect `git diff --check` and the final diff**; commit as `docs: document two-system admin builds and deployment`.

## Final Acceptance

- [ ] Platform and tenant frontends have distinct package roots, dev servers, API base configuration, production build artifacts, and authentication stores.
- [ ] Platform `/me` controls platform identity and permission navigation; tenant `/me` controls HQ/STORE identity and scope presentation.
- [ ] Cross-application and legacy tokens are never attached, restored, or cleared by the wrong app.
- [ ] Unauthorized menu, direct route, `401`, `403`, API offline, loading, and empty states behave as specified.
- [ ] Tenant dynamic menus come from the server; hidden/failed modules have no available app route.
- [ ] Existing `admin/` build and all backend tests continue to pass.
- [ ] User-facing brand website/mini program and release update agent are not reported complete as part of this management-shell increment.
