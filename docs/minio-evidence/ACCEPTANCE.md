# Official MinIO acceptance (M6)

Date: 2026-11-09

## Boundary

| Store | Role |
| --- | --- |
| `adobe/s3mock:3.12.0` | Dev/IT stand-in — `MediaPipelineIT`, `MediaFfmpegIT` (unchanged) |
| `content-minio:RELEASE.2025-04-22T22-12-26Z` | Official MinIO built from source — closes M6 MinIO item |

Do not treat S3Mock green as MinIO close-out. Do not reuse `video_minio_data` or `pgsty/silo`.

S3Mock regression after SigV4: `MediaPipelineIT` **2/0/0** GREEN.

## Image

- Upstream: https://github.com/minio/minio @ `RELEASE.2025-04-22T22-12-26Z` / `0d7408fc9969caf07de6a8c3a84f9fbb10a6739e`
- Build: `infra/docker/minio/build.ps1` (golang:1.24-bookworm → alpine:3.20)
- Runtime entrypoint: `/usr/bin/minio` (upstream entrypoint skipped — Windows CRLF)
- Evidence: `image-RELEASE.2025-04-22T22-12-26Z.txt`, `SOURCE.md`

## Host media profile

- Volume: `content_minio-data` only (`video_minio_data_reused=false`)
- Health `/minio/health/ready` → 200
- Unsigned PUT → 403
- SigV4 put/get → ok (`s3-sigv4-probe.txt`)
- Client: `HttpS3ObjectStore` AWS SigV4 path-style; clients still use HMAC `/api/media/objects`

## IT

```powershell
$env:JAVA_HOME = '…jdk-17…'
mvn -B -ntp -Pintegration "-Dcontent.minio.it=true" "-Dit.test=MediaMinioIT" verify
```

Default CI skips (`@EnabledIfSystemProperty content.minio.it=true`) — skipped ≠ passed.

Result: **Tests run: 1, Failures: 0, Errors: 0** (`MediaMinioIT-run-20261109-072701.log`).

Covered: upload/download, FFmpeg derive, HMAC gateway + bad sig, TTL expiry, offline blocks sign, raw/derived retention after offline.

## Recreate retention (merge gate)

Put object → `--force-recreate` minio on `content_minio-data` → get same key → SHA-256 match.

Evidence: `retain-across-recreate.txt` (`post_get_hash_match=true`).

## Version pin

See `infra/docker/minio/SOURCE.md` § Pin policy / upgrade limits. Fixed at `RELEASE.2025-04-22T22-12-26Z`; upgrades require new build + evidence; forks/Hub substitutes not accepted.
