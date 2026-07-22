# PublishPlugin

`PublishPlugin` 用于给 Android Library 和 Gradle Plugin 模块生成 Maven publication，并提供统一的本地发布、远程发布入口。

当前远程发布入口继续复用旧任务名 `PublishLibraryRemoteTask`，实现上默认面向 Sonatype Central Portal；旧的私服字段仍保留，避免已有项目的 `PublishInfo { ... }` 配置编译失败。

## 快速开始

### 1. 根工程引入插件

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

### 2. 发布 Android Library

在需要发布的 Android Library 模块中应用插件：

```kotlin
plugins {
    id("com.android.library")
    id("cn.entertech.publish")
}
```

最小 `PublishInfo`：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "base"
    version = "0.0.1"
}
```

### 3. 发布 Gradle Plugin

Gradle Plugin 模块需要同时应用 `cn.entertech.publish` 和 `java-gradle-plugin`：

```kotlin
plugins {
    id("cn.entertech.publish")
    `java-gradle-plugin`
}
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

如果项目仍使用 Groovy `build.gradle`，也可以使用旧写法：

```groovy
apply plugin: 'cn.entertech.publish'
```

## 仓库内 demo

本仓库提供两个可运行示例：

| 模块 | 用途 | 覆盖内容 |
| --- | --- | --- |
| `demo-lib` | Android Library 发布示例 | `PublishInfo` 基础坐标、POM/Central 元数据、多 flavor release variant、`artifactIdForVariant`。 |
| `demo-plugin` | Gradle Plugin 发布示例 | `java-gradle-plugin`、`pluginId`、`implementationClass`、Gradle 插件入口类、本地 Maven 发布。 |

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

`PublishInfo` 描述要发布的 Maven 坐标、远程仓库模式、POM 元数据以及多 variant 规则。不同发布场景需要的字段不同：

| 场景 | 必填字段 | 额外要求 |
| --- | --- | --- |
| 本地 Maven 发布 | `groupId`、`artifactId`、`version` | 执行 `PublishLibraryLocalTask` 或 `publishToMavenLocal`。不要求 Central 凭据、签名和完整 POM 元数据。 |
| Android Library 远程发 Central | `groupId`、`artifactId`、`version` | `pomDescription` 默认使用 `Android library published to Central Portal`，`pomUrl` 优先从当前工程 `git remote origin` 推导，再使用 CI 环境兜底。非 `cn.entertech` namespace 必须覆盖 `centralNamespace`。`version` 不能包含 `debug`。 |
| Gradle Plugin 远程发 Central | Android Library 远程字段 + `pluginId`、`implementationClass` | `pluginId` 是用户使用 `plugins { id("...") }` 时看到的插件 ID。 |
| GitHub Packages 发布 | `groupId`、`artifactId`、`version`、`remotePublishMode = "githubPackages"` | 配置 `githubPackagesRepository=owner/repo` 或 `githubPackagesUrl=https://maven.pkg.github.com/owner/repo`。凭据建议用 `GITHUB_ACTOR` / `GITHUB_TOKEN`，也兼容 `gpr.user` / `gpr.key`。 |
| 旧私服发布 | `groupId`、`artifactId`、`version`、`remotePublishMode = "customRepository"`、`publishUrl` | `publishUserName`、`publishPassword` 按仓库权限需要配置。 |

字段值解析优先级按用途分组：

- `groupId`、`artifactId`、`version` 直接来自 `PublishInfo`。
- Central、License、Developer、SCM 等仓库级字段支持命令行和本地通用配置覆盖：`Gradle property > 环境变量 > PublishInfo 显式配置 > local.properties 中 centralPublish.* 非空值 > 默认/推导值`。
- `pomName`、`pomDescription`、`pomUrl` 属于组件 POM 信息，不从 `local.properties` 读取；未显式配置时分别使用 artifactId、组件类型默认描述、当前工程 git remote / CI 推导 URL。
- `publishUrl`、`publishUserName`、`publishPassword` 兼容 `local.properties`：`Gradle property > PublishInfo 字段 > local.properties`。
- GitHub Packages URL 可由 `githubPackagesRepository=owner/repo` 推导；凭据优先使用 Gradle property / 环境变量，再回退到 `PublishInfo` 和 `local.properties` 中的 GitHub Packages 专用字段或旧私服字段。
- Central token 不建议写进 `PublishInfo`。优先使用 `-PcentralUsername/-PcentralPassword` 或 `CENTRAL_USERNAME/CENTRAL_PASSWORD`。
- GPG signing 信息不是 `PublishInfo` 字段；发布运行时只能通过 Gradle property 或环境变量传入。一键配置时可先放在 ignored/untracked 的 `local.properties` 中，用于写入 GitHub repository secrets。

