# PublishPlugin 发布任务与一键配置重构 PRD

## 文档信息

| 项目 | 内容 |
| --- | --- |
| 状态 | Implemented on redesign branch |
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

本方案明确区分四个互相独立的维度：

| 维度 | 可选值 | 含义 |
| --- | --- | --- |
| 组件类型 | `Library`、`Plugin` | 当前模块发布的是 Android/Java Library，还是 Gradle Plugin。 |
| 执行环境 | 本机、GitHub Actions | 发布命令由开发者电脑执行，还是由 CI runner 执行。 |
| 发布目标 | Maven Local、GitHub Packages、Central、未来远程仓库 | 制品最终写入的位置。 |
| 产物来源 | 当前工程构建、指定目录预制产物 | 发布前是构建当前工程，还是直接消费已有 AAR/JAR 及伴生文件。 |

“本机执行”不等于“发布到 Maven Local”。开发者可以在本机执行 GitHub Packages 或 Central 远程发布；GitHub Actions 也可以执行相同的远程任务。执行环境只决定配置与凭据来源，不改变任务语义。

“发布”也不等于“打包”。打包负责产出完整、可校验的制品集合；发布只负责把已经准备好的制品集合上传到目标仓库。默认模式可以先构建当前工程再发布，预制产物模式必须跳过工程编译和打包，直接发布指定目录中的文件。

## 产品目标

1. 每个应用 `cn.entertech.publish` 的模块只显示 4 个 PublishPlugin 自定义发布任务。
2. 任务名称直接表达组件类型和发布目标，不再依赖一个通用远程任务加 `publishTarget` 猜测行为。
3. Library 与 Gradle Plugin 使用各自对应的任务名。
4. 远程仓库采用可扩展模型，首期支持 GitHub Packages 和 Central，后续新增仓库时不重写核心任务选择逻辑。
5. 打包与发布逻辑完全解耦，发布层不依赖 Android/Java 编译过程，并能消费标准化的已有制品集合。
6. GitHub Actions 支持指定项目内目录，不打包当前工程，直接发布其中的 AAR/JAR 和伴生文件。
7. Codex Skill 按责任拆成两个独立入口：
   - `$enter-publish-config` 只配置、校验和生成模板/workflow，不执行发布；
   - `$enter-publish-run` 只消费既有配置，在本机执行发布或触发 GitHub Actions 发布。
8. 本机运行配置和 GitHub Actions 配置完全分离，不再把发布字段写入 Android 根目录 `local.properties`。
9. 组件元数据、非敏感仓库配置和敏感凭据各自只有清晰的归属位置。
10. 删除重复别名和误导性的历史任务名，降低 IDE 任务列表和文档认知成本。

## 非目标

1. 本需求不改变 Maven 坐标、POM、sources、javadoc、signing 和多 Variant publication 的业务规则。
2. 本需求不把多个业务模块聚合成一个根工程发布任务；调用方仍通过 Gradle module path 选择模块。
3. 本需求不支持在 GitHub Actions 中把制品发布到 runner 的 Maven Local 作为正式交付结果。
4. 本需求不在第一阶段开放第三方动态注入任意远程仓库 provider 的公共 SPI；首期只保证内部架构可扩展。
5. 本需求不自动迁移或删除业务仓库已有的 `local.properties` 敏感值；迁移工具只检测并给出安全处理提示。
6. 本需求不根据 AAR/JAR 文件名猜测 Maven 坐标、publication 或伴生文件角色；预制产物必须提供 manifest。

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

### 使用配置 skill 准备本机发布

用户指定模块、执行环境 `local` 和目标。`$enter-publish-config` 完成：

1. 识别模块组件类型。
2. 校验 `PublishInfo` 与非敏感仓库配置。
3. 按需生成独立的本机发布配置模板。
4. 校验配置文件未被 Git 跟踪且已被 ignore。
5. 输出准确的发布任务作为交接信息，但不执行该任务。

