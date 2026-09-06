# TLS / HTTPS

Floci supports optional TLS, enabling `https://` for all REST/JSON/Query endpoints and `wss://` for WebSocket connections. Both HTTP and HTTPS are served simultaneously (LocalStack parity).

## Quick Start

```bash
docker run -e FLOCI_TLS_ENABLED=true -p 4566:4566 floci/floci:latest
```

Then point your SDK at `https://localhost:4566` and trust Floci's local CA in the processes that talk to it. Every certificate Floci issues (its HTTPS endpoint, every ACM certificate and every IoT device certificate) chains to that one CA:

```bash
curl -s http://localhost:4566/_floci/ca.pem -o floci-root-ca.pem

# AWS CLI and every AWS SDK
export AWS_CA_BUNDLE=$PWD/floci-root-ca.pem
aws --endpoint-url https://localhost:4566 sts get-caller-identity

# Node.js (appends to the built-in roots)
export NODE_EXTRA_CA_CERTS=$PWD/floci-root-ca.pem

# curl, Python, Go on Linux (replaces the default roots for that process only)
export SSL_CERT_FILE=$PWD/floci-root-ca.pem
```

Prefer these per-process variables, and scope them to the shell or the tool (a `.envrc`, a compose `environment:` block) that needs them. They cannot affect anything else on the machine.

The download above is plain HTTP and carries no proof of who sent it, which is fine on loopback and nowhere else. For a client on another host, or over a network you do not control, copy the file out of band instead: read `{persistent-path}/tls/floci-root-ca.crt` from Floci's volume, or `docker cp` it out of the container. Either way, compare the fingerprint before trusting it. Floci logs it at startup as `TLS: ... SHA256 fingerprint AB:CD:...`, and the file's fingerprint is:

```bash
openssl x509 -in floci-root-ca.pem -noout -fingerprint -sha256
```

Installing the CA into the operating system trust store (macOS `security add-trusted-cert`, Linux `update-ca-certificates`) also works and is what Safari, Chrome and Go on macOS need, but understand what it does: every process on the machine then trusts anything signed by the key in `{persistent-path}/tls/floci-root-ca.key`. Keep that file private, treat the CA as a dev-machine secret, and remove it from the store when you stop using Floci:

```bash
# macOS login keychain; remove later with: security delete-certificate -c "Floci Local CA"
security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db floci-root-ca.pem
```

Disabling verification (`--no-verify-ssl`, `verify=False`, `NODE_TLS_REJECT_UNAUTHORIZED=0`) still works but is no longer needed.

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `FLOCI_TLS_ENABLED` | `false` | Enable TLS/HTTPS on the server |
| `FLOCI_TLS_CERT_PATH` | *(unset)* | Path to PEM certificate file |
| `FLOCI_TLS_KEY_PATH` | *(unset)* | Path to PEM private key file |
| `FLOCI_TLS_SELF_SIGNED` | `true` | Auto-generate a server certificate signed by Floci's local CA when no cert/key paths provided |

## Local CA and Server Certificate

When `FLOCI_TLS_ENABLED=true` and no custom certificate is provided, Floci keeps a local root CA at `{persistent-path}/tls/floci-root-ca.crt` (key `floci-root-ca.key`, owner-only) and issues its server certificate `floci-server.crt` from it at startup. The server certificate:

- Is persisted to `{persistent-path}/tls/` and reused across restarts
- Includes `localhost`, `127.0.0.1`, `0.0.0.0`, `*.localhost`, `localhost.floci.io`,
  `*.localhost.floci.io`, `*.execute-api.localhost.floci.io`, and
  `*.execute-api.localhost.localstack.cloud` as Subject Alternative Names (SANs)
- Automatically includes custom hostnames from `FLOCI_HOSTNAME` and `FLOCI_BASE_URL` in the SANs
- Is regenerated when hostname configuration changes between restarts, or when it was not issued by the current CA

The CA is created once and never rotates on its own. If its files are missing, corrupt or do not match each other, Floci generates a new CA, logs a warning, and reissues the server certificate; clients then need the new `ca.pem`.

### Custom Hostname Support

If you set `FLOCI_HOSTNAME` or use a custom host in `FLOCI_BASE_URL`, the server certificate automatically includes those hostnames in its SANs. This is essential for Docker Compose setups where containers reference Floci by service name:

```yaml
services:
  floci:
    image: floci/floci:latest
    environment:
      FLOCI_TLS_ENABLED: "true"
      FLOCI_HOSTNAME: floci
    ports:
      - "4566:4566"

  app:
    environment:
      AWS_ENDPOINT_URL: "https://floci:4566"
      AWS_CA_BUNDLE: /floci/floci-root-ca.pem
```

The generated certificate will include `floci` in its SANs, so TLS validation succeeds when `app` connects to `https://floci:4566`. Fetch `ca.pem` from Floci into `app` (an entrypoint `curl`, or a shared volume mounted from `{persistent-path}/tls/`) so the bundle path above exists.

### Custom Domains Learned at Runtime

A wildcard SAN matches one label only, so `*.localhost.floci.io` covers `api.localhost.floci.io` but not `api.dev.localhost.floci.io`. For names like that, Floci can add an exact hostname to the server certificate while it runs: the certificate is reissued by the local CA with the same key and the new name, and the HTTPS listener switches to it at once. No restart, no new CA, nothing new for clients to trust. Learned names are recorded in `{persistent-path}/tls/floci-server.metadata.json`, so they survive restarts and a regeneration after a `FLOCI_HOSTNAME` change; `POST /_floci/state/reset` drops them.

Only a name under a local suffix is accepted: `localhost`, `localhost.floci.io`, `localhost.localstack.cloud`, `FLOCI_HOSTNAME`, the `FLOCI_BASE_URL` host, and every `FLOCI_DNS_EXTRA_SUFFIXES` entry. Any other name is refused with a warning, so a Floci certificate can never cover a public name. This only applies to the generated certificate; a user-provided one is never changed.

Names are added by the custom domain operations: API Gateway `CreateDomainName`, IoT Core `CreateDomainConfiguration` with a `domainName`, and Cognito `CreateUserPoolDomain` with a `CustomDomainConfig`. The AWS operation succeeds either way; a name outside the local suffixes, or a reissue that fails, is logged as a warning.

### Containers Floci Launches

Every container Floci launches, from Lambda functions and ECS or Batch tasks to the Docker backends behind RDS, ElastiCache, MWAA and Flink, and the Kubernetes Lambda pods, receives `/etc/floci-ca-bundle.pem`: the public root CAs the Floci JVM trusts, followed by the Floci CA (or your certificate file when you provide one). `SSL_CERT_FILE`, `CURL_CA_BUNDLE`, `REQUESTS_CA_BUNDLE`, `NODE_EXTRA_CA_CERTS` and `AWS_CA_BUNDLE` point at it, so curl, Python, Node.js, Go, the AWS CLI and the AWS SDKs that read `AWS_CA_BUNDLE` reach both Floci and public HTTPS sites with no setup in the workload. A variable you set on the function or task definition wins over the injected one. The JVM ignores all of these variables, so a Java workload still needs its own trust store, as shown under SDK Configuration Examples. The bundle is written to `{persistent-path}/tls/floci-ca-bundle.pem` at startup; when it is missing, or when an image has no `/etc` to copy it into, the container starts without the variables and Floci logs a warning.

## User-Provided Certificates

To use your own certificate (e.g., from a corporate CA or mkcert):

```bash
docker run \
  -e FLOCI_TLS_ENABLED=true \
  -e FLOCI_TLS_CERT_PATH=/certs/server.crt \
  -e FLOCI_TLS_KEY_PATH=/certs/server.key \
  -v ./certs:/certs:ro \
  -p 4566:4566 \
  floci/floci:latest
```

When custom certificate paths are provided, `FLOCI_TLS_SELF_SIGNED` is ignored and the HTTPS server uses your certificate; no server certificate is generated. `GET /_floci/ca.pem` then returns a PEM bundle: the certificates in your certificate file first (a private key kept in that file is never served), then Floci's local CA, which is created on first use and signs the certificates Floci itself issues (IoT device certificates). Put the CA or the full chain in your certificate file if clients fetch their trust anchor from Floci; a bare leaf only lets them pin that one certificate.

## WebSocket (wss://)

When TLS is enabled, WebSocket connections automatically use `wss://`:

```
wss://localhost:4566/ws/{apiId}/{stage}
```

No additional configuration is needed: Vert.x handles TLS at the transport layer transparently.

## MQTT over TLS (8883)

