# GitHub Actions Execution

Use this mode only when the user explicitly asks to trigger the configured
publishing workflow. GitHub Actions cannot publish to Maven Local.

## Pre-dispatch checks

1. Follow `$git-account-safety` before every Git or GitHub operation.
2. Verify `gh auth status` and `gh api user --jq '.login'`.
3. Resolve the intended repository, branch/ref, and caller workflow file.
4. Inspect the caller workflow and confirm its fixed values match the requested
   module, `component_type`, `publish_target`, `artifact_source`, version, and
   prebuilt inputs.
5. Confirm the selected branch already contains the required configuration.
   Do not commit or push it as part of this skill.
6. Confirm required repository Secrets exist by name without reading or
   printing their values.

If the workflow is absent or its inputs do not match the requested release,
stop and hand off to `$enter-one-click-publish-config`. Do not patch the
workflow during release execution.

## Dispatch

Dispatch the configured caller workflow, not the reusable `workflow_call`
workflow. Typical command:

```bash
gh workflow run <caller-workflow-file> --ref <branch-or-sha>
```

Only pass `-f` inputs if the caller workflow explicitly declares corresponding
`workflow_dispatch.inputs`. Never invent arbitrary task or repository inputs.

After dispatch, identify the newly created run for the exact workflow and ref,
then watch it to a terminal state. Prefer the run ID/URL returned or resolved by
`gh run list`, followed by:

```bash
gh run watch <run-id> --exit-status
```

Do not dispatch a second run automatically after failure, cancellation, timeout,
or ambiguous run lookup.

## Result handling

Report the GitHub identity, repository, ref, workflow, run URL, conclusion, and
configured publication target. If the run fails, summarize relevant failed-step
logs without exposing secrets. For `all`, report only provider outcomes that
the workflow or Gradle output confirms.
