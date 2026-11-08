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
