# PublishPlugin 接入 Sonatype Central Portal 方案

## 背景

当前 `PublishPlugin` 被多个 Android/Gradle 插件项目使用，核心能力是给业务项目生成一个 `EnterPublish` Maven publication，并通过 `PublishLibraryLocalTask` / `PublishLibraryRemoteTask` 触发发布。

现在远程发布目标要迁移到 Sonatype Central Portal：

- Central Portal 地址：https://central.sonatype.com/publishing/namespaces
- Sonatype 已停止旧 OSSRH 流程，旧 `s01.oss.sonatype.org` 不应再作为默认发布目标。
- Sonatype 当前没有官方 Gradle Central Portal 插件；官方建议 Gradle 用户使用社区插件，或通过 OSSRH Staging API 兼容层迁移。
- Maven Central 发布要求比本地 Maven 更严格：POM 元数据、sources jar、javadoc jar、GPG 签名、checksums 都要齐全。

`/Users/chengpeng/project/enter/Android/lt-rpc-schema` 的发布逻辑已经更接近 Central 要求：它使用 `com.vanniktech.maven.publish` 统一配置坐标、POM、sources/javadoc、签名和 Central 发布目标。这个项目的思路可以迁移到自研 `PublishPlugin`，但不建议直接要求所有业务项目改用 Vanniktech；更稳的方式是把这些约束封装进当前插件，让其他项目继续使用现有接入方式。

## 参考资料

- Sonatype Gradle 发布说明：https://central.sonatype.org/publish/publish-portal-gradle/
- Sonatype OSSRH Staging API 兼容层：https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/
- Sonatype Central 发布要求：https://central.sonatype.org/publish/requirements/
- Vanniktech Maven Central 文档：https://vanniktech.github.io/gradle-maven-publish-plugin/central/
- `lt-rpc-schema` 公共发布配置：`/Users/chengpeng/project/enter/Android/lt-rpc-schema/buildSrc/src/main/kotlin/wire-schema-convention.gradle.kts`

## 从 lt-rpc-schema 借鉴的点

`lt-rpc-schema` 的有效设计不是某个单独 task，而是“发布约束集中到一个公共 convention”：

1. 坐标由模块明确声明：`coordinates(group, artifactId, version)`。
2. POM 元数据在公共层统一补齐：项目 URL、license、developer、SCM。
3. Central 发布必须签名：`signAllPublications()`。
4. Central 发布必须包含 sources/javadoc artifact。
5. 凭据不写死在代码里，通过 Gradle properties 或环境变量传入。
6. 业务模块只关心 `artifactId`、`name`、`description` 这些差异项。

`PublishPlugin` 应该保留现有“业务模块只配置 `PublishInfo`”的低接入成本，同时把 Central 所需的元数据、签名、sources/javadoc、上传端点统一封装。

## 当前 PublishPlugin 的主要差距

1. 默认远程地址仍是旧 OSSRH：

```kotlin
https://s01.oss.sonatype.org/content/repositories/releases/
```

2. 代码里有硬编码账号密码，不能继续用于 Central，也存在安全风险。

3. `PublishInfo` 只有基本坐标和仓库账号，缺少 Central 必需的 POM 字段。

4. 本地发布已经按需求处理为“非 debug 不带 sources”，但 Central 远程发布必须强制带 sources/javadoc。

5. 当前没有 GPG signing。

6. 当前 `PublishLibraryRemoteTask` 只执行普通 Maven repository PUT，不会调用 OSSRH Staging API 的 manual upload endpoint，因此上传后可能不会出现在 Central Portal deployments 页面。

7. 当前 remote/local 复用同一个 publication 配置，后续需要按任务上下文区分：

- 本地 Maven：轻量调试，不强制 sources/javadoc/signing。
- Central：严格发布，强制 sources/javadoc/signing/POM 校验。

## 兼容性原则

