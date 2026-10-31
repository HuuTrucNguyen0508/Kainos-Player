#!/usr/bin/env python3
"""Native loader and guarded database-first-read spans in isolated fresh JVMs.
Run under flock -x /tmp/kainos-perf.lock. OS page cache is not dropped.
"""
import argparse
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--out', type=Path, required=True)
p.add_argument('--runs', type=int, default=10)
p.add_argument('--extract-native', type=Path, help='Extract packaged Linux x86_64 SQLite library and compare it with default loading')
p.add_argument('--variant', action='append', default=[], help='NAME=JSON_JVM_FLAGS')
a = p.parse_args()
if a.runs < 1:
    p.error('--runs must be positive')
root = Path(__file__).resolve().parents[2]
app = root/'desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app'
java = Path.home()/'.jdks/temurin-17/bin/java'
if a.extract_native:
    if a.variant:
        p.error('--extract-native supplies its own baseline/native variants')
    jars = list(app.glob('sqlite-jdbc-*.jar'))
    if len(jars) != 1:
        p.error('expected exactly one packaged sqlite-jdbc jar')
    a.extract_native.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(jars[0]) as jar:
        (a.extract_native/'libsqlitejdbc.so').write_bytes(jar.read('org/sqlite/native/Linux/x86_64/libsqlitejdbc.so'))
    a.variant = ['baseline=[]', 'native='+json.dumps(['-Dorg.sqlite.lib.path='+str(a.extract_native.resolve()),
                                                     '-Dorg.sqlite.lib.name=libsqlitejdbc.so'])]
variants = [(v.split('=', 1)[0], json.loads(v.split('=', 1)[1])) for v in a.variant] or [('baseline', [])]
a.out.mkdir(parents=True, exist_ok=False)
rows = []
for i in range(a.runs):
    for name, flags in variants:
        directory = a.out/f'{i}-{name}'
        directory.mkdir()
        env = dict(os.environ, KAINOS_DATA_DIR=str(directory.resolve()/'data'),
                   KAINOS_INSTANCE=f'storage{os.getpid()}_{i}', KAINOS_HUB_PORT='49989')
        row = dict(run=i, variant=name)
        try:
            result = subprocess.run([str(java), '-Xshare:auto', '-XX:SharedArchiveFile='+str(app/'kainos.jsa'),
                *flags, '-cp', ':'.join(str(f) for f in sorted(app.glob('*.jar'))),
                'com.universalmusic.player.desktop.MainKt', '--perf-storage'],
                env=env, capture_output=True, text=True, timeout=30)
        except subprocess.TimeoutExpired:
            row['error'] = 'storage probe timed out after 30s'
            rows.append(row)
            (a.out/'runs.json').write_text(json.dumps(rows, indent=2))
            continue
        (directory/'run.log').write_text(result.stdout+result.stderr)
        marks = {parts[1]: int(parts[2]) for line in result.stdout.splitlines()
                 if len(parts := line.split('\t')) == 3 and parts[0] == 'PERF'}
        if result.returncode or len(marks) != 6:
            row['error'] = f'exit {result.returncode}; markers {len(marks)}'
        else:
            for span in ['sqlite_initialize', 'database_first_read', 'database_second_read']:
                row[span+'_ms'] = (marks[span+'_end']-marks[span+'_begin'])/1e6
        rows.append(row)
        (a.out/'runs.json').write_text(json.dumps(rows, indent=2))
summary = {name: {key: dict(median=statistics.median(values), p90=sorted(values)[math.ceil(.9*len(values))-1],
                            min=min(values), max=max(values), n=len(values))
    for key in ['sqlite_initialize_ms', 'database_first_read_ms', 'database_second_read_ms']
    if (values := [r[key] for r in rows if r['variant'] == name and key in r])} for name, _ in variants}
summary['errors'] = sum('error' in r for r in rows)
(a.out/'summary.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary, indent=2))
raise SystemExit(bool(summary['errors']))
