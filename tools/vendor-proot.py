#!/usr/bin/env python3
"""Vendor proot and its libraries into the APK's jniLibs directory.

Myarch needs to execute proot, and Android only permits executing files from an
app's *native library* directory -- never from its data directory. Anything we
want to run therefore has to be inside the APK, named `lib*.so`, before the APK
is built. That is what this script prepares.

The binaries come from Termux's package repository rather than being compiled
here: Termux builds proot for every Android ABI, patches it for Android's
kernel restrictions, and publishes both the packages and their build scripts.
Rebuilding that from scratch would mean reproducing their patch set.

proot is GPL-2.0-or-later; see docs/THIRD_PARTY.md for the compliance notes and
links to the corresponding sources.

Two ELF edits are made, both of which only shorten an existing string in place:

  * proot's `DT_NEEDED` for `libtalloc.so.2` becomes `libtalloc.so`, because the
    APK packager only extracts files matching `lib*.so` -- a file called
    `libtalloc.so.2` would never reach the device.
  * libtalloc's own `DT_SONAME` is shortened to match.

Usage:
    tools/vendor-proot.py                      # all ABIs
    tools/vendor-proot.py --abi arm64-v8a      # just one
    tools/vendor-proot.py --check              # verify what is already there
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path

REPO = "https://packages.termux.dev/apt/termux-main"
TERMUX_PREFIX = "data/data/com.termux/files/usr"

# Android ABI -> Termux architecture name.
ABIS = {
    "arm64-v8a": "aarch64",
    "armeabi-v7a": "arm",
    "x86_64": "x86_64",
}

# Termux package -> the files we take from it, as
# (path inside the package, name inside jniLibs).
WANTED = {
    "proot": [
        (f"{TERMUX_PREFIX}/bin/proot", "libproot.so"),
        (f"{TERMUX_PREFIX}/libexec/proot/loader", "libproot-loader.so"),
        (f"{TERMUX_PREFIX}/libexec/proot/loader32", "libproot-loader32.so"),
    ],
    "libtalloc": [
        (f"{TERMUX_PREFIX}/lib/libtalloc.so.2", "libtalloc.so"),
    ],
    "libandroid-shmem": [
        (f"{TERMUX_PREFIX}/lib/libandroid-shmem.so", "libandroid-shmem.so"),
    ],
}


@dataclass
class PackageRef:
    name: str
    version: str
    filename: str


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def read_package_index(arch: str) -> dict[str, PackageRef]:
    """Parses the repository index so versions are not hard-coded."""
    import gzip

    raw = gzip.decompress(fetch(f"{REPO}/dists/stable/main/binary-{arch}/Packages.gz"))
    index: dict[str, PackageRef] = {}
    name = version = filename = None
    for line in raw.decode("utf-8", "replace").splitlines():
        if not line.strip():
            if name and version and filename:
                index[name] = PackageRef(name, version, filename)
            name = version = filename = None
            continue
        if line.startswith("Package: "):
            name = line[len("Package: "):].strip()
        elif line.startswith("Version: "):
            version = line[len("Version: "):].strip()
        elif line.startswith("Filename: "):
            filename = line[len("Filename: "):].strip()
    if name and version and filename:
        index[name] = PackageRef(name, version, filename)
    return index


def extract_deb(deb: bytes, wanted: list[tuple[str, str]], destination: Path) -> list[tuple[str, str]]:
    """Pulls the wanted members out of a .deb's data archive."""
    written: list[tuple[str, str]] = []
    with tempfile.TemporaryDirectory() as scratch:
        scratch_path = Path(scratch)
        (scratch_path / "pkg.deb").write_bytes(deb)
        # `ar` is in binutils, which every CI image and build host has; parsing
        # the archive by hand would add nothing.
        subprocess.run(
            ["ar", "x", "pkg.deb"],
            cwd=scratch_path,
            check=True,
            stdout=subprocess.DEVNULL,
        )
        data_archive = next(
            (p for p in scratch_path.glob("data.tar*") if p.is_file()),
            None,
        )
        if data_archive is None:
            raise SystemExit("the package has no data.tar member")
        mode = "r:xz" if data_archive.suffix == ".xz" else "r:*"
        with tarfile.open(data_archive, mode) as archive:
            members = {m.name.lstrip("./"): m for m in archive.getmembers()}
            for source, target in wanted:
                member = members.get(source)
                if member is None:
                    print(f"  ! {source} is not in the package", file=sys.stderr)
                    continue
                extracted = archive.extractfile(member)
                if extracted is None:
                    continue
                out = destination / target
                out.write_bytes(extracted.read())
                out.chmod(0o755)
                written.append((source, target))
    return written


