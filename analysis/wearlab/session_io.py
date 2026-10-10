"""Reading a recorded session and verifying it against the watch's own manifest (read-only)."""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 16), b""):
            h.update(block)
    return h.hexdigest()


@dataclass
class Session:
    path: Path
    manifest: dict
    metadata: dict
    events: pd.DataFrame
    streams: dict[str, pd.DataFrame] = field(default_factory=dict)
    problems: list[str] = field(default_factory=list)

    @property
    def id(self) -> str:
        return self.manifest["session_id"]

    @property
    def participant(self) -> str:
        return self.metadata.get("participant_id", self.id.split("_")[0])

    @property
    def start_utc_ns(self) -> int:
        return int(self.manifest["start_utc"])

    @property
    def requested_rate_hz(self) -> float:
        return float(self.metadata.get("sampling_configuration", {}).get("requested_rate_hz", 50))

    def t(self, df: pd.DataFrame) -> pd.Series:
        """Seconds since session start on the UTC clock (the canonical wall-clock, ADR-002)."""
        return (df["utc_ns"] - self.start_utc_ns) / 1e9

    def capture_windows(self) -> list[tuple[int, float, float]]:
        """(index, start_s, stop_s) of every capture window, from the window_start/window_stop events."""
        ev = self.events
        out = []
        if ev.empty:
            return out
        starts = ev[ev["event"] == "window_start"]
        stops = ev[ev["event"] == "window_stop"]
        for _, s in starts.iterrows():
            idx = int(str(s["detail"]).split("index=")[1].split()[0])
            # detail is "index=K" in v0.3 sessions and "index=K <per-stream counts>" later
            stop = stops[stops["detail"].astype(str).str.match(rf"index={idx}(\s|$)")]
            if len(stop):
                out.append((idx, s["session_offset_ns"] / 1e9, stop.iloc[0]["session_offset_ns"] / 1e9))
        return out


def load_session(path: str | Path, verify: bool = True) -> Session:
    p = Path(path)
    manifest = json.loads((p / "manifest.json").read_text(encoding="utf-8"))
    metadata = json.loads((p / "metadata.json").read_text(encoding="utf-8")) if (p / "metadata.json").exists() else {}
    ev_path = p / "events.jsonl"
    events = pd.read_json(ev_path, lines=True) if ev_path.exists() and ev_path.stat().st_size else pd.DataFrame()
    s = Session(p, manifest, metadata, events)
    for name, info in manifest["streams"].items():
        f = p / info["file"]
        if not f.exists():
            s.problems.append(f"{name}: file missing")
            continue
        if verify and info.get("sha256") and sha256_file(f) != info["sha256"]:
            s.problems.append(f"{name}: sha256 differs from manifest")
        df = pd.read_csv(f)
        if "window" not in df:  # sessions recorded before v0.3
            df.insert(0, "window", 0)
        if len(df) != info["samples"]:
            s.problems.append(f"{name}: {len(df)} rows, manifest says {info['samples']}")
        s.streams[name] = df
    if manifest["status"] not in ("COMPLETED", "INTERRUPTED"):
        s.problems.append(f"status {manifest['status']}")
    return s
