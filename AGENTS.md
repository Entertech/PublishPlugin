# Repository Instructions

## PublishPlugin Codex Skills

The repository copies are the source of truth for the local Codex skills:

- `skills/publishplugin-one-click-publish/`
- `skills/enter-publish-release/`

The local runtime skills should be symlinks from:

- `${CODEX_HOME:-$HOME/.codex}/skills/publishplugin-one-click-publish`
- `${CODEX_HOME:-$HOME/.codex}/skills/enter-publish-release`

to the corresponding repository directories above. The legacy runtime skill
`${CODEX_HOME:-$HOME/.codex}/skills/publishplugin-local-release` must remain
inactive after migration.

When editing either repository skill, do not edit the local Codex copy directly.
Run this after changes to install or verify both symlinks:

```bash
./scripts/install-codex-skill.sh
```

Use `./scripts/install-codex-skill.sh --check` when you only need to verify that
the runtime skill still points at this repository.
