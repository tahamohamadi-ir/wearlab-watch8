"""Labels from the attention study: phases, task performance and EMA, joined onto the analysis windows.

Inputs (written by the watch, same clock as the sensors): events.jsonl (phase_start / phase_stop) and task_events.csv.
Everything here is descriptive; no model is trained and no threshold is tuned on the labels.
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from .session_io import Session


def phases(s: Session) -> pd.DataFrame:
    """phase_id, type, start_s, stop_s (session seconds)."""
    ev = s.events
    if ev is None or ev.empty:
        return pd.DataFrame(columns=["phase", "type", "start_s", "stop_s"])
    rows = []
    for _, e in ev[ev["event"] == "phase_start"].iterrows():
        pid, ptype = str(e["detail"]).split()[:2]
        stop = ev[(ev["event"] == "phase_stop") & (ev["detail"].astype(str) == pid)]
        if len(stop):
            rows.append(dict(phase=pid, type=ptype, start_s=e["session_offset_ns"] / 1e9, stop_s=stop.iloc[0]["session_offset_ns"] / 1e9))
    for stage in ("PRE", "POST"):  # questionnaires are phases too: arousal during them is not "rest"
        a = ev[(ev["event"] == "ema_start") & (ev["detail"].astype(str) == stage)]
        b = ev[(ev["event"] == "ema_end") & (ev["detail"].astype(str) == stage)]
        if len(a) and len(b):
            rows.append(dict(phase=f"EMA_{stage}", type="EMA", start_s=a.iloc[0]["session_offset_ns"] / 1e9, stop_s=b.iloc[0]["session_offset_ns"] / 1e9))
    return pd.DataFrame(rows).sort_values("start_s", ignore_index=True) if rows else pd.DataFrame(columns=["phase", "type", "start_s", "stop_s"])


def task_events(s: Session) -> pd.DataFrame:
    f = s.path / "task_events.csv"
    return pd.read_csv(f) if f.exists() else pd.DataFrame()


def label_windows(win: pd.DataFrame, s: Session) -> pd.DataFrame:
    """Adds `phase`, `phase_type` (the phase containing the window centre) to the windows of one session."""
    w = win.copy()
    w["phase"], w["phase_type"] = None, None
    ph = phases(s)
    centre = (w["t_start_s"] + w["t_end_s"]) / 2
    for _, p in ph.iterrows():
        m = (centre >= p.start_s) & (centre < p.stop_s)
        w.loc[m, "phase"] = p.phase
        w.loc[m, "phase_type"] = p.type
    return w


def d_prime(hits: int, targets: int, fas: int, nontargets: int) -> float:
    from scipy.stats import norm
    h = min(max((hits + 0.5) / (targets + 1.0), 1 / (2 * max(targets, 1))), 1 - 1 / (2 * max(targets, 1)))
    f = min(max((fas + 0.5) / (nontargets + 1.0), 1 / (2 * max(nontargets, 1))), 1 - 1 / (2 * max(nontargets, 1)))
    return float(norm.ppf(h) - norm.ppf(f))


def stroop_summary(t: pd.DataFrame) -> dict:
    s = t[t["event_type"] == "STROOP"]
    if s.empty:
        return {}
    ans = s[s["outcome"] != "OMISSION"]
    out = dict(stroop_trials=len(s), stroop_omissions=int((s["outcome"] == "OMISSION").sum()),
               stroop_accuracy=float((s["outcome"] == "CORRECT").mean()))
    for tag, name in (("C", "congruent"), ("I", "incongruent")):
        sub = s[s["stimulus"].str.endswith("/" + tag)]
        r = sub[sub["outcome"] == "CORRECT"]["rt_ms"].dropna()
        out[f"stroop_rt_{name}_ms"] = float(r.median()) if len(r) else math.nan
        out[f"stroop_acc_{name}"] = float((sub["outcome"] == "CORRECT").mean()) if len(sub) else math.nan
    out["stroop_interference_ms"] = out["stroop_rt_incongruent_ms"] - out["stroop_rt_congruent_ms"]
    rts = ans["rt_ms"].dropna()
    out["stroop_rt_sd_ms"] = float(rts.std()) if len(rts) > 2 else math.nan
    return out


def cpt_summary(t: pd.DataFrame) -> dict:
    c = t[t["event_type"] == "CPT"]
    if c.empty:
        return {}
    n = c["outcome"].value_counts()
    hits, om, co = int(n.get("HIT", 0)), int(n.get("OMISSION", 0)), int(n.get("COMMISSION", 0))
    targets, non = hits + om, int(n.get("CORRECT_REJECTION", 0)) + co
    rt = c[c["outcome"] == "HIT"]["rt_ms"].dropna()
    return dict(cpt_stimuli=len(c), cpt_targets=targets, cpt_hits=hits, cpt_omissions=om, cpt_commissions=co,
                cpt_anticipatory=int(n.get("ANTICIPATORY", 0)),
                cpt_rt_mean_ms=float(rt.mean()) if len(rt) else math.nan,
                cpt_rt_sd_ms=float(rt.std()) if len(rt) > 2 else math.nan,  # RT variability: a core attention marker
                cpt_dprime=d_prime(hits, targets, co, non) if targets and non else math.nan)


def ema_wide(t: pd.DataFrame) -> dict:
    out = {}
    e = t[t["event_type"].str.startswith("EMA_")] if len(t) else t
    for _, r in e.iterrows():
        out[f"ema_{r['event_type'][4:].lower()}_{r['stimulus']}"] = float(r["response"])
    for item in {k.split("_", 2)[2] for k in out}:
        if f"ema_pre_{item}" in out and f"ema_post_{item}" in out:
            out[f"ema_delta_{item}"] = out[f"ema_post_{item}"] - out[f"ema_pre_{item}"]
    return out


def session_labels(s: Session) -> dict:
    t = task_events(s)
    if t.empty:
        return {}
    return {**stroop_summary(t), **cpt_summary(t), **ema_wide(t)}


def add_labels(g: pd.DataFrame, sessions: list[Session]) -> pd.DataFrame:
    """Window-level phase labels + session-level performance/EMA repeated on every window of that session."""
    parts = []
    for s in sessions:
        w = g[g["session_id"] == s.id]
        if w.empty:
            continue
        w = label_windows(w, s)
        for k, v in session_labels(s).items():
            w[k] = v
        parts.append(w)
    return pd.concat(parts).sort_index() if parts else g
