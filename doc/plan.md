# PublishPlugin 后续规划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `release-engineering` plus `superpowers:subagent-driven-development` or `superpowers:executing-plans` when implementing this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于当前已落地的 GitHub Packages 默认发布、一键发布配置、Central 兼容发布、多 variant publication 和 demo 能力，继续补齐发布前统一校验、发布证据、Snapshot、Central Portal 原生 API、variant DSL、CI 兼容性矩阵和技能生命周期。

**Architecture:** `PublishInfo` 继续作为组件级发布入口，`local.properties` 的 `publish.*` 继续作为仓库级配置入口，`PublishLibraryLocalTask` / `PublishLibraryRemoteTask` 继续保持兼容。新能力优先拆到 resolver、校验、报告、workflow、配置 task 和上传 client 中，不把发布目标、secrets、workflow、variant 规则混进单个大任务。

**Tech Stack:** Gradle Plugin、Kotlin JVM、Android Gradle Plugin、Gradle Maven Publish、Gradle Signing、Gradle TestKit、GitHub Actions、GitHub CLI、GPG、Codex Skill。

---

## 当前功能基线

当前项目已经具备以下能力，后续规划不再把它们当作待实现项：

- `cn.entertech.publish` 插件入口在 `plugin_base/src/main/kotlin/custom/android/plugin/PublishPlugin.kt`。
- Android application 模块会被跳过；Android Library 和 Gradle Plugin 模块会自动应用 `maven-publish`。
- Android Library 支持单 release publication 和多 flavor release publication，publication 名为 `EnterPublish` 或 `<VariantName>EnterPublish`。
- Gradle Plugin 模块支持 `java-gradle-plugin`，使用 `pluginId` 和 `implementationClass` 创建 plugin marker。
- `PublishInfo` 支持 `artifactIdForVariant`、`groupIdForVariant`、`versionForVariant` 和 `skipVariantIf`。
- 本地 Maven 发布会执行 `publishToMavenLocal`，普通版本自动追加 `-local` 后缀，已带 `-local` 的版本不重复追加。
- `PublishConfigResolver.resolveVersion(...)` 已支持 `-PpublishVersion`、`PUBLISH_VERSION` 和显式命令行 `-Pversion` 覆盖。
- 默认远程发布模式是 `githubPackages`；显式 `remotePublishMode=central` 使用 Sonatype Central；`customRepository` 保留旧私服兼容。
- GitHub Packages 支持 `githubPackagesRepository` / `githubPackagesUrl` 推导和 `GITHUB_ACTOR` / `GITHUB_TOKEN` / `gpr.user` / `gpr.key` 凭据来源。
- Central 兼容路径使用 OSSRH Staging API repository 上传，再调用 manual upload endpoint 创建 Portal deployment。
- Central 发布强制校验 namespace、publishing type、POM/SCM 元数据、Central token、GPG signing。
- POM 元数据支持默认 Entertech developer/license、当前年份、SCM URL 推导，以及 `publish.*` 仓库级 fallback。
- `local.properties` 的 `publish.*` 配置由 `PublishConfigLoader` 读取；旧 `centralPublish.*` 仍兼容。
- `generatePublishConfig` / `configurePublish` / `rollbackPublishSecrets` 已落地，并保留 `Central` 旧任务别名。
- `configurePublish` 支持 dry-run、GitHub repository secrets 写入、GPG key 生成、workflow 生成、workflow overwrite 保护。
- `scripts/configure-publish-offline.sh` 已提供不依赖 Codex 的本地入口。
- `.github/workflows/publish.yml` 是新的业务仓库 reusable workflow，支持 `github_packages`、`central`、`all` 三种目标。
- `.github/workflows/publish-plugin-pr-check.yml` 和 `publish-plugin-central.yml` 继续负责本插件自身的版本、Central 发布、tag 和 main 合并流程。
- `demo-lib` 已作为 Android Library 多 flavor 发布样例，`demo-plugin` 已作为 Gradle Plugin 发布样例。
- `skills/publishplugin-one-click-publish/` 是仓库内 Codex skill 源文件，运行时 skill 应通过 `scripts/install-codex-skill.sh` 链接。

相关设计文档：

- `doc/tech/central-portal-publish-plugin-plan.md`
- `doc/tech/multi-variant-publish-plugin-plan.md`
- `doc/tech/publish-one-click-config-plan.md`

