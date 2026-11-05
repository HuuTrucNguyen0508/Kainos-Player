#!/usr/bin/env python3
"""Fail only the prewarm mpv spawn, then require the normal packaged playback probe to pass.
Run under flock -x /tmp/kainos-perf.lock. No user audio/data is used.
"""
import argparse, os, pathlib, shutil, subprocess as S
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--out',type=pathlib.Path,required=True)
a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
mpv=shutil.which('mpv');assert mpv
binary=a.out/'bin';binary.mkdir()
marker=(a.out/'attempted').resolve()
shim=binary/'mpv'
# Paths are shell quoted because the script is exec'd by the real engine.
import shlex
shim.write_text('#!/bin/sh\nif [ ! -e '+shlex.quote(str(marker))+' ]; then\n  touch '+shlex.quote(str(marker))+'\n  exit 1\nfi\nexec '+shlex.quote(mpv)+' "$@"\n')
shim.chmod(0o755)
S.run(['python',str(pathlib.Path(__file__).with_name('gapless-bench.py')),'--mode','playback','--runs','1',
       '--out',str(a.out/'playback')],env={**os.environ,'PATH':str(binary.resolve())+':'+os.environ['PATH']},check=True)
assert marker.exists()
print('PROOF failed prewarm is silent; first-press fallback and full transport probe passed')
