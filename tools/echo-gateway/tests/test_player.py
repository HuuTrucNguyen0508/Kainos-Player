from __future__ import annotations

import json
import logging
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import pytest

from echo_gateway.alexa import Player, handle_alexa_envelope
from echo_gateway.library import Library
from echo_gateway.scan import LibraryScan
from echo_gateway.session import SessionStore, StreamToken
from support import (
    DEVICE,
    STREAM_TOKEN,
    audio_event,
    controller,
    envelope,
    intent,
    launch,
    make_settings,
)

MODEL = Path(__file__).resolve().parents[1] / "alexa-skill/skill-package/interactionModels/custom/fr-FR.json"


class Echo:
    """One gateway plus the Alexa requests a real Echo would send it."""

    def __init__(self, music: Path, state_dir: Path | None = None) -> None:
        self.settings = make_settings(music_root=music, **({"state_dir": state_dir} if state_dir else {}))
        self.library = Library(music)
        self.player = Player(
            library=self.library,
            sessions=SessionStore(self.settings.state_dir, self.library),
            scan=LibraryScan(self.settings, self.library),
        )

    def send(self, request: dict) -> dict:
        return handle_alexa_envelope(request, self.settings, self.player)["response"]

    def play(self, request: dict) -> dict:
        response = self.send(request)
        directive = response["directives"][0]
        assert directive["type"] == "AudioPlayer.Play"
        return directive

    def start(self) -> str:
        token = stream(self.play(launch()))["token"]
        self.send(audio_event("PlaybackStarted", token))
        return token

    def session(self, device: str = DEVICE):
        from echo_gateway.session import device_key

        with self.player.sessions.open(device_key(device)) as session:
            return session

    def order(self) -> list[str]:
        return list(self.session().order)

    def advance_naturally(self, token: str) -> str:
        """Play out the current track into the queued one."""
        next_token = stream(self.play(audio_event("PlaybackNearlyFinished", token)))["token"]
        self.send(audio_event("PlaybackFinished", token))
        self.send(audio_event("PlaybackStarted", next_token))
        return next_token


def stream(directive: dict) -> dict:
    return directive["audioItem"]["stream"]


def track_of(directive_or_token) -> str:
    raw = directive_or_token if isinstance(directive_or_token, str) else stream(directive_or_token)["token"]
    parsed = StreamToken.parse(raw)
    assert parsed is not None
    return parsed.track_id


def start_ms_of(directive: dict) -> str | None:
    return parse_qs(urlsplit(stream(directive)["url"]).query).get("start_ms", [None])[0]


@pytest.fixture
def music(tmp_path: Path) -> Path:
    root = tmp_path / "music"
    root.mkdir()
    for index in range(8):
        (root / f"Artist {index} - Song {index}.mp3").write_bytes(b"")
    return root


@pytest.fixture
def echo(music: Path) -> Echo:
    return Echo(music)


def test_launch_plays_with_speech_and_full_metadata(echo: Echo):
    response = echo.send(launch())
    directive = response["directives"][0]
    assert directive["playBehavior"] == "REPLACE_ALL"
    assert response["shouldEndSession"] is True
    assert response["outputSpeech"]["text"] == "Lecture aléatoire du dossier Playlist."
    item = stream(directive)
    assert item["offsetInMilliseconds"] == 0
    assert "expectedPreviousToken" not in item
    parsed = StreamToken.parse(item["token"])
    assert parsed is not None and parsed.base_ms == 0
    assert item["url"] == f"https://example.test/stream/t/{STREAM_TOKEN}/{parsed.track_id}"
    metadata = directive["audioItem"]["metadata"]
    index = echo.library.get(parsed.track_id).relative_path.split()[1]
    assert metadata["title"] == f"Song {index}"
    assert metadata["subtitle"] == f"Artist {index}"
    art = f"https://example.test/art/t/{STREAM_TOKEN}/{parsed.track_id}.jpg"
    assert metadata["art"] == {"sources": [{"url": art}]}
    assert metadata["backgroundImage"] == {"sources": [{"url": art}]}


