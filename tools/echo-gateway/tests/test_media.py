from __future__ import annotations

import asyncio
import logging
import os
import stat
import subprocess
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
import uvicorn
from httpx import ASGITransport, AsyncClient
from starlette.testclient import TestClient

from echo_gateway.art import ART_SIZE_PX, DEFAULT_ART
from echo_gateway.library import Library, track_id_for
from echo_gateway.main import create_app
from echo_gateway.scan import (
    LibraryScan,
    compute_gain_db,
    filename_metadata,
    parse_ebur128,
)
from support import (
    DEVICE,
    STREAM_TOKEN,
    audio_event,
    audio_seconds,
    controller,
    intent,
    launch,
    make_settings,
    make_tone,
)


def client_for(app) -> AsyncClient:
    return AsyncClient(transport=ASGITransport(app=app), base_url="http://test")


def track_url(track_id: str, query: str = "") -> str:
    return f"/stream/t/{STREAM_TOKEN}/{track_id}{query}"


def stream_of(body: dict) -> dict:
    return body["response"]["directives"][0]["audioItem"]["stream"]


def relative_url(url: str) -> str:
    return url.removeprefix("https://example.test")


def loudness(data: bytes, directory: Path) -> float:
    sample = directory / "loudness.aac"
    sample.write_bytes(data)
    result = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostats", "-i", str(sample), "-af", "ebur128=framelog=verbose",
         "-f", "null", "-"],
        check=True, capture_output=True, text=True,
    )
    parsed = parse_ebur128(result.stderr.replace("Peak:", "Peak:  0.0 dBFS #"))
    summary = result.stderr.rpartition("Summary:")[2]
    assert parsed is not None or "I:" in summary
    return float(summary.split("I:")[1].split()[0])


@pytest.fixture
def music(tmp_path: Path) -> Path:
    root = tmp_path / "music"
    root.mkdir()
    make_tone(root / "Tone - Long.flac", seconds=20)
    return root


async def scanned_app(music: Path, **settings):
    app = create_app(make_settings(music_root=music, test_track=music / "missing", **settings))
    await app.state.player.scan.run_pass()
    return app


@pytest.mark.asyncio
async def test_start_ms_really_skips_audio(music: Path, tmp_path: Path):
    app = await scanned_app(music)
    track = app.state.player.library.tracks[0]
    async with client_for(app) as client:
        whole = await client.get(track_url(track.track_id))
        tail = await client.get(track_url(track.track_id, "?start_ms=15000"))
    assert tail.status_code == 200
    assert audio_seconds(whole.content, tmp_path) == pytest.approx(20, abs=0.2)
    assert audio_seconds(tail.content, tmp_path) == pytest.approx(5, abs=0.2)


@pytest.mark.asyncio
@pytest.mark.parametrize("value", ["-1", "abc", "1.5", "", "٣", "86400001", "99999999999999999999"])
async def test_invalid_start_ms_is_rejected(music: Path, value: str):
    app = await scanned_app(music)
    track = app.state.player.library.tracks[0]
    async with client_for(app) as client:
        response = await client.get(track_url(track.track_id), params={"start_ms": value})
    assert response.status_code == 400
    assert response.json()["error"] == "invalid_start_ms"


@pytest.mark.asyncio
async def test_seeking_past_the_end_fails_cleanly(music: Path):
    app = await scanned_app(music)
    track = app.state.player.library.tracks[0]
    async with client_for(app) as client:
        response = await client.get(track_url(track.track_id, "?start_ms=500000"))
    assert response.status_code == 500
    assert app.state.transcoder.active_count == 0


@pytest.mark.asyncio
async def test_full_pause_resume_over_http_streams_the_remainder(music: Path, tmp_path: Path):
    make_tone(music / "Tone - Other.flac", seconds=3)
    app = await scanned_app(music)
    async with client_for(app) as client:
        played = await client.post("/alexa", json=intent("AMAZON.ShuffleOffIntent"))
        assert "directives" not in played.json()["response"]

        started = stream_of((await client.post("/alexa", json=launch())).json())
        while "Long" not in app.state.player.library.get(started["token"].split(".")[0]).relative_path:
            started = stream_of((await client.post("/alexa", json=launch())).json())
        await client.post("/alexa", json=audio_event("PlaybackStarted", started["token"]))
        stopped = await client.post(
            "/alexa", json=audio_event("PlaybackStopped", started["token"], 12_500)
        )
        assert stopped.json() == {"version": "1.0", "response": {}}

        resumed = stream_of((await client.post("/alexa", json=controller("PlayCommandIssued"))).json())
        assert resumed["url"].endswith("?start_ms=12500")
        assert resumed["offsetInMilliseconds"] == 0
        audio = await client.get(relative_url(resumed["url"]))
    assert audio.status_code == 200
    assert audio_seconds(audio.content, tmp_path) == pytest.approx(7.5, abs=0.2)


