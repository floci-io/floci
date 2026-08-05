# MWAA (Amazon Managed Workflows for Apache Airflow)

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/` (path-routed via JAX-RS)

MWAA signs requests as `airflow` (its SigV4 signing name) but registers under the `mwaa` endpoint id, the same signing-name/endpoint-id split as Bedrock Runtime — both are registered so credential-scope lookups resolve correctly either way.

## Supported Operations

| Operation | Description |
|---|---|
| `CreateEnvironment` | Create a new MWAA environment |
| `GetEnvironment` | Describe an environment by name |
| `ListEnvironments` | List all environment names |
| `UpdateEnvironment` | Update metadata-only fields on an environment |
| `DeleteEnvironment` | Delete an environment |
| `CreateWebLoginToken` | Get a token + URL for the Airflow UI |
| `CreateCliToken` | Get a token for the `/aws_mwaa/cli` bridge |
| `TagResource` | Add tags to an environment (via the shared tags controller) |
| `UntagResource` | Remove tags from an environment (via the shared tags controller) |
| `ListTagsForResource` | List tags on an environment (via the shared tags controller) |

## Modes

### Mock mode (`mock: true`)

Environment metadata is stored in-process. No Docker containers are started. The environment transitions directly to `AVAILABLE` on creation. Use this in CI or whenever you only need the MWAA API shape, not a real Airflow instance.

### Real mode (`mock: false`, default)

Floci starts two containers per environment:

- `floci-mwaa-<name>-db` — a private `postgres` metadata database, never given a published host port; it's only ever reached by the sibling Airflow container over the Docker network.
- `floci-mwaa-<name>-airflow` — a real `apache/airflow` container running **LocalExecutor** (webserver + scheduler in one process tree). `AirflowVersion` genuinely selects the image tag (`apache/airflow:<version>-python3.12`), validated against `supported-versions` — unlike some Floci services where a requested version is echoed back but not actually applied, MWAA always runs the exact Airflow version requested.

Once Airflow's unauthenticated `/health` endpoint reports both `metadatabase` and `scheduler` as `"healthy"`, the environment transitions to `AVAILABLE`.

### Web/CLI proxy

Floci runs its own HTTP proxy per environment (published on a host port from the configured range) that fronts the real Airflow webserver: every request is forwarded through to Airflow as-is, **except** `POST /aws_mwaa/cli`, which the proxy intercepts itself — it validates the `Authorization: Bearer <CliToken>` from `CreateCliToken`, then runs the requested `airflow` CLI command inside the container via `docker exec` and returns the AWS-documented `{stdout, stderr}` (base64) shape. `Environment.WebserverUrl` and the URL returned by `CreateWebLoginToken` both point at this proxy, so Airflow-UI browsing and the CLI-token flow go through the same place.

### Startup scripts (`StartupScriptS3Path`)

If configured, the script is fetched from S3 at environment-creation time and injected into the Airflow container before it starts (before `airflow db migrate`/scheduler/webserver), matching real MWAA's ordering. Two behaviors worth knowing about since they're not obvious from the API surface:

- The `airflow` OS user is granted passwordless `sudo` inside the container specifically so scripts using `sudo apt-get ...`-style commands (the pattern shown in AWS's own startup-script documentation) work — the stock `apache/airflow` image ships `sudo` but requires a password by default, so without this grant any `sudo` line would hang.
- A fixed set of Floci-managed environment variables (the Fernet key, the database connection string, the executor setting, and the AWS-SDK-redirection variables described below) are snapshotted before the script runs and restored immediately after, so a script that touches one of these — intentionally or not — can't break the environment. This mirrors real MWAA's "reserved environment variables" behavior.
- A script that fails (non-zero exit, or fails to inject at all) fails `CreateEnvironment` outright, matching real MWAA gating environment creation on startup-script success.

### AWS SDK calls from DAG code

The Airflow container is pointed back at Floci itself (`AWS_ENDPOINT_URL`, placeholder credentials, region) using the same mechanism Lambda and ECS containers already use to reach the emulator — so a DAG's own `boto3.client("s3")`/`boto3.client("secretsmanager")` calls resolve against Floci instead of attempting to reach real AWS.

!!! note "Docker socket required"
    Real mode starts privileged Docker containers. Mount the Docker socket and set the Docker network so containers can reach each other.

```yaml
services:
  floci:
    image: floci/floci:latest
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    ports:
      - "4566:4566"
    environment:
      FLOCI_SERVICES_MWAA_DOCKER_NETWORK: my_project_default
