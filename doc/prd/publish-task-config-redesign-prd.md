# PublishPlugin 发布任务与一键配置重构 PRD

## 文档信息

| 项目 | 内容 |
| --- | --- |
| 状态 | Draft，待评审 |
| 日期 | 2026-08-21 |
| 基线 | `main` / `e6840a1` |
| 目标分支 | `codex/publish-task-config-redesign-docs` |
| 关联技术方案 | `doc/tech/publish-task-config-redesign-plan.md` |

## 背景

当前 PublishPlugin 把“组件类型”“发布目标”“执行环境”和“一键配置”混在了一套任务与 `local.properties` 配置中，主要表现为：

1. 插件在 `customPlugin` 分组中注册了 14 个任务，但实际只有本地发布、远程发布、生成配置、配置 Actions、回退 secrets 等少数几类能力。
2. `generatePublishConfig`、`configurePublish`、`rollbackPublishSecrets` 同时存在新名称、类名式名称和历史 `Central` 名称，IDE 中显示大量重复入口。
3. `PublishLibraryRemoteTask` 通过 `publishTarget` 在 GitHub Packages、Central 和旧自定义 Maven 仓库之间切换，任务名称无法直接说明最终发布目标。
4. Gradle Plugin 模块仍显示 `PublishLibrary*` 任务，组件类型与任务名称不匹配。
5. 本机发布运行参数、GitHub Actions workflow 配置、GitHub repository secrets 的一次性输入被集中写进根目录 `local.properties`，导致开发者无法判断哪些字段用于本机、哪些字段只用于 CI 初始化。
6. `local.properties` 本来承载 Android SDK 等本机配置，继续追加大量发布字段会放大误配置和敏感信息泄露风险。

本需求重新定义 PublishPlugin 的公开任务面和一键发布配置边界。

## 核心概念

本方案明确区分三个互相独立的维度：

| 维度 | 可选值 | 含义 |
| --- | --- | --- |
| 组件类型 | `Library`、`Plugin` | 当前模块发布的是 Android/Java Library，还是 Gradle Plugin。 |
| 执行环境 | 本机、GitHub Actions | 发布命令由开发者电脑执行，还是由 CI runner 执行。 |
| 发布目标 | Maven Local、GitHub Packages、Central、未来远程仓库 | 制品最终写入的位置。 |

“本机执行”不等于“发布到 Maven Local”。开发者可以在本机执行 GitHub Packages 或 Central 远程发布；GitHub Actions 也可以执行相同的远程任务。执行环境只决定配置与凭据来源，不改变任务语义。

## 产品目标

1. 每个应用 `cn.entertech.publish` 的模块只显示 4 个 PublishPlugin 自定义发布任务。
2. 任务名称直接表达组件类型和发布目标，不再依赖一个通用远程任务加 `publishTarget` 猜测行为。
3. Library 与 Gradle Plugin 使用各自对应的任务名。
4. 远程仓库采用可扩展模型，首期支持 GitHub Packages 和 Central，后续新增仓库时不重写核心任务选择逻辑。
5. 一键发布 skill 同时支持：
   - 配置并在本机运行发布；
   - 配置并通过 GitHub Actions 运行发布。
6. 本机运行配置和 GitHub Actions 配置完全分离，不再把发布字段写入 Android 根目录 `local.properties`。
7. 组件元数据、非敏感仓库配置和敏感凭据各自只有清晰的归属位置。
8. 删除重复别名和误导性的历史任务名，降低 IDE 任务列表和文档认知成本。

## 非目标

1. 本需求不改变 Maven 坐标、POM、sources、javadoc、signing 和多 Variant publication 的业务规则。
2. 本需求不把多个业务模块聚合成一个根工程发布任务；调用方仍通过 Gradle module path 选择模块。
3. 本需求不支持在 GitHub Actions 中把制品发布到 runner 的 Maven Local 作为正式交付结果。
4. 本需求不在第一阶段开放第三方动态注入任意远程仓库 provider 的公共 SPI；首期只保证内部架构可扩展。
5. 本需求不自动迁移或删除业务仓库已有的 `local.properties` 敏感值；迁移工具只检测并给出安全处理提示。