@pytest.mark.asyncio
async def test_gain_changes_stream_loudness(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    quiet = make_tone(music / "quiet.flac", seconds=8, volume="0.02")
    app = await scanned_app(music)
    track = app.state.player.library.tracks[0]
    scan = app.state.player.scan
    gain = scan.gain_for(track)
    assert gain > 10
    async with client_for(app) as client:
        boosted = await client.get(track_url(track.track_id))
    plain = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-i", str(quiet), "-c:a", "aac", "-f", "adts", "pipe:1"],
        check=True, capture_output=True,
    ).stdout
    assert loudness(boosted.content, tmp_path) - loudness(plain, tmp_path) == pytest.approx(gain, abs=1.0)


def test_unscanned_track_plays_at_unity_gain(music: Path):
    settings = make_settings(music_root=music)
    library = Library(music)
    assert LibraryScan(settings, library).gain_for(library.tracks[0]) == 0.0


def test_gain_formula():
    assert compute_gain_db(-11, -8.1, -3.0) == -2.9
    assert compute_gain_db(-11, -14.2, -6.0) == 2.0
    assert compute_gain_db(-11, -14.2, -0.5) == -3.5
    assert compute_gain_db(-11, -5.2, 0.4) == -5.8
    assert compute_gain_db(-11, None, None) == 0.0
    assert compute_gain_db(-11, -70.0, -60.0) == 0.0
    assert compute_gain_db(-11, -20.0, None) == 0.0


def test_ebur128_parser_reads_only_the_summary():
    stderr = """
[Parsed_ebur128_0] t: 0.1 I: -1.0 LUFS
[Parsed_ebur128_0] Summary:

  Integrated loudness:
    I:          -8.8 LUFS
    Threshold: -19.9 LUFS

  True peak:
    Peak:        0.4 dBFS
"""
    assert parse_ebur128(stderr) == (-8.8, 0.4)
    silent = "Summary:\n    I:         -70.0 LUFS\n    Peak:       -inf dBFS\n"
    assert parse_ebur128(silent) == (-70.0, None)
    assert parse_ebur128("no summary here") is None


@pytest.mark.parametrize(
    ("name", "title", "artist"),
    [
        (
            "y2mate.com - The Big O THE ビッグオー   Brick Ballades Houston Street_FiMqZmNHU1I_320kbps.mp3",
            "The Big O THE ビッグオー Brick Ballades Houston Street",
            "Artiste inconnu",
        ),
        ("Nightcore - Windows Down.mp3", "Windows Down", "Nightcore"),
        ("Collapse feat 藍月なくる_320kbps.mp3", "Collapse feat 藍月なくる", "Artiste inconnu"),
        ("y2mate.com - Foo - Bar_a-b_c1234XY_128kbps.mp3", "Bar", "Foo"),
        ("sub/dir/Dept - Love in Jakarta (feat. Kelsey Kuan).flac", "Love in Jakarta (feat. Kelsey Kuan)", "Dept"),
        ("plain.flac", "plain", "Artiste inconnu"),
    ],
)
def test_filename_fallback(name: str, title: str, artist: str):
    metadata = filename_metadata(name)
    assert (metadata.title, metadata.artist) == (title, artist)


@pytest.mark.asyncio
async def test_cached_metadata_prefers_tags_and_fills_gaps_from_filename(tmp_path: Path):
    make_tone(tmp_path / "File Artist - File Title.flac", seconds=1, tags={"TITLE": "Tag Title"})
    make_tone(tmp_path / "Both.flac", seconds=1, tags={"title": "T", "artist": "A"})
    library = Library(tmp_path)
    scan = LibraryScan(make_settings(music_root=tmp_path), library)
    await scan.run_pass()
    by_name = {track.relative_path: scan.metadata_for(track) for track in library.tracks}
    assert by_name["File Artist - File Title.flac"].title == "Tag Title"
    assert by_name["File Artist - File Title.flac"].artist == "File Artist"
    assert (by_name["Both.flac"].title, by_name["Both.flac"].artist) == ("T", "A")


