#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PLUGIN_ID = "cn.entertech.publish"
GROUP_ID = "cn.entertech.android"
ARTIFACT_ID = "publish"


def fail(message: str) -> None:
    raise AssertionError(message)


def text(element: ET.Element, name: str) -> str:
    child = element.find(f"{{*}}{name}")
    return "" if child is None or child.text is None else child.text.strip()


def require_text(element: ET.Element, name: str, context: str) -> str:
    value = text(element, name)
    if not value:
        fail(f"{context} missing <{name}>")
    return value


def load_pom(path: Path) -> ET.Element:
    if not path.exists():
        fail(f"Missing POM: {path}")
    return ET.parse(path).getroot()


def assert_coordinates(pom: ET.Element, group: str, artifact: str, version_pattern: str, context: str) -> str:
    actual_group = require_text(pom, "groupId", context)
    actual_artifact = require_text(pom, "artifactId", context)
    actual_version = require_text(pom, "version", context)
    if actual_group != group:
        fail(f"{context} groupId expected {group}, got {actual_group}")
    if actual_artifact != artifact:
        fail(f"{context} artifactId expected {artifact}, got {actual_artifact}")
    if not re.fullmatch(version_pattern, actual_version):
        fail(f"{context} version must match {version_pattern}, got {actual_version}")
    return actual_version


def assert_central_metadata(pom: ET.Element, context: str) -> None:
    for name in ("name", "description", "url"):
        require_text(pom, name, context)

    if pom.find("{*}licenses/{*}license/{*}name") is None:
        fail(f"{context} missing license")
    if pom.find("{*}developers/{*}developer/{*}email") is None:
        fail(f"{context} missing developer email")
    if pom.find("{*}developers/{*}developer/{*}organization") is None:
        fail(f"{context} missing developer organization")
    if pom.find("{*}scm/{*}connection") is None:
        fail(f"{context} missing SCM connection")
    if pom.find("{*}scm/{*}developerConnection") is None:
        fail(f"{context} missing SCM developerConnection")


def assert_marker_dependency(marker_pom: ET.Element, version: str) -> None:
    dependencies = marker_pom.findall("{*}dependencies/{*}dependency")
    for dependency in dependencies:
        if (
            text(dependency, "groupId") == GROUP_ID
            and text(dependency, "artifactId") == ARTIFACT_ID
            and text(dependency, "version") == version
        ):
            return
    fail(f"Marker POM must depend on {GROUP_ID}:{ARTIFACT_ID}:{version}")


def assert_plugin_descriptor() -> None:
    descriptor = ROOT / "plugin_base/build/resources/main/META-INF/gradle-plugins" / f"{PLUGIN_ID}.properties"
    if not descriptor.exists():
        fail(f"Missing plugin descriptor: {descriptor}")
    content = descriptor.read_text(encoding="utf-8")
    if "implementation-class=custom.android.plugin.PublishPlugin" not in content:
        fail("Plugin descriptor points to the wrong implementation class")

    old_descriptor = ROOT / "plugin_base/build/resources/main/META-INF/gradle-plugins/custom.android.plugin.properties"
    if old_descriptor.exists():
        fail("Old custom.android.plugin descriptor must not be generated")


def main() -> None:
    publications = ROOT / "plugin_base/build/publications"
    plugin_pom = load_pom(publications / "pluginMaven/pom-default.xml")
    marker_pom = load_pom(publications / "publishPluginMarkerMaven/pom-default.xml")

    version = assert_coordinates(plugin_pom, GROUP_ID, ARTIFACT_ID, r"\d+\.\d+\.\d+", "pluginMaven")
    assert_central_metadata(plugin_pom, "pluginMaven")

    assert_coordinates(
        marker_pom,
        PLUGIN_ID,
        f"{PLUGIN_ID}.gradle.plugin",
        re.escape(version),
        "publishPluginMarkerMaven",
    )
    assert_central_metadata(marker_pom, "publishPluginMarkerMaven")
    assert_marker_dependency(marker_pom, version)
    assert_plugin_descriptor()
    print("publish plugin publication metadata ok")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
