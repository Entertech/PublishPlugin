package custom.android.plugin

object PublishTaskNames {
    fun local(kind: PublishComponentKind) = "Publish${kind.taskNamePart}LocalTask"
    fun remoteAll(kind: PublishComponentKind) = "Publish${kind.taskNamePart}RemoteAllTask"
    fun remoteGithubPackages(kind: PublishComponentKind) = "Publish${kind.taskNamePart}RemoteGithubPackagesTask"
    fun remoteCentral(kind: PublishComponentKind) = "Publish${kind.taskNamePart}RemoteCentralTask"

    fun variantLocal(kind: PublishComponentKind, variantName: String) =
        "Publish${kind.taskNamePart}${variantName.capitalizeAscii()}LocalTask"

    fun variantRemoteAll(kind: PublishComponentKind, variantName: String) =
        "Publish${kind.taskNamePart}${variantName.capitalizeAscii()}RemoteAllTask"

    fun variantRemoteGithubPackages(kind: PublishComponentKind, variantName: String) =
        "Publish${kind.taskNamePart}${variantName.capitalizeAscii()}RemoteGithubPackagesTask"

    fun variantRemoteCentral(kind: PublishComponentKind, variantName: String) =
        "Publish${kind.taskNamePart}${variantName.capitalizeAscii()}RemoteCentralTask"
}

internal fun String.capitalizeAscii(): String {
    if (isEmpty()) {
        return this
    }
    val first = this[0]
    val capitalizedFirst = if (first in 'a'..'z') first - 32 else first
    return "$capitalizedFirst${substring(1)}"
}
