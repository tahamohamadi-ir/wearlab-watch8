"""Synchronization Validation Report (Phase 3). Read-only on Bronze data.

usage: python sync_validation.py <session_dir> [out_dir]
Writes <out_dir>/sync_validation_<session_id>.{json,md}. Stdlib only.
"""
import csv, json, statistics, sys
from pathlib import Path

NOMINAL_HZ = {"ROTATION": 50, "MAG": 50, "BARO": 10, "LIGHT": 7, "SYSTEM": 0.1, "SKIN_TEMP_OD": 0.2, "LOCATION": 1, "SKIN_TEMP_OD": 1, "ACC": 50, "GYRO": 50, "PPG": 25, "EDA": 1, "HR_IBI": 1, "SKIN_TEMP": 1 / 60, "ECG": 500}


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p / 100 * len(xs)))] if xs else None


def analyze_stream(name, path):
    rows = list(csv.DictReader(open(path)))
    if not rows:
        return {"samples": 0}
    win = [int(r.get("window") or 0) for r in rows]  # sessions before v0.3 have no window column
    src = [int(r["source_ts_ns"]) for r in rows]
    utc = [int(r["utc_ns"]) for r in rows]
    mono = [int(r["monotonic_ns"]) for r in rows]
    seq = [int(r["sequence"]) for r in rows]
    # Intervals are only meaningful inside one capture window; silence between windows is the schedule.
    dts, seq_breaks, active_ns, w_first = [], 0, 0, src[0]
    for i in range(1, len(rows)):
        if win[i] != win[i - 1]:
            active_ns += src[i - 1] - w_first
            w_first = src[i]
            continue
        dts.append((src[i] - src[i - 1]) / 1e6)
        seq_breaks += seq[i] != seq[i - 1] + 1
    active_ns += src[-1] - w_first
    pos = [d for d in dts if d > 0]
    med = statistics.median(pos) if pos else 0
    gaps = [d for d in pos if med and d > 3 * med]
    dur = active_ns / 1e9
    nominal = NOMINAL_HZ.get(name)
    gap_time = sum(d - med for d in gaps) / 1000
    off = [(u - m) / 1e6 for u, m in zip(utc, mono)]
    q = [r["quality"] for r in rows if r.get("quality") not in (None, "")]
    n_win = len(set(win))
    return {
        "samples": len(rows),
        "windows": n_win,
        "active_duration_s": round(dur, 3),
        "observed_rate_hz": round((len(rows) - n_win) / dur, 3) if dur > 0 else None,
        "nominal_rate_hz": nominal,
        "dt_ms": {"median": med, "p95": pct(pos, 95), "p99": pct(pos, 99), "max": max(pos) if pos else None,
                  "sd_of_non_gap": round(statistics.pstdev([d for d in pos if d <= 3 * med]), 3) if len(pos) > 1 else 0},
        "gaps_gt_3x_median": len(gaps),
        "missing_time_ratio": round(gap_time / dur, 4) if dur > 0 else None,
        "duplicate_timestamps": sum(1 for d in dts if d == 0),
        "non_monotonic_source_ts": sum(1 for d in dts if d < 0),
        "sequence_discontinuities": seq_breaks,
        "quality_flag_counts": dict(statistics_counter(q)),
        "utc_minus_mono_range_ms": round(max(off) - min(off), 3),
        "first_utc_ns": utc[0], "last_utc_ns": utc[-1],
    }


def statistics_counter(xs):
    import collections
    return collections.Counter(xs).most_common(8)


def clock_events(path):
    ev = [json.loads(l) for l in open(path) if l.strip()]
    sync = [e for e in ev if e["event"] == "clock_sync"]
    out = {"markers": [{"session_offset_s": round(e["session_offset_ns"] / 1e9, 3), "utc_ns": e["utc_ns"]}
                       for e in ev if e["event"] == "marker"],
           "other_events": sorted({e["event"] for e in ev})}
    if len(sync) >= 2:
        x = [e["monotonic_ns"] / 1e9 for e in sync]
        y = [(e["utc_ns"] - e["monotonic_ns"]) / 1e6 for e in sync]  # ms
        mx, my = sum(x) / len(x), sum(y) / len(y)
        slope = sum((a - mx) * (b - my) for a, b in zip(x, y)) / sum((a - mx) ** 2 for a in x)
        out.update(clock_sync_points=len(sync), utc_minus_mono_range_ms=round(max(y) - min(y), 3),
                   drift_ms_per_hour=round(slope * 3600, 3))
    return out


def main():
    d = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else d.parent / "reports"
    out.mkdir(parents=True, exist_ok=True)
    man = json.load(open(d / "manifest.json"))
    streams = {k: analyze_stream(k, d / v["file"]) for k, v in man["streams"].items()}
    rep = {"session_id": man["session_id"], "status": man["status"], "streams": streams,
           "clock": clock_events(d / "events.jsonl") if (d / "events.jsonl").exists() else {}}
    live = {k: v for k, v in streams.items() if v["samples"]}
    # Overlap is computed over the high-rate streams only (1/min skin temp would shrink it to nothing).
    fast = {k: v for k, v in live.items() if (v["nominal_rate_hz"] or 0) >= 1} or live
    start = max(v["first_utc_ns"] for v in fast.values()); end = min(v["last_utc_ns"] for v in fast.values())
    rep["common_window_s"] = round((end - start) / 1e9, 3)
    (out / f"sync_validation_{man['session_id']}.json").write_text(json.dumps(rep, indent=2))
    md = [f"# Synchronization Validation - {man['session_id']}", f"status: {man['status']}, common window: {rep['common_window_s']} s", "",
          "| stream | n | windows | rate (obs/nom Hz) | dt median ms | p99 ms | max ms | gaps | missing | seq breaks | quality flags |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for k, v in streams.items():
        if not v["samples"]:
            md.append(f"| {k} | 0 ||||||||||"); continue
        d = v["dt_ms"]
        f1 = lambda x: "-" if x is None else f"{x:.1f}"
        pc = lambda x: "-" if x is None else f"{x:.1%}"
        md.append(f"| {k} | {v['samples']} | {v['windows']} | {v['observed_rate_hz']}/{(v['nominal_rate_hz'] or 0):.3g} | "
                  f"{f1(d['median'])} | {f1(d['p99'])} | {f1(d['max'])} | {v['gaps_gt_3x_median']} | {pc(v['missing_time_ratio'])} | "
                  f"{v['sequence_discontinuities']} | {v['quality_flag_counts']} |")
    c = rep["clock"]
    md += ["", f"clock sync points: {c.get('clock_sync_points', 0)}, UTC-mono range: {c.get('utc_minus_mono_range_ms')} ms, "
           f"drift: {c.get('drift_ms_per_hour')} ms/h", f"markers: {c.get('markers')}"]
    (out / f"sync_validation_{man['session_id']}.md").write_text("\n".join(md))
    print("\n".join(md))


if __name__ == "__main__":
    main()