## 当前缺口

1. 远程发布校验逻辑仍散落在 `PublishLibraryRemoteTask` 和 `ConfigurePublishTask`，缺少一个可复用的 validation model。
2. 还没有独立的 `PublishLibraryCheckTask` / publish doctor；用户无法在不上传 artifact 的情况下验证当前 publication、仓库模式、凭据来源和 POM 状态。
3. 发布成功只输出人类可读日志，没有机器可读的 manifest，CI 无法保存本次发布证据。
4. `.github/workflows/publish.yml` 已支持多个目标，但没有 check-only 模式，也没有上传 manifest；Central secrets 仍在 job 级环境中暴露给所有步骤。
5. 还不支持 `-SNAPSHOT` 到 Central Snapshot repository 的发布路径。
6. Central Portal 仍走 staging API 兼容层，缺少原生 Publisher API bundle 上传、状态轮询、publish/drop 能力。
7. Android variant 仍固定 release build type；没有 `publishBuildTypes`、显式 include、artifactId 模板。
8. 一键配置已有 macOS/bash 离线入口，但缺少 Linux/Windows 支持策略和更完整的自校验输出。
9. TestKit 主要固定 Gradle 8.7 / AGP 8.1.3，缺少 Gradle / AGP / JDK 兼容性矩阵。
10. Codex skill 已落地，但 README、skill、tech 文档、`doc/plan.md` 之间还没有自动一致性检查。

## 优先级路线

| 优先级 | 扩展项 | 价值 | 风险 |
| --- | --- | --- | --- |
| P0 | 统一发布校验与 check task | 发布前发现配置和 publication 问题 | 中 |
| P0 | 发布 manifest | 为 CI、排障和审计留下结构化发布证据 | 低 |
| P0 | reusable workflow 加固 | 降低 secrets 暴露面，支持 check-only 和 manifest 上传 | 中 |
| P1 | Snapshot 发布 | 支持预发布、内部联调和灰度依赖 | 中 |
| P1 | 一键配置体验增强 | 让脚本、task、skill 输出一致且可验证 | 中 |
| P2 | Central Portal 原生 API | 摆脱 staging 兼容层，形成完整 deployment 生命周期 | 高 |
| P2 | Variant DSL 增强 | 支持更多业务 variant 命名和发布策略 | 中 |
| P2 | 兼容性矩阵 | 降低 Gradle / AGP / JDK 升级回归 | 中 |
| P3 | 发布安全与回退增强 | 增强 secret 泄露防护和发布后修复路径 | 中 |
| P3 | Skill 与文档一致性 | 让一键发布技能跟代码、README 同步演进 | 低 |

## Task 1: 统一发布校验与 Check Task

**Files:**

- Create: `plugin_base/src/main/kotlin/custom/android/plugin/PublishValidation.kt`
- Create: `plugin_base/src/main/kotlin/custom/android/plugin/PublishLibraryCheckTask.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishLibraryRemoteTask.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/ConfigurePublishTask.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishPlugin.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Test: `plugin_base/src/test/java/custom/android/plugin/OneClickPublishTaskFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 定义校验结果模型**

新增 `PublishValidationResult`：

```kotlin
data class PublishValidationResult(
    val valid: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
    val mode: String,
    val repositoryName: String,
    val publications: List<PublishValidationPublication>
)

data class PublishValidationPublication(
    val name: String,
    val groupId: String,
    val artifactId: String,
    val version: String
)
```

- [ ] **Step 2: 抽出远程发布校验**

`PublishValidation.validateRemote(project, publishInfo)` 负责：

- 检查 `groupId`、`artifactId`、`resolveVersion(...)`。
- 检查 `version` 不包含 `debug`。
- `githubPackages` 模式检查 URL 和用户名/token 来源。
- `central` 模式检查 namespace、publishing type、POM/SCM、Central token、GPG signing。
- `customRepository` 模式检查 `publishUrl`。
- 收集当前 `PublishingExtension` 中所有 `*EnterPublish` publication 坐标。

- [ ] **Step 3: 复用校验**

`PublishLibraryRemoteTask.checkPublishInfo(...)` 不再维护一套单独校验逻辑，改为调用 `PublishValidation.validateRemote(...)` 并逐条打印 error/warning。

