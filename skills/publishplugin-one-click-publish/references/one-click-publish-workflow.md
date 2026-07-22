# One-Click Publish Workflow Reference

## Field Placement

Keep these in module `PublishInfo`:

| Field | Reason |
| --- | --- |
| `groupId`, `artifactId`, `version` | Maven component identity. |
| `pluginId`, `implementationClass` | Gradle Plugin component identity. |
| `pomName`, `pomDescription`, `pomUrl` | Component POM metadata. Defaults may fill these when omitted. |

Keep these in root `local.properties` as `publish.*` repository-wide values. Legacy `centralPublish.*` keys are read for compatibility; the one-click workflow itself defaults to GitHub Packages.

| Field group | Examples |
| --- | --- |
| Publish target | `publishTarget`, `githubPackagesRepository`, `githubPackagesUrl` |
| Central behavior | `centralNamespace`, `centralPublishingType`, `centralRepositoryName` |
| GitHub targeting | `githubRepo`, `githubActions`, `workflowPath`, `workflowUses` |
| GitHub secret setup | `githubSecrets`, `overwriteGithubSecrets`, secret-name override fields |
| One-time secret inputs | `mavenCentralUsername`, `mavenCentralPassword`, `gpgKeyFile`, `signingPassword`, `signingKeyId` |
| GPG generation | `gpgGenerate`, `gpgName`, `gpgEmail`, `gpgKeyType`, `gpgKeyLength`, `gpgKeyExpire`, `gpgComment` |
| Shared POM defaults | license, developer, SCM fields |

Do not put sensitive values into tracked files. `local.properties` must be ignored and untracked.

## Resolution Order

For repository-wide publish fields:

```text
Gradle property > environment variable > PublishInfo explicit value > local.properties publish.* nonblank value > default/inference
```

For inferred POM/SCM URLs, use the current project's `git remote origin` before GitHub Actions, GitLab, or other CI environment variables. This prevents TestKit fixtures and nested builds from inheriting the outer repository's `GITHUB_REPOSITORY`.

For component coordinates:

```text
PublishInfo only
```

For Central token during remote publish:

```text
-PcentralUsername/-PcentralPassword > CENTRAL_USERNAME/CENTRAL_PASSWORD >
-PmavenCentralUsername/-PmavenCentralPassword > MAVEN_CENTRAL_USERNAME/MAVEN_CENTRAL_PASSWORD >
PublishInfo publishUserName/publishPassword > legacy local.properties publishUserName/publishPassword
```

Signing credentials are runtime Gradle properties or environment variables only:

```text
signingInMemoryKey / GPG_KEY_CONTENTS
signingInMemoryKeyId / SIGNING_KEY_ID
signingInMemoryKeyPassword / SIGNING_PASSWORD
```

## Task Behavior Checklist

### `generatePublishConfig` / legacy `generateCentralPublishConfig`

- Resolve `-PpublishConfig`, default `local.properties`.
- Create or update the config file.
- Preserve Android fields such as `sdk.dir` and `ndk.dir`.
- Append missing `publish.*` keys only.
- Keep generated template comments ASCII-only English to avoid mojibake in Java `.properties` and IDE readers.
- With `-PoverwritePublishConfig=true`, refresh generated template comments while preserving existing `publish.*` values and non-`publish.*` lines.
- Do not add `modules` or `module.<alias>.*`.
- Ensure the config path is ignored.
- Fail if the config file is already tracked.

### `configurePublish` / legacy `configureCentralPublish`

- Resolve `-PpublishConfig`, default `local.properties`.
- Fail if config file is missing; suggest `:module:generatePublishConfig`.
- Fail if config file is tracked.
- With `dryRun=true`, do not write files, secrets, or GPG keys.
- Reject component fields and module declaration fields in config.
- Validate current module `PublishInfo`.
- Validate `version` does not contain `debug`.
- Default `publishTarget` to `github_packages`; support `central` and `all` explicitly.
- For `central` or `all`, validate `groupId` belongs to `centralNamespace`.
- For `central` or `all`, validate `centralPublishingType` is `user_managed` or `automatic`.
- If `githubSecrets=true` and target is `central` or `all`, use `gh` to list existing secrets and write missing/overridden names.
- If `githubActions=true`, generate workflow at explicit `workflowPath` or default `.github/workflows/publish-<module>.yml`.

