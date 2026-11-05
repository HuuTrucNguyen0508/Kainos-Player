from __future__ import annotations

import copy
import hashlib
import json
import logging
import random
import re
import secrets
import threading
import time
from collections import OrderedDict
from collections.abc import Callable, Iterable, Iterator
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from echo_gateway.library import Library
from echo_gateway.paths import atomic_write

logger = logging.getLogger("echo_gateway.session")

MAX_START_MS = 24 * 60 * 60 * 1000
SESSION_FILE_VERSION = 1

_STREAM_TOKEN = re.compile(r"([0-9a-f]{32})\.(0|[1-9][0-9]{0,8})\.([0-9a-f]{8})")
_RESUMABLE_ACTIVITIES = {"PLAYING", "PAUSED", "STOPPED", "BUFFER_UNDERRUN"}
# What the device is doing with the stream it reports. Its STOPPED follows our pause.
_DEVICE_ACTIVITY = {
    "PLAYING": "playing",
    "BUFFER_UNDERRUN": "playing",
    "PAUSED": "paused",
    "STOPPED": "paused",
    "FINISHED": "stopped",
    "IDLE": "stopped",
}
ACTIVITIES = {"playing", "paused", "stopped"}


def wall_clock_ms() -> int:
    return time.time_ns() // 1_000_000


@dataclass(frozen=True)
class StreamToken:
    """Alexa stream token. Offsets Alexa reports are relative to base_ms."""

    track_id: str
    base_ms: int
    nonce: str

    def __str__(self) -> str:
        return f"{self.track_id}.{self.base_ms}.{self.nonce}"

    @classmethod
    def new(cls, track_id: str, base_ms: int = 0) -> StreamToken:
        return cls(track_id, min(base_ms, MAX_START_MS), secrets.token_hex(4))

    @classmethod
    def parse(cls, raw: Any) -> StreamToken | None:
        match = _STREAM_TOKEN.fullmatch(raw) if isinstance(raw, str) else None
        if match is None or int(match[2]) > MAX_START_MS:
            return None
        return cls(match[1], int(match[2]), match[3])


def reported_offset(raw: Any) -> int | None:
    return raw if type(raw) is int and raw >= 0 else None


