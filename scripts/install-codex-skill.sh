#!/usr/bin/env bash
set -euo pipefail

skill_names=(
  "publishplugin-one-click-publish"
  "enter-publish-release"
)
legacy_skill_name="publishplugin-local-release"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
codex_home="${CODEX_HOME:-$HOME/.codex}"
runtime_skills_dir="$codex_home/skills"
backup_root="$codex_home/skill-backups"

mode="install"
force="false"

usage() {
  cat <<EOF
Usage: $0 [--check] [--force]

Installs these repository skills as runtime symlinks:
  publishplugin-one-click-publish
  enter-publish-release

The legacy publishplugin-local-release runtime skill is retired after the new
release skill is installed.

Options:
  --check   Verify both symlinks and confirm the legacy skill is inactive.
  --force   Back up a differing existing target before linking.
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --check) mode="check" ;;
    --force) force="true" ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
  shift
done

for skill_name in "${skill_names[@]}"; do
  source_dir="$repo_root/skills/$skill_name"
  [ -d "$source_dir" ] || die "missing repository skill directory: $source_dir"
  [ -f "$source_dir/SKILL.md" ] || die "missing repository SKILL.md: $source_dir/SKILL.md"
done

if [ "$mode" = "check" ]; then
  for skill_name in "${skill_names[@]}"; do
    source_dir="$repo_root/skills/$skill_name"
    target_dir="$runtime_skills_dir/$skill_name"
    link_target=""
    if [ -L "$target_dir" ]; then
      link_target="$(readlink "$target_dir")"
    fi
    [ "$link_target" = "$source_dir" ] || die "$target_dir is not linked to $source_dir"
    printf 'OK: %s -> %s\n' "$target_dir" "$source_dir"
  done
  legacy_target="$runtime_skills_dir/$legacy_skill_name"
  [ ! -e "$legacy_target" ] && [ ! -L "$legacy_target" ] || \
    die "legacy runtime skill is still active: $legacy_target"
  printf 'OK: legacy runtime skill is inactive: %s\n' "$legacy_target"
  exit 0
fi

mkdir -p "$runtime_skills_dir"

for skill_name in "${skill_names[@]}"; do
  source_dir="$repo_root/skills/$skill_name"
  target_dir="$runtime_skills_dir/$skill_name"
  link_target=""
  if [ -L "$target_dir" ]; then
    link_target="$(readlink "$target_dir")"
  fi

  if [ "$link_target" = "$source_dir" ]; then
    printf 'Already linked: %s -> %s\n' "$target_dir" "$source_dir"
    continue
  fi

  if [ -e "$target_dir" ] || [ -L "$target_dir" ]; then
    if [ "$force" != "true" ] && [ ! -L "$target_dir" ]; then
      if ! diff -qr "$source_dir" "$target_dir" >/dev/null; then
        die "existing skill differs from repository copy; review it or rerun with --force: $target_dir"
      fi
    fi
    mkdir -p "$backup_root"
    backup_dir="$backup_root/$skill_name-$(date +%Y%m%d%H%M%S)"
    mv "$target_dir" "$backup_dir"
    printf 'Backed up existing skill to %s\n' "$backup_dir"
  fi

  ln -s "$source_dir" "$target_dir"
  printf 'Linked: %s -> %s\n' "$target_dir" "$source_dir"
done

legacy_target="$runtime_skills_dir/$legacy_skill_name"
if [ -e "$legacy_target" ] || [ -L "$legacy_target" ]; then
  mkdir -p "$backup_root"
  legacy_backup="$backup_root/$legacy_skill_name-$(date +%Y%m%d%H%M%S)"
  mv "$legacy_target" "$legacy_backup"
  printf 'Retired legacy skill to %s\n' "$legacy_backup"
else
  printf 'Legacy skill already inactive: %s\n' "$legacy_target"
fi