@pytest.mark.parametrize("request_builder", [launch, lambda: intent("PlayTestIntent")])
def test_every_invocation_is_a_fresh_shuffle(echo: Echo, request_builder):
    orders = set()
    for _ in range(6):
        echo.send(request_builder())
        orders.add(tuple(echo.order()))
    assert len(orders) > 1


def test_invocation_resets_modes_and_saved_position(echo: Echo):
    token = echo.start()
    echo.send(intent("AMAZON.ShuffleOffIntent"))
    echo.send(intent("AMAZON.LoopOffIntent"))
    echo.send(audio_event("PlaybackStopped", token, 5000))
    echo.start()
    session = echo.session()
    assert (session.shuffle, session.loop, session.repeat_one, session.offset_ms) == (True, True, False, 0)


def test_pause_stop_resume_keeps_position_track_and_queue(echo: Echo):
    token = echo.start()
    track = track_of(token)
    order = echo.order()

    pause = echo.send(
        intent(
            "AMAZON.PauseIntent",
            audio={"token": token, "offsetInMilliseconds": 60_000, "playerActivity": "PLAYING"},
        )
    )
    assert pause == {"directives": [{"type": "AudioPlayer.Stop"}], "shouldEndSession": True}
    assert echo.session().offset_ms == 60_000

    assert echo.send(audio_event("PlaybackStopped", token, 61_234)) == {}
    assert echo.session().offset_ms == 61_234

    resumed = echo.play(controller("PlayCommandIssued"))
    assert resumed["playBehavior"] == "REPLACE_ALL"
    assert track_of(resumed) == track
    assert start_ms_of(resumed) == "61234"
    assert stream(resumed)["offsetInMilliseconds"] == 0
    assert StreamToken.parse(stream(resumed)["token"]).base_ms == 61_234
    assert echo.order() == order

    second_token = stream(resumed)["token"]
    echo.send(audio_event("PlaybackStarted", second_token))
    echo.send(audio_event("PlaybackStopped", second_token, 1_000))
    assert echo.session().offset_ms == 62_234

    again = echo.send(intent("AMAZON.ResumeIntent"))
    directive = again["directives"][0]
    assert start_ms_of(directive) == "62234"
    assert track_of(directive) == track
    assert "outputSpeech" not in again
    assert again["shouldEndSession"] is True


def test_resume_uses_context_when_stopped_event_never_arrived(echo: Echo):
    token = echo.start()
    audio = {"token": token, "offsetInMilliseconds": 9_000, "playerActivity": "STOPPED"}
    assert start_ms_of(echo.play(controller("PlayCommandIssued", audio=audio))) == "9000"


def test_pause_command_stores_context_position(echo: Echo):
    token = echo.start()
    audio = {"token": token, "offsetInMilliseconds": 4_000, "playerActivity": "PLAYING"}
    response = echo.send(controller("PauseCommandIssued", audio=audio))
    assert response == {"directives": [{"type": "AudioPlayer.Stop"}]}
    assert echo.session().offset_ms == 4_000


def test_pause_without_context_position_still_stops(echo: Echo):
    echo.start()
    response = echo.send(intent("AMAZON.PauseIntent"))
    assert response["directives"] == [{"type": "AudioPlayer.Stop"}]


@pytest.mark.parametrize(
    "build",
    [lambda: intent("AMAZON.ResumeIntent"), lambda: controller("PlayCommandIssued")],
)
def test_resume_with_no_session_starts_fresh_shuffle(echo: Echo, build):
    directive = echo.play(build())
    assert start_ms_of(directive) is None
    assert len(echo.order()) == 8


def test_stale_stop_from_replaced_stream_keeps_new_position(echo: Echo):
    old = echo.start()
    new = stream(echo.play(intent("AMAZON.NextIntent")))["token"]
    assert echo.send(audio_event("PlaybackStopped", old, 170_000)) == {}
    session = echo.session()
    assert session.offset_ms == 0
    assert session.current_token == new


def test_start_over_restarts_current_from_zero_keeping_queue(echo: Echo):
    token = echo.start()
    echo.send(audio_event("PlaybackStopped", token, 30_000))
    order = echo.order()
    directive = echo.play(intent("AMAZON.StartOverIntent"))
    assert track_of(directive) == track_of(token)
    assert start_ms_of(directive) is None
    assert echo.order() == order