def test_slow_probe_cannot_hold_up_a_play_response(tmp_path: Path):
    import time

    make_tone(tmp_path / "Slow - Probe.flac", seconds=1)
    slow = tmp_path.parent / "slow-ffprobe"
    slow.write_text("#!/bin/sh\nsleep 30\n")
    slow.chmod(slow.stat().st_mode | stat.S_IEXEC)
    library = Library(tmp_path)
    scan = LibraryScan(make_settings(music_root=tmp_path, ffprobe_bin=str(slow)), library)
    begun = time.monotonic()
    metadata = scan.metadata_for(library.tracks[0])
    assert time.monotonic() - begun < 0.1
    assert (metadata.title, metadata.artist) == ("Probe", "Slow")


def test_missing_ffprobe_falls_back_to_filename(tmp_path: Path):
    make_tone(tmp_path / "No - Probe.flac", seconds=1)
    library = Library(tmp_path)
    scan = LibraryScan(make_settings(music_root=tmp_path, ffprobe_bin="/nonexistent"), library)
    assert scan.metadata_for(library.tracks[0]).title == "Probe"


@pytest.mark.asyncio
async def test_rescan_is_incremental_and_survives_restart(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    make_tone(music / "A - One.flac", seconds=1)
    second = make_tone(music / "B - Two.flac", seconds=1)
    state = tmp_path / "state"
    settings = make_settings(music_root=music, state_dir=state)
    library = Library(music)
    scan = LibraryScan(settings, library)
    await scan.run_pass()
    assert scan.progress() == (2, 2)

    broken = make_settings(music_root=music, state_dir=state, ffmpeg_bin="/nonexistent")
    reloaded = LibraryScan(broken, library)
    assert reloaded.progress() == (2, 2)
    await reloaded.run_pass()
    assert reloaded.progress() == (2, 2)
    assert reloaded.gain_for(library.tracks[0]) != 0

    make_tone(second, seconds=2)
    await reloaded.run_pass()
    assert reloaded.gain_for(library.tracks[0]) != 0
    assert reloaded.gain_for(library.tracks[1]) == 0.0
    assert reloaded.progress() == (1, 2)


@pytest.mark.asyncio
async def test_scan_sees_new_files_and_forgets_removed_ones(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    first = make_tone(music / "A - One.flac", seconds=1)
    library = Library(music)
    scan = LibraryScan(make_settings(music_root=music), library)
    await scan.run_pass()
    first.unlink()
    make_tone(music / "B - Two.flac", seconds=1)
    await scan.run_pass()
    assert library.track_count == 1
    assert library.tracks[0].relative_path == "B - Two.flac"
    assert scan.progress() == (1, 1)


@pytest.mark.asyncio
async def test_undecodable_file_counts_as_scanned_and_plays_at_unity(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    (music / "Broken - File.flac").write_bytes(b"not audio")
    library = Library(music)
    scan = LibraryScan(make_settings(music_root=music), library)
    await scan.run_pass()
    assert scan.progress() == (1, 1)
    assert scan.gain_for(library.tracks[0]) == 0.0
    assert scan.metadata_for(library.tracks[0]).title == "File"


def test_lifespan_runs_the_scan_and_health_reports_it(music: Path):
    app = create_app(make_settings(music_root=music))
    with TestClient(app) as client:
        client.post("/alexa", json=launch())
        for _ in range(200):
            body = client.get("/health").json()
            if body["scan"]["scanned"] == 1:
                break
            import time

            time.sleep(0.02)
    assert body["scan"] == {"scanned": 1, "total": 1}
    assert body["sessions"] == 1
    text = str(body)
    assert STREAM_TOKEN not in text
    assert DEVICE not in text
    assert str(music) not in text
    assert "Tone" not in text


@pytest.mark.asyncio
@pytest.mark.skipif(os.geteuid() == 0, reason="root ignores file modes")
async def test_unreadable_files_are_counted_not_silently_dropped(music: Path, caplog):
    private = make_tone(music / "Private - Song.flac", seconds=1)
    private.chmod(0)
    with caplog.at_level(logging.WARNING, logger="echo_gateway.library"):
        app = create_app(make_settings(music_root=music))
    async with client_for(app) as client:
        body = (await client.get("/health")).json()
    assert body["library_tracks"] == 1
    assert body["unreadable_tracks"] == 1
    assert "file_count=1" in caplog.text
    assert "Private" not in caplog.text
    assert str(music) not in str(body)

    private.chmod(0o644)
    app.state.player.library.refresh()
    assert app.state.player.library.track_count == 2
    assert app.state.player.library.unreadable_count == 0


def art_url(track_id: str, token: str = STREAM_TOKEN) -> str:
    return f"/art/t/{token}/{track_id}.jpg"


def image_size(data: bytes, directory: Path) -> tuple[int, int]:
    path = directory / "art.jpg"
    path.write_bytes(data)
    output = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", str(path)],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    codec, width, height = output.split(",")
    assert codec == "mjpeg"
    return int(width), int(height)


def with_cover(path: Path, *, size: str, source: str = "testsrc") -> Path:
    tone = make_tone(path.with_suffix(".plain.flac"), seconds=1)
    cover = path.with_suffix(".cover.png")
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", f"{source}=size={size}:duration=1",
         "-frames:v", "1", str(cover)],
        check=True,
    )
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(tone), "-i", str(cover), "-map", "0", "-map", "1",
         "-c", "copy", "-disposition:v", "attached_pic", str(path)],
        check=True,
    )
    tone.unlink()
    cover.unlink()
    return path


