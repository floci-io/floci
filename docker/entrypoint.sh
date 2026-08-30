#!/bin/sh
# Starts as root, normalizes the bind-mounted Docker socket's group
# ownership so the unprivileged `floci` user can reach it on any host,
# then re-executes this script as `floci` via gosu. The second invocation
# falls through to exec the user's command.
#
# Why: on native Linux Docker, /var/run/docker.sock is owned by
# root:docker with mode 660 and the docker GID varies by distro. On
# Docker Desktop (macOS/Windows) the socket is typically root:root (GID
# 0). Without the group fix-up below, floci (uid 1001, group 0) can open
# the socket on Docker Desktop but not on native Linux — which breaks
# ECR, Lambda, and RDS service emulation there. Discovering the GID at
# runtime handles every host transparently.

set -eu

if [ "$(id -u)" = '0' ]; then
    if [ -S /var/run/docker.sock ]; then
        sock_gid="$(stat -c '%g' /var/run/docker.sock)"
        if [ "$sock_gid" != '0' ]; then
            group_name="$(getent group "$sock_gid" | cut -d: -f1)" || group_name=''
            if [ -z "$group_name" ]; then
                groupadd -g "$sock_gid" docker-host
                group_name='docker-host'
            fi
            usermod -aG "$group_name" floci
        fi
    fi

    # Re-own state dir for the case where a host bind-mount arrives with
    # ownership the floci user cannot write to. Ignore errors (read-only
    # mounts, unusual filesystems) so the container still starts.
    if [ -d /app/data ]; then
        chown -R floci:root /app/data 2>/dev/null || true
    fi

    exec gosu floci "$0" "$@"
fi

if [ "${LOCALSTACK_PARITY:-true}" != "false" ]; then
    . /usr/local/bin/localstack-parity.sh
fi

# Probe the state dir as the unprivileged user and warn loudly when it is not
# writable (read-only or root-owned bind mounts the chown above could not fix).
# The container still starts: with the default in-memory storage the dir is
# unused, and non-memory modes fail fast at boot with an actionable error.
data_dir="${FLOCI_STORAGE_PERSISTENT_PATH:-/app/data}"
if [ -d "$data_dir" ]; then
    probe="$data_dir/.floci-write-probe.$$"
    if ! ( touch "$probe" && rm -f "$probe" ) 2>/dev/null; then
        cat >&2 <<EOF
**************************************************************************
WARNING: $data_dir is not writable by $(id -un) (uid $(id -u)).
Persisted state cannot be saved there. If FLOCI_STORAGE_MODE (or any
per-service storage mode) is not 'memory', Floci will refuse to start.
Fix the volume mount permissions (it may be read-only or root-owned) or
set FLOCI_STORAGE_PERSISTENT_PATH to a writable directory.
**************************************************************************
EOF
    fi
fi

# Fall back to the image's default command when invoked with no arguments.
# Some tooling re-executes this entrypoint directly with an empty argv —
# Testcontainers' LocalStackContainer does exactly that via its starter
# script — and `exec "$@"` with no argv would make the script reach EOF
# and exit 0 without ever starting the emulator. LocalStack's entrypoint
# ignores its argv entirely, so this keeps drop-in parity.
# The default matches the CMD of the image variant: native images ship
# /app/application, JVM images ship /app/quarkus-app/quarkus-run.jar.
if [ $# -eq 0 ]; then
    if [ -x /app/application ]; then
        set -- /app/application -Dquarkus.http.host=0.0.0.0
    else
        set -- java -jar /app/quarkus-app/quarkus-run.jar -Dquarkus.http.host=0.0.0.0
    fi
fi

exec "$@"
