from __future__ import annotations

import json
import os
import socket
import time
from pathlib import Path

import httpx
import pytest
from starlette.testclient import TestClient

from echo_gateway.library import Library
from echo_gateway.main import create_app
from echo_gateway.scan import LibraryScan
from echo_gateway.session import SessionStore, StreamToken, device_key
from support import (
    DEVICE,
    STREAM_TOKEN,
    audio_event,
    envelope,
    intent,
    launch,
    make_settings,
    make_tone,
)

KEY = device_key(DEVICE)
START = 1_793_924_990_000


class Clock:
    def __init__(self) -> None:
        self.ms = START

    def __call__(self) -> int:
        return self.ms


class Gateway:
    """The public app Alexa calls, and the loopback app the desktop card reads."""

    def __init__(self, music: Path, **settings) -> None:
        self.app = create_app(make_settings(music_root=music, **settings))
        self.clock = Clock()
        self.app.state.player.sessions.now = self.clock
        self.alexa_client = TestClient(self.app)
        self.local = TestClient(self.app.state.local_app, base_url="http://127.0.0.1:8788")

    @property
    def library(self) -> Library:
        return self.app.state.player.library

    def alexa(self, request: dict) -> dict:
        response = self.alexa_client.post("/alexa", json=request)
        assert response.status_code == 200
        return response.json()["response"]

    def launch(self) -> str:
        return play_token(self.alexa(launch()))

    def at(self, ms_after_start: int) -> Gateway:
        self.clock.ms = START + ms_after_start
        return self

    def now_playing(self) -> dict:
        response = self.local.get("/now-playing")
        assert response.status_code == 200
        assert response.headers["cache-control"] == "no-store"
        return response.json()

    def echo(self) -> dict:
        (echo,) = self.now_playing()["echoes"]
        return echo

    def state(self) -> tuple[str, str | None, int, int]:
        echo = self.echo()
        track = echo["track"]["id"] if echo["track"] else None
        return echo["state"], track, echo["position_ms"], echo["position_at_ms"] - START

    def order(self) -> list[str]:
        with self.app.state.player.sessions.open(KEY) as session:
            return list(session.order)


def play_token(response: dict) -> str:
    directive = response["directives"][0]
    assert directive["type"] == "AudioPlayer.Play"
    return directive["audioItem"]["stream"]["token"]


def track_of(token: str) -> str:
    parsed = StreamToken.parse(token)
    assert parsed is not None
    return parsed.track_id


@pytest.fixture
def music(tmp_path: Path) -> Path:
    root = tmp_path / "music"
    root.mkdir()
    for index in range(8):
        (root / f"Artist {index} - Song {index}.mp3").write_bytes(b"")
    return root


@pytest.fixture
def gateway(music: Path) -> Gateway:
    return Gateway(music)


def test_no_sessions_means_no_echoes(gateway: Gateway):
    assert gateway.now_playing() == {"version": 1, "generated_at_ms": START, "echoes": []}


def test_public_app_has_no_now_playing_and_no_tokenless_art(gateway: Gateway):
    track_id = gateway.library.tracks[0].track_id
    assert gateway.alexa_client.get("/now-playing").status_code == 404
    assert gateway.alexa_client.get(f"/art/{track_id}.jpg").status_code == 404


def test_loopback_app_serves_only_its_three_routes(gateway: Gateway):
    track_id = gateway.library.tracks[0].track_id
    assert gateway.local.get("/health").json()["status"] == "ok"
    assert gateway.local.post("/alexa", json=launch()).status_code == 404
    assert gateway.local.get(f"/stream/t/{STREAM_TOKEN}/{track_id}").status_code == 404
    assert gateway.local.get(f"/art/t/{STREAM_TOKEN}/{track_id}.jpg").status_code == 404
    assert gateway.local.post("/now-playing").status_code == 405


def test_loopback_app_answers_only_loopback_host_names(gateway: Gateway):
    rebound = gateway.local.get("/now-playing", headers={"host": "attacker.example:8788"})
    assert rebound.status_code == 400
    assert gateway.local.get("/now-playing", headers={"host": "localhost:8788"}).status_code == 200
    assert gateway.local.get("/now-playing").status_code == 200


