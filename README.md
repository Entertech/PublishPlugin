# PublishPlugin

`PublishPlugin` 是 Enter/Flowtime Android 项目的发布编排插件。它为 Android
Library 和 Gradle Plugin 模块生成标准 Maven publications，并把“组件类型、
发布目标、执行环境、产物来源”拆成清晰的配置和任务边界。

它适合以下场景：

- 在本机把 Library 或 Gradle Plugin 发布到 Maven Local；
- 从本机或 GitHub Actions 发布到 GitHub Packages 或 Sonatype Central Portal；
- 直接发布已经准备好的 AAR/JAR、POM、sources、javadoc、签名和校验文件；
- 在不把凭据写入仓库的前提下，为业务模块生成可审查的发布配置。

PublishPlugin 负责 publication 和发布编排，不负责聚合多个业务模块，也不把
凭据或一次性发布参数写入 Android 根目录的 `local.properties`。

## 能力边界

发布行为由四个互相独立的维度决定：

| 维度 | 支持值 | 说明 |
| --- | --- | --- |
| 组件类型 | `library`、`plugin` | Android Library 或 Gradle Plugin。 |
| 执行环境 | `local`、`github_actions` | 本机执行，或由 GitHub Actions runner 执行。 |
| 发布目标 | `local`、`github_packages`、`central`、`all` | Maven Local、GitHub Packages、Central，或所有已启用的远程 provider。 |
| 产物来源 | `project`、`prebuilt` | 当前工程生成 publication，或消费指定目录中的预制产物。 |

几个重要约束：

- GitHub Actions 不把 Maven Local 作为正式交付目标；`github_actions + local`
  无效。
- 远程任务只发布显式启用的 provider；`all` 不承诺跨仓库事务性。
- 预制模式不会运行当前工程的 `compile`、`assemble`、`bundle`、`jar` 等打包任务。
- workflow 只接受 allowlist 中的组件类型和目标，不接受任意 Gradle task 字符串。
- 凭据只能来自环境变量、Gradle property、ignored 的本机配置文件或 GitHub
  Secrets，不能提交到仓库。

详细的需求、迁移约束和验收标准见
[发布任务与一键配置重构 PRD](doc/prd/publish-task-config-redesign-prd.md)；
实现边界见[发布架构](doc/tech/publish-architecture.md)。

深入文档：

- [发布前测试与验收清单](doc/tech/pre-release-testing.md)
- [Sonatype Central 发布](doc/tech/central-publishing.md)
- [Android Variant 发布](doc/tech/android-variant-publishing.md)
- [发布配置与凭据](doc/tech/publish-configuration.md)
- [未完成工作与优先级](doc/plan.md)

## 快速开始

### 1. 引入插件

在根工程的 `build.gradle.kts` 中声明插件依赖。版本以本仓库当前发布版本为例：

```kotlin
buildscript {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }

    dependencies {
        classpath("cn.entertech.android:publish:2.0.1")
    }
}
```

### 2. 配置 Android Library

```kotlin
plugins {
    id("com.android.library")
    id("cn.entertech.publish")
}

PublishInfo {
    groupId = "cn.example.android"
    artifactId = "example-library"
    version = "2.0.1"

    // 开源组件需要发布真实源码；闭源组件可保持默认 false。
    hasSource = true
}
```

### 3. 配置 Gradle Plugin

```kotlin
plugins {
    id("cn.entertech.publish")
    `java-gradle-plugin`
}

PublishInfo {
    groupId = "cn.example.gradle"
    artifactId = "example-plugin"
    version = "2.0.1"
    pluginId = "cn.example.plugin"
    implementationClass = "cn.example.plugin.ExamplePlugin"
}
```

### 4. 先做本地发布验证

Library 和 Gradle Plugin 使用不同的任务名：

```bash
# Library
./gradlew :library:PublishLibraryLocalTask

# Gradle Plugin
./gradlew :plugin:PublishPluginLocalTask
```

工程模式下，本地发布版本会追加 `-local`，例如：
`cn.example.android:example-library:2.0.1-local`。
执行 `PublishLibraryLocalTask` 或 `PublishPluginLocalTask` 后，终端会输出 Maven Local
仓库根地址、publication 坐标和对应版本目录地址，并附带可直接复制的 Gradle/Maven
依赖片段。

