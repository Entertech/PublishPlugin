---
name: enter-publish-config
description: Configure an Android project to use Enter/Flowtime PublishPlugin for local or GitHub Actions publishing. Edit publishing metadata, repository settings, local credential templates, and workflows, but do not build, publish, upload artifacts, or trigger workflows.
---

# Enter Publish Config

Configure a project so that a developer or a separate release workflow can
publish it later. This skill prepares and validates configuration only.

## Hard boundary

Never perform the release itself while using this skill:

- Do not execute any `PublishLibrary*Task` or `PublishPlugin*Task`.
- Do not execute `publishToMavenLocal` or any Gradle `publish*Publication*` task.
- Do not build artifacts for the purpose of publishing them.
- Do not upload AAR/JAR files or call repository publishing APIs.
- Do not run or dispatch a GitHub Actions workflow, including `gh workflow run`.

The user asking to “configure publishing” does not authorize publication. This
skill stops after configuration and hands execution to
`$enter-publish-run`, which requires an explicit publish request.

## Configuration work in scope

1. Detect whether the target module is an Android Library or Gradle Plugin.
2. Apply or verify `cn.entertech.publish` and configure module `PublishInfo`.
3. Configure non-sensitive provider settings in `PublishRepositories`.
4. Create or update an ignored `.publish/local.properties` template for local
   execution. Never put publishing fields in root Android `local.properties`.
5. Generate or update a GitHub Actions caller workflow and identify required
   repository Secrets. Write Secrets only when the user explicitly requests
   that configuration write; never trigger the workflow afterward.
6. Configure `project` or `prebuilt` artifact-source inputs. For `prebuilt`,
   create or validate `publish-artifacts.json` without uploading its files.
7. Run non-publishing checks, such as inspecting `tasks --all`, parsing YAML,
   checking ignored/untracked files, or running configuration-focused tests.
8. Report the files changed, missing user-supplied values, and the exact task or
   workflow that `$enter-publish-run` may execute later.

Read [references/publish-config-workflow.md](references/publish-config-workflow.md)
when configuring task selection, local credentials, GitHub Actions, or prebuilt
artifact manifests.

## Legacy configuration migration

The plugin runtime does not support legacy fields or task aliases. This skill
does support projects that still contain them: detect legacy entries during
configuration and replace them in the same pass. Do not leave a legacy key as
a fallback, create a compatibility alias, or ask the release skill to interpret
it later.

Scan module build files, root `local.properties`, existing `publish.*` or
`centralPublish.*` properties files, and caller workflows. Move values to the
new location, then remove the old entry and update task/workflow references:

| Legacy input | Replace with |
| --- | --- |
| Component fields in properties (`groupId`, `artifactId`, `version`, `pluginId`, `implementationClass`, POM fields, `hasSource`/`obfuscate`) | The module `PublishInfo` block; convert `obfuscate` to `hasSource = !obfuscate`. |
| `publish.githubRepo`, `publish.githubPackagesRepository`, `publish.githubPackagesUrl` | `PublishRepositories.githubPackages { repository = ...; repositoryUrl = ... }` (and the equivalent reusable-workflow inputs when needed). |
| `publish.centralNamespace`, `publish.centralPublishingType`, `publish.centralRepositoryName` | `PublishRepositories.central { namespace = ...; publishingType = ...; releaseRepositoryName = ... }`. |
| `publish.mavenCentralUsername`, `publish.mavenCentralPassword`, `publish.gpgKeyFile`, `publish.signingKeyId`, `publish.signingPassword` (including `centralPublish.*`) | Ignored `.publish/local.properties` keys `publish.local.central.username`, `.password`, `.signingKeyFile`, `.signingKeyId`, `.signingPassword`. |
| `publishTarget` and generic `Publish*RemoteTask` invocations | Explicit workflow `publish_target` and the matching task: `github_packages` → `RemoteGithubPackagesTask`, `central` → `RemoteCentralTask`, `all` → `RemoteAllTask`; use `Plugin` instead of `Library` for plugin modules. |
| `githubActions`, `workflowPath`, `workflowUses`, GitHub secret-name fields | A tracked caller workflow plus repository Secrets; never copy secret values into tracked files. |
| `dryRun`, `overwriteGithubSecrets`, `gpgGenerate` and GPG key-generation fields | Remove them from project configuration; they have no runtime equivalent in the new contract. Report their removal, and never generate keys or secrets as part of this skill. |

When a legacy sensitive value is found, move it without printing the value and
report the destination key. If the source file is tracked or would expose a
secret, stop before committing and require the value to be rotated or removed;
the generated local file must remain ignored and untracked. After migration,
run non-publishing checks and report every removed legacy key and its new
destination. If both legacy and new keys exist, keep the new key's value,
remove the legacy key, and report the conflict without printing either value.

## Public task contract

Configuration must target exactly four PublishPlugin tasks per module:

- Library: `PublishLibraryLocalTask`, `PublishLibraryRemoteAllTask`,
  `PublishLibraryRemoteGithubPackagesTask`, `PublishLibraryRemoteCentralTask`.
- Gradle Plugin: the same names with `Library` replaced by `Plugin`.

The old configuration tasks, rollback aliases, and generic
`PublishLibraryRemoteTask` must not be restored. These task names are handoff
information only; this skill must not execute them.

## Configuration placement

- Component identity, POM metadata, and variant rules: module `PublishInfo`.
- Non-sensitive repository selection: tracked `PublishRepositories` DSL.
- Local-only credentials: ignored `.publish/local.properties`.
- GitHub Actions settings: tracked workflow inputs.
- CI credentials: GitHub repository Secrets.

Resolution order is Gradle property, environment variable, then
`.publish/local.properties`. GitHub Actions must not read local configuration
files.

After changing this repository skill, install and validate its source-of-truth
symlink:

```bash
./scripts/install-codex-skill.sh
./scripts/install-codex-skill.sh --check
```
