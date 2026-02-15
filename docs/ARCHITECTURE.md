## Architecture Overview

**Note:** This document describes the intended architecture. AWS deployment has not been tested. Currently only local development with docker-compose (LocalStack) is validated.

### High-Level Design

```
┌─────────────────────────────────────────────────────────────┐
│                        API Clients                           │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Application Load Balancer                 │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Rate Limiter Service (ECS/Lambda)               │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  HTTP API    │  │  Rate Limit  │  │ Idempotency  │      │
│  │  (HTTP4s)    │  │  Engine      │  │   Store      │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
         │                    │                    │
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────────┐  ┌──────────────────┐  ┌─────────────────┐
│   CloudWatch    │  │    DynamoDB      │  │    Kinesis      │
│   (Metrics)     │  │  (State Store)   │  │  (Events)       │
└─────────────────┘  └──────────────────┘  └─────────────────┘
                                                    │
                                                    ▼
                                          ┌──────────────────┐
                                          │  Data Analytics  │
                                          │  (S3 + Athena)   │
                                          └──────────────────┘
```

---

### Design doc

#### Components

| Component | Role |
|-----------|------|
| **HTTP API (HTTP4s)** | REST endpoints: rate limit check, idempotency check/complete, health, ready, dashboard. Auth via API key; tier selects rate-limit profile. |
| **Rate Limit Engine** | Token-bucket logic: get state from DynamoDB, refill by elapsed time, consume tokens, write back with OCC (version-based conditional write). |
| **Idempotency Store** | First-writer-wins: conditional create (`attribute_not_exists(pk)` or `status = Failed`), then complete via conditional update (`status = Pending`). |
| **DynamoDB** | Two tables: rate-limit state (per key) and idempotency records. All reads use **consistent read** for correct, up-to-date state. |
| **Kinesis** | Event stream for rate-limit and idempotency events. Publishing is **fire-and-forget**; failures are logged and do not affect the API response. |
| **CloudWatch** | Metrics (allowed/blocked, latency, circuit breaker state) and optional dashboards when deployed to AWS. |

#### Data models (DynamoDB)

**Rate limit table**

| Attribute | Type | Description |
|-----------|------|-------------|
| `pk` | S (partition key) | `"ratelimit#<key>"` — one item per rate-limit key. |
| `tokens` | N | Current token count (double stored as string). |
| `lastRefillMs` | N | Last refill timestamp (milliseconds, from Clock.realTime; used for elapsed-time refill). |
| `version` | N | Incremented on every successful write; used for OCC. |
| `ttl` | N | Expiration time (epoch seconds) for DynamoDB TTL cleanup. |

**Idempotency table**

| Attribute | Type | Description |
|-----------|------|-------------|
| `pk` | S (partition key) | `"idempotency#<key>"` — one item per idempotency key. |
| `clientId` | S | Client (e.g. API key) that created the record. |
| `status` | S | `Pending` \| `Completed` \| `Failed`. |
| `response` | S | JSON-encoded stored response (when `Completed`). |
| `createdAt` | N | Creation time (epoch ms). |
| `updatedAt` | N | Last update time (epoch ms). |
| `version` | N | Version for conditional updates. |
| `ttl` | N | Expiration (epoch seconds) for DynamoDB TTL cleanup. |

#### Main flows

**Rate limit check path**

1. Client sends `POST /v1/ratelimit/check` with `key`, `cost`, optional `profile`.
2. API resolves rate-limit profile from client tier (or request).
3. **GetItem** (consistent read) on rate-limit table for `pk = "ratelimit#<key>"`; if missing, treat as full bucket.
4. Compute refill: `elapsed_sec = (now_ms - lastRefillMs) / 1000`, `tokens_to_add = elapsed_sec * refillRate`, `refilled = min(capacity, current_tokens + tokens_to_add)`.
5. If `refilled >= cost`: **PutItem** with new tokens and `version + 1`, condition `version = :expectedVersion` (or `attribute_not_exists(pk)` for first write).
6. On `ConditionalCheckFailedException`: retry with 1ms delay, up to 10 times; then **reject** (safe: no over-issue).
7. Publish rate-limit event to Kinesis (fire-and-forget); return 200 (allowed) or 429 (rejected).

