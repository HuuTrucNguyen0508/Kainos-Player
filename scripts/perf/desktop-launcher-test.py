#!/usr/bin/env python3
"""Existing launcher checks plus optional AppCDS argument coverage."""
import os
from pathlib import Path
import runpy
import subprocess
import unittest

root = Path(__file__).resolve().parents[2]
Base = runpy.run_path(str(root/'scripts/tests/test_launcher.py'))['LauncherTest']

class CdsLauncherTest(Base):
    def test_optional_archive_keeps_main_and_arguments(self):
        temporary, result = self.run_launcher()
        self.assertEqual(result.returncode, 0)
        java = temporary/'fake-jdk/bin/java'
        java.write_text('#!/bin/bash\nprintf "%s\\n" "$@" > "$ROOT_MARKER/java-args"\n')
        for present in [False, True]:
            archive = temporary/'desktopApp/build/compose/binaries/main/app/Kainos Player/lib/app/kainos.jsa'
            if present:
                archive.write_bytes(b'not a real archive; this test records arguments only')
                archive.with_suffix('.jsa.size').write_text(str(archive.stat().st_size)+'\n')
            result = subprocess.run(['bash', str(temporary/'scripts/kainos-player'), '--probe-argument'],
                env={**os.environ, 'JAVA_HOME':str(temporary/'fake-jdk'), 'ROOT_MARKER':str(temporary), 'KAINOS_REBUILD':'0'},
                capture_output=True,text=True)
            self.assertEqual(result.returncode,0,msg=result.stderr)
            args=(temporary/'java-args').read_text().splitlines()
            self.assertEqual('-Xshare:auto' in args,present)
            self.assertEqual('-XX:SharedArchiveFile='+str(archive) in args,present)
            self.assertEqual(args[-2:],['com.universalmusic.player.desktop.MainKt','--probe-argument'])

    def test_incomplete_archive_uses_normal_startup(self):
        temporary, result = self.run_launcher()
        java=temporary/'fake-jdk/bin/java'
        java.write_text('#!/bin/bash\nprintf "%s\\n" "$@" > "$ROOT_MARKER/java-args"\n')
        archive=temporary/'desktopApp/build/compose/binaries/main/app/Kainos Player/lib/app/kainos.jsa'
        archive.write_bytes(b'truncated')
        archive.with_suffix('.jsa.size').write_text('1000000\n')
        result=subprocess.run(['bash',str(temporary/'scripts/kainos-player')],
            env={**os.environ,'JAVA_HOME':str(temporary/'fake-jdk'),'ROOT_MARKER':str(temporary),'KAINOS_REBUILD':'0'},capture_output=True,text=True)
        self.assertEqual(result.returncode,0,msg=result.stderr)
        self.assertNotIn('-Xshare:auto',(temporary/'java-args').read_text().splitlines())

del Base

if __name__ == '__main__':
    unittest.main()
