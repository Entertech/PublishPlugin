# PublishPlugin 可扩展功能实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Android Library / Gradle Plugin 发布能力基础上，补齐发布前诊断、版本覆盖、产物报告、Snapshot、Portal API、Variant DSL、CI 和兼容性验证等可扩展功能。

**Architecture:** 保持 `PublishInfo` 作为业务项目唯一接入入口，把新增能力拆到配置解析、发布校验、任务注册、上传客户端和报告生成几个边界清晰的模块中。旧任务名 `PublishLibraryLocalTask` / `PublishLibraryRemoteTask` 保持兼容，新能力通过新增 task、可选字段和 CLI property 渐进启用。

**Tech Stack:** Gradle Plugin、Kotlin JVM、Android Gradle Plugin、Gradle Maven Publish、Gradle Signing、Gradle TestKit、GitHub Actions、Python workflow helper scripts。

---

## 现有功能基线

当前项目已经具备这些能力：

- `cn.entertech.publish` Gradle 插件入口在 `plugin_base/src/main/kotlin/custom/android/plugin/PublishPlugin.kt`。
- `PublishInfo` DSL 支持 Maven 坐标、Gradle Plugin 坐标、旧私服字段、Central 字段、POM 元数据、license、developer、SCM、动态 `artifactIdForVariant` 和 `skipVariantIf`。
- Android Library 自动创建 `EnterPublish` 或 `<VariantName>EnterPublish` publication；Gradle Plugin 模块发布 `java` component。
- `PublishLibraryLocalTask` 通过嵌套 Gradle 调用执行 `publishToMavenLocal`。
- `PublishLibraryRemoteTask` 支持 Central Staging API 兼容层和 `customRepository` 两种远程模式，多 publication 时执行 `publishAllPublicationsTo<RepositoryName>Repository`。
- Central 发布时强制 POM 元数据、凭据、签名配置，上传后调用 manual upload endpoint 生成 Central Portal deployment。
- 本地发布对非 debug 版本移除 sources；Central 发布补齐 sources 和 javadoc artifact。
- Gradle TestKit 已覆盖 sources 规则、Central POM 覆盖、多 flavor artifactId、多 variant 本地发布、variant 过滤、sources configuration mismatch。
- `.github/workflows` 已提供业务项目 reusable workflow、插件自身 PR 校验和插件自身 Central 发布流程。
- `.github/scripts` 已覆盖版本规范化、README 版本同步、签名 key id 规范化、PGP 公钥可用性、publication 元数据验证。

已有技术方案文档：

- `doc/tech/central-portal-publish-plugin-plan.md`
- `doc/tech/multi-variant-publish-plugin-plan.md`

## 当前可扩展缺口

1. `publish.yml` 已支持 `version` 输入并传入 `-Pversion=...`，但插件代码当前直接使用 `PublishInfo.version`，没有通过 resolver 读取 CLI 覆盖值。
2. 发布前没有独立的 dry-run / doctor 任务；用户只能通过真实发布或 `generatePomFileFor...` 间接发现配置问题。
3. 发布成功日志只打印坐标和仓库地址，没有机器可读的 publication manifest，CI 难以沉淀发布证据。
4. 远程发布只覆盖 release Central 和自定义 Maven 仓库，尚未提供 Snapshot 场景。
5. Central Portal 当前走 OSSRH Staging API 兼容层 + manual upload，尚未提供原生 Portal Publisher API 路径。
6. Android variant 目前固定发布 release build type，缺少显式 include/exclude variant、build type 选择、artifactId 模板等更易用 DSL。
7. POM 元数据需要每个业务模块重复填写，缺少 root/project 级默认配置。
8. reusable workflow 当前一次发布一个 module，缺少多模块矩阵、发布前 check task、manifest 上传和更细的输入校验。
9. 兼容性测试集中在当前 TestKit fixture，缺少 Gradle / AGP / JDK 组合矩阵。
10. `demo-lib`、`demo-plugin` 目前更像普通 Android Library 模块，未形成可直接验证插件接入方式的样例矩阵。

## 优先级路线

