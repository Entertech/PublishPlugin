import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "resolve-publish-plugin-version.py"
SPEC = importlib.util.spec_from_file_location("resolve_publish_plugin_version", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ResolvePublishPluginVersionTest(unittest.TestCase):
    def test_repository_readme_matches_plugin_base_version(self):
        resolved = subprocess.run(
            ["python3", str(SCRIPT)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        build = (ROOT / "plugin_base" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn(f'val baseVersion = "{resolved}"', build)

    def test_reads_consistent_classpath_and_plugin_dsl_versions(self):
        content = """
        classpath("cn.entertech.android:publish:3.4.5")
        id("cn.entertech.publish") version "3.4.5"
        """
        self.assertEqual("3.4.5", MODULE.extract_readme_plugin_version(content))

    def test_rejects_inconsistent_versions(self):
        content = """
        classpath("cn.entertech.android:publish:3.4.5")
        id("cn.entertech.publish") version "3.4.6"
        """
        with self.assertRaisesRegex(ValueError, "inconsistent"):
            MODULE.extract_readme_plugin_version(content)

    def test_rejects_missing_or_non_semver_version(self):
        with self.assertRaisesRegex(ValueError, "does not declare"):
            MODULE.extract_readme_plugin_version("# no dependency")
        with self.assertRaisesRegex(ValueError, "digits.digits.digits"):
            MODULE.extract_readme_plugin_version(
                'classpath("cn.entertech.android:publish:<version>")'
            )

    def test_cli_can_read_an_explicit_readme(self):
        with tempfile.TemporaryDirectory() as directory:
            readme = Path(directory) / "README.md"
            readme.write_text(
                'classpath("cn.entertech.android:publish:9.8.7")',
                encoding="utf-8",
            )
            result = subprocess.run(
                ["python3", str(SCRIPT), "--readme", str(readme)],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual("9.8.7", result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
