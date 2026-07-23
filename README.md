# PublishPlugin

`PublishPlugin` 用于给 Android Library 和 Gradle Plugin 模块生成 Maven publication，并提供统一的本地发布、远程发布入口。

当前远程发布统一入口是 `PublishLibraryRemoteTask`，默认发布到 GitHub Packages；GitHub Packages 和 Sonatype Central Portal 统一通过 `publishTarget` 选择。旧私服发布仍保留历史兼容入口。

本仓库需求分支、`pre_publish` 预发布和 `main` 合入规则见 [分支与发布工作流](doc/workflow.md)。

## 快速开始

### 1. Skills 配置

Skills 配置适合在 Codex 中交互式完成发布接入、发布目标选择、配置校验和问题修复。入口示例：

```text
使用 $publishplugin-one-click-publish，帮我为 :library 配置发布。
```

也可以指定目标，例如发布到 Central：

```text
使用 $publishplugin-one-click-publish，帮我为 :library 配置 Central Portal 发布。
```

Skill 会根据发布目标引导配置：

| 发布目标 | 配置重点 |
| --- | --- |
| 本地 Maven 发布 | 校验模块 `PublishInfo`，执行 `publishToMavenLocal` 或 `PublishLibraryLocalTask`。 |
| GitHub Packages 发布 | 配置 GitHub Packages 仓库、凭据来源和远程发布命令。 |
| Sonatype Central Portal 发布 | 配置 Central namespace、Central token、GPG signing、POM/SCM 元数据、GitHub Actions workflow。 |
| 旧自定义 Maven 仓库 | 配置旧私服字段和兼容发布模式。 |

### 2. 本地脚本配置

本地脚本配置用于不依赖 Codex 的终端执行。离线脚本封装的是一键发布配置流程，默认生成 GitHub Packages 发布 workflow；需要发布到 Central 时显式选择 `central` 或 `all`。

| 系统环境 | 支持状态 | 入口 |
| --- | --- | --- |
| macOS | 支持 | `scripts/configure-publish-offline.sh` |
| Linux | 暂不支持 | 使用 Gradle task 或手动配置。 |
| Windows | 暂不支持 | 使用 Gradle task 或手动配置。 |

先生成配置模板：

```bash
scripts/configure-publish-offline.sh :library --generate-only
```

然后在根目录 `local.properties` 中填写发布配置。敏感字段只用于写入 GitHub repository secrets，`local.properties` 必须保持 ignored/untracked。

完成配置后执行：

```bash
scripts/configure-publish-offline.sh :library --configure-only -- --stacktrace
```

如果配置文件已填好，也可以直接一键执行生成与配置：

```bash
scripts/configure-publish-offline.sh :library -- --stacktrace
```

等价 Gradle task：

```bash
./gradlew :library:generatePublishConfig
./gradlew :library:configurePublish
```

本地 Maven 发布不需要执行一键发布配置脚本，可直接运行：

```bash
./gradlew :library:publishToMavenLocal
```

GitHub Packages 是默认远程发布目标，配置仓库和凭据后执行：

```bash
GITHUB_ACTOR=<github-user> \
GITHUB_TOKEN=<token-with-package-write> \
./gradlew :library:PublishLibraryRemoteTask \
  -PgithubPackagesRepository=owner/repo \
  --stacktrace
```

### 3. 代码配置

#### 3.1 根工程引入插件

在根工程 `build.gradle.kts` 中加入插件依赖：

```kotlin
buildscript {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }

    dependencies {
        classpath("cn.entertech.android:publish:1.2.2")
    }
}
```

#### 3.2 发布 Android Library

在需要发布的 Android Library 模块中应用插件：

```kotlin
plugins {
    id("com.android.library")
    id("cn.entertech.publish")
}
```

配置最小 `PublishInfo`：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "base"
    version = "0.0.1"
}
```

发布到本机 Maven 仓库：

```bash
./gradlew :library:publishToMavenLocal
```

消费方启用 `mavenLocal()` 后，通过 Maven 坐标依赖本地发布产物：

```kotlin
dependencies {
    implementation("cn.entertech.android:base:0.0.1-local")
}
```

#### 3.3 发布 Gradle Plugin

Gradle Plugin 模块需要同时应用 `cn.entertech.publish` 和 `java-gradle-plugin`：

```kotlin
plugins {
    id("cn.entertech.publish")
    `java-gradle-plugin`
}
```

如果项目仍使用 Groovy `build.gradle`，也可以使用旧写法：

```groovy
apply plugin: 'cn.entertech.publish'
```

最小 `PublishInfo`：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-publish-plugin"
    version = "1.0.0"

    pluginId = "cn.entertech.demo"
    implementationClass = "cn.entertech.demo.DemoPlugin"
}
```

发布到本机 Maven 仓库：

```bash
./gradlew :demo-plugin:publishToMavenLocal
```

消费方启用 `mavenLocal()` 后，可通过 buildscript classpath 引入插件 artifact：

```kotlin
buildscript {
    dependencies {
        classpath("cn.entertech.android:demo-publish-plugin:1.0.0-local")
    }
}
```

然后在消费方模块中应用已发布的 Gradle 插件：

```kotlin
apply(plugin = "cn.entertech.demo")
```

#### 3.4 选择发布目标

`PublishLibraryRemoteTask` 是统一远程发布入口。默认远程发布目标是 GitHub Packages；Sonatype Central Portal 和旧自定义 Maven 仓库需要显式选择。

| 目标 | 关键配置 | 命令 |
| --- | --- | --- |
| 本地 Maven | `groupId`、`artifactId`、`version` | `./gradlew :library:publishToMavenLocal` |
| GitHub Packages | `githubPackagesRepository=owner/repo` 或 `githubPackagesUrl=...`，并提供 GitHub Packages 凭据 | `./gradlew :library:PublishLibraryRemoteTask` |
| Sonatype Central Portal | `publishTarget=central`，Central token，GPG signing，POM/SCM 元数据 | `./gradlew :library:PublishLibraryRemoteTask -PpublishTarget=central` |
| 旧自定义 Maven 仓库 | `remotePublishMode=customRepository`、`publishUrl`，按需配置用户名和密码 | `./gradlew :library:PublishLibraryRemoteTask -PremotePublishMode=customRepository` |

发布到 GitHub Packages：

```bash
GITHUB_ACTOR=<github-user> \
GITHUB_TOKEN=<token-with-package-write> \
./gradlew :library:PublishLibraryRemoteTask \
  -PgithubPackagesRepository=owner/repo \
  --stacktrace
```

发布到 Sonatype Central Portal：

```bash
CENTRAL_USERNAME=<central-token-username> \
CENTRAL_PASSWORD=<central-token-password> \
ORG_GRADLE_PROJECT_signingInMemoryKey=<gpg-private-key> \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=<gpg-password> \
./gradlew :library:PublishLibraryRemoteTask \
  -PpublishTarget=central \
  -PcentralNamespace=cn.entertech \
  --stacktrace
```

## 仓库内 demo

本仓库提供两个可运行示例：

| 模块 | 用途 | 覆盖内容 |
| --- | --- | --- |
| [`demo-lib`](demo-lib/build.gradle.kts) | Android Library 发布示例 | [`PublishInfo` 基础坐标、POM/Central 元数据、多 flavor release variant、`artifactIdForVariant`](demo-lib/build.gradle.kts)，以及动态 artifactId 辅助逻辑 [`DemoArtifactNames.kt`](demo-lib/src/main/java/cn/entertech/plugin/demo/lib/DemoArtifactNames.kt)。 |
| [`demo-plugin`](demo-plugin/build.gradle.kts) | Gradle Plugin 发布示例 | [`java-gradle-plugin`、`pluginId`、`implementationClass`](demo-plugin/build.gradle.kts)、Gradle 插件入口类 [`DemoPublishPlugin.java`](demo-plugin/src/main/java/cn/entertech/plugin/demo/plugin/DemoPublishPlugin.java)、本地 Maven 发布。 |

## 与 Vanniktech 方案对比

