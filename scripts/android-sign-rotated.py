#!/usr/bin/env python3
"""Sign the optimized APK with a rotated key. Gradle cannot apply a v3 lineage.

The result updates an app installed with the old certificate. See "Adopting a
release key" in androidApp/README.md before running this.
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUTS = ROOT / "androidApp/build/outputs/apk/optimized"
ENV_KEYS = {
    "storeFile": "KAINOS_SIGNING_STORE_FILE",
    "storePassword": "KAINOS_SIGNING_STORE_PASSWORD",
    "keyAlias": "KAINOS_SIGNING_KEY_ALIAS",
    "keyPassword": "KAINOS_SIGNING_KEY_PASSWORD",
}


def apksigner() -> str:
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
    tools = sorted((sdk / "build-tools").glob("*/apksigner"), key=lambda p: [int(n) for n in p.parent.name.split("-")[0].split(".")])
    if not tools:
        sys.exit(f"apksigner not found under {sdk}/build-tools")
    return str(tools[-1])


def new_key() -> dict[str, str]:
    values = {}
    file = ROOT / "signing.properties"
    if file.exists():
        for line in file.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    for key, env in ENV_KEYS.items():
        if env in os.environ:
            values[key] = os.environ[env]
    missing = [key for key in ENV_KEYS if not values.get(key)]
    if missing:
        sys.exit(f"Release key is missing {missing}; set signing.properties or KAINOS_SIGNING_*")
    return values


def signer_certs(tool: str, apk: Path, min_sdk: int, max_sdk: int) -> list[str]:
    output = subprocess.run(
        [tool, "verify", "--print-certs", "--min-sdk-version", str(min_sdk), "--max-sdk-version", str(max_sdk), str(apk)],
        check=True, capture_output=True, text=True,
    ).stdout
    return sorted({line.rsplit(" ", 1)[1] for line in output.splitlines() if "certificate SHA-256 digest" in line})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lineage", type=Path, required=True, help="file written by apksigner rotate")
    parser.add_argument("--in", dest="source", type=Path, default=OUTPUTS / "androidApp-optimized.apk")
    parser.add_argument("--out", type=Path, default=OUTPUTS / "androidApp-optimized-rotated.apk")
    parser.add_argument("--old-keystore", type=Path, default=Path.home() / ".android/debug.keystore")
    parser.add_argument("--old-alias", default="androiddebugkey")
    args = parser.parse_args()

    key = new_key()
    tool = apksigner()
    env = {
        **os.environ,
        "KAINOS_OLD_PASS": os.environ.get("KAINOS_OLD_KEY_PASSWORD", "android"),
        "KAINOS_NEW_STORE_PASS": key["storePassword"],
        "KAINOS_NEW_KEY_PASS": key["keyPassword"],
    }
    # Oldest signer first. It stays the v2 signer that API 26-27 verify; API 28+
    # read the v3 block and accept the new key through the lineage.
    subprocess.run([
        tool, "sign",
        "--in", str(args.source), "--out", str(args.out),
        "--v4-signing-enabled", "false",
        "--lineage", str(args.lineage), "--rotation-min-sdk-version", "28",
        "--ks", str(args.old_keystore), "--ks-key-alias", args.old_alias,
        "--ks-pass", "env:KAINOS_OLD_PASS", "--key-pass", "env:KAINOS_OLD_PASS",
        "--next-signer",
        "--ks", key["storeFile"], "--ks-key-alias", key["keyAlias"],
        "--ks-pass", "env:KAINOS_NEW_STORE_PASS", "--key-pass", "env:KAINOS_NEW_KEY_PASS",
    ], check=True, env=env)

    legacy = signer_certs(tool, args.out, 26, 27)
    rotated = signer_certs(tool, args.out, 28, 99)
    print(f"API 26-27 signer: {legacy}")
    print(f"API 28+ signer:   {rotated}")
    if legacy == rotated:
        sys.exit("Rotation did not apply: both API ranges report the same signer")
    print(args.out)


if __name__ == "__main__":
    main()
