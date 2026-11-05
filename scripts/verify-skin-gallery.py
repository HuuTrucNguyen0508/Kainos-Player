#!/usr/bin/env python3
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
gallery = root / "shared/build/skin-gallery"
logs = root / "logs"
logs.mkdir(exist_ok=True)
env = os.environ.copy()
env.pop("DISPLAY", None)
env.pop("WAYLAND_DISPLAY", None)
snapshots = []
for run in (1, 2):
    log = logs / f"skin-gallery-verify-{run}.log"
    with log.open("w") as output:
        result = subprocess.run([str(root / "gradlew"), ":shared:skinGallery", "--console=plain"],
                                cwd=root, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit(f"Gallery run {run} failed. Read {log}")
    with (gallery / "manifest.tsv").open() as manifest:
        expected = {row["file"] for row in csv.DictReader(manifest, delimiter="\t")}
    files = {str(path.relative_to(gallery)): path for path in gallery.rglob("*.png")}
    if files.keys() != expected:
        raise SystemExit(f"Gallery files differ from the manifest: {files.keys() ^ expected}")
    snapshots.append({name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in sorted(files.items())})
changed = [name for name in snapshots[0].keys() | snapshots[1].keys() if snapshots[0].get(name) != snapshots[1].get(name)]
report = {"pngCount": len(snapshots[1]), "changed": sorted(changed), "sha256": snapshots[1]}
(logs / "skin-gallery-verify.json").write_text(json.dumps(report, indent=2) + "\n")
if changed:
    raise SystemExit(f"Gallery PNGs changed between runs: {changed}")
print(f"PASS: {len(snapshots[1])} PNGs match the manifest and have identical SHA256 hashes across two headless runs.")
