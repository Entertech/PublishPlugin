# PublishPlugin 多 Variant 发布方案

## 背景

`PublishPlugin` 需要支持 Android Library 一次发布多个 release variant。当前典型业务场景是本地 SDK 同时存在两个 flavor 维度：

```kotlin
flavorDimensions += listOf("project", "authentication")

productFlavors {
    create("breath") { dimension = "project" }
    create("obsession") { dimension = "project" }
    create("flowtime") { dimension = "project" }
    create("sdk") { dimension = "project" }

    create("auth") { dimension = "authentication" }
    create("noAuth") { dimension = "authentication" }
}
```

目标是同一个模块一次发布 8 个 release 组件：

```text
breathAuthRelease
breathNoAuthRelease
obsessionAuthRelease
obsessionNoAuthRelease
flowtimeAuthRelease
flowtimeNoAuthRelease
sdkAuthRelease
sdkNoAuthRelease
```

发布插件不能把 `project`、`authentication` 这类业务维度写死。插件只负责识别 Android release variants、创建 Maven publications、提供 variant 信息；artifactId 命名规则由业务项目通过 `PublishInfo` 动态声明。

## 已发现 Bug

在本地 SDK 项目里使用 `artifactIdForVariant` 后，POM 任务可以成功：

```bash
./gradlew :affective_local_sdk:generatePomFileForBreathAuthReleaseEnterPublishPublication
```

但真实本地发布失败：

```bash
./gradlew :affective_local_sdk:publishToMavenLocal --stacktrace
```

失败信息：

```text
:affective_local_sdk:generateMetadataFileForBreathAuthReleaseEnterPublishPublication FAILED

Invalid publication 'BreathAuthReleaseEnterPublish':
  - This publication must publish at least one variant
```

结论：

1. 插件当前能创建 `BreathAuthReleaseEnterPublish` 等 publication，也能写出 POM。
2. 只跑 `generatePomFileFor...Publication` 不能证明 publication 可真实发布。
3. `publishToMavenLocal` 会生成 Gradle module metadata，metadata 要求 publication 至少包含一个可发布 variant。
4. 当前多 flavor publication 没有绑定到 AGP 暴露的可发布 variant，所以 metadata 生成失败。
5. 不能通过禁用 `GenerateModuleMetadata` 修复。这只是在绕过错误，会丢失 Gradle variant metadata，并可能发布不完整组件。

后续复测又发现第二类“假成功”问题：

```text
PublishPlugin register android singleVariant breathAuthRelease
PublishPlugin publish breathAuthRelease as BreathAuthReleaseEnterPublish:breath-affective-offline-sdk-authentication
PublishPlugin PluginModule error Variant for configuration breathAuthReleaseSourcesElements does not exist in component breathAuthRelease

> Task :affective_local_sdk:publishToMavenLocal UP-TO-DATE
BUILD SUCCESSFUL
```

此时 `~/.m2/repository/cn/entertech/android/breath-affective-offline-sdk-authentication` 等新坐标目录没有生成，只有旧命名规则下的残留目录。

根因：

1. 插件为了非 debug 版本不发布 sources，会尝试对 `<variant>SourcesElements` 调用 `withVariantsFromConfiguration(...).skip()`。
2. 某些 AGP/Gradle 场景下 `<variant>SourcesElements` configuration 存在，但它不是当前 `SoftwareComponent` 的 variant。
3. `withVariantsFromConfiguration` 会抛出 `Variant for configuration ... does not exist in component ...`。
4. 外层 `afterEvaluate` catch 只打印错误，没有重新抛出异常。
5. publication 创建被中断，但 Gradle 没有失败，最终 `publishToMavenLocal` 没有实际 publication 可执行，表现为 `BUILD SUCCESSFUL` / `UP-TO-DATE`。

结论：多 variant 发布的验收必须检查 Maven Local 是否真实生成 `.aar/.pom/.module`，不能只看 Gradle 退出码和 POM 生成任务。

## 设计目标

