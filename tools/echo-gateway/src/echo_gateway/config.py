from __future__ import annotations

import os
import secrets
from dataclasses import dataclass
from pathlib import Path


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    host: str
    port: int
    music_root: Path
    test_track: Path
    ffmpeg_bin: str
    max_concurrent_streams: int
    aac_bitrate: str
    aac_sample_rate: int
    aac_channels: int
    public_stream_token: str
    public_base_url: str
    allow_open_test_stream: bool
    alexa_skill_id: str
    skip_alexa_signature_verify: bool

    @property
    def public_stream_url(self) -> str:
        base = self.public_base_url.rstrip("/")
        return f"{base}/stream/t/{self.public_stream_token}"

    @property
    def alexa_endpoint_url(self) -> str:
        return f"{self.public_base_url.rstrip('/')}/alexa"

    @classmethod
    def from_env(cls) -> Settings:
        music_root = Path(os.environ.get("MUSIC_ROOT", "/music")).expanduser()
        raw_track = Path(os.environ.get("TEST_TRACK", "test.flac")).expanduser()
        if not raw_track.is_absolute():
            test_track = music_root / raw_track
        else:
            test_track = raw_track

        token = os.environ.get("PUBLIC_STREAM_TOKEN", "").strip()
        if not token:
            token = secrets.token_urlsafe(32)

        return cls(
            host=os.environ.get("HOST", "127.0.0.1"),
            port=int(os.environ.get("PORT", "8787")),
            music_root=music_root,
            test_track=test_track,
            ffmpeg_bin=os.environ.get("FFMPEG_BIN", "ffmpeg"),
            max_concurrent_streams=max(1, int(os.environ.get("MAX_CONCURRENT_STREAMS", "2"))),
            aac_bitrate=os.environ.get("AAC_BITRATE", "256k"),
            aac_sample_rate=int(os.environ.get("AAC_SAMPLE_RATE", "44100")),
            aac_channels=int(os.environ.get("AAC_CHANNELS", "2")),
            public_stream_token=token,
            public_base_url=os.environ.get(
                "PUBLIC_BASE_URL",
                "https://theadenkingof.taila3d69a.ts.net",
            ).rstrip("/"),
            allow_open_test_stream=_env_bool("ALLOW_OPEN_TEST_STREAM", True),
            alexa_skill_id=os.environ.get("ALEXA_SKILL_ID", "").strip(),
            skip_alexa_signature_verify=_env_bool("SKIP_ALEXA_SIGNATURE_VERIFY", False),
        )
