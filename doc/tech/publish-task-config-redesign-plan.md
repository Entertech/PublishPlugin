# PublishPlugin 发布任务与配置分层技术方案

## 文档信息

| 项目 | 内容 |
| --- | --- |
| 状态 | Draft，待评审 |
| 日期 | 2026-08-21 |
| 基线 | `main` / `e6840a1` |
| 对应 PRD | `doc/prd/publish-task-config-redesign-prd.md` |
| 影响范围 | `plugin_base`、reusable workflow、离线脚本、README、一键发布 skill |

## 方案摘要

本方案做四项结构性调整：

1. 用“组件类型 + 发布目标”生成唯一、明确的公开任务名，每个模块只注册 4 个 PublishPlugin 自定义任务。
2. 把当前 `PublishLibraryRemoteTask` 中的目标判断拆成远程仓库 provider，由目标任务显式选择 provider。
3. 把执行环境与发布目标解耦：同一个明确的 Gradle 发布任务既可在本机运行，也可在 GitHub Actions runner 运行。
4. 删除 PublishPlugin 对 Android 根目录 `local.properties` 的发布配置依赖，改为共享 DSL、本机专用 properties、GitHub workflow/Secrets 三层配置。

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

## 目标架构

```text
                         +----------------------+
                         | PublishInfo          |
                         | component metadata   |
                         +----------+-----------+
                                    |
                         +----------v-----------+
                         | PublishRepositories  |
                         | non-secret providers |
                         +----------+-----------+
                                    |
                  +-----------------+-----------------+
                  |                                   |
        +---------v----------+              +---------v----------+
        | local execution    |              | GitHub Actions     |
        | env / Gradle prop  |              | workflow + Secrets |
        | .publish/local...  |              | no local file      |
        +---------+----------+              +---------+----------+
                  |                                   |
                  +-----------------+-----------------+
                                    |
                         +----------v-----------+
                         | explicit public task |
                         +----------+-----------+
                                    |
                         +----------v-----------+
                         | repository provider  |
                         +----------------------+
```

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

internal interface RemoteRepositoryProvider {
    val id: String
    val taskNamePart: String
    val order: Int

    fun isEnabled(context: PublishContext): Boolean
    fun validate(context: PublishContext)
    fun configureRepository(context: PublishContext)
    fun resolvePublishCommand(context: PublishContext): String
    fun afterPublish(context: PublishContext, output: String)
}
```

首期实现：

```text
GithubPackagesRepositoryProvider
CentralRepositoryProvider
```

provider registry 由插件内部构造并注入任务，不在 resolver 中继续堆叠 `if/when`。

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
```

注册时固定 convention/value，用户不能用普通命令行 property 把 GitHub Packages task 改成 Central。

## 执行模型

### LocalTask

1. 校验 `PublishInfo` 的最终坐标。
2. 使用现有多 publication 解析结果。
3. 执行当前模块的 `publishToMavenLocal`。
4. 不配置远程 repository，不校验远程凭据，不启用 Central signing 要求。
5. 打印每个 publication 的 Maven Local 地址和依赖声明。

### Remote provider task

1. 根据 task 注册时固定的 provider ID 获取 provider。
2. 读取共享非敏感配置和当前执行环境的凭据。
3. provider 独立校验配置。
4. 设置内部 target 标识并执行底层标准 Maven Publish task。
5. 执行 provider 的发布后动作。
6. 输出单一目标的结果摘要。

### RemoteAllTask

1. 从 registry 按 `order` 获取显式启用的 providers。
2. 没有 provider 时失败。
3. 逐个调用与单 provider task 相同的执行服务，不复制校验和命令选择逻辑。
4. 首期采用 fail-fast：某 provider 失败后不启动后续 provider。
5. 输出 `succeeded`、`failed`、`not_started` 三组结果。
6. 提示使用失败 provider 的专用任务重试。

远程仓库发布无法提供跨 provider 原子事务。文档和日志不得把 `All` 描述成“全部成功或全部回滚”。

### 底层 Gradle task 选择

保留现有 Maven Publish task 规则：

| publication 数量 | provider | 底层 task |
| --- | --- | --- |
| 单 publication | GitHub Packages | `publish<Publication>PublicationTo<RepositoryName>Repository` |
| 单 publication | Central | `publish<Publication>PublicationTo<RepositoryName>Repository` |
| 多 publication | 任意远程 provider | `publishAllPublicationsTo<RepositoryName>Repository` |
| 任意 publication | Maven Local | `publishToMavenLocal` |

当前 `BasePublishTask` 通过启动 nested Gradle 执行标准 task。第一阶段可保留该机制以降低改造范围，但必须通过内部 property 明确传递 provider：

```text
-Pcn.entertech.publish.internalTarget=github_packages
-Pcn.entertech.publish.internalTarget=central
-Pcn.entertech.publish.internalTarget=local
```

内部 property：

