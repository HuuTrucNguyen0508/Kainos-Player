#!/usr/bin/env python3
"""Keep every final-state trial in the denominator. Latency is reported even when state is wrong."""
import csv, math, statistics, sys
from collections import defaultdict
rows = defaultdict(list)
for path in sys.argv[1:]:
    for row in csv.DictReader(open(path), delimiter='\t'):
        rows[row['arm'], row['scenario']].append(row)
print('arm\tscenario\tn\tcorrect\tmedian_ms\tp90_ms\tmin_ms\tmax_ms\tapplications')
for (arm, scenario), group in sorted(rows.items()):
    if arm == 'head':
        expected = 1 if scenario in ('stale-display', 'reordered-request') else 2
        assert all(r['correct'] == 'true' and int(r['applications']) == expected for r in group), (scenario, 'missing or duplicate application')
    times = sorted(float(r['final_ms']) for r in group)
    correct = sum(r['correct'] == 'true' for r in group)
    print(f'{arm}\t{scenario}\t{len(group)}\t{correct}\t{statistics.median(times):.2f}\t{times[math.ceil(.9*len(times))-1]:.2f}\t{min(times):.2f}\t{max(times):.2f}\t{sum(int(r["applications"]) for r in group)}')
