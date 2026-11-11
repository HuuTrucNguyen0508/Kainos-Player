"""Offline installer checks. All executables and destinations live in a temporary tree."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "scripts").mkdir()
        shutil.copyfile(Path(__file__).resolve().parents[1] / "install-yt-dlp.sh",
                        self.root / "scripts/install-yt-dlp.sh")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.home = self.root / "home"
        self.home.mkdir()
        self.dest = self.root / "dest"
        self.dest.mkdir()
        self.write(self.root / "latest", "#!/bin/sh\necho new\n")
        self.write(self.bin / "curl", '#!/bin/sh\nwhile [ "$1" != "-o" ]; do shift; done\ncp "$LATEST" "$2"\n')
        self.env = dict(os.environ, HOME=str(self.home), LATEST=str(self.root / "latest"),
                        PATH=str(self.bin))
        self.env.pop("KAINOS_YT_DLP", None)
        for command in ["bash", "sh", "dirname", "readlink", "mktemp", "mkdir", "rm", "cp",
                        "chmod", "install", "mv"]:
            shutil.copy2(shutil.which(command), self.bin / command)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        path.chmod(0o755)

    def run_installer(self):
        return subprocess.run(["bash", str(self.root / "scripts/install-yt-dlp.sh"), str(self.dest)],
                              env=self.env, capture_output=True, text=True, timeout=10)

    def test_failed_install_preserves_existing_executable(self):
        target = self.dest / "yt-dlp"
        self.write(target, "#!/bin/sh\necho old\n")
        original = target.read_bytes()
        self.write(self.bin / "install", '#!/bin/bash\nprintf broken > "${@: -1}"\nexit 1\n')
        self.run_installer()
        self.assertEqual(original, target.read_bytes())

    def test_updates_user_owned_path_copy_and_home_copy(self):
        for target in [self.bin / "yt-dlp", self.home / ".local/bin/yt-dlp"]:
            self.write(target, "#!/bin/sh\necho old\n")
        self.assertEqual(0, self.run_installer().returncode)
        for target in [self.bin / "yt-dlp", self.home / ".local/bin/yt-dlp", self.dest / "yt-dlp"]:
            self.assertEqual("new", subprocess.check_output([str(target), "--version"], text=True).strip())

    def test_invalid_download_leaves_working_copy_untouched(self):
        target = self.dest / "yt-dlp"
        self.write(target, "#!/bin/sh\necho old\n")
        original = target.read_bytes()
        self.write(self.root / "latest", "#!/bin/sh\nexit 1\n")
        self.assertNotEqual(0, self.run_installer().returncode)
        self.assertEqual(original, target.read_bytes())


if __name__ == "__main__":
    unittest.main()
