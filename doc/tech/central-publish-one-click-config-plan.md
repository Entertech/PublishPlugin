# PublishPlugin Central 一键配置方案

## 背景

其他 Android Library 或 Gradle Plugin 仓库接入 `cn.entertech.publish` 发布到 Sonatype Central Portal 时，需要同时完成几类配置：

1. 模块 `build.gradle.kts` / `build.gradle` 中的 `PublishInfo { ... }` 元数据。
2. GitHub Actions 发布所需的 repository secrets。
3. GitHub Actions workflow 入口。
4. 敏感信息误写入仓库后的回退和修复提示。

如果这些步骤都靠人工处理，容易出现以下问题：

- `centralNamespace` 与 `groupId` 不匹配，发布前才失败。
- POM 信息不完整或推导失败，例如 `pomUrl` 无法从当前工程 git remote / CI 获取。
- Central token 或 GPG 私钥被误写进 `build.gradle.kts`、`local.properties`、`gradle.properties` 并提交。
- CI 里 secret 名称与 reusable workflow 期望不一致。
- `user_managed` 上传成功后，误以为已经发布到 Maven Central。

本方案提供随插件发布的 Gradle task，让业务仓库只维护一个仓库级通用配置文件，然后一键完成通用发布配置、GitHub repository secrets、workflow 文件和安全校验。各个组件自己的发布坐标、POM 描述、Gradle Plugin ID 等信息仍以模块内 `PublishInfo { ... }` 为主，避免多模块仓库在配置文件里重复维护同一批组件字段。

## 设计结论

采用方案 1：**Gradle task 形式的一键配置 CLI**。

业务仓库在目标模块执行：

```bash
./gradlew :library:configureCentralPublish
```

兼容当前插件已有的大写任务风格，同时注册：

```bash
./gradlew :library:ConfigureCentralPublishTask
```

回退 GitHub repository secrets 使用：

```bash
./gradlew :library:rollbackCentralPublishSecrets
```

兼容大写任务名：

```bash
./gradlew :library:RollbackCentralPublishSecretsTask
```

生成本地配置模板使用：

```bash
./gradlew :library:generateCentralPublishConfig
```

兼容大写任务名：

```bash
./gradlew :library:GenerateCentralPublishConfigTask
```

如果当前仓库存在多个需要发布的 Android Library 或 Gradle Plugin 模块，不额外设计 `modules` 配置字段，也不提供根工程聚合任务。每个模块本来就有自己的 Gradle task，直接按模块执行即可：

```bash
./gradlew :demo-lib:configureCentralPublish
./gradlew :demo-plugin:configureCentralPublish
```

每个模块 task 读取同一个仓库级配置文件。GitHub repository secrets 是仓库级资源，重复执行时应复用已有 secrets；各模块的组件信息仍由各自 `PublishInfo` 维护。

范围收敛：

- 不设计 `modules` / `module.<alias>.*`，模块选择只由 Gradle task path 决定。
- 不设计 `configureCentralPublishAll`，多模块仓库按模块分别执行 task。
- 不自动修改 `build.gradle.kts` / `build.gradle` 中的 `PublishInfo`，只做校验和错误提示。
- 不把 Central token、GPG 私钥、GPG 密码作为发布运行时 fallback；这些字段只用于一次性写入 GitHub repository secrets。
- 不自动执行 git history rewrite，只输出泄露处理指引。

最终责任边界：

| 配置类别 | 一键配置行为 | 是否进入版本控制 |
| --- | --- | --- |
| 组件发布元数据 | 由目标模块 `build.gradle.kts` / `build.gradle` 中的 `PublishInfo` 维护；一键任务只校验，不写入、不覆盖 | 是 |
| 本地 `local.properties` | 默认配置落点；追加 `centralPublish.*` key，不覆盖已有 `sdk.dir` / `ndk.dir`；发布运行时作为仓库级通用字段 fallback；task 负责确保文件被 ignore | 否，不能被 git track |
| Central token | 通过 GitHub CLI 写入 repository secrets | 否 |
| GPG 私钥和密码 | 通过 GitHub CLI 写入 repository secrets | 否 |
| GitHub Actions workflow | 生成或更新 workflow 文件 | 是 |
| 历史泄露清理 | 输出安全回退指令，不默认改写历史 | 不自动 |

`local.properties` 是 Android 项目的本地配置约定，通常已有 `sdk.dir` 等本机路径，也通常已经在 Android 模板的 `.gitignore` 中。一键配置默认把 `centralPublish.*` 配置块追加到根目录 `local.properties`；如果文件不存在就创建；如果 `.gitignore` 缺少 `local.properties`，task 自动补充；如果 `local.properties` 已经被 git track，task 必须失败并提示先移出版本控制。

## 配置优先级

发布运行时会读取 `local.properties` 作为仓库级通用配置 fallback，但它不能承载组件发布字段，更不能覆盖 `build.gradle.kts` / `build.gradle` 中已经显式配置的 `PublishInfo` 字段。设计上固定为：仓库级通用字段放配置文件，组件级字段放各模块 `PublishInfo`。

推荐优先级：

```text
Gradle property > 环境变量 > PublishInfo 显式配置 > local.properties 中 centralPublish.* 非空值 > 插件默认值/推导值
```

说明：

- Gradle property 和环境变量仍用于 CI 或临时发布覆盖，优先级最高。
- `PublishInfo` 中显式写过的字段优先于本地配置文件。
- `local.properties` 中只读取 `centralPublish.*` key；已有 `sdk.dir` / `ndk.dir` 等 Android 本地字段会被保留并忽略。
- `local.properties` 中 value 为空的 `centralPublish.*` key 会被忽略，等同于没有配置。
- `local.properties` 不支持 `centralPublish.groupId`、`centralPublish.artifactId`、`centralPublish.version`、`centralPublish.pluginId`、`centralPublish.implementationClass`、`centralPublish.pomName`、`centralPublish.pomDescription`、`centralPublish.pomUrl` 等组件字段；发现这些字段时任务失败并提示移动到对应模块 `PublishInfo`。
- 插件已有默认值或推导值最后兜底，例如 `centralNamespace = "cn.entertech"`、Developer 默认信息、SCM 推导、POM URL 推导、POM name/description 默认值。
- 为了准确区分“用户显式配置”和“插件默认值”，实现时需要让 `PublishInfo` 记录被 DSL setter 写过的字段；否则无法判断用户没有配置 `centralNamespace`，还是显式配置了默认值 `cn.entertech`。

多模块仓库也不需要在配置文件声明模块清单。Gradle task path 已经表达了当前目标模块：

- `./gradlew :demo-lib:configureCentralPublish` 只处理 `:demo-lib`。
- `./gradlew :demo-plugin:configureCentralPublish` 只处理 `:demo-plugin`。
- `groupId`、`artifactId`、`version` 属于组件坐标，必须放在各模块 `PublishInfo` 中。
- `pluginId`、`implementationClass` 属于 Gradle Plugin 模块必需信息，必须放在该插件模块 `PublishInfo` 中。
- `pomName`、`pomDescription`、`pomUrl` 属于组件 POM 信息，可在 `PublishInfo` 中显式配置；未配置时分别使用 artifactId、默认描述、当前工程 git remote / CI 推导 URL 兜底。
- `centralNamespace`、`centralPublishingType`、`scmUrl`、Developer、License、workflow、secrets 等多个模块可共用的字段，放在配置文件顶层，作为所有模块 task 的 fallback。