`local.properties` 中的旧私服字段值不要加引号：

```properties
publishUrl=https://repo.example.com/releases
publishUserName=username
publishPassword=password
```

## Central Portal 一键配置

其他仓库接入插件后，可以在目标模块执行一键配置任务。任务只处理当前模块，不需要在配置文件声明 `modules`：

```bash
./gradlew :library:generateCentralPublishConfig
./gradlew :library:configureCentralPublish
```

兼容大写任务名：

```bash
./gradlew :library:GenerateCentralPublishConfigTask
./gradlew :library:ConfigureCentralPublishTask
```

`generateCentralPublishConfig` 默认生成或更新根目录 `local.properties`。Android 项目通常已经有这个文件，task 会保留已有 `sdk.dir` / `ndk.dir`，只追加 `centralPublish.*` 配置块；如果 `.gitignore` 缺少 `local.properties`，task 会自动补充。如果 `local.properties` 已经被 git track，任务会失败，避免敏感信息继续进入版本控制。

生成模板的注释使用 ASCII English，避免 IDE 按 `.properties` 默认编码打开时出现中文注释乱码。已有旧模板需要刷新注释时，可以传入 `-PoverwritePublishConfig=true`，task 会重写模板注释并保留已有 `centralPublish.*` value。

`local.properties` 中只放仓库级通用字段和一键配置输入，例如：

```properties
centralPublish.githubRepo=Entertech/demo-lib
centralPublish.dryRun=false
centralPublish.centralNamespace=cn.entertech
centralPublish.centralPublishingType=user_managed

centralPublish.githubSecrets=true
centralPublish.mavenCentralUsername=central-token-username
centralPublish.mavenCentralPassword=central-token-password
centralPublish.gpgKeyFile=/Users/example/secrets/gpg-private-key.asc
centralPublish.signingPassword=gpg-private-key-password

centralPublish.githubActions=true
centralPublish.workflowUses=Entertech/PublishPlugin/.github/workflows/central-publish.yml@main
```

组件字段仍然放在各模块 `PublishInfo`，不要写入 `local.properties`：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "1.0.0"
}
```

Central token 和 GPG 字段只用于 `configureCentralPublish` 调用 `gh secret set` 写入 GitHub repository secrets，不会作为发布运行时 fallback。CI 发布时使用 repository secrets：`MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`、`GPG_KEY_CONTENTS`、`SIGNING_PASSWORD`，可选 `SIGNING_KEY_ID`。

需要预演时设置 `centralPublish.dryRun=true`。dry run 只做解析、校验和打印摘要，不写 `.gitignore`，不生成或覆盖 workflow，不调用 `gh secret set`，也不生成 GPG key。

已有 repository secrets `GPG_KEY_CONTENTS` / `SIGNING_PASSWORD` 且未配置 `centralPublish.gpgGenerate=true` 时，任务默认复用现有 GPG secrets，不重新生成 key。仓库只缺其中一个 GPG secret 时，任务只写入缺失的 secret，不覆盖已经存在的 secret；只有配置 `centralPublish.overwriteGithubSecrets=true` 或 `centralPublish.gpgGenerate=true` 时才会覆盖。需要生成新 GPG key 时，配置 `centralPublish.gpgGenerate=true`、`centralPublish.gpgName`、`centralPublish.gpgEmail`、`centralPublish.signingPassword`、`centralPublish.gpgKeyFile` 后再运行配置任务。

回退 GitHub repository secrets：

```bash
./gradlew :library:rollbackCentralPublishSecrets
```

回退任务会读取同一份 `local.properties`。如果没有配置 `centralPublish.githubRepo`，会先尝试通过 `gh repo view` 推导仓库，再回退到 `git remote origin`。传入 `-PremoveGeneratedWorkflow=true` 时，会删除带 `# Generated by PublishPlugin configureCentralPublish` 标记的 workflow；未显式配置 `centralPublish.workflowPath` 时，默认删除 `.github/workflows/publish-central-<module>.yml`。

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

