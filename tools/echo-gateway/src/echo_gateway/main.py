from __future__ import annotations

import asyncio
import contextlib
import hmac
import json
import logging
import uuid
from collections.abc import AsyncGenerator, AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response, StreamingResponse
from starlette.routing import Route
from starlette.types import Receive, Scope, Send

from echo_gateway.alexa import Player, handle_alexa_envelope
from echo_gateway.alexa_verify import (
    AlexaVerificationError,
    verify_alexa_signature,
    verify_application_id,
    verify_timestamp,
)
from echo_gateway.art import ArtCache
from echo_gateway.config import Settings
from echo_gateway.library import Library
from echo_gateway.paths import PathValidationError, resolve_under_root
from echo_gateway.scan import LibraryScan
from echo_gateway.session import MAX_START_MS, SessionStore
from echo_gateway.stream import (
    CONTENT_TYPE_ADTS_AAC,
    StreamTranscoder,
    TranscodeStartError,
    TranscodeCapacityError,
    build_ffmpeg_command,
)

logger = logging.getLogger("echo_gateway")

MAX_ALEXA_BODY_BYTES = 256 * 1024

def configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


class EncoderResponse(StreamingResponse):
    """Closes the encoder when the response ends. On a client disconnect Starlette
    only abandons the body iterator, which would keep FFmpeg alive until a GC pass.
    """

    def __init__(self, encoder: AsyncGenerator[bytes, None], *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._encoder = encoder

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        try:
            await super().__call__(scope, receive, send)
        finally:
            await self._encoder.aclose()


def create_app(settings: Settings | None = None) -> Starlette:
    configure_logging()
    cfg = settings or Settings.from_env()
    transcoder = StreamTranscoder(cfg)
    library = Library(cfg.music_root)
    scan = LibraryScan(cfg, library)
    sessions = SessionStore(cfg.state_dir, library)
    player = Player(library=library, sessions=sessions, scan=scan)
    art = ArtCache(cfg)

    @asynccontextmanager
    async def lifespan(_app: Starlette):
        logger.info(
            "gateway_start host=%s port=%s music_root_set=%s max_concurrent=%s "
            "public_base_url=%s open_test_stream=%s "
            "alexa_skill_configured=%s library_tracks=%s sessions=%s",
            cfg.host,
            cfg.port,
            bool(cfg.music_root),
            cfg.max_concurrent_streams,
            cfg.public_base_url,
            cfg.allow_open_test_stream,
            bool(cfg.alexa_skill_id),
            library.track_count,
            sessions.count,
        )
        scan_task = asyncio.create_task(scan.run_forever())
        try:
            yield
        finally:
            scan_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await scan_task
            await transcoder.shutdown()
            logger.info("gateway_stop")

    def _token_ok(provided: str) -> bool:
        expected = cfg.public_stream_token
        return bool(expected) and hmac.compare_digest(provided.encode(), expected.encode())

    async def health(_: Request) -> JSONResponse:
        scanned, total = await asyncio.to_thread(scan.progress)
        session_count = await asyncio.to_thread(lambda: sessions.count)
        return JSONResponse(
            {
                "status": "ok",
                "service": "kainos-echo-gateway",
                "active_streams": transcoder.active_count,
                "max_concurrent_streams": cfg.max_concurrent_streams,
                "public_base_url": cfg.public_base_url,
                "open_test_stream": cfg.allow_open_test_stream,
                "alexa_endpoint": "/alexa",
                "library_tracks": library.track_count,
                "unreadable_tracks": library.unreadable_count,
                "scan": {"scanned": scanned, "total": total},
                "sessions": session_count,
            }
        )

    async def stream_test(request: Request) -> Response:
        request_id = request.headers.get("x-request-id") or uuid.uuid4().hex[:12]
        if not cfg.allow_open_test_stream:
            return JSONResponse(
                {
                    "error": "open_test_stream_disabled",
                    "request_id": request_id,
                    "detail": "Use /stream/t/{token}/{trackId} for Funnel-exposed playback",
                },
                status_code=404,
            )
        try:
            source = resolve_under_root(cfg.test_track, cfg.music_root)
        except PathValidationError as exc:
            return JSONResponse(
                {"error": exc.category, "request_id": request_id},
                status_code=404 if exc.category in {"missing", "outside_root"} else 400,
            )
        return await _stream_file(request, request_id, source)

    async def stream_token(request: Request) -> Response:
        request_id = request.headers.get("x-request-id") or uuid.uuid4().hex[:12]
        if not _token_ok(request.path_params.get("token") or ""):
            logger.warning("stream_token_reject request_id=%s category=bad_token", request_id)
            return JSONResponse(
                {"error": "not_found", "request_id": request_id},
                status_code=404,
            )

        start_ms = _parse_start_ms(request.query_params.get("start_ms"))
        if start_ms is None:
            return JSONResponse(
                {"error": "invalid_start_ms", "request_id": request_id},
                status_code=400,
            )

        gain_db = 0.0
        track_id = request.path_params.get("track_id")
        if track_id:
            track = library.get(track_id)
            if track is None:
                logger.warning(
                    "stream_track_reject request_id=%s category=unknown_track",
                    request_id,
                )
                return JSONResponse(
                    {"error": "not_found", "request_id": request_id},
                    status_code=404,
                )
            try:
                source = resolve_under_root(track.absolute_path, cfg.music_root)
            except PathValidationError:
                return JSONResponse({"error": "not_found"}, status_code=404)
            gain_db = scan.gain_for(track)
        else:
            # Legacy token-only URL: serve configured TEST_TRACK if present.
            try:
                source = resolve_under_root(cfg.test_track, cfg.music_root)
            except PathValidationError as exc:
                return JSONResponse(
                    {"error": exc.category, "request_id": request_id},
                    status_code=404 if exc.category in {"missing", "outside_root"} else 400,
                )

        return await _stream_file(request, request_id, source, start_ms=start_ms, gain_db=gain_db)

    async def art_token(request: Request) -> Response:
        request_id = request.headers.get("x-request-id") or uuid.uuid4().hex[:12]
        track = library.get(request.path_params["track_id"])
        if not _token_ok(request.path_params["token"]) or track is None:
            logger.warning("art_reject request_id=%s category=not_found", request_id)
            return JSONResponse({"error": "not_found", "request_id": request_id}, status_code=404)
        try:
            resolve_under_root(track.absolute_path, cfg.music_root)
        except PathValidationError:
            return JSONResponse({"error": "not_found"}, status_code=404)
        return Response(
            await art.jpeg_for(track),
            media_type="image/jpeg",
            headers={"Cache-Control": "public, max-age=86400", "X-Content-Type-Options": "nosniff"},
        )

    async def _stream_file(
        request: Request,
        request_id: str,
        source: Path,
        *,
        start_ms: int = 0,
        gain_db: float = 0.0,
    ) -> Response:
        if request.method != "GET":
            return JSONResponse(
                {"error": "method_not_allowed", "request_id": request_id},
                status_code=405,
                headers={"Allow": "GET"},
            )

        if not _range_is_whole_stream(request.headers.get("range")):
            return JSONResponse(
                {
                    "error": "range_not_supported",
                    "request_id": request_id,
                    "detail": "Live FFmpeg ADTS output does not support HTTP Range",
                },
                status_code=416,
            )

        agen = transcoder.stream(source, request_id=request_id, start_ms=start_ms, gain_db=gain_db)
        try:
            first = await agen.__anext__()
        except TranscodeCapacityError:
            await agen.aclose()
            return JSONResponse({"error": "stream_capacity", "request_id": request_id},
                                status_code=503, headers={"Retry-After": "2"})
        except StopAsyncIteration:
            await agen.aclose()
            return JSONResponse(
                {"error": "empty_stream", "request_id": request_id},
                status_code=500,
            )
        except TranscodeStartError as exc:
            await agen.aclose()
            return JSONResponse(
                {
                    "error": exc.category,
                    "request_id": request_id,
                    "exit_code": exc.exit_code,
                },
                status_code=500,
            )
        except Exception:
            await agen.aclose()
            raise

        async def remaining() -> AsyncIterator[bytes]:
            try:
                yield first
                async for chunk in agen:
                    yield chunk
            except TranscodeStartError:
                logger.error(
                    "stream_midflight_error request_id=%s category=ffmpeg_after_headers",
                    request_id,
                )

        return EncoderResponse(
            agen,
            remaining(),
            media_type=CONTENT_TYPE_ADTS_AAC,
            headers={
                "Cache-Control": "no-store",
                "X-Request-Id": request_id,
                "X-Content-Type-Options": "nosniff",
                "Accept-Ranges": "none",
                "X-Transcode": f"aac-lc-adts/{cfg.aac_bitrate}",
            },
        )

    async def alexa(request: Request) -> Response:
        request_id = request.headers.get("x-request-id") or uuid.uuid4().hex[:12]
        body = await _read_limited(request, MAX_ALEXA_BODY_BYTES)
        if body is None:
            logger.warning("alexa_reject request_id=%s category=body_too_large", request_id)
            return JSONResponse(
                {"error": "body_too_large", "request_id": request_id},
                status_code=413,
            )
        try:
            if not cfg.skip_alexa_signature_verify:
                await asyncio.to_thread(
                    verify_alexa_signature,
                    body=body,
                    signature_cert_chain_url=request.headers.get("SignatureCertChainUrl")
                    or request.headers.get("signaturecertchainurl"),
                    signature_256=request.headers.get("Signature-256")
                    or request.headers.get("signature-256"),
                )
            envelope = json.loads(body.decode("utf-8"))
            if not cfg.skip_alexa_signature_verify:
                verify_timestamp((envelope.get("request") or {}).get("timestamp"))
            verify_application_id(envelope, cfg.alexa_skill_id)
        except AlexaVerificationError as exc:
            logger.warning(
                "alexa_reject request_id=%s category=%s",
                request_id,
                exc.category,
            )
            return JSONResponse(
                {"error": exc.category, "request_id": request_id},
                status_code=400,
            )
        except (json.JSONDecodeError, UnicodeDecodeError):
            return JSONResponse(
                {"error": "invalid_json", "request_id": request_id},
                status_code=400,
            )

        # State writes may fsync, so dispatch off the event loop.
        response = await asyncio.to_thread(handle_alexa_envelope, envelope, cfg, player)
        if (envelope.get("request") or {}).get("type") in {
            "AudioPlayer.PlaybackStopped", "System.ExceptionEncountered",
        }:
            return Response(status_code=200)
        return JSONResponse(response)

    routes = [
        Route("/health", health, methods=["GET"]),
        Route(
            "/stream/test",
            stream_test,
            methods=["GET", "HEAD", "POST", "PUT", "DELETE", "PATCH"],
        ),
        Route(
            "/stream/t/{token}/{track_id}",
            stream_token,
            methods=["GET", "HEAD", "POST", "PUT", "DELETE", "PATCH"],
        ),
        Route(
            "/stream/t/{token}",
            stream_token,
            methods=["GET", "HEAD", "POST", "PUT", "DELETE", "PATCH"],
        ),
        Route("/art/t/{token}/{track_id}.jpg", art_token, methods=["GET"]),
        Route("/alexa", alexa, methods=["POST"]),
    ]

    app = Starlette(routes=routes, lifespan=lifespan)
    app.state.settings = cfg
    app.state.transcoder = transcoder
    app.state.player = player
    app.state.ffmpeg_command_for_docs = command_docs(cfg)
    return app


def _parse_start_ms(raw: str | None) -> int | None:
    """0 when absent, None when invalid."""
    if raw is None:
        return 0
    if not (raw.isascii() and raw.isdigit()) or len(raw) > 10 or int(raw) > MAX_START_MS:
        return None
    return int(raw)


def _range_is_whole_stream(header: str | None) -> bool:
    return header is None or "".join(header.split()).lower() == "bytes=0-"


async def _read_limited(request: Request, limit: int) -> bytes | None:
    chunks = bytearray()
    async for chunk in request.stream():
        chunks.extend(chunk)
        if len(chunks) > limit:
            return None
    return bytes(chunks)


def command_docs(cfg: Settings) -> list[str]:
    return build_ffmpeg_command(cfg, Path("<source.flac>")).argv


app = create_app()
