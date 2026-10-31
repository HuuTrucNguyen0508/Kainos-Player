"""Start Xvfb on an explicit private display.

Never use -displayfd here. It claims the lowest display, including the user's
live :0 under Hyprland, and removes /tmp/.X11-unix/X0 on exit, after which no
X11 application can start.
"""
import pathlib
import subprocess
import time


def free_display(first=90):
    for number in range(first, 500):
        if not pathlib.Path(f'/tmp/.X{number}-lock').exists() and not pathlib.Path(f'/tmp/.X11-unix/X{number}').exists():
            return number
    raise RuntimeError('No free private X display')


def start(binary, size='1280x800x24', **popen):
    number = free_display()
    server = subprocess.Popen([binary, f':{number}', '-screen', '0', size, '-nolisten', 'tcp'], **popen)
    socket = pathlib.Path(f'/tmp/.X11-unix/X{number}')
    deadline = time.monotonic() + 10
    while not socket.exists():
        if server.poll() is not None or time.monotonic() > deadline:
            server.terminate()
            raise RuntimeError(f'Xvfb did not start on :{number}')
        time.sleep(0.02)
    return server, f':{number}'
