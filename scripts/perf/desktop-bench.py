#!/usr/bin/env python3
"""Run under flock -x /tmp/kainos-perf.lock. No page-cache eviction: cold JVM, warm OS cache.
Requires packaged jars, Temurin 17, real mpv/ffmpeg, private Xvfb for startup.
All raw runs, failures, logs and environment metadata are retained in --out.
"""
import threading, runpy
import argparse, ctypes as C, glob, json, math, os, pathlib, shutil, signal, statistics, subprocess as S, time, wave
P = pathlib.Path
ROOT = P(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--out', required=True, type=P)
p.add_argument('--runs', type=int, default=10)
p.add_argument('--app-dir', type=P, help='Immutable packaged lib/app directory')
p.add_argument('--compare-app-dir', type=P, help='Interleave baseline package with --app-dir candidate')
p.add_argument('--mode', choices=['startup', 'playback', 'spotify', 'idle-gui'], required=True)
p.add_argument('--jvm-flag', action='append', default=[])
p.add_argument('--xvfb', default='Xvfb')
p.add_argument('--startup-matrix', action='store_true', help='Interleave default, tier1, tier2 and SerialGC for each run')
p.add_argument('--capture-pcm', action='store_true', help='Requires pactl, ffmpeg PulseAudio and numpy; records only a private null sink')
a = p.parse_args(); a.out.mkdir(parents=True, exist_ok=True)
(a.out/'environment.json').write_text(json.dumps(dict(load=os.getloadavg(), cores=os.cpu_count(),
    flags=a.jvm_flag, page_cache_dropped=False, revision=S.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()), indent=2))
app = a.app_dir or ROOT/'desktopApp/build/compose/binaries/main/app/Kainos Player/lib/app'
java = P(os.environ.get('JAVA_HOME', str(P.home()/'.jdks/temurin-17')))/'bin/java'
cmd = [str(java), *a.jvm_flag, '-Dcompose.application.configure.swing.globals=true',
    f'-Dcompose.application.resources.dir={app}/resources', f'-Dskiko.library.path={app}',
    '-Djpackage.app-version=1.0.0','-cp', ':'.join(sorted(glob.glob(str(app/'*.jar')))),
    'com.universalmusic.player.desktop.MainKt']
assert list(app.glob('*.jar')), 'Build :desktopApp:createDistributable first'
metadata=json.loads((a.out/'environment.json').read_text())
metadata['packaged_jars']=[dict(name=f.name,size=f.stat().st_size) for f in sorted(app.glob('*.jar'))]
metadata['worktree_dirty']=bool(S.check_output(['git','status','--porcelain'],cwd=ROOT,text=True))
(a.out/'environment.json').write_text(json.dumps(metadata,indent=2))

def finish(proc):
    if proc.poll() is None:
        os.killpg(proc.pid, signal.SIGTERM)
        try: proc.wait(timeout=8)
        except S.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL); proc.wait()

def markers(file):
    result = {}
    for line in file.read_text(errors='replace').splitlines():
        if line.startswith('PERF\t'):
            _, name, ns = line.split('\t'); result.setdefault(name, int(ns))
    return result

x = None
if a.mode != 'playback':
    # -displayfd allocates a free private server; never connect to the user's display.
    r,w=os.pipe()
    x=S.Popen([a.xvfb,'-displayfd',str(w),'-screen','0','1280x800x24','-nolisten','tcp'],pass_fds=[w],stdout=S.DEVNULL,stderr=(a.out/'xvfb.log').open('w'))
    os.close(w)
    display=':'+os.read(r,32).decode().strip(); os.close(r)
    if not display[1:]: raise RuntimeError('Xvfb failed; see xvfb.log')

rows=[]
recorder=None
module=None
if a.capture_pcm:
    assert a.mode=='playback'
    sink='kainos_perf_'+str(os.getpid())
    module=S.check_output(['pactl','load-module','module-null-sink',f'sink_name={sink}','rate=48000'],text=True).strip()
