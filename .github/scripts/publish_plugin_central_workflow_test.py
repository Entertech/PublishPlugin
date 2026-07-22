import re
import unittest
from pathlib import Path


WORKFLOWS = Path(__file__).resolve().parents[1] / "workflows"
CENTRAL_WORKFLOW = WORKFLOWS / "publish-plugin-central.yml"
PR_CHECK_WORKFLOW = WORKFLOWS / "publish-plugin-pr-check.yml"
SECRET_ENV_NAMES = (
    "CENTRAL_USERNAME",
    "CENTRAL_PASSWORD",
    "GPG_KEY_CONTENTS",
    "SIGNING_KEY_ID",
    "SIGNING_PASSWORD",
)


def workflow_text(path=CENTRAL_WORKFLOW):
    return path.read_text(encoding="utf-8")


def step_block(name, path=CENTRAL_WORKFLOW):
    text = workflow_text(path)
    match = re.search(
        rf"(?ms)^      - name: {re.escape(name)}\n(?P<body>.*?)(?=^      - name: |\Z)",
        text,
    )
    if match is None:
        raise AssertionError(f"Missing workflow step: {name}")
    return match.group("body")


class PublishPluginCentralWorkflowTest(unittest.TestCase):
    def test_local_metadata_validation_does_not_inherit_publish_secrets(self):
        text = workflow_text()
        job_header, _, _ = text.partition("    steps:")
        validation = step_block("Validate local publication metadata")

        for name in SECRET_ENV_NAMES:
            self.assertNotIn(f"{name}:", job_header)
            self.assertNotIn(f"{name}:", validation)

    def test_central_publish_steps_receive_required_publish_env(self):
        central_publish = step_block("Publish to Central staging")
        central_upload = step_block("Create Central Portal deployment")

        for name in SECRET_ENV_NAMES:
            self.assertIn(f"{name}:", central_publish)

        for name in ("CENTRAL_NAMESPACE", "CENTRAL_PUBLISHING_TYPE", "CENTRAL_USERNAME", "CENTRAL_PASSWORD"):
            self.assertIn(f"{name}:", central_upload)

    def test_central_publish_falls_back_when_signing_key_id_is_invalid(self):
        central_publish = step_block("Publish to Central staging")

        self.assertIn("SIGNING_KEY_ID", central_publish)
        self.assertIn("normalize_signing_key_id.py", central_publish)
        self.assertIn("original_signing_key_id", central_publish)
        self.assertIn("falling back to infer it from GPG_KEY_CONTENTS", central_publish)
        self.assertIn("normalized to a Gradle-compatible long key id", central_publish)

    def test_central_publish_makes_pgp_public_key_available_before_upload(self):
        text = workflow_text()
        public_key = step_block("Ensure PGP public key is available")

        self.assertLess(
            text.index("- name: Ensure PGP public key is available"),
            text.index("- name: Publish to Central staging"),
        )
        self.assertLess(
            text.index("- name: Ensure PGP public key is available"),
            text.index("- name: Set up JDK"),
        )
        self.assertIn("steps.release.outputs.publish_required == 'true'", public_key)
        self.assertIn("GPG_KEY_CONTENTS:", public_key)
        self.assertIn("ensure_pgp_public_key_available.py", public_key)
        self.assertIn("keyserver.ubuntu.com", public_key)
        self.assertIn("--stable-lookups 3", public_key)
        self.assertNotIn("CENTRAL_PASSWORD:", public_key)
        self.assertNotIn("SIGNING_PASSWORD:", public_key)

    def test_central_publish_is_gated_by_pre_publish_vs_main_version(self):
        release = step_block("Resolve release version")
        check_tag = step_block("Check release tag")
        publish = step_block("Publish to Central staging")
        merge = step_block("Merge pre_publish to main")

        self.assertIn("MAIN_VERSION=", release)
        self.assertIn("publish_required=true", release)
        self.assertIn("publish_required=false", release)
        self.assertIn("must not be lower than main version", release)

        publish_condition = "steps.release.outputs.publish_required == 'true'"
        self.assertIn(publish_condition, check_tag)
        self.assertIn(publish_condition, publish)
        self.assertIn("steps.release.outputs.publish_required", merge)
        self.assertIn("== \"true\"", merge)
        self.assertIn("does not require Central publish; merging directly", merge)

    def test_release_sync_updates_readme_and_root_build_after_publish(self):
        sync = step_block("Sync README release version")

        self.assertIn("--root-build-file build.gradle.kts", sync)
        self.assertIn("README.md build.gradle.kts", sync)
        self.assertIn("git add plugin_base/build.gradle.kts README.md build.gradle.kts", sync)
        self.assertIn("Update publish plugin usage version", sync)

    def test_ci_mode_publishes_plugin_snapshot_without_release_steps(self):
        text = workflow_text()
        snapshot = step_block("Publish snapshot to Central snapshots")
        resolve = step_block("Resolve snapshot version")

        self.assertIn("publish_mode:", text)
        self.assertIn("github.event.inputs.publish_mode != 'ci'", text)
        self.assertIn("ci_snapshot:", text)
        self.assertIn("github.event.inputs.publish_mode == 'ci'", text)
        self.assertIn('snapshot_version="${base_version}-SNAPSHOT"', resolve)
        self.assertIn("plugin_base/build.gradle.kts", resolve)
        self.assertIn("publishAllPublicationsToCentralSnapshotsRepository", snapshot)
        self.assertIn("-PcentralReleaseType=snapshot", snapshot)
        self.assertNotIn("sync_readme_publish_version.py", snapshot)
        self.assertNotIn("git tag", snapshot)


class PublishPluginPrCheckWorkflowTest(unittest.TestCase):
    def test_version_bump_only_runs_when_plugin_base_changed(self):
        detect_changes = step_block("Detect plugin_base changes", PR_CHECK_WORKFLOW)
        ensure_version = step_block("Ensure publish plugin version", PR_CHECK_WORKFLOW)
        sync_readme = step_block("Sync README publish plugin version", PR_CHECK_WORKFLOW)
        commit_bump = step_block("Commit version bump", PR_CHECK_WORKFLOW)

        self.assertIn("git diff --quiet", detect_changes)
        self.assertIn("FETCH_HEAD...HEAD", detect_changes)
        self.assertIn("plugin_base/", detect_changes)
        self.assertIn("changed=true", detect_changes)
        self.assertIn("changed=false", detect_changes)

        expected_condition = "steps.plugin_base_changes.outputs.changed == 'true'"
        self.assertIn(expected_condition, ensure_version)
        self.assertIn(expected_condition, sync_readme)
        self.assertIn(expected_condition, commit_bump)

    def test_workflow_only_changes_do_not_need_bot_version_commit(self):
        text = workflow_text(PR_CHECK_WORKFLOW)

        self.assertIn("id: plugin_base_changes", text)
        self.assertRegex(
            text,
            r"(?s)- name: Commit version bump.*if:.*steps\.plugin_base_changes\.outputs\.changed == 'true'",
        )


if __name__ == "__main__":
    unittest.main()