本仓库的 `plugin_base` 是插件实现模块，使用标准任务发布到本地时同样会自动追加
`-local` 并输出地址：

```bash
./gradlew :plugin_base:publishToMavenLocal
```

发布前可运行只读检查，不会上传制品：

```bash
./gradlew :library:checkPublish
./gradlew :library:checkPublish -PcheckPublishTarget=central
```

检查成功后会在模块的 `build/reports/publish/publish-manifest.json` 和
`build/reports/publish/publish-manifest.md` 生成不含凭据的发布摘要。

## 公开任务 API

每个应用 `cn.entertech.publish` 的模块只注册以下四个 `customPlugin` 任务：

| 组件 | Maven Local | 所有已启用远程仓库 | GitHub Packages | Central |
| --- | --- | --- | --- | --- |
| Library | `PublishLibraryLocalTask` | `PublishLibraryRemoteAllTask` | `PublishLibraryRemoteGithubPackagesTask` | `PublishLibraryRemoteCentralTask` |
| Plugin | `PublishPluginLocalTask` | `PublishPluginRemoteAllTask` | `PublishPluginRemoteGithubPackagesTask` | `PublishPluginRemoteCentralTask` |

远程任务的目标由任务名决定，不再通过一个通用任务和 `publishTarget` 猜测。
Gradle、Android Gradle Plugin、`maven-publish` 和 `signing` 自身生成的标准任务
仍可能存在，但不属于 PublishPlugin 的公开任务集合。

以下历史任务不再注册：

- `PublishLibraryRemoteTask`；
- `generatePublishConfig`、`configurePublish`；
- `rollbackPublishSecrets`；
- 上述任务的大小写别名和 Central 别名。

这是一次公开任务 API 的破坏性变更。旧任务调用方请按目标迁移到对应的
`PublishLibraryRemote*Task` 或 `PublishPluginRemote*Task`。

## 共享配置与凭据隔离

### 组件和仓库配置

组件坐标、POM 元数据和 variant 规则写在模块 `PublishInfo`。非敏感的远程仓库
选择写在可提交的 `PublishRepositories` DSL：

```kotlin
configure<custom.android.plugin.PublishRepositories> {
    githubPackages {
        enabled.set(true)
        repository.set("OWNER/example-library")
        // 也可以显式指定 repositoryUrl / repositoryName。
    }

    central {
        enabled.set(true)
        namespace.set("cn.example")
        publishingType.set("user_managed")
    }
}
```

启用专用远程任务前，必须启用对应 provider；`RemoteAllTask` 至少需要一个已启用
的远程 provider。Central 的 `groupId`（以及预制 manifest 中的 publication）
必须位于配置的 namespace 下。

Android Library 默认只发布 `release` build type。需要发布其他 build type 或按
variant 过滤时，可在 `PublishInfo` 中配置：

```kotlin
PublishInfo {
    publishBuildTypes("release", "staging")
    publishVariantIf { variant -> variant.flavor("channel") != "internal" }
    artifactIdPattern = "{artifactId}-{flavor.channel}-{buildType}"
}
```

### 本机凭据

本机持久化配置文件是项目根目录下的
`.publish/local.properties`。这里的 `publish.local.*` 是配置键命名空间：
`publish` 表示由 PublishPlugin 使用，`local` 表示只在本机发布时读取；它不是
Android 的根目录 `local.properties`，也不会被 GitHub Actions 读取。键名后面的
`githubPackages` / `central` 表示 provider，最后一段表示具体字段。

使用已被 `.gitignore` 忽略的
[`.publish/local.properties.example`](.publish/local.properties.example) 作为模板：

```properties
publish.local.githubPackages.username=
publish.local.githubPackages.token=
publish.local.central.username=
publish.local.central.password=
publish.local.central.signingKeyFile=
publish.local.central.signingKeyId=
publish.local.central.signingPassword=
```

需要持久化本机配置时复制模板（生成文件已被 `.gitignore` 忽略）：

```bash
cp .publish/local.properties.example .publish/local.properties
```

