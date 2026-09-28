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


def scan_library(music_root: Path) -> list[LibraryTrack]:
    root = music_root.resolve()
    if not root.is_dir():
        logger.warning("library_missing category=music_root_invalid")
        return []

    tracks: list[LibraryTrack] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix.lower() not in AUDIO_EXTENSIONS:
            continue
        try:
            resolved = resolve_under_root(path, root)
        except PathValidationError:
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
    return tracks


def index_by_id(tracks: list[LibraryTrack]) -> dict[str, LibraryTrack]:
    return {track.track_id: track for track in tracks}
