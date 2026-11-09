# MinIO acceptance progress — Docker Desktop crash

Time: 2026-11-09T06:44:44.9321784+08:00

## Crash
Docker Desktop dialog: unexpected error, needs to close.
Detail: service containerd failed: signal: bus error (core dumped)
Trigger: source-build of content-minio (golang:1.24-bookworm compile) hit I/O / read-only FS errors, then daemon 500s.

## Do NOT
- Reset Docker Desktop to factory defaults (would wipe unrelated volumes/images)
- Reuse video_minio_data
- Treat pgsty/silo as official MinIO close-out

## Inventory (read-only, completed before crash)
- Image: pgsty/silo:RELEASE.2026-09-16T00-00-00Z
  - Id: sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46
  - RepoDigest: pgsty/silo@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46
  - Source: https://github.com/pgsty/silo (Silo fork, AGPL; Copyright MinIO + PGSTY modifications)
- Container: video-minio (0f977ebcd1fc) Exited; mount video_minio_data — not reused
- Official pulls still denied: docker.io/minio/minio, quay.io/minio/minio 401

## Silo probe (Content-owned volume content_minio_silo_probe only)
- Started with server /data --console-address :9001
- /minio/health/ready -> 200
- Unsigned PUT/bucket -> 403 (auth required)
- Stopped; volume retained; not adopted as official MinIO

## Code prepared (pending Docker recovery to finish)
- HttpS3ObjectStore: AWS SigV4 path-style (needed for real MinIO)
- infra/docker/minio/{Dockerfile,build.ps1,SOURCE.md}
- compose media image -> content-minio:RELEASE.2025-04-22T22-12-26Z
- Source pin: tag RELEASE.2025-04-22T22-12-26Z commit 0d7408fc9969caf07de6a8c3a84f9fbb10a6739e
- First docker build FAILED (I/O + read-only /tmp in builder), then Desktop crashed

## Resolved after relaunch (not factory reset)

See `ACCEPTANCE.md`. Image rebuilt (entrypoint CRLF fixed by skipping upstream script), media profile healthy on `content_minio-data`, SigV4 probe ok, `MediaMinioIT` GREEN.
