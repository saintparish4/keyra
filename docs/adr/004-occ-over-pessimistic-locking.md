# ADR-004: Optimistic Concurrency Control Over Pessimistic Locking

**Context:** Multiple stateless service instances must atomically read-modify-write the same token-bucket state in DynamoDB without over-issuing tokens. We needed a concurrency control strategy.

## Decision

Use optimistic concurrency control (OCC) via DynamoDB conditional writes on a `version` field. No distributed lock service.

## How It Works

1. **Read:** `GetItem` with `consistentRead(true)` fetches the current bucket state, including `version = N`.
2. **Compute:** Refill tokens by elapsed time, then deduct the request cost.
3. **Write:** `PutItem` with condition `version = N` (or `attribute_not_exists(pk)` for first write). If the condition succeeds, the write is atomic and the version becomes `N+1`.
4. **Conflict:** If another instance wrote `version = N+1` between our read and write, DynamoDB raises `ConditionalCheckFailedException`. We retry from step 1 with exponential backoff (25 ms base, 2× multiplier, 10% jitter), up to 10 attempts.
5. **Exhaustion:** After 10 failed retries, the request is **rejected** (HTTP 429). This is a safety choice: the system under-issues rather than over-issues.

## Why OCC

- **No lock service.** Pessimistic locking requires a distributed lock store (Redis, ZooKeeper, or a DynamoDB-based lock table) with its own failure modes: lock acquisition timeouts, lock lease expiry, deadlock detection, and a separate monitoring/alerting surface.
- **Simpler failure modes.** The only failure is `ConditionalCheckFailedException`, which is deterministic and retryable. There is no "lock held by a crashed instance" scenario.
- **Good enough under typical contention.** Rate-limit keys are usually per-user or per-API-key. With thousands of keys spread across users, the probability of two instances racing on the same key in the same millisecond is low. The common path is 2 DynamoDB round-trips with zero retries.
- **Correct by construction.** OCC cannot over-issue tokens. If two instances both read `tokens = 5` and both try to consume 3, only one write succeeds. The other retries with fresh state. In the worst case (retry exhaustion), the request is rejected — safe.

## What's Sacrificed

- **Throughput under hot-key contention.** With 50 concurrent writers on a single key, OCC retries dominate: measured 4–8 RPS per key with p99 latency of ~13 seconds. Each retry costs two more DynamoDB round-trips (re-read + re-write). Worst case: 10 retries × 2 round-trips = 20 DynamoDB operations per request.
- **Tail latency.** Even at moderate contention (5–10 concurrent writers on one key), the retry loop introduces variable latency. p50 may be fine (5–20 ms) but p99 can spike.
- **DynamoDB cost.** Failed conditional writes are still billed as write capacity units. Under contention, the effective cost per successful rate-limit check increases linearly with retry count.

## Contention Ceiling

The current implementation retries up to 10 times with `RetryPolicy.dynamoDB` (25 ms base delay, 2× backoff, max 3 seconds, 10% jitter). After 10 retries (~6 seconds of delay), the request is rejected.

This ceiling means: for any single key, sustained concurrent write throughput is bounded by DynamoDB single-item write throughput divided by average retries. In practice this is ~10–50 successful writes/second per key depending on contention level and DynamoDB response time.

## Alternatives Considered

- **DynamoDB Transactions (`TransactWriteItems`).** Provides atomicity but at 2× the write cost and with a 25-item limit per transaction. Overkill for a single-item read-modify-write.
- **Single-round-trip `UpdateItem` expression.** `SET tokens = tokens - :cost IF tokens >= :cost` eliminates the read round-trip. This halves DynamoDB consumption and latency under normal load but requires restructuring the refill logic into a DynamoDB expression. Tracked as a future optimisation.
- **Redis `DECR` / Lua script.** Atomic single-operation token decrement with no retry loop. Superior throughput under hot-key contention, but introduces Redis as a dependency (see ADR-001).

## When to Reconsider

- **Sustained hot-key traffic.** If a legitimate access pattern produces sustained single-key concurrency above ~50 writes/second (e.g., a global rate limit shared by all users), OCC retry exhaustion will cause frequent 429s unrelated to actual rate-limit quota. Solutions: key sharding (split one logical limit across N DynamoDB items), or the single-round-trip `UpdateItem` optimisation.
- **Cost pressure.** If DynamoDB bills become dominated by failed conditional writes, it signals contention is too high for OCC to be efficient.

## References

- `src/main/scala/storage/DynamoDBRateLimitStore.scala` — OCC retry implementation using `RetryPolicy.dynamoDB`
- `src/main/scala/resilience/RetryPolicy.scala` § `dynamoDB` preset — 10 retries, 25 ms base, 2× backoff
- `src/main/scala/storage/DynamoDBOps.scala` — `conditionalPut` helper
- `docs/ARCHITECTURE.md` § "Locks vs OCC" and "Performance characteristics"
- `README.md` § "Benchmark Results" — measured contention impact
