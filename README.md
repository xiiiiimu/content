# Content Platform

Java 17, Spring Boot 3 and MyBatis modular monolith. The V2.1 system design and the [implementation plan](docs/m0-m3-implementation-plan.md) define the M0–M9 roadmap. Milestones follow dependencies and acceptance criteria.

## Windows setup

Install JDK 17, Maven and Docker Desktop in Linux container mode. Open this directory in PowerShell. Copy the template once, then fill in the two MySQL passwords before starting.

```powershell
Copy-Item infra/docker/.env.example infra/docker/.env
.\infra\docker\start.ps1
.\infra\docker\status.ps1
```

The default startup waits for MySQL, Redis, RocketMQ NameServer and Broker health checks. It explicitly targets these four services, including when COMPOSE_PROFILES is set in the calling environment. For the native Java process, set `CONTENT_MYSQL_PASSWORD` to the same value as in the local Compose environment file. If you changed the database user or published port, also set `CONTENT_MYSQL_USER` or `CONTENT_DB_URL` respectively. Docker Compose does not export these settings to the Java process.

```powershell
$env:CONTENT_MYSQL_PASSWORD = Read-Host 'Content MySQL password' -MaskInput
mvn spring-boot:run
```

Use PowerShell 7 for `-MaskInput`. In a second terminal, check `Invoke-RestMethod http://127.0.0.1:28080/actuator/health/readiness`. Flyway migrates the database on application startup. Redis and RocketMQ are part of the default M0 environment. The application will connect to each as its corresponding feature is implemented. MinIO belongs to the optional media profile for M6.

| Service | Host loopback port | Container port | Named data volume |
| --- | ---: | ---: | --- |
| MySQL 8.4 | 23306 | 3306 | content_mysql-data |
| Redis 7.4 | 26379 | 6379 | content_redis-data |
| RocketMQ NameServer | 29876 | 9876 | — |
| RocketMQ broker | 30911 | 30911 | content_rocketmq-data |
| MinIO API and console | 29000 / 29001 | 9000 / 9001 | content_minio-data |

MinIO is retained under `profiles: [media]`, but community MinIO Server archives are unavailable (Docker Hub denied, Quay 401, `dl.min.io` 410 Gone). Do not treat any substitute as official MinIO. M6 media ITs use an S3-compatible stand-in (`adobe/s3mock`) against `content.media.*` / `ObjectStore`. Leave `CONTENT_MINIO_PASSWORD` empty for default stacks. If a maintainable official image returns later, set a password of at least eight characters and use `.\infra\docker\start.ps1 -Profile media`.

`status.ps1` and `stop.ps1` default to all Content profiles so an enabled MinIO container is included. Both accept `-Profile media` explicitly. Stop preserves containers and named volumes. These commands always use project name `content`.

The broker advertises `127.0.0.1:30911` for the native Windows client. If 30911 is occupied, update both the Compose mapping and `listenPort` in `infra/docker/broker.conf` to the same free port. Use the advertised broker port directly when configuring future RocketMQ clients, with the legacy VIP channel disabled. Provision named topics before sending messages. The other middleware host ports can be overridden in `.env`. The Java API defaults to 28080 and accepts `CONTENT_API_PORT`.

Use `.\infra\docker\stop.ps1` for daily shutdown. Services are scoped to project `content` on `content_network` and have restart policy `no`. The named volumes live in the Docker Desktop Linux Engine storage. Inspect the engine-side mountpoint with `docker volume inspect content_mysql-data --format '{{.Mountpoint}}'`. The backing virtual disk location is shown in Docker Desktop settings under Resources and Advanced. `infra/docker` contains the configuration and local environment file. Changing the MySQL environment passwords does not reset users in an already initialized volume.

## Build and tests

- `mvn -B verify` compiles, runs JUnit 5 tests and packages the app.
- `mvn -B -Pintegration verify` additionally runs migration, publication, Relay and Redis candidate integration tests using disposable Testcontainers MySQL 8.4, RocketMQ 5.3.2 and Redis 7.4 instances. PR CI runs this command. It requires Docker access, including a free host port 30921 for the isolated test Broker, and fails visibly when dependencies are unavailable.
- Add real MySQL, Redis and RocketMQ integration scenarios as M1–M3 land. Run longer fault injection and performance experiments separately.

