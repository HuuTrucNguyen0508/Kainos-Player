from __future__ import annotations

import asyncio
import contextlib
import hmac
import json
import logging
import uuid
import re
from collections.abc import AsyncGenerator, AsyncIterator
from urllib.parse import urlsplit
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

import uvicorn
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response, StreamingResponse
from starlette.middleware import Middleware
from starlette.middleware.trustedhost import TrustedHostMiddleware
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
from echo_gateway.now_playing import now_playing
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

class StreamTokenFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        # Uvicorn keeps the request target in args; also handle preformatted records.
        redact = lambda value: re.sub(r"(/(?:stream|art)/t/)[^/?\s]+", r"\1[redacted]", value)
        if isinstance(record.args, tuple):
            record.args = tuple(redact(arg) if isinstance(arg, str) else arg for arg in record.args)
        if isinstance(record.msg, str):
            record.msg = redact(record.msg)
        return True


def configure_logging() -> None:
    access = logging.getLogger("uvicorn.access")
    if not any(isinstance(item, StreamTokenFilter) for item in access.filters):
        access.addFilter(StreamTokenFilter())
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


class LocalServer(uvicorn.Server):
    """The loopback listener. It shares the main server's event loop, and the main
    server owns the signals: its lifespan stops this one through should_exit.
    """

    @contextlib.contextmanager
    def capture_signals(self):
        yield


async def _serve_local(server: LocalServer) -> None:
    """A busy port must not take Alexa playback down with it."""
    try:
        await server.serve()
    except SystemExit:
        # uvicorn exits when it cannot bind.
        logger.error("local_api_failed category=bind port=%s", server.config.port)
    except Exception:
        logger.exception("local_api_failed category=serve")


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
    local_only = cfg.host in {"127.0.0.1", "::1", "localhost"} and urlsplit(cfg.public_base_url).hostname in {
        "127.0.0.1", "::1", "localhost",
    }
    if cfg.skip_alexa_signature_verify and not local_only:
        raise ValueError("SKIP_ALEXA_SIGNATURE_VERIFY=true requires a local loopback HOST and PUBLIC_BASE_URL")
    if not cfg.alexa_skill_id and not (cfg.skip_alexa_signature_verify and local_only):
        raise ValueError("Set ALEXA_SKILL_ID=amzn1.ask.skill.<your-skill-id> in .env, then recreate the gateway container")
    transcoder = StreamTranscoder(cfg)
    library = Library(cfg.music_root)
    scan = LibraryScan(cfg, library)
    sessions = SessionStore(cfg.state_dir, library)
    player = Player(library=library, sessions=sessions, scan=scan)
    art = ArtCache(cfg)

    @asynccontextmanager
    async def lifespan(app: Starlette):
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
        local_server = LocalServer(
            uvicorn.Config(
                local_app,
                host=cfg.host,
                port=cfg.local_port,
                lifespan="off",
                access_log=False,
                log_config=None,
                timeout_graceful_shutdown=2,
            )
        )
        app.state.local_server = local_server
        local_task = asyncio.create_task(_serve_local(local_server))
        try:
            yield
        finally:
            local_server.should_exit = True
            await local_task
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
        if not _token_ok(request.path_params["token"]):
            return _art_not_found(request)
        return await art_local(request)

    def _art_not_found(request: Request) -> Response:
        request_id = request.headers.get("x-request-id") or uuid.uuid4().hex[:12]
        logger.warning("art_reject request_id=%s category=not_found", request_id)
        return JSONResponse({"error": "not_found", "request_id": request_id}, status_code=404)

    async def art_local(request: Request) -> Response:
        """The loopback listener serves art as is. The public one checks the token first."""
        track = library.get(request.path_params["track_id"])
        if track is None:
            return _art_not_found(request)
        try:
            resolve_under_root(track.absolute_path, cfg.music_root)
        except PathValidationError:
            return JSONResponse({"error": "not_found"}, status_code=404)
        return Response(
            await art.jpeg_for(track),
            media_type="image/jpeg",
            headers={"Cache-Control": "public, max-age=86400", "X-Content-Type-Options": "nosniff"},
        )

    async def now_playing_route(_: Request) -> Response:
        # The session lock can wait on an fsync, so read off the event loop.
        body = await asyncio.to_thread(now_playing, player, cfg.local_api_base_url)
        return JSONResponse(body, headers={"Cache-Control": "no-store"})

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
        if not cfg.skip_alexa_signature_verify and (
            not request.headers.get("SignatureCertChainUrl") or not request.headers.get("Signature-256")
        ):
            return JSONResponse({"error": "signature_headers"}, status_code=400)
        try:
            async with asyncio.timeout(10):
                body = await _read_limited(request, MAX_ALEXA_BODY_BYTES)
        except TimeoutError:
            return JSONResponse({"error": "request_timeout"}, status_code=408)
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
            # These can't be answered, so send the empty envelope the Alexa SDKs send.
            return JSONResponse({"version": "1.0", "response": {}})
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

    # Apps on this machine read playback here. No token and no stream URL ever appear on it.
    local_app = Starlette(
        routes=[
            Route("/health", health, methods=["GET"]),
            Route("/now-playing", now_playing_route, methods=["GET"]),
            Route("/art/{track_id}.jpg", art_local, methods=["GET"]),
        ],
        # A web page that rebinds its own name to 127.0.0.1 must not read this.
        middleware=[
            Middleware(
                TrustedHostMiddleware,
                allowed_hosts=sorted({"127.0.0.1", "localhost", urlsplit(cfg.local_api_base_url).hostname or "127.0.0.1"}),
            )
        ],
    )

    app = Starlette(routes=routes, lifespan=lifespan)
    app.state.settings = cfg
    app.state.transcoder = transcoder
    app.state.player = player
    app.state.local_app = local_app
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