@pytest.mark.parametrize(
    "name", ["AMAZON.NavigateHomeIntent", "AMAZON.FallbackIntent", "SomethingElseIntent", ""]
)
def test_unknown_intent_leaves_playback_alone(echo: Echo, name: str):
    token = echo.start()
    before = echo.session()
    snapshot = (list(before.order), before.position, before.current_token, before.offset_ms)
    response = echo.send(intent(name))
    assert "directives" not in response
    assert response["outputSpeech"]["text"] == "Je n'ai pas compris."
    after = echo.session()
    assert (list(after.order), after.position, after.current_token, after.offset_ms) == snapshot
    assert after.current_token == token


def test_help_mentions_the_controls(echo: Echo):
    text = echo.send(intent("AMAZON.HelpIntent"))["outputSpeech"]["text"]
    for word in ("pause", "reprends", "suivant", "précédent", "aléatoire", "répète"):
        assert word in text


def test_next_and_previous_walk_the_same_order(echo: Echo):
    echo.start()
    order = echo.order()
    second = echo.play(intent("AMAZON.NextIntent"))
    assert track_of(second) == order[1]
    assert track_of(echo.play(controller("NextCommandIssued"))) == order[2]
    assert track_of(echo.play(controller("PreviousCommandIssued"))) == order[1]
    assert track_of(echo.play(intent("AMAZON.PreviousIntent"))) == order[0]
    assert track_of(echo.play(intent("AMAZON.PreviousIntent"))) == order[0]
    assert echo.order() == order


@pytest.mark.parametrize(
    "name",
    [
        "AMAZON.NextIntent",
        "AMAZON.StartOverIntent",
        "AMAZON.RepeatIntent",
        "AMAZON.ShuffleOnIntent",
        "AMAZON.LoopOffIntent",
    ],
)
def test_transport_with_no_session_only_speaks(echo: Echo, name: str):
    response = echo.send(intent(name))
    assert "directives" not in response
    assert response["outputSpeech"]["text"] == "Rien en cours. Dis lance personnel."
    assert echo.player.sessions.count == 0


def test_shuffle_on_before_near_end_keeps_playing_without_directive(echo: Echo):
    echo.start()
    echo.send(intent("AMAZON.ShuffleOffIntent"))
    order = echo.order()
    position = echo.session().position
    response = echo.send(intent("AMAZON.ShuffleOnIntent"))
    assert "directives" not in response
    assert response["outputSpeech"]["text"] == "Aléatoire activé."
    assert response["shouldEndSession"] is True
    after = echo.order()
    assert after[: position + 1] == order[: position + 1]
    assert sorted(after[position + 1 :]) == sorted(order[position + 1 :])


def test_shuffle_toggle_after_enqueue_replaces_queued_item(echo: Echo):
    token = echo.start()
    # Force a different successor so this test does not depend on random shuffle.
    session = echo.session()
    ids = echo.library.ids()
    expected = ids[(ids.index(track_of(token)) + 1) % len(ids)]
    others = [item for item in session.order if item not in (track_of(token), expected)]
    session.order = [track_of(token), *others, expected]
    session.position = 0
    queued = echo.play(audio_event("PlaybackNearlyFinished", token))
    assert queued["playBehavior"] == "ENQUEUE"

    response = echo.send(intent("AMAZON.ShuffleOffIntent"))
    assert response["outputSpeech"]["text"] == "Aléatoire désactivé."
    directive = response["directives"][0]
    assert directive["playBehavior"] == "REPLACE_ENQUEUED"
    assert "expectedPreviousToken" not in stream(directive)

    session = echo.session()
    assert session.current_token == token
    library_order = echo.library.ids()
    assert session.order == library_order
    expected_next = library_order[(library_order.index(track_of(token)) + 1) % len(library_order)]
    assert track_of(directive) == expected_next

    replacement = stream(directive)["token"]
    echo.send(audio_event("PlaybackFinished", token))
    echo.send(audio_event("PlaybackStarted", replacement))
    assert echo.session().current_id == expected_next


