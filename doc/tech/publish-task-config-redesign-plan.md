# PublishPlugin 发布任务与配置分层技术方案

## 文档信息

| 项目 | 内容 |
| --- | --- |
| 状态 | Implemented on redesign branch |
| 日期 | 2026-08-21 |
| 基线 | `main` / `e6840a1` |
| 对应 PRD | `doc/prd/publish-task-config-redesign-prd.md` |
| 影响范围 | `plugin_base`、reusable workflow、离线脚本、README、一键发布 skill |

## 方案摘要

本方案做五项结构性调整：

1. 用“组件类型 + 发布目标”生成唯一、明确的公开任务名，每个模块只注册 4 个 PublishPlugin 自定义任务。
2. 把当前 `PublishLibraryRemoteTask` 中的目标判断拆成远程仓库 provider，由目标任务显式选择 provider。
3. 把执行环境与发布目标解耦：同一个明确的 Gradle 发布任务既可在本机运行，也可在 GitHub Actions runner 运行。
4. 删除 PublishPlugin 对 Android 根目录 `local.properties` 的发布配置依赖，改为共享 DSL、本机专用 properties、GitHub workflow/Secrets 三层配置。
5. 在打包和仓库传输之间建立版本化 `ArtifactBundle` 契约，使发布器既能消费当前工程构建结果，也能直接消费项目指定目录中的已有 AAR/JAR 和伴生文件。

本方案取代现有 `doc/tech/publish-one-click-config-plan.md` 中以下设计结论：

- 在 `local.properties` 追加 `publish.*`；
- 正常发布运行时读取 `local.properties` fallback；
- 使用 `generatePublishConfig`、`configurePublish`、`rollbackPublishSecrets` Gradle task；
- 为配置任务注册大写和 `Central` 兼容别名；
- 使用单一 `PublishLibraryRemoteTask` + `publishTarget` 选择仓库。

现有 Central、多 Variant、POM、sources、javadoc、signing 规则继续有效，除非与本方案的任务名或配置来源冲突。

## 当前实现问题

### 任务注册

`PublishPlugin.kt` 当前无条件注册：

- 2 个发布任务；
- `generate/configure/rollback` 三类配置任务；
- 每类配置任务的当前名称、大写类名、旧 `Central` 名和旧大写类名。

这些 task class 都将 `group` 设置为 `customPlugin`，因此 IDE 展示 14 个入口。测试 `OneClickPublishTaskFunctionalTest` 还把全部别名固化为兼容行为。

### 远程任务

`PublishLibraryRemoteTask` 当前通过 `PublishConfigResolver.resolveRemotePublishMode()` 选择：

- GitHub Packages；
- Central；
- 旧自定义 Maven 仓库。

任务名本身不携带目标信息，publication 配置又依赖 `publishTarget` 和 start parameter 中的任务名判断，导致配置阶段与执行阶段耦合。

### 组件类型

当前插件能够识别 `com.android.library` 和 `java-gradle-plugin`，但任务注册没有根据组件类型切换名称。Gradle Plugin 模块仍得到 `PublishLibrary*` 任务。

### 配置来源

当前 `PublishConfigResolver`、配置模板和一键配置流程共同读取根目录 `local.properties`，其中同时出现：

- Android SDK 本机路径；
- 发布目标；
- POM/SCM fallback；
- GitHub workflow 选项；
- secret 名称；
- Central/GPG 一次性 secret 输入。

这使本机运行配置与 GitHub Actions 初始化配置无法独立理解和维护。

### 打包与发布耦合

当前 `BasePublishTask`/`PublishLibraryRemoteTask` 通过 nested Gradle 直接调用 `publish...PublicationTo...Repository`。标准 Maven Publish task 会在同一执行图里完成编译产物解析、POM/module metadata 生成、签名和网络上传，因此：

- publisher 无法只接收一个已经准备好的 AAR/JAR；
- GitHub Actions 无法声明“跳过打包，直接发布某目录”；
- provider 校验和产物生成互相依赖；
- 很难在任何网络请求前完整校验全部伴生文件。

新方案必须先在本地 staging 目录形成完整 bundle，再把网络传输作为独立阶段。

## 目标架构

```text
        +--------------------+       +----------------------+
        | project producer   |       | prebuilt producer    |
        | compile/package    |       | manifest + AAR/JAR   |
        +---------+----------+       +----------+-----------+
                  |                             |
                  +-------------+---------------+
                                |
                     +----------v-----------+
                     | PreparedArtifactBundle|
                     | immutable Maven layout|
                     +----------+-----------+
                                |
                     +----------v-----------+
                     | bundle validator     |
                     | common + target rules|
                     +----------+-----------+
                                |
                +---------------+----------------+
                |               |                |
        +-------v------+ +------v-------+ +------v-------+
        | Maven Local  | | GitHub Pkgs  | | Central      |
        | publisher    | | publisher    | | publisher    |
        +--------------+ +--------------+ +--------------+
```

执行环境在 producer 和 publisher 外围提供配置来源：本机使用环境变量/Gradle property/`.publish/local.properties`；GitHub Actions 使用 workflow inputs/Secrets。两种执行环境消费同一个 bundle 契约。

### 核心类型

建议新增内部模型：

