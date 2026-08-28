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
      PublishValidation (structure / credentials / remote)
                         |
             +-----------+-----------+
             |                       |
             v                       v
      project publication      prebuilt bundle
             |                       |
             +-----------+-----------+
                         |
       preflight + supply-chain gates
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

`checkPublish` 调用统一的 `PublishValidation`，收集 publication、目标仓库与错误/警告；成功后写入 dry-run manifest，不上传 artifact。校验级别为：

- `structure`：坐标、目标、版本、POM 与 bundle 结构，不要求 secret；
- `credentials`：在 structure 上检查凭据来源是否可用，默认级别；
- `remote`：显式发布使用的完整本地校验级别，远端 I/O 仍由 preflight 执行。

credential source 只记录 `gradle_property`、`environment`、`local_file` 或 `missing`，绝不记录值。reusable workflow 的 `check_only` 固定使用 `structure`，所以无需注入 secret。

### Project 产物

Local 通过 nested Gradle 调用标准 Maven Publish task。所有远程 project 任务先将 publications 发布到隔离的 Maven Local layout，再扫描为与 prebuilt 相同的 `PreparedArtifactBundle`，因此 preflight、供应链门禁、上传与恢复都共享 provider 边界。Snapshot 仍上传 Maven snapshot repository，不进入 deployment API。成功时可用 `-PcleanupPreparedBundle=true` 清理 project staging；失败时始终保留诊断 bundle。

### Prebuilt 产物

`artifactSource=prebuilt` 要求 workspace 内的相对目录和 `publish-artifacts.json`。加载过程检查：

- 路径不能逃逸 workspace；
- manifest schema、坐标、packaging 和 file role；
- 文件存在性、尺寸和 SHA-256；
- Central 所需 sources、javadoc 和 signature；
- Central namespace。

通过校验后，同一 `PreparedArtifactBundle` 可交给 Maven Local、GitHub Packages 或 Central provider。

## 校验与报告

`PublishValidationResult` 是显式 task 与 `checkPublish` 的共享校验结果。除 mode、repository、publication、error/warning 外，还包含 validation level、credential source、preflight、provider、gate 与 provenance，不包含 credential value。

报告输出：

```text
<module>/build/reports/publish/publish-manifest.json
<module>/build/reports/publish/publish-manifest.md
```

manifest 记录模块、mode、仓库、dry-run、publication、校验级别、非敏感凭据来源、preflight、provider、gate 与 provenance。显式发布还生成 `publish-sbom.cdx.json`、`publish-api.txt`（有 prepared bundle 时）和 `provider-state.json`（All）。token、password、GPG key 等敏感内容禁止写入日志或报告。

## Preflight、恢复与门禁

Maven provider 对每个目标 POM 执行认证 HEAD：404 为可发布，2xx 表示版本已存在，401/403 为永久认证失败，5xx 为 retryable。默认阻止已存在版本；只有 `-PallowExistingVersion=true` 才允许继续。Publisher API 没有无副作用的坐标/权限检查接口，因此 release Portal 返回 `unsupported`，而不是假成功。

All 任务按 provider 原子写入 `not_started`、`running`、`succeeded`、`failed` 或 `skipped`。`-PresumePublish=true` 只跳过坐标与全部文件 SHA-256 形成的 fingerprint 相同，且历史状态为 succeeded/skipped 的 provider。

供应链输出包括 CycloneDX 1.5 SBOM、Git commit、GitHub run id、Gradle/JDK 版本与 bundle SHA-256。门禁包括：

- `publishApiBaseline=<file>`：使用 `javap -public` 生成 public API dump 并对比基线；
- `publishDeniedDependencyGroups=a,b`：拒绝 POM 中命中的 dependency group；
- `trustedArtifactRoots=<paths>` 与 `publishTrustedKeyring=<file>`：限制 prebuilt 根目录并用 GPG keyring 验证 detached signatures。

未配置可选基线/信任材料时状态为 `skipped`；远端不提供能力时为 `unsupported`；只有 `failed` 阻断发布。

## Provider 行为

| Provider | Project | Prebuilt | 备注 |
| --- | --- | --- | --- |
| Maven Local | 标准 `publishToMavenLocal` 或显式 `Publish*LocalTask` | 写入 Maven layout，并在成功后输出仓库根地址与 publication 版本目录 | 工程 publication 的本地请求自动追加 `-local`（已带后缀时保持不变） |
| GitHub Packages | project bundle HTTP PUT | Maven HTTP PUT | URL 可从 `owner/repo` 推导 |
| Central staging | project bundle HTTP PUT | Maven HTTP PUT | release 后调用 manual upload；snapshot 不调用 |
| Central portal API | project staging bundle upload | bundle upload | 支持 status polling、publish/drop |
| All | 共享 prepared bundle（Portal 场景） | 共享 bundle | provider 状态持久化，可安全 resume |

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

## 支持边界

- 所有远程 project/prebuilt 在 provider 边界统一为 bundle；Maven Local 继续使用标准 Maven Publish。
- Central Publisher API 无无副作用 token 权限/坐标查询，preflight 明确报告 `unsupported`。
- SLSA statement/签名尚未选定外部实现；当前 provenance 是 manifest 内的构建证据，不宣称达到某个 SLSA level。
