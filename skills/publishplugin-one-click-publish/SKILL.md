---
name: publishplugin-one-click-publish
description: Use when, and only when, the user explicitly mentions the enter publish or flowtime publish publishing plugin and asks to one-click configure publishing information or publish workflow settings for that plugin.
---

# PublishPlugin One-Click Publish

## Overview

Use this skill only after the user explicitly names the enter publish or flowtime publish publishing plugin and asks about one-click publishing information/configuration. It is not a general Sonatype Central, Maven publishing, GPG, GitHub Actions, or GitHub secrets skill.

Use it to configure, review, or change PublishPlugin's one-click publish workflow without rediscovering the README and plan each time.

The core boundary is fixed: module identity and component metadata stay in each module's `PublishInfo`; one-click publish workflow settings and Central-only one-time secret inputs live in ignored/untracked `local.properties` as `publish.*`. Legacy `centralPublish.*` keys are read for compatibility. The default generated workflow target is GitHub Packages.

## Required Source Docs

When making code, docs, or review decisions, read the relevant sections before acting:

- General user-facing behavior: `../../README.md`, section `一键发布配置`.
- Detailed design and acceptance criteria: `../../doc/tech/publish-one-click-config-plan.md`.
- If touching multi-publication behavior, also read `../../doc/tech/multi-variant-publish-plugin-plan.md`.
- If touching Central remote publish internals, also read `../../doc/tech/central-portal-publish-plugin-plan.md`.

For full operational detail, load `references/one-click-publish-workflow.md`.

## Workflows

### Configure a Module

1. Confirm the target Gradle module path, for example `:library`.
2. Ensure the module applies `cn.entertech.publish`.
3. Keep required coordinates in module `PublishInfo`: `groupId`, `artifactId`, `version`.
4. For Gradle Plugin modules, also require `pluginId` and `implementationClass`.
5. For Android Library modules with flavors, keep variant coordinate callbacks such as `groupIdForVariant`, `artifactIdForVariant`, `versionForVariant`, and `skipVariantIf` in `PublishInfo`; do not move them into local publish config.
6. Generate or update the root config:
   ```bash
   ./gradlew :library:generatePublishConfig
   ```
7. Fill only repository-level publish keys in `local.properties`. Leave `publish.publishTarget` blank or set it to `github_packages` for the default GitHub Packages workflow; use `central` or `all` only when Central Portal publishing is required.
8. Run a dry run first when possible:
   ```properties
   publish.dryRun=true
   ```
   Dry run must not write `.gitignore`, workflow files, secrets, or GPG keys.
9. Run:
   ```bash
   ./gradlew :library:configurePublish
   ```

### Offline Script Wrapper

For users who want the same workflow without an AI assistant, use the repository script from the target repository root:

```bash
scripts/configure-publish-offline.sh :library --generate-only
scripts/configure-publish-offline.sh :library --configure-only -- --stacktrace
scripts/configure-publish-offline.sh :library --publish-target central -- --stacktrace
```

The script only orchestrates the existing Gradle tasks. It may accept workflow control flags such as `--publish-target github_packages|central|all`, but it must not accept Central token, GPG private key, GitHub token, or signing password values as command arguments.

### CI Snapshot Package Build

When using this repository's skill to trigger a CI package build instead of a normal release workflow, call the reusable workflow with:

```yaml
publish_target: "central"
publish_mode: "ci"
version: "<base semver without -SNAPSHOT>"
```

This mode supports both modules in this repository and modules in other repositories that apply `cn.entertech.publish`. It must publish only to Central snapshots, must append `-SNAPSHOT`, and must not sync README.

### Review or Fix Implementation

Check these invariants first:

- `local.properties` may contain repository-wide fields only. It must reject component fields such as `publish.groupId`, `publish.artifactId`, `publish.version`, `publish.pluginId`, `publish.implementationClass`, `publish.pomName`, `publish.pomDescription`, and `publish.pomUrl`.
- `modules` and `module.<alias>.*` are unsupported. Multi-module repos run each module's task separately.
- `PublishInfo` explicit values outrank `local.properties`; blank `publish.*` values are ignored.
- POM/SCM URL default inference must prefer the current project's `git remote origin` before GitHub Actions or other CI environment variables, so TestKit fixtures and nested builds are not polluted by the outer `GITHUB_REPOSITORY`.
- `generatePublishConfig` / legacy `generateCentralPublishConfig` template comments must stay ASCII-only English so Java `.properties` and IDE readers do not render raw UTF-8 comments as mojibake.
- `overwritePublishConfig=true` may refresh generated template comments, but it must preserve existing `publish.*` values and non-`publish.*` lines such as `sdk.dir`.
- Central token and GPG secret values are one-time inputs for `configurePublish` when `publishTarget=central` or `all`; runtime publish must use Gradle properties or environment variables, not local secret fallback.
- `publishTarget` defaults to `github_packages`; `githubSecrets=true` must not write Central/GPG secrets for the default GitHub Packages-only workflow.
- Generated workflow must call the configured reusable workflow, defaulting to `Entertech/PublishPlugin/.github/workflows/publish.yml@main`, and pass `publish_target` as `github_packages`, `central`, or `all`.
- Generated release workflows must pass `publish_mode=release`, `version`, and `sync_readme=true`. Skill-triggered CI package builds use `publish_mode=ci`, must force `publish_target=central`, append `-SNAPSHOT`, publish to Central snapshots, and must not update README.
- `dryRun=true` has no side effects.
- `overwriteGithubSecrets=false` never overwrites an existing repository secret; missing secrets are filled individually.
- `rollbackPublishSecrets` can infer the GitHub repo and can delete the default generated workflow path. Legacy rollback task names remain supported.

### Roll Back Secrets

Use:

```bash
./gradlew :library:rollbackPublishSecrets
./gradlew :library:rollbackCentralPublishSecrets
```

If `publish.githubRepo` is empty, infer with `gh repo view`; fallback to `git remote origin` when needed. With `-PremoveGeneratedWorkflow=true`, remove only workflows containing `# Generated by PublishPlugin configurePublish` or the legacy marker; default path is `.github/workflows/publish-<module>.yml`, with legacy `.github/workflows/publish-central-<module>.yml` cleanup retained.

## GitHub And Secret Safety

- Use local `gh`, not GitHub connector writes, for repository secrets.
- Run `gh auth status` before secret writes.
- Check existing names with `gh secret list -R OWNER/REPO --json name --jq '.[].name'`.
- Pass secret values through stdin, never command arguments.
- Do not print Central token, GPG private key, or signing password.
- If secrets were committed or pushed, advise token/key rotation plus history rewrite; `git replace` is not cleanup.

## Verification

For code changes in this repo, prefer focused red/green tests plus full verification:

```bash
./gradlew :plugin_base:test --tests custom.android.plugin.OneClickPublishTaskFunctionalTest --console=plain
./gradlew test --console=plain
```

When reviewing PRs, compare behavior against both `README.md` and `doc/tech/publish-one-click-config-plan.md`, not just current tests.

After changing this repository skill, verify the runtime symlink still points here:

```bash
./scripts/install-codex-skill.sh --check
```
