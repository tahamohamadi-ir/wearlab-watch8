"""Pure feature functions (numpy in, dict out). No I/O, no hidden state: every function is unit-tested on synthetic signals.

Conventions: NaN means "not computable from the valid data in this window" (never 0). Units are in the key name.
"""
from __future__ import annotations

import math

import numpy as np
from scipy import signal

NAN = float("nan")


# ----------------------------------------------------------------------------- HRV

def clean_ibi(ibi_ms: np.ndarray, tol: float = 0.20) -> tuple[np.ndarray, np.ndarray]:
    """Artifact rejection (local-median rule, in the spirit of Malik): a beat is dropped when it differs by more than
    `tol` from the median of its neighbourhood. Returns (kept_values, kept_original_positions)."""
    x = np.asarray(ibi_ms, float)
    if len(x) < 3:
        return x, np.arange(len(x))
    k = 5
    pad = np.pad(x, (k // 2, k // 2), mode="edge")
    med = np.array([np.median(pad[i:i + k]) for i in range(len(x))])
    keep = np.abs(x - med) <= tol * med
    return x[keep], np.nonzero(keep)[0]


def hrv_features(ibi_ms: np.ndarray, min_beats: int = 20) -> dict:
    out = dict(hrv_n_beats=0, hrv_n_rejected=0, hr_mean_bpm=NAN, ibi_mean_ms=NAN, sdnn_ms=NAN, rmssd_ms=NAN, pnn50_pct=NAN)
    x = np.asarray(ibi_ms, float)
    x = x[np.isfinite(x)]
    kept, pos = clean_ibi(x)
    out["hrv_n_beats"] = int(len(kept))
    out["hrv_n_rejected"] = int(len(x) - len(kept))
    if len(kept) < min_beats:
        return out
    out["ibi_mean_ms"] = float(kept.mean())
    out["hr_mean_bpm"] = float(60000.0 / kept.mean())
    out["sdnn_ms"] = float(kept.std(ddof=1))
    adjacent = np.diff(pos) == 1  # only successive beats define a valid difference
    d = np.diff(kept)[adjacent]
    if len(d) >= max(5, min_beats // 2):
        out["rmssd_ms"] = float(math.sqrt(np.mean(d ** 2)))
        out["pnn50_pct"] = float(100.0 * np.mean(np.abs(d) > 50.0))
    return out


# ----------------------------------------------------------------------------- PPG

def _resample_uniform(t: np.ndarray, x: np.ndarray, fs: float) -> np.ndarray:
    n = int(round((t[-1] - t[0]) * fs)) + 1
    return np.interp(t[0] + np.arange(n) / fs, t, x)


def ppg_features(t_s: np.ndarray, ppg: np.ndarray, fs: float = 25.0, device_hr_bpm: float = NAN) -> dict:
    """Spectral signal-quality index and pulse rate of one window. Use the GREEN channel on the wrist: on the Watch8 it
    tracks the device heart rate in most still windows, whereas IR/red mostly lock onto slow artefacts (checked on real data).

    sqi = share of 0.5-5 Hz band power that sits at the dominant cardiac peak (0.7-3 Hz) and its 2nd harmonic.
    Clean PPG concentrates power there; motion and noise spread it. It is a transparent heuristic, to be calibrated
    against a reference (ECG) before any claim - the thresholds live in `quality.py`.
    """
    out = dict(ppg_sqi=NAN, pulse_rate_bpm=NAN, pulse_amp=NAN, ppg_hr_agree=NAN, ppg_power_at_device_hr=NAN)
    t = np.asarray(t_s, float)
    x = np.asarray(ppg, float)
    ok = np.isfinite(t) & np.isfinite(x)
    t, x = t[ok], x[ok]
    if len(t) < int(fs * 8) or t[-1] - t[0] < 8:
        return out
    u = _resample_uniform(t, x, fs)
    u = u - np.mean(u)
    sos = signal.butter(3, [0.5, 5.0], btype="band", fs=fs, output="sos")
    f = signal.sosfiltfilt(sos, u)
    nfft = 8192
    spec = np.abs(np.fft.rfft(f * np.hanning(len(f)), nfft)) ** 2
    freq = np.fft.rfftfreq(nfft, 1 / fs)
    band = (freq >= 0.5) & (freq <= 5.0)
    total = spec[band].sum()
    if total <= 0 or not np.isfinite(total):
        return out
    cardiac = (freq >= 0.7) & (freq <= 3.0)
    f0 = freq[cardiac][np.argmax(spec[cardiac])]
    near = lambda c: (np.abs(freq - c) <= 0.12) & band
    p = spec[near(f0)].sum() + (spec[near(2 * f0)].sum() if 2 * f0 < 5.0 else 0.0)
    out["ppg_sqi"] = float(min(1.0, p / total))
    out["pulse_rate_bpm"] = float(f0 * 60.0)
    out["pulse_amp"] = float(np.percentile(f, 95) - np.percentile(f, 5))
    if np.isfinite(device_hr_bpm):
        out["ppg_hr_agree"] = float(abs(out["pulse_rate_bpm"] - device_hr_bpm) <= 8.0)
        # independent consistency check: how much band power sits at the device's own heart rate (+ 2nd harmonic)
        fd = device_hr_bpm / 60.0
        pd_ = spec[near(fd)].sum() + (spec[near(2 * fd)].sum() if 2 * fd < 5.0 else 0.0)
        out["ppg_power_at_device_hr"] = float(min(1.0, pd_ / total))
    return out


# ----------------------------------------------------------------------------- EDA

def eda_features(t_s: np.ndarray, sc_uS: np.ndarray, prominence_uS: float = 0.01) -> dict:
    out = dict(scl_median_uS=NAN, scl_mean_uS=NAN, eda_sd_uS=NAN, eda_slope_uS_per_min=NAN,
               scr_count=NAN, scr_rate_per_min=NAN, scr_amp_mean_uS=NAN, phasic_mean_uS=NAN, eda_n=0)
    t = np.asarray(t_s, float)
    x = np.asarray(sc_uS, float)
    ok = np.isfinite(t) & np.isfinite(x)
    t, x = t[ok], x[ok]
    out["eda_n"] = int(len(x))
    if len(x) < 10:
        return out
    out["scl_median_uS"] = float(np.median(x))
    out["scl_mean_uS"] = float(x.mean())
    out["eda_sd_uS"] = float(x.std(ddof=1))
    out["eda_slope_uS_per_min"] = float(np.polyfit(t - t[0], x, 1)[0] * 60.0)
    # tonic/phasic split at the native ~1 Hz: tonic = centred rolling median over ~21 s (a shorter window follows the SCR itself and halves its amplitude); SCRs = phasic peaks above the noise floor
    import pandas as pd
    tonic = pd.Series(x).rolling(21, center=True, min_periods=5).median().to_numpy()
    phasic = x - tonic
    peaks, props = signal.find_peaks(phasic, prominence=prominence_uS, distance=2)
    dur_min = max((t[-1] - t[0]) / 60.0, 1e-9)
    out["scr_count"] = float(len(peaks))
    out["scr_rate_per_min"] = float(len(peaks) / dur_min)
    out["scr_amp_mean_uS"] = float(np.mean(props["prominences"])) if len(peaks) else 0.0
    out["phasic_mean_uS"] = float(np.mean(np.clip(phasic, 0, None)))
    return out


# ----------------------------------------------------------------------------- motion

def motion_features(xyz: np.ndarray, fs: float) -> dict:
    """xyz: (n, 3) accelerometer in m/s^2 (gravity included)."""
    keys = ["acc_mag_mean", "acc_dyn_sd", "acc_dyn_rms", "acc_sma", "acc_jerk_rms", "acc_dom_freq_hz",
            "acc_spec_entropy", "acc_power_lo", "acc_power_hi", "acc_burst_count", "acc_still_frac"]
    out = {k: NAN for k in keys}
    a = np.asarray(xyz, float)
    a = a[np.isfinite(a).all(axis=1)]
    if len(a) < int(fs * 4):
        return out
    mag = np.linalg.norm(a, axis=1)
    dyn = mag - np.median(mag)
    out["acc_mag_mean"] = float(mag.mean())
    out["acc_dyn_sd"] = float(dyn.std(ddof=1))
    out["acc_dyn_rms"] = float(np.sqrt(np.mean(dyn ** 2)))
    out["acc_sma"] = float(np.mean(np.abs(a - a.mean(axis=0)).sum(axis=1)))
    out["acc_jerk_rms"] = float(np.sqrt(np.mean((np.diff(mag) * fs) ** 2)))
    f, p = signal.welch(dyn - dyn.mean(), fs=fs, nperseg=min(len(dyn), int(fs * 4)))
    band = (f >= 0.3) & (f <= 10.0)
    if p[band].sum() > 0:
        out["acc_dom_freq_hz"] = float(f[band][np.argmax(p[band])])
        pn = p[band] / p[band].sum()
        out["acc_spec_entropy"] = float(-(pn * np.log2(pn + 1e-12)).sum() / math.log2(len(pn)))
        out["acc_power_lo"] = float(p[(f >= 0.3) & (f < 3.0)].sum())
        out["acc_power_hi"] = float(p[(f >= 3.0) & (f <= 10.0)].sum())
    burst = np.abs(dyn) > 0.5
    out["acc_burst_count"] = float(np.sum(np.diff(burst.astype(int)) == 1))
    sec = int(fs)
    n = len(dyn) // sec
    sds = dyn[: n * sec].reshape(n, sec).std(axis=1)
    out["acc_still_frac"] = float(np.mean(sds < 0.05)) if n else NAN
    return out


def gyro_features(xyz: np.ndarray) -> dict:
    g = np.asarray(xyz, float)
    g = g[np.isfinite(g).all(axis=1)]
    if len(g) < 10:
        return dict(gyro_rms_rad_s=NAN, gyro_mean_rad_s=NAN)
    m = np.linalg.norm(g, axis=1)
    return dict(gyro_rms_rad_s=float(np.sqrt(np.mean(m ** 2))), gyro_mean_rad_s=float(m.mean()))


def tilt_features(q: np.ndarray) -> dict:
    """q: (n, 4) = qx, qy, qz, qw. Roll / pitch of the wrist in degrees."""
    q = np.asarray(q, float)
    q = q[np.isfinite(q).all(axis=1)]
    if len(q) < 5:
        return dict(roll_mean_deg=NAN, roll_sd_deg=NAN, pitch_mean_deg=NAN, pitch_sd_deg=NAN)
    x, y, z, w = q.T
    roll = np.degrees(np.arctan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y)))
    pitch = np.degrees(np.arcsin(np.clip(2 * (w * y - z * x), -1, 1)))
    return dict(roll_mean_deg=float(roll.mean()), roll_sd_deg=float(roll.std(ddof=1)),
                pitch_mean_deg=float(pitch.mean()), pitch_sd_deg=float(pitch.std(ddof=1)))


# ----------------------------------------------------------------------------- temperature / environment

def slope_per_min(t_s: np.ndarray, x: np.ndarray) -> float:
    t = np.asarray(t_s, float)
    x = np.asarray(x, float)
    ok = np.isfinite(t) & np.isfinite(x)
    if ok.sum() < 3 or np.ptp(t[ok]) < 5:
        return NAN
    return float(np.polyfit(t[ok] - t[ok][0], x[ok], 1)[0] * 60.0)


def skin_features(t_s: np.ndarray, obj_c: np.ndarray, amb_c: np.ndarray) -> dict:
    obj = np.asarray(obj_c, float)
    obj = obj[np.isfinite(obj)]
    return dict(
        skin_temp_mean_c=float(obj.mean()) if len(obj) else NAN,
        skin_temp_slope_c_per_min=slope_per_min(t_s, obj_c),
        ambient_temp_mean_c=float(np.nanmean(amb_c)) if len(amb_c) and np.isfinite(amb_c).any() else NAN,
        skin_n=int(len(obj)),
    )
