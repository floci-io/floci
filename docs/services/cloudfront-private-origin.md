# Private S3 origin example

This recipe creates a disposable S3 bucket and object, one origin access control
(OAC), and two CloudFront distributions. The bucket policy grants `s3:GetObject`
to the CloudFront service principal only when `AWS:SourceArn` equals the first
distribution's ARN. All four bucket Block Public Access flags are enabled.

Start a separate Floci instance with S3 authentication enforcement enabled:

```bash
docker run --rm --name floci-private-origin -p 4566:4566 \
  -e FLOCI_SERVICES_S3_ENFORCE_AUTH=true floci/floci:latest
```

Use an available host port if another emulator is already running. Do not reuse
an existing container without recreating it with the enforcement setting. The
example uses the default account, region `us-east-1`, and synthetic credentials.
IAM policy enforcement is left at its default setting; S3 authentication
enforcement is the setting exercised here.

With AWS CLI v2, `curl`, `jq`, and Bash installed, run from the repository root:

```bash
AWS_ENDPOINT_URL=http://localhost:4566 bash tools/examples/private-s3-origin.sh
```

The [script](https://github.com/floci-io/floci/blob/main/tools/examples/private-s3-origin.sh)
uses AWS APIs to provision the resources. Anonymous `curl` requests connect to
the local endpoint with the distribution's `Host` header, so no public DNS or
host-file changes are needed. It checks both status codes and the successful
response body, fails on an unexpected result, and removes its resources on exit.
It does not reset or delete unrelated emulator resources.

Expected output with enforcement enabled:

```text
Authorized CloudFront distribution: HTTP 200
Anonymous direct S3 request: HTTP 403
Other CloudFront distribution: HTTP 403
```

Both distributions use the same S3 origin and OAC. The second distribution is
denied because its ARN does not match the bucket-policy condition. This checks
origin authorization separately from simply attaching an OAC. See AWS's
[private S3 origin guidance](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-s3.html)
for the corresponding service-principal grant.

| S3 authentication enforcement | Intended distribution | Anonymous direct S3 | Other distribution |
|---|---|---|---|
| `true` | 200, exact object body | 403 | 403 |
| `false` (default) | 200 | 200 | 200 |

With enforcement disabled, the script deliberately fails at the direct-S3
negative check. Block Public Access metadata and an OAC policy alone do not
enable read authorization. See [S3 Block Public Access](s3.md#block-public-access)
for the settings that depend on `enforce-auth`.

These distributions allow anonymous viewers and do not configure trusted key
groups or signed URLs. A viewer signature controls access to CloudFront;
the OAC and bucket policy control CloudFront's access to S3. Passing signed-URL
checks separately would not establish the origin privacy proved by these
negative checks.
