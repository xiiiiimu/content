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

- `mvn -B verify` compiles, runs JUnit 5 tests and packages the app. This is the GitHub Actions command for PRs and pushes to `main`.
- `mvn -B -Pintegration verify` additionally runs the MySQL Flyway smoke test using a disposable Testcontainers instance. It requires Docker access and fails visibly when Docker is unavailable.
- Add real MySQL, Redis and RocketMQ integration scenarios as M1–M3 land. Run longer fault injection and performance experiments separately.

## Code boundaries and TDD

`api` contains HTTP adapters, `domain` contains rules and use cases, `infrastructure` contains database and middleware adapters, and `worker` contains asynchronous entry points. At M0 these packages mark the intended boundaries.

For each business story, commit the RED test first. Verify that it compiles, the environment is healthy and the assertion fails for the missing behavior. Commit the smallest GREEN implementation separately and refactor in a follow-up commit when useful. Exercise transaction, retry and concurrency behavior against real services. Use the [acceptance matrix](docs/m0-m3-test-matrix.md) to determine what counts as complete.
