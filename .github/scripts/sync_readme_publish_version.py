#!/usr/bin/env python3
import argparse
import re
from pathlib import Path

from distribution_config import DistributionConfig


SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")
BUILD_VERSION_RE = re.compile(r"(?m)^\s*version\s*=\s*['\"]([^'\"]+)['\"]")


def read_plugin_version(build_file: Path) -> str:
    content = build_file.read_text(encoding="utf-8")
    match = BUILD_VERSION_RE.search(content)
    if not match:
        config = DistributionConfig.load(build_file.resolve().parents[1] / "gradle.properties")
        return config.version
    return match.group(1)


def sync_readme_publish_version(content: str, version: str, config: DistributionConfig | None = None) -> str:
    if not SEMVER_RE.fullmatch(version):
        raise ValueError(f"Publish plugin version '{version}' must use digits.digits.digits format")

    config = config or DistributionConfig.load()
    marker_artifact = f"{config.plugin_id}.gradle.plugin"
    replacements = (
        (
            rf'classpath\("[A-Za-z0-9_.-]+:{re.escape(config.artifact_id)}:[^"]+"\)',
            f'classpath("{config.group_id}:{config.artifact_id}:{version}")',
        ),
        (
            rf"[A-Za-z0-9_.-]+:{re.escape(config.artifact_id)}:(?:<version>|\d+\.\d+\.\d+)",
            f"{config.group_id}:{config.artifact_id}:{version}",
        ),
        (
            r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+\.gradle\.plugin:(?:<version>|\d+\.\d+\.\d+)",
            f"{config.plugin_id}:{marker_artifact}:{version}",
        ),
        (
            r'id\("[A-Za-z0-9_.-]+"\) version "(?:<version>|\d+\.\d+\.\d+)"',
            f'id("{config.plugin_id}") version "{version}"',
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