`ConfigurePublishTask.validatePublishInfo(...)` 保留配置阶段特有逻辑，但坐标、version、Gradle Plugin module 字段校验复用 `PublishValidation` 中的公共函数。

- [ ] **Step 4: 新增 check task**

注册：

```kotlin
project.tasks.register("PublishLibraryCheckTask", PublishLibraryCheckTask::class.java)
project.tasks.register("checkPublish", PublishLibraryCheckTask::class.java)
```

行为：

- 不执行上传。
- 不调用 GitHub secrets。
- 打印 mode、repository、publication 坐标、凭据来源摘要。
- 校验失败时抛出 `GradleException("发布配置校验失败")`。

- [ ] **Step 5: 测试**

Run:

```bash
./gradlew :plugin_base:test --tests custom.android.plugin.PublishPluginFunctionalTest --stacktrace
```

覆盖：

- GitHub Packages 缺少 token 时 check task 失败。
- Central 缺少 signing 时 check task 失败。
- `customRepository` 缺少 `publishUrl` 时 check task 失败。
- 多 variant module check task 输出多个 publication。
- Gradle Plugin module 缺少 `pluginId` 或 `implementationClass` 时失败。

## Task 2: 发布 Manifest

**Files:**

- Create: `plugin_base/src/main/kotlin/custom/android/plugin/PublishReport.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/BasePublishTask.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishLibraryCheckTask.kt`
- Modify: `.github/workflows/publish.yml`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Test: `.github/scripts/reusable_publish_workflow_test.py`
- Docs: `README.md`

- [ ] **Step 1: 定义输出路径**

发布或 check 后写入：

```text
<module>/build/reports/publish/publish-manifest.json
<module>/build/reports/publish/publish-manifest.md
```

- [ ] **Step 2: 定义 JSON contract**

```json
{
  "modulePath": ":demo-lib",
  "mode": "githubPackages",
  "repositoryName": "GitHubPackages",
  "repositoryUrl": "https://maven.pkg.github.com/owner/repo",
  "dryRun": false,
  "publications": [
    {
      "name": "BreathAuthReleaseEnterPublish",
      "groupId": "cn.entertech.android.demo",
      "artifactId": "breath-publish-demo-lib-authentication",
      "version": "1.0.0"
    }
  ]
}
```

敏感字段不得进入 manifest。

- [ ] **Step 3: 写入 report**

`BasePublishTask` 在远程或本地发布成功后调用 `PublishReport.write(...)`。

`PublishLibraryCheckTask` 在校验通过后写入 dry-run manifest。

- [ ] **Step 4: workflow 上传 artifact**

`.github/workflows/publish.yml` 增加：

```yaml
- name: Upload publish manifest
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: publish-manifest
    path: "**/build/reports/publish/publish-manifest.*"
```

- [ ] **Step 5: 测试**

TestKit 执行 `:fixture:checkPublish` 和 `:fixture:PublishLibraryRemoteTask`，断言 manifest 存在、坐标正确、没有 token/password/key 字样。

## Task 3: Reusable Workflow 加固

**Files:**

- Modify: `.github/workflows/publish.yml`
- Modify: `.github/scripts/reusable_publish_workflow_test.py`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/config/GitHubActionsWorkflowWriter.kt`
- Modify: `plugin_base/src/test/java/custom/android/plugin/OneClickPublishTaskFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 增加 check-only 输入**

```yaml
check_only:
  description: "Validate publication configuration without uploading artifacts"
  required: false
  default: false
  type: boolean
```

`check_only=true` 时只执行：

```bash
./gradlew "${MODULE_PATH}:checkPublish" --no-daemon --stacktrace
```

- [ ] **Step 2: 收窄 secrets 暴露**

不要把 Central token、GPG private key、signing password 放在 job 级 `env`。只在 `Publish to Central Portal` 步骤注入：

```yaml
env:
  CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
  CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
  GPG_KEY_CONTENTS: ${{ secrets.GPG_KEY_CONTENTS }}
  SIGNING_PASSWORD: ${{ secrets.SIGNING_PASSWORD }}
```

GitHub Packages 步骤只注入 GitHub Packages 需要的 token。

- [ ] **Step 3: 生成 workflow 支持 check-only**

`GitHubActionsWorkflowWriter.writeWorkflow(...)` 增加可选 input，默认生成发布 workflow；后续可以由 `publish.checkOnly=true` 生成只校验 workflow。

- [ ] **Step 4: workflow 文本测试**

