# Web Console

Floci ships a browser-based console for inspecting the resources in your local emulator. It runs as
a sidecar container that Floci starts for you on demand.

**Open it at:** `http://localhost:4566/_floci/ui`

The first request starts the console container, so you land on a short "starting the console" page
that redirects as soon as it is ready. Later requests redirect straight away.

## How it works

Floci starts nothing at boot. On the first `/_floci/ui` request it:

1. pulls the console image if it is not present locally,
2. starts it with `-p 4500:4500` on Floci's Docker network,
3. hands it Floci's own reachable address plus the standard AWS environment,
4. polls the console's health endpoint until it reports it can reach Floci,
5. redirects your browser to `http://<host>:4500/`.

A console container that survives a Floci restart is adopted rather than recreated, unless the
address it was built with no longer points at the running Floci, in which case it is replaced so
the console reconnects without you doing anything.

Console container logs are streamed into CloudWatch Logs under the log group `/floci/ui`, so
`awslocal logs tail /floci/ui` shows them.

## Ports

Port `4500` is bound on the host by Docker, not by Floci, so it needs **no** `ports:` entry in your
`docker-compose.yml`. See [Ports Reference](../configuration/ports.md).

## Configuration

All keys live under `floci.services.ui.*`, so `FLOCI_SERVICES_UI_*` as environment variables.

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_UI_ENABLED` | `true` | Enable the console sidecar |
| `FLOCI_SERVICES_UI_IMAGE` | `floci/floci-ui:latest` | Console image to run |
| `FLOCI_SERVICES_UI_CONTAINER_NAME` | `floci-ui` | Name of the sidecar container |
| `FLOCI_SERVICES_UI_PORT` | `4500` | Host port the console is published on |
| `FLOCI_SERVICES_UI_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave the sidecar running when Floci stops |
| `FLOCI_SERVICES_UI_DOCKER_NETWORK` | _(none)_ | Docker network for the sidecar (overrides `FLOCI_SERVICES_DOCKER_NETWORK`) |
| `FLOCI_SERVICES_UI_ENDPOINT` | _(derived)_ | Floci endpoint handed to the console, instead of deriving it from the Docker host and TLS settings |
| `FLOCI_SERVICES_UI_EXTRA_ENV` | _(none)_ | Extra `KEY=VALUE` entries for the console, comma-separated (escape a literal comma as `\,`). Applied last, so an entry may override an injected default |
| `FLOCI_SERVICES_UI_INSECURE_SKIP_TLS_VERIFY` | `false` | Have the console skip TLS verification on its connection to Floci |

The next group exists only for a console that neither follows the
[console contract](console-contract.md) nor describes itself in its image labels. Leave them unset
otherwise: setting one overrides both of those discovery paths.

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_UI_INTERNAL_PORT` | _(discovered, else `4500`)_ | Port the console listens on inside its container |
| `FLOCI_SERVICES_UI_ENDPOINT_ENV` | _(none)_ | An extra variable to repeat the Floci endpoint in, for a console reading neither `AWS_ENDPOINT_URL` nor `FLOCI_ENDPOINT` |
| `FLOCI_SERVICES_UI_STATUS_PATH` | _(discovered, else `/api/health`)_ | Path the readiness probe requests |
| `FLOCI_SERVICES_UI_STATUS_READY_FIELD` | _(discovered, else `status`)_ | JSON field reporting readiness. Set to `none` for a console whose health endpoint is a plain liveness check, which makes any `200` count as ready |
| `FLOCI_SERVICES_UI_STATUS_READY_VALUE` | _(discovered, else `ok`)_ | Value of that field meaning the console reached Floci |
| `FLOCI_SERVICES_UI_STATUS_UNAVAILABLE_VALUE` | _(discovered, else `unavailable`)_ | Value meaning the console is up but cannot reach Floci |

## Running a different console

Any console implementing the [Floci console contract](console-contract.md) works with nothing but
an image name:

```yaml
environment:
  FLOCI_SERVICES_UI_IMAGE: acme/my-console:1.0
```

A console that listens elsewhere, or answers a different health route, needs that much said. For
[StackPort](https://github.com/DaviReisVieira/stackport), which listens on 8080 and answers
`/api/health`:

```yaml
environment:
  FLOCI_SERVICES_UI_IMAGE: davireis/stackport:latest
  FLOCI_SERVICES_UI_CONTAINER_NAME: floci-stackport
  FLOCI_SERVICES_UI_PORT: "8080"
  FLOCI_SERVICES_UI_INTERNAL_PORT: "8080"
