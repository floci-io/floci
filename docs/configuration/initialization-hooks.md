# Initialization Hooks

Floci can execute shell scripts during startup and shutdown.

## Directories

- Startup hooks are loaded from `/etc/floci/init/start.d`
- Shutdown hooks are loaded from `/etc/floci/init/stop.d`

Only files ending with `.sh` are executed.

## Execution Model

- Scripts are executed in lexicographical order
- Hook scripts are executed sequentially
- Hook execution is fail-fast: execution stops at the first script that fails or times out

## Configuration

| Key | Default | Description |
|---|---|---|
| `floci.init-hooks.shell-executable` | `/bin/bash` | Shell executable used to run hook scripts |
| `floci.init-hooks.timeout-seconds` | `30` | Maximum execution time per hook script before it is considered failed |
| `floci.init-hooks.shutdown-grace-period-seconds` | `2` | Time to wait after `destroy()` before forcing process termination |

### Example

```yaml
floci:
  init-hooks:
    shell-executable: /bin/bash
    timeout-seconds: 30
    shutdown-grace-period-seconds: 2
```

## Docker Compose Example

```yaml
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"
    volumes:
      - ./init/start.d:/etc/floci/init/start.d:ro
      - ./init/stop.d:/etc/floci/init/stop.d:ro
```
