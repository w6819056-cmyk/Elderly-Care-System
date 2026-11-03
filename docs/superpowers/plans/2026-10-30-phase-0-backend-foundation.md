# 模块化多企业养老 SaaS 阶段 0：后端工程基线实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不丢失现有 22 个集成测试的前提下，把单应用后端改造成平台端、租户端、共享 API 和业务模块清晰分离的 Maven 多模块工程。

**Architecture:** 使用一个 Maven 父工程统一版本，现有单租户程序先迁入 `apps/tenant-app` 保持可运行，再新增独立的 `apps/platform-app`。公共响应、请求追踪和模块描述契约放入 `libs`，业务核心与模块先建立空模块边界，由架构测试阻止平台端依赖企业业务实现。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Maven 3.8.1、MyBatis-Plus 3.5.7、JUnit 5、AssertJ、ArchUnit 1.3.0、H2、PowerShell 5+。

**执行状态：** 已完成，2026-10-30 通过验收。验收结果见 E:\yanglao\docs\阶段0验收记录.md。

## Global Constraints

- 工作目录固定为 `E:\yanglao`；所有路径必须显式解析，不允许根据未校验变量执行递归移动或删除。
- Java 版本固定为 17；Spring Boot 版本固定为 3.3.5；Maven 基线为 3.8.1。
- 平台端端口为 `8081`，租户端端口为 `8082`。
- 平台 API 前缀为 `/api/platform/v1`，租户 API 前缀为 `/api/tenant/v1`，企业客户 API 前缀为 `/api/client/v1`。
- 包根固定为 `com.smartelder`。
- 平台端不得依赖 `com.smartelder.core`、`com.smartelder.business` 或 `com.smartelder.module`。
- `-api` 模块不得依赖任何 `-impl` 或 `internal` 包。
- 模块之间只能依赖公开 API 包，不得直接依赖其他模块的 Entity、Mapper 或数据库表。
- 所有金额使用 `BigDecimal` 和数据库 `DECIMAL`；所有时间以 UTC 存储。
- 生产环境禁止 DROP 重建表；本阶段不改变现有 `dev` 和 `test` 初始化行为。
- 不使用微服务、运行时插件上传或动态脚本加载。
- 每个代码任务必须先运行失败测试，再写最小实现，最后运行模块测试和全仓回归。
- 未经人工明确批准，不初始化 Git、不提交、不推送。当前目录目前不是 Git 仓库。

---

## File Structure

```text
E:\yanglao\
├── .gitattributes                             换行和文本编码规则
├── .gitignore                                 追加多模块与前端构建产物规则
├── backend\
│   ├── pom.xml                                Maven 父工程
│   ├── apps\
│   │   ├── platform-app\                      平台控制面可执行程序
│   │   └── tenant-app\                        企业租户可执行程序，承接现有代码
│   ├── libs\
│   │   ├── care-common\                       统一响应、错误码、TraceId
│   │   ├── care-security\                     认证和 JWT 公共契约
│   │   ├── care-core-api\                     基础底座公开接口
│   │   ├── care-business-api\                 订单支付等业务公开接口
│   │   └── care-module-api\                   模块描述、依赖和权限契约
│   ├── core\
│   │   ├── care-core\                         基础底座实现
│   │   └── care-business\                     业务底座实现
│   ├── modules\
│   │   ├── institution\                       机构康养 API 与实现
│   │   ├── community\                         社区居家 API 与实现
│   │   └── membership\                        会员运营 API 与实现
│   ├── platform\
│   │   ├── platform-core\                     平台企业、模块和套餐实现
│   │   └── platform-licensing\                授权、心跳和版本实现
│   └── tests\
│       └── architecture-tests\                跨模块架构边界测试
├── scripts\
│   ├── test-backend-structure.ps1             多模块结构检查
│   ├── verify-backend.ps1                     后端完整验证
│   └── verify-all.ps1                         当前全仓验证
└── docs\superpowers\plans\                    实施计划
```

## Task 0: 建立版本控制与现有基线

**Files:**
- Create: `E:\yanglao\.gitattributes`
- Modify: `E:\yanglao\.gitignore`

**Interfaces:**
- Consumes: 当前单租户工程。
- Produces: 可恢复的 Git 基线提交和统一的文本换行规则。

- [ ] **Step 1: 验证待处理路径**

Run:

```powershell
$root = (Resolve-Path 'E:\yanglao').Path
if ($root -ne 'E:\yanglao') { throw "Unexpected root: $root" }
Get-ChildItem -Force $root | Select-Object Name,Length,LastWriteTime
```

Expected: 输出 `backend`、`admin`、`miniprogram`、`docs`、`scripts`、`README.md`，且根路径严格为 `E:\yanglao`。

- [ ] **Step 2: 运行改造前基线**

Run:

```powershell
Set-Location 'E:\yanglao\backend'
mvn test
Set-Location 'E:\yanglao\admin'
npm run build
```

