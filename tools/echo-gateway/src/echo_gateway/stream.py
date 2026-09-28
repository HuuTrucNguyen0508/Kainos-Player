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


def build_ffmpeg_command(settings: Settings, source: Path) -> FfmpegCommand:
    """Build a pipe-friendly ADTS AAC encode for Alexa-style progressive streaming.

    Ordinary MP4 is unsuitable for an unfinished stdout pipe. ADTS AAC with
    Content-Type audio/aac is the Phase 1/2 candidate format.
    """
    argv = [
        settings.ffmpeg_bin,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-i",
        str(source),
        "-vn",
        "-sn",
        "-dn",
        "-map_metadata",
        "-1",
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


class TranscodeBusy(Exception):
    pass


class TranscodeStartError(Exception):
    def __init__(self, category: str, message: str, exit_code: int | None = None) -> None:
        super().__init__(message)
        self.category = category
        self.message = message
        self.exit_code = exit_code


class StreamTranscoder:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._slots = settings.max_concurrent_streams
        self._in_use = 0
        self._active: set[asyncio.subprocess.Process] = set()
        self._lock = asyncio.Lock()

    @property
    def active_count(self) -> int:
        return self._in_use

    async def shutdown(self) -> None:
        async with self._lock:
            processes = list(self._active)
        for proc in processes:
            await self._terminate(proc, reason="shutdown")

    async def stream(self, source: Path, request_id: str) -> AsyncIterator[bytes]:
        async with self._lock:
            if self._in_use >= self._slots:
                raise TranscodeBusy("Too many concurrent streams")
            self._in_use += 1
            acquired = True

        try:
            command = build_ffmpeg_command(self._settings, source)
            logger.info(
                "transcode_start request_id=%s category=start max_concurrent=%s",
                request_id,
                self._settings.max_concurrent_streams,
            )

            proc = await asyncio.create_subprocess_exec(
                *command.argv,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                start_new_session=True,
            )
            async with self._lock:
                self._active.add(proc)

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
            except asyncio.CancelledError:
                await self._terminate(proc, reason="client_disconnect")
                if not stderr_task.done():
                    stderr_task.cancel()
                    with contextlib.suppress(asyncio.CancelledError):
                        await stderr_task
                logger.info(
                    "transcode_cancel request_id=%s category=client_disconnect bytes_sent=%s",
                    request_id,
                    bytes_sent,
                )
                raise
            finally:
                if proc.returncode is None:
                    await self._terminate(proc, reason="cleanup")
                async with self._lock:
                    self._active.discard(proc)
                if not stderr_task.done():
                    stderr_task.cancel()
                    with contextlib.suppress(asyncio.CancelledError):
                        await stderr_task
        finally:
            if acquired:
                async with self._lock:
                    self._in_use = max(0, self._in_use - 1)

    async def _wait_failed_start(
        self,
        proc: asyncio.subprocess.Process,
        stderr_task: asyncio.Task[str],
        request_id: str,
    ) -> None:
        try:
            exit_code = await asyncio.wait_for(proc.wait(), timeout=2.0)
        except TimeoutError:
            await self._terminate(proc, reason="empty_stdout")
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
                chunks.append(data)
                if sum(len(c) for c in chunks) > 16_384:
                    break
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

    async def _terminate(self, proc: asyncio.subprocess.Process, reason: str) -> None:
        if proc.returncode is not None:
            return
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        except OSError:
            proc.terminate()

        try:
            await asyncio.wait_for(proc.wait(), timeout=2.0)
            return
        except TimeoutError:
            pass

        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            return
        except OSError:
            proc.kill()
        try:
            await asyncio.wait_for(proc.wait(), timeout=2.0)
        except TimeoutError:
            logger.error("ffmpeg_reap_timeout reason=%s pid=%s", reason, proc.pid)


def _sanitize_stderr(text: str) -> str:
    # Avoid dumping long absolute paths from ffmpeg; keep a short snippet.
    compact = " ".join(text.split())
    if len(compact) > 240:
        compact = compact[:240] + "…"
    return compact
