# Kubernetes (Helm)

The Helm chart in [`charts/floci`](https://github.com/floci-io/floci/tree/main/charts/floci) runs
Floci as one pod behind one Service, for CI pipelines and development clusters where Docker Compose
is not an option. Lambda functions run as pods through the
[Kubernetes executor](../services/lambda.md#kubernetes-executor), so the cluster needs no Docker
socket and no privileged containers.

The defaults satisfy the `restricted` Pod Security Standard: Floci runs as UID 1001 with a read-only
root filesystem, no capabilities and the `RuntimeDefault` seccomp profile.

## Install

The chart is not published to a chart repository yet, so install it from a checkout:

```bash
git clone https://github.com/floci-io/floci.git
helm install floci ./floci/charts/floci --namespace floci --create-namespace --wait
helm test floci --namespace floci --logs
```

`helm test` runs the AWS CLI in a pod against the Service: it creates an S3 bucket, writes and reads
an object, and checks that SQS returns a `QueueUrl` on the Service's address. Add
`--set tests.lambda.enabled=true` to the install to also create and invoke a Python Lambda function,
which starts a real Lambda pod.

Test pods are deleted as soon as they pass. Helm 4 prints their logs first; Helm 3 prints logs only
for a failed test, whose pod is kept.

Requirements: Kubernetes 1.25 or later and Helm 3 or 4. The chart has been verified with Helm 4.3.1
and 3.22.1, on kind (Kubernetes 1.34 and 1.37) and OrbStack (Kubernetes 1.36).

## Use it from the cluster

Every pod reaches Floci at `http://<release>-floci.<namespace>.svc.cluster.local:4566`, or
`http://floci.<namespace>.svc.cluster.local:4566` when the release is named `floci`, with any
credentials:

```bash
kubectl run aws-cli --rm -it --restart=Never --image amazon/aws-cli:2.39.6 \
  --env AWS_ACCESS_KEY_ID=test --env AWS_SECRET_ACCESS_KEY=test --env AWS_DEFAULT_REGION=us-east-1 \
  --command -- sh -c '
    endpoint=http://floci.floci.svc.cluster.local:4566
    aws --endpoint-url "$endpoint" s3 mb s3://demo-bucket
    aws --endpoint-url "$endpoint" s3 ls'
```

For an application, set the endpoint once in its environment; AWS SDKs and the AWS CLI v2 read it:

```yaml
env:
  - name: AWS_ENDPOINT_URL
    value: http://floci.floci.svc.cluster.local:4566
  - name: AWS_ACCESS_KEY_ID
    value: test
  - name: AWS_SECRET_ACCESS_KEY
    value: test
  - name: AWS_REGION
    value: us-east-1
```

URLs Floci puts in responses, such as SQS `QueueUrl` and pre-signed S3 URLs, use the Service address
too: the chart sets `FLOCI_BASE_URL` to it. Clients outside the cluster can use a port-forward:

```bash
kubectl port-forward --namespace floci svc/floci 4566:4566
aws --endpoint-url http://localhost:4566 s3 ls
```

or an Ingress (`ingress.enabled=true`); then set `floci.baseUrl` to the Ingress URL so the URLs in
responses point there.

## Configuration

[`values.yaml`](https://github.com/floci-io/floci/blob/main/charts/floci/values.yaml) documents every
value. The ones most installs touch:

| Value | Default | Description |
|---|---|---|
| `image.repository` / `image.tag` | `floci/floci` / chart `appVersion` | Image to run. |
| `image.variant` | `""` | Appended to the tag: `compat` adds Python 3, the AWS CLI and boto3 for init hooks. |
| `floci.baseUrl` | Service URL | `FLOCI_BASE_URL`, the base of URLs in responses. |
| `floci.storage.mode` | `memory`, or `persistent` with persistence | `FLOCI_STORAGE_MODE`. |
| `floci.tls.enabled` | `false` | `FLOCI_TLS_ENABLED`: HTTPS and HTTP on port 4566. |
| `floci.lambda.executor` | `kubernetes` | `kubernetes` runs Lambda pods; `docker` needs a Docker daemon. |
| `floci.lambda.kubernetes.namespace` | release namespace | Where Lambda pods run. |
| `floci.env` | `{}` | Any other `FLOCI_*` variable (see [Environment Variables](../configuration/environment-variables.md)). |
| `initHooks.<phase>` | `{}` | Hook scripts per phase (`boot`, `start`, `ready`, `stop`). |
| `persistence.enabled` | `false` | Keep `/app/data` on a PersistentVolumeClaim. |
| `rbac.create` | `true` | Role and RoleBinding for the Kubernetes executor. |
| `service.type` / `service.port` | `ClusterIP` / `4566` | Service exposure. |
| `ingress.enabled` | `false` | Ingress to the Service. |
| `resources` | 100m CPU, 128Mi requested; 1Gi memory limit | Raise the limit for large in-memory data. |

Any other `FLOCI_*` setting can go in `floci.env`:

```yaml
floci:
  defaultRegion: eu-central-1
  env:
    FLOCI_SERVICES_SQS_ENDPOINT_STRATEGY: aws
    FLOCI_SERVICES_LAMBDA_CONTAINER_IDLE_TIMEOUT_SECONDS: 300
```

The variables the chart sets itself cannot go there or in `extraEnv`, because other manifests follow
the values behind them: the Role and the ServiceAccount token follow `floci.lambda.executor` and
`floci.tls.enabled`, and the container port and the probes are fixed at 4566, so `FLOCI_PORT` is
refused too (`service.port` changes the Service port). The render fails and names the value to set:

```
Error: execution error at (floci/templates/deployment.yaml:68:16): floci.env.FLOCI_TLS_ENABLED: the chart sets this variable; use floci.tls.enabled instead
```

The chart ships a JSON schema, so a misspelled key or a wrong type fails `helm install` instead of
being ignored. Pass account IDs as strings (`--set-string floci.defaultAccountId=123456789012`).

## Lambda on Kubernetes

With the default `floci.lambda.executor=kubernetes`, each Lambda execution environment is a pod in
`floci.lambda.kubernetes.namespace`: an init container downloads the function code from Floci, and
the runtime container polls Floci's Runtime API. See
[Kubernetes executor](../services/lambda.md#kubernetes-executor) for how it works and its limits.

```bash
cat > handler.py <<'PY'
def handler(event, context):
    return {"hello": event.get("name", "world")}
PY
zip function.zip handler.py
aws --endpoint-url http://localhost:4566 lambda create-function --function-name hello \
  --runtime python3.12 --handler handler.handler --zip-file fileb://function.zip \
  --role arn:aws:iam::000000000000:role/lambda
aws --endpoint-url http://localhost:4566 lambda wait function-active-v2 --function-name hello
aws --endpoint-url http://localhost:4566 --cli-read-timeout 600 lambda invoke --function-name hello \
  --cli-binary-format raw-in-base64-out --payload '{"name": "kubernetes"}' out.json
kubectl get pods --namespace floci -l app.kubernetes.io/managed-by=floci
```

The first invocation waits while the node pulls the runtime image (`public.ecr.aws/lambda/python:3.12`
is about 770 MB unpacked); warm pods are reused and removed after the idle timeout.

What the chart sets up for it:

- **RBAC, least privilege.** A Role in the Lambda namespace allows exactly what the executor calls:
  `pods` `create`, `get`, `list`, `delete` and `pods/log` `get`. With TLS it adds `configmaps`
  `create` and, for the one ConfigMap named `floci-lambda-ca`, `get` and `update`. The cleanup hook
  below adds `delete` on that one ConfigMap, with or without TLS. The ServiceAccount token is mounted
  only when the Kubernetes executor is on. With `rbac.create=false`, grant these yourself.
- **A separate Lambda namespace** (`floci.lambda.kubernetes.namespace`). The Role and RoleBinding are
  created there. Lambda pods carry no security context of their own, so a namespace that enforces the
  `restricted` Pod Security Standard rejects them; keep Floci in a `restricted` namespace and run
  Lambda pods in one that allows `baseline`.
- **Network.** Lambda pods connect back to the Floci pod's IP on port 4566 and on the Runtime API
  range 12000 to 12499. A NetworkPolicy around Floci must allow both from the Lambda namespace.
- **Uninstall cleanup.** Helm removes the ServiceAccount while Floci is still shutting down, so Floci
  can no longer delete the Lambda pods that are warm at that moment. A `pre-delete` hook
  (`cleanup.enabled`, on by default) deletes them, and the `floci-lambda-ca` ConfigMap, first. It
  runs under Floci's ServiceAccount, bounds every API call and always exits 0; if its pod cannot
  run at all (for example, the image cannot be pulled), `helm uninstall --no-hooks` skips it.

### One release per Lambda namespace

Floci treats every Lambda pod in its Lambda namespace as its own: the first time a Floci process
launches a Lambda pod, it deletes the ones it did not create (pods labelled
`app.kubernetes.io/managed-by=floci` and `floci.io/service=lambda`), and the uninstall hook deletes
all of them along with the `floci-lambda-ca` ConfigMap, whose name is fixed. Two releases that share
a Lambda namespace would delete each other's warm Lambda pods after every restart and on uninstall,
and with TLS each would overwrite the other's CA in that ConfigMap. Give every release a Lambda
namespace of its own. The default is the release namespace, so two releases in one namespace each
need their own `floci.lambda.kubernetes.namespace`:

```bash
helm install ci-a ./floci/charts/floci -n ci --set floci.lambda.kubernetes.namespace=ci-a-lambda
helm install ci-b ./floci/charts/floci -n ci --set floci.lambda.kubernetes.namespace=ci-b-lambda
```

The Lambda namespaces must exist before the install, since the chart creates its Role in them.

## Persistence

```yaml
persistence:
  enabled: true
  size: 8Gi
```

`/app/data` then lives on a PersistentVolumeClaim and the storage mode becomes `persistent`, so S3,
DynamoDB, SSM and the other services keep their state across pod restarts. Choose another mode with
`floci.storage.mode` (see [Storage Modes](../configuration/storage.md)). The Deployment always uses
the `Recreate` strategy, so a `ReadWriteOnce` claim can move to the new pod. Without persistence
`/app/data` is an `emptyDir`.

## Initialization hooks

Scripts under `initHooks` are mounted into `/etc/floci/init/<phase>.d`
(see [Initialization Hooks](../configuration/initialization-hooks.md)). Scripts that call `aws`
need the `compat` image variant:

```yaml
image:
  variant: compat
initHooks:
  ready:
    01-seed.sh: |
      #!/bin/sh
      set -eu
      aws s3api head-bucket --bucket assets 2>/dev/null || aws s3 mb s3://assets
      aws ssm put-parameter --name /app/mode --value test --type String --overwrite
```

The readiness probe passes only once `/_floci/init` reports the `ready` phase complete, so the
Service sends no traffic before the hooks have seeded their resources. A failing hook stops Floci.
With persistence, hooks run again on every restart, so keep them idempotent as above.

## TLS

`floci.tls.enabled=true` makes port 4566 serve HTTPS as well as HTTP, with Floci's self-signed
certificate. Its CA is at `http://<service>:4566/_floci/ca.pem`. URLs in responses then start with
`https://`. Lambda pods receive the CA through the `floci-lambda-ca` ConfigMap; the
[Kubernetes executor limitations](../services/lambda.md#limitations) explain the certificate names.

## Services that need Docker

RDS, ElastiCache, OpenSearch, MSK, ECS, EKS and the other container-backed services start Docker
containers, and Kubernetes offers no Docker daemon. Without one they keep their control plane only
(Floci logs which services are affected at startup). If Floci can reach a Docker daemon, set
`FLOCI_DOCKER_DOCKER_HOST` in `floci.env` and publish the ports of the proxies Floci runs in its own
process, as `docker-compose.yml` maps them:

```yaml
floci:
  env:
    FLOCI_DOCKER_DOCKER_HOST: tcp://docker.example.internal:2375
service:
  portRanges:
    - name: elasticache
      start: 6379
      end: 6399
    - name: rds
      start: 7001
      end: 7099
```

OpenSearch is not in that list: its data plane is published by the Docker daemon, not by Floci
(see [Ports Reference](../configuration/ports.md)).

## OpenShift

OpenShift assigns each namespace its own UID range, so drop the fixed IDs. Helm merges a map you pass
into the chart's defaults, so a key you leave out keeps its default; set the IDs to `null` instead:

```yaml
podSecurityContext:
  runAsUser: null
  runAsGroup: null
  fsGroup: null
```

The `helm test` pods and the uninstall cleanup hook use the same `podSecurityContext`, so this one
override covers them too.

## Testing the chart

The chart's checks run in the **Helm Chart** workflow (`.github/workflows/helm-chart.yml`) on every
change under `charts/`. Locally:

```bash
helm lint charts/floci --strict
helm unittest charts/floci              # helm-unittest plugin; -u refreshes the snapshots
ct lint --config .github/ct.yaml        # also requires a version bump in Chart.yaml
ct install --config .github/ct.yaml --charts charts/floci   # needs a cluster, for example kind
```

`ct install` installs the chart once per `charts/floci/ci/*-values.yaml` file and runs `helm test`.
The workflow also writes an SPDX SBOM of the deployed image with syft and a grype vulnerability
report, both kept as run artifacts. The report never fails the build: the image is a published
release, and its packages are fixed where the image is built.
