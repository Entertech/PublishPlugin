---
name: enter-publish-run
description: Execute an already-configured Enter/Flowtime PublishPlugin release when the user explicitly asks to publish, upload, or trigger a publishing workflow. Supports Library and Plugin modules, local or GitHub Actions execution, and project or prebuilt artifacts; does not configure the project.
---

# Enter Publish Run

Execute a publication only after the user explicitly asks for the release
operation. This skill consumes configuration prepared by
`$enter-publish-config`; it does not create or repair that
configuration.

## PublishPlugin version

Keep the PublishPlugin dependency version separate from the component version
being published.

- If the user explicitly names a PublishPlugin dependency version, verify that
  exact version.
- Otherwise resolve the current documented version by walking two directories
  up from this `SKILL.md` to the PublishPlugin repository root and running
  `python3 scripts/resolve-publish-plugin-version.py` there.
- Verify the target project resolves `cn.entertech.publish` from that version,
  including version catalogs or convention plugins when applicable. Do not
  edit the dependency during release execution. A mismatch is configuration
  work and must be handed back to `$enter-publish-config`.

The resolver reads the dependency declaration in the repository `README.md`,
so synchronizing README version information changes this skill's default
without a hardcoded skill edit. `PublishInfo.version`, workflow `version`, and
`-PpublishVersion` are component versions and do not override the plugin
dependency version.

## Authorization boundary

Requests to configure, prepare, inspect, validate, explain, or generate a
workflow do not authorize publication. Publish only when the current request
explicitly asks to execute, upload, release, or trigger the publishing
workflow.

Do not reconfirm when the user already provided an unambiguous module,
destination, and explicit instruction to publish. Stop and ask when choosing a
different module, destination, version, branch, or artifact source would be
necessary.

## Preconditions

Before any upload or workflow dispatch:

1. Resolve the exact module, component kind (`Library` or `Plugin`), execution
   environment (`local` or `github_actions`), destination, artifact source, and
   effective version.
2. Verify the module applies `cn.entertech.publish` and has complete
   `PublishInfo` coordinates.
3. Verify the selected remote provider is enabled in `PublishRepositories`.
4. Reject `github_actions + local` and remote versions containing `debug`.
5. For `prebuilt`, validate the configured `publish-artifacts.json` and all
   declared local files before publication.
6. Never print credentials, tokens, signing keys, or passwords.

If configuration is missing or inconsistent, do not edit it. Report the exact
problem and hand off to `$enter-publish-config`.

## Execution routing

- For execution on the current machine, read
  [references/local-execution.md](references/local-execution.md).
- For GitHub Actions dispatch and monitoring, read
  [references/github-actions-execution.md](references/github-actions-execution.md).
- For `artifactSource=prebuilt`, also read
  [references/prebuilt-release.md](references/prebuilt-release.md).

## Exact public tasks

Use only the task determined by component and destination:

| Component | Local | GitHub Packages | Central | All enabled remotes |
| --- | --- | --- | --- | --- |
| Library | `PublishLibraryLocalTask` | `PublishLibraryRemoteGithubPackagesTask` | `PublishLibraryRemoteCentralTask` | `PublishLibraryRemoteAllTask` |
| Plugin | `PublishPluginLocalTask` | `PublishPluginRemoteGithubPackagesTask` | `PublishPluginRemoteCentralTask` | `PublishPluginRemoteAllTask` |

Do not use the removed `PublishLibraryRemoteTask`, configuration tasks, task
aliases, or `publishTarget` to change the meaning of a dedicated task. Do not
fall back to a broader task when the selected task is missing.

## Mutation rules

- Do not edit `PublishInfo`, `PublishRepositories`, local credential files,
  manifests, workflows, versions, or source code as part of release execution.
- A user-provided version may be passed as `-PpublishVersion`; do not write it
  into the build file. Maven Local suffix behavior belongs to the plugin and
  must not be recreated by the skill.
- Do not commit or push source changes unless separately requested.
- Make one release attempt. A failed remote release or workflow must not be
  retried automatically because the first attempt may have partially succeeded.
- `RemoteAllTask` is not transactional. Report each known succeeded, failed,
  and not-started provider.

## Completion report

After execution, report:

- module, component, execution environment, destination, artifact source, and
  effective version;
- the exact Gradle task or GitHub Actions run URL;
- every confirmed published coordinate and repository when available;
- partial success or failure without claiming unpublished artifacts succeeded;
- the safe retry entry point, while leaving retry authorization to the user.
