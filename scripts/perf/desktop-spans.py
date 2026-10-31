#!/usr/bin/env python3
"""Summarize AppContainer wall-clock spans from desktop-bench.py runs.json.
Library loading overlaps composition; these spans are not additive CPU costs.
"""
import argparse
import json
from pathlib import Path
import statistics

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('bench', type=Path)
a = p.parse_args()
rows = json.loads((a.bench/'runs.json').read_text())
result = {}
spans = [('http', 'container_begin', 'http_end'), ('stores', 'http_end', 'stores_end'),
         ('providers', 'stores_end', 'providers_end'), ('player', 'providers_end', 'player_end'),
         ('mpris', 'mpris_begin', 'mpris_end'), ('library_load', 'library_load_begin', 'library_load_end')]
for variant in sorted({r['variant'] for r in rows}):
    selected = [r for r in rows if r['variant'] == variant and 'error' not in r]
    result[variant] = {}
    for name, start, end in spans:
        values = [r[end+'_ms']-r[start+'_ms'] for r in selected if end+'_ms' in r and start+'_ms' in r]
        if values:
            result[variant][name+'_ms'] = dict(n=len(values), median=statistics.median(values),
                p90=sorted(values)[int(.9*len(values)+.999)-1], min=min(values), max=max(values))
result['errors'] = sum('error' in r for r in rows)
print(json.dumps(result, indent=2))
raise SystemExit(bool(result['errors']))