@pytest.mark.asyncio
async def test_art_is_a_square_jpeg_cached_in_state(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    with_cover(music / "Wide - Cover.flac", size="1200x600")
    state = tmp_path / "state"
    app = create_app(make_settings(music_root=music, state_dir=state))
    track = app.state.player.library.tracks[0]
    async with client_for(app) as client:
        response = await client.get(art_url(track.track_id))
        cached = list((state / "art").glob("*.jpg"))
        assert len(cached) == 1
        again = await client.get(art_url(track.track_id))
    assert response.status_code == 200
    assert response.headers["content-type"] == "image/jpeg"
    assert image_size(response.content, tmp_path) == (ART_SIZE_PX, ART_SIZE_PX)
    assert response.content != DEFAULT_ART.read_bytes()
    assert again.content == response.content == cached[0].read_bytes()


@pytest.mark.asyncio
async def test_transparent_png_cover_converts_to_jpeg(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    with_cover(music / "Alpha - Cover.flac", size="300x500", source="rgbtestsrc")
    app = create_app(make_settings(music_root=music))
    track = app.state.player.library.tracks[0]
    async with client_for(app) as client:
        response = await client.get(art_url(track.track_id))
    assert image_size(response.content, tmp_path) == (ART_SIZE_PX, ART_SIZE_PX)


@pytest.mark.asyncio
async def test_track_without_art_gets_the_bundled_square(music: Path, tmp_path: Path):
    state = tmp_path / "state"
    app = create_app(make_settings(music_root=music, state_dir=state))
    track = app.state.player.library.tracks[0]
    async with client_for(app) as client:
        response = await client.get(art_url(track.track_id))
        repeat = await client.get(art_url(track.track_id))
    assert response.status_code == 200
    assert response.content == repeat.content == DEFAULT_ART.read_bytes()
    assert image_size(response.content, tmp_path) == (ART_SIZE_PX, ART_SIZE_PX)
    assert len(list((state / "art").glob("*.none"))) == 1


@pytest.mark.asyncio
async def test_art_survives_an_unusable_state_dir(music: Path, tmp_path: Path):
    blocked = tmp_path / "file"
    blocked.write_text("")
    app = create_app(make_settings(music_root=music, state_dir=blocked))
    track = app.state.player.library.tracks[0]
    async with client_for(app) as client:
        response = await client.get(art_url(track.track_id))
    assert response.status_code == 200


@pytest.mark.asyncio
async def test_art_requires_token_and_a_known_track(music: Path):
    app = create_app(make_settings(music_root=music))
    track = app.state.player.library.tracks[0]
    outside = track_id_for("../../etc/passwd")
    async with client_for(app) as client:
        assert (await client.get(art_url(track.track_id, "wrong"))).status_code == 404
        assert (await client.get(art_url(track.track_id, "é"))).status_code == 404
        assert (await client.get(art_url(outside))).status_code == 404
        assert (await client.get(art_url("..%2F..%2Fetc%2Fpasswd"))).status_code == 404
        assert (await client.get(f"/stream/t/é/{track.track_id}")).status_code == 404


@asynccontextmanager
async def real_server(app):
    """ASGITransport buffers whole responses, so stalled clients need real sockets."""
    server = uvicorn.Server(
        uvicorn.Config(app, host="127.0.0.1", port=0, lifespan="off", log_level="warning")
    )
    task = asyncio.create_task(server.serve())
    while not server.started:
        await asyncio.sleep(0.01)
    try:
        yield server.servers[0].sockets[0].getsockname()[1]
    finally:
        server.should_exit = True
        await task


async def open_stream(port: int, path: str) -> tuple[asyncio.StreamReader, asyncio.StreamWriter, bytes]:
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    writer.write(f"GET {path} HTTP/1.1\r\nHost: test\r\n\r\n".encode())
    await writer.drain()
    headers = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=5)
    return reader, writer, headers


@pytest.mark.asyncio
@pytest.mark.parametrize("cap", [2, 4])
async def test_full_cap_protects_existing_streams(tmp_path: Path, cap):
    """Backpressured long mixes must survive retries and prefetch at the cap."""
    music = tmp_path / "music"
    music.mkdir()
    make_tone(music / "Tone - Long.flac", seconds=3000)
    app = create_app(make_settings(music_root=music, max_concurrent_streams=cap))
    track = app.state.player.library.tracks[0]
    url = track_url(track.track_id)

    async with real_server(app) as port:
        stalled = [await open_stream(port, url) for _ in range(cap)]
        for _, _, headers in stalled:
            assert headers.startswith(b"HTTP/1.1 200")
        assert app.state.transcoder.active_count == cap

        reader, writer, headers = await open_stream(port, f"{url}?start_ms=2990000")
        assert headers.startswith(b"HTTP/1.1 503")
        assert app.state.transcoder.active_count == cap
        assert all(proc.returncode is None for proc in app.state.transcoder._active)

        for _, stale_writer, _ in stalled:
            stale_writer.close()
        writer.close()
        await asyncio.wait_for(_until_idle(app), timeout=5)


async def _until_idle(app) -> None:
    while app.state.transcoder.active_count:
        await asyncio.sleep(0.02)


@pytest.mark.asyncio
async def test_transcoder_never_exceeds_the_slot_cap(tmp_path: Path):
    from echo_gateway.stream import StreamTranscoder

    source = make_tone(tmp_path / "long.flac", seconds=600)
    transcoder = StreamTranscoder(make_settings(max_concurrent_streams=1))
    first = transcoder.stream(source, "a")
    assert await anext(first)
    second = transcoder.stream(source, "b")
    from echo_gateway.stream import TranscodeCapacityError
    with pytest.raises(TranscodeCapacityError):
        await anext(second)
    assert transcoder.active_count == 1
    assert await anext(first)
    await first.aclose()
    await second.aclose()
    assert transcoder.active_count == 0


def test_stream_ffmpeg_command_seeks_before_input_and_applies_gain():
    from echo_gateway.stream import build_ffmpeg_command

    argv = build_ffmpeg_command(make_settings(), Path("x.flac"), start_ms=61_234, gain_db=-3.2).argv
    assert argv.index("-ss") < argv.index("-i")
    assert argv[argv.index("-ss") + 1] == "61.234"
    assert argv[argv.index("-af") + 1] == "volume=-3.20dB"
    plain = build_ffmpeg_command(make_settings(), Path("x.flac")).argv
    assert "-ss" not in plain and "-af" not in plain


@pytest.mark.asyncio
async def test_info_logs_hold_no_secret_or_path_during_streaming(music: Path, caplog):
    app = await scanned_app(music)
    track = app.state.player.library.tracks[0]
    with caplog.at_level(logging.INFO, logger="echo_gateway"):
        async with client_for(app) as client:
            await client.get(track_url(track.track_id, "?start_ms=1000"))
            await client.get(art_url(track.track_id))
    assert STREAM_TOKEN not in caplog.text
    assert str(music) not in caplog.text


@pytest.mark.asyncio
async def test_alexa_body_is_size_limited(music: Path):
    app = create_app(make_settings(music_root=music))
    async with client_for(app) as client:
        response = await client.post("/alexa", content=b"x" * (256 * 1024 + 1))
    assert response.status_code == 413


@pytest.mark.parametrize('expression', [
    'aevalsrc=0.9*sgn(sin(2*PI*997*t)):s=44100:d=3',
    'anoisesrc=color=white:amplitude=0.9:seed=42:d=3:r=44100',
    r'aevalsrc=if(eq(mod(n\,997)\,0)\,0.99\,0):s=44100:d=3',
    'aevalsrc=0.4*sin(2*PI*997*t)+0.4*sin(2*PI*12001*t):s=44100:d=3',
])
def test_gain_ceiling_survives_aac_encoding(tmp_path, expression):
    from echo_gateway.scan import measure_command
    from echo_gateway.stream import build_ffmpeg_command
    source = tmp_path / 'signal.wav'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', expression,
                    '-af', 'pan=stereo|c0=c0|c1=c0', '-c:a', 'pcm_f32le', str(source)], check=True)
    settings = make_settings(target_lufs=0)
    def measure(path):
        result = subprocess.run(measure_command(settings, path), check=True, capture_output=True, text=True)
        return parse_ebur128(result.stderr)
    lufs, peak = measure(source)
    gain = compute_gain_db(settings.target_lufs, lufs, peak)
    encoded = subprocess.run(build_ffmpeg_command(settings, source, gain_db=gain).argv,
                             check=True, capture_output=True).stdout
    output = tmp_path / 'encoded.aac'
    output.write_bytes(encoded)
    assert measure(output)[1] <= 0.0