## Code boundaries and TDD

`api` contains HTTP adapters, `domain` contains rules and use cases, `infrastructure` contains database and middleware adapters, and `worker` contains asynchronous entry points. At M0 these packages mark the intended boundaries.

For each business story, commit the RED test first. Verify that it compiles, the environment is healthy and the assertion fails for the missing behavior. Commit the smallest GREEN implementation separately and refactor in a follow-up commit when useful. Exercise transaction, retry and concurrency behavior against real services. Use the [acceptance matrix](docs/m0-m3-test-matrix.md) to determine what counts as complete.

## M1 Story 1: publish an existing text draft

`POST /api/posts/{postId}/publish` requires `Idempotency-Key` and `{"expectedVersion":4}`. The author id comes exclusively from the trusted servlet `Principal` name (positive numeric user id). Authentication integration remains outside this Story; without a Principal the endpoint returns 401. Tests supply a Principal directly, never a production author header. The endpoint publishes the stored body and rejects additional body fields.

The [Story protocol and verification record](docs/m1-story1-publish.md) define replay, conflicts and response fields. Publication locks the author mode row, increments the author sequence, and atomically saves the post, `PostPublished` Outbox event and successful request snapshot. Replays return that snapshot. Outbox rows remain `NEW`; Relay is a subsequent Story.

## M1 Story 2: Outbox Relay

The [Relay protocol and evidence](docs/m1-story2-outbox-relay.md) freeze routing, leases, retry limits, fencing, replay and durability boundaries. Relay is disabled by default. With the MySQL credentials from Windows setup already configured, explicitly provision the Topic, then enable the worker in the application process:

```powershell
.\infra\docker\create-relay-topic.ps1
$env:CONTENT_RELAY_ENABLED = 'true'
mvn spring-boot:run
```

The RocketMQ 5.3.2 remoting client uses NameServer `127.0.0.1:29876`, the advertised Broker `127.0.0.1:30911`, and no VIP channel. Topic `content-post-published-v1` uses Tag `PostPublished`. Only `SEND_OK` permits a fenced transition to `SENT`. The current single Broker uses asynchronous flush and has no replica; ACK does not promise survival of a host power loss or disk loss. Duplicates are expected after ambiguous sends and crashes; consumers need the later Inbox contract.

Defaults: 30-second lease, 3-second client send timeout, no renewal, at most five successful claims, capped exponential retry starting at one second, and a one-second scan interval. Set `content.relay.*` Spring properties for other environments. Each scan reaps exhausted expired leases and claims at most ten events sequentially. Unknown event types remain untouched until a routing contract is implemented.

Replay is a local operator command, with database credentials and OS access as its trust boundary. It accepts only DEAD supported feed events (PostPublished, FollowChanged, FeedModeChanged, PostOfflined), preserves identity/payload, resets the attempt counter, increments the token and atomically inserts OS operator/reason audit data. The command forces both scanning and the MQ producer off, regardless of environment flags. Build the executable JAR and use Java 17 (the script honors `JAVA_HOME`):

```powershell
.\infra\docker\replay-outbox.ps1 -EventId 123 -Reason 'Incident review approved replay'
```

`mvn -B -Pintegration verify` now also executes real MySQL/RocketMQ Relay tests, including independent JVM termination at committed-claim and real-ACK barriers. They require Docker access and a free local port 30921. CI runs the same suite and uploads `mysql-rocketmq-test-evidence`, containing JUnit/Failsafe reports and Relay process/Broker logs. Three non-success ACK variants are RPC response fault injections after actual Broker sends; they do not simulate physical disk or replica loss. Broker pause/stop cases use only isolated test containers.

## M2 Story 1: ordered Redis candidates

