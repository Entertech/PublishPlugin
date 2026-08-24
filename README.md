# PublishPlugin

`PublishPlugin` 为 Android Library 与 Gradle Plugin 模块生成 Maven
publication，并提供明确的本地/远程发布任务。任务、配置和制品来源按
[PRD](doc/prd/publish-task-config-redesign-prd.md) 与
[技术方案](doc/tech/publish-task-config-redesign-plan.md) 分层。

## 公开任务

每个应用 `cn.entertech.publish` 的模块只注册以下四个 `customPlugin` 任务：

| 组件 | 本地 | 全部远程 | GitHub Packages | Central |
| --- | --- | --- | --- | --- |
| Library | `PublishLibraryLocalTask` | `PublishLibraryRemoteAllTask` | `PublishLibraryRemoteGithubPackagesTask` | `PublishLibraryRemoteCentralTask` |
| Plugin | `PublishPluginLocalTask` | `PublishPluginRemoteAllTask` | `PublishPluginRemoteGithubPackagesTask` | `PublishPluginRemoteCentralTask` |

旧的 `PublishLibraryRemoteTask`、`generatePublishConfig`、
`configurePublish`、rollback task 及其别名不再注册。Gradle/AGP 自身的标准
任务仍可存在，但不属于 PublishPlugin 的公开任务集合。

## 配置边界

模块坐标、POM 元数据和 variant 规则写在模块 `PublishInfo`：

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "2.0.0"
}
```

非敏感远程仓库选择写在可提交的 DSL：

```kotlin
PublishRepositories {
    githubPackages {
        enabled = true
        repository = "Entertech/demo-lib"
    }
    central {
        enabled = true
        namespace = "cn.entertech"
        publishingType = "user_managed"
    }
}
```

本机凭据只放在 ignored `.publish/local.properties`：

```properties
publish.local.githubPackages.username=
publish.local.githubPackages.token=
publish.local.central.username=
publish.local.central.password=
publish.local.central.signingKeyFile=
publish.local.central.signingKeyId=
publish.local.central.signingPassword=
```

解析优先级为 Gradle property > 环境变量 > `.publish/local.properties`。
根目录 Android `local.properties` 不再参与发布配置；GitHub Actions 只使用
workflow inputs 和 repository Secrets。

## 发布与打包分离

默认 `artifactSource=project` 使用当前工程的标准 publication 任务。需要
直接发布已有 AAR/JAR 时使用 `artifactSource=prebuilt` 和项目内相对目录：

```bash
./gradlew :library:PublishLibraryRemoteCentralTask \
  -PartifactSource=prebuilt \
  -PartifactBundlePath=release-artifacts/library
```

目录必须包含 `publish-artifacts.json`。manifest 明确声明坐标、packaging、
文件 role、相对路径、大小和 SHA-256；不能按文件名猜坐标。Central release
至少需要 main、POM、sources、javadoc 和完整签名文件。预制模式不会执行
compile、assemble、bundle、jar 等当前工程打包任务，也不会上传 manifest 之外
的文件。

## GitHub Actions

可复用 workflow 支持：

```yaml
with:
  module: ":library"
  component_type: "library"
  publish_target: "central"
  artifact_source: "prebuilt"
  artifact_bundle_path: "release-artifacts/library"
  artifact_bundle_artifact: ""
```

workflow 对组件类型和目标做固定 allowlist 映射，只调用对应的四个任务之一。
`artifact_bundle_artifact` 非空时会先下载 Actions artifact；不同 job 不共享
文件系统，因此下载步骤不能省略。GitHub Actions 不支持把 Maven Local 作为
正式交付目标。

## 本地脚本

脚本只负责选择明确任务，不写根目录 `local.properties`，也不接受 secret
参数：

```bash
scripts/configure-publish-offline.sh :library \
  --component-type library --publish-target local --run

scripts/configure-publish-offline.sh :library \
  --component-type library --publish-target central \
  --artifact-source prebuilt \
  --artifact-bundle-path release-artifacts/library --run
```

## 示例模块

- [`demo-lib`](demo-lib/build.gradle.kts)：Android Library、多 flavor release variant。
- [`demo-plugin`](demo-plugin/build.gradle.kts)：Gradle Plugin、plugin marker publication。

提交前运行：

```bash
./gradlew :plugin_base:compileKotlin
./gradlew :plugin_base:test
```
