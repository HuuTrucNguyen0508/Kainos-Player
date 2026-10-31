#!/usr/bin/env python3
"""Opt-in, fixed-seed real engine stress; private mpv null output, no app or user data.
Runs under the exclusive performance lock. Preserves Gradle's exit code.
"""
import os, pathlib, subprocess
root=pathlib.Path(__file__).resolve().parents[2]
subprocess.run(['uptime'],check=True)
result=subprocess.run(['flock','-x','/tmp/kainos-perf.lock','./gradlew',
    ':core:playback:jvmTest','--tests','*GaplessBoundaryStressTest','--rerun-tasks','--console=plain'],
    cwd=root,env={**os.environ,'JAVA_HOME':str(pathlib.Path.home()/'.jdks/temurin-17'),'KAINOS_GAPLESS_STRESS':'1'})
if result.returncode: raise SystemExit(result.returncode)
import xml.etree.ElementTree as ET
for report in (root/'core/playback/build/test-results/jvmTest').glob('TEST-*GaplessBoundaryStressTest.xml'):
    print(ET.parse(report).getroot().findtext('system-out'))
