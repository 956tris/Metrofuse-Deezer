"""Collect already-built plugin packages and write their release checksums."""

from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import zipfile


root = Path(__file__).resolve().parent.parent
output = root / "dist"
output.mkdir(exist_ok=True)
manifest = json.loads((root / "native/manifest.json").read_text(encoding="utf-8"))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--version", default=manifest["version"])
version = parser.parse_args().version
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    raise SystemExit("Use a stable semantic plugin version")
packages = []
for filename in ("deezer.jar", "deezer.apk"):
    source = root / "native/build/outputs/plugins" / filename
    if not source.is_file():
        raise SystemExit(f"Build {filename} before packaging a release")
    with zipfile.ZipFile(source) as archive:
        location = "manifest.json" if source.suffix == ".jar" else "assets/metrofuse/manifest.json"
        packaged = json.loads(archive.read(location))
        if packaged["id"] != manifest["id"] or packaged["version"] != version:
            raise SystemExit(f"Rebuild the outdated {filename}")
        if source.suffix == ".jar":
            browser = archive.getinfo("assets/windows/deezer-browser.exe")
            if browser.file_size < 1024 * 1024:
                raise SystemExit("Desktop sign-in requires the packaged Windows browser")
        else:
            dex_files = [name for name in archive.namelist() if re.fullmatch(r"classes(?:\d+)?\.dex", name)]
            if not dex_files:
                raise SystemExit("Android plugin is missing its DEX code")
            for name in dex_files:
                bytecode = archive.read(name)
                if b"_skikoKt;" in bytecode or b"Lorg/jetbrains/skiko/" in bytecode:
                    raise SystemExit(f"Android plugin references desktop-only Compose code in {name}")
        if archive.testzip() is not None:
            raise SystemExit(f"Invalid archive: {filename}")
    target = output / filename
    shutil.copy2(source, target)
    packages.append(target)
(output / "SHA256SUMS").write_text(
    "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in packages), encoding="utf-8"
)
print(f"Prepared Deezer {version} in {output}")
