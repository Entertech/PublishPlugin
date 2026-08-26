# One-Click Publish Configuration Reference

This reference describes configuration outputs and handoff information. It does
not authorize or instruct the skill to execute a publication.

## Configuration intent

Resolve these values before editing the project:

| Field | Values |
| --- | --- |
| Module | Gradle path such as `:library` |
| Component | `library` or `plugin` |
| Execution environment | `local` or `github_actions` |
| Destination | `local`, `github_packages`, `central`, or `all` |
| Artifact source | `project` or `prebuilt` |

`github_actions + local` is invalid. These values select configuration and the
handoff task; they do not cause that task to run.

## Task handoff mapping

| Component | Local | GitHub Packages | Central | All |
| --- | --- | --- | --- | --- |
| Library | `PublishLibraryLocalTask` | `PublishLibraryRemoteGithubPackagesTask` | `PublishLibraryRemoteCentralTask` | `PublishLibraryRemoteAllTask` |
| Plugin | `PublishPluginLocalTask` | `PublishPluginRemoteGithubPackagesTask` | `PublishPluginRemoteCentralTask` | `PublishPluginRemoteAllTask` |

At completion, report the applicable task name as handoff information for
`$enter-publish-run`. Do not execute it, even for Maven Local.

## Module configuration

Keep coordinates and component metadata in `PublishInfo`:

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "2.0.0"
}
```

Gradle Plugin modules additionally require `pluginId` and
`implementationClass`. Preserve existing POM and variant configuration.

Keep non-sensitive provider selection in tracked DSL:

```kotlin
PublishRepositories {
    githubPackages {
        enabled = true
        repository = "Entertech/demo-lib"
    }
    central {
        enabled = true
        namespace = "cn.entertech"
        publishingType = "user_managed"
    }
}
```

A dedicated remote task requires its provider to be enabled. `all` requires at
least one enabled provider.

## Local execution configuration

Use ignored `.publish/local.properties` only:

```properties
publish.local.githubPackages.username=
publish.local.githubPackages.token=
publish.local.central.username=
publish.local.central.password=
publish.local.central.signingKeyFile=
publish.local.central.signingKeyId=
publish.local.central.signingPassword=
```

Ensure `/.publish/local.properties` is ignored and untracked. Never read or
write publishing fields in root Android `local.properties`. Do not populate
secret values unless the user explicitly supplies them and asks for that local
configuration write; never print their values.

## GitHub Actions configuration

Generate a tracked caller workflow using:

```yaml
jobs:
  publish:
    uses: Entertech/PublishPlugin/.github/workflows/publish.yml@main
    secrets: inherit
    with:
      module: ":library"
      component_type: "library"
      publish_target: "central"
      artifact_source: "project"
      publish_mode: "release"
      version: "2.0.0"
```

Identify the repository Secrets required by the selected provider. Secret
creation is a separate configuration mutation and requires explicit user
authorization. Generating the workflow does not authorize dispatching it.

## Prebuilt artifact configuration

For `artifact_source=prebuilt`, configure a project-relative
`artifact_bundle_path` and optional `artifact_bundle_artifact`. The directory
must contain `publish-artifacts.json`, which declares publication coordinates,
packaging, file roles, relative paths, sizes, and SHA-256 values.

Allowed roles are `main`, `pom`, `gradle_module`, `sources`, `javadoc`,
`signature`, `checksum`, and `plugin_marker`. Validate schema, paths, files,
sizes, checksums, and target requirements without uploading anything. Do not
infer coordinates from filenames.

## Safe validation

Allowed checks include:

- inspecting `:module:tasks --all` to verify the four task names;
- parsing generated YAML;
- verifying `.publish/local.properties` is ignored and untracked;
- validating a prebuilt manifest and its local files without network access;
- running configuration-focused unit or functional tests.

Do not use a publish-task dry run as a substitute for this boundary. The skill
must not invoke a publish task at all.