1. 兼容旧字段：`PublishInfo.artifactId` 仍是必填字段，也是默认 artifactId。
2. 兼容旧项目：无 flavor 的普通 `release` 组件仍生成 `EnterPublish` publication。
3. 支持多 release variant：每个 release variant 生成一个 publication。
4. publication 命名稳定：`<VariantName>EnterPublish`，例如 `BreathAuthReleaseEnterPublish`。
5. artifactId 由业务项目通过 `artifactIdForVariant` 回调动态计算。
6. 未配置 `artifactIdForVariant` 时，所有 publication 使用 `PublishInfo.artifactId`，不破坏旧行为。
7. 支持业务项目过滤不需要发布的 variant，例如只发布 `sdk` 项目，或跳过某些 `project/authentication` 组合。
8. `PublishLibraryLocalTask`、`publishToMavenLocal`、`PublishLibraryRemoteTask` 都必须支持多 publication。
9. 验收标准必须跑真实 `publishToMavenLocal`，不能只跑 POM 生成。
10. sources variant 跳过逻辑不能假设所有 `<variant>SourcesElements` 都属于当前 component；不属于当前 component 时应跳过该 sources configuration，而不是中断 publication 创建。
11. publication 配置阶段的未知异常必须让 Gradle 失败，禁止只打印日志后吞掉异常，避免“假成功”。

## 发布模型

多 variant 发布必须分两步：

1. **推导候选 release variants**：根据 flavor dimension 顺序和 release build type 计算所有候选 variant。
2. **应用发布过滤规则**：用 `PublishInfo` 中的 variant filter 移除不需要发布的 variant。
3. **让 AGP 暴露可发布 variant**：在 Android DSL finalize 前，只为过滤后的 variants 注册 `android.publishing.singleVariant("<variantName>")`。
4. **创建 MavenPublication**：在 components 生成后，只为过滤后的 release component 创建 publication，并调用 `publication.from(component)`。

插件最终应自动完成类似配置：

```kotlin
android {
    publishing {
        singleVariant("breathAuthRelease")
        singleVariant("breathNoAuthRelease")
        singleVariant("obsessionAuthRelease")
        singleVariant("obsessionNoAuthRelease")
        singleVariant("flowtimeAuthRelease")
        singleVariant("flowtimeNoAuthRelease")
        singleVariant("sdkAuthRelease")
        singleVariant("sdkNoAuthRelease")
    }
}
```

这段不应该要求业务项目手写。业务项目只配置 flavors 和 `PublishInfo`。

## PublishInfo API

新增 variant-aware artifactId 回调和发布过滤回调：

```kotlin
fun artifactIdForVariant(action: (PublishVariantInfo) -> String)
fun artifactIdForVariant(action: groovy.lang.Closure<*>)

fun skipVariantIf(action: (PublishVariantInfo) -> Boolean)
fun skipVariantIf(action: groovy.lang.Closure<*>)
```

默认行为是不跳过任何 variant。`skipVariantIf` 返回 `true` 表示该 variant 不发布；返回 `false` 表示保留发布。

Variant 信息模型：

```kotlin
open class PublishVariantInfo(
    val name: String,
    val buildType: String,
    val flavors: Map<String, String>
) {
    fun flavor(dimension: String): String
}
```

字段说明：

| 字段/API | 说明 |
| --- | --- |
| `name` | 当前 Android component/variant 名，例如 `breathAuthRelease` |
| `buildType` | 当前 build type，第一版只发布 `release` component |
| `flavors` | flavor 维度到 flavor 名称的映射，例如 `project -> breath`、`authentication -> auth` |
| `flavor("dimension")` | 获取指定维度的 flavor 名称，不存在时返回空字符串 |

过滤规则：

| 规则 | 说明 |
| --- | --- |
| 未配置 `skipVariantIf` | 发布所有 release variants，保持兼容 |
| 配置一个 `skipVariantIf` | 回调返回 `true` 的 variant 不发布 |
| 配置多个 `skipVariantIf` | 任意一个回调返回 `true` 就不发布 |
| 所有 variants 都被跳过 | 插件应失败并输出明确错误，避免静默发布空结果 |

过滤必须在 `singleVariant(...)` 注册前执行。否则不需要的 variant 仍会被 AGP 暴露为 publish component，后续 publication/task 也可能被创建出来。

Kotlin DSL 示例：

