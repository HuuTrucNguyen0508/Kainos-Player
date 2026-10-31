#!/usr/bin/env python3
"""Full required checks on a private Xvfb. Gradle's exit code is preserved."""
import os, pathlib, select, subprocess as S
root=pathlib.Path(__file__).resolve().parents[2]
r,w=os.pipe()
x=S.Popen(['/tmp/kainos-desktop-tools/usr/bin/Xvfb','-displayfd',str(w),'-screen','0','1280x800x24','-nolisten','tcp'],pass_fds=[w],stdout=S.DEVNULL)
os.close(w)
try:
    assert select.select([r],[],[],10)[0], 'Xvfb timeout'
    display=':'+os.read(r,32).decode().strip()
    os.close(r)
    result=S.run(['flock','-s','/tmp/kainos-perf.lock','./gradlew','jvmTest',
        ':core:data:verifyCommonMainKainosDatabaseMigration',':androidApp:assembleDebug',
        ':desktopApp:createDistributable','--console=plain'],cwd=root,
        env={**os.environ,'JAVA_HOME':str(pathlib.Path.home()/'.jdks/temurin-17'),
             'DISPLAY':display,'WAYLAND_DISPLAY':'','SKIKO_RENDER_API':'SOFTWARE',
             'KAINOS_CDS_XVFB':'/tmp/kainos-desktop-tools/usr/bin/Xvfb'})
finally:
    x.terminate(); x.wait()
raise SystemExit(result.returncode)
