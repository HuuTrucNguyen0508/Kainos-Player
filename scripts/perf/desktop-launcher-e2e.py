#!/usr/bin/env python3
"""Exercise the actual launcher with three generated FLACs and null output.
Run under flock -x /tmp/kainos-perf.lock after CDS-capable packaging.
"""
import argparse
import os
from pathlib import Path
import subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--out',type=Path,required=True)
a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parents[2]
files=[]
for i in range(3):
    track=a.out/f'tone{i}.flac'
    subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i',f'sine=frequency={440+i*220}:duration=2','-c:a','flac',str(track)],check=True)
    files.append(str(track.resolve()))
config=a.out/'config/mpv';config.mkdir(parents=True,exist_ok=True)
(config/'mpv.conf').write_text('ao=null\n')
env=os.environ.copy()
env.update(KAINOS_DATA_DIR=str(a.out.resolve()/'data'),KAINOS_INSTANCE='launchere2e'+str(os.getpid()),
    KAINOS_HUB_PORT='49991',XDG_CONFIG_HOME=str(a.out.resolve()/'config'),DISPLAY='',WAYLAND_DISPLAY='',
    JAVA_TOOL_OPTIONS='-Xlog:cds=info')
subprocess.run([str(root/'scripts/kainos-player'),'--perf-playback',*files],cwd=root,env=env,check=True,timeout=40)
log=(root/'logs/desktop-run-latest.log').resolve()
text=log.read_text();(a.out/'run.log').write_text(text)
assert 'PERF\tcomplete' in text and 'Exception' not in text and 'engine -> FAILED' not in text
assert 'Mapped dynamic region' in text, 'launcher did not map its generated archive'
assert 'stopAtQueueEnd' in text and 'skipToNext' in text
print('Launcher E2E passed; original log:',log)