Expected: 后端显示 `Tests run: 22, Failures: 0, Errors: 0`；前端 TypeScript 检查和 Vite 构建成功。

- [ ] **Step 3: 获取 Git 初始化批准后建立基线**

只有在人工明确同意初始化 Git 后执行：

```powershell
Set-Location 'E:\yanglao'
git init -b main
git add .
git commit -m "chore: capture single-tenant baseline"
```

Expected: 生成首个基线提交；`git status --short` 为空。若 Git 身份未配置，停止执行并让仓库所有者配置 `user.name` 和 `user.email`。

- [ ] **Step 4: 写 `.gitattributes`**

```text
* text=auto
*.java text eol=lf
*.xml text eol=lf
*.md text eol=lf
*.yml text eol=lf
*.yaml text eol=lf
*.ts text eol=lf
*.tsx text eol=lf
*.js text eol=lf
*.json text eol=lf
*.sql text eol=lf
*.ps1 text eol=crlf
*.sh text eol=lf
*.png binary
*.jpg binary
*.jpeg binary
*.gif binary
*.jar binary
```

- [ ] **Step 5: 追加构建产物忽略规则**

在 `.gitignore` 末尾追加：

```gitignore
# Maven reactor
backend/**/target/
backend/.mvn/
backend/mvnw
backend/mvnw.cmd

# Node workspaces
admin-platform/node_modules/
admin-platform/dist/
admin-tenant/node_modules/
admin-tenant/dist/
brand-site/node_modules/
brand-site/dist/

# Runtime and secrets
.env
.env.*
!.env.example
*.pem
*.p12
*.jks
```

- [ ] **Step 6: 提交工程卫生配置**

```powershell
Set-Location 'E:\yanglao'
git add .gitattributes .gitignore
git commit -m "chore: add repository hygiene rules"
```

Expected: 提交成功，工作区干净。

## Task 1: 建立 Maven 父工程并迁移现有租户应用

**Files:**
- Create: `E:\yanglao\scripts\test-backend-structure.ps1`
- Create: `E:\yanglao\backend\pom.xml`
- Move: `E:\yanglao\backend\src` to `E:\yanglao\backend\apps\tenant-app\src`
- Move: `E:\yanglao\backend\pom.xml` content to `E:\yanglao\backend\apps\tenant-app\pom.xml`
- Create: shared、core、module、platform 子模块的 `pom.xml`

**Interfaces:**
- Consumes: 现有 `com.smartelder` 单应用源码和 22 个测试。
- Produces: `elderly-care-backend` 聚合父工程、`tenant-app`、`platform-app` 和全部模块骨架。

- [ ] **Step 1: 写结构检查并确认失败**

创建 `scripts/test-backend-structure.ps1`：

```powershell
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$required = @(
    'backend/pom.xml',
    'backend/apps/platform-app/pom.xml',
    'backend/apps/tenant-app/pom.xml',
    'backend/libs/care-common/pom.xml',
    'backend/libs/care-security/pom.xml',
    'backend/libs/care-core-api/pom.xml',
    'backend/libs/care-business-api/pom.xml',
    'backend/libs/care-module-api/pom.xml',
    'backend/core/care-core/pom.xml',
    'backend/core/care-business/pom.xml',
    'backend/modules/institution/institution-api/pom.xml',
    'backend/modules/institution/institution-impl/pom.xml',
    'backend/modules/community/community-api/pom.xml',
    'backend/modules/community/community-impl/pom.xml',
    'backend/modules/membership/membership-api/pom.xml',
    'backend/modules/membership/membership-impl/pom.xml',
    'backend/platform/platform-core/pom.xml',
    'backend/platform/platform-licensing/pom.xml',
    'backend/tests/architecture-tests/pom.xml'
)
$missing = $required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $root $_)) }
if ($missing.Count -gt 0) {
    $missing | ForEach-Object { Write-Error "Missing: $_" }
    exit 1
}
Write-Host 'Backend structure is valid.'
```

Run:

```powershell
Set-Location 'E:\yanglao'
.\scripts\test-backend-structure.ps1
```

Expected: 失败并列出缺失模块。
- [ ] **Step 2: 校验并移动现有后端路径**

```powershell
$root = (Resolve-Path 'E:\yanglao').Path
$backend = Join-Path $root 'backend'
$source = Join-Path $backend 'src'
$destinationRoot = Join-Path $backend 'apps\tenant-app'
if (-not $source.StartsWith($backend, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe source path' }
if (-not $destinationRoot.StartsWith($backend, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe destination path' }
New-Item -ItemType Directory -Force -Path $destinationRoot | Out-Null
Move-Item -LiteralPath $source -Destination (Join-Path $destinationRoot 'src')
```

Expected: 原 `backend/src` 不存在，`backend/apps/tenant-app/src` 存在。

- [ ] **Step 3: 创建租户应用 POM**