```kotlin
val baseArtifactId = "affective-offline-sdk"

fun buildLocalSdkArtifactId(
    project: String,
    requireAuthentication: Boolean,
): String {
    val projectPrefix = project.takeIf { it.isNotBlank() }?.let { "$it-" } ?: ""
    val authenticationSuffix = if (requireAuthentication) "-authentication" else ""
    return "$projectPrefix$baseArtifactId$authenticationSuffix"
}

PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = baseArtifactId
    version = libraryVersion

    artifactIdForVariant { variant ->
        buildLocalSdkArtifactId(
            project = variant.flavor("project"),
            requireAuthentication = variant.flavor("authentication") == "auth",
        )
    }

    // 示例：flowtime 暂不发布。
    skipVariantIf { variant ->
        variant.flavor("project") == "flowtime"
    }
}
```

Groovy DSL 示例：

```groovy
def baseArtifactId = 'affective-offline-sdk'

PublishInfo {
    groupId = 'cn.entertech.android'
    artifactId = baseArtifactId
    version = libraryVersion

    artifactIdForVariant { variant ->
        def productPrefix = "${variant.flavor('project')}-"
        def authSuffix = variant.flavor('authentication') == 'auth' ? '-authentication' : ''
        return "${productPrefix}${baseArtifactId}${authSuffix}"
    }

    // 示例：只发布 sdk 项目。
    skipVariantIf { variant ->
        return variant.flavor('project') != 'sdk'
    }
}
```

未过滤时的示例产物：

| Variant | artifactId |
| --- | --- |
| `breathAuthRelease` | `breath-affective-offline-sdk-authentication` |
| `breathNoAuthRelease` | `breath-affective-offline-sdk` |
| `sdkAuthRelease` | `sdk-affective-offline-sdk-authentication` |
| `sdkNoAuthRelease` | `sdk-affective-offline-sdk` |

如果配置：

```kotlin
skipVariantIf { variant ->
    variant.flavor("project") == "flowtime"
}
```

则不会注册、不会创建、也不会发布：

```text
flowtimeAuthRelease
flowtimeNoAuthRelease
```

如果配置：

```kotlin
skipVariantIf { variant ->
    variant.flavor("project") != "sdk"
}
```

则最终只发布：

```text
sdkAuthRelease
sdkNoAuthRelease
```

## AGP/Gradle 兼容策略

插件需要兼容不同 Android Gradle Plugin / Gradle 版本，因此不能只按某个 AGP 版本的强类型 API 写死。

推荐策略：

1. 优先使用 AGP 7+ 的 `androidComponents.finalizeDsl { ... }` 时机读取最终 Android DSL。
2. 在 `finalizeDsl` 中根据 flavor dimension 顺序和 release build type 推导所有 release variant 名。
3. 通过反射或版本隔离调用 `android.publishing.singleVariant(variantName)`。
4. 如果运行环境没有 `androidComponents.finalizeDsl` 或 `singleVariant`，保持旧单 release 行为。
5. 如果检测到多 flavor 但当前 AGP 不支持自动注册多 variant publication，给出明确错误，提示升级 AGP 或临时手写 `android.publishing.singleVariant(...)`。
6. 不在 `afterEvaluate` 里首次注册 `singleVariant`，因为 Android DSL 可能已经 finalize，过晚修改不稳定。

伪代码：

```kotlin
plugins.withId("com.android.library") {
    val androidComponents = extensions.findByName("androidComponents")
    if (supportsFinalizeDsl(androidComponents)) {
        finalizeDsl(androidComponents) { androidDsl ->
            val releaseVariantNames = computeReleaseVariantNames(androidDsl)
            releaseVariantNames.forEach { variantName ->
                registerSingleVariant(androidDsl, variantName)
            }
        }
    }
}
```

`computeReleaseVariantNames` 规则：

1. 读取 Android DSL 中的 `flavorDimensions` 顺序。
2. 读取每个 `productFlavor` 的 `name` 和 `dimension`。
3. 按 dimension 顺序做笛卡尔积。
4. 拼接 flavor 名时，第一个 flavor 保持原样，后续 flavor 首字母大写。
5. 最后追加 `Release`。
6. 构造 `PublishVariantInfo`，应用 `skipVariantIf` 过滤。
7. 没有 flavor 时只返回 `release`，继续走旧 `EnterPublish` publication。
8. 如果过滤后没有任何可发布 release variant，构建失败并输出被过滤的候选 variants，提示检查 `skipVariantIf`。

## Publication 创建规则

Android Library：

