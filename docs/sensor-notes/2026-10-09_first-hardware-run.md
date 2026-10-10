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

## Update 2026-10-10: worn 5-min session at 100 Hz (BASELINE_REST_V2, T001_20261010_162734_BASELINE_006)
- ACC / GYRO / rotation / magnetometer: 99.70 Hz, max dt 16 ms, 0 gaps, 0 sequence breaks. Barometer 9.97 Hz, light 7.1 Hz, PPG 24.93 Hz.
- EDA: 0.993 Hz, all 258 samples status 0 (valid), 0.01-0.96 uS. HR/IBI: 577 per-beat rows, 456 with status 1 (valid), 120 with status -10; mean valid IBI 649 ms.
- HR/IBI and EDA both stopped delivering at +259.5 s with no error while ACC/PPG continued (also at +269 s in session 003): the Samsung trackers stall silently when the watch is still and the screen is off. Mitigation implemented: watchdog that restarts a tracker after 10-15 s of silence and logs `tracker_silent` / `tracker_restart`.
- Phone transfer: Watch -> phone -> PC, 6/6 sessions (28 MB) checksum-verified end to end. Bluetooth Data Layer without compression ~20 KB/s; with gzip ~5x faster.
- The "phone" used as gateway reports model SM-N975F, Android 12.

## Finding: Samsung trackers need the display on (2026-10-10, sessions 007-009, same wearer, 5 min, 100 Hz)

| Condition | PPG | EDA | HR/IBI | `tracker_silent` events |
|---|---|---|---|---|
| Screen off (007) | 12.99 Hz, 48 % missing | 0.34 Hz | 0.37 Hz, ends at 208 s | 45 |
| Screen off, restart cap hit (008) | 9.67 Hz, 61 % missing | 0.31 Hz | ends at 102 s | 45 |
| **Screen kept on (009)** | **24.94 Hz, 0 % missing** | **0.996 Hz, all status 0** | 423 beats to 300 s | **0** |

- With the screen off the Health Platform pauses HR/EDA/PPG in bursts (10-15 s of data, then silence). The watchdog restarts the tracker and data returns each time, but only briefly, so restarts cannot fully compensate.
- A `SCREEN_DIM_WAKE_LOCK` from the foreground service did **not** keep the display on (Wear OS 6): `screen_interactive` stayed 0. The activity flag `FLAG_KEEP_SCREEN_ON` did.
- Consequence: for physiology, record with the recording screen on ("Keep screen on" in the Schedule screen; costs battery). Motion/environment streams (ACC, GYRO, MAG, BARO, light, steps, GPS) are unaffected by the display and stay clean with the screen off.
- Capture-window design: windows should be short enough that holding the screen on is affordable; the display is released between windows.