## 用户配置文件

默认读取仓库根目录的 `local.properties`。Android 项目通常已经有这个文件，一键配置只追加 `centralPublish.*` 配置块，不改写已有的 `sdk.dir` / `ndk.dir`。也可以通过 `-PpublishConfig=...` 指定相对路径或绝对路径；自定义文件仍建议使用同一套 `centralPublish.*` key，并由 task 自动检查 ignore/track 状态。

业务仓库可以先生成或更新本地配置模板：

```bash
./gradlew :library:generateCentralPublishConfig
```

如果 `local.properties` 已存在，生成器保留原内容，只追加或更新 `centralPublish` 配置块；如果文件不存在，则创建一个只包含该配置块的 `local.properties`。生成模板里的注释使用 ASCII English，避免 IDE 按 `.properties` 默认编码打开时出现中文注释乱码。已有旧模板需要刷新注释时，可以传入 `-PoverwritePublishConfig=true`，task 会重写模板注释并保留已有 `centralPublish.*` value。生成内容示例：

```properties
# Existing Android project content is preserved.
sdk.dir=/Users/example/Library/Android/sdk

# GitHub repository in owner/repo format. Blank tries gh repo view, then git remote origin.
centralPublish.githubRepo=

# Dry-run switch.
# true: Print planned files and secret names only. Do not write files or call gh secret set.
# false/blank: Run normally.
centralPublish.dryRun=

# Sonatype Central namespace. Blank uses plugin default cn.entertech.
centralPublish.centralNamespace=

# Central deployment publishing type.
# user_managed: Upload and validate, then leave the deployment in Central Portal for manual Publish.
# automatic: Upload and validate, then Central tries to publish to Maven Central automatically.
centralPublish.centralPublishingType=

# Central staging repository name. Blank uses plugin default CentralStaging.
centralPublish.centralRepositoryName=

# POM inception year. Blank uses the current year.
centralPublish.pomInceptionYear=

# POM license name. Blank uses plugin default The Apache License, Version 2.0.
centralPublish.licenseName=

# POM license URL. Blank uses plugin default https://www.apache.org/licenses/LICENSE-2.0.txt.
centralPublish.licenseUrl=

# POM license distribution. Blank uses plugin default repo.
centralPublish.licenseDistribution=

# POM developer id. Blank uses plugin default Entertech.
centralPublish.developerId=

# POM developer name. Blank uses plugin default Entertech.
centralPublish.developerName=

# POM developer email. Blank uses plugin default developer@entertech.cn.
centralPublish.developerEmail=

# POM developer organization. Blank uses plugin default Entertech.
centralPublish.developerOrganization=

# POM developer organization URL. Blank uses plugin default https://github.com/Entertech.
centralPublish.developerOrganizationUrl=

# POM developer URL. Blank uses plugin default https://github.com/Entertech.
centralPublish.developerUrl=

# SCM browser URL. Blank lets the plugin infer it from the current project git remote origin, then CI.
centralPublish.scmUrl=

# SCM connection. Blank may be inferred from scmUrl, for example scm:git:https://github.com/owner/repo.git.
centralPublish.scmConnection=

# SCM developer connection. Blank may be inferred from scmUrl, for example scm:git:ssh://git@github.com/owner/repo.git.
centralPublish.scmDeveloperConnection=

# Whether to configure GitHub repository secrets.
# true: Call gh secret set for Central token and GPG signing secrets.
# false/blank: Do not process GitHub secrets.
centralPublish.githubSecrets=

# Whether to overwrite existing repository secrets.
# true: Overwrite existing secrets.
# false/blank: Reuse existing secrets to avoid replacing CI credentials by mistake.
centralPublish.overwriteGithubSecrets=

# Central Portal User Token username. Default secret is MAVEN_CENTRAL_USERNAME.
centralPublish.mavenCentralUsername=

# Central Portal User Token password. Default secret is MAVEN_CENTRAL_PASSWORD.
centralPublish.mavenCentralPassword=

# GPG ASCII private key file path. Required when GPG_KEY_CONTENTS is missing and gpgGenerate=false.
centralPublish.gpgKeyFile=

# GPG private key password. Required when SIGNING_PASSWORD is missing or gpgGenerate=true.
centralPublish.signingPassword=

# GPG key id, optional. Blank lets Gradle signing infer it from the private key.
centralPublish.signingKeyId=

# Central username repository secret name. Blank uses MAVEN_CENTRAL_USERNAME.
centralPublish.mavenCentralUsernameSecret=

# Central password repository secret name. Blank uses MAVEN_CENTRAL_PASSWORD.
centralPublish.mavenCentralPasswordSecret=

# GPG private key repository secret name. Blank uses GPG_KEY_CONTENTS.
centralPublish.gpgKeySecret=

# GPG private key password repository secret name. Blank uses SIGNING_PASSWORD.
centralPublish.signingPasswordSecret=

# GPG key id repository secret name. Blank uses SIGNING_KEY_ID.
centralPublish.signingKeyIdSecret=

# Whether this task should call local gpg to generate a new publishing signing key.
# true: Generate a new GPG key and overwrite GPG_KEY_CONTENTS / SIGNING_PASSWORD.
# false/blank: Do not generate. Reuse existing GPG secrets when present.
centralPublish.gpgGenerate=

# GPG key type. The first phase supports RSA only.
centralPublish.gpgKeyType=

# RSA key length. Recommended value is 4096.
centralPublish.gpgKeyLength=

# GPG key expiration, for example 1y, 2y, or 0. 0 means no expiration and is not recommended by default.
centralPublish.gpgKeyExpire=

# GPG uid name, for example Entertech.
centralPublish.gpgName=

# GPG uid email, for example developer@entertech.cn.
centralPublish.gpgEmail=

# GPG uid comment, for example Central Publish Signing Key.
centralPublish.gpgComment=

# Whether to generate a GitHub Actions workflow.
# true: Generate or update the workflow specified by workflowPath.
# false/blank: Do not process workflow files.
centralPublish.githubActions=

# GitHub Actions workflow file path. Blank uses the current module name, for example .github/workflows/publish-central-demo-lib.yml.
centralPublish.workflowPath=

# Reusable workflow reference, for example Entertech/PublishPlugin/.github/workflows/central-publish.yml@main.
centralPublish.workflowUses=

# Multi-module repositories do not declare a module list in this file.
# Run each target module task directly, for example:
# ./gradlew :demo-lib:configureCentralPublish
# ./gradlew :demo-plugin:configureCentralPublish
```

模板里的 key 和注释都预先写好，value 默认为空。解析时空值会被忽略；业务仓库只填写仓库级通用字段、CI/secrets/workflow 字段。枚举或布尔字段必须在注释中说明每个选项的含义。

仓库级推荐配置：

```properties
centralPublish.githubRepo=Entertech/demo-lib

centralPublish.centralNamespace=cn.entertech
centralPublish.centralPublishingType=user_managed
centralPublish.scmUrl=https://github.com/Entertech/demo-lib

centralPublish.githubSecrets=true
centralPublish.overwriteGithubSecrets=false
centralPublish.mavenCentralUsername=central-token-username
centralPublish.mavenCentralPassword=central-token-password
centralPublish.gpgKeyFile=/Users/chengpeng/secrets/gpg-private-key.asc
centralPublish.signingPassword=gpg-private-key-password
centralPublish.signingKeyId=
centralPublish.gpgGenerate=false
centralPublish.gpgKeyType=RSA
centralPublish.gpgKeyLength=4096
centralPublish.gpgKeyExpire=2y
centralPublish.gpgName=Entertech
centralPublish.gpgEmail=developer@entertech.cn
centralPublish.gpgComment=Central Publish Signing Key

centralPublish.githubActions=true
centralPublish.workflowPath=
centralPublish.workflowUses=Entertech/PublishPlugin/.github/workflows/central-publish.yml@main
```