**Idempotent POST path**

1. Client sends `POST /v1/idempotency/check` with `idempotencyKey`, optional `ttl`.
2. **PutItem** with condition `attribute_not_exists(pk) OR status = :failed`. If condition succeeds → **New** (client should perform work).
3. If condition fails: **GetItem** (consistent read) for existing record. Return **InProgress** (status `Pending`), **Duplicate** (status `Completed`, with stored response), or **New** (status `Failed`, allow retry).
4. After client completes work: `POST /v1/idempotency/:key/complete` with response body. **UpdateItem** with condition `status = :pending`; set `status = Completed`, store response. Duplicate complete calls fail the condition and do not overwrite.

**Event streaming path**

1. After a rate-limit decision or idempotency check/complete, the API builds an event (e.g. `RateLimitChecked`, `IdempotencyChecked`).
2. Event is published to Kinesis (`PutRecord` or `PutRecords`) in a **fire-and-forget** manner (e.g. spawned fiber); the HTTP response is not delayed by Kinesis.
3. Failures (throttling, network) are logged; no retry to the client and no back-pressure on the request path. Consumers (e.g. S3/Athena, real-time dashboards) read from the stream independently.

---

### Design alternatives

**Redis vs DynamoDB**

- **Redis:** Lower latency and natural fit for counters/windows, but adds a separate stateful service, persistence and failover configuration, and (typically) single-region concerns. Good when latency is critical and the team already operates Redis.
- **DynamoDB (chosen):** Single AWS-consistent stack, built-in durability and auto-scaling, TTL for automatic cleanup of rate-limit and idempotency rows, and **strong consistency** via consistent read. Slightly higher latency than Redis (~5–10 ms vs ~1 ms) is acceptable for rate limiting. Avoids operating a separate cache/store.

**Locks vs OCC**

- **Pessimistic locking:** Would require a distributed lock store (e.g. Redis, DynamoDB-based lock table) and lock lifecycle (acquire, extend, release). Adds complexity, deadlock/lease handling, and another failure mode.
- **OCC (chosen):** Uses DynamoDB conditional writes only: no separate lock service. Under low-to-moderate contention (typical for per-key rate limits), retries (1 ms delay, max 10) usually succeed. Under high contention we **reject** after max retries to preserve correctness (no over-issuing). Simpler and good enough for the target workload.

---

### Failure scenarios and trade-offs

**Eventual vs strong consistency**

- **Choice:** All DynamoDB reads in this service use **consistent read** (`GetItem` with `consistentRead(true)`). That gives read-your-writes and accurate token counts / idempotency state without replica lag.
- **Trade-off:** Consistent reads consume more capacity and have slightly higher latency than eventually consistent reads; we accept that for correctness.

**Over-issuing tokens under races**

- **Behavior:** Two instances can read the same state, both compute refill and consume; only one PutItem (with version condition) succeeds. The other retries with a fresh read. After **max 10 retries** we **reject** the request.
- **Result:** We may **under-issue** (reject when a retry could have succeeded) but we do **not** over-issue; the rate limit is enforced safely.

**Partition hot-spotting (single key = one partition)**

- **DynamoDB:** Partition key = `"ratelimit#<key>"` or `"idempotency#<key>"`. One logical key maps to one partition. Throughput for that partition is limited by DynamoDB’s partition capacity.
- **Impact:** A single very hot key (e.g. one user or one shared API key) will hit one partition and can throttle. Mitigations: use capacity modes or on-demand billing, spread load with multiple keys where possible, and monitor throttles and backoff/retries.
