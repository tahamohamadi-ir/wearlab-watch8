"""Self-contained HTML report (inline SVG, no JS, no dependencies): one section per session.

usage: python -m wearlab.report <data_root> [--out report.html]
"""
from __future__ import annotations

import argparse
import html
import json
from pathlib import Path

import numpy as np
import pandas as pd

from . import FEATURE_SET

GREEN, RED, AMBER, GREY, BLUE = "#2e9e5b", "#d64545", "#e0a526", "#9aa5b1", "#2f7fd1"


def svg_line(xs, ys, w=520, h=90, color=BLUE, title="", unit="", hline=None):
    xs, ys = np.asarray(xs, float), np.asarray(ys, float)
    ok = np.isfinite(xs) & np.isfinite(ys)
    if ok.sum() < 2:
        return f'<div class="chart empty">{html.escape(title)}: no valid samples</div>'
    xs, ys = xs[ok], ys[ok]
    x0, x1 = xs.min(), xs.max()
    y0, y1 = float(ys.min()), float(ys.max())
    if y1 - y0 < 1e-9:
        y0, y1 = y0 - 1, y1 + 1
    px = lambda x: 36 + (x - x0) / max(x1 - x0, 1e-9) * (w - 44)
    py = lambda y: h - 14 - (y - y0) / (y1 - y0) * (h - 24)
    step = max(1, len(xs) // 600)
    pts = " ".join(f"{px(x):.1f},{py(y):.1f}" for x, y in zip(xs[::step], ys[::step]))
    ref = ""
    if hline is not None and y0 <= hline <= y1:
        ref = f'<line x1="36" x2="{w-8}" y1="{py(hline):.1f}" y2="{py(hline):.1f}" stroke="{AMBER}" stroke-dasharray="4 3"/>'
    return (f'<figure class="chart"><figcaption>{html.escape(title)}</figcaption>'
            f'<svg viewBox="0 0 {w} {h}" width="100%" role="img" aria-label="{html.escape(title)}">'
            f'<rect x="36" y="4" width="{w-44}" height="{h-18}" fill="none" stroke="#d9dee4"/>{ref}'
            f'<polyline fill="none" stroke="{color}" stroke-width="1.5" points="{pts}"/>'
            f'<text x="2" y="12" font-size="9" fill="{GREY}">{y1:.3g}</text><text x="2" y="{h-14}" font-size="9" fill="{GREY}">{y0:.3g}</text>'
            f'<text x="36" y="{h-2}" font-size="9" fill="{GREY}">{x0:.0f}s</text><text x="{w-40}" y="{h-2}" font-size="9" fill="{GREY}">{x1:.0f}s {unit}</text>'
            f"</svg></figure>")


def quality_strip(g: pd.DataFrame, w=520):
    mods = [("usable_motion", "motion"), ("usable_ppg", "PPG"), ("usable_hrv", "HRV"), ("usable_eda", "EDA")]
    n = max(len(g), 1)
    cw = (w - 60) / n
    rows = []
    for i, (col, label) in enumerate(mods):
        y = 6 + i * 20
        rows.append(f'<text x="2" y="{y+11}" font-size="10">{label}</text>')
        for j, v in enumerate(g[col].to_numpy()):
            rows.append(f'<rect x="{56 + j*cw:.1f}" y="{y}" width="{max(cw-1,1):.1f}" height="14" fill="{GREEN if v else RED}"><title>{label} window {j}: {"usable" if v else "not usable"}</title></rect>')
    return f'<figure class="chart"><figcaption>Usable per 30 s window (green = usable)</figcaption><svg viewBox="0 0 {w} 92" width="100%">{"".join(rows)}</svg></figure>'


def table(df: pd.DataFrame, cols: list[str], fmt="{:.2f}") -> str:
    cols = [c for c in cols if c in df]
    head = "".join(f"<th>{c}</th>" for c in cols)
    body = "".join("<tr>" + "".join(f"<td>{'' if pd.isna(v) else (fmt.format(v) if isinstance(v, (float, np.floating)) else v)}</td>" for v in row) + "</tr>"
                   for row in df[cols].itertuples(index=False))
    return f'<div class="scroll"><table><tr>{head}</tr>{body}</table></div>'


def section(root: Path, sid: str, g: pd.DataFrame) -> str:
    sd = root / "silver" / sid
    bz = next((root / "bronze").rglob(f"{sid}/_bronze.json"), None)
    b = json.loads(bz.read_text()) if bz else {}
    parts = [f"<h2>{html.escape(sid)}</h2>"]
    ok = b.get("verified_against_manifest")
    parts.append(f'<p class="meta">manifest status <b>{b.get("manifest_status")}</b> &middot; checksums '
                 f'<b style="color:{GREEN if ok else RED}">{"verified" if ok else "NOT verified " + str(b.get("problems"))}</b> &middot; '
                 f'{len(g)} windows &middot; usable: motion {g.usable_motion.mean():.0%}, PPG {g.usable_ppg.mean():.0%}, '
                 f'HRV {g.usable_hrv.mean():.0%}, EDA {g.usable_eda.mean():.0%}</p>')
    parts.append(quality_strip(g))
    if (sd / "EDA.parquet").exists():
        e = pd.read_parquet(sd / "EDA.parquet")
        e = e[e["valid"]]
        parts.append(svg_line(e.t_s, e.skin_conductance_uS, title="EDA skin conductance (valid samples)", unit="uS", color="#7b4fd1"))
    if (sd / "HR_IBI.parquet").exists():
        h = pd.read_parquet(sd / "HR_IBI.parquet")
        v = h[h["valid"]]
        parts.append(svg_line(v.t_s, v.hr, title="Heart rate from the device (valid samples)", unit="bpm", color=RED))
        iv = h[h.get("ibi_valid", False)] if "ibi_valid" in h else h.iloc[0:0]
        parts.append(svg_line(iv.t_s, iv.ibi_ms, title="Beat intervals (valid IBI)", unit="ms", color=AMBER))
    if (sd / "ACC.parquet").exists():
        a = pd.read_parquet(sd / "ACC.parquet")
        m = np.sqrt(a.x**2 + a.y**2 + a.z**2).to_numpy()
        k = max(1, len(m) // 600)
        parts.append(svg_line(a.t_s.to_numpy()[::k], m[::k], title="Acceleration magnitude", unit="m/s2", color=BLUE))
    parts.append(svg_line(g.t_start_s, g.ppg_sqi, title="PPG spectral quality (threshold 0.4)", color=GREEN, hline=0.4))
    parts.append(svg_line(g.t_start_s, g.acc_dyn_sd, title="Motion: sd of dynamic acceleration per window", unit="m/s2", color=GREY, hline=1.0))
    cols = ["t_start_s", "on_body", "acc_dyn_sd", "ppg_sqi", "pulse_rate_bpm", "hr_device_median_bpm", "hr_mean_bpm", "rmssd_ms", "sdnn_ms",
            "scl_median_uS", "scr_count", "skin_temp_mean_c", "usable_ppg", "usable_hrv", "usable_eda"]
    parts.append("<h3>Window features</h3>" + table(g, cols))
    return "\n".join(parts)


CSS = """body{font:14px/1.45 system-ui,sans-serif;max-width:900px;margin:24px auto;padding:0 16px;color:#1d2733;background:#fff}
h1{font-size:22px}h2{font-size:17px;margin-top:34px;border-top:1px solid #d9dee4;padding-top:14px}h3{font-size:14px}
.meta{color:#556}.chart{margin:8px 0}.chart figcaption{font-size:12px;color:#556}.empty{color:#9aa5b1;font-size:12px;margin:6px 0}
.scroll{overflow-x:auto}table{border-collapse:collapse;font-size:11px}td,th{border:1px solid #e2e6ea;padding:2px 6px;text-align:right}
th{background:#f3f5f7}@media(prefers-color-scheme:dark){body{background:#12171d;color:#e6eaee}th{background:#1c242d}td,th{border-color:#2b3641}
.chart svg rect[fill=none]{stroke:#2b3641}}"""


def write_report(root: Path, out: Path) -> Path:
    g = pd.read_parquet(root / "gold" / f"features_{FEATURE_SET}.parquet")
    man = json.loads((root / "gold" / "feature_manifest.json").read_text())
    body = [f"<h1>WearLab session report</h1><p class='meta'>feature set {man['feature_set']} &middot; pipeline {man['pipeline_version']} "
            f"&middot; window {man['window_s']:.0f} s &middot; thresholds are provisional (see feature_manifest.json)</p>"]
    for sid, grp in g.groupby("session_id", sort=True):
        body.append(section(root, sid, grp.reset_index(drop=True)))
    out.write_text(f"<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                   f"<title>WearLab report</title><style>{CSS}</style>{''.join(body)}", encoding="utf-8")
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--out", default=None)
    a = ap.parse_args(argv)
    root = Path(a.root)
    print(write_report(root, Path(a.out) if a.out else root / "report.html"))


if __name__ == "__main__":
    main()