用户明确要求实际发布时，改由 `$enter-publish-run` 校验既有配置，
在本机执行对应任务并报告结果。配置请求本身不构成发布授权。

### 使用配置 skill 准备 GitHub Actions 发布

用户指定模块、执行环境 `github_actions` 和目标。`$enter-publish-config` 完成：

1. 识别模块组件类型并校验目标。
2. 生成或更新模块对应的 GitHub Actions workflow。
3. 校验 GitHub repository secrets；只写入缺失或明确允许覆盖的 secrets。
4. workflow 调用与组件类型、目标匹配的唯一任务。
5. 输出 caller workflow 与所需输入作为交接信息，但不触发 workflow。

用户明确要求发布后，`$enter-publish-run` 才能触发 caller workflow 并
跟踪结果。GitHub Actions 流程不读取本机发布配置文件。

### GitHub Actions 直接发布已有制品

业务仓库已经在当前提交中保存了制品，或在 workflow 前置步骤中把制品下载到项目的特定目录。调用方选择 `artifact_source=prebuilt` 并传入项目根目录下的相对路径：

```yaml
with:
  module: ":library"
  component_type: "library"
  publish_target: "central"
  artifact_source: "prebuilt"
  artifact_bundle_path: "release-artifacts/library"
```

GitHub Actions 必须：

1. checkout 当前项目；
2. 校验目录位于当前 workspace 内；
3. 读取目录中的 `publish-artifacts.json`；
4. 校验 manifest 声明的 AAR/JAR、POM、module metadata、sources、javadoc、签名等文件；
5. 不执行当前模块的 compile、assemble、bundle、jar 或其他打包任务；
6. 直接调用目标明确的发布任务上传该制品集合。

如果制品来自同一 workflow 的前置 job，应先通过 `actions/download-artifact` 下载到 `artifact_bundle_path`，再进入发布步骤；不能假设不同 job 共享文件系统。

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

### FR-5：打包与发布分离

系统必须建立统一的 `ArtifactBundle` 中间契约，把产物准备和仓库上传拆成两个独立阶段：

```text
Artifact producer -> ArtifactBundle -> validator -> repository publisher
```

产物 producer 支持两种模式：

| 模式 | 行为 |
| --- | --- |
| `project` | 执行当前工程必要的标准构建/产物生成逻辑，整理为 `ArtifactBundle`。 |
| `prebuilt` | 不构建当前工程，读取指定目录的 manifest 和已有文件，整理为同一种 `ArtifactBundle`。 |

发布层要求：

1. Maven Local、GitHub Packages、Central 和未来 provider 只接收 `ArtifactBundle`，不得直接调用 Android/Java 编译和打包 API。
2. 4 个公开发布任务是流程入口，可以编排“准备 → 校验 → 发布”，但打包实现和发布实现必须是独立组件。
3. 不新增公开的 PublishPlugin 打包 task；工程模式可复用 Gradle/AGP 已有标准任务，内部准备步骤不进入 `customPlugin` 任务集合。
4. 预制产物模式必须保证 compile、assemble、bundle、jar 等工程打包任务没有进入执行图。
5. 发布前完成全部本地校验；文件不完整、坐标冲突或校验和不匹配时，在任何网络请求之前失败。
6. 发布层不得修改调用方传入的预制目录；如需生成校验和或签名，复制到受控 staging 目录后处理。

`ArtifactBundle` 至少描述：

- 一个或多个 publication 的 `groupId`、`artifactId`、`version`、packaging；
- 主文件 AAR 或 JAR；
- POM；
- 可选 Gradle module metadata；
- 可选 sources JAR、javadoc JAR；
- 可选签名和 checksum 文件；
- 每个文件的相对路径、角色、大小和 SHA-256。

Central provider 可要求比 GitHub Packages 更完整的伴生文件。缺少目标仓库必需文件时，由 validator 给出缺失清单，publisher 不负责临时重新打包。

### FR-6：项目目录预制产物

