# Metric-filter contract verification

This record separates AWS observations from emulator tests and documentation-derived requirements.
It is not a claim that every CloudWatch Logs behavior has been tested.

| Label | Meaning |
|---|---|
| **Verified on Live AWS** | The stated scenario was executed against official AWS endpoints. Variations outside that scenario are not implied. |
| **SDK-tested against Floci** | An AWS SDK client exercised Floci. This establishes client interoperability, not an independent AWS behavior oracle. |
| **Documentation-backed** | An AWS API reference, guide or public schema supports the requirement; this label does not claim a live execution. |

## Live observation context

The stateful probes ran on 2026-09-16 in `eu-west-1`, using isolated synthetic log groups,
metric filters and one CloudFormation stack. AWS CLI version: `2.36.45`.
STS identity was checked privately and service calls used explicit official endpoints.
No credentials, account IDs, real log messages or temporary resource identifiers belong in fixtures.

Metric queries used `GetMetricStatistics`, period 60, and Sum, SampleCount, Minimum and Maximum.
The main observation ended at 08:40:45 UTC. The exact mixed-event follow-up converged through
08:56:48 UTC, including repeated reads after an initially incomplete result. The final
system-dimension probe and cleanup completed at 09:06:51 UTC.
An early empty or partial result was not treated as the final contract.

The pattern corpus is separately captured by
`tools/aws-capture/cloudwatchlogs/capture_filter_patterns.py`. Its 148 synthetic cases record
their request, AWS response or error, endpoint, timestamp and documentation reference in
`filter-pattern-aws*.json`. Replaying that opt-in script calls real AWS; normal tests consume
the recorded files without credentials or network access.
Normalized stateful observations are recorded in `metric-filter-publishing-aws.json` in the
same resource directory. The scalar-wire comparison has its own
`metric-filter-default-value-wire-aws.json` provenance record.

## Observed behavior

Every row in this table is **Verified on Live AWS** for its stated scope.

