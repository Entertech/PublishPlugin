---
name: enter-one-click-publish-config
description: Use when the user explicitly mentions Enter's one-click publishing configuration or the Enter Publish / Flowtime Publish plugin and asks to configure publishing information or workflow settings.
---

# Enter One-Click Publish Configuration

Use this skill only for PublishPlugin one-click publishing configuration. The
current contract separates component metadata, repository DSL, local runtime
credentials, and GitHub Actions inputs/secrets.

## Public tasks

After applying `cn.entertech.publish`, exactly four PublishPlugin tasks are
registered per module:

- Library: `PublishLibraryLocalTask`, `PublishLibraryRemoteAllTask`, `PublishLibraryRemoteGithubPackagesTask`, `PublishLibraryRemoteCentralTask`.
- Gradle Plugin: the same four names with `Library` replaced by `Plugin`.

The old `generatePublishConfig`, `configurePublish`, rollback tasks, aliases,
and generic `PublishLibraryRemoteTask` are removed. Do not recreate them.

`LocalTask` publishes to Maven Local. Explicit remote tasks publish only to
their named provider. `RemoteAllTask` uses providers enabled in the
`PublishRepositories` DSL and fails if none are enabled. Execution environment
(local computer or GitHub Actions) does not change task names.

## Configuration boundaries

Keep coordinates and component metadata in module `PublishInfo`:

```kotlin
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-lib"
    version = "2.0.0"
}
```

Keep non-sensitive repository selection in tracked Gradle DSL:

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

Local-only credentials belong in ignored `.publish/local.properties`:

```properties
publish.local.githubPackages.username=
publish.local.githubPackages.token=
publish.local.central.username=
publish.local.central.password=
publish.local.central.signingKeyFile=
publish.local.central.signingKeyId=
publish.local.central.signingPassword=
```

Runtime resolution is Gradle property, then environment variable, then the
`.publish/local.properties` value. The root Android `local.properties` is never
used for publishing credentials or workflow settings. GitHub Actions uses
workflow inputs and repository Secrets and must not read either local file.

## Artifact source

Publishing and packaging are separate. `artifactSource=project` may invoke the
standard project publication tasks. `artifactSource=prebuilt` requires a
project-relative `artifactBundlePath` containing `publish-artifacts.json` and
the declared AAR/JAR, POM, module metadata, sources, javadoc, signatures, and
checksums. The manifest supplies coordinates and file roles; never infer them
from filenames. Prebuilt mode must not execute compile, assemble, bundle, jar,
or other project packaging tasks.

Local examples:

```bash
./gradlew :library:PublishLibraryLocalTask
./gradlew :library:PublishLibraryRemoteCentralTask \
  -PartifactSource=prebuilt \
  -PartifactBundlePath=release-artifacts/library
```

GitHub Actions uses reusable workflow inputs `component_type`, `publish_target`,
`artifact_source`, `artifact_bundle_path`, and optional
`artifact_bundle_artifact`; it maps the allowlisted combination to one exact
task name. Central release requires sources, javadoc, and complete signature
files. `all` validates the bundle once and publishes the immutable bundle to
each enabled provider.

## Workflow and safety

Generated workflows pass `component_type` and use
`Entertech/PublishPlugin/.github/workflows/publish.yml@main`. Secrets are
provided only at the publish step. Validate module paths, target combinations,
and prebuilt paths before invoking Gradle.

Use `scripts/configure-publish-offline.sh` to select or run an exact task; it
does not accept secret values as arguments. When editing this skill, run:

```bash
./scripts/install-codex-skill.sh
./scripts/install-codex-skill.sh --check
```
