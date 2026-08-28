package custom.android.plugin

data class PublishPreflightResult(
    val provider: String,
    val status: String,
    val message: String,
    val retryable: Boolean = false
)

data class PublishProviderResult(
    val provider: String,
    val status: String,
    val message: String = ""
)

data class PublishGateResult(
    val name: String,
    val status: String,
    val message: String = ""
)