| Area | Scenario | Observation |
|---|---|---|
| Group regex quota | Five metric filters, each containing two regex expressions | Accepted. A sixth regex-bearing filter was rejected with `LimitExceededException`. The group quota counts regex-containing filters. |
| Regex replacement | Replace a regex-bearing pattern with plain text; later attempt plain-to-regex at capacity | The first change released its slot. The rejected update preserved the plain definition; deleting another regex-bearing filter allowed the update. |
| Pattern regex quota | Three expressions in one pattern | Rejected with `InvalidParameterException`; two expressions were accepted. |
| Default contribution | Three nonmatches in one request, default 7, then no further ingestion | Sum 21, SampleCount 3, Minimum 7, Maximum 7. No extra points appeared in later idle minutes. |
| Mixed batch | Pattern `ERROR`, metric value 3, default 7; `ERROR`, `INFO`, `ERROR` in one batch | Sum 13, SampleCount 3, Minimum 3, Maximum 7. Matching events did not suppress the nonmatch's default. |
| Separate batches | The same filter; hit then miss, or miss then hit, in the same event-time minute | Both orders converged to Sum 10 and SampleCount 2. |
| Late append | Default 11 observed first; later matching value 3 timestamped in the same historical minute | Sum 14 and SampleCount 2. The earlier default was not retracted. |
| Timestamp placement | Newly ingested backdated, contemporary and future-timestamped events | Metrics appeared in the event-timestamp minutes. An event timestamp before filter creation also worked when ingestion occurred after creation. |
| Extracted value | Match on a separate discriminator; metric value references `$.value`; default 7 | Missing or explicit-null value produced 7. Numeric 3 produced 3. A nonnumeric string produced no point within the completed observation window. |
| Transformation reference validation | Scalar metric-value and dimension selectors not mentioned in a JSON filter pattern; then wildcard metric value `$.values[*]` | Unmentioned scalar selectors were accepted. The wildcard metric value was rejected with `InvalidParameterException`. These four normalized validation observations are in `metric-filter-reference-validation-aws.json`; no publication or raw HTTP response was measured in this probe. |
| No default | The same extraction cases, without a default | Only valid numeric values produced points. |
| Ordinary dimensions | Configure A from `$.a` and B from `$.b`; send complete, missing-A, missing-B and missing-both events | The complete set produced its dimensioned series. The three incomplete sets produced dimensionless samples, not partial-dimension series or dropped events. |
| Default JSON type | Numeric `defaultValue: 7`, then string `defaultValue: "7"` in raw signed requests | Number: HTTP 200, empty body. String: HTTP 400 `SerializationException`, `STRING_VALUE cannot be converted to Double`. |
| Default and dimensions | Default with system-only dimensions, then with ordinary transformation dimensions | System-only dimensions were accepted. Ordinary dimensions plus a default were rejected. Acceptance is not a claim about every emitted dimension value. |
| System-dimension publication | Regular noncentralized group; pattern `ERROR`, value 3, default 7; one `ERROR` and one `INFO`; request account, region, then both system dimensions | The matching value received the requested dimensions. The pattern-nonmatch default was dimensionless. Each case accounted for exactly two samples totaling 10. Extraction-fallback defaults were not measured in this probe. |
| Dimension count | Two ordinary dimensions plus one system dimension, then plus two system dimensions | Total three accepted; total four rejected with `InvalidParameterException`. |
| Selection operators | Word-form `AND` and `OR` in `fieldSelectionCriteria` | Accepted. |
| Transformed-log flag | `true` and `false` on a newly created group whose `GetTransformer` returned an empty configuration | Both accepted and round-tripped. A subsequent account-policy read returned zero transformer policies; it was not simultaneous with the initial flag probe. This does not test transformer execution. |
| CloudFormation identity | Explicitly named metric filter; stack output `Ref` and `DescribeStackResource` | Both values were the filter name alone, not `LogGroupName|FilterName`. Generated-name variations were not independently observed here. |
| CloudFormation replacement | Rename an explicitly named filter | DELETE_IN_PROGRESS and DELETE_COMPLETE for the old filter preceded CREATE_IN_PROGRESS and CREATE_COMPLETE for the replacement. The stack completed its update. |
| CloudFormation rollback | Rename with invalid filter pattern `{` after a valid `WARN`/value `2` definition | The old filter was deleted, replacement creation failed, and rollback recreated the previous name and definition. Logs reads, changed creationTime, Ref and the restored template confirmed it; the attempted filter did not survive. |
| CloudFormation replacement policy | Install `UpdateReplacePolicy: Retain`, then rename the filter | This type still deleted the old filter before creating its replacement. No DELETE_SKIPPED event appeared, and only the replacement survived. This does not describe other resource types or stack DeletionPolicy. |
| TestMetricFilter numbering | Recorded pattern-corpus requests with matching and nonmatching messages | Matched event numbers were one-based. |
| TestMetricFilter JSON extraction | Recorded JSON-pattern matches | Public `extractedValues` was an empty object. Internal field extraction is still needed to publish metric values. |

The default-value observations contradict a once-per-minute default cap. CloudWatch aggregates
the event contributions by minute; a minute accumulator that suppresses distinct nonmatching
events is not an equivalent implementation.

The timestamp observation does not imply retroactive processing of logs that were already
ingested before a filter existed.

## Documentation-backed and unmeasured boundaries

The shared metric/subscription regex quota is **Documentation-backed** by both Put operation
references and the regex syntax guide. The stateful probe did not create a subscription destination,
so it does not establish live mixed-writer quota behavior.

The current `AWS::Logs::MetricFilter` registry schema specifies `delete_then_create`, and a
subsequent live CFN update probe confirmed that order and rollback recreation. Its normalized
events and observations are in `metric-filter-cfn-aws.json`. The probe did not fill the group
to 100 filters or test collisions with another stack's resource.

Null or duplicate system-dimension entries, every invalid dimension-value variant, full transformer
execution, centralized source metadata, crash recovery and publication-failure injection are not
covered by the live observations above.

## Sources

- [PutMetricFilter API](https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutMetricFilter.html)
- [PutSubscriptionFilter API](https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutSubscriptionFilter.html)
- [TestMetricFilter API](https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_TestMetricFilter.html)
- [Filter and pattern syntax](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/FilterAndPatternSyntax.html)
- [Metric-filter values and dimensions](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/FilterAndPatternSyntaxForMetricFilters.html)
- [Metric-filter concepts](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/MonitoringLogData.html)
- [CloudFormation MetricFilter reference](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-logs-metricfilter.html)
- [CloudFormation registry schemas](https://schema.cloudformation.us-east-1.amazonaws.com/CloudformationSchema.zip)
