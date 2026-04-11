# ECR

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonEC2ContainerRegistry_V20150921.*`) for the control plane.
**Data plane:** OCI Distribution Spec v2 (`/v2/...`), served by a real `registry:2` container managed by Floci.
**Endpoint:** `POST http://localhost:4566/` for the control plane; `<account>.dkr.ecr.<region>.localhost:<port>/<repo>` for `docker push` / `docker pull`.

## Supported Actions

| Action | Description |
| --- | --- |
| `CreateRepository` | Create a new repository (lazy-starts the backing registry on first call) |
| `DescribeRepositories` | List repositories or fetch by name |
| `DeleteRepository` | Delete a repository (with `force=true` semantics for non-empty repos) |
| `GetAuthorizationToken` | Returns a docker-login token + proxy endpoint |
| `ListImages` | Enumerate tags and digests in a repository |
| `DescribeImages` | Image metadata: digest, size, push timestamp, manifest media type |
| `BatchGetImage` | Fetch image manifests, honoring `acceptedMediaTypes` |
| `BatchDeleteImage` | Delete images by tag or digest |
| `PutImageTagMutability` | Set tag mutability (round-trip; not enforced on push) |
| `TagResource` / `UntagResource` / `ListTagsForResource` | Resource tagging |
| `PutLifecyclePolicy` / `GetLifecyclePolicy` / `DeleteLifecyclePolicy` | Lifecycle policy round-trip (stored, not enforced) |
| `SetRepositoryPolicy` / `GetRepositoryPolicy` / `DeleteRepositoryPolicy` | Repository policy round-trip (stored, not enforced) |

### Admin Endpoints

| Endpoint | Description |
| --- | --- |
| `POST /_floci/ecr/gc` | Run garbage collection on the backing `registry:2` container to reclaim disk after image deletions |

## Emulation Behavior

- **Real OCI registry backing.** A single shared `registry:2` container per Floci instance serves all repositories. The container is started lazily on the first ECR API call and reused across Floci restarts (`keep-running-on-shutdown: true` by default), so pushed image bytes survive restarts.
- **Loopback URI scheme.** Repository URIs follow `<account>.dkr.ecr.<region>.localhost:<registryPort>/<repoName>`. RFC 6761 reserves `*.localhost` to resolve to the loopback address, and the docker daemon auto-trusts loopback as an insecure registry, so **no daemon configuration changes are required** — `docker push` and `docker pull` work out of the box. A `path` URI style fallback (`localhost:<port>/<account>/<region>/<repo>`) is available via `floci.services.ecr.uri-style: path` for environments where `*.localhost` resolution misbehaves.
- **Authorization.** `GetAuthorizationToken` returns `Base64("AWS:floci")` plus a proxy endpoint. The backing `registry:2` runs without auth, so any `aws ecr get-login-password | docker login` succeeds.
- **Manifest format negotiation.** `BatchGetImage` forwards the caller's `acceptedMediaTypes` as the upstream `Accept` header. Modern OCI manifests (`application/vnd.oci.image.manifest.v1+json`) and Docker v2 schema 2 are both supported.
- **Cross-account / cross-region isolation.** Internally the registry namespaces repositories as `<account>/<region>/<repoName>`, so the same repository name in different accounts or regions cannot collide.
- **Reconcile on first start.** When the registry container starts, Floci queries `GET /v2/_catalog` and recreates `Repository` metadata entries for any namespaces present in the registry but missing from local storage. This means image bytes are never orphaned across restarts.
- **Lambda integration.** Image-backed Lambda functions (`PackageType=Image`) reference the same loopback `repositoryUri`. Floci's Lambda runner rewrites real-AWS-shaped `<account>.dkr.ecr.<region>.amazonaws.com/...` URIs to the loopback registry at pull time, so CDK's `DockerImageFunction` (which generates AWS-shaped URIs in CloudFormation templates) works without any user-side rewriting.

## Configuration

Defaults under `floci.services.ecr` in `application.yml`:

```yaml
floci:
  services:
    ecr:
      enabled: true
      registry-image: "registry:2"
      registry-container-name: floci-ecr-registry
      registry-base-port: 5100
      registry-max-port: 5199
      data-path: ./data/ecr
      tls-enabled: false
      keep-running-on-shutdown: true
      uri-style: hostname     # or "path"
```

