package cn.entertech.plugin.demo.lib

object DemoArtifactNames {
    const val BASE_ARTIFACT_ID = "publish-demo-lib"

    fun artifactId(project: String, authentication: String): String {
        val authSuffix = if (authentication == "auth") {
            "-authentication"
        } else {
            ""
        }
        return "$project-$BASE_ARTIFACT_ID$authSuffix"
    }
}
