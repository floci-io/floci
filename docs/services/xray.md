# AWS X-Ray

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/TraceSegments` (SigV4 service `xray`)

Floci emulates the X-Ray tracing API. A client, the X-Ray SDK or the X-Ray daemon sends
segment documents and Floci stores them by trace, returning them exactly as they were
sent. Traces are isolated by account and Region, and kept for the same 30 day window AWS
uses.

Point any client at the emulator and the existing configuration works unchanged:

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
aws xray put-trace-segments --trace-segment-documents file://segment.json
aws xray batch-get-traces --trace-ids 1-5f3b8a0e-3c9d4d8e9b7a2c1d4e5f6a7b
```

The X-Ray daemon reads the endpoint from the standard AWS environment, so the same
variable points it at Floci. It is the real gate for this service: it fetches sampling
rules, samples, sends segments and reports telemetry on its own timers.

```bash
docker run -d --name xray-daemon -p 2000:2000/tcp -p 2000:2000/udp \
  -e AWS_ENDPOINT_URL=http://host.docker.internal:4566 \
  -e AWS_REGION=us-east-1 \
  -e AWS_ACCESS_KEY_ID=test -e AWS_SECRET_ACCESS_KEY=test \
  amazon/aws-xray-daemon -o -n us-east-1
```

Send a segment to the daemon over UDP, the way an instrumented app does:

```bash
printf '{"format": "json", "version": 1}\n%s\n' \
  '{"trace_id":"1-abcdef01-23456789abcdef0123456789","id":"0f1e2d3c4b5a6979",
    "name":"orders-api","start_time":1794038810.0,"end_time":1794038811.0}' \
  | nc -u -w1 127.0.0.1 2000
```

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `PutTraceSegments` | Stores segment documents grouped by trace and reports the ones it cannot index. |
| `BatchGetTraces` | Returns the stored segments of up to five traces, exactly as they were sent. |
| `GetTraceSummaries` | Lists the traces in a time window, filtered by the supported expression subset. |
| `GetServiceGraph` | Aggregates every trace in a window into services, calls between them and their counts. |
| `GetTraceGraph` | Answers the same graph for named traces, without a window. |
| `GetSamplingRules` | Lists the sampling rules of a Region, always including the `Default` rule AWS serves. |
| `CreateSamplingRule` | Creates a sampling rule, minting its ARN, up to five per Region. |
| `UpdateSamplingRule` | Changes the members supplied for a rule and keeps the rest, as AWS does. |
| `DeleteSamplingRule` | Deletes a rule by name or ARN; the `Default` rule cannot be deleted. |
| `GetSamplingTargets` | Answers the fixed rate and reservoir quota the daemon should sample at. |
| `PutTelemetryRecords` | Accepts the segment and connection counters the daemon reports. |
<!-- floci:actions:end -->

`PutTraceSegments` accepts up to 100 documents per call and reports the ones it refuses
under `UnprocessedTraceSegments`, per document, instead of failing the whole request.
`BatchGetTraces` takes up to 5 trace ids and answers the stored documents verbatim.

`GetTraceSummaries` takes a `StartTime`/`EndTime` window and answers one summary per trace,
with the duration, the outcome flags, the HTTP call, the services the trace touched and the
root segment's annotations. `FilterExpression` supports `service("name")`, the `ok`, `error`,
`fault` and `throttle` keywords, `duration`, `responsetime`, `http.status`, `annotation.key`,
comparisons, `AND`, `OR`, `NOT`, `!` and parentheses; anything outside that set is refused with
`InvalidRequestException` rather than ignored, so a filter never quietly returns a superset.

`GetServiceGraph` and `GetTraceGraph` answer the same shape: one node per service, one edge per
call a segment made into another, `SummaryStatistics` with ok/error/fault/throttle counts and
`DurationHistogram` in cumulative buckets. A node's `Type` is `client` when the segment has an
`http` block and no `aws` block, and is omitted otherwise.

`GetSamplingRules` always answers the `Default` rule AWS serves in every Region (priority 10000,
fixed rate 0.05, reservoir 1), so a daemon has something to sample at before any rule exists. A
Region takes five rules, the `Default` one excluded and not editable, and `GetSamplingTargets`
answers each rule's fixed rate with its reservoir size as the quota, renewed every 10 seconds.
`LastRuleModification` moves when any rule of the Region changes, so a daemon that did not name the
changed rule still refetches the set.

## Not emulated

Floci stores, serves and aggregates traces; it does not analyse them. Insights, trace retrieval
jobs, indexing rules, groups and tags are not served: their paths are not claimed by this service, so
a call to them reaches whatever else claims the path (S3's `/{bucket}` routes) rather than answering
an AWS-shaped error.

The rest of Floci is not instrumented either: the segments are those a client, an SDK or the daemon
sends. Lambda and API Gateway do not emit segments of their own, so a trace that crosses them shows
only what the caller recorded.

Sampling is answered, not enforced: the daemon is given a fixed rate and a reservoir quota, but the
quota is not decremented per report, so a client keeps sampling at the rule's rate for as long as it
runs. That is enough to exercise a trace pipeline locally and is not AWS's adaptive sampling.

The store is bounded: at most 10 000 traces per Region, oldest evicted first, so a long
running emulator does not grow without bound.
