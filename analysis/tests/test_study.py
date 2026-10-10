import json
import sys
import tempfile
import unittest
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from wearlab import pipeline, study  # noqa: E402
from wearlab.session_io import load_session  # noqa: E402
from test_pipeline import make_session  # noqa: E402

COLS = "event_type,phase,trial,stimulus,expected,response,outcome,rt_ms,utc_ns,monotonic_ns,session_offset_ns"


def add_study_files(d: Path, secs=60):
    ev = [json.loads(l) for l in (d / "events.jsonl").read_text().splitlines()]
    for i, (pid, typ, a, b) in enumerate([("REST_1", "REST", 0, 20), ("STROOP", "STROOP", 20, 40), ("CPT", "CPT", 40, 60)]):
        ev.append({"utc_ns": 0, "monotonic_ns": 0, "session_offset_ns": a * 10**9, "event": "phase_start", "detail": f"{pid} {typ} sec={b-a}"})
        ev.append({"utc_ns": 0, "monotonic_ns": 0, "session_offset_ns": b * 10**9, "event": "phase_stop", "detail": pid})
    ev.append({"utc_ns": 0, "monotonic_ns": 0, "session_offset_ns": 0, "event": "ema_start", "detail": "PRE"})
    ev.append({"utc_ns": 0, "monotonic_ns": 0, "session_offset_ns": 5 * 10**9, "event": "ema_end", "detail": "PRE"})
    (d / "events.jsonl").write_text("\n".join(json.dumps(e) for e in ev))
    rows = [COLS]
    for i, (stim, out, rt) in enumerate([("RED/RED/C", "CORRECT", 600), ("RED/RED/C", "CORRECT", 620), ("RED/BLUE/I", "CORRECT", 800),
                                         ("GREEN/BLUE/I", "ERROR", 700), ("BLUE/BLUE/C", "OMISSION", "")]):
        rows.append(f"STROOP,STROOP,{i+1},{stim},X,X,{out},{rt},0,0,0")
    for i, (stim, out, rt) in enumerate([("X", "HIT", 400), ("X", "HIT", 500), ("X", "OMISSION", ""), ("A", "CORRECT_REJECTION", ""),
                                         ("B", "CORRECT_REJECTION", ""), ("C", "CORRECT_REJECTION", ""), ("D", "COMMISSION", 450)]):
        rows.append(f"CPT,CPT,{i+1},{stim},T,T,{out},{rt},0,0,0")
    rows += ["EMA_PRE,EMA,1,focus,,4,ANSWERED,,0,0,0", "EMA_POST,EMA,1,focus,,7,ANSWERED,,0,0,0"]
    (d / "task_events.csv").write_text("\n".join(rows) + "\n")


class StudyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.d = make_session(self.tmp)
        add_study_files(self.d)
        self.s = load_session(self.d, verify=False)

    def test_phases_are_parsed(self):
        p = study.phases(self.s)
        self.assertEqual(sorted(p.phase), ["CPT", "EMA_PRE", "REST_1", "STROOP"])
        self.assertEqual(p[p.phase == "STROOP"].start_s.iloc[0], 20)
        self.assertEqual(p[p.phase == "EMA_PRE"].type.iloc[0], "EMA")

    def test_stroop_metrics(self):
        r = study.session_labels(self.s)
        self.assertEqual(r["stroop_trials"], 5)
        self.assertEqual(r["stroop_omissions"], 1)
        self.assertAlmostEqual(r["stroop_rt_congruent_ms"], 610.0)      # median of the correct congruent RTs
        self.assertAlmostEqual(r["stroop_rt_incongruent_ms"], 800.0)
        self.assertAlmostEqual(r["stroop_interference_ms"], 190.0)

    def test_cpt_metrics_and_dprime(self):
        r = study.session_labels(self.s)
        self.assertEqual((r["cpt_hits"], r["cpt_omissions"], r["cpt_commissions"]), (2, 1, 1))
        self.assertEqual(r["cpt_targets"], 3)
        self.assertAlmostEqual(r["cpt_rt_mean_ms"], 450.0)
        self.assertGreater(r["cpt_dprime"], 0)

    def test_ema_pre_post_delta(self):
        r = study.session_labels(self.s)
        self.assertEqual(r["ema_pre_focus"], 4.0)
        self.assertEqual(r["ema_post_focus"], 7.0)
        self.assertEqual(r["ema_delta_focus"], 3.0)

    def test_pipeline_labels_windows_by_phase(self):
        g = pipeline.run([str(self.d)], self.tmp / "out", window_s=10.0, verify=False)
        self.assertEqual(list(g.phase_type[:6]), ["REST", "REST", "STROOP", "STROOP", "CPT", "CPT"])
        self.assertTrue((g["cpt_hits"] == 2).all())  # session-level label repeated on every window

    def test_session_without_study_still_works(self):
        d2 = make_session(Path(tempfile.mkdtemp()))
        g = pipeline.run([str(d2)], self.tmp / "out2", window_s=30.0, verify=False)
        self.assertNotIn("cpt_hits", g.columns)


if __name__ == "__main__":
    unittest.main()
