import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--transport', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
labels = ('Home', 'Search', 'Library', 'Settings')

def adb(*command):
    return subprocess.check_output(['adb', '-t', args.transport, *command])

def snapshot(name):
    adb('shell', 'uiautomator', 'dump', '/sdcard/kainos-tabs.xml')
    xml = adb('exec-out', 'cat', '/sdcard/kainos-tabs.xml')
    (out / (name + '.xml')).write_bytes(xml)
    (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    return ET.fromstring(xml)

def tabs(tree):
    found = {}
    for node in tree.iter('node'):
        text = [child.get('text') for child in node.iter('node') if child.get('text')]
        if node.get('focusable') == 'true' and len(text) == 1 and text[0] in labels:
            found[text[0]] = node
    assert set(found) == set(labels), 'All four bottom tabs must be visible'
    return found

def selected(tree):
    return next(label for label, node in tabs(tree).items() if node.get('selected') == 'true')

tree = snapshot('initial')
checks = []
for index, label in enumerate(('Settings', 'Home', 'Library', 'Settings', 'Settings', 'Home', 'Library', 'Home')):
    bounds = list(map(int, re.findall(r'\d+', tabs(tree)[label].get('bounds'))))
    x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    previous = selected(tree)
    adb('shell', 'input', 'tap', str(x), str(y))
    time.sleep(.8)
    tree = snapshot(f'{index + 1:02d}-{previous}-to-{label}')
    actual = selected(tree)
    passed = actual == label
    result = f'{"PASS" if passed else "FAIL"} {previous} -> {label} at {x},{y}; selected={actual}'
    print(result, flush=True)
    checks.append(result)
    (out / 'results.txt').write_text('\n'.join(checks) + '\n')
    if not passed:
        raise SystemExit(1)
print(f'{len(checks)} navigation checks passed', flush=True)