这次改造必须兼容旧版本字段，不要求兼容旧版本发布逻辑。也就是说：旧项目里已经写下的 `PublishInfo { ... }` 不能因为字段删除、字段改名、类型变化而编译失败；但字段背后的发布实现可以迁移到 Central Portal。

必须兼容的旧字段：

1. `PublishInfo.groupId`
2. `PublishInfo.artifactId`
3. `PublishInfo.version`
4. `PublishInfo.pluginId`
5. `PublishInfo.implementationClass`
6. `PublishInfo.publishUrl`
7. `PublishInfo.publishUserName`
8. `PublishInfo.publishPassword`

字段兼容要求：

1. 字段名不能改。
2. 字段类型不能改。
3. 字段默认值不能变成会让旧项目配置阶段失败的值。
4. 旧字段必须能映射到新 Central 发布模型。
5. 新增 Central/POM/signing 字段必须是可选字段。
6. 只有执行远程发布 `PublishLibraryRemoteTask` 时，才校验 Central 必需字段。
7. `local.properties` 中的 `publishUrl`、`publishUserName`、`publishPassword` 作为旧字段来源继续支持，但它们是字段兼容输入，不代表必须保留旧远程发布逻辑。

不作为硬性兼容目标的旧逻辑：

1. 旧 `s01.oss.sonatype.org` 默认地址。
2. 插件内置默认账号密码。
3. `PublishLibraryRemoteTask` 必须继续指向旧 Maven repository 的行为。
4. 旧远程发布只做 Maven PUT、不触发 Central Portal deployment 的行为。

由于旧 Sonatype/OSSRH 地址已经失效，远程发布入口直接复用 `PublishLibraryRemoteTask`。也就是说：任务名兼容旧调用方式，任务实现迁移到 Central Portal。

## 推荐架构

采用两阶段路线。

第一阶段使用 Sonatype OSSRH Staging API 兼容层，改造成本最低，能继续复用 Gradle `maven-publish`：

```text
业务模块 build.gradle
        |
        v
custom.android.plugin
        |
        +-- MavenPublication(EnterPublish)
        |     +-- main artifact: aar/jar
        |     +-- central only: sources.jar
        |     +-- central only: javadoc.jar
        |     +-- central only: .asc signatures
        |     +-- pom metadata
        |
        +-- PublishLibraryLocalTask
        |     +-- publishToMavenLocal
        |
        +-- PublishLibraryRemoteTask
              +-- publishEnterPublishPublicationToCentralStagingRepository
              +-- POST /manual/upload/defaultRepository/<namespace>
              +-- user_managed by default
```

第二阶段再做 Central Portal Publisher API 原生 bundle 上传：

```text
publish to build/central-staging-repo
        |
        v
validate files: pom, module, sources, javadoc, asc
        |
        v
zip Maven layout
        |
        v
POST https://central.sonatype.com/api/v1/publisher/upload
        |
        v
poll status / publish or drop
```

第一阶段先落地，第二阶段作为后续增强。

## 为什么不直接把 Vanniktech 塞进 PublishPlugin

可以做，但不作为第一选择。

原因：

1. 当前插件被多个旧项目使用，直接引入 Vanniktech 会把插件兼容性绑定到它支持的 Gradle/AGP/Kotlin 版本。
2. Vanniktech 的 DSL 是 `mavenPublishing { ... }`，当前业务项目用的是 `PublishInfo { ... }`，直接替换会影响接入方式。
3. 当前插件还支持 Gradle 插件模块和 Android Library 模块，先用 `maven-publish + signing` 自研封装更容易保持行为兼容。

后续可以增加一个可选实现：当项目环境满足较新 Gradle/AGP 时，内部委托 Vanniktech；否则走自研兼容路径。

## PublishInfo 字段设计

保留现有字段，不能重命名，不能删除：

