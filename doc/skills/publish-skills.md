# PublishPlugin Codex Skills 使用说明

PublishPlugin 提供两个职责独立的 Codex Skill：

| Skill | 用途 | 是否会发布 |
| --- | --- | --- |
| `$enter-publish-config` | 配置或校验项目的本机/GitHub Actions 发布能力 | 否 |
| `$enter-publish-run` | 消费已有配置，执行本机发布或触发 GitHub Actions 发布 | 是，必须有明确发布指令 |

推荐始终先完成配置，再单独发起发布请求。配置请求不会被视为发布授权。

## `$enter-publish-config`

在需要新增、修改、迁移或检查发布配置时使用。它可以处理：

- 模块 `PublishInfo` 与 `PublishRepositories`；
- 本机 ignored `.publish/local.properties` 模板；
- GitHub Actions caller workflow 与所需 Secrets 名称；
- `project` 或 `prebuilt` 产物来源；
- `publish-artifacts.json` 的创建或校验；
- Library/Plugin 对应发布任务的选择与交接。

它不会运行发布任务、上传 AAR/JAR，也不会触发 GitHub Actions workflow。

典型用法：

```text
使用 $enter-publish-config，为 :demo-lib 配置本机 Maven Local 发布，产物从当前工程构建。
```

```text
使用 $enter-publish-config，为 :demo-lib 配置本机发布到 Central；创建本机凭据模板，但不要填写或打印凭据，也不要发布。
```

```text
使用 $enter-publish-config，为 :demo-plugin 配置 GitHub Actions 发布到 GitHub Packages 和 Central，产物来自 release-artifacts/plugin，并校验 prebuilt manifest。不要触发 workflow。
```

配置完成后，Skill 应报告修改文件、缺失值，以及可交给
`$enter-publish-run` 的模块、执行环境、目标、产物来源和准确任务/workflow。

## `$enter-publish-run`

仅在项目已配置完成，并且当前请求明确要求“执行发布、上传、发布组件或触发发布 workflow”时使用。它不会在发布过程中修改配置、源码、版本文件、workflow 或 manifest。

典型用法：

```text
使用 $enter-publish-run，把 :demo-lib 发布到本机 Maven Local，产物从当前工程构建。
```

```text
使用 $enter-publish-run，在当前机器将 :demo-lib 的 2.1.0 发布到 Central。
```

```text
使用 $enter-publish-run，触发当前分支已配置的 GitHub Actions caller workflow，把 :demo-plugin 发布到所有已启用远程仓库，并跟踪到结束。
```

```text
使用 $enter-publish-run，在当前机器直接发布 release-artifacts/library 中已有的 AAR/JAR 及伴生文件到 GitHub Packages，不执行工程打包。
```

临时指定版本只通过 `-PpublishVersion` 传递，不修改构建文件。远程发布失败后不会自动重试；`all` 发布会分别报告成功、失败和未开始的 provider。

## 必要输入

调用任一 Skill 时，尽量明确以下信息：

| 输入 | 可选值或示例 |
| --- | --- |
| module | `:demo-lib`、`:demo-plugin` |
| execution | `local`、`github_actions` |
| target | `local`、`github_packages`、`central`、`all` |
| artifact source | `project`、`prebuilt` |
| bundle path | `prebuilt` 时提供项目内相对目录 |
| version | 可选；发布时作为显式覆盖值 |

`github_actions + local` 无效，因为 runner 的 Maven Local 不是正式交付目标。

## 配置与凭据位置

- 组件坐标和 POM：模块 `PublishInfo`。
- 非敏感仓库配置：模块 `PublishRepositories`。
- 本机凭据：ignored `.publish/local.properties` 或环境变量/Gradle property。
- GitHub Actions 编排：tracked caller workflow。
- CI 凭据：GitHub repository/organization Secrets。
- 根目录 Android `local.properties`：不存放 PublishPlugin 配置。

不要在提示词、命令行或日志中粘贴 token、密码、签名私钥等秘密值。

## 任务映射

| 组件 | Local | GitHub Packages | Central | All |
| --- | --- | --- | --- | --- |
| Library | `PublishLibraryLocalTask` | `PublishLibraryRemoteGithubPackagesTask` | `PublishLibraryRemoteCentralTask` | `PublishLibraryRemoteAllTask` |
| Plugin | `PublishPluginLocalTask` | `PublishPluginRemoteGithubPackagesTask` | `PublishPluginRemoteCentralTask` | `PublishPluginRemoteAllTask` |

配置 Skill 只报告这些任务；只有发布 Skill 可以执行它们。
