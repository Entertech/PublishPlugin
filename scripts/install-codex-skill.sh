#!/usr/bin/env bash
set -euo pipefail

skill_name="publishplugin-one-click-publish"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
source_dir="$repo_root/skills/$skill_name"
codex_home="${CODEX_HOME:-$HOME/.codex}"
target_dir="$codex_home/skills/$skill_name"

mode="install"
force="false"

usage() {
  cat <<EOF
Usage: $0 [--check] [--force]

Installs the local Codex runtime skill as a symlink to:
  $source_dir

Options:
  --check   Verify the symlink without changing files.
  --force   Back up any existing target before creating the symlink.
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --check)
      mode="check"
      ;;
    --force)
      force="true"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown argument: $1"
      ;;
  esac
  shift
done

[ -d "$source_dir" ] || die "missing repository skill directory: $source_dir"
[ -f "$source_dir/SKILL.md" ] || die "missing repository SKILL.md: $source_dir/SKILL.md"

link_target=""
if [ -L "$target_dir" ]; then
  link_target="$(readlink "$target_dir")"
fi

if [ "$mode" = "check" ]; then
  if [ "$link_target" = "$source_dir" ]; then
    printf 'OK: %s -> %s\n' "$target_dir" "$source_dir"
    exit 0
  fi

  if [ -n "$link_target" ]; then
    die "$target_dir points to $link_target, expected $source_dir"
  fi

  die "$target_dir is not linked to $source_dir"
fi

mkdir -p "$codex_home/skills"

if [ "$link_target" = "$source_dir" ]; then
  printf 'Already linked: %s -> %s\n' "$target_dir" "$source_dir"
  exit 0
fi

if [ -e "$target_dir" ] || [ -L "$target_dir" ]; then
  if [ "$force" != "true" ] && [ ! -L "$target_dir" ]; then
    if ! diff -qr "$source_dir" "$target_dir" >/dev/null; then
      die "existing skill differs from repository copy; review it or rerun with --force"
    fi
  fi

  backup_dir="$target_dir.backup-$(date +%Y%m%d%H%M%S)"
  mv "$target_dir" "$backup_dir"
  printf 'Backed up existing skill to %s\n' "$backup_dir"
fi

ln -s "$source_dir" "$target_dir"
printf 'Linked: %s -> %s\n' "$target_dir" "$source_dir"