When TLS is enabled, the IoT MQTT broker also listens on port 8883 with the same certificate as the HTTPS endpoint, hostnames learned at runtime included. Devices connect with `ssl://` trusting `ca.pem`; see [IoT Core](../services/iot.md#mqtt-over-tls).

## SDK Configuration Examples

With `AWS_CA_BUNDLE` set as in Quick Start, no SDK needs code changes. The examples below trust the CA in code instead, for processes whose environment you do not control.

### AWS SDK for JavaScript v3

```typescript
import { STSClient } from '@aws-sdk/client-sts';
import { NodeHttpHandler } from '@smithy/node-http-handler';
import https from 'node:https';
import { readFileSync } from 'node:fs';

const client = new STSClient({
  endpoint: 'https://localhost:4566',
  region: 'us-east-1',
  credentials: { accessKeyId: 'test', secretAccessKey: 'test' },
  requestHandler: new NodeHttpHandler({
    httpsAgent: new https.Agent({ ca: readFileSync('floci-root-ca.pem') }),
  }),
});
```

Or set the environment variable globally:

```bash
NODE_EXTRA_CA_CERTS=$PWD/floci-root-ca.pem npx vitest run
```

### AWS SDK for Java v2

```java
SdkHttpClient httpClient = ApacheHttpClient.builder()
    .tlsTrustManagersProvider(() -> {
        try {
            var ca = CertificateFactory.getInstance("X.509")
                .generateCertificate(Files.newInputStream(Path.of("floci-root-ca.pem")));
            var trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            trust.setCertificateEntry("floci", ca);
            var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trust);
            return factory.getTrustManagers();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    })
    .build();

StsClient sts = StsClient.builder()
    .endpointOverride(URI.create("https://localhost:4566"))
    .httpClient(httpClient)
    .build();
```

### Python (boto3)

```python
import boto3

client = boto3.client(
    'sts',
    endpoint_url='https://localhost:4566',
    verify='floci-root-ca.pem',  # or export AWS_CA_BUNDLE
    region_name='us-east-1',
    aws_access_key_id='test',
    aws_secret_access_key='test',
)
```

## When Do I Need to Disable TLS Verification?

| Certificate type | Verification disabled? | Why |
|-----------------|----------------------|-----|
| Floci local CA (default) | **No**, once you trust `floci-root-ca.crt` (`GET /_floci/ca.pem`) | One CA covers the HTTPS endpoint and every certificate Floci issues |
| `mkcert` with local CA installed | **No** | `mkcert -install` adds its root CA to the OS trust store |
| Corporate/internal CA already trusted | **No** | Your OS or JVM already trusts the issuing CA |
| Public CA (Let's Encrypt, etc.) | **No** | Trusted by default in all runtimes |

In short: you only need to disable verification when the certificate's issuer is **not** in the client's trust chain. If you provide your own certificate via `FLOCI_TLS_CERT_PATH` and its CA is already trusted by your system, no extra client configuration is needed.

## Troubleshooting

**Certificate errors after changing `FLOCI_HOSTNAME`.**
Floci detects hostname configuration changes and regenerates the server certificate automatically. If you still see errors, delete the `{persistent-path}/tls/` directory and restart. That also creates a new CA, so re-download `ca.pem`.

**`UNABLE_TO_GET_ISSUER_CERT_LOCALLY` or `DEPTH_ZERO_SELF_SIGNED_CERT` in Node.js.**
The process does not trust the Floci CA yet. Set `NODE_EXTRA_CA_CERTS` to `ca.pem` or pass it as the agent's `ca` option as shown above.

**Java `SSLHandshakeException: PKIX path building failed`.**
Import `ca.pem` into the trust store the client uses (`keytool -importcert -file floci-root-ca.pem -alias floci -cacerts`, or a `TrustManager` built from it as shown above). For user-provided certificates from a trusted CA, this should not occur.

**`Go: x509: certificate signed by unknown authority` on macOS.**
Go on macOS ignores `SSL_CERT_FILE` and reads the keychain. Pin the CA in code with `x509.NewCertPool().AppendCertsFromPEM` (the AWS SDK for Go honours `AWS_CA_BUNDLE` regardless), or add it to the login keychain as shown in Quick Start.

**I regenerated `{persistent-path}/tls/`.**
That created a new CA. Re-download `/_floci/ca.pem` wherever you installed the old one.

**Certificate doesn't include my custom hostname.**
Ensure `FLOCI_HOSTNAME` or `FLOCI_BASE_URL` is set *before* Floci starts. The certificate is generated during startup. Check the logs for `TLS: detected custom hostnames: [...]`.
