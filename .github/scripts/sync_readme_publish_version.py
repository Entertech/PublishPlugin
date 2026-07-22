#!/usr/bin/env python3
import argparse
import re
from pathlib import Path


SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")
BUILD_VERSION_RE = re.compile(r"(?m)^\s*version\s*=\s*['\"]([^'\"]+)['\"]")
PLUGIN_REPOSITORY_BLOCK_RE = re.compile(
    r"(?ms)(buildscript\s*\{\s*repositories\s*\{\n)(?P<body>.*?)(\n\s*\}\s*\n\s*\n\s*dependencies\s*\{\s*classpath\(\"cn\.entertech\.android:publish:)"
)
PUBLISH_CLASSPATH_RE = re.compile(r'classpath\("cn\.entertech\.android:publish:[^"]+"\)')


def read_plugin_version(build_file: Path) -> str:
    content = build_file.read_text(encoding="utf-8")
    match = BUILD_VERSION_RE.search(content)
    if not match:
        raise ValueError(f"Cannot find publish plugin version in {build_file}")
    return match.group(1)


def github_packages_url_from_repository(repository: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("GitHub Packages repository must use owner/repo format")
    return f"https://maven.pkg.github.com/{repository}"


def normalize_publish_target(publish_target: str) -> str:
    normalized = (publish_target or "central").strip().lower().replace("-", "_")
    if normalized == "githubpackages":
        normalized = "github_packages"
    if normalized not in {"central", "github_packages", "all"}:
        raise ValueError("publish_target must be central, github_packages, or all")
    return normalized


def sync_readme_code_repository(content: str, publish_target: str, github_packages_url: str = "") -> str:
    normalized_target = normalize_publish_target(publish_target)
    if normalized_target == "central":
        repository_lines = [
            "        google()",
            "        mavenCentral()",
            "        mavenLocal()",
        ]
    else:
        if not github_packages_url:
            raise ValueError("github_packages_url is required when publish_target includes github_packages")
        repository_lines = [
            "        google()",
            "        mavenLocal()",
            "        maven {",
            f"            url = uri(\"{github_packages_url}\")",
            "            credentials {",
            "                username = providers.gradleProperty(\"gpr.user\").orNull",
            "                    ?: System.getenv(\"GITHUB_ACTOR\")",
            "                password = providers.gradleProperty(\"gpr.key\").orNull",
            "                    ?: System.getenv(\"GITHUB_TOKEN\")",
            "            }",
            "        }",
        ]

    def replace(match: re.Match) -> str:
        return match.group(1) + "\n".join(repository_lines) + match.group(3)

    updated, count = PLUGIN_REPOSITORY_BLOCK_RE.subn(replace, content, count=1)
    if count != 1:
        raise ValueError("Cannot find README code configuration buildscript repository block")
    return updated


def sync_readme_publish_version(
    content: str,
    version: str,
    publish_target: str = "central",
    github_packages_url: str = "",
) -> str:
    if not SEMVER_RE.fullmatch(version):
        raise ValueError(f"Publish plugin version '{version}' must use digits.digits.digits format")

    replacements = (
        (
            r'classpath\("cn\.entertech\.android:publish:[^"]+"\)',
            f'classpath("cn.entertech.android:publish:{version}")',
        ),
        (
            r"cn\.entertech\.android:publish:(?:<version>|\d+\.\d+\.\d+)",
            f"cn.entertech.android:publish:{version}",
        ),
        (
            r"cn\.entertech\.publish:cn\.entertech\.publish\.gradle\.plugin:(?:<version>|\d+\.\d+\.\d+)",
            f"cn.entertech.publish:cn.entertech.publish.gradle.plugin:{version}",
        ),
        (
            r'id\("cn\.entertech\.publish"\) version "(?:<version>|\d+\.\d+\.\d+)"',
            f'id("cn.entertech.publish") version "{version}"',
        ),
    )

    next_content = content
    for pattern, replacement in replacements:
        next_content = re.sub(pattern, replacement, next_content)
    next_content = sync_readme_code_repository(next_content, publish_target, github_packages_url)
    return next_content


def sync_root_build_publish_version(content: str, version: str) -> str:
    if not SEMVER_RE.fullmatch(version):
        raise ValueError(f"Publish plugin version '{version}' must use digits.digits.digits format")

    updated, count = PUBLISH_CLASSPATH_RE.subn(
        f'classpath("cn.entertech.android:publish:{version}")',
        content,
        count=1,
    )
    if count != 1:
        raise ValueError("Cannot find root build publish plugin classpath")
    return updated


def main() -> None:
    parser = argparse.ArgumentParser(description="Synchronize README publish plugin version.")
    parser.add_argument("--readme", default="README.md")
    parser.add_argument("--build-file", default="plugin_base/build.gradle.kts")
    parser.add_argument("--root-build-file", default="")
    parser.add_argument("--version")
    parser.add_argument("--publish-target", default="central", choices=("central", "github_packages", "all"))
    parser.add_argument("--github-packages-repository", default="")
    parser.add_argument("--github-packages-url", default="")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    readme_path = Path(args.readme)
    version = args.version or read_plugin_version(Path(args.build_file))
    github_packages_url = args.github_packages_url
    if not github_packages_url and args.github_packages_repository:
        github_packages_url = github_packages_url_from_repository(args.github_packages_repository)
    current = readme_path.read_text(encoding="utf-8")
    updated = sync_readme_publish_version(
        current,
        version,
        publish_target=args.publish_target,
        github_packages_url=github_packages_url,
    )

    if args.check:
        if updated != current:
            raise SystemExit(f"{readme_path} publish plugin docs are not synchronized to {version}")
        print(f"{readme_path} publish plugin docs are synchronized to {version}")
        return

    if updated != current:
        readme_path.write_text(updated, encoding="utf-8")
        print(f"Updated {readme_path} publish plugin docs to {version}")
    else:
        print(f"{readme_path} publish plugin docs already {version}")

    if args.root_build_file:
        root_build_path = Path(args.root_build_file)
        root_current = root_build_path.read_text(encoding="utf-8")
        root_updated = sync_root_build_publish_version(root_current, version)
        if root_updated != root_current:
            root_build_path.write_text(root_updated, encoding="utf-8")
            print(f"Updated {root_build_path} publish plugin classpath to {version}")
        else:
            print(f"{root_build_path} publish plugin classpath already {version}")


if __name__ == "__main__":
    main()
