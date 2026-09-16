# Floci Console Contract v1

**Status:** stable. Implemented by Floci since the release this page shipped in.
**Audience:** authors of a web console that wants to run against Floci.

Floci can run any web console as a sidecar container, not only the one it ships. This page is the
contract between the two. A console that implements it runs with no configuration at all: the
operator sets one image name and Floci does the rest.

If you are running a console rather than writing one, see [Web Console](index.md).

## The short version

Three things make a console a Floci console:

1. It listens for HTTP on the port given in the `PORT` environment variable, default `4500`.
2. It serves `GET /api/health`.
3. It talks to Floci at the URL in `AWS_ENDPOINT_URL`.

Everything else on this page is detail.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are to be interpreted as described in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119).

## What Floci gives the console

Floci starts the console container with the same AWS baseline it gives every container it launches,
so a console built on any AWS SDK is configured entirely by that SDK's ordinary endpoint and
credential discovery.

| Variable | Example | Notes |
|---|---|---|
| `AWS_ENDPOINT_URL` | `http://floci:4566` | The canonical endpoint. Every SDK reads it. |
| `FLOCI_ENDPOINT` | `http://floci:4566` | The same value, for a console that predates the contract. |
| `FLOCI_HOSTNAME` | `floci` | Host part of the endpoint. |
| `AWS_REGION` | `us-east-1` | Floci's default region. |
| `AWS_DEFAULT_REGION` | `us-east-1` | The same value. |
| `AWS_ACCESS_KEY_ID` | `test` | Placeholder. Floci's own credentials are never forwarded. |
| `AWS_SECRET_ACCESS_KEY` | `test` | Placeholder. |
| `AWS_SESSION_TOKEN` | `test` | Placeholder. |
| `PORT` | `4500` | The port the console MUST listen on. |
| `FLOCI_CLOUD` | `aws` | Which emulator this is: `aws`, `gcp`, `az` or `oci`. |
| `FLOCI_TLS_SKIP_VERIFY` | `1` | Present only when the operator opted out of TLS verification. |
| `NODE_TLS_REJECT_UNAUTHORIZED` | `0` | The same instruction in the form a Node or Bun client honours. |

Operator-supplied `extra-env` entries are applied last and may override any of these, except
`PORT` and `AWS_ENDPOINT_URL`. Those two are structural: Floci has already used them to publish
the container's port, to aim the readiness probe, and to decide whether an existing sidecar is
still addressing this Floci. Setting them through `extra-env` would change only the console's
copy, leaving a sidecar that listens where nothing is published or that is recreated on every
check. Floci ignores such an entry and logs which key to use instead:
`floci.services.ui.internal-port` and `floci.services.ui.endpoint` set the environment and the
structural path together.

## What the console gives Floci

### The health endpoint

The console MUST serve `GET /api/health`. Floci polls it while the browser waits on the
interstitial page, and again whenever the console's status is checked.

```json
{
  "status": "ok",
  "endpoint": "http://floci:4566",
  "error": null,
  "console": { "name": "my-console", "version": "1.2.3" }
}
```

| Field | Required | Meaning |
|---|---|---|
| `status` | yes | `ok`, `unavailable`, or anything else for "still coming up" |
| `endpoint` | no | The Floci endpoint the console is actually using. Shown to the user when it cannot be reached. |
| `error` | no | Why the console cannot reach Floci. Shown to the user verbatim. |
| `console` | no | Informational; carried in logs and diagnostics. |

How Floci reads it:

| Response | Floci's conclusion |
|---|---|
| `200` and `status` is `ok` | ready: the browser is redirected to the console |
| `200` and `status` is `unavailable` | not ready, and `endpoint` plus `error` are shown to the user |
| `200` and any other `status` | not ready, keep polling (a cold boot looks like this) |
| anything other than `200`, or no response | not ready, keep polling |