先保留旧 `backend/pom.xml` 内容作为依赖参考，再创建 `backend/apps/tenant-app/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.smartelder</groupId>
        <artifactId>elderly-care-backend</artifactId>
        <version>2.0.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>tenant-app</artifactId>
    <name>tenant-app</name>

    <dependencies>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-security</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-core-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-business-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-module-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-business</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>institution-impl</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>community-impl</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>membership-impl</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <mainClass>com.smartelder.ElderlyCareApplication</mainClass>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```
- [ ] **Step 4: 创建父工程 POM**

`backend/pom.xml` 的完整内容：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.smartelder</groupId>
    <artifactId>elderly-care-backend</artifactId>
    <version>2.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>elderly-care-backend</name>

    <modules>
        <module>libs/care-common</module>
        <module>libs/care-security</module>
        <module>libs/care-core-api</module>
        <module>libs/care-business-api</module>
        <module>libs/care-module-api</module>
        <module>core/care-core</module>
        <module>core/care-business</module>
        <module>modules/institution/institution-api</module>
        <module>modules/institution/institution-impl</module>
        <module>modules/community/community-api</module>
        <module>modules/community/community-impl</module>
        <module>modules/membership/membership-api</module>
        <module>modules/membership/membership-impl</module>
        <module>platform/platform-core</module>
        <module>platform/platform-licensing</module>
        <module>apps/platform-app</module>
        <module>apps/tenant-app</module>
        <module>tests/architecture-tests</module>
    </modules>

    <properties>
        <java.version>17</java.version>
        <mybatis-plus.version>3.5.7</mybatis-plus.version>
        <jjwt.version>0.12.6</jjwt.version>
        <springdoc.version>2.6.0</springdoc.version>
        <archunit.version>1.3.0</archunit.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-api</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-impl</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-jackson</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>${springdoc.version}</version>
            </dependency>
            <dependency>
                <groupId>com.tngtech.archunit</groupId>
                <artifactId>archunit-junit5</artifactId>
                <version>${archunit.version}</version>
                <scope>test</scope>
            </dependency>

            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>care-common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>care-security</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>care-core-api</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>care-business-api</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>care-module-api</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>care-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>care-business</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>institution-api</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>institution-impl</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>community-api</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>community-impl</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>membership-api</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>membership-impl</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>platform-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.smartelder</groupId>
                <artifactId>platform-licensing</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```
- [ ] **Step 5: 生成 16 个叶子模块 POM**

Run:

```powershell
$root = 'E:\yanglao\backend'
$modules = @(
    @{ Path='libs/care-common'; Artifact='care-common'; Description='Common API response and trace primitives' },
    @{ Path='libs/care-security'; Artifact='care-security'; Description='Shared authentication contracts' },
    @{ Path='libs/care-core-api'; Artifact='care-core-api'; Description='Core public APIs' },
    @{ Path='libs/care-business-api'; Artifact='care-business-api'; Description='Business public APIs' },
    @{ Path='libs/care-module-api'; Artifact='care-module-api'; Description='Module descriptor contracts' },
    @{ Path='core/care-core'; Artifact='care-core'; Description='Core implementation' },
    @{ Path='core/care-business'; Artifact='care-business'; Description='Business implementation' },
    @{ Path='modules/institution/institution-api'; Artifact='institution-api'; Description='Institution module API' },
    @{ Path='modules/institution/institution-impl'; Artifact='institution-impl'; Description='Institution module implementation' },
    @{ Path='modules/community/community-api'; Artifact='community-api'; Description='Community module API' },
    @{ Path='modules/community/community-impl'; Artifact='community-impl'; Description='Community module implementation' },
    @{ Path='modules/membership/membership-api'; Artifact='membership-api'; Description='Membership module API' },
    @{ Path='modules/membership/membership-impl'; Artifact='membership-impl'; Description='Membership module implementation' },
    @{ Path='platform/platform-core'; Artifact='platform-core'; Description='Platform control implementation' },
    @{ Path='platform/platform-licensing'; Artifact='platform-licensing'; Description='Platform licensing implementation' },
    @{ Path='tests/architecture-tests'; Artifact='architecture-tests'; Description='Cross-module architecture tests' }
)
foreach ($module in $modules) {
    $dir = Join-Path $root $module.Path
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $relative = if ($module.Path.StartsWith('modules/')) { '../../../pom.xml' } else { '../../pom.xml' }
    $xml = @"
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.smartelder</groupId>
        <artifactId>elderly-care-backend</artifactId>
        <version>2.0.0-SNAPSHOT</version>
        <relativePath>$relative</relativePath>
    </parent>
    <artifactId>$($module.Artifact)</artifactId>
    <name>$($module.Artifact)</name>
    <description>$($module.Description)</description>
</project>
"@
    [System.IO.File]::WriteAllText((Join-Path $dir 'pom.xml'), $xml, (New-Object System.Text.UTF8Encoding($false)))
}
```

Expected: 16 个叶子模块 POM 写入；模块目录结构完整。

- [ ] **Step 6: 运行结构检查和租户回归**

```powershell
Set-Location 'E:\yanglao'
.\scripts\test-backend-structure.ps1
mvn -f backend/pom.xml -pl apps/tenant-app -am test
```

Expected: 结构检查输出 `Backend structure is valid.`；租户应用仍显示 `Tests run: 22, Failures: 0, Errors: 0`。

- [ ] **Step 7: 提交多模块基线**

```powershell
Set-Location 'E:\yanglao'
git add scripts/test-backend-structure.ps1 backend
git commit -m "refactor: create backend multi-module baseline"
```

Expected: 提交成功，现有业务代码行为未改变。
## Task 2: 实现统一响应和稳定错误码

**Files:**
- Modify: `E:\yanglao\backend\libs\care-common\pom.xml`
- Create: `E:\yanglao\backend\libs\care-common\src\main\java\com\smartelder\common\ErrorCode.java`
- Create: `E:\yanglao\backend\libs\care-common\src\main\java\com\smartelder\common\ApiResponse.java`
- Create: `E:\yanglao\backend\libs\care-common\src\test\java\com\smartelder\common\ApiResponseTest.java`

**Interfaces:**
- Consumes: Java 17 标准库和 JUnit 5。
- Produces: `ErrorCode` 枚举、`ApiResponse.success(T,String,Clock)`、`ApiResponse.error(ErrorCode,String,String,Clock)`。

- [ ] **Step 1: 给 `care-common` 增加测试依赖**

在 `backend/libs/care-common/pom.xml` 的 `</project>` 前加入：

```xml
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

