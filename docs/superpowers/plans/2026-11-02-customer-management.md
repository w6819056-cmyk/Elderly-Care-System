# 客户管理实施计划

依据已确认的同日 customer-management-design.md。

1. 新增 CustomerManagementServiceTest：使用独立 H2 数据，覆盖总部全量、指定门店、门店去重、详情隔离、权限、搜索和分页。
2. 实现 CustomerManagementService 与 CustomerManagementController：分页查询账号，按身份与数据范围过滤关联门店、预约明细和搜索；新增 V13 权限迁移并更新迁移断言。
3. 新增 CustomerManagementPage 与独立样式、接口类型；接入 /hq/customers、/store/customers 和权限控制导航。复用现有详情弹窗组件与滚动区域。
4. 前端测试覆盖加载、详情、搜索、分页、空态、请求错误重试；运行后端相关测试、前端测试和构建。
5. 停止本工作树租户 jar 后重新打包，使用原 tenant-preview.properties 重启，不启用 dev profile。验证本地接口与页面入口。

不自动新增演示客户，不修改旧迁移，不提交当前混合工作区。
