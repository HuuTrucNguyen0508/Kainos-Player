FROM python:3.12-slim-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 echo \
    && useradd --system --uid 10001 --gid echo --home-dir /app --shell /usr/sbin/nologin echo

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src ./src

ENV PYTHONPATH=/app/src \
    HOST=0.0.0.0 \
    PORT=8787 \
    MUSIC_ROOT=/music \
    TEST_TRACK=/music/test.flac \
    MAX_CONCURRENT_STREAMS=2 \
    AAC_BITRATE=256k \
    AAC_SAMPLE_RATE=44100 \
    AAC_CHANNELS=2

# Music is mounted read-only at runtime. No secrets baked into the image.
USER echo
EXPOSE 8787

CMD ["uvicorn", "echo_gateway.main:app", "--host", "0.0.0.0", "--port", "8787", "--log-level", "info"]
