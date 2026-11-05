#!/usr/bin/env python3
"""Verify real JVM startup with missing and stale AppCDS archives.
Run under flock -x /tmp/kainos-perf.lock, after packaging.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--xvfb', default='Xvfb')
p.add_argument('--stale-archive', required=True)
a=p.parse_args()
root=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='kainos-cds-fallback-') as temporary:
    d=Path(temporary)
    (d/'bad.jsa').write_bytes(b'invalid archive')
    for label, archive in [('missing',d/'missing.jsa'),('stale',Path(a.stale_archive))]:
        out=root/'desktopApp/build/compose/cds-fallbacks'/label
        subprocess.run([sys.executable,str(root/'scripts/perf/desktop-bench.py'),'--mode','startup',
            '--runs','1','--out',str(out),'--xvfb',a.xvfb,'--jvm-flag=-Xshare:auto',
            '--jvm-flag=-XX:SharedArchiveFile='+str(archive),'--jvm-flag=-Xlog:cds=info'],check=True)
        summary=json.loads((out/'summary.json').read_text())
        assert summary['errors']==0 and summary['first_draw_ms']['n']==1, (label,summary)
        print(label,'fallback passed')

    # Clone the launcher/package so corruption never touches the packaged archive.
    import hashlib
    import os
    import shutil
    import time
    sandbox=d/'launcher'
    (sandbox/'scripts').mkdir(parents=True)
    shutil.copy2(root/'scripts/kainos-player',sandbox/'scripts/kainos-player')
    source=root/'desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app'
    app=sandbox/'desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app'
    shutil.copytree(source,app)
    archive=app/'kainos.jsa'
    assert archive.is_file(), 'Train an archive before running corruption checks'
    (app/'kainos.jsa.sha256').write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  kainos.jsa\n')
    (app/'kainos.jsa.size').write_text(str(archive.stat().st_size)+'\n')
    with archive.open('r+b') as stream:
        stream.seek(archive.stat().st_size//2)
        byte=stream.read(1)
        stream.seek(-1,1);stream.write(bytes([byte[0]^0xff]))
    tracks=[]
    for i in range(3):
        track=d/f'tone{i}.flac'
        subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','sine=duration=2','-c:a','flac',str(track)],check=True)
        tracks.append(str(track))
    config=d/'config/mpv';config.mkdir(parents=True)
    (config/'mpv.conf').write_text('ao=null\n')
    env={**os.environ,'KAINOS_DATA_DIR':str(d/'data'),'KAINOS_INSTANCE':'corruption'+str(os.getpid()),
         'KAINOS_HUB_PORT':'49992','XDG_CONFIG_HOME':str(d/'config'),'DISPLAY':'','WAYLAND_DISPLAY':''}
    command=[str(sandbox/'scripts/kainos-player'),'--perf-playback',*tracks]
    first=subprocess.run(command,cwd=sandbox,env=env,timeout=40)
    deadline=time.monotonic()+5
    while archive.exists() and time.monotonic()<deadline: time.sleep(.01)
    assert not archive.exists(), 'Corrupt archive must be removed even if JVM crashes'
    subprocess.run(command,cwd=sandbox,env=env,check=True,timeout=40)
    text=(sandbox/'logs/desktop-run-latest.log').read_text()
    assert 'PERF\tcomplete' in text and 'engine -> FAILED' not in text
    print('same-size corruption: first exit',first.returncode,'; next launch completed without archive')
