#!/usr/bin/env python3
"""Inspect untrusted archives without extracting them. Never proves device execution."""
import argparse
import hashlib
import json
import posixpath
import struct
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path, PurePosixPath

PREFIX = "data/data/com.neversoft.shell/files/usr"
STOCK = (b"/data/data/com.termux", b"/data/user/0/com.termux",
         b"/data/user_de/0/com.termux")
REQUIRED = {"bin/bash", "bin/apt", "bin/dpkg", "SYMLINKS.txt"}


def safe_name(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name or "\x00" in name:
        raise ValueError("unsafe archive path: " + name)
    return str(path)


def inspect_bytes(name, data, errors):
    if any(old in data for old in STOCK):
        errors.append(f"{name}: stock Termux runtime path")
    if data.startswith(b"\x7fELF"):
        if len(data) < 20 or data[4:6] != b"\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
            errors.append(f"{name}: expected little-endian ELF64 AArch64")


def inspect_stream(name, stream, errors):
    head = stream.read(65536)
    inspect_bytes(name, head, errors)
    tail = head[-128:]
    while True:
        chunk = stream.read(65536)
        if not chunk:
            break
        if any(old in tail + chunk for old in STOCK):
            errors.append(f"{name}: stock Termux runtime path")
            break
        tail = chunk[-128:]


def validate_links(entries, links, errors):
    """Resolve archive links in prefix-relative space without touching the host.

    Keep '..' components until after expanding intermediate links: normalizing
    a target first can hide an escape through a directory alias.
    """
    targets = {}
    for link, (target, hardlink) in links.items():
        if link in ("", ".") or not target or "\x00" in target:
            errors.append(f"{link}: invalid link")
            continue
        if target.startswith("/") or hardlink:
            rooted = target.lstrip("/")
            while rooted.startswith("./"):
                rooted = rooted[2:]
            if rooted == PREFIX:
                rooted = ""
            elif rooted.startswith(PREFIX + "/"):
                rooted = rooted[len(PREFIX) + 1:]
            else:
                errors.append(f"{link}: link escapes prefix")
                continue
        else:
            rooted = posixpath.join(posixpath.dirname(link), target)
        targets[link] = rooted.split("/")

    for link, parts in targets.items():
        pending, resolved, followed = list(parts), [], 0
        while pending:
            part = pending.pop(0)
            if part in ("", "."):
                continue
            if part == "..":
                if not resolved:
                    errors.append(f"{link}: link escapes prefix")
                    break
                resolved.pop()
                continue
            resolved.append(part)
            alias = "/".join(resolved)
            if alias in targets:
                followed += 1
                if followed > 40:
                    errors.append(f"{link}: cyclic or excessively nested link")
                    break
                pending = targets[alias] + pending
                resolved = []
        if any(name.startswith(link + "/") for name in entries | links.keys()):
            errors.append(f"{link}: link overlaps extracted directory")


def validate_bootstrap(path):
    errors, seen, symlinks = [], set(), {}
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            name = safe_name(entry.filename)
            if name in seen:
                errors.append(f"{name}: duplicate ZIP entry")
            seen.add(name)
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                errors.append(f"{name}: ZIP symlinks must use SYMLINKS.txt")
            if entry.is_dir():
                continue
            with archive.open(entry) as stream:
                inspect_stream(name, stream, errors)
            if name == "SYMLINKS.txt":
                for line in archive.read(entry).decode("utf-8").splitlines():
                    if "←" not in line:
                        errors.append("malformed SYMLINKS.txt entry")
                        continue
                    target, link = line.split("←", 1)
                    link = safe_name(link)
                    if link in symlinks:
                        errors.append(f"{link}: colliding symlink")
                    symlinks[link] = (target, False)
        errors.extend(f"{name}: required bootstrap member missing" for name in sorted(REQUIRED - seen))
        for link in symlinks:
            if link in seen:
                errors.append(f"{link}: colliding symlink")
        validate_links(seen, symlinks, errors)
        for executable in ("bin/bash", "bin/apt", "bin/dpkg"):
            if executable in seen:
                with archive.open(executable) as stream:
                    if not stream.read(4) == b"\x7fELF":
                        errors.append(f"{executable}: required native executable is not ELF")
    return sorted(set(errors))


def validate_deb(path):
    errors = []
    arch = subprocess.check_output(["dpkg-deb", "-f", str(path), "Architecture"], text=True).strip()
    if arch not in ("aarch64", "all"):
        errors.append(f"unsupported package architecture: {arch}")
    for option in ("--ctrl-tarfile", "--fsys-tarfile"):
        seen, links = set(), {}
        with subprocess.Popen(["dpkg-deb", option, str(path)], stdout=subprocess.PIPE) as process:
            with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
                for member in archive:
                    name = safe_name(member.name)
                    inspect_bytes(name, (name + "\n" + member.linkname).encode(), errors)
                    if option == "--fsys-tarfile" and not member.isdir():
                        if not name.startswith(PREFIX + "/"):
                            errors.append(f"{name}: payload outside NeverSoft prefix")
                    relative = name[len(PREFIX) + 1:] if name.startswith(PREFIX + "/") else None
                    if option == "--fsys-tarfile" and relative is not None:
                        if relative in seen:
                            errors.append(f"{name}: duplicate archive entry")
                        seen.add(relative)
                    if member.issym() or member.islnk():
                        if option == "--fsys-tarfile" and relative is not None:
                            links[relative] = (member.linkname, member.islnk())
                    elif member.isfile():
                        inspect_stream(name, archive.extractfile(member), errors)
                    elif not member.isdir():
                        errors.append(f"{name}: unsupported archive member type")
            if process.wait() != 0:
                errors.append(f"dpkg-deb failed: {option}")
        validate_links(seen, links, errors)
    return sorted(set(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("bootstrap", "deb"))
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    reports = []
    for path in args.paths:
        try:
            errors = (validate_bootstrap if args.kind == "bootstrap" else validate_deb)(path)
        except (ValueError, OSError, zipfile.BadZipFile, tarfile.TarError, subprocess.SubprocessError) as error:
            errors = [str(error)]
        digest = None
        if path.is_file():
            with path.open("rb") as source:
                digest = hashlib.file_digest(source, "sha256").hexdigest()
        reports.append({"file": path.name, "sha256": digest,
                        "passed": not errors, "errors": errors, "runtime_verified": False})
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(reports, indent=2) + "\n")
        for report in reports:
            print(f"{report['file']}: {'PASS' if report['passed'] else 'FAIL'}; {len(report['errors'])} findings; runtime NOT verified")
    else:
        print(json.dumps(reports, indent=2))
    return 0 if all(report["passed"] for report in reports) else 1


if __name__ == "__main__":
    sys.exit(main())
