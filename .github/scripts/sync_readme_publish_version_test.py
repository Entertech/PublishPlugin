import unittest

from sync_readme_publish_version import github_packages_url_from_repository, sync_readme_publish_version


README_CODE_CONFIG = """
buildscript {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }

    dependencies {
        classpath("cn.entertech.android:publish:1.2.0")
    }
}
"""


class SyncReadmePublishVersionTest(unittest.TestCase):
    def test_updates_publish_plugin_versions_only(self):
        readme = README_CODE_CONFIG + """
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

    def test_keeps_maven_central_code_config_for_central_publish(self):
        result = sync_readme_publish_version(README_CODE_CONFIG, "1.2.3", publish_target="central")

        self.assertIn("        mavenCentral()", result)
        self.assertNotIn("maven.pkg.github.com", result)

    def test_updates_code_config_for_github_packages_publish(self):
        result = sync_readme_publish_version(
            README_CODE_CONFIG,
            "1.2.3",
            publish_target="github_packages",
            github_packages_url="https://maven.pkg.github.com/owner/repo",
        )

        self.assertNotIn("        mavenCentral()", result)
        self.assertIn('url = uri("https://maven.pkg.github.com/owner/repo")', result)
        self.assertIn('username = providers.gradleProperty("gpr.user").orNull', result)
        self.assertIn('password = providers.gradleProperty("gpr.key").orNull', result)
        self.assertIn('classpath("cn.entertech.android:publish:1.2.3")', result)

    def test_updates_code_config_for_all_publish(self):
        result = sync_readme_publish_version(
            README_CODE_CONFIG,
            "1.2.3",
            publish_target="all",
            github_packages_url="https://maven.pkg.github.com/owner/repo",
        )

        self.assertNotIn("        mavenCentral()", result)
        self.assertIn('url = uri("https://maven.pkg.github.com/owner/repo")', result)

    def test_requires_github_packages_url_when_target_includes_github_packages(self):
        with self.assertRaisesRegex(ValueError, "github_packages_url is required"):
            sync_readme_publish_version(README_CODE_CONFIG, "1.2.3", publish_target="github_packages")

    def test_builds_github_packages_url_from_repository(self):
        self.assertEqual(
            "https://maven.pkg.github.com/owner/repo",
            github_packages_url_from_repository("owner/repo"),
        )

    def test_rejects_non_semver(self):
        with self.assertRaisesRegex(ValueError, "digits.digits.digits"):
            sync_readme_publish_version(README_CODE_CONFIG, "1.2.3-rc1")


if __name__ == "__main__":
    unittest.main()
