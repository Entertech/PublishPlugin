# PublishPlugin 发布前测试与验收清单

## 目的与范围

本文是 PublishPlugin 发布前的版本化验收契约，适用于 `plugin_base` 的正式版本、
Central snapshot 以及影响 reusable workflow 的变更。测试目标是证明：待发布源码和
版本明确，Gradle Plugin 实现包与 marker 可被消费，支持矩阵没有回归，发布流程不会
泄露凭据，并且真正执行远程上传前仍保留人工确认点。

普通 PR 不应上传远程制品、创建 tag 或合并发布分支。本地自动化只发布到隔离的临时
Maven Local 仓库；远程用例必须由对应 GitHub Actions workflow 在明确选择版本和目标
后执行。

## 一键自动化入口

完整执行本机非破坏性门禁：

```bash
./scripts/pre-release-check.sh
```

只运行静态、版本、文档和 Python workflow 回归测试：

```bash
./scripts/pre-release-check.sh --static-only
```

准备从 `pre_publish` 发布到 `main` 时，额外校验版本高于 `main` 且工作区干净：

```bash
git fetch enter main
./scripts/pre-release-check.sh --require-clean --base-ref enter/main
```

PR workflow 使用同一个脚本，并通过单独的 matrix job 覆盖：

- JDK 17 / Gradle 8.7 / AGP 8.1.3；
- JDK 21 / Gradle 8.10 / AGP 8.5.2。

脚本失败即返回非零退出码。测试产生的 Maven 仓库和消费工程位于临时目录，结束后自动
删除；不会读取发布 secret，也不会访问 Central/GitHub Packages 写接口。

## 自动化测试用例

| ID | 用例 | 预期结果 | 自动化位置 |
| --- | --- | --- | --- |
| PRE-001 | 检查未暂存 diff 的空白与冲突标记 | `git diff --check` 成功 | `pre-release-check.sh` |
| PRE-002 | 检查已暂存 diff 的空白与冲突标记 | `git diff --cached --check` 成功 | `pre-release-check.sh` |
| PRE-003 | 检查已跟踪文件中没有本机配置、key store、私钥或 crash dump | 未发现已知敏感/生成文件类型 | `pre-release-check.sh` |
| PRE-004 | 检查 `baseVersion` 是规范 SemVer；指定 base 时必须更大 | 版本格式为 `x.y.z` 且未复用旧版本 | `ensure_publish_version.py` |
| PRE-005 | 检查 README 中的插件依赖版本 | README 坐标与 `baseVersion` 一致 | `sync_readme_publish_version.py --check` |
| PRE-006 | 执行版本、签名 key id、PGP、公用/release workflow 脚本回归 | 所有 `*_test.py` 成功 | `.github/scripts/*_test.py` |
| PRE-007 | 检查公开任务、配置前缀、workflow 和文档事实 | 文档契约与实现入口一致 | `verify_publishplugin_docs.py` |
| PRE-008 | 检查本机 config/run Skills 安装状态 | runtime symlink 指向本仓库；CI 无目录时跳过 | `install-codex-skill.sh --check` |
| PRE-009 | 执行单元测试、TestKit 功能测试、`validatePlugins` 和构建 | 所有测试及 Gradle Plugin 校验成功 | `:plugin_base:test/validatePlugins/build` |
| PRE-010 | 发布实现包和 plugin marker 到隔离 Maven Local | 两个 publication 都使用 `<version>-local` | `:plugin_base:publishToMavenLocal` |
| PRE-011 | 校验实现 POM、Central 元数据、marker POM 与依赖关系 | marker 精确依赖同版本 `cn.entertech.android:publish` | `validate_publish_plugin_publications.py` |
| PRE-011A | 校验实现 jar、sources、javadoc、module metadata 和 marker POM 已落盘 | 隔离仓库中所有必需文件存在且非空 | `validate_publish_plugin_publications.py --repository` |
| PRE-012 | 从干净消费工程通过 plugin DSL 解析本地 marker | `cn.entertech.publish` 可应用并注册 `PublishInfo` | `pre-release-check.sh` |
| PRE-013 | 最低支持组合回归 | JDK 17 / Gradle 8.7 / AGP 8.1.3 测试成功 | PR compatibility job |
| PRE-014 | 当前目标组合回归 | JDK 21 / Gradle 8.10 / AGP 8.5.2 测试成功 | PR compatibility job |

`plugin_base` 测试还应覆盖以下业务场景；这些场景已由单元测试或 TestKit 自动化，不需要
在发布前手工重复操作：

