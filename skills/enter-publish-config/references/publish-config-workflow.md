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
    version = "1.0.0"
}
```

Gradle Plugin modules additionally require `pluginId` and
`implementationClass`. Preserve existing POM and variant configuration.

Keep non-sensitive provider selection in tracked DSL:

```kotlin
configure<custom.android.plugin.PublishRepositories> {
    githubPackages {
        enabled.set(true)
        repository.set("Entertech/demo-lib")
    }
    central {
        enabled.set(true)
        namespace.set("cn.entertech")
        publishingType.set("user_managed")
    }
}
```

Use the explicit `configure<custom.android.plugin.PublishRepositories>` form
for Kotlin DSL so the configuration also compiles when a convention plugin
applies `cn.entertech.publish` dynamically. The short generated accessor is
safe only when the publish plugin is applied directly in the same script.

A dedicated remote task requires its provider to be enabled. `all` requires at
least one enabled provider.

For a local read-only preflight, run:

```bash
scripts/configure-publish-offline.sh :library \
  --component-type library --publish-target central --check-only --run
```

The helper supports macOS and Linux with Bash/Java/Gradle; Windows users should
use Git Bash or WSL, or run `:module:checkPublish` directly.

## Legacy-field migration

When the target project still has a previous PublishPlugin configuration, do
not preserve it for runtime compatibility. Read each old value, write the new
configuration, and delete the old key in the same edit. Never print secret
values.

| Legacy location/key | New location |
| --- | --- |
| `publish.groupId`, `publish.artifactId`, `publish.version`, `publish.pluginId`, `publish.implementationClass`, and other component/POM fields | Module `PublishInfo { ... }`. `obfuscate` becomes `hasSource = !obfuscate`. |
| `publish.githubRepo`, `publish.githubPackagesRepository`, `publish.githubPackagesUrl` | Tracked `PublishRepositories.githubPackages` (`repository`, `repositoryUrl`) or the matching reusable-workflow inputs. |
| `publish.centralNamespace`, `publish.centralPublishingType`, `publish.centralRepositoryName` | Tracked `PublishRepositories.central` (`namespace`, `publishingType`, `releaseRepositoryName`). |
| `publish.mavenCentralUsername`, `publish.mavenCentralPassword`, `publish.gpgKeyFile`, `publish.signingKeyId`, `publish.signingPassword`; same keys under `centralPublish.*` | Ignored `.publish/local.properties`: `publish.local.central.username`, `.password`, `.signingKeyFile`, `.signingKeyId`, `.signingPassword`. |
| `publishTarget`, `Publish*RemoteTask`, or old task aliases | Workflow `publish_target` and the matching explicit task: `github_packages` → `RemoteGithubPackagesTask`, `central` → `RemoteCentralTask`, `all` → `RemoteAllTask`; use `Plugin` instead of `Library` for plugin modules. |
| `githubActions`, `workflowPath`, `workflowUses`, and custom secret-name fields | A tracked caller workflow using the reusable workflow's fixed inputs/secrets. |
| `dryRun`, `overwriteGithubSecrets`, `gpgGenerate`, and GPG key-generation fields | Delete and report as removed; they are not part of the new runtime contract. |

The migration is complete only when searches of the configured files find no
legacy keys or generic task references. If a legacy secret is in a tracked
file, remove it from that file, keep the replacement local file ignored, and
tell the user to rotate the exposed credential before committing. If both old
and new keys are present, keep the new value, delete the old key, and report the
conflict without printing either value.

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
      version: "1.0.0"
```

Identify the repository Secrets required by the selected provider. Secret
creation is a separate configuration mutation and requires explicit user
authorization. Generating the workflow does not authorize dispatching it.

## Prebuilt artifact configuration

For `artifact_source=prebuilt`, configure a project-relative
`artifact_bundle_path` and optional `artifact_bundle_artifact`. The directory
must contain `publish-artifacts.json`, which declares publication coordinates,
packaging, file roles, relative paths, sizes, and SHA-256 values.

Before selecting `prebuilt`, distinguish a standard bundle from Maven Local
output. `Publish*LocalTask` and `publishToMavenLocal` normally create a
`-local` version under the developer's Maven repository, do not create
`publish-artifacts.json`, and do not make those files visible to GitHub
Actions. Do not infer a manifest from that directory or silently rewrite its
coordinates. If CI should rebuild the checked-out source, configure
`artifact_source: project`. If CI should consume a bundle, require the exact
remote version plus the manifest and all destination-specific roles first.

An `artifact_bundle_artifact` must be uploaded by an earlier job in the same
workflow run before the reusable publish job starts. A file that exists only
on the developer machine, or an artifact from an unrelated historical run, is
not available through this input.

Allowed roles are `main`, `pom`, `gradle_module`, `sources`, `javadoc`,
`signature`, `checksum`, and `plugin_marker`. Validate schema, paths, files,
sizes, checksums, and target requirements without uploading anything. Do not
infer coordinates from filenames.

For Central, `project` mode requires the CI GPG private-key secrets because it
builds and signs there. A valid Central `prebuilt` bundle already contains its
detached signatures, so the publish job requires Central repository
credentials but must not require the GPG private key again.

## Safe validation

Allowed checks include:

- inspecting `:module:tasks --all` to verify the four task names;
- parsing generated YAML;
- verifying `.publish/local.properties` is ignored and untracked;
- validating a prebuilt manifest and its local files without network access;
- running configuration-focused unit or functional tests.

Do not use a publish-task dry run as a substitute for this boundary. The skill
must not invoke a publish task at all.
