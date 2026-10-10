"""Bronze -> Silver -> Gold. Every layer is derived from the one below and can be rebuilt from Bronze alone.

  bronze/<participant>/<date>/<session>/<stream>.parquet   lossless copy of the CSVs + _bronze.json (source checksums)
  silver/<session>/<stream>.parquet                        samples + t_s + valid flag (flagged, never dropped)
  silver/windows.parquet                                   one row per analysis window: coverage, validity, quality, context
  gold/features_FS_V1.parquet                              windows + features + usable flags + personal z-scores
  gold/feature_manifest.json, gold/baseline.json           what was computed, with which thresholds and from which sessions

usage: python -m wearlab.pipeline <session_dir> [<session_dir> ...] --out data [--window 30] [--tz 3.5] [--baseline baseline.json]
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from . import FEATURE_SET, PIPELINE_VERSION, features as F
from .quality import THRESHOLDS, usable_flags
from .session_io import Session, load_session
from .study import add_labels
from .validity import RULES_VERSION, ibi_valid_mask, valid_mask

NOMINAL_HZ = {"PPG": 25.0, "EDA": 1.0, "HR_IBI": 1.0}
BASELINE_FEATURES = ["hr_mean_bpm", "rmssd_ms", "sdnn_ms", "scl_median_uS", "scr_rate_per_min", "skin_temp_mean_c",
                     "pulse_rate_bpm", "acc_dyn_sd"]


# --------------------------------------------------------------------------- bronze

def to_bronze(s: Session, out_root: Path) -> Path:
    date = dt.datetime.fromtimestamp(s.start_utc_ns / 1e9, dt.timezone.utc).strftime("%Y-%m-%d")
    d = out_root / "bronze" / s.participant / date / s.id
    d.mkdir(parents=True, exist_ok=True)
    for name, df in s.streams.items():
        df.to_parquet(d / f"{name}.parquet", index=False)  # values untouched (ADR-001)
    (d / "_bronze.json").write_text(json.dumps({
        "session_id": s.id, "pipeline_version": PIPELINE_VERSION, "verified_against_manifest": not s.problems,
        "problems": s.problems, "source_sha256": {k: v.get("sha256") for k, v in s.manifest["streams"].items()},
        "rows": {k: len(v) for k, v in s.streams.items()}, "manifest_status": s.manifest["status"],
    }, indent=2))
    return d


# --------------------------------------------------------------------------- silver

def _slice(t: np.ndarray, a: float, b: float) -> slice:
    return slice(int(np.searchsorted(t, a, "left")), int(np.searchsorted(t, b, "left")))


def build_silver(s: Session, out_root: Path, window_s: float, tz_h: float, th: dict) -> pd.DataFrame:
    sd = out_root / "silver" / s.id
    sd.mkdir(parents=True, exist_ok=True)
    S: dict[str, pd.DataFrame] = {}
    for name, df in s.streams.items():
        d = df.copy()
        d["t_s"] = s.t(d)
        d["valid"] = valid_mask(name, d).to_numpy() if len(d) else np.array([], bool)
        if name == "HR_IBI" and len(d):
            d["ibi_valid"] = ibi_valid_mask(d).to_numpy()
        d.to_parquet(sd / f"{name}.parquet", index=False)
        S[name] = d
    caps = s.capture_windows()
    if not caps:  # recorded before windows were logged: one window spanning the whole recording
        # on-change streams (off-body, steps) carry the time their state *began*, possibly long before the session
        ts = [d["t_s"] for n, d in S.items() if len(d) and n in ("ACC", "GYRO", "PPG", "EDA", "HR_IBI")]
        caps = [(0, float(min(x.min() for x in ts)), float(max(x.max() for x in ts)))]
    rate = s.requested_rate_hz
    rows = []
    for cap_idx, a, b in caps:
        k = 0
        while a + (k + 1) * window_s <= b + 0.6:
            w0, w1 = a + k * window_s, a + (k + 1) * window_s
            rows.append(_window_row(s, S, cap_idx, k, w0, w1, rate, tz_h, th))
            k += 1
    wdf = pd.DataFrame(rows)
    wdf.to_parquet(sd / "windows.parquet", index=False)
    return wdf


def _col(S, name, col, a, b):
    d = S.get(name)
    if d is None or not len(d):
        return np.array([]), np.array([]), np.array([], bool)
    t = d["t_s"].to_numpy()
    sl = _slice(t, a, b)
    return t[sl], d[col].to_numpy()[sl], d["valid"].to_numpy()[sl]


def _window_row(s, S, cap, k, a, b, rate, tz_h, th) -> dict:
    W = b - a
    r = dict(session_id=s.id, participant_id=s.participant, capture_window=cap, win_in_capture=k,
             t_start_s=a, t_end_s=b, utc_start_ns=s.start_utc_ns + int(a * 1e9))
    r["local_hour"] = float(((r["utc_start_ns"] / 1e9 / 3600.0) + tz_h) % 24)

    # --- context (on-change sensors are forward-filled: the latest event at or before the window end)
    def last_before(name, col):
        d = S.get(name)
        if d is None or not len(d):
            return np.nan
        t = d["t_s"].to_numpy()
        i = np.searchsorted(t, b, "right") - 1
        return float(d[col].iloc[i]) if i >= 0 else np.nan

    r["on_body"] = last_before("OFFBODY", "on_body")
    r["charging"] = last_before("SYSTEM", "charging")
    r["battery_pct"] = last_before("SYSTEM", "battery_pct")
    tt, bt, _ = _col(S, "SYSTEM", "battery_temp_c", a, b)
    r["battery_temp_c"] = float(np.nanmean(bt)) if len(bt) else np.nan
    st, sv, _ = _col(S, "STEPS", "steps_since_boot", a - 3600, b)
    r["steps_in_window"] = float(sv[-1] - sv[0]) if len(sv) >= 2 else np.nan
    mk = s.events[s.events["event"] == "marker"] if len(s.events) else s.events
    r["markers"] = int(((mk["session_offset_ns"] / 1e9 >= a) & (mk["session_offset_ns"] / 1e9 < b)).sum()) if len(mk) else 0

    # --- motion
    d = S.get("ACC")
    if d is not None and len(d):
        sl = _slice(d["t_s"].to_numpy(), a, b)
        xyz = d[["x", "y", "z"]].to_numpy()[sl]
        r["acc_n"] = len(xyz)
        r["acc_coverage"] = float(min(1.0, len(xyz) / (rate * W)))
        fs = len(xyz) / W if len(xyz) else rate
        r.update(F.motion_features(xyz, fs))
    else:
        r["acc_coverage"] = 0.0
    d = S.get("GYRO")
    if d is not None and len(d):
        sl = _slice(d["t_s"].to_numpy(), a, b)
        r.update(F.gyro_features(d[["x", "y", "z"]].to_numpy()[sl]))
    d = S.get("ROTATION")
    if d is not None and len(d):
        sl = _slice(d["t_s"].to_numpy(), a, b)
        r.update(F.tilt_features(d[["qx", "qy", "qz", "qw"]].to_numpy()[sl]))

    # --- heart rate / IBI
    d = S.get("HR_IBI")
    hr_med = np.nan
    if d is not None and len(d):
        t = d["t_s"].to_numpy()
        sl = _slice(t, a, b)
        sub = d.iloc[sl]
        vhr = sub[sub["valid"]]
        r["hr_valid_ratio"] = float(len(np.unique(np.floor(vhr["t_s"]))) / W)
        hr_med = float(vhr["hr"].median()) if len(vhr) else np.nan
        ibi = sub.loc[sub["ibi_valid"], "ibi_ms"].to_numpy()
        r.update(F.hrv_features(ibi, th["min_beats"]))
        r["hr_device_median_bpm"] = hr_med
    else:
        r["hr_valid_ratio"] = 0.0

    # --- PPG
    d = S.get("PPG")
    if d is not None and len(d):
        t = d["t_s"].to_numpy()
        sl = _slice(t, a, b)
        sub = d.iloc[sl]
        r["ppg_coverage"] = float(min(1.0, sub["valid"].sum() / (NOMINAL_HZ["PPG"] * W)))
        v = sub[sub["valid"]]
        r.update(F.ppg_features(v["t_s"].to_numpy(), v["green"].to_numpy(), NOMINAL_HZ["PPG"], hr_med))
        r["ppg_ir_dc_mean"] = float(v["ir"].mean()) if len(v) else np.nan  # contact / perfusion indicator
    else:
        r["ppg_coverage"] = 0.0

    # --- EDA
    d = S.get("EDA")
    if d is not None and len(d):
        t = d["t_s"].to_numpy()
        sub = d.iloc[_slice(t, a, b)]
        r["eda_valid_ratio"] = float(min(1.0, sub["valid"].sum() / (NOMINAL_HZ["EDA"] * W)))
        v = sub[sub["valid"]]
        r.update(F.eda_features(v["t_s"].to_numpy(), v["skin_conductance_uS"].to_numpy()))
    else:
        r["eda_valid_ratio"] = 0.0

    # --- skin temperature (continuous or spot)
    for name in ("SKIN_TEMP_OD", "SKIN_TEMP"):
        d = S.get(name)
        if d is not None and len(d):
            sub = d.iloc[_slice(d["t_s"].to_numpy(), a, b)]
            sub = sub[sub["valid"]]
            if len(sub):
                r.update(F.skin_features(sub["t_s"].to_numpy(), sub["object_c"].to_numpy(), sub["ambient_c"].to_numpy()))
                break

    # --- environment
    for name, col, key in (("BARO", "pressure_hPa", "baro"), ("LIGHT", "lux", "light")):
        d = S.get(name)
        if d is not None and len(d):
            tt, v, _ = _col(S, name, col, a, b)
            if len(v):
                if key == "baro":
                    r["baro_mean_hPa"] = float(v.mean())
                    r["baro_slope_hPa_per_min"] = F.slope_per_min(tt, v)
                else:
                    r["light_median_lux"] = float(np.median(v))
    return r


# --------------------------------------------------------------------------- gold

def add_gold(win: pd.DataFrame, th: dict, baseline: dict | None) -> tuple[pd.DataFrame, dict]:
    g = win.copy()
    flags = g.apply(lambda row: usable_flags(row, th), axis=1, result_type="expand")
    g = pd.concat([g, flags], axis=1)
    if baseline is None:
        baseline = fit_baseline(g)
    for f, b in baseline["features"].items():
        if f in g and b["mad"] > 0 and b["n"] >= baseline["min_windows"]:
            g[f + "_z"] = (g[f] - b["median"]) / (1.4826 * b["mad"])
    return g, baseline


def fit_baseline(g: pd.DataFrame, min_windows: int = 5) -> dict:
    """Personal baseline = robust median/MAD of each feature over this person's quiet, on-wrist, usable windows."""
    quiet = (g["on_body"] == 1) & (g["acc_dyn_sd"] < 0.30) & (g["charging"] != 1)
    out = {"definition": "on_body & acc_dyn_sd<0.30 m/s^2 & not charging & feature usable", "min_windows": min_windows, "features": {}}
    gate = {"hr_mean_bpm": "usable_hrv", "rmssd_ms": "usable_hrv", "sdnn_ms": "usable_hrv", "scl_median_uS": "usable_eda",
            "scr_rate_per_min": "usable_eda", "skin_temp_mean_c": None, "pulse_rate_bpm": "usable_ppg", "acc_dyn_sd": "usable_motion"}
    for f in BASELINE_FEATURES:
        if f not in g:
            continue
        m = quiet & (g[gate[f]] if gate.get(f) else True) & g[f].notna()
        x = g.loc[m, f]
        if len(x):
            med = float(x.median())
            out["features"][f] = {"median": med, "mad": float((x - med).abs().median()), "n": int(len(x))}
    return out