`status` MUST report whether the console can actually reach Floci, not merely whether its own
process is alive. Reporting `ok` while Floci is unreachable turns a diagnosable failure into a
console that loads and then shows nothing. A console that genuinely cannot tell SHOULD say so with
[an empty `health-ready-field` label](#self-description-labels), which makes any `200` count as
ready.

The health endpoint MUST NOT require authentication, and SHOULD respond within a second.

### Reaching Floci

- The console MUST take its endpoint from `AWS_ENDPOINT_URL` and MUST NOT hardcode
  `localhost:4566`. The address is resolved at start time and is usually a container IP on a shared
  Docker network, not localhost.
- The console MUST sign requests with SigV4 using the injected credentials. Floci accepts the
  placeholder values; it does not accept unsigned requests on the AWS APIs.
- The console MUST tolerate an endpoint whose host is a bare IP address and whose scheme is plain
  `http` even when Floci is running with TLS enabled. Floci's self-signed certificate carries no IP
  SAN for its own container address, and its listener does HTTP/HTTPS protocol detection on one
  port, so `http://<container-ip>:4566` is the reachable form. See
  [TLS / HTTPS](../configuration/tls.md).
- When `FLOCI_TLS_SKIP_VERIFY` is set, the console SHOULD skip certificate verification on its
  connection to Floci, and only on that connection.
- The console SHOULD re-resolve rather than cache a dead endpoint. Floci's container IP changes
  when Floci restarts.

### The container

- The console MUST serve its application at `/`. Floci redirects the browser to
  `http://<host>:<published-port>/` and has no way to add a path prefix.
- The console MUST bind `0.0.0.0`, not `127.0.0.1`, or the published port reaches nothing.
- The console MUST NOT require the Docker socket, a mounted volume, or any host path. Floci gives
  it none of them.
- The console SHOULD run as a non-root user.
- The console SHOULD start in well under a minute. Floci polls indefinitely, but the user is
  watching an interstitial page the whole time.
- The console MAY be stateless and MUST tolerate being stopped and recreated at any time: Floci
  recreates it whenever the endpoint it was built with no longer addresses the running Floci.

## Self-description labels

A console whose shape differs from the contract's defaults can say so in its own image labels,
rather than making every operator configure it by hand. Floci reads them from the image at start
time.

Labels are read only when the image declares the contract version Floci implements:

```dockerfile
LABEL io.floci.console.contract="1"
LABEL io.floci.console.name="StackPort"
LABEL io.floci.console.port="8080"
LABEL io.floci.console.health-path="/api/health"
LABEL io.floci.console.health-ready-field="status"
LABEL io.floci.console.health-ready-value="ok"
LABEL io.floci.console.health-unavailable-value="unavailable"
LABEL io.floci.console.endpoint-env="CONSOLE_API_URL"
LABEL io.floci.console.clouds="aws"
LABEL io.floci.console.url="https://example.com/my-console"
```

| Label | Default | Meaning |
|---|---|---|
| `contract` | _(none)_ | Must be `1`. Without it every other label is ignored. |
| `name` | _(none)_ | Display name used in Floci's logs and error messages. |
| `port` | `4500` | Port the console listens on. A value that is not a usable port number is ignored with a warning. |
| `health-path` | `/api/health` | Path Floci probes. A leading slash is added if missing. |
| `health-ready-field` | `status` | Field in the health response that reports readiness. Declare it **empty** to say the endpoint is a plain liveness check, which makes any `200` count as ready. |
| `health-ready-value` | `ok` | Value of that field meaning the console reached Floci. |
| `health-unavailable-value` | `unavailable` | Value meaning the console is up but cannot reach Floci. |
| `endpoint-env` | _(none)_ | An **additional** variable to repeat the endpoint in, for a console that reads neither `AWS_ENDPOINT_URL` nor `FLOCI_ENDPOINT`. |
| `clouds` | `aws` | Comma-separated emulators the console supports. Informational today. |

Only the labels you set are applied; the rest keep their resolved values.

## How Floci resolves a console's shape

Four layers, first match wins, resolved separately for each setting:

1. explicit `floci.services.ui.*` configuration set by the operator
2. `io.floci.console.*` labels on the image
3. a built-in profile for a console Floci recognises by image name
4. the contract v1 defaults

Layer 3 exists for one console today: `floci/floci-ui` answers `/api/clouds/aws/status` with a
`runtime` field rather than `/api/health` with a `status` field, because it predates the contract.
The entry retires once floci-ui serves `/api/health`.

The practical consequence is that an operator running a contract-conformant console sets nothing
beyond the image name, and an operator running a console that neither conforms nor labels itself
can still make it work from configuration alone.

## Conformance checklist

- [ ] Listens on `0.0.0.0:$PORT`, defaulting to `4500` when `PORT` is unset
- [ ] Serves `GET /api/health` unauthenticated, returning JSON with a `status` field
- [ ] `status` is `ok` only when Floci is actually reachable
- [ ] Reports `unavailable` with `endpoint` and `error` when Floci is not reachable
- [ ] Reads `AWS_ENDPOINT_URL`; nothing is hardcoded to `localhost:4566`
- [ ] Signs with SigV4 using the injected credentials
- [ ] Works when the endpoint is `http://<ip>:4566` and when it is `https://<name>:4566`
- [ ] Honours `FLOCI_TLS_SKIP_VERIFY`
- [ ] Serves the application at `/`
- [ ] Needs no Docker socket, no volume, no host path
- [ ] Declares `io.floci.console.contract="1"` and any label whose value differs from the default

## Reference health handlers

=== "Node"

    ```js
    import express from "express";
    import { STSClient, GetCallerIdentityCommand } from "@aws-sdk/client-sts";

    const endpoint = process.env.AWS_ENDPOINT_URL;
    const sts = new STSClient({ endpoint, region: process.env.AWS_REGION });
    const app = express();

    app.get("/api/health", async (_req, res) => {
      try {
        await sts.send(new GetCallerIdentityCommand({}));
        res.json({ status: "ok", endpoint });
      } catch (err) {
        res.json({ status: "unavailable", endpoint, error: String(err) });
      }
    });

    app.listen(Number(process.env.PORT ?? 4500), "0.0.0.0");
    ```

=== "Go"

    ```go
    endpoint := os.Getenv("AWS_ENDPOINT_URL")

    http.HandleFunc("/api/health", func(w http.ResponseWriter, r *http.Request) {
        body := map[string]any{"status": "ok", "endpoint": endpoint}
        if _, err := sts.GetCallerIdentity(r.Context(), &sts.GetCallerIdentityInput{}); err != nil {
            body = map[string]any{"status": "unavailable", "endpoint": endpoint, "error": err.Error()}
        }
        w.Header().Set("Content-Type", "application/json")
        json.NewEncoder(w).Encode(body)
    })

    port := os.Getenv("PORT")
    if port == "" {
        port = "4500"
    }
    http.ListenAndServe("0.0.0.0:"+port, nil)
    ```

## Known consoles

| Console | Image | Notes |
|---|---|---|
| Floci UI | `floci/floci-ui` | Floci's own console, the default. Served by a built-in profile until it adopts `/api/health`. |
| [Floci Dash](https://github.com/ofsazib/floci-dash) | `ghcr.io/ofsazib/floci-dash` | AWS Console-style dashboard. Honours `PORT`; reads `FLOCI_URL` and answers `/api/healthz`, so it needs those two named. [Setup](index.md#example-floci-dash). |
| [StackPort](https://github.com/DaviReisVieira/stackport) | `davireis/stackport` | Third-party; listens on 8080 and answers `/api/health`. |

Built a console against this contract? Open a pull request adding it to this table, or an issue at
[floci-io/floci](https://github.com/floci-io/floci/issues) if something in the contract got in your
way.
