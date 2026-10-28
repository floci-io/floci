# ECR

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonEC2ContainerRegistry_V20150921.*`) for the control plane.
**Data plane:** OCI Distribution Spec v2 (`/v2/...`), proxied by Floci to a real `registry:2` container.
**Endpoint:** `POST http://localhost:4566/` (or `https://...`) for the control plane; `<account>.dkr.ecr.<region>.localhost:4566/<repo>` (HTTP) or `<account>.dkr.ecr.<region>.localhost.floci.io:4566/<repo>` (TLS) for `docker push` / `docker pull`.

## Supported Actions

| Action | Description |
| --- | --- |
| `CreateRepository` | Create a new repository (lazy-starts the backing registry on first call) |
| `CreatePullThroughCacheRule` | Create and persist pull-through cache rule configuration |
| `DescribePullThroughCacheRules` | List or filter pull-through cache rules with pagination |
| `UpdatePullThroughCacheRule` | Update a rule's credential or custom role |
| `ValidatePullThroughCacheRule` | Validate that a configured rule exists and report it as usable |
| `DeletePullThroughCacheRule` | Delete a pull-through cache rule by repository prefix |
| `CreateRepositoryCreationTemplate` | Store a repository creation template under its prefix (recorded, not applied) |
| `DescribeRepositoryCreationTemplates` | List templates, or those for given prefixes, with pagination |
| `UpdateRepositoryCreationTemplate` | Replace the fields a request sets on a template |
| `DeleteRepositoryCreationTemplate` | Delete a template by prefix |
| `DescribeRepositories` | List repositories or fetch by name |
| `DeleteRepository` | Delete a repository (with `force=true` semantics for non-empty repos) |
| `GetAuthorizationToken` | Returns a docker-login token + proxy endpoint |
| `ListImages` | Enumerate paginated tags and digests in a repository |
| `DescribeImages` | Image metadata: digest, size, push timestamp, manifest media type |
| `BatchGetImage` | Fetch image manifests, honoring `acceptedMediaTypes` |
| `BatchDeleteImage` | Delete images by tag or digest |
| `PutImage` | Write an image manifest under a tag, or under its digest, into the backing registry |
| `BatchCheckLayerAvailability` | Report which layers a repository holds, with their sizes |
| `GetDownloadUrlForLayer` | Return the layer's address on Floci's registry endpoint |
| `InitiateLayerUpload` | Open a layer upload backed by a registry upload session |
| `UploadLayerPart` | Send the next part of a layer upload |
| `CompleteLayerUpload` | Store an uploaded layer under its digest |
| `PutImageTagMutability` | Set tag mutability and reject replacement pushes to immutable tags |
| `PutImageScanningConfiguration` | Set a repository's `scanOnPush` flag and return the updated configuration; a non-boolean value is rejected |
| `BatchGetRepositoryScanningConfiguration` | Report each repository's `scanOnPush` setting with a derived `scanFrequency` (`SCAN_ON_PUSH` or `MANUAL`); a missing repository fails the whole batch instead of appearing in `failures` |
| `TagResource` / `UntagResource` / `ListTagsForResource` | Resource tagging |
| `PutLifecyclePolicy` / `GetLifecyclePolicy` / `DeleteLifecyclePolicy` | Lifecycle policy round-trip (stored, not enforced) |
| `SetRepositoryPolicy` / `GetRepositoryPolicy` / `DeleteRepositoryPolicy` | Repository policy round-trip (stored, not enforced) |
| `PutRegistryPolicy` / `GetRegistryPolicy` / `DeleteRegistryPolicy` | Registry policy round-trip (stored, not enforced) |
| `PutReplicationConfiguration` / `DescribeRegistry` | Store the registry's replication rules and return them with the registry ID (stored, not acted on) |
| `PutRegistryScanningConfiguration` / `GetRegistryScanningConfiguration` | Store the registry's scan type and scanning rules (stored, not acted on) |

