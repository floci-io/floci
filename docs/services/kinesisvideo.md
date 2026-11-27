# Kinesis Video Streams

Floci implements the REST JSON control plane of Amazon Kinesis Video Streams for the stream lifecycle: creating, describing, listing and deleting video streams. Requests are signed for `kinesisvideo` and served at the same endpoint as every other service.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateStream` | Creates a video stream and returns its ARN. |
| `DeleteStream` | Deletes a stream by ARN, checking `CurrentVersion` when it is given. |
| `DescribeStream` | Returns a stream's `StreamInfo`, by `StreamName` or `StreamARN`. |
| `ListStreams` | Lists the streams of the Region, filtered by `StreamNameCondition` and paginated. |
<!-- floci:actions:end -->

## Behavior

- A stream is `ACTIVE` as soon as `CreateStream` returns. AWS passes through `CREATING` first.
- `DeleteStream` removes the stream at once, so `DescribeStream` returns `ResourceNotFoundException` right after it. AWS passes through `DELETING` first.
- The stream ARN has the AWS shape `arn:<partition>:kinesisvideo:<region>:<account>:stream/<name>/<creation time in milliseconds>`. An ARN of a deleted stream does not match a stream later created with the same name.
- Without `KmsKeyId`, `StreamInfo.KmsKeyId` is the ARN of the AWS managed key alias `alias/aws/kinesisvideo`.
- `DataRetentionInHours` defaults to `0`, which AWS reads as no retention.
- `Version` is generated at creation. `DeleteStream` returns `VersionMismatchException` when `CurrentVersion` differs from it.
- Creating a stream whose name exists in the account and Region returns `ResourceInUseException`.
- Requests are checked by `RequestConstraintCheck` against the member types, required members, lengths, patterns and enum values generated from botocore's Kinesis Video model, such as the `StreamName` and `DeviceName` pattern (`[a-zA-Z0-9_.-]+`), the `MediaType` pattern, the `Version` pattern of `CurrentVersion`, the `ResourceARN` pattern of `StreamARN`, the 1 to 10000 range of `MaxResults` and the `BEGINS_WITH` value of `ComparisonOperator`. `tools/aws/regen_kinesisvideo_shapes.py` vendors them; `make aws-data-sync` regenerates them and `make aws-data-check` checks for drift. A member of the wrong JSON type fails with `SerializationException`, more than the 50 tags the model allows with `TagsPerResourceExceededLimitException`, and any other violation with `InvalidArgumentException`. A `DescribeStream` naming neither `StreamName` nor `StreamARN`, and a `NextToken` that `ListStreams` did not return, also fail with `InvalidArgumentException`.

## IAM

Under IAM enforcement (`floci.services.iam.enforcement-enabled`), each request is checked as its `kinesisvideo:` action, from the routes and request resources generated from botocore and the AWS Service Reference. `DescribeStream` and `DeleteStream` are checked on the stream they name, `ListStreams` acts on no resource type and is checked on `*`, and a `CreateStream` with `Tags` also needs `kinesisvideo:TagStream`, with its tags as `aws:RequestTag/<key>` and `aws:TagKeys`. A refusal is `AccessDeniedException` with the 401 status the model declares for the service.

## Not implemented

Media ingestion and playback (`GetDataEndpoint` and the `kinesis-video-media` and `kinesis-video-archived-media` data planes), signaling channels, stream updates, tagging actions, and the image generation, notification, edge and storage configuration actions are not emulated yet.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws kinesisvideo create-stream --stream-name camera-1 --media-type video/h264 --data-retention-in-hours 24
aws kinesisvideo describe-stream --stream-name camera-1
aws kinesisvideo list-streams
aws kinesisvideo delete-stream --stream-arn "$(aws kinesisvideo describe-stream --stream-name camera-1 \
    --query StreamInfo.StreamARN --output text)"
```

See the [Kinesis Video Streams API Reference](https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_Operations_Amazon_Kinesis_Video_Streams.html).
