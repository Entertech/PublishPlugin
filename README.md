# PublishPlugin

`PublishPlugin` 用于给 Android Library 和 Gradle Plugin 模块生成 Maven publication，并提供统一的本地发布、远程发布入口。

当前远程发布入口继续复用旧任务名 `PublishLibraryRemoteTask`，实现上默认面向 Sonatype Central Portal；旧的私服字段仍保留，避免已有项目的 `PublishInfo { ... }` 配置编译失败。

## 接入插件

在根工程 `build.gradle.kts` 中加入插件依赖：

```kotlin
buildscript {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }

    dependencies {
        classpath("cn.entertech.android:publish:1.2.0-local")
    }
}
```

在需要发布的 Library 或 Gradle Plugin 模块中应用插件：

```kotlin
plugins {
    id("com.android.library")
    id("custom.android.plugin")
}
```

Gradle Plugin 模块继续使用：

```kotlin
plugins {
    `java-gradle-plugin`
    id("custom.android.plugin")
}
```
或者 在需要打包的library｜gradle插件目录下的  **build.gradle** 文件添加下面的插件
```
apply plugin: 'custom.android.plugin'
```

## 基础配置

最小配置：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "base"
    version = "0.0.1"
}
```

旧字段仍兼容：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "base"
    version = "0.0.1"

    pluginId = ""
    implementationClass = ""

    publishUrl = ""
    publishUserName = ""
    publishPassword = ""
}
```

`local.properties` 也可以提供旧私服字段，值不要加引号：

```properties
publishUrl=https://repo.example.com/releases
publishUserName=username
publishPassword=password
```

## PublishInfo 字段说明

### 基础坐标字段

| 字段 | 作用 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `groupId` | Maven 坐标的 groupId | 是 | Central 发布时必须落在已验证的 `centralNamespace` 下，例如 namespace 是 `ai.looktech`，则 `groupId` 可以是 `ai.looktech` 或 `ai.looktech.xxx`。 |
| `artifactId` | Maven 坐标的 artifactId | 是 | 普通单组件发布时直接作为 artifactId；多 variant 发布时作为默认值和 base 值，可被 `artifactIdForVariant` 动态覆盖。 |
| `version` | Maven 坐标的 version | 是 | 本地发布时 `-debug` 版本会附带 sources jar；远程 Central 发布禁止使用包含 `debug` 的版本。 |

### Gradle 插件发布字段

只有发布 Gradle Plugin 模块时需要配置这两个字段；普通 Android Library 不需要。

| 字段 | 作用 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `pluginId` | Gradle 插件 ID | 发布 Gradle Plugin 时必填 | 写入 `gradlePlugin { plugins { ... } }` 的插件 ID，例如 `cn.entertech.foo`。 |
| `implementationClass` | Gradle 插件实现类 | 发布 Gradle Plugin 时必填 | 插件入口类全限定名，例如 `cn.entertech.foo.FooPlugin`。 |

### 旧私服字段

这些字段保留是为了兼容旧项目；默认 Central 发布不建议把凭据写在 `build.gradle.kts` 里。

| 字段 | 作用 | 生效场景 | 说明 |
| --- | --- | --- | --- |
| `publishUrl` | 自定义 Maven 仓库地址 | `remotePublishMode = "customRepository"` | 旧私服发布地址。默认 Central 模式不会用它作为上传地址。 |
| `publishUserName` | 自定义仓库用户名 | `customRepository`，也作为 Central token username fallback | 优先级低于 `-PcentralUsername`、`CENTRAL_USERNAME`、`-PmavenCentralUsername`、`MAVEN_CENTRAL_USERNAME`。 |
| `publishPassword` | 自定义仓库密码 | `customRepository`，也作为 Central token password fallback | 优先级低于 `-PcentralPassword`、`CENTRAL_PASSWORD`、`-PmavenCentralPassword`、`MAVEN_CENTRAL_PASSWORD`。 |

### Central 发布字段

| 字段 | 作用 | 是否必填 | 默认值/说明 |
| --- | --- | --- | --- |
| `remotePublishMode` | 选择远程发布模式 | 否 | 默认 `central`。可设为 `customRepository` 走旧自定义 Maven 仓库。 |
| `centralNamespace` | Sonatype Central namespace | Central 远程发布必填 | 例如 `ai.looktech`。远程发布前会校验 `groupId` 是否在该 namespace 下。 |
| `centralPublishingType` | Central Portal deployment 发布方式 | 否 | 默认 `user_managed`，上传后在 Portal 手动 Publish；可设为 `automatic`。 |
| `centralRepositoryName` | Gradle repository 名称 | 否 | 默认 `CentralStaging`，会影响 Gradle 任务名，例如 `publishEnterPublishPublicationToCentralStagingRepository`。 |

### POM 元数据字段

Central 远程发布要求 POM 元数据完整；这些字段本地发布可以为空，执行 `PublishLibraryRemoteTask` 时会校验。

| 字段 | 作用 | Central 是否必填 | 说明 |
| --- | --- | --- | --- |
| `pomName` | POM `<name>` | 否 | 为空时默认使用当前 artifactId。多 variant 动态 artifactId 场景下，会使用每个 publication 的最终 artifactId 作为 fallback。 |
| `pomDescription` | POM `<description>` | 是 | 简短说明库的用途。 |
| `pomInceptionYear` | POM `<inceptionYear>` | 否 | 项目开始年份。 |
| `pomUrl` | POM `<url>` | 是 | 项目主页、仓库地址或文档地址。 |

