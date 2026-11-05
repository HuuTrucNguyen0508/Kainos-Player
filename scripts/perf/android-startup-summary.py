#!/usr/bin/env python3
"""Summarize >=15 saved am start -W outputs from an emulator test runner.

No device control. Preserve compile-state evidence separately. Does not discard
outliers, errors or missing runs. Each file must represent one force-stopped start.
"""
import argparse
import json
from pathlib import Path
import re
import statistics

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('samples', type=Path, nargs='+')
p.add_argument('--compile-state', required=True)
p.add_argument('--label', required=True)
a = p.parse_args()
if len(a.samples) < 15:
    p.error('at least 15 samples are required')
rows = []
for sample in a.samples:
    raw = sample.read_text()
    match = re.search(r'^TotalTime: (\d+)\s*$', raw, re.M)
    if 'Status: ok' not in raw or 'Error' in raw or not match:
        p.error(f'{sample}: failed or missing start; no samples discarded')
    rows.append(dict(file=str(sample), total_ms=int(match[1])))
values = sorted(row['total_ms'] for row in rows)
print(json.dumps(dict(label=a.label, compile_state=a.compile_state, samples=rows,
                      n=len(values), median_ms=statistics.median(values),
                      p90_ms=values[__import__('math').ceil(.9 * len(values))-1],
                      min_ms=min(values), max_ms=max(values), errors=0), indent=2))
