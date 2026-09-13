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

`DataFormatConversionConfiguration` on the extended S3 destination converts buffered JSON records to Parquet at delivery time, typed by the stream's Glue table schema. Parquet is the only format Floci writes: the configuration is validated as AWS models it, but an enabled `OrcSerDe` is rejected at create and update time, so the rest of this section describes the Parquet path only. The Parquet file itself is written by the `floci-duck` sidecar (the same engine behind Athena, S3 Select, and CUR), so conversion needs Docker available, or a pre-started sidecar via `floci.services.duck.url`.

Each buffered record is parsed and type-checked against the Glue table's columns. The valid rows are staged as NDJSON in `floci.services.firehose.staging-bucket`, then written to the destination as one Parquet object keyed like any other delivery but ending in `.parquet`, unless `FileExtension` replaces it. `ParquetSerDe.Compression` picks the codec, SNAPPY by default.

`array`, `struct` and `map` columns convert too, nested up to 32 levels deep. The coercion is as forgiving as AWS's, all of it probed:

- Under `OpenXJsonSerDe`, a value that is not an array becomes a **one-element array**, an object bound for `array<struct<..>>` included.
- A `struct` matches members by name, dropping undeclared ones and leaving missing ones null, so `{}` is a struct of nulls rather than a null struct. Under `OpenXJsonSerDe` **an array fills the members by position**, extras ignored, and only a scalar fails the record.
- **`HiveJsonSerDe` refuses what AWS documents it refusing, and fails the record** (all probed): a non-array in an `array<T>` column ("Start of Array expected"), a non-object in a `struct` column ("Start of Object expected"), a JSON string in a numeric column ("Current token (VALUE_STRING) not numeric"), and a non-scalar in a text column ("One or more fields have incorrect format. Exception when validating field (root)"). It still coerces a number into a text column, keeps nulls inside containers, and treats missing and undeclared members as OpenX does. The same numeric rule applies to top-level columns.
- A `map` takes only an object. Its **keys are lowercased under `OpenXJsonSerDe`**, which lowercases every JSON key at any depth before the value is read, and kept as written under `HiveJsonSerDe` or `CaseInsensitive: false`. The same lowercasing shows in the JSON text a non-scalar leaves in a text column.
- Names match case-insensitively at every depth under both deserializers, unless `CaseInsensitive` is `false`, which makes columns and members alike match exactly. The primitive coercions apply inside containers as at the top level, so `[1, 2, 3]` fills an `array<string>` and `"7"` an `int` member.
- A value that cannot be coerced at any depth fails **its whole record**.

Records that cannot be converted fail **individually**, as on AWS: the rest of the batch still converts. They are delivered instead to the evaluated `ErrorOutputPrefix`, where `!{firehose:error-output-type}` resolves to `format-conversion-failed`, as one extensionless object holding an NDJSON line per record: `attemptsMade`, `arrivalTimestamp`, `lastErrorCode`, `lastErrorMessage`, `attemptEndingTimestamp`, base64 `rawData`, and a `dataCatalogTable` block. The code is `DataFormatConversion.ParseError` for a record that is not valid JSON, `DataFormatConversion.MalformedData` for one whose values do not fit the column types.

A failure of the batch as a whole is routed rather than discarded: a missing Glue table, a schema Floci cannot convert, or a sidecar failure sends every buffered record to the error output, under `DataFormatConversion.EntityNotFoundException`, `.UnsupportedSchema` and `.InternalError` respectively; those three codes are Floci's own. That routing is only as good as the write it depends on. A flush whose S3 write fails keeps its batch buffered, with or without conversion, and retries it on the next flush ahead of anything buffered since. The two destination writes of a mixed batch are not atomic either: the error output is written first, so its failure delivers nothing at all, but the reverse remains possible, where the error object lands and the Parquet write then fails; the retry then delivers the converted records and reports the failed ones a second time.

