#!/usr/bin/env python3
"""Three playback-jar arms, interleaved, identical test probe and dependencies.
Run under flock -x /tmp/kainos-perf.lock after jvmTestClasses and jvmJar.
Engine-call to observed playback-restart; Pulse monitor PCM uses 1 ms RMS blocks.
No OS page-cache eviction; private sink/config/data, never user audio.
"""
import argparse, hashlib, json, os, pathlib, re, subprocess as S, time, wave
import numpy as np
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--out', required=True, type=pathlib.Path)
p.add_argument('--app-dir', required=True, type=pathlib.Path)
p.add_argument('--baseline-jar', required=True, type=pathlib.Path)
p.add_argument('--baseline-model-jar', required=True, type=pathlib.Path)
p.add_argument('--restart-jar', required=True, type=pathlib.Path)
p.add_argument('--head-jar', required=True, type=pathlib.Path)
p.add_argument('--runs', type=int, default=10)
p.add_argument('--warm', action='store_true', help='One untimed seek per JVM before preparing the successor; cold results should be retained separately')
a=p.parse_args(); a.out.mkdir(parents=True, exist_ok=False)
root=pathlib.Path(__file__).resolve().parents[2]
S.run(['uptime'], check=True)
(a.out/'artifacts.json').write_text(json.dumps({str(f.resolve()):hashlib.sha256(f.read_bytes()).hexdigest() for f in [a.baseline_jar,a.baseline_model_jar,a.restart_jar,a.head_jar]},indent=2))
sink=f'kainos_seek_{os.getpid()}'
module=S.check_output(['pactl','load-module','module-null-sink',f'sink_name={sink}','rate=48000'],text=True).strip()
rows=[]
try:
    tone=a.out/'tone.flac'
    S.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=frequency=441:duration=10','-c:a','flac',str(tone)],check=True)
    config=a.out/'config/mpv'; config.mkdir(parents=True); (config/'mpv.conf').write_text('ao=pulse\n')
    libs=[str(f.resolve()) for f in sorted(a.app_dir.glob('*.jar')) if not f.name.startswith('playback-jvm-')]
    for run in range(a.runs):
        for variant,jar in [('base',a.baseline_jar),('restart',a.restart_jar),('head',a.head_jar)]:
            d=a.out/f'{run}-{variant}'; d.mkdir()
            env={**os.environ,'PULSE_SINK':sink,'XDG_CONFIG_HOME':str(config.parent.resolve()),
                 'KAINOS_SEEK_WARM':'1' if a.warm else '0',
                 'KAINOS_DATA_DIR':str(d.resolve()/'data'),'KAINOS_INSTANCE':f'seek{os.getpid()}'}
            capture=d/'capture.wav'
            recorder=S.Popen(['ffmpeg','-v','error','-y','-f','pulse','-i',sink+'.monitor','-ac','1','-ar','48000','-c:a','pcm_s16le',str(capture)],stdin=S.PIPE,stderr=(d/'capture.log').open('w'))
            time.sleep(.3)
            arm_libs=([str(a.baseline_model_jar.resolve())]+[f for f in libs if not pathlib.Path(f).name.startswith('model-jvm-')]) if variant=='base' else libs
            cp=':'.join([str(root/'core/playback/build/classes/kotlin/jvm/test'),str(jar.resolve()),*arm_libs])
            try:
                result=S.run([str(pathlib.Path.home()/'.jdks/temurin-17/bin/java'),'-cp',cp,
                    'com.universalmusic.player.platform.SeekLatencyProbeKt',str(tone.resolve()),'single'],env=env,capture_output=True,text=True,timeout=20)
                (d/'run.log').write_text(result.stdout+result.stderr)
                assert result.returncode==0,result.stdout+result.stderr
            finally:
                recorder.communicate(b'q',timeout=10)
                assert recorder.returncode == 0, f'PCM recorder failed: {d}'
            fields=dict(re.findall(r'(\w+)=([\d.]+)',result.stdout.split('SEEK_RESULT ')[1]))
            with wave.open(str(capture)) as wav: pcm=np.frombuffer(wav.readframes(wav.getnframes()),dtype='<i2').astype(float)
            blocks=pcm[:len(pcm)//48*48].reshape(-1,48)
            rms=np.sqrt((blocks**2).mean(axis=1)); audible=np.flatnonzero(rms>100)
            # Exclude initial/final capture silence, count only silence between audible samples.
            inner=rms[audible[0]:audible[-1]+1]<50
            longest=length=0
            for silent in inner:
                length=length+1 if silent else 0; longest=max(longest,length)
            rows.append(dict(run=run,variant=variant,**{k:float(v) for k,v in fields.items()},pcm_gap_ms=longest))
            (a.out/'samples.json').write_text(json.dumps(rows,indent=2))
    # A fresh JVM for the slider storm, same jars/config. Spawn count excludes initial play.
    env={**os.environ,'PULSE_SINK':sink,'XDG_CONFIG_HOME':str(config.parent.resolve()),'KAINOS_DATA_DIR':str(a.out.resolve()/'storm-data')}
    cp=':'.join([str(root/'core/playback/build/classes/kotlin/jvm/test'),str(a.head_jar.resolve()),*libs])
    storm=S.run([str(pathlib.Path.home()/'.jdks/temurin-17/bin/java'),'-cp',cp,'com.universalmusic.player.platform.SeekLatencyProbeKt',str(tone.resolve()),'storm'],env=env,capture_output=True,text=True,timeout=20)
    (a.out/'storm.log').write_text(storm.stdout+storm.stderr); assert storm.returncode==0,storm.stderr
    summary={v:{key:dict(median=float(np.median([r[key] for r in rows if r['variant']==v])),p90=float(np.percentile([r[key] for r in rows if r['variant']==v],90))) for key in ['latency_ms','pcm_gap_ms','spawns']} for v in ['base','restart','head']}
    summary['storm']=storm.stdout
    (a.out/'summary.json').write_text(json.dumps(summary,indent=2)); print(json.dumps(summary,indent=2))
finally: S.run(['pactl','unload-module',module],check=True)