扩展 `.github/scripts/reusable_publish_workflow_test.py`：

- 断言 `check_only` 输入存在。
- 断言 check-only 分支不包含 Central secrets。
- 断言 manifest artifact 上传步骤存在。
- 断言 GitHub Packages 步骤不接收 GPG secrets。

## Task 4: Snapshot 发布模式

**Files:**

- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishInfo.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishConfigResolver.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishPlugin.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishLibraryRemoteTask.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 增加模式常量**

```kotlin
const val MODE_CENTRAL_SNAPSHOT = "centralSnapshot"
const val CENTRAL_SNAPSHOT_URL = "https://central.sonatype.com/repository/maven-snapshots/"
```

- [ ] **Step 2: 模式解析**

规则：

- 显式 `remotePublishMode=customRepository` 或 `githubPackages` 时不自动改写。
- 显式 `remotePublishMode=centralSnapshot` 时必须要求版本以 `-SNAPSHOT` 结尾。
- 未显式设置 mode，且版本以 `-SNAPSHOT` 结尾时，默认使用 `centralSnapshot`。
- release 版本继续默认 `githubPackages`，保持当前兼容行为。

- [ ] **Step 3: repository 配置**

新增 `configureCentralSnapshotRepository(...)`，repository name 使用 `CentralSnapshot`。

Snapshot 模式需要 Central token，但不调用 `CentralPortalClient.manualUpload(...)`。

- [ ] **Step 4: 任务选择**

单 publication：

```text
publishEnterPublishPublicationToCentralSnapshotRepository
```

多 publication：

```text
publishAllPublicationsToCentralSnapshotRepository
```

- [ ] **Step 5: 测试**

覆盖：

- `1.2.3-SNAPSHOT` 生成 CentralSnapshot repository task。
- `remotePublishMode=centralSnapshot` + release version 失败。
- Snapshot 成功路径不调用 manual upload。

## Task 5: 一键配置体验增强

**Files:**

- Modify: `scripts/configure-publish-offline.sh`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/ConfigurePublishTask.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/config/PublishConfig.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/config/PublishConfigTemplateWriter.kt`
- Modify: `skills/publishplugin-one-click-publish/SKILL.md`
- Modify: `skills/publishplugin-one-click-publish/references/one-click-publish-workflow.md`
- Test: `plugin_base/src/test/java/custom/android/plugin/OneClickPublishTaskFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 增加配置摘要输出**

`configurePublish` 成功后输出：

- module path。
- publish target。
- workflow path。
- secret names。
- dry-run 状态。
- 下一条可执行命令。

不输出 secret value。

- [ ] **Step 2: 脚本增加 check-only**

`scripts/configure-publish-offline.sh` 增加：

```bash
--check-only
```

行为：执行 `:module:checkPublish`，不生成配置模板、不写 workflow、不写 secrets。

- [ ] **Step 3: 明确 Linux 支持策略**

当前脚本是 bash 实现，Linux 理论可运行。需要在脚本和 README 中明确：

- macOS: supported。
- Linux: supported when bash, Java, Gradle wrapper, gh, gpg are available。
- Windows: use Git Bash / WSL, or run Gradle tasks manually。

- [ ] **Step 4: 同步 Skill**

如果修改 `skills/publishplugin-one-click-publish/**`，必须运行：

```bash
./scripts/install-codex-skill.sh --check
```

如果 symlink 不存在，运行：

```bash
./scripts/install-codex-skill.sh
```

## Task 6: Central Portal 原生 Publisher API

**Files:**

- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishInfo.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishConfigResolver.kt`
- Create: `plugin_base/src/main/kotlin/custom/android/plugin/CentralPortalBundle.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/CentralPortalClient.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishLibraryRemoteTask.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 增加上传模式字段**

```kotlin
var centralUploadMode: String = "stagingApi" // stagingApi / portalApi
```

默认继续走 `stagingApi`，避免改变现有发布行为。

- [ ] **Step 2: 生成 Portal bundle**

实现 `CentralPortalBundle`：

- 发布到本地 staging 目录。
- 校验 Maven layout。
- 校验 POM、module metadata、sources、javadoc、`.asc`。
- 打 zip bundle。

- [ ] **Step 3: Client 能力**

`CentralPortalClient` 增加：

