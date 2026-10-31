#!/usr/bin/env python3
"""Merge only profiles collected by ProfileGenerator into AGP's source inputs."""
from pathlib import Path
root = Path(__file__).resolve().parents[2]
outputs = root / 'androidMacrobenchmark/build/outputs'
for kind in ('baseline', 'startup'):
    files = sorted(outputs.rglob(f'ProfileGenerator_*-{kind}-prof.txt'))
    if not files:
        raise SystemExit(f'No collected {kind} profile under {outputs}; run android-runtime profile first')
    if kind == 'baseline':
        files += sorted(outputs.rglob('ProfileGenerator_*-startup-prof.txt'))
    rules = sorted({line for f in files for line in f.read_text().splitlines() if line and not line.startswith('#')})
    # Never commit an empty or dependency-only generation as an application profile.
    if not any('Lcom/universalmusic/player/' in line for line in rules):
        raise SystemExit(f'{kind} profile has no Kainos classes')
    dest = root / ('androidApp/src/main/baseline-prof.txt' if kind == 'baseline' else 'androidApp/src/main/baselineProfiles/startup-prof.txt')
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text('\n'.join(rules) + '\n')
    print(f'{dest.relative_to(root)}: {len(rules)} rules from {len(files)} files')
