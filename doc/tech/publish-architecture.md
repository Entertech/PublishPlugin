# PublishPlugin 发布架构

## 文档状态

| 项目 | 内容 |
| --- | --- |
| 状态 | 当前实现契约 |
| 更新日期 | 2026-08-27 |
| 适用范围 | `plugin_base`、reusable workflow、离线脚本、发布 Skills |
| 关联 PRD | [发布任务与配置重设计 PRD](../prd/publish-task-config-redesign-prd.md) |

本文描述当前代码实际提供的发布模型。未完成工作统一记录在 [后续规划](../plan.md)，不在本文维护实施 checklist。

## 设计边界

PublishPlugin 将发布拆为四类责任：

1. `PublishInfo`：组件坐标、POM、源码策略和 variant 规则。
2. `PublishRepositories`：可提交的非敏感 provider 配置。
3. runtime config：本机 `.publish/local.properties`、Gradle properties 或环境变量中的凭据。
4. task/provider：选择产物来源、执行校验并发布到明确目标。

```text
PublishInfo + PublishRepositories + runtime credentials
                         |
                         v
                PublishValidation
                         |
             +-----------+-----------+
             |                       |
             v                       v
      project publication      prebuilt bundle
             |                       |
             +-----------+-----------+
                         |
        Maven Local / GitHub Packages / Central
                         |
                         v
                   PublishReport
```

配置 Skill 可以编辑配置并把交接信息传给发布 Skill；发布 Skill 不反向修改配置，也不会因为“验证”请求自动上传。

## 公开任务

每个支持的模块注册 `checkPublish`、`PublishCheckTask` 和四个显式发布任务。

| 组件 | Local | GitHub Packages | Central | All |
| --- | --- | --- | --- | --- |
| Android Library | `PublishLibraryLocalTask` | `PublishLibraryRemoteGithubPackagesTask` | `PublishLibraryRemoteCentralTask` | `PublishLibraryRemoteAllTask` |
| Gradle Plugin | `PublishPluginLocalTask` | `PublishPluginRemoteGithubPackagesTask` | `PublishPluginRemoteCentralTask` | `PublishPluginRemoteAllTask` |

`generatePublishConfig`、`configurePublish`、`rollbackPublishSecrets` 和通用 `PublishLibraryRemoteTask` 不属于公开任务 API。对应旧实现只为源码迁移保留为 `internal`/`@Deprecated`，不得重新注册。

## 执行模型

### Check

`checkPublish` 调用统一的 `PublishValidation`，收集 publication、目标仓库与错误/警告；成功后写入 dry-run manifest，不上传 artifact。

当前远程 check 仍把凭据值存在性作为校验条件，而 reusable workflow 的 `check_only` 分支不注入 secret。这一语义冲突属于 P0 缺口，见后续规划 Task 1。

### Project 产物

显式 task 通过 nested Gradle 调用标准 Maven Publish task。Local 使用 `publishToMavenLocal`；远程目标根据 provider 对应的 repository name 选择单 publication 或 all-publications task。

project 模式尚未统一生成 `PreparedArtifactBundle`。因此 Central `portalApi` 暂不接受 project 产物，仍需要 staging 兼容路径。

### Prebuilt 产物

`artifactSource=prebuilt` 要求 workspace 内的相对目录和 `publish-artifacts.json`。加载过程检查：

- 路径不能逃逸 workspace；
- manifest schema、坐标、packaging 和 file role；
- 文件存在性、尺寸和 SHA-256；
- Central 所需 sources、javadoc 和 signature；
- Central namespace。

通过校验后，同一 `PreparedArtifactBundle` 可交给 Maven Local、GitHub Packages 或 Central provider。

## 校验与报告

`PublishValidationResult` 是显式 task 与 `checkPublish` 的共享校验结果。它包含 mode、repository、publication、error 和 warning，不包含 credential value。

报告输出：

```text
<module>/build/reports/publish/publish-manifest.json
<module>/build/reports/publish/publish-manifest.md
```

manifest 记录模块、mode、仓库、dry-run 状态、生成时间和 publication 坐标。token、password、GPG key 等敏感内容禁止写入日志或报告。

## Provider 行为

| Provider | Project | Prebuilt | 备注 |
| --- | --- | --- | --- |
| Maven Local | 标准 `publishToMavenLocal` | 写入 Maven layout | 普通 project 版本自动追加 `-local` |
| GitHub Packages | Maven Publish repository | Maven HTTP PUT | URL 可从 `owner/repo` 推导 |
| Central staging | Maven Publish repository | Maven HTTP PUT | release 后调用 manual upload；snapshot 不调用 |
| Central portal API | 尚未支持 | bundle upload | 支持 status polling、publish/drop |
| All | 顺序执行启用的远程 provider | 顺序执行 | 当前 fail-fast，不具备恢复状态 |

## Workflow

`.github/workflows/publish.yml` 是业务仓库 reusable workflow，输入经过 allowlist 转换为明确的 Gradle task。主要约束：

- `publish_target` 仅支持 `central`、`github_packages`、`all`；
- `artifact_source` 仅支持 `project`、`prebuilt`；
- `publish_mode=ci` 仅用于 Central snapshot；
- secret 只进入发布 step，不放在 job 级环境；
- README 同步在独立 `contents: write` job 中执行；
- manifest 作为 Actions artifact 上传。

## 配置优先级

解析器按字段类型处理覆盖来源。一般运行时优先级是：

```text
显式命令行 Gradle property
  > 普通 Gradle property
  > 环境变量
  > PublishRepositories / PublishInfo 显式配置
  > .publish/local.properties
  > 插件默认值
```

具体字段及迁移规则见 [发布配置与凭据](publish-configuration.md)。

## 已知限制

- project/prebuilt 尚未在 provider 边界统一为同一 bundle。
- `RemoteAllTask` 部分成功后不能恢复。
- 远程版本存在性与权限没有统一 preflight。
- manifest 尚未包含 SBOM/provenance/API ABI 门禁结果。
- 兼容性矩阵仍以手动 workflow 为主。