- `uploadBundle(...)`
- `deploymentStatus(...)`
- `publishDeployment(...)`
- `dropDeployment(...)`

HTTP 继续使用 JDK 标准库，避免给插件增加运行时依赖。

- [ ] **Step 4: 测试边界**

不请求真实 Central。用 fake HTTP server 或 request builder 单测覆盖：

- Authorization header。
- multipart/bundle 路径。
- 失败响应脱敏。
- deployment id 解析。
- poll timeout。

## Task 7: Variant DSL 增强

**Files:**

- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishInfo.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/PublishPlugin.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: 支持 build type 选择**

新增：

```kotlin
fun publishBuildTypes(vararg names: String)
```

默认仍为 `release`。

- [ ] **Step 2: 支持显式 include**

新增：

```kotlin
fun publishVariantIf(action: (PublishVariantInfo) -> Boolean)
fun publishVariantIf(action: Closure<*>)
```

解析顺序：

```text
build type candidates -> publishVariantIf include -> skipVariantIf exclude
```

- [ ] **Step 3: 支持坐标模板**

已有 `groupIdForVariant`、`artifactIdForVariant`、`versionForVariant` 继续优先。新增模板作为简单场景替代闭包：

```kotlin
var artifactIdPattern: String = ""
```

模板变量：

```text
{artifactId}
{variant}
{buildType}
{flavor.<dimension>}
```

- [ ] **Step 4: 测试**

覆盖：

- debug/release build type 选择。
- `publishVariantIf` 和 `skipVariantIf` 组合。
- pattern 与 closure 同时存在时 closure 优先。
- Groovy DSL 和 Kotlin DSL 都可用。

## Task 8: 兼容性矩阵

**Files:**

- Modify: `plugin_base/src/test/java/custom/android/plugin/PublishPluginFunctionalTest.java`
- Modify: `plugin_base/src/test/java/custom/android/plugin/OneClickPublishTaskFunctionalTest.java`
- Create: `.github/workflows/compatibility-matrix.yml`
- Docs: `README.md`

- [ ] **Step 1: 参数化 TestKit 版本**

把测试中的固定值改成：

```java
private static final String TEST_GRADLE_VERSION =
        System.getProperty("testGradleVersion", "8.7");
```

AGP fixture version 改成读取：

```java
System.getProperty("testAgpVersion", "8.1.3")
```

- [ ] **Step 2: 建立手动矩阵 workflow**

```yaml
on:
  workflow_dispatch:

strategy:
  fail-fast: false
  matrix:
    java: ["17", "21"]
    gradle: ["8.7", "8.10"]
    agp: ["8.1.3", "8.5.2"]
```

- [ ] **Step 3: 运行命令**

```bash
./gradlew :plugin_base:test \
  -DtestGradleVersion="${{ matrix.gradle }}" \
  -DtestAgpVersion="${{ matrix.agp }}" \
  --stacktrace
```

- [ ] **Step 4: 接入策略**

先作为手动 workflow。连续稳定后，再把最小矩阵接入 PR 必跑。

## Task 9: 发布安全与回退增强

**Files:**

- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/config/GitSafetyChecker.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/ConfigurePublishTask.kt`
- Modify: `plugin_base/src/main/kotlin/custom/android/plugin/RollbackPublishSecretsTask.kt`
- Test: `plugin_base/src/test/java/custom/android/plugin/OneClickPublishTaskFunctionalTest.java`
- Docs: `README.md`

- [ ] **Step 1: tracked secret 扫描**

`configurePublish` 在写 secrets 前扫描 tracked files 中是否出现明显敏感 key 名：

- `publish.mavenCentralPassword`
- `publish.signingPassword`
- `publish.gpgKeyFile`
- `GPG_KEY_CONTENTS`
- `SIGNING_PASSWORD`

命中时阻断并提示轮换 secret。

- [ ] **Step 2: gh 身份摘要**

写 secret 前输出：

- `gh auth status` 是否通过。
- 目标 repository。
- 将写入或跳过的 secret name。

不输出值。

- [ ] **Step 3: rollback dry-run**

`rollbackPublishSecrets` 支持：

```bash
-PdryRun=true
```

只输出将删除的 secret 和 workflow path，不执行删除。

- [ ] **Step 4: workflow 回退更明确**

删除 workflow 前必须确认包含生成标记：

```text
# Generated by PublishPlugin configurePublish
```

缺少标记时输出 warning，不删除。

## Task 10: Skill 与文档一致性

**Files:**

- Modify: `skills/publishplugin-one-click-publish/SKILL.md`
- Modify: `skills/publishplugin-one-click-publish/references/one-click-publish-workflow.md`
- Modify: `README.md`
- Modify: `doc/tech/publish-one-click-config-plan.md`
- Modify: `doc/plan.md`
- Create: `.github/scripts/verify_publishplugin_docs.py`
- Test: `.github/scripts/verify_publishplugin_docs.py`

- [ ] **Step 1: 增加文档一致性检查脚本**

检查这些固定事实是否一致：

- 默认 remote publish mode 是 `githubPackages`。
- reusable workflow 是 `.github/workflows/publish.yml`。
- 默认 generated workflow marker 是 `# Generated by PublishPlugin configurePublish`。
- 一键配置字段前缀是 `publish.*`。
- skill 目录必须是 `skills/publishplugin-one-click-publish/`。

