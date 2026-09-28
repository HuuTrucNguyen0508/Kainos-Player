from __future__ import annotations

import logging
import random
import threading
from dataclasses import dataclass, field

from echo_gateway.config import Settings
from echo_gateway.library import LibraryTrack, index_by_id, scan_library

logger = logging.getLogger("echo_gateway.playlist")


@dataclass
class PlaylistSession:
    order: list[LibraryTrack] = field(default_factory=list)
    position: int = 0

    @property
    def current(self) -> LibraryTrack | None:
        if not self.order or self.position < 0 or self.position >= len(self.order):
            return None
        return self.order[self.position]

    def peek_next(self) -> LibraryTrack | None:
        nxt = self.position + 1
        if nxt >= len(self.order):
            return None
        return self.order[nxt]

    def advance(self) -> LibraryTrack | None:
        if self.position + 1 >= len(self.order):
            return None
        self.position += 1
        return self.current

    def retreat(self) -> LibraryTrack | None:
        if self.position <= 0:
            return self.current
        self.position -= 1
        return self.current


class PlaylistController:
    """In-memory shuffled playlist over MUSIC_ROOT. Fresh shuffle on each start."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._lock = threading.Lock()
        self._tracks: list[LibraryTrack] = []
        self._by_id: dict[str, LibraryTrack] = {}
        self._session = PlaylistSession()
        self.refresh()

    def refresh(self) -> int:
        tracks = scan_library(self._settings.music_root)
        with self._lock:
            self._tracks = tracks
            self._by_id = index_by_id(tracks)
        return len(tracks)

    @property
    def track_count(self) -> int:
        return len(self._tracks)

    def get(self, track_id: str) -> LibraryTrack | None:
        return self._by_id.get(track_id)

    def start_shuffled(self) -> LibraryTrack | None:
        with self._lock:
            if not self._tracks:
                self._tracks = scan_library(self._settings.music_root)
                self._by_id = index_by_id(self._tracks)
            if not self._tracks:
                logger.warning("playlist_empty category=no_tracks")
                return None
            order = list(self._tracks)
            random.shuffle(order)
            self._session = PlaylistSession(order=order, position=0)
            current = self._session.current
            logger.info(
                "playlist_shuffle category=ok track_count=%s first_id=%s",
                len(order),
                (current.track_id[:8] if current else ""),
            )
            return current

    def current(self) -> LibraryTrack | None:
        with self._lock:
            return self._session.current

    def next_track(self, *, advance: bool) -> LibraryTrack | None:
        with self._lock:
            if advance:
                return self._session.advance()
            return self._session.peek_next()

    def previous_track(self) -> LibraryTrack | None:
        with self._lock:
            return self._session.retreat()

    def sync_to_token(self, token: str) -> LibraryTrack | None:
        """Align session position to the track Alexa reports as playing."""
        if not token:
            return None
        with self._lock:
            for index, track in enumerate(self._session.order):
                if track.track_id == token:
                    self._session.position = index
                    return track
            known = self._by_id.get(token)
            if known is not None:
                logger.info(
                    "playlist_sync_miss category=token_not_in_order track_id=%s",
                    token[:8],
                )
            return known

    def stream_url_for(self, track: LibraryTrack) -> str:
        base = self._settings.public_base_url.rstrip("/")
        return f"{base}/stream/t/{self._settings.public_stream_token}/{track.track_id}"
