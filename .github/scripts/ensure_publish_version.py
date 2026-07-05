#!/usr/bin/env python3
import argparse
import os
import re
from dataclasses import dataclass
from pathlib import Path


VERSION_LINE_RE = re.compile(r"(?m)^(\s*version\s*=\s*)(['\"])([^'\"]+)(\2)")
PUBLISH_VERSION_PROPERTY_RE = re.compile(r"(?m)^(\s*publishVersion\s*[=:]\s*)([^\s#]+)")
SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")
NORMALIZABLE_SEMVER_RE = re.compile(r"^\s*v?(\d+)\.(\d+)\.(\d+)(?:[^\d].*)?\s*$")


@dataclass(frozen=True)
class VersionResult:
    content: str
    base_version: str
    version: str
    changed: bool


def extract_version(content: str) -> str:
    match = VERSION_LINE_RE.search(content)
    if match:
        return match.group(3)
    match = PUBLISH_VERSION_PROPERTY_RE.search(content)
    if match:
        return match.group(2).strip()
    raise ValueError("Cannot find publish plugin version")


def parse_semver(version: str) -> tuple[int, int, int]:
    if not SEMVER_RE.fullmatch(version):
        raise ValueError(f"Publish plugin version '{version}' must use digits.digits.digits format")
    major, minor, patch = version.split(".")
    return int(major), int(minor), int(patch)


def normalize_semver(version: str) -> str:
    if SEMVER_RE.fullmatch(version):
        return version
    match = NORMALIZABLE_SEMVER_RE.fullmatch(version)
    if not match:
        raise ValueError(f"Publish plugin version '{version}' must use digits.digits.digits format")
    return ".".join(match.groups())


def bump_patch(version: str) -> str:
    major, minor, patch = parse_semver(version)
    return f"{major}.{minor}.{patch + 1}"


def replace_version(content: str, version: str) -> str:
    match = VERSION_LINE_RE.search(content)
    if match:
        return VERSION_LINE_RE.sub(rf"\g<1>\g<2>{version}\g<4>", content, count=1)
    match = PUBLISH_VERSION_PROPERTY_RE.search(content)
    if match:
        return PUBLISH_VERSION_PROPERTY_RE.sub(rf"\g<1>{version}", content, count=1)
    raise ValueError("Cannot find publish plugin version")


def ensure_publish_version(head_content: str, base_content: str) -> VersionResult:
    base_version = normalize_semver(extract_version(base_content))
    head_version = extract_version(head_content)
    normalized_head_version = normalize_semver(head_version)

    base_tuple = parse_semver(base_version)
    changed = normalized_head_version != head_version
    next_content = head_content
    final_version = normalized_head_version

    if changed:
        next_content = replace_version(next_content, final_version)

    if final_version == base_version:
        final_version = bump_patch(base_version)
        next_content = replace_version(next_content, final_version)
        changed = True

    head_tuple = parse_semver(final_version)
    if head_tuple <= base_tuple:
        raise ValueError(
            f"Publish plugin version '{final_version}' must be greater than base version '{base_version}'"
        )

    return VersionResult(
        content=next_content,
        base_version=base_version,
        version=final_version,
        changed=changed,
    )


def normalize_publish_version(content: str) -> VersionResult:
    head_version = extract_version(content)
    final_version = normalize_semver(head_version)
    changed = final_version != head_version
    next_content = replace_version(content, final_version) if changed else content

    return VersionResult(
        content=next_content,
        base_version="",
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
    parser.add_argument("--base-file")
    parser.add_argument("--normalize-only", action="store_true")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()

    head_path = Path(args.head_file)
    head_content = head_path.read_text(encoding="utf-8")
    if args.normalize_only:
        result = normalize_publish_version(head_content)
    else:
        if not args.base_file:
            raise ValueError("--base-file is required unless --normalize-only is used")
        base_path = Path(args.base_file)
        result = ensure_publish_version(
            head_content=head_content,
            base_content=base_path.read_text(encoding="utf-8"),
        )

    if args.write and result.changed:
        head_path.write_text(result.content, encoding="utf-8")

    write_github_output(result)
    if args.normalize_only and result.changed:
        print(f"Normalized publish plugin version to {result.version}")
    elif args.normalize_only:
        print(f"Publish plugin version already normalized as {result.version}")
    elif result.changed:
        print(f"Bumped publish plugin version from {result.base_version} to {result.version}")
    else:
        print(f"Publish plugin version {result.version} is greater than base version {result.base_version}")


if __name__ == "__main__":
    main()
