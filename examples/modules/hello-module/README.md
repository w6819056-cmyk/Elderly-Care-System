# 首个可运行模块样例

这是安装底座的验证模块，不是会员模块，也不提供养老业务。包含真实外置 JAR、门店前端单文件页面、权限声明、宿主权限拦截及独立数据库迁移。企业底座源码不需要加入它的 Maven 依赖或页面 import。

在仓库根目录执行：

```powershell
mvn -f backend/pom.xml -pl libs/care-module-api -am install -DskipTests
mvn -f examples/modules/hello-module/pom.xml package
New-Item -ItemType Directory -Force examples/modules/hello-module/payload/backend
Copy-Item examples/modules/hello-module/target/hello-module-1.0.0.jar examples/modules/hello-module/payload/backend/module.jar
node scripts/build-module-package.mjs examples/modules/hello-module/payload examples/modules/hello-module/manifest-template.json C:/private/preview-publisher-private.pem C:/release/hello-module-1.0.0.caremod
```

实际发布时替换 keyId 与发布私钥，私钥不能放入仓库。模块包上传到平台模块中心，发布后还需要企业有效授权和实例激活才能下载。总部确认计划后由已经配置的执行器安装。安装完成后，总部启用模块、分配门店名额，并为角色分配“查看安装验证模块”权限；门店菜单随后出现“安装验证”。

本样例确实新增 hello_module_probe 表；只能在专用验证企业先演练。不要把旧 delivery-probe 协议样包当作本模块；旧样包没有实现运行接口。