1. 只供插件发起的 nested build 和 TestKit 使用。
2. 不作为用户文档中的目标选择入口。
3. 优先级高于任务名推断。
4. 让 publication 配置阶段明确知道是否需要 Central signing/javadoc。

长期可评估用 task dependency + BuildService 消除 nested Gradle，但不作为本次任务重构前置条件。

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
action: configure | dry_run | publish | rollback
```

约束：

- `github_actions + local` 非法；
- `local + local` 不要求远程配置文件；
- `target=all` 要求至少两个或一个显式启用 provider；
- `action=publish` 才执行真实上传；
- `rollback` 只处理 skill/脚本创建的 workflow 或 GitHub secrets，不注册 Gradle task。

### 本机流程

```text
inspect module
  -> detect component kind
  -> validate PublishInfo / PublishRepositories
  -> optionally create .publish/local.properties
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
  - component kind + destination -> task/provider plan
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
| `RemoteRepositoryProvider.kt` | provider 内部接口。 |
| `GithubPackagesRepositoryProvider.kt` | GitHub Packages 配置、校验、命令选择。 |
| `CentralRepositoryProvider.kt` | Central 配置、签名、上传后动作。 |
| `PublishProviderRegistry.kt` | provider 顺序与查找。 |
| `PublishTaskNames.kt` | 精确公共任务名生成。 |
| `PublishLocalTask.kt` | 参数化本地任务实现。 |
| `PublishRemoteTargetTask.kt` | 参数化单 provider 任务实现。 |
| `PublishRemoteAllTask.kt` | All 编排与部分成功摘要。 |
| `LocalPublishConfigLoader.kt` | 只读取 `.publish/local.properties`。 |
| `LocalPublishConfigTemplateWriter.kt` | 本机配置模板与 Git ignore 安全检查。 |
| `PublishExecutionPlanner.kt` | 目标到执行计划映射。 |
| `LegacyPublishConfigScanner.kt` | 只读识别旧字段并输出迁移提示。 |

### 修改

| 文件 | 修改点 |
| --- | --- |
| `PublishPlugin.kt` | 按组件类型只注册 4 个任务；通过 provider registry 配仓库。 |
| `BasePublishTask.kt` | 抽取可复用执行服务，支持 kind/provider 参数。 |
| `PublishConfigResolver.kt` | 拆分职责，移除 `local.properties` 和通用目标 fallback。 |
| `PublishInfo.kt` | 移除或 deprecated 敏感仓库凭据字段；保留组件元数据。 |
| `CentralPortalClient.kt` | 由 Central provider 调用。 |
| `.github/workflows/publish.yml` | 增加 component allowlist 映射并调用新任务。 |
| 业务示例 workflow | 增加 `component_type` 并更新 task 语义。 |
| `scripts/configure-publish-offline.sh` | 改为直接配置 local 或 GitHub Actions。 |
| `skills/publishplugin-one-click-publish/SKILL.md` | 改为 execution × target 双维度流程。 |
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

### Phase 2：provider 抽取

1. 建立 registry 和两个首期 providers。
2. 把 GitHub Packages 校验/仓库命令从 `PublishLibraryRemoteTask` 移入 provider。
3. 把 Central 校验、signing、manual upload 移入 provider。
4. 实现单目标 task。
5. 实现 All task 和部分成功摘要。
6. 移除通用 `publishTarget` 对具体 task 的改写能力。

### Phase 3：Library/Plugin 任务落地

1. 完成唯一组件类型识别。
2. 注册对应类型的 4 个任务。
3. 验证 Library 单 publication、多 Variant publication。
4. 验证 Gradle Plugin publication 和 plugin marker。
5. 删除所有任务别名。

### Phase 4：配置分层

1. 新增 `PublishRepositories` DSL。
2. 新增 `.publish/local.properties` loader/template。
3. 删除发布运行时 `local.properties` fallback。
4. 新增旧配置只读扫描和迁移报告。
5. 验证本机敏感字段不出现在命令行和日志。

### Phase 5：Actions、脚本与 skill

1. reusable workflow 增加 `component_type` allowlist。
2. 更新 Library/Plugin 示例 workflow。
3. 重写离线脚本，不再调用配置类 Gradle task。
4. 更新仓库内一键发布 skill 及 reference。
5. 执行 `./scripts/install-codex-skill.sh` 安装/验证仓库 source-of-truth symlink。

### Phase 6：文档与迁移

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

## 完成定义

1. PRD 的 14 条验收标准全部有自动化测试或明确的人工验证记录。
2. 每类模块 `customPlugin` 只显示 4 个任务。
3. GitHub Packages、Central、All 在 Library 与 Plugin 模块均有端到端覆盖。
4. 根目录 `local.properties` 与 PublishPlugin 完全解耦。
5. 本机和 GitHub Actions 配置、凭据来源与文档完全分离。
6. README、离线脚本、reusable workflow、skill 和 skill reference 与新契约一致。
7. 全量测试通过，试点仓库本机与 CI 发布成功。
