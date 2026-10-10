"""Per-window signal-quality decisions. All thresholds are explicit, recorded in feature_manifest.json and provisional:
they encode what is visibly clean in the recorded data and must be calibrated (e.g. PPG vs ECG) before any claim."""
from __future__ import annotations

import math

THRESHOLDS = dict(
    acc_coverage=0.95,       # share of expected accelerometer samples present
    ppg_coverage=0.90,       # valid PPG samples / expected
    ppg_sqi=0.40,            # spectral cardiac-peak share
    ppg_hr_agree_required=True,   # the free spectral peak must also match the device's own heart rate
    eda_valid_ratio=0.80,    # valid EDA seconds / window seconds
    hr_valid_ratio=0.50,     # seconds with a valid HR / window seconds
    min_beats=20,            # valid beats needed for HRV
    motion_sd_max=1.0,       # m/s^2 (sd of dynamic acc. magnitude): above this PPG was never clean in the recorded sessions
)


def _ok(x, lo) -> bool:
    return x is not None and not (isinstance(x, float) and math.isnan(x)) and x >= lo


def usable_flags(r, th: dict = THRESHOLDS) -> dict:
    """One boolean per modality. A window can be usable for motion and unusable for physiology - never one global flag."""
    worn = r.get("on_body") != 0 and r.get("charging") != 1  # unknown (NaN) is not treated as off-body
    moving = r.get("acc_dyn_sd")
    still_enough = (moving is None) or math.isnan(moving) or moving <= th["motion_sd_max"]
    ppg = worn and _ok(r.get("ppg_coverage"), th["ppg_coverage"]) and _ok(r.get("ppg_sqi"), th["ppg_sqi"]) and still_enough
    if th["ppg_hr_agree_required"]:
        ppg = ppg and r.get("ppg_hr_agree") == 1.0
    hrv = worn and _ok(r.get("hr_valid_ratio"), th["hr_valid_ratio"]) and (r.get("hrv_n_beats") or 0) >= th["min_beats"]
    return dict(
        usable_motion=bool(_ok(r.get("acc_coverage"), th["acc_coverage"])),
        usable_ppg=bool(ppg),
        usable_hrv=bool(hrv and not math.isnan(r.get("rmssd_ms", float("nan")))),
        usable_eda=bool(worn and _ok(r.get("eda_valid_ratio"), th["eda_valid_ratio"])),
        motion_contamination=float(min(1.0, (moving if _ok(moving, 0) else 0.0) / th["motion_sd_max"])),
    )