```kotlin
var groupId = ""
var artifactId = ""
var version = ""
var pluginId = ""
var implementationClass = ""
var publishUrl = ""
var publishUserName = ""
var publishPassword = ""
```

旧字段到新发布模型的映射：

| 字段 | 旧字段含义 | 新模型映射 |
| --- | --- | --- |
| `groupId` | Maven groupId | 继续作为 Central 坐标，必须落在已验证 namespace 下 |
| `artifactId` | Maven artifactId | 继续作为 Central 坐标 |
| `version` | Maven version | 继续作为 Central 版本；远程发布禁止 `debug` 版本 |
| `pluginId` | Gradle 插件发布时使用 | 保持不变 |
| `implementationClass` | Gradle 插件发布时使用 | 保持不变 |
| `publishUrl` | 发布仓库地址 | 作为旧字段保留；远程发布默认走 Central Staging API，只有显式启用自定义 repository 模式时才使用该值 |
| `publishUserName` | 发布用户名 | Central 发布中作为 token username fallback |
| `publishPassword` | 发布密码 | Central 发布中作为 token password fallback |

为了避免旧项目配置阶段突然失败，新增字段不能要求旧项目立即配置。只有执行 `PublishLibraryRemoteTask` 时，才按 Central 标准校验新增字段。

新增 Central 与 POM 字段：

```kotlin
var remotePublishMode = "central" // central / customRepository
var centralNamespace = ""
var centralPublishingType = "user_managed" // user_managed / automatic / portal_api
var centralRepositoryName = "CentralStaging"

var pomName = ""
var pomDescription = ""
var pomInceptionYear = ""
var pomUrl = ""

var licenseName = "The Apache License, Version 2.0"
var licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt"
var licenseDistribution = "repo"

var developerId = ""
var developerName = ""
var developerEmail = ""
var developerOrganization = ""
var developerOrganizationUrl = ""
var developerUrl = ""

var scmUrl = ""
var scmConnection = ""
var scmDeveloperConnection = ""
```

凭据不建议继续放在 `PublishInfo`，但为了兼容可以保留。解析优先级：

远程 Central 发布的 token 解析优先级：

1. Gradle property：`centralUsername` / `centralPassword`
2. 环境变量：`CENTRAL_USERNAME` / `CENTRAL_PASSWORD`
3. Gradle property：`mavenCentralUsername` / `mavenCentralPassword`
4. 环境变量：`MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD`
5. 兼容字段：`publishUserName` / `publishPassword`
6. `local.properties` 中的 `publishUserName` / `publishPassword`

当 `remotePublishMode = "customRepository"` 时，自定义 repository 发布的凭据解析优先级：

1. `PublishInfo.publishUrl` / `publishUserName` / `publishPassword`
2. `local.properties` 中的 `publishUrl` / `publishUserName` / `publishPassword`

不再建议保留旧默认账号密码 fallback。字段要兼容，硬编码敏感默认值不属于字段兼容范围。

签名凭据优先用环境变量或 Gradle property：

```text
ORG_GRADLE_PROJECT_signingInMemoryKey
ORG_GRADLE_PROJECT_signingInMemoryKeyId
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
```

远程 Central 发布必须禁用插件内置默认账号密码，避免误用旧 OSSRH/私服凭据。

## 使用示例

业务项目继续只配置 `custom.android.plugin` 和 `PublishInfo`。

```kotlin
plugins {
    id("com.android.library")
    id("custom.android.plugin")
}

PublishInfo {
    groupId = "ai.looktech.ltrpc.schema"
    artifactId = "bt-server"
    version = "1.0.0"

    centralNamespace = "ai.looktech"
    centralPublishingType = "user_managed"

    pomName = "Looktech RPC BT Server Schema"
    pomDescription = "BT Server Schema library for Looktech RPC"
    pomInceptionYear = "2025"
    pomUrl = "https://github.com/Entertech/lt-rpc-schema"

    developerId = "Entertech"
    developerName = "Entertech"
    developerEmail = "dev@example.com"
    developerOrganization = "Entertech"
    developerOrganizationUrl = "https://github.com/Entertech"
    developerUrl = "https://github.com/Entertech"

    scmUrl = "https://github.com/Entertech/lt-rpc-schema"
    scmConnection = "scm:git:git://github.com/Entertech/lt-rpc-schema.git"
    scmDeveloperConnection = "scm:git:ssh://git@github.com/Entertech/lt-rpc-schema.git"
}
```

