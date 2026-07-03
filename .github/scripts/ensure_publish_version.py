#!/usr/bin/env python3
import argparse
import os
import re
from dataclasses import dataclass
from pathlib import Path


VERSION_LINE_RE = re.compile(r"(?m)^(\s*version\s*=\s*)(['\"])([^'\"]+)(\2)")
SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")


@dataclass(frozen=True)
class VersionResult:
    content: str
    base_version: str
    version: str
    changed: bool


def extract_version(content: str) -> str:
    match = VERSION_LINE_RE.search(content)
    if not match:
        raise ValueError("Cannot find publish plugin version")
    return match.group(3)


def parse_semver(version: str) -> tuple[int, int, int]:
    if not SEMVER_RE.fullmatch(version):
        raise ValueError(f"Publish plugin version '{version}' must use digits.digits.digits format")
    major, minor, patch = version.split(".")
    return int(major), int(minor), int(patch)


def bump_patch(version: str) -> str:
    major, minor, patch = parse_semver(version)
    return f"{major}.{minor}.{patch + 1}"


def replace_version(content: str, version: str) -> str:
    match = VERSION_LINE_RE.search(content)
    if not match:
        raise ValueError("Cannot find publish plugin version")
    return VERSION_LINE_RE.sub(rf"\g<1>\g<2>{version}\g<4>", content, count=1)


def ensure_publish_version(head_content: str, base_content: str) -> VersionResult:
    base_version = extract_version(base_content)
    head_version = extract_version(head_content)

    base_tuple = parse_semver(base_version)
    changed = False
    next_content = head_content
    final_version = head_version

    if head_version == base_version:
        final_version = bump_patch(base_version)
        next_content = replace_version(head_content, final_version)
        changed = True

    head_tuple = parse_semver(final_version)
    if head_tuple <= base_tuple:
        raise ValueError(
            f"Publish plugin version '{final_version}' must be greater than main version '{base_version}'"
        )

    return VersionResult(
        content=next_content,
        base_version=base_version,
        version=final_version,
        changed=changed,
    )


def write_github_output(result: VersionResult) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if not output_path:
        return
    with open(output_path, "a", encoding="utf-8") as output:
        output.write(f"changed={str(result.changed).lower()}\n")
        output.write(f"base_version={result.base_version}\n")
        output.write(f"version={result.version}\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Ensure publish plugin version is valid for a PR.")
    parser.add_argument("--head-file", required=True)
    parser.add_argument("--base-file", required=True)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()

    head_path = Path(args.head_file)
    base_path = Path(args.base_file)
    result = ensure_publish_version(
        head_content=head_path.read_text(encoding="utf-8"),
        base_content=base_path.read_text(encoding="utf-8"),
    )

    if args.write and result.changed:
        head_path.write_text(result.content, encoding="utf-8")

    write_github_output(result)
    if result.changed:
        print(f"Bumped publish plugin version from {result.base_version} to {result.version}")
    else:
        print(f"Publish plugin version {result.version} is greater than main version {result.base_version}")


if __name__ == "__main__":
    main()