## 用户角色与使用场景

### Library 开发者本地联调

开发者只需要执行：

```bash
./gradlew :library:PublishLibraryLocalTask
```

任务发布到 `~/.m2/repository`，不要求 GitHub Packages、Central、GitHub Actions 或 GitHub Secrets 配置。

### Library 开发者从本机发布远程仓库

开发者选择明确的仓库任务：

```bash
./gradlew :library:PublishLibraryRemoteGithubPackagesTask
./gradlew :library:PublishLibraryRemoteCentralTask
./gradlew :library:PublishLibraryRemoteAllTask
```

凭据来自环境变量、Gradle property 或独立的本机发布配置文件，不读取根目录 `local.properties`。

### Gradle Plugin 开发者发布

Gradle Plugin 模块使用 `PublishPlugin*` 任务：

```bash
./gradlew :plugin:PublishPluginLocalTask
./gradlew :plugin:PublishPluginRemoteGithubPackagesTask
./gradlew :plugin:PublishPluginRemoteCentralTask
./gradlew :plugin:PublishPluginRemoteAllTask
```

任务名不再出现 `Library`。

### 使用一键发布 skill 配置本机发布

用户指定模块、执行环境 `local` 和目标。skill 完成：

1. 识别模块组件类型。
2. 校验 `PublishInfo` 与非敏感仓库配置。
3. 按需生成独立的本机发布配置模板。
4. 校验配置文件未被 Git 跟踪且已被 ignore。
5. 选择准确的发布任务。
6. 用户要求实际发布时，在本机执行对应任务并报告结果。

### 使用一键发布 skill 配置 GitHub Actions 发布

用户指定模块、执行环境 `github_actions` 和目标。skill 完成：

1. 识别模块组件类型并校验目标。
2. 生成或更新模块对应的 GitHub Actions workflow。
3. 校验 GitHub repository secrets；只写入缺失或明确允许覆盖的 secrets。
4. workflow 调用与组件类型、目标匹配的唯一任务。
5. 用户要求实际发布并授权后，通过 GitHub Actions 触发发布。

GitHub Actions 流程不读取本机发布配置文件。

## 功能需求

### FR-1：组件类型识别

插件必须把应用模块识别为且仅识别为以下一种类型：

- `Library`：应用 `com.android.library`，且不是 Gradle Plugin 模块。
- `Plugin`：应用 `java-gradle-plugin`，或满足插件现有 Gradle Plugin 识别规则。

约束：

1. 同一模块如果同时满足两种类型且无法确定唯一语义，配置阶段必须失败并输出解决建议。
2. 尚未应用支持的组件插件时，不提前注册错误类型的任务；在插件解析完成后再注册。
3. 每个模块只允许执行一次 PublishPlugin 自定义任务注册。

### FR-2：唯一公开任务集合

Library 模块只注册以下 4 个 `customPlugin` 任务：

```text
PublishLibraryLocalTask
PublishLibraryRemoteAllTask
PublishLibraryRemoteGithubPackagesTask
PublishLibraryRemoteCentralTask
```

Gradle Plugin 模块只注册以下 4 个 `customPlugin` 任务：

```text
PublishPluginLocalTask
PublishPluginRemoteAllTask
PublishPluginRemoteGithubPackagesTask
PublishPluginRemoteCentralTask
```

“只有这些任务”指 `cn.entertech.publish` 主动注册到 `customPlugin` 分组的公开任务。Gradle `maven-publish`、`signing`、AGP 或 `java-gradle-plugin` 自身生成的标准任务仍可存在于其他分组。

以下 PublishPlugin 自定义任务不再注册：

