#!/usr/bin/env python3
"""Real packaged-engine checks for fresh FLAC duration and ffprobe fallbacks.
Run under flock -x. Optional --unsafe-playback-jar proves rejection of the old cached-duration shortcut.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--out', type=Path, required=True)
p.add_argument('--unsafe-playback-jar', type=Path)
a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parents[2]
bench=root/'scripts/perf/desktop-bench.py'
for name,fmt,duration in [('stale-flac','flac',123000),('stale-wav','wav',123000),('hires-flac','hires-flac',123000),('unknown-flac','unknown-flac',2000)]:
    out=a.out/name
    subprocess.run([sys.executable,str(bench),'--mode','playback','--runs','1','--fixture-format',fmt,'--out',str(out)],
        env={**os.environ,'KAINOS_PERF_FIXTURE_DURATION_MS':str(duration)},check=True)
    summary=json.loads((out/'summary.json').read_text())
    assert summary['errors']==0, (name,summary)
    print(name, 'observed ffprobe processes:', summary.get('ffprobe_processes'))
if a.unsafe_playback_jar:
    app=root/'desktopApp/build/compose/binaries/main/app/Kainos Player/lib/app'
    unsafe=a.out/('unsafe-package-'+str(os.getpid()))
    shutil.copytree(app,unsafe,ignore=shutil.ignore_patterns('kainos.jsa*'),)
    for jar in unsafe.glob('playback-jvm-*.jar'): jar.unlink()
    shutil.copy2(a.unsafe_playback_jar,unsafe/a.unsafe_playback_jar.name)
    out=a.out/'rejected-cached-duration'
    subprocess.run([sys.executable,str(bench),'--mode','playback','--runs','1','--app-dir',str(unsafe),'--out',str(out)],
        env={**os.environ,'KAINOS_PERF_FIXTURE_DURATION_MS':'123000'},check=True)
    summary=json.loads((out/'summary.json').read_text())
    log=(out/'0-default/run.log').read_text()
    assert summary['errors']==1 and 'PERF_VALUE\tduration_ms\t123000' in log
    print('Old shortcut rejected by the stale-duration check')
print('Fresh-duration and fallback checks passed')