组件字段不进入 `local.properties`。每个模块继续在自己的 `build.gradle.kts` / `build.gradle` 中维护 `PublishInfo`。Android Library 的最小配置：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "1.0.0"
}
```

如果需要更准确的公开 POM 信息，可以显式覆盖：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "1.0.0"

    pomName = "Demo Library"
    pomDescription = "Android library published to Central Portal"
    pomUrl = "https://github.com/Entertech/demo-lib"
}
```

Gradle Plugin 模块还需要在该插件模块的 `PublishInfo` 中配置 `pluginId` 和 `implementationClass`；`pomName`、`pomDescription`、`pomUrl` 同样可选覆盖。

### 配置字段分组

#### 模块和 GitHub 仓库

| 字段 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `githubRepo` | 否 | 从 `gh repo view --json nameWithOwner` 或 `git remote origin` 推导 | GitHub 仓库，格式 `owner/repo`。无法推导时必须显式配置。 |
| `dryRun` | 否 | `false` | 只打印将要变更的文件和 secret 名称，不写文件、不调用 `gh secret set`。 |
| `overwriteGithubSecrets` | 否 | `false` | repository secret 已存在时是否覆盖。默认不覆盖，避免误替换线上 CI 正在使用的密钥。 |

多模块仓库不提供 `modules` 字段。要配置哪个模块，就执行哪个模块自己的 task；这比在 properties 里再维护一份模块列表更直接，也能避免模块清单与 Gradle 实际项目结构不一致。

#### PublishInfo 元数据

这些字段是发布解析器最终需要的有效值，但推荐来源不同：

| 字段 | 推荐来源 | 说明 |
| --- | --- | --- |
| `groupId` | 各模块 `PublishInfo` | Maven groupId，Central 发布时必须落在 `centralNamespace` 下。 |
| `artifactId` | 各模块 `PublishInfo` | Maven artifactId。 |
| `version` | 各模块 `PublishInfo` 或项目统一版本管理 | Maven version，不能包含 `debug`。 |
| `pluginId` | Gradle Plugin 模块 `PublishInfo` | Gradle Plugin 模块的插件 ID。 |
| `implementationClass` | Gradle Plugin 模块 `PublishInfo` | Gradle Plugin 模块的入口类。 |
| `pomName` | `PublishInfo` 或默认值 | POM `<name>`；为空时使用最终 artifactId，artifactId 仍为空时使用 `project.name`。 |
| `pomDescription` | `PublishInfo` 或默认值 | POM `<description>`；Android Library 默认 `Android library published to Central Portal`，Gradle Plugin 默认 `Gradle plugin published to Central Portal`。 |
| `pomUrl` | `PublishInfo` 或 Git/CI 推导 | POM `<url>`；优先从当前工程 `git remote origin` 推导为 HTTPS 仓库地址，再使用 GitHub Actions 环境兜底。 |
| `centralNamespace` | 配置文件顶层或插件默认值 | 仓库内通常相同，默认 `cn.entertech`。 |
| `centralPublishingType` | 配置文件顶层或插件默认值 | `user_managed` 或 `automatic`。默认 `user_managed`。 |
| `scmUrl` | 配置文件顶层或 git / CI 推导 | 仓库内通常相同；推导不到时配置。 |

配置文件里的组件字段会导致任务失败，不会被写入 `PublishInfo`。多模块仓库按模块分别执行 task，每次只处理当前模块，不从配置文件复制其他模块的组件字段。Developer、License、SCM connection 字段已有插件默认值或推导能力，不要求配置文件必须提供；需要所有模块共用时放配置文件顶层即可。

#### POM 默认值和推导

`pomName`、`pomDescription`、`pomUrl` 不再要求业务模块必须手写，但显式配置仍然优先级最高。

| 字段 | 推导规则 |
| --- | --- |
| `pomName` | `PublishInfo.pomName` 非空时使用它；否则使用最终 artifactId；artifactId 仍为空时使用 `project.name`。 |
| `pomDescription` | `PublishInfo.pomDescription` 非空时使用它；Android Library 默认 `Android library published to Central Portal`；Gradle Plugin 默认 `Gradle plugin published to Central Portal`。 |
| `pomUrl` | `PublishInfo.pomUrl` 非空时使用它；否则优先使用当前工程 `git remote origin`；再使用 `GITHUB_SERVER_URL` + `GITHUB_REPOSITORY`；最后可复用已推导的 `scmUrl`。 |

Git remote URL 归一化规则：

- `git@github.com:Entertech/demo.git` 转成 `https://github.com/Entertech/demo`。
- `ssh://git@github.com/Entertech/demo.git` 转成 `https://github.com/Entertech/demo`。
- `https://github.com/Entertech/demo.git` 去掉末尾 `.git`。
- 非 GitHub HTTPS URL 保留原 scheme/host/path，只去掉末尾 `.git`。

#### 仓库级通用发布字段

| 字段 | 默认值/推导 | 说明 |
| --- | --- | --- |
| `centralNamespace` | `cn.entertech` | Sonatype Central namespace，`groupId` 必须等于它或以它加 `.` 开头。 |
| `centralPublishingType` | `user_managed` | `user_managed` 表示上传并校验后停在 Central Portal，人工确认后才公开；`automatic` 表示校验通过后自动发布。 |
| `centralRepositoryName` | `CentralStaging` | Central staging repository 名称。 |
| `pomInceptionYear` | 当前年份 | POM inception year；实现时用 `Year.now()` 计算。 |
| `licenseName` | `The Apache License, Version 2.0` | POM license name。 |
| `licenseUrl` | `https://www.apache.org/licenses/LICENSE-2.0.txt` | POM license URL。 |
| `licenseDistribution` | `repo` | POM license distribution。 |
| `developerId` | `Entertech` | POM developer id。 |
| `developerName` | `Entertech` | POM developer name。 |
| `developerEmail` | `developer@entertech.cn` | POM developer email。 |
| `developerOrganization` | `Entertech` | POM developer organization。 |
| `developerOrganizationUrl` | `https://github.com/Entertech` | POM developer organization URL。 |
| `developerUrl` | `https://github.com/Entertech` | POM developer URL。 |
| `scmUrl` | 当前工程 `git remote origin` 或 CI 推导 | SCM 浏览地址，例如 `https://github.com/owner/repo`。 |
| `scmConnection` | 根据 `scmUrl` 推导 | HTTPS 形式 SCM connection，例如 `scm:git:https://github.com/owner/repo.git`。 |
| `scmDeveloperConnection` | 根据 `scmUrl` 推导 | SSH 形式 SCM developer connection，例如 `scm:git:ssh://git@github.com/owner/repo.git`。 |

#### GitHub repository secrets

下表展示逻辑字段名；写入 `local.properties` 时统一使用 `centralPublish.<字段名>`，例如 `centralPublish.mavenCentralUsername=`。

