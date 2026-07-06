import unittest

from ensure_pgp_public_key_available import (
    CommandResult,
    ensure_public_keys_available,
    parse_primary_secret_key_fingerprints,
)


SECRET_KEY_LISTING = """\
sec:u:3072:1:8190C4130ABA0F98:1624478814:1782245214::u:::scESC:::+:::23::0:
fpr:::::::::CA925CD6C9E8D064FF05B4728190C4130ABA0F98:
grp:::::::::0123456789ABCDEF:
uid:u::::1624478814::1234567890ABCDEF::Central Repo Test <central@example.com>::::::::::0:
ssb:u:3072:1:4656B4857C17C93B:1624478814:1782245214:::::e:::+:::23:
fpr:::::::::515D4F4A1EDCCA2E7C904FA84656B4857C17C93B:
"""


class FakeGpgRunner:
    def __init__(self):
        self.calls = []
        self.recv_attempts = 0

    def __call__(self, command, *, gnupg_home, input_text=None):
        self.calls.append((command, gnupg_home, input_text))
        if "--list-secret-keys" in command:
            return CommandResult(0, SECRET_KEY_LISTING, "")
        return CommandResult(0, "", "")


class EnsurePgpPublicKeyAvailableTest(unittest.TestCase):
    def test_parses_primary_secret_key_fingerprint(self):
        self.assertEqual(
            parse_primary_secret_key_fingerprints(SECRET_KEY_LISTING),
            ["CA925CD6C9E8D064FF05B4728190C4130ABA0F98"],
        )

    def test_parses_primary_fingerprint_from_secret_key_record_variants(self):
        listing = SECRET_KEY_LISTING.replace("sec:u:", "sec#:", 1)

        self.assertEqual(
            parse_primary_secret_key_fingerprints(listing),
            ["CA925CD6C9E8D064FF05B4728190C4130ABA0F98"],
        )

    def test_imports_private_key_then_publishes_and_verifies_public_key(self):
        runner = FakeGpgRunner()

        fingerprints = ensure_public_keys_available(
            key_contents="secret-key",
            keyservers=["keyserver.ubuntu.com"],
            runner=runner,
            retries=1,
            sleep=lambda _: None,
        )

        self.assertEqual(fingerprints, ["CA925CD6C9E8D064FF05B4728190C4130ABA0F98"])
        commands = [call[0] for call in runner.calls]
        self.assertEqual(commands[0], ["gpg", "--batch", "--import"])
        self.assertEqual(commands[1], ["gpg", "--batch", "--with-colons", "--list-secret-keys", "--fingerprint"])
        self.assertEqual(
            commands[2],
            [
                "gpg",
                "--batch",
                "--keyserver",
                "keyserver.ubuntu.com",
                "--send-keys",
                "CA925CD6C9E8D064FF05B4728190C4130ABA0F98",
            ],
        )
        self.assertEqual(
            commands[3],
            [
                "gpg",
                "--batch",
                "--keyserver",
                "keyserver.ubuntu.com",
                "--recv-keys",
                "CA925CD6C9E8D064FF05B4728190C4130ABA0F98",
            ],
        )
        self.assertEqual(runner.calls[0][2], "secret-key")
        self.assertEqual(runner.calls[0][1], runner.calls[1][1])
        self.assertNotEqual(runner.calls[0][1], runner.calls[3][1])

    def test_retries_public_key_lookup_until_keyserver_has_the_key(self):
        class RetryRunner(FakeGpgRunner):
            def __call__(self, command, *, gnupg_home, input_text=None):
                if "--recv-keys" in command:
                    self.recv_attempts += 1
                    self.calls.append((command, gnupg_home, input_text))
                    if self.recv_attempts == 1:
                        return CommandResult(2, "", "not found")
                    return CommandResult(0, "", "")
                return super().__call__(command, gnupg_home=gnupg_home, input_text=input_text)

        runner = RetryRunner()
        sleeps = []

        ensure_public_keys_available(
            key_contents="secret-key",
            keyservers=["keyserver.ubuntu.com"],
            runner=runner,
            retries=2,
            retry_delay_seconds=3,
            sleep=sleeps.append,
        )

        self.assertEqual(runner.recv_attempts, 2)
        self.assertEqual(sleeps, [3])

    def test_requires_multiple_stable_keyserver_lookups(self):
        class FlakyRunner(FakeGpgRunner):
            def __call__(self, command, *, gnupg_home, input_text=None):
                if "--recv-keys" in command:
                    self.recv_attempts += 1
                    self.calls.append((command, gnupg_home, input_text))
                    if self.recv_attempts == 2:
                        return CommandResult(2, "", "not found")
                    return CommandResult(0, "", "")
                return super().__call__(command, gnupg_home=gnupg_home, input_text=input_text)

        runner = FlakyRunner()
        sleeps = []

        ensure_public_keys_available(
            key_contents="secret-key",
            keyservers=["keyserver.ubuntu.com"],
            runner=runner,
            retries=6,
            stable_lookups=3,
            retry_delay_seconds=5,
            sleep=sleeps.append,
        )

        self.assertEqual(runner.recv_attempts, 5)
        self.assertEqual(sleeps, [5, 5, 5, 5])

    def test_uses_custom_gpg_executable_for_all_commands(self):
        runner = FakeGpgRunner()

        ensure_public_keys_available(
            key_contents="secret-key",
            keyservers=["keyserver.ubuntu.com"],
            gpg="gpg2",
            runner=runner,
            retries=1,
            sleep=lambda _: None,
        )

        self.assertTrue(all(call[0][0] == "gpg2" for call in runner.calls))


if __name__ == "__main__":
    unittest.main()
