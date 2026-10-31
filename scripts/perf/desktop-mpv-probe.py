#!/usr/bin/env python3
"""Observe only this probe JVM's real mpv children. No wrapper in the audio path.
Requires psutil. Each observer owns an IPC connection, consumes events, and writes JSONL.
"""
import json
from pathlib import Path
import socket
import threading
import time
import psutil


def observe_children(pid, output, stop):
    seen = set()
    lock = threading.Lock()
    def observe(child, path):
        def record(event):
            with lock, Path(output).open('a') as f:
                f.write(json.dumps(dict(ns=time.monotonic_ns(), pid=child.pid, **event)) + '\n')
        with socket.socket(socket.AF_UNIX) as connection:
            connection.settimeout(.1)
            for _ in range(1000):
                if stop.is_set() or not child.is_running(): return
                try:
                    connection.connect(path)
                    break
                except OSError:
                    time.sleep(.001)
            else: return
            connection.sendall(b'{"command":["observe_property",1,"pause"]}\n')
            pending = b''
            while not stop.is_set():
                try: data = connection.recv(8192)
                except socket.timeout: continue
                except OSError: return
                if not data: return
                pending += data
                while b'\n' in pending:
                    line, pending = pending.split(b'\n', 1)
                    try: record(json.loads(line))
                    except ValueError: pass
    parent = psutil.Process(pid)
    while not stop.is_set():
        try: children = parent.children(recursive=True)
        except psutil.NoSuchProcess: return
        for child in children:
            if child.pid in seen: continue
            try:
                if child.name()=='ffprobe':
                    seen.add(child.pid)
                    with lock, Path(output).open('a') as f:
                        f.write(json.dumps(dict(ns=time.monotonic_ns(),pid=child.pid,event='ffprobe-spawn'))+'\n')
                    continue
                args = child.cmdline()
                path = next((x.split('=',1)[1] for x in args if x.startswith('--input-ipc-server=')), None)
                if path and child.name() == 'mpv':
                    seen.add(child.pid)
                    threading.Thread(target=observe,args=(child,path),daemon=True).start()
            except psutil.Error: pass
        stop.wait(.002)
