# Changelog

## Unreleased

- Android Library 可以按 variant 名发布指定组合：`publishVariants("sdkAuthRelease")`。
- `publishAllVariants()` 发布全部通过 build type 与 include/exclude 过滤的变种。
- 未配置这两项时，行为与之前一致：默认只考虑 `release`，且没有变种坐标规则时只发布一个变种。
- 指定了不存在的 variant 名时，配置阶段失败并列出候选名称。
- 配置了指定变种或全部变种后，Gradle `customplugin` 组会显示每个变种的 Local / RemoteAll / GitHub Packages / Central 任务。单独运行某个变种任务时只发布该变种，且不能超出已配置范围。一次请求多个变种任务会失败。
- 同时发布多个变种时，Maven 坐标必须互不相同，否则配置阶段失败。
- 从项目构建的变种本地任务同样追加 `-local`，终端提示使用实际发布坐标。

## 1.2.3

### 升级必读

这是行为变更，不是纯修复。从 `1.2.2` 升级后，**Central 默认不再上传真实业务源码**。

以前 Central 发布等价于始终附带真实 sources。现在所有发布目标都上传 `sources.jar`，但默认 `PublishInfo.hasSource = false`，jar 里只有 README 占位文件。

升级时按组件类型处理：

| 组件类型 | 需要做的事 |
| --- | --- |
| 开源 / 需要公开源码 | 在模块 `PublishInfo` 中设置 `hasSource = true`。 |
| 闭源 | 保持默认 `hasSource = false`。可用 `publishToMavenLocal` 先确认占位 sources。 |
| 仍使用旧字段 | `obfuscate = true` 等于 `hasSource = false`；`obfuscate = false` 等于 `hasSource = true`。 |

临时覆盖只用：

- `-PhasSource=true|false`
- `PUBLISH_HAS_SOURCE=true|false`
- 兼容项：`-Pobfuscate` / `PUBLISH_OBFUSCATE`（语义相反）

不要使用无前缀的 `HAS_SOURCE` 或 `OBFUSCATE` 环境变量。

### 变更

- 新增 `PublishInfo.hasSource`，统一本地 Maven、GitHub Packages 和 Central 的 sources 策略。
- `hasSource = false` 时上传 README 占位 `sources.jar`；`hasSource = true` 或 `-debug` 版本上传真实源码。
- Central 仍强制 javadoc 和签名；`hasSource` 只决定 sources 内容。
- 占位 sources 在 task 执行阶段生成，避免 `clean` 后再发布得到空 jar。
