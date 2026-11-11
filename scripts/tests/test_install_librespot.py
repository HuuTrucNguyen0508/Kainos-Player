import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class HelperInstallTest(unittest.TestCase):
    def test_invalid_helper_does_not_replace_the_working_binary(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "scripts").mkdir()
            shutil.copyfile(Path(__file__).parents[1] / "install-librespot.sh", root / "scripts/install-librespot.sh")
            binaries = root / "tools/librespot-runtime/bin"
            binaries.mkdir(parents=True)
            helper = binaries / "kainos-discover-weekly"
            helper.write_text("#!/bin/sh\necho working-radio: >&2\nexit 2\n")
            helper.chmod(0o755)
            original = helper.read_bytes()
            (binaries / "librespot").write_text("#!/bin/sh\nexit 0\n")
            (binaries / "librespot").chmod(0o755)
            built = root / "tools/kainos-spotify-rootlist/target/release/kainos-spotify-rootlist"
            built.parent.mkdir(parents=True)
            built.write_text("#!/bin/sh\necho obsolete >&2\nexit 2\n")
            built.chmod(0o755)
            fake_bin = root / "fake-bin"
            fake_bin.mkdir()
            (fake_bin / "cargo").write_text("#!/bin/sh\nexit 0\n")
            (fake_bin / "cargo").chmod(0o755)
            result = subprocess.run(["bash", str(root / "scripts/install-librespot.sh")],
                                    env={**os.environ, "PATH": str(fake_bin) + ":" + os.environ["PATH"]},
                                    capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(original, helper.read_bytes())


if __name__ == "__main__":
    unittest.main()
