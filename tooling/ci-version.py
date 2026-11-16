"""Assign a distinct stable plugin version to each GitHub Actions build."""

import json
import os
from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent
base = json.loads((root / "native/manifest.json").read_text(encoding="utf-8"))["version"]
match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", base)
run = os.environ.get("GITHUB_RUN_NUMBER", "")
if not match or not re.fullmatch(r"[1-9]\d*", run):
    raise SystemExit("A stable base version and GitHub run number are required")
major, minor, patch = map(int, match.groups())
version = f"{major}.{minor}.{patch + int(run)}"
with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
    output.write(f"version={version}\n")
print(f"Building Deezer {version}")