def test_shuffle_off_continues_in_library_order(echo: Echo):
    token = echo.start()
    echo.send(intent("AMAZON.ShuffleOffIntent"))
    ids = echo.library.ids()
    current = ids.index(track_of(token))
    assert track_of(echo.play(intent("AMAZON.NextIntent"))) == ids[(current + 1) % len(ids)]


def test_near_finished_retry_does_not_queue_a_second_item(echo: Echo):
    token = echo.start()
    first = echo.play(audio_event("PlaybackNearlyFinished", token))
    assert first["playBehavior"] == "ENQUEUE"
    assert stream(first)["expectedPreviousToken"] == token
    retry = echo.play(audio_event("PlaybackNearlyFinished", token))
    assert retry["playBehavior"] == "REPLACE_ENQUEUED"
    assert stream(retry)["token"] == stream(first)["token"]
    assert "expectedPreviousToken" not in stream(retry)


def test_stale_near_finished_is_ignored(echo: Echo):
    old = echo.start()
    echo.play(intent("AMAZON.NextIntent"))
    assert echo.send(audio_event("PlaybackNearlyFinished", old)) == {}
    assert echo.session().queued_token == ""


def test_repeat_one_loops_the_track_until_next(echo: Echo):
    token = echo.start()
    track = track_of(token)
    echo.send(audio_event("PlaybackStopped", token, 20_000))

    repeated = echo.send(intent("AMAZON.RepeatIntent"))
    directive = repeated["directives"][0]
    assert directive["playBehavior"] == "REPLACE_ALL"
    assert track_of(directive) == track
    assert start_ms_of(directive) is None
    assert repeated["outputSpeech"]["text"]

    current = stream(directive)["token"]
    echo.send(audio_event("PlaybackStarted", current))
    queued = echo.play(audio_event("PlaybackNearlyFinished", current))
    assert queued["playBehavior"] == "ENQUEUE"
    assert track_of(queued) == track
    assert stream(queued)["token"] != current
    assert stream(queued)["expectedPreviousToken"] == current

    echo.send(audio_event("PlaybackFinished", current))
    echo.send(audio_event("PlaybackStarted", stream(queued)["token"]))
    assert echo.session().repeat_one is True

    next_track = echo.play(intent("AMAZON.NextIntent"))
    assert track_of(next_track) != track
    assert echo.session().repeat_one is False


def test_previous_also_ends_repeat_one(echo: Echo):
    echo.start()
    echo.play(intent("AMAZON.NextIntent"))
    echo.play(intent("AMAZON.RepeatIntent"))
    echo.play(intent("AMAZON.PreviousIntent"))
    assert echo.session().repeat_one is False


def test_loop_off_clears_repeat_one_and_requeues(echo: Echo):
    echo.start()
    token = stream(echo.play(intent("AMAZON.RepeatIntent")))["token"]
    echo.send(audio_event("PlaybackStarted", token))
    queued = echo.play(audio_event("PlaybackNearlyFinished", token))
    assert track_of(queued) == track_of(token)

    response = echo.send(intent("AMAZON.LoopOffIntent"))
    assert echo.session().repeat_one is False
    assert echo.session().loop is False
    directive = response["directives"][0]
    assert directive["playBehavior"] == "REPLACE_ENQUEUED"
    assert track_of(directive) == echo.order()[1]


def test_shuffled_list_wraps_with_a_fresh_order(echo: Echo):
    token = echo.start()
    first_cycle = echo.order()
    for _ in range(7):
        token = echo.advance_naturally(token)
    assert track_of(token) == first_cycle[-1]

    wrapped = echo.play(audio_event("PlaybackNearlyFinished", token))
    assert wrapped["playBehavior"] == "ENQUEUE"
    session = echo.session()
    assert session.current_id == first_cycle[-1]
    assert sorted(session.order) == sorted(first_cycle)
    assert track_of(wrapped) != first_cycle[-1]
    echo.send(audio_event("PlaybackFinished", token))
    echo.send(audio_event("PlaybackStarted", stream(wrapped)["token"]))
    assert echo.session().position == 1