```kotlin
internal enum class PublishComponentKind(
    val taskComponentName: String,
) {
    LIBRARY("Library"),
    PLUGIN("Plugin"),
}

internal enum class PublishDestination {
    LOCAL,
    GITHUB_PACKAGES,
    CENTRAL,
    ALL,
}

internal enum class ArtifactSource {
    PROJECT,
    PREBUILT,
}

internal data class PreparedArtifactBundle(
    val schemaVersion: Int,
    val rootDirectory: File,
    val publications: List<PreparedPublication>,
)

internal interface ArtifactBundleProducer {
    val source: ArtifactSource
    fun prepare(request: ArtifactPreparationRequest): PreparedArtifactBundle
}

internal interface RemoteRepositoryProvider {
    val id: String
    val taskNamePart: String
    val order: Int

    fun isEnabled(context: PublishContext): Boolean
    fun requirements(context: PublishContext): ArtifactRequirements
    fun validate(bundle: PreparedArtifactBundle, context: PublishContext)
    fun publish(bundle: PreparedArtifactBundle, context: PublishContext): PublishResult
    fun afterPublish(bundle: PreparedArtifactBundle, result: PublishResult, context: PublishContext)
}
```

首期实现：

```text
GithubPackagesRepositoryProvider
CentralRepositoryProvider
```

provider registry 由插件内部构造并注入任务，不在 resolver 中继续堆叠 `if/when`。

producer 首期实现：

```text
ProjectArtifactBundleProducer
PrebuiltArtifactBundleProducer
```

publisher/provider 只依赖 `PreparedArtifactBundle`，不依赖 Android `SoftwareComponent`、Java `SourceSet` 或编译 task。

## 公开任务设计

### 任务名生成

```kotlin
internal object PublishTaskNames {
    fun local(kind: PublishComponentKind) =
        "Publish${kind.taskComponentName}LocalTask"

    fun remoteAll(kind: PublishComponentKind) =
        "Publish${kind.taskComponentName}RemoteAllTask"

    fun remote(kind: PublishComponentKind, provider: RemoteRepositoryProvider) =
        "Publish${kind.taskComponentName}Remote${provider.taskNamePart}Task"
}
```

首期精确结果：

| 类型 | 任务 |
| --- | --- |
| Library | `PublishLibraryLocalTask` |
| Library | `PublishLibraryRemoteAllTask` |
| Library | `PublishLibraryRemoteGithubPackagesTask` |
| Library | `PublishLibraryRemoteCentralTask` |
| Plugin | `PublishPluginLocalTask` |
| Plugin | `PublishPluginRemoteAllTask` |
| Plugin | `PublishPluginRemoteGithubPackagesTask` |
| Plugin | `PublishPluginRemoteCentralTask` |

### 注册时机

当前 `afterProject` + display name 比较应替换为插件感知注册：

1. `plugins.withId("com.android.library")` 标记 Library candidate。
2. `plugins.withId("java-gradle-plugin")` 标记 Plugin candidate。
3. 在所有插件应用完成、publication 配置前解析唯一 `PublishComponentKind`。
4. 使用 project extra/internal state 防止重复注册。
5. 只注册对应类型的 4 个任务。

如果项目同时应用两类组件插件：

- 优先不猜测；
- 配置阶段失败；
- 提示拆分模块或显式采用后续支持的组件类型声明。

所有公开任务设置：

```kotlin
group = "customPlugin"
```

不再把配置辅助能力包装成 Gradle task。

### 任务实现类

建议从当前固定类名重构为参数化实现：

```text
AbstractPublishTask
  +-- PublishLocalTask
  +-- PublishRemoteTargetTask
  +-- PublishRemoteAllTask
```

Gradle task 的公开名称与 Kotlin 实现类解耦。Library/Plugin 不需要复制 8 个几乎相同的实现类。

`PublishRemoteTargetTask` 具有 internal task property：

```kotlin
@Internal
abstract val providerId: Property<String>

@Internal
abstract val componentKind: Property<PublishComponentKind>

@Input
abstract val artifactSource: Property<ArtifactSource>

@Optional
@InputDirectory
abstract val artifactBundleDirectory: DirectoryProperty
```

注册时固定 convention/value，用户不能用普通命令行 property 把 GitHub Packages task 改成 Central。

## 执行模型

### LocalTask

1. 根据 `artifactSource` 选择 project 或 prebuilt producer。
2. 准备并校验 `PreparedArtifactBundle`。
3. Maven Local publisher 把 bundle 发布到 `~/.m2/repository`。
4. 不配置远程 repository，不校验远程凭据，不启用 Central signing 要求。
5. 打印每个 publication 的 Maven Local 地址和依赖声明。

### Remote provider task

1. 根据 task 注册时固定的 provider ID 获取 provider。
2. 读取共享非敏感配置和当前执行环境的凭据。
3. 获取 provider 的 `ArtifactRequirements`。
4. producer 准备 `PreparedArtifactBundle`；预制模式只读取指定目录，不执行工程打包。
5. 通用 validator 与 provider validator 在网络请求前完成全部校验。
6. provider 只上传 bundle，不生成或修改主产物。
7. 执行 provider 的发布后动作并输出单一目标摘要。

### RemoteAllTask

