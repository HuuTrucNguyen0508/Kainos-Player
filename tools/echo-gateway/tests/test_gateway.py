from __future__ import annotations

import subprocess
import time
from pathlib import Path

import pytest
from httpx import ASGITransport, AsyncClient

from echo_gateway.main import create_app
from echo_gateway.paths import PathValidationError, resolve_under_root
from support import STREAM_TOKEN, TEST_FLAC, make_settings, make_tone


@pytest.fixture
def app():
    return create_app(make_settings())


@pytest.fixture
async def client(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.mark.asyncio
async def test_health_ok(client):
    response = await client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "kainos-echo-gateway"
    assert body["active_streams"] == 0


@pytest.mark.asyncio
async def test_stream_rejects_post(client):
    response = await client.post("/stream/test")
    assert response.status_code == 405
    assert response.headers.get("allow") == "GET"
    assert response.json()["error"] == "method_not_allowed"


@pytest.mark.asyncio
@pytest.mark.parametrize("value", ["bytes=0-100", "bytes=100-", "bytes=0-0", "bytes=-500", "junk"])
async def test_stream_rejects_partial_range(client, value):
    response = await client.get("/stream/test", headers={"Range": value})
    assert response.status_code == 416
    assert response.json()["error"] == "range_not_supported"


@pytest.mark.asyncio
@pytest.mark.parametrize("value", ["bytes=0-", "BYTES=0-", " bytes = 0- "])
async def test_range_from_zero_is_the_whole_stream(client, value):
    whole = await client.get("/stream/test")
    response = await client.get("/stream/test", headers={"Range": value})
    assert response.status_code == 200
    assert response.headers["accept-ranges"] == "none"
    assert len(response.content) == len(whole.content)


@pytest.mark.asyncio
async def test_stream_test_transcodes_flac(client):
    response = await client.get("/stream/test")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("audio/aac")
    assert response.headers.get("accept-ranges") == "none"
    data = response.content
    assert len(data) > 1000
    # ADTS sync word is 0xFFF
    assert data[0] == 0xFF
    assert (data[1] & 0xF0) == 0xF0


@pytest.mark.asyncio
async def test_stream_missing_track(tmp_path: Path):
    settings = make_settings(music_root=tmp_path, test_track=tmp_path / "missing.flac")
    app = create_app(settings)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/stream/test")
    assert response.status_code == 404
    assert response.json()["error"] == "missing"
    assert str(tmp_path) not in response.text


@pytest.mark.asyncio
async def test_stream_outside_root(tmp_path: Path):
    outside = tmp_path / "outside.flac"
    outside.write_bytes(TEST_FLAC.read_bytes())
    music = tmp_path / "music"
    music.mkdir()
    settings = make_settings(music_root=music, test_track=outside)
    app = create_app(settings)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/stream/test")
    assert response.status_code == 404
    assert response.json()["error"] == "outside_root"


@pytest.mark.asyncio
async def test_stream_corrupt_source(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    bad = music / "test.flac"
    bad.write_bytes(b"not a flac file")
    settings = make_settings(music_root=music, test_track=bad)
    app = create_app(settings)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/stream/test")
    assert response.status_code == 500
    assert response.json()["error"] == "ffmpeg_start"


def test_resolve_rejects_directory(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    with pytest.raises(PathValidationError) as exc:
        resolve_under_root(music, music)
    assert exc.value.category == "not_a_file"


def test_build_ffmpeg_command_is_adts():
    from echo_gateway.stream import build_ffmpeg_command

    cmd = build_ffmpeg_command(make_settings(), TEST_FLAC)
    assert "-f" in cmd.argv
    assert cmd.argv[cmd.argv.index("-f") + 1] == "adts"
    assert "aac_low" in cmd.argv
    assert cmd.content_type == "audio/aac"


@pytest.mark.asyncio
async def test_client_disconnect_reaps_ffmpeg(tmp_path: Path):
    long_flac = make_tone(tmp_path / "long.flac", seconds=12)
    settings = make_settings(
        music_root=tmp_path, test_track=long_flac, max_concurrent_streams=1
    )
    app = create_app(settings)
    transport = ASGITransport(app=app)

    before = _ffmpeg_child_count()
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        async with client.stream("GET", "/stream/test") as response:
            assert response.status_code == 200
            _ = await response.aiter_bytes().__anext__()
            # Drop the client without draining; ASGI cancel should kill ffmpeg.
        # Give the event loop a moment to run cancellation handlers.
        await _wait_until(lambda: _ffmpeg_child_count() <= before, timeout=3.0)

    assert app.state.transcoder.active_count == 0


@pytest.mark.asyncio
async def test_token_stream_ok():
    app = create_app(make_settings())
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        bad = await client.get("/stream/t/wrong-token")
        assert bad.status_code == 404
        good = await client.get("/stream/t/test-public-token-not-for-funnel")
        assert good.status_code == 200
        assert good.headers["content-type"].startswith("audio/aac")
        assert len(good.content) > 1000
        track = app.state.player.library.tracks[0]
        by_id = await client.get(f"/stream/t/{STREAM_TOKEN}/{track.track_id}")
        assert by_id.status_code == 200
        assert len(by_id.content) > 500


@pytest.mark.asyncio
async def test_open_test_stream_can_be_disabled():
    app = create_app(make_settings(allow_open_test_stream=False))
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/stream/test")
        assert response.status_code == 404
        assert response.json()["error"] == "open_test_stream_disabled"


@pytest.mark.asyncio
async def test_alexa_launch_returns_play_directive():
    from datetime import datetime, timezone

    app = create_app(make_settings())
    transport = ASGITransport(app=app)
    envelope = {
        "version": "1.0",
        "session": {
            "new": True,
            "sessionId": "s1",
            "application": {"applicationId": "amzn1.ask.skill.test"},
            "user": {"userId": "u1"},
        },
        "context": {
            "System": {
                "application": {"applicationId": "amzn1.ask.skill.test"},
                "user": {"userId": "u1"},
                "device": {"deviceId": "d1", "supportedInterfaces": {"AudioPlayer": {}}},
                "apiEndpoint": "https://api.amazonalexa.com",
            }
        },
        "request": {
            "type": "LaunchRequest",
            "requestId": "r1",
            "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "locale": "fr-FR",
        },
    }
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/alexa", json=envelope)
    assert response.status_code == 200
    body = response.json()
    directive = body["response"]["directives"][0]
    assert directive["type"] == "AudioPlayer.Play"
    assert directive["playBehavior"] == "REPLACE_ALL"
    assert body["response"]["shouldEndSession"] is True
    url = directive["audioItem"]["stream"]["url"]
    assert "/stream/t/test-public-token-not-for-funnel/" in url
    assert directive["audioItem"]["stream"]["token"]
    assert app.state.player.library.track_count >= 1


@pytest.mark.asyncio
async def test_alexa_rejects_unsigned_when_verify_enabled():
    app = create_app(make_settings(skip_alexa_signature_verify=False))
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/alexa", json={"request": {"type": "LaunchRequest"}})
    assert response.status_code == 400
    assert response.json()["error"] == "signature_headers"


def test_cert_url_validation():
    from echo_gateway.alexa_verify import AlexaVerificationError, validate_cert_chain_url

    ok = validate_cert_chain_url("https://s3.amazonaws.com/echo.api/../echo.api/echo-api-cert.pem")
    assert ok.endswith("/echo.api/echo-api-cert.pem")
    with pytest.raises(AlexaVerificationError):
        validate_cert_chain_url("https://evil.example/echo.api/cert.pem")


def _ffmpeg_child_count() -> int:
    try:
        out = subprocess.check_output(["pgrep", "-c", "ffmpeg"], text=True).strip()
        return int(out or "0")
    except subprocess.CalledProcessError:
        return 0


async def _wait_until(predicate, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        import asyncio

        await asyncio.sleep(0.05)
    raise AssertionError("condition not met before timeout")

@pytest.mark.asyncio
async def test_alexa_missing_auth_does_not_read_body():
    app = create_app(make_settings(skip_alexa_signature_verify=False))
    async def body():
        raise AssertionError("Unauthenticated body was read")
        yield b""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post("/alexa", content=body())
    assert response.status_code == 400


@pytest.mark.asyncio
async def test_alexa_caps_chunked_body(client):
    async def body():
        for _ in range(17):
            yield b"x" * 65536
    response = await client.post("/alexa", content=body())
    assert response.status_code == 413


def test_public_gateway_requires_skill_id():
    with pytest.raises(ValueError, match="Set ALEXA_SKILL_ID=amzn1.ask.skill"):
        create_app(make_settings(alexa_skill_id="", skip_alexa_signature_verify=False))


def test_signature_bypass_requires_loopback_public_url():
    with pytest.raises(ValueError, match="local"):
        create_app(make_settings(skip_alexa_signature_verify=True, public_base_url="https://public.example"))


def test_stream_access_log_redacts_token():
    import logging
    from echo_gateway.main import configure_logging
    configure_logging()
    record = logging.LogRecord("uvicorn.access", logging.INFO, "", 0,
        '%s - "%s %s HTTP/%s" %d',
        ("127.0.0.1", "GET", "/stream/t/secret-bearer/track?x=1", "1.1", 200), None)
    for log_filter in logging.getLogger("uvicorn.access").filters:
        log_filter.filter(record)
    assert "secret-bearer" not in record.getMessage()
    assert "/stream/t/[redacted]/track" in record.getMessage()
    assert "200" in record.getMessage()
    art = logging.LogRecord("uvicorn.access", logging.INFO, "", 0,
        '%s - "%s %s HTTP/%s" %d',
        ("127.0.0.1", "GET", "/art/t/secret-bearer/abc.jpg", "1.1", 200), None)
    for log_filter in logging.getLogger("uvicorn.access").filters:
        log_filter.filter(art)
    assert "secret-bearer" not in art.getMessage()
    assert "/art/t/[redacted]/abc.jpg" in art.getMessage()