- [ ] **Step 2: 写失败测试**

`ApiResponseTest.java`：

```java
package com.smartelder.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void successUsesUtcTimestampAndStableCode() {
        ApiResponse<String> response = ApiResponse.success("ok", "tr_test_123", clock);

        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.message()).isEqualTo("成功");
        assertThat(response.data()).isEqualTo("ok");
        assertThat(response.traceId()).isEqualTo("tr_test_123");
        assertThat(response.timestamp().toString()).isEqualTo("2026-10-30T00:00Z");
    }

    @Test
    void errorUsesDeclaredCodeAndCustomMessage() {
        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.MODULE_NOT_ASSIGNED,
                "该门店未开通机构康养模块",
                "tr_test_456",
                clock
        );

        assertThat(response.code()).isEqualTo("MODULE_NOT_ASSIGNED");
        assertThat(response.message()).isEqualTo("该门店未开通机构康养模块");
        assertThat(response.data()).isNull();
        assertThat(response.traceId()).isEqualTo("tr_test_456");
    }
}
```

- [ ] **Step 3: 运行并确认失败**

```powershell
mvn -f 'E:\yanglao\backend\pom.xml' -pl libs/care-common -am test
```

Expected: 编译失败，提示 `ApiResponse` 和 `ErrorCode` 不存在或 `MODULE_NOT_ASSIGNED` 不存在。

- [ ] **Step 4: 实现稳定错误码**

`ErrorCode.java`：

```java
package com.smartelder.common;

public enum ErrorCode {
    OK("OK", "成功", 200),
    INVALID_REQUEST("INVALID_REQUEST", "请求参数错误", 400),
    UNAUTHORIZED("UNAUTHORIZED", "未登录或登录已失效", 401),
    FORBIDDEN("FORBIDDEN", "无权执行该操作", 403),
    NOT_FOUND("NOT_FOUND", "资源不存在", 404),
    CONFLICT("CONFLICT", "数据已被其他操作修改", 409),
    BUSINESS_RULE_VIOLATION("BUSINESS_RULE_VIOLATION", "业务规则校验失败", 422),
    RATE_LIMITED("RATE_LIMITED", "请求过于频繁", 429),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务内部错误", 500),
    DEPENDENCY_UNAVAILABLE("DEPENDENCY_UNAVAILABLE", "依赖服务不可用", 503),
    MODULE_NOT_AUTHORIZED("MODULE_NOT_AUTHORIZED", "企业未获得该模块授权", 403),
    MODULE_NOT_ASSIGNED("MODULE_NOT_ASSIGNED", "该门店未开通该模块", 403),
    MODULE_DEPENDENCY_MISSING("MODULE_DEPENDENCY_MISSING", "缺少正式授权的依赖模块", 422);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    ErrorCode(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
```

- [ ] **Step 5: 实现统一响应**

`ApiResponse.java`：

```java
package com.smartelder.common;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId,
        OffsetDateTime timestamp
) {

    public static <T> ApiResponse<T> success(T data, String traceId, Clock clock) {
        return new ApiResponse<>(
                ErrorCode.OK.code(),
                ErrorCode.OK.defaultMessage(),
                data,
                traceId,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        );
    }

    public static <T> ApiResponse<T> error(
            ErrorCode errorCode,
            String message,
            String traceId,
            Clock clock
    ) {
        String resolvedMessage = message == null || message.isBlank()
                ? errorCode.defaultMessage()
                : message;
        return new ApiResponse<>(
                errorCode.code(),
                resolvedMessage,
                null,
                traceId,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        );
    }
}
```

