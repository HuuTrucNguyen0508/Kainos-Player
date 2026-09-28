from __future__ import annotations

import hmac
import json
import logging
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response, StreamingResponse
from starlette.routing import Route

from echo_gateway.alexa import handle_alexa_envelope
from echo_gateway.alexa_verify import (
    AlexaVerificationError,
    verify_alexa_signature,
    verify_application_id,
    verify_timestamp,
)
from echo_gateway.config import Settings
from echo_gateway.paths import PathValidationError, resolve_under_root
from echo_gateway.playlist import PlaylistController
from echo_gateway.stream import (
    CONTENT_TYPE_ADTS_AAC,
    StreamTranscoder,
    TranscodeBusy,
    TranscodeStartError,
    build_ffmpeg_command,
)

logger = logging.getLogger("echo_gateway")


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


def create_app(settings: Settings | None = None) -> Starlette:
    configure_logging()
    cfg = settings or Settings.from_env()
    transcoder = StreamTranscoder(cfg)
    playlist = PlaylistController(cfg)

    @asynccontextmanager
    async def lifespan(_app: Starlette):
        token_fingerprint = cfg.public_stream_token[:4] + "…" + cfg.public_stream_token[-4:]
        logger.info(
            "gateway_start host=%s port=%s music_root_set=%s max_concurrent=%s "
            "public_base_url=%s stream_token_fp=%s open_test_stream=%s "
            "alexa_skill_configured=%s library_tracks=%s",
            cfg.host,
            cfg.port,
            bool(cfg.music_root),
            cfg.max_concurrent_streams,
            cfg.public_base_url,
            token_fingerprint,
            cfg.allow_open_test_stream,
            bool(cfg.alexa_skill_id),
            playlist.track_count,
        )
        try:
            yield
        finally:
            await transcoder.shutdown()
            logger.info("gateway_stop")

    async def health(_: Request) -> JSONResponse:
        return JSONResponse(
            {
                "status": "ok",
                "service": "kainos-echo-gateway",
                "active_streams": transcoder.active_count,
                "max_concurrent_streams": cfg.max_concurrent_streams,
                "public_base_url": cfg.public_base_url,
                "open_test_stream": cfg.allow_open_test_stream,
                "alexa_endpoint": "/alexa",
                "library_tracks": playlist.track_count,
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
        provided = request.path_params.get("token") or ""
        expected = cfg.public_stream_token
        if not expected or not hmac.compare_digest(provided, expected):
            logger.warning("stream_token_reject request_id=%s category=bad_token", request_id)
            return JSONResponse(
                {"error": "not_found", "request_id": request_id},
                status_code=404,
            )

        track_id = request.path_params.get("track_id")
        if track_id:
            track = playlist.get(track_id)
            if track is None:
                logger.warning(
                    "stream_track_reject request_id=%s category=unknown_track",
                    request_id,
                )
                return JSONResponse(
                    {"error": "not_found", "request_id": request_id},
                    status_code=404,
                )
            source = track.absolute_path
        else:
            # Legacy token-only URL: serve configured TEST_TRACK if present.
            try:
                source = resolve_under_root(cfg.test_track, cfg.music_root)
            except PathValidationError as exc:
                return JSONResponse(
                    {"error": exc.category, "request_id": request_id},
                    status_code=404 if exc.category in {"missing", "outside_root"} else 400,
                )

        return await _stream_file(request, request_id, source)

    async def _stream_file(request: Request, request_id: str, source: Path) -> Response:
        if request.method != "GET":
            return JSONResponse(
                {"error": "method_not_allowed", "request_id": request_id},
                status_code=405,
                headers={"Allow": "GET"},
            )

        if "range" in request.headers:
            return JSONResponse(
                {
                    "error": "range_not_supported",
                    "request_id": request_id,
                    "detail": "Live FFmpeg ADTS output does not support HTTP Range",
                },
                status_code=416,
            )

        async def body() -> AsyncIterator[bytes]:
            async for chunk in transcoder.stream(source, request_id=request_id):
                yield chunk

        agen = body()
        try:
            first = await agen.__anext__()
        except StopAsyncIteration:
            await agen.aclose()
            return JSONResponse(
                {"error": "empty_stream", "request_id": request_id},
                status_code=500,
            )
        except TranscodeBusy:
            await agen.aclose()
            logger.warning("stream_busy request_id=%s category=busy", request_id)
            return JSONResponse(
                {"error": "busy", "request_id": request_id},
                status_code=503,
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
            finally:
                await agen.aclose()

        return StreamingResponse(
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
        body = await request.body()
        try:
            if not cfg.skip_alexa_signature_verify:
                verify_alexa_signature(
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
        except json.JSONDecodeError:
            return JSONResponse(
                {"error": "invalid_json", "request_id": request_id},
                status_code=400,
            )

        response = handle_alexa_envelope(envelope, cfg, playlist)
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
        Route("/alexa", alexa, methods=["POST"]),
    ]

    app = Starlette(routes=routes, lifespan=lifespan)
    app.state.settings = cfg
    app.state.transcoder = transcoder
    app.state.playlist = playlist
    app.state.ffmpeg_command_for_docs = command_docs(cfg)
    return app


def command_docs(cfg: Settings) -> list[str]:
    return build_ffmpeg_command(cfg, Path("<source.flac>")).argv


app = create_app()
