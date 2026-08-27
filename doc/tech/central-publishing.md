# Sonatype Central 发布技术方案

## 文档状态

| 项目 | 内容 |
| --- | --- |
| 状态 | 当前实现契约与已知限制 |
| 更新日期 | 2026-08-27 |
| 主要实现 | `PublishConfigResolver`、`PublishValidation`、`CentralPortalClient`、`CentralPortalBundle` |

未完成事项统一记录在 [后续规划](../plan.md)。

## 发布模式

Central 当前包含三条路径：

| 场景 | mode/release type | 上传协议 | 发布后动作 |
| --- | --- | --- | --- |
| Release 兼容路径 | `central` + `release` | OSSRH Staging API Maven repository | 调用 manual upload 创建 Portal deployment |
| Snapshot | `centralSnapshot` + `snapshot` | Central snapshots Maven repository | 不调用 release deployment API |
| Release 原生路径 | `central` + `portalApi` | Publisher API bundle upload | poll status，按 publishing type publish 或等待人工操作 |

默认 `centralUploadMode=stagingApi`，以保持既有项目行为。`portalApi` 当前只接受 `artifactSource=prebuilt`；project 模式在校验阶段会给出明确错误。

## 配置

```kotlin
PublishRepositories {
    central {
        enabled.set(true)
        namespace.set("cn.entertech")
        publishingType.set("user_managed") // or automatic
        releaseRepositoryName.set("CentralStaging")
        snapshotRepositoryName.set("CentralSnapshots")
    }
}

PublishInfo {
    groupId = "cn.entertech.example"
    artifactId = "example-library"
    version = "1.0.0"
    centralUploadMode = "stagingApi" // or portalApi
}
```

Central User Token 和 signing credentials 必须来自 Gradle property、环境变量或未跟踪的 `.publish/local.properties`，不能写入可提交 DSL。

## Release 兼容路径

```text
Maven publication
  -> CentralStaging repository
  -> namespace/POM/signing validation
  -> upload Maven layout
  -> manual upload endpoint
  -> Central Portal deployment
```

`centralPublishingType=user_managed` 会把 deployment 留在 Portal 等待人工 Publish；`automatic` 由 Central 在校验通过后继续发布。

该路径是当前 project 产物的稳定默认值。它依赖 staging 兼容层的生命周期，不等同于 Publisher API bundle upload。

## Snapshot

以下任一条件可推导 snapshot：

- `remotePublishMode=centralSnapshot`；
- `centralReleaseType=snapshot`；
- 未显式选择其他 mode 且版本以 `-SNAPSHOT` 结尾。

Snapshot 必须使用 `-SNAPSHOT` 版本，并路由到 `CentralSnapshots`。Snapshot 不调用 manual upload、Publisher deployment publish 或 drop。

## Publisher API 原生路径

### Bundle

prebuilt manifest 先经过通用校验和 Central 完整性校验。`CentralPortalBundle` 将文件写成 Maven Central 所需的目录结构：

```text
<group path>/<artifactId>/<version>/<artifact files>
```

bundle 必须至少满足 manifest 中声明的 POM、main artifact、sources、javadoc 和 `.asc` 规则；文件 hash 与 size 必须匹配。

### Lifecycle

```text
uploadBundle
  -> deployment id
  -> waitForDeployment / deploymentStatus
  -> validated
  -> user_managed: leave in Portal
     automatic: publishDeployment

failure or operator decision
  -> dropDeployment
```

Client 使用 JDK `HttpURLConnection`，避免增加插件运行时 HTTP 依赖。Authorization 由 Central User Token 生成，响应进入异常或日志前必须脱敏。

## 发布前校验

Central project publication 检查：

- `groupId` 位于 `centralNamespace` 下；
- version 与 release/snapshot 模式一致，且远程发布不接受 debug 版本；
- publishing type 仅为 `user_managed` 或 `automatic`；
- POM description、URL、developer 和 SCM 字段完整；
- Central token 与 signing key/password 存在；
- sources、javadoc 与签名符合目标要求。

prebuilt 额外检查 manifest、路径、SHA-256、size、file role 和 bundle namespace。

## 凭据与日志安全

- workflow 只在实际发布 step 注入 Central/GPG secrets。
- manifest 不包含 username、token、password、key id 或私钥内容。
- HTTP 错误只允许输出状态码和脱敏后的响应摘要。
- bundle 可作为失败诊断材料保存，但不得包含本机配置文件。

## 未完成边界

- project publication 尚不能生成本地 Maven layout 后直接走 Publisher API。
- fake HTTP server 测试尚未完整覆盖 header、multipart、状态轮询和 timeout。
- 远程 namespace/权限/版本存在性 preflight 尚未实现。
- deployment 的重试、恢复与持久化结果尚未纳入 `RemoteAllTask`。

对应路线见 [后续规划 Task 2～5](../plan.md)。
