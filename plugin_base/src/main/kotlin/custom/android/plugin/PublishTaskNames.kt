package custom.android.plugin

object PublishTaskNames {
    fun local(kind: PublishComponentKind) = "Publish${kind.taskNamePart}LocalTask"
    fun remoteAll(kind: PublishComponentKind) = "Publish${kind.taskNamePart}RemoteAllTask"
    fun remoteGithubPackages(kind: PublishComponentKind) = "Publish${kind.taskNamePart}RemoteGithubPackagesTask"
    fun remoteCentral(kind: PublishComponentKind) = "Publish${kind.taskNamePart}RemoteCentralTask"
}