### 远程发布模式字段

这些字段只影响远程发布任务 `PublishLibraryRemoteTask` 或标准远程 repository publish 任务；本地 `publishToMavenLocal` 不要求 Central token 或签名。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `remotePublishMode` | `central` | 远程发布模式。`central` 使用 Sonatype Central Portal；`githubPackages` 使用 GitHub Packages；`customRepository` 使用旧自定义 Maven 仓库。 |
| `centralNamespace` | `cn.entertech` | Central namespace。发布前会校验 `groupId` 必须等于该 namespace 或以 `${centralNamespace}.` 开头。非 Entertech namespace 必须覆盖该值。 |
| `centralPublishingType` | `user_managed` | Central Portal deployment 发布方式。`user_managed` 表示上传并通过校验后，只在 Portal 生成一个待发布的 deployment，需要手动点击 Publish 才会真正发布到 Maven Central，也可以点击 Drop 丢弃；`automatic` 表示通过校验后自动发布到 Maven Central。Sonatype OSSRH Staging API 还支持 `portal_api`，但当前插件不会继续调用 Portal Publisher API 跟踪状态，因此不作为推荐值。 |
| `centralRepositoryName` | `CentralStaging` | Gradle repository 名称，会影响任务名，例如 `publishEnterPublishPublicationToCentralStagingRepository`。一般不需要改。 |

### GitHub Packages 字段

| 字段 | 生效场景 | 说明 |
| --- | --- | --- |
| `githubPackagesRepository` | `remotePublishMode = "githubPackages"` | GitHub 仓库，格式为 `owner/repo`。为空时会尝试使用 `GITHUB_REPOSITORY` 或当前工程 `git remote origin` 推导。 |
| `githubPackagesUrl` | `githubPackages` | Maven 仓库完整地址。优先级高于 `githubPackagesRepository` 推导值。 |
| `githubPackagesRepositoryName` | `githubPackages` | Gradle repository 名称，默认 `GitHubPackages`，会生成 `publishEnterPublishPublicationToGitHubPackagesRepository`。 |
| `githubPackagesUsername` | `githubPackages` | GitHub Packages 用户名；CI 通常使用 `GITHUB_ACTOR`。 |
| `githubPackagesPassword` | `githubPackages` | GitHub token；CI 通常使用 `GITHUB_TOKEN`。 |

### 旧私服字段

这些字段保留是为了兼容旧项目；默认 Central 发布不建议把凭据写在 `build.gradle.kts` 里。

| 字段 | 生效场景 | 说明 |
| --- | --- | --- |
| `publishUrl` | `remotePublishMode = "customRepository"` | 旧私服 Maven 仓库地址。Central 模式不会把它作为上传地址。 |
| `publishUserName` | `customRepository`；Central token fallback | 自定义仓库用户名；Central 模式下只作为 token username 的低优先级 fallback。 |
| `publishPassword` | `customRepository`；Central token fallback | 自定义仓库密码；Central 模式下只作为 token password 的低优先级 fallback。 |

### POM 元数据字段

Central 远程发布要求 POM 元数据完整；这些字段本地发布可以为空，执行 `PublishLibraryRemoteTask` 时会校验。

| 字段 | Central 是否必填 | 说明 |
| --- | --- | --- |
| `pomName` | 否 | POM `<name>`。为空时使用当前 publication 的最终 artifactId。多 variant 动态 artifactId 场景下，每个 publication 都会用自己的最终 artifactId 作为 fallback。 |
| `pomDescription` | 有效值必需 | POM `<description>`。为空时 Android Library 默认 `Android library published to Central Portal`，Gradle Plugin 默认 `Gradle plugin published to Central Portal`。 |
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

### 动态 artifactId 与 variant 过滤

`artifactIdForVariant { variant -> ... }` 和 `skipVariantIf { variant -> ... }` 只对 Android Library 的 release variant 生效。无 flavor 的普通 `release` 组件不会传入 variant 回调，直接使用 `artifactId`。

| API/字段 | 作用 | 说明 |
| --- | --- | --- |
| `artifactIdForVariant` | 为每个 release variant 返回独立 artifactId | 返回空字符串时回退到 `artifactId`。适合多 flavor 输出不同 Maven 坐标。 |
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

## Central Portal 发布

远程发布仍执行旧任务名：

```bash
./gradlew :module:PublishLibraryRemoteTask
```