- [ ] **Step 6: 运行测试并提交**

```powershell
mvn -f 'E:\yanglao\backend\pom.xml' -pl libs/care-common -am test
Set-Location 'E:\yanglao'
git add backend/libs/care-common
git commit -m "feat: add shared API response contract"
```

Expected: `ApiResponseTest` 两项通过，提交成功。
## Task 3: 增加请求 TraceId 过滤器

**Files:**
- Modify: `E:\yanglao\backend\libs\care-common\pom.xml`
- Create: `E:\yanglao\backend\libs\care-common\src\main\java\com\smartelder\common\TraceIdFilter.java`
- Create: `E:\yanglao\backend\libs\care-common\src\test\java\com\smartelder\common\TraceIdFilterTest.java`

**Interfaces:**
- Consumes: `jakarta.servlet.FilterChain`、`HttpServletRequest`、`HttpServletResponse`。
- Produces: 请求属性 `traceId`、响应头 `X-Trace-Id`、常量 `TraceIdFilter.ATTRIBUTE_NAME` 和 `TraceIdFilter.HEADER_NAME`。

- [ ] **Step 1: 增加 Web 依赖**

在 `backend/libs/care-common/pom.xml` 的 `<dependencies>` 中加入：

```xml
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
        </dependency>
        <dependency>
            <groupId>jakarta.servlet</groupId>
            <artifactId>jakarta.servlet-api</artifactId>
            <scope>provided</scope>
        </dependency>
```

- [ ] **Step 2: 写失败测试**

`TraceIdFilterTest.java`：

```java
package com.smartelder.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void preservesSafeIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER_NAME, "tr_client_123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)).isEqualTo("tr_client_123");
        assertThat(response.getHeader(TraceIdFilter.HEADER_NAME)).isEqualTo("tr_client_123");
        verify(chain).doFilter(request, response);
    }

    @Test
    void replacesUnsafeIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER_NAME, "../../bad trace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String traceId = (String) request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        assertThat(traceId).startsWith("tr_");
        assertThat(traceId).matches("[A-Za-z0-9_-]{8,64}");
        assertThat(response.getHeader(TraceIdFilter.HEADER_NAME)).isEqualTo(traceId);
    }
}
```

- [ ] **Step 3: 运行并确认失败**

```powershell
mvn -f 'E:\yanglao\backend\pom.xml' -pl libs/care-common -am test
```

Expected: 编译失败，提示 `TraceIdFilter` 不存在。

- [ ] **Step 4: 实现过滤器**

`TraceIdFilter.java`：

```java
package com.smartelder.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE_NAME = "traceId";
    public static final String HEADER_NAME = "X-Trace-Id";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_NAME);
        String traceId = incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()
                ? incoming
                : "tr_" + UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTRIBUTE_NAME, traceId);
        response.setHeader(HEADER_NAME, traceId);
        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 5: 运行测试并提交**

```powershell
mvn -f 'E:\yanglao\backend\pom.xml' -pl libs/care-common -am test
Set-Location 'E:\yanglao'
git add backend/libs/care-common
git commit -m "feat: add request trace id filter"
```

Expected: 公共模块全部测试通过。
## Task 4: 创建平台应用和健康接口

**Files:**
- Create: `E:\yanglao\backend\apps\platform-app\pom.xml`
- Create: `E:\yanglao\backend\apps\platform-app\src\main\java\com\smartelder\platform\PlatformApplication.java`
- Create: `E:\yanglao\backend\apps\platform-app\src\main\java\com\smartelder\platform\PlatformConfiguration.java`
- Create: `E:\yanglao\backend\apps\platform-app\src\main\java\com\smartelder\platform\api\PlatformHealthController.java`
- Create: `E:\yanglao\backend\apps\platform-app\src\main\resources\application.yml`
- Create: `E:\yanglao\backend\apps\platform-app\src\test\java\com\smartelder\platform\api\PlatformHealthControllerTest.java`

**Interfaces:**
- Consumes: `ApiResponse.success(T,String,Clock)`、`TraceIdFilter.ATTRIBUTE_NAME`。
- Produces: `GET /api/platform/v1/health`，返回 `code=OK` 和 `data.status=UP`。

- [ ] **Step 1: 写平台 POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.smartelder</groupId>
        <artifactId>elderly-care-backend</artifactId>
        <version>2.0.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>platform-app</artifactId>
    <name>platform-app</name>

    <dependencies>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>platform-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>platform-licensing</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <mainClass>com.smartelder.platform.PlatformApplication</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 写失败接口测试**

`PlatformHealthControllerTest.java`：

```java
package com.smartelder.platform.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartelder.platform.PlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class PlatformHealthControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthReturnsTraceableSuccessEnvelope() throws Exception {
        mvc.perform(get("/api/platform/v1/health")
                        .header("X-Trace-Id", "tr_platform_health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "tr_platform_health"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.traceId").value("tr_platform_health"))
                .andExpect(jsonPath("$.timestamp").isString());
    }
}
```

- [ ] **Step 3: 运行并确认失败**

```powershell
mvn -f 'E:\yanglao\backend\pom.xml' -pl apps/platform-app -am test
```

Expected: 测试编译失败，提示 `PlatformApplication` 或健康接口类不存在。

- [ ] **Step 4: 实现平台入口与 Clock**

`PlatformApplication.java`：

```java
package com.smartelder.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.smartelder")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
```

`PlatformConfiguration.java`：

```java
package com.smartelder.platform;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 5: 实现健康接口**