| 配置字段 | 默认 secret 名 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `mavenCentralUsername` | `MAVEN_CENTRAL_USERNAME` | `githubSecrets=true` 时必填 | Central Portal 生成的 User Token username。 |
| `mavenCentralPassword` | `MAVEN_CENTRAL_PASSWORD` | `githubSecrets=true` 时必填 | Central Portal 生成的 User Token password。 |
| `gpgKeyFile` | `GPG_KEY_CONTENTS` | 仓库没有 `GPG_KEY_CONTENTS` 且 `gpgGenerate=false` 时必填 | GPG 私钥文件路径，推荐 `.asc` 文本私钥。 |
| `signingPassword` | `SIGNING_PASSWORD` | 仓库没有 `SIGNING_PASSWORD` 或 `gpgGenerate=true` 时必填 | GPG 私钥密码。 |
| `signingKeyId` | `SIGNING_KEY_ID` | 否 | 可留空，让 Gradle signing 从私钥推断。 |

如果业务仓库还没有 GPG key，可以配置一键生成字段：

| 配置字段 | 默认值 | 说明 |
| --- | --- | --- |
| `gpgGenerate` | `false` | 是否由任务调用本机 `gpg` 生成发布签名 key。 |
| `gpgKeyType` | `RSA` | 密钥类型。第一阶段只支持 `RSA`。 |
| `gpgKeyLength` | `4096` | RSA 密钥长度。 |
| `gpgKeyExpire` | `2y` | 有效期，例如 `1y`、`2y`、`0`。`0` 表示不过期，不建议默认使用。 |
| `gpgName` | 空 | GPG uid 的姓名。 |
| `gpgEmail` | 空 | GPG uid 的邮箱地址。建议使用组织发布邮箱。 |
| `gpgComment` | 空 | GPG uid 注释，例如 `Central Publish Signing Key`。 |

`SIGNING_PASSWORD` 不是从 GPG 命令输出里“获取”的值，而是生成 GPG key 时由用户设置的私钥密码。`GPG_KEY_CONTENTS` 是生成 key 后导出的 ASCII 私钥内容。

GPG repository secrets 的处理规则：

- 如果仓库已经存在 `GPG_KEY_CONTENTS` 和 `SIGNING_PASSWORD`，并且 `gpgGenerate=false`、`overwriteGithubSecrets=false`，任务默认复用已有 secrets，不生成 GPG key，也不要求配置 `gpgKeyFile` / `signingPassword`。
- 如果仓库缺少 `GPG_KEY_CONTENTS` 或 `SIGNING_PASSWORD`，并且 `gpgGenerate=false`，任务要求提供 `gpgKeyFile` 和 `signingPassword`，然后写入缺失的 repository secrets。
- 如果显式配置 `gpgGenerate=true`，表示强制生成新的 GPG key，并用新导出的私钥和 `signingPassword` 覆盖 repository secrets。这个模式需要用户确认，因为它会让后续发布改用新 key。
- 如果只想用已有私钥文件覆盖 GitHub secrets，不生成新 key，配置 `gpgGenerate=false`、`overwriteGithubSecrets=true`、`gpgKeyFile=...`、`signingPassword=...`。

兼容输入字段：

| 兼容字段 | 等价字段 |
| --- | --- |
| `centralPublish.centralUsername` | `centralPublish.mavenCentralUsername` |
| `centralPublish.centralPassword` | `centralPublish.mavenCentralPassword` |
| `centralPublish.signingInMemoryKeyPassword` | `centralPublish.signingPassword` |
| `centralPublish.signingInMemoryKeyId` | `centralPublish.signingKeyId` |

secret 名称允许覆盖：

```properties
centralPublish.mavenCentralUsernameSecret=MAVEN_CENTRAL_USERNAME
centralPublish.mavenCentralPasswordSecret=MAVEN_CENTRAL_PASSWORD
centralPublish.gpgKeySecret=GPG_KEY_CONTENTS
centralPublish.signingPasswordSecret=SIGNING_PASSWORD
centralPublish.signingKeyIdSecret=SIGNING_KEY_ID
```

默认 secret 名称必须与本仓库 reusable workflow 保持一致。

## 任务行为

### configureCentralPublish

执行顺序：

1. 解析 `-PpublishConfig`，默认 `local.properties`。
2. 在读取敏感字段前检查配置文件安全：
   - 配置文件必须存在；不存在时提示先运行 `:module:generateCentralPublishConfig`。
   - 如果配置文件路径没有被 `.gitignore` 忽略，自动追加该路径到根目录 `.gitignore`。
   - 如果配置文件已被 git track，任务失败；如果其中包含敏感字段，还要额外提示立刻轮换 Central token 和 GPG key。
3. 加载 properties，忽略空值。
4. 如果配置文件包含 `centralPublish.groupId` 等组件字段，任务失败并提示移动到当前模块 `PublishInfo`。
5. 使用当前 Gradle project 作为目标模块，合并当前模块 `PublishInfo`、配置文件仓库级 fallback、插件默认值，得到有效发布信息。
6. 校验 PublishInfo 必填坐标字段：
   - `groupId`
   - `artifactId`
   - `version`
7. 校验 Central 规则：
   - `version` 不能包含 `debug`。
   - 如果配置 `centralNamespace`，`groupId` 必须等于该 namespace 或以 `${centralNamespace}.` 开头。
   - `centralPublishingType` 只能是 `user_managed` 或 `automatic`。
   - 最终解析出的 `pomName`、`pomDescription`、`pomUrl` 必须非空；默认值和 Git/CI 推导失败时再要求用户在 `PublishInfo` 显式配置。
8. 如果 `githubSecrets=true`：
   - 检查 `gh` 是否存在。
   - 执行 `gh auth status`。
   - 解析或校验 `githubRepo`。
   - 通过 `gh secret list` 检查目标仓库已有 repository secrets。
   - 如果仓库已有 `GPG_KEY_CONTENTS` 和 `SIGNING_PASSWORD`，且 `gpgGenerate=false`、`overwriteGithubSecrets=false`，跳过 GPG key 生成和 GPG secrets 写入。
   - 如果 `gpgGenerate=true`，检查 `gpg` 是否存在，生成 GPG key，并导出到 `gpgKeyFile`，然后覆盖 GPG repository secrets。
   - 如果 `gpgGenerate=false` 且仓库缺少 GPG secrets，校验 `gpgKeyFile` 和 `signingPassword`，并写入缺失的 GPG repository secrets。
   - 使用 `gh secret set` 写入 repository secrets。
9. 如果 `githubActions=true`：
   - 生成或更新 `workflowPath` 指定的文件；为空时按当前模块名生成 `.github/workflows/publish-central-<module>.yml`。
10. 打印结果摘要：
   - 校验了哪个模块。
   - 写入了哪些 secret 名称。
   - 生成了哪个 workflow。
   - `user_managed` 时提醒还需要在 Central Portal 手动 Publish。

单模块任务 `:module:configureCentralPublish` 只配置当前模块。多模块仓库重复执行各模块自己的 task，不通过配置文件声明模块列表，也不注册 `configureCentralPublishAll`。

### GPG key 生成与导出

已有 GPG key 时，推荐直接导出私钥文件并把路径写入 `gpgKeyFile`：

```bash
gpg --list-secret-keys --keyid-format LONG
gpg --armor --export-secret-keys <KEY_ID> > /Users/chengpeng/secrets/gpg-private-key.asc
```

这个 `gpg-private-key.asc` 文件的内容就是写入 GitHub secret `GPG_KEY_CONTENTS` 的值；生成或导出这个 key 时使用的私钥密码就是写入 `SIGNING_PASSWORD` 的值。

