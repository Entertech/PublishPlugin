# Android Variant 发布技术方案

## 文档状态

| 项目 | 内容 |
| --- | --- |
| 状态 | 当前实现契约 |
| 更新日期 | 2026-08-27 |
| 主要实现 | `PublishInfo`、`PublishVariantInfo`、`PublishPlugin` |

## 目标

插件不写死业务 flavor 维度或 artifact 命名。它负责：

- 根据 build type 与 flavor 组合发现候选 variant；
- 在 AGP `singleVariant` 注册前执行 include/exclude；
- 为最终 `SoftwareComponent` 创建 Maven publication；
- 把 variant 名、build type 和 flavor map 交给业务命名规则。

## DSL

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "example-sdk"
    version = "1.0.0"

    publishBuildTypes("release", "staging")
    publishVariantIf { variant -> variant.flavor("channel") != "internal" }
    skipVariantIf { variant -> variant.flavor("authentication") == "legacy" }
    artifactIdPattern = "{artifactId}-{flavor.channel}-{buildType}"
}
```

未配置 `publishBuildTypes` 时默认只考虑 `release`。

## Variant 模型

```kotlin
class PublishVariantInfo(
    val name: String,
    val buildType: String,
    val flavors: Map<String, String>
) {
    fun flavor(dimension: String): String
}
```

插件从 Android DSL 读取 flavor dimensions 与 product flavors，计算候选组合。最终 component 名必须与候选 variant 名对应，避免只生成 POM 但无法发布真实 AAR/module metadata。

## 选择顺序

```text
publishBuildTypes candidates
  -> publishVariantIf (all predicates must pass)
  -> skipVariantIf (no predicate may match)
  -> register singleVariant
  -> match SoftwareComponent
  -> create MavenPublication
```

如果候选不为空但全部被过滤，配置阶段失败并列出候选名称，避免静默生成空发布。

## Publication 命名

- 默认单 release component 使用 `EnterPublish`，保持旧项目 task 名兼容。
- 多 publication 使用 `<VariantName>EnterPublish`。
- 标准 Maven Publish task 根据 publication 与 repository name 生成。
- 显式 PublishPlugin task 在单 publication 时调用单 publication task，多 publication 时调用 `publishAllPublicationsTo...Repository`。

## 坐标解析优先级

每个坐标 closure 返回空字符串时回退到基础字段。

```text
artifactIdForVariant closure
  > artifactIdPattern
  > artifactId

groupIdForVariant closure
  > groupId

versionForVariant closure
  > version
```

`artifactIdPattern` 支持：

- `{artifactId}`
- `{variant}`
- `{buildType}`
- `{flavor.<dimension>}`

未知或缺失 flavor dimension 当前替换为空字符串。业务规则应避免由此产生尾部连接符或坐标碰撞；后续可增加严格模板校验。

## Sources 与 Central

每个 publication 共享 POM、sources、javadoc 和 signing 策略：

- `hasSource=true` 或 debug 版本附加真实 sources；
- 其他情况附加占位 sources jar；
- Central publication 额外附加 javadoc 并签名；
- `SourcesElements` 不应重复进入 module metadata。

variant 过滤同时作用于 `singleVariant` 注册和 publication 创建，不能只在后一阶段跳过。

## 已知限制与测试缺口

- build type/include/template 的 Groovy 与 Kotlin DSL 组合测试尚不完整。
- pattern 与 closure 优先级缺少端到端 fixture。
- flavor 名解析仍依赖 component 命名约定，新的 AGP 版本需要兼容性矩阵持续验证。
- KMP、Android application 和 test fixtures 不属于当前支持范围。

对应工作见 [后续规划 Task 2 与 Task 7](../plan.md)。