CI 环境变量：

```yaml
env:
  ORG_GRADLE_PROJECT_centralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
  ORG_GRADLE_PROJECT_centralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
  ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_KEY_CONTENTS }}
  ORG_GRADLE_PROJECT_signingInMemoryKeyId: ${{ secrets.SIGNING_KEY_ID }}
  ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_PASSWORD }}
```

本地开发：

```bash
./gradlew :module:PublishLibraryLocalTask
```

Central 远程发布：

```bash
./gradlew :module:PublishLibraryRemoteTask
```

## CLI 支持

插件需要支持直接从命令行发布，适合本地验证、CI 调试和非 GitHub 平台。

### 本地 Maven

旧命令保持不变：

```bash
./gradlew :module:PublishLibraryLocalTask
```

### 远程发布到 Central Portal

旧 `PublishLibraryRemoteTask` 对应地址已经失效，因此该任务语义迁移为发布到 Central Portal：

```bash
./gradlew :module:PublishLibraryRemoteTask
```

### Central Portal 手动管理发布

推荐先用 `user_managed`：

```bash
CENTRAL_USERNAME="$TOKEN_USERNAME" \
CENTRAL_PASSWORD="$TOKEN_PASSWORD" \
ORG_GRADLE_PROJECT_signingInMemoryKey="$GPG_PRIVATE_KEY" \
ORG_GRADLE_PROJECT_signingInMemoryKeyId="$GPG_KEY_ID" \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$GPG_PASSWORD" \
./gradlew :module:PublishLibraryRemoteTask \
  -PcentralNamespace=ai.looktech \
  -PcentralPublishingType=user_managed \
  --stacktrace
```

### Central Portal 自动发布

自动发布只建议在试点稳定后开启：

```bash
CENTRAL_USERNAME="$TOKEN_USERNAME" \
CENTRAL_PASSWORD="$TOKEN_PASSWORD" \
ORG_GRADLE_PROJECT_signingInMemoryKey="$GPG_PRIVATE_KEY" \
ORG_GRADLE_PROJECT_signingInMemoryKeyId="$GPG_KEY_ID" \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$GPG_PASSWORD" \
./gradlew :module:PublishLibraryRemoteTask \
  -PcentralNamespace=ai.looktech \
  -PcentralPublishingType=automatic \
  --stacktrace
```

### CLI 参数覆盖规则

为了支持无需修改业务 `build.gradle` 的发布，Central 相关字段允许 Gradle property 覆盖 `PublishInfo`：

| Gradle property | 覆盖字段 |
| --- | --- |
| `-PcentralNamespace=...` | `centralNamespace` |
| `-PcentralPublishingType=...` | `centralPublishingType` |
| `-PcentralUsername=...` | token username |
| `-PcentralPassword=...` | token password |
| `-PpomName=...` | `pomName` |
| `-PpomDescription=...` | `pomDescription` |
| `-PpomUrl=...` | `pomUrl` |

解析顺序：

```text
Gradle property > 环境变量 > PublishInfo 字段 > local.properties > 默认值
```

默认值只能用于非敏感字段。账号、密码、GPG 私钥不能有代码内默认值。

## GitHub Actions CI 支持

需要提供一份可复用 workflow 示例，业务项目可以直接复制。

### Required secrets

```text
MAVEN_CENTRAL_USERNAME
MAVEN_CENTRAL_PASSWORD
GPG_KEY_CONTENTS
SIGNING_KEY_ID
SIGNING_PASSWORD
```

