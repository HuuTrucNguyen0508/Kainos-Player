# Desktop performance probes

Build with JDK 17, then run under the shared measurement lock:

```bash
flock -s /tmp/kainos-perf.lock env JAVA_HOME="$HOME/.jdks/temurin-17" ./gradlew :desktopApp:createDistributable
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-bench.py --mode playback --capture-pcm --out /tmp/kainos-playback-baseline
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-bench.py --mode startup --startup-matrix --out /tmp/kainos-startup
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-bench.py --mode spotify --out /tmp/spotify-startup
```

Default sample count is 10 per arm. Startup requires Xvfb; `--xvfb /absolute/path/to/Xvfb` supports an unpacked package. Playback uses real mpv and ffmpeg, and psutil for external child-process discovery. PCM capture additionally requires numpy, pactl and ffmpeg's PulseAudio input. The sink is private and unloaded on exit. No account, real library, speakers or phone is used. Raw logs, every failed run, WAV captures, timestamps, CPU/RSS samples and summary JSON stay in the output directory.

The command uses exactly the launcher's Temurin java classpath and system properties against `createDistributable` jars. It starts no Gradle runtime. Optional JVM flags are passed with `--jvm-flag=-XX:...`. The matrix interleaves default, TieredStopAtLevel=1, TieredStopAtLevel=2 and SerialGC. These are experiments, not shipped launcher defaults. The page cache is NOT dropped: startup means cold JVM with warm OS cache. Xvfb software rendering differs from the user's GPU desktop.

`first_draw_ms` ends after the first Compose content draw callback. It does not prove display presentation or Library interaction. Spotify's `mapped_ms` ends at a mapped nontrivial X window, a different endpoint, so do not claim a like-for-like winner. Spotify runs only when no Spotify process exists, with isolated XDG config/cache, no playback, and SIGTERM on completion.

Playback starts at the real `PlayerSession.play` call and ends at mpv's `playback-restart` event observed over JSON IPC. Pause/resume start at the engine call and end at mpv's pause-property notification, which acknowledges state but does not prove hardware drain latency. The external observer discovers only this probe JVM's mpv children, polls for their initial socket connection, then streams events. It adds no subprocess to the playback path. Instrumentation is identical in both arms.

Three synthetic two-second FLAC tones exercise pause, resume, seek, manual skip, and natural completion. A second queue of 512 entries pauses for five seconds; RSS and CPU measure that packaged probe JVM, without Compose UI. It is not full-GUI idle memory. The dedicated single session thread follows PlayerSession's confinement contract without assuming Compose initialized a Main dispatcher.

PCM capture identifies the naturally consecutive 660 Hz and 880 Hz tones, then counts low-energy five-millisecond blocks between them. `natural_pcm_silence_ms` is measured recorded silence, not event spacing; `natural_pcm_boundary_ms` includes mixed boundary blocks and sink buffering. Resolution is 5 ms; this cannot prove sample-accurate gapless. Pauses in the first tone and startup silence are excluded by frequency, rather than manually deleting samples. Captures remain available for independent analysis.

Full checks can run without the user's display:

```bash
flock -s /tmp/kainos-perf.lock python scripts/perf/desktop-checks.py --xvfb /absolute/path/to/Xvfb
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-bench.py --mode idle-gui --out /tmp/kainos-gui-idle
```

`idle-gui` restores a 512-entry synthetic queue paused into the real AppContainer after readiness, verifies its size, lets the UI settle for two seconds, and samples process CPU/RSS for five seconds. No media is opened. The fixture exists only under `KAINOS_PERF_IDLE_QUEUE=1` and requires isolation. Its numbers are separate from the smaller playback-only probe JVM.

Calibration failures remain under `/tmp/kainos-desktop-baseline-playback` (10 missing-Main-dispatcher failures) and `/tmp/kainos-desktop-baseline-pcm` (four interrupted/failed PCM runs with malformed `file:/` fixture paths). These are harness failures, not latency samples. The initial Spotify attempt under `/tmp/kainos-desktop-spotify` measured one window at 3552 ms, then refused the next launch while its own child was still exiting. The corrected harness waits one second after SIGTERM. The ten-run Spotify comparison used fresh isolated config directories and warm OS pages; the initial 3552 ms observation is not silently pooled or discarded.

The earlier ten-run `/tmp/kainos-desktop-baseline-valid` dataset used a Python mpv wrapper. That wrapper added process-launch overhead to every start and transition; the data is retained as `calibration-wrapper-*` and superseded for absolute playback latency. The accepted comparison uses interleaved baseline/candidate runs in `/tmp/kainos-desktop-native-ab`.

To interleave two immutable packaged versions, add `--compare-app-dir /path/to/baseline/lib/app --app-dir /path/to/candidate/lib/app` to playback mode. Ten runs means ten per arm, in A/B order. Snapshot the packages before running any further builds. The recorded native A/B baseline substituted the exact original playback jar, MD5 `a977f65831d9501bad578adc8f60c86b`, into a copy of the candidate package. Every other jar and resource is identical between arms. This isolates the duration-probe change and avoids races with Gradle packaging. The original jar was read from another lane's unchanged package and its content digest was checked before use.

Earlier `/tmp/kainos-desktop-baseline-native` and `/tmp/kainos-desktop-profile` failed before main due to an incomplete package created by concurrently queued packaging builds. They are excluded harness failures, with logs retained. The successful serial full-check logs are `/tmp/kainos-desktop-checks-serial.log` and `/tmp/kainos-desktop-candidate-checks.log`.