- Library 与 Gradle Plugin 只暴露 Local、GitHub Packages、Central、All 四类任务；
- PublishPlugin 与 `java-gradle-plugin` 的两种应用顺序都能生成 marker；
- marker 指向 `EnterPublish` 的最终 group/artifact/version，而不是默认 publication；
- Android build type、flavor predicate 和 `artifactIdPattern` 生成正确坐标；
- structure check 不要求 secret，manifest 只记录凭据来源类型；
- Central namespace、POM、签名要求和 prebuilt bundle 完整性失败时会阻断；
- Central snapshot 不进入 Portal release deployment API；
- Portal upload/status/publish/drop 使用正确方法、参数和终态；
- RemoteAll resume 按 bundle fingerprint 跳过已成功 provider；
- 已存在版本、认证失败、服务端临时失败被分类为阻断或 retryable；
- manifest、SBOM、provenance、API baseline、依赖策略和可信 artifact root 不泄露 secret。

## 发布 workflow 自动门禁

以下用例需要 GitHub 上的分支、tag 或受保护 secret，但仍由 workflow 自动判定：

| ID | 用例 | 预期结果 | 自动化位置 |
| --- | --- | --- | --- |
| REL-001 | `pre_publish` 对 `main` 做无提交预合并 | 无冲突；冲突时在上传前终止 | `publish-plugin-central.yml` |
| REL-002 | 比较 `pre_publish` 与 `main` 的插件版本 | 仅更高版本触发 Central 发布；更低版本阻断 | `publish-plugin-central.yml` |
| REL-003 | 检查 `v<version>` tag 是否已存在 | 同 commit 可幂等跳过；不同 commit 复用 tag 时阻断 | `publish-plugin-central.yml` |
| REL-004 | 检查 PGP 公钥可从 keyserver 稳定查询 | 连续查询成功后才进入发布 | `ensure_pgp_public_key_available.py` |
| REL-005 | 检查发布 secret 的作用域 | secret 只注入实际发布 step，不进入本地校验/job 全局环境 | workflow 回归测试 |
| REL-006 | Central snapshot 发布 | 使用 `-SNAPSHOT` 和 snapshot repository，不创建 tag/merge/deployment | `publish_mode=ci` job |
| REL-007 | Central release 发布与 Portal deployment | staging artifact 完成后才创建 deployment | `publish_mode=release` job |
| REL-008 | 发布成功后的 tag、README 和 `main` 同步 | 只在发布成功后执行，保留发布 commit 可追溯性 | `publish-plugin-central.yml` |

## 必须人工确认的用例

这些项目涉及权限、外部平台状态或不可逆写入，不能仅凭本地测试替代。执行人应把结论和
对应 Actions run/deployment 链接记录在 PR 或发布记录中。

| ID | 人工检查 | 通过标准 |
| --- | --- | --- |
| MAN-001 | PR 审查与必需检查 | PR 目标为 `pre_publish`，审批满足分支规则，所有 required checks 成功 |
| MAN-002 | 版本与发布说明 | major/minor/patch 符合兼容性影响；破坏性变更和迁移方式已写入 README/changelog |
| MAN-003 | Central namespace 与凭据权限 | token 对目标 namespace 有发布权限，账号未过期或被撤销 |
| MAN-004 | 签名身份 | PGP fingerprint、公开 key、私钥来源和 passphrase 对应同一发布身份 |
| MAN-005 | Central user-managed 模式 | deployment 通过 Central 校验后，由授权人员确认 publish；失败项已处理或安全 drop |
| MAN-006 | Central automatic 模式 | workflow 等待到 `PUBLISHED`，Portal 中坐标、版本和文件与 manifest 一致 |
| MAN-007 | GitHub Packages（若本版本包含该目标） | 使用测试版本完成上传和只读消费，仓库 owner/repo 与权限符合预期 |
| MAN-008 | RemoteAll 恢复演练（发布编排有改动时） | 一个 provider 成功、另一个受控失败后，resume 不重复上传已成功 provider |
| MAN-009 | 发布后消费冒烟 | 一个不依赖本仓库 composite build 的样例工程可从正式仓库解析并应用插件 |

## 失败处理

- 任一 `PRE-*` 或 compatibility job 失败：不得发布。先保留测试报告、manifest 和临时
  bundle 的诊断信息，修复后完整重跑。
- 版本或 tag 冲突：不得使用 `allowExistingVersion` 绕过正式版本不可变约束；升级版本。
- 401/403：作为凭据/权限永久失败处理，不自动重试。
- 5xx、网络超时：可在确认远端没有成功写入后重试；RemoteAll 使用相同 bundle
  fingerprint 和 `-PresumePublish=true` 恢复。
- Central 校验失败：不得创建 tag 或合并到 `main`；根据 Portal 错误修复，无法修复时
  drop deployment。
- 发布已成功但 aftercare 失败：保留 tag、deployment、commit 和 manifest 的映射，
  单独修复 README/分支同步，不重复发布同一版本。

## 发布证据

正式发布记录至少应包含：source commit、版本与 tag、Actions run、Central deployment
或目标仓库地址、实现包与 marker 坐标、publish manifest、兼容矩阵结果，以及任何跳过
或人工处理项目。只有制品可解析且发布证据可追溯时，才视为发布成功。
