import importlib.util
import io
import os
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("distribution", Path(__file__).parents[1] / "scripts/validate-distribution.py")
distribution = importlib.util.module_from_spec(spec)
spec.loader.exec_module(distribution)


class DistributionTests(unittest.TestCase):
    def archive(self, replacements=None):
        elf = bytearray(64)
        elf[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", elf, 18, 183)
        members = {name: bytes(elf) for name in ("bin/bash", "bin/apt", "bin/dpkg")}
        members["SYMLINKS.txt"] = b"bash\xe2\x86\x90bin/sh\n"
        members.update(replacements or {})
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name) / "bootstrap.zip"
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in members.items():
                archive.writestr(name, data)
        return path

    def test_native_archive(self):
        self.assertEqual([], distribution.validate_bootstrap(self.archive()))

    def test_reject_stock_elf_path(self):
        errors = distribution.validate_bootstrap(self.archive({"lib/test.so": b"\x00/data/data/com.termux/files/usr\x00"}))
        self.assertTrue(any("stock Termux" in e for e in errors))

    def test_reject_user_alias(self):
        self.assertTrue(distribution.validate_bootstrap(self.archive({"etc/path": b"/data/user/0/com.termux/files/usr"})))

    def test_reject_wrong_arch(self):
        self.assertTrue(distribution.validate_bootstrap(self.archive({"bin/bash": b"\x7fELF" + bytes(60)})))

    def test_reject_non_executable(self):
        self.assertTrue(distribution.validate_bootstrap(self.archive({"bin/bash": b"not a binary"})))

    def test_reject_traversal(self):
        with self.assertRaises(ValueError):
            distribution.validate_bootstrap(self.archive({"../outside": b"bad"}))

    def test_reject_absolute_path(self):
        with self.assertRaises(ValueError):
            distribution.validate_bootstrap(self.archive({"/outside": b"bad"}))

    def test_reject_symlink_escape(self):
        self.assertTrue(distribution.validate_bootstrap(self.archive({"SYMLINKS.txt": b"../../outside\xe2\x86\x90bin/sh\n"})))

    def test_reject_symlink_collision(self):
        self.assertTrue(distribution.validate_bootstrap(self.archive({"SYMLINKS.txt": b"apt\xe2\x86\x90bin/bash\n"})))

    def test_scans_across_chunk_boundary(self):
        errors = []
        distribution.inspect_stream("test", io.BytesIO(b"x" * 65530 + b"/data/data/com.termux/files/usr"), errors)
        self.assertTrue(errors)

    def test_missing_binary(self):
        path = self.archive()
        with zipfile.ZipFile(path) as original:
            members = {name: original.read(name) for name in original.namelist() if name != "bin/dpkg"}
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in members.items():
                archive.writestr(name, data)
        self.assertTrue(distribution.validate_bootstrap(path))


@unittest.skipUnless(shutil.which("dpkg-deb"), "dpkg-deb required")
class PackageTests(unittest.TestCase):
    def package(self, payload=None, control_script=None, arch="aarch64"):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name) / "pkg"
        control = root / "DEBIAN"
        control.mkdir(parents=True)
        (control / "control").write_text(
            f"Package: neversoft-test\nVersion: 1.0\nArchitecture: {arch}\n"
            "Maintainer: Test <test@example.invalid>\nDescription: Archive test fixture\n")
        binary = root / distribution.PREFIX / "bin/test-command"
        binary.parent.mkdir(parents=True)
        binary.write_bytes(payload or b"#!/data/data/com.neversoft.shell/files/usr/bin/bash\nexit 0\n")
        binary.chmod(0o755)
        if control_script:
            (control / "postinst").write_bytes(control_script)
            (control / "postinst").chmod(0o755)
        result = Path(temp.name) / "package.deb"
        subprocess.run(["dpkg-deb", "--build", str(root), str(result)], check=True, capture_output=True)
        return result

    def test_real_deb_archive_passes(self):
        self.assertEqual([], distribution.validate_deb(self.package()))

    def test_control_script_contamination_fails(self):
        errors = distribution.validate_deb(self.package(control_script=b"#!/data/data/com.termux/files/usr/bin/sh\n"))
        self.assertTrue(any("stock Termux" in e for e in errors))

    def test_payload_contamination_fails(self):
        self.assertTrue(distribution.validate_deb(self.package(payload=b"/data/user/0/com.termux/files/usr")))

    def test_wrong_package_arch_fails(self):
        self.assertTrue(distribution.validate_deb(self.package(arch="amd64")))

    def test_release_paths_and_hashes(self):
        # Execute the real repository generator in an isolated miniature checkout.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            repo = Path(__file__).parents[1]
            shutil.copytree(repo / "scripts", root / "scripts")
            shutil.copytree(repo / "config", root / "config")
            output = root / "work/termux-packages/output"
            output.mkdir(parents=True)
            shutil.copy(self.package(), output / "test.deb")
            environment = dict(os.environ, NEVERSOFT_WORK_DIR=str(root / "work"))
            result = subprocess.run(["bash", str(root / "scripts/make-apt-repo.sh")],
                                    env=environment, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            release = root / "artifacts/publish/apt/termux-main/dists/stable/Release"
            for line in release.read_text().split("SHA256:\n", 1)[1].splitlines():
                digest, size, relative = line.split()
                index = release.parent / relative
                self.assertTrue(index.is_file(), relative)
                self.assertEqual(int(size), index.stat().st_size)
                self.assertEqual(digest, distribution.hashlib.sha256(index.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