```

### Example: Floci Dash

[Floci Dash](https://github.com/ofsazib/floci-dash) is an AWS Console-style dashboard for Floci. It
deviates from the contract in exactly two places, so two settings are all it takes:

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_HOSTNAME: floci
      FLOCI_BASE_URL: http://floci:4566
      FLOCI_SERVICES_UI_IMAGE: ghcr.io/ofsazib/floci-dash:latest
      FLOCI_SERVICES_UI_CONTAINER_NAME: floci-dash
      # Floci Dash reads its endpoint from FLOCI_URL, not AWS_ENDPOINT_URL
      FLOCI_SERVICES_UI_ENDPOINT_ENV: FLOCI_URL
      # and answers /api/healthz rather than /api/health
      FLOCI_SERVICES_UI_STATUS_PATH: /api/healthz
```

Then open `http://localhost:4566/_floci/ui` and Floci starts the dashboard on port 4500.

Nothing else is needed, and it is worth seeing why:

- **The port is not configured.** Floci Dash defaults to 3000 but honours `PORT`, which Floci sets
  to the resolved internal port, so it listens on 4500 and Floci publishes it there. A console that
  ignores `PORT` needs `FLOCI_SERVICES_UI_INTERNAL_PORT` instead, as StackPort does above.
- **The health fields are not configured.** Floci Dash answers `{"status": "ok"}`, which already
  matches the contract's `status` field and `ok` value. Only its path differs.
- **The endpoint is not configured, only its name.** Floci resolves its own reachable address at
  start time; `FLOCI_SERVICES_UI_ENDPOINT_ENV` just repeats that value under a second name.

Two caveats:

- Its EC2 terminal feature wants the Docker socket, which a Floci-managed sidecar is not given. The
  rest of the dashboard works; that one feature does not.
- `/api/healthz` is a liveness check: it answers `{"status": "ok"}` whether or not the dashboard can
  reach Floci. So Floci reports it ready as soon as it is up. If the dashboard loads but every page
  is empty, check that `FLOCI_SERVICES_UI_ENDPOINT_ENV: FLOCI_URL` is actually set. Without it the
  dashboard falls back to its own `localhost:4566`, which inside its container is nothing, and the
  readiness probe cannot tell.

!!! tip "Two labels would remove both settings"

    A console can carry its own quirks in its image instead of asking every operator to configure
    them. Adding `io.floci.console.contract="1"`, `io.floci.console.health-path="/api/healthz"` and
    `io.floci.console.endpoint-env="FLOCI_URL"` to the image would reduce the above to
    `FLOCI_SERVICES_UI_IMAGE` alone. See [Console Contract v1](console-contract.md#self-description-labels).

The endpoint itself is never configured by hand. Floci resolves its own reachable address at start
time and injects it as `AWS_ENDPOINT_URL`, which is also what tells a console left over from a
previous run that it is pointing at a dead address.

## Troubleshooting

!!! note "The page says the image is unavailable"

    Floci could not pull the console image. Pull it yourself to see the real error:
    `docker pull floci/floci-ui:latest`.

!!! note "The page says Floci could not reach the container runtime"

    Floci needs the Docker socket. Check that `/var/run/docker.sock` is mounted into the Floci
    container. On SELinux hosts the bind-mount may need relabeling (`:z`) or
    `--security-opt label=disable`. See [Docker Configuration](../configuration/docker.md).

!!! note "The console loads but says it cannot reach Floci"

    The message includes the address the console tried. If Floci is running with TLS, the derived
    endpoint may be `https://<container-ip>:4566`, which fails certificate verification because
    Floci's self-signed certificate carries no IP SAN for its own container address. Either point
    the console at the plain-HTTP form, which reaches the same TLS-enabled listener:

    ```yaml
    FLOCI_SERVICES_UI_ENDPOINT: http://floci:4566
    ```

    or set `FLOCI_SERVICES_UI_INSECURE_SKIP_TLS_VERIFY: "true"`. See [TLS / HTTPS](../configuration/tls.md).

!!! note "The console keeps saying it is starting"

    Check the container's own logs: `awslocal logs tail /floci/ui --follow`, or
    `docker logs floci-ui`. A console whose health endpoint is at a different path than Floci is
    probing never reports ready; `FLOCI_SERVICES_UI_STATUS_PATH` fixes that.