`ListImages` accepts `maxResults` from 1 to 1000 (default 100) and returns `nextToken`
when another page remains. Tokens are scoped to the region, registry, and repository;
local pages use stable tag order. Listing still enumerates tagged images and ignores
the optional `filter` fields.

### Admin Endpoints

| Endpoint | Description |
| --- | --- |
| `POST /_floci/ecr/gc` | Run garbage collection on the backing `registry:2` container to reclaim disk after image deletions |

## Emulation Behavior

- **Real OCI registry backing.** A single shared `registry:2` container per Floci instance stores all repositories. Floci proxies Docker Distribution traffic to it and starts it lazily on the first ECR API call. The container is reused across Floci restarts (`keep-running-on-shutdown: true` by default), so pushed image bytes survive restarts.
- **TLS registry data plane.** Real AWS ECR is HTTPS-only. When TLS is enabled (`FLOCI_TLS_ENABLED=true`), Floci serves the registry data plane over TLS at `<account>.dkr.ecr.<region>.localhost.floci.io:<port>/<repo>`. The server certificate automatically includes regional wildcards `*.dkr.ecr.<region>.localhost.floci.io` for every advertised AWS region as Subject Alternative Names (SANs). Container runtimes and tools (Docker, containerd, nerdctl, podman) connect over HTTPS after trusting Floci's root CA certificate (`GET /_floci/ca.pem` or system trust store). The plain HTTP loopback path (`<account>.dkr.ecr.<region>.localhost:<port>`) remains fully supported without changes.
- **Advertise TLS addresses.** Set `FLOCI_SERVICES_ECR_TLS_URI=true` together with `FLOCI_TLS_ENABLED=true` to return the TLS hostname in `repositoryUri` and an HTTPS `proxyEndpoint` from `GetAuthorizationToken`. This opt-in defaults to `false`. With `uri-style: path`, the URI becomes `localhost.floci.io:<port>/<account>/<region>/<repo>` and the login endpoint is `https://localhost.floci.io:<port>`. Existing repository records acquire the current URI when described or returned by deletion. If global TLS is disabled, the option has no effect. Clients must trust the Floci CA, or the configured certificate must cover the advertised names.
- **Storage cleanup.** `DeleteRepository --force` removes repository manifests, including untagged manifests. When it deletes the final repository, or when a non-retained runtime stops, Floci removes the named registry volume only in `memory` mode or when `prune-volumes-on-delete: true`.
- **Loopback URI scheme.** Repository URIs follow `<account>.dkr.ecr.<region>.localhost:<flociPort>/<repoName>`. RFC 6761 reserves `*.localhost` to resolve to the loopback address, and a Docker daemon with the classic image store auto-trusts loopback as an insecure registry, so `docker push` and `docker pull` work without daemon configuration. A daemon with the containerd image store, the default from Docker Engine 29, reaches only `localhost` and `127.0.0.1` over plain HTTP and takes `*.localhost` for an HTTPS registry: use the `path` URI style (`localhost:<flociPort>/<account>/<region>/<repo>`, `floci.services.ecr.uri-style: path`) there, as well as where `*.localhost` resolution misbehaves.
- **Image tag mutability.** `IMMUTABLE` repositories allow the first manifest write for a tag and reject every replacement, including a replacement with the same manifest. The proxy forwards blob uploads and digest-addressed manifests unchanged. `PutImage` follows the same rule and answers `ImageTagAlreadyExistsException`; a `docker push` and a `PutImage` of the same tag cannot both succeed.
- **Pushing through the API.** `PutImage` and the layer API write to the same `registry:2` container as `docker push`, so an image written through the API is the one `docker pull` and `BatchGetImage` return. Retagging without Docker works as on AWS: read the manifest with `batch-get-image`, then write it with `put-image --image-tag <new>`.
  - `PutImage` takes the media type from `imageManifestMediaType`, else from the manifest's own `mediaType`; a manifest with neither is `InvalidParameterException`, as on AWS. A tag outside ECR's rule (`[a-zA-Z0-9-_.]+`, at most 300 characters) is `InvalidParameterException` with ECR's message. The backing registry follows the OCI tag grammar, so Floci also refuses two kinds of tag ECR takes, with a message naming the limit: one starting with `.` or `-`, and one longer than 128 characters. A manifest whose layers the repository does not hold answers `LayersNotFoundException`; writing the manifest a tag already points at answers `ImageAlreadyExistsException`.
  - Layer uploads map onto the registry's upload sessions. `InitiateLayerUpload` reports a `partSize` of 10 MiB. Each part must start at the byte after the last one received and can be at most 20 MiB, and every part but the last must be at least 5 MiB (`LayerPartTooSmallException`). `CompleteLayerUpload` checks the digest (`InvalidLayerException`) and refuses a layer the repository already holds (`LayerAlreadyExistsException`).
  - Upload sessions live in memory: a restart ends them, and one left idle for a day is dropped.
  - `GetDownloadUrlForLayer` returns `<scheme>://<repository URI host>/v2/<repo>/blobs/<digest>`, served by Floci's registry endpoint without credentials, in place of AWS's pre-signed S3 URL.
  - `BatchCheckLayerAvailability` lists held layers as `AVAILABLE`, and a layer the repository does not hold under `failures` as `MissingLayerDigest`.
