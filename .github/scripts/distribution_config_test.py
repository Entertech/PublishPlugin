import tempfile
import unittest
from pathlib import Path

from distribution_config import DistributionConfig


class DistributionConfigTest(unittest.TestCase):
    def test_reads_publish_identity_from_gradle_properties(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            properties = Path(temp_dir) / "gradle.properties"
            properties.write_text(
                "\n".join(
                    (
                        "publishGroup=io.github.example.android",
                        "publishArtifactId=publish",
                        "publishPluginId=example.android.publish",
                        "publishVersion=1.2.3",
                        "publishCentralNamespace=io.github.example",
                    )
                ),
                encoding="utf-8",
            )

            config = DistributionConfig.load(properties)

            self.assertEqual(config.group_id, "io.github.example.android")
            self.assertEqual(config.artifact_id, "publish")
            self.assertEqual(config.plugin_id, "example.android.publish")
            self.assertEqual(config.version, "1.2.3")
            self.assertEqual(config.central_namespace, "io.github.example")

    def test_requires_core_publish_identity(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            properties = Path(temp_dir) / "gradle.properties"
            properties.write_text(
                "\n".join(
                    (
                        "publishGroup=io.github.example.android",
                        "publishArtifactId=publish",
                    )
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "publishPluginId"):
                DistributionConfig.load(properties)


if __name__ == "__main__":
    unittest.main()
