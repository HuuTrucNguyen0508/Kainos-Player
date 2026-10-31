from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

from echo_gateway.config import Settings
from echo_gateway.library import Library
from echo_gateway.scan import LibraryScan
from echo_gateway.session import (
    Session,
    SessionStore,
    StreamToken,
    device_key,
    reported_offset,
)

logger = logging.getLogger("echo_gateway.alexa")

MAX_CONSECUTIVE_FAILURES = 5
MAX_LOGGED_ERROR_CHARS = 240
FALLBACK_DEVICE = "no-device-id"

STOP = {"type": "AudioPlayer.Stop"}
CLEAR_ENQUEUED = {"type": "AudioPlayer.ClearQueue", "clearBehavior": "CLEAR_ENQUEUED"}

SPEECH_FRESH_START = "Lecture aléatoire du dossier Playlist."
SPEECH_NEW_SHUFFLE = "Nouvelle lecture aléatoire."
SPEECH_NOTHING_PLAYING = "Rien en cours. Dis lance test."
SPEECH_EMPTY_LIBRARY = "Je ne trouve aucun fichier audio dans le dossier musique."
SPEECH_HELP = (
    "Dis lance test pour jouer le dossier Playlist dans un ordre aléatoire. "
    "Pendant la lecture, tu peux dire pause, reprends, suivant, précédent, aléatoire ou répète."
)
SPEECH_NOT_UNDERSTOOD = "Je n'ai pas compris."


@dataclass(frozen=True)
class Player:
    library: Library
    sessions: SessionStore
    scan: LibraryScan


@dataclass(frozen=True)
class Play:
    token: StreamToken
    behavior: str = "REPLACE_ALL"
    expected_previous: str | None = None


@dataclass(frozen=True)
class Reply:
    play: Play | None = None
    control: dict[str, str] | None = None
    speak: str | None = None


def stream_url(settings: Settings, token: StreamToken) -> str:
    url = f"{settings.public_stream_url}/{token.track_id}"
    return f"{url}?start_ms={token.base_ms}" if token.base_ms else url


def art_url(settings: Settings, track_id: str) -> str:
    base = settings.public_base_url.rstrip("/")
    return f"{base}/art/t/{settings.public_stream_token}/{track_id}.jpg"


def _play_directive(play: Play, settings: Settings, player: Player) -> dict[str, Any]:
    stream: dict[str, Any] = {
        "url": stream_url(settings, play.token),
        "token": str(play.token),
        "offsetInMilliseconds": 0,
    }
    if play.expected_previous is not None:
        stream["expectedPreviousToken"] = play.expected_previous
    audio_item: dict[str, Any] = {"stream": stream}

    track = player.library.get(play.token.track_id)
    if track is not None:
        metadata = player.scan.metadata_for(track)
        image = {"sources": [{"url": art_url(settings, track.track_id)}]}
        audio_item["metadata"] = {
            "title": metadata.title,
            "subtitle": metadata.artist,
            "art": image,
            "backgroundImage": image,
        }
    return {"type": "AudioPlayer.Play", "playBehavior": play.behavior, "audioItem": audio_item}


def render(
    reply: Reply,
    settings: Settings,
    player: Player,
    *,
    conversational: bool,
) -> dict[str, Any]:
    """Only LaunchRequest and IntentRequest responses may carry standard properties."""
    response: dict[str, Any] = {}
    directives = []
    if reply.play is not None:
        directives.append(_play_directive(reply.play, settings, player))
    if reply.control is not None:
        directives.append(reply.control)
    if directives:
        response["directives"] = directives
    if conversational:
        if reply.speak:
            response["outputSpeech"] = {"type": "PlainText", "text": reply.speak}
        response["shouldEndSession"] = True
    return {"version": "1.0", "response": response}


def _start_fresh(session: Session, library: Library, speak: str | None) -> Reply:
    if not library.track_count:
        library.refresh()
    if not library.track_count:
        logger.warning("playlist_empty category=no_tracks")
        return Reply(speak=SPEECH_EMPTY_LIBRARY)
    session.start_shuffled(library.ids())
    logger.info("playlist_shuffle category=ok track_count=%s", len(session.order))
    return Reply(play=Play(session.play_current()), speak=speak)


def _resume(session: Session, context: dict[str, Any], library: Library, speak: str | None) -> Reply:
    if session.current_id is None:
        return _start_fresh(session, library, speak)
    session.record_context(context)
    return Reply(play=Play(session.play_current(session.offset_ms)))


def _pause(session: Session, context: dict[str, Any]) -> Reply:
    session.record_context(context)
    return Reply(control=STOP)


def _next(session: Session) -> Reply:
    if session.current_id is None:
        return Reply(speak=SPEECH_NOTHING_PLAYING)
    session.repeat_one = False
    track_id = session.successor()
    if track_id is None:
        return Reply(speak="Fin de la liste.")
    session.move_to(track_id)
    return Reply(play=Play(session.play_current()))