字段含义和格式：

| 配置键 | 值的格式 |
| --- | --- |
| `publish.local.githubPackages.username` | GitHub 用户名；不是 token。 |
| `publish.local.githubPackages.token` | GitHub Packages token，按明文 property 值填写，不要提交文件。 |
| `publish.local.central.username` | Sonatype Central token 用户名。 |
| `publish.local.central.password` | Sonatype Central token 密码。 |
| `publish.local.central.signingKeyFile` | **文件路径**：相对路径按项目根目录解析，也可填写绝对路径。文件内容应是 ASCII-armored OpenPGP 私钥文本；这里不是文件名，也不是 Base64 字符串。 |
| `publish.local.central.signingKeyId` | 可选的 GPG key ID，例如 `00B5050F` 或 `0x00B5050F`。 |
| `publish.local.central.signingPassword` | 私钥口令（passphrase）。 |

例如，以下两种写法都表示“读取一个私钥文件”，不是把文件名或编码后的内容填进
配置：

```properties
# 相对项目根目录
publish.local.central.signingKeyFile=.secrets/central-signing-key.asc

# 或使用绝对路径
# publish.local.central.signingKeyFile=/Users/me/.keys/central-signing-key.asc
```

如果不想使用文件路径，也可以通过 `GPG_KEY_CONTENTS` 或
`-PsigningInMemoryKey=...` 直接提供 ASCII-armored 私钥内容；GitHub Actions 应使用
Secrets 注入该内容，而不是生成 `.publish/local.properties`。

本机配置文件路径默认为 `.publish/local.properties`，也可以使用
`-PpublishLocalConfig=/absolute/or/root-relative/path` 指定其他路径。

推荐优先使用环境变量；本机文件只用于本机执行，必须保持未跟踪且被
`.gitignore` 忽略。根目录 Android `local.properties` 不参与新的发布解析，也不会
被 PublishPlugin 写入发布字段。

常用环境变量：

| 目标 | 环境变量 |
| --- | --- |
| GitHub Packages 用户名 | `GITHUB_PACKAGES_USER` 或 `GITHUB_ACTOR` |
| GitHub Packages token | `GITHUB_PACKAGES_TOKEN` 或 `GITHUB_TOKEN` |
| Central 用户名 | `CENTRAL_USERNAME` 或 `MAVEN_CENTRAL_USERNAME` |
| Central 密码/token | `CENTRAL_PASSWORD` 或 `MAVEN_CENTRAL_PASSWORD` |
| GPG 私钥 | `GPG_KEY_CONTENTS` 或 `SIGNING_IN_MEMORY_KEY` |
| GPG key ID | `SIGNING_KEY_ID`（可选） |
| GPG 密码 | `SIGNING_PASSWORD` 或 `SIGNING_IN_MEMORY_KEY_PASSWORD` |

解析优先级按用途区分：显式 Gradle property/CI input 优先于环境变量和 DSL；本机
敏感值按 `Gradle property > 环境变量 > .publish/local.properties` 解析；GitHub
Actions 不回退到本机配置文件。

## 发布到远程仓库

### GitHub Packages

启用 `PublishRepositories.githubPackages` 后执行对应任务：

```bash
GITHUB_PACKAGES_USER="OWNER" \
GITHUB_PACKAGES_TOKEN="<token>" \
./gradlew :library:PublishLibraryRemoteGithubPackagesTask
```

也可以使用 `-PgithubPackagesRepository=OWNER/example-library` 或
`-PgithubPackagesUrl=https://maven.pkg.github.com/OWNER/example-library` 显式覆盖
仓库位置。

### Central Portal

Central release 需要 namespace、完整 POM/SCM 元数据、Central 凭据和 GPG 签名。
发布流程先上传 Maven layout 到 Central staging，再提交 Central Portal deployment；
snapshot 版本按 Central snapshot 规则处理。

```bash
CENTRAL_USERNAME="<central-token-username>" \
CENTRAL_PASSWORD="<central-token-password>" \
GPG_KEY_CONTENTS="<armored-private-key>" \
SIGNING_PASSWORD="<gpg-password>" \
./gradlew :library:PublishLibraryRemoteCentralTask
```