没有 GPG key 时，可以手动生成：

```bash
gpg --full-generate-key
```

交互式推荐选择：

- 密钥类型：`RSA and RSA` 或等价 RSA 选项。
- RSA 长度：`4096`。
- 有效期：建议明确设置，例如 `2y`。
- 姓名：组织或发布负责人名称，例如 `Entertech`。
- 邮箱：组织发布邮箱，例如 `developer@entertech.cn`。
- 注释：例如 `Central Publish Signing Key`。
- 密码：这就是后续 GitHub secret `SIGNING_PASSWORD`。

一键配置任务支持 `gpgGenerate=true` 的非交互式生成模式。实现时应使用临时 batch 文件调用 `gpg --batch --generate-key`，生成后立刻导出 ASCII 私钥到 `gpgKeyFile`。临时 batch 文件必须写入系统临时目录，执行完成后删除。任务日志不得打印 `signingPassword` 或私钥内容。

如果 GitHub repository 已经配置了 `GPG_KEY_CONTENTS` 和 `SIGNING_PASSWORD`，默认不需要再生成 GPG key，也不需要在配置文件里填写 `gpgKeyFile` / `signingPassword`。只有显式配置 `gpgGenerate=true` 时，才认为用户要生成新 key 并覆盖已有 GPG secrets。

`gpgGenerate=true` 必填字段：

```properties
centralPublish.gpgKeyFile=/Users/chengpeng/secrets/gpg-private-key.asc
centralPublish.signingPassword=gpg-private-key-password
centralPublish.gpgName=Entertech
centralPublish.gpgEmail=developer@entertech.cn
```

生成后任务继续执行 `gh secret set`：

```bash
gh secret set GPG_KEY_CONTENTS -R OWNER/REPO < /Users/chengpeng/secrets/gpg-private-key.asc
printf '%s' "$SIGNING_PASSWORD_VALUE" | gh secret set SIGNING_PASSWORD -R OWNER/REPO
```

`SIGNING_KEY_ID` 可选。需要显式配置时，可以通过下面命令查看 long key id：

```bash
gpg --list-secret-keys --keyid-format LONG
```

### generateCentralPublishConfig

执行顺序：

1. 解析 `-PpublishConfig`，默认 `local.properties`。
2. 如果文件不存在，创建 `local.properties` 并写入完整 `centralPublish.*` key 模板。
3. 模板注释必须使用 ASCII-only English，避免 `.properties` 文件在 IDE 或 Java properties 语义下显示乱码。
4. 如果文件已存在，保留 `sdk.dir` / `ndk.dir` 和其他已有内容，只追加缺失的 `centralPublish.*` key；已存在的非空 value 默认不覆盖。
5. 用户传入 `-PoverwritePublishConfig=true` 时，允许重写已存在的 `centralPublish.*` 模板注释并保留已有 value，不能删除 `sdk.dir` / `ndk.dir` 等非 `centralPublish.*` 字段。
6. 确保 `.gitignore` 包含配置文件路径；缺少时自动追加。
7. 打印下一步提示：
   - 仓库级通用字段、workflow 字段、secrets 输入字段填入配置文件。
   - 组件级字段始终放在各模块 `PublishInfo`，不要填入配置文件。
   - `PublishInfo` 显式配置优先级高于配置文件。
   - 敏感字段只用于 `gh secret set`，`local.properties` 必须保持未被 git track；如果被 track，任务会失败。
   - 枚举字段和布尔字段的可选值已经写在模板注释中。

模板生成器不支持 `-PpublishModules` 或 `-PpublishModuleAliases`。多模块仓库使用同一份仓库级配置文件，然后分别在目标模块执行 `generateCentralPublishConfig` 或 `configureCentralPublish`。

### rollbackCentralPublishSecrets

执行顺序：

1. 加载同一个 `local.properties`，只读取 `centralPublish.*` 配置。
2. 解析 `centralPublish.githubRepo`，为空时尝试通过 `gh repo view` 或 `git remote origin` 推导。
3. 删除配置涉及的 repository secrets：
   - `MAVEN_CENTRAL_USERNAME`
   - `MAVEN_CENTRAL_PASSWORD`
   - `GPG_KEY_CONTENTS`
   - `SIGNING_PASSWORD`
   - `SIGNING_KEY_ID`
4. 如果配置文件被 git track，输出处理建议：

```bash
git rm --cached local.properties
```

5. 确保 `.gitignore` 中包含配置文件路径；缺少时自动追加。
6. 如果用户传入 `-PremoveGeneratedWorkflow=true`，删除生成的 workflow 文件。
7. 输出泄露后必须人工完成的动作：
   - 去 Central Portal revoke 旧 token 并重新生成。
   - 如果 GPG 私钥已经提交或 push，吊销旧 key，换新 key。
   - 清理 GitHub Actions logs / artifacts。
   - 通知协作者重新同步重写后的历史。

## GitHub CLI 调用细节

必须使用本机 `gh`，不使用 GitHub connector。调用前打印当前账号和目标仓库：

```bash
gh api user --jq .login
gh repo view OWNER/REPO --json nameWithOwner --jq .nameWithOwner
```

写入前先检查目标 repository 已存在的 secret 名称：

```bash
gh secret list -R OWNER/REPO --json name --jq '.[].name'
```

这个命令只能看到 secret 名称和更新时间等元数据，不能读取 secret value。任务只用它判断是否需要创建或覆盖。

写入 secret 时不能把 secret value 放进命令参数，避免出现在进程列表或 Gradle 日志中。GitHub CLI 支持不传 `--body` 时从 stdin 读取，因此 task 应使用标准输入：

```bash
printf '%s' "$MAVEN_CENTRAL_USERNAME_VALUE" | gh secret set MAVEN_CENTRAL_USERNAME -R OWNER/REPO
printf '%s' "$MAVEN_CENTRAL_PASSWORD_VALUE" | gh secret set MAVEN_CENTRAL_PASSWORD -R OWNER/REPO
gh secret set GPG_KEY_CONTENTS -R OWNER/REPO < /Users/chengpeng/secrets/gpg-private-key.asc
printf '%s' "$SIGNING_PASSWORD_VALUE" | gh secret set SIGNING_PASSWORD -R OWNER/REPO
```

`SIGNING_KEY_ID` 为空时不写入。Gradle signing 可以从私钥内容推断 key id。

删除 secret：

```bash
gh secret delete MAVEN_CENTRAL_USERNAME -R OWNER/REPO
gh secret delete MAVEN_CENTRAL_PASSWORD -R OWNER/REPO
gh secret delete GPG_KEY_CONTENTS -R OWNER/REPO
gh secret delete SIGNING_PASSWORD -R OWNER/REPO
gh secret delete SIGNING_KEY_ID -R OWNER/REPO
```

删除时如果 secret 不存在，记录 warning，不让整个回退任务失败。

## PublishInfo 校验策略

一键配置任务不自动修改 `build.gradle.kts` 或 `build.gradle`。如果当前模块没有应用 `cn.entertech.publish`，任务失败并提示用户先接入插件。原因是 plugins block 可能存在 version catalog、buildscript classpath、旧 Groovy apply 等多种写法，自动改动风险高。

如果当前模块缺少 `PublishInfo` 或缺少必填坐标字段，任务失败，并输出当前模块可直接参考的 Kotlin DSL 示例：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "1.0.0"
}
```

如果业务方希望公开 POM 信息更精确，可以在 `PublishInfo` 中显式覆盖：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "1.0.0"

    pomName = "Demo Library"
    pomDescription = "Android library published to Central Portal"
    pomUrl = "https://github.com/Entertech/demo-lib"
}
```

