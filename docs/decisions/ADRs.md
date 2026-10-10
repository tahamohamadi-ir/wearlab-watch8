# Architecture Decision Records (short form)
- ADR-001 Raw data is immutable: no interpolation/smoothing/resampling in Bronze.
- ADR-002 UTC is canonical wall-clock time; monotonic clock orders samples; both stored per sample.
- ADR-003 Bronze/Silver/Gold separation.
- ADR-004 S9 is the initial gateway (Watch -> S9 -> PC), no cloud.
- ADR-005 ML begins only after signal-quality validation.
- ADR-006 No 24/7 raw collection by default.
- ADR-007 Personal baseline is a first-class feature.
- ADR-008 Capability-first: sensor availability is read from the device, never hardcoded.
- ADR-009 Single Gradle module with package-per-concern until a second consumer exists.