The [candidate adapter contract](docs/m2-story1-feed-candidates.md) implements ADR-001 on the separate key `feed:v2:{uid}:inbox`. Every score is zero; members are fixed-width ASCII `(UTC milliseconds, post id)` tuples. Reads use `BYLEX REV` with a snapshot upper bound, an exclusive continuation boundary, and `LIMIT 0 n`. Read and write batches are each capped at 200.

`RedisFeedCandidateStore` is a plain adapter over a caller-owned Lettuce connection with a finite command timeout. This Story adds no Redis Spring auto-configuration, Worker, HTTP endpoint, user-follow logic or MQ distribution. Writes require the immutable publication timestamp from the authoritative post. Replaying the same tuple is idempotent.

Wrong key types, nonzero scores and malformed returned members mark the index untrusted and raise an error. Connection/timeout failures also raise an error; neither condition becomes an empty successful read. Normal operations cannot clear the untrusted marker. The M2/M3 composition below supplies authoritative rebuilding and recovery readiness. CI includes the real Redis tests and uploads the Redis log alongside the existing integration evidence.

`CandidateSlice.lastReadBoundary` describes this source read only. It is not a signed client cursor or ADR-002's complete merge/visibility pagination state. An empty Redis read does not prove the entire Feed is exhausted, or that the cache is complete.

## M2 Story 2: bounded multi-source merge

The [merge contract](docs/m2-story2-merge-plan.md) defines `FeedMerger`, a request-local kernel over caller-provided ordered sources and an authoritative tuple/visibility check. `RedisCandidateSource` adapts the existing Redis store; at that Story 2 checkpoint, SQL sources and visibility/follow-epoch decisions were test fixtures. The production composition is provided in the M2/M3 section below.

Prefetch never advances consumed positions. All sources must confirm a head or normal range exhaustion before the greatest candidate can be consumed; equal heads are consumed together. Each new request reconstructs sources below shared global/consumed boundaries and safely rereads unconsumed prefetch. The structured continuation is internal, unsigned, and must not be accepted directly from an HTTP client.

Defaults: at most eight sources, page size 1..100, source batches up to 32, 256 consumed candidates/checks and 64 source reads per request. Each read/check has a two-second maximum wait; this is not a two-second total request deadline. Callers own a bounded executor, finite connection/query timeouts and resource shutdown. The Story 2 checkpoint required source coverage and production authorization; the M2/M3 composition below supplies the primary fallback and current-epoch authority checks.

`HAS_MORE` proves another candidate is known, not that it is visible; `UNKNOWN` means bounded work ended before exhaustion could be established; empty `UNKNOWN` must advance the consumed boundary. `EXHAUSTED` requires normal exhaustion of every configured source and no buffered candidates. Source/authority failures fail the whole request, preserving the caller's input continuation for retry. CI runs the real Redis/MySQL merge suite under `mvn -Pintegration verify` and retains `target/feed-merge-evidence` traces and component logs with the existing integration artifact.

## M2/M3 production pipeline (explicit opt-in)

The batch contract and validation ledger are in [docs/m2-m3-batch.md](docs/m2-m3-batch.md). M0–M9 remains the complete roadmap. This batch adds real MyBatis candidate/authority queries, relationship transactions, signed continuations, HTTP, MQ Inbox and persistent delivery, mode transitions and controlled Redis recovery. Fixtures prepare input data; they do not supply the production authorization or dispatch implementations.

`pipeline` enables the complete composition. Provide `CONTENT_MYSQL_PASSWORD`, two distinct signing secrets `CONTENT_CURSOR_KEY` and `CONTENT_IDENTITY_KEY` (at least 32 UTF-8 bytes each), and optionally `CONTENT_REDIS_URI`. Store secrets outside Git. There is no default signing secret. Existing authors and drafts remain prerequisites; account registration, login and draft creation are outside this batch.

Before starting workers for a new or lost Redis index, build the executable JAR and run the controlled bootstrap:

```powershell
# Stop incompatible old writers/workers; use the upgraded binary for every writer.
.\infra\docker\feed-ops.ps1 -Action rebuild -Reason 'Initial or incident-reviewed authoritative rebuild'
java -jar target/content-0.1.0-SNAPSHOT.jar --spring.profiles.active=pipeline
```

