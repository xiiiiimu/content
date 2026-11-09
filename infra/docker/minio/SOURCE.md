# Content MinIO image — source build record

## Why

Docker Hub `minio/minio` pull denied; Quay `quay.io/minio/minio` 401. `dl.min.io` archives are gone (410).  
Local `pgsty/silo:RELEASE.2026-09-16T00-00-00Z` is a **PGSTY fork** (AGPL, “Silo”), not official MinIO — probed only; **not** used to close M6 official MinIO.

## Source pin

| Field | Value |
| --- | --- |
| Upstream | https://github.com/minio/minio |
| Tag | `RELEASE.2025-04-22T22-12-26Z` |
| Commit | `0d7408fc9969caf07de6a8c3a84f9fbb10a6739e` |
| go.mod | `go 1.24.0` / toolchain `go1.24.2` |

## Build images (host pulls at build time; digests recorded in build log)

| Role | Image |
| --- | --- |
| Builder | `golang:1.24-bookworm` |
| Runtime | `alpine:3.20` |

No dependency on Hub MinIO base layers or `dl.min.io` binaries.

## Build

```powershell
.\infra\docker\minio\build.ps1
```

Produces: `content-minio:RELEASE.2025-04-22T22-12-26Z`

Runtime image runs `/usr/bin/minio` directly (upstream `docker-entrypoint.sh` skipped: Windows checkout CRLF breaks the Alpine shebang).

## Runtime (Content-owned volume only)

```powershell
.\infra\docker\start.ps1 -Profile media
```

Uses compose volume `minio-data` (`content_minio-data`). Never mounts `video_minio_data`.

## Pin policy / upgrade limits

- **Pinned old release on purpose.** Community MinIO server distribution is archived; Hub/Quay pulls for `minio/minio` fail; `dl.min.io` binaries return 410. This pin is a reproducible local/source-build acceptance baseline, not a rolling “latest MinIO”.
- **Do not silently retag** to a newer upstream RELEASE without a new evidence pass (SigV4 put/get, `MediaMinioIT`, recreate+hash retention on `content_minio-data`).
- **Do not substitute** Hub/Quay images, `pgsty/silo`, or other forks for closing official MinIO acceptance.
- **Upgrade path (when needed):** pick a new upstream tag + commit → rebuild `content-minio:<tag>` via `build.ps1` → update compose image pin + this SOURCE.md → re-run probes/IT → record new digests under `docs/minio-evidence/`. Data-format compatibility across MinIO majors is **not** assumed; plan a backup of `content_minio-data` before any upgrade.
- **CI does not build or run** this image; local `-Dcontent.minio.it=true` remains the official MinIO gate.
