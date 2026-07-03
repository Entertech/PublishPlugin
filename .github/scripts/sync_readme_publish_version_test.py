import unittest

from sync_readme_publish_version import sync_readme_publish_version


class SyncReadmePublishVersionTest(unittest.TestCase):
    def test_updates_publish_plugin_versions_only(self):
        readme = """
classpath("cn.entertech.android:publish:1.2.0")
cn.entertech.android:publish:<version>
cn.entertech.publish:cn.entertech.publish.gradle.plugin:<version>
id("cn.entertech.publish") version "<version>"
PublishInfo {
    version = "1.0.0"
}
"""

        result = sync_readme_publish_version(readme, "1.2.3")

        self.assertIn('classpath("cn.entertech.android:publish:1.2.3")', result)
        self.assertIn("cn.entertech.android:publish:1.2.3", result)
        self.assertIn("cn.entertech.publish:cn.entertech.publish.gradle.plugin:1.2.3", result)
        self.assertIn('id("cn.entertech.publish") version "1.2.3"', result)
        self.assertIn('version = "1.0.0"', result)

    def test_rejects_non_semver(self):
        with self.assertRaisesRegex(ValueError, "digits.digits.digits"):
            sync_readme_publish_version("README", "1.2.3-rc1")


if __name__ == "__main__":
    unittest.main()
