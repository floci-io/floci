#!/bin/sh
# Unit tests for localstack-parity.sh.
# Run directly: sh docker/test-localstack-parity.sh
# Exit 0 on success, non-zero on first failure.

set -eu

SCRIPT="$(dirname "$0")/localstack-parity.sh"
PASS=0
FAIL=0

# Run the parity script in a subshell with a given environment and print the
# value of a single variable. Arguments: VAR_NAME [ENV_KEY=VALUE ...]
_run() {
    var="$1"; shift
    env -i "$@" sh -c ". '${SCRIPT}'; printf '%s' \"\${${var}:-}\""
}

# Assert that _run produces an expected value.
assert_eq() {
    desc="$1"; expected="$2"; actual="$3"
    if [ "${actual}" = "${expected}" ]; then
        printf '[PASS] %s\n' "${desc}"
        PASS=$((PASS + 1))
    else
        printf '[FAIL] %s\n  expected: %s\n  actual:   %s\n' "${desc}" "${expected}" "${actual}"
        FAIL=$((FAIL + 1))
    fi
}

# --- PERSISTENCE ---
assert_eq "PERSISTENCE=1 sets FLOCI_STORAGE_MODE=persistent" \
    "persistent" \
    "$(_run FLOCI_STORAGE_MODE PERSISTENCE=1)"

assert_eq "PERSISTENCE=true sets FLOCI_STORAGE_MODE=persistent" \
    "persistent" \
    "$(_run FLOCI_STORAGE_MODE PERSISTENCE=true)"

assert_eq "PERSIST_STATE=1 sets FLOCI_STORAGE_MODE=persistent" \
    "persistent" \
    "$(_run FLOCI_STORAGE_MODE PERSIST_STATE=1)"

assert_eq "FLOCI_STORAGE_MODE wins over PERSISTENCE" \
    "hybrid" \
    "$(_run FLOCI_STORAGE_MODE PERSISTENCE=1 FLOCI_STORAGE_MODE=hybrid)"

# --- EDGE_PORT ---
assert_eq "EDGE_PORT sets FLOCI_PORT" \
    "4567" \
    "$(_run FLOCI_PORT EDGE_PORT=4567)"

assert_eq "FLOCI_PORT wins over EDGE_PORT" \
    "4568" \
    "$(_run FLOCI_PORT EDGE_PORT=4567 FLOCI_PORT=4568)"

# --- LOCALSTACK_HOST / LOCALSTACK_HOSTNAME ---
assert_eq "LOCALSTACK_HOST sets FLOCI_HOSTNAME" \
    "myhost" \
    "$(_run FLOCI_HOSTNAME LOCALSTACK_HOST=myhost)"

assert_eq "LOCALSTACK_HOSTNAME sets FLOCI_HOSTNAME when LOCALSTACK_HOST unset" \
    "myhost2" \
    "$(_run FLOCI_HOSTNAME LOCALSTACK_HOSTNAME=myhost2)"

assert_eq "LOCALSTACK_HOST takes priority over LOCALSTACK_HOSTNAME" \
    "primary" \
    "$(_run FLOCI_HOSTNAME LOCALSTACK_HOST=primary LOCALSTACK_HOSTNAME=secondary)"

assert_eq "FLOCI_HOSTNAME wins over LOCALSTACK_HOST" \
    "explicit" \
    "$(_run FLOCI_HOSTNAME LOCALSTACK_HOST=myhost FLOCI_HOSTNAME=explicit)"

# --- GATEWAY_LISTEN ---
assert_eq "GATEWAY_LISTEN sets QUARKUS_HTTP_HOST" \
    "0.0.0.0" \
    "$(_run QUARKUS_HTTP_HOST GATEWAY_LISTEN=0.0.0.0)"

assert_eq "GATEWAY_LISTEN allows a non-loopback address" \
    "true" \
    "$(_run FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE GATEWAY_LISTEN=0.0.0.0)"

assert_eq "FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE wins over GATEWAY_LISTEN" \
    "false" \
    "$(_run FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE GATEWAY_LISTEN=0.0.0.0 FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE=false)"

