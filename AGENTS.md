# Repository Instructions

## PublishPlugin Codex Skill

The repository copy is the source of truth for the local Codex skill:

- `skills/publishplugin-central-one-click/`

The local runtime skill should be a symlink from:

- `${CODEX_HOME:-$HOME/.codex}/skills/publishplugin-central-one-click`

to the repository directory above.

When editing `skills/publishplugin-central-one-click/**`, do not edit the local
Codex copy directly. Run this after changes to install or verify the symlink:

```bash
./scripts/install-codex-skill.sh
```

Use `./scripts/install-codex-skill.sh --check` when you only need to verify that
the runtime skill still points at this repository.
