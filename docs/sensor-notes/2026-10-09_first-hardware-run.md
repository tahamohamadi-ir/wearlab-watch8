# First hardware run - Watch8 SM-L330 (2026-10-09, app 0.2.0)

Prereq discovered: Samsung SDK returns `SDK_POLICY_ERROR` for every tracker until **Developer mode** is turned on inside the *Health Platform* app (v1.7.00.05; called "Health Sensor Service" in Samsung docs): tap the title ~10x, then Developer mode > agree > On.

Session T001_20261009_205252_BASELINE_001 (5 min, seated, COMPLETED, SHA-256 of all 6 pulled files match manifest; battery 78 -> 77 %):

| Stream | Observed rate | Samples | Max dt | Notes |
|---|---|---|---|---|
| ACC | 49.86 Hz | 14978 | 22 ms | no gaps, jitter sd ~0.8 ms |
| GYRO | 49.86 Hz | 14979 | 22 ms | no gaps |
| PPG (G/IR/R) | 21.5 Hz | 6370 | 2520 ms | 96 % of intervals = 40 ms (25 Hz); 64 intervals > 200 ms (burst pauses up to 1 s+). All status flags 0. Investigate: motion/contact vs. SDK pauses |
| EDA | 0.985 Hz | 296 | 2001 ms | skin_conductance ~0.47 uS |
| HR_IBI | 0.997 Hz | 299 | 1004 ms | |
| SKIN_TEMP | 1 sample / 60 s | 4 | 60.2 s | object ~34.1-34.2 C, ambient ~35 C |

ECG (on-demand, needs finger on electrode) not tested yet.

## Update: sync validation (same day)
- Adapter bug found by `analysis/preprocessing/sync_validation.py`: callbackFlow default buffer (64) silently dropped PPG bursts (203 sequence breaks in session 001). Fixed with `buffer(UNLIMITED)`: PPG then 24.94-24.98 Hz, 0 breaks.
- Second bug: with screen off, ACC/GYRO lost ~15 % of samples (max dt 3.5 s) because the CPU suspended. Fixed with PARTIAL_WAKE_LOCK in InspectorService. Session 003 (screen mostly off): ACC/GYRO 49.95 Hz, PPG 24.98 Hz, 0 gaps, 0 seq breaks.
- Clock: 30 clock_sync points per 5 min, UTC-minus-monotonic range ~1 ms, drift ~-1 ms/h (session 003).
- ECG (on-demand): 500 Hz, lead_off=0 with finger on top button, ends by itself after ~30 s. Samples arrive in batches, so per-interval dt stats are meaningless for ECG; use observed rate.
- Open: EDA/HR_IBI sometimes deliver fewer than 1 sample/s (269-296 in 300 s); likely SDK status gating, check `status` columns.
- MARK button verified: writes `marker` events with UTC + monotonic + session offset.