| 优先级 | 扩展项 | 价值 | 风险 |
| --- | --- | --- | --- |
| P0 | CLI version 覆盖闭环 | 修复 workflow 已暴露但插件未真正支持的能力 | 低 |
| P0 | 发布前诊断任务 | 降低 Central 发布失败成本，避免假成功 | 中 |
| P1 | 发布产物 manifest | 让 CI、排障、审计可以读取结构化结果 | 低 |
| P1 | Snapshot 发布模式 | 支持预发布和多项目联调 | 中 |
| P2 | 原生 Portal Publisher API | 摆脱 staging 兼容层，形成完整 Portal 生命周期 | 高 |
| P2 | Variant DSL 增强 | 降低多 flavor 项目接入复杂度 | 中 |
| P2 | POM 默认配置 | 减少业务仓库重复配置 | 中 |
| P2 | reusable workflow v2 | 支持多模块和可审计发布 | 中 |
| P3 | 兼容性矩阵 | 降低 Gradle / AGP 升级回归 | 中 |
| P3 | demo 和文档样例 | 提升接入可复制性 | 低 |

## Task 1: CLI Version 覆盖闭环

**Files:**

- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishConfigResolver.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishPlugin.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishLibraryRemoteTask.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 增加版本解析方法**

在 `PublishConfigResolver` 中增加：

```kotlin
fun resolveVersion(project: Project, publishInfo: PublishInfo): String {
    return firstNotBlank(
        projectProperty(project, "publishVersion"),
        environment("PUBLISH_VERSION"),
        projectProperty(project, "version"),
        publishInfo.version
    )
}
```

说明：`publishVersion` 优先于通用 `version`，避免和 Gradle project version 语义混淆；保留 `-Pversion` 是为了兼容当前 workflow。

- [ ] **Step 2: publication 使用解析后的版本**

在 `PublishPlugin.createPublication(...)` 中把：

```kotlin
publication.version = publishInfo.version
val publishSources = centralPublish || publishInfo.version.endsWith("-debug")
```

替换为：

```kotlin
val resolvedVersion = PublishConfigResolver.resolveVersion(project, publishInfo)
publication.version = resolvedVersion
val publishSources = centralPublish || resolvedVersion.endsWith("-debug")
```

- [ ] **Step 3: 远程校验使用解析后的版本**

在 `PublishLibraryRemoteTask.checkPublishInfo(...)` 中使用 `resolvedVersion` 判断空值和 `debug`：

```kotlin
val resolvedVersion = PublishConfigResolver.resolveVersion(project, publishInfo)
if (resolvedVersion.isBlank()) {
    PluginLogUtil.printlnErrorInScreen("PublishInfo.version is required")
    return false
}
if (resolvedVersion.contains("debug", ignoreCase = true)) {
    PluginLogUtil.printlnErrorInScreen("$resolvedVersion contains debug")
    return false
}
```

- [ ] **Step 4: 嵌套 Gradle 调用转发版本参数**

在 `PublishLibraryRemoteTask.forwardedProjectProperties` 中加入：

```kotlin
"publishVersion",
"version",
```

- [ ] **Step 5: 增加 TestKit 覆盖**

新增测试：传入 `-PpublishVersion=1.2.3` 和 `-Pversion=1.2.4` 时，POM 中优先出现 `1.2.3`；只传 `-Pversion=1.2.4` 时，POM 中出现 `1.2.4`。

Run:

```bash
./gradlew :plugin_base:test --tests custom.android.plugin.PublishPluginFunctionalTest --stacktrace
```

Expected: PASS。

## Task 2: 发布前诊断任务

**Files:**

- Create: `plugin_base/src/main/kotlin/custom/android/plugin/PublishValidation.kt`
- Create: `plugin_base/src/main/kotlin/custom/android/plugin/PublishLibraryCheckTask.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishPlugin.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishLibraryRemoteTask.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 抽出发布校验模型**

新增 `PublishValidation.kt`：

```kotlin
package custom.android.plugin

data class PublishValidationResult(
    val valid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)

object PublishValidation {
    fun validateRemote(project: org.gradle.api.Project, publishInfo: PublishInfo): PublishValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val version = PublishConfigResolver.resolveVersion(project, publishInfo)
        if (publishInfo.groupId.isBlank()) errors += "PublishInfo.groupId is required"
        if (publishInfo.artifactId.isBlank()) errors += "PublishInfo.artifactId is required"
        if (version.isBlank()) errors += "PublishInfo.version is required"
        if (version.contains("debug", ignoreCase = true)) errors += "$version contains debug"