def test_sorted_list_wraps_to_the_start(echo: Echo):
    echo.start()
    echo.send(intent("AMAZON.ShuffleOffIntent"))
    ids = echo.library.ids()
    for _ in range(8):
        directive = echo.play(intent("AMAZON.NextIntent"))
        if track_of(directive) == ids[-1]:
            break
    assert track_of(echo.play(intent("AMAZON.NextIntent"))) == ids[0]


def test_loop_off_stops_at_end_of_list(echo: Echo):
    token = echo.start()
    echo.send(intent("AMAZON.LoopOffIntent"))
    for _ in range(7):
        token = echo.advance_naturally(token)

    assert echo.send(audio_event("PlaybackNearlyFinished", token)) == {}
    assert echo.send(audio_event("PlaybackFinished", token)) == {}
    assert echo.send(intent("AMAZON.NextIntent"))["outputSpeech"]["text"] == "Fin de la liste."
    assert echo.send(controller("NextCommandIssued")) == {}

    resumed = echo.play(controller("PlayCommandIssued"))
    assert track_of(resumed) == track_of(token)
    assert start_ms_of(resumed) is None


def test_mode_change_that_keeps_the_queued_track_sends_no_directive(echo: Echo):
    token = echo.start()
    echo.play(audio_event("PlaybackNearlyFinished", token))
    queued = echo.session().queued_token
    response = echo.send(intent("AMAZON.LoopOffIntent"))
    assert "directives" not in response
    assert response["outputSpeech"]["text"] == "Répétition désactivée."
    assert echo.session().queued_token == queued


def test_loop_on_after_end_of_list_near_finished_queues_the_wrap(echo: Echo):
    token = echo.start()
    echo.send(intent("AMAZON.LoopOffIntent"))
    for _ in range(7):
        token = echo.advance_naturally(token)
    assert echo.send(audio_event("PlaybackNearlyFinished", token)) == {}

    directive = echo.send(intent("AMAZON.LoopOnIntent"))["directives"][0]
    assert directive["playBehavior"] == "ENQUEUE"
    assert stream(directive)["expectedPreviousToken"] == token


def test_loop_off_clears_a_queued_wrap(echo: Echo):
    token = echo.start()
    for _ in range(7):
        token = echo.advance_naturally(token)
    echo.play(audio_event("PlaybackNearlyFinished", token))
    session = echo.session()
    session.order, session.position = [session.order[0]], 0

    response = echo.send(intent("AMAZON.LoopOffIntent"))
    assert response["directives"] == [
        {"type": "AudioPlayer.ClearQueue", "clearBehavior": "CLEAR_ENQUEUED"}
    ]


def test_session_survives_restart(music: Path, tmp_path: Path):
    state = tmp_path / "state"
    echo = Echo(music, state)
    token = echo.start()
    echo.send(intent("AMAZON.ShuffleOffIntent"))
    echo.send(intent("AMAZON.LoopOffIntent"))
    echo.send(audio_event("PlaybackStopped", token, 75_500))
    order = echo.order()

    restarted = Echo(music, state)
    session = restarted.session()
    assert session.order == order
    assert session.offset_ms == 75_500
    assert (session.shuffle, session.loop) == (False, False)
    assert restarted.player.sessions.count == 1

    resumed = restarted.play(controller("PlayCommandIssued"))
    assert track_of(resumed) == track_of(token)
    assert start_ms_of(resumed) == "75500"


def test_state_files_hold_no_device_id_and_leave_no_temp_files(music: Path, tmp_path: Path):
    state = tmp_path / "state"
    echo = Echo(music, state)
    echo.start()
    files = [path for path in state.rglob("*") if path.is_file()]
    assert len(files) == 1
    assert files[0].parent.name == "sessions"
    assert DEVICE not in files[0].name
    assert DEVICE not in files[0].read_text()


def test_restart_drops_missing_tracks_and_keeps_going(music: Path, tmp_path: Path):
    state = tmp_path / "state"
    echo = Echo(music, state)
    token = echo.start()
    order = echo.order()
    echo.send(audio_event("PlaybackStopped", token, 75_500))

    current = echo.library.get(order[0])
    other = echo.library.get(order[3])
    current.absolute_path.unlink()
    other.absolute_path.unlink()

    restarted = Echo(music, state)
    session = restarted.session()
    assert session.order == [order[1], order[2], *order[4:]]
    assert session.position == 0
    assert session.offset_ms == 0
    directive = restarted.play(controller("PlayCommandIssued"))
    assert track_of(directive) == order[1]
    assert start_ms_of(directive) is None


