# Initialization Hooks

Floci allows you to execute custom shell scripts when it starts and stops. These scripts can help set up your
environment (creating buckets, populating data, configuring resources, etc.) or tidy up during shutdown.

Hook scripts ending with `.sh` are discovered in the following directories:

- **Startup hooks** (`/etc/floci/init/start.d`) run after Floci services are initialized, but before the environment is marked as ready.
- **Shutdown hooks** (`/etc/floci/init/stop.d`) run when Floci is shutting down, after `destroy()` is triggered.

If a hook directory does not exist or contains no `.sh` scripts, Floci skips it and continues normally.
If the hook path exists but is not a directory, it is ignored.

## Execution

### Execution Environment

Hooks run:

- Inside the Floci runtime environment (same context as Floci services)
- Using the configured shell (default: `/bin/bash`)
- With access to configured services and their endpoints
- With the same environment variables as Floci

Hooks can call Floci service endpoints directly from inside the container. If a hook depends on additional CLI tools,
make sure those tools are available in the runtime image.

### Execution Behavior

Scripts are executed:

- In **lexicographical (alphabetical) order**
- **Sequentially** (one at a time)

When execution order matters, prefix filenames with numbers such as `01-`, `02-`, and `03-`.

Execution uses a fail-fast strategy:

- If a script exits with a non-zero status, remaining hooks are not executed.
- If a script exceeds the configured timeout, it is terminated and remaining hooks are not executed.
- A hook failure marks the corresponding startup or shutdown phase as **failed**.

## Examples

The following examples assume the runtime image includes the AWS CLI and that Floci is reachable at
`http://localhost:4566`.

### Startup Hook

For example, a startup hook could look like this:

```sh
#!/bin/sh
set -eu

aws --endpoint-url http://localhost:4566 \
  ssm put-parameter \
  --name /demo/app/bootstrapped \
  --type String \
  --value true \
  --overwrite
```

This example assumes the script is stored at `/etc/floci/init/start.d/01-seed-parameter.sh`.
It seeds a known SSM parameter during startup so tests or local services can rely on it.

### Shutdown Hook

For example, a shutdown hook could look like this:

```sh
#!/bin/sh
set -eu

aws --endpoint-url http://localhost:4566 \
  ssm delete-parameter \
  --name /demo/app/bootstrapped
```

This example assumes the script is stored at `/etc/floci/init/stop.d/01-cleanup-parameter.sh`.
It removes the parameter during shutdown to leave the environment clean.

## Configuration

You can customize hook behavior via configuration:

| Key                                              | Default     | Description                                                                                                      |
|--------------------------------------------------|-------------|------------------------------------------------------------------------------------------------------------------|
| `floci.init-hooks.shell-executable`              | `/bin/bash` | Shell executable used to run scripts                                                                             |
| `floci.init-hooks.timeout-seconds`               | `30`        | Maximum execution time per script before it is terminated and considered failed                                  |
| `floci.init-hooks.shutdown-grace-period-seconds` | `2`         | Time to wait after calling `destroy()` before forcefully stopping the process (allows cleanup hooks to complete) |

### Example

The following configuration can be useful when startup hooks perform more in-depth setup work, such as seeding test 
data or provisioning resources before an integration test suite starts.

```yaml
floci:
  init-hooks:
    shell-executable: /bin/sh
    timeout-seconds: 60
    shutdown-grace-period-seconds: 10
```

In this example:

- `shell-executable` uses `/bin/sh` for portable POSIX-compatible scripts.
- `timeout-seconds: 60` gives startup hooks more time to complete initialization tasks.
- `shutdown-grace-period-seconds: 10` gives shutdown hooks more time to finish cleanup before Floci stops.