try:
    variants=[('default',[]),('tier1',['-XX:TieredStopAtLevel=1']),('tier2',['-XX:TieredStopAtLevel=2']),('serial',['-XX:+UseSerialGC'])] if a.startup_matrix else [('default',[])]
    if a.compare_app_dir: variants=[('baseline',[]),('candidate',[])]
    for iteration in range(a.runs*len(variants)):
        i=iteration//len(variants)
        variant,extra_flags=variants[iteration%len(variants)]
        d=a.out/(str(i)+'-'+variant); d.mkdir(exist_ok=True)
        env=os.environ.copy()
        env.update(KAINOS_DATA_DIR=str(d.resolve()/'data'), KAINOS_INSTANCE=f'perfdesktop{i}',
            KAINOS_HUB_PORT=str(49000+i), KAINOS_PERF_MARKERS='1', KAINOS_MUSIC_DIRS=str(d.resolve()/'music'))
        (d/'music').mkdir(exist_ok=True)
        selected=a.compare_app_dir if a.compare_app_dir and variant=='baseline' else app
        command=[cmd[0],*extra_flags,*cmd[1:]]
        if selected != app:
            command=[part.replace(str(app),str(selected)) for part in command]
            command[command.index('-cp')+1]=':'.join(str(f) for f in sorted(selected.glob('*.jar')))
        if a.mode != 'playback': env.update(DISPLAY=display, WAYLAND_DISPLAY='', SKIKO_RENDER_API='SOFTWARE')
        if a.mode == 'idle-gui': env['KAINOS_PERF_IDLE_QUEUE']='1'
        if a.mode == 'spotify':
            if S.run(['pgrep','-x','spotify'],stdout=S.DEVNULL).returncode == 0:
                raise RuntimeError('Spotify is already running; refusing to launch or terminate it')
            # Isolated config/cache, never the real account or user flags.
            env.update(XDG_CONFIG_HOME=str(d.resolve()/'config'), XDG_CACHE_HOME=str(d.resolve()/'cache'))
            command=['/usr/bin/spotify','--disable-gpu','--no-sandbox']
            lib=C.CDLL('libX11.so.6'); lib.XOpenDisplay.restype=C.c_void_p
            xd=lib.XOpenDisplay(display.encode()); assert xd
            lib.XDefaultRootWindow.argtypes=[C.c_void_p]; lib.XDefaultRootWindow.restype=C.c_ulong
            root=lib.XDefaultRootWindow(xd)
            lib.XQueryTree.argtypes=[C.c_void_p,C.c_ulong,C.POINTER(C.c_ulong),C.POINTER(C.c_ulong),C.POINTER(C.POINTER(C.c_ulong)),C.POINTER(C.c_uint)]
            lib.XGetWindowAttributes.argtypes=[C.c_void_p,C.c_ulong,C.c_void_p]
            class Attr(C.Structure):
                _fields_=[('x',C.c_int),('y',C.c_int),('width',C.c_int),('height',C.c_int),('border_width',C.c_int),('depth',C.c_int),('visual',C.c_void_p),('root',C.c_ulong),('cls',C.c_int),('bit_gravity',C.c_int),('win_gravity',C.c_int),('backing_store',C.c_int),('backing_planes',C.c_ulong),('backing_pixel',C.c_ulong),('save_under',C.c_int),('colormap',C.c_ulong),('map_installed',C.c_int),('map_state',C.c_int),('all_event_masks',C.c_long),('your_event_mask',C.c_long),('do_not_propagate_mask',C.c_long),('override_redirect',C.c_int),('screen',C.c_void_p)]
            def mapped():
                rr=C.c_ulong(); parent=C.c_ulong(); children=C.POINTER(C.c_ulong)(); n=C.c_uint()
                lib.XQueryTree(xd,root,C.byref(rr),C.byref(parent),C.byref(children),C.byref(n))
                found=False
                for k in range(n.value):
                    at=Attr(); lib.XGetWindowAttributes(xd,children[k],C.byref(at))
                    found |= at.map_state==2 and at.width>200 and at.height>200
                if children: lib.XFree(children)
                return found
        if a.mode == 'playback':
            config=d.resolve()/'config'; (config/'mpv').mkdir(parents=True,exist_ok=True)
            (config/'mpv/mpv.conf').write_text('ao='+('pulse' if a.capture_pcm else 'null')+'\n')
            env['XDG_CONFIG_HOME']=str(config)
            files=[]
            for k in range(3):
                file=d/f'tone{k}.flac'
                S.run(['ffmpeg','-v','error','-y','-f','lavfi','-i',f'sine=frequency={440+k*220}:duration=2','-c:a','flac',str(file)],check=True)
                files.append(str(file.resolve()))
            command=command+['--perf-playback',*files]
        recorder=None
        if a.capture_pcm:
            env.update(PULSE_SINK=sink)
            recorder=S.Popen(['ffmpeg','-v','error','-y','-f','pulse','-i',sink+'.monitor','-ac','1','-ar','48000','-c:a','pcm_s16le',str(d/'capture.wav')],stdin=S.PIPE,stdout=S.DEVNULL,stderr=(d/'capture.log').open('w'))
            time.sleep(.3)
        with (d/'run.log').open('w') as log:
            start=time.monotonic_ns(); proc=S.Popen(command,cwd=ROOT,env=env,stdout=log,stderr=S.STDOUT,start_new_session=True)
            observer_stop=threading.Event()
            if a.mode=='playback':
                observe=runpy.run_path(str(ROOT/'scripts/perf/desktop-mpv-probe.py'))['observe_children']
                threading.Thread(target=observe,args=(proc.pid,d/'mpv.jsonl',observer_stop),daemon=True).start()
            row=dict(run=i,variant=variant)
            try:
                if a.mode in ('playback','idle-gui'):
                    idle=None
                    for _ in range(6000):
                        m=markers(d/'run.log')
                        if 'idle_begin' in m and idle is None:
                            stat=P(f'/proc/{proc.pid}/stat').read_text().split(); idle=(time.monotonic(),int(stat[13])+int(stat[14]))
                        if 'idle_end' in m and idle:
                            stat=P(f'/proc/{proc.pid}/stat').read_text().split()
                            row['idle_cpu_percent']=100*((int(stat[13])+int(stat[14])-idle[1])/os.sysconf('SC_CLK_TCK'))/(time.monotonic()-idle[0])
                            row['idle_rss_kib']=int(next(l for l in P(f'/proc/{proc.pid}/status').read_text().splitlines() if l.startswith('VmRSS:')).split()[1]); break
                        if proc.poll() is not None: break
                        time.sleep(.01)
                    if a.mode=='idle-gui': finish(proc)
                    else: proc.wait(timeout=10)
                    if a.mode=='idle-gui':
                        assert 'idle_rss_kib' in row, 'no GUI idle sample'
                        rows.append(row); (a.out/'runs.json').write_text(json.dumps(rows,indent=2)); print(json.dumps(row),flush=True); continue
                    assert proc.returncode==0 and 'complete' in markers(d/'run.log'), 'playback did not complete'
                    assert 'Exception' not in (d/'run.log').read_text() and 'engine -> FAILED' not in (d/'run.log').read_text(), 'playback exception or engine failure'
                    m=markers(d/'run.log'); events=[json.loads(l) for l in (d/'mpv.jsonl').read_text().splitlines()]
                    played=next(e for e in events if e.get('event')=='playback-restart')
                    row['press_to_started_ms']=(played['ns']-m['session_play'])/1e6
                    for name, value in [('pause',True),('resume',False)]:
                        e=next(e for e in events if e['ns']>=m['engine_'+name] and e.get('name')=='pause' and e.get('data')==value)
                        row[name+'_ack_ms']=(e['ns']-m['engine_'+name])/1e6
                else:
                    for _ in range(3000):
                        if a.mode=='startup' and 'first_draw' in markers(d/'run.log'):
                            row['first_draw_ms']=(markers(d/'run.log')['first_draw']-start)/1e6; break
                        if a.mode=='spotify' and mapped(): row['mapped_ms']=(time.monotonic_ns()-start)/1e6; break
                        if proc.poll() is not None: raise RuntimeError('exited before drawing/mapping')
                        time.sleep(.01)
                    else: raise TimeoutError('no frame/window within 30s')
            except Exception as e:
                row['error']=str(e)
            finally:
                finish(proc)
                observer_stop.set()
        if recorder:
            recorder.communicate(b'q',timeout=10)
            # Identify the two consecutive natural-transition tones using 5ms FFT blocks.
            # Report the actual low-energy PCM between them, not the spacing of mpv events.
            import numpy as np
            with wave.open(str(d/'capture.wav')) as wav:
                assert wav.getframerate()==48000 and wav.getnchannels()==1
                pcm=np.frombuffer(wav.readframes(wav.getnframes()),dtype='<i2').astype(float)
            size=240; blocks=pcm[:len(pcm)//size*size].reshape(-1,size)
            rms=np.sqrt(np.mean(blocks*blocks,axis=1))
            freqs=np.fft.rfftfreq(size,1/48000)
            peaks=freqs[np.abs(np.fft.rfft(blocks)).argmax(axis=1)]
            second=np.where((peaks==600)&(rms>100))[0]
            third=np.where((peaks==800)&(rms>100))[0]
            if len(second) and len(third):
                left=second[-1]; right=third[third>left][0]
                row['natural_pcm_silence_ms']=float(np.sum(rms[left+1:right]<50)*5)
                row['natural_pcm_boundary_ms']=float((right-left-1)*5)
                row['pcm_resolution_ms']=5
            else: row['error']=(row.get('error','')+'; natural transition tones missing in PCM').strip('; ')
        if a.mode=='spotify': time.sleep(1)
        rows.append(row); print(json.dumps(row),flush=True)
        (a.out/'runs.json').write_text(json.dumps(rows,indent=2))
finally:
    if recorder and recorder.poll() is None:
        recorder.communicate(b'q',timeout=10)
    if x: x.terminate(); x.wait()
    if module: S.run(['pactl','unload-module',module],check=True)
summary={}
for key in sorted(set().union(*(r.keys() for r in rows))-{'run','error','variant'}):
    vals=sorted(r[key] for r in rows if key in r)
    summary[key]=dict(n=len(vals),median=statistics.median(vals),p90=vals[math.ceil(.9*len(vals))-1],min=min(vals),max=max(vals))
if a.startup_matrix:
    summary={variant:{key:dict(n=len(vals),median=statistics.median(vals),p90=sorted(vals)[math.ceil(.9*len(vals))-1],min=min(vals),max=max(vals)) for key in ['first_draw_ms'] if (vals:=[r[key] for r in rows if r['variant']==variant and key in r])} for variant,_ in variants}
if a.compare_app_dir:
    summary={variant:{key:dict(n=len(vals),median=statistics.median(vals),p90=sorted(vals)[math.ceil(.9*len(vals))-1],min=min(vals),max=max(vals)) for key in sorted(set().union(*(r.keys() for r in rows))-{'run','error','variant'}) if (vals:=[r[key] for r in rows if r['variant']==variant and key in r])} for variant,_ in variants}
summary['errors']=sum('error' in r for r in rows)
(a.out/'summary.json').write_text(json.dumps(summary,indent=2)); print(json.dumps(summary,indent=2))