1. 从 registry 按 `order` 获取显式启用的 providers。
2. 没有 provider 时失败。
3. 合并所有 provider 的 artifact requirements，并且只准备一次 bundle。
4. 在任何上传开始前，对所有目标完成预校验。
5. 逐个调用与单 provider task 相同的 publisher，不复制校验和传输逻辑。
6. 首期采用 fail-fast：某 provider 失败后不启动后续 provider。
7. 输出 `succeeded`、`failed`、`not_started` 三组结果。
8. 提示使用失败 provider 的专用任务重试。

远程仓库发布无法提供跨 provider 原子事务。文档和日志不得把 `All` 描述成“全部成功或全部回滚”。

### 工程产物准备

工程模式不直接把标准 Maven Publish task 指向远程仓库，而是指向插件创建的本地 staging Maven repository：

| publication 数量 | 准备 task |
| --- | --- |
| 单 publication | `publish<Publication>PublicationToPublishBundleStagingRepository` |
| 多 publication | `publishAllPublicationsToPublishBundleStagingRepository` |

staging 根目录：

```text
build/publish-bundles/<execution-id>/repository/
```

标准 Maven Publish 负责把当前工程的 AAR/JAR、POM、module metadata、sources、javadoc 和签名写成本地 Maven layout。随后 bundle scanner 生成 manifest 并计算 SHA-256。这个阶段不得发起任何远程网络请求。

当前 `BasePublishTask` 可暂时通过 nested Gradle 执行 staging task，但必须传入内部 preparation target，而不是远程 repository：

```text
-Pcn.entertech.publish.internalStage=artifact_bundle
-Pcn.entertech.publish.artifactRequirements=central
```

内部参数：

1. 只供插件发起的 nested build 和 TestKit 使用。
2. 不作为用户文档中的发布目标入口。
3. 只决定 staging bundle 需要包含哪些伴生文件，不决定上传仓库。
4. `RemoteAllTask` 传入所有 provider requirements 的并集。

长期可评估用 task dependency + BuildService 消除 nested Gradle，但 producer/publisher 接口不能因此合并。

## ArtifactBundle 契约

### 目录布局

运行时统一复制或生成到受控 staging：

```text
build/publish-bundles/<execution-id>/
  publish-artifacts.json
  repository/
    cn/entertech/android/demo-lib/2.0.0/
      demo-lib-2.0.0.aar
      demo-lib-2.0.0.pom
      demo-lib-2.0.0.module
      demo-lib-2.0.0-sources.jar
      demo-lib-2.0.0-javadoc.jar
      demo-lib-2.0.0.aar.asc
      ...
```

manifest 使用相对路径，至少包含：

```json
{
  "schemaVersion": 1,
  "publications": [
    {
      "name": "EnterPublish",
      "groupId": "cn.entertech.android",
      "artifactId": "demo-lib",
      "version": "2.0.0",
      "packaging": "aar",
      "files": [
        {
          "role": "main",
          "path": "repository/cn/entertech/android/demo-lib/2.0.0/demo-lib-2.0.0.aar",
          "size": 123456,
          "sha256": "..."
        }
      ]
    }
  ]
}
```

role allowlist 首期包括：

```text
main, pom, gradle_module, sources, javadoc, signature, checksum,
plugin_marker
```

### 预制目录加载

GitHub Actions 和本机共用 `PrebuiltArtifactBundleProducer`：

1. 输入为项目根目录相对路径 `artifactBundlePath`。
2. 使用 real path 校验最终目录位于 Gradle root project/workspace 内。
3. 拒绝绝对路径、`..` 逃逸、指向目录外的 symlink 和 manifest 外的额外待发布文件。
4. 读取并校验 `publish-artifacts.json` schema。
5. 校验所有文件存在、是普通文件、size 和 SHA-256 一致。
6. 校验 POM 坐标、文件名和 Maven layout 与 manifest 一致。
7. 把文件复制到本次 execution staging；不修改输入目录。
8. 预制模式的 Gradle task graph 不得包含 compile、assemble、bundle、jar 等工程产物生成任务。

目录可来自 Git checkout，也可由当前 job 的 `actions/download-artifact` 写入。不同 GitHub Actions job 没有共享 workspace，reusable workflow 如果支持 `artifact_bundle_artifact` 输入，应先下载到 `artifact_bundle_path` 再加载。

### 目标完整性规则

| 目标 | 必需文件 |
| --- | --- |
| Maven Local | main、POM；manifest 声明的其他文件必须完整。 |
| GitHub Packages | main、POM；Gradle module metadata/sources/javadoc 按 manifest 原样发布。 |
| Central release | main、POM、sources、javadoc、所有 Central 要求的签名；module metadata 如声明则完整发布。 |
| Central snapshot | main、POM、sources、javadoc、签名策略按现有 Central snapshot 规则。 |

Gradle Plugin bundle 还必须覆盖 implementation publication 和必要的 plugin marker publications。多 Variant Library 可在同一 manifest 中声明多个 publications。

缺少文件时 validator 一次性列出全部缺失项。publisher 不调用 producer 补文件；如允许使用本机/CI signing 凭据为预制文件补签，必须作为显式的 bundle finalization 步骤在 staging 中完成，并重新生成 manifest，仍然发生在 publisher 之前。

### 仓库传输

远程 provider 不再调用会触发工程打包的 `publish...PublicationTo...Repository`，而是通过独立 transport 上传 staging Maven layout：

