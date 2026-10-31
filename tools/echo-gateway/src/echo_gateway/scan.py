from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import math
import re
import threading
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Any

from echo_gateway.config import Settings
from echo_gateway.library import Library, LibraryTrack
from echo_gateway.paths import atomic_write

logger = logging.getLogger("echo_gateway.scan")

SCAN_FILE_VERSION = 1
SCAN_PROBE_TIMEOUT_S = 30.0
MEASURE_TIMEOUT_S = 15 * 60.0
RESCAN_INTERVAL_S = 60 * 60.0
MEASUREMENT_FLOOR_LUFS = -70.0
# AAC encoding can overshoot the input peak. See the encoded-signal regressions.
TRUE_PEAK_CEILING_DB = -4.0
UNKNOWN_ARTIST = "Artiste inconnu"
NICE_EXEC_FAILED = {125, 126, 127}

_DOWNLOADER_PREFIX = re.compile(r"^y2mate\.[a-z]+\s*-\s*", re.IGNORECASE)
_DOWNLOADER_SUFFIX = re.compile(r"(?:_[A-Za-z0-9_-]{11})?_\d+kbps$", re.IGNORECASE)
_LOUDNESS = re.compile(r"\bI:\s+(-?(?:\d+(?:\.\d+)?|inf|nan)) LUFS")
_TRUE_PEAK = re.compile(r"\bPeak:\s+(-?(?:\d+(?:\.\d+)?|inf|nan)) dBFS")


@dataclass(frozen=True)
class TrackMetadata:
    title: str
    artist: str


@dataclass
class ScanEntry:
    size: int
    mtime_ns: int
    title: str
    artist: str
    measured: bool = False
    lufs: float | None = None
    true_peak: float | None = None


def _filename_tags(relative_path: str) -> tuple[str, str | None]:
    stem = PurePosixPath(relative_path).stem
    stem = _DOWNLOADER_SUFFIX.sub("", _DOWNLOADER_PREFIX.sub("", stem))
    stem = " ".join(stem.split())
    artist, separator, title = stem.partition(" - ")
    if separator and artist and title:
        return title, artist
    return stem, None


def filename_metadata(relative_path: str) -> TrackMetadata:
    title, artist = _filename_tags(relative_path)
    return TrackMetadata(title=title, artist=artist or UNKNOWN_ARTIST)


def metadata_from_probe(raw_json: str | bytes, relative_path: str) -> TrackMetadata:
    """Tags win. A missing field comes from the filename."""
    try:
        probe = json.loads(raw_json)
    except ValueError:
        return filename_metadata(relative_path)
    tags: dict[str, str] = {}
    streams = probe.get("streams") or [{}]
    for source in (streams[0], probe.get("format") or {}):
        for key, value in (source.get("tags") or {}).items():
            if str(value).strip():
                tags[key.lower()] = " ".join(str(value).split())
    file_title, file_artist = _filename_tags(relative_path)
    return TrackMetadata(
        title=tags.get("title") or file_title,
        artist=tags.get("artist") or tags.get("album_artist") or file_artist or UNKNOWN_ARTIST,
    )


def parse_ebur128(stderr: str) -> tuple[float, float | None] | None:
    """Integrated loudness and true peak from an ebur128 summary. Peak is None for silence."""
    summary = stderr.rpartition("Summary:")[2]
    loudness = _LOUDNESS.search(summary)
    peak = _TRUE_PEAK.search(summary)
    if loudness is None or peak is None:
        return None
    lufs = float(loudness[1])
    true_peak = float(peak[1])
    if not math.isfinite(lufs):
        return None
    return lufs, true_peak if math.isfinite(true_peak) else None


def compute_gain_db(target_lufs: float, lufs: float | None, true_peak: float | None) -> float:
    if lufs is None or true_peak is None or lufs <= MEASUREMENT_FLOOR_LUFS:
        return 0.0
    return round(min(target_lufs - lufs, TRUE_PEAK_CEILING_DB - true_peak), 2)


def measure_command(settings: Settings, source: Path) -> list[str]:
    return [
        "nice",
        "-n",
        "19",
        settings.ffmpeg_bin,
        "-hide_banner",
        "-nostats",
        "-nostdin",
        "-threads",
        "1",
        "-i",
        str(source),
        "-vn",
        "-sn",
        "-dn",
        "-af",
        "ebur128=peak=true:framelog=verbose",
        "-f",
        "null",
        "-",
    ]


def probe_command(settings: Settings, source: Path) -> list[str]:
    return [
        settings.ffprobe_bin,
        "-v",
        "error",
        "-print_format",
        "json",
        "-show_format",
        "-show_streams",
        "-select_streams",
        "a:0",
        str(source),
    ]