Gradle Plugin 模块的错误提示还应包含：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-plugin"
    version = "1.0.0"

    pluginId = "cn.entertech.demo"
    implementationClass = "cn.entertech.demo.DemoPlugin"
}
```

配置文件如果包含 `groupId`、`artifactId`、`version`、`pluginId`、`implementationClass`、`pomName`、`pomDescription`、`pomUrl`，任务直接失败，并提示这些字段应移动到当前模块的 `PublishInfo`。

## 发布运行时读取本地配置文件

除了 `configureCentralPublish` 会读取 `local.properties` 外，正常发布任务也会读取该文件中的 `centralPublish.*` 作为仓库级 fallback。这样业务仓库可以把仓库级通用字段集中放在本地配置文件中，组件级字段继续留在各模块 `PublishInfo`。

示例：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "1.0.0"
}
```

`local.properties`：

```properties
centralPublish.centralNamespace=cn.entertech
centralPublish.centralPublishingType=user_managed
centralPublish.scmUrl=https://github.com/Entertech/demo-lib
```

发布时等价于在 `PublishInfo` 之外补充这些通用字段。`pomName` 会从 artifactId 兜底，`pomDescription` 使用组件类型默认文案，`pomUrl` 从当前工程 `git remote origin` 或 CI 推导。配置文件不允许提供组件字段；如果出现 `pomUrl`、`artifactId` 等字段，解析器直接失败并提示移动到当前模块 `PublishInfo`。

实现要求：

- `PublishConfigResolver` 增加 `loadCentralPublishProperties(project)`，默认读取 root `local.properties`，并支持 `-PpublishConfig=...`。
- 只解析 `centralPublish.*` 前缀字段；`sdk.dir` / `ndk.dir` 等本地 Android 字段必须忽略。
- central/developer/license/scm 等仓库级解析方法在 `PublishInfo` 显式字段之后读取本地配置文件非空值。
- artifact/plugin/pom 基础元数据解析方法不读取本地配置文件。
- 解析器不支持组件字段；发现 `groupId`、`artifactId`、`version`、`pluginId`、`implementationClass`、`pomName`、`pomDescription`、`pomUrl` 时必须失败。
- 解析器不支持 `module.<alias>.*` 这类 namespaced 字段；发现后必须失败并提示删除该字段，改为执行对应模块自己的 task。
- `centralUsername` / `centralPassword` 和 GPG signing 不从 `local.properties` 参与本地发布解析，避免用户误以为可以把敏感信息留在本地配置文件中长期使用；这两个类别只由 `configureCentralPublish` 用来写 GitHub secrets。
- 若要支持本地发布读取敏感字段，必须新增显式开关，例如 `allowLocalSecretFallback=true`，默认关闭。

## GitHub Actions workflow 生成

当在 `:library` 模块执行 task，并配置：

```properties
centralPublish.githubActions=true
centralPublish.workflowPath=.github/workflows/publish-central-library.yml
centralPublish.workflowUses=Entertech/PublishPlugin/.github/workflows/central-publish.yml@main
```

生成：

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

多模块仓库不生成一个多 job 聚合 workflow。需要多个模块发布入口时，分别执行各模块 task，并为每个模块使用不同 `workflowPath`，例如：

```bash
./gradlew :demo-lib:configureCentralPublish \
  -PworkflowPath=.github/workflows/publish-central-demo-lib.yml

./gradlew :demo-plugin:configureCentralPublish \
  -PworkflowPath=.github/workflows/publish-central-demo-plugin.yml
```

如果 workflow 文件已存在：

- 如果文件包含标记 `# Generated by PublishPlugin configureCentralPublish`，允许覆盖。
- 如果没有生成标记，任务失败并提示 `-PoverwriteWorkflow=true`。

生成文件顶部增加标记：

```yaml
# Generated by PublishPlugin configureCentralPublish
```

## 敏感信息泄露处理

### 当前工作区误 track

如果 `local.properties` 被 git track，`configureCentralPublish` 必须失败，不写 secret，不写 workflow。即使当前 value 为空，也要先移出版本控制，避免后续填入敏感信息后误提交。

输出建议：

```bash
git rm --cached local.properties
git add .gitignore
git commit -m "[codex] ignore central publish config"
```

这里不要求用户手工编辑 `.gitignore`。`generateCentralPublishConfig` / `configureCentralPublish` 会在发现缺少 `local.properties` ignore 规则时自动追加；如果文件已经被 git track，只能由用户确认后执行 `git rm --cached`，task 不自动修改暂存区。

### 已经提交但还没 push

可以用普通交互式 rebase 或 reset 修复，但 task 不自动执行。输出建议：

```bash
git rm --cached local.properties
git commit --amend
```

如果敏感信息在更早的提交中：

```bash
git rebase -i <bad_commit_parent>
```

### 已经 push 到远程

`git replace` 不能作为清理方案。它只影响本地对象读取视图，不会从远程仓库、fork、CI logs、缓存中删除原始敏感内容。

需要执行两类动作。

第一类是仓库历史清理。推荐使用 `git filter-repo` 或 BFG。针对误提交整个配置文件：

```bash
git filter-repo --path local.properties --invert-paths
git push --force-with-lease --all
git push --force-with-lease --tags
```

第二类是密钥轮换：

- Central Portal revoke 旧 token，重新生成 User Token。
- GPG 私钥泄露时吊销旧 key，换新 key。
- 重新运行 `configureCentralPublish` 写入新的 repository secrets。
- 清理 GitHub Actions logs / artifacts。
- 通知协作者重新 clone 或按团队约定 reset 到新历史。

`rollbackCentralPublishSecrets` 可以删除 GitHub repository secrets 和提示历史清理命令，但默认不自动执行 history rewrite。如果要输出完整 history rewrite 指南，使用显式危险开关：

```bash
./gradlew :library:rollbackCentralPublishSecrets \
  -PprintHistoryRewriteGuide=true
```

不建议提供默认自动 `filter-repo`，因为它会改写所有协作者的历史基准。

## 文件结构计划

新增文件：

| 文件 | 责任 |
| --- | --- |
| `plugin_base/src/main/kotlin/custom/android/plugin/config/CentralPublishConfig.kt` | properties 配置模型和字段归一化。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/config/CentralPublishConfigLoader.kt` | 读取配置文件、解析相对路径、校验必填字段。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/config/CentralPublishConfigTemplateWriter.kt` | 生成 key 完整、value 为空且包含字段注释的 `centralPublish.*` 配置块；写入 `local.properties` 时保留已有 `sdk.dir` / `ndk.dir`。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/config/GitUrlNormalizer.kt` | 把 git remote / CI URL 归一化为 HTTPS 仓库 URL，用于 `pomUrl` 和 `scmUrl` 推导。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/config/PomMetadataDefaults.kt` | 解析 `pomName`、`pomDescription`、`pomUrl` 默认值。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/config/GitHubSecretClient.kt` | 调用 `gh secret set/delete`。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/config/GpgKeyManager.kt` | 检查 `gpg`、生成 GPG key、导出 ASCII 私钥文件、读取 key id。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/config/GitSafetyChecker.kt` | 检查配置文件是否被 track、是否被 ignore；缺少 ignore 规则时自动追加；支持 GitHub repo 推导。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/config/GitHubActionsWorkflowWriter.kt` | 生成 reusable workflow。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/GenerateCentralPublishConfigTask.kt` | 生成本地配置模板 task。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/ConfigureCentralPublishTask.kt` | 一键配置入口 task。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/RollbackCentralPublishSecretsTask.kt` | repository secrets 回退 task。 |

修改文件：

| 文件 | 修改点 |
| --- | --- |
| `plugin_base/src/main/kotlin/custom/android/plugin/PublishInfo.kt` | 记录 DSL 显式配置过的字段，用于区分用户配置和插件默认值。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/PublishConfigResolver.kt` | 增加 `local.properties` 非空值 fallback，优先级低于 `PublishInfo` 显式配置。 |
| `plugin_base/src/main/kotlin/custom/android/plugin/PublishPlugin.kt` | 注册 `generateCentralPublishConfig` / `GenerateCentralPublishConfigTask` / `configureCentralPublish` / `ConfigureCentralPublishTask` / `rollbackCentralPublishSecrets` / `RollbackCentralPublishSecretsTask`。 |
| `README.md` | 增加一键配置使用说明。 |
| `doc/tech/central-publish-one-click-config-plan.md` | 本方案文档。 |