`MAVEN_CENTRAL_USERNAME` 和 `MAVEN_CENTRAL_PASSWORD` 必须是 Central Portal 生成的 User Token，不是登录密码，也不是旧 OSSRH token。

`GPG_KEY_CONTENTS` 使用：

```bash
gpg --export-secret-keys --armor <key-id>
```

### Workflow 示例

```yaml
name: Publish Maven Central

on:
  workflow_dispatch:
    inputs:
      module:
        description: "Gradle module path, for example :library"
        required: true
        type: string
      version:
        description: "Release version, for example 1.0.0"
        required: true
        type: string
      namespace:
        description: "Central namespace, for example ai.looktech"
        required: true
        type: string
      publishing_type:
        description: "user_managed or automatic"
        required: true
        default: "user_managed"
        type: choice
        options:
          - user_managed
          - automatic

permissions:
  contents: read

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          distribution: zulu
          java-version: "17"

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Publish to Central Portal
        env:
          CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_KEY_CONTENTS }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyId: ${{ secrets.SIGNING_KEY_ID }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_PASSWORD }}
        run: |
          ./gradlew "${{ inputs.module }}:PublishLibraryRemoteTask" \
            -Pversion="${{ inputs.version }}" \
            -PcentralNamespace="${{ inputs.namespace }}" \
            -PcentralPublishingType="${{ inputs.publishing_type }}" \
            --no-daemon \
            --stacktrace
```

### CI 行为要求

1. `workflow_dispatch` 先手动触发，不自动绑定 tag。
2. 默认 `user_managed`，发布后去 Central Portal 手动检查和 Publish。
3. 试点稳定后，再增加 tag release 自动触发。
4. CI 日志不能打印 token、GPG 私钥、签名密码。
5. 发布失败时保留 Gradle stacktrace，但敏感字段必须脱敏。
6. 如果 manual upload 返回 deployment id，CI 应输出 Central Portal deployments 链接。

## 插件内部改造点

### 1. 复用远程发布任务

职责：

1. 校验 `PublishInfo` 的 Central 必需字段。
2. 校验 version 不是 `-debug`，不是空版本。
3. 执行 `publishEnterPublishPublicationToCentralStagingRepository`。
4. 调用 manual upload endpoint，把 OSSRH Staging API 兼容层里的文件转入 Central Portal deployment。
5. 打印 deployment 后续操作提示。

保留现有任务：

- `PublishLibraryLocalTask`：本地验证，不强制 sources/javadoc/signing。
- `PublishLibraryRemoteTask`：继续作为“远程发布”入口，内部实现迁移为 Central Portal 发布；重点是旧 `PublishInfo` 字段仍然可用。

### 2. Central repository 配置

Central release repository：

```text
https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/
```

repository name：

```text
CentralStaging
```

对应 Gradle task 会变成：

```text
publishEnterPublishPublicationToCentralStagingRepository
```

Snapshots 后续可单独支持：

```text
https://central.sonatype.com/repository/maven-snapshots/
```

第一阶段先只做 release。

### 3. POM 元数据

在创建 `MavenPublication` 时统一补齐：

```kotlin
publication.pom {
    name.set(publishInfo.pomName.ifBlank { publishInfo.artifactId })
    description.set(publishInfo.pomDescription)
    url.set(publishInfo.pomUrl)
    inceptionYear.set(publishInfo.pomInceptionYear)

    licenses {
        license {
            name.set(publishInfo.licenseName)
            url.set(publishInfo.licenseUrl)
            distribution.set(publishInfo.licenseDistribution)
        }
    }

    developers {
        developer {
            id.set(publishInfo.developerId)
            name.set(publishInfo.developerName)
            email.set(publishInfo.developerEmail)
            organization.set(publishInfo.developerOrganization)
            organizationUrl.set(publishInfo.developerOrganizationUrl)
            url.set(publishInfo.developerUrl)
        }
    }

    scm {
        url.set(publishInfo.scmUrl)
        connection.set(publishInfo.scmConnection)
        developerConnection.set(publishInfo.scmDeveloperConnection)
    }
}
```

