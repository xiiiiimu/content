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

MinIO is retained under `profiles: [media]`. Its current official image reference is blocked by the reported registry authorization failure. Resolve this before M6 by verifying an official image address and exact version with an actual pull and health check. The existing reference is retained pending verification. M0-M3 can start with CONTENT_MINIO_PASSWORD empty. After the image is verified, set a MinIO password of at least eight characters and use `.\infra\docker\start.ps1 -Profile media` to start the default services plus MinIO. The script validates the media password before startup.

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

Replay is a local operator command, with database credentials and OS access as its trust boundary. It accepts only DEAD PostPublished, preserves identity/payload, resets the attempt counter, increments the token and atomically inserts OS operator/reason audit data. The command forces both scanning and the MQ producer off, regardless of environment flags. Build the executable JAR and use Java 17 (the script honors `JAVA_HOME`):

```powershell
.\infra\docker\replay-outbox.ps1 -EventId 123 -Reason 'Incident review approved replay'
```

`mvn -B -Pintegration verify` now also executes real MySQL/RocketMQ Relay tests, including independent JVM termination at committed-claim and real-ACK barriers. They require Docker access and a free local port 30921. CI runs the same suite and uploads `mysql-rocketmq-test-evidence`, containing JUnit/Failsafe reports and Relay process/Broker logs. Three non-success ACK variants are RPC response fault injections after actual Broker sends; they do not simulate physical disk or replica loss. Broker pause/stop cases use only isolated test containers.

## M2 Story 1: ordered Redis candidates

The [candidate adapter contract](docs/m2-story1-feed-candidates.md) implements ADR-001 on the separate key `feed:v2:{uid}:inbox`. Every score is zero; members are fixed-width ASCII `(UTC milliseconds, post id)` tuples. Reads use `BYLEX REV` with a snapshot upper bound, an exclusive continuation boundary, and `LIMIT 0 n`. Read and write batches are each capped at 200.

`RedisFeedCandidateStore` is a plain adapter over a caller-owned Lettuce connection with a finite command timeout. This Story adds no Redis Spring auto-configuration, Worker, HTTP endpoint, user-follow logic or MQ distribution. Writes require the immutable publication timestamp from the authoritative post. Replaying the same tuple is idempotent.

Wrong key types, nonzero scores and malformed returned members mark the index untrusted and raise an error. Connection/timeout failures also raise an error; neither condition becomes an empty successful read. Normal operations cannot clear the untrusted marker. Authoritative rebuilding and recovery readiness remain future work. CI includes the real Redis tests and uploads the Redis log alongside the existing integration evidence.

`CandidateSlice.lastReadBoundary` describes this source read only. It is not a signed client cursor or ADR-002's complete merge/visibility pagination state. An empty Redis read does not prove the entire Feed is exhausted, or that the cache is complete.
