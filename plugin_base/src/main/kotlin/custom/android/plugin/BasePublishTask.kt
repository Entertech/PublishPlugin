package custom.android.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.StringBuilder

abstract class BasePublishTask : DefaultTask() {
    //检验状态是否通过
    private var checkStatus = false

    /**
     * 不能写成get/set
     * */
    abstract fun initPublishCommandLine(): String

    companion object {
        private const val TAG = "BasePublishTask"
        const val MAVEN_PUBLICATION_NAME = "EnterPublish"
    }

    init {
        group = "customPlugin"
    }


    @TaskAction
    fun doTask() {
        executeTask()
    }

    protected fun executeTask() {
        val publishInfo = project.extensions.getByType(PublishInfo::class.java)

        //1、对publisher配置的信息进行基础校验
        //2、把publisher上传到服务器端，做版本重复性校验
        checkStatus = checkPublishInfo(publishInfo)
        //如果前两步都校验通过了，checkStatus设置为true
//        PluginLogUtil.printlnDebugInScreen("projectDir: ${project.projectDir.absolutePath}")
//        PluginLogUtil.printlnDebugInScreen("rootDir: ${project.rootDir.absolutePath}")
        if (!checkStatus) {
            throw GradleException("发布配置校验失败")
        }
        val realTaskName =
            project.projectDir.absolutePath
                .removePrefix(project.rootDir.absolutePath)
                .replace(File.separator, ":") + initPublishCommandLine()

        if (checkStatus) {
            val out = ByteArrayOutputStream()
            val osName = System.getProperty("os.name")
            PluginLogUtil.printlnInfoInScreen("current System is :$osName")
            val gradlewFileName = if (osName.contains("Windows")) {
                // Windows 系统
                "gradlew.bat"
            } else if (osName.contains("Mac")) {
                // macOS 系统
                "gradlew"
            } else if (osName.contains("Linux")) {
                // Linux 系统
                "gradlew"
            } else {
                ""
            }
            val path = "${project.rootDir}${File.separator}${gradlewFileName}"
            PluginLogUtil.printlnDebugInScreen("$TAG path: $path realTaskName: $realTaskName")
            //通过命令行的方式进行调用上传maven的task
            val execResult = project.exec { exec ->
                exec.standardOutput = out
                exec.errorOutput = out
                exec.isIgnoreExitValue = true
                configureNestedGradleExec(exec, publishInfo)
                exec.setCommandLine(
                    path,
                    realTaskName
                )
            }
            val result = out.toString()
            PluginLogUtil.printlnDebugInScreen("result: $result")
            if (execResult.exitValue != 0) {
                PluginLogUtil.printlnErrorInScreen("===============================下面是执行指令的输出结果===============================")
                PluginLogUtil.printlnErrorInScreen("")
                PluginLogUtil.printlnErrorInScreen(result)
                PluginLogUtil.printlnErrorInScreen("")
                PluginLogUtil.printlnErrorInScreen("==================================================================================")
                throw GradleException("上传Maven仓库失败，请检查配置！")
            }
            //上传maven仓库成功，上报到服务器
            val isSuccess = requestUploadVersion()
            if (isSuccess) {
                afterPublishSuccess(publishInfo, result)
                printPublishSuccess()
            } else {
                throw Exception("上传Maven仓库失败，请检查配置！")
            }
            PluginLogUtil.printlnDebugInScreen("$TAG executeTask finish ")
        }
    }


    /**
     * 上报服务器进行版本检查,这里直接模拟返回成功
     * */
    protected open fun checkPublishInfo(publishInfo: PublishInfo): Boolean {
        return true
    }

    abstract fun getPublishingExtensionRepositoriesPath(publishing: PublishingExtension): String

    protected open fun appendPublicationGroupPathToRepositoryPath(): Boolean = false

    protected open fun printRemoteArtifactVerificationPath(): Boolean = false

    protected open fun afterPublishSuccess(publishInfo: PublishInfo, output: String) {
    }

    protected open fun configureNestedGradleExec(exec: ExecSpec, publishInfo: PublishInfo) {
    }

    private fun printPublishSuccess() {
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        val mavenPublications = publishing.publications
            .withType(MavenPublication::class.java)
            .toList()
        val publications = mavenPublications
            .filter { it.name.endsWith(MAVEN_PUBLICATION_NAME) }
            .ifEmpty { mavenPublications }
        publishing.repositories { artifactRepositories ->
            artifactRepositories.maven {
                //url可能为null，虽然提示不会为null
                PluginLogUtil.printlnInfoInScreen("${it.name} url: ${it.url}")
            }
        }
        publishing.repositories.maven {
            PluginLogUtil.printlnInfoInScreen(" ${it.name} url: ${it.url}")
        }
        PluginLogUtil.printlnInfoInScreen("构建成功")
        publications.forEach { publication ->
            val repositoryPath = getPublishingExtensionRepositoriesPath(publishing)
            val pathSb = StringBuilder(repositoryPath)
            if (appendPublicationGroupPathToRepositoryPath()) {
                pathSb.clear()
                pathSb.append(repositoryPath.trimEnd('/', File.separatorChar))
                pathSb.append("/")
                pathSb.append(publication.groupId.replace('.', '/'))
                pathSb.append("/")
            }
//                    pathSb.append(artifactId)
//                    pathSb.append(File.separatorChar)
            PluginLogUtil.printlnInfoInScreen("Maven 仓库地址（Gradle/Maven 配置用）：  $pathSb")
            if (printRemoteArtifactVerificationPath()) {
                PluginLogUtil.printlnInfoInScreen(
                    "POM 验证地址（需要 GitHub Packages 认证）：  " +
                        remotePomPath(repositoryPath, publication)
                )
                PluginLogUtil.printlnInfoInScreen("网页查看入口：  GitHub 仓库或组织的 Packages 页面")
            }
            PluginLogUtil.printlnInfoInScreen("===================================================================")
            PluginLogUtil.printlnInfoInScreen("")
            PluginLogUtil.printlnInfoInScreen(
                "dependencies {\n" +
                    "    implementation '${publication.groupId}:${publication.artifactId}:${publication.version}'\n" +
                    "}"
            )
            PluginLogUtil.printlnInfoInScreen("")
            PluginLogUtil.printlnInfoInScreen("==================================================================")
            PluginLogUtil.printlnInfoInScreen("")
            PluginLogUtil.printlnInfoInScreen(
                "dependencies {\n" +
                    "    implementation(\"${publication.groupId}:${publication.artifactId}:${publication.version}\")\n" +
                    "}"
            )
            PluginLogUtil.printlnInfoInScreen("")
            PluginLogUtil.printlnInfoInScreen("==================================================================")
        }
    }

    private fun remotePomPath(repositoryPath: String, publication: MavenPublication): String {
        val baseUrl = repositoryPath.trimEnd('/')
        val groupPath = publication.groupId.replace('.', '/')
        val artifactId = publication.artifactId
        val version = publication.version
        return "$baseUrl/$groupPath/$artifactId/$version/$artifactId-$version.pom"
    }

    /**
     * 上报服务器进行版本更新操作,这里直接模拟返回成功
     * */
    private fun requestUploadVersion(): Boolean {
        return true
    }

    abstract fun fetchTaskName(): String
}
