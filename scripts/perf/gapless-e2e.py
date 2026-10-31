#!/usr/bin/env python3
"""Packaged playback jars plus the lane's own probe, private Xvfb and private Pulse sink.
Run under flock -x /tmp/kainos-perf.lock after jvmTestClasses and desktop packaging.
"""
import argparse, os, pathlib, select, subprocess as S
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',required=True,type=pathlib.Path);p.add_argument('--app-dir',required=True,type=pathlib.Path);a=p.parse_args()
a.out.mkdir(parents=True,exist_ok=False);root=pathlib.Path(__file__).resolve().parents[2]
S.run(['uptime'],check=True)
r,w=os.pipe();x=S.Popen(['/tmp/kainos-desktop-tools/usr/bin/Xvfb','-displayfd',str(w),'-screen','0','1280x800x24','-nolisten','tcp'],pass_fds=[w],stderr=(a.out/'xvfb.log').open('w'));os.close(w)
module=None
try:
    assert select.select([r],[],[],10)[0];display=':'+os.read(r,32).decode().strip();os.close(r)
    sink=f'kainos_gapless_{os.getpid()}'
    module=S.check_output(['pactl','load-module','module-null-sink',f'sink_name={sink}','rate=48000'],text=True).strip()
    config=a.out/'config/mpv';config.mkdir(parents=True);(config/'mpv.conf').write_text('ao=pulse\n')
    files=[]
    for k in range(3):
        f=a.out/f'tone{k}.flac';files.append(str(f.resolve()))
        S.run(['ffmpeg','-v','error','-f','lavfi','-i',f'sine=frequency={440+220*k}:duration=2','-c:a','flac',str(f)],check=True)
    cp=':'.join([str(root/'core/playback/build/classes/kotlin/jvm/test'),*[str(f) for f in sorted(a.app_dir.glob('*.jar'))]])
    env={**os.environ,'JAVA_HOME':str(pathlib.Path.home()/'.jdks/temurin-17'),'DISPLAY':display,'WAYLAND_DISPLAY':'',
        'KAINOS_DATA_DIR':str((a.out/'data').resolve()),'KAINOS_INSTANCE':f'gapless{os.getpid()}',
        'KAINOS_HUB_PORT':'49987','XDG_CONFIG_HOME':str(config.parent.resolve()),'PULSE_SINK':sink}
    with (a.out/'run.log').open('w') as log:
        result=S.run([env['JAVA_HOME']+'/bin/java','-cp',cp,'com.universalmusic.player.platform.GaplessPackagedProbeKt',*files],env=env,stdout=log,stderr=S.STDOUT,timeout=90)
    text=(a.out/'run.log').read_text()
    assert result.returncode==0 and text.count('PROOF ')==5,text
    assert 'Exception in thread' not in text and '\tat com.' not in text,text
    print(text)
finally:
    x.terminate();x.wait()
    if module:S.run(['pactl','unload-module',module],check=True)