### `rollbackPublishSecrets` / legacy `rollbackCentralPublishSecrets`

- Load same config file.
- Resolve repo from `publish.githubRepo`, `gh repo view`, or `git remote origin`.
- Delete the configured/default secret names.
- Treat missing secret deletion as warning.
- Ensure config path is ignored.
- With `-PremoveGeneratedWorkflow=true`, delete only generated workflows.

## Secret Rules

- Default secret names:
  - `MAVEN_CENTRAL_USERNAME`
  - `MAVEN_CENTRAL_PASSWORD`
  - `GPG_KEY_CONTENTS`
  - `SIGNING_PASSWORD`
  - `SIGNING_KEY_ID`
- `SIGNING_KEY_ID` is optional; omit when blank.
- If both GPG key and password already exist and `gpgGenerate=false`, reuse them.
- If only one GPG secret is missing, write only the missing one unless overwrite or generate mode is enabled.
- `overwriteGithubSecrets=true` allows overwriting existing names.
- `gpgGenerate=true` means generate a new key and overwrite GPG secrets; require `gpgName`, `gpgEmail`, `signingPassword`, and `gpgKeyFile`.

## Workflow Rules

Default generated workflow shape:

```yaml
# Generated by PublishPlugin configurePublish
name: Publish Maven

on:
  workflow_dispatch:

permissions:
  contents: write
  packages: write

jobs:
  publish:
    uses: Entertech/PublishPlugin/.github/workflows/publish.yml@main
    secrets: inherit
    with:
      module: ":library"
      publish_target: "github_packages"
      publish_mode: "release"
      version: "1.0.0"
      sync_readme: true
```

Central target workflow shape:

```yaml
jobs:
  publish:
    uses: Entertech/PublishPlugin/.github/workflows/publish.yml@main
    secrets: inherit
    with:
      module: ":library"
      publish_target: "central"
      publish_mode: "release"
      version: "1.0.0"
      sync_readme: true
      namespace: "cn.entertech"
      publishing_type: "user_managed"
```

CI snapshot workflow shape for skill-triggered package builds:

```yaml
jobs:
  publish:
    uses: Entertech/PublishPlugin/.github/workflows/publish.yml@main
    secrets: inherit
    with:
      module: ":library"
      publish_target: "central"
      publish_mode: "ci"
      version: "1.0.0"
      namespace: "cn.entertech"
      publishing_type: "user_managed"
```

`publish_mode=ci` only supports `publish_target=central`, appends `-SNAPSHOT`, publishes to Central snapshots, and must not update README. `publish_mode=release` keeps the exact version, publishes to release repositories, and may sync README after successful publish.

If a workflow file already exists:

- overwrite when it contains the generated marker;
- otherwise fail unless the user explicitly passes `-PoverwriteWorkflow=true`.

## Common Review Findings

| Symptom | Expected fix |
| --- | --- |
| `dryRun=true` still writes `.gitignore`, workflow, secrets, or GPG key | Gate all writes behind dry-run summary output. |
| Existing GPG secret is rewritten when only the companion secret is missing | Decide write/skip per secret name. |
| Rollback no-ops when `githubRepo` is empty | Infer via `gh repo view`, then `git remote origin`; fail clearly if unavailable. |
| Rollback cannot delete default workflow | Use `.github/workflows/publish-<module>.yml` when `workflowPath` is blank; keep legacy `.github/workflows/publish-central-<module>.yml` cleanup. |
| Config accepts `publish.pomUrl` | Reject and instruct moving it to current module `PublishInfo`. |
| Sensitive values appear in command args/logs | Use stdin and sanitize logs. |
