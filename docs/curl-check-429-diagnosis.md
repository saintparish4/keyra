# curl-check 429 diagnosis

## Symptom

`make curl-check` fails: `FAIL: First request returned HTTP 429 (expected 200).`
Even the very first request with a brand-new unique key gets 429.

## Root cause

**Primary cause: PureConfig parse failure + silent fallback**

`Main.scala` uses `AppConfig.loadOrDefault[IO]`. When the primary config load (from `application.conf`) **fails**, it falls back to a hardcoded default config. That fallback was:

- **Silent** — the parse error was swallowed, so nothing in logs indicated config had failed.
- **Ignoring env vars** — it hardcoded `degradationMode = "reject-all"` and never read `DEGRADATION_MODE`.
- **Wrong defaults** — `defaultCapacity = 100` (hence `limit: 100` in the 429 response), no tier profiles, and `algorithm = "token-bucket"`.

So even with `DEGRADATION_MODE=allow-all` in `docker-compose.yml`, the running app was using the fallback config and thus always `reject-all`. Any failure (circuit breaker, timeout, etc.) then produced 429 with the degradation response.

**Why the primary load failed:** PureConfig could not map `application.conf` to the Scala case classes because several HOCON keys had no matching field:

- `resilience.timeout.idempotency-check` — missing `idempotencyCheck` in `TimeoutSettings`
- `dynamodb.max-retries` — missing `maxRetries` in `DynamoDBConfig`
- `kinesis.max-retries` — missing `maxRetries` in `KinesisConfig`
- `cache { ... }` — no `CacheConfig` / `cache` in `AppConfig`
- `metrics.flush-interval`, `metrics.high-resolution` — missing from `MetricsConfig`
- `security.authentication.header-name`, `api-key-prefix` — missing from `AuthenticationConfig`

**Secondary:** Non-unique smoke-test key (`smoke-<pid>`) could collide with persisted DynamoDB state across runs. Fixed by using `smoke-$(CURL_CHECK_ID)-<pid>`.

**Diagnostic clue:** The 429 response body showed `limit: 100` and `retryAfter: 60`. Premium tier in `application.conf` has `capacity = 1000`, so `limit: 100` proved the real config was not loaded — only the fallback (default capacity 100) was in use.

## Fixes applied

1. **`AppConfig.scala`**
   - **Align case classes with HOCON:** Added missing fields so PureConfig can parse `application.conf`:
     - `TimeoutSettings.idempotencyCheck`
     - `DynamoDBConfig.maxRetries`, `KinesisConfig.maxRetries`
     - `CacheConfig` and `AppConfig.cache`
     - `MetricsConfig.flushInterval`, `MetricsConfig.highResolution`
     - `AuthenticationConfig.headerName`, `AuthenticationConfig.apiKeyPrefix`
   - **Fallback respects env vars:** When the primary load still fails, the fallback now reads `DEGRADATION_MODE` and `RATE_LIMIT_ALGORITHM` from the environment.
   - **Log parse errors:** Fallback path logs `[WARN] AppConfig.load failed, using env-var fallback: <message>` so future parse failures are visible.

2. **`docker-compose.yml`**  
   Added `DEGRADATION_MODE=allow-all` for the rate-limiter service so local dev fails open when the circuit breaker trips. Production keeps the `reject-all` default from `application.conf`.

3. **`Makefile`**  
   `CURL_CHECK_ID` ensures a unique smoke-test key per run (`smoke-$(CURL_CHECK_ID)-<pid>`), avoiding stale DynamoDB state from earlier runs.

## Result

After these changes, the primary config load succeeds: full `application.conf` (including `rateLimit.profiles.premium`, `rateLimit.algorithm = "sliding-window"`, and `resilience.degradation-mode = ${?DEGRADATION_MODE}`) is used. The smoke test passes:

```text
Smoke test OK: 100 requests sent, X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset verified.
```

## To verify

```bash
# Restart the Docker stack to pick up code/config changes
make docker-stop && make docker-run

# Run the smoke test
make curl-check
```

## Notes

- The `/health` endpoint returns `{"status":"healthy"}` even when the circuit breaker is open for rate-limit operations — health checks bypass the circuit breaker.
- Circuit breaker config: `max-failures = 5`, `reset-timeout = 30s`. After 30s of no calls it moves to half-open (3 probe calls).
- If you see `[WARN] AppConfig.load failed, using env-var fallback:` in logs, the primary config parse failed; the fallback now at least respects `DEGRADATION_MODE` and `RATE_LIMIT_ALGORITHM`.
