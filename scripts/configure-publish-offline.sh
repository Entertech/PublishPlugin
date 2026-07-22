#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/configure-publish-offline.sh :module [options] [-- <extra Gradle args>]

Runs PublishPlugin's one-click publish Gradle tasks from a repository root
without requiring an AI assistant. GitHub Packages is the default generated
workflow target; set publish.publishTarget=central or all when Central
Portal publishing is required.

Options:
  --module <path>                 Target Gradle module path, for example :library.
  --config <path>                 Publish config file. Defaults to local.properties.
  --generate-only                 Only run generatePublishConfig.
  --configure-only                Only run configurePublish.
  --skip-generate                 Alias for --configure-only.
  --overwrite-publish-config      Pass -PoverwritePublishConfig=true.
  --publish-target <target>       Override generated workflow target: github_packages, central, or all.
  --github-actions <true|false>   Override workflow generation.
  --workflow-path <path>          Override generated workflow path.
  --workflow-uses <ref>           Override reusable workflow reference.
  --overwrite-workflow            Allow replacing an existing non-generated workflow file.
  -h, --help                      Show this help.

Examples:
  scripts/configure-publish-offline.sh :library --generate-only
  scripts/configure-publish-offline.sh :library --configure-only
  scripts/configure-publish-offline.sh :library --publish-target central -- --stacktrace

Sensitive values must stay in an ignored/untracked publish config file or in
GitHub repository secrets. This script does not accept secret values as command
arguments.
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

module_path=""
run_generate="true"
run_configure="true"
gradle_props=()
extra_gradle_args=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --module)
      [ "$#" -ge 2 ] || die "--module requires a value"
      module_path="$2"
      shift 2
      ;;
    --config)
      [ "$#" -ge 2 ] || die "--config requires a value"
      gradle_props+=("-PpublishConfig=$2")
      shift 2
      ;;
    --generate-only)
      run_generate="true"
      run_configure="false"
      shift
      ;;
    --configure-only|--skip-generate)
      run_generate="false"
      run_configure="true"
      shift
      ;;
    --overwrite-publish-config)
      gradle_props+=("-PoverwritePublishConfig=true")
      shift
      ;;
    --publish-target)
      [ "$#" -ge 2 ] || die "--publish-target requires github_packages, central, or all"
      case "$2" in
        github_packages|central|all) gradle_props+=("-PpublishTarget=$2") ;;
        *) die "--publish-target only supports github_packages, central, or all" ;;
      esac
      shift 2
      ;;
    --github-actions)
      [ "$#" -ge 2 ] || die "--github-actions requires true or false"
      case "$2" in
        true|false) gradle_props+=("-PgithubActions=$2") ;;
        *) die "--github-actions only supports true or false" ;;
      esac
      shift 2
      ;;
    --workflow-path)
      [ "$#" -ge 2 ] || die "--workflow-path requires a value"
      gradle_props+=("-PworkflowPath=$2")
      shift 2
      ;;
    --workflow-uses)
      [ "$#" -ge 2 ] || die "--workflow-uses requires a value"
      gradle_props+=("-PworkflowUses=$2")
      shift 2
      ;;
    --overwrite-workflow)
      gradle_props+=("-PoverwriteWorkflow=true")
      shift
      ;;
    --)
      shift
      extra_gradle_args+=("$@")
      break
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      die "unknown option: $1"
      ;;
    *)
      if [ -n "$module_path" ]; then
        die "unexpected positional argument: $1"
      fi
      module_path="$1"
      shift
      ;;
  esac
done

[ -n "$module_path" ] || die "missing module path, for example :library"
case "$module_path" in
  :*) ;;
  *) die "module path must start with ':'" ;;
esac

[ -f "./gradlew" ] || die "run this script from the target repository root containing ./gradlew"
[ -x "./gradlew" ] || chmod +x ./gradlew

task_path() {
  local task_name="$1"
  if [ "$module_path" = ":" ]; then
    printf ':%s\n' "$task_name"
  else
    printf '%s:%s\n' "$module_path" "$task_name"
  fi
}

if [ "$run_generate" = "true" ]; then
  ./gradlew "$(task_path generatePublishConfig)" "${gradle_props[@]}" "${extra_gradle_args[@]}"
fi

if [ "$run_configure" = "true" ]; then
  ./gradlew "$(task_path configurePublish)" "${gradle_props[@]}" "${extra_gradle_args[@]}"
fi