The operator command forces all producers, consumers and scheduled workers off, including when environment flags enable them. Its trust boundary is OS execution plus database/Redis credentials; it records the OS operator and reason. Rebuild first persists maintenance, drains compatible SQL writers, fences old Redis generations, rebuilds and compares the complete retained candidate range, then publishes READY. A crash leaves primary reads active and cache readiness false; an expired recovery lease can be taken over by another reviewed rebuild. It never reconstructs only unfinished jobs: DONE events and current follow history are included. Only the owned `feed:v2:{uid}:inbox` and untrusted keys are cleared; unrelated Redis data is preserved. Local deployment is single-node Redis with `noeviction`, not Redis Cluster.

API and workers can run as separate processes. An API-only process uses `--spring.profiles.active=pipeline --content.pipeline.enabled=false --content.relay.enabled=false`; a worker process adds `--spring.main.web-application-type=none` while retaining the profile's worker flags. A Redis outage does not prevent the lazy Redis adapter from starting the primary-read API. MQ-enabled workers require the Broker/NameServer; metadata RPCs use a configurable 60-second limit (content.pipeline.metadata-timeout-millis, 1..120 seconds) and three cleaned-up startup attempts; this accommodates the observed Windows SDK connection setup and is independent of Relay sends or task leases. The Broker's existing asynchronous flush/single-master ACK durability boundary is unchanged.

Mint a short-lived local identity token with `infra/docker/mint-identity.ps1 -UserId <id>` and send `Authorization: Bearer <token>`. The operator holding `CONTENT_IDENTITY_KEY` is the temporary issuer. The filter verifies purpose, MAC, subject, issuance/expiry and lifetime before creating Principal; an ID header cannot authenticate a request. A trusted container Principal is still accepted. This is an identity adapter, not a login system.

| API | Input/behavior |
| --- | --- |
| `PUT /api/follows/{author}` | Principal is the reader; repeated following keeps epoch/version unchanged. |
| `DELETE /api/follows/{author}` | Current relation becomes UNFOLLOWED; reads stop authorizing it immediately. |
| `GET /api/feed/following?limit=20&cursor=...` | Returns items, nextCursor, pageState and scanned; limit 1..100. |
| `PUT /api/authors/me/feed-mode` | `{mode: PUSH or PULL, expectedEpoch: integer}`; the author owns the change. |
| `POST /api/posts/{id}/offline` | `{expectedVersion: integer}`; author-only versioned state change plus Outbox. |

Cursor HMAC binds schema, reader, limit, fixed snapshot/window, returned/consumed boundaries, graph version, recovery generation and 15-minute expiry. Invalid tokens return 400, changed graph/generation returns 409 CURSOR_STALE, and source/authority failures return a retryable 503 without a new continuation. Empty UNKNOWN advances the consumed boundary. HAS_MORE confirms a remaining candidate, not necessarily a visible post. EXHAUSTED requires normal exhaustion of the configured range. Signing/expiry does not upgrade the L1 late-arrival contract to a frozen snapshot.

The primary sources select current followed authors and split by each post's immutable PUSH/PULL mode. A ready inbox is an additional source; the complete PUSH primary fallback is always retained. Old epochs remain covered across either direction of mode change, Redis loss or delayed fanout. Every candidate is checked against the primary post state and the current relation: sequence strictly above cutoff, or membership of the current epoch's fixed whitelist. History defaults to 20 (maximum 100) within a configurable 30-day window. Hiding a historical post does not refill the whitelist. A new follow epoch creates a new fixed set. No blind old-epoch inbox deletion is used.

Source batches default to 32, at most 64 source calls and 256 checks/consumptions per page, with a bounded eight-thread/16-queue executor. Page context adds six SQL calls and, when READY, up to two Redis readiness reads. SQL statements and merge callbacks have two-second limits; connection establishment/pool waits and database network reads are bounded at five seconds, with a ten-second pool initialization failure window. Redis connects lazily with a five-second initialization limit, then uses two-second command timeouts. These are individual limits, not a total request deadline. LIMIT and callback counts do not guarantee a physical database rows-examined bound; the real SQL plans/counters are retained in CI and do not constitute a capacity benchmark. Retained Redis reads have the same lower/upper time window as SQL. Low-priority trimming checks at most 100 readers and removes at most 100 expired members per reader per scan, with generation fencing; its restartable in-memory position is not a delivery watermark.

