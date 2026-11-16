"""Collect already-built plugin packages and write their release checksums."""

from pathlib import Path
import hashlib
import json
import shutil
import zipfile


root = Path(__file__).resolve().parent.parent
output = root / "dist"
output.mkdir(exist_ok=True)
manifest = json.loads((root / "native/manifest.json").read_text(encoding="utf-8"))
packages = []
for filename in ("deezer.jar", "deezer.apk"):
    source = root / "native/build/outputs/plugins" / filename
    if not source.is_file():
        raise SystemExit(f"Build {filename} before packaging a release")
    with zipfile.ZipFile(source) as archive:
        location = "manifest.json" if source.suffix == ".jar" else "assets/metrofuse/manifest.json"
        packaged = json.loads(archive.read(location))
        if packaged["id"] != manifest["id"] or packaged["version"] != manifest["version"]:
            raise SystemExit(f"Rebuild the outdated {filename}")
        if archive.testzip() is not None:
            raise SystemExit(f"Invalid archive: {filename}")
    target = output / filename
    shutil.copy2(source, target)
    packages.append(target)
(output / "SHA256SUMS").write_text(
    "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in packages), encoding="utf-8"
)
print(f"Prepared Deezer {manifest['version']} in {output}")
