# Floci local-cloud runbook

## What this stack is for

This is a local cloud-emulation environment.  It lets applications, SDKs, CLI
tools, and tests use AWS-, Azure-, or GCP-shaped APIs without accessing a real
cloud account.

The Compose project lives in `/mnt/elements/floci`.  Its persistent state is in
Docker named volumes, not in the project directory.  Do not remove those
volumes unless you intentionally want to erase locally emulated resources.

## Components and why they exist

| Container | Function | Host address | Needed when |
|---|---|---|---|
| `floci-floci-1` | Main AWS-compatible emulator.  It also creates Docker-backed emulated services where required (for example Lambda or RDS). | `http://localhost:4566` (and HTTPS where supported) | Always, for AWS-local development or the console. |
| `floci-floci-az-1` | Azure-compatible emulator. | `http://localhost:4577` | Only for Azure-local development or tests. |
| `floci-floci-gcp-1` | GCP-compatible emulator. | `http://localhost:4588` | Only for GCP-local development or tests. |
| `floci-ui` | Browser console used to inspect and manage local Floci resources.  It is a sidecar started by the main Floci service when the console is opened/requested. | `http://localhost:4500` | Optional; needed only for the browser console. |
| `floci-proxy` | Nginx reverse proxy from `http://localhost:4567` to the main Floci endpoint.  Its config is `/home/camara/floci-proxy/nginx.conf`. | `http://localhost:4567` | Optional; needed only if an application is configured to use port 4567 or needs this proxy layer. |

## Dependency map

```text
AWS tools/tests ────────> floci (4566) <──── floci-ui (4500)
Azure tools/tests ──────> floci-az (4577)
GCP tools/tests ────────> floci-gcp (4588)
Applications using 4567 -> nginx proxy -> floci (4566)
```

The three emulator services share Docker network `floci_default`.  The UI has
the internal endpoints for all three services, so it can display their data.

`healthy` means a container health check is passing. `running` only means the
container process is running; it does *not* mean it is broken or necessarily
less functional. In this installation, Nginx and the UI do not define a Docker
health check.

## Decide what to keep

Keep `floci-floci-1` if you use local AWS APIs.  Keep `floci-ui` only if you use
the graphical console.  Keep `floci-proxy` only if something uses
`localhost:4567`.  Keep `floci-floci-az-1` and `floci-floci-gcp-1` only when you
use their respective cloud emulators.

Before stopping an optional component, find references to its port/name in your
projects and scripts:

```bash
rg -n --hidden --glob '!node_modules/**' --glob '!.git/**' \
  'localhost:(4567|4577|4588)|floci-(az|gcp)|FLOCI_(AZURE|GCP)' \
  /home/camara 2>/dev/null
```

No matches is good evidence that the corresponding optional component is not
actively configured, but it does not prove it is unneeded by a manually run
command or an external machine.

## Normal operations

Run these from the project directory:

```bash
cd /mnt/elements/floci
docker compose ps
docker compose logs --tail=100 floci floci-az floci-gcp
docker compose up -d
docker compose down
```

`docker compose down` stops and removes the Compose containers and network, but
keeps the named data volumes. It does not manage `floci-ui` or `floci-proxy`,
because those were not created by this Compose file.

For a temporary shutdown of an optional Compose emulator:

```bash
docker compose stop floci-az       # Azure
docker compose stop floci-gcp      # GCP
docker compose start floci-az      # start it again
docker compose start floci-gcp
```

For the separately managed optional containers:

```bash
docker stop floci-proxy            # stop proxy temporarily
docker start floci-proxy           # restore it
docker stop floci-ui               # stop console temporarily
```

Do not remove a container or any `floci-*` Docker volume merely to stop it.
Stopping is reversible; volume removal deletes local emulator state.

## Quick health checks

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
curl -fsS http://localhost:4566/_floci/ui >/dev/null && echo 'Floci reachable'
curl -fsS http://localhost:4567/ >/dev/null && echo 'Proxy reachable'
curl -fsS http://localhost:4500/api/health && echo
```

The last two checks apply only if you have chosen to keep the proxy and UI.

## Data and safety

The main emulator has Docker socket access so it can create supporting
containers.  This is deliberate for Docker-backed cloud services, but it is a
high-trust capability: treat images and configuration for this stack as trusted.

Inspect persistent state without deleting it:

```bash
docker volume ls -f label=floci=true
docker system df -v
```

Never use `docker compose down -v`, `docker volume rm`, or `docker system prune
--volumes` unless you have confirmed the affected volumes are disposable.
