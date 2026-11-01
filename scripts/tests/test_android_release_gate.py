"""Check the gate's safety decisions without a device or a build."""
import importlib.util
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

GATE = Path(__file__).resolve().parents[1] / "android-release-gate.py"
spec = importlib.util.spec_from_file_location("android_release_gate", GATE)
gate = importlib.util.module_from_spec(spec)
sys.modules["android_release_gate"] = gate
spec.loader.exec_module(gate)

FAKE_ADB = """#!/bin/sh
echo "$@" >> "$ADB_CALLS"
case "$*" in
  *get-state*) echo device ;;
  *ro.kernel.qemu*) echo "$QEMU" ;;
esac
"""


class GateTest(unittest.TestCase):
    def run_gate(self, serial, qemu, *args):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        sdk = Path(temporary.name)
        adb = sdk / "platform-tools/adb"
        adb.parent.mkdir()
        adb.write_text(FAKE_ADB)
        adb.chmod(adb.stat().st_mode | stat.S_IEXEC)
        calls = sdk / "calls"
        calls.touch()
        result = subprocess.run(
            [sys.executable, str(GATE), "--serial", serial, *args],
            env={**os.environ, "ANDROID_HOME": str(sdk), "ADB_CALLS": str(calls), "QEMU": qemu},
            capture_output=True, text=True,
        )
        return result, calls.read_text()

    def test_physical_device_is_refused_before_anything_touches_it(self):
        result, calls = self.run_gate("ABC123", "")
        self.assertEqual(result.returncode, 2)
        self.assertIn("physical device", result.stderr)
        for command in ("install", "uninstall", "pm clear", "am instrument"):
            self.assertNotIn(command, calls)

    def test_emulator_serial_without_qemu_counts_as_physical(self):
        self.assertFalse(gate.is_emulator("emulator-5554", ""))
        self.assertFalse(gate.is_emulator("ABC123", "1"))
        self.assertTrue(gate.is_emulator("emulator-5554", "1"))

    def test_physical_flow_never_clears_data_or_runs_tests(self):
        source = GATE.read_text()
        physical = source[source.index("def update_physical"):source.index("def install_emulator")]
        self.assertNotIn('"uninstall"', physical)
        self.assertNotIn('"clear"', physical)

    def test_a_run_with_no_tests_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            self.assertEqual(gate.junit_counts(Path(temporary)), (0, 0, 0))
            xml = Path(temporary) / "TEST-device.xml"
            xml.write_text('<testsuite tests="3" failures="1" errors="1" skipped="1"/>')
            self.assertEqual(gate.junit_counts(Path(temporary)), (3, 2, 1))

    def test_connected_requires_executed_tests(self):
        from unittest.mock import patch
        for total, skipped, expected in ((0, 0, False), (3, 3, False), (3, 2, True), (3, 0, True)):
            with self.subTest(total=total, skipped=skipped), tempfile.TemporaryDirectory() as temporary:
                run = gate.Gate(type("Args", (), {"max_workers": 6, "serial": "emulator-5554"})(), Path(temporary), Path(temporary))
                with patch.object(run, "execute", return_value=(True, "test.log")), patch.object(gate, "junit_counts", return_value=(total, 0, skipped)), patch.object(gate.shutil, "rmtree"):
                    self.assertEqual(run.connected("stage", "PlaybackTest"), expected)

    def test_only_a_clean_run_passes(self):
        def outcome(*statuses):
            run = type("Run", (), {"rows": [gate.Row("x", status, 0, "") for status in statuses]})
            with tempfile.TemporaryDirectory() as temporary:
                return gate.report(run, Path(temporary) / "summary.txt")
        self.assertTrue(outcome("PASS", "SKIP"))
        self.assertFalse(outcome("PASS", "FAIL"))
        self.assertFalse(outcome("SKIP"))

    def test_badging_reads_version(self):
        self.assertEqual(gate.parse_badging("package: name='x' versionCode='362' versionName='2026-11-01' platformBuildVersionName='16'"),
                         {"versionCode": "362", "versionName": "2026-11-01"})


if __name__ == "__main__":
    unittest.main()