### License 字段

| 字段 | 作用 | 默认值 |
| --- | --- | --- |
| `licenseName` | POM license name | `The Apache License, Version 2.0` |
| `licenseUrl` | POM license URL | `https://www.apache.org/licenses/LICENSE-2.0.txt` |
| `licenseDistribution` | POM license distribution | `repo` |

### Developer 字段

Central 远程发布会校验开发者信息，建议全部配置。

| 字段 | 作用 | Central 是否必填 |
| --- | --- | --- |
| `developerId` | 开发者或组织 ID | 是 |
| `developerName` | 开发者或组织名称 | 是 |
| `developerEmail` | 联系邮箱 | 是 |
| `developerOrganization` | 组织名称 | 是 |
| `developerOrganizationUrl` | 组织主页 | 是 |
| `developerUrl` | 开发者主页 | 否 |

### SCM 字段

Central 远程发布要求源码仓库信息。

| 字段 | 作用 | 示例 |
| --- | --- | --- |
| `scmUrl` | 仓库浏览地址 | `https://github.com/Entertech/lt-rpc-schema` |
| `scmConnection` | 只读 SCM 地址 | `scm:git:git://github.com/Entertech/lt-rpc-schema.git` |
| `scmDeveloperConnection` | 开发者 SCM 地址 | `scm:git:ssh://git@github.com/Entertech/lt-rpc-schema.git` |

### 动态 artifactId 回调

`artifactIdForVariant { variant -> ... }` 不是普通字段，而是按 Android release variant 计算 artifactId 的回调。

| API | 作用 | 生效场景 |
| --- | --- | --- |
| `artifactIdForVariant` | 为每个 variant 返回独立 artifactId | Android Library 存在多个 release component 时 |
| `variant.name` | 当前 component/variant 名 | 例如 `breathAuthRelease` |
| `variant.buildType` | 当前 build type | 当前插件只发布 `release` component |
| `variant.flavors` | flavor 维度到 flavor 名称的 Map | 例如 `project -> breath`、`authentication -> auth` |
| `variant.flavor("dimension")` | 获取指定维度的 flavor 名称 | 不存在时返回空字符串 |

## 本地发布

执行模块下的自定义任务：

```bash
./gradlew :module:PublishLibraryLocalTask
```

或直接使用 Gradle Maven Publish 标准任务：

```bash
./gradlew :module:publishToMavenLocal
```

本地发布的 sources 规则：

- `version` 以 `-debug` 结尾时，发布 sources jar。
- 非 debug 版本默认不发布 sources jar。
- Central 远程发布不受这个规则限制，Central 必须包含 sources 和 javadoc。

## Central Portal 发布

远程发布仍执行旧任务名：

```bash
./gradlew :module:PublishLibraryRemoteTask
```

Central 必需配置示例：

```kotlin
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

Central token 和签名建议通过环境变量或 Gradle property 注入，不要写入仓库：

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

支持的 Central 相关字段：

```kotlin
PublishInfo {
    remotePublishMode = "central" // central / customRepository
    centralNamespace = ""
    centralPublishingType = "user_managed" // user_managed / automatic
    centralRepositoryName = "CentralStaging"

    pomName = ""
    pomDescription = ""
    pomInceptionYear = ""
    pomUrl = ""

    licenseName = "The Apache License, Version 2.0"
    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt"
    licenseDistribution = "repo"

    developerId = ""
    developerName = ""
    developerEmail = ""
    developerOrganization = ""
    developerOrganizationUrl = ""
    developerUrl = ""

    scmUrl = ""
    scmConnection = ""
    scmDeveloperConnection = ""
}
```

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

## CLI 覆盖

Central 字段支持命令行覆盖，便于本地和 CI 使用：

| Gradle property | 用途 |
| --- | --- |
| `-PcentralNamespace=...` | 覆盖 `centralNamespace` |
| `-PcentralPublishingType=...` | 覆盖 `centralPublishingType` |
| `-PcentralUsername=...` | Central token username |
| `-PcentralPassword=...` | Central token password |
| `-PmavenCentralUsername=...` | Central token username fallback |
| `-PmavenCentralPassword=...` | Central token password fallback |
| `-PpomName=...` | 覆盖 `pomName` |
| `-PpomDescription=...` | 覆盖 `pomDescription` |
| `-PpomUrl=...` | 覆盖 `pomUrl` |

解析优先级：

```text
Gradle property > 环境变量 > PublishInfo 字段 > local.properties > 默认值
```

## GitHub Actions 示例

```yaml
name: Publish Maven Central

on:
  workflow_dispatch:
    inputs:
      module:
        description: "Gradle module path, for example :library"
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
            -PcentralNamespace="${{ inputs.namespace }}" \
            -PcentralPublishingType="${{ inputs.publishing_type }}" \
            --no-daemon \
            --stacktrace
```

`MAVEN_CENTRAL_USERNAME` 和 `MAVEN_CENTRAL_PASSWORD` 必须使用 Central Portal 生成的 User Token。