POM 校验参考 `lt-rpc-schema` 的失败信息：Kotlin publication 检查会明确要求 developer 的 `email`、`organization`、`organizationUrl`。自研插件也应该在 `PublishLibraryRemoteTask` 执行前主动校验这些字段，避免等远端失败。

### 4. sources/javadoc 策略

规则：

- 本地 `publishToMavenLocal`：非 `-debug` 不带 sources，保持当前用户诉求。
- `-debug` 本地发布：可带 sources，保持当前插件已有逻辑。
- Central 远程发布：无论是否 debug，必须带 sources/javadoc；但 `PublishLibraryRemoteTask` 禁止 debug version。

sources jar：

- Android Library：读取 `android.sourceSets["main"].java.srcDirs`。
- Java/Groovy/Gradle 插件：读取 `sourceSets["main"].allSource`。

javadoc jar：

- 如果项目有 `javadoc` task，则打包 javadoc 输出。
- Android/Kotlin 项目没有稳定 javadoc 输出时，生成一个空 `javadoc.jar`，至少满足 Central artifact 要求。
- 后续可接 Dokka，但第一阶段不强制引入。

### 5. GPG signing

远程 Central 发布需要：

```kotlin
project.plugins.apply("signing")
```

配置：

```kotlin
signing {
    useInMemoryPgpKeys(keyId, key, password)
    sign(publication)
}
```

签名只在 Central 发布时 required。普通本地发布、自定义 Maven repository 发布不强制签名，避免破坏非 Central 场景。

### 6. manual upload endpoint

Gradle `maven-publish` 只会 PUT Maven layout 文件。Sonatype 文档说明，为了让 deployment 出现在 Central Portal，上传完成后必须追加：

```http
POST https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/<namespace>?publishing_type=user_managed
Authorization: Bearer base64(tokenUsername:tokenPassword)
```

默认使用：

```text
publishing_type=user_managed
```

这样上传后需要登录 Central Portal 检查并手动 Publish。等验证稳定后，再允许配置：

```text
centralPublishingType = "automatic"
```

### 7. 错误处理

`PublishLibraryRemoteTask` 失败时需要给出明确原因：

- 缺 namespace。
- groupId 不在 namespace 下。
- 缺 POM 必需字段。
- 缺 Central token。
- 缺 signing key。
- version 包含 `debug`。
- 远端返回 401：token 错误或仍在使用旧 OSSRH token。
- 远端返回 403：namespace 权限不匹配。
- manual upload 后 Portal 校验失败：提示去 Central Portal deployment 页面看具体错误。

## 实施步骤

### Phase 1：最小可用 Central 发布

1. `PublishInfo` 增加 Central/POM 字段，所有新增字段保持可选。
2. 保留旧字段名、类型和配置入口；不要求保留旧远程发布实现。
3. 复用 `PublishLibraryRemoteTask`，把远程发布实现迁移为 Central Portal。
4. 配置 `CentralStaging` repository。
5. `PublishLibraryRemoteTask` 执行前校验 POM/namespace/凭据/signing。
6. Central publication 强制 sources/javadoc。
7. 接入 `signing` 插件并生成 `.asc`。
8. 发布完成后调用 manual upload endpoint。
9. 增加 CLI 参数覆盖能力。
10. README 增加 Central 使用说明和 GitHub Actions 示例。
11. 添加 Gradle TestKit 测试覆盖：
    - local 非 debug 不生成 sources。
    - old `PublishInfo` 字段仍能完成插件配置。
    - old `publishUserName` / `publishPassword` 能作为 Central token fallback。
    - old `local.properties` 字段仍能作为字段输入 fallback。
    - central release 生成 sources/javadoc/signatures。
    - 缺 POM 必需字段时报错。
    - 缺 signing key 时报错。
    - Central repository URL 正确。
    - CLI `-PcentralNamespace` 能覆盖 `PublishInfo.centralNamespace`。

