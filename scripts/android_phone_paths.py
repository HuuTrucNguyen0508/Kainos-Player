"""Black-box phone-path stages for the Android release gate.

They drive the installed app the way the system does: media keys through
`cmd media_session dispatch` (the path a headset, lock screen or car takes), the
notification shade, the launcher, screen off and process death. They read only
what Android reports: `dumpsys media_session`, `dumpsys notification`, the service
record and logcat. No test code runs in the app process, so the process lives and
dies on the same terms as on a phone.

Emulator only: the stages clear app data, push audio into shared storage and
change device_config for one stage, restoring it afterwards.
"""
from __future__ import annotations

import dataclasses
import re
import struct
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Callable

APP = "com.universalmusic.player"
SEED_DIR = "/sdcard/Music/KainosGate"
TRACKS = 16
TRACK_MS = 45_000
RAPID_SKIPS = 10
RAPID_GAP_SECONDS = 0.1
LOCKED_SECONDS = 50
BACKGROUND_GRACE_SECONDS = 11
# device_config "media" values that strip the exemptions letting a media key start a foreground
# service from the background. The service starts within 1 ms of the key, so a 1 ms allowlist still
# covers it; Android rejects a 0 ms allowlist and grants none. The engaged-session window takes 1.
MEDIA_EXEMPTIONS = {
    "media_session_calback_fgs_allowlist_duration_ms": "0",
    "media_session_callback_fgs_while_in_use_temp_allow_duration_ms": "0",
    "media_session_temp_user_engaged_duration_ms": "1",
}


def track(index: int) -> str:
    return f"Gate track {index:02d}"


class StageFailed(Exception):
    pass


@dataclasses.dataclass(frozen=True)
class Session:
    """The app's MediaSession as Android reports it to controllers."""
    state: str
    position_ms: int
    speed: float
    updated_ms: int
    title: str | None

    def position_at(self, now_ms: int) -> int:
        if self.state != "PLAYING":
            return self.position_ms
        return self.position_ms + int((now_ms - self.updated_ms) * self.speed)


@dataclasses.dataclass(frozen=True)
class MediaNotification:
    title: str | None
    foreground_service: bool


def parse_session(dumpsys: str, package: str = APP) -> Session | None:
    lines = dumpsys.splitlines()
    start = next((i for i, line in enumerate(lines) if line.strip() == f"package={package}"), None)
    if start is None:
        return None
    state = title = None
    for line in lines[start + 1:]:
        # Fields of one session are indented six spaces; anything shallower starts the next block.
        if line.strip() and not line.startswith("      "):
            break
        if match := re.search(r"state=PlaybackState \{state=([A-Z_]+)\(\d+\), position=(-?\d+), "
                              r"buffered position=-?\d+, speed=([\d.]+), updated=(\d+)", line):
            state = match
        elif match := re.search(r"metadata: size=\d+, description=(.*)", line):
            description = match.group(1).split(", ")[0]
            title = None if description == "null" else description
    if state is None:
        return Session("NONE", 0, 0.0, 0, title)
    return Session(state.group(1), int(state.group(2)), float(state.group(3)), int(state.group(4)), title)


def parse_media_notification(dumpsys: str, package: str = APP) -> MediaNotification | None:
    records = re.split(r"\n(?=\s+NotificationRecord\()", dumpsys)
    for record in records:
        head = record.strip().splitlines()[0] if record.strip() else ""
        if f"pkg={package} " not in head or "template=android.app.Notification$MediaStyle" not in head:
            continue
        title = re.search(r"android\.title=String \((.*)\)", record)
        return MediaNotification(title.group(1) if title else None, "FOREGROUND_SERVICE" in head)
    return None


def parse_service_foreground(dumpsys: str, service: str = ".platform.AndroidPlaybackService") -> bool | None:
    """None when the playback service is not running at all."""
    block = dumpsys.partition(f"{APP}/{service}")[2]
    if not block:
        return None
    match = re.search(r"isForeground=(true|false)", block.split("* ServiceRecord")[0])
    return match is not None and match.group(1) == "true"


