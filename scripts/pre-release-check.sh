#!/usr/bin/env bash

set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
static_only=false
require_clean=false
base_ref=""
base_file=""
temporary_root=""

usage() {
  cat <<'EOF'
Usage: ./scripts/pre-release-check.sh [options]

Run PublishPlugin's non-destructive pre-release checks.

Options:
  --static-only       Run source, version, documentation, and script checks only.
  --require-clean     Fail when the Git worktree contains tracked or untracked changes.
  --base-ref REF      Require the plugin version to be greater than plugin_base on REF.
  --base-file PATH    Require the plugin version to be greater than the supplied build file.
  -h, --help          Show this help.

--base-ref and --base-file are mutually exclusive. The full mode publishes only to an
isolated temporary Maven Local repository; it never uploads remote artifacts or creates tags.
EOF
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

run_case() {
  local case_id="$1"
  local description="$2"
  shift 2
  echo
  echo "==> ${case_id}: ${description}"
  "$@"
}

cleanup() {
  if [[ -n "$temporary_root" && -d "$temporary_root" ]]; then
    rm -rf -- "$temporary_root"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --static-only)
      static_only=true
      shift
      ;;
    --require-clean)
      require_clean=true
      shift
      ;;
    --base-ref)
      [[ $# -ge 2 ]] || fail "--base-ref requires a value"
      base_ref="$2"
      shift 2
      ;;
    --base-file)
      [[ $# -ge 2 ]] || fail "--base-file requires a value"
      base_file="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1"
      ;;
  esac
done

[[ -z "$base_ref" || -z "$base_file" ]] || fail "--base-ref and --base-file cannot be used together"

for command_name in git python3; do
  command -v "$command_name" >/dev/null 2>&1 || fail "required command not found: $command_name"
done
if [[ "$static_only" == false ]]; then
  [[ -x "$repository_root/gradlew" ]] || fail "Gradle wrapper is missing or not executable"
fi

cd "$repository_root"

if [[ "$require_clean" == true && -n "$(git status --porcelain)" ]]; then
  git status --short >&2
  fail "worktree must be clean"
fi

run_case PRE-001 "Git diff has no whitespace errors" git diff --check
run_case PRE-002 "Staged Git diff has no whitespace errors" git diff --cached --check

while IFS= read -r tracked_file; do
  case "$tracked_file" in
    local.properties|*/local.properties|*.hprof|*/__pycache__/*|*.jks|*.keystore|*.p12|*.pfx|*private*.asc|*secret*.asc|*private*.pem|*private*.key)
      fail "sensitive or generated file is tracked: $tracked_file"
      ;;
  esac
done < <(git ls-files)
echo "PRE-003: no known sensitive/generated file type is tracked"

if [[ -n "$base_ref" ]]; then
  temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/publishplugin-pre-release.XXXXXX")"
  trap cleanup EXIT
  base_file="$temporary_root/base-plugin-build.gradle.kts"
  if ! git show "${base_ref}:plugin_base/build.gradle.kts" > "$base_file" 2>/dev/null; then
    git show "${base_ref}:plugin_base/build.gradle" > "$base_file" 2>/dev/null ||
      fail "cannot read plugin_base build file from $base_ref"
  fi
fi

if [[ -n "$base_file" ]]; then
  [[ -f "$base_file" ]] || fail "base build file does not exist: $base_file"
  run_case PRE-004 "Plugin version is valid and greater than the release base" \
    python3 .github/scripts/ensure_publish_version.py \
      --head-file plugin_base/build.gradle.kts \
      --base-file "$base_file"
else
  run_case PRE-004 "Plugin version uses normalized semantic versioning" \
    python3 .github/scripts/ensure_publish_version.py \
      --head-file plugin_base/build.gradle.kts \
      --normalize-only
fi

run_case PRE-005 "README dependency coordinates match the plugin version" \
  python3 .github/scripts/sync_readme_publish_version.py --check

while IFS= read -r test_script; do
  run_case PRE-006 "Python regression: $test_script" python3 "$test_script"
done < <(find .github/scripts -maxdepth 1 -name '*_test.py' -print | sort)

run_case PRE-007 "Publishing documentation facts are consistent" \
  python3 .github/scripts/verify_publishplugin_docs.py

runtime_skills_dir="${CODEX_HOME:-$HOME/.codex}/skills"
if [[ -d "$runtime_skills_dir" ]]; then
  run_case PRE-008 "Runtime publishing skills point to this repository" \
    ./scripts/install-codex-skill.sh --check
else
  echo "PRE-008: SKIP - Codex runtime skills directory is unavailable"
fi

if [[ "$static_only" == true ]]; then
  echo
  echo "Pre-release static checks passed. Gradle build and artifact checks were skipped by request."
  exit 0
fi

if [[ -z "$temporary_root" ]]; then
  temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/publishplugin-pre-release.XXXXXX")"
  trap cleanup EXIT
fi
isolated_maven_repository="$temporary_root/m2"

run_case PRE-009 "Unit, functional TestKit, plugin validation, and build tasks pass" \
  ./gradlew \
    :plugin_base:test \
    :plugin_base:validatePlugins \
    :plugin_base:build \
    -PpluginBaseOnly=true \
    --rerun-tasks \
    --no-daemon \
    --stacktrace

run_case PRE-010 "Plugin implementation and marker publish to isolated Maven Local" \
  ./gradlew \
    :plugin_base:publishToMavenLocal \
    -PpluginBaseOnly=true \
    "-Dmaven.repo.local=$isolated_maven_repository" \
    --no-daemon \
    --stacktrace

run_case PRE-011 "POM, marker dependency, metadata, sources, and javadoc artifacts are valid" \
  python3 .github/scripts/validate_publish_plugin_publications.py \
    --repository "$isolated_maven_repository"

plugin_version="$(python3 - <<'PY'
import re
from pathlib import Path

content = Path("plugin_base/build.gradle.kts").read_text(encoding="utf-8")
match = re.search(r'(?m)^\s*val\s+baseVersion\s*=\s*["\x27]([^"\x27]+)["\x27]', content)
if not match:
    raise SystemExit("Cannot find plugin baseVersion")
print(match.group(1) if match.group(1).endswith("-local") else match.group(1) + "-local")
PY
)"
consumer_root="$temporary_root/consumer"
mkdir -p "$consumer_root"
cat > "$consumer_root/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        maven { url = uri("$isolated_maven_repository") }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "publishplugin-pre-release-consumer"
EOF
cat > "$consumer_root/build.gradle.kts" <<EOF
plugins {
    id("java-gradle-plugin")
    id("cn.entertech.publish") version "$plugin_version"
}

PublishInfo {
    groupId = "com.example"
    artifactId = "publishplugin-consumer-smoke"
    version = "1.0.0"
    pluginId = "com.example.publishplugin.consumer.smoke"
    implementationClass = "com.example.PublishPluginConsumerSmoke"
}

tasks.register("verifyPluginResolution") {
    doLast {
        check(project.extensions.findByName("PublishInfo") != null) {
            "PublishInfo extension was not registered"
        }
        println("Resolved cn.entertech.publish:$plugin_version from isolated Maven Local")
    }
}
EOF

run_case PRE-012 "A clean consumer resolves and applies the published plugin marker" \
  ./gradlew \
    --project-dir "$consumer_root" \
    verifyPluginResolution \
    "-Dmaven.repo.local=$isolated_maven_repository" \
    --no-daemon \
    --stacktrace

echo
echo "All non-destructive pre-release checks passed."
echo "Remote repository upload, tag creation, and release merge were not performed."
