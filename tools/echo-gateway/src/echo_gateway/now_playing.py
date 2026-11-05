from __future__ import annotations

from typing import Any

from echo_gateway.alexa import Player
from echo_gateway.library import LibraryTrack
from echo_gateway.session import Session

NOW_PLAYING_VERSION = 1
UP_NEXT_LIMIT = 5
# A device that went quiet (unplugged, asleep) sends no event. Give up on it after this.
STALE_AFTER_TRACK_MS = 60_000
STALE_WITHOUT_DURATION_MS = 15 * 60_000


def now_playing(player: Player, local_base_url: str) -> dict[str, Any]:
    """What each Echo plays, for apps on this machine. Reads state without changing it."""
    now_ms = player.sessions.now()
    echoes = [
        _echo(key, session, player, local_base_url, now_ms)
        for key, session in player.sessions.snapshot().items()
        if session.order or session.last_event_at_ms
    ]
    echoes.sort(key=lambda echo: echo["last_event_at_ms"], reverse=True)
    return {"version": NOW_PLAYING_VERSION, "generated_at_ms": now_ms, "echoes": echoes}


def _echo(key: str, session: Session, player: Player, base_url: str, now_ms: int) -> dict[str, Any]:
    current = player.library.get(session.current_id) if session.current_id else None
    track = None if current is None else _track(current, player, f"{base_url}/art/{current.track_id}.jpg")
    duration = track["duration_ms"] if track is not None else None
    position = session.position_ms if duration is None else min(session.position_ms, duration)
    up_next = [
        _track(upcoming, player)
        for track_id in session.upcoming(UP_NEXT_LIMIT)
        if (upcoming := player.library.get(track_id)) is not None
    ]
    return {
        "device": key[:8],
        "state": _state(session, duration, now_ms) if track is not None else "idle",
        "last_event_at_ms": session.last_event_at_ms,
        "track": track,
        "position_ms": position,
        "position_at_ms": session.position_at_ms,
        "shuffle": session.shuffle,
        "loop": session.loop,
        "repeat_one": session.repeat_one,
        "up_next": up_next,
    }


def _track(track: LibraryTrack, player: Player, art_url: str | None = None) -> dict[str, Any]:
    metadata = player.scan.metadata_for(track)
    entry: dict[str, Any] = {"id": track.track_id, "title": metadata.title, "artist": metadata.artist}
    if art_url is not None:
        entry["art_url"] = art_url
    entry["duration_ms"] = metadata.duration_ms
    return entry


def _state(session: Session, duration_ms: int | None, now_ms: int) -> str:
    if session.activity != "playing":
        return session.activity
    if duration_ms is not None:
        stale = now_ms - session.position_at_ms > duration_ms + STALE_AFTER_TRACK_MS
    else:
        stale = now_ms - session.last_event_at_ms > STALE_WITHOUT_DURATION_MS
    return "stopped" if stale else "playing"
