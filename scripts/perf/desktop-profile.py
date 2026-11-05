#!/usr/bin/env python3
"""Summarize measured startup spans and a separate -Xlog:class+load,startuptime,library trace.
Native-load spans are bounds, not exclusive CPU attribution. Trace runs are not timing samples.
"""
import argparse
import json
from pathlib import Path
import re
import statistics

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--bench', type=Path, required=True)
p.add_argument('--trace', type=Path, required=True)
a = p.parse_args()
rows = json.loads((a.bench/'runs.json').read_text())
result = {}
for variant in sorted({r['variant'] for r in rows}):
    selected = [r for r in rows if r['variant'] == variant and 'error' not in r]
    result[variant] = {name: round(statistics.median(r[end]-r.get(start, 0) for r in selected), 2)
        for name, start, end in [('launch_to_wiring_ms', 'launch', 'container_begin_ms'),
                                 ('container_wiring_ms', 'container_begin_ms', 'container_end_ms'),
                                 ('wiring_to_first_draw_ms', 'container_end_ms', 'first_draw_ms')]}
lines = a.trace.read_text().splitlines()
classes = [l for l in lines if 'source: ' in l]
result['trace'] = dict(loaded_classes=len(classes), shared_classes=sum('shared objects file' in l for l in classes),
                       dynamic_shared_classes=sum('shared objects file (top)' in l for l in classes))
def first(pattern):
    return next((round(float(re.match(r'\[([0-9.]+)s\]', l)[1])*1000, 2)
                 for l in lines if pattern in l and re.match(r'\[([0-9.]+)s\]', l)), None)
for name, pattern in [('vm_created_ms', 'Create VM,'), ('cds_dynamic_map_ms', 'Mapped dynamic region #0'),
                      ('skiko_class_ms', 'org.jetbrains.skiko.Library source:'),
                      ('skiko_loaded_ms', 'libskiko-linux-x64.so, handle'),
                      ('sqlite_native_class_ms', 'org.sqlite.core.NativeDB source:'),
                      ('sqlite_native_loaded_ms', '-libsqlitejdbc.so, handle')]:
    result['trace'][name] = first(pattern)
print(json.dumps(result, indent=2))