class Elf:
    """Just enough ELF to rewrite strings in `.dynstr` and read `DT_NEEDED`."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.data = bytearray(path.read_bytes())
        if self.data[:4] != b"\x7fELF":
            raise ValueError(f"{path} is not an ELF file")
        self.is64 = self.data[4] == 2
        self.little = self.data[5] == 1
        self._read_sections()

    def _unpack(self, fmt: str, offset: int):
        prefix = "<" if self.little else ">"
        size = struct.calcsize(prefix + fmt)
        return struct.unpack_from(prefix + fmt, self.data, offset), size

    def _read_sections(self) -> None:
        if self.is64:
            (shoff,), _ = self._unpack("Q", 0x28)
            (shentsize, shnum, shstrndx), _ = self._unpack("HHH", 0x3A)
            entry_fmt = "IIQQQQIIQQ"
        else:
            (shoff,), _ = self._unpack("I", 0x20)
            (shentsize, shnum, shstrndx), _ = self._unpack("HHH", 0x2E)
            entry_fmt = "IIIIIIIIII"
        self.sections: dict[str, tuple[int, int]] = {}
        raw = []
        for index in range(shnum):
            fields, _ = self._unpack(entry_fmt, shoff + index * shentsize)
            raw.append(fields)
        name_table_offset = raw[shstrndx][4]
        for fields in raw:
            name_offset = fields[0]
            end = self.data.index(b"\x00", name_table_offset + name_offset)
            name = self.data[name_table_offset + name_offset:end].decode()
            self.sections[name] = (fields[4], fields[5])  # offset, size

    def dynstr(self) -> tuple[int, int]:
        if ".dynstr" not in self.sections:
            raise ValueError(f"{self.path} has no .dynstr")
        return self.sections[".dynstr"]

    def strings(self) -> list[str]:
        offset, size = self.dynstr()
        return [s.decode("utf-8", "replace") for s in bytes(self.data[offset:offset + size]).split(b"\x00") if s]

    def shorten_string(self, old: str, new: str) -> bool:
        """Replaces [old] with the shorter [new], in place, NUL terminated."""
        if len(new) > len(old):
            raise ValueError("a replacement string may only be shorter")
        offset, size = self.dynstr()
        needle = old.encode() + b"\x00"
        start = bytes(self.data[offset:offset + size]).find(needle)
        if start < 0:
            return False
        absolute = offset + start
        replacement = new.encode() + b"\x00" * (len(needle) - len(new))
        self.data[absolute:absolute + len(needle)] = replacement
        return True

    def save(self) -> None:
        self.path.write_bytes(bytes(self.data))


def vendor(abi: str, arch: str, jni_root: Path) -> dict:
    destination = jni_root / abi
    destination.mkdir(parents=True, exist_ok=True)
    print(f"[{abi}] reading the package index")
    index = read_package_index(arch)

    record: dict = {"abi": abi, "arch": arch, "packages": {}, "files": {}}
    for package, wanted in WANTED.items():
        ref = index.get(package)
        if ref is None:
            raise SystemExit(f"{package} is not in the {arch} index")
        print(f"[{abi}] {package} {ref.version}")
        deb = fetch(f"{REPO}/{ref.filename}")
        record["packages"][package] = {
            "version": ref.version,
            "sha256": hashlib.sha256(deb).hexdigest(),
            "url": f"{REPO}/{ref.filename}",
        }
        for _source, target in extract_deb(deb, wanted, destination):
            print(f"[{abi}]   -> {target}")

    patch_names(destination, abi)

    for file in sorted(destination.glob("*.so")):
        record["files"][file.name] = {
            "sha256": hashlib.sha256(file.read_bytes()).hexdigest(),
            "bytes": file.stat().st_size,
        }
    return record


def patch_names(destination: Path, abi: str) -> None:
    """Makes the ELF names match the files the APK can actually ship."""
    talloc = destination / "libtalloc.so"
    if talloc.is_file():
        elf = Elf(talloc)
        if elf.shorten_string("libtalloc.so.2", "libtalloc.so"):
            elf.save()
            print(f"[{abi}]   patched libtalloc SONAME -> libtalloc.so")

    proot = destination / "libproot.so"
    if proot.is_file():
        elf = Elf(proot)
        if elf.shorten_string("libtalloc.so.2", "libtalloc.so"):
            elf.save()
            print(f"[{abi}]   patched proot DT_NEEDED -> libtalloc.so")
        remaining = [s for s in Elf(proot).strings() if s.startswith("lib") and ".so" in s]
        print(f"[{abi}]   proot needs: {', '.join(sorted(set(remaining)))}")


# A 32 bit proot has no second loader to ship, so armeabi-v7a legitimately
# lacks this file.
OPTIONAL = {"libproot-loader32.so"}


def check(jni_root: Path) -> int:
    """Reports what is present, for a quick sanity check after vendoring."""
    problems = 0
    for abi in ABIS:
        directory = jni_root / abi
        files = sorted(p.name for p in directory.glob("*.so")) if directory.is_dir() else []
        expected = {
            target
            for wanted in WANTED.values()
            for _source, target in wanted
            if target not in OPTIONAL
        }
        missing = expected - set(files)
        status = "ok" if not missing else f"missing {', '.join(sorted(missing))}"
        if missing:
            problems += 1
        print(f"{abi}: {status}")
        for name in files:
            size = (directory / name).stat().st_size
            print(f"  {name}  {size} bytes")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--abi", action="append", choices=sorted(ABIS), help="limit to one ABI")
    parser.add_argument(
        "--jni-root",
        default="app/src/main/jniLibs",
        help="where to write the binaries",
    )
    parser.add_argument("--check", action="store_true", help="report what is already vendored")
    args = parser.parse_args()

    jni_root = Path(args.jni_root)
    if args.check:
        return check(jni_root)

    if shutil.which("ar") is None:
        raise SystemExit("`ar` (binutils) is required to unpack the packages")

    abis = args.abi or list(ABIS)
    manifest = {"source": REPO, "abis": []}
    for abi in abis:
        manifest["abis"].append(vendor(abi, ABIS[abi], jni_root))

    manifest_path = jni_root / "VENDORED.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"wrote {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
