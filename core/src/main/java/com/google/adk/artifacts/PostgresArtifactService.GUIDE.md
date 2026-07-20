# PostgresArtifactService Guide

## What It Does

`PostgresArtifactService` stores artifacts in PostgreSQL and can optionally mirror them to S3
and emit Kafka events. It is the runtime artifact service used by the application.

Core behavior:
- Always writes artifact bytes and metadata to Postgres.
- Uploads bytes to S3 when `S3_BUCKET` is set.
- Publishes a Kafka event when `use_kafka=true` and `kafka_topic` is set.

## Save Flow (Write Path)

1. Extract bytes and MIME type from the `Part`.
2. Save to Postgres (data + metadata).
3. If S3 is enabled, upload bytes to `s3://<bucket>/<key>`.
4. If Kafka is enabled, publish a JSON event describing the artifact.

## Configuration

### Postgres (required)

Environment variables:
- `DBURL`
- `DBUSER`
- `DBPASSWORD`

### S3 (optional)

Environment variables:
- `S3_BUCKET` (enables S3 upload)
- `S3_REGION` (recommended)
- `S3_ENDPOINT` (optional, for S3-compatible storage)
- `S3_PATH_STYLE` (`true` for path-style access)

### Kafka (optional)

Properties (via `PropertiesHelper`):
- `use_kafka=true`
- `kafka_topic=<topic-name>`

## S3 Object Key Format

```
<appName>/<userId>/<sessionId>/<filename>/<version>
```

If `filename` starts with `user:`, the key becomes:

```
<appName>/<userId>/user/<filename>/<version>
```

## Kafka Event Payload

Published fields:
- `type` = `artifact`
- `appName`, `userId`, `sessionId`, `filename`, `version`
- `mimeType`
- `fileUri` (S3 URI)
- `sizeBytes`
- `metadata` (only included when non-null)

## Common Checks

- If S3 upload fails, the Postgres write still succeeds (fail-open).
- If Kafka is disabled or topic is missing, no event is published.
- Missing `S3_REGION` or invalid AWS credentials will cause S3 failures.

## Related Files

- `PostgresArtifactService.java`
- `PostgresArtifactStore.java`
- `S3ArtifactService.java`