        val mode = PublishConfigResolver.resolveRemotePublishMode(project, publishInfo)
        if (mode == PublishConfigResolver.MODE_CENTRAL) {
            val namespace = PublishConfigResolver.resolveCentralNamespace(project, publishInfo)
            if (namespace.isBlank()) errors += "Central publish requires centralNamespace"
            if (namespace.isNotBlank() && publishInfo.groupId != namespace && !publishInfo.groupId.startsWith("$namespace.")) {
                errors += "PublishInfo.groupId(${publishInfo.groupId}) must be under centralNamespace($namespace)"
            }
            val requiredPomFields = mapOf(
                "pomDescription" to PublishConfigResolver.resolveText(project, "pomDescription", publishInfo.pomDescription),
                "pomUrl" to PublishConfigResolver.resolveText(project, "pomUrl", publishInfo.pomUrl),
                "developerId" to PublishConfigResolver.resolveText(project, "developerId", publishInfo.developerId),
                "developerName" to PublishConfigResolver.resolveText(project, "developerName", publishInfo.developerName),
                "developerEmail" to PublishConfigResolver.resolveText(project, "developerEmail", publishInfo.developerEmail),
                "developerOrganization" to PublishConfigResolver.resolveText(project, "developerOrganization", publishInfo.developerOrganization),
                "developerOrganizationUrl" to PublishConfigResolver.resolveText(project, "developerOrganizationUrl", publishInfo.developerOrganizationUrl),
                "scmUrl" to PublishConfigResolver.resolveText(project, "scmUrl", publishInfo.scmUrl),
                "scmConnection" to PublishConfigResolver.resolveText(project, "scmConnection", publishInfo.scmConnection),
                "scmDeveloperConnection" to PublishConfigResolver.resolveText(project, "scmDeveloperConnection", publishInfo.scmDeveloperConnection)
            )
            val missingPomFields = requiredPomFields.filterValues { it.isBlank() }.keys
            if (missingPomFields.isNotEmpty()) {
                errors += "Central publish missing POM fields: ${missingPomFields.joinToString()}"
            }
            val credentials = PublishConfigResolver.resolveCentralCredentials(project, publishInfo)
            if (credentials.username.isBlank() || credentials.password.isBlank()) {
                errors += "Central publish requires centralUsername/centralPassword"
            }
            val signing = PublishConfigResolver.resolveSigningCredentials(project)
            if (signing.key.isBlank() || signing.password.isBlank()) {
                errors += "Central publish requires signingInMemoryKey and signingInMemoryKeyPassword"
            }
        } else if (mode == PublishConfigResolver.MODE_CUSTOM_REPOSITORY) {
            val url = PublishConfigResolver.resolveCustomRepositoryUrl(project, publishInfo)
            if (url.isBlank()) errors += "customRepository mode requires publishUrl"
        } else {
            errors += "Unsupported remotePublishMode: $mode"
        }
        return PublishValidationResult(errors.isEmpty(), errors, warnings)
    }
}
```

- [ ] **Step 2: 远程任务复用校验模型**

`PublishLibraryRemoteTask.checkPublishInfo(...)` 调用 `PublishValidation.validateRemote(...)`，逐条打印 `errors`，返回 `result.valid`。

- [ ] **Step 3: 新增 check task**

新增 `PublishLibraryCheckTask`，只做配置解析和 publication 列表输出，不执行上传：

```kotlin
open class PublishLibraryCheckTask : org.gradle.api.DefaultTask() {
    companion object {
        const val TAG = "PublishLibraryCheckTask"
    }

    init {
        group = "customPlugin"
        description = "Validate PublishInfo and print publish publications without uploading artifacts."
    }

    @org.gradle.api.tasks.TaskAction
    fun check() {
        val publishInfo = project.extensions.getByType(PublishInfo::class.java)
        val result = PublishValidation.validateRemote(project, publishInfo)
        result.warnings.forEach { PluginLogUtil.printlnInfoInScreen(it) }
        result.errors.forEach { PluginLogUtil.printlnErrorInScreen(it) }
        if (!result.valid) {
            throw org.gradle.api.GradleException("发布配置校验失败")
        }
        PluginLogUtil.printlnInfoInScreen("发布配置校验通过")
    }
}
```

- [ ] **Step 4: 注册任务**

在 `PublishPlugin.apply(...)` 已注册本地和远程任务的位置追加：

```kotlin
project.tasks.register(PublishLibraryCheckTask.TAG, PublishLibraryCheckTask::class.java)
```

- [ ] **Step 5: 覆盖成功和失败测试**

TestKit 增加两个用例：

```bash
./gradlew :fixture:PublishLibraryCheckTask -PcentralNamespace=com.example --stacktrace
```

Expected: 缺少 signing 时失败并输出 `signingInMemoryKey`。

```bash
./gradlew :fixture:PublishLibraryCheckTask \
  -PcentralNamespace=com.example \
  -PcentralUsername=user \
  -PcentralPassword=password \
  -PsigningInMemoryKey=dummy \
  -PsigningInMemoryKeyPassword=password \
  --stacktrace