正式远程发布会拒绝包含 `debug` 的版本。发布失败不会自动重试；使用
`Publish*RemoteGithubPackagesTask` 或 `Publish*RemoteCentralTask` 对单个 provider
重试更安全。

### 同时发布到多个远程仓库

```bash
./gradlew :library:PublishLibraryRemoteAllTask
```

`RemoteAllTask` 按已启用 provider 依次执行。远程仓库之间没有事务性：如果前一个
provider 成功、后一个失败，任务会报告部分成功状态，后续应使用失败 provider 的
专用任务重试。

## 预制产物发布

当 AAR/JAR 已由其他 job 或其他构建系统产出时，使用 `artifactSource=prebuilt`：

```bash
./gradlew :library:PublishLibraryRemoteGithubPackagesTask \
  -PartifactSource=prebuilt \
  -PartifactBundlePath=release-artifacts/library
```

目录必须包含 `publish-artifacts.json`。manifest 明确声明每个 publication 的：

- `groupId`、`artifactId`、`version`、`packaging`；
- `main`、`pom`、`gradle_module`、`sources`、`javadoc`、`signature`、`checksum`
  等文件 role；
- 项目根目录内的相对路径、文件大小和 SHA-256。

发布器会在网络请求前校验 manifest、文件存在性、路径边界、大小、哈希和 Central
所需伴生文件；不会按文件名猜坐标，也不会上传 manifest 之外的文件。Central
对每个非 POM publication 至少需要 main、POM、sources、javadoc 和签名文件；POM
publication 需要 POM（或 plugin marker）和签名。

`Publish*LocalTask` 和 `publishToMavenLocal` 的输出不是 prebuilt bundle：其版本通常带
`-local`，目录中没有 `publish-artifacts.json`，并且本机 Maven 仓库不会自动传入 CI。
需要 CI 从当前提交重新构建时使用 `artifact_source: project`；只有已经具备 manifest、
目标版本和完整伴生文件的标准目录才使用 `prebuilt`。

