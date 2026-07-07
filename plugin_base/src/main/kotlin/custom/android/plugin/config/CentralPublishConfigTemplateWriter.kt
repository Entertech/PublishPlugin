package custom.android.plugin.config

import java.io.File

object CentralPublishConfigTemplateWriter {
    private val entries = listOf(
        Entry("githubRepo", "# GitHub 仓库，格式 owner/repo。为空时尝试通过 gh repo view 或 git remote origin 推导。"),
        Entry(
            "dryRun",
            "# dry run 开关。\n# true: 只打印将要变更的文件和 secret 名称，不写文件、不调用 gh secret set。\n# false/空: 正常执行。"
        ),
        Entry("centralNamespace", "# Sonatype Central namespace。为空时使用插件默认值 cn.entertech。"),
        Entry(
            "centralPublishingType",
            "# Central deployment 发布方式。\n# user_managed: 上传并校验后留在 Central Portal，需要手动点击 Publish 才会发布。\n# automatic: 上传并校验通过后，Central 自动尝试发布到 Maven Central。"
        ),
        Entry("centralRepositoryName", "# Central staging repository 名称。为空时使用插件默认值 CentralStaging。"),
        Entry("pomInceptionYear", "# POM inception year。为空时插件使用当前年份。"),
        Entry("licenseName", "# POM license 名称。为空时使用插件默认值 The Apache License, Version 2.0。"),
        Entry("licenseUrl", "# POM license URL。为空时使用插件默认值 https://www.apache.org/licenses/LICENSE-2.0.txt。"),
        Entry("licenseDistribution", "# POM license distribution。为空时使用插件默认值 repo。"),
        Entry("developerId", "# POM developer id。为空时使用插件默认值 Entertech。"),
        Entry("developerName", "# POM developer name。为空时使用插件默认值 Entertech。"),
        Entry("developerEmail", "# POM developer email。为空时使用插件默认值 developer@entertech.cn。"),
        Entry("developerOrganization", "# POM developer organization。为空时使用插件默认值 Entertech。"),
        Entry("developerOrganizationUrl", "# POM developer organization URL。为空时使用插件默认值 https://github.com/Entertech。"),
        Entry("developerUrl", "# POM developer URL。为空时使用插件默认值 https://github.com/Entertech。"),
        Entry("scmUrl", "# SCM 浏览地址。为空时插件尝试从 CI 或 git remote origin 推导。"),
        Entry("scmConnection", "# SCM connection。为空时可由 scmUrl 推导，例如 scm:git:https://github.com/owner/repo.git。"),
        Entry("scmDeveloperConnection", "# SCM developer connection。为空时可由 scmUrl 推导，例如 scm:git:ssh://git@github.com/owner/repo.git。"),
        Entry(
            "githubSecrets",
            "# 是否配置 GitHub repository secrets。\n# true: 调用 gh secret set 写入 Central token 和 GPG signing secrets。\n# false/空: 不处理 GitHub secrets。"
        ),
        Entry(
            "overwriteGithubSecrets",
            "# repository secret 已存在时是否覆盖。\n# true: 覆盖已有 secrets。\n# false/空: 复用已有 secrets，避免误替换线上 CI 密钥。"
        ),
        Entry("mavenCentralUsername", "# Central Portal User Token username。默认写入 secret MAVEN_CENTRAL_USERNAME。"),
        Entry("mavenCentralPassword", "# Central Portal User Token password。默认写入 secret MAVEN_CENTRAL_PASSWORD。"),
        Entry("gpgKeyFile", "# GPG ASCII 私钥文件路径。仓库没有 GPG_KEY_CONTENTS 且 gpgGenerate=false 时必填。"),
        Entry("signingPassword", "# GPG 私钥密码。仓库没有 SIGNING_PASSWORD 或 gpgGenerate=true 时必填。"),
        Entry("signingKeyId", "# GPG key id，可选。为空时 Gradle signing 尝试从私钥内容推断。"),
        Entry("mavenCentralUsernameSecret", "# Central username repository secret 名称。为空时使用 MAVEN_CENTRAL_USERNAME。"),
        Entry("mavenCentralPasswordSecret", "# Central password repository secret 名称。为空时使用 MAVEN_CENTRAL_PASSWORD。"),
        Entry("gpgKeySecret", "# GPG 私钥 repository secret 名称。为空时使用 GPG_KEY_CONTENTS。"),
        Entry("signingPasswordSecret", "# GPG 私钥密码 repository secret 名称。为空时使用 SIGNING_PASSWORD。"),
        Entry("signingKeyIdSecret", "# GPG key id repository secret 名称。为空时使用 SIGNING_KEY_ID。"),
        Entry(
            "gpgGenerate",
            "# 是否由 task 调用本机 gpg 生成新的发布签名 key。\n# true: 生成新 GPG key，并覆盖 GPG_KEY_CONTENTS / SIGNING_PASSWORD。\n# false/空: 不生成；如果仓库已有 GPG secrets，则复用已有 secrets。"
        ),
        Entry("gpgKeyType", "# GPG 密钥类型。第一阶段只支持 RSA。"),
        Entry("gpgKeyLength", "# RSA 密钥长度。推荐 4096。"),
        Entry("gpgKeyExpire", "# GPG key 有效期。例如 1y、2y、0；0 表示不过期，不建议默认使用。"),
        Entry("gpgName", "# GPG uid 姓名，例如 Entertech。"),
        Entry("gpgEmail", "# GPG uid 邮箱，例如 developer@entertech.cn。"),
        Entry("gpgComment", "# GPG uid 注释，例如 Central Publish Signing Key。"),
        Entry(
            "githubActions",
            "# 是否生成 GitHub Actions workflow。\n# true: 生成或更新 workflowPath 指定的 workflow。\n# false/空: 不处理 workflow。"
        ),
        Entry("workflowPath", "# GitHub Actions workflow 文件路径。为空时按当前模块名生成，例如 .github/workflows/publish-central-demo-lib.yml。"),
        Entry("workflowUses", "# reusable workflow 引用，例如 Entertech/PublishPlugin/.github/workflows/central-publish.yml@main。")
    )

    fun writeTemplate(rootDir: File, configFile: File, overwrite: Boolean) {
        configFile.parentFile?.mkdirs()
        val existing = if (configFile.exists()) configFile.readText() else ""
        val existingKeys = Regex("""(?m)^\s*centralPublish\.([A-Za-z0-9_.-]+)\s*=""")
            .findAll(existing)
            .map { it.groupValues[1] }
            .toSet()

        val baseContent = if (overwrite) {
            existing.lineSequence()
                .filterNot { it.trimStart().startsWith("centralPublish.") }
                .joinToString(System.lineSeparator())
                .trimEnd()
        } else {
            existing.trimEnd()
        }
        val keysToWrite = if (overwrite) entries else entries.filter { it.key !in existingKeys }
        val template = keysToWrite.joinToString(System.lineSeparator() + System.lineSeparator()) { entry ->
            "${entry.comment}${System.lineSeparator()}centralPublish.${entry.key}="
        }
        val newContent = listOf(baseContent, template)
            .filter { it.isNotBlank() }
            .joinToString(System.lineSeparator() + System.lineSeparator()) + System.lineSeparator()
        configFile.writeText(newContent)
        GitSafetyChecker.ensureIgnored(rootDir, configFile)
    }

    private data class Entry(val key: String, val comment: String)
}
