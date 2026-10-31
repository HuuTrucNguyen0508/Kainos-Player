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
    # A server that loses a race for its display exits at once, so wait for our own socket
    # and confirm our process survived before trusting it.
    first = 90
    for _ in range(8):
        number = free_display(first)
        server = subprocess.Popen([binary, f':{number}', '-screen', '0', size, '-nolisten', 'tcp'], **popen)
        socket = pathlib.Path(f'/tmp/.X11-unix/X{number}')
        deadline = time.monotonic() + 10
        while not socket.exists() and server.poll() is None and time.monotonic() < deadline:
            time.sleep(0.02)
        time.sleep(0.15)
        if server.poll() is None and socket.exists():
            return server, f':{number}'
        server.terminate()
        server.wait(timeout=5)
        first = number + 1
    raise RuntimeError('Xvfb did not start on a private display')