def find_node(hierarchy: str, *, text: str | None = None, desc: str | None = None) -> tuple[int, int] | None:
    """Centre of the first node with exactly this text or content description."""
    for node in ElementTree.fromstring(hierarchy).iter("node"):
        if (text is not None and node.get("text") != text) or (desc is not None and node.get("content-desc") != desc):
            continue
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
        return (left + right) // 2, (top + bottom) // 2
    return None


def app_crashes(crash_log: str, events_log: str, package: str = APP) -> list[str]:
    problems = [line.strip() for line in crash_log.splitlines() if f"Process: {package}," in line]
    problems += [line.strip() for line in events_log.splitlines() if "am_anr" in line and package in line]
    return problems


def fgs_decision(logcat: str, package: str = APP) -> str:
    """How ActivityManager answered the playback service's background foreground start."""
    decisions = re.findall(r"Background started FGS: (\w+) \[callingPackage: " + re.escape(package) +
                           r";.*?BFGS denied: (\w+);.*?code:(\w+);", logcat)
    unique = dict.fromkeys(f"{verdict} (policy denied={denied}, code {code})" for verdict, denied, code in decisions)
    return ", ".join(unique) or "no FGS start logged"


def silent_wav(milliseconds: int, rate: int = 8000) -> bytes:
    samples = rate * milliseconds // 1000
    return (b"RIFF" + struct.pack("<I", 36 + samples * 2) + b"WAVEfmt " +
            struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16) + b"data" + struct.pack("<I", samples * 2) +
            bytes(samples * 2))


def track_index(title: str | None) -> int | None:
    match = re.fullmatch(r"Gate track (\d+)", title or "")
    return int(match.group(1)) if match else None


class Phone:
    """adb views of the app. `adb` is the gate's serial-pinned runner."""

    def __init__(self, adb: Callable[..., subprocess.CompletedProcess[str]]):
        self.adb = adb

    def shell(self, *command: str) -> str:
        return self.adb("shell", *command).stdout

    def session(self) -> Session | None:
        return parse_session(self.shell("dumpsys", "media_session"))

    def notification(self) -> MediaNotification | None:
        return parse_media_notification(self.shell("dumpsys", "notification", "--noredact"))

    def foreground(self) -> bool | None:
        return parse_service_foreground(self.shell("dumpsys", "activity", "services", APP))

    def pid(self) -> str:
        return self.shell("pidof", APP).strip()

    def uptime_ms(self) -> int:
        """Same clock as the session's `updated` field (elapsedRealtime)."""
        return int(float(self.shell("cat", "/proc/uptime").split()[0]) * 1000)

    def key(self, name: str) -> None:
        self.shell("cmd", "media_session", "dispatch", name)

    def launch(self) -> None:
        self.shell("monkey", "-p", APP, "-c", "android.intent.category.LAUNCHER", "1")

    def hierarchy(self) -> str:
        for _ in range(5):
            if "UI hierchary dumped" in self.shell("uiautomator", "dump", "/sdcard/kainos-gate-ui.xml"):
                return self.shell("cat", "/sdcard/kainos-gate-ui.xml")
            time.sleep(0.5)
        raise StageFailed("uiautomator could not dump the screen")

    def wait_node(self, timeout: float = 15, **selector) -> tuple[int, int]:
        deadline = time.monotonic() + timeout
        while True:
            node = find_node(self.hierarchy(), **selector)
            if node:
                return node
            if time.monotonic() > deadline:
                raise StageFailed(f"no node {selector} on screen")
            time.sleep(0.5)

    def tap(self, timeout: float = 15, **selector) -> None:
        x, y = self.wait_node(timeout, **selector)
        self.shell("input", "tap", str(x), str(y))

    def wait_session(self, check: Callable[[Session], bool], what: str, timeout: float = 15) -> Session:
        deadline = time.monotonic() + timeout
        while True:
            session = self.session()
            if session and check(session):
                return session
            if time.monotonic() > deadline:
                raise StageFailed(f"{what}; Android reports {session}")
            time.sleep(0.3)

    def wait_notification(self, title: str, timeout: float = 10) -> MediaNotification:
        """Media3 posts the notification a moment after the session changes."""
        deadline = time.monotonic() + timeout
        while True:
            notification = self.notification()
            if notification and notification.title == title:
                return notification
            if time.monotonic() > deadline:
                raise StageFailed(f"no MediaStyle notification titled {title}: {notification}")
            time.sleep(0.5)

    def wait_playing(self, title: str, timeout: float = 15) -> Session:
        return self.wait_session(lambda s: s.state == "PLAYING" and s.title == title, f"expected PLAYING {title}", timeout)

    def wake_and_unlock(self) -> None:
        self.shell("input", "keyevent", "KEYCODE_WAKEUP")
        self.shell("wm", "dismiss-keyguard")
        time.sleep(1)