本机和 GitHub Actions 都必须支持从项目根目录下的指定目录加载预制产物；GitHub Actions 是首要验收场景。

目录约定：

```text
release-artifacts/library/
  publish-artifacts.json
  demo-lib-2.0.0.aar
  demo-lib-2.0.0.pom
  demo-lib-2.0.0.module
  demo-lib-2.0.0-sources.jar
  demo-lib-2.0.0-javadoc.jar
  demo-lib-2.0.0.aar.asc
  ...
```

要求：

1. `artifact_bundle_path` 必须是相对项目根目录的路径；拒绝绝对路径、`..` 逃逸和解析后位于 workspace 外的 symlink。
2. `publish-artifacts.json` 必须存在，且其 schema version 受支持。
3. manifest 中的文件路径必须相对 bundle 目录，不能访问目录外文件。
4. manifest 坐标必须与调用方显式版本覆盖及 workflow 输入一致；不一致时失败，禁止静默改写 POM。
5. 支持一个目录声明多个 publications，满足多 Variant Library 和 Gradle Plugin marker publication。
6. 主文件只能是允许的发布类型，首期为 `.aar`、`.jar`；伴生文件必须在 allowlist 中。
7. GitHub Actions 必须输出 `artifact_source=prebuilt` 和经过规范化的相对目录，但不得输出 secret 或私钥内容。
8. 预制目录既可以来自 Git checkout，也可以由前置步骤下载；如果来自另一个 job，调用方必须显式下载 GitHub Actions artifact。

示例 manifest：

```json
{
  "schemaVersion": 1,
  "publications": [
    {
      "groupId": "cn.entertech.android",
      "artifactId": "demo-lib",
      "version": "2.0.0",
      "packaging": "aar",
      "files": [
        { "role": "main", "path": "demo-lib-2.0.0.aar", "sha256": "..." },
        { "role": "pom", "path": "demo-lib-2.0.0.pom", "sha256": "..." },
        { "role": "sources", "path": "demo-lib-2.0.0-sources.jar", "sha256": "..." }
      ]
    }
  ]
}
```

### FR-7：配置分层与文件隔离

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

### FR-8：本机配置文件

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

### FR-9：GitHub Actions 配置

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

workflow 还必须支持：

- `artifact_source=project|prebuilt`，默认 `project`；
- `artifact_bundle_path`，仅在 `prebuilt` 时必填；
- `artifact_source=prebuilt` 时跳过 JDK/Gradle 构建所需的打包步骤，只保留运行发布器所需的最小环境；
- 在任务执行图和日志中验证没有运行 compile/assemble/bundle/jar 等打包任务。

### FR-10：配置与发布 Skill

两个 Skill 使用相同的交接模型：

```text
module + execution(local|github_actions) + target(local|github_packages|central|all) + artifactSource(project|prebuilt)
```

行为要求：

1. `skills/enter-publish-config/` 对外名为 `$enter-publish-config`，只负责配置、校验、模板/workflow/manifest 生成和配置回退，禁止执行发布任务、上传制品或触发 workflow。
2. `skills/enter-publish-run/` 对外名为 `$enter-publish-run`，只在用户明确要求发布时消费已有配置；不得顺便创建、修复或改写发布配置。
3. `execution=local,target=local`：发布 Skill 校验后调用组件对应的 `LocalTask`。
4. `execution=local,target=<remote>`：发布 Skill 读取环境变量或 `.publish/local.properties`，调用对应远程任务。
5. `execution=github_actions,target=<remote>`：配置 Skill 生成/更新 caller workflow、检查 secrets 并映射任务；发布 Skill 只负责触发该 caller workflow 和报告结果。
6. `execution=github_actions,target=local`：两个 Skill 均拒绝，并解释 Maven Local 不是 CI 交付目标。
7. `artifactSource=prebuilt`：配置 Skill 要求 bundle path 并准备/校验 manifest；发布 Skill 在网络请求前再次校验 manifest，并明确报告跳过工程打包。
8. GitHub secret 值通过 stdin 传给 `gh`，不得出现在命令行参数和日志中。
9. 发布 Skill 一次请求只尝试一次；远程失败不得自动重试，`all` 必须报告部分成功状态。
10. 旧 `$publishplugin-local-release` 由 `$enter-publish-run` 完全替代，不再作为活动 runtime Skill 保留。
11. Skill 和离线脚本不得为了配置或发布重新注册公开 Gradle task。

