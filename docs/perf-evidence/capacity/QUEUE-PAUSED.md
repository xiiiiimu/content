# Auto queue paused

As of 2026-11-09: **do not** auto-start pull / ladder / stability after the in-flight PUSH single load point finishes.

Reason: full-quota re-seed per run is multi-hour; harness is being changed to **one seed per mode, reuse across load points**.

In-flight: `push-capacity-*` single-point run may complete and its evidence is kept.  
`clients=8, durationSec=60` = **one load point only**, not a capacity ceiling.
