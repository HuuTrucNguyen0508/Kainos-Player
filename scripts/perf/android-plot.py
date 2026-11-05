#!/usr/bin/env python3
"""Plot committed summaries; bars show observed min/max, dots median, triangles p90."""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(__file__).resolve().parents[2]
data = json.loads((root / 'androidApp/runtime-benchmark-summary.json').read_text())
states = ['debug-none', 'optimized-none', 'optimized-partial']
labels = ['Debug / None', 'Optimized / None', 'Optimized / Partial']
fig, axes = plt.subplots(1, 3, figsize=(12, 3.2), layout='constrained')
for ax, key, title, scale in zip(axes,
    ['timeToInitialDisplayMs', 'frameDurationCpuMs', 'idleRssKiB'],
    ['Cold initial display (ms)', 'Journey frame CPU (ms)', 'Idle RSS (MiB)'], [1, 1, 1024]):
    for row, state in enumerate(states):
        s = data['states'][state][key]
        color = ['#526781', '#178271', '#99672b'][row]
        ax.plot([s['min']/scale, s['max']/scale], [row, row], color=color, lw=3)
        ax.plot(s['median']/scale, row, 'o', color=color)
        ax.plot(s['p90']/scale, row, '>', color=color)
    ax.set_yticks(range(3), labels if ax is axes[0] else ['']*3)
    ax.invert_yaxis()
    ax.set_ylim(2.5, -.5)
    ax.set_title(title, fontsize=11)
    ax.grid(axis='x', alpha=.2)
    ax.spines[['top', 'right', 'left']].set_visible(False)
fig.suptitle('Kainos Player: emulator comparison, 15 iterations per state', fontsize=13)
fig.supxlabel('Range = min/max; circle = median; triangle = p90. Frame values summarize each full journey, not scroll alone.', fontsize=9)
fig.savefig(root / 'androidApp/runtime-benchmark.png', dpi=160)
