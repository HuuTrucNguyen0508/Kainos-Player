from __future__ import annotations

import logging
from typing import Any

from echo_gateway.config import Settings
from echo_gateway.library import LibraryTrack
from echo_gateway.playlist import PlaylistController

logger = logging.getLogger("echo_gateway.alexa")


def build_play_response(
    track: LibraryTrack,
    stream_url: str,
    *,
    play_behavior: str = "REPLACE_ALL",
    expected_previous_token: str | None = None,
    speak: str | None = None,
) -> dict[str, Any]:
    stream: dict[str, Any] = {
        "url": stream_url,
        "token": track.track_id,
        "offsetInMilliseconds": 0,
    }
    if play_behavior == "ENQUEUE":
        if not expected_previous_token:
            raise ValueError("expected_previous_token required for ENQUEUE")
        stream["expectedPreviousToken"] = expected_previous_token

    response: dict[str, Any] = {
        "shouldEndSession": True,
        "directives": [
            {
                "type": "AudioPlayer.Play",
                "playBehavior": play_behavior,
                "audioItem": {"stream": stream},
            }
        ],
    }
    if speak:
        response["outputSpeech"] = {"type": "PlainText", "text": speak}
    return {"version": "1.0", "response": response}


def build_stop_response() -> dict[str, Any]:
    return {
        "version": "1.0",
        "response": {
            "shouldEndSession": True,
            "directives": [{"type": "AudioPlayer.Stop"}],
        },
    }


def build_empty_response() -> dict[str, Any]:
    return {"version": "1.0", "response": {"shouldEndSession": True}}


def build_speak_response(text: str) -> dict[str, Any]:
    return {
        "version": "1.0",
        "response": {
            "outputSpeech": {"type": "PlainText", "text": text},
            "shouldEndSession": True,
        },
    }


def _start_playlist(playlist: PlaylistController, *, speak: str | None) -> dict[str, Any]:
    track = playlist.start_shuffled()
    if track is None:
        return build_speak_response("Je ne trouve aucun fichier audio dans le dossier musique.")
    return build_play_response(
        track,
        playlist.stream_url_for(track),
        speak=speak,
    )


def handle_alexa_envelope(
    envelope: dict[str, Any],
    settings: Settings,
    playlist: PlaylistController,
) -> dict[str, Any]:
    request = envelope.get("request") or {}
    request_type = request.get("type") or ""
    request_id = request.get("requestId") or ""

    logger.info(
        "alexa_request request_id=%s type=%s",
        request_id,
        request_type,
    )

    if request_type == "LaunchRequest":
        return _start_playlist(playlist, speak="Lecture aléatoire du dossier Playlist.")

    if request_type == "IntentRequest":
        intent = (request.get("intent") or {}).get("name") or ""
        logger.info("alexa_intent request_id=%s intent=%s", request_id, intent)
        if intent in {"PlayTestIntent", "AMAZON.StartOverIntent", "AMAZON.ResumeIntent"}:
            # Fresh shuffle on explicit play/start; resume also reshuffles for this spike
            # because live ADTS streams are not seekable.
            return _start_playlist(playlist, speak="Nouvelle lecture aléatoire.")
        if intent in {"AMAZON.PauseIntent", "AMAZON.StopIntent", "AMAZON.CancelIntent"}:
            return build_stop_response()
        if intent == "AMAZON.NextIntent":
            track = playlist.next_track(advance=True)
            if track is None:
                return build_speak_response("Fin de la liste aléatoire.")
            return build_play_response(track, playlist.stream_url_for(track), speak=None)
        if intent == "AMAZON.PreviousIntent":
            track = playlist.previous_track()
            if track is None:
                return build_speak_response("Aucun morceau précédent.")
            return build_play_response(track, playlist.stream_url_for(track), speak=None)
        if intent == "AMAZON.HelpIntent":
            return build_speak_response(
                "Dis lance test pour jouer le dossier Playlist dans un ordre aléatoire."
            )
        return _start_playlist(playlist, speak="Lecture aléatoire du dossier Playlist.")

    if request_type.startswith("AudioPlayer."):
        token = request.get("token") or ""
        offset = request.get("offsetInMilliseconds")
        error = request.get("error") or {}
        logger.info(
            "alexa_playback request_id=%s type=%s token=%s offset_ms=%s error=%s",
            request_id,
            request_type,
            token[:24],
            offset,
            error.get("type") or error.get("message") or "",
        )
        if request_type == "AudioPlayer.PlaybackFailed":
            logger.error(
                "alexa_playback_failed request_id=%s category=playback_failed detail=%s",
                request_id,
                error.get("message") or error.get("type") or "unknown",
            )
            track = playlist.next_track(advance=True)
            if track is not None:
                return build_play_response(track, playlist.stream_url_for(track), speak=None)
            return build_empty_response()

        if request_type == "AudioPlayer.PlaybackStarted":
            playlist.sync_to_token(token)
            return build_empty_response()

        if request_type == "AudioPlayer.PlaybackNearlyFinished":
            nxt = playlist.next_track(advance=False)
            if nxt is None:
                return build_empty_response()
            return build_play_response(
                nxt,
                playlist.stream_url_for(nxt),
                play_behavior="ENQUEUE",
                expected_previous_token=token,
                speak=None,
            )

        return build_empty_response()

    if request_type == "PlaybackController.PlayCommandIssued":
        current = playlist.current()
        if current is None:
            return _start_playlist(playlist, speak=None)
        return build_play_response(current, playlist.stream_url_for(current), speak=None)

    if request_type == "PlaybackController.NextCommandIssued":
        track = playlist.next_track(advance=True)
        if track is None:
            return build_empty_response()
        return build_play_response(track, playlist.stream_url_for(track), speak=None)

    if request_type == "PlaybackController.PreviousCommandIssued":
        track = playlist.previous_track()
        if track is None:
            return build_empty_response()
        return build_play_response(track, playlist.stream_url_for(track), speak=None)

    if request_type == "PlaybackController.PauseCommandIssued":
        return build_stop_response()

    if request_type == "SessionEndedRequest":
        reason = request.get("reason")
        logger.info("alexa_session_ended request_id=%s reason=%s", request_id, reason)
        return build_empty_response()

    logger.warning("alexa_unhandled request_id=%s type=%s", request_id, request_type)
    return build_empty_response()
