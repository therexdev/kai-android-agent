"""Compress streamed speech weights, preserving mmap memory assets and all file contents.

Usage: python3 tools/compact_voice_apk.py input.apk compact-unsigned.apk
Run Android zipalign afterwards, then sign with the existing private owner key.
Do not distribute the unsigned intermediate. No signing key is handled by this tool.
"""
from pathlib import Path
import sys
import zipfile
import copy

source, destination = map(Path, sys.argv[1:3])
if source.resolve() == destination.resolve(): raise ValueError("Use a separate output path")
with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w") as compact:
    for source_entry in original.infolist():
        entry = copy.copy(source_entry)
        # Signatures must be regenerated after packaging. Other metadata is preserved.
        if entry.filename.upper() in {"META-INF/MANIFEST.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA"}: continue
        if entry.filename.startswith(("assets/desktop-voice/", "assets/test-voices/")) and entry.filename.endswith(".onnx"):
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry._compresslevel = 9
        compact.writestr(entry, original.read(entry.filename))
with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination) as compact:
    for entry in compact.infolist():
        assert original.read(entry.filename) == compact.read(entry.filename), entry.filename
    assert compact.getinfo("assets/memory/minilm-l6-v2-qint8.onnx").compress_type == zipfile.ZIP_STORED
print("Prepared compact unsigned APK:", destination.stat().st_size)
