import re
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / "workflows" / "publish.yml"
PR_WORKFLOW = Path(__file__).resolve().parents[1] / "workflows" / "publish-plugin-pr-check.yml"
MATRIX_WORKFLOW = Path(__file__).resolve().parents[1] / "workflows" / "compatibility-matrix.yml"


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
    def test_compatibility_jobs_use_matrix_gradle_for_outer_build(self):
        for workflow in (PR_WORKFLOW, MATRIX_WORKFLOW):
            text = workflow.read_text(encoding="utf-8")
            self.assertIn("gradle-version: ${{ matrix.gradle }}", text)
            self.assertIn("gradle :plugin_base:test", text)
            self.assertIn("-PpluginBaseOnly=true", text)
            self.assertNotIn(
                "./gradlew :plugin_base:test \\\n"
                "            -DtestGradleVersion=\"${{ matrix.gradle }}\"",
                text,
            )

    def test_publish_target_input_supports_three_modes(self):
        text = workflow_text()
        validation = step_block("Validate publish inputs")
        publish = step_block("Publish prepared or project artifacts")

        self.assertIn("publish_target:", text)
        self.assertIn('default: "github_packages"', text)
        self.assertIn("publish_mode:", text)
        self.assertIn("sync_readme:", text)
        self.assertIn("check_only:", text)
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

    def test_publish_step_uses_allowlisted_component_target_task(self):
        publish = step_block("Resolve publish task")

        self.assertIn("library:github_packages", publish)
        self.assertIn("library:central", publish)
        self.assertIn("library:all", publish)
        self.assertIn("plugin:github_packages", publish)
        self.assertIn("PublishPluginRemoteCentralTask", publish)
        publish = step_block("Publish prepared or project artifacts")
        self.assertIn("-PgithubPackagesRepository=${GITHUB_PACKAGES_REPOSITORY}", publish)
        self.assertIn("-PgithubPackagesUrl=${GITHUB_PACKAGES_URL}", publish)
        self.assertIn("-PpublishVersion=${EFFECTIVE_PUBLISH_VERSION}", publish)
        self.assertIn("artifactSource=prebuilt", publish)
        self.assertIn('"${{ inputs.check_only }}" == "true"', publish)
        self.assertIn("-PpublishValidationLevel=structure", publish)
        self.assertIn("-PartifactSource=${ARTIFACT_SOURCE}", publish)
        self.assertIn("-PartifactBundlePath=${ARTIFACT_BUNDLE_PATH}", publish)

    def test_release_publish_can_sync_readme(self):
        text = workflow_text()
        sync = step_block("Sync README for release")
        commit = step_block("Commit README sync")

        self.assertIn("needs.publish.result == 'success'", text)
        self.assertIn("inputs.sync_readme", text)
        self.assertIn("inputs.publish_mode == 'release'", text)
        self.assertIn("inputs.version != ''", text)
        self.assertIn("README_GITHUB_PACKAGES_URL", sync)
        self.assertIn("maven.pkg.github.com", sync)
        self.assertIn("cn.entertech.android:publish:{version}", sync)
        self.assertIn("buildscript", sync)
        self.assertIn("mavenCentral", sync)
        self.assertIn("mavenLocal", sync)
        self.assertIn("git commit -m \"[codex] Sync README publish config to ${PUBLISH_VERSION} [skip ci]\"", commit)
        self.assertIn("git push", commit)

    def test_central_publish_inputs_are_forwarded(self):
        publish = step_block("Publish prepared or project artifacts")

        self.assertIn("-PcentralReleaseType=${CENTRAL_RELEASE_TYPE}", publish)
        self.assertIn("-PcentralNamespace=${CENTRAL_NAMESPACE}", publish)
        self.assertIn("-PcentralPublishingType=${CENTRAL_PUBLISHING_TYPE}", publish)
        self.assertIn("-PpublishVersion=${EFFECTIVE_PUBLISH_VERSION}", publish)

    def test_central_secrets_are_conditionally_required(self):
        text = workflow_text()
        validation = step_block("Validate publish inputs")
        publish = step_block("Publish prepared or project artifacts")

        self.assertIn("MAVEN_CENTRAL_USERNAME:\n        required: false", text)
        self.assertIn("GPG_KEY_CONTENTS:\n        required: false", text)
        self.assertIn("PUBLISH_TARGET\" == \"central\"", publish)
        self.assertIn("PUBLISH_TARGET\" == \"all\"", publish)
        self.assertIn("CENTRAL_USERNAME", publish)
        self.assertIn("SIGNING_PASSWORD", publish)
        self.assertIn('required_central_env=(CENTRAL_USERNAME CENTRAL_PASSWORD)', publish)
        self.assertIn('if [[ "$ARTIFACT_SOURCE" == "project" ]]', publish)
        self.assertIn('required_central_env+=(GPG_KEY_CONTENTS SIGNING_PASSWORD)', publish)

    def test_prebuilt_central_uses_bundle_signatures_without_ci_private_key(self):
        publish = step_block("Publish prepared or project artifacts")

        for name in (
            "GPG_KEY_CONTENTS",
            "SIGNING_KEY_ID",
            "SIGNING_PASSWORD",
            "ORG_GRADLE_PROJECT_signingInMemoryKey",
            "ORG_GRADLE_PROJECT_signingInMemoryKeyId",
            "ORG_GRADLE_PROJECT_signingInMemoryKeyPassword",
        ):
            self.assertRegex(
                publish,
                rf"{name}: \$\{{\{{ !inputs\.check_only && inputs\.artifact_source == 'project'",
            )

    def test_publish_permissions_and_secret_scope_are_minimal(self):
        text = workflow_text()
        publish = step_block("Publish prepared or project artifacts")

        self.assertIn("permissions:\n  contents: read\n  packages: write", text)
        self.assertIn("inputs.publish_target == 'central'", publish)
        self.assertIn("inputs.publish_target == 'all'", publish)
        self.assertIn("inputs.publish_target == 'github_packages'", publish)
        self.assertIn("secrets.GITHUB_PACKAGES_TOKEN || github.token", publish)
        self.assertIn("secrets.GPG_KEY_CONTENTS || ''", publish)
        self.assertIn("secrets.SIGNING_PASSWORD || ''", publish)
        self.assertIn("permissions:\n      contents: write", text)
        self.assertIn("Upload publish manifest", text)
        self.assertIn('if-no-files-found: ignore', text)


if __name__ == "__main__":
    unittest.main()
