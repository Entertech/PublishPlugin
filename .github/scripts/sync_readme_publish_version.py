#!/usr/bin/env python3
import argparse
import re
from pathlib import Path


SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")
BUILD_VERSION_RE = re.compile(r"(?m)^\s*version\s*=\s*['\"]([^'\"]+)['\"]")


def read_plugin_version(build_file: Path) -> str:
    content = build_file.read_text(encoding="utf-8")
    match = BUILD_VERSION_RE.search(content)
    if not match:
        raise ValueError(f"Cannot find publish plugin version in {build_file}")
    return match.group(1)


def sync_readme_publish_version(content: str, version: str) -> str:
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
    return next_content


def main() -> None:
    parser = argparse.ArgumentParser(description="Synchronize README publish plugin version.")
    parser.add_argument("--readme", default="README.md")
    parser.add_argument("--build-file", default="plugin_base/build.gradle.kts")
    parser.add_argument("--version")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    readme_path = Path(args.readme)
    version = args.version or read_plugin_version(Path(args.build_file))
    current = readme_path.read_text(encoding="utf-8")
    updated = sync_readme_publish_version(current, version)

    if args.check:
        if updated != current:
            raise SystemExit(f"{readme_path} publish plugin version is not synchronized to {version}")
        print(f"{readme_path} publish plugin version is synchronized to {version}")
        return

    if updated != current:
        readme_path.write_text(updated, encoding="utf-8")
        print(f"Updated {readme_path} publish plugin version to {version}")
    else:
        print(f"{readme_path} publish plugin version already {version}")


if __name__ == "__main__":
    main()
