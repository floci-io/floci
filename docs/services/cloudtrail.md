# CloudTrail

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`
**Target prefix:** `X-Amz-Target: com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101.*`

Floci emulates the AWS CloudTrail management API. Trails, their event selectors, and logging status are persisted in Floci's storage backend so you can create and manage trails and validate IaC locally. Floci does not record live API activity into trails.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateTrail` | Creates a trail and returns its ARN |
| `UpdateTrail` | Updates the settings of an existing trail |
| `DescribeTrails` | Returns the settings for one or more trails |
| `StartLogging` | Starts logging for a trail |
| `StopLogging` | Stops logging for a trail |
| `DeleteTrail` | Deletes a trail |
| `GetTrailStatus` | Returns the logging status of a trail |
| `PutEventSelectors` | Configures the event selectors for a trail |
| `LookupEvents` | - |
<!-- floci:actions:end -->

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# A trail needs a destination S3 bucket
aws s3 mb s3://my-trail-bucket

aws cloudtrail create-trail \
  --name demo-trail \
  --s3-bucket-name my-trail-bucket

aws cloudtrail start-logging --name demo-trail
aws cloudtrail get-trail-status --name demo-trail
aws cloudtrail describe-trails
```