`com.vanniktech.maven.publish` 是成熟的通用 Maven 发布插件。它对 Maven Central、签名、sources/javadoc、KMP 等场景支持更完整，但直接替换当前插件会改变已有业务项目的 DSL、任务名和版本基线。以下对比基于当前 `cn.entertech.publish` 设计，以及 Vanniktech 官方文档中 2026-07-07 可见的 `0.37.0` 版本信息；后续版本要求以 [Vanniktech changelog](https://vanniktech.github.io/gradle-maven-publish-plugin/changelog/) 为准。

| 维度 | 当前自研 `cn.entertech.publish` | 直接使用 `com.vanniktech.maven.publish` | 判断 |
| --- | --- | --- | --- |
| 接入方式 | 业务项目继续配置 `PublishInfo { ... }` | 需要改为 `mavenPublishing { ... }` 或 Vanniktech 约定属性 | 自研对已有项目迁移成本更低 |
| 旧项目兼容 | 保留 `publishUrl`、`publishUserName`、`publishPassword`、`PublishLibraryLocalTask`、`PublishLibraryRemoteTask` | DSL、任务名、属性名都不同 | 自研更适合公司存量项目 |
| 版本基线 | 当前仓库 wrapper 为 Gradle 8.0；插件自身按 Java 8 target、AGP 4.2.0 compileOnly 维护兼容 | `0.37.0` 最低要求 JDK 17、Gradle 9.0.0、AGP 8.13.0、KGP 2.2.0 | 直接引入 Vanniktech 会带来明显升级压力 |
| Central Portal 支持 | 已封装 Central staging 上传和 manual upload；状态轮询、deployment validation 仍偏轻 | 内置 Central Portal 发布、自动发布、deployment validation 等能力 | Vanniktech 更成熟 |
| POM 元数据 | Entertech 默认 license/developer/SCM 规则内置，可由 `PublishInfo`、Gradle property、环境变量覆盖 | 使用通用 DSL 或 Gradle properties 配置 | 自研更贴公司默认值，Vanniktech 更标准 |
| GPG 签名 | 已支持 in-memory key，但签名属性解析和校验由本插件维护 | 内置 CI 友好的 signing 配置 | Vanniktech 维护成本更低 |
| sources/javadoc | 本地 debug 版本才附带 sources；Central 远程发布强制 sources/javadoc | 自动配置 sources/javadoc，可配合 Dokka | Vanniktech 更完整 |
| Android 多 variant | 只发布 release component，支持 `artifactIdForVariant` 和 `skipVariantIf` | 支持 Android variant 发布和过滤，但 DSL 不同 | 两者都能做，自研更贴现有业务规则 |
| Gradle Plugin 发布 | 支持 `java-gradle-plugin`，并复用公司坐标和 POM 规则 | 也支持 Gradle Plugin 发布 | 能力接近 |
| 旧私服发布 | 保留 `remotePublishMode = "customRepository"` 和旧私服字段 | 支持发布到任意 Maven repository，但配置方式不同 | 自研迁移成本更低 |
| CI 密钥管理 | 支持公司约定的 Central token、GPG key、SCM fallback 变量 | 支持标准 Gradle properties 和环境变量 | 都可用，Vanniktech 更通用 |
| 维护成本 | Central API、checksum、validation 行为变化需要自研跟进 | 社区插件持续维护这些细节 | Vanniktech 维护成本更低 |
| 可控性 | 可按公司规则定制任务名、默认值、校验和输出日志 | 受第三方插件 DSL 和版本策略约束 | 自研可控性更强 |
| 生态覆盖 | 主要覆盖 Android Library 和 Gradle Plugin | 覆盖 Android、Java、Kotlin Multiplatform、version catalog 等 | Vanniktech 覆盖面更广 |

推荐策略：已有业务项目继续使用 `cn.entertech.publish`，以保持 `PublishInfo` 和旧任务名兼容；新项目如果已经满足较新的 Gradle/AGP/Kotlin 基线，可以评估直接使用 Vanniktech。后续也可以在本插件内部增加可选 backend：外部 DSL 仍保持 `PublishInfo`，环境满足要求时内部委托 Vanniktech，否则继续走当前兼容实现。

## PublishInfo 配置速查

`PublishInfo` 描述要发布的 Maven 坐标、POM 元数据以及多 variant 规则。远程发布目标使用 `publishTarget` 管理。不同发布场景需要的字段不同：

| 场景 | 必填字段 | 额外要求 |
| --- | --- | --- |
| 本地 Maven 发布 | `groupId`、`artifactId`、`version` | 执行 `PublishLibraryLocalTask` 或 `publishToMavenLocal`。不要求 Central 凭据、签名和完整 POM 元数据。 |
| Android Library 远程发 Central | `groupId`、`artifactId`、`version` | `pomDescription` 默认使用通用 Maven 描述，`pomUrl` 优先从当前工程 `git remote origin` 推导，再使用 CI 环境兜底。非 `cn.entertech` namespace 必须覆盖 `centralNamespace`。`version` 不能包含 `debug`。 |
| Gradle Plugin 远程发 Central | Android Library 远程字段 + `pluginId`、`implementationClass` | `pluginId` 是用户使用 `plugins { id("...") }` 时看到的插件 ID。 |
| GitHub Packages 发布 | `groupId`、`artifactId`、`version` | 默认远程发布模式。配置 `githubPackagesRepository=owner/repo` 或 `githubPackagesUrl=https://maven.pkg.github.com/owner/repo`。凭据建议用 `GITHUB_ACTOR` / `GITHUB_TOKEN`，也兼容 `gpr.user` / `gpr.key`。 |
| 旧私服发布 | `groupId`、`artifactId`、`version`、`remotePublishMode = "customRepository"`、`publishUrl` | `publishUserName`、`publishPassword` 按仓库权限需要配置。 |

字段值解析优先级按用途分组：

- `groupId`、`artifactId`、`version` 直接来自 `PublishInfo`。
- Central、License、Developer、SCM 等仓库级字段支持命令行和本地通用配置覆盖：`Gradle property > 环境变量 > PublishInfo 显式配置 > local.properties 中 publish.* 非空值 > 默认/推导值`。
- `pomName`、`pomDescription`、`pomUrl` 属于组件 POM 信息，不从 `local.properties` 读取；未显式配置时分别使用 artifactId、组件类型默认描述、当前工程 git remote / CI 推导 URL。
- `publishUrl`、`publishUserName`、`publishPassword` 兼容 `local.properties`：`Gradle property > PublishInfo 字段 > local.properties`。
- GitHub Packages URL 可由 `githubPackagesRepository=owner/repo` 推导；凭据优先使用 Gradle property / 环境变量，再回退到 `PublishInfo` 和 `local.properties` 中的 GitHub Packages 专用字段或旧私服字段。
- Central token 不建议写进 `PublishInfo`。优先使用 `-PcentralUsername/-PcentralPassword` 或 `CENTRAL_USERNAME/CENTRAL_PASSWORD`。
- GPG signing 信息不是 `PublishInfo` 字段；发布运行时只能通过 Gradle property 或环境变量传入。一键发布配置时可先放在 ignored/untracked 的 `local.properties` 中，用于写入 GitHub repository secrets。

`local.properties` 中的旧私服字段值不要加引号：

```properties
publishUrl=https://repo.example.com/releases
publishUserName=username
publishPassword=password
```

## 一键发布配置

一键发布配置用于降低新仓库接入发布能力的初始配置成本，着重点是为目标模块生成可执行的发布 workflow。不同发布目标需要的配置不同：

- 本地 Maven 发布不需要执行一键发布配置脚本，配置 `PublishInfo` 后可直接运行本地发布任务。
- GitHub Packages 是默认远程发布模式，通常只需要配置 `PublishInfo` 和 GitHub Packages 凭据。
- Sonatype Central Portal 需要额外完成仓库级元数据、GitHub repository secrets 和签名密钥配置；这是显式选择 `central` 或 `all` 时才需要的配置。

因此，一键发布配置默认生成 GitHub Packages workflow。`publish.*` 是新的本地配置前缀；旧 `centralPublish.*` 只作为兼容读取保留。Central token/GPG 字段只在 `publishTarget=central` 或 `publishTarget=all` 时生效；`publishTarget=central` 会让 `PublishLibraryRemoteTask` 发布到 Central。

组件级信息继续放在目标模块 `PublishInfo` 中：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "1.0.0"

    pomName = "Demo Library"
    pomDescription = "Demo Android library"
    pomUrl = "https://github.com/Entertech/demo-lib"
}
```

仓库级配置和一次性 secret 输入放在根目录 `local.properties` 的 `publish.*` 字段中。不要在 `local.properties` 中声明 `groupId`、`artifactId`、`version`、`pluginId`、`implementationClass`、`pomName`、`pomDescription`、`pomUrl` 等组件字段。

### 配置入口

Codex Skill 方式：

```text
使用 $publishplugin-one-click-publish，帮我为 :library 配置发布。
```

macOS 离线脚本方式：

```bash
scripts/configure-publish-offline.sh :library --generate-only
scripts/configure-publish-offline.sh :library --configure-only -- --stacktrace
```

Gradle task 方式：

```bash
./gradlew :library:generatePublishConfig
./gradlew :library:configurePublish
```

### 推荐 Task 说明

插件会把发布相关入口注册到 Gradle 的 `customPlugin` task group 下。日常只需要使用下面这些推荐 task：

| Task | 作用 | 常见场景 |
| --- | --- | --- |
| `generatePublishConfig` | 生成或补全发布配置模板，默认写入根目录 `local.properties`，保留已有 `sdk.dir` / `ndk.dir` 和已有配置值。 | 首次接入发布、补齐缺失的 `publish.*` 配置。 |
| `configurePublish` | 校验当前模块 `PublishInfo`，确认配置文件未被 Git 跟踪，按配置写入 GitHub repository secrets，并可生成 GitHub Actions workflow。 | 配好 `local.properties` 后，一键完成远程发布前置配置。 |
| `PublishLibraryLocalTask` | 校验当前模块发布配置后，执行本模块的 `publishToMavenLocal`，发布到 `~/.m2/repository/`。 | 本地联调、验证 Maven 坐标和 POM 依赖。 |
| `PublishLibraryRemoteTask` | 远程发布统一入口。默认发布到 GitHub Packages，也可通过 `publishTarget=central` 发布到 Sonatype Central Portal；旧自定义 Maven 仓库保留兼容模式。 | 正式发布到远程 Maven 仓库。 |
| `rollbackPublishSecrets` | 读取同一份发布配置，删除配置过的 GitHub repository secrets，并可按参数删除插件生成的 workflow。 | 回收发布凭据、撤销一键配置产生的远程 secrets。 |

### 执行流程

1. 在目标模块配置 `PublishInfo` 的坐标和 POM 元数据。
2. 生成或更新根目录 `local.properties`：
   ```bash
   ./gradlew :library:generatePublishConfig
   ```
3. 填写发布目标和必要的仓库级字段。Android 项目已有的 `sdk.dir` / `ndk.dir` 会被保留。
4. 建议先设置 `publish.dryRun=true` 执行预演。dry run 只做解析、校验和摘要输出，不写 `.gitignore`、workflow、secrets 或 GPG key。
5. 确认配置后执行：
   ```bash
   ./gradlew :library:configurePublish
   ```

如果配置文件已存在且需要刷新注释，传入 `-PoverwritePublishConfig=true`。任务会刷新模板注释，并保留已有 `publish.*` value 和非 `publish.*` 行。

### local.properties 模板

```properties
# GitHub repository in owner/repo format. Blank tries gh repo view, then git remote origin.
publish.githubRepo=Entertech/demo-lib