### FR-11：配置优先级

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

### FR-12：日志与失败提示

每次发布前输出不含敏感值的摘要：

```text
module=:library
component=Library
execution=local|github_actions
target=local|github_packages|central|all
artifact_source=project|prebuilt
artifact_bundle=<generated>|<normalized project-relative path>
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
| `generatePublishConfig` / `configurePublish` | 使用 `$enter-publish-config` 或离线脚本的配置命令 |
| `rollbackPublishSecrets` | 使用 `$enter-publish-config` 或离线脚本的回退命令 |
| `$enter-one-click-publish-config` | `$enter-publish-config` |
| `$enter-publish-release` | `$enter-publish-run` |
| `$publishplugin-local-release` | 使用 `$enter-publish-run`，同时支持本机和 GitHub Actions 发布 |
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
12. `$enter-publish-config` 的配置/校验请求不会执行 Gradle 发布任务或触发 workflow；`$enter-publish-run` 仅在明确发布请求下分别完成本机或 GitHub Actions 发布。
13. 新增远程 provider 时，核心 All 任务无需增加新的 `when(providerId)` 分支。
14. 所有日志和测试夹具均不泄露 token、密码和私钥。
15. 工程模式先生成标准化 `ArtifactBundle`，随后由发布层消费；发布层代码不依赖 Android/Java 编译 API。
16. 预制模式能从项目指定目录发布 AAR/JAR 及其伴生文件，且 Gradle task graph 不包含工程打包任务。
17. GitHub Actions 支持 `artifact_source=prebuilt` 和 `artifact_bundle_path`，能直接发布 checkout 或下载到当前项目目录的制品。
18. manifest 缺文件、SHA-256 不匹配、坐标冲突、目录逃逸或 provider 必需伴生文件缺失时，在发起网络请求前失败。
19. 同一份预制 `ArtifactBundle` 可分别交给 Maven Local、GitHub Packages 和 Central publisher；provider 不重新生成主产物。

## 成功指标

1. IDE 中每个发布模块的 `customPlugin` 任务数从当前 14 个降为 4 个。
2. 用户无需查看 `publishTarget` 即可从任务名判断发布目标。
3. `local.properties` 中 PublishPlugin 新增字段数为 0。
4. Library/Plugin 错用任务名的支持问题归零。
5. 配置文档不再把本机配置与 GitHub Actions 配置放在同一模板，且配置 Skill 与发布 Skill 的职责无重叠。
6. 预制产物模式的 CI 日志中 compile/assemble/bundle/jar 执行数为 0。

## 风险与决策

1. 删除旧任务名会破坏现有脚本和 workflow；本需求选择清晰任务面优先，采用 major 版本迁移，不保留别名。
2. 多仓库发布无法原子回滚；`RemoteAllTask` 必须清楚报告部分成功，不承诺事务一致性。
3. 本机 properties 仍可能保存敏感值；因此文件为可选、必须 ignore，环境变量仍是推荐方式。
4. `GithubPackages` 大小写按本需求固定为任务公共 API；provider ID 和 workflow input 继续使用 `github_packages`。
5. 旧自定义 Maven 仓库不进入首期任务集合；如确认仍有使用方，应在实施前按新 provider 模型补充独立需求，而不是恢复通用远程任务。
6. 已有制品可能不满足 Central 完整性规则；方案选择在上传前严格校验并列出缺失伴生文件，不由 publisher 隐式重建。
7. GitHub Actions job 之间不共享 workspace；跨 job 的预制产物必须通过 Actions artifact 等显式传递机制下载到指定目录。
