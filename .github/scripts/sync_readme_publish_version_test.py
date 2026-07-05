import unittest

from distribution_config import DistributionConfig
from sync_readme_publish_version import sync_readme_publish_version


class SyncReadmePublishVersionTest(unittest.TestCase):
    def test_updates_publish_plugin_versions_only(self):
        config = DistributionConfig(
            group_id="io.github.example.android",
            artifact_id="publish",
            plugin_id="example.android.publish",
            version="1.2.0",
            central_namespace="io.github.example",
        )
        readme = """
classpath("io.github.example.android:publish:1.2.0")
io.github.example.android:publish:<version>
example.android.publish:example.android.publish.gradle.plugin:<version>
id("example.android.publish") version "<version>"
PublishInfo {
    version = "1.0.0"
}
"""

        result = sync_readme_publish_version(readme, "1.2.3", config)

        self.assertIn('classpath("io.github.example.android:publish:1.2.3")', result)
        self.assertIn("io.github.example.android:publish:1.2.3", result)
        self.assertIn("example.android.publish:example.android.publish.gradle.plugin:1.2.3", result)
        self.assertIn('id("example.android.publish") version "1.2.3"', result)
        self.assertIn('version = "1.0.0"', result)

    def test_rejects_non_semver(self):
        with self.assertRaisesRegex(ValueError, "digits.digits.digits"):
            sync_readme_publish_version("README", "1.2.3-rc1")


if __name__ == "__main__":
    unittest.main()