def test_restart_keeps_position_when_other_tracks_vanish(music: Path, tmp_path: Path):
    state = tmp_path / "state"
    echo = Echo(music, state)
    echo.start()
    token = stream(echo.play(intent("AMAZON.NextIntent")))["token"]
    echo.send(audio_event("PlaybackStarted", token))
    echo.send(audio_event("PlaybackStopped", token, 1_000))
    order = echo.order()
    echo.library.get(order[0]).absolute_path.unlink()

    restarted = Echo(music, state)
    resumed = restarted.play(controller("PlayCommandIssued"))
    assert track_of(resumed) == order[1]
    assert start_ms_of(resumed) == "1000"


def test_corrupt_session_file_is_ignored(music: Path, tmp_path: Path):
    state = tmp_path / "state"
    echo = Echo(music, state)
    echo.start()
    next(state.joinpath("sessions").glob("*.json")).write_text('{"order": 7')
    restarted = Echo(music, state)
    assert restarted.player.sessions.count == 0
    assert restarted.play(launch())


def test_unwritable_state_dir_keeps_playback_working(music: Path, tmp_path: Path):
    blocked = tmp_path / "not-a-directory"
    blocked.write_text("")
    echo = Echo(music, blocked)
    token = echo.start()
    assert StreamToken.parse(token) is not None
    assert echo.play(intent("AMAZON.NextIntent"))


def test_each_echo_has_its_own_session(echo: Echo):
    echo.send(launch(device="kitchen"))
    echo.send(launch(device="bedroom"))
    assert echo.player.sessions.count == 2
    assert echo.session("kitchen").order != [] and echo.session("bedroom").order != []
    token = echo.session("kitchen").current_token
    echo.send(audio_event("PlaybackStopped", token, 1_000, device="kitchen"))
    assert echo.session("kitchen").offset_ms == 1_000
    assert echo.session("bedroom").offset_ms == 0


def test_missing_context_uses_the_fallback_session(echo: Echo):
    first = {"version": "1.0", "request": {"type": "LaunchRequest", "requestId": "r"}}
    echo.send(first)
    assert echo.player.sessions.count == 1
    event = {
        "version": "1.0",
        "request": {"type": "PlaybackController.PlayCommandIssued", "requestId": "r2"},
    }
    assert echo.play(event)
    assert echo.player.sessions.count == 1


@pytest.mark.parametrize(
    "raw",
    ["", "abc", "0" * 32, f"{'a' * 32}.-1.deadbeef", f"{'a' * 32}.5.deadbee", f"{'g' * 32}.5.deadbeef",
     f"{'a' * 32}.05.deadbeef", f"{'a' * 32}.999999999.deadbeef", None, 7],
)
def test_bad_stream_tokens_are_unknown(echo: Echo, raw):
    assert StreamToken.parse(raw) is None
    echo.start()
    before = echo.session().current_token
    echo.send(audio_event("PlaybackStarted", raw))
    assert echo.session().current_token == before


def test_response_shape_per_request_type(echo: Echo):
    token = echo.start()
    assert echo.send(audio_event("PlaybackStarted", token)) == {}
    assert echo.send(audio_event("PlaybackFinished", "foreign")) == {}
    assert echo.send(audio_event("PlaybackStopped", token, 10)) == {}
    assert echo.send(envelope({"type": "SessionEndedRequest", "reason": "USER_INITIATED"})) == {}
    assert (
        echo.send(
            envelope(
                {
                    "type": "System.ExceptionEncountered",
                    "error": {"type": "INVALID_RESPONSE", "message": "bad"},
                    "cause": {"requestId": "r"},
                }
            )
        )
        == {}
    )
    assert echo.send(envelope({"type": "Some.FutureRequest"})) == {}

    for response in (
        echo.send(audio_event("PlaybackNearlyFinished", token)),
        echo.send(controller("PlayCommandIssued")),
        echo.send(controller("NextCommandIssued")),
        echo.send(controller("PreviousCommandIssued")),
        echo.send(controller("PauseCommandIssued")),
    ):
        assert list(response) == ["directives"]


