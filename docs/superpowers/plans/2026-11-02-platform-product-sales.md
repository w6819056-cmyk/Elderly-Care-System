# 平台商品配置与模块销售实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让平台管理员配置单模块价格与组合套餐，并能为企业购买套餐或单个模块。

**Architecture:** 复用模块目录价格、套餐、报价、订单和许可证履约模型。新增平台套餐目录读取及停售接口；销售授权页面分为商品配置和企业授权，模块价格仍通过既有模块 API 保存，单模块订单仍由报价服务校验依赖、现有授权抵扣和续期。

**Tech Stack:** Spring Boot 3、JdbcClient、React 18、TypeScript、Vite、Vitest、JUnit 5、MockMvc、MySQL/Flyway。

## Global Constraints

- 平台预览库保留现有数据，不使用 dev profile，不重建数据库。
- 模块价格、套餐、订单与授权只能通过现有领域服务/API操作。
- 单模块或套餐报价以服务端报价为准；支付仍明确标为模拟支付。
- 套餐停售不撤销已完成订单或企业现有授权。

---

### Task 1: 套餐管理 API

**Files:**
- Modify: `backend/platform/platform-core/src/main/java/com/smartelder/platform/core/commerce/PlanService.java`
- Modify: `backend/apps/platform-app/src/main/java/com/smartelder/platform/api/commerce/PlanController.java`
- Test: `backend/apps/platform-app/src/test/java/com/smartelder/platform/api/commerce/OrderPurchaseIntegrationTest.java`

- [x] Add tests for listing all configured plans and changing ON_SALE/OFF_SALE.
- [x] Add `GET /plans/catalog` under `platform.plan.view` and `PUT /plans/{code}/status` under `platform.plan.manage`.
- [x] Verify existing `GET /plans?enterpriseId=...` remains filtered to purchasable plans and permanent ownership rules.

### Task 2: 商品配置与直购界面

**Files:**
- Modify: `admin-platform/src/pages/PlatformSalesPage.tsx`
- Modify: `admin-platform/src/pages/PlatformSalesPage.test.tsx`
- Modify: `admin-platform/src/styles.css`

- [x] Add a 商品配置 view that saves module price schedules through existing `PUT /modules/{code}/prices` and creates packages through `POST /plans`.
- [x] Add plan catalog loading and sale status controls.
- [x] Add enterprise purchase mode selection for套餐 or 单模块; module billing choices come from configured prices.
- [x] For single-module purchase, send `items` and `period` to the existing quote/order endpoints.

### Task 3: Verification and preview

- [x] Run all platform frontend tests and production build.
- [x] Run platform backend tests.
- [ ] Build a side-by-side platform JAR without replacing a locked running artifact; restart only platform service if configuration permits.
- [ ] Verify enterprise list, package creation/listing, module quote, simulated payment, and enterprise visibility.
