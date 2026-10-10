"""Verify sessions that arrived on the PC (Watch -> phone -> PC) against the watch's own manifest.

usage: python verify_transfer.py <received_dir>
For every session dir: each stream's SHA-256 and sample count must equal what the watch wrote into
manifest.json at finalize time. Exit code 1 if anything differs. Read-only.
"""
import csv, hashlib, json, sys
from pathlib import Path


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 16), b""):
            h.update(b)
    return h.hexdigest()


def main():
    root = Path(sys.argv[1])
    bad = 0
    for d in sorted(p for p in root.iterdir() if p.is_dir()):
        mf = d / "manifest.json"
        if not mf.exists():
            print(f"{d.name}: NO MANIFEST"); bad += 1; continue
        m = json.load(open(mf))
        problems = []
        for name, s in m["streams"].items():
            f = d / s["file"]
            if not f.exists():
                problems.append(f"{name}: file missing"); continue
            if s.get("sha256") and sha256(f) != s["sha256"]:
                problems.append(f"{name}: sha256 mismatch")
            rows = sum(1 for _ in open(f, encoding="utf-8")) - 1
            if rows != s["samples"]:
                problems.append(f"{name}: {rows} rows vs {s['samples']} in manifest")
        status = "OK " if not problems else "BAD"
        print(f"{status} {d.name}  status={m['status']}  streams={len(m['streams'])}  {'; '.join(problems)}")
        bad += bool(problems)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