def _previous(session: Session) -> Reply:
    if session.current_id is None:
        return Reply(speak="Aucun morceau précédent.")
    session.repeat_one = False
    session.position = max(0, session.position - 1)
    return Reply(play=Play(session.play_current()))


def _enqueue_successor(session: Session) -> Reply:
    """Queue what follows the current track.

    A queued stream already exists after a retry or a mode change. REPLACE_ENQUEUED
    leaves exactly one stream queued in both cases, and takes no expectedPreviousToken.
    """
    track_id = session.successor()
    queued = StreamToken.parse(session.queued_token)
    if track_id is None:
        session.queued_token = ""
        return Reply(control=CLEAR_ENQUEUED if queued else None)
    if queued is not None and queued.track_id == track_id:
        return Reply(play=Play(queued, behavior="REPLACE_ENQUEUED"))
    token = StreamToken.new(track_id)
    session.queued_token = str(token)
    if queued is not None:
        return Reply(play=Play(token, behavior="REPLACE_ENQUEUED"))
    return Reply(play=Play(token, behavior="ENQUEUE", expected_previous=session.current_token))


def _after_mode_change(session: Session, speak: str) -> Reply:
    """Before PlaybackNearlyFinished nothing is queued, and the next one sees the new order."""
    if not session.near_end:
        return Reply(speak=speak)
    reply = _enqueue_successor(session)
    return Reply(play=reply.play, control=reply.control, speak=speak)


def _handle_intent(
    intent: str,
    session: Session,
    context: dict[str, Any],
    player: Player,
) -> Reply:
    if intent == "PlayTestIntent":
        return _start_fresh(session, player.library, SPEECH_NEW_SHUFFLE)
    if intent == "AMAZON.ResumeIntent":
        return _resume(session, context, player.library, SPEECH_NEW_SHUFFLE)
    if intent in {"AMAZON.PauseIntent", "AMAZON.StopIntent", "AMAZON.CancelIntent"}:
        return _pause(session, context)
    if intent == "AMAZON.NextIntent":
        return _next(session)
    if intent == "AMAZON.PreviousIntent":
        return _previous(session)
    if intent == "AMAZON.HelpIntent":
        return Reply(speak=SPEECH_HELP)

    transport_intents = {
        "AMAZON.StartOverIntent",
        "AMAZON.RepeatIntent",
        "AMAZON.ShuffleOnIntent",
        "AMAZON.ShuffleOffIntent",
        "AMAZON.LoopOnIntent",
        "AMAZON.LoopOffIntent",
    }
    if intent not in transport_intents:
        logger.info("alexa_intent_unknown category=ignored")
        return Reply(speak=SPEECH_NOT_UNDERSTOOD)
    if session.current_id is None:
        return Reply(speak=SPEECH_NOTHING_PLAYING)

    if intent == "AMAZON.StartOverIntent":
        return Reply(play=Play(session.play_current()))
    if intent == "AMAZON.RepeatIntent":
        session.repeat_one = True
        return Reply(play=Play(session.play_current()), speak="Je répète ce morceau.")
    if intent == "AMAZON.ShuffleOnIntent":
        session.set_shuffle(True, player.library.ids())
        return _after_mode_change(session, "Aléatoire activé.")
    if intent == "AMAZON.ShuffleOffIntent":
        session.set_shuffle(False, player.library.ids())
        return _after_mode_change(session, "Aléatoire désactivé.")
    session.repeat_one = False
    if intent == "AMAZON.LoopOnIntent":
        session.loop = True
        return _after_mode_change(session, "Répétition activée.")
    session.loop = False
    return _after_mode_change(session, "Répétition désactivée.")


def _handle_playback_failed(
    request: dict[str, Any],
    session: Session,
    settings: Settings,
) -> Reply:
    token = request.get("token") or ""
    error = request.get("error") or {}
    state = request.get("currentPlaybackState") or {}
    message = str(error.get("message") or "").replace(settings.public_stream_token, "<token>")
    logger.error(
        "alexa_playback_failed request_id=%s error_type=%s message=%s %s offset_ms=%s "
        "state_activity=%s state_%s state_offset_ms=%s failures=%s",
        request.get("requestId") or "",
        error.get("type") or "unknown",
        message[:MAX_LOGGED_ERROR_CHARS],
        _describe_token(token),
        request.get("offsetInMilliseconds"),
        state.get("playerActivity"),
        _describe_token(state.get("token")),
        state.get("offsetInMilliseconds"),
        session.failures,
    )
    if not token or token not in (session.current_token, session.queued_token):
        return Reply()

    session.failures += 1
    session.repeat_one = False
    if session.failures >= MAX_CONSECUTIVE_FAILURES:
        logger.error("alexa_playback_giveup category=too_many_failures failures=%s", session.failures)
        session.queued_token = ""
        return Reply()

    still_playing = (
        state.get("playerActivity") == "PLAYING" and state.get("token") == session.current_token
    )
    if token == session.queued_token and still_playing:
        failed = StreamToken.parse(token)
        session.queued_token = ""
        if failed is not None and failed.track_id != session.current_id:
            session.remove(failed.track_id)
        return _enqueue_successor(session)

    session.owns(token)
    session.drop_current()
    track_id = session.successor()
    if track_id is None:
        return Reply()
    session.move_to(track_id)
    return Reply(play=Play(session.play_current()))


