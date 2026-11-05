#!/usr/bin/env python3
"""Run required Gradle checks on a private Xvfb display. Invoke under flock -s."""
import argparse
import fcntl
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--xvfb', default='Xvfb')
a = p.parse_args()
root = Path(__file__).resolve().parents[2]
(root/'desktopApp/build').mkdir(parents=True, exist_ok=True)
build_lock = (root/'desktopApp/build/.perf-build.lock').open('a')
fcntl.flock(build_lock, fcntl.LOCK_EX)
import private_xvfb
server, display = private_xvfb.start(a.xvfb)
try:
    env = os.environ.copy()
    env.update(DISPLAY=display, WAYLAND_DISPLAY='', SKIKO_RENDER_API='SOFTWARE',
               JAVA_HOME=str(Path.home()/'.jdks/temurin-17'))
    result = subprocess.run(['./gradlew', 'jvmTest', ':core:data:verifyCommonMainKainosDatabaseMigration',
                             ':desktopApp:createDistributable', '--rerun', '--continue', '--console=plain'], env=env,
                            cwd=Path(__file__).resolve().parents[2])
finally:
    server.terminate()
    server.wait()
    build_lock.close()
raise SystemExit(result.returncode)