- **Authorization.** `GetAuthorizationToken` returns `Base64("AWS:floci")` plus a proxy endpoint. The backing `registry:2` runs without auth, so any `aws ecr get-login-password | docker login` succeeds.
- **Pull through cache rule configuration.** Pull-through cache rule actions persist AWS-compatible rule metadata with account and Region isolation. `UpdatePullThroughCacheRule` preserves fields omitted by the request and refreshes `updatedAt`. `ValidatePullThroughCacheRule` checks that the rule exists and reports it as valid; Floci does not contact the upstream registry or validate credentials. Rules do not proxy or cache images from the configured upstream registry.
- **Registry settings.** The registry policy, replication configuration and scanning configuration are kept per account and Region and returned as written. Floci does not replicate or scan images, or evaluate the registry policy.
  - Each request is checked against the constraints of the ECR service model, vendored in `src/main/resources/aws/ecr-request-shapes.json` by `tools/aws/regen_ecr_shapes.py`: member types, required members, lengths and counts, patterns and enum values. A request outside them is `InvalidParameterException` in ECR's form, `Invalid parameter at '<member>' failed to satisfy constraint: '<rule>'`.
  - Concurrent writes to the policy, replication and scanning configuration, as Terraform makes, each keep their setting.
  - `GetRegistryPolicy` and `DeleteRegistryPolicy` answer `RegistryPolicyNotFoundException` when no policy is set. `PutRegistryPolicy` refuses text that is not a JSON object.
  - An empty `rules` list in `PutReplicationConfiguration` clears the configuration. `DescribeRegistry` returns `{"rules": []}` until rules are set.
  - The scanning configuration is `BASIC` with no rules until one is put. `CONTINUOUS_SCAN` is refused with `BASIC`, since AWS offers it only with enhanced scanning. `BatchGetRepositoryScanningConfiguration` still reports each repository's own `scanOnPush`. Missing repository names appear in `failures` with `REPOSITORY_NOT_FOUND`, alongside configurations for the names that exist.