def test_controller_response_with_empty_library_has_no_speech(tmp_path: Path):
    empty = tmp_path / "empty"
    empty.mkdir()
    echo = Echo(empty)
    assert echo.send(controller("PlayCommandIssued")) == {}
    assert "Je ne trouve" in echo.send(launch())["outputSpeech"]["text"]


def failed(token: str, *, state_token: str = "", activity: str = "IDLE", **fields) -> dict:
    return audio_event(
        "PlaybackFailed",
        token,
        error={"type": "MEDIA_ERROR_SERVICE_UNAVAILABLE", "message": "no route"},
        currentPlaybackState={
            "token": state_token,
            "offsetInMilliseconds": 0,
            "playerActivity": activity,
        },
        **fields,
    )


def test_failed_current_track_skips_to_next(echo: Echo):
    token = echo.start()
    order = echo.order()
    response = echo.send(failed(token))
    assert list(response) == ["directives"]
    assert response["directives"][0]["playBehavior"] == "REPLACE_ALL"
    assert track_of(response["directives"][0]) == order[1]
    assert echo.order() == order


def test_failures_are_bounded_on_a_broken_library(echo: Echo):
    token = echo.start()
    attempts = 0
    while True:
        attempts += 1
        assert attempts < 20
        response = echo.send(failed(token))
        if not response:
            break
        token = stream(response["directives"][0])["token"]
    assert attempts == 5
    assert echo.play(controller("PlayCommandIssued"))


def test_failures_end_when_the_list_runs_out_with_loop_off(echo: Echo):
    token = echo.start()
    echo.send(intent("AMAZON.LoopOffIntent"))
    for _ in range(7):
        token = echo.advance_naturally(token)
    assert echo.send(failed(token)) == {}


def test_failure_counter_resets_when_a_track_nearly_finishes(echo: Echo):
    token = echo.start()
    for _ in range(4):
        token = stream(echo.play(failed(token)))["token"]
    assert echo.session().failures == 4
    echo.send(audio_event("PlaybackNearlyFinished", token))
    assert echo.session().failures == 0


def test_stale_failure_is_ignored(echo: Echo):
    old = echo.start()
    echo.play(intent("AMAZON.NextIntent"))
    order = echo.order()
    assert echo.send(failed(old)) == {}
    assert echo.order() == order


def test_failed_prefetch_requeues_without_stopping_current(echo: Echo):
    token = echo.start()
    order = echo.order()
    queued = stream(echo.play(audio_event("PlaybackNearlyFinished", token)))["token"]
    response = echo.send(failed(queued, state_token=token, activity="PLAYING"))
    directive = response["directives"][0]
    assert directive["playBehavior"] == "ENQUEUE"
    assert stream(directive)["expectedPreviousToken"] == token
    assert track_of(directive) == order[2]
    session = echo.session()
    assert session.current_token == token
    assert session.current_id == order[0]


def test_failed_queued_stream_after_current_ended_starts_next_now(echo: Echo):
    token = echo.start()
    order = echo.order()
    queued = stream(echo.play(audio_event("PlaybackNearlyFinished", token)))["token"]
    echo.send(audio_event("PlaybackFinished", token))
    response = echo.send(failed(queued, state_token=queued, activity="IDLE"))
    directive = response["directives"][0]
    assert directive["playBehavior"] == "REPLACE_ALL"
    assert track_of(directive) == order[2]


def test_failed_repeat_prefetch_leaves_repeat_one_and_keeps_current(echo: Echo):
    echo.start()
    token = stream(echo.play(intent("AMAZON.RepeatIntent")))["token"]
    echo.send(audio_event("PlaybackStarted", token))
    queued = stream(echo.play(audio_event("PlaybackNearlyFinished", token)))["token"]
    response = echo.send(failed(queued, state_token=token, activity="PLAYING"))
    assert track_of(response["directives"][0]) != track_of(token)
    session = echo.session()
    assert session.repeat_one is False
    assert session.current_id == track_of(token)


