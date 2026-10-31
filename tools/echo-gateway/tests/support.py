from __future__ import annotations

import subprocess
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path

from echo_gateway.config import Settings

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"
TEST_FLAC = FIXTURES / "test.flac"
STREAM_TOKEN = "test-public-token-not-for-funnel"
SKILL_ID = "amzn1.ask.skill.test"
DEVICE = "amzn1.ask.device.kitchen"

_STATE_ROOT = tempfile.TemporaryDirectory(prefix="echo-gateway-tests-")


def make_settings(**overrides) -> Settings:
    base = dict(
        host="127.0.0.1",
        port=8787,
        music_root=FIXTURES,
        test_track=TEST_FLAC,
        ffmpeg_bin="ffmpeg",
        ffprobe_bin="ffprobe",
        state_dir=Path(tempfile.mkdtemp(dir=_STATE_ROOT.name)),
        target_lufs=-11.0,
        max_concurrent_streams=2,
        aac_bitrate="256k",
        aac_sample_rate=44100,
        aac_channels=2,
        public_stream_token=STREAM_TOKEN,
        public_base_url="https://example.test",
        allow_open_test_stream=True,
        alexa_skill_id=SKILL_ID,
        skip_alexa_signature_verify=True,
    )
    base.update(overrides)
    return Settings(**base)


def make_tone(path: Path, *, seconds: float, volume: str = "0.3", tags: dict | None = None) -> Path:
    tag_args = [arg for key, value in (tags or {}).items() for arg in ("-metadata", f"{key}={value}")]
    subprocess.run(
        [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}:sample_rate=44100",
            "-af", f"volume={volume}", *tag_args, str(path),
        ],
        check=True,
    )
    return path


def audio_seconds(data: bytes, directory: Path) -> float:
    """Decoded length of an AAC stream."""
    sample = directory / "sample.aac"
    sample.write_bytes(data)
    output = subprocess.run(
        ["ffprobe", "-v", "error", "-count_packets", "-select_streams", "a:0",
         "-show_entries", "stream=nb_read_packets,sample_rate", "-of", "csv=p=0", str(sample)],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    sample_rate, packets = output.split(",")[:2]
    return int(packets) * 1024 / int(sample_rate)


def envelope(request: dict, *, device: str | None = DEVICE, audio: dict | None = None) -> dict:
    system: dict = {
        "application": {"applicationId": SKILL_ID},
        "user": {"userId": "user-1"},
        "apiEndpoint": "https://api.amazonalexa.com",
    }
    if device is not None:
        system["device"] = {"deviceId": device, "supportedInterfaces": {"AudioPlayer": {}}}
    context: dict = {"System": system}
    if audio is not None:
        context["AudioPlayer"] = audio
    return {
        "version": "1.0",
        "context": context,
        "request": {
            "requestId": uuid.uuid4().hex,
            "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "locale": "fr-FR",
            **request,
        },
    }


def launch(**kwargs) -> dict:
    return envelope({"type": "LaunchRequest"}, **kwargs)


def intent(name: str, **kwargs) -> dict:
    return envelope({"type": "IntentRequest", "intent": {"name": name}}, **kwargs)


def audio_event(kind: str, token: str, offset_ms: int = 0, **fields) -> dict:
    device = fields.pop("device", DEVICE)
    return envelope(
        {
            "type": f"AudioPlayer.{kind}",
            "token": token,
            "offsetInMilliseconds": offset_ms,
            **fields,
        },
        device=device,
    )


def controller(command: str, **kwargs) -> dict:
    return envelope({"type": f"PlaybackController.{command}"}, **kwargs)