- `PublishLibraryRemoteTask`
- `generatePublishConfig`
- `GeneratePublishConfigTask`
- `generateCentralPublishConfig`
- `GenerateCentralPublishConfigTask`
- `configurePublish`
- `ConfigurePublishTask`
- `configureCentralPublish`
- `ConfigureCentralPublishTask`
- `rollbackPublishSecrets`
- `RollbackPublishSecretsTask`
- `rollbackCentralPublishSecrets`
- `RollbackCentralPublishSecretsTask`

不保留隐藏别名、deprecated 分组或大小写兼容任务，避免 IDE 继续显示多余入口。该调整按破坏性版本升级处理。

### FR-3：任务语义

| 任务后缀 | 行为 |
| --- | --- |
| `LocalTask` | 发布当前模块的全部有效 publications 到 Maven Local。不得要求任何远程仓库凭据。 |
| `RemoteGithubPackagesTask` | 只发布到 GitHub Packages。不得因为其他 provider 已配置而发布到其他仓库。 |
| `RemoteCentralTask` | 只发布到 Central。执行 Central POM、namespace、signing 和凭据校验。 |
| `RemoteAllTask` | 按 provider 注册顺序发布到当前模块显式启用的全部远程仓库。首期为 GitHub Packages、Central。 |

额外约束：

1. 具体仓库任务的行为不能再由 `publishTarget` 改写。
2. `RemoteAllTask` 不具备跨仓库事务性。若前一个仓库成功、后一个失败，必须明确报告部分成功状态，并提示使用失败仓库的专用任务重试。
3. `RemoteAllTask` 没有启用任何远程仓库时必须失败，不能静默成功。
4. 远程任务必须拒绝包含 `debug` 的正式发布版本；现有 CI snapshot 规则另行保留。
5. 多 Variant 模块的每个任务必须发布全部未过滤的 `*EnterPublish` publications。

### FR-4：远程仓库扩展能力

首期 provider：

| Provider ID | 任务名称片段 | 状态 |
| --- | --- | --- |
| `github_packages` | `GithubPackages` | 必须支持 |
| `central` | `Central` | 必须支持 |

后续新增 provider 时应满足：

1. 独立封装仓库配置、凭据校验、底层 Gradle publish task 选择和发布后动作。
2. 自动纳入 `RemoteAllTask` 的 provider 列表。
3. 新增明确的目标任务，例如 `PublishLibraryRemoteCompanyMavenTask`。
4. 不向用户重新引入一个依赖 `publishTarget` 的模糊远程入口。

旧 `customRepository` 不属于首期公开 provider，不被 `RemoteAllTask` 隐式调用。如仍有业务需求，应按新 provider 模型单独立项并提供明确任务名。

### FR-5：配置分层与文件隔离

配置必须按责任分为四层：

| 配置类别 | 推荐位置 | 是否入库 | 使用方 |
| --- | --- | --- | --- |
| 组件坐标、POM、多 Variant 规则 | 模块 `PublishInfo` | 是 | 本机与 CI 共用 |
| 非敏感仓库配置 | 模块 Gradle DSL `PublishRepositories` | 是 | 本机与 CI 共用 |
| 本机凭据和本机覆盖值 | 根目录 `.publish/local.properties` | 否 | 仅本机任务 |
| CI 编排与凭据 | `.github/workflows/publish-<module>.yml` + GitHub Secrets | workflow 入库，secret 不入库 | 仅 GitHub Actions |

硬性规则：

1. PublishPlugin 不再向 Android 根目录 `local.properties` 写入任何 `publish.*` 字段。
2. 新发布运行时不读取 `local.properties` 中的 `publish.*`、`publishUserName` 或 `publishPassword`。
3. `.publish/local.properties` 只用于本机执行；GitHub Actions workflow 不得读取或生成该文件。
4. CI secret 不得持久化到仓库内的任何 properties/yaml 文件。
5. 不敏感且本机、CI 共用的配置不得在两份配置文件里重复维护，应进入 Gradle DSL。

### FR-6：本机配置文件

`.publish/local.properties` 是可选文件。推荐优先使用环境变量；需要持久化本机凭据时才生成。