# Dry-run switch.
# true: Print planned files and secret names only. Do not write files or call gh secret set.
# false/blank: Run normally.
publish.dryRun=false

# Generated workflow publish target.
# Blank/default: github_packages.
# Supported values: github_packages, central, all.
publish.publishTarget=github_packages

# GitHub Packages repository in owner/repo format. Blank uses the workflow caller repository.
publish.githubPackagesRepository=

# Optional full GitHub Packages Maven repository URL. Blank derives it from githubPackagesRepository.
publish.githubPackagesUrl=

# Sonatype Central namespace. Used when publishTarget is central or all. Blank uses plugin default cn.entertech.
publish.centralNamespace=cn.entertech

# Central deployment publishing type.
# user_managed: Upload and validate, then leave the deployment in Central Portal for manual Publish.
# automatic: Upload and validate, then Central tries to publish to Maven Central automatically.
publish.centralPublishingType=user_managed

# Central staging repository name. Blank uses plugin default CentralStaging.
publish.centralRepositoryName=

# Shared POM metadata defaults. Blank values use PublishInfo, local inference, or plugin defaults.
publish.pomInceptionYear=
publish.licenseName=
publish.licenseUrl=
publish.licenseDistribution=
publish.developerId=
publish.developerName=
publish.developerEmail=
publish.developerOrganization=
publish.developerOrganizationUrl=
publish.developerUrl=
publish.scmUrl=
publish.scmConnection=
publish.scmDeveloperConnection=

# Whether to configure GitHub repository secrets.
# true: Call gh secret set for Central token and GPG signing secrets when publishTarget is central or all.
# false/blank: Do not process GitHub secrets.
publish.githubSecrets=false

# Whether to overwrite existing repository secrets.
# true: Overwrite existing secrets.
# false/blank: Reuse existing secrets to avoid replacing CI credentials by mistake.
publish.overwriteGithubSecrets=false

# Central Portal User Token. Used only by configurePublish for central/all targets.
publish.mavenCentralUsername=central-token-username
publish.mavenCentralPassword=central-token-password

# GPG signing secret inputs. Used only by configurePublish for central/all targets.
publish.gpgKeyFile=/Users/example/secrets/gpg-private-key.asc
publish.signingPassword=gpg-private-key-password
publish.signingKeyId=

# Repository secret names. Blank values use the defaults shown in the comments below.
publish.mavenCentralUsernameSecret=
publish.mavenCentralPasswordSecret=
publish.gpgKeySecret=
publish.signingPasswordSecret=
publish.signingKeyIdSecret=

# Optional local GPG key generation.
publish.gpgGenerate=false
publish.gpgKeyType=
publish.gpgKeyLength=
publish.gpgKeyExpire=
publish.gpgName=
publish.gpgEmail=
publish.gpgComment=

# Whether to generate a GitHub Actions workflow.
publish.githubActions=true

# Generated workflow path and reusable workflow reference.
publish.workflowPath=
publish.workflowUses=Entertech/PublishPlugin/.github/workflows/publish.yml@main
```

模板注释保持 ASCII English，避免 Java `.properties` 和 IDE 默认编码造成中文注释乱码。

### 发布配置字段说明

| 字段 | 类别 | 默认/空值行为 | 说明 |
| --- | --- | --- | --- |
| `githubRepo` | GitHub 目标仓库 | 空值时尝试 `gh repo view`，再回退到 `git remote origin` | GitHub repository，格式为 `owner/repo`，用于写 secrets 和定位 workflow。 |
| `dryRun` | 执行控制 | `false` | `true` 时只打印计划执行内容，不写文件、不写 secrets、不生成 GPG key。 |
| `publishTarget` | GitHub Actions | `github_packages` | 生成 workflow 的发布目标，可选 `github_packages`、`central`、`all`。 |
| `githubPackagesRepository` | GitHub Packages | 调用 workflow 的仓库 | GitHub Packages 发布仓库，格式为 `owner/repo`。 |
| `githubPackagesUrl` | GitHub Packages | 由 `githubPackagesRepository` 推导 | GitHub Packages Maven repository 完整 URL。 |
| `centralNamespace` | Central 发布行为 | `cn.entertech` | Sonatype Central 已验证 namespace。`PublishInfo.groupId` 必须等于该值或位于该 namespace 下。 |
| `centralPublishingType` | Central 发布行为 | `user_managed` | `user_managed` 上传并校验后需要在 Portal 手动 Publish；`automatic` 上传校验后自动尝试发布。 |
| `centralRepositoryName` | Central 发布行为 | `CentralStaging` | Gradle Maven repository 名称，影响任务名，例如 `publishEnterPublishPublicationToCentralStagingRepository`。 |
| `pomInceptionYear` | 共享 POM 默认值 | 当前年份 | POM `<inceptionYear>`。只在需要覆盖默认年份时填写。 |
| `licenseName` | 共享 POM 默认值 | `The Apache License, Version 2.0` | POM license name。 |
| `licenseUrl` | 共享 POM 默认值 | `https://www.apache.org/licenses/LICENSE-2.0.txt` | POM license URL。 |
| `licenseDistribution` | 共享 POM 默认值 | `repo` | POM license distribution。 |
| `developerId` | 共享 POM 默认值 | `Entertech` | POM developer id。 |
| `developerName` | 共享 POM 默认值 | `Entertech` | POM developer name。 |
| `developerEmail` | 共享 POM 默认值 | `developer@entertech.cn` | POM developer email。 |
| `developerOrganization` | 共享 POM 默认值 | `Entertech` | POM developer organization。 |
| `developerOrganizationUrl` | 共享 POM 默认值 | `https://github.com/Entertech` | POM developer organization URL。 |
| `developerUrl` | 共享 POM 默认值 | `https://github.com/Entertech` | POM developer URL。 |
| `scmUrl` | 共享 POM 默认值 | 空值时从当前工程 `git remote origin`、GitHub Actions、GitLab CI 等推导 | POM SCM browser URL。 |
| `scmConnection` | 共享 POM 默认值 | 空值时由 `scmUrl` 推导 | POM read-only SCM connection。 |
| `scmDeveloperConnection` | 共享 POM 默认值 | 空值时由 `scmUrl` 推导 | POM developer SCM connection。 |
| `githubSecrets` | GitHub secrets | `false` | `true` 且 `publishTarget=central/all` 时使用本机 `gh` 写入 Central token 和 GPG signing repository secrets。 |
| `overwriteGithubSecrets` | GitHub secrets | `false` | `true` 时允许覆盖已有 repository secrets；默认复用已有 secrets，避免误替换 CI 凭据。 |
| `mavenCentralUsername` | 一次性 secret 输入 | 无默认值 | Central Portal User Token username，只用于写入 repository secret。 |
| `mavenCentralPassword` | 一次性 secret 输入 | 无默认值 | Central Portal User Token password，只用于写入 repository secret。 |
| `gpgKeyFile` | 一次性 secret 输入 | 无默认值 | GPG ASCII private key 文件路径。需要写入 GPG secret 时必填。 |
| `signingPassword` | 一次性 secret 输入 | 无默认值 | GPG private key password。需要写入 signing password secret 或生成新 key 时必填。 |
| `signingKeyId` | 一次性 secret 输入 | 空值表示运行时由 Gradle signing 从私钥推断 | 可选 GPG key id。通常可以留空。 |
| `mavenCentralUsernameSecret` | secret 名称覆盖 | `MAVEN_CENTRAL_USERNAME` | Central username repository secret 名称。 |
| `mavenCentralPasswordSecret` | secret 名称覆盖 | `MAVEN_CENTRAL_PASSWORD` | Central password repository secret 名称。 |
| `gpgKeySecret` | secret 名称覆盖 | `GPG_KEY_CONTENTS` | GPG private key repository secret 名称。 |
| `signingPasswordSecret` | secret 名称覆盖 | `SIGNING_PASSWORD` | GPG private key password repository secret 名称。 |
| `signingKeyIdSecret` | secret 名称覆盖 | `SIGNING_KEY_ID` | GPG key id repository secret 名称。 |
| `gpgGenerate` | GPG 生成 | `false` | `true` 时调用本机 `gpg` 生成新发布签名 key，并覆盖对应 GPG secrets。 |
| `gpgKeyType` | GPG 生成 | `RSA` | GPG key 类型。当前推荐 RSA。 |
| `gpgKeyLength` | GPG 生成 | `4096` | RSA key length。 |
| `gpgKeyExpire` | GPG 生成 | 无默认值 | GPG key 过期时间，例如 `1y`、`2y`、`0`。`0` 表示不过期。 |
| `gpgName` | GPG 生成 | 无默认值 | GPG uid name。生成新 key 时必填。 |
| `gpgEmail` | GPG 生成 | 无默认值 | GPG uid email。生成新 key 时必填。 |
| `gpgComment` | GPG 生成 | 空值 | GPG uid comment。 |
| `githubActions` | GitHub Actions | `false` | `true` 时生成或更新 GitHub Actions workflow。 |
| `workflowPath` | GitHub Actions | `.github/workflows/publish-<module>.yml` | 生成 workflow 的路径。 |
| `workflowUses` | GitHub Actions | `Entertech/PublishPlugin/.github/workflows/publish.yml@main` | 业务仓库调用的 reusable workflow 引用。 |