| Setting | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Enable the ECR control plane and lazy registry start |
| `registry-image` | `registry:2` | Backing OCI registry image |
| `registry-container-name` | `floci-ecr-registry` | Name used for idempotent reuse across restarts |
| `registry-base-port` / `-max-port` | `5100` / `5199` | Port range allocated for the published registry port |
| `data-path` | `./data/ecr` | Bind-mount root for `<data-path>/registry` (the registry's `/var/lib/registry`) |
| `keep-running-on-shutdown` | `true` | Leave the container up so the next Floci start adopts it |
| `uri-style` | `hostname` | `hostname` returns `*.dkr.ecr.<region>.localhost`; `path` returns `localhost:<port>/<account>/<region>/<repo>` |
| `tls-enabled` | `false` | Reserved for the future ACM-backed TLS phase |

## Examples

```bash
export AWS_ENDPOINT=http://localhost:4566

# Create a repository
aws ecr create-repository \
  --repository-name floci-it/app \
  --endpoint-url $AWS_ENDPOINT
# {
#   "repository": {
#     "repositoryArn":  "arn:aws:ecr:us-east-1:000000000000:repository/floci-it/app",
#     "repositoryUri":  "000000000000.dkr.ecr.us-east-1.localhost:5100/floci-it/app",
#     "imageTagMutability": "MUTABLE",
#     ...
#   }
# }

# Authenticate stock docker against the emulated registry
aws ecr get-login-password --endpoint-url $AWS_ENDPOINT \
  | docker login --username AWS --password-stdin \
        000000000000.dkr.ecr.us-east-1.localhost:5100

# Push an image
docker pull alpine:3.19
docker tag  alpine:3.19 \
            000000000000.dkr.ecr.us-east-1.localhost:5100/floci-it/app:v1
docker push 000000000000.dkr.ecr.us-east-1.localhost:5100/floci-it/app:v1

# Inspect via the AWS CLI
aws ecr list-images     --repository-name floci-it/app --endpoint-url $AWS_ENDPOINT
aws ecr describe-images --repository-name floci-it/app --endpoint-url $AWS_ENDPOINT

# Pull from a clean local image store
docker rmi  000000000000.dkr.ecr.us-east-1.localhost:5100/floci-it/app:v1
docker pull 000000000000.dkr.ecr.us-east-1.localhost:5100/floci-it/app:v1

# Use the image as a Lambda function
aws lambda create-function \
  --function-name my-image-fn \
  --package-type Image \
  --code ImageUri=000000000000.dkr.ecr.us-east-1.localhost:5100/floci-it/app:v1 \
  --role arn:aws:iam::000000000000:role/lambda-role \
  --endpoint-url $AWS_ENDPOINT

aws lambda invoke --function-name my-image-fn /tmp/out.json --endpoint-url $AWS_ENDPOINT

# Tear down
aws ecr batch-delete-image --repository-name floci-it/app \
    --image-ids imageTag=v1 --endpoint-url $AWS_ENDPOINT
aws ecr delete-repository  --repository-name floci-it/app --force \
    --endpoint-url $AWS_ENDPOINT
```

## SDK Example (Java)

```java
EcrClient ecr = EcrClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

// Create a repository
Repository repo = ecr.createRepository(req -> req.repositoryName("floci-it/app"))
    .repository();

// Get a docker login token
GetAuthorizationTokenResponse auth = ecr.getAuthorizationToken();
AuthorizationData data = auth.authorizationData().get(0);
String decoded = new String(Base64.getDecoder().decode(data.authorizationToken()));
// decoded = "AWS:floci" → pipe to `docker login --username AWS --password-stdin <proxyEndpoint>`

// List images after a docker push
ListImagesResponse images = ecr.listImages(req -> req.repositoryName("floci-it/app"));
images.imageIds().forEach(System.out::println);

// Force-delete the repository
ecr.deleteRepository(req -> req.repositoryName("floci-it/app").force(true));
```

## Using with AWS CDK

CDK's `DockerImageFunction` works against Floci unchanged:

```typescript
import * as lambda from 'aws-cdk-lib/aws-lambda';

new lambda.DockerImageFunction(this, 'MyFn', {
  functionName: 'hello',
  code: lambda.DockerImageCode.fromImageAsset('./docker-fn'),  // local Dockerfile
});
```

`cdk bootstrap` creates the asset ECR repository (`cdk-hnb659fds-container-assets-…`) via Floci's CloudFormation provisioner; `cdk deploy` runs `docker build` + `docker push` against the emulated registry; `aws lambda invoke` then pulls the image from the loopback registry and runs the handler. See [`compatibility-tests/compat-cdk`](https://github.com/floci-io/floci/tree/main/compatibility-tests/compat-cdk) for a working end-to-end example.

## Not Implemented

The following ECR features are **not** implemented. Stored values for policies and lifecycle rules round-trip via the API but are not enforced at runtime:

- Replication and pull-through cache
- Image scanning (`StartImageScan`, `DescribeImageScanFindings`)
- Image signing and notary attachments
- Lifecycle policy enforcement (the policy text is stored but not applied)
- Repository policy enforcement (no IAM evaluation against repository-level policies)
- TLS via emulated ACM

## Troubleshooting

**`Function.TimedOut` when invoking image-backed Lambdas on native Linux Docker.** Lambda containers reach Floci's Runtime API server via the docker bridge gateway. On Ubuntu / Pop!_OS / Debian with UFW enabled, the default `INPUT DROP` policy blocks this path. See [Quick Start → Lambda on native Linux Docker](../getting-started/quick-start.md#lambda-on-native-linux-docker-ufw) for the one-line `ufw allow in on docker0` fix.

**`docker login` fails with TLS errors.** Floci's emulated registry serves plain HTTP. Docker auto-trusts loopback addresses (`127.0.0.1`, `*.localhost`) as insecure registries, so this should not normally happen. If your URIs end up pointing somewhere non-loopback (e.g. you set `FLOCI_HOSTNAME=floci` for Docker Compose), add the hostname to the daemon's `insecure-registries` array in `/etc/docker/daemon.json`.

**Disk not reclaimed after deleting images.** `BatchDeleteImage` removes manifests but blobs remain on disk until garbage collection runs. Trigger it with `curl -X POST http://localhost:4566/_floci/ecr/gc`. The endpoint runs `registry garbage-collect` inside the backing container and returns the reclaimed blob list. The operation is serialized — ECR API calls block for its duration (typically a few seconds).

**`*.localhost` does not resolve to loopback on this platform.** Set `floci.services.ecr.uri-style: path` to fall back to `localhost:<port>/<account>/<region>/<repo>` URIs.
