#!/usr/bin/env python3
"""Idle mpv and JVM-death proof. Run under the exclusive perf lock after packaging.
Only private config/data and a private Pulse sink are used. Raw evidence stays in --out.
"""
import argparse, json, os, pathlib, select, signal, socket, subprocess as S, time
import psutil
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--out', required=True, type=pathlib.Path)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=False)
root = pathlib.Path(__file__).resolve().parents[2]
app = root/'desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app'
java = pathlib.Path.home()/'.jdks/temurin-17/bin'
cp = ':'.join(str(f) for f in sorted(app.glob('*.jar')))
source = a.out/'WarmupProof.java'
source.write_text('''public class WarmupProof {
 public static void main(String[] args) throws Exception {
  com.universalmusic.player.platform.DesktopPlaybackWarmup.INSTANCE.start().get(10, java.util.concurrent.TimeUnit.SECONDS);
  System.out.println("READY"); System.out.flush();
  while (System.in.read() != -1) { }
 }
}''')
S.run([str(java/'javac'), '-cp', cp, str(source)], check=True)
S.run(['uptime'], check=True)
sink = f'kainos_warmup_{os.getpid()}'
module = S.check_output(['pactl','load-module','module-null-sink',f'sink_name={sink}'],text=True).strip()
rows = []
try:
    for mode in ['sigterm','sigkill','missing']:
        config = a.out/mode/'config/mpv'; config.mkdir(parents=True)
        (config/'mpv.conf').write_text('ao=pulse\n')
        env = {**os.environ, 'XDG_CONFIG_HOME':str(config.parent.resolve()), 'PULSE_SINK':sink,
               'KAINOS_DATA_DIR':str((a.out/mode/'data').resolve()), 'KAINOS_INSTANCE':f'warmproof{os.getpid()}',
               'KAINOS_HUB_PORT':'49981'}
        if mode == 'missing': env['PATH'] = '/nonexistent'
        proc = S.Popen([str(java/'java'),'-cp',f'{a.out.resolve()}:{cp}','WarmupProof'], env=env,
                       stdin=S.PIPE,stdout=S.PIPE,stderr=(a.out/f'{mode}.stderr').open('w'))
        children = []
        try:
            assert select.select([proc.stdout], [], [], 15)[0], 'warm-up JVM readiness timeout'
            assert proc.stdout.readline() == b'READY\n'
            children = [c for c in psutil.Process(proc.pid).children() if c.name() == 'mpv']
            if mode == 'missing':
                assert not children
                rows.append(dict(mode=mode, children=0))
            else:
                assert len(children) == 1
                child = children[0]
                path = next(x.split('=',1)[1] for x in child.cmdline() if x.startswith('--input-ipc-server='))
                with socket.socket(socket.AF_UNIX) as channel:
                    channel.connect(path); channel.settimeout(2)
                    replies = {}
                    reader = channel.makefile("rb")
                    for key in ['idle-active','current-ao','audio-params']:
                        channel.sendall((json.dumps({'command':['get_property',key]})+'\n').encode())
                        while True:
                            reply = json.loads(reader.readline())
                            if 'error' in reply: break
                        replies[key] = reply
                assert replies['idle-active']['data'] is True
                assert all(replies[k]['error'] == 'property unavailable' for k in ['current-ao','audio-params']),replies
                streams = json.loads(S.check_output(['pactl','-f','json','list','sink-inputs'],text=True))
                assert not [s for s in streams if s.get('properties',{}).get('application.process.id') == str(child.pid)]
                before = sum(child.cpu_times()[:2]); start=time.monotonic(); time.sleep(5)
                cpu = 100*(sum(child.cpu_times()[:2])-before)/(time.monotonic()-start)
                rows.append(dict(mode=mode,mpv_rss_kib=child.memory_info().rss//1024,cpu_percent=cpu,
                                 audio_output_open=False,pulse_streams=0,replies=replies))
                assert cpu == 0, cpu
            proc.send_signal(signal.SIGKILL if mode == 'sigkill' else signal.SIGTERM)
            proc.wait(timeout=5)
            deadline = time.monotonic()+5
            while any(c.is_running() and c.status()!=psutil.STATUS_ZOMBIE for c in children) and time.monotonic()<deadline: time.sleep(.01)
            assert not any(c.is_running() and c.status()!=psutil.STATUS_ZOMBIE for c in children),'orphan mpv'
            rows[-1]['no_orphan'] = True
        finally:
            if proc.poll() is None: proc.kill(); proc.wait()
            for c in children:
                if c.is_running() and c.status()!=psutil.STATUS_ZOMBIE: c.kill()
finally:
    S.run(['pactl','unload-module',module],check=True)
(a.out/'summary.json').write_text(json.dumps(rows,indent=2))
print(json.dumps(rows,indent=2))
