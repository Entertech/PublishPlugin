import re
import sys


SHORT_OR_LONG_KEY_ID = re.compile(r"^(0x)?[0-9A-Fa-f]{8,16}$")
FINGERPRINT = re.compile(r"^[0-9A-Fa-f]{40}$")


def normalize_signing_key_id(value):
    key_id = value.strip()
    if not key_id:
        return ""
    if SHORT_OR_LONG_KEY_ID.fullmatch(key_id):
        return key_id
    if FINGERPRINT.fullmatch(key_id):
        return key_id[-16:]
    return ""


def shell_exports(value):
    key_id = normalize_signing_key_id(value)
    if not key_id:
        return "\n".join(
            (
                "unset SIGNING_KEY_ID",
                "unset SIGNING_IN_MEMORY_KEY_ID",
                "unset ORG_GRADLE_PROJECT_signingInMemoryKeyId",
            )
        )

    escaped = key_id.replace("'", "'\"'\"'")
    return "\n".join(
        (
            f"export SIGNING_KEY_ID='{escaped}'",
            f"export SIGNING_IN_MEMORY_KEY_ID='{escaped}'",
            f"export ORG_GRADLE_PROJECT_signingInMemoryKeyId='{escaped}'",
        )
    )


def main():
    value = sys.argv[1] if len(sys.argv) > 1 else ""
    print(shell_exports(value))


if __name__ == "__main__":
    main()