其他仓库接入这个插件发布到 Central Portal 前，需要先确认这些事项：

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
  -PcentralNamespace=ai.looktech \
  -PcentralPublishingType=user_managed \
  --stacktrace
```

`ORG_GRADLE_PROJECT_signingInMemoryKeyId` 可以不传。默认建议只传私钥内容和密码，让 Gradle 从 `GPG_PRIVATE_KEY` 中推断 key id；只有确实需要指定 key id 时，才传 8 位或 16 位 hex key id，例如 `00B5050F`、`0x00B5050F` 或 `2BA16C9B594CE0E6`。

支持的 Central 相关字段：

```kotlin
PublishInfo {
    remotePublishMode = "central" // central / customRepository
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
- 多 flavor release 组件使用 `<VariantName>EnterPublish`，例如 `BreathAuthReleaseEnterPublish`。
- `PublishLibraryRemoteTask` 检测到多个 `*EnterPublish` publication 时，会执行 `publishAllPublicationsTo<RepositoryName>Repository`。

如果需要按 variant 动态设置 artifactId，使用 `artifactIdForVariant`：

```kotlin
val baseArtifactId = "affective-offline-sdk"

PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = baseArtifactId
    version = "1.0.0"

    artifactIdForVariant { variant ->
        val productPrefix = "${variant.flavor("project")}-"
        val authSuffix = if (variant.flavor("authentication") == "auth") {
            "-authentication"
        } else {
            ""
        }

        "$productPrefix$baseArtifactId$authSuffix"
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

    artifactIdForVariant { variant ->
        def productPrefix = "${variant.flavor('project')}-"
        def authSuffix = variant.flavor('authentication') == 'auth' ? '-authentication' : ''
        return "${productPrefix}${baseArtifactId}${authSuffix}"
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

没有配置 `artifactIdForVariant` 时，所有 publication 使用 `PublishInfo.artifactId`，保持旧项目行为。

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
| 发布模式 | `-PremotePublishMode=central` | `REMOTE_PUBLISH_MODE` | 可选 `central` / `githubPackages` / `customRepository`。 |
| GitHub Packages 仓库 | `-PgithubPackagesRepository=owner/repo` | `GITHUB_PACKAGES_REPOSITORY` / `GITHUB_REPOSITORY` | 用于推导 `https://maven.pkg.github.com/owner/repo`。 |
| GitHub Packages URL | `-PgithubPackagesUrl=...` | `GITHUB_PACKAGES_URL` | 显式覆盖完整 Maven repository URL。 |
| GitHub Packages 用户名 | `-PgithubPackagesUsername=...` / `-Pgpr.user=...` | `GITHUB_PACKAGES_USER` / `GITHUB_ACTOR` / `USERNAME` | GitHub Packages repository credentials username。 |
| GitHub Packages Token | `-PgithubPackagesPassword=...` / `-Pgpr.key=...` | `GITHUB_PACKAGES_TOKEN` / `GITHUB_TOKEN` / `TOKEN` | GitHub Packages repository credentials password/token。 |
| Central namespace | `-PcentralNamespace=...` | `CENTRAL_NAMESPACE` | 覆盖 `PublishInfo.centralNamespace`。 |
| Central 发布方式 | `-PcentralPublishingType=user_managed` | `CENTRAL_PUBLISHING_TYPE` | 可选 `user_managed` / `automatic`。 |
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

业务项目不需要在各自仓库里单独维护 GPG 私钥和 Central token。把这些敏感字段配置为 `Entertech` organization secrets 后，业务项目只调用本仓库提供的 reusable workflow，并使用 `secrets: inherit` 继承组织级 secrets。

业务项目示例：

```yaml
name: Publish Maven Central

on:
  workflow_dispatch:

jobs:
  publish:
    uses: Entertech/PublishPlugin/.github/workflows/central-publish.yml@main
    secrets: inherit
    with:
      module: ":library"
      namespace: "cn.entertech"
      publishing_type: "user_managed"
```

如果发布时需要临时覆盖版本，可以传入：

```yaml
with:
  module: ":library"
  namespace: "cn.entertech"
  publishing_type: "user_managed"
  version: "1.2.3"
```

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
6. Central deployment 创建成功后，workflow 再次同步 README 中的插件版本，并把变更提交回 `pre_publish`。
7. README 同步提交成功后，workflow 创建并推送 `v<version>` tag。
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
