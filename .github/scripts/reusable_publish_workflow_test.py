import re
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / "workflows" / "publish.yml"


def workflow_text():
    return WORKFLOW.read_text(encoding="utf-8")


def step_block(name):
    text = workflow_text()
    match = re.search(
        rf"(?ms)^      - name: {re.escape(name)}\n(?P<body>.*?)(?=^      - name: |\Z)",
        text,
    )
    if match is None:
        raise AssertionError(f"Missing workflow step: {name}")
    return match.group("body")


class ReusablePublishWorkflowTest(unittest.TestCase):
    def test_publish_target_input_supports_three_modes(self):
        text = workflow_text()
        validation = step_block("Validate publish inputs")

        self.assertIn("publish_target:", text)
        self.assertIn('default: "github_packages"', text)
        self.assertIn("publish_mode:", text)
        self.assertIn("sync_readme:", text)
        self.assertIn("contents: write", text)
        self.assertIn("central|github_packages|all", validation)
        self.assertIn("publish_target must be central, github_packages, or all", validation)
        self.assertIn("publish_mode must be release or ci", validation)
        self.assertIn("publish_mode=ci only supports publish_target=central", validation)

    def test_publish_version_resolution_supports_ci_snapshots(self):
        resolve = step_block("Resolve publish version")

        self.assertIn('effective_version="${effective_version}-SNAPSHOT"', resolve)
        self.assertIn('central_release_type="snapshot"', resolve)
        self.assertIn('sync_readme_effective=false', resolve)
        self.assertIn("publish_mode=release must not use a -SNAPSHOT version", resolve)
        self.assertIn("EFFECTIVE_PUBLISH_VERSION", resolve)
        self.assertIn("CENTRAL_RELEASE_TYPE", resolve)

    def test_github_packages_publish_step_is_target_gated(self):
        publish = step_block("Publish to GitHub Packages")

        self.assertIn("inputs.publish_target == 'github_packages'", publish)
        self.assertIn("inputs.publish_target == 'all'", publish)
        self.assertIn("-PpublishTarget=github_packages", publish)
        self.assertIn("-PgithubPackagesRepository=${GITHUB_PACKAGES_REPOSITORY}", publish)
        self.assertIn("-PgithubPackagesUrl=${GITHUB_PACKAGES_URL}", publish)
        self.assertIn("-PpublishVersion=${EFFECTIVE_PUBLISH_VERSION}", publish)

    def test_release_publish_can_sync_readme(self):
        sync = step_block("Sync README for release")
        commit = step_block("Commit README sync")

        self.assertIn("inputs.sync_readme", sync)
        self.assertIn("inputs.publish_mode == 'release'", sync)
        self.assertIn("inputs.version != ''", sync)
        self.assertIn("README_GITHUB_PACKAGES_URL", sync)
        self.assertIn("maven.pkg.github.com", sync)
        self.assertIn("cn.entertech.android:publish:{version}", sync)
        self.assertIn("buildscript", sync)
        self.assertIn("mavenCentral", sync)
        self.assertIn("mavenLocal", sync)
        self.assertIn("git commit -m \"[codex] Sync README publish config to ${EFFECTIVE_PUBLISH_VERSION} [skip ci]\"", commit)
        self.assertIn("git push", commit)

    def test_central_publish_step_is_target_gated(self):
        publish = step_block("Publish to Central Portal")

        self.assertIn("inputs.publish_target == 'central'", publish)
        self.assertIn("inputs.publish_target == 'all'", publish)
        self.assertIn("-PpublishTarget=central", publish)
        self.assertIn("-PcentralReleaseType=${CENTRAL_RELEASE_TYPE}", publish)
        self.assertIn("-PcentralNamespace=${CENTRAL_NAMESPACE}", publish)
        self.assertIn("-PcentralPublishingType=${CENTRAL_PUBLISHING_TYPE}", publish)
        self.assertIn("-PpublishVersion=${EFFECTIVE_PUBLISH_VERSION}", publish)

    def test_central_secrets_are_conditionally_required(self):
        text = workflow_text()
        validation = step_block("Validate publish inputs")

        self.assertIn("MAVEN_CENTRAL_USERNAME:\n        required: false", text)
        self.assertIn("GPG_KEY_CONTENTS:\n        required: false", text)
        self.assertIn("PUBLISH_TARGET\" == \"central\"", validation)
        self.assertIn("PUBLISH_TARGET\" == \"all\"", validation)
        self.assertIn("CENTRAL_USERNAME", validation)
        self.assertIn("SIGNING_PASSWORD", validation)


if __name__ == "__main__":
    unittest.main()
