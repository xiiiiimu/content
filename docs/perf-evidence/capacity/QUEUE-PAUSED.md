# Capacity queue paused

As of 2026-11-09 (post PUSH reuse GREEN): **do not** auto-start PULL / HYBRID full-quota reuse, ladder, or additional long runs.

## Keep

- Valid evidence already on disk under `docs/perf-evidence/` and `docs/perf-evidence/capacity/`
- SQL claim fix (`V9` + NEW/RUNNING split) and runner stderr hardening

## Do not

- Append PULL/HYBRID full-scale capacity suites in this pause
- Treat `clients=8, durationSec=60` (or any single load point) as a capacity ceiling
- Claim “highest capacity” / cross-mode full acceptance

## Honest M8 status

See `docs/m8-capacity-plan.md` §验收现状.