- **Repository creation templates.** Templates are kept per account and Region, keyed by `prefix` (`ROOT` included). A new template defaults to `AES256` encryption and `MUTABLE` tags. `UpdateRepositoryCreationTemplate` replaces each field the request sets, an empty string or list clears it, and fields left out are kept. The exclusion filters are the exception: the model requires at least one, so `[]` is refused. Instead, an update that leaves the template in a mode without exclusions (`MUTABLE` or `IMMUTABLE`) drops the filters, which is what Terraform relies on, since it leaves the member out when the filters are removed. `DescribeRepositoryCreationTemplates` leaves out a requested prefix with no template rather than failing. Requests are checked against the vendored ECR model constraints, as the registry settings are, with `InvalidParameterException`. On top of the model, `appliedFor` must name at least one value, and policy text must be a JSON object. Nothing in Floci creates repositories through replication, pull through cache or create-on-push, so templates are never applied.
- **Manifest format negotiation.** `BatchGetImage` forwards the caller's `acceptedMediaTypes` as the upstream `Accept` header. Modern OCI manifests (`application/vnd.oci.image.manifest.v1+json`) and Docker v2 schema 2 are both supported.
- **Cross-account / cross-region isolation.** Internally the registry namespaces repositories as `<account>/<region>/<repoName>`, so the same repository name in different accounts or regions cannot collide.
- **Reconcile on first start.** When the registry container starts, Floci queries `GET /v2/_catalog` and recreates `Repository` metadata entries for any namespaces present in the registry but missing from local storage. This means image bytes are never orphaned across restarts.
- **Upgrade from direct registry access.** Floci recreates an older backing container with the same volume and a loopback-only port binding. Repositories written through the former direct endpoint retain their physical paths and map to the default account and Region; their returned URI moves to port `4566`.
- **Lambda and ECS integration.** Image-backed Lambda functions (`PackageType=Image`) and ECS task containers reference the same loopback `repositoryUri`. Floci rewrites real-AWS-shaped `<account>.dkr.ecr.<region>.amazonaws.com/...` URIs to the loopback registry at pull time, path style on `localhost` (`localhost:<flociPort>/<account>/<region>/<repo>`) in either URI style and with or without TLS, the one address every Docker daemon pulls from over plain HTTP, so CDK's `DockerImageFunction` (which generates AWS-shaped URIs in CloudFormation templates) works without any user-side rewriting.
- **Locally built images win.** An AWS-shaped URI that names an image already present on the Docker daemon is used as-is instead of being rewritten (`FLOCI_SERVICES_ECR_PREFER_LOCAL_IMAGES`, default `true`). Build the image under the exact URI your function or task definition declares (`docker build -t <account>.dkr.ecr.<region>.amazonaws.com/<repo>:<tag> .`) and it runs without a push to the emulated registry, and without the registry container being started. The check uses the reference Floci would launch, so with `FLOCI_DOCKER_IMAGE_REGISTRY_BASE` set the image must be present as `<base>/<account>.dkr.ecr.<region>.amazonaws.com/<repo>:<tag>`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ECR_ENABLED` | `true` | Enable the ECR control plane and lazy registry start |
| `FLOCI_SERVICES_ECR_REGISTRY_IMAGE` | `registry:2` | Backing OCI registry image |
| `FLOCI_SERVICES_ECR_REGISTRY_CONTAINER_NAME` | `floci-ecr-registry` | Container name used for idempotent reuse across restarts |
| `FLOCI_SERVICES_ECR_REGISTRY_BASE_PORT` | `5100` | First private loopback port for the backing registry |
| `FLOCI_SERVICES_ECR_REGISTRY_MAX_PORT` | `5199` | Last private loopback port for the backing registry |
| `FLOCI_SERVICES_ECR_DATA_PATH` | `./data/ecr` | Bind-mount root for the registry data directory |
| `FLOCI_SERVICES_ECR_KEEP_RUNNING_ON_SHUTDOWN` | `true` | Leave the registry container running so the next Floci start adopts it |
| `FLOCI_SERVICES_ECR_DOCKER_NETWORK` | Inherit Floci's container network | Docker network shared by Floci and the backing registry when running in Docker |
| `FLOCI_SERVICES_ECR_URI_STYLE` | `hostname` | `hostname` = `*.dkr.ecr.<region>.localhost`; `path` = `localhost:<port>/<account>/<region>/<repo>` |
| `FLOCI_SERVICES_ECR_TLS_URI` | `false` | Report `localhost.floci.io` registry addresses and an HTTPS login endpoint when global `FLOCI_TLS_ENABLED` is also `true` |
| `FLOCI_SERVICES_ECR_PREFER_LOCAL_IMAGES` | `true` | Use an AWS-shaped ECR image URI as-is when the Docker daemon already has that image, instead of rewriting it to the loopback registry |
| `FLOCI_SERVICES_ECR_TLS_ENABLED` | `false` | Reserved for future ACM-backed TLS |

When Docker reports the chosen registry port as already in use, for example by another emulator on the same daemon, Floci tries the next free port in the range. When no port in the range is free, startup fails and the error names the ports Docker refused. Any other startup error, such as an unreachable Docker daemon or a failed image pull, fails at once.

### Docker Compose port mapping

ECR uses Floci's existing `4566` listener. The backing registry binds a loopback-only implementation port, so no ECR range belongs in `docker-compose.yml`:

```yaml
# ECR uses the existing Floci API port
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
      - "6379-6399:6379-6399"   # ElastiCache
      - "7001-7099:7001-7099"   # RDS
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

