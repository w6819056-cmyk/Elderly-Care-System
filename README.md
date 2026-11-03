# 模块化智慧养老管理系统

面向养老机构、社区居家服务与连锁门店的模块化管理项目，由小组协作开发。项目采用**同一仓库、两个独立构建与部署的系统**：平台负责企业登记、软件销售与模块授权，企业系统负责总部、门店和客户的业务运营。

当前处于开发与联调阶段。仓库同时保留一期养老业务和小程序源码，不能将目录存在视为所有功能已完成产品化。

## 系统架构

```text
平台侧基础设施                         客户企业基础设施
┌──────────────────────┐              ┌────────────────────────┐
│ 平台总管理端          │              │ 企业总部管理端          │
│ admin-platform       │              │ 门店管理端 / 用户网站   │
│          ↓           │  激活、授权   │ admin-tenant            │
│ platform-app         │ ←──────────→ │          ↓              │
│          ↓           │  模块包交付   │ tenant-app              │
│ 平台独立数据库        │              │          ↓              │
└──────────────────────┘              │ 企业独立业务数据库      │
                                      └────────────────────────┘
```

- **平台总端**由项目运营方部署，管理企业、销售商品、订单、许可证和模块版本。
- **企业总部端**是客户的总管理端，负责旗下门店、员工、角色权限、模块购买与分配；总部本身不是门店。
- **门店管理端**使用独立门店登录入口，办理本店业务；总部与门店在企业系统内共享基础服务。
- **用户网站**展示企业品牌和门店信息，支持注册登录、咨询与到店参观预约、查看自己的预约。
- 企业客户的账号、联系方式与业务记录保存在企业数据库，不上传平台授权系统。平台权限域与企业权限域分离。

## 当前功能

| 范围 | 已接入的能力 |
| --- | --- |
| 平台管理 | 企业自主登记与平台审核、企业资料管理、实例激活、授权期限与门店额度管理 |
| 模块交付 | 模块目录、包校验、版本上传与发布、企业下载和安装计划、安装确认及运行时接入 |
| 销售管理 | 单模块商品定价、套餐组合、售卖卡片编辑与预览、折扣券与抵扣券、优惠券码 |
| 订单授权 | 企业报价与下单、订单查询、待支付订单修改与取消、模拟支付、模块授权撤销与恢复 |
| 企业组织 | 门店管理、员工管理、入职申请与审批、自定义角色及权限组、指定门店数据范围 |
| 企业运营 | 品牌首页维护、门店首页编辑及总部审核、咨询和参观预约管理 |
| 客户管理 | 客户卡片、搜索、分页、资料及预约详情；总部按范围查看、门店仅查看本店关联客户 |
| 用户网站 | 企业与门店展示、账号注册登录、持久化预约记录、个人中心 |
| 会员模块 | 会员等级与权益说明、总部开通及调整会员、门店最小会员卡查询、用户查看本人会员信息 |

会员模块需要有效授权、安装、启用及员工权限配置，不随普通用户注册自动开通。积分、储值、自动折扣和权益核销不属于当前会员模块已交付范围。

模块销售当前使用本地模拟支付验证流程，**尚未完成微信支付、支付宝、银联等真实支付渠道对接**。完整系统升级、更多养老业务模块及第三方设备接入仍需继续开发和验证。

## 模块化规则

免费基础底座提供组织、身份、权限、网站及基础客户管理。业务模块通过独立模块包交付，模块自行维护清单、前后端资源和增量数据库迁移，通过底座接口关联基础数据。

典型使用流程：

```text
企业登记并通过审核 → 实例激活 → 购买模块或套餐 → 同步授权
→ 下载模块并由总部确认安装 → 启用模块 → 分配门店名额与员工权限
```

- 套餐由多个模块组成，模块名额分别计算，在分配时占用。
- 必需依赖需满足正式授权条件；试用不替代正式依赖授权。
- 正常有效授权续费叠加剩余期限；撤销后重新购买不叠加被撤销的期限。
- 门店停用不自动回收名额，由总部手动撤销分配。
- 联网时通过签名协议同步许可证；离线使用受本地已验证许可证及离线策略约束，不承诺无限期离线授权。

具体模块协议、安装前提及权限以 [模块包规范](docs/platform/模块包规范-v1.md) 和 [会员模块使用说明](docs/platform/会员模块使用说明.md) 为准。

## 仓库结构

```text
admin-platform/              平台总管理端（React + TypeScript）
admin-tenant/                企业总部、门店工作台与用户网站
backend/
  apps/platform-app/         平台 API
  apps/tenant-app/           企业 API
  apps/installer-app/        安装执行器相关工程
  core/                     组织权限和基础业务
  libs/                     公共协议、模块包及运行时基础设施
  modules/                  会员、机构康养、社区居家等模块工程
  platform/                 平台管理与许可证服务
  tests/architecture-tests/  架构边界测试
admin/                       一期旧版管理端，保留兼容与回归用途
miniprogram/                 一期微信小程序源码
examples/                    模块接入示例
scripts/                     构建、验证及冒烟脚本
docs/                        设计、接口、部署与开发交接文档
```

## 技术栈