### Secret 与运行时凭据

Central token、GPG key、signing password 在一键发布配置阶段只用于写入 GitHub repository secrets，不会作为发布运行时 fallback。CI 发布时使用 repository secrets：`MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`、`GPG_KEY_CONTENTS`、`SIGNING_PASSWORD`，可选 `SIGNING_KEY_ID`。

已有 repository secrets `GPG_KEY_CONTENTS` / `SIGNING_PASSWORD` 且未配置 `publish.gpgGenerate=true` 时，任务默认复用现有 GPG secrets，不重新生成 key。仓库只缺其中一个 GPG secret 时，任务只写入缺失的 secret，不覆盖已经存在的 secret；只有配置 `publish.overwriteGithubSecrets=true` 或 `publish.gpgGenerate=true` 时才会覆盖。

需要生成新 GPG key 时，配置 `publish.gpgGenerate=true`、`publish.gpgName`、`publish.gpgEmail`、`publish.signingPassword`、`publish.gpgKeyFile` 后再运行配置任务。

回退 GitHub repository secrets：

```bash
./gradlew :library:rollbackPublishSecrets
```

回退任务会读取同一份 `local.properties`。如果没有配置 `publish.githubRepo`，会先尝试通过 `gh repo view` 推导仓库，再回退到 `git remote origin`。传入 `-PremoveGeneratedWorkflow=true` 时，会删除带 `# Generated by PublishPlugin configurePublish` 标记的 workflow；未显式配置 `publish.workflowPath` 时，默认删除 `.github/workflows/publish-<module>.yml`，并兼容清理旧的 `.github/workflows/publish-central-<module>.yml`。

如果 `local.properties` 曾被提交，需要先轮换 Central token / GPG key，再用 `git filter-repo` 或 BFG 清理历史；`git replace` 不能清理已经 push 到远程、fork、CI logs 或缓存中的泄露内容。

## PublishInfo 字段说明

### Maven 坐标字段

| 字段 | 必填场景 | 说明 |
| --- | --- | --- |
| `groupId` | 所有发布 | Maven 坐标的 groupId。Central 发布时必须落在已验证的 `centralNamespace` 下，例如 namespace 是 `ai.looktech`，则 `groupId` 可以是 `ai.looktech` 或 `ai.looktech.xxx`。 |
| `artifactId` | 所有发布 | Maven 坐标的 artifactId。单 publication 直接使用该值；多 release variant 场景下是默认值，可被 `artifactIdForVariant` 为每个 variant 动态覆盖。 |
| `version` | 所有发布 | Maven 坐标的 version。本地发布时，版本以 `-debug` 结尾会额外发布 sources jar；Central 远程发布会拒绝包含 `debug` 的版本。 |

### Gradle Plugin 字段

只有发布 Gradle Plugin 模块时需要配置这两个字段；普通 Android Library 不需要。

| 字段 | 必填场景 | 说明 |
| --- | --- | --- |
| `pluginId` | Gradle Plugin 发布 | Gradle 插件 ID，例如 `cn.entertech.foo`。用户消费插件时写 `plugins { id("cn.entertech.foo") }`。 |
| `implementationClass` | Gradle Plugin 发布 | 插件入口类全限定名，例如 `cn.entertech.foo.FooPlugin`。该类必须实现 `org.gradle.api.Plugin<Project>`。 |

### 远程发布目标字段

这些字段只影响远程发布任务 `PublishLibraryRemoteTask` 或标准远程 repository publish 任务；本地 `publishToMavenLocal` 不要求 Central token 或签名。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `publishTarget` | `github_packages` | 远程发布目标。`github_packages` 使用 GitHub Packages；`central` 使用 Sonatype Central Portal；`all` 由 reusable workflow 同时发布 GitHub Packages 和 Central。 |
| `centralNamespace` | `cn.entertech` | Central namespace。发布前会校验 `groupId` 必须等于该 namespace 或以 `${centralNamespace}.` 开头。非 Entertech namespace 必须覆盖该值。 |
| `centralPublishingType` | `user_managed` | Central Portal deployment 发布方式。`user_managed` 表示上传并通过校验后，只在 Portal 生成一个待发布的 deployment，需要手动点击 Publish 才会真正发布到 Maven Central，也可以点击 Drop 丢弃；`automatic` 表示通过校验后自动发布到 Maven Central。Sonatype OSSRH Staging API 还支持 `portal_api`，但当前插件不会继续调用 Portal Publisher API 跟踪状态，因此不作为推荐值。 |
| `centralRepositoryName` | `CentralStaging` | Gradle repository 名称，会影响任务名，例如 `publishEnterPublishPublicationToCentralStagingRepository`。一般不需要改。 |

### GitHub Packages 字段

| 字段 | 生效场景 | 说明 |
| --- | --- | --- |
| `githubPackagesRepository` | `publishTarget = "github_packages"` | GitHub 仓库，格式为 `owner/repo`。为空时会尝试使用 `GITHUB_REPOSITORY` 或当前工程 `git remote origin` 推导。 |
| `githubPackagesUrl` | `githubPackages` | Maven 仓库完整地址。优先级高于 `githubPackagesRepository` 推导值。 |
| `githubPackagesRepositoryName` | `githubPackages` | Gradle repository 名称，默认 `GitHubPackages`，会生成 `publishEnterPublishPublicationToGitHubPackagesRepository`。 |
| `githubPackagesUsername` | `githubPackages` | GitHub Packages 用户名；CI 通常使用 `GITHUB_ACTOR`。 |
| `githubPackagesPassword` | `githubPackages` | GitHub token；CI 通常使用 `GITHUB_TOKEN`。 |

### 旧私服字段

这些字段保留是为了兼容旧项目；默认 GitHub Packages 和 Central 发布都不建议把凭据写在 `build.gradle.kts` 里。

