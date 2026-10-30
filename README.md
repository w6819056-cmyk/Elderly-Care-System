# 综合智慧养老平台

按《综合智慧养老平台软件需求规格说明书》V1.0 实现的一期核心链路系统。

- `backend/`　Spring Boot 3 + MyBatis-Plus 后端服务（Java 17）
- `admin/`　　React 18 + TypeScript 管理端（机构/服务商端 + 平台运营端）
- `miniprogram/`　微信小程序（老人端 + 家属端）
- `docs/`　　　接口清单、需求追溯表、部署说明

> 工程正在迁移为模块化多企业 SaaS 架构。现有单租户程序已迁入
> `backend/apps/tenant-app`，平台控制面位于 `backend/apps/platform-app`。
> 实施边界以 `docs/superpowers/plans/2026-10-30-phase-0-backend-foundation.md` 为准。


## 一期交付范围

| 模块 | 已实现 | 对应需求编号 |
| --- | --- | --- |
| 账号与权限 | 注册登录、JWT 鉴权、RBAC、账号停用 | USR-01、OP-01、OP-07 |
| 家属绑定 | 双向确认绑定、授权范围校验 | USR-02、USR-03 |
| 健康管理 | 电子健康档案、体征上报与趋势、阈值异常提示、用药计划、家属代设置 | HC-01、HC-02、HC-05~HC-09 |
| 安全监护 | SOS 一键呼叫、跌倒/围栏/久未活动事件接入、红黄蓝分级、处置闭环 | SA-01、SA-04~SA-10 |
| 家人互联 | 老人状态面板、告警联动、代下单 | FM-01、FM-05、FM-06、FM-08 |
| 服务调度 | 服务目录、下单预约、派单规则、订单状态机、上门打卡、评价、退款 | SV-01、SV-02、SV-05~SV-15 |
| 运营中台 | 服务商审核、项目审核、订单监控、数据大屏、用户管理 | OP-02~OP-06、OP-08~OP-12 |
| 消息通知 | 告警/服务/系统三类消息、分级触达、渠道扩展点、已读记录 | MSG-01~MSG-05 |

未纳入一期的部分（文档中标注二期/三期或占位）：视频探望（FM-02/FM-07）、兴趣社群与志愿互助（CM-04/CM-05）、真实微信支付与短信网关对接、真实 IoT 设备接入。代码中均留有扩展点，见 `docs/接口清单.md` 的"扩展点"章节。

## 快速启动

### 1. 数据库

```powershell
mysql -u root -p -e "CREATE DATABASE elderly_care DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -u root -p elderly_care -e "source E:/yanglao/backend/apps/tenant-app/src/main/resources/db/schema.sql"
mysql -u root -p elderly_care -e "source E:/yanglao/backend/apps/tenant-app/src/main/resources/db/data-demo.sql"
```

表结构与演示数据也可由 `dev` profile 启动时自动初始化（`spring.sql.init`）。

### 2. 后端

```powershell
# 按本机情况修改 backend/apps/tenant-app/src/main/resources/application.yml 中的数据库账号密码
mvn -f E:\yanglao\backend\pom.xml -pl apps/tenant-app -am package -DskipTests
java -jar E:\yanglao\backend\apps\tenant-app\target\tenant-app-2.0.0-SNAPSHOT.jar --spring.profiles.active=dev
```

- 租户服务地址：http://localhost:8082
- 接口文档：http://localhost:8082/swagger-ui.html
- 平台控制面：http://localhost:8081/api/platform/v1/health
- `dev` profile 会自动建表并写入演示数据；生产环境请去掉该 profile 并设置 `APP_JWT_SECRET`。

### 3. 管理端

```powershell
Set-Location E:\yanglao\admin
npm install
npm run dev
```

打开 http://localhost:5173 ，已配置 `/api` 代理到 8082。

### 4. 小程序

1. 用微信开发者工具导入 `miniprogram/` 目录，AppID 选择“测试号”。
2. 详情 → 本地设置 → 勾选“不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书”。
3. 如需真机调试，把 `app.js` 中 `baseUrl` 改为本机局域网 IP。
## 演示账号

密码均为 `888888`。

| 角色 | 手机号 | 登录端 |
| --- | --- | --- |
| 老人（张桂兰，elderId=1） | 13800000001 | 小程序 |
| 家属（张小明，已绑定 elderId=1） | 13800000002 | 小程序 |
| 老人（李长顺，elderId=2） | 13800000003 | 小程序 |
| 机构管理员（康颐护理中心） | 13900000001 | 管理端 |
| 机构管理员（夕阳红助餐） | 13900000002 | 管理端 |
| 护理员（康颐护理中心） | 13900000011 | 管理端 |
| 平台运营管理员 | 13700000001 | 管理端 |
| 待审核服务商（安康陪诊） | 13900000003 | 管理端（审核通过后开通） |

## 验证

推荐使用统一入口执行后端结构检查、全仓 Maven 测试和管理端构建：

```powershell
Set-Location E:\yanglao
.\scripts\verify-all.ps1
```

只验证后端：

```powershell
.\scripts\verify-backend.ps1
```

后端集成测试继续覆盖一期完整闭环：登录鉴权 → 体征上报与阈值告警 → SOS 分级推送 → 家属状态面板 → 告警处置闭环 → 家属代下单 → 支付自动派单 → 机构接单/上门打卡 → 评价与结算 → 服务商审核开通账号 → 项目审核后上架 → 越权与状态机非法流转拦截。

阶段 0 实测：后端 33 项测试通过（其中原租户集成测试 22 项），架构边界 3 项通过；管理端 TypeScript + Vite 构建通过，JS 产物 188.01 KB（gzip 59.64 KB）。详见 `docs/阶段0验收记录.md`。

接口冒烟（需后端已启动）：

```bash
bash scripts/smoke-test.sh http://localhost:8082
```

依次验证：登录 → 服务商城 → 体征异常 → SOS → 家属面板 → 告警处置闭环 → 代下单/支付/派单 → 接单打卡 → 评价 → 越权拦截 → 数据大屏。
## 目录结构

```text
backend/
  pom.xml                          Maven 聚合父工程
  apps/
    platform-app/                  平台控制面应用，端口 8081
    tenant-app/                    企业租户业务应用，端口 8082
  libs/
    care-common/                   统一响应、错误码、TraceId
    care-security/                 安全基础设施
    care-core-api/                 核心公开契约
    care-business-api/             业务公开契约
    care-module-api/               模块描述与依赖契约
  core/
    care-core/                     核心共享实现
    care-business/                 业务共享实现
  modules/
    institution/                   机构康养模块 API + 实现
    community/                     社区居家模块 API + 实现
    membership/                    会员运营模块 API + 实现
  platform/
    platform-core/                 平台控制面核心
    platform-licensing/            授权领域
  tests/
    architecture-tests/            架构边界测试
admin/                             React 管理端
miniprogram/                       微信小程序
docs/                              设计、计划、接口、需求追溯、部署与验收
scripts/                           结构检查、统一验证与冒烟脚本
```

