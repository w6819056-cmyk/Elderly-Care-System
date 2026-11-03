# 独立会员模块
仅依赖 host API；普通 thin JAR，禁止 Boot repackage。Spring 通过 Entry 返回显式 Bean 配置。
包版本 1.0.1，底座 [2.1.0,3.0.0)。新增总部客户详情会员摘要 provider，受客户数据范围及 membership:member:read 权限控制。`GET /api/tenant/v1/customers/{customerPublicId}/module-features` 会在总部客户详情中聚合此摘要；门店仍仅可通过客户主动出示编号执行 `/api/tenant/v1/modules/membership/lookup`。manifest-template 的摘要为占位，打包时重新计算，前端入口已声明为 frontend/main.js。迁移在 payload/migrations，MySQL/H2 MySQL模式。
所有管理服务检查 HQ；门店仅 lookup；消费者 feature 由宿主传入认证客户 UUID。等级停用而不删除；会员唯一客户约束；改级行锁、版本检查和审计同事务。禁用等级不能新分配。
接口前缀 /api/tenant/v1/modules/membership，成功统一 {code:"OK",data:...}。
GET /levels -> [{id,name,rank,benefits,enabled,version}]；POST /levels / PUT /levels/{id} 输入 {name,rank,benefits,enabled,version}。
GET /customers?q= -> [{publicId,username,displayName}]。
GET /members?q= -> [{id,customerPublicId,levelId,version}]；POST /members 输入 {customerPublicId,levelId,reason}；PUT /members/{id}/level 输入 {levelId,reason,version}，返回会员对象。
GET /members/{id}/history -> [{id,memberPublicId,fromLevelId,toLevelId,reason,operatorPublicId,changedAt}]，开通 fromLevelId 为 null。
GET /lookup?customerPublicId= 与 consumer feature -> {member:false,customerPublicId} 或 {member:true,customerPublicId,memberPublicId,levelName,benefits}。
所有公开 id 为 UUID，版本为数字；冲突 HTTP409，无权限403，输入400，不存在404。列表最多100，客户搜索50。无删除端点和 GET 写操作。

会员卡另含 levelEnabled:boolean、status:ACTIVE|LEVEL_DISABLED、displayName（通过客户桥获取）。HQ菜单 /modules/membership/manage，STORE菜单 /modules/membership。等级名称去空格并唯一，rank范围0..9999。宿主需映射 ResponseStatusException 保留HTTP状态。
