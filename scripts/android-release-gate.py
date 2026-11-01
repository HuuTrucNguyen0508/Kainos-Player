#!/usr/bin/env python3
"""Release gate for the Android optimized build. Run against one chosen device.

    scripts/android-release-gate.py --serial emulator-5554

On an emulator it regenerates the baseline profile, builds, verifies and installs
the optimized APK, then runs every instrumented stage against it. Emulator app
data is disposable and gets cleared. A physical device needs --allow-physical,
and then only receives a certificate-checked `install -r`: instrumented stages
clear data and revoke grants, so they never run on one.

Exit status is non-zero on any failure. A skipped stage is reported as SKIP.
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ElementTree
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = "com.universalmusic.player"
BENCH = f"{APP}.benchmark"
TESTS = f"{APP}.Android"
APK = ROOT / "androidApp/build/outputs/apk/optimized/androidApp-optimized.apk"
RESULTS = ROOT / "androidApp/build/outputs/androidTest-results/connected"
BASELINE = "androidApp/src/main/baseline-prof.txt"
STAGE_TIMEOUT_SECONDS = 1200
INSTRUMENTED = ("PlaybackTest", "VaultSafPublishTest", "OptimizedRuntimeTest", "HomeLanIdentityTest", "CleartextPolicyTest")
# (clear app data first, stage). The others build on the state the previous stage left.
FIRST_RUN = (
    (True, "fresh"), (True, "grant"), (False, "restore"), (False, "revoke"), (False, "revokedLaunch"),
    (True, "dark"), (True, "spotify"), (True, "youtube"), (False, "version"),
)
MANUAL = (
    "Update the phone with `adb install -r` and confirm hearts, SAF folders, playlists and settings survive",
    "Lock screen: metadata and transport controls across several track changes",
    "Notification and HyperOS island: controls stay stable, tapping opens Now Playing",
    "Bluetooth: play, pause and skip from a real headset or car",
    "Live Spotify: login, playback, and a natural end of track moving to the next item",
    "Live YouTube: search, play two results, background playback",
)


class Gate:
    def __init__(self, args: argparse.Namespace, sdk: Path, logs: Path):
        self.args = args
        self.sdk = sdk
        self.logs = logs
        self.rows: list[Row] = []

    def adb(self, *command: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run([str(self.sdk / "platform-tools/adb"), "-s", self.args.serial, *command],
                              capture_output=True, text=True, timeout=300)

    def prop(self, name: str) -> str:
        return self.adb("shell", "getprop", name).stdout.strip()

    def tool(self, name: str) -> str:
        tools = sorted((self.sdk / "build-tools").glob(f"*/{name}"),
                       key=lambda p: [int(n) for n in re.findall(r"\d+", p.parent.name)])
        if not tools:
            raise SystemExit(f"{name} not found under {self.sdk}/build-tools")
        return str(tools[-1])

    def execute(self, name: str, command: list[str], env: dict[str, str] | None) -> tuple[bool, str]:
        log = self.logs / (re.sub(r"[^\w.-]+", "-", name) + ".log")
        print(f"... {name}", flush=True)
        with log.open("w") as output:
            try:
                result = subprocess.run(command, cwd=ROOT, env={**os.environ, **(env or {})},
                                        stdout=output, stderr=subprocess.STDOUT, timeout=STAGE_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                return False, f"timed out after {STAGE_TIMEOUT_SECONDS} s; {log.name}"
        return result.returncode == 0, log.name

    def run(self, name: str, command: list[str], env: dict[str, str] | None = None) -> bool:
        started = time.monotonic()
        passed, log = self.execute(name, command, env)
        self.record(name, "PASS" if passed else "FAIL", started, log)
        return passed

    def record(self, name: str, status: str, started: float, detail: str) -> None:
        self.rows.append(Row(name, status, time.monotonic() - started, detail))
        print(f"{status:4} {name}: {detail}", flush=True)

    def gradle(self, *tasks: str) -> list[str]:
        return ["nice", "-n", "10", "./gradlew", f"--max-workers={self.args.max_workers}", "--console=plain", *tasks]

    @property
    def gradle_env(self) -> dict[str, str]:
        return {"ANDROID_SERIAL": self.args.serial, "ANDROID_HOME": str(self.sdk),
                "JAVA_HOME": os.environ.get("JAVA_HOME", str(Path.home() / ".jdks/temurin-17")),
                "GRADLE_OPTS": f"-Dorg.gradle.workers.max={self.args.max_workers}"}

    def connected(self, name: str, klass: str, *extra: str) -> bool:
        """Runs one class against the test-target optimized build, reading counts from JUnit XML."""
        shutil.rmtree(RESULTS, ignore_errors=True)
        started = time.monotonic()
        passed, log = self.execute(name, self.gradle(
            ":androidApp:connectedOptimizedAndroidTest", "-PandroidTestTarget=optimized",
            f"-Pandroid.testInstrumentationRunnerArguments.class={TESTS}{klass}", *extra,
        ), self.gradle_env)
        total, bad, skipped = junit_counts(RESULTS)
        ok = passed and total > 0 and bad == 0
        self.record(name, "PASS" if ok else "FAIL", started, f"{total} tests, {bad} failed, {skipped} skipped; {log}")
        return ok

    def certs(self, apk: Path) -> set[str]:
        output = subprocess.run([self.tool("apksigner"), "verify", "--print-certs", str(apk)],
                                capture_output=True, text=True)
        return set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", output.stdout))


@dataclasses.dataclass
class Row:
    name: str
    status: str
    seconds: float
    detail: str


def junit_counts(results: Path) -> tuple[int, int, int]:
    total = bad = skipped = 0
    for file in results.rglob("TEST-*.xml"):
        root = ElementTree.parse(file).getroot()
        total += int(root.get("tests", 0))
        bad += int(root.get("failures", 0)) + int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))
    return total, bad, skipped


def is_emulator(serial: str, qemu_prop: str) -> bool:
    return serial.startswith("emulator-") and qemu_prop == "1"


def parse_badging(text: str) -> dict[str, str]:
    return dict(re.findall(r"(versionCode|versionName)='([^']*)'", text))


def profile_stale_note() -> str:
    last = subprocess.run(["git", "log", "-1", "--format=%h", "--", BASELINE], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    behind = subprocess.run(["git", "rev-list", "--count", f"{last}..HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    return f"baseline profile not regenerated, last changed {behind or '?'} commits ago ({last or 'never'})"


def check_apk(gate: Gate) -> set[str]:
    started = time.monotonic()
    aapt2 = gate.tool("aapt2")
    badging = subprocess.run([aapt2, "dump", "badging", str(APK)], capture_output=True, text=True).stdout
    version = parse_badging(badging)
    manifest = subprocess.run([aapt2, "dump", "xmltree", str(APK), "--file", "AndroidManifest.xml"], capture_output=True, text=True).stdout
    verify = subprocess.run([gate.tool("apksigner"), "verify", "--min-sdk-version", "26", str(APK)], capture_output=True, text=True)
    certs = gate.certs(APK)
    entries = subprocess.run(["unzip", "-l", str(APK)], capture_output=True, text=True).stdout
    problems = []
    if verify.returncode != 0 or not certs:
        problems.append("apksigner: " + (verify.stderr.strip() or "no signer"))
    if int(version.get("versionCode", 0)) <= 1:
        problems.append("versionCode is 1: shallow clone? pass -PkainosVersionCode")
    if "application-debuggable" in badging:
        problems.append("APK is debuggable")
    if "networkSecurityConfig" not in manifest:
        problems.append("no network security config")
    if "assets/dexopt/baseline.prof" not in entries:
        problems.append("no baseline profile")
    detail = "; ".join(problems) or (
        f"{version['versionName']} ({version['versionCode']}), {APK.stat().st_size} bytes, signer {', '.join(c[:12] for c in certs)}")
    gate.record("Verify APK", "FAIL" if problems else "PASS", started, detail)
    # Test-target runs rebuild the same output path with extra keep rules.
    shutil.copy2(APK, gate.logs / APK.name)
    print(f"Verified APK: {gate.logs / APK.name}\nSHA-256: {hashlib.sha256(APK.read_bytes()).hexdigest()}")
    return certs


def installed_certs(gate: Gate, work: Path) -> set[str] | None:
    """Certificates of the installed app, or None if it is not installed."""
    path = gate.adb("shell", "pm", "path", APP).stdout.strip().splitlines()
    base = next((line.removeprefix("package:") for line in path if line.endswith("/base.apk")), None)
    if base is None:
        return None
    local = work / "installed.apk"
    if gate.adb("pull", base, str(local)).returncode != 0:
        raise RuntimeError(f"Could not read the installed APK at {base}")
    return gate.certs(local)


def update_physical(gate: Gate, certs: set[str]) -> bool:
    started = time.monotonic()
    with tempfile.TemporaryDirectory() as work:
        try:
            installed = installed_certs(gate, Path(work))
        except RuntimeError as error:
            gate.record("Install", "FAIL", started, str(error))
            return False
    # A failed update loses nothing, but checking first gives the real reason.
    if installed is not None and not installed & certs:
        gate.record("Install", "FAIL", started, "certificate differs from the installed app; not attempted. "
                    "Do not uninstall: see Adopting a release key in androidApp/README.md")
        return False
    return gate.run("Install", [str(gate.sdk / "platform-tools/adb"), "-s", gate.args.serial, "install", "-r", str(APK)])


def install_emulator(gate: Gate) -> bool:
    started = time.monotonic()
    result = gate.adb("install", "-r", str(APK))
    output = result.stdout + result.stderr
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output or "INSTALL_FAILED_VERSION_DOWNGRADE" in output:
        gate.adb("uninstall", APP)
        result = gate.adb("install", str(APK))
        output = result.stdout + result.stderr
    passed = result.returncode == 0 and "Success" in output
    # A folder grant left by an earlier run turns MediaStore off and hides the seeded library.
    gate.adb("shell", "pm", "clear", APP)
    gate.record("Install", "PASS" if passed else "FAIL", started, output.strip().splitlines()[-1] if output.strip() else "no output")
    return passed


def regenerate_profile(gate: Gate) -> bool:
    env = gate.gradle_env
    runtime = ["nice", "-n", "10", "scripts/perf/android-runtime"]
    return (gate.run("Profile: seed library", [*runtime, "seed", "profile"], env)
            and gate.run("Profile: collect", [*runtime, "profile"], env)
            and gate.run("Profile: copy into sources", ["python3", "scripts/perf/android-copy-profiles.py"], env))


def smoke_shipped_apk(gate: Gate) -> bool:
    """The test-target APK carries extra keep rules, so this is the only stage that
    proves the exact artifact, unmodified, against R8."""
    runtime = ["nice", "-n", "10", "scripts/perf/android-runtime"]
    return (gate.run("Shipped APK: seed library", [*runtime, "seed", "optimized"], gate.gradle_env)
            and gate.run("Shipped APK: playback, Settings, sync screen", [*runtime, "smoke", "optimized", "localTransportSettingsAndSync"], gate.gradle_env))


def first_run(gate: Gate) -> bool:
    gate.adb("shell", "rm", "-rf", "/sdcard/Download/kainos-proof")
    keep_installed = "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
    for clear, stage in FIRST_RUN:
        if clear:
            gate.adb("shell", "pm", "clear", APP)
        if not gate.connected(f"First run: {stage}", "FirstRunProofTest", keep_installed,
                              f"-Pandroid.testInstrumentationRunnerArguments.stage={stage}"):
            return False
    gate.adb("pull", "/sdcard/Download/kainos-proof", str(gate.logs))
    return True


def report(gate: Gate, summary: Path) -> bool:
    width = max(len(row.name) for row in gate.rows)
    table = [f"{'Stage'.ljust(width)}  Result  Seconds  Detail"] + [
        f"{row.name.ljust(width)}  {row.status:6}  {row.seconds:7.0f}  {row.detail}" for row in gate.rows]
    passed = all(row.status != "FAIL" for row in gate.rows) and any(row.status == "PASS" for row in gate.rows)
    manual = ["Manual checks no emulator can do:"] + [f"- [ ] {item}" for item in MANUAL]
    text = "\n".join(table + ["", f"Gate: {'PASS' if passed else 'FAIL'}", "", *manual]) + "\n"
    print("\n" + text)
    summary.write_text(text)
    print(f"Report: {summary}")
    return passed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True, help="adb serial; `adb devices` lists them")
    parser.add_argument("--allow-physical", action="store_true", help="update a physical device with install -r; runs no tests on it")
    parser.add_argument("--skip-profile", action="store_true", help="keep the committed baseline profile")
    parser.add_argument("--max-workers", type=int, default=6)
    args = parser.parse_args()

    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
    logs = ROOT / "logs" / f"android-release-gate-{datetime.now():%Y%m%d-%H%M%S}"
    logs.mkdir(parents=True)
    gate = Gate(args, sdk, logs)

    started = time.monotonic()
    if gate.adb("get-state").stdout.strip() != "device":
        print(f"Device {args.serial} is not online", file=sys.stderr)
        return 2
    emulator = is_emulator(args.serial, gate.prop("ro.kernel.qemu") or gate.prop("ro.boot.qemu"))
    if not emulator and not args.allow_physical:
        print(f"{args.serial} is a physical device. Instrumented stages clear app data, so the gate "
              "refuses it. --allow-physical only updates it with install -r.", file=sys.stderr)
        return 2
    gate.record("Device", "PASS", started, f"{gate.prop('ro.product.model')}, API {gate.prop('ro.build.version.sdk')}, "
                f"{'emulator' if emulator else 'physical'}")

    def steps() -> bool:
        started = time.monotonic()
        if args.skip_profile or not emulator:
            gate.record("Baseline profile", "SKIP", started, profile_stale_note())
        elif not regenerate_profile(gate):
            return False
        if not gate.run("Build optimized", gate.gradle(":androidApp:assembleOptimized"), gate.gradle_env):
            return False
        certs = check_apk(gate)
        if gate.rows[-1].status == "FAIL":
            return False
        started = time.monotonic()
        if not emulator:
            passed = update_physical(gate, certs)
            gate.record("Instrumented suite", "SKIP", started, "physical device: tests clear data; run the gate on an emulator")
            return passed
        if not install_emulator(gate):
            return False
        if not smoke_shipped_apk(gate):
            return False
        results = [gate.connected(f"Instrumented: {name}", name) for name in INSTRUMENTED]
        return all(results) and first_run(gate)

    steps()
    return 0 if report(gate, logs / "summary.txt") else 1


if __name__ == "__main__":
    sys.exit(main())