1. 只发布 release component。
2. 单 release component：publication 名为 `EnterPublish`。
3. 多 release component：publication 名为 `<VariantName>EnterPublish`。
4. 被 `skipVariantIf` 过滤掉的 component 不注册 `singleVariant`，不创建 publication，不生成 publish task。
5. 每个 publication 调用 `publication.from(component)`。
6. 每个 publication 使用 `publishInfo.resolveArtifactId(variantInfo)` 计算 artifactId。
7. POM 的 `name` fallback 使用最终 artifactId，而不是固定 `PublishInfo.artifactId`。
8. 非 debug 本地发布移除 sources artifact 时，如果 `<variant>SourcesElements` configuration 不属于当前 component，只记录 debug 日志并继续创建 publication。
9. 除上述已知 sources mismatch 外，publication 创建过程中的异常必须继续抛出，让 Gradle 构建失败。

Gradle Plugin：

1. 继续只发布 `java` component。
2. publication 名继续是 `EnterPublish`。
3. 不参与 Android variant 逻辑。

## PublishLibraryRemoteTask 行为

远程发布入口不新增任务，继续复用 `PublishLibraryRemoteTask`。

任务选择规则：

| publication 数量 | repository 模式 | 执行任务 |
| --- | --- | --- |
| 单个 `EnterPublish` | Central | `publishEnterPublishPublicationTo<RepositoryName>Repository` |
| 单个 `EnterPublish` | customRepository | `publishEnterPublishPublicationToMavenRepository` |
| 多个 `*EnterPublish` | Central | `publishAllPublicationsTo<RepositoryName>Repository` |
| 多个 `*EnterPublish` | customRepository | `publishAllPublicationsToMavenRepository` |

成功日志也要支持多 publication，逐个打印：

```text
implementation "group:artifact:version"
```

## 测试方案

### 1. 红灯测试：真实 publishToMavenLocal 失败

新增 TestKit fixture：Android Library + 两个 flavor 维度。

执行：

```bash
./gradlew :fixture:publishToMavenLocal \
  -Dmaven.repo.local=<temp-maven-local> \
  --stacktrace
```

当前预期失败：

```text
This publication must publish at least one variant
```

### 2. 绿灯测试：真实 publishToMavenLocal 成功

修复后同一命令必须成功。

断言本地 Maven 目录存在：

```text
com/example/breath-affective-offline-sdk-authentication/1.0.0/*.aar
com/example/breath-affective-offline-sdk-authentication/1.0.0/*.pom
com/example/breath-affective-offline-sdk-authentication/1.0.0/*.module
com/example/breath-affective-offline-sdk/1.0.0/*.aar
com/example/breath-affective-offline-sdk/1.0.0/*.pom
com/example/breath-affective-offline-sdk/1.0.0/*.module
```

同时检查 POM：

```xml
<artifactId>breath-affective-offline-sdk-authentication</artifactId>
```

### 3. 非 debug sources 规则

发布 `version = "1.0.0"` 后确认没有 sources jar：

```text
breath-affective-offline-sdk-authentication-1.0.0-sources.jar
```

不应该存在。

发布 `version = "1.0.0-debug"` 时，sources jar 可以存在，保持旧逻辑。

### 4. 单 release 兼容测试

没有 flavor 的 Android Library 仍应发布：

```text
EnterPublish
```

artifactId 使用 `PublishInfo.artifactId`。

### 5. Variant 过滤测试

新增 fixture 配置：

```groovy
PublishInfo {
    groupId = 'com.example'
    artifactId = 'affective-offline-sdk'
    version = '1.0.0'

    skipVariantIf { variant ->
        return variant.flavor('project') == 'flowtime'
    }
}
```

执行：

```bash
./gradlew :fixture:publishToMavenLocal \
  -Dmaven.repo.local=<temp-maven-local> \
  --stacktrace
```

断言：

1. `breathAuthRelease`、`breathNoAuthRelease`、`sdkAuthRelease`、`sdkNoAuthRelease` 等未过滤 variants 正常发布。
2. `flowtimeAuthRelease` 和 `flowtimeNoAuthRelease` 不存在对应 `.aar/.pom/.module`。
3. Gradle task 列表中不应该出现 `publishFlowtimeAuthReleaseEnterPublishPublicationToMavenLocal`。
4. 如果 `skipVariantIf` 过滤掉所有 candidates，构建失败并提示 `No publishable Android release variants`。