```

Expected: 校验通过。

## Task 3: 发布产物 Manifest

**Files:**

- Create: `plugin_base/src/main/kotlin/custom/android/plugin/PublishReport.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/BasePublishTask.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishLibraryCheckTask.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 定义 report 输出格式**

输出到：

```text
<module>/build/reports/publish/publish-manifest.json
<module>/build/reports/publish/publish-manifest.md
```

JSON 字段：

```json
{
  "modulePath": ":library",
  "mode": "central",
  "repositoryName": "CentralStaging",
  "publications": [
    {
      "name": "EnterPublish",
      "groupId": "cn.entertech.android",
      "artifactId": "publish",
      "version": "1.2.1"
    }
  ]
}
```

- [ ] **Step 2: 实现 `PublishReport.write(...)`**

从 `PublishingExtension.publications.withType(MavenPublication::class.java)` 收集 publication 坐标，避免解析 Gradle 输出字符串。

- [ ] **Step 3: 发布成功后写 report**

在 `BasePublishTask.afterPublishSuccess(...)` 调用之后写 manifest；`PublishLibraryCheckTask` 校验通过时也写一份 dry-run manifest。

- [ ] **Step 4: CI 上传 manifest**

在 `.github/workflows/publish.yml` 的发布步骤后增加 artifact 上传：

```yaml
- name: Upload publish manifest
  uses: actions/upload-artifact@v4
  with:
    name: publish-manifest
    path: "**/build/reports/publish/publish-manifest.*"
```

- [ ] **Step 5: 测试 manifest**

TestKit 执行 `PublishLibraryCheckTask` 后断言 JSON 存在，并包含 `modulePath`、`mode`、`publications`。

## Task 4: Snapshot 发布模式

**Files:**

- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishInfo.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishConfigResolver.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishPlugin.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishLibraryRemoteTask.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 增加模式常量**

在 `PublishConfigResolver` 增加：

```kotlin
const val MODE_CENTRAL_SNAPSHOT = "centralSnapshot"
```

- [ ] **Step 2: 自动识别 Snapshot**

`resolveRemotePublishMode(...)` 在解析后的 mode 为空或为 `central`，且版本以 `-SNAPSHOT` 结尾时，返回 `centralSnapshot`；如果用户配置了 `customRepository`，不改写发布模式。

- [ ] **Step 3: Snapshot 仓库配置**

新增 `configureCentralSnapshotRepository(...)`，repository name 使用 `CentralSnapshot`。Snapshot 模式仍要求 Central token，但不调用 manual upload。

- [ ] **Step 4: 调整远程任务选择**

`PublishLibraryRemoteTask.initPublishCommandLine()` 在 Snapshot 模式下返回：

```text
:publishEnterPublishPublicationToCentralSnapshotRepository
```

多 publication 时返回：

```text
:publishAllPublicationsToCentralSnapshotRepository
```

- [ ] **Step 5: 增加测试**

用 `version = "1.2.3-SNAPSHOT"` 的 fixture 执行 `:fixture:tasks --all`，断言输出包含 `publishEnterPublishPublicationToCentralSnapshotRepository`，并断言 `afterPublishSuccess` 不调用 manual upload。

## Task 5: 原生 Central Portal Publisher API

**Files:**

- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishInfo.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishConfigResolver.kt`
- Create: `plugin_base/src/main/kotlin/custom/android/plugin/CentralPortalBundle.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/CentralPortalClient.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishLibraryRemoteTask.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 新增上传模式字段**

在 `PublishInfo` 增加：

```kotlin
var centralUploadMode: String = "stagingApi" // stagingApi / portalApi
```

- [ ] **Step 2: 生成本地 Central bundle**

新增 `CentralPortalBundle`：执行 `publishAllPublicationsToCentralBundleRepository` 后，校验 Maven layout 中每个 publication 都包含 main artifact、POM、module metadata、sources、javadoc 和 `.asc`。

- [ ] **Step 3: Portal API 上传**