@dataclass
class Session:
    """One Echo's queue. An empty order means nothing to resume."""

    order: list[str] = field(default_factory=list)
    position: int = 0
    offset_ms: int = 0
    shuffle: bool = True
    loop: bool = True
    repeat_one: bool = False
    current_token: str = ""
    queued_token: str = ""
    near_end: bool = False
    failures: int = 0
    failed_tracks: list[str] = field(default_factory=list)
    # What the device last said it was doing, for listeners. position_ms was the
    # position in the current track at epoch ms position_at_ms.
    activity: str = "stopped"
    position_ms: int = 0
    position_at_ms: int = 0
    last_event_at_ms: int = 0

    @property
    def current_id(self) -> str | None:
        if 0 <= self.position < len(self.order):
            return self.order[self.position]
        return None

    def start_shuffled(self, track_ids: Iterable[str]) -> None:
        order = list(track_ids)
        random.shuffle(order)
        vars(self).update(vars(Session(order=order)))

    def play_current(self, base_ms: int = 0) -> StreamToken:
        assert self.current_id is not None
        token = StreamToken.new(self.current_id, base_ms)
        self.current_token = str(token)
        self.offset_ms = token.base_ms
        self.queued_token = ""
        self.near_end = False
        return token

    def move_to(self, track_id: str) -> None:
        self.position = self.order.index(track_id)

    def successor(self) -> str | None:
        candidate = self._successor()
        if candidate is None:
            return None
        index = self.order.index(candidate)
        for _ in self.order:
            candidate = self.order[index]
            if candidate not in self.failed_tracks:
                return candidate
            index += 1
            if index == len(self.order):
                if not self.loop:
                    return None
                index = 0
        return None

    def _successor(self) -> str | None:
        """Track that follows the current one, or None at the end of the list.

        Finishing a shuffled cycle reshuffles behind the current track, so the
        position stays valid while it plays out and repeated calls agree.
        """
        if not self.order:
            return None
        if self.repeat_one:
            return self.current_id
        if self.position + 1 < len(self.order):
            return self.order[self.position + 1]
        if not self.loop:
            return None
        if not self.shuffle or len(self.order) == 1:
            return self.order[0]
        current = self.order[self.position]
        rest = [track_id for track_id in self.order if track_id != current]
        random.shuffle(rest)
        self.order = [current, *rest]
        self.position = 0
        return self.order[1]

    def set_shuffle(self, enabled: bool, library_order: list[str]) -> None:
        current = self.current_id
        self.shuffle = enabled
        if enabled:
            upcoming = self.order[self.position + 1 :]
            random.shuffle(upcoming)
            self.order[self.position + 1 :] = upcoming
        elif current in library_order:
            self.order = list(library_order)
            self.move_to(current)

    def owns(self, token: str) -> bool:
        """True if token is the stream in play. Stale events return False."""
        if token and token == self.queued_token:
            self.adopt(token)
        return bool(token) and token == self.current_token

    def adopt(self, token: str) -> None:
        """The device says this stream is playing."""
        parsed = StreamToken.parse(token)
        if token != self.queued_token or parsed is None or parsed.track_id not in self.order:
            return
        self.move_to(parsed.track_id)
        self.current_token = token
        self.offset_ms = parsed.base_ms
        self.queued_token = ""
        self.near_end = False

    def record_position(self, offset_ms: Any) -> None:
        parsed = StreamToken.parse(self.current_token)
        offset = reported_offset(offset_ms)
        if parsed is not None and offset is not None:
            self.offset_ms = parsed.base_ms + offset

    def stream_position(self, token: str, offset_ms: Any) -> int:
        """Position in the track for an offset Alexa reports on token's stream."""
        parsed = StreamToken.parse(token)
        base = parsed.base_ms if parsed is not None else 0
        return base + (reported_offset(offset_ms) or 0)

    def mark(self, activity: str, now_ms: int, position_ms: int) -> None:
        assert activity in ACTIVITIES
        self.activity = activity
        self.position_ms = position_ms
        self.position_at_ms = now_ms
        self.last_event_at_ms = now_ms

    def observe_device(self, context: dict[str, Any], now_ms: int) -> None:
        """Take the activity the device reports for the stream this session owns."""
        audio = context.get("AudioPlayer") or {}
        activity = _DEVICE_ACTIVITY.get(audio.get("playerActivity"))
        token = audio.get("token") or ""
        if activity is None:
            return
        if audio.get("playerActivity") == "FINISHED" and self.queued_token:
            # The queued stream starts next; PlaybackStarted moves the session to it.
            return
        # A queued stream the device has finished or never started is not in play.
        owned = self.owns(token) if activity != "stopped" else bool(token) and token == self.current_token
        if owned:
            self.mark(activity, now_ms, self.stream_position(token, audio.get("offsetInMilliseconds")))

    def upcoming(self, limit: int) -> list[str]:
        """Tracks that play after the current one, without changing the session.

        Stops where a shuffled list would reshuffle, since that order is not drawn yet.
        """
        if self.current_id is None:
            return []
        if self.repeat_one:
            return [self.current_id]
        probe = copy.deepcopy(self)
        upcoming: list[str] = []
        while len(upcoming) < limit:
            at_end = probe.position + 1 >= len(probe.order)
            if at_end and probe.loop and probe.shuffle and len(probe.order) > 1:
                break
            track_id = probe.successor()
            if track_id is None:
                break
            upcoming.append(track_id)
            probe.move_to(track_id)
        return upcoming

    def record_context(self, context: dict[str, Any]) -> None:
        audio = context.get("AudioPlayer") or {}
        if audio.get("playerActivity") in _RESUMABLE_ACTIVITIES and self.owns(audio.get("token") or ""):
            self.record_position(audio.get("offsetInMilliseconds"))

    def reconcile(self, known_ids: set[str]) -> None:
        """Drop ids no longer in the library and keep going from the next survivor."""
        if known_ids.issuperset(self.order):
            return
        current = self.current_id
        survivors_before = sum(track_id in known_ids for track_id in self.order[: self.position])
        self.order = [track_id for track_id in self.order if track_id in known_ids]
        if not self.order:
            vars(self).update(vars(Session()))
            return
        self.position = survivors_before if survivors_before < len(self.order) else 0
        if current not in known_ids:
            self.offset_ms = 0
            self.current_token = ""
            self.activity = "stopped"
            self.position_ms = 0
        queued = StreamToken.parse(self.queued_token)
        if queued is not None and queued.track_id not in known_ids:
            self.queued_token = ""
            self.near_end = False

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> Session:
        order = raw["order"]
        if not isinstance(order, list) or not all(isinstance(item, str) for item in order):
            raise ValueError("order must be a list of track ids")
        session = cls(
            order=order,
            position=int(raw["position"]),
            offset_ms=int(raw["offset_ms"]),
            shuffle=bool(raw["shuffle"]),
            loop=bool(raw["loop"]),
            repeat_one=bool(raw["repeat_one"]),
            current_token=str(raw["current_token"]),
            queued_token=str(raw["queued_token"]),
            near_end=bool(raw["near_end"]),
            failures=int(raw["failures"]),
            failed_tracks=list(raw.get("failed_tracks", [])),
            activity=raw.get("activity") if raw.get("activity") in ACTIVITIES else "stopped",
            position_ms=int(raw.get("position_ms", raw["offset_ms"])),
            position_at_ms=int(raw.get("position_at_ms", 0)),
            last_event_at_ms=int(raw.get("last_event_at_ms", 0)),
        )
        if order and not 0 <= session.position < len(order):
            session.position = 0
        return session