测试文件：

| 文件 | 覆盖点 |
| --- | --- |
| `plugin_base/src/test/java/custom/android/plugin/config/CentralPublishConfigLoaderTest.java` | properties 解析、只读取 `centralPublish.*`、忽略 `sdk.dir` / `ndk.dir`、兼容字段、必填字段校验。 |
| `plugin_base/src/test/java/custom/android/plugin/config/CentralPublishConfigTemplateWriterTest.java` | 模板生成、字段注释、枚举说明、空值保留、已有 `local.properties` 保留 `sdk.dir` / `ndk.dir`、缺少 ignore 规则时自动追加。 |
| `plugin_base/src/test/java/custom/android/plugin/config/GitUrlNormalizerTest.java` | SSH/HTTPS git remote URL 到 HTTPS 仓库 URL 的归一化。 |
| `plugin_base/src/test/java/custom/android/plugin/config/PomMetadataDefaultsTest.java` | `pomName`、`pomDescription`、`pomUrl` 默认值和推导规则。 |
| `plugin_base/src/test/java/custom/android/plugin/config/GitHubSecretClientTest.java` | fake `gh` 验证 secret 通过 stdin 写入，不出现在命令参数。 |
| `plugin_base/src/test/java/custom/android/plugin/config/GpgKeyManagerTest.java` | fake `gpg` 验证 batch 生成、私钥导出、敏感信息不进入日志。 |
| `plugin_base/src/test/java/custom/android/plugin/PublishConfigResolverLocalConfigTest.java` | `PublishInfo` 显式配置优先于本地配置文件，本地配置文件空值被忽略，组件字段和 `module.<alias>.*` 字段会失败。 |
| `plugin_base/src/test/java/custom/android/plugin/ConfigureCentralPublishTaskFunctionalTest.java` | TestKit 端到端配置模块、生成 workflow、调用 fake `gh`。 |
| `plugin_base/src/test/java/custom/android/plugin/RollbackCentralPublishSecretsTaskFunctionalTest.java` | 删除 secrets、配置文件被 track 时的提示。 |

## 实施计划

> 这个计划用于后续编码实施。每个 task 要按 TDD 执行：先写失败测试，再实现，再跑通过。

### Task 1：配置模型和 loader

- [ ] 新增 `CentralPublishConfig` 数据类。
- [ ] 新增 `CentralPublishConfigLoader`。
- [ ] 测试只读取 `centralPublish.*` 前缀字段，忽略 `sdk.dir` / `ndk.dir`。
- [ ] 测试无前缀的 `githubRepo` / `centralNamespace` 不会被当作有效配置读取，并提示使用 `centralPublish.*`。
- [ ] 测试空 value 会被忽略，不参与配置覆盖。
- [ ] 测试配置文件出现 `modules` 时失败，并提示执行目标模块自己的 task。
- [ ] 测试配置文件出现 `module.<alias>.*` 时失败，并提示删除 namespaced 字段。
- [ ] 测试配置文件出现 `groupId` / `artifactId` / `pomUrl` 等组件字段时失败，并提示移动到 `PublishInfo`。
- [ ] 测试 properties 中 `centralUsername` / `centralPassword` 会归一化为 `mavenCentralUsername` / `mavenCentralPassword`。
- [ ] 测试仓库缺少 GPG secrets 且 `gpgGenerate=false` 时，缺少 `gpgKeyFile` / `signingPassword` 会失败。
- [ ] 测试仓库已有 `GPG_KEY_CONTENTS` / `SIGNING_PASSWORD` 且 `gpgGenerate=false` 时，不要求 `gpgKeyFile` / `signingPassword`。
- [ ] 测试 `centralPublishingType=portal_api` 会失败，并提示只支持 `user_managed` / `automatic`。

### Task 1.5：配置模板生成

- [ ] 新增 `CentralPublishConfigTemplateWriter`。
- [ ] 新增 `GenerateCentralPublishConfigTask`。
- [ ] 测试生成的 `local.properties` 包含所有支持 key，value 为空。
- [ ] 测试生成的模板不包含 `modules` 或 `module.<alias>.*` 字段。
- [ ] 测试每个字段前都有说明注释。
- [ ] 测试 `centralPublishingType`、`githubSecrets`、`overwriteGithubSecrets`、`gpgGenerate`、`githubActions` 等枚举/布尔字段包含可选值含义。
- [ ] 测试 `local.properties` 已存在时保留 `sdk.dir` / `ndk.dir` 和其他非 `centralPublish.*` 内容。
- [ ] 测试文件已存在且已有非空 `centralPublish.*` value 时不覆盖。
- [ ] 测试 `-PoverwritePublishConfig=true` 时允许覆盖。
- [ ] 测试 `.gitignore` 缺少配置文件路径时自动追加。
- [ ] 测试 `local.properties` 已被 git track 时任务失败，不继续写入模板或 secrets。

### Task 1.6：运行时本地配置 fallback

- [ ] 修改 `PublishInfo`，让 DSL setter 记录显式配置字段。
- [ ] 修改 `PublishConfigResolver`，读取 `local.properties` 非空值。
- [ ] 新增 `GitUrlNormalizer`，测试 `git@github.com:Entertech/demo.git` 转成 `https://github.com/Entertech/demo`。
- [ ] 测试 `ssh://git@github.com/Entertech/demo.git` 转成 `https://github.com/Entertech/demo`。
- [ ] 测试 `https://github.com/Entertech/demo.git` 去掉末尾 `.git`。
- [ ] 新增 `PomMetadataDefaults`，测试 `pomName` 为空时使用最终 artifactId。
- [ ] 测试 Android Library 的默认 `pomDescription` 为 `Android library published to Central Portal`。
- [ ] 测试 Gradle Plugin 的默认 `pomDescription` 为 `Gradle plugin published to Central Portal`。
- [ ] 测试 `pomUrl` 优先从当前工程 `git remote origin` 推导，不被外层 CI 的 `GITHUB_REPOSITORY` 污染。
- [ ] 测试无 git remote 时，`pomUrl` 从 `GITHUB_SERVER_URL` + `GITHUB_REPOSITORY` 推导。
- [ ] 测试 `PublishInfo.centralNamespace` 显式配置时优先于 `local.properties`。
- [ ] 测试 `PublishInfo` 未显式配置且本地配置有 `centralPublishingType` 时使用本地配置。
- [ ] 测试本地配置文件中空 `centralPublishingType=` 不覆盖插件默认值。
- [ ] 测试本地配置文件中 `pomUrl=` 会失败并提示移动到 `PublishInfo`。
- [ ] 测试本地配置文件中 `centralPublish.pomUrl=` 会失败并提示移动到 `PublishInfo`。
- [ ] 测试配置文件中的 `module.<alias>.*` 字段会失败并提示执行对应模块 task。
- [ ] 测试本地配置文件中的敏感字段不会被发布解析器读取为 signing/token fallback。