- [ ] **Step 2: 接入 PR 校验**

在 `.github/workflows/publish-plugin-pr-check.yml` 中执行：

```bash
python3 .github/scripts/verify_publishplugin_docs.py
./scripts/install-codex-skill.sh --check
```

只在仓库环境存在可检查 symlink 时强制 `--check`；CI 没有 Codex home 时，脚本应给出清晰跳过原因。

- [ ] **Step 3: 更新技能规则**

如果发布流程、workflow 输入、secret 名或 config 字段改变，必须同步：

- README 的用户入口。
- skill `SKILL.md` 的工作流。
- skill reference 的行为清单。
- 本计划的当前基线。

## 验收命令

每个功能任务完成后至少运行：

```bash
./gradlew :plugin_base:test --stacktrace
./gradlew :plugin_base:build --stacktrace
./gradlew :plugin_base:publishToMavenLocal --stacktrace
python3 .github/scripts/validate_publish_plugin_publications.py
python3 .github/scripts/reusable_publish_workflow_test.py
```

涉及一键配置时追加：

```bash
./gradlew :plugin_base:test --tests custom.android.plugin.OneClickPublishTaskFunctionalTest --stacktrace
scripts/configure-publish-offline.sh :demo-lib --generate-only -- --stacktrace
```

涉及 workflow 时追加：

```bash
python3 .github/scripts/ensure_publish_version_test.py
python3 .github/scripts/normalize_signing_key_id_test.py
python3 .github/scripts/sync_readme_publish_version_test.py
python3 .github/scripts/reusable_publish_workflow_test.py
```

涉及 skill 时追加：

```bash
./scripts/install-codex-skill.sh --check
```

涉及 demo 时追加：

```bash
./gradlew :demo-lib:publishToMavenLocal --stacktrace
./gradlew :demo-plugin:publishToMavenLocal --stacktrace
```

## 风险控制

- 不删除或重命名现有 `PublishInfo` 字段。
- 不改变 `PublishLibraryLocalTask` / `PublishLibraryRemoteTask` 任务名。
- `githubPackages` 继续作为默认远程发布模式；Central 必须显式选择。
- 新字段必须有默认值；远程必需项只在 remote publish、check task 或 configure task 中校验。
- `local.properties` 只承载仓库级配置和一次性 secret 输入，不承载组件坐标。
- 所有 secret 值只能通过环境变量、Gradle property、ignored config 或 stdin 传递，不打印、不写 manifest。
- Central Portal 原生 API、Snapshot、workflow 多目标发布必须先覆盖失败路径，再进入真实发布流程。
- 多 variant 扩展必须用真实 `publishToMavenLocal` 验收，不能只跑 POM 生成任务。
- 修改 `skills/publishplugin-one-click-publish/**` 时，不直接编辑本地 Codex runtime copy；用 `scripts/install-codex-skill.sh` 安装或校验 symlink。

## 推荐执行顺序

1. Task 1: 统一发布校验与 Check Task。
2. Task 2: 发布 Manifest。
3. Task 3: Reusable Workflow 加固。
4. Task 5: 一键配置体验增强。
5. Task 4: Snapshot 发布模式。
6. Task 8: 兼容性矩阵。
7. Task 7: Variant DSL 增强。
8. Task 9: 发布安全与回退增强。
9. Task 10: Skill 与文档一致性。
10. Task 6: Central Portal 原生 Publisher API。
