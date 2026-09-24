# PublishPlugin 后续规划

> 本文件只跟踪尚未完成的工作。已经落地的能力记录在 README 和主题技术文档中，不在这里保留已完成 checklist。

## 当前基线

PublishPlugin 当前公开能力包括：

- Library 与 Gradle Plugin 各自拥有 Local、GitHub Packages、Central、All 四类显式发布任务。
- `checkPublish` 复用统一校验模型，并生成 dry-run manifest。
- 显式发布成功后生成 JSON/Markdown manifest。
- `artifactSource=project|prebuilt`、Central release/snapshot、GitHub Packages 和 Maven Local 已可用。
- Android variant 支持 build type 选择、include/exclude predicate 和 artifactId 模板。
- Android Library 可通过 `publishVariants(...)` 发布指定变种，或通过 `publishAllVariants()` 发布全部通过过滤的变种。未配置时仍默认只发布一个 `release`。配置后 `customplugin` 组为每个变种显示 Local、RemoteAll、GitHub Packages、Central 任务；单独执行某个变种任务只发布该变种。
- reusable workflow 支持 `check_only`、预制 bundle 和 manifest artifact。
- 配置入口已经迁移到 `skills/enter-publish-config/`、`skills/enter-publish-run/` 与离线脚本；旧配置 task 不再注册。
- `centralUploadMode=portalApi` 已覆盖 project/prebuilt bundle 的 upload、status polling、publish/drop；snapshot 继续使用 Maven snapshot repository。
- 分层 check-only、远端 preflight、All provider 恢复状态、CycloneDX/provenance/门禁和 PR 最小兼容矩阵已落地。

当前技术契约见：

- [发布架构](tech/publish-architecture.md)
- [发布前测试与验收清单](tech/pre-release-testing.md)
- [Central 发布](tech/central-publishing.md)
- [Android Variant 发布](tech/android-variant-publishing.md)
- [发布配置与凭据](tech/publish-configuration.md)

## 当前缺口

对照 [vanniktech/gradle-maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin) 与 Android 官方 `multipleVariants()` 后，当前发布编排不缺目标仓库和校验，缺的是多变种坐标安全和更广的组件类型。按收益排序：

1. **多变种坐标会碰撞。** `publishAllVariants()` 默认复用同一个 `artifactId`。`demo-lib` 的四个 release 变种都会发布为 `publish-demo-lib`。多变种发布必须由 `artifactIdPattern` 或 `artifactIdForVariant` 区分坐标；`{flavor.<dimension>}` 缺失时当前替换成空字符串，可能生成 `lib--release`。配置阶段应在坐标重复或模板替换为空时失败。
2. **只有 `singleVariant()`，没有 `multipleVariants()`。** 现在每个变种是独立 Maven 坐标。官方还支持把多个变种放进同一份 module metadata，由消费方用 `missingDimensionStrategy` 选择。内部 SDK 若需要「一个依赖、按 flavor 解析」，当前做不到。
3. **不支持 KMP 与 `com.android.kotlin.multiplatform.library`。** 同类插件已覆盖，本文档当前明确排除。KMP 侧对应能力是 `androidVariantsToPublish`，默认只发布 `release`。

可后做：

- 开源库的 Javadoc 只有空 jar。闭源可保持空 jar；开源应能接入标准 Javadoc 或 Dokka。
- 根工程没有统一的 `VERSION` / `POM_*`。多模块各自写 `PublishInfo`，同时发布时版本容易不一致。
- 发布路径依赖 `afterEvaluate` 和 AGP 反射，尚未把 `--configuration-cache` 作为验收条件。
- `publishApiBaseline` 已能对比 public API，但没有默认生成并提交 `.api` 基线。

不跟进：不再包一层面向旧 Nexus staging API 的 `gradle-nexus-publish-plugin`；也不新增根工程 `publishAllPublicationsToMavenCentralRepository` 这类聚合任务。每个模块、每个目标保持一个明确任务。

## 候选能力

以下能力尚未排期，进入实施路线前需要先补技术决策：

- release channel：release、snapshot、rc、nightly 的版本与仓库路由。
- 多模块发布拓扑、并发上限与一致版本策略。
- changelog、Git tag、GitHub Release 与发布 manifest 自动关联。
- 发布耗时、失败率和仓库响应指标，以及 Slack/飞书通知。
- Central deployment 的人工审批 handoff 与超时后的安全 drop 策略。

## 验收命令

基础验证：

```bash
./scripts/pre-release-check.sh
```

涉及 demo 模块配置或 publication 行为时追加对应 demo 验证：

```bash
./gradlew :demo-lib:publishToMavenLocal --stacktrace
./gradlew :demo-plugin:publishToMavenLocal --stacktrace
```

涉及 Android 变种任务列表或单变种发布时：

```bash
./gradlew :demo-lib:tasks --group=customPlugin
./gradlew :demo-lib:PublishLibrarySdkAuthReleaseLocalTask
```
