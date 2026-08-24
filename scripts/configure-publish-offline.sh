#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/configure-publish-offline.sh :module [options] [-- <extra Gradle args>]

Select and optionally run one of PublishPlugin's four public publish tasks.
Local credentials are read from .publish/local.properties or runtime
properties/environment variables; GitHub Actions uses workflow inputs/secrets.

Options:
  --module <path>                 Target Gradle module path, for example :library.
  --component-type <type>         library or plugin.
  --execution <type>              local or github_actions (default: local).
  --publish-target <target>       local, github_packages, central, or all.
  --artifact-source <source>      project or prebuilt (default: project).
  --artifact-bundle-path <path>   Project-relative path for prebuilt bundles.
  --run                           Execute the selected task (otherwise print it).
  -h, --help                      Show this help.

This script never accepts secret values as command arguments and never writes
the root local.properties file.
EOF
}

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

module_path=""
component_type=""
execution="local"
publish_target="local"
artifact_source="project"
artifact_bundle_path=""
run_task="false"
extra_gradle_args=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --module) [ "$#" -ge 2 ] || die "--module requires a value"; module_path="$2"; shift 2 ;;
    --component-type) [ "$#" -ge 2 ] || die "--component-type requires library or plugin"; component_type="$2"; shift 2 ;;
    --execution) [ "$#" -ge 2 ] || die "--execution requires local or github_actions"; execution="$2"; shift 2 ;;
    --publish-target) [ "$#" -ge 2 ] || die "--publish-target requires a value"; publish_target="$2"; shift 2 ;;
    --artifact-source) [ "$#" -ge 2 ] || die "--artifact-source requires project or prebuilt"; artifact_source="$2"; shift 2 ;;
    --artifact-bundle-path) [ "$#" -ge 2 ] || die "--artifact-bundle-path requires a value"; artifact_bundle_path="$2"; shift 2 ;;
    --run) run_task="true"; shift ;;
    --) shift; extra_gradle_args=("$@"); break ;;
    -h|--help) usage; exit 0 ;;
    -*) die "unknown option: $1" ;;
    *) [ -z "$module_path" ] || die "unexpected positional argument: $1"; module_path="$1"; shift ;;
  esac
done

[ -n "$module_path" ] || die "missing module path, for example :library"
[[ "$module_path" == :* ]] || die "module path must start with ':'"
case "$execution" in local|github_actions) ;; *) die "execution must be local or github_actions" ;; esac
case "$component_type" in library|plugin) ;; *) die "component type must be library or plugin" ;; esac
case "$publish_target" in local|github_packages|central|all) ;; *) die "unsupported publish target" ;; esac
case "$artifact_source" in
  project) [ -z "$artifact_bundle_path" ] || die "artifact bundle path is only valid for project" ;;
  prebuilt) [ -n "$artifact_bundle_path" ] || die "artifact bundle path is required for prebuilt"; [[ "$artifact_bundle_path" != /* && "$artifact_bundle_path" != *..* ]] || die "artifact bundle path must stay inside the workspace" ;;
  *) die "artifact source must be project or prebuilt" ;;
esac
if [[ "$execution" == github_actions && "$publish_target" == local ]]; then
  die "GitHub Actions does not publish to Maven Local; choose github_packages, central, or all"
fi

component_name="Library"
[[ "$component_type" == plugin ]] && component_name="Plugin"
case "$publish_target" in
  local) task="Publish${component_name}LocalTask" ;;
  github_packages) task="Publish${component_name}RemoteGithubPackagesTask" ;;
  central) task="Publish${component_name}RemoteCentralTask" ;;
  all) task="Publish${component_name}RemoteAllTask" ;;
esac

gradle_args=("${module_path}:${task}" "--no-daemon")
if [[ "$artifact_source" == prebuilt ]]; then
  gradle_args=("${gradle_args[@]}" "-PartifactSource=prebuilt" "-PartifactBundlePath=${artifact_bundle_path}")
fi
gradle_args=("${gradle_args[@]}" "${extra_gradle_args[@]}")
printf '%q ' ./gradlew "${gradle_args[@]}"
printf '\n'
if [[ "$run_task" == true ]]; then
  [ -f ./gradlew ] || die "run this script from the repository root containing ./gradlew"
  ./gradlew "${gradle_args[@]}"
fi
