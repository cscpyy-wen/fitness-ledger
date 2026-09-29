"""Verify the frozen source subset and user-guide links; no network or credentials."""
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
manifest = json.loads((ROOT / "source-snapshot-manifest.json").read_text(encoding="utf-8"))
assert manifest["format"] == "fitness-ledger-github-source-snapshot-v1"
assert manifest["originalBuildCommit"] == "2979188aa531d68142d18684557a51a40d93dc78"
paths = set()
for entry in manifest["files"]:
    relative = entry["path"]
    assert relative not in paths and not Path(relative).is_absolute() and ".." not in Path(relative).parts
    paths.add(relative)
    data = (ROOT / relative).read_bytes()
    assert len(data) == entry["bytes"], f"Size mismatch: {relative}"
    assert hashlib.sha256(data).hexdigest() == entry["sha256"], f"SHA-256 mismatch: {relative}"
    assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == entry["originalGitBlob"], f"Original Git blob mismatch: {relative}"
assert len(paths) == 194, "Frozen source inventory changed; review the snapshot boundary."

assert (ROOT / "LICENSE").read_text(encoding="utf-8").splitlines() == (ROOT / "xiaomi-probe/LICENSE").read_text(encoding="utf-8").splitlines(), "GPL standard license text changed."
assert "SPDX-License-Identifier: GPL-3.0-or-later" in (ROOT / "OPEN_SOURCE_NOTICE.md").read_text(encoding="utf-8")
for relative in ("README.md", "docs/DEVELOPMENT.md", "docs/RELEASE-0.6.0-alpha11.md", "OPEN_SOURCE_NOTICE.md"):
    doc = ROOT / relative
    text = doc.read_text(encoding="utf-8")
    targets = re.findall(r"\]\(([^)]+)\)", text) + re.findall(r'<img\s+[^>]*src="([^"]+)"', text)
    for target in targets:
        if target.startswith(("https://", "http://", "#", "mailto:")):
            continue
        target = target.split("#", 1)[0]
        assert (doc.parent / target).is_file(), f"Broken local link in {relative}: {target}"

tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0")
for relative in filter(None, tracked):
    parts = Path(relative).parts
    assert not any(p in {".signing", ".toolchains", ".gradle", "outputs", "交付", "audit_evidence", "测试截图"} for p in parts), f"Private/generated path tracked: {relative}"
    assert not relative.endswith((".jks", ".keystore", ".db", ".apk", ".env")), f"Sensitive/runtime file tracked: {relative}"
print(f"PASS: {len(paths)} original-source blobs, user-guide links and tracked-file boundaries. This is not an Android build or device test.")