async def run_quiet(argv: list[str], timeout: float) -> tuple[int, bytes, bytes] | None:
    """Run argv, or None if it timed out. Cancelling kills the child."""
    proc = await asyncio.create_subprocess_exec(
        *argv,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except TimeoutError:
        return None
    finally:
        if proc.returncode is None:
            with contextlib.suppress(ProcessLookupError):
                proc.kill()
            await proc.wait()
    return proc.returncode or 0, stdout, stderr


def file_stamp(track: LibraryTrack) -> tuple[int, int] | None:
    try:
        info = track.absolute_path.stat()
    except OSError:
        return None
    return info.st_size, info.st_mtime_ns


class LibraryScan:
    """Loudness and tags per track, kept in STATE_DIR/scan.json.

    Entries are keyed by relative path and valid for one size and mtime, so a
    pass only reads new or changed files.
    """

    def __init__(self, settings: Settings, library: Library) -> None:
        self._settings = settings
        self._library = library
        self._path = settings.state_dir / "scan.json"
        self._lock = threading.Lock()
        self._entries: dict[str, ScanEntry] = {}
        self._load()

    def progress(self) -> tuple[int, int]:
        tracks = self._library.tracks
        scanned = sum(
            1 for track in tracks if (entry := self._valid_entry(track)) and entry.measured
        )
        return scanned, len(tracks)

    def gain_for(self, track: LibraryTrack) -> float:
        entry = self._valid_entry(track)
        if entry is None:
            return 0.0
        return compute_gain_db(self._settings.target_lufs, entry.lufs, entry.true_peak)

    def metadata_for(self, track: LibraryTrack) -> TrackMetadata:
        """Use cached tags or the filename. Play responses never wait for a probe."""
        entry = self._valid_entry(track)
        if entry is not None:
            return TrackMetadata(title=entry.title, artist=entry.artist)
        return filename_metadata(track.relative_path)

    async def run_forever(self) -> None:
        while True:
            try:
                await self.run_pass()
            except Exception:
                logger.exception("scan_pass_failed")
            await asyncio.sleep(RESCAN_INTERVAL_S)

    async def run_pass(self) -> None:
        await asyncio.to_thread(self._library.refresh)
        tracks = self._library.tracks
        for track in tracks:
            valid = self._valid_entry(track)
            if valid is not None and valid.measured:
                continue
            entry = await self._scan_track(track)
            if entry is not None:
                self._remember(track, entry)
                await asyncio.to_thread(self._save)
        await asyncio.to_thread(self._prune_and_save, {track.relative_path for track in tracks})
        scanned, total = self.progress()
        logger.info("scan_pass category=ok scanned=%s total=%s", scanned, total)

    async def _scan_track(self, track: LibraryTrack) -> ScanEntry | None:
        stamp = file_stamp(track)
        if stamp is None:
            return None
        try:
            probed = await run_quiet(
                probe_command(self._settings, track.absolute_path), SCAN_PROBE_TIMEOUT_S
            )
            measured = await run_quiet(
                measure_command(self._settings, track.absolute_path), MEASURE_TIMEOUT_S
            )
        except OSError:
            logger.error("scan_tool_failed category=spawn track=%s", track.track_id[:8])
            return None
        if measured is not None and (measured[0] < 0 or measured[0] in NICE_EXEC_FAILED):
            # The tool never ran or was killed: says nothing about the file, so retry next pass.
            logger.error("scan_tool_failed category=exit track=%s", track.track_id[:8])
            return None

        metadata = (
            metadata_from_probe(probed[1], track.relative_path)
            if probed is not None and probed[0] == 0
            else filename_metadata(track.relative_path)
        )
        loudness = None
        if measured is not None and measured[0] == 0:
            loudness = parse_ebur128(measured[2].decode("utf-8", errors="replace"))
        if loudness is None:
            # Recorded as measured so an undecodable file is not retried every pass.
            logger.warning("scan_measure_failed category=no_loudness track=%s", track.track_id[:8])
        lufs, true_peak = loudness or (None, None)
        return ScanEntry(*stamp, metadata.title, metadata.artist, True, lufs, true_peak)

    def _valid_entry(self, track: LibraryTrack) -> ScanEntry | None:
        with self._lock:
            entry = self._entries.get(track.relative_path)
        if entry is None or file_stamp(track) != (entry.size, entry.mtime_ns):
            return None
        return entry

    def _remember(self, track: LibraryTrack, entry: ScanEntry) -> None:
        with self._lock:
            self._entries[track.relative_path] = entry

    def _load(self) -> None:
        try:
            raw = json.loads(self._path.read_text(encoding="utf-8"))
            entries = {path: ScanEntry(**fields) for path, fields in raw["entries"].items()}
        except FileNotFoundError:
            return
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            logger.warning("scan_load_failed category=corrupt_file")
            return
        self._entries = entries

    def _prune_and_save(self, known_paths: set[str]) -> None:
        with self._lock:
            self._entries = {
                path: entry for path, entry in self._entries.items() if path in known_paths
            }
        self._save()

    def _save(self) -> None:
        with self._lock:
            entries: dict[str, Any] = {path: asdict(entry) for path, entry in self._entries.items()}
        payload = json.dumps({"version": SCAN_FILE_VERSION, "entries": entries}, ensure_ascii=False)
        try:
            atomic_write(self._path, payload.encode("utf-8"))
        except OSError:
            logger.error("scan_persist_failed category=state_dir")
