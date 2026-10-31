#!/usr/bin/env python3
"""Run with flock -x /tmp/kainos-perf.lock. Real engine, raw rendered PCM, no sound device.
Requires precompiled jvmTest classes (build them under the shared lock first).
"""
import argparse, json, math, os, pathlib, subprocess as S
import numpy as np
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args()
a.out.mkdir(exist_ok=False,parents=True)
root=pathlib.Path(__file__).resolve().parents[2]
S.run(['uptime'],check=True)
for pair in ['same']:
    for k in range(3):
        rate=96000 if pair=='mixed' and k==1 else 44100
        S.run(['ffmpeg','-v','error','-f','lavfi','-i',f'sine=frequency=441:sample_rate={rate}:duration=2',
               '-c:a','flac',str(a.out/f'{pair}-{k}.flac')],check=True)
# Deliberately use the project's test runner: its exit code is checked without a pipe.
with (a.out/'run.log').open('w') as log:
    result=S.run(['./gradlew',':core:playback:jvmTest','--tests','*GaplessPcmTest','--rerun-tasks','--console=plain'],cwd=root,
                 env={**os.environ,'JAVA_HOME':str(pathlib.Path.home()/'.jdks/temurin-17'),'KAINOS_GAPLESS_PCM':str(a.out.resolve())},stdout=log,stderr=S.STDOUT)
if result.returncode: raise SystemExit(f'Gradle failed ({result.returncode}); see {a.out}/run.log')
rows=[]
for pair in ['same']:
    for k in range(10):
        pcm=np.fromfile(a.out/f'{pair}-{k}.raw',dtype='<i2').astype(float)
        # 441 Hz has an integer 100-sample period at 44.1 kHz. Compare joins to that period;
        # zeros inserted at a join or a dropped/duplicated sample shift the residual phase.
        assert len(pcm)==176400,(pair,k,len(pcm))
        for boundary in [88200]:
            region=pcm[boundary-200:boundary+200]
            reference=pcm[boundary-400:boundary].copy()
            error=float(np.max(np.abs(region-reference)))
            # Resampler quantization is allowed for mixed-rate; no missing/inserted PCM samples.
            tolerance=2 if pair=='same' else 100
            assert error<=tolerance,(pair,k,boundary,error)
            rows.append(dict(pair=pair,run=k,boundary=boundary,pcm_samples=len(pcm),join_max_error_lsb=error))
(a.out/'results.json').write_text(json.dumps(rows,indent=2))
print(json.dumps(rows,indent=2))
