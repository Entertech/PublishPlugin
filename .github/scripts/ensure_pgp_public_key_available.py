#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence


DEFAULT_KEYSERVERS = ("keyserver.ubuntu.com",)


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str


class GpgCommandError(RuntimeError):
    pass


def parse_primary_secret_key_fingerprints(colon_output: str) -> list[str]:
    fingerprints: list[str] = []
    waiting_for_primary_fingerprint = False

    for line in colon_output.splitlines():
        if not line:
            continue

        fields = line.split(":")
        record_type = fields[0]
        if record_type.startswith("sec"):
            waiting_for_primary_fingerprint = True
            continue
        if record_type == "fpr" and waiting_for_primary_fingerprint:
            fingerprint = fields[9].strip().upper() if len(fields) > 9 else ""
            if fingerprint and fingerprint not in fingerprints:
                fingerprints.append(fingerprint)
            waiting_for_primary_fingerprint = False
            continue
        if record_type in {"pub", "sub", "ssb", "uid"}:
            waiting_for_primary_fingerprint = False

    return fingerprints


def run_command(command: Sequence[str], *, gnupg_home: Path, input_text: str | None = None) -> CommandResult:
    env = os.environ.copy()
    env["GNUPGHOME"] = str(gnupg_home)
    try:
        completed = subprocess.run(
            list(command),
            input=input_text,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env,
            timeout=120,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        return CommandResult(
            124,
            error.stdout if isinstance(error.stdout, str) else "",
            error.stderr if isinstance(error.stderr, str) else "gpg command timed out",
        )

    return CommandResult(completed.returncode, completed.stdout, completed.stderr)


def require_success(result: CommandResult, action: str) -> None:
    if result.returncode == 0:
        return

    message = (result.stderr or result.stdout or "no output").strip()
    raise GpgCommandError(f"{action} failed: {message}")


def make_gnupg_home(path: str) -> Path:
    home = Path(path)
    home.chmod(0o700)
    return home


def verify_keyserver_has_key(
    *,
    fingerprint: str,
    keyserver: str,
    gpg: str,
    runner: Callable[[Sequence[str]], CommandResult],
    retries: int,
    retry_delay_seconds: int,
    sleep: Callable[[int], None],
) -> bool:
    for attempt in range(1, retries + 1):
        result = runner(
            [
                gpg,
                "--batch",
                "--keyserver",
                keyserver,
                "--recv-keys",
                fingerprint,
            ]
        )
        if result.returncode == 0:
            return True
        if attempt < retries:
            sleep(retry_delay_seconds)

    return False


def ensure_public_keys_available(
    *,
    key_contents: str,
    keyservers: Sequence[str] | None = None,
    gpg: str = "gpg",
    runner: Callable[[Sequence[str]], CommandResult] | None = None,
    retries: int = 6,
    retry_delay_seconds: int = 10,
    sleep: Callable[[int], None] = time.sleep,
) -> list[str]:
    if not key_contents.strip():
        raise ValueError("GPG_KEY_CONTENTS is required")
    if retries < 1:
        raise ValueError("retries must be at least 1")

    selected_keyservers = tuple(keyservers or DEFAULT_KEYSERVERS)
    if not selected_keyservers:
        raise ValueError("at least one keyserver is required")

    with tempfile.TemporaryDirectory() as import_dir, tempfile.TemporaryDirectory() as verify_dir:
        import_home = make_gnupg_home(import_dir)
        verify_home = make_gnupg_home(verify_dir)

        def run_in_import_home(command: Sequence[str], input_text: str | None = None) -> CommandResult:
            active_runner = runner or run_command
            return active_runner(command, gnupg_home=import_home, input_text=input_text)

        def run_in_verify_home(command: Sequence[str]) -> CommandResult:
            active_runner = runner or run_command
            return active_runner(command, gnupg_home=verify_home, input_text=None)

        require_success(
            run_in_import_home([gpg, "--batch", "--import"], input_text=key_contents),
            "Importing GPG private key",
        )
        listing = run_in_import_home([gpg, "--batch", "--with-colons", "--list-secret-keys", "--fingerprint"])
        require_success(listing, "Listing GPG secret keys")

        fingerprints = parse_primary_secret_key_fingerprints(listing.stdout)
        if not fingerprints:
            raise GpgCommandError("No primary secret key fingerprint found in GPG_KEY_CONTENTS")

        for fingerprint in fingerprints:
            publish_errors: list[str] = []
            for keyserver in selected_keyservers:
                send_result = run_in_import_home(
                    [gpg, "--batch", "--keyserver", keyserver, "--send-keys", fingerprint]
                )
                if send_result.returncode != 0:
                    publish_errors.append((send_result.stderr or send_result.stdout or "").strip())
                    continue

                if verify_keyserver_has_key(
                    fingerprint=fingerprint,
                    keyserver=keyserver,
                    gpg=gpg,
                    runner=run_in_verify_home,
                    retries=retries,
                    retry_delay_seconds=retry_delay_seconds,
                    sleep=sleep,
                ):
                    break
                publish_errors.append(f"{keyserver} did not return key {fingerprint} after upload")
            else:
                detail = "; ".join(error for error in publish_errors if error) or "no keyserver accepted the key"
                raise GpgCommandError(f"Could not publish PGP public key {fingerprint}: {detail}")

        return fingerprints


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Publish and verify a PGP public key for Maven Central.")
    parser.add_argument("--key-env", default="GPG_KEY_CONTENTS", help="Environment variable containing the private key")
    parser.add_argument("--keyserver", action="append", dest="keyservers", help="GPG keyserver to publish to")
    parser.add_argument("--gpg", default="gpg", help="gpg executable")
    parser.add_argument("--retries", type=int, default=6, help="Keyserver lookup attempts after upload")
    parser.add_argument("--retry-delay-seconds", type=int, default=10, help="Delay between lookup attempts")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    key_contents = os.environ.get(args.key_env, "")

    try:
        fingerprints = ensure_public_keys_available(
            key_contents=key_contents,
            keyservers=args.keyservers or DEFAULT_KEYSERVERS,
            gpg=args.gpg,
            retries=args.retries,
            retry_delay_seconds=args.retry_delay_seconds,
        )
    except (GpgCommandError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1

    for fingerprint in fingerprints:
        print(f"PGP public key {fingerprint} is available on a Maven Central supported keyserver.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