def test_loopback_art_matches_token_art_and_unknown_ids_are_404(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    make_tone(music / "Tone - Art.flac", seconds=1)
    gateway = Gateway(music)
    track_id = gateway.library.tracks[0].track_id
    local = gateway.local.get(f"/art/{track_id}.jpg")
    public = gateway.alexa_client.get(f"/art/t/{STREAM_TOKEN}/{track_id}.jpg")
    assert local.status_code == 200
    assert local.headers["content-type"] == "image/jpeg"
    assert local.content == public.content
    assert gateway.local.get(f"/art/{'0' * 32}.jpg").status_code == 404
    assert gateway.local.get("/art/..%2F..%2Fetc%2Fpasswd.jpg").status_code == 404


def test_full_document_for_a_playing_echo(gateway: Gateway):
    token = gateway.at(0).launch()
    gateway.alexa(intent("AMAZON.ShuffleOffIntent"))
    gateway.at(2_000).alexa(audio_event("PlaybackStarted", token, 0))
    current = track_of(token)
    ids = gateway.library.ids()
    index = ids.index(current)
    after = [ids[(index + step) % len(ids)] for step in range(1, 6)]

    def name(track_id: str) -> tuple[str, str]:
        n = gateway.library.get(track_id).relative_path.split()[1]
        return f"Song {n}", f"Artist {n}"

    assert gateway.at(5_000).now_playing() == {
        "version": 1,
        "generated_at_ms": START + 5_000,
        "echoes": [
            {
                "device": KEY[:8],
                "state": "playing",
                "last_event_at_ms": START + 2_000,
                "track": {
                    "id": current,
                    "title": name(current)[0],
                    "artist": name(current)[1],
                    "art_url": f"http://127.0.0.1:8788/art/{current}.jpg",
                    "duration_ms": None,
                },
                "position_ms": 0,
                "position_at_ms": START + 2_000,
                "shuffle": False,
                "loop": True,
                "repeat_one": False,
                "up_next": [
                    {"id": track_id, "title": name(track_id)[0], "artist": name(track_id)[1], "duration_ms": None}
                    for track_id in after
                ],
            }
        ],
    }
    text = json.dumps(gateway.now_playing())
    assert STREAM_TOKEN not in text
    assert DEVICE not in text
    assert "/stream/" not in text
    assert token not in text


def test_launch_shows_playing_from_the_start_before_the_device_confirms(gateway: Gateway):
    token = gateway.at(1_000).launch()
    assert gateway.state() == ("playing", track_of(token), 0, 1_000)


def test_pause_and_resume_anchor_the_position_the_device_reports(gateway: Gateway):
    token = gateway.at(0).launch()
    gateway.at(2_000).alexa(audio_event("PlaybackStarted", token, 0))
    assert gateway.state() == ("playing", track_of(token), 0, 2_000)

    gateway.at(14_000).alexa(audio_event("PlaybackStopped", token, 12_000))
    assert gateway.state() == ("paused", track_of(token), 12_000, 14_000)

    resumed = play_token(gateway.at(20_000).alexa(intent("AMAZON.ResumeIntent")))
    assert StreamToken.parse(resumed).base_ms == 12_000
    assert gateway.state() == ("playing", track_of(token), 12_000, 20_000)

    gateway.at(21_000).alexa(audio_event("PlaybackStarted", resumed, 500))
    assert gateway.state() == ("playing", track_of(token), 12_500, 21_000)

    # The replaced stream's late stop says nothing about the one in play.
    gateway.at(22_000).alexa(audio_event("PlaybackStopped", token, 30_000))
    assert gateway.state() == ("playing", track_of(token), 12_500, 21_000)
    assert gateway.echo()["last_event_at_ms"] == START + 21_000


def test_natural_advance_moves_to_the_queued_track_at_zero(gateway: Gateway):
    token = gateway.at(0).launch()
    gateway.alexa(audio_event("PlaybackStarted", token, 0))
    queued = play_token(gateway.at(1_000).alexa(audio_event("PlaybackNearlyFinished", token, 1_000)))
    assert gateway.echo()["up_next"][0]["id"] == track_of(queued)

    gateway.at(180_000).alexa(audio_event("PlaybackFinished", token, 179_000))
    assert gateway.state() == ("playing", track_of(token), 0, 0)
    assert gateway.echo()["last_event_at_ms"] == START + 180_000

    gateway.at(180_200).alexa(audio_event("PlaybackStarted", queued, 0))
    assert gateway.state() == ("playing", track_of(queued), 0, 180_200)


def test_natural_advance_with_the_context_a_real_echo_sends_stays_playing(gateway: Gateway):
    token = gateway.at(0).launch()
    playing = {"token": token, "offsetInMilliseconds": 0, "playerActivity": "PLAYING"}
    gateway.alexa(audio_event("PlaybackStarted", token, 0, audio=playing))
    nearly = {**playing, "offsetInMilliseconds": 1_000}
    queued = play_token(gateway.at(1_000).alexa(audio_event("PlaybackNearlyFinished", token, 1_000, audio=nearly)))

    finished = {"token": token, "offsetInMilliseconds": 179_000, "playerActivity": "FINISHED"}
    gateway.at(180_000).alexa(audio_event("PlaybackFinished", token, 179_000, audio=finished))
    assert gateway.state() == ("playing", track_of(token), 1_000, 1_000)

    started = {"token": queued, "offsetInMilliseconds": 0, "playerActivity": "PLAYING"}
    gateway.at(180_200).alexa(audio_event("PlaybackStarted", queued, 0, audio=started))
    assert gateway.state() == ("playing", track_of(queued), 0, 180_200)


def test_whats_playing_after_the_list_has_finished_names_nothing(gateway: Gateway):
    gateway.at(0).launch()
    gateway.alexa(intent("AMAZON.LoopOffIntent"))
    with gateway.app.state.player.sessions.open(KEY) as session:
        session.move_to(session.order[-1])
        token = str(session.play_current())
    gateway.alexa(audio_event("PlaybackStarted", token, 0))
    gateway.alexa(audio_event("PlaybackNearlyFinished", token, 1_000))
    gateway.alexa(audio_event("PlaybackFinished", token, 2_000))
    speech = gateway.alexa(intent("NowPlayingIntent"))["outputSpeech"]["text"]
    assert speech == "Rien en cours. Dis lance personnel."


def test_finishing_the_last_track_with_loop_off_stops(gateway: Gateway):
    gateway.at(0).launch()
    gateway.alexa(intent("AMAZON.ShuffleOffIntent"))
    gateway.alexa(intent("AMAZON.LoopOffIntent"))
    last = gateway.library.ids()[-1]
    with gateway.app.state.player.sessions.open(KEY) as session:
        session.move_to(last)
        token = str(session.play_current())
    gateway.at(1_000).alexa(audio_event("PlaybackStarted", token, 0))
    assert gateway.echo()["up_next"] == []
    gateway.alexa(audio_event("PlaybackNearlyFinished", token, 1_000))

    gateway.at(90_000).alexa(audio_event("PlaybackFinished", token, 89_000))
    assert gateway.state() == ("stopped", last, 89_000, 90_000)


def test_failed_stream_skips_and_shows_the_next_track_playing(gateway: Gateway):
    token = gateway.at(0).launch()
    failed = envelope({
        "type": "AudioPlayer.PlaybackFailed",
        "token": token,
        "error": {"type": "MEDIA_ERROR_UNKNOWN", "message": "boom"},
        "currentPlaybackState": {"token": token, "offsetInMilliseconds": 0, "playerActivity": "IDLE"},
    })
    replacement = play_token(gateway.at(3_000).alexa(failed))
    assert track_of(replacement) != track_of(token)
    assert gateway.state() == ("playing", track_of(replacement), 0, 3_000)


def test_failed_stream_with_nothing_left_stops(gateway: Gateway):
    gateway.at(0).launch()
    gateway.alexa(intent("AMAZON.ShuffleOffIntent"))
    gateway.alexa(intent("AMAZON.LoopOffIntent"))
    last = gateway.library.ids()[-1]
    with gateway.app.state.player.sessions.open(KEY) as session:
        session.move_to(last)
        token = str(session.play_current())
    failed = envelope({
        "type": "AudioPlayer.PlaybackFailed",
        "token": token,
        "error": {"type": "MEDIA_ERROR_UNKNOWN", "message": "boom"},
        "currentPlaybackState": {"token": token, "offsetInMilliseconds": 0, "playerActivity": "IDLE"},
    })
    gateway.at(4_000).alexa(failed)
    assert gateway.state() == ("stopped", last, 0, 4_000)


def test_device_context_on_any_request_updates_the_state(gateway: Gateway):
    token = gateway.at(0).launch()
    gateway.alexa(audio_event("PlaybackStarted", token, 0))

    paused = {"token": token, "offsetInMilliseconds": 3_000, "playerActivity": "PAUSED"}
    gateway.at(9_000).alexa(intent("AMAZON.HelpIntent", audio=paused))
    assert gateway.state() == ("paused", track_of(token), 3_000, 9_000)

    playing = {"token": token, "offsetInMilliseconds": 4_000, "playerActivity": "PLAYING"}
    gateway.at(10_000).alexa(intent("AMAZON.HelpIntent", audio=playing))
    assert gateway.state() == ("playing", track_of(token), 4_000, 10_000)

    finished = {"token": token, "offsetInMilliseconds": 7_000, "playerActivity": "FINISHED"}
    gateway.at(13_000).alexa(intent("AMAZON.HelpIntent", audio=finished))
    assert gateway.state() == ("stopped", track_of(token), 7_000, 13_000)

    stale = {"token": str(StreamToken.new(track_of(token))), "offsetInMilliseconds": 1, "playerActivity": "PLAYING"}
    gateway.at(20_000).alexa(intent("AMAZON.HelpIntent", audio=stale))
    assert gateway.state() == ("stopped", track_of(token), 7_000, 13_000)


def test_device_context_naming_the_queued_stream_moves_to_it(gateway: Gateway):
    token = gateway.at(0).launch()
    gateway.alexa(audio_event("PlaybackStarted", token, 0))
    queued = play_token(gateway.alexa(audio_event("PlaybackNearlyFinished", token, 1_000)))
    playing = {"token": queued, "offsetInMilliseconds": 2_500, "playerActivity": "PLAYING"}
    gateway.at(200_000).alexa(intent("AMAZON.HelpIntent", audio=playing))
    assert gateway.state() == ("playing", track_of(queued), 2_500, 200_000)


def test_playing_without_a_duration_goes_stale_after_fifteen_minutes(gateway: Gateway):
    token = gateway.at(0).launch()
    gateway.at(1_000).alexa(audio_event("PlaybackStarted", token, 0))
    assert gateway.at(1_000 + 15 * 60_000).echo()["state"] == "playing"
    assert gateway.at(1_001 + 15 * 60_000).echo()["state"] == "stopped"


async def test_playing_goes_stale_a_minute_after_the_track_should_have_ended(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    make_tone(music / "Tone - One.flac", seconds=2)
    make_tone(music / "Tone - Two.flac", seconds=2)
    gateway = Gateway(music)
    await gateway.app.state.player.scan.run_pass()
    token = gateway.at(0).launch()
    gateway.at(1_000).alexa(audio_event("PlaybackStarted", token, 0))

    echo = gateway.at(1_000 + 2_000 + 60_000).echo()
    assert echo["track"]["duration_ms"] == 2_000
    assert echo["up_next"][0]["duration_ms"] == 2_000
    assert echo["state"] == "playing"
    assert gateway.at(1_001 + 2_000 + 60_000).echo()["state"] == "stopped"

    # A position past the end, from a device report, is clamped to the duration.
    late = {"token": token, "offsetInMilliseconds": 9_000, "playerActivity": "PAUSED"}
    gateway.alexa(intent("AMAZON.HelpIntent", audio=late))
    assert gateway.echo()["position_ms"] == 2_000


async def test_a_track_anchored_late_goes_stale_a_minute_after_its_end(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    make_tone(music / "Tone - One.flac", seconds=2)
    gateway = Gateway(music)
    await gateway.app.state.player.scan.run_pass()
    token = gateway.at(0).launch()
    late = {"token": token, "offsetInMilliseconds": 1_500, "playerActivity": "PLAYING"}
    gateway.at(1_000).alexa(intent("AMAZON.HelpIntent", audio=late))
    # 500 ms of the track were left at the anchor.
    assert gateway.at(1_000 + 500 + 60_000).echo()["state"] == "playing"
    assert gateway.at(1_001 + 500 + 60_000).echo()["state"] == "stopped"


def test_up_next_follows_the_shuffled_order_without_changing_it(gateway: Gateway):
    token = gateway.at(0).launch()
    gateway.alexa(audio_event("PlaybackStarted", token, 0))
    order = gateway.order()
    assert [entry["id"] for entry in gateway.echo()["up_next"]] == order[1:6]
    assert gateway.order() == order


def test_up_next_stops_where_a_shuffled_list_would_reshuffle(gateway: Gateway):
    gateway.at(0).launch()
    order = gateway.order()
    with gateway.app.state.player.sessions.open(KEY) as session:
        session.move_to(order[-3])
        session.play_current()
    assert [entry["id"] for entry in gateway.echo()["up_next"]] == order[-2:]
    assert gateway.order() == order


def test_up_next_with_loop_off_ends_with_the_list(gateway: Gateway):
    gateway.at(0).launch()
    gateway.alexa(intent("AMAZON.ShuffleOffIntent"))
    gateway.alexa(intent("AMAZON.LoopOffIntent"))
    ids = gateway.library.ids()
    with gateway.app.state.player.sessions.open(KEY) as session:
        session.move_to(ids[-3])
        session.play_current()
    assert [entry["id"] for entry in gateway.echo()["up_next"]] == ids[-2:]


def test_up_next_with_repeat_one_is_the_same_track(gateway: Gateway):
    token = gateway.at(0).launch()
    gateway.alexa(intent("AMAZON.RepeatIntent"))
    echo = gateway.echo()
    assert echo["repeat_one"] is True
    assert [entry["id"] for entry in echo["up_next"]] == [track_of(token)]


def test_reading_changes_no_session_and_writes_no_file(gateway: Gateway):
    token = gateway.at(0).launch()
    gateway.alexa(audio_event("PlaybackStarted", token, 0))
    sessions_dir = gateway.app.state.player.sessions._dir
    before = {path.name: (path.read_bytes(), path.stat().st_mtime_ns) for path in sessions_dir.iterdir()}
    snapshot_before = gateway.app.state.player.sessions.snapshot()
    for _ in range(3):
        gateway.at(5_000).now_playing()
    after = {path.name: (path.read_bytes(), path.stat().st_mtime_ns) for path in sessions_dir.iterdir()}
    assert after == before
    assert gateway.app.state.player.sessions.snapshot() == snapshot_before


def test_echoes_are_newest_first_by_device_hash(gateway: Gateway):
    kitchen = gateway.at(0).launch()
    gateway.alexa(audio_event("PlaybackStarted", kitchen, 0))
    bedroom = play_token(gateway.at(5_000).alexa(launch(device="amzn1.ask.device.bedroom")))
    assert [echo["device"] for echo in gateway.now_playing()["echoes"]] == [
        device_key("amzn1.ask.device.bedroom")[:8], KEY[:8],
    ]
    gateway.at(9_000).alexa(audio_event("PlaybackStopped", kitchen, 9_000))
    assert [echo["device"] for echo in gateway.now_playing()["echoes"]] == [
        KEY[:8], device_key("amzn1.ask.device.bedroom")[:8],
    ]
    assert bedroom


def test_session_file_from_before_play_state_loads(music: Path, tmp_path: Path):
    library = Library(music)
    ids = library.ids()
    state = tmp_path / "state"
    path = state / "sessions" / f"{KEY}.json"
    path.parent.mkdir(parents=True)
    token = f"{ids[2]}.0.0a1b2c3d"
    path.write_text(json.dumps({
        "version": 1, "order": ids, "position": 2, "offset_ms": 42_000, "shuffle": False,
        "loop": True, "repeat_one": False, "current_token": token, "queued_token": "",
        "near_end": False, "failures": 0, "failed_tracks": [],
    }))
    saved_at = 1_700_000_000_000
    os.utime(path, ns=(saved_at * 1_000_000, saved_at * 1_000_000))
    gateway = Gateway(music, state_dir=state)
    echo = gateway.echo()
    assert (echo["state"], echo["track"]["id"], echo["position_ms"]) == ("stopped", ids[2], 42_000)
    assert echo["last_event_at_ms"] == echo["position_at_ms"] == saved_at
    assert [entry["id"] for entry in echo["up_next"]] == ids[3:8]

    gateway.alexa(audio_event("PlaybackStarted", token, 1_000))
    assert gateway.state()[:3] == ("playing", ids[2], 1_000)
    saved = json.loads(path.read_text())
    assert saved["activity"] == "playing"
    reloaded = SessionStore(state, library)
    assert reloaded.snapshot()[KEY].position_ms == 1_000


async def test_old_scan_entries_gain_a_duration_without_a_new_measurement(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    make_tone(music / "A - One.flac", seconds=2)
    make_tone(music / "B - Two.flac", seconds=3)
    state = tmp_path / "state"
    library = Library(music)
    await LibraryScan(make_settings(music_root=music, state_dir=state), library).run_pass()

    scan_file = state / "scan.json"
    raw = json.loads(scan_file.read_text())
    for fields in raw["entries"].values():
        del fields["duration_ms"]
        fields["lufs"], fields["true_peak"] = -20.0, -6.0
    raw["version"] = 1
    scan_file.write_text(json.dumps(raw))

    calls = tmp_path / "ffmpeg-calls"
    ffmpeg = tmp_path / "ffmpeg"
    ffmpeg.write_text(f"#!/bin/sh\necho \"$@\" >> {calls}\nexit 1\n")
    ffmpeg.chmod(0o755)
    settings = make_settings(music_root=music, state_dir=state, ffmpeg_bin=str(ffmpeg))
    scan = LibraryScan(settings, library)
    assert [scan.metadata_for(track).duration_ms for track in library.tracks] == [None, None]

    await scan.run_pass()
    assert not calls.exists()
    assert [scan.metadata_for(track).duration_ms for track in library.tracks] == [2_000, 3_000]
    assert [scan.gain_for(track) for track in library.tracks] == [5.0, 5.0]
    saved = json.loads(scan_file.read_text())
    assert saved["version"] == 2
    assert sorted(
        (entry["duration_ms"], entry["lufs"]) for entry in saved["entries"].values()
    ) == [(2_000, -20.0), (3_000, -20.0)]


async def test_unprobeable_old_entry_is_probed_once(tmp_path: Path):
    music = tmp_path / "music"
    music.mkdir()
    (music / "Broken - File.flac").write_bytes(b"not audio")
    state = tmp_path / "state"
    library = Library(music)
    await LibraryScan(make_settings(music_root=music, state_dir=state), library).run_pass()
    scan_file = state / "scan.json"
    raw = json.loads(scan_file.read_text())
    for fields in raw["entries"].values():
        del fields["duration_ms"]
    scan_file.write_text(json.dumps(raw))

    calls = tmp_path / "ffprobe-calls"
    ffprobe = tmp_path / "ffprobe"
    ffprobe.write_text(f"#!/bin/sh\necho probe >> {calls}\nexit 1\n")
    ffprobe.chmod(0o755)
    scan = LibraryScan(make_settings(music_root=music, state_dir=state, ffprobe_bin=str(ffprobe)), library)
    await scan.run_pass()
    await scan.run_pass()
    assert calls.read_text().splitlines() == ["probe"]
    assert scan.metadata_for(library.tracks[0]).duration_ms is None
    assert scan.progress() == (1, 1)

    # A restart tries again; the saved null marks the duration as still unknown.
    restarted = LibraryScan(make_settings(music_root=music, state_dir=state, ffprobe_bin=str(ffprobe)), library)
    await restarted.run_pass()
    assert calls.read_text().splitlines() == ["probe", "probe"]


def test_lifespan_runs_the_loopback_listener_and_stops_it(music: Path):
    app = create_app(make_settings(music_root=music))
    with TestClient(app) as client:
        client.post("/alexa", json=launch())
        server = app.state.local_server
        for _ in range(200):
            if server.started:
                break
            time.sleep(0.02)
        port = server.servers[0].sockets[0].getsockname()[1]
        body = httpx.get(f"http://127.0.0.1:{port}/now-playing").json()
        assert [echo["device"] for echo in body["echoes"]] == [KEY[:8]]
        assert httpx.get(f"http://127.0.0.1:{port}/alexa").status_code == 404
    with pytest.raises(OSError):
        socket.create_connection(("127.0.0.1", port), timeout=1).close()


def test_busy_loopback_port_leaves_alexa_working(music: Path, caplog):
    blocker = socket.socket()
    blocker.bind(("127.0.0.1", 0))
    blocker.listen()
    port = blocker.getsockname()[1]
    try:
        app = create_app(make_settings(music_root=music, local_port=port))
        with TestClient(app) as client:
            for _ in range(200):
                if "local_api_failed" in caplog.text:
                    break
                time.sleep(0.02)
            assert client.post("/alexa", json=launch()).status_code == 200
            assert client.get("/health").status_code == 200
    finally:
        blocker.close()
    assert "local_api_failed category=bind" in caplog.text


def test_removed_current_track_shows_the_next_one_stopped(gateway: Gateway, music: Path):
    token = gateway.at(0).launch()
    gateway.alexa(audio_event("PlaybackStarted", token, 0))
    gateway.at(30_000).alexa(audio_event("PlaybackStopped", token, 30_000))
    order = gateway.order()
    gateway.library.get(order[0]).absolute_path.unlink()
    gateway.library.refresh()
    echo = gateway.echo()
    assert (echo["state"], echo["track"]["id"], echo["position_ms"]) == ("stopped", order[1], 0)
