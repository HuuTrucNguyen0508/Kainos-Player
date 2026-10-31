"""Check the installed launcher's build and failure paths without running Java."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import time

LAUNCHER = Path(__file__).resolve().parents[1] / 'kainos-player'


class LauncherTest(unittest.TestCase):
    def run_launcher(self, build_succeeds=True, with_jars=True, missing_package=False, args=(), rebuild="0", package="KainosPlayer", archive=None, corrupt=False):

        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / 'scripts').mkdir()
        shutil.copy2(LAUNCHER, root / 'scripts/kainos-player')

        app_dir = root / 'desktopApp/build/compose/binaries/main/app' / package / 'lib/app'
        if not missing_package:
            app_dir.mkdir(parents=True)
        if with_jars and not missing_package:
            (app_dir / 'app.jar').write_bytes(b'')

        if archive is not None:
            (app_dir / 'kainos.jsa').write_bytes(archive)
            (app_dir / 'kainos.jsa.size').write_text(str(len(archive))+'\n')
            (app_dir / 'kainos.jsa.sha256').write_text(hashlib.sha256(archive).hexdigest()+'  kainos.jsa\n')
            if corrupt:
                (app_dir / 'kainos.jsa').write_bytes(bytes(b ^ 1 for b in archive))

        jdk = root / 'fake-jdk'
        (jdk / 'bin').mkdir(parents=True)
        java = jdk / 'bin/java'
        java.write_text('#!/bin/bash\nprintf launched > "$ROOT_MARKER/app-launched"\nprintf "%s\\n" "$@" > "$ROOT_MARKER/java-args"\n')
        java.chmod(0o755)

        gradle = root / 'gradlew'
        gradle.write_text(
            '#!/bin/bash\n'
            'printf checked > build-checked\n'
            'mkdir -p \"desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app\"\n'
            'touch \"desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app/app.jar\"\n'
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

    def test_corrupt_same_size_archive_is_removed_before_next_launch(self):
        root, result = self.run_launcher(archive=b'archive contents', corrupt=True)
        self.assertEqual(0, result.returncode, result.stderr)
        archive = root / 'desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app/kainos.jsa'
        deadline = time.monotonic() + 5
        while archive.exists() and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertFalse(archive.exists())
        result = subprocess.run(['bash', str(root / 'scripts/kainos-player')], capture_output=True,
            env={**os.environ, 'JAVA_HOME': str(root / 'fake-jdk'), 'ROOT_MARKER': str(root)}, timeout=5)
        self.assertEqual(0, result.returncode)
        self.assertNotIn('-Xshare:auto', (root / 'java-args').read_text())

    def test_valid_archive_uses_sharing(self):
        root, result = self.run_launcher(archive=b'archive contents')
        self.assertEqual(0, result.returncode)
        self.assertIn('-Xshare:auto', (root / 'java-args').read_text())

    def test_existing_package_launches_without_build(self):
        root, result = self.run_launcher()
        self.assertEqual(0, result.returncode, msg=result.stderr)
        self.assertFalse((root / 'build-checked').exists())
        self.assertTrue((root / 'app-launched').exists())
        self.assertTrue((root / 'logs/desktop-run-latest.log').is_file())

    def test_legacy_package_fallback_and_rebuild(self):
        for rebuild in ("0", "1"):
            with self.subTest(rebuild=rebuild):
                root, result = self.run_launcher(package="Kainos Player", rebuild=rebuild)
                self.assertEqual(0, result.returncode, result.stderr)
                args = (root / 'java-args').read_text().splitlines()
                selected = "KainosPlayer" if rebuild == "1" else "Kainos Player"
                self.assertEqual(str(root / 'desktopApp/build/compose/binaries/main/app' /
                                     selected / 'lib/app/app.jar'), args[args.index('-cp') + 1])

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