`docker login 000000000000.dkr.ecr.us-east-1.localhost:4566` works once Floci starts the registry sidecar.

When Floci runs in a container that publishes its port on another host port (for example `docker run -p 54321:4566`), the `repositoryUri`, the `GetAuthorizationToken` `proxyEndpoint` and the rewritten Lambda and ECS image URIs all name that host port (`000000000000.dkr.ecr.us-east-1.localhost:54321`, and `localhost:54321/...` for a rewrite), because the Docker daemon performs login, push and pull through it. Floci reads the published port from its own container through the mounted Docker socket, and uses `floci.port` (`4566`) when it does not run in a container or the port is not published.

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
#     "repositoryUri":  "000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app",
#     "imageTagMutability": "MUTABLE",
#     ...
#   }
# }

# Authenticate stock docker against the emulated registry
aws ecr get-login-password --endpoint-url $AWS_ENDPOINT \
  | docker login --username AWS --password-stdin \
        000000000000.dkr.ecr.us-east-1.localhost:4566

# Push an image
docker pull alpine:3.19
docker tag  alpine:3.19 \
            000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app:v1
docker push 000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app:v1

# Inspect via the AWS CLI
aws ecr list-images     --repository-name floci-it/app --endpoint-url $AWS_ENDPOINT
aws ecr describe-images --repository-name floci-it/app --endpoint-url $AWS_ENDPOINT

# Pull from a clean local image store
docker rmi  000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app:v1
docker pull 000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app:v1

# Use the image as a Lambda function
aws lambda create-function \
  --function-name my-image-fn \
  --package-type Image \
  --code ImageUri=000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app:v1 \
  --role arn:aws:iam::000000000000:role/lambda-role \
  --endpoint-url $AWS_ENDPOINT

aws lambda invoke --function-name my-image-fn /tmp/out.json --endpoint-url $AWS_ENDPOINT

# Tear down
aws ecr batch-delete-image --repository-name floci-it/app \
    --image-ids imageTag=v1 --endpoint-url $AWS_ENDPOINT
aws ecr delete-repository  --repository-name floci-it/app --force \
    --endpoint-url $AWS_ENDPOINT
```

### Push and Pull over TLS

Start Floci with `FLOCI_TLS_ENABLED=true` and `FLOCI_SERVICES_ECR_TLS_URI=true`, then trust Floci's root CA and use the repository address returned by the API:

```bash
# 1. Download and trust Floci's root CA
curl -s http://localhost:4566/_floci/ca.pem -o floci-root-ca.pem
export AWS_CA_BUNDLE=$PWD/floci-root-ca.pem
export AWS_ENDPOINT=https://localhost:4566

# 2. Read the advertised repository and registry addresses
REPOSITORY_URI=$(aws ecr create-repository --repository-name floci-it/tls-app \
  --endpoint-url "$AWS_ENDPOINT" --query repository.repositoryUri --output text)
