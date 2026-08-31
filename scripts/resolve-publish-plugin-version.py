#!/usr/bin/env python3
import argparse
import re
from pathlib import Path


SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")
CLASSPATH_RE = re.compile(
    r'classpath\(\s*["\']cn\.entertech\.android:publish:([^"\']+)["\']\s*\)'
)
PLUGIN_DSL_RE = re.compile(
    r'id\(\s*["\']cn\.entertech\.publish["\']\s*\)\s*version\s*["\']([^"\']+)["\']'
)


def extract_readme_plugin_version(content: str) -> str:
    versions = CLASSPATH_RE.findall(content) + PLUGIN_DSL_RE.findall(content)
    if not versions:
        raise ValueError("README does not declare the PublishPlugin dependency version")
    unique_versions = set(versions)
    if len(unique_versions) != 1:
        raise ValueError(
            "README contains inconsistent PublishPlugin dependency versions: "
            + ", ".join(sorted(unique_versions))
        )
    version = versions[0]
    if not SEMVER_RE.fullmatch(version):
        raise ValueError(
            f"README PublishPlugin version '{version}' must use digits.digits.digits format"
        )
    return version


def main() -> None:
    repository_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description="Print the default PublishPlugin version documented by README.md."
    )
    parser.add_argument("--readme", default=str(repository_root / "README.md"))
    args = parser.parse_args()

    readme_path = Path(args.readme)
    try:
        version = extract_readme_plugin_version(readme_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error
    print(version)


if __name__ == "__main__":
    main()