```kotlin
internal interface MavenRepositoryTransport {
    fun publish(
        repository: ResolvedRepository,
        bundle: PreparedArtifactBundle,
        credentials: ResolvedCredentials,
    ): PublishResult
}
```

传输规则：

1. 只上传 manifest 声明并通过校验的文件，不递归上传目录中的未知文件。
2. 远程路径由经过校验的 Maven 坐标和文件角色生成，不直接拼接未经验证的用户输入。
3. GitHub Packages transport 使用目标 Maven repository URL 和 package credentials。
4. Central transport 先把完整 Maven layout 上传到 Central staging/snapshot endpoint；release 成功后再由 `CentralPortalClient` 执行现有 manual upload/publish 行为。
5. Maven Local publisher 使用同一 Maven layout 写入用户 Maven Local，不调用 Android/Java 打包逻辑。
6. 传输层负责认证、超时、重试、响应脱敏和部分成功记录，不负责生成 POM、签名或主产物。
7. `RemoteAllTask` 复用同一份 immutable staging bundle，各 provider 不得相互修改文件。

## Publication 配置调整

### 删除任务名字符串推断

当前 `PublishConfigResolver.isCentralPublish()` 会检查 start parameter 中是否包含 `PublishLibraryRemoteTask`。新方案改为：

```text
internalTarget > 当前明确公开任务映射 > false
```

`PublishLibraryRemoteAllTask` / `PublishPluginRemoteAllTask` 的顶层 task action 本身不直接执行 publication；它为每个 provider 启动带明确 internal target 的 nested build。因此每个 nested build 只配置当前 provider 所需 publication 能力。

### Library 与 Plugin publication

Library：

- Android Library 继续发布 release component；
- 多 Variant 继续生成 `<VariantName>EnterPublish`；
- `skipVariantIf` 继续在 `singleVariant` 注册和 publication 创建两阶段生效。

Plugin：

- 使用 `java` component；
- 保持 Gradle Plugin marker/publication 规则；
- 使用 `PublishPlugin*` 公开任务名；
- Central 所需 POM、sources、javadoc、signing 校验与 Library 使用同一 provider。

## 配置模型

### 共享组件配置：PublishInfo

继续保留在模块 Gradle DSL：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "2.0.0"

    pomName = "Demo Library"
    pomDescription = "Demo Android library"
    pomUrl = "https://github.com/Entertech/demo-lib"
}
```

Gradle Plugin 模块继续要求：

```kotlin
PublishInfo {
    groupId = "cn.entertech.gradle"
    artifactId = "demo-plugin"
    version = "2.0.0"
    pluginId = "cn.entertech.demo"
    implementationClass = "cn.entertech.demo.DemoPlugin"
}
```

组件坐标、多 Variant callback、POM 元数据、`hasSource` 不进入 properties 文件。

### 共享非敏感仓库配置：PublishRepositories

新增独立 extension，避免继续把仓库行为混入组件元数据：

```kotlin
PublishRepositories {
    githubPackages {
        enabled = true
        repository = "Entertech/demo-lib"
    }

    central {
        enabled = true
        namespace = "cn.entertech"
        publishingType = "user_managed"
    }
}
```

设计原则：

1. 只允许非敏感、可入库字段。
2. provider 默认 `enabled = false`，避免应用插件后意外进入远程发布。
3. provider 专用任务在未启用时失败并提示 DSL 示例。
4. `RemoteAllTask` 只读取 `enabled = true` 的 providers。
5. 多模块仓库可通过 convention plugin 或 root convention 复用配置，本方案不在 properties 中设计 module map。

建议字段：

| Provider | 字段 |
| --- | --- |
| GitHub Packages | `enabled`、`repository`、可选 `repositoryUrl`、`repositoryName` |
| Central | `enabled`、`namespace`、`publishingType`、`releaseRepositoryName`、`snapshotRepositoryName` |

License、Developer、SCM 默认值如果属于所有组件共享的组织约定，应保留代码默认或进入明确的共享 DSL；不再放进本机配置文件。

### 本机专用配置

默认路径：

```text
.publish/local.properties
```

允许通过以下参数覆盖路径：

```text
-PpublishLocalConfig=/absolute/or/root-relative/path
```

示例模板：

```properties
# Local execution only. Keep this file ignored and untracked.

# GitHub Packages credentials.
publish.local.githubPackages.username=
publish.local.githubPackages.token=