`PlatformHealthController.java`：

```java
package com.smartelder.platform.api;

import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import java.time.Clock;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/v1")
public class PlatformHealthController {

    private final Clock clock;

    public PlatformHealthController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health(
            @RequestAttribute(TraceIdFilter.ATTRIBUTE_NAME) String traceId
    ) {
        return ApiResponse.success(Map.of("status", "UP"), traceId, clock);
    }
}
```

`application.yml`：

```yaml
server:
  port: 8081

spring:
  application:
    name: platform-app

logging:
  level:
    com.smartelder: INFO
```

- [ ] **Step 6: 运行测试并提交**

```powershell
mvn -f 'E:\yanglao\backend\pom.xml' -pl apps/platform-app -am test
Set-Location 'E:\yanglao'
git add backend/apps/platform-app
git commit -m "feat: add platform application health endpoint"
```

Expected: 平台健康接口测试通过。
## Task 5: 定义模块描述和依赖契约

**Files:**
- Modify: `E:\yanglao\backend\libs\care-module-api\pom.xml`
- Create: `E:\yanglao\backend\libs\care-module-api\src\main\java\com\smartelder\module\api\ModuleDependency.java`
- Create: `E:\yanglao\backend\libs\care-module-api\src\main\java\com\smartelder\module\api\ModuleDescriptor.java`
- Create: `E:\yanglao\backend\libs\care-module-api\src\test\java\com\smartelder\module\api\ModuleDescriptorTest.java`

**Interfaces:**
- Consumes: Java 17 record、List、Set。
- Produces: `ModuleDependency(String moduleCode,String minimumVersion)` 和 `ModuleDescriptor(String code,String name,String version,int menuOrder,List<ModuleDependency>,Set<String>)`。

- [ ] **Step 1: 增加测试依赖并写失败测试**

在 `care-module-api/pom.xml` 中加入：

