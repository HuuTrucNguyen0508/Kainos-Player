from __future__ import annotations

import asyncio
import logging
from pathlib import Path

from echo_gateway.config import Settings
from echo_gateway.library import LibraryTrack
from echo_gateway.paths import atomic_write
from echo_gateway.scan import run_quiet, file_stamp

logger = logging.getLogger("echo_gateway.art")

ART_SIZE_PX = 512
ART_TIMEOUT_S = 10.0
DEFAULT_ART = Path(__file__).with_name("assets") / "default-art.jpg"


def art_command(settings: Settings, source: Path) -> list[str]:
    return [
        settings.ffmpeg_bin,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-i",
        str(source),
        "-map",
        "0:v:0",
        "-frames:v",
        "1",
        "-vf",
        f"crop=min(iw\\,ih):min(iw\\,ih),scale={ART_SIZE_PX}:{ART_SIZE_PX}:flags=lanczos",
        "-c:v",
        "mjpeg",
        "-q:v",
        "3",
        "-f",
        "image2pipe",
        "pipe:1",
    ]


class ArtCache:
    """Square JPEG per track under STATE_DIR/art, valid for one size and mtime."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._dir = settings.state_dir / "art"
        self._default = DEFAULT_ART.read_bytes()
        self._extract_one_at_a_time = asyncio.Semaphore(1)

    async def jpeg_for(self, track: LibraryTrack) -> bytes:
        stamp = await asyncio.to_thread(file_stamp, track)
        if stamp is None:
            return self._default
        name = f"{track.track_id}-{stamp[0]}-{stamp[1]}"
        cached = self._dir / f"{name}.jpg"
        no_art = self._dir / f"{name}.none"
        async with self._extract_one_at_a_time:
            if await asyncio.to_thread(cached.is_file):
                return await asyncio.to_thread(cached.read_bytes)
            if await asyncio.to_thread(no_art.is_file):
                return self._default
            return await self._extract(track, cached, no_art)

    async def _extract(self, track: LibraryTrack, cached: Path, no_art: Path) -> bytes:
        try:
            result = await run_quiet(art_command(self._settings, track.absolute_path), ART_TIMEOUT_S)
        except OSError:
            logger.error("art_tool_failed category=spawn track=%s", track.track_id[:8])
            return self._default
        if result is None:
            logger.warning("art_extract_timeout track=%s", track.track_id[:8])
            return self._default
        exit_code, jpeg, _ = result
        target, payload = (cached, jpeg) if exit_code == 0 and jpeg else (no_art, b"")
        try:
            await asyncio.to_thread(atomic_write, target, payload)
        except OSError:
            logger.error("art_cache_failed category=state_dir")
        return payload or self._default
