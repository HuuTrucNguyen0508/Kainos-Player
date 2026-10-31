#!/usr/bin/env python3
"""Optional AppCDS training after packaging; never touches a real app instance.
KAINOS_CDS_XVFB may point to an unpacked Xvfb. Without Xvfb, packaging still succeeds.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[2]
app = root/'desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app'
xvfb = shutil.which(os.environ.get('KAINOS_CDS_XVFB', 'Xvfb'))
if not xvfb:
    print('AppCDS training skipped: Xvfb unavailable; launcher will use normal startup.')
    raise SystemExit(0)
archive = app/'kainos.jsa'
# A partial/failed training archive is never installed over a previous valid archive.
# The final archive filename is not part of the Java classpath, only jar paths are.
with tempfile.TemporaryDirectory(prefix='kainos-cds-') as temporary:
    pending = Path(temporary)/'kainos.jsa'
    output = root/'desktopApp/build/compose/cds-training'
    result = subprocess.run([sys.executable, str(root/'scripts/perf/desktop-bench.py'),
        '--mode', 'library', '--app-dir', str(app), '--runs', '1', '--out', str(output),
        '--xvfb', xvfb, '--settle-ms', '0', '--jvm-flag=-XX:ArchiveClassesAtExit='+str(pending)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    print(result.stdout)
    summary = json.loads((output/'summary.json').read_text()) if (output/'summary.json').exists() else {'errors': 1}
    valid = False
    if result.returncode == 0 and summary.get('errors') == 0 and pending.is_file() and pending.stat().st_size > 0:
        java = Path(os.environ.get('JAVA_HOME', str(Path.home()/'.jdks/temurin-17')))/'bin/java'
        classpath = ':'.join(str(f) for f in sorted(app.glob('*.jar')))
        try:
            validation = subprocess.run([str(java), '-Xshare:on', '-XX:SharedArchiveFile='+str(pending),
                '-cp', classpath, '-version'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=20)
            (output/'archive-validation.log').write_text(validation.stdout)
            valid = validation.returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            pass
    if valid:
        staged = app/'kainos.jsa.pending'
        shutil.copyfile(pending, staged)
        staged.replace(archive)
        size_file = app/'kainos.jsa.size.pending'
        size_file.write_text(str(archive.stat().st_size)+'\n')
        size_file.replace(app/'kainos.jsa.size')
        digest_file = app/'kainos.jsa.sha256.pending'
        digest_file.write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  kainos.jsa\n')
        digest_file.replace(app/'kainos.jsa.sha256')
        print('AppCDS archive ready:', archive)
    else:
        print('AppCDS training unavailable; launcher will use normal startup.')
