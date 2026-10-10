import math
import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from wearlab import features as F  # noqa: E402


class HrvTests(unittest.TestCase):
    def test_known_sdnn_rmssd(self):
        # alternating 800/900 ms: mean 850, sample sd ~ 51.3, every successive diff is 100 ms
        ibi = np.tile([800.0, 900.0], 15)
        r = F.hrv_features(ibi)
        self.assertAlmostEqual(r["ibi_mean_ms"], 850.0, places=6)
        self.assertAlmostEqual(r["rmssd_ms"], 100.0, places=6)
        self.assertAlmostEqual(r["pnn50_pct"], 100.0, places=6)
        self.assertAlmostEqual(r["sdnn_ms"], np.std(ibi, ddof=1), places=6)
        self.assertAlmostEqual(r["hr_mean_bpm"], 60000 / 850, places=6)

    def test_too_few_beats_is_nan_not_zero(self):
        r = F.hrv_features(np.full(10, 800.0))
        self.assertTrue(math.isnan(r["rmssd_ms"]))
        self.assertEqual(r["hrv_n_beats"], 10)

    def test_artifact_beat_is_rejected_and_does_not_enter_rmssd(self):
        ibi = np.full(30, 800.0)
        ibi[10] = 1700.0  # missed beat -> would add two huge differences
        r = F.hrv_features(ibi)
        self.assertEqual(r["hrv_n_rejected"], 1)
        self.assertAlmostEqual(r["rmssd_ms"], 0.0, places=6)


class PpgTests(unittest.TestCase):
    fs = 25.0

    def _t(self, secs=30):
        return np.arange(0, secs, 1 / self.fs)

    def test_clean_pulse_has_high_sqi_and_correct_rate(self):
        t = self._t()
        x = 1e6 + 5e3 * np.sin(2 * np.pi * 1.2 * t) + 1e3 * np.sin(2 * np.pi * 2.4 * t)  # 72 bpm + harmonic
        r = F.ppg_features(t, x, self.fs, device_hr_bpm=72.0)
        self.assertAlmostEqual(r["pulse_rate_bpm"], 72.0, delta=1.0)
        self.assertGreater(r["ppg_sqi"], 0.8)
        self.assertEqual(r["ppg_hr_agree"], 1.0)

    def test_noise_has_low_sqi(self):
        rng = np.random.default_rng(0)
        t = self._t()
        r = F.ppg_features(t, 1e6 + rng.normal(0, 5e3, len(t)), self.fs)
        self.assertLess(r["ppg_sqi"], 0.35)

    def test_too_short_window_is_nan(self):
        t = self._t(5)
        self.assertTrue(math.isnan(F.ppg_features(t, np.sin(t), self.fs)["ppg_sqi"]))


class EdaTests(unittest.TestCase):
    def test_counts_injected_scrs_and_slope(self):
        t = np.arange(0, 60.0)
        x = 0.5 + 0.002 * t  # slow rise: 0.12 uS / min
        for c in (15, 30, 45):  # three SCR-like bumps
            x = x + 0.1 * np.exp(-0.5 * ((t - c) / 2.0) ** 2)
        r = F.eda_features(t, x)
        self.assertEqual(r["scr_count"], 3.0)
        self.assertGreater(r["scr_amp_mean_uS"], 0.05)
        self.assertAlmostEqual(r["eda_slope_uS_per_min"], 0.12, delta=0.03)

    def test_flat_signal_has_zero_scrs(self):
        r = F.eda_features(np.arange(60.0), np.full(60, 0.7))
        self.assertEqual(r["scr_count"], 0.0)
        self.assertAlmostEqual(r["eda_sd_uS"], 0.0)

    def test_too_few_samples(self):
        self.assertTrue(math.isnan(F.eda_features(np.arange(5.0), np.ones(5))["scl_median_uS"]))


class MotionTests(unittest.TestCase):
    fs = 100.0

    def test_still_wrist(self):
        n = int(self.fs * 30)
        xyz = np.tile([0.0, 0.0, 9.81], (n, 1)) + np.random.default_rng(1).normal(0, 0.005, (n, 3))
        r = F.motion_features(xyz, self.fs)
        self.assertAlmostEqual(r["acc_mag_mean"], 9.81, delta=0.02)
        self.assertLess(r["acc_dyn_sd"], 0.02)
        self.assertEqual(r["acc_still_frac"], 1.0)
        self.assertEqual(r["acc_burst_count"], 0.0)

    def test_periodic_motion_dominant_frequency(self):
        t = np.arange(0, 30, 1 / self.fs)
        z = 9.81 + 2.0 * np.sin(2 * np.pi * 3.0 * t)  # 3 Hz shake
        xyz = np.column_stack([np.zeros_like(t), np.zeros_like(t), z])
        r = F.motion_features(xyz, self.fs)
        self.assertAlmostEqual(r["acc_dom_freq_hz"], 3.0, delta=0.3)
        self.assertGreater(r["acc_dyn_sd"], 1.0)
        self.assertEqual(r["acc_still_frac"], 0.0)

    def test_tilt_identity_quaternion_is_flat(self):
        r = F.tilt_features(np.tile([0, 0, 0, 1.0], (50, 1)))
        self.assertAlmostEqual(r["roll_mean_deg"], 0.0)
        self.assertAlmostEqual(r["pitch_mean_deg"], 0.0)

    def test_tilt_90_degrees_about_x(self):
        s = math.sin(math.pi / 4)
        r = F.tilt_features(np.tile([s, 0, 0, s], (50, 1)))
        self.assertAlmostEqual(r["roll_mean_deg"], 90.0, places=3)


class SkinTests(unittest.TestCase):
    def test_slope(self):
        t = np.array([0, 60, 120, 180.0])
        r = F.skin_features(t, 34 + 0.1 * np.arange(4), np.full(4, 30.0))
        self.assertAlmostEqual(r["skin_temp_slope_c_per_min"], 0.1, places=6)
        self.assertEqual(r["skin_n"], 4)


if __name__ == "__main__":
    unittest.main()