### 6. SourcesElements mismatch 回归测试

新增 fixture 人为创建同名 sources configuration，但不把它注册为 component variant：

```groovy
configurations.maybeCreate('breathAuthReleaseSourcesElements')
```

执行：

```bash
./gradlew :fixture:publishToMavenLocal \
  -Dmaven.repo.local=<temp-maven-local> \
  --stacktrace
```

断言：

1. 构建成功。
2. `breath-affective-offline-sdk-authentication`、`breath-affective-offline-sdk`、`sdk-affective-offline-sdk-authentication`、`sdk-affective-offline-sdk` 均生成 `.aar/.pom/.module`。
3. 非 debug 版本不生成 `*-sources.jar`。
4. 插件日志可以提示跳过不属于 component 的 sources configuration，但不能中断 publication 创建。

### 7. 假成功防护测试

publication 配置阶段如果发生未知异常，必须让构建失败。不能再出现：

```text
PublishPlugin PluginModule error ...
BUILD SUCCESSFUL
```

这类错误不能被外层 catch 吞掉。已知可忽略错误只能在局部捕获并明确判断，例如 sources configuration 不属于 component。

### 8. POM-only 测试降级为轻量规则测试

`generatePomFileFor...Publication` 可以保留，但只能验证 artifactId 计算规则。不能再作为多 variant 发布可用性的验收标准。

## 实施步骤

1. 在 `PublishPluginFunctionalTest` 中新增真实 `publishToMavenLocal` 多 flavor 测试，先确认失败。
2. 在 `PublishInfo` 中增加 `skipVariantIf` API，并保持默认不过滤。
3. 在插件配置阶段接入 `androidComponents.finalizeDsl`，推导 release variant 名。
4. 在注册 `singleVariant` 前应用 `skipVariantIf`，只注册需要发布的 variants。
5. 保留当前 publication 创建逻辑，但确保 components 已包含 AGP 暴露的可发布 variant。
6. 创建 publication 时再次按同一份过滤结果约束，避免 component 扫描误创建被过滤的 publication。
7. 为不支持 `finalizeDsl` / `singleVariant` 的 AGP 输出明确错误。
8. 修复 `skipSourcesVariants`：只忽略“sources configuration 不属于当前 component”的已知 mismatch，其它异常继续抛出。
9. 外层 publication 配置 catch 只能补充日志，不能吞掉异常。
10. 扩展 `PublishLibraryRemoteTask` 和发布成功日志，确认多 publication 路径可用。
11. 跑完整 `:plugin_base:build`。
12. 先执行 `:plugin_base:publishToMavenLocal` 把修复后的插件发布到本机。
13. 在 `affective_local_sdk` 试点执行 `publishToMavenLocal`，确认本地 Maven 生成期望的 `.aar/.pom/.module`，且被过滤 variants 不生成任何发布产物。

## 临时 Workaround

如果插件修复前必须发布，可以在业务模块手动配置：

```kotlin
android {
    publishing {
        singleVariant("breathAuthRelease")
        singleVariant("breathNoAuthRelease")
        singleVariant("obsessionAuthRelease")
        singleVariant("obsessionNoAuthRelease")
        singleVariant("flowtimeAuthRelease")
        singleVariant("flowtimeNoAuthRelease")
        singleVariant("sdkAuthRelease")
        singleVariant("sdkNoAuthRelease")
    }
}
```

这只是临时方案，不应作为最终接入方式。最终应由 `PublishPlugin` 自动注册，避免每个业务项目重复维护 variant 列表。

## 不推荐方案

1. 不推荐业务项目手写 8 个 `MavenPublication`，这会绕开插件统一的 Central/POM/signing/sources 逻辑。
2. 不推荐禁用 `GenerateModuleMetadata`，这会隐藏 publication 没有 variant 的根因。
3. 不推荐把 `project` / `authentication` 命名规则写死进插件，其他业务项目可能有完全不同的 flavor 维度。
4. 不推荐只用 POM 生成任务做验收，必须跑真实 `publishToMavenLocal`。
5. 不推荐只在 publication 创建阶段过滤，而不在 `singleVariant` 注册前过滤；这样会让 AGP 暴露无用 component 和任务。
