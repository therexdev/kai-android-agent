"""Reproduce bundled assets: python3 tools/bundle_test_voices.py /path/to/downloads.

Download the Kitten archive from k2-fsa/sherpa-onnx's tts-models GitHub release.
Uses only Python's standard library. No credentials are needed.
"""
from pathlib import Path
import hashlib
import json
import shutil
import sys
import tarfile
import tempfile

ARCHIVES = {
    "kitten": ("kitten-nano-en-v0_8-int8", "6fa5be852612ce761094ba74ee6123b4fc4acfefa79bf64dc63acae4a83af2fd"),
}
project = Path(__file__).resolve().parent.parent
destination = project / "app/src/main/assets/test-voices"
download = Path(sys.argv[1]).resolve()
destination.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as temporary:
    unpacked = Path(temporary)
    for family, (name, expected) in ARCHIVES.items():
        archive = download / (name + ".tar.bz2")
        if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
            raise RuntimeError("Archive checksum mismatch: " + name)
        with tarfile.open(archive) as packed:
            for member in packed.getmembers():
                if member.name.startswith("/") or ".." in Path(member.name).parts or member.issym() or member.islnk():
                    raise RuntimeError("Unsafe archive entry")
            packed.extractall(unpacked, filter="data")
        target = destination / family
        target.mkdir(exist_ok=True)
        names = ["model.int8.onnx", "voices.bin", "tokens.txt", "LICENSE"]
        for filename in names: shutil.copyfile(unpacked / name / filename, target / filename)
    shutil.copytree(unpacked / ARCHIVES["kitten"][0] / "espeak-ng-data",
        destination / "shared/espeak-ng-data", dirs_exist_ok=True)
files = [dict(path=p.relative_to(destination).as_posix(), sizeBytes=p.stat().st_size,
    sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sorted(destination.rglob("*"))
    if p.is_file() and p.name != "catalog.json"]
revision = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
sources = {family: dict(name=name, archiveSha256=checksum,
    source="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" + name + ".tar.bz2")
    for family, (name, checksum) in ARCHIVES.items()}
(destination / "catalog.json").write_text(json.dumps(dict(schema=1, revision=revision, models=sources, files=files), indent=2) + "\n")
print("Bundled", len(files), "pinned voice files")