示例：

```properties
# Local execution only. Keep this file ignored and untracked.
publish.local.githubPackages.username=
publish.local.githubPackages.token=
publish.local.central.username=
publish.local.central.password=
publish.local.central.signingKeyFile=
publish.local.central.signingKeyId=
publish.local.central.signingPassword=
```

要求：

1. 模板 value 默认留空。
2. 文件必须被 `.gitignore` 忽略；如已被 Git 跟踪则拒绝继续并提示迁移和轮换凭据。
3. 不包含 workflow path、workflow uses、GitHub repository secret 名称或 Actions 开关。
4. Maven Local 发布不要求该文件存在。

### FR-7：GitHub Actions 配置

GitHub Actions 的非敏感编排配置直接进入模块 workflow，不再通过本地 properties 中转：

- module path；
- component type；
- remote target；
- reusable workflow reference；
- Central publishing type 等允许公开的 workflow input。

敏感值仅来自 GitHub repository/organization secrets。首期沿用或迁移下列 secret 语义：

- GitHub Packages：`GITHUB_TOKEN` 或显式 package token。
- Central：`MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`。
- Signing：`GPG_KEY_CONTENTS`、`SIGNING_PASSWORD`、可选 `SIGNING_KEY_ID`。

workflow 必须按 allowlist 将输入映射为明确任务名，不能接受任意 Gradle task 字符串。

### FR-8：一键发布 skill

`skills/publishplugin-one-click-publish/` 必须升级为双执行环境入口：

```text
module + execution(local|github_actions) + target(local|github_packages|central|all)
```

行为要求：

1. `execution=local,target=local`：校验后调用组件对应的 `LocalTask`。
2. `execution=local,target=<remote>`：读取环境变量或 `.publish/local.properties`，调用对应远程任务。
3. `execution=github_actions,target=<remote>`：生成/更新 workflow、检查 secrets，并映射到对应远程任务。
4. `execution=github_actions,target=local`：拒绝并解释 Maven Local 不是 CI 交付目标。
5. 配置、预演和实际发布是独立步骤；默认先预演，只有用户要求运行发布时才执行实际发布。
6. GitHub secret 值通过 stdin 传给 `gh`，不得出现在命令行参数和日志中。
7. skill 和离线脚本可以承担生成配置、配置 secrets、回退配置等辅助动作，但不得为这些动作重新注册公开 Gradle task。

### FR-9：配置优先级

组件元数据：

```text
Gradle property / CI input 显式覆盖 > PublishInfo > 默认值或推导值
```

非敏感仓库配置：

```text
Gradle property / CI input 显式覆盖 > PublishRepositories > 默认值或推导值
```

本机敏感凭据：

```text
Gradle property > 环境变量 > .publish/local.properties
```

GitHub Actions 敏感凭据：

```text
GitHub Actions secret > 明确失败
```

CI 不得回退到 `.publish/local.properties`，本机任务也不得把 GitHub repository secret 当作配置来源。

### FR-10：日志与失败提示

每次发布前输出不含敏感值的摘要：

```text
module=:library
component=Library
execution=local|github_actions
target=local|github_packages|central|all
publications=...
```

失败信息必须至少包含：缺少的配置名、应放置的位置、可执行的修复命令或示例。日志不得输出 token、密码、私钥或完整 Authorization header。

## 交互流程

### 本机执行

```text
选择 module / target
        |
        v
识别 Library 或 Plugin
        |
        v
校验 PublishInfo / PublishRepositories
        |
        +-- local ------------> 运行 LocalTask
        |
        +-- remote -----------> 读取本机凭据
                                  |
                                  v
                            运行明确的 Remote task
```

### GitHub Actions 执行

```text
选择 module / remote target
        |
        v
识别 Library 或 Plugin
        |
        v
生成 tracked workflow
        |
        v
校验/写入 GitHub Secrets
        |
        v
workflow allowlist 映射到明确 Remote task
```

## 迁移要求

