#!/usr/bin/env python3
"""Reuse desktop PCM A/B harness with an optional 44.1 -> 96 kHz natural boundary.
All desktop-bench options pass through. Run under flock -x /tmp/kainos-perf.lock.
"""
from pathlib import Path
import sys
import zipfile
source=Path(__file__).with_name('desktop-bench.py')
code=source.read_text()
if '--app-dir' in sys.argv:
    app=Path(sys.argv[sys.argv.index('--app-dir')+1])
    jar=next(app.glob('desktopApp-jvm-*.jar'))
    with zipfile.ZipFile(jar) as archive:
        probe=archive.read('com/universalmusic/player/desktop/PlaybackProbeKt$runPlaybackProbe$1$engine$1.class')
    assert b'getSupportsGapless' in probe, 'Recompile desktopApp: stale probe bypasses gapless capability'
# Include the persistent mpv's idle cost, rather than counting only the probe JVM.
import psutil
needle="stat=P(f'/proc/{proc.pid}/stat').read_text().split(); idle=(time.monotonic(),int(stat[13])+int(stat[14]))"
assert code.count(needle)==1
code=code.replace(needle,needle+"\n                            mpv_idle={c.pid:sum(c.cpu_times()[:2]) for c in psutil.Process(proc.pid).children() if c.name()=='mpv'}")
needle="row['idle_rss_kib']=int("
assert code.count(needle)==1
code=code.replace(needle,"row['mpv_idle_cpu_percent']=100*sum(max(0,sum(psutil.Process(pid).cpu_times()[:2])-cpu) for pid,cpu in mpv_idle.items() if psutil.pid_exists(pid))/(time.monotonic()-idle[0])\n                            "+needle)
if '--mixed-rate' in sys.argv:
    sys.argv.remove('--mixed-rate')
    needle="if a.fixture_format=='hires-flac' else []"
    assert code.count(needle)==1
    # Natural transition is tone1 -> tone2. Leave the first two files at 44.1 kHz.
    code=code.replace(needle,"if k==2 else []")
exec(compile(code,str(source),'exec'),dict(__name__='__main__',__file__=str(source),psutil=psutil))