Validation matches AWS on both `CreateDeliveryStream` and `UpdateDestination`, down to the [modeled](https://docs.aws.amazon.com/firehose/latest/APIReference/API_DataFormatConversionConfiguration.html) bounds, enums and patterns, so a raw JSON client cannot store a configuration AWS would reject. Two rules are worth knowing because they constrain the destination rather than the conversion block: `CompressionFormat` must be `UNCOMPRESSED`, compression being chosen by the output serializer instead, and an explicitly specified `BufferingHints.SizeInMBs` must be at least 64. Omitting `BufferingHints` stores 128 MiB, AWS's larger default for converting streams, rather than the ordinary 5 MiB.

An omitted `Enabled` counts as enabled; `Enabled: false` stores and merges the configuration without applying those two rules. `UpdateDestination` merges member-wise, nested `SchemaConfiguration` members included, then validates the merged result, so an update carrying only `{"Enabled": false}` keeps the stored schema and formats, and one naming only a new `TableName` keeps the stored role, database and region.

### Known deviations from AWS

- **An enabled `OrcSerDe` is rejected** with `InvalidArgumentException` at create/update time. Real AWS accepts it; DuckDB cannot write ORC, and refusing early beats storing a configuration that could never deliver. A disabled one is stored and echoed with its ORC members intact.
- **The Glue table is not resolved at create or update time.** Real AWS rejects a `SchemaConfiguration` naming a database or table that does not exist, with `InvalidArgumentException` wrapping a Glue `EntityNotFoundException`. Floci accepts it and only discovers the missing table at delivery, routing that batch to the error output as `DataFormatConversion.EntityNotFoundException`. Floci performs no role-assumption check either, where AWS validates the role last.
- **Two kinds of validation are skipped**: the element constraints AWS puts on `OrcSerDe.BloomFilterColumns` and `HiveJsonSerDe.TimestampFormats` entries (the equivalent ones on `ColumnToJsonKeyMappings` are enforced), and AWS's semantic check of SerDe option values, which answers `InvalidArgumentException` ("Invalid deserializer option(s): ...") for a well-formed but unusable value.
- A malformed but non-empty `SchemaConfiguration.RoleARN` is accepted. AWS answers `InvalidParameterValueException` ("Invalid role ARN.") before its modeled rules run; Floci validates no ARNs, so it applies only the length and pattern rules, which an empty value trips exactly as on AWS.
- **Several stored members do not reach the conversion**: `HiveJsonSerDe.TimestampFormats`, `OpenXJsonSerDe.ConvertDotsInJsonKeysToUnderscores`, every `ParquetSerDe` member except `Compression`, and `SchemaConfiguration.Region`, `CatalogId` and `VersionId`, which are not resolved against the Glue store (Floci's catalog is neither region- nor version-partitioned) and only flow into the error-output metadata.
- An epoch timestamp outside the range AWS's Parquet writer can hold wraps there into an unrelated instant (probed: `999999999999` seconds arrives as the year 2092, not 33658). Floci keeps such a value as long as it is representable as a `TIMESTAMP`, and fails only a value beyond that, as its own record's `DataFormatConversion.MalformedData` rather than as a silently corrupted row.
- `binary` and `uniontype` columns are not convertible, nor is a type string Floci cannot parse or one nested past 32 levels; each routes the batch as `DataFormatConversion.UnsupportedSchema`.
- A map column given an array or a scalar fails its record with Floci's own wording; real AWS fails it with a cast error naming OpenX classes Floci does not use.
- The `HiveJsonSerDe` refusals reproduce AWS's messages up to the Jackson source location AWS appends to the numeric one (`at [Source: java.io.ByteArrayInputStream@...; line: 1, column: N]`), whose object hash changes per run. Two cells were not probed and follow the nearest measured one: a map column itself given a non-object fails like a struct ("Start of Object expected"), and a `boolean` column given a string is still coerced.
- The Parquet list element is named `element`, where AWS's writer names it `array_element`; both are shapes the LIST annotation allows.
- Error-output records report the delivery time as both `arrivalTimestamp` and `attemptEndingTimestamp` (Floci does not track per-record arrival), and omit AWS's `sequenceNumber`/`subSequenceNumber` members. An absent `ErrorOutputPrefix` writes them at the bucket root with no default time prefix, which matches AWS's object-name documentation but was not probed.
- The Parquet object carries no `Content-Encoding`, where AWS labels it `hadoop-snappy` even though the body is a plain Parquet file (probed), and its row order may differ from put order, as it does on AWS (probed).

## Record transformation

`ProcessingConfiguration` on the extended S3 destination is applied at delivery: a flushed buffer goes to the Lambda processor's function as one invocation, `Ok` records deliver the returned data, and `Dropped` records leave no trace. Any other per-record outcome goes to the error output while the rest of the batch still delivers, one NDJSON line each under the `ErrorOutputPrefix` with `!{firehose:error-output-type}` resolving to `processing-failed`.

A function that errors is retried `NumberOfRetries` more times and then fails the whole batch as `Lambda.FunctionError`; a response the function returned successfully is final however malformed it is. The transformation runs before data format conversion, so a stream configured for both converts what the function returned.

Two behaviors invert what the conversion block above does, both probed rather than assumed: `UpdateDestination` replaces this block whole instead of merging it member-wise, so an update carrying only `{"Enabled": false}` leaves no processors behind and is validated against its own content; and an omitted `Enabled` leaves the transformation **disabled**, where on the conversion block it means enabled. A stored Lambda processor is echoed with `NumberOfRetries`, `RoleArn`, `BufferSizeInMBs` and `BufferIntervalInSeconds` filled in and reordered, as AWS returns them; the two buffer values default to 1 and 60, the processor's own defaults rather than anything `BufferingHints` says.

### Known deviations from AWS

- Only the `Lambda` processor type is applied; the others are stored and echoed.
- The whole buffer is one invocation, so the processor's buffering parameters do not re-buffer and no payload size limit is enforced.
- `recordId` is a UUID rather than AWS's longer sequence token, and every timestamp is the delivery time: Floci tracks no per-record arrival.
- Neither `NumberOfRetries` nor the existence of the function a `LambdaArn` names is checked at configuration time, as on AWS. At delivery an ARN naming nothing fails as `Lambda.FunctionError`, and a `NumberOfRetries` above 100 is capped, a bound AWS does not impose so that one flush cannot occupy the flusher indefinitely.
- A null entry in `Processors` or `Parameters`, which a raw JSON client can send, is ignored rather than reported; real AWS answers `InternalFailure` there, a fault of its own not worth reproducing.

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
| `FLOCI_SERVICES_FIREHOSE_STAGING_BUCKET` | `floci-firehose-staging` | S3 bucket used to stage NDJSON batches before DuckDB writes the Parquet object |

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