| 字段 | 生效场景 | 说明 |
| --- | --- | --- |
| `publishUrl` | `remotePublishMode = "customRepository"` 兼容模式 | 旧私服 Maven 仓库地址。Central 模式不会把它作为上传地址。 |
| `publishUserName` | `customRepository`；Central token fallback | 自定义仓库用户名；Central 模式下只作为 token username 的低优先级 fallback。 |
| `publishPassword` | `customRepository`；Central token fallback | 自定义仓库密码；Central 模式下只作为 token password 的低优先级 fallback。 |

### POM 元数据字段

Central 远程发布要求 POM 元数据完整；这些字段本地发布可以为空，执行 `PublishLibraryRemoteTask` 时会校验。

| 字段 | Central 是否必填 | 说明 |
| --- | --- | --- |
| `pomName` | 否 | POM `<name>`。为空时使用当前 publication 的最终 artifactId。多 variant 动态 artifactId 场景下，每个 publication 都会用自己的最终 artifactId 作为 fallback。 |
| `pomDescription` | 有效值必需 | POM `<description>`。为空时 Android Library 默认 `Android library published as a Maven artifact`，Gradle Plugin 默认 `Gradle plugin published as a Maven artifact`。 |
| `pomInceptionYear` | 否 | POM `<inceptionYear>`，默认使用当前年份。只有项目起始年份不是当前年份时才需要覆盖。 |
| `pomUrl` | 有效值必需 | POM `<url>`，通常是项目主页、Git 仓库或文档地址。为空时优先从当前工程 `git remote origin` 推导，再使用 GitHub Actions 环境兜底。 |

### License 字段

License 字段会写入 POM。默认使用 Apache-2.0，只有项目许可证不是 Apache-2.0 时才需要改。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `licenseName` | `The Apache License, Version 2.0` | POM license name。 |
| `licenseUrl` | `https://www.apache.org/licenses/LICENSE-2.0.txt` | POM license URL。 |
| `licenseDistribution` | `repo` | POM license distribution。 |

### Developer 字段

Central 远程发布会校验开发者信息。插件默认使用 Entertech 信息，业务项目如需显示自己的组织或维护者，可以覆盖这些字段。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `developerId` | `Entertech` | 开发者或组织 ID。 |
| `developerName` | `Entertech` | 开发者或组织名称。 |
| `developerEmail` | `developer@entertech.cn` | 联系邮箱。 |
| `developerOrganization` | `Entertech` | 组织名称。 |
| `developerOrganizationUrl` | `https://github.com/Entertech` | 组织主页。 |
| `developerUrl` | `https://github.com/Entertech` | 开发者主页。 |

### SCM 字段

Central 远程发布要求源码仓库信息。插件会按以下顺序推导 `scmUrl`：

```text
-PscmUrl / SCM_URL > PublishInfo.scmUrl > git remote origin
> GitHub Actions(GITHUB_SERVER_URL + GITHUB_REPOSITORY) > GitLab CI(CI_PROJECT_URL)
> GIT_URL / BUILD_REPOSITORY_URI
```

`scmConnection` 和 `scmDeveloperConnection` 如果未显式配置，会根据最终 `scmUrl` 自动生成。以 `https://github.com/Entertech/PublishPlugin` 为例：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| `scmUrl` | 仓库浏览地址，可由 CI 或本地 Git 推导 | `https://github.com/Entertech/PublishPlugin` |
| `scmConnection` | 只读 SCM 地址，可由 `scmUrl` 推导 | `scm:git:https://github.com/Entertech/PublishPlugin.git` |
| `scmDeveloperConnection` | 开发者 SCM 地址，可由 `scmUrl` 推导 | `scm:git:ssh://git@github.com/Entertech/PublishPlugin.git` |

### 动态坐标与 variant 过滤

`groupIdForVariant { variant -> ... }`、`artifactIdForVariant { variant -> ... }`、`versionForVariant { variant -> ... }` 和 `skipVariantIf { variant -> ... }` 只对 Android Library 的 release variant 生效。无 flavor 的普通 `release` 组件不会传入 variant 回调，直接使用 `groupId`、`artifactId`、`version`。

| API/字段 | 作用 | 说明 |
| --- | --- | --- |
| `groupIdForVariant` | 为每个 release variant 返回独立 groupId | 返回空字符串时回退到 `groupId`。 |
| `artifactIdForVariant` | 为每个 release variant 返回独立 artifactId | 返回空字符串时回退到 `artifactId`。适合多 flavor 输出不同 Maven 坐标。 |
| `versionForVariant` | 为每个 release variant 返回独立 version | 返回空字符串时回退到 `version`。本地发布仍会追加 `-local` 后缀。 |
| `skipVariantIf` | 过滤不需要发布的 release variant | 返回 `true` 时跳过该 variant，不注册 publication，也不会生成对应 publish 任务。 |
| `variant.name` | 当前 component/variant 名 | 例如 `breathAuthRelease`。 |
| `variant.buildType` | 当前 build type | 当前插件只发布 `release` component。 |
| `variant.flavors` | flavor 维度到 flavor 名称的 Map | 例如 `project -> breath`、`authentication -> auth`。 |
| `variant.flavor("dimension")` | 获取指定维度的 flavor 名称 | 不存在时返回空字符串，便于安全拼接 artifactId。 |

## 本地打 AAR

如果只是想在当前项目里生成一个本地 AAR 文件，不需要执行发布任务，直接使用 Android Gradle Plugin 的 assemble 任务。

单一 release 组件：

```bash
./gradlew :module:assembleRelease
```

生成目录：

```text
module/build/outputs/aar/
```

常见产物名：

```text
module-release.aar
```

如果 Library 有 flavor，需要执行具体 variant 的 assemble 任务：

```bash
./gradlew :affective_local_sdk:assembleBreathAuthRelease
./gradlew :affective_local_sdk:assembleBreathNoAuthRelease
```

也可以一次构建所有 release variant：

```bash
./gradlew :affective_local_sdk:assembleRelease
```

多 flavor AAR 仍输出在模块的 `build/outputs/aar/` 目录下，文件名由 Android Gradle Plugin 按 variant 生成，例如：

```text
affective_local_sdk-breath-auth-release.aar
affective_local_sdk-breath-noAuth-release.aar
```

`assemble...` 只负责生成 AAR 文件，不会写入 `~/.m2`，也不会生成可供其他项目通过 Maven 坐标依赖的本地库。

## 发布到本地 Maven

执行模块下的自定义任务：

```bash
./gradlew :module:PublishLibraryLocalTask
```

或直接使用 Gradle Maven Publish 标准任务：

```bash
./gradlew :module:publishToMavenLocal
```

本地发布的 sources 规则：

- 本地发布时，实际 publication version 会使用 `-local` 后缀；`version = "1.0.0"` 会发布为 `1.0.0-local`。
- 如果 `PublishInfo.version` 已经以 `-local` 结尾，则保持原值，不会追加成 `-local-local`。
- `version` 以 `-debug` 结尾时，发布 sources jar。
- 非 debug 版本默认不发布 sources jar。
- Central 远程发布不受这个规则限制，Central 必须包含 sources 和 javadoc。

发布成功后，产物会进入本机 Maven 仓库：

```text
~/.m2/repository/<group-path>/<artifactId>/<version>/
```

例如：

```text
~/.m2/repository/cn/entertech/android/affective-offline-sdk/1.0.0-local/
```

多 variant 发布时，每个未过滤的 release variant 都会生成独立 artifact 目录，并且目录里应至少包含 `.aar`、`.pom`、`.module`：

```text
~/.m2/repository/cn/entertech/android/breath-affective-offline-sdk-authentication/1.3.6-local/
~/.m2/repository/cn/entertech/android/breath-affective-offline-sdk/1.3.6-local/
~/.m2/repository/cn/entertech/android/sdk-affective-offline-sdk-authentication/1.3.6-local/
~/.m2/repository/cn/entertech/android/sdk-affective-offline-sdk/1.3.6-local/
```

验证本地发布不能只看 `BUILD SUCCESSFUL`。如果 Gradle 显示成功但目标 artifact 目录没有 `.aar/.pom/.module`，说明 publication 没有真实执行，应优先检查插件日志和 `publishToMavenLocal --stacktrace` 输出。

其他项目使用本地发布产物时，需要在仓库中启用 `mavenLocal()`：

```kotlin
repositories {
    google()
    mavenCentral()
    mavenLocal()
}
```

然后按 Maven 坐标依赖：

```kotlin
dependencies {
    implementation("cn.entertech.android:affective-offline-sdk:1.0.0-local")
}
```

多 variant 动态 artifactId 场景下，依赖最终生成的 artifactId：

```kotlin
dependencies {
    implementation("cn.entertech.android:breath-affective-offline-sdk-authentication:1.0.0-local")
    implementation("cn.entertech.android:breath-affective-offline-sdk:1.0.0-local")
}
```

使用 `PublishLibraryLocalTask` 发布成功后，控制台会打印可直接复制的 Groovy DSL 和 Kotlin DSL `dependencies { ... }` 依赖代码块。

