"""Exercise artifact routing without pretending to cross-compile native code."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class BootstrapRoutingTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        repo = Path(__file__).parents[1]
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in ("build-native-bootstrap.sh", "stage-bootstrap-for-app.sh"):
            shutil.copy(repo / "scripts" / name, scripts / name)
        shutil.copytree(repo / "config", self.root / "config")

        # Only orchestration is under test: Docker, cross-compilation and archive
        # validation are test doubles. No fixture is installable or distributed.
        (scripts / "prepare-packages.sh").write_text("#!/bin/bash\nexit 0\n")
        (scripts / "validate-distribution.py").write_text("raise SystemExit(0)\n")
        package_scripts = self.root / "work/termux-packages/scripts"
        package_scripts.mkdir(parents=True)
        builder = package_scripts / "run-docker.sh"
        builder.write_text(
            "#!/bin/bash\nset -eu\n"
            "printf native-candidate > bootstrap-aarch64.zip\n"
            "mkdir -p output\nprintf candidate-package > output/candidate.deb\n")
        builder.chmod(0o755)
        tools = self.root / "test-bin"
        tools.mkdir()
        docker = tools / "docker"
        docker.write_text("#!/bin/bash\nexit 0\n")
        docker.chmod(0o755)
        self.environment = dict(os.environ,
                                PATH=str(tools) + os.pathsep + os.environ["PATH"],
                                NEVERSOFT_WORK_DIR=str(self.root / "work"))
        self.app = self.root / "work/termux-app/neversoft-app"
        self.app.mkdir(parents=True)
        self.legacy = self.root / "artifacts/bootstrap/bootstrap-aarch64.zip"

    def run_script(self, name):
        return subprocess.run(["bash", str(self.root / "scripts" / name)],
                              env=self.environment, capture_output=True, text=True)

    def test_native_candidate_preserves_legacy_archive_and_staging(self):
        self.legacy.parent.mkdir(parents=True)
        self.legacy.write_bytes(b"legacy-compatibility")
        result = self.run_script("build-native-bootstrap.sh")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(b"legacy-compatibility", self.legacy.read_bytes())
        candidate = self.root / "artifacts/native-bootstrap/bootstrap-aarch64.zip"
        self.assertEqual(b"native-candidate", candidate.read_bytes())
        result = self.run_script("stage-bootstrap-for-app.sh")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        staged = self.app / "src/main/res/raw/neversoft_bootstrap.zip"
        self.assertEqual(b"legacy-compatibility", staged.read_bytes())

    def test_native_candidate_cannot_satisfy_missing_legacy_input(self):
        result = self.run_script("build-native-bootstrap.sh")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        result = self.run_script("stage-bootstrap-for-app.sh")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("bootstrap not built", result.stderr)
        self.assertFalse((self.app / "src/main/res/raw/neversoft_bootstrap.zip").exists())


if __name__ == "__main__":
    unittest.main()
