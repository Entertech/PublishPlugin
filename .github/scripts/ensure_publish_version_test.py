import unittest

from ensure_publish_version import ensure_publish_version


class EnsurePublishVersionTest(unittest.TestCase):
    def test_bumps_patch_when_head_matches_base(self):
        head = 'version = "1.2.9"\n'
        base = "version = '1.2.9'\n"

        result = ensure_publish_version(head, base)

        self.assertTrue(result.changed)
        self.assertEqual("1.2.10", result.version)
        self.assertEqual('version = "1.2.10"\n', result.content)

    def test_keeps_valid_manual_version_when_greater_than_base(self):
        head = 'version = "1.3.0"\n'
        base = "version = '1.2.9'\n"

        result = ensure_publish_version(head, base)

        self.assertFalse(result.changed)
        self.assertEqual("1.3.0", result.version)
        self.assertEqual(head, result.content)

    def test_rejects_non_numeric_head_version(self):
        with self.assertRaisesRegex(ValueError, "must use digits.digits.digits"):
            ensure_publish_version('version = "1.2.0-local"\n', "version = '1.0.5'\n")

    def test_rejects_head_version_not_greater_than_base_after_manual_change(self):
        with self.assertRaisesRegex(ValueError, "must be greater than base version"):
            ensure_publish_version('version = "1.0.4"\n', "version = '1.0.5'\n")

    def test_rejects_missing_version(self):
        with self.assertRaisesRegex(ValueError, "Cannot find publish plugin version"):
            ensure_publish_version("plugins {}\n", "version = '1.0.5'\n")


if __name__ == "__main__":
    unittest.main()
