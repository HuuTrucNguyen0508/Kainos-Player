#!/usr/bin/env python3
"""Dump a static CDS archive from a prior Library --DumpLoadedClassList run.
Run under the exclusive perf lock; never disables verification.
"""
import argparse
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--classes', type=Path, required=True)
p.add_argument('--archive', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[2]
app = root/'desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app'
java = Path(os.environ.get('JAVA_HOME', str(Path.home()/'.jdks/temurin-17')))/'bin/java'
subprocess.run([str(java), '-Xshare:dump', '-XX:SharedClassListFile='+str(a.classes),
                '-XX:SharedArchiveFile='+str(a.archive), '-cp',
                ':'.join(str(f) for f in sorted(app.glob('*.jar')))], check=True)