### Task 2：Git 安全检查

- [ ] 测试配置文件被 git track 时失败。
- [ ] 测试被 git track 且包含敏感字段时，输出 token/key 轮换提示。
- [ ] 测试 `.gitignore` 缺少配置文件路径时自动追加。
- [ ] 测试 `githubRepo` 未配置时从 `gh repo view` 推导。
- [ ] 测试无法推导 repo 时失败并提示配置 `githubRepo=owner/repo`。

### Task 3：GitHub Secret Client

- [ ] 用 fake `gh` 脚本记录参数和 stdin。
- [ ] 测试 `MAVEN_CENTRAL_USERNAME` 通过 stdin 传入。
- [ ] 测试 `gh secret list` 已有 `GPG_KEY_CONTENTS` / `SIGNING_PASSWORD` 时跳过 GPG secrets 写入。
- [ ] 测试 `GPG_KEY_CONTENTS` 从 `gpgKeyFile` 内容传入。
- [ ] 测试 `overwriteGithubSecrets=true` 时会覆盖已有 GPG secrets。
- [ ] 测试 secret value 不出现在 fake `gh` 的参数列表。
- [ ] 测试删除不存在的 secret 只记录 warning。

### Task 3.5：GPG key 生成和导出

- [ ] 新增 `GpgKeyManager`。
- [ ] 用 fake `gpg` 脚本验证 `gpgGenerate=true` 会调用 batch 生成命令。
- [ ] 测试缺少 `gpgName` / `gpgEmail` / `signingPassword` 时失败。
- [ ] 测试仓库已有 GPG secrets 且 `gpgGenerate=false` 时不会调用 fake `gpg`。
- [ ] 测试生成后会导出 ASCII 私钥到 `gpgKeyFile`。
- [ ] 测试临时 batch 文件执行后删除。
- [ ] 测试日志不包含 `signingPassword` 和私钥内容。

### Task 4：configureCentralPublish functional test

- [ ] 构造 TestKit fixture，模块应用 `cn.entertech.publish`。
- [ ] 准备 `local.properties`。
- [ ] 使用 fake `gh` 跑 `:fixture:configureCentralPublish`。
- [ ] 验证 `build.gradle` 不会被写入或改写 `PublishInfo`。
- [ ] 验证 `PublishInfo` 只配置 `groupId` / `artifactId` / `version` 时任务成功，`pomName` / `pomDescription` / `pomUrl` 由默认值和 git 推导补齐。
- [ ] 验证缺少 `groupId` / `artifactId` / `version` 时任务失败，并输出 Kotlin DSL 示例。
- [ ] 验证默认 `.github/workflows/publish-central-fixture.yml` 生成，或 `-PworkflowPath=...` 指定路径时生成指定文件。
- [ ] 验证 fake `gh` 收到 4 个必需 secret。
- [ ] 验证 `dryRun=true` 不写文件、不调用 fake `gh`。
- [ ] 在同一个 TestKit fixture 中增加 `:demo-lib` 和 `:demo-plugin`，用同一份 `local.properties` 分别运行两个模块 task。
- [ ] 验证两个模块 task 分别读取自己的 `PublishInfo`，并复用同一份仓库级通用配置。
- [ ] 验证两个模块使用不同 `workflowPath` 时分别生成 workflow。
- [ ] 验证配置文件包含 `modules` 或 `module.<alias>.*` 时任务失败。

### Task 5：rollbackCentralPublishSecrets functional test

- [ ] 用 fake `gh` 跑 `:fixture:rollbackCentralPublishSecrets`。
- [ ] 验证删除默认 secret 名称。
- [ ] 验证配置自定义 secret 名称时删除自定义名称。
- [ ] 验证 `removeGeneratedWorkflow=true` 会删除带生成标记的 workflow。
- [ ] 验证无生成标记的 workflow 不会被删除。

### Task 6：任务注册和 README

- [ ] 在 `PublishPlugin` 注册单模块配置、模板生成和回退任务名。
- [ ] 跑 `:demo-lib:tasks --all` 验证能看到任务。
- [ ] 跑 `:demo-plugin:tasks --all` 验证插件模块也能看到任务。
- [ ] README 增加按模块执行的一键配置命令、配置文件 fallback 优先级、执行命令、回退命令、泄露处理说明。
- [ ] 跑 `./gradlew :plugin_base:test :demo-lib:test :demo-plugin:test --console=plain`。

## 验收标准

1. 业务仓库执行 `:module:generateCentralPublishConfig` 能生成或更新根目录 `local.properties`，保留 Android 已有 `sdk.dir` / `ndk.dir`，并自动确保 `.gitignore` 包含 `local.properties`。
2. 多模块仓库不需要 `modules` 字段；分别执行 `:demo-lib:configureCentralPublish`、`:demo-plugin:configureCentralPublish` 时，每个 task 只处理当前模块，并复用同一份仓库级通用配置。
3. `PublishInfo` 最小只需要 `groupId`、`artifactId`、`version`；`pomName`、`pomDescription`、`pomUrl` 能通过默认值和 Git/CI 推导得到。
4. 执行 `:module:configureCentralPublish` 能完成单模块 `PublishInfo` 校验、GitHub secrets 写入和 workflow 生成。
5. 发布解析时，`PublishInfo` 显式配置优先于 `local.properties`；本地配置文件中的空值被忽略。
6. 配置文件不支持 `modules`、`module.<alias>.*` 或组件字段；出现这些字段时任务失败并提示执行目标模块自己的 task 或移动到 `PublishInfo`。
7. 生成的 `local.properties` 模板 key 完整、value 为空且带 ASCII-only English 字段注释；枚举/布尔字段注释说明每个选项含义。
8. Central token、GPG 私钥和 GPG 密码不会写入 `build.gradle.kts`、`gradle.properties` 或任何 tracked 文件；`local.properties` 可作为本机一次性输入来源，但必须被 ignore 且不能被 git track。
9. 调用 `gh secret set` 时 secret value 不出现在命令参数和 Gradle 日志中。
10. 仓库已有 `GPG_KEY_CONTENTS` / `SIGNING_PASSWORD` 且未显式 `gpgGenerate=true` 时，任务复用已有 secrets，不生成 GPG key。
11. 配置 `gpgGenerate=true` 时，任务能通过本机 `gpg` 生成 key、导出私钥文件，并写入 `GPG_KEY_CONTENTS` / `SIGNING_PASSWORD` repository secrets。
12. 配置文件被 git track 时任务必须失败；如果已包含敏感字段，还必须提示轮换密钥。
13. `rollbackCentralPublishSecrets` 能删除对应 repository secrets。
14. 文档清楚说明 `git replace` 不能清理远程泄露，history rewrite 和密钥轮换都必须执行。
15. 所有新增测试和现有 `plugin_base`、`demo-lib`、`demo-plugin` 测试通过。
