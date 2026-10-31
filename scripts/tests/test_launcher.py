"""Check the installed launcher's build and failure paths without running Java."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

LAUNCHER = Path(__file__).resolve().parents[1] / 'kainos-player'


class LauncherTest(unittest.TestCase):
    def run_launcher(self, build_succeeds=True, with_jars=True, missing_package=False, args=(), rebuild="0"):

        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / 'scripts').mkdir()
        shutil.copy2(LAUNCHER, root / 'scripts/kainos-player')

        app_dir = root / 'desktopApp/build/compose/binaries/main/app/Kainos Player/lib/app'
        if not missing_package:
            app_dir.mkdir(parents=True)
        if with_jars and not missing_package:
            (app_dir / 'app.jar').write_bytes(b'')

        jdk = root / 'fake-jdk'
        (jdk / 'bin').mkdir(parents=True)
        java = jdk / 'bin/java'
        java.write_text('#!/bin/bash\nprintf launched > "$ROOT_MARKER/app-launched"\n')
        java.chmod(0o755)

        gradle = root / 'gradlew'
        gradle.write_text(
            '#!/bin/bash\n'
            'printf checked > build-checked\n'
            'mkdir -p \"desktopApp/build/compose/binaries/main/app/Kainos Player/lib/app\"\n'
            'touch \"desktopApp/build/compose/binaries/main/app/Kainos Player/lib/app/app.jar\"\n'
            'exit ' + ('0' if build_succeeds else '1') + '\n',
        )
        gradle.chmod(0o755)

        result = subprocess.run(
            ['bash', str(root / 'scripts/kainos-player'), *args],
            cwd=root,
            capture_output=True,
            text=True,
            timeout=5,
            env={**os.environ, 'JAVA_HOME': str(jdk), 'ROOT_MARKER': str(root), 'KAINOS_REBUILD': rebuild},
        )
        return root, result

    def test_existing_package_launches_without_build(self):
        root, result = self.run_launcher()
        self.assertEqual(0, result.returncode, msg=result.stderr)
        self.assertFalse((root / 'build-checked').exists())
        self.assertTrue((root / 'app-launched').exists())
        self.assertTrue((root / 'logs/desktop-run-latest.log').is_file())

    def test_failed_build_does_not_silently_launch_stale_package(self):
        root, result = self.run_launcher(build_succeeds=False, args=("--rebuild",))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((root / 'app-launched').exists())
        self.assertTrue((root / 'logs/desktop-run-latest.log').is_file())
        self.assertIn('log', result.stderr.lower())

    def test_explicit_and_missing_package_build_modes(self):
        for options in ({"args": ("--rebuild",)}, {"rebuild": "1"}, {"missing_package": True}):
            with self.subTest(options=options):
                root, result = self.run_launcher(**options)
                self.assertEqual(0, result.returncode, msg=result.stderr)
                self.assertTrue((root / 'build-checked').exists())
                self.assertTrue((root / 'app-launched').exists())

    def test_failed_environment_rebuild_stops(self):
        root, result = self.run_launcher(build_succeeds=False, rebuild="1")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((root / 'app-launched').exists())

    def test_missing_jars_fail_clearly(self):
        root, result = self.run_launcher(with_jars=False)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((root / 'app-launched').exists())
        self.assertIn('no jars', result.stderr.lower())


if __name__ == '__main__':
    unittest.main()