# Central Portal User Token and signing credentials.
publish.local.central.username=
publish.local.central.password=
publish.local.central.signingKeyFile=
publish.local.central.signingKeyId=
publish.local.central.signingPassword=
```

解析规则：

```text
Gradle property > environment variable > .publish/local.properties
```

建议映射：

| 本机 key | Gradle property | 环境变量 |
| --- | --- | --- |
| `publish.local.githubPackages.username` | `githubPackagesUsername` | `GITHUB_ACTOR` |
| `publish.local.githubPackages.token` | `githubPackagesPassword` | `GITHUB_TOKEN` / `GITHUB_PACKAGES_TOKEN` |
| `publish.local.central.username` | `centralUsername` | `CENTRAL_USERNAME` / `MAVEN_CENTRAL_USERNAME` |
| `publish.local.central.password` | `centralPassword` | `CENTRAL_PASSWORD` / `MAVEN_CENTRAL_PASSWORD` |
| `publish.local.central.signingKeyFile` | `signingInMemoryKeyFile` | 不直接映射文件；CI 使用 key contents secret |
| `publish.local.central.signingKeyId` | `signingInMemoryKeyId` | `SIGNING_KEY_ID` |
| `publish.local.central.signingPassword` | `signingInMemoryKeyPassword` | `SIGNING_PASSWORD` |

读取 signing key 文件后只把内容放入 nested Gradle 的 stdin-safe 环境或临时受控通道，不拼入可见命令参数。实现阶段必须验证 Gradle 子进程传递方式不会打印私钥。

安全要求：

1. 模板写入器只操作 `.publish/local.properties`，绝不操作根目录 `local.properties`。
2. 自动确保 `.gitignore` 包含 `/.publish/local.properties`。
3. 已 tracked 时拒绝写入或读取敏感值，并输出 `git rm --cached` 与凭据轮换提示。
4. 文件权限在类 Unix 系统上尽量设置为 owner read/write；设置失败给 warning，不打印内容。
5. CI 环境检测到该文件时也不读取。

### GitHub Actions 配置

CI 非敏感配置直接进入 tracked workflow：

```yaml
# Generated by PublishPlugin one-click publish
name: Publish library

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
      component_type: "library"
      publish_target: "central"
      publish_mode: "release"
      version: "2.0.0"
      sync_readme: true
```

不再需要 `publish.githubActions`、`publish.workflowPath`、`publish.workflowUses` 等本机 properties。它们是 skill/脚本参数或生成 workflow 的直接内容。

CI 凭据只来自 GitHub Secrets。workflow 不创建 `.publish/local.properties`，也不读取根目录 `local.properties`。

## Reusable workflow 改造

### 输入

保留 `module`、`publish_target`、`publish_mode` 等现有输入，新增或规范化：

```yaml
component_type:
  required: true
  type: string
artifact_source:
  required: false
  default: "project"
  type: string
artifact_bundle_path:
  required: false
  default: ""
  type: string
artifact_bundle_artifact:
  required: false
  default: ""
  type: string
```

允许值：

```text
library
plugin
```

`publish_target` 首期允许：

```text
github_packages
central
all
```

`artifact_source` 允许 `project` 和 `prebuilt`。输入规则：

1. `project` 模式按当前工程准备 bundle，不接受非空 `artifact_bundle_path`。
2. `prebuilt` 模式要求 `artifact_bundle_path` 是项目根目录相对路径。
3. `artifact_bundle_artifact` 非空时，workflow 先通过 `actions/download-artifact` 下载上游 job 产物到 bundle path。
4. `artifact_bundle_artifact` 为空时，bundle 目录必须已存在于 checkout 或当前 job workspace。
5. 路径校验完成后，预制模式不得执行 assemble、compile、bundle、jar 等打包步骤。

### Allowlist 任务映射

workflow 不接受任意 `publish_task` 输入，而是在 shell 中进行固定映射：

| component_type | publish_target | task suffix |
| --- | --- | --- |
| `library` | `github_packages` | `PublishLibraryRemoteGithubPackagesTask` |
| `library` | `central` | `PublishLibraryRemoteCentralTask` |
| `library` | `all` | `PublishLibraryRemoteAllTask` |
| `plugin` | `github_packages` | `PublishPluginRemoteGithubPackagesTask` |
| `plugin` | `central` | `PublishPluginRemoteCentralTask` |
| `plugin` | `all` | `PublishPluginRemoteAllTask` |

任何其他组合在执行 Gradle 前失败。

当前 workflow 对 `all` 分别调用两次通用 `PublishLibraryRemoteTask`。新方案只调用一次 `RemoteAllTask`，由插件内部 provider registry 保证与本机行为一致。

预制模式调用同一个目标任务，并传入：

```text
-PartifactSource=prebuilt
-PartifactBundlePath=<validated-relative-path>
```

任务内部使用 `PrebuiltArtifactBundleProducer`。workflow 不能用任意 shell `find` 结果直接上传，也不能根据扩展名猜测 Maven 坐标。

### CI snapshot

保留现有约束：

- `publish_mode=ci` 只支持 Central snapshot；
- 版本追加 `-SNAPSHOT`；
- 不调用 Central release manual upload；
- 不同步 README。

此时 workflow 映射到组件对应的 `RemoteCentralTask`，通过 `publishMode=ci` 选择 Central snapshot repository，而不是选择另一个公开 task。

## 一键发布 skill 与脚本改造

### Skill 输入模型

skill 的内部意图模型：

```text
module: :library
execution: local | github_actions
target: local | github_packages | central | all
artifact_source: project | prebuilt
artifact_bundle_path: <project-relative path, required for prebuilt>
action: configure | dry_run | publish | rollback
```

约束：

- `github_actions + local` 非法；
- `local + local` 不要求远程配置文件；
- `target=all` 要求至少两个或一个显式启用 provider；
- `artifact_source=prebuilt` 要求 manifest 和项目内相对目录，并明确跳过打包；
- `action=publish` 才执行真实上传；
- `rollback` 只处理 skill/脚本创建的 workflow 或 GitHub secrets，不注册 Gradle task。

### 本机流程

```text
inspect module
  -> detect component kind
  -> validate PublishInfo / PublishRepositories
  -> choose project or prebuilt producer
  -> optionally create .publish/local.properties
  -> validate ArtifactBundle before network
  -> verify ignored/untracked
  -> resolve exact task name
  -> dry run or execute Gradle
