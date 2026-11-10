# AWS Signer

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/signing-profiles` (SigV4 service `signer`)

Floci emulates the AWS Signer control plane: signing profiles, the permissions granted on them, the
platforms AWS publishes, and the signing jobs started against a profile. Profiles, permissions, and
jobs are persisted and isolated by account and region, and the `/tags/{resourceArn}` actions are
served by the shared tag routes.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `PutSigningProfile` | Creates a signing profile, or mints a new version of an existing one. |
| `GetSigningProfile` | Returns one signing profile, its platform, overrides, tags and revocation record. |
| `ListSigningProfiles` | Lists signing profiles with the platform and status filters and paged results. |
| `CancelSigningProfile` | Deletes a signing profile. |
| `RevokeSigningProfile` | Revokes a profile version, recording the reason and the effective time. |
| `AddProfilePermission` | Grants a cross-account permission on a profile and moves its revision. |
| `ListProfilePermissions` | Lists the permissions granted on a profile. |
| `RemoveProfilePermission` | Removes a permission, requiring the profile's current revision id. |
| `StartSigningJob` | Records a signing job against a profile and completes it. |
| `ListSigningJobs` | Lists signing jobs with the status, platform and revocation filters. |
| `DescribeSigningJob` | Returns one signing job with its S3 source, destination and revocation record. |
| `RevokeSignature` | Marks a signing job's signature revoked, recording the reason. |
| `GetRevocationStatus` | Reports the revocation status of the signatures named in the request. |
| `ListSigningPlatforms` | Lists the signing platforms Floci serves, with their algorithms and image formats. |
| `GetSigningPlatform` | Returns one signing platform. |
| `ListTagsForResource` | Lists the tags attached to a signing profile. |
| `TagResource` | Adds or replaces tags on a signing profile. |
| `UntagResource` | Removes selected tags from a signing profile. |
<!-- floci:actions:end -->

`PutSigningProfile` behaves as an upsert: the first call creates the profile and every later call
mints a new ten-character `profileVersion`, leaving the previous version superseded, which is what
AWS does with one exception. AWS returns the members the request omits to their defaults on the new
version; Floci keeps the values the profile already had, so a client that re-puts a profile with only
a `platformId` does not lose its certificate, overrides or validity period. Tags live on the profile
rather than on a version, and a put that sends no `tags` member leaves them untouched.

The signing platforms are `AWSLambda-SHA384-ECDSA`, `Notation-OCI-SHA384-ECDSA` and
`AWSIoTDeviceManagement-SHA256-ECDSA`. A profile naming any other `platformId` is refused with
`ValidationException`, and so is an override or signing parameter the platform does not accept: a
`SHA-256` hash on the Lambda platform, for instance.

`GetRevocationStatus` always answers with an empty `revokedEntities` list. It reports on signatures
whose bytes a caller already holds, and Floci produces none (see below), so there is nothing that
could have been revoked.

Both list operations paginate with the model's `maxResults` (1 to 25) and an opaque `nextToken`
that names the last item returned, so a page stays resumable when profiles or jobs are added
between requests.

A job carries its `clientRequestToken`, and a second `StartSigningJob` with the same token answers
the job the first one created instead of starting another, so a client that retries after a timeout
does not duplicate work.

A profile that sets no `signatureValidityPeriod` gets the period Signer applies by default, 135
months, on the platforms whose signatures expire; a job started from it therefore reports a
`signatureExpiresAt`. The AWS IoT platform expires nothing and supports no revocation, so a job on
it reports no expiry and `RevokeSignature` refuses it.

Every timestamp this service emits is epoch seconds, as a JSON number, because that is the form the
Signer model unmarshals. The AWS CLI coerces an ISO 8601 string and hides the difference, while the
Java and Go SDKs reject the whole response, so the compatibility suite asserts the numeric form.

## Not emulated

Floci records a signing job but does not sign, and it says so instead of reporting a success
nothing backs. `StartSigningJob` validates the profile and its S3 source and destination and
completes the job as `Failed`, with a `statusReason` naming the object that was not written, so no
caller downloads a key that is not there; `RevokeSignature` refuses a job that has no signature to
revoke. `FLOCI_SERVICES_SIGNER_MOCK_SIGNING_JOBS=true` records jobs as `Succeeded` and echoes the
destination as `signedObject`, for tools that only read job metadata; that mode is a mock, and the
artifact still does not exist.

`SignPayload` is not served: it answers an AWS-shaped error rather than a signature, because a
payload signature is the one thing the metadata model cannot approximate. A profile permission can
still name `signer:SignPayload`, which AWS lists with the other profile actions: the grant is policy
metadata and is accepted, only the operation itself is not served.

Cancelling a profile keeps it, as AWS does: it stays readable through `GetSigningProfile` with the
same ARN and version and the status `Canceled`, it stops accepting jobs, and `ListSigningProfiles`
hides it unless `includeCanceled=true` or `statuses` names `Canceled`. A `PutSigningProfile` on the
same name brings it back as a new `Active` version.

IAM treats Signer as any other service: `signer` and `signer-data` are in the vendored
request-resource table, so an action is checked against the profile or job ARN the call names
rather than against `*`. A profile a CloudFormation stack provisions through
`AWS::Signer::SigningProfile` is the same record this service serves: the stack's `Ref` is the
profile ARN, `Fn::GetAtt` answers `Arn`, `ProfileName`, `ProfileVersion` and `ProfileVersionArn`,
the template's `Tags` land on the profile, and deleting the stack cancels it.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SIGNER_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_SIGNER_MOCK_SIGNING_JOBS` | `false` | Record jobs as `Succeeded` without an artifact, for tools that only read job metadata |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws signer put-signing-profile \
  --profile-name my-profile \
  --platform-id AWSLambda-SHA384-ECDSA \
  --signature-validity-period type=DAYS,value=135 \
  --tags env=local

job_id=$(aws signer start-signing-job \
  --profile-name my-profile \
  --source s3={bucketName=artifacts,key=app.zip,version=1} \
  --destination s3={bucketName=signed,prefix=out/} \
  --client-request-token local-1 \
  --query jobId --output text)

aws signer describe-signing-job --job-id "$job_id"   # Failed: no artifact is written

aws signer cancel-signing-profile --profile-name my-profile
aws signer get-signing-profile --profile-name my-profile   # Canceled, same ARN
aws signer list-signing-profiles --include-canceled --query 'profiles[].profileName'
```

Through CloudFormation, the profile the stack reports is the profile this service serves:

```json
{
  "Resources": {
    "Profile": {
      "Type": "AWS::Signer::SigningProfile",
      "Properties": {"PlatformId": "AWSLambda-SHA384-ECDSA"}
    }
  },
  "Outputs": {
    "Arn": {"Value": {"Fn::GetAtt": ["Profile", "Arn"]}}
  }
}
```
