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
- Creating a stream whose name exists in the account and Region returns `ResourceInUseException`. Invalid names, media types, retention, tags, `MaxResults` and `NextToken` return `InvalidArgumentException`, and more than 50 tags return `TagsPerResourceExceededLimitException`.

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
