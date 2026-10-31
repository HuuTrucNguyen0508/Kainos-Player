#!/usr/bin/env python3
"""mpv shim used only by desktop-bench.py; real mpv, private/null output."""
import json, os, socket, subprocess, sys, time
from pathlib import Path

args = sys.argv[1:]
sock = next(x.split('=', 1)[1] for x in args if x.startswith('--input-ipc-server='))
log = Path(os.environ['KAINOS_PERF_MPV_LOG'])
def record(event):
    with log.open('a') as f:
        f.write(json.dumps(dict(ns=time.monotonic_ns(), pid=os.getpid(), **event)) + '\n')
record(dict(event='spawn'))
p = subprocess.Popen(['/usr/bin/mpv', '--no-config', '--ao=' + os.environ.get('KAINOS_PERF_AO', 'null'), *args])
s = socket.socket(socket.AF_UNIX)
for _ in range(1000):
    try:
        s.connect(sock)
        break
    except OSError:
        if p.poll() is not None:
            sys.exit(p.returncode)
        time.sleep(.001)
else:
    p.terminate()
    sys.exit(2)
s.sendall(b'{"command":["observe_property",1,"pause"]}\n')
for line in s.makefile():
    try:
        record(json.loads(line))
    except ValueError:
        pass
sys.exit(p.wait())
