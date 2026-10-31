#!/usr/bin/env python3
"""Nearest-rank quantiles; failed deliveries stay in the denominator, never in latency quantiles."""
import csv, math, sys
from collections import defaultdict
rows = defaultdict(list)
for row in csv.DictReader(open(sys.argv[1]), delimiter='\t'):
    rows[row['scenario']].append(row)
print('scenario\tn\tapplied\tp50_ms\tp90_ms\tp99_ms\trequest_drops\treply_drops\toutcomes')
for name, group in rows.items():
    times = sorted(float(r['applied_ms']) for r in group if r['applied_ms'] != 'NA')
    def q(p):
        return f'{times[max(0, math.ceil(len(times)*p)-1)]:.2f}' if times else 'NA'
    outcomes = defaultdict(int)
    for r in group:
        outcomes[r['outcome']] += 1
    print(f'{name}\t{len(group)}\t{len(times)}\t{q(.5)}\t{q(.9)}\t{q(.99)}\t{sum(int(r.get('request_drops') or 0) for r in group)}\t{sum(int(r.get('reply_drops') or 0) for r in group)}\t{dict(outcomes)}')
