#!/bin/sh
# Maps LocalStack Community environment variables to their Floci equivalents.
# Sourced by entrypoint.sh when LOCALSTACK_PARITY=true.
# Floci vars always win: every mapping uses ${FLOCI_VAR:-<derived>} so an
# explicitly-set Floci var is never overwritten.

# Storage mode — PERSISTENCE=1 / PERSIST_STATE=1 → persistent storage
if [ -n "${PERSISTENCE:-}" ] || [ -n "${PERSIST_STATE:-}" ]; then
    _ls_persist="${PERSISTENCE:-${PERSIST_STATE:-}}"
    if [ "${_ls_persist}" = "1" ] || [ "${_ls_persist}" = "true" ]; then
        export FLOCI_STORAGE_MODE="${FLOCI_STORAGE_MODE:-persistent}"
    fi
fi

# Bind port — EDGE_PORT → FLOCI_PORT
[ -n "${EDGE_PORT:-}" ] && export FLOCI_PORT="${FLOCI_PORT:-${EDGE_PORT}}"

# Hostname returned in response URLs — LOCALSTACK_HOST / LOCALSTACK_HOSTNAME → FLOCI_HOSTNAME
_ls_host="${LOCALSTACK_HOST:-${LOCALSTACK_HOSTNAME:-}}"
[ -n "${_ls_host}" ] && export FLOCI_HOSTNAME="${FLOCI_HOSTNAME:-${_ls_host}}"

# Bind address — GATEWAY_LISTEN → QUARKUS_HTTP_HOST
[ -n "${GATEWAY_LISTEN:-}" ] && export QUARKUS_HTTP_HOST="${QUARKUS_HTTP_HOST:-${GATEWAY_LISTEN}}"

# Log level — LS_LOG / DEBUG=1 → QUARKUS_LOG_LEVEL
if [ -n "${LS_LOG:-}" ]; then
    export QUARKUS_LOG_LEVEL="${QUARKUS_LOG_LEVEL:-${LS_LOG}}"
elif [ "${DEBUG:-}" = "1" ]; then
    export QUARKUS_LOG_LEVEL="${QUARKUS_LOG_LEVEL:-DEBUG}"
fi

# Lambda — LAMBDA_EXECUTOR is intentionally ignored; Floci always runs Lambda in Docker containers.

# Lambda Docker network
[ -n "${LAMBDA_DOCKER_NETWORK:-}" ] && \
    export FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK="${FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK:-${LAMBDA_DOCKER_NETWORK}}"

# Lambda ephemeral containers
if [ "${LAMBDA_REMOVE_CONTAINERS:-}" = "1" ] || [ "${LAMBDA_REMOVE_CONTAINERS:-}" = "true" ]; then
    export FLOCI_SERVICES_LAMBDA_EPHEMERAL="${FLOCI_SERVICES_LAMBDA_EPHEMERAL:-true}"
fi

# LAMBDA_REMOTE_DOCKER — not fully supported.
# Floci's hot-reload is per-function opt-in (S3Bucket=hot-reload), not a global bind-mount mode.
if [ -n "${LAMBDA_REMOTE_DOCKER:-}" ]; then
    echo "[floci-parity] WARNING: LAMBDA_REMOTE_DOCKER is not fully supported by Floci." >&2
    echo "[floci-parity] Use S3Bucket=hot-reload per function instead. See https://floci.io/docs/services/lambda" >&2
fi

# Docker host
[ -n "${DOCKER_HOST:-}" ] && export FLOCI_DOCKER_DOCKER_HOST="${FLOCI_DOCKER_DOCKER_HOST:-${DOCKER_HOST}}"

# Docker network — shared across all container-based services (Lambda, RDS, ElastiCache, MSK, OpenSearch, EKS).
# Per-service overrides (e.g. FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK) take precedence when set.
[ -n "${DOCKER_NETWORK:-}" ] && export FLOCI_SERVICES_DOCKER_NETWORK="${FLOCI_SERVICES_DOCKER_NETWORK:-${DOCKER_NETWORK}}"

# DNS suffixes — register LocalStack and Floci hostname suffixes so that container-to-container
# hostname routing (Function URLs, presigned S3, SQS QueueUrl, etc.) works without manual config.
_parity_suffixes="localhost.localstack.cloud,localhost.floci.io"
if [ -n "${FLOCI_DNS_EXTRA_SUFFIXES:-}" ]; then
    export FLOCI_DNS_EXTRA_SUFFIXES="${FLOCI_DNS_EXTRA_SUFFIXES},${_parity_suffixes}"
else
    export FLOCI_DNS_EXTRA_SUFFIXES="${_parity_suffixes}"
fi

# TLS — USE_SSL=1 → FLOCI_TLS_ENABLED=true
if [ "${USE_SSL:-0}" = "1" ]; then
    export FLOCI_TLS_ENABLED="${FLOCI_TLS_ENABLED:-true}"
fi

# TLS — CUSTOM_SSL_CERT_PATH → FLOCI_TLS_CERT_PATH + FLOCI_TLS_KEY_PATH
# LocalStack uses a single combined PEM (cert+key). Floci accepts it in both fields.
if [ -n "${CUSTOM_SSL_CERT_PATH:-}" ]; then
    export FLOCI_TLS_CERT_PATH="${FLOCI_TLS_CERT_PATH:-${CUSTOM_SSL_CERT_PATH}}"
    export FLOCI_TLS_KEY_PATH="${FLOCI_TLS_KEY_PATH:-${CUSTOM_SSL_CERT_PATH}}"
fi

# SERVICES: intentionally ignored; Floci starts every service in ~24ms.