预制产物格式和生成示例见
[发布架构中的 Prebuilt 产物章节](doc/tech/publish-architecture.md#prebuilt-产物)。

## GitHub Actions

仓库提供可复用 workflow：
[`.github/workflows/publish.yml`](.github/workflows/publish.yml)。业务仓库应提交
一个 caller workflow，只保存非敏感编排参数，并通过 `secrets: inherit` 或明确的
repository/organization Secrets 提供凭据：

```yaml
name: Publish Maven

on:
  workflow_dispatch:

permissions:
  contents: read
  packages: write

jobs:
  publish:
    uses: Entertech/PublishPlugin/.github/workflows/publish.yml@main
    secrets: inherit
    with:
      module: ":library"
      component_type: "library"
      publish_target: "github_packages"
      publish_mode: "release"
      version: "2.0.1"
```

使用预制产物时增加：

```yaml
      artifact_source: "prebuilt"
      artifact_bundle_path: "release-artifacts/library"
      artifact_bundle_artifact: "library-bundle"
```

如果产物来自 workflow 的前置 job，必须先用
`actions/download-artifact` 下载到 `artifact_bundle_path`；不同 job 不共享文件系统。
`artifact_bundle_artifact` 只能引用当前 workflow run 中已上传的 Actions artifact，
不能直接引用开发机文件或默认读取其他历史 run 的 artifact。
workflow 会校验组件类型、目标、版本和产物路径，并只映射到上表中的明确任务。
`publish_mode=ci` 仅支持 Central，并会为非 snapshot 版本自动追加 `-SNAPSHOT`；
`publish_mode=release` 拒绝 `-SNAPSHOT` 版本。

也可以在本机通过 `-PremotePublishMode=centralSnapshot` 使用 Central Snapshot；
未显式指定目标且版本已经以 `-SNAPSHOT` 结尾时，会自动选择该模式。Snapshot
发布不会调用 release deployment 的 manual upload endpoint。

调用 reusable workflow 时设置 `check_only: true` 可只运行 `checkPublish`；发布和
检查产生的 `publish-manifest` 都会作为 Actions artifact 上传（没有文件时忽略）。
workflow 的 check-only 使用 `structure` 级别，不需要注入任何 secret。本机
`checkPublish` 默认使用 `credentials`；可通过
`-PpublishValidationLevel=structure|credentials|remote` 明确选择。

Central 的 `project` 模式在 CI 构建时需要 GPG 私钥与口令；`prebuilt` 模式要求
manifest 已声明并携带 detached signatures，因此发布 job 只需要 Central 仓库凭据，
不会再次读取 GPG 私钥。

PR 必跑支持组合为 JDK 17 / Gradle 8.7 / AGP 8.1.3 和 JDK 21 / Gradle 8.10 /
AGP 8.5.2。`.github/workflows/compatibility-matrix.yml` 每周和手动运行完整 2×2×2
矩阵；交叉组合属于 experimental，失败不阻断维护。

远程任务默认在上传前执行版本存在性 preflight；`-PallowExistingVersion=true`
可显式允许已有版本，`-PpublishPreflight=false` 可关闭检查。All 任务部分成功后，
使用同一产物重新运行并增加 `-PresumePublish=true`，会根据 bundle fingerprint
跳过已成功 provider。manifest 同目录还会生成 CycloneDX SBOM、provenance、
provider state 和门禁结果。完整契约见
[发布架构](doc/tech/publish-architecture.md)。

完整的分支、PR、预发布和 Central 发布流程见
[分支与发布工作流](doc/workflow.md)。

## Codex Skills

仓库提供两个职责独立的 Codex Skill：

| Skill | 作用 | 是否发布 |
| --- | --- | --- |
| `$enter-publish-config` | 配置/校验 `PublishInfo`、`PublishRepositories`、本机模板、caller workflow 和 manifest。 | 否 |
| `$enter-publish-run` | 消费已有配置，在本机执行发布或触发 GitHub Actions。 | 是，必须有明确发布指令 |

推荐先配置、再单独发起发布请求。配置 Skill 不会运行发布任务、上传制品或触发
workflow；发布 Skill 发现配置缺失时会停止并交回配置 Skill。详细输入、交接模型
和示例见 [PublishPlugin Codex Skills 使用说明](doc/skills/publish-skills.md)。

## 版本迁移提示

### 1.2.3 的 sources 行为

从 `1.2.2` 升级后，所有目标都会附带 `sources.jar`，但默认
`PublishInfo.hasSource = false`，其中只包含 README 占位内容；需要公开真实源码的
组件必须设置：

```kotlin
PublishInfo {
    hasSource = true
}
```

兼容字段 `obfuscate` 仍可使用，但语义相反：`obfuscate = true` 等于
`hasSource = false`。完整变更记录见 [doc/changelog.md](doc/changelog.md)。

### 任务和配置迁移

| 旧用法 | 新用法 |
| --- | --- |
| `PublishLibraryRemoteTask` | 按目标改为 `PublishLibraryRemoteGithubPackagesTask`、`PublishLibraryRemoteCentralTask` 或 `PublishLibraryRemoteAllTask` |
| Plugin 模块使用 `PublishLibrary*Task` | 使用 `PublishPlugin*Task` |
| `generatePublishConfig` / `configurePublish` | 使用 `$enter-publish-config` 或按文档配置 caller workflow |
| 根目录 `local.properties` 中的发布字段 | 非敏感字段迁入 `PublishRepositories`；本机凭据迁入 `.publish/local.properties`；CI 凭据迁入 GitHub Secrets |

不要为了兼容旧任务而恢复已删除的别名；任务名和配置位置的变更应在升级时一并
迁移，并在发现旧文件包含凭据时先轮换相关 token/key。

## 仓库示例与开发验证

- [`demo-lib`](demo-lib/build.gradle.kts)：Android Library、多 flavor release variant；
- [`demo-plugin`](demo-plugin/build.gradle.kts)：Gradle Plugin、plugin marker publication。

提交前至少运行：

```bash
./gradlew :plugin_base:check
python3 .github/scripts/reusable_publish_workflow_test.py
./scripts/install-codex-skill.sh --check
git diff --check
```

仓库贡献和分支约束见 [doc/workflow.md](doc/workflow.md)。
