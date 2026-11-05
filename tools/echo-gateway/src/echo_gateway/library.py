from __future__ import annotations

import hashlib
import logging
from dataclasses import dataclass
from pathlib import Path

from echo_gateway.paths import PathValidationError, resolve_under_root

logger = logging.getLogger("echo_gateway.library")

AUDIO_EXTENSIONS = {".flac", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav"}


@dataclass(frozen=True)
class LibraryTrack:
    track_id: str
    relative_path: str
    absolute_path: Path


def track_id_for(relative_path: str) -> str:
    digest = hashlib.sha256(relative_path.encode("utf-8")).hexdigest()
    return digest[:32]


def scan_library(music_root: Path) -> tuple[list[LibraryTrack], int]:
    """Tracks under music_root, and how many audio files could not be opened."""
    root = music_root.resolve()
    if not root.is_dir():
        logger.warning("library_missing category=music_root_invalid")
        return [], 0

    tracks: list[LibraryTrack] = []
    unreadable = 0
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix.lower() not in AUDIO_EXTENSIONS:
            continue
        try:
            resolved = resolve_under_root(path, root)
        except PathValidationError as exc:
            unreadable += exc.category == "unreadable"
            continue
        rel = resolved.relative_to(root).as_posix()
        tracks.append(
            LibraryTrack(
                track_id=track_id_for(rel),
                relative_path=rel,
                absolute_path=resolved,
            )
        )

    logger.info("library_scan category=ok track_count=%s", len(tracks))
    if unreadable:
        logger.warning("library_unreadable category=permissions file_count=%s", unreadable)
    return tracks, unreadable


class Library:
    """Current snapshot of MUSIC_ROOT, in sorted path order."""

    def __init__(self, music_root: Path) -> None:
        self._music_root = music_root
        self._tracks: tuple[LibraryTrack, ...] = ()
        self._by_id: dict[str, LibraryTrack] = {}
        self.unreadable_count = 0
        self.refresh()

    def refresh(self) -> int:
        tracks, self.unreadable_count = scan_library(self._music_root)
        self._by_id = {track.track_id: track for track in tracks}
        self._tracks = tuple(tracks)
        return len(tracks)

    @property
    def tracks(self) -> tuple[LibraryTrack, ...]:
        return self._tracks

    @property
    def track_count(self) -> int:
        return len(self._tracks)

    def ids(self) -> list[str]:
        return [track.track_id for track in self._tracks]

    def get(self, track_id: str) -> LibraryTrack | None:
        return self._by_id.get(track_id)
