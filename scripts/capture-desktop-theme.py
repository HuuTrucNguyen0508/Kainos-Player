#!/usr/bin/env python3
import argparse
from collections import Counter
import json
from pathlib import Path
import subprocess
import time
from PIL import Image

parser = argparse.ArgumentParser(description="Capture one Kainos window and check visible scheme colours.")
parser.add_argument("--pid", type=int, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--scheme", type=Path, default=Path.home() / ".local/state/caelestia/scheme.json")
args = parser.parse_args()

def hypr(*command):
    return subprocess.check_output(["hyprctl", *command], timeout=5, text=True)

window = next(w for w in json.loads(hypr("-j", "clients")) if w["pid"] == args.pid and w["title"] == "Kainos Player")
monitor = next(m for m in json.loads(hypr("-j", "monitors")) if m["id"] == window["monitor"])
active = json.loads(hypr("-j", "activewindow"))
opened_special = window["workspace"]["name"].startswith("special:") and monitor["specialWorkspace"]["id"] != window["workspace"]["id"]
args.output.parent.mkdir(parents=True, exist_ok=True)
try:
    hypr("dispatch", "hl.dsp.focus({ monitor = " + json.dumps(monitor["name"]) + " })")
    if opened_special:
        hypr("dispatch", "hl.dsp.focus({ workspace = " + json.dumps(window["workspace"]["name"]) + " })")
    hypr("dispatch", "hl.dsp.focus({ window = " + json.dumps("address:" + window["address"]) + " })")
    time.sleep(.7)
    window = next(w for w in json.loads(hypr("-j", "clients")) if w["pid"] == args.pid and w["title"] == "Kainos Player")
    x, y = window["at"]
    width, height = window["size"]
    subprocess.run(["grim", "-g", f"{x},{y} {width}x{height}", str(args.output)], check=True, timeout=8)
finally:
    if opened_special:
        hypr("dispatch", "hl.dsp.workspace.toggle_special(" + json.dumps(window["workspace"]["name"].removeprefix("special:")) + ")")
    if active.get("address"):
        hypr("dispatch", "hl.dsp.focus({ window = " + json.dumps("address:" + active["address"]) + " })")

colors = Counter(Image.open(args.output).convert("RGB").get_flattened_data())
scheme = json.loads(args.scheme.read_text())
counts = {role: colors[tuple(bytes.fromhex(scheme["colours"][role]))] for role in ["background", "surfaceContainer", "surfaceContainerHighest"]}
evidence = {"pid": args.pid, "window": window["address"], "scheme": str(args.scheme), "mode": scheme["mode"],
            "screenshot": str(args.output), "exactRolePixelCounts": counts}
args.output.with_suffix(".json").write_text(json.dumps(evidence, indent=2) + "\n")
print(json.dumps(evidence))
assert counts["background"] > 500, "The window does not show the expected scheme background"
assert counts["surfaceContainer"] > 100, "The window does not show the expected scheme surface container"
