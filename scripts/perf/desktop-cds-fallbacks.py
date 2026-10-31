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
