#!/bin/sh
# Starts the compat image with the host's Docker socket mounted and checks what PID 1 ends up as.
# Run directly: sh docker/test-compat-socket.sh <image>
#
# Needs a socket whose group is not root, as on native Linux: on Docker Desktop the socket is
# root:root, group 0 is every container's primary group, and the check could not fail.

set -eu

IMAGE="${1:?usage: test-compat-socket.sh <image>}"
SOCKET=/var/run/docker.sock
NAME="floci-compat-socket-test-$$"
FAIL=0

SOCKET_GID="$(stat -c '%g' "${SOCKET}")"
if [ "${SOCKET_GID}" = '0' ]; then
    echo "${SOCKET} is owned by group 0, so this cannot tell a working image from a broken one." >&2
    exit 1
fi

remove_container() {
    # A stop, not a kill, so Floci removes the helper containers it started on this daemon.
    docker stop -t 10 "${NAME}" >/dev/null 2>&1 || true
    docker rm -f "${NAME}" >/dev/null 2>&1 || true
}
trap remove_container EXIT INT TERM

check() {
    desc="$1"; shift
    if "$@"; then
        printf '[PASS] %s\n' "${desc}"
    else
        printf '[FAIL] %s\n' "${desc}"
        FAIL=1
    fi
}

start() {
    remove_container
    docker run -d --name "${NAME}" -v "${SOCKET}:${SOCKET}" "$@" "${IMAGE}" >/dev/null
    for _ in $(seq 1 60); do
        [ "$(docker inspect -f '{{.State.Health.Status}}' "${NAME}")" = healthy ] && return 0
        sleep 1
    done
    docker logs "${NAME}" 2>&1 | tail -20
    echo "${IMAGE} did not become healthy" >&2
    exit 1
}

pid1_uid() { docker exec "${NAME}" sh -c "awk '/^Uid:/ {print \$2}' /proc/1/status"; }
pid1_has_socket_group() { docker exec "${NAME}" grep -Eq "^Groups:.*\b${SOCKET_GID}\b" /proc/1/status; }
# As uid 1001: root in a container has no CAP_SYS_PTRACE, so it cannot read another user's environ.
pid1_home() { docker exec -u 1001 "${NAME}" sh -c "tr '\0' '\n' < /proc/1/environ | sed -n 's/^HOME=//p'"; }
uid_is() { [ "$(pid1_uid)" = "$1" ]; }
home_is() { [ "$(pid1_home)" = "$1" ]; }
reports_unreachable() { docker logs "${NAME}" 2>&1 | grep -q 'Docker is not reachable'; }

# The availability report runs on its own thread after startup and logs nothing when Docker answers.
# Only this case has something to wait for. A missing warning would not prove the report ran, so the
# other cases do not look for it: the group and uid checks on PID 1 are what prove the fix.
wait_for_unreachable_report() {
    for _ in $(seq 1 30); do
        reports_unreachable && return 0
        sleep 1
    done
    return 1
}

echo "Socket group: ${SOCKET_GID}"

echo '--- started as uid 1001: the entrypoint cannot add the group, so the check can fail'
start --user 1001
check 'PID 1 is uid 1001' uid_is 1001
check 'Floci reports Docker as unreachable' wait_for_unreachable_report

echo '--- default'
start
check 'PID 1 is uid 1001' uid_is 1001
check 'PID 1 has the socket group' pid1_has_socket_group
check 'PID 1 HOME is /app' home_is /app
check 'HOME is writable by uid 1001' docker exec -u 1001 "${NAME}" touch /app/.home-write-probe

echo '--- FLOCI_RUN_AS_ROOT=true'
start -e FLOCI_RUN_AS_ROOT=true
check 'PID 1 is uid 0' uid_is 0

exit "${FAIL}"