### Phase 2：CI 与业务项目接入

1. 在一个试点项目中配置 Central namespace 和 POM 字段。
2. GitHub Actions 增加 Central secrets。
3. 增加 `workflow_dispatch` 手动发布 workflow。
4. release workflow 先用 `user_managed` 上传。
5. 在 Central Portal 手动检查并 Publish。
6. 验证 Maven Central 可消费。
7. 写迁移说明给其他项目复用。
8. 试点稳定后再接入 tag 自动发布。

### Phase 3：增强为 Portal API 原生上传

1. 发布到本地 staging 目录。
2. 校验 Maven layout 文件完整性。
3. zip staging repo。
4. 调用 Publisher API upload。
5. 轮询 deployment status。
6. 支持 automatic publish / drop。

## 验证清单

本地插件验证：

```bash
./gradlew :plugin_base:build
```

业务项目本地发布验证：

```bash
./gradlew :some-library:PublishLibraryLocalTask
```

确认本地 Maven 非 debug 无 sources：

```bash
find ~/.m2/repository/<group-path>/<artifact>/<version> -maxdepth 1 -type f
```

Central dry run 验证：

```bash
./gradlew :some-library:generatePomFileForEnterPublishPublication
./gradlew :some-library:signEnterPublishPublication
```

Central 上传验证：

```bash
./gradlew :some-library:PublishLibraryRemoteTask --stacktrace
```

CLI 覆盖验证：

```bash
./gradlew :some-library:PublishLibraryRemoteTask \
  -PcentralNamespace=ai.looktech \
  -PcentralPublishingType=user_managed \
  --stacktrace
```

Portal 检查：

```text
https://central.sonatype.com/publishing/deployments
```

## 风险与取舍

1. Central 发布不可覆盖同版本。测试时必须使用新版本号。
2. OSSRH Staging API 兼容层按 IP 隔离 repository，同一个 CI 出现失败后可能需要 drop repository。
3. 空 javadoc jar 通常可满足 Central artifact 要求，但长期建议引入 Dokka 生成真实文档。
4. 当前 `PublishPlugin` 主要面向 Android Library 和 Gradle Plugin；如果其他项目是 Kotlin Multiplatform，仍建议使用 `lt-rpc-schema` 那套 Vanniktech 方案，或者后续扩展插件支持 KMP publications。
5. signing key 和 Central token 必须进 CI secret，不能写入 `local.properties` 或仓库。
6. 旧字段兼容不等于旧逻辑兼容；如果旧字段映射到 Central 后行为变化，需要在 README 和日志中明确说明。
7. CLI 覆盖能力提升灵活性，但也可能造成构建脚本和发布参数不一致；CI 日志要打印最终非敏感配置摘要。

## 推荐结论

先不要让业务项目直接改用 Vanniktech。短期把 `lt-rpc-schema` 的发布约束抽象进 `PublishPlugin`：

- 对业务项目保持 `PublishInfo` 接入方式。
- 旧字段名、字段类型、字段配置方式继续兼容。
- 复用 `PublishLibraryRemoteTask` 作为 Central 远程发布入口。
- 支持 CLI 参数覆盖，便于本地和非 GitHub CI 使用。
- 提供 GitHub Actions workflow 示例，支持手动 `workflow_dispatch` 发布。
- Central 发布强制 POM、sources、javadoc、signing。
- 使用 Sonatype OSSRH Staging API 兼容层完成第一版迁移。
- 默认 `user_managed`，先人工在 Portal 发布。

这样能最小化对现有项目的影响，同时满足 Central Portal 的发布规则。