| 旧用法 | 新用法 |
| --- | --- |
| `PublishLibraryRemoteTask` 默认发布 GitHub Packages | `PublishLibraryRemoteGithubPackagesTask` |
| `PublishLibraryRemoteTask -PpublishTarget=central` | `PublishLibraryRemoteCentralTask` |
| `PublishLibraryRemoteTask -PpublishTarget=all` | `PublishLibraryRemoteAllTask` |
| Plugin 模块的 `PublishLibraryLocalTask` | `PublishPluginLocalTask` |
| Plugin 模块的 `PublishLibraryRemoteTask` | 根据目标改为 `PublishPluginRemote*Task` |
| `generatePublishConfig` / `configurePublish` | 使用一键发布 skill 或离线脚本的配置命令 |
| `rollbackPublishSecrets` | 使用一键发布 skill 或离线脚本的回退命令 |
| 根目录 `local.properties` 中的发布字段 | 非敏感共享字段迁入 DSL；本机凭据迁入 `.publish/local.properties`；CI 凭据迁入 GitHub Secrets |

迁移版本必须：

1. 在 release note 中明确这是公开任务名和配置位置的破坏性变更。
2. 提供只读迁移检查，列出旧字段去向，但不打印字段值。
3. 检测到旧文件含敏感字段时提示先轮换相关 token/key。
4. 不在新版本继续读取旧字段，以避免配置表面迁移、实际仍走旧 fallback。

## 验收标准

1. Library fixture 的 `customPlugin` 分组只包含 4 个 Library 任务，名称与 FR-2 完全一致。
2. Plugin fixture 的 `customPlugin` 分组只包含 4 个 Plugin 任务，名称与 FR-2 完全一致。
3. 两类模块都不再注册 `PublishLibraryRemoteTask`、配置类任务及其任何历史别名。
4. Maven Local 任务在没有任何远程配置和凭据时成功。
5. GitHub Packages 与 Central 专用任务只触发自身 provider。
6. `RemoteAllTask` 只执行显式启用的 provider，全部成功时成功，部分失败时报告已成功与失败目标。
7. 多 Variant Library 的四个任务都能处理全部未过滤 publications。
8. Gradle Plugin 模块使用 `java` component，并通过 `PublishPlugin*` 任务成功发布。
9. 根目录 `local.properties` 不再被模板写入器修改，也不参与新发布解析。
10. 本机远程发布可通过环境变量或 `.publish/local.properties` 获取凭据。
11. GitHub Actions 发布只通过 workflow inputs 和 GitHub Secrets 获取配置，不读取本机配置文件。
12. 一键发布 skill 能分别完成本机发布和 GitHub Actions 发布的配置、预演与执行。
13. 新增远程 provider 时，核心 All 任务无需增加新的 `when(providerId)` 分支。
14. 所有日志和测试夹具均不泄露 token、密码和私钥。

## 成功指标

1. IDE 中每个发布模块的 `customPlugin` 任务数从当前 14 个降为 4 个。
2. 用户无需查看 `publishTarget` 即可从任务名判断发布目标。
3. `local.properties` 中 PublishPlugin 新增字段数为 0。
4. Library/Plugin 错用任务名的支持问题归零。
5. 一键发布配置文档中不再把本机配置与 GitHub Actions 配置放在同一模板。

## 风险与决策

1. 删除旧任务名会破坏现有脚本和 workflow；本需求选择清晰任务面优先，采用 major 版本迁移，不保留别名。
2. 多仓库发布无法原子回滚；`RemoteAllTask` 必须清楚报告部分成功，不承诺事务一致性。
3. 本机 properties 仍可能保存敏感值；因此文件为可选、必须 ignore，环境变量仍是推荐方式。
4. `GithubPackages` 大小写按本需求固定为任务公共 API；provider ID 和 workflow input 继续使用 `github_packages`。
5. 旧自定义 Maven 仓库不进入首期任务集合；如确认仍有使用方，应在实施前按新 provider 模型补充独立需求，而不是恢复通用远程任务。
