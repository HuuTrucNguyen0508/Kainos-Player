#!/usr/bin/env python3
"""Release gate for the Android optimized build. Run against one chosen device.

    scripts/android-release-gate.py --serial emulator-5554

On an emulator it regenerates the baseline profile, builds, verifies and installs
the optimized APK, then runs every instrumented stage against it. Between the two it drives the
shipped APK black-box through the phone paths in android_phone_paths.py. Emulator
app data is disposable and gets cleared.

    scripts/android-release-gate.py --serial emulator-5554 --phone-paths path/to.apk

installs that APK and runs only the phone paths, in a few minutes. A physical device needs --allow-physical,
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
from typing import Callable

import android_phone_paths

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
# Each needs real hardware or a real account. "Emulator:" names the stage that proves the stock-Android half.
MANUAL = (
    "Update the phone with `adb install -r`: hearts, SAF folders, playlists, settings and listening history "
    "survive, and the session comes back paused. Emulator: restart restores paused",
    "HyperOS lock screen: title and controls follow at least five track changes, pause and play work locked. "
    "Emulator: background with the screen off",
    "HyperOS notification and island: controls stay stable across skips; tapping opens Now Playing, also from a "
    "cold start. Emulator: notification opens Now Playing",
    "Bluetooth headset or car: play, pause, next, previous; disconnecting pauses (becoming noisy). "
    "Emulator: headset and lock-screen keys covers media keys only, not AVRCP",
    "Rapid skips on HyperOS: ten quick Next presses from the notification and from a headset land on the tenth "
    "track with no stuck buffering; repeat on a Spotify queue. Emulator: rapid skips (local files)",
    "Live Spotify: sign in, play, and a natural end of track moves to the next item",
    "Spotify in the background: play with the phone locked for 10 minutes; no process death, playback-trace.log "
    "shows no receiver restart",
    "Spotify reconnect (2026-11-02 crash 1): during Spotify playback switch Wi-Fi off and on, and Wi-Fi to mobile "
    "data, several times. No fatal on session-packet-receiver; the trace shows `interrupted AP reconnect; "
    "recreating receiver` then `session ready`, playback pauses, and Play resumes at the saved position",
    "Foreground denial (2026-11-02 crash 2): pause for over 10 minutes with Kainos in the background, then start "
    "playback separately from desktop Connect, a Home sync handoff, Bluetooth and the notification. No process "
    "death; it either plays or pauses with Session3 `foreground promotion denied` and a toast. "
    "Emulator: background play after demotion (this emulator admits the start, so the denial branch is unrun)",
    "Live YouTube: search, play two results, background playback, YouTube Mix refills the queue",
    "Track row menu (#33): Play next, Add to queue, Heart and Add to playlist on Library and Search rows. "
    "Emulator: track menu Play next",
    "Ready offline (#34): the chip lists and queues only audio that is on the phone, with Wi-Fi off",
    "Sleep timer (#36): a 5-minute timer fades out over its last 30 seconds and pauses; End of track pauses at the end",
    "Listening history (#37): after a few plays Home shows On repeat, Rediscover and Recently added",
    "Volume levelling (#38): local files with ReplayGain tags play at matched loudness, untagged ones unchanged",
    "Home sync between the phone and the PC on the previous and the new version",
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

    def stage(self, name: str, action: Callable[[], str]) -> bool:
        """Runs one black-box stage; on failure keeps a screenshot and the UI tree."""
        started = time.monotonic()
        print(f"... {name}", flush=True)
        try:
            detail = action()
        except Exception as error:  # A stage failure of any kind is a gate FAIL, not a crash of the gate.
            stem = self.logs / re.sub(r"[^\w.-]+", "-", name)
            try:
                with open(f"{stem}.png", "wb") as image:
                    subprocess.run([str(self.sdk / "platform-tools/adb"), "-s", self.args.serial, "exec-out", "screencap", "-p"],
                                   stdout=image, timeout=60)
                Path(f"{stem}.xml").write_text(self.adb("exec-out", "uiautomator", "dump", "/dev/tty").stdout)
            except (OSError, subprocess.SubprocessError) as capture:
                print(f"Could not capture the screen: {capture}", file=sys.stderr)
            self.record(name, "FAIL", started, f"{error or type(error).__name__}; {stem.name}.png")
            return False
        self.record(name, "PASS", started, detail)
        return True

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
        ok = passed and total > skipped and bad == 0
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


def install_emulator(gate: Gate, apk: Path = APK) -> bool:
    started = time.monotonic()
    result = gate.adb("install", "-r", str(apk))
    output = result.stdout + result.stderr
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output or "INSTALL_FAILED_VERSION_DOWNGRADE" in output:
        gate.adb("uninstall", APP)
        result = gate.adb("install", str(apk))
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


def phone_paths(gate: Gate) -> bool:
    """Emulator only: clears app data and seeds shared storage."""
    phone = android_phone_paths.Phone(gate.adb)
    gate.adb("logcat", "-c", "-b", "all")
    paths = android_phone_paths.PhonePaths(phone, lambda local, remote: gate.adb("push", f"{local}/.", remote).returncode == 0)
    try:
        stages = [*paths.stages(), ("Phone: no crash or ANR", lambda: android_phone_paths.crash_check(phone))]
        for index, (name, action) in enumerate(stages):
            if not gate.stage(name, action):
                for skipped, _ in stages[index + 1:]:
                    gate.record(skipped, "SKIP", time.monotonic(), "an earlier phone stage failed")
                return False
        return True
    finally:
        phone.wake_and_unlock()
        android_phone_paths.remove_seed(phone)


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
    manual = ["Manual checks on the phone, for the APK this gate verified:"] + [f"- [ ] {item}" for item in MANUAL]
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
    parser.add_argument("--phone-paths", type=Path, metavar="APK",
                        help="install this APK on the emulator and run only the black-box phone paths")
    parser.add_argument("--max-workers", type=int, default=6)
    args = parser.parse_args()

    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
    logs = ROOT / "logs" / f"android-release-gate-{datetime.now():%Y%m%d-%H%M%S}"
    gate = Gate(args, sdk, logs)

    started = time.monotonic()
    if gate.adb("get-state").stdout.strip() != "device":
        print(f"Device {args.serial} is not online", file=sys.stderr)
        return 2
    emulator = is_emulator(args.serial, gate.prop("ro.kernel.qemu") or gate.prop("ro.boot.qemu"))
    if not emulator and args.phone_paths:
        print(f"{args.serial} is a physical device. Phone paths clear app data and seed storage, "
              "so they only run on an emulator.", file=sys.stderr)
        return 2
    if not emulator and not args.allow_physical:
        print(f"{args.serial} is a physical device. Instrumented stages clear app data, so the gate "
              "refuses it. --allow-physical only updates it with install -r.", file=sys.stderr)
        return 2
    logs.mkdir(parents=True)
    gate.record("Device", "PASS", started, f"{gate.prop('ro.product.model')}, API {gate.prop('ro.build.version.sdk')}, "
                f"{'emulator' if emulator else 'physical'}")

    def phone_paths_only() -> bool:
        return install_emulator(gate, args.phone_paths) and phone_paths(gate)

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
        if not smoke_shipped_apk(gate) or not phone_paths(gate):
            return False
        results = [gate.connected(f"Instrumented: {name}", name) for name in INSTRUMENTED]
        return all(results) and first_run(gate)

    phone_paths_only() if args.phone_paths else steps()
    return 0 if report(gate, logs / "summary.txt") else 1


if __name__ == "__main__":
    sys.exit(main())