- 后端：Java 17、Spring Boot 3.3、Spring Security、MyBatis-Plus、Spring JDBC。
- 存储与迁移：MySQL 8、Flyway；自动化测试使用 H2。
- 前端：React 18、TypeScript、Vite、React Router。
- 验证：JUnit、Spring Boot Test、ArchUnit、Vitest、Testing Library。
- 安全与交付：JWT、角色与数据范围校验、签名许可证、模块包校验及增量迁移。

## 本地开发

### 环境

JDK 17、Maven 3.8+、Node.js 20+、MySQL 8，以及 Git。以下命令从仓库根目录执行。

### 后端构建与配置

```powershell
mvn -f backend/pom.xml -pl apps/platform-app,apps/tenant-app -am package -DskipTests
```

平台与企业使用两个独立数据库。先创建空数据库，再通过外部配置提供连接信息，由 Flyway 执行迁移；不要向新架构数据库手动导入一期演示 SQL。

| 服务 | 配置要求 |
| --- | --- |
| 平台 | `PLATFORM_DB_URL`、`PLATFORM_DB_USERNAME`、`PLATFORM_DB_PASSWORD`、`PLATFORM_JWT_SECRET`、`PLATFORM_LICENSE_SIGNING_PRIVATE_KEY` |
| 平台初始化管理员 | `PLATFORM_BOOTSTRAP_ADMIN_USERNAME`、`PLATFORM_BOOTSTRAP_ADMIN_PASSWORD`；仅首次初始化生效 |
| 企业 | 外部配置中的 `spring.datasource.url`、`spring.datasource.username`；密码可设置 `TENANT_DB_PASSWORD` 或外部 `spring.datasource.password` |
| 企业认证 | `TENANT_STAFF_JWT_SECRET`、`APP_JWT_SECRET`，部署时设置独立随机值 |
| 企业首次初始化 | `TENANT_BOOTSTRAP_ENABLED`、`TENANT_ORG_NO`、`TENANT_ENTERPRISE_NAME`、`TENANT_OWNER_ACCOUNT`、`TENANT_OWNER_DISPLAY_NAME`、`TENANT_OWNER_PASSWORD` |
| 企业授权连接 | 激活信息、企业实例密钥、平台地址与受信任公钥，详见服务配置和安装文档 |

将真实密码与密钥保存在仓库外。例如使用绝对路径指定外部配置：

```powershell
java -jar backend/apps/platform-app/target/platform-app-2.0.0-SNAPSHOT.jar --spring.config.additional-location=file:C:/your-config/platform.yml
java -jar backend/apps/tenant-app/target/tenant-app-2.0.0-SNAPSHOT.jar --spring.config.additional-location=file:C:/your-config/tenant.properties
```

默认平台 API 端口为 `8081`，企业 API 为 `8082`。首次企业初始化完成后关闭引导开关。项目不提供通用生产账号，本机预览账号不会随源码发布。

> **不要使用 `dev` profile 启动已有业务数据库。** 一期开发配置包含 DROP 和演示数据初始化逻辑，可能覆盖数据。生产或持续联调环境应使用外部配置、Flyway 增量迁移及独立备份。

### 前端启动

分别在两个终端执行：

```powershell
npm --prefix admin-platform ci
npm --prefix admin-platform run dev
```

```powershell
npm --prefix admin-tenant ci
npm --prefix admin-tenant run dev
```

| 页面 | 默认地址 |
| --- | --- |
| 平台管理登录 | http://localhost:5174/login |
| 企业总部登录 | http://localhost:5175/hq/login |
| 门店员工登录 | http://localhost:5175/store-admin/门店编号/login |
| 企业品牌网站 | http://localhost:5175/brand |
| 门店用户网站 | http://localhost:5175/stores/门店编号 |
| 用户个人中心 | http://localhost:5175/customer |

开发服务器分别将 `/api` 代理到 `8081`、`8082`。正式环境分别构建部署，可配置构建时变量 `VITE_API_BASE_URL`，或使用同源反向代理；HTTPS、域名与小程序配置由部署环境提供。

## 测试与构建

```powershell
mvn -f backend/pom.xml test
npm --prefix admin-platform run test -- --run
npm --prefix admin-tenant run test -- --run
npm --prefix admin-platform run build
npm --prefix admin-tenant run build
```

测试覆盖组织权限、数据隔离、授权签名、订单履约、模块接入及页面交互等。测试通过不等同于真实支付、短信或设备服务已接通；以对应模块文档和实际部署验收结果为准。

## 开发文档

- [部署说明](docs/部署说明.md)
- [客户管理与数据范围](docs/platform/客户管理与数据范围.md)
- [会员模块使用说明](docs/platform/会员模块使用说明.md)
- [模块包规范](docs/platform/模块包规范-v1.md)
- [模块运行时接入](docs/platform/模块执行器与运行时接入.md)
- [开发交接记录](docs/AI开发交接-2026-11-02.md)

本项目正由一期养老业务逐步演进为模块化双系统。旧版页面、示例和历史文档用于追溯，不应直接作为当前生产部署方案。

## 许可证

GitHub 仓库现有许可证为 [BSD 3-Clause](LICENSE)。
