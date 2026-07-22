import unittest

from normalize_signing_key_id import normalize_signing_key_id, shell_exports


class NormalizeSigningKeyIdTest(unittest.TestCase):
    def test_keeps_short_and_long_hex_key_ids(self):
        self.assertEqual(normalize_signing_key_id("00B5050F"), "00B5050F")
        self.assertEqual(normalize_signing_key_id("0x00B5050F"), "0x00B5050F")
        self.assertEqual(normalize_signing_key_id("2BA16C9B594CE0E6"), "2BA16C9B594CE0E6")

    def test_converts_fingerprint_to_long_key_id(self):
        self.assertEqual(
            normalize_signing_key_id("672F6476219EAB8C55C127A32BA16C9B594CE0E6"),
            "2BA16C9B594CE0E6",
        )

    def test_rejects_non_hex_values(self):
        self.assertEqual(normalize_signing_key_id("Developer <dev@example.com>"), "")
        self.assertEqual(normalize_signing_key_id("not-a-key-id"), "")

    def test_shell_exports_clear_all_gradle_key_id_aliases_for_invalid_value(self):
        output = shell_exports("not-a-key-id")

        self.assertIn("unset SIGNING_KEY_ID", output)
        self.assertIn("unset SIGNING_IN_MEMORY_KEY_ID", output)
        self.assertIn("unset ORG_GRADLE_PROJECT_signingInMemoryKeyId", output)

    def test_shell_exports_sets_gradle_alias_for_fingerprint(self):
        output = shell_exports("672F6476219EAB8C55C127A32BA16C9B594CE0E6")

        self.assertIn("export SIGNING_KEY_ID='2BA16C9B594CE0E6'", output)
        self.assertIn("export ORG_GRADLE_PROJECT_signingInMemoryKeyId='2BA16C9B594CE0E6'", output)


if __name__ == "__main__":
    unittest.main()