REGISTRY_HOST=${REPOSITORY_URI%%/*}

# 3. Trust the CA in Linux Docker Engine (separate from AWS_CA_BUNDLE)
sudo mkdir -p "/etc/docker/certs.d/$REGISTRY_HOST"
sudo cp floci-root-ca.pem "/etc/docker/certs.d/$REGISTRY_HOST/ca.crt"

# 4. Authenticate Docker against the TLS endpoint
aws ecr get-login-password --endpoint-url $AWS_ENDPOINT \
  | docker login --username AWS --password-stdin \
        "$REGISTRY_HOST"

# 5. Tag and push an image over TLS
docker pull alpine:3.19
docker tag alpine:3.19 "$REPOSITORY_URI:v1"
docker push "$REPOSITORY_URI:v1"

# 6. Pull over TLS
docker pull "$REPOSITORY_URI:v1"
```

For Docker Desktop, replace step 3 with installation of the Floci CA in the host trust store
and restart Docker Desktop, following [Docker's CA certificate instructions](https://docs.docker.com/engine/network/ca-certs/).
`AWS_CA_BUNDLE` affects the AWS CLI; it does not configure Docker's certificate trust.

!!! note "Pulling from other containers (EKS, same-network consumers)"
    The `localhost`-based repository URI only works from the host. [Floci EKS](eks.md#pulling-images-from-floci-ecr)
    configures a containerd mirror to reach Floci's data plane, so Helm charts can reference the
    pushed URI as-is.

    When TLS URI advertising is enabled, newly configured EKS registry mirrors accept both
    the loopback and TLS names and forward them to the same internal HTTP endpoint. Recreate
    existing clusters to pick up changed registry mirror settings. Automatic rewrites of
    AWS-shaped Lambda/ECS image references continue using the plain HTTP loopback address; an
    explicitly supplied TLS image reference is used as supplied.

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

- Replication and registry scanning (the configurations are stored, but images are not copied or scanned)
- Pull-through cache image proxying and population. Rule configuration APIs are supported, but pulls are not routed to upstream registries
- Image scanning (`StartImageScan`, `DescribeImageScanFindings`)
- Image signing and notary attachments
- Lifecycle policy enforcement (the policy text is stored but not applied)
- Applying repository creation templates (they are stored, but Floci never creates repositories through replication, pull through cache or create-on-push)
- Repository policy enforcement (no IAM evaluation against repository-level policies)
- TLS via emulated ACM

## Troubleshooting

**`Function.TimedOut` when invoking image-backed Lambdas on native Linux Docker.** Lambda containers reach Floci's Runtime API server via the docker bridge gateway. On Ubuntu / Pop!_OS / Debian with UFW enabled, the default `INPUT DROP` policy blocks this path. See [Quick Start → Lambda on native Linux Docker](../getting-started/quick-start.md#lambda-on-native-linux-docker-ufw) for the one-line `ufw allow in on docker0` fix.

**`docker login` fails with TLS errors.** When TLS URI advertising is enabled, Docker must trust the Floci CA independently of the AWS CLI, and the server certificate must cover the returned registry hostname. Follow the trust steps above; setting only `AWS_CA_BUNDLE` is insufficient. Default loopback URIs remain usable over HTTP without additional Docker trust configuration.

**Registry requests return `503 Service Unavailable`.** Floci could not start or connect to its
backing registry. Errors detected by Floci include a Distribution `errors` array with a network configuration
hint. Check Docker availability and the registry container's health. When Floci itself runs in
Docker, attach Floci and its registry to the same user-defined network. Docker's default `bridge`
does not resolve container names, which Floci uses to reach the registry. Create a network with
`docker network create floci-net`, start Floci with `--network floci-net`, and set
`FLOCI_SERVICES_ECR_DOCKER_NETWORK=floci-net` if selecting the registry network explicitly.
See [Docker's bridge network documentation](https://docs.docker.com/engine/network/drivers/bridge/).

**Disk not reclaimed after deleting images.** `BatchDeleteImage` removes manifests but blobs remain on disk until garbage collection runs. Trigger it with `curl -X POST http://localhost:4566/_floci/ecr/gc`. The endpoint runs `registry garbage-collect` inside the backing container and returns the reclaimed blob list. The operation is serialized — ECR API calls block for its duration (typically a few seconds).

**`*.localhost` does not resolve to loopback on this platform.** Set `floci.services.ecr.uri-style: path` to fall back to `localhost:<port>/<account>/<region>/<repo>` URIs.
