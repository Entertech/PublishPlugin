# One-Click Publish Workflow Reference

## Task contract

Each module exposes only four PublishPlugin tasks. Choose the component name
from the applied Gradle plugins and choose the target explicitly:

| Component | Local | GitHub Packages | Central | All |
| --- | --- | --- | --- | --- |
| Library | `PublishLibraryLocalTask` | `PublishLibraryRemoteGithubPackagesTask` | `PublishLibraryRemoteCentralTask` | `PublishLibraryRemoteAllTask` |
| Plugin | `PublishPluginLocalTask` | `PublishPluginRemoteGithubPackagesTask` | `PublishPluginRemoteCentralTask` | `PublishPluginRemoteAllTask` |

There is no generic remote task and no Gradle configuration/rollback task.

## Configuration placement

Coordinates, component metadata, POM metadata, and variant rules stay in the
module `PublishInfo` DSL. Non-sensitive provider selection stays in the tracked
`PublishRepositories` DSL. Local credentials use ignored
`.publish/local.properties` with `publish.local.*` keys. Gradle properties and
environment variables override those local values. Root `local.properties` is
not a publishing configuration file.

GitHub Actions receives non-sensitive values as reusable-workflow inputs and
credentials as repository Secrets. It does not read `.publish/local.properties`
or root `local.properties`.

## Artifact source

`artifact_source=project` uses the current project's standard publication
preparation. `artifact_source=prebuilt` requires a project-relative directory
and `publish-artifacts.json` manifest. The manifest declares every publication,
coordinate, packaging, role, relative path, size, and SHA-256. Roles are
`main`, `pom`, `gradle_module`, `sources`, `javadoc`, `signature`, `checksum`,
and `plugin_marker`.

The validator checks all files before any upload. Central release requires main,
POM, sources, javadoc, and all declared signature files. A prebuilt task never
adds compile, assemble, bundle, jar, or other packaging tasks to the execution
graph and never uploads files not declared by the manifest.

## Reusable workflow inputs

```yaml
with:
  module: ":library"
  component_type: "library"
  publish_target: "central"
  artifact_source: "prebuilt"
  artifact_bundle_path: "release-artifacts/library"
  artifact_bundle_artifact: ""
```

`artifact_bundle_artifact` optionally downloads an Actions artifact into the
bundle path before validation. The workflow uses a fixed component/target
allowlist and invokes one exact task. GitHub Actions never supports Maven Local
as a release destination.
