# Control-Plane Endpoints

Floci serves its own endpoints for health, metadata, initialization hooks, state reset and the TLS certificate under `/_floci`. The four Floci emulators (AWS, Azure, GCP, OCI) serve the same contract at the same paths, so one probe or one script works against any of them; the `cloud` field says which emulator answered.

Every path below is also served under `/_localstack`, the LocalStack-compatible prefix, with the same body.

| Endpoint | Answers |
|---|---|
| `GET /_floci/health` | 200 JSON: `status`, `cloud`, `services`, `version`, plus `edition` and `original_edition` |
| `GET /_floci/info` | 200 JSON: the emulator manifest of the [Console Contract](../ui/console-contract.md#the-manifest) |
| `GET /_floci/init` | 200 JSON: the state of the [initialization hooks](initialization-hooks.md) |
| `POST /_floci/state/reset` | 200 `{"status":"OK"}` after clearing every service's state |
| `POST /_floci/state/nuke` | the same as `state/reset` |
| `GET /_floci/tls-cert` | 200 `application/x-pem-file`: the certificate to trust for HTTPS; 404 JSON when TLS is off |
| `GET /_floci/ca.pem` | 200 `text/plain`: the same PEM, served whether or not TLS is on |
| any other path under `/_floci` | 404 JSON `{"error":"NotFound","path":"<path>"}` |

## Health

```bash
curl -s http://localhost:4566/_floci/health
```

```json
{
  "status": "UP",
  "cloud": "aws",
  "services": {"s3": "running", "sqs": "running", "...": "..."},
  "edition": "community",
  "original_edition": "floci-always-free",
  "version": "2.4.0"
}
```

- `status`: `UP`. An emulator that can detect a failed service answers `DOWN` with a 503 and a `problems` object; Floci AWS has no such signal, so it always answers `UP`.
- `cloud`: `aws`. The other emulators answer `azure`, `gcp` and `oci`.
- `services`: each service name with `running` (enabled) or `available` (disabled).

`GET /health` serves the same body.

## Readiness

`GET /_floci/init` reports `completed.ready: true` once the ready hooks have run. Wait on it, rather than on health, when a test needs the resources those hooks create. The Docker image's `HEALTHCHECK` uses it.

## State reset

`POST /_floci/state/reset` clears every service's state, stops the containers Floci started, and runs each service's bootstrap again, so the emulator looks freshly started. It answers 409 and changes nothing when a service cannot be reset, such as DynamoDB with the DynamoDB Local backend.

## TLS certificate

```bash
curl -s http://localhost:4566/_floci/tls-cert -o floci-ca.pem
```

With `FLOCI_TLS_ENABLED=true` this is the PEM described in [TLS / HTTPS](tls.md): the local CA, or your certificate followed by the local CA when you provide one. With TLS off it answers 404:

```json
{"tlsEnabled": false, "error": "TLS is not enabled", "message": "..."}
```

`GET /_floci/ca.pem` keeps serving the local CA either way, because it also signs the certificates Floci issues (ACM, IoT).

## Unknown paths

A path under `/_floci` or `/_localstack` that Floci does not serve answers 404 JSON instead of reaching S3:

```json
{"error": "NotFound", "path": "/_floci/helth"}
```

No S3 bucket can be named `_floci`, so this shadows no bucket. A virtual-hosted request (`mybucket.s3.localhost.floci.io`) whose path starts with `/_floci/` or `/_localstack/` is also served by the control plane rather than read as an object key; address such a key path-style instead.

## Other endpoints under `/_floci`

These are specific to Floci AWS and are not part of the shared contract: the [web console](../ui/index.md) at `/_floci/ui`, `/_floci/ecr/gc`, the EKS cluster webhooks under `/_floci/eks/`, the AppSync resolver callback, and the AWS Sign-In consent page. `GET /_floci/diagnose` and `GET /_floci/config` return `{}` for LocalStack compatibility.
