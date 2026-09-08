# Data Firehose

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates Amazon Data Firehose for streaming data ingestion and delivery to S3.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDeliveryStream` | Creates a new delivery stream |
| `UpdateDestination` | - |
| `StartDeliveryStreamEncryption` | - |
| `StopDeliveryStreamEncryption` | - |
| `DescribeDeliveryStream` | Returns metadata about a stream |
| `ListDeliveryStreams` | Lists all delivery streams |
| `DeleteDeliveryStream` | Deletes a delivery stream |
| `PutRecord` | Writes a single data record to the stream |
| `PutRecordBatch` | Writes multiple data records to the stream |
| `TagDeliveryStream` | - |
| `UntagDeliveryStream` | - |
| `ListTagsForDeliveryStream` | - |
<!-- floci:actions:end -->

## How it works

1. **Buffering**: Incoming records are buffered in memory.
2. **Automatic Flush**: A buffer is delivered to S3 when either trigger of the stream's `BufferingHints` fires first, mirroring AWS's buffered delivery:
    - the buffered records reach `SizeInMBs` (default 5 MiB), or
    - `IntervalInSeconds` (default 300 s) has elapsed since the first buffered record. A background flusher checks this every `floci.services.firehose.tick-interval-seconds` (default 10 s).

    An emulator-only record-count trigger is also available for local dev: set `floci.services.firehose.flush-record-count` (env: `FLOCI_SERVICES_FIREHOSE_FLUSH_RECORD_COUNT`) to flush after that many buffered records — `1` gives LocalStack-style record-at-a-time delivery. Disabled by default (`0`) so delivery timing matches real AWS.

    Buffers are also flushed on shutdown, so pending records are not lost. Deleting a delivery stream discards its pending records without flushing, matching real AWS.
3. **Format**: Buffered records are concatenated as bytes and delivered to the bucket configured in the S3 destination (`floci-firehose-results` if the stream has no destination configuration). The object is then compressed according to the destination's `CompressionFormat` and always stored with `Content-Type: application/octet-stream`, as AWS does.

    Known deviations from AWS in the delivery path:

    - **Record separator.** Floci appends a newline after any record that does not already end with one, so the delivered object is newline-delimited. Real AWS concatenates the record bytes verbatim and inserts no separator — three records of `abc` are delivered as the 9 bytes `abcabcabc`, not as 12 bytes with separators. Producers that rely on the delimiter therefore work against Floci and not against AWS, which is why AWS's own guidance is to put the delimiter inside the record.
    - **Optional destination.** Floci accepts a delivery stream with no destination configuration at all, and one whose destination omits `BucketARN` or `RoleARN`; with no destination it delivers to `floci-firehose-results`. AWS requires exactly one complete destination: it answers `InvalidArgumentException` ("Exactly one destination configuration is supported for a Firehose") when none is given, and `ValidationException` ("Member must not be null") for a destination missing `bucketARN` or `roleARN`. The default bucket is a local-development convenience with no AWS equivalent.

## Compression

`CompressionFormat` is applied to the delivered object, adding the key extension and `Content-Encoding` AWS pairs with each format:

| `CompressionFormat` | Key extension | `Content-Encoding` | Container format |
|---|---|---|---|
| `UNCOMPRESSED` | *(none)* | *(none)* | the records as they were put |
| `GZIP` | `.gz` | `gzip` | gzip stream |
| `ZIP` | `.zip` | `zip` | zip archive holding a single deflated entry named with a UUID |
| `Snappy` | `.snappy` | `snappy-java` | the [xerial snappy-java stream format](https://github.com/xerial/snappy-java#compatibility-notes) (magic `0x82 "SNAPPY" 0x00`), **not** the [official Snappy framing format](https://github.com/google/snappy/blob/main/framing_format.txt) |
| `HADOOP_SNAPPY` | `.snappy` | `hadoop-snappy` | Hadoop's block framing, what Hadoop and Spark `SnappyCodec` consumers read |

The two Snappy variants share an extension and are told apart only by `Content-Encoding`; they are mutually unreadable, so a consumer must pick the matching decoder. Note that AWS's [object name documentation](https://docs.aws.amazon.com/firehose/latest/dev/s3-object-name.html) tables `.hsnappy` for Hadoop-Snappy, but the service delivers `.snappy`; Floci follows the service.

Any other value, including differently-cased ones such as `SNAPPY` instead of `Snappy`, is rejected with `ValidationException`.

## Data format conversion

`DataFormatConversionConfiguration` on the extended S3 destination is accepted, validated, persisted, and echoed back by `DescribeDeliveryStream`. **The conversion itself, JSON to Parquet at delivery time, is not implemented yet**: a conversion-enabled stream delivers its records unconverted, under the usual key format, and creating or updating one logs a warning saying so.

Validation matches AWS on both `CreateDeliveryStream` and `UpdateDestination`, down to the [modeled](https://docs.aws.amazon.com/firehose/latest/APIReference/API_DataFormatConversionConfiguration.html) bounds, enums and patterns, so a raw JSON client cannot store a configuration AWS would reject. Two rules are worth knowing because they constrain the destination rather than the conversion block: `CompressionFormat` must be `UNCOMPRESSED`, compression being chosen by the output serializer instead, and an explicitly specified `BufferingHints.SizeInMBs` must be at least 64. Omitting `BufferingHints` stores 128 MiB, AWS's larger default for converting streams, rather than the ordinary 5 MiB.

An omitted `Enabled` counts as enabled; `Enabled: false` stores and merges the configuration without applying those two rules. `UpdateDestination` merges member-wise, nested `SchemaConfiguration` members included, then validates the merged result, so an update carrying only `{"Enabled": false}` keeps the stored schema and formats, and one naming only a new `TableName` keeps the stored role, database and region.

### Known deviations from AWS

- **An enabled `OrcSerDe` is rejected** with `InvalidArgumentException` at create/update time. Real AWS accepts it; Floci's planned conversion engine (DuckDB) cannot write ORC, and refusing early beats storing a configuration that could never deliver. A disabled one is stored and echoed with its ORC members intact.
- **The Glue table is not resolved at create or update time.** Real AWS rejects a `SchemaConfiguration` naming a database or table that does not exist, with `InvalidArgumentException` wrapping a Glue `EntityNotFoundException`; Floci accepts it. Floci performs no role-assumption check either, where AWS validates the role last.
- **Two kinds of validation are skipped**: the element constraints AWS puts on `OrcSerDe.BloomFilterColumns` and `HiveJsonSerDe.TimestampFormats` entries (the equivalent ones on `ColumnToJsonKeyMappings` are enforced), and AWS's semantic check of SerDe option values, which answers `InvalidArgumentException` ("Invalid deserializer option(s): ...") for a well-formed but unusable value.
- A malformed but non-empty `SchemaConfiguration.RoleARN` is accepted. AWS answers `InvalidParameterValueException` ("Invalid role ARN.") before its modeled rules run; Floci validates no ARNs, so it applies only the length and pattern rules, which an empty value trips exactly as on AWS.

## S3 object keys

Delivered objects are named `<evaluated prefix><streamName>-<versionId>-<yyyy-MM-dd-HH-mm-ss>-<uuid><file extension>`, matching [AWS's object name format](https://docs.aws.amazon.com/firehose/latest/dev/s3-object-name.html):

- Without a `Prefix`, the default prefix `yyyy/MM/dd/HH/` is used.
- A `Prefix` without expressions gets `yyyy/MM/dd/HH/` appended by literal concatenation — no `/` is inserted, exactly like AWS (`legacy` → `legacy2026/07/13/14/...`).
- [Custom prefix expressions](https://docs.aws.amazon.com/firehose/latest/dev/s3-prefixes.html) are evaluated: `!{timestamp:<pattern>}` (Java `DateTimeFormatter` pattern; all instances share the same instant) and `!{firehose:random-string}` (a fresh 11-character alphanumeric string per instance). When the prefix contains any expression, nothing is appended to it.
- `CustomTimeZone` is honored for all timestamps; absent or invalid time zones fall back to UTC.
- `<file extension>` comes from the `CompressionFormat` table above, unless the destination sets `FileExtension`, which **replaces** it rather than being appended to it (`GZIP` plus `FileExtension: .custom.log` yields a key ending in `.custom.log`, with a gzip body and `Content-Encoding: gzip` regardless). The empty string means "not specified" and brings the compression extension back. `FileExtension` must match `^(|\.[0-9a-z!\-_.*'()]+)$` and be at most 128 characters, or the request is rejected with `ValidationException`.

Known deviations from AWS: timestamps are evaluated at flush time instead of the oldest buffered record's arrival time; expressions AWS would reject at create time (unknown namespaces, `!{partitionKeyFromQuery:...}`/`!{partitionKeyFromLambda:...}` dynamic partitioning keys, invalid patterns) are kept literally in the key; `FileExtension` is accepted on the legacy `S3DestinationConfiguration` shape too, where AWS only defines it on the extended one.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_FIREHOSE_ENABLED` | `true` | Enable or disable the service |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a stream
aws firehose create-delivery-stream --delivery-stream-name my-stream --endpoint-url $AWS_ENDPOINT_URL

# Put a record
aws firehose put-record \
  --delivery-stream-name my-stream \
  --record '{"Data": "{\"id\": 1, \"amount\": 10.5}"}' \
  --endpoint-url $AWS_ENDPOINT_URL
```
