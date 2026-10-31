from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import signal
from collections.abc import AsyncIterator
from dataclasses import dataclass
from pathlib import Path

from echo_gateway.config import Settings

logger = logging.getLogger("echo_gateway.stream")

CONTENT_TYPE_ADTS_AAC = "audio/aac"


@dataclass(frozen=True)
class FfmpegCommand:
    argv: list[str]
    content_type: str = CONTENT_TYPE_ADTS_AAC


def build_ffmpeg_command(
    settings: Settings,
    source: Path,
    *,
    start_ms: int = 0,
    gain_db: float = 0.0,
) -> FfmpegCommand:
    """Build a pipe-friendly ADTS AAC encode for Alexa-style progressive streaming.

    Ordinary MP4 is unsuitable for an unfinished stdout pipe. ADTS AAC with
    Content-Type audio/aac is the Phase 1/2 candidate format. -ss before -i
    seeks in the source, so the stream starts at start_ms with timestamps at 0.
    """
    seek = ["-ss", f"{start_ms / 1000:.3f}"] if start_ms else []
    gain = ["-af", f"volume={gain_db:.2f}dB"] if gain_db else []
    argv = [
        settings.ffmpeg_bin,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        *seek,
        "-i",
        str(source),
        "-vn",
        "-sn",
        "-dn",
        "-map_metadata",
        "-1",
        *gain,
        "-c:a",
        "aac",
        "-profile:a",
        "aac_low",
        "-b:a",
        settings.aac_bitrate,
        "-ac",
        str(settings.aac_channels),
        "-ar",
        str(settings.aac_sample_rate),
        "-f",
        "adts",
        "pipe:1",
    ]
    return FfmpegCommand(argv=argv)


class TranscodeStartError(Exception):
    def __init__(self, category: str, message: str, exit_code: int | None = None) -> None:
        super().__init__(message)
        self.category = category
        self.message = message
        self.exit_code = exit_code


class StreamTranscoder:
    """Runs at most max_concurrent_streams encoders.

    A paused Echo may keep its connection open, so a finished listener can hold
    a slot for a long time. The newest request always wins: when full, the
    oldest encoder is killed instead of refusing the stream.
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._slots = settings.max_concurrent_streams
        self._active: list[asyncio.subprocess.Process] = []
        self._reapers: set[asyncio.Task[None]] = set()
        self._lock = asyncio.Lock()

    @property
    def active_count(self) -> int:
        return len(self._active)

    async def shutdown(self) -> None:
        async with self._lock:
            processes = list(self._active)
            self._active.clear()
        for proc in processes:
            _kill(proc)

    async def stream(
        self,
        source: Path,
        request_id: str,
        *,
        start_ms: int = 0,
        gain_db: float = 0.0,
    ) -> AsyncIterator[bytes]:
        command = build_ffmpeg_command(self._settings, source, start_ms=start_ms, gain_db=gain_db)
        async with self._lock:
            while len(self._active) >= self._slots:
                logger.info("transcode_evict request_id=%s category=slot_reclaimed", request_id)
                _kill(self._active.pop(0))
            logger.info(
                "transcode_start request_id=%s category=start max_concurrent=%s start_ms=%s gain_db=%s",
                request_id,
                self._slots,
                start_ms,
                gain_db,
            )
            proc = await asyncio.create_subprocess_exec(
                *command.argv,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                start_new_session=True,
            )
            self._active.append(proc)

        assert proc.stdout is not None
        assert proc.stderr is not None

        stderr_task = asyncio.create_task(self._drain_stderr(proc, request_id))
        bytes_sent = 0
        try:
            first = await proc.stdout.read(64 * 1024)
            if not first:
                await self._wait_failed_start(proc, stderr_task, request_id)
            yield first
            bytes_sent += len(first)

            while True:
                chunk = await proc.stdout.read(64 * 1024)
                if not chunk:
                    break
                yield chunk
                bytes_sent += len(chunk)

            exit_code = await proc.wait()
            await stderr_task
            if exit_code != 0:
                logger.warning(
                    "transcode_exit request_id=%s category=ffmpeg_error exit_code=%s bytes_sent=%s",
                    request_id,
                    exit_code,
                    bytes_sent,
                )
            else:
                logger.info(
                    "transcode_done request_id=%s category=ok exit_code=0 bytes_sent=%s",
                    request_id,
                    bytes_sent,
                )
        except (asyncio.CancelledError, GeneratorExit):
            logger.info(
                "transcode_cancel request_id=%s category=client_disconnect bytes_sent=%s",
                request_id,
                bytes_sent,
            )
            raise
        finally:
            # Synchronous, then a separate task: a client disconnect cancels this
            # generator's scope again on every await, so awaiting here could leak the slot.
            _kill(proc)
            if proc in self._active:
                self._active.remove(proc)
            reaper = asyncio.create_task(self._reap(proc, stderr_task))
            self._reapers.add(reaper)
            reaper.add_done_callback(self._reapers.discard)

    async def _wait_failed_start(
        self,
        proc: asyncio.subprocess.Process,
        stderr_task: asyncio.Task[str],
        request_id: str,
    ) -> None:
        try:
            exit_code = await asyncio.wait_for(proc.wait(), timeout=2.0)
        except TimeoutError:
            _kill(proc)
            exit_code = proc.returncode
        stderr_text = ""
        try:
            stderr_text = await asyncio.wait_for(stderr_task, timeout=1.0)
        except (TimeoutError, asyncio.CancelledError):
            pass
        logger.warning(
            "transcode_fail request_id=%s category=ffmpeg_start exit_code=%s stderr=%s",
            request_id,
            exit_code,
            _sanitize_stderr(stderr_text),
        )
        raise TranscodeStartError(
            category="ffmpeg_start",
            message="FFmpeg failed to produce audio output",
            exit_code=exit_code,
        )

    async def _drain_stderr(self, proc: asyncio.subprocess.Process, request_id: str) -> str:
        assert proc.stderr is not None
        chunks: list[bytes] = []
        try:
            while True:
                data = await proc.stderr.read(4096)
                if not data:
                    break
                if sum(len(c) for c in chunks) <= 16_384:
                    chunks.append(data)
        except Exception:
            logger.exception("stderr_drain_error request_id=%s", request_id)
        text = b"".join(chunks).decode("utf-8", errors="replace").strip()
        if text:
            logger.warning(
                "ffmpeg_stderr request_id=%s category=ffmpeg_stderr text=%s",
                request_id,
                _sanitize_stderr(text),
            )
        return text

    async def _reap(self, proc: asyncio.subprocess.Process, stderr_task: asyncio.Task[str]) -> None:
        """Collect a killed encoder. asyncio's wait() cannot finish until stdout is drained."""
        assert proc.stdout is not None
        try:
            await asyncio.wait_for(proc.stdout.read(), timeout=2.0)
            await asyncio.wait_for(proc.wait(), timeout=2.0)
            await asyncio.wait_for(stderr_task, timeout=2.0)
        except TimeoutError:
            logger.error("ffmpeg_reap_timeout pid=%s", proc.pid)


def _kill(proc: asyncio.subprocess.Process) -> None:
    if proc.returncode is None:
        with contextlib.suppress(ProcessLookupError):
            os.killpg(proc.pid, signal.SIGKILL)


def _sanitize_stderr(text: str) -> str:
    # Avoid dumping long absolute paths from ffmpeg; keep a short snippet.
    compact = " ".join(text.split())
    if len(compact) > 240:
        compact = compact[:240] + "…"
    return compact