def _handle_audio_event(
    request_type: str,
    request: dict[str, Any],
    session: Session,
    settings: Settings,
) -> Reply:
    token = request.get("token") or ""
    logger.info(
        "alexa_playback request_id=%s type=%s %s offset_ms=%s",
        request.get("requestId") or "",
        request_type,
        _describe_token(token),
        reported_offset(request.get("offsetInMilliseconds")),
    )
    if request_type == "AudioPlayer.PlaybackFailed":
        return _handle_playback_failed(request, session, settings)
    if request_type == "AudioPlayer.PlaybackStarted":
        session.adopt(token)
    elif request_type == "AudioPlayer.PlaybackNearlyFinished":
        if session.owns(token):
            session.failures = 0
            session.near_end = True
            return _enqueue_successor(session)
    elif request_type == "AudioPlayer.PlaybackStopped":
        if session.owns(token):
            session.record_position(request.get("offsetInMilliseconds"))
    elif request_type == "AudioPlayer.PlaybackFinished":
        if session.owns(token):
            session.failures = 0
            if not session.queued_token:
                session.offset_ms = 0
                session.near_end = False
    return Reply()


def _dispatch(
    request: dict[str, Any],
    context: dict[str, Any],
    session: Session,
    settings: Settings,
    player: Player,
) -> Reply:
    request_type = request.get("type") or ""
    request_id = request.get("requestId") or ""

    if request_type.startswith("AudioPlayer."):
        return _handle_audio_event(request_type, request, session, settings)

    if request_type in {"LaunchRequest", "IntentRequest"} or request_type.startswith(
        "PlaybackController."
    ):
        session.failures = 0

    if request_type == "LaunchRequest":
        return _start_fresh(session, player.library, SPEECH_FRESH_START)
    if request_type == "IntentRequest":
        intent = (request.get("intent") or {}).get("name") or ""
        logger.info("alexa_intent request_id=%s intent=%s", request_id, intent)
        return _handle_intent(intent, session, context, player)
    if request_type == "PlaybackController.PlayCommandIssued":
        return _resume(session, context, player.library, None)
    if request_type == "PlaybackController.PauseCommandIssued":
        return _pause(session, context)
    if request_type == "PlaybackController.NextCommandIssued":
        return _next(session)
    if request_type == "PlaybackController.PreviousCommandIssued":
        return _previous(session)
    if request_type == "SessionEndedRequest":
        logger.info("alexa_session_ended request_id=%s reason=%s", request_id, request.get("reason"))
        return Reply()
    if request_type == "System.ExceptionEncountered":
        error = request.get("error") or {}
        logger.error(
            "alexa_exception request_id=%s error_type=%s message=%s",
            request_id,
            error.get("type") or "unknown",
            str(error.get("message") or "")[:MAX_LOGGED_ERROR_CHARS],
        )
        return Reply()

    logger.warning("alexa_unhandled request_id=%s type=%s", request_id, request_type)
    return Reply()


def _session_key(context: dict[str, Any]) -> str:
    device = (context.get("System") or {}).get("device") or {}
    device_id = device.get("deviceId")
    if not device_id:
        logger.warning("alexa_device_missing category=fallback_session")
    return device_key(device_id or FALLBACK_DEVICE)


def _describe_token(token: Any) -> str:
    parsed = StreamToken.parse(token)
    if parsed is None:
        return "track=unknown"
    return f"track={parsed.track_id[:8]} base_ms={parsed.base_ms}"


def handle_alexa_envelope(
    envelope: dict[str, Any],
    settings: Settings,
    player: Player,
) -> dict[str, Any]:
    request = envelope.get("request") or {}
    context = envelope.get("context") or {}
    request_type = request.get("type") or ""
    logger.info("alexa_request request_id=%s type=%s", request.get("requestId") or "", request_type)

    with player.sessions.open(_session_key(context)) as session:
        reply = _dispatch(request, context, session, settings, player)
    return render(
        reply,
        settings,
        player,
        conversational=request_type in {"LaunchRequest", "IntentRequest"},
    )