选择方式：

| 目标 | 使用命令 | 产物位置 | 适合场景 |
| --- | --- | --- | --- |
| 只要一个 `.aar` 文件 | `assembleRelease` 或具体 `assemble<Variant>Release` | `module/build/outputs/aar/` | 手动拷贝、临时检查 AAR 内容 |
| 让其他项目通过 Maven 坐标依赖 | `PublishLibraryLocalTask` 或 `publishToMavenLocal` | `~/.m2/repository/` | 本地联调、验证 POM 依赖、模拟远程发布消费方式 |

## 发布到 GitHub Packages

GitHub Packages 是默认远程发布目标。发布前需要确认模块已经配置 `PublishInfo` 的 Maven 坐标：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "1.0.0"
}
```

执行远程发布任务：

```bash
./gradlew :module:PublishLibraryRemoteTask
```

显式指定 GitHub Packages repository：

```bash
GITHUB_ACTOR=<github-user> \
GITHUB_TOKEN=<token-with-package-write> \
./gradlew :module:PublishLibraryRemoteTask \
  -PgithubPackagesRepository=owner/repo \
  --stacktrace
```

也可以在 `PublishInfo` 中固定仓库信息：

```kotlin
PublishInfo {
    githubPackagesRepository = "owner/repo"
    githubPackagesRepositoryName = "GitHubPackages"
}
```

`githubPackagesRepository=owner/repo` 会推导出 Maven repository URL：

```text
https://maven.pkg.github.com/owner/repo
```

这个 URL 是 Gradle/Maven repository endpoint，不是浏览器网页详情页；直接在浏览器打开可能显示 `404 page not found`。发布成功后请在 GitHub 仓库的 Packages 页面查看包，例如 `https://github.com/owner/repo/packages`，或带 GitHub Packages 认证访问具体 POM/metadata 路径验证。

如果需要直接覆盖完整地址，使用 `githubPackagesUrl`：

```bash
./gradlew :module:PublishLibraryRemoteTask \
  -PgithubPackagesUrl=https://maven.pkg.github.com/owner/repo \
  --stacktrace
```

凭据来源建议使用 CI 环境变量：

| 用途 | 推荐环境变量 | 兼容 Gradle property |
| --- | --- | --- |
| GitHub Packages 用户名 | `GITHUB_ACTOR` 或 `GITHUB_PACKAGES_USER` | `-PgithubPackagesUsername=...` 或 `-Pgpr.user=...` |
| GitHub Packages token | `GITHUB_TOKEN` 或 `GITHUB_PACKAGES_TOKEN` | `-PgithubPackagesPassword=...` 或 `-Pgpr.key=...` |

GitHub Actions 中可以使用默认 `GITHUB_TOKEN`，但 repository/package 权限必须允许写入 packages：

```yaml
permissions:
  contents: write
  packages: write
```

消费 GitHub Packages 产物时，业务工程需要配置同一个 Maven repository，并提供具备 package read 权限的 GitHub token：

```kotlin
repositories {
    google()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/owner/repo")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
                ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull
                ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
```

## Central Portal 发布

发布到 Central Portal 时，需要显式指定 `publishTarget=central`，并先确认这些事项：

- 发布账号已经在 Central Portal 验证过对应 namespace。`groupId` 必须等于该 namespace 或位于该 namespace 下；如果不是 `cn.entertech`，必须配置 `centralNamespace` 或通过 `-PcentralNamespace=...` 覆盖。
- 模块已应用 `cn.entertech.publish`。Android Library 模块需要配置 `groupId`、`artifactId`、`version`、`pomDescription`、`pomUrl`；Gradle Plugin 模块还需要配置 `pluginId` 和 `implementationClass`。
- Central 凭据不要写入 `build.gradle.kts`。CI 中使用 Central Portal 生成的 User Token，传入 `CENTRAL_USERNAME` / `CENTRAL_PASSWORD`，或使用兼容变量 `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD`。
- GPG 签名信息必须由 CI 或命令行注入。通常传 `ORG_GRADLE_PROJECT_signingInMemoryKey` 和 `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`；key id 可以不传，让 Gradle 从私钥内容推断。
- SCM 信息默认会先从当前工程 `git remote origin` 推导，再使用 GitHub Actions、GitLab CI、`GIT_URL`、`BUILD_REPOSITORY_URI` 兜底。如果仍拿不到仓库地址，显式传 `-PscmUrl=...`；`scmConnection` 和 `scmDeveloperConnection` 会自动根据 `scmUrl` 生成。
- `version` 不能包含 `debug`，并且发布到 Maven Central 的版本号不能复用；同一个坐标版本已经发布后不能覆盖。
- 默认 `centralPublishingType = "user_managed"`。构建上传成功后，还需要登录 Central Portal 手动点击 Publish 才会真正发布到 Maven Central；如果要自动发布，再改成 `automatic`。
- 如果业务仓库使用本仓库的 reusable workflow，需要使用 `secrets: inherit`，并确保 organization secrets 的 repository access 已授权给调用 workflow 的仓库。

Central 必需配置示例。`centralNamespace`、Developer、SCM、`pomInceptionYear` 都有默认或推导值；如果发布的 namespace 不是 `cn.entertech`，必须覆盖 `centralNamespace`：

```kotlin
PublishInfo {
    groupId = "ai.looktech.ltrpc.schema"
    artifactId = "bt-server"
    version = "1.0.0"

    centralNamespace = "ai.looktech"
    centralPublishingType = "user_managed"

    pomName = "Looktech RPC BT Server Schema"
    pomDescription = "BT Server Schema library for Looktech RPC"
    pomUrl = "https://github.com/Entertech/lt-rpc-schema"
}
```

Central token 和签名建议通过环境变量或 Gradle property 注入，不要写入仓库：

```bash
CENTRAL_USERNAME="$TOKEN_USERNAME" \
CENTRAL_PASSWORD="$TOKEN_PASSWORD" \
ORG_GRADLE_PROJECT_signingInMemoryKey="$GPG_PRIVATE_KEY" \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$GPG_PASSWORD" \
./gradlew :module:PublishLibraryRemoteTask \
  -PpublishTarget=central \
  -PcentralNamespace=ai.looktech \
  -PcentralPublishingType=user_managed \
  --stacktrace
```

`ORG_GRADLE_PROJECT_signingInMemoryKeyId` 可以不传。默认建议只传私钥内容和密码，让 Gradle 从 `GPG_PRIVATE_KEY` 中推断 key id；只有确实需要指定 key id 时，才传 8 位或 16 位 hex key id，例如 `00B5050F`、`0x00B5050F` 或 `2BA16C9B594CE0E6`。

支持的 Central 相关字段：

```kotlin
PublishInfo {
    githubPackagesRepository = "owner/repo"
    githubPackagesRepositoryName = "GitHubPackages"

    centralNamespace = "cn.entertech"
    centralPublishingType = "user_managed" // user_managed / automatic
    centralRepositoryName = "CentralStaging"

    pomName = ""
    pomDescription = ""
    pomInceptionYear = "<当前年份>" // 默认当前年份，通常不用配置
    pomUrl = ""

    licenseName = "The Apache License, Version 2.0"
    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt"
    licenseDistribution = "repo"

    developerId = "Entertech"
    developerName = "Entertech"
    developerEmail = "developer@entertech.cn"
    developerOrganization = "Entertech"
    developerOrganizationUrl = "https://github.com/Entertech"
    developerUrl = "https://github.com/Entertech"

    scmUrl = "" // 为空时优先从当前工程 git remote origin 推导，CI 环境兜底
    scmConnection = "" // 为空时根据 scmUrl 推导
    scmDeveloperConnection = "" // 为空时根据 scmUrl 推导
}
```

`centralPublishingType` 对应 Sonatype Central 的 deployment 发布方式：

- `user_managed`：默认值。上传并通过校验后，只会在 Central Portal 生成一个待发布的 deployment；此时还没有真正发布到 Maven Central，需要登录 Portal 手动点击 Publish 才会发布，也可以点击 Drop 丢弃这次上传。
- `automatic`：上传并校验后，如果校验通过，Central 会尝试自动发布到 Maven Central。

也就是说，`user_managed` 下“上传成功”不等于“已经发布成功”；它适合第一次发布、测试发布或需要人工检查产物后再确认发布的场景。确认 CI 发版流程稳定后，可以考虑切到 `automatic`。

