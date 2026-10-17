# Initialization Hooks

Floci supports init hook scripts that run at defined points in the startup and shutdown lifecycle.
Use them to seed resources, configure state, or clean up after a run — before or after the AWS APIs are available.

!!! tip "Use the compat image for scripts that call `aws` or `boto3`"
    Scripts that invoke the AWS CLI or Python boto3 require the compat image, which bundles Python 3, the AWS CLI, and boto3 — all pre-configured for `http://localhost:4566`.
    Use `floci/floci:latest-compat` (or a pinned `x.y.z-compat`) instead of the standard image.

## Lifecycle Phases

Floci runs hooks in four ordered phases:

| Phase | When it runs | AWS APIs available? | Directory |
|---|---|---|---|
| **boot** | Before storage is loaded, before services start | No | `boot.d` |
| **start** | After the HTTP server is ready on port 4566 | Yes ✅ | `start.d` |
| **ready** | After all `start` hooks complete | Yes ✅ | `ready.d` |
| **stop** | During pre-shutdown, while HTTP server is still up | Yes ✅ | `stop.d` |

The `/_floci/init` and `/_localstack/init` endpoints reflect each phase's completion status in real time, so external tooling can wait for `ready` before proceeding.

## Hook Directories

Floci merges scripts from two directory trees. The Floci-native tree has priority — if the same filename exists in both, the Floci copy runs and the LocalStack copy is skipped:

| Phase | Floci path | LocalStack-compat path |
|---|---|---|
| boot | `/etc/floci/init/boot.d` | `/etc/localstack/init/boot.d` |
| start | `/etc/floci/init/start.d` | `/etc/localstack/init/start.d` |
| ready | `/etc/floci/init/ready.d` | `/etc/localstack/init/ready.d` |
| stop | `/etc/floci/init/stop.d` or `/etc/floci/init/shutdown.d` | `/etc/localstack/init/shutdown.d` |

The LocalStack-compat paths let existing LocalStack bootstrap scripts work without modification.
Mount them under `/etc/localstack/init/` and they run as-is.

The Floci root is `/etc/floci/init` by default, which is where the image expects the directories to be
mounted. When Floci runs as a binary on the host, set `FLOCI_INIT_HOOKS_DIR` to a directory of your
own: the phase directories are read from under it (for example `~/floci-init/ready.d`).

## Script Types

Floci discovers scripts with the following extensions:

- `.sh` — executed with the configured shell (default `/bin/sh`)
- `.py` — executed with `python3`

On Windows, where there is no `/bin/sh`, Floci also runs:

- `.ps1` with PowerShell (`powershell -NoProfile -ExecutionPolicy Bypass -File`)
- `.cmd` and `.bat` with `cmd /c`
- `.py` with `python` rather than `python3`

`.sh` scripts are skipped on Windows, with a warning naming the file, unless `FLOCI_INIT_HOOKS_SHELL_EXECUTABLE`
points at a shell such as Git Bash. On Linux and macOS, `.ps1`, `.cmd` and `.bat` files are ignored.

Files with any other extension are ignored.

## Execution Order and Behavior

Within each phase, scripts run in **lexicographical order** and **sequentially** (one at a time).
Prefix filenames with numbers to control order: `01-`, `02-`, `03-`, etc.

Floci uses a fail-fast strategy:

- If a script exits with a non-zero status, remaining scripts in that phase are skipped.
- If a script exceeds the configured timeout, it is terminated and treated as a failure.
- A `boot` or `start`/`ready` hook failure causes Floci to shut down.
- A `stop` hook failure is logged but does not prevent shutdown or resource cleanup.

## AWS CLI in Hook Scripts

The compat image (`floci/floci:latest-compat`) includes the AWS CLI and boto3 with the local endpoint pre-configured.
Scripts can call `aws` directly — no `--endpoint-url` flag needed:

```sh
#!/bin/sh
set -eu
aws sqs create-queue --queue-name my-queue
aws s3 mb s3://my-bucket
aws ssm put-parameter --name /app/config --type String --value production
```

