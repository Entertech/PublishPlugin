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
- `centralUploadMode=portalApi` 已覆盖预制 bundle 的 upload、status polling、publish/drop；project 产物仍走 staging 兼容路径。

当前技术契约见：

- [发布架构](tech/publish-architecture.md)
- [Central 发布](tech/central-publishing.md)
- [Android Variant 发布](tech/android-variant-publishing.md)
- [发布配置与凭据](tech/publish-configuration.md)

## 当前缺口

| 优先级 | 缺口 | 当前影响 |
| --- | --- | --- |
| P0 | check-only 的凭据语义不清晰 | workflow 不注入 secret，但远程校验要求凭据值，结构校验与可发布校验没有分层 |
| P0 | Portal 与 variant 边界测试不足 | Portal HTTP 协议和新版 variant DSL 主要依赖实现审查，缺少完整自动回归 |
| P1 | `portalApi` 不支持 project 产物 | 当前工程无法先生成本地 Maven layout/bundle 再直接上传 Publisher API |
| P1 | 缺少远程幂等预检 | 版本已存在、namespace、仓库可写性与权限问题通常要到上传阶段才暴露 |
| P1 | `RemoteAllTask` 不可恢复 | provider 顺序执行，部分成功后没有持久化结果和继续执行语义 |
| P2 | 缺少供应链发布门禁 | API/ABI、依赖兼容性、SBOM、provenance 与 bundle 签名可信度未纳入校验 |
| P2 | 兼容性矩阵仍是手动 workflow | 尚未形成稳定的 PR 必跑最小矩阵和失败分级策略 |

## 实施路线

### Task 1：分层 check-only 校验语义（P0）

- [ ] 为 `PublishValidation` 增加明确的校验级别：`structure`、`credentials`、`remote`。
- [ ] `checkPublish` 默认检查结构和凭据来源是否配置，不读取或打印 secret value。
- [ ] reusable workflow 明确 `check_only` 是否需要 secrets；若不需要，不能把空 secret 当作配置缺失。
- [ ] manifest 记录校验级别和 credential source 类型，只记录 `environment`、`gradle_property`、`local_file`、`missing` 等非敏感摘要。
- [ ] TestKit 与 workflow 文本测试覆盖本机、GitHub Packages、Central、All 和 prebuilt。

完成定义：`check_only=true` 能在不上传 artifact 的前提下稳定验证 CI 配置，结果不会因为 workflow 主动隐藏 secret value 而误报。

### Task 2：补齐 Portal 与 Variant 自动测试（P0）

- [ ] 使用 fake HTTP server 覆盖 Publisher API Authorization、multipart bundle、deployment id、status polling、publish、drop。
- [ ] 覆盖错误响应脱敏、失败状态、未知状态和 poll timeout。
- [ ] TestKit 覆盖 `publishBuildTypes("debug", "staging")`。
- [ ] 覆盖 `publishVariantIf` 与 `skipVariantIf` 组合。
- [ ] 覆盖 `artifactIdPattern` 与 `artifactIdForVariant` 同时存在时 closure 优先。
- [ ] 分别覆盖 Groovy DSL 与 Kotlin DSL fixture。
- [ ] 恢复或重写 secret rollback/dry-run functional test，不重新注册 legacy task。

完成定义：Portal 网络协议和 variant 选择/命名规则均有不访问真实远端的自动回归。

### Task 3：Project 产物接入原生 Portal API（P1）

- [ ] 新增 project staging producer，将 publications 写入隔离的本地 Maven layout。
- [ ] staging scanner 生成与 prebuilt 相同的 `PreparedArtifactBundle`，统一 SHA-256、路径和 Central 完整性校验。
- [ ] `centralUploadMode=portalApi` 对 project/prebuilt 使用同一 bundle uploader。
- [ ] release 支持 upload → poll → user-managed/automatic；snapshot 明确继续走 snapshot repository，不误走 deployment API。
- [ ] 失败时保留可诊断 bundle 和脱敏 manifest；成功后按配置清理临时目录。

完成定义：project 与 prebuilt 在进入 Central provider 后不再有两套上传协议。

### Task 4：远程发布幂等预检（P1）

- [ ] 定义 provider-neutral preflight 结果模型。
- [ ] GitHub Packages/自定义 Maven 仓库检查目标版本 POM 是否已存在。
- [ ] Central 检查 namespace、版本策略和 token 权限；远端不支持的能力必须返回 `unsupported`，不能假装通过。
- [ ] 增加 `-PallowExistingVersion=false|true`，默认阻止不可覆盖仓库的重复版本。
- [ ] 将 preflight 结果写入 manifest，并区分 retryable/permanent failure。

完成定义：常见不可逆失败在上传第一个文件前被阻断。

### Task 5：RemoteAll 可恢复执行（P1）

- [ ] 定义 provider 状态：`not_started`、`running`、`succeeded`、`failed`、`skipped`。
- [ ] 每个 provider 完成后原子写入结果文件，不把 secret 或认证响应写入磁盘。
- [ ] 增加显式 resume 开关，仅跳过坐标、版本、bundle hash 完全一致且已成功的 provider。
- [ ] 输出部分成功摘要和下一条恢复命令。
- [ ] 覆盖 GitHub 成功/Central 失败以及反向顺序的故障测试。

完成定义：部分成功后可以安全继续未完成 provider，不重复上传已确认成功的同一 bundle。

### Task 6：供应链门禁（P2）

- [ ] 为 Android/Java publication 生成 CycloneDX 或 SPDX SBOM。
- [ ] manifest 关联 git commit、workflow run、bundle SHA-256 和构建工具版本。
- [ ] 增加 API/ABI 基线检查和依赖兼容性策略接口。
- [ ] prebuilt bundle 支持签名验证和可信构建来源 allowlist。
- [ ] 评估 SLSA provenance/签名方案，先输出设计决策再选择实现库。

完成定义：发布证据能够回答“由什么源码、在哪个构建环境、生成了什么内容、通过了哪些门禁”。

### Task 7：兼容性矩阵准入（P2）

- [ ] 收集手动矩阵连续运行结果，标记 supported/experimental/unsupported 组合。
- [ ] 选择一组 JDK 17/21、Gradle 8.x、AGP 8.x 的最小 PR 必跑矩阵。
- [ ] 重型完整矩阵保留手动或定时运行，避免所有 PR 成本失控。
- [ ] README 与技术文档公布支持窗口和升级策略。

完成定义：核心兼容组合成为分支保护的一部分，实验组合失败不会阻塞正常维护。

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
./gradlew :plugin_base:test --stacktrace
./gradlew :plugin_base:build --stacktrace
python3 .github/scripts/verify_publishplugin_docs.py
python3 .github/scripts/reusable_publish_workflow_test.py
./scripts/install-codex-skill.sh --check
git diff --check
```

涉及 publication 或 demo 时追加：

```bash
./gradlew :plugin_base:publishToMavenLocal --stacktrace
./gradlew :demo-lib:publishToMavenLocal --stacktrace
./gradlew :demo-plugin:publishToMavenLocal --stacktrace
python3 .github/scripts/validate_publish_plugin_publications.py
```
