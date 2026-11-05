#!/usr/bin/env python3
"""Real-button proof on profiles seeded by RemoteDesktopFixtureTest. Run under exclusive perf lock."""
import json, os, pathlib, subprocess, sys, time
root = pathlib.Path(__file__).resolve().parents[2]
profiles = pathlib.Path(sys.argv[1]).resolve()
java = pathlib.Path.home()/'.jdks/temurin-17/bin/java'
helper = root/'scripts/perf/remote-desktop-input.java'
launcher = subprocess.Popen([sys.executable, str(root/'scripts/perf/remote-desktops.py'), str(profiles), sys.argv[2]], stdin=subprocess.PIPE)
results = {}

def input_for(name, *args):
    display = (profiles/f'{name}.display').read_text().strip()
    env = os.environ.copy()
    env.update(DISPLAY=display, KAINOS_REMOTE_PRIVATE_DISPLAY=display, XDG_SESSION_TYPE='x11', WAYLAND_DISPLAY='', JAVA_TOOL_OPTIONS='')
    if args[0] == 'capture':
        subprocess.run(['import', '-display', display, '-window', 'root', str(args[1])], env=env, check=True, timeout=10)
    else:
        subprocess.run([str(java), '-Dawt.toolkit.name=XToolkit', str(helper), *map(str, args)], env=env, check=True, stdout=subprocess.DEVNULL, timeout=10)

def status(name):
    return subprocess.check_output(['playerctl', '-p', f'kainosplayer.instanceremoteproof{name}', 'status'], text=True).strip()

def expect(name, wanted):
    for _ in range(30):
        actual = status(name)
        if actual == wanted: return
        time.sleep(.1)
    raise AssertionError((name, wanted, actual))

try:
    for _ in range(100):
        if all((profiles/f'{name}.display').exists() for name in ['pc','phone']): break
        time.sleep(.1)
    time.sleep(10)
    for name in ['pc', 'phone']:
        input_for(name, 'space')
        expect(name, 'Playing')
    time.sleep(2)
    for name in ['pc', 'phone']:
        input_for(name, 'click', 1080, 136)
        input_for(name, 'click', 1080, 236)
        input_for(name, 'capture', profiles/f'{name}-remote-before.png')
    # Coordinates are from the retained 1280x800 reference proof. Verify captures before claiming a pass.
    for sender, target in [('pc','phone'), ('phone','pc')]:
        input_for(sender, 'click', 1138, 663)
        expect(target, 'Paused'); expect(sender, 'Playing')
        input_for(sender, 'capture', profiles/f'{sender}-paused-target.png')
        input_for(sender, 'click', 1138, 663)
        expect(target, 'Playing')
        time.sleep(.5)
        input_for(sender, 'click', 1138, 663, 2, 100)
        time.sleep(.5)
        expect(target, 'Playing')
        results[f'{sender}_to_{target}'] = dict(pause=True, resume=True, double_tap_100ms=True, sender_unaffected=status(sender))
    (profiles/'button-proof.json').write_text(json.dumps(results, indent=2)+'\n')
    print(json.dumps(results))
finally:
    launcher.communicate(input=b'\n', timeout=30)