class PhonePaths:
    def __init__(self, phone: Phone, push: Callable[[Path, str], bool]):
        self.phone = phone
        self.push = push
        self.pid = ""

    def same_process(self) -> None:
        now = self.phone.pid()
        if now != self.pid:
            raise StageFailed(f"app process changed from {self.pid or 'none'} to {now or 'none'}")

    def seed_and_launch(self) -> str:
        p = self.phone
        p.shell("pm", "clear", APP)
        p.shell("rm", "-rf", SEED_DIR)
        with tempfile.TemporaryDirectory() as work:
            audio = silent_wav(TRACK_MS)
            for index in range(TRACKS):
                (Path(work) / f"{track(index)}.wav").write_bytes(audio)
            if not self.push(Path(work), SEED_DIR):
                raise StageFailed("adb push of the seed tracks failed")
        p.shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{SEED_DIR}")
        deadline = time.monotonic() + 30
        while (scanned := p.shell("content", "query", "--uri", "content://media/external/audio/media",
                                  "--projection", "_display_name", "--where", "\"is_music=1 AND _display_name LIKE 'Gate track %'\"")
               .count("Row:")) < TRACKS:
            if time.monotonic() > deadline:
                raise StageFailed(f"MediaStore scanned {scanned} of {TRACKS} seed tracks")
            time.sleep(1)
        for permission in ("READ_MEDIA_AUDIO", "POST_NOTIFICATIONS"):
            p.shell("pm", "grant", APP, f"android.permission.{permission}")
        p.launch()
        p.tap(text="Library")
        p.wait_node(text=track(0), timeout=30)
        return f"{TRACKS} silent tracks of {TRACK_MS // 1000} s in MediaStore; Library lists {track(0)}"

    def play_from_library(self) -> str:
        p = self.phone
        p.tap(text=track(0))
        p.wait_playing(track(0))
        p.wait_node(desc="Close now playing")
        self.pid = p.pid()
        return f"{track(0)} PLAYING in MediaSession, Now Playing open, pid {self.pid}"

    def media_notification(self) -> str:
        p = self.phone
        notification = p.wait_notification(track(0))
        if not notification.foreground_service or p.foreground() is not True:
            raise StageFailed(f"notification {notification}, service foreground={p.foreground()}")
        return f"MediaStyle notification '{notification.title}' with FOREGROUND_SERVICE; playback service is foreground"

    def headset_keys(self) -> str:
        p = self.phone
        p.key("pause")
        p.wait_session(lambda s: s.state == "PAUSED" and s.title == track(0), "pause key did not pause")
        p.key("play")
        p.wait_playing(track(0))
        # Previous goes back only within the first 3 s of a track, so it follows Next at once.
        p.key("next")
        p.wait_playing(track(1))
        p.key("previous")
        p.wait_playing(track(0))
        p.wait_notification(track(0))
        p.key("next")
        p.wait_playing(track(1))
        p.wait_notification(track(1))
        self.same_process()
        return f"pause, play, next, previous, next; session and notification followed each to {track(1)}"

    def rapid_skips(self) -> str:
        p = self.phone
        target = track((track_index(p.session().title) or 0) + RAPID_SKIPS)
        started = time.monotonic()
        for _ in range(RAPID_SKIPS):
            p.key("next")
            time.sleep(RAPID_GAP_SECONDS)
        burst = time.monotonic() - started
        session = p.wait_playing(target, timeout=20)
        time.sleep(1.5)
        later = p.session()
        if later is None or later.state != "PLAYING" or later.title != target or later.position_at(p.uptime_ms()) <= session.position_ms:
            raise StageFailed(f"playback did not settle and advance on {target}: {later}")
        p.wait_notification(target)
        self.same_process()
        return f"{RAPID_SKIPS} next keys in {burst:.1f} s landed on {target}, PLAYING and advancing; notification matches"

    def background_and_lock(self) -> str:
        p = self.phone
        before = p.session()
        start_ms = p.uptime_ms()
        p.shell("input", "keyevent", "KEYCODE_HOME")
        p.shell("input", "keyevent", "KEYCODE_SLEEP")
        time.sleep(LOCKED_SECONDS)
        if "mWakefulness=Asleep" not in p.shell("dumpsys", "power"):
            raise StageFailed("screen did not stay off")
        after = p.session()
        self.same_process()
        if after is None or after.state != "PLAYING" or p.foreground() is not True:
            raise StageFailed(f"locked playback stopped: {after}, foreground={p.foreground()}")
        played = ((track_index(after.title) or 0) - (track_index(before.title) or 0)) * TRACK_MS \
            + after.position_at(p.uptime_ms()) - before.position_at(start_ms)
        if played < (LOCKED_SECONDS - 5) * 1000:
            raise StageFailed(f"only {played} ms played in {LOCKED_SECONDS} s locked ({before.title} -> {after.title})")
        if track_index(after.title) != (track_index(before.title) or 0) + 1:
            raise StageFailed(f"expected one natural track end while locked: {before.title} -> {after.title}")
        p.key("pause")
        p.wait_session(lambda s: s.state == "PAUSED", "pause key while locked did not pause")
        p.key("play")
        p.wait_playing(after.title)
        p.wake_and_unlock()
        return (f"{played / 1000:.0f} s played in {LOCKED_SECONDS} s with the screen off, natural end "
                f"{before.title} -> {after.title}; pause and play keys worked while locked")

    def notification_opens_now_playing(self) -> str:
        p = self.phone
        title = (p.session() or Session("NONE", 0, 0.0, 0, None)).title
        if title is None:
            raise StageFailed("MediaSession has no title to look for in the shade")
        p.launch()
        if find_node(p.hierarchy(), desc="Close now playing"):
            p.shell("input", "keyevent", "KEYCODE_BACK")
            time.sleep(1)
        if find_node(p.hierarchy(), desc="Close now playing"):
            raise StageFailed("Back did not close Now Playing")
        p.shell("input", "keyevent", "KEYCODE_HOME")
        time.sleep(1)
        p.shell("cmd", "statusbar", "expand-notifications")
        p.tap(text=title, timeout=10)
        p.wait_node(desc="Close now playing")
        resumed = re.search(r"topResumedActivity=.*? (\S+/\S+)", p.shell("dumpsys", "activity", "activities"))
        if not resumed or not resumed.group(1).startswith(APP):
            raise StageFailed(f"top activity is {resumed.group(1) if resumed else 'unknown'}")
        self.same_process()
        return f"tapped '{title}' in the shade from the home screen; Now Playing opened in {resumed.group(1)}"

    def background_play_after_demotion(self) -> str:
        """The 2026-11-02 crash: Media3 promoting the service from the background after
        it left the foreground. Media3 waits 10 minutes of pause before leaving it;
        Android's background restriction demotes it at once and is then lifted. Every
        media key grants a 10 s exemption, so the exemptions are cut before the first key."""
        p = self.phone
        try:
            for name, value in MEDIA_EXEMPTIONS.items():
                p.shell("device_config", "put", "media", name, value)
            p.key("pause")
            p.wait_session(lambda s: s.state == "PAUSED", "pause key did not pause")
            p.shell("input", "keyevent", "KEYCODE_HOME")
            p.shell("cmd", "appops", "set", APP, "RUN_ANY_IN_BACKGROUND", "ignore")
            deadline = time.monotonic() + 10
            while p.foreground() is not False:
                if time.monotonic() > deadline:
                    raise StageFailed("background restriction did not take the service out of the foreground")
                time.sleep(0.5)
            p.shell("cmd", "appops", "set", APP, "RUN_ANY_IN_BACKGROUND", "allow")
            p.shell("cmd", "media_session", "expire-temp-engaged-sessions")
            # Outlast the earlier stage's last media key (10 s) and fg_to_bg_fgs_grace_duration (5 s).
            time.sleep(BACKGROUND_GRACE_SECONDS)
            # Crash and events buffers stay: the final stage reads them for the whole run.
            p.adb("logcat", "-c", "-b", "main", "-b", "system")
            p.key("play")
            time.sleep(5)
            log = p.adb("logcat", "-d", "-b", "main", "-b", "system").stdout
        finally:
            p.shell("cmd", "appops", "set", APP, "RUN_ANY_IN_BACKGROUND", "allow")
            for name in MEDIA_EXEMPTIONS:
                p.shell("device_config", "delete", "media", name)
        self.same_process()
        session, foreground = p.session(), p.foreground()
        decision = fgs_decision(log)
        denied = "foreground promotion denied" in log
        if "policy denied=true" not in decision and not denied:
            raise StageFailed(f"an exemption still let the start through, so the path was not exercised; ActivityManager: {decision}")
        if session and session.state == "PLAYING" and foreground:
            return f"background start refused by policy, yet the service came back to the foreground and plays; ActivityManager: {decision}"
        if session and session.state == "PAUSED" and denied:
            return f"promotion denied, transport paused without a crash; ActivityManager: {decision}"
        raise StageFailed(f"session {session}, foreground={foreground}; ActivityManager: {decision}")

    def restart_restores_paused(self) -> str:
        p = self.phone
        title = (p.session() or Session("NONE", 0, 0.0, 0, None)).title
        if title is None:
            raise StageFailed("MediaSession has no title before the restart")
        p.shell("am", "force-stop", APP)
        time.sleep(1)
        p.launch()
        p.wait_node(text=title, timeout=20)
        # Home's session button reads "Resume" only for a restored, paused session; history alone gives "Play".
        p.wait_node(desc="Resume")
        time.sleep(3)
        session = p.session()
        if session is not None and session.state == "PLAYING":
            raise StageFailed(f"restored session autoplayed: {session}")
        self.pid = p.pid()
        return f"after force-stop Home offers Resume for {title}; MediaSession {session.state if session else 'absent'}"

    def play_next_from_menu(self) -> str:
        p = self.phone
        p.tap(text="Library")
        p.tap(desc=f"More actions for {track(3)}")
        p.tap(text="Play next")
        time.sleep(1)
        p.key("next")
        p.wait_session(lambda s: s.title == track(3), f"next after Play next did not reach {track(3)}")
        self.same_process()
        return f"track menu Play next on {track(3)}, then the next key played it"

    def stages(self) -> list[tuple[str, Callable[[], str]]]:
        return [
            ("Phone: seed library and launch", self.seed_and_launch),
            ("Phone: play from Library", self.play_from_library),
            ("Phone: media notification", self.media_notification),
            ("Phone: headset and lock-screen keys", self.headset_keys),
            ("Phone: rapid skips", self.rapid_skips),
            ("Phone: background with the screen off", self.background_and_lock),
            # Before the notification tap, whose PendingIntent exempts the app for 30 s.
            ("Phone: background play after demotion", self.background_play_after_demotion),
            ("Phone: notification opens Now Playing", self.notification_opens_now_playing),
            ("Phone: restart restores paused", self.restart_restores_paused),
            ("Phone: track menu Play next", self.play_next_from_menu),
        ]


def crash_check(phone: Phone) -> str:
    problems = app_crashes(phone.adb("logcat", "-d", "-b", "crash").stdout,
                           phone.adb("logcat", "-d", "-b", "events").stdout)
    if problems:
        raise StageFailed("; ".join(problems[:3]))
    return "no app crash or ANR in logcat"


def remove_seed(phone: Phone) -> None:
    phone.shell("rm", "-rf", SEED_DIR)
    phone.shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{SEED_DIR}")
