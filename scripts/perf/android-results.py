#!/usr/bin/env python3
"""Summarize Macrobenchmark JSON, preserving all iterations and errors in originals."""
import json, sys, statistics, math, re
from pathlib import Path

def describe(values):
    values = sorted(values)
    return {'n': len(values), 'median': statistics.median(values),
            'p90': values[math.ceil(.9*len(values))-1], 'min': values[0], 'max': values[-1]}

for source in map(Path, sys.argv[1:]):
    print(source)
    if source.suffix != '.json':
        values = [int(v) for v in re.findall(r'KainosPerf: idle_rss_kib=(\d+)', source.read_text())]
        if not values:
            raise SystemExit('No RSS samples in ' + str(source))
        print('idle_rss_kib', describe(values))
        continue
    data = json.loads(source.read_text())
    for benchmark in data.get('benchmarks', []):
        print(benchmark['name'])
        for key, metric in benchmark.get('metrics', {}).items():
            print(key, describe(metric['runs']))
        for key, metric in benchmark.get('sampledMetrics', {}).items():
            # Frame percentiles are per journey; summarize each iteration's median,
            # not a pooled number dominated by the iteration with most frames.
            runs = metric.get('runs', [])
            if runs:
                print(key, 'per-iteration-median', describe([statistics.median(r) for r in runs]))