assert_eq "no GATEWAY_LISTEN leaves network exposure unallowed" \
    "" \
    "$(_run FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE)"

# --- LOG LEVEL ---
assert_eq "LS_LOG sets QUARKUS_LOG_LEVEL" \
    "WARN" \
    "$(_run QUARKUS_LOG_LEVEL LS_LOG=WARN)"

assert_eq "DEBUG=1 sets QUARKUS_LOG_LEVEL=DEBUG" \
    "DEBUG" \
    "$(_run QUARKUS_LOG_LEVEL DEBUG=1)"

assert_eq "LS_LOG takes priority over DEBUG=1" \
    "TRACE" \
    "$(_run QUARKUS_LOG_LEVEL LS_LOG=TRACE DEBUG=1)"

assert_eq "QUARKUS_LOG_LEVEL wins over LS_LOG" \
    "INFO" \
    "$(_run QUARKUS_LOG_LEVEL LS_LOG=DEBUG QUARKUS_LOG_LEVEL=INFO)"

# --- LAMBDA ---
assert_eq "LAMBDA_DOCKER_NETWORK sets FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK" \
    "mynet" \
    "$(_run FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK LAMBDA_DOCKER_NETWORK=mynet)"

assert_eq "FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK wins over LAMBDA_DOCKER_NETWORK" \
    "floci-net" \
    "$(_run FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK LAMBDA_DOCKER_NETWORK=mynet FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK=floci-net)"

assert_eq "LAMBDA_REMOVE_CONTAINERS=1 sets FLOCI_SERVICES_LAMBDA_EPHEMERAL=true" \
    "true" \
    "$(_run FLOCI_SERVICES_LAMBDA_EPHEMERAL LAMBDA_REMOVE_CONTAINERS=1)"

assert_eq "LAMBDA_REMOVE_CONTAINERS=true sets FLOCI_SERVICES_LAMBDA_EPHEMERAL=true" \
    "true" \
    "$(_run FLOCI_SERVICES_LAMBDA_EPHEMERAL LAMBDA_REMOVE_CONTAINERS=true)"

assert_eq "FLOCI_SERVICES_LAMBDA_EPHEMERAL wins over LAMBDA_REMOVE_CONTAINERS" \
    "false" \
    "$(_run FLOCI_SERVICES_LAMBDA_EPHEMERAL LAMBDA_REMOVE_CONTAINERS=1 FLOCI_SERVICES_LAMBDA_EPHEMERAL=false)"

# --- DOCKER HOST / NETWORK ---
assert_eq "DOCKER_HOST sets FLOCI_DOCKER_DOCKER_HOST" \
    "unix:///var/run/docker.sock" \
    "$(_run FLOCI_DOCKER_DOCKER_HOST DOCKER_HOST=unix:///var/run/docker.sock)"

assert_eq "DOCKER_NETWORK sets FLOCI_SERVICES_DOCKER_NETWORK" \
    "shared" \
    "$(_run FLOCI_SERVICES_DOCKER_NETWORK DOCKER_NETWORK=shared)"

assert_eq "FLOCI_SERVICES_DOCKER_NETWORK wins over DOCKER_NETWORK" \
    "override" \
    "$(_run FLOCI_SERVICES_DOCKER_NETWORK DOCKER_NETWORK=shared FLOCI_SERVICES_DOCKER_NETWORK=override)"

# --- DNS SUFFIXES ---
assert_eq "DNS suffixes set when FLOCI_DNS_EXTRA_SUFFIXES unset" \
    "localhost.localstack.cloud,localhost.floci.io" \
    "$(_run FLOCI_DNS_EXTRA_SUFFIXES)"

assert_eq "DNS suffixes appended to existing FLOCI_DNS_EXTRA_SUFFIXES" \
    "custom.internal,localhost.localstack.cloud,localhost.floci.io" \
    "$(_run FLOCI_DNS_EXTRA_SUFFIXES FLOCI_DNS_EXTRA_SUFFIXES=custom.internal)"

# --- Summary ---
printf '\nResults: %d passed, %d failed\n' "${PASS}" "${FAIL}"
[ "${FAIL}" -eq 0 ]