```xml
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

`ModuleDescriptorTest.java`：

```java
package com.smartelder.module.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleDescriptorTest {

    @Test
    void createsImmutableDescriptor() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                "institution",
                "机构康养",
                "1.0.0",
                20,
                List.of(new ModuleDependency("membership", "1.0.0")),
                Set.of("institution:bed:read", "institution:bed:write")
        );

        assertThat(descriptor.code()).isEqualTo("institution");
        assertThat(descriptor.menuOrder()).isEqualTo(20);
        assertThat(descriptor.permissionCodes()).contains("institution:bed:read");
        assertThatThrownBy(() -> descriptor.dependencies().add(
                new ModuleDependency("community", "1.0.0")
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidCodeAndMenuOrder() {
        assertThatThrownBy(() -> new ModuleDescriptor(
                "Institution Module",
                "机构康养",
                "1.0.0",
                20,
                List.of(),
                Set.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");

        assertThatThrownBy(() -> new ModuleDescriptor(
                "institution",
                "机构康养",
                "1.0.0",
                0,
                List.of(),
                Set.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("menuOrder");
    }

    @Test
    void rejectsSelfDependencyAndInvalidPermissionCode() {
        assertThatThrownBy(() -> new ModuleDescriptor(
                "institution",
                "机构康养",
                "1.0.0",
                20,
                List.of(new ModuleDependency("institution", "1.0.0")),
                Set.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self");

        assertThatThrownBy(() -> new ModuleDescriptor(
                "institution",
                "机构康养",
                "1.0.0",
                20,
                List.of(),
                Set.of("bad permission")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");
    }
}
```

- [ ] **Step 2: 运行并确认失败**

```powershell
mvn -f 'E:\yanglao\backend\pom.xml' -pl libs/care-module-api -am test
```

Expected: 编译失败，提示模块契约类不存在。

- [ ] **Step 3: 实现依赖记录**

`ModuleDependency.java`：

```java
package com.smartelder.module.api;

import java.util.Objects;
import java.util.regex.Pattern;

public record ModuleDependency(String moduleCode, String minimumVersion) {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,31}");

    public ModuleDependency {
        Objects.requireNonNull(moduleCode, "moduleCode must not be null");
        Objects.requireNonNull(minimumVersion, "minimumVersion must not be null");
        if (!CODE.matcher(moduleCode).matches()) {
            throw new IllegalArgumentException("Invalid module dependency code: " + moduleCode);
        }
        if (minimumVersion.isBlank()) {
            throw new IllegalArgumentException("minimumVersion must not be blank");
        }
    }
}
```

- [ ] **Step 4: 实现模块描述契约**

`ModuleDescriptor.java`：

```java
package com.smartelder.module.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record ModuleDescriptor(
        String code,
        String name,
        String version,
        int menuOrder,
        List<ModuleDependency> dependencies,
        Set<String> permissionCodes
) {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Pattern PERMISSION = Pattern.compile("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*");

    public ModuleDescriptor {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(dependencies, "dependencies must not be null");
        Objects.requireNonNull(permissionCodes, "permissionCodes must not be null");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid module code: " + code);
        }
        if (name.isBlank() || version.isBlank()) {
            throw new IllegalArgumentException("name and version must not be blank");
        }
        if (menuOrder <= 0) {
            throw new IllegalArgumentException("menuOrder must be positive");
        }
        if (dependencies.stream().anyMatch(dependency -> dependency.moduleCode().equals(code))) {
            throw new IllegalArgumentException("A module cannot depend on itself");
        }
        if (permissionCodes.stream().anyMatch(permission -> !PERMISSION.matcher(permission).matches())) {
            throw new IllegalArgumentException("Invalid permission code");
        }
        dependencies = List.copyOf(dependencies);
        permissionCodes = Set.copyOf(permissionCodes);
    }
}
```

- [ ] **Step 5: 运行测试并提交**

```powershell
mvn -f 'E:\yanglao\backend\pom.xml' -pl libs/care-module-api -am test
Set-Location 'E:\yanglao'
git add backend/libs/care-module-api
git commit -m "feat: define module descriptor contract"
```

Expected: 模块契约三项测试通过。
## Task 6: 增加跨模块架构边界测试

**Files:**
- Modify: `E:\yanglao\backend\tests\architecture-tests\pom.xml`
- Create: `E:\yanglao\backend\tests\architecture-tests\src\test\java\com\smartelder\architecture\ArchitectureBoundaryTest.java`

**Interfaces:**
- Consumes: 已编译的 `com.smartelder` 类。
- Produces: 平台端与企业业务模块、API 与实现包之间的自动边界检查。

- [ ] **Step 1: 写架构测试 POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.smartelder</groupId>
        <artifactId>elderly-care-backend</artifactId>
        <version>2.0.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>architecture-tests</artifactId>
    <name>architecture-tests</name>

    <dependencies>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>platform-app</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>tenant-app</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-core-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-business-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>care-module-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>institution-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>community-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>membership-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>platform-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.smartelder</groupId>
            <artifactId>platform-licensing</artifactId>
        </dependency>
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 写边界规则**

`ArchitectureBoundaryTest.java`：

```java
package com.smartelder.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "com.smartelder")
class ArchitectureBoundaryTest {

    @ArchTest
    static final ArchRule platform_does_not_depend_on_tenant_business = noClasses()
            .that().resideInAPackage("com.smartelder.platform..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.smartelder.core..",
                    "com.smartelder.business..",
                    "com.smartelder.module.."
            );

    @ArchTest
    static final ArchRule api_does_not_depend_on_internal_implementations = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..internal..");

    @Test
    void importedClassModelContainsPlatformAndSharedContracts() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.smartelder");
        classes.contain("com.smartelder.platform.PlatformApplication");
        classes.contain("com.smartelder.common.ApiResponse");
        classes.contain("com.smartelder.module.api.ModuleDescriptor");
    }
}
```

- [ ] **Step 3: 运行架构测试**

```powershell
mvn -f 'E:\yanglao\backend\pom.xml' -pl tests/architecture-tests -am test
```

Expected: 架构测试通过，三个核心类均可由 ArchUnit 导入。

- [ ] **Step 4: 提交架构测试**

```powershell
Set-Location 'E:\yanglao'
git add backend/tests/architecture-tests
git commit -m "test: enforce backend architecture boundaries"
```

Expected: 提交成功。
## Task 7: 建立统一验证脚本

**Files:**
- Create: `E:\yanglao\scripts\verify-backend.ps1`
- Create: `E:\yanglao\scripts\verify-all.ps1`

**Interfaces:**
- Consumes: `backend/pom.xml`、`admin/package.json`、`scripts/test-backend-structure.ps1`。
- Produces: 开发者和 CI 可重复执行的单一验证入口。

- [ ] **Step 1: 写后端验证脚本**

`verify-backend.ps1`：

```powershell
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

& (Join-Path $PSScriptRoot 'test-backend-structure.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Backend structure check failed' }

mvn -f (Join-Path $root 'backend/pom.xml') clean test
if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' }

Write-Host 'Backend verification passed.'
```

- [ ] **Step 2: 写全仓验证脚本**

`verify-all.ps1`：

```powershell
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

& (Join-Path $PSScriptRoot 'verify-backend.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' }

Push-Location (Join-Path $root 'admin')
try {
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Admin build failed' }
} finally {
    Pop-Location
}

Write-Host 'All current checks passed.'
```

- [ ] **Step 3: 运行验证**

```powershell
Set-Location 'E:\yanglao'
.\scripts\verify-all.ps1
```

Expected: 后端全仓测试通过，原有租户测试仍为 22 项通过，管理端 TypeScript 和 Vite 构建通过。

- [ ] **Step 4: 提交验证入口**

```powershell
Set-Location 'E:\yanglao'
git add scripts/verify-backend.ps1 scripts/verify-all.ps1
git commit -m "chore: add repeatable verification scripts"
```

Expected: 提交成功。

## Task 8: 阶段 0 验收和交付说明

**Files:**
- Create: `E:\yanglao\docs\阶段0验收记录.md`
- Modify: `E:\yanglao\README.md`

**Interfaces:**
- Consumes: 阶段 0 全部构建和测试结果。
- Produces: 可复查的验收记录、启动命令和下一阶段入口。

- [ ] **Step 1: 写验收记录**

`docs/阶段0验收记录.md`：

```markdown
# 阶段 0 验收记录

- 日期：2026-10-30
- 设计依据：`docs/superpowers/specs/2026-10-29-modular-multi-tenant-saas-design.md`
- 实施计划：`docs/superpowers/plans/2026-10-30-phase-0-backend-foundation.md`

## 验收结果

| 项目 | 命令 | 预期 |
| --- | --- | --- |
| 模块结构 | `.\scripts\test-backend-structure.ps1` | 输出 `Backend structure is valid.` |
| 后端全仓 | `mvn -f backend/pom.xml clean test` | 全部测试通过 |
| 现有租户回归 | `mvn -f backend/pom.xml -pl apps/tenant-app -am test` | 22 项通过 |
| 平台健康接口 | `mvn -f backend/pom.xml -pl apps/platform-app -am test` | 健康接口测试通过 |
| 管理端 | `cd admin; npm run build` | TypeScript 与 Vite 构建通过 |
| 全仓 | `.\scripts\verify-all.ps1` | 输出 `All current checks passed.` |

## 启动命令

```powershell
mvn -f E:\yanglao\backend\pom.xml -pl apps/platform-app -am spring-boot:run
mvn -f E:\yanglao\backend\pom.xml -pl apps/tenant-app -am spring-boot:run -Dspring-boot.run.profiles=dev
```

## 未纳入阶段 0

- 平台账号、企业、授权和销售订单业务实现。
- 企业总部、门店和权限体系。
- 前端拆分为 `admin-platform` 和 `admin-tenant`。
- Flyway 生产迁移启用。
- 真实支付、微信和短信适配器。
```

- [ ] **Step 2: 更新 README 的新旧结构说明**

在 `README.md` 的目录结构章节前加入：

```markdown
> 工程正在迁移为模块化 SaaS 架构。现有单租户程序已迁入 `backend/apps/tenant-app`，
> 平台控制面位于 `backend/apps/platform-app`。迁移期间以
> `docs/superpowers/plans/2026-10-30-phase-0-backend-foundation.md` 为准。
```

- [ ] **Step 3: 执行最终验证**

```powershell
Set-Location 'E:\yanglao'
.\scripts\verify-all.ps1
mvn -f 'E:\yanglao\backend\pom.xml' -pl tests/architecture-tests -am test
```

Expected: 两条命令均成功；无编译错误、无测试失败。

- [ ] **Step 4: 检查工作区并提交**

```powershell
Set-Location 'E:\yanglao'
git status --short
git add README.md docs/阶段0验收记录.md
git commit -m "docs: record phase zero acceptance"
git status --short
```

Expected: 第一次 `git status --short` 只包含 README 和验收记录；最后为空。

## Plan Self-Review

- 规格覆盖：本计划覆盖设计文档的阶段 0：Maven 多模块、双应用、公共契约、模块骨架、架构边界、验证脚本和现有测试回归。
- 范围控制：平台业务表、企业业务表、前端拆分、Flyway 启用和真实支付不在本计划中，分别进入后续阶段计划。
- 类型一致性：`ApiResponse.success(T,String,Clock)`、`TraceIdFilter.ATTRIBUTE_NAME`、`ModuleDescriptor` 和 `ModuleDependency` 的命名在相关任务中保持一致。
- 迁移安全：现有源码先移动、后构建、再提交；每次提交前运行对应测试。
- 数据安全：本计划不修改现有 `schema.sql`、`data-demo.sql` 或生产配置。
