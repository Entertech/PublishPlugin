import re
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / "workflows" / "publish-plugin-central.yml"
SECRET_ENV_NAMES = (
    "CENTRAL_USERNAME",
    "CENTRAL_PASSWORD",
    "GPG_KEY_CONTENTS",
    "SIGNING_KEY_ID",
    "SIGNING_PASSWORD",
)


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


if __name__ == "__main__":
    unittest.main()
