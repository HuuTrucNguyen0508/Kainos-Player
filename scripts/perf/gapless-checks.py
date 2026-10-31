#!/usr/bin/env python3
"""Full required checks on a private Xvfb. Gradle's exit code is preserved."""
import os, pathlib, select, subprocess as S
root=pathlib.Path(__file__).resolve().parents[2]
import private_xvfb
x,display=private_xvfb.start(str(pathlib.Path.home()/'.local/share/kainos-player/Xvfb'),stdout=S.DEVNULL)
try:
    os.close(r)
    result=S.run(['flock','-s','/tmp/kainos-perf.lock','./gradlew','jvmTest',
        ':core:data:verifyCommonMainKainosDatabaseMigration',':androidApp:assembleDebug',
        ':desktopApp:createDistributable','--console=plain'],cwd=root,
        env={**os.environ,'JAVA_HOME':str(pathlib.Path.home()/'.jdks/temurin-17'),
             'DISPLAY':display,'WAYLAND_DISPLAY':'','SKIKO_RENDER_API':'SOFTWARE',
             'KAINOS_CDS_XVFB':str(pathlib.Path.home()/'.local/share/kainos-player/Xvfb')})
finally:
    x.terminate(); x.wait()
raise SystemExit(result.returncode)
