# Changelog

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
