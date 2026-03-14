# Scalax Development Guide

## Project Structure

```
/src/
  /main/scala/           - Application code
    /api/                - HTTP routes (RateLimitApi, IdempotencyApi, DashboardApi)
    /core/               - Rate limit engine, token bucket, idempotency logic
    /storage/            - DynamoDB implementations (rate limit, idempotency)
    /events/             - Kinesis event publishing, EventPublisher
    /metrics/            - CloudWatch metrics
    /config/             - PureConfig application config
    /resilience/         - Circuit breaker, retry, graceful degradation
    /security/           - API key auth, SecretsManager
  /test/scala/           - Unit and integration tests
    /integration/        - DynamoDB, Kinesis, HTTP API (TestContainers/LocalStack)
/loadSim/                - Load simulation subproject (sbt loadSim/run)
/terraform/              - AWS infra (DynamoDB, Kinesis, ECS Fargate, ALB)
/docs/                   - API reference, architecture
/scripts/                - load-test.sh (k6)
/localstack-init/        - Init scripts for LocalStack (init-aws.sh, keyra-entrypoint.sh)
localstack.Dockerfile    - Builds LocalStack image with keyra init; used by docker-compose
```

## Technology Stack

- **Scala 3.7.4** — Core language
- **SBT** — Build tool
- **Cats Effect 3.6** — Functional effects, Resource management
- **HTTP4s 0.23** — HTTP server/client
- **Circe** — JSON serialization
- **PureConfig** — Configuration
- **AWS SDK 2.x** — DynamoDB, Kinesis, CloudWatch, Secrets Manager
- **LocalStack** — Local AWS emulation (DynamoDB, Kinesis)
- **Docker** — LocalStack, containerized app
- **Terraform** — AWS infrastructure (when deployed)
- **Testing:** ScalaTest, Cats Effect Testing, TestContainers (LocalStack)

## Local Development

See `README.md` for full setup. Quick start:

```bash
# Start LocalStack (DynamoDB, Kinesis)
make start

# Run application (requires LocalStack)
make run

# Or combined: start LocalStack + run app
make dev
```

- **API URL:** http://localhost:8080
- **Dashboard:** http://localhost:8080/dashboard
- **Health:** http://localhost:8080/health

## Testing

Run from repo root:

```bash
# Unit tests only (no Docker)
make test
# or: sbt test

# Unit tests only, excluding integration (faster)
sbt unitTest

# Integration tests (uses TestContainers, starts LocalStack)
make integration
# or: sbt "testOnly *IntegrationSpec"

# All tests (unit + integration)
make all-tests

# Load simulation (requires server running)
make dev   # in one terminal
sbt "loadSim/run --scenario normal"
sbt "loadSim/run --scenario burst"
sbt "loadSim/run --scenario idempotency"
sbt "loadSim/run --scenario realistic"

# k6 load tests (optional)
./scripts/load-test.sh dev baseline
./scripts/load-test.sh dev quick
```

## Code Quality

```bash
# Scala format (if scalafmt configured)
sbt scalafmtCheck
sbt scalafmt

# Unit tests
make test

# Integration tests
make integration

# Terraform validation
make tf-validate
```

## Build Commands

```bash
make start          # Start LocalStack only
make stop           # Stop LocalStack
make run            # Run app with sbt (requires LocalStack)
make dev            # Start LocalStack + run app
make test           # Unit tests
make integration    # Integration tests
make all-tests      # Unit + integration
make clean          # Stop containers, clean target, .bsp, .metals

make docker-build   # Build Docker image
make docker-run     # Full stack (LocalStack + app)
make docker-stop    # Stop Docker stack

make curl-check     # Test POST /v1/ratelimit/check
make curl-health    # Test GET /health
make curl-ready     # Test GET /ready
make tf-validate    # Validate Terraform
```

## Coding Standards

- **Scala:** snake_case (methods/variables), PascalCase (types). Use Scala 3 syntax (indentation, given/using).
- **Documentation:** Doc comments on public APIs; explain design decisions in `/docs`.
- **Line endings:** LF (Unix)
- **Resource management:** Use Cats Effect `Resource[F, _]` for AWS clients and other resources.
- **Error handling:** Explicit error types; no silent failures. Use `Either`, `Option`, or domain errors.

## Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>
```

**Types:** feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert

**Scopes (examples):** api, core, storage, events, metrics, config, resilience, terraform, docs, deps

**Examples:**

- `feat(api): add dashboard SSE endpoint for live stats`
- `fix(storage): resolve OCC retry exhaustion under contention`
- `docs(api): document idempotency TTL semantics`
- `chore(deps): bump AWS SDK to 2.38.7`

**Breaking changes:** Add `!` after type/scope or use `BREAKING CHANGE:` in footer.

## API Key Auth (Local)

Use `test-api-key`, `admin-api-key`, or `free-api-key` for local development. Configure in `application.conf` or env vars.

## Common Gotchas

- **LocalStack readiness:** `make start` waits for DynamoDB; if it times out, run `make stop` then `make start` again.
- **Integration tests:** Require Docker (TestContainers). CI runs them with LocalStack; locally use `sbt "testOnly *IntegrationSpec"`.
- **In-memory vs DynamoDB:** Local dev with LocalStack uses DynamoDB; without LocalStack, some paths may fall back to in-memory (check config).
- **OCC retries:** Rate limit engine retries up to 10 times on conditional write conflicts; after exhaustion, request is rejected.
- **Fire-and-forget events:** Kinesis publishing is async; failures are logged but do not affect API responses.
- **Dashboard SSE:** `GET /v1/ratelimit/dashboard/stats` streams rate-limit events; keep connection open for live updates.

## Key Documentation

- `README.md` — Overview, quick start, API examples, architecture
- `docs/API.md` — Complete API reference
- `docs/ARCHITECTURE.md` — Design decisions, data models, trade-offs
- `TESTING_DASHBOARD.md` — Dashboard testing, load sim scenarios, troubleshooting