```

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MWAA_ENABLED` | `true` | Enable the MWAA service |
| `FLOCI_SERVICES_MWAA_MOCK` | `false` | Metadata-only mode (no Docker) |
| `FLOCI_SERVICES_MWAA_DEFAULT_POSTGRES_IMAGE` | `postgres:16-alpine` | Metadata-database Docker image |
| `FLOCI_SERVICES_MWAA_SUPPORTED_VERSIONS` | `2.10.5,2.9.3,2.8.4` | Comma-separated `AirflowVersion` allow-list; `CreateEnvironment` rejects any other value |
| `FLOCI_SERVICES_MWAA_DEFAULT_VERSION` | `2.10.5` | `AirflowVersion` used when the request omits one |
| `FLOCI_SERVICES_MWAA_PROXY_BASE_PORT` | `8700` | First port in the web/CLI proxy range |
| `FLOCI_SERVICES_MWAA_PROXY_MAX_PORT` | `8799` | Last port in the web/CLI proxy range |
| `FLOCI_SERVICES_MWAA_DATA_PATH` | `./data/mwaa` | Host data directory root |
| `FLOCI_SERVICES_MWAA_DOCKER_NETWORK` | *(unset)* | Docker network for the Postgres/Airflow containers (falls back to the global `FLOCI_SERVICES_DOCKER_NETWORK`, then Floci's own network) |
| `FLOCI_SERVICES_MWAA_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave Postgres/Airflow containers running after Floci stops |
| `FLOCI_SERVICES_MWAA_DAG_SYNC_INTERVAL_SECONDS` | `30` | How often to poll S3 for DAG and `requirements.txt` changes |
| `FLOCI_SERVICES_MWAA_INSTALL_REQUIREMENTS` | `true` | Install `RequirementsS3Path` (via `pip install -r`) when it changes |

## DAG and requirements sync

Every `dag-sync-interval-seconds`, Floci lists the environment's `DagS3Path` prefix in the bucket referenced by `SourceBucketArn` and copies any changed files into the container's `/opt/airflow/dags` — reusing Floci's existing in-process S3 emulation rather than any new AWS-facing surface. `RequirementsS3Path`, if set, is fetched and `pip install -r`'d the same way whenever its content changes. A broken DAG file or a failing `pip install` is logged but never fails the environment, matching real MWAA behavior.

## ARN Format

```
arn:aws:airflow:<region>:<accountId>:environment/<name>
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Create an environment
aws mwaa create-environment \
  --name my-environment \
  --execution-role-arn arn:aws:iam::000000000000:role/mwaa-role \
  --source-bucket-arn arn:aws:s3:::my-mwaa-bucket \
  --dag-s3-path dags \
  --network-configuration SubnetIds=subnet-1,subnet-2,SecurityGroupIds=sg-1 \
  --airflow-version 2.10.5

# Get the environment (poll Status until AVAILABLE)
aws mwaa get-environment --name my-environment

# List environments
aws mwaa list-environments

# Get a web login token
aws mwaa create-web-login-token --name my-environment

# Get a CLI token, then call the bridge directly
aws mwaa create-cli-token --name my-environment
curl -s -X POST "http://localhost:<proxyPort>/aws_mwaa/cli" \
  -H "Authorization: Bearer <CliToken>" \
  -H "Content-Type: text/plain" \
  --data-raw "dags list"

# Tag the environment
aws mwaa tag-resource \
  --resource-arn arn:aws:airflow:us-east-1:000000000000:environment/my-environment \
  --tags env=dev,team=platform

# Delete the environment
aws mwaa delete-environment --name my-environment
```

## Java SDK Example

```java
MwaaClient mwaa = MwaaClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

// Create environment
CreateEnvironmentResponse created = mwaa.createEnvironment(r -> r
    .name("my-environment")
    .executionRoleArn("arn:aws:iam::000000000000:role/mwaa-role")
    .sourceBucketArn("arn:aws:s3:::my-mwaa-bucket")
    .dagS3Path("dags")
    .networkConfiguration(n -> n
        .subnetIds("subnet-1", "subnet-2")
        .securityGroupIds("sg-1"))
    .airflowVersion("2.10.5"));

// Get environment
GetEnvironmentResponse described = mwaa.getEnvironment(r -> r
    .name("my-environment"));

System.out.println(described.environment().statusAsString()); // AVAILABLE

// List environments
List<String> names = mwaa.listEnvironments(r -> {}).environments();

// Tag resource
mwaa.tagResource(r -> r
    .resourceArn(created.arn())
    .tags(Map.of("team", "platform")));

// Delete environment
mwaa.deleteEnvironment(r -> r.name("my-environment"));
```

## Not Implemented

The following are current, known gaps:

- `PluginsS3Path` is accepted and stored on the environment but not fetched or applied — real MWAA's `plugins.zip` mechanism for shipping custom binaries has no equivalent here yet.
- `UpdateEnvironment` supports metadata-only fields (tags, description, logging-config toggles). Changing `AirflowVersion`, `AirflowConfigurationOptions`, or `RequirementsS3Path` requires deleting and recreating the environment — these would require recreating the running container, which is out of scope for an in-place update today.
- `CreateWebLoginToken` returns a stubbed token rather than performing a real Airflow FAB/JWT SSO handshake.
- No per-component `MWAA_AIRFLOW_COMPONENT` environment variable — real MWAA runs the startup script separately on webserver, scheduler, and worker; Floci runs webserver and scheduler together in one container, so there's no per-component distinction to expose.
- No `triggerer` process — DAGs using deferrable operators will log warnings, since only the scheduler and webserver run.
- Docker container and volume names are namespace-aware (via `FLOCI_DOCKER_RESOURCE_NAMESPACE`, same as every other Docker-backed service) but not scoped by AWS account id — two accounts creating an identically-named environment can collide. This is a pre-existing characteristic shared by every Docker-backed Floci service (EKS, RDS, ...), not unique to MWAA.
- Environments loaded from persistent storage on Floci restart are not automatically reconnected to their Docker containers — again, a gap shared by every Docker-backed service today, not specific to MWAA.
