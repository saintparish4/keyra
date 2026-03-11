# Keyra Compliance Documentation — PCI DSS 4.0.1

## Audit Trail Architecture

Every denied request (rate limit rejected, idempotency key conflict, token quota
exceeded) produces an `AuditEvent` that is:

1. Logged as a structured INFO line (for CloudWatch Logs / stdout)
2. Published to Kinesis as a JSON event (partition key = clientId)
3. Delivered to S3 via Kinesis Firehose in Parquet format (Snappy compressed)
4. Retained for 7 years in S3 (90 days Standard -> Deep Archive until expiry)

### Audit Event Schema

| Field       | Type            | Description                               |
|-------------|-----------------|-------------------------------------------|
| timestamp   | ISO-8601 string | When the decision was made                |
| event_type  | string          | Always `"audit"`                          |
| request_id  | UUID string     | Unique ID for this audit record           |
| api_key     | string          | API key that made the request             |
| client_id   | string          | Client identifier                         |
| decision    | string          | `rejected`, `conflict`, `quota_exceeded`  |
| reason      | string          | Human-readable denial reason              |
| endpoint    | string (opt)    | Request endpoint if available             |
| source_ip   | string (opt)    | Client IP if available                    |
| tier        | string (opt)    | Client tier (free/basic/premium/etc)      |
| trace_id    | string (opt)    | OpenTelemetry trace ID for correlation    |

### S3 Partitioning

```
s3://bucket/audit/year=2026/month=03/day=09/part-00000.snappy.parquet
```

### Acceptance Criteria

- Every 429 response produces an audit event within 1 second
- S3 export delivers Parquet files within 5 minutes (Firehose buffer interval)
- Audit records are queryable via Athena using the Glue catalog table
- 7-year retention enforced by S3 lifecycle (2557 days)
- Events transition to Deep Archive after 90 days

## CloudWatch Insights Queries

### All denied requests in the last 24 hours

```
fields @timestamp, decision, client_id, reason, endpoint, trace_id
| filter @message like /AUDIT decision=/
| sort @timestamp desc
| limit 1000
```

### Denied requests by client

```
fields @timestamp, decision, client_id, reason
| filter @message like /AUDIT decision=/
| stats count(*) as denials by client_id
| sort denials desc
```

### Rate limit rejections by tier

```
fields @timestamp, client_id, tier
| filter @message like /AUDIT decision=rejected/
| stats count(*) as rejections by tier
| sort rejections desc
```

### Idempotency conflicts

```
fields @timestamp, client_id, reason
| filter @message like /AUDIT decision=conflict/
| sort @timestamp desc
| limit 500
```

### Token quota exceeded events by user

```
fields @timestamp, client_id, reason
| filter @message like /AUDIT decision=quota_exceeded/
| stats count(*) as exceeded by client_id
| sort exceeded desc
```

### Correlation: trace a single denied request end-to-end

```
fields @timestamp, @message
| filter trace_id = "TRACE_ID_HERE"
| sort @timestamp asc
```

### Daily denial rate trend (last 30 days)

```
fields @timestamp, decision
| filter @message like /AUDIT decision=/
| stats count(*) as denials by bin(1d) as day
| sort day asc
```

## PCI DSS 4.0.1 Evidence

| Requirement | Control                          | Evidence                         |
|-------------|----------------------------------|----------------------------------|
| 10.2.2     | Log all access to cardholder data | AuditEvent on every denied req   |
| 10.2.4     | Invalid logical access attempts  | `decision=rejected/conflict`     |
| 10.3       | Record audit trail entries        | JSON schema with all fields      |
| 10.5       | Secure audit trails               | S3 SSE-AES256 + versioning       |
| 10.7       | Retain audit trail >= 1 year     | 7-year S3 lifecycle policy       |
| 10.7.b    | Available for analysis >= 3 mo   | 90 days Standard before Glacier  |