def feature_manifest(g: pd.DataFrame, sessions: list[Session], th: dict, window_s: float) -> dict:
    return {
        "feature_set": FEATURE_SET, "pipeline_version": PIPELINE_VERSION, "validity_rules": RULES_VERSION,
        "window_s": window_s, "thresholds": th, "rows": int(len(g)),
        "columns": list(g.columns),
        "sessions": {s.id: {"manifest_status": s.manifest["status"], "problems": s.problems,
                            "source_sha256": {k: v.get("sha256") for k, v in s.manifest["streams"].items()}} for s in sessions},
        "notes": [
            "NaN = not computable from valid data in the window (never imputed).",
            "ppg_sqi is a spectral heuristic; thresholds are provisional until calibrated against ECG.",
            "Beat timestamps within a 1 s HR batch are estimated by the app; HRV uses interval values only.",
        ],
    }


# --------------------------------------------------------------------------- CLI

def run(session_dirs: list[str], out: Path, window_s: float = 30.0, tz_h: float = 0.0, th: dict | None = None,
        baseline_path: str | None = None, verify: bool = True) -> pd.DataFrame:
    th = th or THRESHOLDS
    sessions, wins = [], []
    for p in session_dirs:
        s = load_session(p, verify=verify)
        sessions.append(s)
        to_bronze(s, out)
        w = build_silver(s, out, window_s, tz_h, th)
        wins.append(w)
        print(f"{s.id}: {len(w)} windows" + (f"  PROBLEMS: {s.problems}" if s.problems else ""), file=sys.stderr)
    win = pd.concat(wins, ignore_index=True)
    (out / "silver").mkdir(parents=True, exist_ok=True)
    win.to_parquet(out / "silver" / "windows.parquet", index=False)
    baseline = json.load(open(baseline_path)) if baseline_path else None
    win = add_labels(win, sessions)  # phase + task performance + EMA (no-op for sessions without a study)
    g, baseline = add_gold(win, th, baseline)
    gd = out / "gold"
    gd.mkdir(parents=True, exist_ok=True)
    g.to_parquet(gd / f"features_{FEATURE_SET}.parquet", index=False)
    (gd / "baseline.json").write_text(json.dumps(baseline, indent=2))
    (gd / "feature_manifest.json").write_text(json.dumps(feature_manifest(g, sessions, th, window_s), indent=2))
    return g


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sessions", nargs="+")
    ap.add_argument("--out", default="data")
    ap.add_argument("--window", type=float, default=30.0, help="analysis window length in seconds")
    ap.add_argument("--tz", type=float, default=0.0, help="local UTC offset in hours for local_hour (e.g. 3.5)")
    ap.add_argument("--baseline", default=None, help="reuse a frozen baseline.json instead of fitting one")
    a = ap.parse_args(argv)
    g = run(a.sessions, Path(a.out), a.window, a.tz, baseline_path=a.baseline)
    print(f"gold rows: {len(g)}  -> {Path(a.out) / 'gold'}")


if __name__ == "__main__":
    main()
