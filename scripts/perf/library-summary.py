#!/usr/bin/env python3
import sys, statistics, math
from collections import defaultdict
rows = defaultdict(list)
for path in sys.argv[1:]:
    for line in open(path):
        if 'LIBRARY ' not in line: continue
        parts = line.split('LIBRARY ', 1)[1].strip().split('\t')
        if len(parts) != 5 or parts[0] == 'metric': continue
        metric, size, run, ms, count = parts
        rows[(path, size, 'query' if metric.startswith('query:') else metric)].append(float(ms))
print('file\tsize\tmetric\tn\tp50_ms\tp90_ms\tp99_ms\tmin_ms\tmax_ms')
for key, values in rows.items():
    values.sort()
    q = lambda p: values[math.ceil(len(values)*p)-1]
    print('\t'.join(key), len(values), f'{statistics.median(values):.3f}', f'{q(.9):.3f}', f'{q(.99):.3f}', f'{min(values):.3f}', f'{max(values):.3f}', sep='\t')