@pytest.mark.asyncio
@pytest.mark.parametrize('kind', ['AudioPlayer.PlaybackStopped', 'System.ExceptionEncountered'])
async def test_one_way_alexa_events_get_an_empty_envelope(kind):
    from support import envelope
    app = create_app(make_settings())
    async with client_for(app) as client:
        response = await client.post('/alexa', json=envelope({'type': kind}))
    assert response.status_code == 200
    assert response.json() == {'version': '1.0', 'response': {}}


@pytest.mark.asyncio
@pytest.mark.parametrize('route', ['stream', 'art'])
async def test_indexed_file_replaced_with_external_symlink_is_rejected(tmp_path, route):
    music = tmp_path / 'music'
    music.mkdir()
    source = make_tone(music / 'track.flac', seconds=1)
    app = create_app(make_settings(music_root=music))
    track = app.state.player.library.tracks[0]
    outside = tmp_path / 'outside.flac'
    source.rename(outside)
    source.symlink_to(outside)
    url = track_url(track.track_id) if route == 'stream' else art_url(track.track_id)
    async with client_for(app) as client:
        response = await client.get(url)
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_art_cache_write_does_not_block_event_loop(music, monkeypatch):
    import threading
    import echo_gateway.art as art
    event_loop_thread = threading.get_ident()
    original = art.atomic_write
    def checked_write(*args):
        assert threading.get_ident() != event_loop_thread
        return original(*args)
    monkeypatch.setattr(art, 'atomic_write', checked_write)
    app = create_app(make_settings(music_root=music))
    track = app.state.player.library.tracks[0]
    async with client_for(app) as client:
        assert (await client.get(art_url(track.track_id))).status_code == 200


@pytest.mark.asyncio
async def test_health_does_not_block_stream_loop_during_session_persist(monkeypatch):
    import threading
    import time
    app = create_app(make_settings())
    entered = threading.Event()
    release = threading.Event()
    original = app.state.player.sessions._persist
    def slow_persist(*args):
        entered.set()
        release.wait(timeout=2)
        return original(*args)
    monkeypatch.setattr(app.state.player.sessions, '_persist', slow_persist)
    async with client_for(app) as client:
        alexa = asyncio.create_task(client.post('/alexa', json=launch()))
        assert await asyncio.to_thread(entered.wait, 2)
        timer = threading.Timer(0.3, release.set)
        timer.start()
        try:
            health = asyncio.create_task(client.get('/health'))
            begun = time.monotonic()
            await asyncio.sleep(0.02)
            elapsed = time.monotonic() - begun
            assert elapsed < 0.15
        finally:
            release.set()
            timer.cancel()
            await alexa
            await health
