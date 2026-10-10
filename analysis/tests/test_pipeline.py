import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from wearlab import pipeline  # noqa: E402

START_NS = 1_791_000_000 * 10**9


def _base(win, n, hz, t0=0.0, jitter=0.0):
    t = t0 + np.arange(n) / hz
    ts = START_NS + (t * 1e9).astype("int64")
    return pd.DataFrame({"window": win, "sequence": np.arange(n), "source_ts_ns": ts, "monotonic_ns": ts, "utc_ns": ts,
                         "session_offset_ns": (t * 1e9).astype("int64"), "quality": 3}), t


def make_session(root: Path, still=True, worn=True, secs=60) -> Path:
    d = root / "T001_20261010_000000_TEST_001"
    d.mkdir()
    rng = np.random.default_rng(0)
    streams, man = {}, {}

    def put(name, df, extra):
        for k, v in extra.items():
            df[k] = v
        streams[name] = df
        f = f"{name.lower()}.csv"
        df.to_csv(d / f, index=False)
        man[name] = {"file": f, "samples": len(df), "sha256": None}

    b, t = _base(0, secs * 50, 50)
    noise = 0.01 if still else 1.2
    put("ACC", b, {"x": rng.normal(0, noise, len(t)), "y": rng.normal(0, noise, len(t)), "z": 9.81 + rng.normal(0, noise, len(t))})
    b, t = _base(0, secs * 25, 25)
    green = 5e3 * np.sin(2 * np.pi * 1.2 * t) + rng.normal(0, 200, len(t))  # 72 bpm
    put("PPG", b, {"green": green, "green_status": 0, "ir": 1.3e6, "ir_status": 0, "red": 4e5, "red_status": 0})
    # one beat every 833 ms, 3 beats batched per 2.5 s -> one row per beat, status valid
    beats = np.arange(0.5, secs, 0.8333)
    b, _ = _base(0, len(beats), 1)
    ts = START_NS + (beats * 1e9).astype("int64")
    b["source_ts_ns"] = b["monotonic_ns"] = b["utc_ns"] = ts
    b["session_offset_ns"] = (beats * 1e9).astype("int64")
    ibi = 833.3 + (np.arange(len(beats)) % 2) * 40.0  # alternating 833/873
    put("HR_IBI", b, {"hr": 71.0, "hr_status": 1, "ibi_ms": ibi, "ibi_status": 0, "ibi_index": 0, "ibi_n": 1})
    b, t = _base(0, secs, 1)
    put("EDA", b, {"skin_conductance_uS": 0.6 + 0.001 * t, "status": 0})
    b, t = _base(0, 1, 1)
    put("OFFBODY", b, {"on_body": 1.0 if worn else 0.0})
    b, t = _base(0, secs // 10, 0.1)
    put("SYSTEM", b, {"battery_pct": 80.0, "battery_temp_c": 33.0, "voltage_mv": 4000.0, "charging": 0.0,
                      "thermal_status": 0.0, "screen_interactive": 1.0})
    (d / "manifest.json").write_text(json.dumps({"session_id": d.name, "status": "COMPLETED", "start_utc": str(START_NS),
                                                 "end_utc": str(START_NS + secs * 10**9), "streams": man}))
    (d / "metadata.json").write_text(json.dumps({"participant_id": "T001", "sampling_configuration": {"requested_rate_hz": 50}}))
    ev = [{"utc_ns": START_NS, "monotonic_ns": START_NS, "session_offset_ns": 0, "event": "window_start", "detail": "index=0 scheduled_offset_s=0 late_ms=1"},
          {"utc_ns": START_NS, "monotonic_ns": START_NS, "session_offset_ns": secs * 10**9, "event": "window_stop", "detail": "index=0 ACC=1"}]
    (d / "events.jsonl").write_text("\n".join(json.dumps(e) for e in ev))
    return d


class PipelineTests(unittest.TestCase):
    def run_pipe(self, **kw):
        tmp = Path(tempfile.mkdtemp())
        d = make_session(tmp, **kw)
        g = pipeline.run([str(d)], tmp / "out", window_s=30.0, verify=False)
        return tmp, g

    def test_clean_worn_session_is_usable_everywhere(self):
        tmp, g = self.run_pipe()
        self.assertEqual(len(g), 2)  # 60 s / 30 s
        r = g.iloc[0]
        self.assertTrue(r.usable_motion and r.usable_ppg and r.usable_hrv and r.usable_eda)
        self.assertAlmostEqual(r.pulse_rate_bpm, 72.0, delta=1.5)
        self.assertAlmostEqual(r.rmssd_ms, 40.0, delta=1.0)  # alternating 833/873 -> successive diff 40 ms
        self.assertAlmostEqual(r.acc_mag_mean, 9.81, delta=0.05)
        self.assertLess(r.motion_contamination, 0.05)

    def test_off_body_blocks_physiology_but_not_motion(self):
        _, g = self.run_pipe(worn=False)
        r = g.iloc[0]
        self.assertTrue(r.usable_motion)
        self.assertFalse(r.usable_ppg or r.usable_hrv or r.usable_eda)

    def test_vigorous_motion_blocks_ppg(self):
        _, g = self.run_pipe(still=False)
        self.assertGreater(g.iloc[0].motion_contamination, 0.5)
        self.assertFalse(g.iloc[0].usable_ppg)

    def test_layers_are_written_and_bronze_is_lossless(self):
        tmp, g = self.run_pipe()
        out = tmp / "out"
        self.assertTrue((out / "gold" / "features_FS_V1.parquet").exists())
        self.assertTrue((out / "gold" / "feature_manifest.json").exists())
        self.assertTrue((out / "silver" / "windows.parquet").exists())
        b = next((out / "bronze").rglob("ACC.parquet"))
        src = pd.read_csv(tmp / "T001_20261010_000000_TEST_001" / "acc.csv")
        pd.testing.assert_frame_equal(pd.read_parquet(b), src, check_dtype=False)

    def test_baseline_needs_enough_windows(self):
        _, g = self.run_pipe()
        self.assertFalse([c for c in g.columns if c.endswith("_z")])  # 2 windows < min_windows: no z-scores invented


if __name__ == "__main__":
    unittest.main()
