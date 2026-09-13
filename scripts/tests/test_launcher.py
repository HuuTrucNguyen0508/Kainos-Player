"""Check the installed launcher's build and failure paths without running Java."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

LAUNCHER = Path(__file__).resolve().parents[1] / 'kainos-player'


class LauncherTest(unittest.TestCase):
    def run_launcher(self, build_succeeds=True, with_jars=True):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / 'scripts').mkdir()
        shutil.copy2(LAUNCHER, root / 'scripts/kainos-player')

        app_dir = root / 'desktopApp/build/compose/binaries/main/app/Kainos Player/lib/app'
        app_dir.mkdir(parents=True)
        if with_jars:
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
            'exit ' + ('0' if build_succeeds else '1') + '\n',
        )
        gradle.chmod(0o755)

        result = subprocess.run(
            ['bash', str(root / 'scripts/kainos-player')],
            cwd=root,
            capture_output=True,
            text=True,
            timeout=5,
            env={**os.environ, 'JAVA_HOME': str(jdk), 'ROOT_MARKER': str(root)},
        )
        return root, result

    def test_existing_package_is_checked_for_updates_before_launch(self):
        root, result = self.run_launcher()
        self.assertEqual(0, result.returncode, msg=result.stderr)
        self.assertTrue((root / 'build-checked').exists(), 'Existing package skipped the build check')
        self.assertTrue((root / 'app-launched').exists())
        self.assertTrue((root / 'logs/desktop-run-latest.log').is_file())

    def test_failed_build_does_not_silently_launch_stale_package(self):
        root, result = self.run_launcher(build_succeeds=False)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((root / 'app-launched').exists())
        self.assertTrue((root / 'logs/desktop-run-latest.log').is_file())
        self.assertIn('log', result.stderr.lower())

    def test_missing_jars_fail_clearly(self):
        root, result = self.run_launcher(with_jars=False)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((root / 'app-launched').exists())
        self.assertIn('no jars', result.stderr.lower())


if __name__ == '__main__':
    unittest.main()