Sonatype OSSRH Staging API 文档还列出 `portal_api`，它只上传 deployment，期望调用方继续使用 Portal Publisher API 查询状态和执行后续操作。当前插件没有实现这套后续 API 流程，因此建议只使用 `user_managed` 或 `automatic`。参考 Sonatype 文档：[Publish OSSRH Staging API](https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/) 和 [Publish Portal API](https://central.sonatype.org/publish/publish-portal-api/)。

## 多 Variant 动态 artifactId

`PublishInfo.artifactId` 是默认 artifactId，也是没有 variant 回调时的 fallback。

当 Android Library 存在多个 release variant 时，插件会为每个 release component 创建独立 publication：

- 无 flavor 的普通 `release` 组件仍使用旧 publication 名：`EnterPublish`。
- 配置了 `groupIdForVariant`、`artifactIdForVariant` 或 `versionForVariant` 时，多 flavor release 组件使用 `<VariantName>EnterPublish`，例如 `BreathAuthReleaseEnterPublish`。
- 没有配置 variant 级坐标时，只注册并发布第一个可发布的 release variant，publication 名仍为 `EnterPublish`，避免多个 publication 使用同一 Maven 坐标互相覆盖。
- `PublishLibraryRemoteTask` 检测到多个 `*EnterPublish` publication 时，会执行 `publishAllPublicationsTo<RepositoryName>Repository`。

如果需要按 variant 动态设置 Maven 坐标，使用 `groupIdForVariant`、`artifactIdForVariant`、`versionForVariant`：

```kotlin
val baseArtifactId = "affective-offline-sdk"

PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = baseArtifactId
    version = "1.0.0"

    groupIdForVariant { variant ->
        if (variant.flavor("project") == "sdk") {
            "cn.entertech.android.sdk"
        } else {
            "cn.entertech.android.breath"
        }
    }

    artifactIdForVariant { variant ->
        val productPrefix = "${variant.flavor("project")}-"
        val authSuffix = if (variant.flavor("authentication") == "auth") {
            "-authentication"
        } else {
            ""
        }

        "$productPrefix$baseArtifactId$authSuffix"
    }

    versionForVariant { variant ->
        if (variant.flavor("authentication") == "auth") {
            "1.0.0-auth"
        } else {
            "1.0.0"
        }
    }
}
```

对应 Groovy DSL：

```groovy
def baseArtifactId = 'affective-offline-sdk'

PublishInfo {
    groupId = 'cn.entertech.android'
    artifactId = baseArtifactId
    version = '1.0.0'

    groupIdForVariant { variant ->
        return variant.flavor('project') == 'sdk' ? 'cn.entertech.android.sdk' : 'cn.entertech.android.breath'
    }

    artifactIdForVariant { variant ->
        def productPrefix = "${variant.flavor('project')}-"
        def authSuffix = variant.flavor('authentication') == 'auth' ? '-authentication' : ''
        return "${productPrefix}${baseArtifactId}${authSuffix}"
    }

    versionForVariant { variant ->
        return variant.flavor('authentication') == 'auth' ? '1.0.0-auth' : '1.0.0'
    }
}
```

示例结果：

| Variant | artifactId |
| --- | --- |
| `breathAuthRelease` | `breath-affective-offline-sdk-authentication` |
| `breathNoAuthRelease` | `breath-affective-offline-sdk` |
| `sdkAuthRelease` | `sdk-affective-offline-sdk-authentication` |
| `sdkNoAuthRelease` | `sdk-affective-offline-sdk` |

`PublishVariantInfo` 可用字段：

```kotlin
variant.name // 例如 breathAuthRelease
variant.buildType // 例如 release
variant.flavors // Map<String, String>
variant.flavor("project") // 例如 breath / sdk
variant.flavor("authentication") // 例如 auth / noAuth
```

没有配置 `groupIdForVariant`、`artifactIdForVariant` 或 `versionForVariant` 时，多 flavor 模块只发布一次，并使用 `PublishInfo` 中的固定 `groupId`、`artifactId` 和 `version`。

如果某些 variant 不需要发布，使用 `skipVariantIf`：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "affective-offline-sdk"
    version = "1.0.0"

    skipVariantIf { variant ->
        variant.flavor("project") == "flowtime"
    }
}
```

也可以只发布指定 product：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "affective-offline-sdk"
    version = "1.0.0"

    skipVariantIf { variant ->
        variant.flavor("project") != "sdk"
    }
}
```

过滤规则：

- 未配置 `skipVariantIf` 时发布所有 release variants。
- 可配置多个 `skipVariantIf`，任意一个返回 `true` 就跳过该 variant。
- 被跳过的 variant 不会注册 `singleVariant`，不会创建 publication，也不会生成对应 `publish...PublicationToMavenLocal` 任务。
- 如果所有候选 release variants 都被跳过，插件会让构建失败，避免出现没有实际发布内容的假成功。

## CLI 覆盖

CI 或本地临时发布时，建议用 Gradle property 或环境变量覆盖敏感信息和临时元数据，不要把 token、密码、GPG 私钥写入 `build.gradle.kts`。

常用覆盖项：

| 用途 | Gradle property | 环境变量 | 说明 |
| --- | --- | --- | --- |
| 发布目标 | `-PpublishTarget=github_packages` | `PUBLISH_TARGET` | 可选 `github_packages` / `central` / `all`。旧 `-PremotePublishMode` / `REMOTE_PUBLISH_MODE` 仅兼容读取。 |
| GitHub Packages 仓库 | `-PgithubPackagesRepository=owner/repo` | `GITHUB_PACKAGES_REPOSITORY` / `GITHUB_REPOSITORY` | 用于推导 `https://maven.pkg.github.com/owner/repo`。 |
| GitHub Packages URL | `-PgithubPackagesUrl=...` | `GITHUB_PACKAGES_URL` | 显式覆盖完整 Maven repository URL。 |
| GitHub Packages 用户名 | `-PgithubPackagesUsername=...` / `-Pgpr.user=...` | `GITHUB_PACKAGES_USER` / `GITHUB_ACTOR` / `USERNAME` | GitHub Packages repository credentials username。 |
| GitHub Packages Token | `-PgithubPackagesPassword=...` / `-Pgpr.key=...` | `GITHUB_PACKAGES_TOKEN` / `GITHUB_TOKEN` / `TOKEN` | GitHub Packages repository credentials password/token。 |
| 发布版本 | `-PpublishVersion=...` / `-Pversion=...` | `PUBLISH_VERSION` | 临时覆盖 `PublishInfo.version`。`publishVersion` 优先级更高；`version` 用于兼容 reusable workflow 的 `version` 输入。 |
| Central namespace | `-PcentralNamespace=...` | `CENTRAL_NAMESPACE` | 覆盖 `PublishInfo.centralNamespace`。 |
| Central 发布方式 | `-PcentralPublishingType=user_managed` | `CENTRAL_PUBLISHING_TYPE` | 可选 `user_managed` / `automatic`。 |
| Central release 类型 | `-PcentralReleaseType=snapshot` | `CENTRAL_RELEASE_TYPE` | 可选 `release` / `snapshot`。`snapshot` 使用 `CentralSnapshots` 和 `https://central.sonatype.com/repository/maven-snapshots/`。 |
| Central repository 名称 | `-PcentralRepositoryName=CentralStaging` | `CENTRAL_REPOSITORY_NAME` | 一般不需要改。 |
| Central token username | `-PcentralUsername=...` | `CENTRAL_USERNAME` | 优先级高于旧字段 `publishUserName`。 |
| Central token password | `-PcentralPassword=...` | `CENTRAL_PASSWORD` | 优先级高于旧字段 `publishPassword`。 |
| Central token username fallback | `-PmavenCentralUsername=...` | `MAVEN_CENTRAL_USERNAME` | 兼容已有 CI 变量名。 |
| Central token password fallback | `-PmavenCentralPassword=...` | `MAVEN_CENTRAL_PASSWORD` | 兼容已有 CI 变量名。 |
| POM name | `-PpomName=...` | `POM_NAME` | 覆盖 `PublishInfo.pomName`。 |
| POM description | `-PpomDescription=...` | `POM_DESCRIPTION` | 覆盖 `PublishInfo.pomDescription`。 |
| POM URL | `-PpomUrl=...` | `POM_URL` | 覆盖 `PublishInfo.pomUrl`。 |
| SCM URL | `-PscmUrl=...` | `SCM_URL` | 覆盖 SCM 仓库浏览地址；为空时从当前工程 git remote / CI 推导。 |
| SCM connection | `-PscmConnection=...` | `SCM_CONNECTION` | 覆盖只读 SCM 地址；为空时根据 `scmUrl` 推导。 |
| SCM developer connection | `-PscmDeveloperConnection=...` | `SCM_DEVELOPER_CONNECTION` | 覆盖开发者 SCM 地址；为空时根据 `scmUrl` 推导。 |
| GPG 私钥 | `-PsigningInMemoryKey=...` | `SIGNING_IN_MEMORY_KEY` / `GPG_KEY_CONTENTS` | Central 发布必需。 |
| GPG 私钥密码 | `-PsigningInMemoryKeyPassword=...` | `SIGNING_IN_MEMORY_KEY_PASSWORD` / `SIGNING_PASSWORD` | Central 发布必需。 |
| GPG key id | `-PsigningInMemoryKeyId=...` | `SIGNING_IN_MEMORY_KEY_ID` / `SIGNING_KEY_ID` | 可选；通常留空让 Gradle 从私钥推断。 |

