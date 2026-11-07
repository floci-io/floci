# Floci Helm chart

Runs [Floci](https://github.com/floci-io/floci), the local AWS emulator, as one pod behind one
Service on port 4566, for CI pipelines and development clusters. Lambda functions run as pods through
Floci's Kubernetes executor, so the cluster needs no Docker socket and no privileged containers.

```bash
git clone https://github.com/floci-io/floci.git
helm install floci ./floci/charts/floci --namespace floci --create-namespace --wait
helm test floci --namespace floci --logs
```

Pods then reach Floci at `http://floci.floci.svc.cluster.local:4566` with any credentials:

```bash
aws --endpoint-url http://floci.floci.svc.cluster.local:4566 s3 mb s3://my-bucket
```

What the chart provides:

- Deployment with the `Recreate` strategy, an optional PersistentVolumeClaim for `/app/data`, and
  probes on `/_floci/health`; readiness waits for the `ready` init hooks (`/_floci/init`).
- Service on 4566, optional port ranges for the TCP proxies of container-backed services, optional
  Ingress.
- ServiceAccount, Role and RoleBinding with exactly the permissions the Kubernetes Lambda executor
  uses, in the release namespace or a separate Lambda namespace.
- `restricted` Pod Security Standard defaults: non-root, read-only root filesystem, no capabilities.
- `FLOCI_*` settings from values, init hook scripts from values, a JSON schema for the values.
- `helm test` pods that exercise S3, SQS and, optionally, a Lambda invocation, and a pre-delete hook
  that removes the Lambda pods Floci created.

Every value is documented in [`values.yaml`](values.yaml). The full guide, with Lambda, persistence,
TLS and init hook examples, is [Kubernetes (Helm)](https://floci.io/floci/getting-started/kubernetes/)
(`docs/getting-started/kubernetes.md`).

## Development

```bash
helm lint charts/floci --strict
helm unittest charts/floci
ct lint --config .github/ct.yaml
ct install --config .github/ct.yaml --charts charts/floci
```

Bump `version` in `Chart.yaml` with every change to the chart; `ct lint` enforces it.