`CentralPortalClient` 增加 `uploadBundle(...)`、`pollDeployment(...)`、`publishDeployment(...)`、`dropDeployment(...)` 方法。HTTP 客户端继续使用 JDK 标准库，避免给插件引入额外运行时依赖。

- [ ] **Step 4: 远程任务分流**

`centralUploadMode = "stagingApi"` 保持现有行为；`centralUploadMode = "portalApi"` 走 bundle 上传和 deployment 轮询。

- [ ] **Step 5: 测试边界**

不在单元测试里请求真实 Central。测试 `CentralPortalBundle` 的文件校验、HTTP request 构造、失败响应脱敏、deployment id 解析。

## Task 6: Variant DSL 增强

**Files:**

- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishInfo.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishPlugin.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 支持 build type 选择**

新增 DSL：

```kotlin
fun publishBuildTypes(vararg names: String)
```

默认仍为 `release`，保持兼容。

- [ ] **Step 2: 支持显式 variant include**

新增 DSL：

```kotlin
fun publishVariantIf(action: (PublishVariantInfo) -> Boolean)
fun publishVariantIf(action: groovy.lang.Closure<*>)
```

解析规则：先按 `publishBuildTypes` 生成候选 variants，再应用 `publishVariantIf`，最后应用已有 `skipVariantIf`。

- [ ] **Step 3: 支持 artifactId 模板**

新增字段：

```kotlin
var artifactIdPattern: String = ""
```

支持占位符：

```text
{artifactId}
{variant}
{buildType}
{flavor.project}
{flavor.authentication}
```

如果同时配置 `artifactIdForVariant` 和 `artifactIdPattern`，闭包优先。

- [ ] **Step 4: 覆盖 Groovy / Kotlin DSL**

TestKit fixture 分别用 Groovy DSL 和 Kotlin DSL 验证 include、skip、pattern 的优先级。

## Task 7: POM 默认配置

**Files:**

- Create: `plugin_base/src/main/kotlin/custom/android/plugin/PublishDefaults.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishPlugin.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishConfigResolver.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 新增 root 级 extension**

插件在 root project 注册：

```kotlin
project.rootProject.extensions.create("PublishDefaults", PublishDefaults::class.java)
```

字段与 `PublishInfo` 的 POM、license、developer、SCM 字段保持同名。

- [ ] **Step 2: 调整解析优先级**

POM 相关字段解析顺序：

```text
Gradle property > 环境变量 > PublishInfo 字段 > PublishDefaults 字段 > 默认值
```

- [ ] **Step 3: 支持多模块复用**

业务项目可以在根工程配置一次：

```kotlin
PublishDefaults {
    developerId = "Entertech"
    developerName = "Entertech"
    developerEmail = "developer@entertech.cn"
    licenseName = "The Apache License, Version 2.0"
}
```

子模块只保留 `groupId`、`artifactId`、`version`、`pomDescription`。

- [ ] **Step 4: TestKit 验证覆盖顺序**

根配置 developer，子模块覆盖 `pomDescription`，CLI 覆盖 `pomUrl`；生成 POM 后逐项断言。

## Task 8: Reusable Workflow v2

**Files:**

- Modify: `.github/workflows/publish.yml`
- Modify: `.github/workflows/publish-plugin-pr-check.yml`
- Modify: `.github/scripts/publish_plugin_central_workflow_test.py`
- Docs: `README.md`

- [ ] **Step 1: 增加 check-only 模式**

新增 workflow input：

```yaml
check_only:
  required: false
  default: false
  type: boolean
```

`check_only = true` 时执行 `${module}:PublishLibraryCheckTask`，不执行远程发布。

- [ ] **Step 2: 支持多 module**

新增 `modules` 输入，格式为逗号分隔 Gradle path。workflow 用 shell 拆分后逐个执行 check 或 publish。

- [ ] **Step 3: 统一版本覆盖参数**

workflow 优先传：

```bash
-PpublishVersion="${PUBLISH_VERSION}"
```

为兼容旧输入，可在一个版本内同时保留 `-Pversion`。

- [ ] **Step 4: 上传 manifest**

发布或 check 完成后上传 `build/reports/publish/publish-manifest.*`。

- [ ] **Step 5: Workflow 文本测试**

扩展 `.github/scripts/publish_plugin_central_workflow_test.py`，断言发布 secrets 只注入发布步骤，check-only 不接收 Central 密码和 GPG 私钥。

## Task 9: 兼容性矩阵

**Files:**

- Modify: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Create: `.github/workflows/compatibility-matrix.yml`
- Docs: `README.md`

- [ ] **Step 1: 参数化 TestKit Gradle 版本**

把 `TEST_GRADLE_VERSION` 改为读取系统属性：

```java
private static final String TEST_GRADLE_VERSION =
        System.getProperty("testGradleVersion", "8.7");
