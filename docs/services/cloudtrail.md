# CloudTrail

**Protocol:** JSON 1.1 (`X-Amz-Target: com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101.*`)
**Endpoint:** `POST http://localhost:4566/`

Floci emulates a CloudTrail trail's full life cycle and emits S3 data events
and selected Secrets Manager management writes to the trail's destination bucket as gzipped JSON log files at the
AWS-shaped key path:

```
${prefix}AWSLogs/${accountId}/CloudTrail/${region}/yyyy/MM/dd/${accountId}_CloudTrail_${region}_${YYYYMMDDTHHMMZ}_${rand12}.json.gz
```

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateTrail` | Creates a trail, optionally tagged via `TagsList`, and returns its ARN |
| `DescribeTrails` | Returns the settings for one or more trails |
| `DeleteTrail` | Deletes a trail |
| `UpdateTrail` | Updates the settings of an existing trail |
| `PutEventSelectors` | Configures the event selectors for a trail |
| `GetEventSelectors` | - |
| `StartLogging` | Starts logging for a trail |
| `StopLogging` | Stops logging for a trail |
| `GetTrailStatus` | Returns the logging status of a trail |
| `LookupEvents` | - |
| `ListTrails` | - |
| `AddTags` | Adds or overwrites tags on a trail, identified by ARN |
| `RemoveTags` | Removes the given tag keys from a trail, identified by ARN |
| `ListTags` | Returns the tags for one or more trails, identified by ARN |
<!-- floci:actions:end -->

Selector matching honors `ReadWriteType` (`All`, `ReadOnly`, `WriteOnly`)
and `AWS::S3::Object` data resource ARNs in any of these forms:

- `arn:aws:s3:::` — every bucket
- `arn:aws:s3:::bucket/` — every key in the bucket
- `arn:aws:s3:::bucket/prefix` — keys with the given prefix
- `arn:aws:s3:::bucket/prefix/key` — exact key match

## Encryption and CloudWatch Logs settings

`CreateTrail` and `UpdateTrail` record `KmsKeyId`, `CloudWatchLogsLogGroupArn` and `CloudWatchLogsRoleArn`, and `DescribeTrails` returns them, so a Terraform `aws_cloudtrail` with encryption or log delivery reads back what it declared. `UpdateTrail` with an empty value clears one, as on AWS. A log group without a role is refused with `InvalidCloudWatchLogsRoleArnException`, and a role without a log group with `InvalidCloudWatchLogsLogGroupArnException`. The settings are recorded only: log files are not encrypted, events are not delivered to the log group, and `KmsKeyId` is stored as sent (an alias is not resolved to the key ARN, and an unknown key is not refused).

## S3 Hook Coverage

Floci emits a CloudTrail data event whenever any of these S3 ops runs
against a bucket matched by an active trail's selectors:

- `PutObject`, `GetObject`, `HeadObject`, `DeleteObject`, `ListObjects`,
  `GetObjectAcl`

Both success and `AwsException` paths emit; the latter populates
`errorCode` / `errorMessage` (`NoSuchKey`, `NoSuchBucketPolicy`, etc.).

If `floci.services.iam.enforcement-enabled` is set, IAM-deny responses also
emit records with `errorCode: "AccessDenied"`, named after the operation that
was refused. Besides the ops above, that covers the object ACL and tagging
operations, `PutObjectRetention`, `GetObjectAttributes`, and the `POST`
operations `CreateMultipartUpload`, `CompleteMultipartUpload`, `RestoreObject`
and `SelectObjectContent`. A `POST` is named from the request's
sub-resource, since `CreateMultipartUpload` and `CompleteMultipartUpload` are
both checked as `s3:PutObject` and `SelectObjectContent` as `s3:GetObject`. A request
refused for a permission it needs besides its own, such as
`s3:PutObjectTagging` for an upload that sets tags, is recorded as the
operation. A browser `POST` upload, whose credential is in its form fields, is
recorded as `PostObject` when IAM refuses it.

## Management Write Coverage

Successful Secrets Manager `PutSecretValue` and `UpdateSecret` API calls produce management
records when a logging trail selects them. New trails include management events by default.
Basic selectors honor `IncludeManagementEvents`, `ReadWriteType`, and source exclusions.
Advanced selectors evaluate `eventCategory`, `eventSource`, `eventName`, `readOnly`, `eventType`,
and `userIdentity.arn`; unsupported fields do not match these records.

Records have `eventCategory: Management`, `managementEvent: true`, and `readOnly: false`.
Sensitive secret values are omitted. Buffers and background S3 delivery retain the originating
account, including when different accounts use the same trail name. Multi-region trails cover
regions within their own partition.

These writes also reach EventBridge's default bus in the request's account and region. See
[Secrets Manager event delivery](secrets-manager.md#secret-write-events) for prerequisites,
the secret-specific event pattern, and the limits of this coverage.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CLOUDTRAIL_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_CLOUDTRAIL_FLUSH_INTERVAL_SECONDS` | `60` | How often the writer flushes buffered records into the destination bucket. Real CloudTrail delivers with ~5 minute latency; the local default is shorter for fast feedback loops. |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test

aws s3 mb s3://my-source
aws s3 mb s3://my-trail-logs

aws cloudtrail create-trail \
    --name my-trail \
    --s3-bucket-name my-trail-logs

aws cloudtrail put-event-selectors \
    --trail-name my-trail \
    --event-selectors '[{
        "ReadWriteType": "All",
        "IncludeManagementEvents": false,
        "DataResources": [{
            "Type": "AWS::S3::Object",
            "Values": ["arn:aws:s3:::my-source/"]
        }]
    }]'

aws cloudtrail start-logging --name my-trail

# Drive some traffic
echo hello | aws s3 cp - s3://my-source/greeting.txt
aws s3 cp s3://my-source/greeting.txt -

# After ~60s, log files appear
aws s3 ls s3://my-trail-logs/AWSLogs/ --recursive
```