```

### GitHub Actions 流程

```text
inspect module
  -> detect component kind
  -> validate shared DSL
  -> generate workflow
  -> configure project/prebuilt artifact inputs
  -> gh auth/repository checks
  -> list/write missing secrets via stdin
  -> dry run or gh workflow run
```

### 离线脚本

替换现有依赖 `generatePublishConfig` / `configurePublish` task 的脚本接口，建议：

```bash
scripts/configure-publish-offline.sh \
  --module :library \
  --execution local \
  --target central \
  --configure

scripts/configure-publish-offline.sh \
  --module :library \
  --execution github-actions \
  --target central \
  --artifact-source prebuilt \
  --artifact-bundle-path release-artifacts/library \
  --configure
```

脚本负责模板/workflow 文件操作和安全检查，不注册或调用配置类 Gradle task。实际发布时调用精确发布任务。

## Resolver 重构

将当前 `PublishConfigResolver` 按责任拆分：

```text
PublishMetadataResolver
  - coordinates, version, POM, SCM, sources policy

PublishRepositoryResolver
  - non-secret provider configuration from DSL / explicit overrides

LocalCredentialResolver
  - Gradle props, env, .publish/local.properties

CiCredentialContract
  - expected environment variable names only

PublishExecutionPlanner
  - component kind + destination + artifact source -> producer/task/provider plan

ArtifactBundleResolver
  - manifest schema, paths, publications, file roles, SHA-256