```

- [ ] **Step 2: 参数化 Android Gradle Plugin 版本**

fixture build script 中的 `com.android.library` version 改为读取系统属性 `testAgpVersion`，默认 `8.1.3`。

- [ ] **Step 3: CI matrix**

新增 matrix：

```yaml
strategy:
  fail-fast: false
  matrix:
    gradle: ["8.7", "8.10"]
    agp: ["8.1.3", "8.5.2"]
    java: ["17", "21"]
```

运行：

```bash
./gradlew :plugin_base:test \
  -DtestGradleVersion="${{ matrix.gradle }}" \
  -DtestAgpVersion="${{ matrix.agp }}" \
  --stacktrace
```

- [ ] **Step 4: 失败策略**

matrix workflow 先作为手动触发 `workflow_dispatch`；连续稳定后再接入 PR 必跑。

## Task 10: Demo 和文档样例

**Files:**

- Modify: `demo-lib/build.gradle.kts`
- Modify: `demo-plugin/build.gradle.kts`
- Create: `demo-gradle-plugin/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Docs: `README.md`

- [ ] **Step 1: `demo-lib` 接入 Android Library 发布样例**

`demo-lib` 应用 `cn.entertech.publish`，配置本地发布可用的最小 `PublishInfo`。

- [ ] **Step 2: 新增 Gradle Plugin 样例模块**

新增 `demo-gradle-plugin`，应用 `java-gradle-plugin` 和 `cn.entertech.publish`，配置 `pluginId` 与 `implementationClass`。

- [ ] **Step 3: 增加多 variant 样例**

在 `demo-lib` 增加两个 flavor 维度，并配置 `artifactIdForVariant` 与 `skipVariantIf`。

- [ ] **Step 4: 文档补充可复制命令**

README 增加：

```bash
./gradlew :demo-lib:PublishLibraryCheckTask
./gradlew :demo-lib:PublishLibraryLocalTask
./gradlew :demo-gradle-plugin:PublishLibraryLocalTask
```

## 验收命令

每个功能任务完成后至少运行：

```bash
./gradlew :plugin_base:test --stacktrace
./gradlew :plugin_base:build --stacktrace
./gradlew :plugin_base:publishToMavenLocal --stacktrace
python3 .github/scripts/validate_publish_plugin_publications.py
python3 .github/scripts/publish_plugin_central_workflow_test.py
```

涉及 demo 修改时追加：

```bash
./gradlew :demo-lib:PublishLibraryCheckTask --stacktrace
./gradlew :demo-lib:PublishLibraryLocalTask --stacktrace
```

涉及 workflow 修改时追加：

```bash
python3 .github/scripts/ensure_publish_version_test.py
python3 .github/scripts/normalize_signing_key_id_test.py
python3 .github/scripts/sync_readme_publish_version_test.py
```

## 风险控制

- 不删除或重命名现有 `PublishInfo` 字段，避免旧业务项目配置期失败。
- 不改变 `PublishLibraryLocalTask` / `PublishLibraryRemoteTask` 任务名。
- 新字段必须提供默认值；远程发布必需项只在远程发布或 check task 中校验。
- 所有凭据只做存在性校验，不写入 report，不打印原始值。
- 原生 Portal API、Snapshot、workflow 多模块发布必须先在 TestKit 或 workflow 文本测试中覆盖失败路径，再进入真实发布流程。
- 多 variant 相关扩展必须继续用真实 `publishToMavenLocal` 验收，不能只依赖 POM 生成任务。

## 推荐执行顺序

1. Task 1: CLI Version 覆盖闭环。
2. Task 2: 发布前诊断任务。
3. Task 3: 发布产物 Manifest。
4. Task 8: Reusable Workflow v2 中的 check-only、version 参数、manifest 上传。
5. Task 4: Snapshot 发布模式。
6. Task 6: Variant DSL 增强。
7. Task 7: POM 默认配置。
8. Task 9: 兼容性矩阵。
9. Task 10: Demo 和文档样例。
10. Task 5: 原生 Central Portal Publisher API。
