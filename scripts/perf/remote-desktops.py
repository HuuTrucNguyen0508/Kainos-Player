#!/usr/bin/env python3
"""Launch two real GUIs with seeded profiles. Requires RemoteDesktopFixtureTest setup and packaged jars.
Leaves only owned processes, controlled by this interactive helper. Audio is mpv --ao=null.
Run under flock -x /tmp/kainos-perf.lock. Pass the portable Xvfb path explicitly.
"""
import glob, json, os, pathlib, subprocess, sys, wave
import private_xvfb
root = pathlib.Path(__file__).resolve().parents[2]
profiles = pathlib.Path(sys.argv[1]).resolve()
xvfb = sys.argv[2]
assert (profiles/'pc/settings.json').exists() and (profiles/'phone/settings.json').exists()
with wave.open(str(profiles/'tone.wav'), 'wb') as out:
    out.setnchannels(1); out.setsampwidth(2); out.setframerate(44100)
    out.writeframes(b'\x00\x00'*44100*120)
shim = profiles/'bin'; shim.mkdir(exist_ok=True)
(shim/'mpv').write_text('#!/bin/sh\nexec /usr/bin/mpv --no-config --ao=null "$@"\n'); (shim/'mpv').chmod(0o755)
java = pathlib.Path.home()/'.jdks/temurin-17/bin/java'
app = root/'desktopApp/build/compose/binaries/main/app/Kainos Player/lib/app'
jars = sorted(glob.glob(str(app/'*.jar'))); assert jars, 'Package this worktree first'
hub_port = json.loads((profiles/'phone/settings.json').read_text())['homeLanSyncHub'].get('lastKnownPort', 43822)
processes = []
try:
    for i, name in enumerate(['pc', 'phone']):
        display_log = (profiles/f'{name}-display.log').open('w')
        x, display = private_xvfb.start(xvfb, stdout=subprocess.DEVNULL, stderr=display_log)
        processes.append(x)
        (profiles/f'{name}.display').write_text(display)
        env = os.environ.copy()
        env.update(DISPLAY=display, KAINOS_REMOTE_PRIVATE_DISPLAY=display, WAYLAND_DISPLAY='', XDG_SESSION_TYPE='x11',
                   SKIKO_RENDER_API='SOFTWARE', KAINOS_DATA_DIR=str(profiles/name), KAINOS_INSTANCE=f'remoteproof{name}',
                   KAINOS_HUB_PORT=str(hub_port+i), PATH=str(shim)+':'+env['PATH'])
        log = (profiles/f'{name}.log').open('w')
        processes.append(subprocess.Popen([str(java), '-Dcompose.application.configure.swing.globals=true',
            f'-Dcompose.application.resources.dir={app}/resources', f'-Dskiko.library.path={app}',
            '-Djpackage.app-version=1.0.0', '-cp', ':'.join(jars), 'com.universalmusic.player.desktop.MainKt'], cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT))
        print(f'{name}: DISPLAY={display}', flush=True)
    input('Owned GUIs running. Press Enter to stop both.\n')
finally:
    for process in reversed(processes):
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=8)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
