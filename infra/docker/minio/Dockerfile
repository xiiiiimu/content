# Content-dedicated official MinIO image built from source (no Hub/Quay/dl.min.io binary fetch).
# Build context MUST be a checkout of github.com/minio/minio at the recorded RELEASE tag.
# Builder: golang:1.24-bookworm (pinned digest recorded in SOURCE.md at build time)
# Runtime: alpine:3.20 (pinned digest recorded in SOURCE.md at build time)

ARG GOLANG_IMAGE=golang:1.24-bookworm
ARG RUNTIME_IMAGE=alpine:3.20

FROM ${GOLANG_IMAGE} AS build
ARG RELEASE=RELEASE.2025-04-22T22-12-26Z
ARG GIT_COMMIT=unknown
WORKDIR /src
ENV GOPROXY=https://proxy.golang.org,direct
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY . .
# Avoid gen-ldflags.go (needs full git history / writable tmp). Pin version via build-args.
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -tags kqueue -trimpath \
      -ldflags "-s -w -X github.com/minio/minio/cmd.Version=${RELEASE} -X github.com/minio/minio/cmd.ReleaseTag=${RELEASE} -X github.com/minio/minio/cmd.CommitID=${GIT_COMMIT} -X github.com/minio/minio/cmd.ShortCommitID=${GIT_COMMIT} -X github.com/minio/minio/cmd.CopyrightYear=2025" \
      -o /out/minio .


FROM ${RUNTIME_IMAGE}
ARG RELEASE=unknown
ARG GIT_COMMIT=unknown
LABEL org.opencontainers.image.title="content-minio" \
      org.opencontainers.image.description="Official MinIO server built from source for Content media profile" \
      org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.version="${RELEASE}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      content.minio.release="${RELEASE}" \
      content.minio.commit="${GIT_COMMIT}"
RUN apk add --no-cache ca-certificates curl
COPY --from=build /out/minio /usr/bin/minio
RUN chmod 755 /usr/bin/minio
EXPOSE 9000
VOLUME ["/data"]
# Skip upstream docker-entrypoint.sh (CRLF from Windows checkout breaks ash shebang).
ENTRYPOINT ["/usr/bin/minio"]
CMD ["server", "/data", "--console-address", ":9001"]
