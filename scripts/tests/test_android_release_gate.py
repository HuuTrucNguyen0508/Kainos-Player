"""Check the gate's safety decisions without a device or a build."""
import importlib.util
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

GATE = Path(__file__).resolve().parents[1] / "android-release-gate.py"
sys.path.insert(0, str(GATE.parent))
import android_phone_paths as phone_paths  # noqa: E402
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

    def test_phone_paths_refuse_a_physical_device_even_with_allow_physical(self):
        result, calls = self.run_gate("ABC123", "", "--allow-physical", "--phone-paths", "app.apk")
        self.assertEqual(result.returncode, 2)
        self.assertIn("only run on an emulator", result.stderr)
        for command in ("install", "pm clear", "push", "device_config", "appops"):
            self.assertNotIn(command, calls)

    def run_main(self, *args):
        """Runs main() on a fake emulator with every stage replaced by a recorder."""
        order = []
        sdk = tempfile.TemporaryDirectory()
        self.addCleanup(sdk.cleanup)
        def adb(_, *command):
            stdout = {"get-state": "device", "getprop": "1"}.get(command[0] if command[0] != "shell" else command[1], "")
            return subprocess.CompletedProcess(command, 0, stdout, "")
        def record(name, result=True):
            def stage(run, *rest):
                order.append((name, *[str(r) for r in rest]))
                run.record(name, "PASS", 0, "")
                return result
            return stage
        with patch.object(gate.Gate, "adb", adb), patch.object(gate, "ROOT", Path(sdk.name)), \
                patch.object(gate.Gate, "run", lambda run, name, *_: record(name)(run)), \
                patch.object(gate, "check_apk", record("check apk")), \
                patch.object(gate, "install_emulator", record("install")), \
                patch.object(gate, "smoke_shipped_apk", record("smoke")), \
                patch.object(gate, "phone_paths", record("phone paths")), \
                patch.object(gate.Gate, "connected", lambda run, name, *_: record(name)(run)), \
                patch.object(gate, "first_run", record("first run")), \
                patch.object(sys, "argv", ["gate", "--serial", "emulator-5554", *args]):
            status = gate.main()
        return status, [step[0] for step in order], order

    def test_full_gate_drives_the_shipped_apk_before_test_builds_replace_it(self):
        status, order, _ = self.run_main("--skip-profile")
        self.assertEqual(status, 0)
        self.assertEqual(order, ["Build optimized", "check apk", "install", "smoke", "phone paths",
                                 *[f"Instrumented: {name}" for name in gate.INSTRUMENTED], "first run"])

    def test_phone_paths_mode_installs_the_given_apk_and_builds_nothing(self):
        status, order, calls = self.run_main("--phone-paths", "some/app.apk")
        self.assertEqual(status, 0)
        self.assertEqual(order, ["install", "phone paths"])
        self.assertEqual(calls[0], ("install", "some/app.apk"))

    def test_a_failed_phone_stage_fails_the_gate_skips_the_rest_and_cleans_up(self):
        class FakePaths:
            def __init__(self, phone, push):
                pass
            def stages(self):
                def broken():
                    raise phone_paths.StageFailed("notification stuck on Gate track 00")
                return [("Phone: one", lambda: "fine"), ("Phone: two", broken), ("Phone: three", lambda: "never")]
        cleaned = []
        with tempfile.TemporaryDirectory() as temporary:
            run = gate.Gate(type("Args", (), {"serial": "emulator-5554"})(), Path(temporary), Path(temporary))
            with patch.object(gate.Gate, "adb", lambda *_: subprocess.CompletedProcess([], 0, "", "")), \
                    patch.object(gate.subprocess, "run"), \
                    patch.object(phone_paths, "PhonePaths", FakePaths), \
                    patch.object(phone_paths.Phone, "wake_and_unlock", lambda _: cleaned.append("wake")), \
                    patch.object(phone_paths, "remove_seed", lambda _: cleaned.append("seed")):
                self.assertFalse(gate.phone_paths(run))
            self.assertEqual([(row.name, row.status) for row in run.rows],
                             [("Phone: one", "PASS"), ("Phone: two", "FAIL"), ("Phone: three", "SKIP"),
                              ("Phone: no crash or ANR", "SKIP")])
            self.assertIn("notification stuck on Gate track 00", run.rows[1].detail)
            self.assertEqual(cleaned, ["wake", "seed"])
            self.assertFalse(gate.report(run, Path(temporary) / "summary.txt"))

    def test_report_lists_every_manual_check_unticked(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = type("Run", (), {"rows": [gate.Row("x", "PASS", 0, "")]})
            gate.report(run, Path(temporary) / "summary.txt")
            text = (Path(temporary) / "summary.txt").read_text()
        self.assertEqual(text.count("- [ ] "), len(gate.MANUAL))
        for topic in ("Bluetooth", "Spotify reconnect", "Foreground denial", "lock screen", "Rapid skips"):
            self.assertIn(topic, text)


SESSIONS = """\
  Sessions Stack - have 2 sessions:
    Telecom com.android.server.telecom/Telecom (userId=0)
      ownerPid=648, ownerUid=1000, userId=0
      package=com.android.server.telecom
      active=false
      state=PlaybackState {state=NONE(0), position=0, buffered position=0, speed=0.0, updated=0, actions=0, custom actions=[], active item id=-1, error=null}
      metadata: size=0, description=null, null, null
    androidx.media3.session.id. com.universalmusic.player/androidx.media3.session.id./2 (userId=0)
      ownerPid=9009, ownerUid=10229, userId=0
      package=com.universalmusic.player
      mediaButtonReceiver=null
      active=true
      state=PlaybackState {state=PLAYING(3), position=3165, buffered position=45000, speed=1.0, updated=1201086, actions=7340027, custom actions=[Action:mName='Add to favorites, mIcon=2131034134, mExtras=Bundle[mParcelledData.dataSize=140]], active item id=0, error=null}
      volumeType=LOCAL, controlType=ABSOLUTE, max=0, current=0
      metadata: size=8, description=Gate track 00, Unknown artist, KainosGate
      queueTitle=null, size=0
Audio playback (lastly played comes first)
  uid=10229 packages=com.universalmusic.player
"""

NOTIFICATIONS = """\
  Notification List:
    NotificationRecord(0x0a1: pkg=com.universalmusic.player user=UserHandle{0} id=7 tag=null importance=2 key=0|com.universalmusic.player|7|null|10229: Notification(channel=sync shortcut=null contentView=null vibrate=null sound=null defaults=0 flags=FOREGROUND_SERVICE color=0x00000000 vis=PRIVATE))
      extras={
                android.title=String (Syncing with the PC)
      }
    NotificationRecord(0x057507c3: pkg=com.universalmusic.player user=UserHandle{0} id=1001 tag=null importance=2 key=0|com.universalmusic.player|1001|null|10229: Notification(channel=default_channel_id shortcut=null contentView=null vibrate=null sound=null defaults=0 flags=ONLY_ALERT_ONCE|NO_CLEAR|FOREGROUND_SERVICE color=0x00000000 category=transport groupKey=media3_group_key actions=5 vis=PUBLIC template=android.app.Notification$MediaStyle))
      flags=ONLY_ALERT_ONCE|NO_CLEAR|FOREGROUND_SERVICE
            extras={
                android.title=String (Gate track 00)
                android.template=String (android.app.Notification$MediaStyle)
                android.text=String (Unknown artist)
            }
"""

SERVICES = """\
ACTIVITY MANAGER SERVICES (dumpsys activity services)
  User 0 active services:
  * ServiceRecord{ec04fd1 u0 com.universalmusic.player/.platform.AndroidPlaybackService c:com.universalmusic.player}
    intent={act=androidx.media3.session.MediaSessionService cmp=com.universalmusic.player/.platform.AndroidPlaybackService}
    isForeground=false foregroundId=1001 types=0x00000002
  * ServiceRecord{ec04fd2 u0 com.universalmusic.player/.sync.HomeSyncService c:com.universalmusic.player}
    isForeground=true foregroundId=7 types=0x00000001
"""

HIERARCHY = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">\
<node index="0" text="" content-desc="" bounds="[0,0][1280,2856]">\
<node index="1" text="Gate track 03" content-desc="" bounds="[204,1506][454,1555]" />\
<node index="2" text="" content-desc="More actions for Gate track 03" bounds="[1142,1521][1202,1581]" />\
</node></hierarchy>"""


class PhonePathParsingTest(unittest.TestCase):
    def test_session_is_read_from_the_apps_block_not_another_package(self):
        self.assertEqual(phone_paths.parse_session(SESSIONS),
                         phone_paths.Session("PLAYING", 3165, 1.0, 1201086, "Gate track 00"))
        self.assertIsNone(phone_paths.parse_session(SESSIONS, "com.example.absent"))

    def test_a_session_without_state_reads_as_none(self):
        restored = "      package=com.universalmusic.player\n      active=false\n      metadata: size=2, description=null, null, null\n"
        self.assertEqual(phone_paths.parse_session(restored), phone_paths.Session("NONE", 0, 0.0, 0, None))

    def test_live_position_extrapolates_only_while_playing(self):
        playing = phone_paths.Session("PLAYING", 3165, 1.0, 1201086, "Gate track 00")
        self.assertEqual(playing.position_at(1211086), 13165)
        paused = phone_paths.Session("PAUSED", 3165, 0.0, 1201086, "Gate track 00")
        self.assertEqual(paused.position_at(1211086), 3165)

    def test_media_notification_skips_other_notifications_of_the_app(self):
        self.assertEqual(phone_paths.parse_media_notification(NOTIFICATIONS),
                         phone_paths.MediaNotification("Gate track 00", True))
        without_media = NOTIFICATIONS.split("    NotificationRecord(0x057507c3")[0]
        self.assertIsNone(phone_paths.parse_media_notification(without_media))

    def test_service_foreground_reads_the_playback_service_only(self):
        self.assertFalse(phone_paths.parse_service_foreground(SERVICES))
        self.assertIsNone(phone_paths.parse_service_foreground("ACTIVITY MANAGER SERVICES\n  (nothing)\n"))

    def test_find_node_by_text_and_description(self):
        self.assertEqual(phone_paths.find_node(HIERARCHY, text="Gate track 03"), (329, 1530))
        self.assertEqual(phone_paths.find_node(HIERARCHY, desc="More actions for Gate track 03"), (1172, 1551))
        self.assertIsNone(phone_paths.find_node(HIERARCHY, text="Gate track 3"))

    def test_crashes_and_anrs_of_the_app_are_found(self):
        crash = ("11-02 09:21:40.432 E AndroidRuntime: FATAL EXCEPTION: main\n"
                 "11-02 09:21:40.432 E AndroidRuntime: Process: com.universalmusic.player, PID: 9009\n"
                 "11-02 09:22:00.000 E AndroidRuntime: Process: com.android.systemui, PID: 900\n")
        events = ("11-02 10:00:00.000 I am_anr  : [0,9009,com.universalmusic.player,952647236,Input dispatching timed out]\n"
                  "11-02 10:00:01.000 I am_anr  : [0,900,com.android.systemui,0,Broadcast]\n")
        self.assertEqual(phone_paths.app_crashes(crash, events), [
            "11-02 09:21:40.432 E AndroidRuntime: Process: com.universalmusic.player, PID: 9009",
            "11-02 10:00:00.000 I am_anr  : [0,9009,com.universalmusic.player,952647236,Input dispatching timed out]",
        ])
        self.assertEqual(phone_paths.app_crashes("", ""), [])

    def test_fgs_decision_reports_policy_and_exemption(self):
        log = ("E ActivityManager: Background started FGS: Allowed [callingPackage: com.universalmusic.player; callingUid: 10229; "
               "uidState: SVC ; uidBFSL: n/a; BFGS denied: true; intent: Intent { act=x }; code:DENIED; tempAllowListReason:<null>; allowWiu:-1]\n"
               "I am_wtf  : [0,648,system_server,-1,ActivityManager,Background started FGS: Allowed [callingPackage: com.universalmusic.player; "
               "callingUid: 10229; uidState: SVC ; uidBFSL: n/a; BFGS denied: true; intent: Intent { act=x }; code:DENIED; tempAllowListReason:<null>]\n"
               "I ActivityManager: Background started FGS: Allowed [callingPackage: com.universalmusic.player; callingUid: 10229; "
               "uidState: FGS ; uidBFSL: [BFSL]; BFGS denied: false; intent: Intent { xflg=0x4 }; code:PROC_STATE_FGS; tempAllowListReason:<null>]\n")
        self.assertEqual(phone_paths.fgs_decision(log),
                         "Allowed (policy denied=true, code DENIED), Allowed (policy denied=false, code PROC_STATE_FGS)")
        self.assertEqual(phone_paths.fgs_decision(""), "no FGS start logged")

    def test_seed_audio_is_a_valid_wav_of_the_requested_length(self):
        import io
        import wave
        with wave.open(io.BytesIO(phone_paths.silent_wav(45_000))) as audio:
            self.assertEqual((audio.getframerate(), audio.getnframes(), audio.getsampwidth()), (8000, 360_000, 2))


if __name__ == "__main__":
    unittest.main()
