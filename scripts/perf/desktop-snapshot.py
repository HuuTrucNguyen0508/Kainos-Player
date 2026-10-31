#!/usr/bin/env python3
"""Freeze packaged jars for an A/B run; invoke under the exclusive performance lock.
An optional baseline playback jar replaces only that module, leaving the probe and dependencies identical.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--out',type=Path,required=True)
p.add_argument('--playback-jar',type=Path)
a=p.parse_args()
root=Path(__file__).resolve().parents[2]
app=root/'desktopApp/build/compose/binaries/main/app/Kainos Player/lib/app'
shutil.copytree(app,a.out,ignore=shutil.ignore_patterns('kainos.jsa*'))
if a.playback_jar:
    for jar in a.out.glob('playback-jvm-*.jar'): jar.unlink()
    shutil.copy2(a.playback_jar,a.out/a.playback_jar.name)
manifest={}
for jar in sorted(a.out.glob('*.jar')):
    content=jar.read_bytes()
    suffix=jar.stem.rsplit('-',1)[-1]
    if len(suffix)==32: assert hashlib.md5(content).hexdigest()==suffix, jar
    manifest[jar.name]=hashlib.sha256(content).hexdigest()
(a.out/'perf-manifest.json').write_text(json.dumps(manifest,indent=2))
print(a.out, len(manifest), 'verified jars')
