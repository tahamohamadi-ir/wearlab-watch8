"""Per-sample validity rules (Silver). A sample is *flagged*, never deleted: Bronze keeps everything.

Status codes come from the Samsung Health Sensor SDK and were read from real recordings:
  EDA status 0 = valid;  HR status 1 = valid heart rate, 0 = still measuring, -3 / -5 / -10 = no usable signal;
  IBI status 0 = valid beat;  PPG status 0 = ok.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

RULES_VERSION = "validity-v1"
IBI_MIN_MS, IBI_MAX_MS = 300.0, 2000.0
HR_MIN, HR_MAX = 30.0, 220.0
EDA_MIN_US, EDA_MAX_US = 0.005, 100.0
SKIN_MIN_C, SKIN_MAX_C = 15.0, 45.0


def valid_mask(stream: str, df: pd.DataFrame) -> pd.Series:
    """Boolean Series aligned with df."""
    finite = np.isfinite(df.select_dtypes("number")).all(axis=1) if len(df) else pd.Series(dtype=bool)
    if stream == "EDA":
        return (df["status"] == 0) & df["skin_conductance_uS"].between(EDA_MIN_US, EDA_MAX_US)
    if stream == "HR_IBI":
        # row-level: the heart-rate value is usable; the beat interval has its own mask (ibi_valid_mask)
        return (df["hr_status"] == 1) & df["hr"].between(HR_MIN, HR_MAX)
    if stream == "PPG":
        return (df["green_status"] == 0) & df["green"].notna() & df["ir"].notna()
    if stream in ("SKIN_TEMP", "SKIN_TEMP_OD"):
        return df["object_c"].between(SKIN_MIN_C, SKIN_MAX_C)
    return finite.reindex(df.index, fill_value=False) if len(df) else finite


def ibi_valid_mask(df: pd.DataFrame) -> pd.Series:
    """A beat interval is usable when the device flagged both the heart rate and the beat as valid."""
    return (
        (df["hr_status"] == 1)
        & (df["ibi_status"] == 0)
        & df["ibi_ms"].between(IBI_MIN_MS, IBI_MAX_MS)
    )
