# PublishPlugin 后续规划

> 本文件只跟踪尚未完成的工作。已经落地的能力记录在 README 和主题技术文档中，不在这里保留已完成 checklist。

## 当前基线

PublishPlugin 当前公开能力包括：

- Library 与 Gradle Plugin 各自拥有 Local、GitHub Packages、Central、All 四类显式发布任务。
- `checkPublish` 复用统一校验模型，并生成 dry-run manifest。
- 显式发布成功后生成 JSON/Markdown manifest。
- `artifactSource=project|prebuilt`、Central release/snapshot、GitHub Packages 和 Maven Local 已可用。
- Android variant 支持 build type 选择、include/exclude predicate 和 artifactId 模板。
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

上述实施路线已完成，当前没有待修复的已知发布缺口。实现契约和支持边界已迁移到对应主题技术文档；本文件不保留已完成 checklist。

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