The following environment variables are pre-set in the compat image:

| Variable | Value |
|---|---|
| `AWS_DEFAULT_REGION` | `us-east-1` |
| `AWS_ACCESS_KEY_ID` | `test` |
| `AWS_SECRET_ACCESS_KEY` | `test` |
| `AWS_ENDPOINT_URL` | `http://localhost:4566` |
| `AWS_CONFIG_FILE` | `/etc/floci/aws/config` |

Override any of them via `docker run -e` or the compose `environment` block.

Python scripts can use boto3 the same way — the config file is read automatically:

```python
#!/usr/bin/env python3
import boto3

sqs = boto3.client("sqs")
sqs.create_queue(QueueName="my-queue")

s3 = boto3.client("s3")
s3.create_bucket(Bucket="my-bucket")
```

## Mounting Hook Directories

```yaml title="docker-compose.yml"
services:
  floci:
    image: floci/floci:latest-compat
    ports:
      - "4566:4566"
    volumes:
      - ./init/boot.d:/etc/floci/init/boot.d:ro
      - ./init/start.d:/etc/floci/init/start.d:ro
      - ./init/ready.d:/etc/floci/init/ready.d:ro
      - ./init/stop.d:/etc/floci/init/stop.d:ro
```

Phases you don't need can be omitted — Floci skips missing or empty directories.

### Migrating from LocalStack

If you have existing LocalStack init scripts, mount them under the LocalStack-compat paths and they work unchanged:

```yaml title="docker-compose.yml"
volumes:
  - ./localstack-init/ready.d:/etc/localstack/init/ready.d:ro
```

To override individual scripts with Floci-specific versions while keeping the rest:

```yaml title="docker-compose.yml"
volumes:
  - ./localstack-init/ready.d:/etc/localstack/init/ready.d:ro   # existing scripts
  - ./floci-init/ready.d:/etc/floci/init/ready.d:ro             # overrides (take priority)
```

## Examples

### Seed resources on startup

```sh title="/etc/floci/init/ready.d/01-seed.sh"
#!/bin/sh
set -eu
aws sqs create-queue --queue-name orders
aws s3 mb s3://assets
aws ssm put-parameter --name /app/bootstrapped --type String --value true
```

### Seed with Python + boto3

```python title="/etc/floci/init/ready.d/01-seed.py"
#!/usr/bin/env python3
import boto3

boto3.client("sqs").create_queue(QueueName="orders")
boto3.client("s3").create_bucket(Bucket="assets")
```

### Clean up on shutdown

```sh title="/etc/floci/init/stop.d/01-cleanup.sh"
#!/bin/sh
set -eu
aws ssm delete-parameter --name /app/bootstrapped
```

!!! note "Shutdown timing"
    Stop hooks run before the HTTP server shuts down, so Floci's total shutdown time grows by
    the cumulative runtime of all stop hooks. Adjust your orchestrator grace period accordingly
    (e.g. Kubernetes `terminationGracePeriodSeconds`, Docker Compose `stop_grace_period`).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_INIT_HOOKS_DIR` | `/etc/floci/init` | Root of the Floci hook directories (`boot.d`, `start.d`, `ready.d`, `stop.d`, `shutdown.d`) |
| `FLOCI_INIT_HOOKS_SHELL_EXECUTABLE` | `/bin/sh` | Shell used to run `.sh` scripts |
| `FLOCI_INIT_HOOKS_TIMEOUT_SECONDS` | `30` | Maximum runtime per script before it is killed and treated as a failure |
| `FLOCI_INIT_HOOKS_SHUTDOWN_GRACE_PERIOD_SECONDS` | `2` | Extra wait after terminating a timed-out script |

Example — extend the timeout for slow seed scripts:

```bash
FLOCI_INIT_HOOKS_TIMEOUT_SECONDS=120
```

Or in Docker Compose:

```yaml
environment:
  FLOCI_INIT_HOOKS_TIMEOUT_SECONDS: "120"
  FLOCI_INIT_HOOKS_SHELL_EXECUTABLE: /bin/bash
```