Relay routing now includes FollowChanged, FeedModeChanged and PostOfflined on the existing Topic with explicit tags, preserving PostPublished identity and payload. Inbox compares the original immutable event, hash and any existing task before acknowledging. PUSH creates a PUBLISH task, PULL records NO_FANOUT, active FollowChanged creates BACKFILL, and cancellation/control events record their durable decision. Conflicts enter `feed_event_conflicts` and are not silently acknowledged.

Delivery claims commit before Redis I/O, increment token/attempt, fix a follower-ID upper bound and use database time. Each follower/history batch is at most 100. Complete Redis success precedes a fenced progress update; only an unexpired owner can advance and renew its 30-second lease. Retries keep event/payload and use capped exponential backoff (1s to 60s), with at most five claims. The final expired claim is reaped DEAD. An old lease can have late immutable Redis side effects; it cannot commit state, and current authoritative permissions still apply. Recovery additionally fences old Redis generations. Controlled task replay preserves event content and committed scan positions, resets attempts, increments the token and audits the operator:

```powershell
.\infra\docker\feed-ops.ps1 -Action replay -TaskId 123 -Reason 'Reviewed and repaired incident cause'
```

Outbox replay remains available through the existing replay command and now accepts the supported feed event types. Unknown types remain untouched for an explicit routing contract. Task/Outbox DEAD and conflict records require operational attention; this batch does not silently discard them.

CI uses `mvn -Pintegration verify` against actual MySQL 8.4, Redis 7.4 and RocketMQ 5.3.2. Fault processes run separate Java 17 JVMs; their bounded startup watchdog is separate from leases. Evidence includes transaction/token barriers, force-kill records, real Broker redelivery, Redis generation fencing, per-page traces, query plans/counters, recovery audit timings and component logs. Test ports 30921/30922 are Broker endpoints; 30923 is an explicitly bound volatile Redis used for kill/start fault injection. Recovery timing is fixture evidence, not a production performance claim.

## M4–M6 visibility, interactions and media

Batch ledger: [docs/m4-m6-batch.md](docs/m4-m6-batch.md). Every application read path consults authoritative MySQL visibility; warmed caches cannot authorize after offline. Likes/comments are idempotent with reconciliable `post_stats`. Topic trending uses a deduped event ledger, minute buckets and rebuildable ranking (`net_events` ≠ active like counts). Media tasks use lease fencing, S3-compatible object I/O and signed GET URLs only after visibility checks.

| API | Behavior |
| --- | --- |
| `GET /api/posts/{id}` | Authoritative detail; offline → not visible. |
| `PUT/DELETE /api/posts/{id}/likes` | Idempotent like/unlike; optional `topic` for trending. |
| `POST /api/posts/{id}/comments` | Requires `Idempotency-Key`; replays return the same comment id. |
| `GET /api/posts/{id}/stats` | Reconciled like/comment counts. |
| `GET /api/topics/trending?window=1h\|24h&metric=net_events\|active_interactions` | Minute-bucket window ranking. |
| `POST /api/posts/{id}/media` | Author upload (`expectedVersion`, base64); creates `media_tasks`. |
| `POST /api/posts/{id}/media/sign` | Short-lived signed URL after DONE + visibility. |

Enable media with `content.media.enabled=true` and `content.media.s3-endpoint`. Local verify: `mvn -Pintegration verify` (124 tests) including `VisibilityAndOfflineIT`, `InteractionsAndTrendingIT` and `MediaPipelineIT`.

Remaining beyond M4–M6: M7 broad shutdown/reconciliation, M8 capacity/performance and M9 TrendGuard. L2/L3 and strict publication-time follower snapshots remain later contracts. Official MinIO image health remains blocked.