def test_failure_log_explains_itself_without_the_url_secret(echo: Echo, caplog):
    token = echo.start()
    request = failed(token)
    request["request"]["error"]["message"] = f"GET /stream/t/{STREAM_TOKEN}/x failed"
    with caplog.at_level(logging.ERROR, logger="echo_gateway.alexa"):
        echo.send(request)
    text = caplog.text
    assert "MEDIA_ERROR_SERVICE_UNAVAILABLE" in text
    assert track_of(token)[:8] in text
    assert STREAM_TOKEN not in text


def test_info_logs_hold_no_secret_device_or_path(echo: Echo, music: Path, caplog):
    with caplog.at_level(logging.INFO):
        token = echo.start()
        echo.send(audio_event("PlaybackStopped", token, 1_000))
        echo.send(controller("PlayCommandIssued"))
    assert STREAM_TOKEN not in caplog.text
    assert DEVICE not in caplog.text
    assert str(music) not in caplog.text


def test_interaction_model_declares_every_handled_builtin_intent():
    model = json.loads(MODEL.read_text(encoding="utf-8"))
    declared = {item["name"] for item in model["interactionModel"]["languageModel"]["intents"]}
    assert declared >= {
        "AMAZON.CancelIntent",
        "AMAZON.HelpIntent",
        "AMAZON.LoopOffIntent",
        "AMAZON.LoopOnIntent",
        "AMAZON.NextIntent",
        "AMAZON.PauseIntent",
        "AMAZON.PreviousIntent",
        "AMAZON.RepeatIntent",
        "AMAZON.ResumeIntent",
        "AMAZON.ShuffleOffIntent",
        "AMAZON.ShuffleOnIntent",
        "AMAZON.StartOverIntent",
        "AMAZON.StopIntent",
        "PlayTestIntent",
    }


def test_retried_next_replays_response_without_advancing(echo: Echo):
    echo.start()
    request = intent("AMAZON.NextIntent")
    first = echo.send(request)
    token = echo.session().current_token
    assert echo.send(request) == first
    assert echo.session().current_token == token


def test_delayed_started_cannot_restore_replaced_stream(echo: Echo):
    old = echo.start()
    new = stream(echo.play(intent("AMAZON.NextIntent")))["token"]
    echo.send(audio_event("PlaybackStarted", old))
    echo.send(audio_event("PlaybackStopped", old, 9000))
    assert echo.session().current_token == new
    assert echo.session().offset_ms == 0


def test_prefetch_failures_preserve_queue_and_skip_each_failed_track(echo: Echo):
    token = echo.start()
    order = echo.order()
    queued = stream(echo.play(audio_event("PlaybackNearlyFinished", token)))["token"]
    for index in (2, 3):
        queued = stream(echo.play(failed(queued, state_token=token, activity="PLAYING")))["token"]
        assert track_of(queued) == order[index]
    assert echo.order() == order


def test_exception_event_redacts_stream_token(echo: Echo, caplog):
    with caplog.at_level(logging.ERROR):
        echo.send(envelope({'type': 'System.ExceptionEncountered',
                            'error': {'message': f'Failed https://example/stream/t/{STREAM_TOKEN}/x'}}))
    assert STREAM_TOKEN not in caplog.text


def test_concurrent_retries_advance_once(echo: Echo):
    from concurrent.futures import ThreadPoolExecutor
    echo.start()
    order = echo.order()
    request = intent('AMAZON.NextIntent')
    with ThreadPoolExecutor(max_workers=8) as pool:
        replies = list(pool.map(echo.send, [request] * 8))
    assert all(reply == replies[0] for reply in replies)
    assert echo.session().current_id == order[1]


def test_request_cache_is_bounded_and_expires(echo: Echo, monkeypatch):
    import echo_gateway.session as sessions
    now = [0.0]
    monkeypatch.setattr(sessions.time, 'monotonic', lambda: now[0])
    echo.start()
    request = intent('AMAZON.NextIntent')
    first = echo.send(request)
    now[0] = 301.0
    assert echo.send(request) != first
    for _ in range(520):
        echo.send(intent('AMAZON.HelpIntent'))
    assert len(echo.player.sessions._responses) == 512
