#!/usr/bin/env python3
"""Real mpv output clock probe. Run under flock -x /tmp/kainos-perf.lock."""
import json, pathlib, socket, subprocess, tempfile, time, wave, struct, select
with tempfile.TemporaryDirectory(prefix='kainos-short-') as tmp:
    root=pathlib.Path(tmp)
    for name,duration in [('a',2),('b',.05)]:
        with wave.open(str(root/f'{name}.wav'),'wb') as w:
            w.setparams((1,2,44100,0,'NONE','not compressed'))
            w.writeframes(struct.pack('<h',1000)*int(44100*duration))
    p=subprocess.Popen(['mpv','--no-config','--ao=null','--idle=yes','--gapless-audio=yes',f'--input-ipc-server={root}/ipc','--no-terminal'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        for _ in range(200):
            if (root/'ipc').exists(): break
            time.sleep(.01)
        s=socket.socket(socket.AF_UNIX);s.connect(str(root/'ipc'));f=s.makefile('rb')
        def cmd(c):
            s.sendall((json.dumps({'command':c})+'\n').encode())
            while True:
                r=json.loads(f.readline())
                if 'event' in r: print(round(time.monotonic()-start,3),r,flush=True)
                else: return r
        start=time.monotonic()
        cmd(['loadfile',str(root/'a.wav'),'replace']);cmd(['loadfile',str(root/'b.wav'),'append'])
        while time.monotonic()-start<2.5:
            pts=cmd(['get_property','audio-pts']);path=cmd(['get_property','path'])
            print(round(time.monotonic()-start,3),'pts',pts.get('data'),'path',path.get('data'),flush=True)
            time.sleep(.01)
    finally: p.terminate();p.wait()