```

必须删除的 fallback：

- `local.properties publish.*`；
- `local.properties publishUserName/publishPassword`；
- `PublishInfo.publishUserName/publishPassword` 作为新远程凭据来源；
- 通过通用 `publishTarget` 改写具体远程 task。

旧字段的值只允许迁移检查器识别字段名并提示去向，不参与新运行时解析。

## 文件改造计划

### 新增

| 文件 | 责任 |
| --- | --- |
| `PublishComponentKind.kt` | 唯一组件类型识别模型。 |
| `PublishRepositories.kt` | 非敏感 provider DSL。 |
| `ArtifactBundle.kt` | 版本化 manifest、publication 和文件角色模型。 |
| `ArtifactBundleProducer.kt` | project/prebuilt producer 接口。 |
| `ProjectArtifactBundleProducer.kt` | 将当前工程 publications 写入本地 staging Maven layout。 |
| `PrebuiltArtifactBundleProducer.kt` | 从项目指定目录安全加载已有 AAR/JAR 与伴生文件。 |
| `ArtifactBundleValidator.kt` | 通用完整性、路径、坐标、size、SHA-256 校验。 |
| `ArtifactBundleManifestCodec.kt` | `publish-artifacts.json` 读写和 schema version 校验。 |
| `MavenLocalPublisher.kt` | 仅消费 bundle 的 Maven Local 发布器。 |
| `MavenRepositoryTransport.kt` | 只上传 manifest allowlist 文件的 Maven 仓库传输层。 |
| `RemoteRepositoryProvider.kt` | 只消费 bundle 的 provider 内部接口。 |
| `GithubPackagesRepositoryProvider.kt` | GitHub Packages bundle 校验与传输。 |
| `CentralRepositoryProvider.kt` | Central bundle 完整性、传输和上传后动作。 |
| `PublishProviderRegistry.kt` | provider 顺序与查找。 |
| `PublishTaskNames.kt` | 精确公共任务名生成。 |
| `PublishLocalTask.kt` | 参数化本地任务实现。 |
| `PublishRemoteTargetTask.kt` | 参数化单 provider 任务实现。 |
| `PublishRemoteAllTask.kt` | All 编排与部分成功摘要。 |
| `LocalPublishConfigLoader.kt` | 只读取 `.publish/local.properties`。 |
| `LocalPublishConfigTemplateWriter.kt` | 本机配置模板与 Git ignore 安全检查。 |
| `PublishExecutionPlanner.kt` | 组件类型、目标和产物来源到执行计划的映射。 |
| `LegacyPublishConfigScanner.kt` | 只读识别旧字段并输出迁移提示。 |

### 修改

| 文件 | 修改点 |
| --- | --- |
| `PublishPlugin.kt` | 按组件类型只注册 4 个任务；通过 provider registry 配仓库。 |
| `BasePublishTask.kt` | 仅编排 producer、validator、publisher，移除打包/上传混合实现。 |
| `PublishConfigResolver.kt` | 拆分职责，移除 `local.properties` 和通用目标 fallback。 |
| `PublishInfo.kt` | 移除或 deprecated 敏感仓库凭据字段；保留组件元数据。 |
| `CentralPortalClient.kt` | 由 Central provider 调用。 |
| `.github/workflows/publish.yml` | 增加 component allowlist、project/prebuilt 模式、bundle path 和可选 Actions artifact 下载。 |
| 业务示例 workflow | 增加 `component_type` 并更新 task 语义。 |
| `scripts/configure-publish-offline.sh` | 改为直接配置 local 或 GitHub Actions。 |
| `skills/publishplugin-one-click-publish/SKILL.md` | 改为 execution × target × artifact source 三维流程。 |
| skill reference | 删除 `local.properties` 混合模板与配置 Gradle tasks。 |
| `README.md` | 更新任务、配置位置、迁移和示例。 |

### 删除

在迁移完成后删除：

- `PublishLibraryRemoteTask.kt`；
- `GeneratePublishConfigTask.kt`；
- `ConfigurePublishTask.kt`；
- `RollbackPublishSecretsTask.kt`；
- 仅服务于旧混合配置的 loader/template 字段和测试。

如果实现复用其中内部类，必须改名并确保不再注册旧公开任务。

## 分阶段实施

### Phase 1：任务契约测试

1. 新增 Library fixture 任务列表测试，只允许 4 个精确名称。
2. 新增 Plugin fixture 任务列表测试，只允许 4 个精确名称。
3. 把现有“所有兼容别名必须存在”测试改成“旧任务全部不存在”。
4. 新增组件类型冲突测试。
5. 先得到失败测试，再修改注册逻辑。

### Phase 2：ArtifactBundle 与打包发布分层

1. 定义 versioned manifest、file role 和 `PreparedArtifactBundle`。
2. 使用本地 staging Maven repository 实现 project producer。
3. 实现 prebuilt producer、workspace 路径安全和 SHA-256 校验。
4. 抽取通用 validator 和 provider requirements。
5. 让 Maven Local publisher 只消费 bundle。
6. 添加测试证明预制模式不执行 compile/assemble/bundle/jar。
7. 添加多 publication、Gradle Plugin marker 和多 Variant manifest 测试。

### Phase 3：provider 抽取

1. 建立 registry 和两个首期 providers。
2. 把 GitHub Packages 校验/仓库命令从 `PublishLibraryRemoteTask` 移入 provider。
3. 把 Central 校验、signing、manual upload 移入 provider。
4. 实现单目标 task。
5. 实现 All task 和部分成功摘要。
6. 移除通用 `publishTarget` 对具体 task 的改写能力。

### Phase 4：Library/Plugin 任务落地

1. 完成唯一组件类型识别。
2. 注册对应类型的 4 个任务。
3. 验证 Library 单 publication、多 Variant publication。
4. 验证 Gradle Plugin publication 和 plugin marker。
5. 删除所有任务别名。

### Phase 5：配置分层

1. 新增 `PublishRepositories` DSL。
2. 新增 `.publish/local.properties` loader/template。
3. 删除发布运行时 `local.properties` fallback。
4. 新增旧配置只读扫描和迁移报告。
5. 验证本机敏感字段不出现在命令行和日志。

### Phase 6：Actions、脚本与 skill

1. reusable workflow 增加 `component_type` allowlist。
2. 更新 Library/Plugin 示例 workflow。
3. 增加 `artifact_source`、`artifact_bundle_path` 和可选 Actions artifact 下载。
4. 验证 prebuilt workflow 不运行打包任务。
5. 重写离线脚本，不再调用配置类 Gradle task。
6. 更新仓库内一键发布 skill 及 reference。
7. 执行 `./scripts/install-codex-skill.sh` 安装/验证仓库 source-of-truth symlink。

### Phase 7：文档与迁移

1. 更新 README 的所有旧任务名和配置示例。
2. 标明 major 版本和删除别名的影响。
3. 发布旧字段到新位置的迁移表。
4. 在试点 Library 和 Plugin 仓库分别演练本机与 GitHub Actions 发布。

## 测试方案

### Task 注册

- Library 只注册 4 个 Library 任务。
- Plugin 只注册 4 个 Plugin 任务。
- 旧 `RemoteTask`、generate/configure/rollback 及所有别名不存在。
- 重复 plugin callback 不会重复注册。
- 模糊组件类型会失败。

### 本地发布

- 无远程配置时 Maven Local 成功。
- Library 单 release、多 Variant 都生成真实 Maven Local 产物。
- Plugin 生成 implementation publication 和必要 marker。
- LocalTask 不读取远程 credentials。

### ArtifactBundle 与预制产物

- project producer 只向本地 staging repository 写文件，不发起网络请求。
- project producer 生成的 manifest 覆盖 AAR/JAR、POM、module metadata 和实际伴生文件。
- prebuilt producer 可加载项目相对目录中的 AAR 或 JAR bundle。
- manifest schema version 不支持、文件缺失、size/SHA-256 不一致时失败。
- 拒绝绝对路径、`..`、workspace 外 real path 和逃逸 symlink。
- 输入目录保持不变，所有补签或 checksum 都在 staging 副本完成。
- 一个 manifest 支持多 Variant publications 和 Gradle Plugin marker publications。
- 同一 bundle 可被 Maven Local、GitHub Packages、Central publisher 消费。
- transport 只上传 manifest allowlist 文件，目录中的未知文件不会上传。
- prebuilt task graph 明确断言没有 compile、assemble、bundle、jar 任务。

### GitHub Packages

- 专用任务只配置/调用 GitHub Packages repository。
- 缺 repository、username、token 时分别给出明确错误。
- 环境变量覆盖 `.publish/local.properties`。
- 多 publication 选择 `publishAllPublicationsTo...Repository`。

### Central

- 专用任务启用 Central POM、javadoc、sources、signing。
- namespace、publishing type、token、GPG 缺失分别失败。
- release 执行 manual upload；snapshot 不执行。
- GitHub Packages 配置不会影响 Central task。

### All

- 只启用 GitHub Packages 时只执行该 provider。
- 两个 provider 启用时按固定顺序执行。
- 第一个失败时后续为 `not_started`。
- 第二个失败时摘要显示第一个已成功。
- 没有 provider 时失败。

### 配置隔离

- 模板只写 `.publish/local.properties`。
- 根目录 `local.properties` 内容和时间戳保持不变。
- tracked 本机配置文件被拒绝。
- GitHub Actions 测试 fixture 即使存在本机配置也不会读取。
- 旧字段只出现在迁移报告，不影响解析结果。

### Workflow

- 6 种 component/remote target 组合映射到正确任务。
- 非法 component/target 在调用 Gradle 前失败。
- `all` 只调用一个 `RemoteAllTask`。
- secrets 只注入发布步骤。
- CI snapshot 仍只允许 Central。
- `artifact_source=project` 走 project producer。
- `artifact_source=prebuilt` 要求并校验 `artifact_bundle_path`。
- 指定 `artifact_bundle_artifact` 时先下载 Actions artifact，再从指定目录发布。
- prebuilt workflow 日志和 task graph 不包含工程打包步骤。
- bundle 目录不存在、路径逃逸或 manifest 无效时，在 secret 注入和网络上传前失败。

### 安全

- fake `gh` 验证 secret 通过 stdin 写入。
- fake Gradle/exec 验证命令参数不含 token、密码和私钥。
- 日志脱敏测试覆盖成功、校验失败和 nested build 失败。
- 旧配置扫描只输出 key，不输出 value。

## 验证命令

实现阶段至少执行：

```bash
./gradlew :plugin_base:test --console=plain
./gradlew :plugin_base:build --console=plain
./gradlew test --console=plain
```

任务列表验收：

```bash
./gradlew :demo-lib:tasks --all
./gradlew :demo-plugin:tasks --all
```

真实发布验收：

```bash
./gradlew :demo-lib:PublishLibraryLocalTask --stacktrace
./gradlew :demo-plugin:PublishPluginLocalTask --stacktrace
```

远程发布必须在测试仓库和测试坐标执行，Central 不得复用已发布 release 版本。

skill 变更后：

```bash
./scripts/install-codex-skill.sh
./scripts/install-codex-skill.sh --check
```

## 兼容与发布策略

本方案不保留旧公开任务别名，因此必须作为 major 版本发布。建议：

1. 前一个 minor 版本先加入迁移扫描器和 deprecation 文档，但不新增更多任务别名。
2. major 版本删除旧任务和旧运行时 fallback。
3. reusable workflow 使用带 major tag 的引用，避免业务 workflow 在未迁移时被 `main` 静默破坏。
4. 为业务仓库提供自动替换建议，但不自动提交或 push。
5. 迁移期对检测到的旧 secret 字段要求轮换，不只移动文件。

## 关键风险

1. **任务名破坏性变更**：现有 CI 会直接失败。通过 major tag、迁移扫描和 release note 控制。
2. **Plugin publication 差异**：Gradle Plugin 可能包含 marker publications，测试必须验证 All/target task 不遗漏或重复发布。
3. **nested Gradle 凭据传递**：如果继续使用子进程，必须确保敏感值不进入参数和日志。
4. **All 部分成功**：无法跨远程仓库回滚，必须输出可操作的结果摘要。
5. **旧 customRepository 使用方**：在编码前应通过代码搜索或发布日志确认是否仍有真实调用；若有，按 provider 模型补需求。
6. **旧 skill 约束冲突**：当前 skill 把 `local.properties` 作为固定边界。实现本方案时必须同步更新仓库 skill、reference、README 和旧技术文档，不能只改插件代码。
7. **预制文件可信度**：已有 AAR/JAR 可能被替换或与 POM 坐标不一致。manifest、SHA-256、路径和坐标必须在网络请求前验证。
8. **CI job 文件隔离**：上游 job 生成的目录不会自动出现在发布 job。workflow 必须显式下载 Actions artifact，不能把路径存在性当成跨 job 保证。
9. **伴生文件差异**：GitHub Packages 可接受的 bundle 可能不满足 Central。requirements validator 必须按目标校验，不能由 publisher 临时构建缺失文件。

## 完成定义

1. PRD 的验收标准全部有自动化测试或明确的人工验证记录。
2. 每类模块 `customPlugin` 只显示 4 个任务。
3. GitHub Packages、Central、All 在 Library 与 Plugin 模块均有端到端覆盖。
4. 根目录 `local.properties` 与 PublishPlugin 完全解耦。
5. 本机和 GitHub Actions 配置、凭据来源与文档完全分离。
6. README、离线脚本、reusable workflow、skill 和 skill reference 与新契约一致。
7. 全量测试通过，试点仓库本机与 CI 发布成功。
8. project 和 prebuilt 两种产物来源都先形成相同的 `PreparedArtifactBundle`，所有 publisher 只消费该契约。
9. GitHub Actions 能从当前项目指定目录直接发布 AAR/JAR 及伴生文件，且没有执行工程打包任务。