旧私服字段的解析优先级不同：

```text
publishUrl / publishUserName / publishPassword:
Gradle property > PublishInfo 字段 > local.properties
```

Central token 的解析优先级：

```text
centralUsername / centralPassword:
Gradle property > 环境变量 > mavenCentral* fallback > PublishInfo 旧字段 > local.properties
```

## GitHub Actions 发布

业务项目可以调用本仓库提供的 reusable workflow 发布远程 Maven 产物。`publish_target` 不传时默认是 `github_packages`，也可以显式选择三种模式：

| publish_target | 行为 | 需要的凭据 |
| --- | --- | --- |
| `central` | 只发布到 Sonatype Central Portal | Central token、GPG signing secrets。 |
| `github_packages` | 只发布到 GitHub Packages | `GITHUB_TOKEN` 或 `GITHUB_PACKAGES_TOKEN`，并授予 packages write 权限。 |
| `all` | 先发布 GitHub Packages，再发布 Sonatype Central Portal | 同时满足上面两类凭据。 |

`publish_mode` 控制同一个 workflow 的发布语义：

| publish_mode | 行为 |
| --- | --- |
| `release` | 正式发布。版本号保持 `1.2.3`，Central 走 release/staging 仓库；如果 `sync_readme=true`，发布成功后同步 README 并提交。 |
| `ci` | CI 快照打包。只允许 `publish_target=central`；版本自动追加 `-SNAPSHOT`，发布到 `https://central.sonatype.com/repository/maven-snapshots/`；不更新 README。 |

### 只发布 Central

业务项目不需要在各自仓库里单独维护 GPG 私钥和 Central token。把这些敏感字段配置为 `Entertech` organization secrets 后，业务项目只调用本仓库提供的 reusable workflow，并使用 `secrets: inherit` 继承组织级 secrets。

```yaml
name: Publish Maven

on:
  workflow_dispatch:

jobs:
  publish:
    uses: Entertech/PublishPlugin/.github/workflows/publish.yml@main
    secrets: inherit
    with:
      module: ":library"
      publish_target: "central"
      publish_mode: "release"
      version: "1.2.3"
      sync_readme: true
      namespace: "cn.entertech"
      publishing_type: "user_managed"
```

### CI 快照发布到 Central Snapshots

通过本仓库 skill 触发 CI 打包时使用 `publish_mode: "ci"`。该模式只支持 Central，workflow 会把 `version: "1.2.3"` 实际发布为 `1.2.3-SNAPSHOT`，并发布到 Sonatype Central snapshots 仓库；不会同步 README。

```yaml
name: Publish CI Snapshot

on:
  workflow_dispatch:

jobs:
  publish:
    uses: Entertech/PublishPlugin/.github/workflows/publish.yml@main
    secrets: inherit
    with:
      module: ":library"
      publish_target: "central"
      publish_mode: "ci"
      version: "1.2.3"
      namespace: "cn.entertech"
      publishing_type: "user_managed"
```

### 只发布 GitHub Packages

```yaml
name: Publish GitHub Packages

on:
  workflow_dispatch:

permissions:
  contents: write
  packages: write

jobs:
  publish:
    uses: Entertech/PublishPlugin/.github/workflows/publish.yml@main
    with:
      module: ":library"
      publish_target: "github_packages"
      publish_mode: "release"
      version: "1.2.3"
      sync_readme: true
      github_packages_repository: "owner/repo"
```

`github_packages_repository` 为空时会使用调用 workflow 的仓库。需要发布到其他仓库或使用 PAT 时，可以配置 repository secret `GITHUB_PACKAGES_TOKEN`，并使用 `secrets: inherit` 继承给 reusable workflow。

### 同时发布 Central 和 GitHub Packages

```yaml
name: Publish Maven

on:
  workflow_dispatch:

permissions:
  contents: write
  packages: write

jobs:
  publish:
    uses: Entertech/PublishPlugin/.github/workflows/publish.yml@main
    secrets: inherit
    with:
      module: ":library"
      publish_target: "all"
      publish_mode: "release"
      namespace: "cn.entertech"
      publishing_type: "user_managed"
      version: "1.2.3"
      sync_readme: true
      github_packages_repository: "owner/repo"
```

如果发布时需要临时覆盖版本，三种发布模式都可以传入；当开启 `sync_readme` 时，`version` 也是 README 版本同步的来源：

```yaml
with:
  module: ":library"
  publish_target: "all"
  publish_mode: "release"
  namespace: "cn.entertech"
  publishing_type: "user_managed"
  github_packages_repository: "owner/repo"
  version: "1.2.3"
```

`configurePublish` 生成的 workflow 默认使用 `publish_mode: "release"`，会传入 `version` 并开启 `sync_readme`。reusable workflow 发布成功后会把 README「代码配置」章节中的插件版本同步为本次发布版本；如果发布目标包含 `github_packages`，根工程插件仓库配置会切换为对应的 `https://maven.pkg.github.com/owner/repo`，否则保持 `mavenCentral()`。

`Entertech` organization secrets 需要维护：

```text
MAVEN_CENTRAL_USERNAME
MAVEN_CENTRAL_PASSWORD
GPG_KEY_CONTENTS
SIGNING_PASSWORD
```

可选 secret：

```text
SIGNING_KEY_ID
```

`MAVEN_CENTRAL_USERNAME` 和 `MAVEN_CENTRAL_PASSWORD` 必须使用 Central Portal 生成的 User Token。`GPG_KEY_CONTENTS` 和 `SIGNING_PASSWORD` 使用公司统一发布 GPG key。`SIGNING_KEY_ID` 建议留空，让 Gradle 从 `GPG_KEY_CONTENTS` 私钥内容推断；如果配置，必须是 Gradle signing 支持的 PGP key id，例如 `00B5050F`、`0x00B5050F` 或 16 位 long key id。业务仓库无需重复创建这些 secrets，但 organization secret 的 repository access 必须授权给调用 workflow 的仓库。

## 发布本插件到 Central

`PublishPlugin` 自身是 Gradle Plugin 模块，不通过自己的 `PublishLibraryRemoteTask` 发布。发布插件本身使用仓库内的 GitHub Actions：

```text
.github/workflows/publish-plugin-central.yml
```

发布流程：

1. PR 合入预发布分支 `pre_publish` 前，`publish-plugin-pr-check.yml` 校验版本号、构建、本地发布和 publication 元数据，并同步 README 中的插件版本。
2. PR 合入 `pre_publish` 后，`publish-plugin-central.yml` 自动发布插件到 Central。
3. 发布版本会自动规范化为 `数字.数字.数字`，例如 `1.2.0-local` 会发布为 `1.2.0`；如果无法识别出三段数字，workflow 失败。
4. 发布前先预演 `pre_publish` 合入 `main`，如果存在冲突，workflow 直接失败，不发布 Central。
5. 同一个 `pre_publish` 发布 run 正在执行时，如果又有新的提交 push 到 `pre_publish`，旧 run 会被自动取消。
6. Central deployment 创建成功后，workflow 再次同步 README 和根 `build.gradle.kts` 中的插件版本，并把变更提交回 `pre_publish`。
7. 版本同步提交成功后，workflow 创建并推送 `v<version>` tag。
8. tag 推送成功后，workflow 将当前 `pre_publish` merge 到 `main`。
9. 如果 Central 发布失败，不会更新 README，不会创建 tag，也不会合入 `main`。

手动触发或自动发布时需要配置这些 secrets：

```text
MAVEN_CENTRAL_USERNAME
MAVEN_CENTRAL_PASSWORD
GPG_KEY_CONTENTS
SIGNING_PASSWORD
```

`SIGNING_KEY_ID` 是可选项。为空时发布 workflow 不会传 key id 给 Gradle，Gradle 会从 `GPG_KEY_CONTENTS` 推断；如果填了 40 位 fingerprint，workflow 会自动截取最后 16 位作为 long key id；如果填了邮箱、描述文本等非法值，workflow 会清空该值并回退到从私钥推断，避免 `Could not read PGP secret key`。

发布坐标：

```text
cn.entertech.android:publish:1.2.2
cn.entertech.publish:cn.entertech.publish.gradle.plugin:1.2.2
```

其中第二个是 Gradle plugin marker，用于支持：

```kotlin
plugins {
    id("cn.entertech.publish") version "1.2.2"
}
```