def device_key(device_id: str) -> str:
    return hashlib.sha256(device_id.encode("utf-8")).hexdigest()[:32]


class SessionStore:
    """Sessions by hashed device id, one JSON file each under STATE_DIR/sessions."""

    def __init__(
        self,
        state_dir: Path,
        library: Library,
        clock: Callable[[], int] = wall_clock_ms,
    ) -> None:
        self._dir = state_dir / "sessions"
        self._library = library
        self.now = clock
        self._lock = threading.Lock()
        self._sessions: dict[str, Session] = {}
        self._saved: dict[str, str] = {}
        self._responses: OrderedDict[tuple[str, str], tuple[float, dict]] = OrderedDict()
        self._load()

    def cached_response(self, key: str, request_id: str) -> dict | None:
        # Called under open()'s lock. Keep retries for the signature timestamp window.
        now = time.monotonic()
        while self._responses and next(iter(self._responses.values()))[0] < now - 300:
            self._responses.popitem(last=False)
        cached = self._responses.get((key, request_id))
        return copy.deepcopy(cached[1]) if cached is not None else None

    def remember_response(self, key: str, request_id: str, response: dict) -> None:
        if request_id:
            self._responses[(key, request_id)] = (time.monotonic(), copy.deepcopy(response))
            while len(self._responses) > 512:
                self._responses.popitem(last=False)

    @property
    def count(self) -> int:
        with self._lock:
            return sum(1 for session in self._sessions.values() if session.order)

    @contextmanager
    def open(self, key: str) -> Iterator[Session]:
        with self._lock:
            session = self._sessions.setdefault(key, Session())
            known_ids = set(self._library.ids())
            if known_ids:
                session.reconcile(known_ids)
            try:
                yield session
            finally:
                self._persist(key, session)

    def snapshot(self) -> dict[str, Session]:
        """Copies of every session, reconciled with the library. Changes and writes nothing."""
        with self._lock:
            sessions = {key: copy.deepcopy(session) for key, session in self._sessions.items()}
        known_ids = set(self._library.ids())
        if known_ids:
            for session in sessions.values():
                session.reconcile(known_ids)
        return sessions

    def _load(self) -> None:
        try:
            paths = sorted(self._dir.glob("*.json"))
        except OSError:
            logger.warning("session_load_failed category=state_dir")
            return
        for path in paths:
            try:
                raw = json.loads(path.read_text(encoding="utf-8"))
                session = Session.from_dict(raw)
                if not session.last_event_at_ms:
                    # Saved before events were timed: the last write is the best guess.
                    session.last_event_at_ms = path.stat().st_mtime_ns // 1_000_000
                    session.position_at_ms = session.last_event_at_ms
            except (OSError, ValueError, KeyError, TypeError):
                logger.warning("session_load_failed category=corrupt_file key=%s", path.stem[:8])
                continue
            self._sessions[path.stem] = session
            self._saved[path.stem] = _encode(session)
        logger.info("session_load category=ok sessions=%s", len(self._sessions))

    def _persist(self, key: str, session: Session) -> None:
        payload = _encode(session)
        if payload == self._saved.get(key, _encode(Session())):
            return
        try:
            atomic_write(self._dir / f"{key}.json", payload.encode("utf-8"))
        except OSError:
            logger.error("session_persist_failed category=state_dir key=%s", key[:8])
            return
        self._saved[key] = payload


def _encode(session: Session) -> str:
    return json.dumps({"version": SESSION_FILE_VERSION, **asdict(session)}, separators=(",", ":"))
