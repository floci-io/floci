#!/bin/bash
# Container health probe. Bash only, because ubi9-micro has no curl.
trim() {
    local value=$1
    value=${value#"${value%%[![:space:]]*}"}
    printf '%s' "${value%"${value##*[![:space:]]}"}"
}

read_gateway_listener() {
    local gateway=${1%%,*}
    local ipv6_pattern='^\[([^]]+)\](:([0-9]+))?$'
    gateway=$(trim "$gateway")
    gateway_host=
    gateway_port=
    if [[ $gateway =~ $ipv6_pattern ]]; then
        gateway_host=${BASH_REMATCH[1]}
        gateway_port=${BASH_REMATCH[3]}
    elif [[ $gateway == *:* ]]; then
        gateway_host=$(trim "${gateway%%:*}")
        gateway_port=$(trim "${gateway#*:}")
    else
        gateway_host=$gateway
    fi
}

host=${QUARKUS_HTTP_HOST:-}
public_port=${FLOCI_PORT:-}
tls_enabled=${FLOCI_TLS_ENABLED:-}
# Match LocalStackParity: a nonempty Floci setting wins, and parity can be disabled.
if [[ ${LOCALSTACK_PARITY:-} != false ]]; then
    read_gateway_listener "${GATEWAY_LISTEN:-}"
    host=${host:-$gateway_host}
    public_port=${public_port:-${EDGE_PORT:-$gateway_port}}
    if [[ -z $tls_enabled && ${USE_SSL:-} == 1 ]]; then
        tls_enabled=true
    fi
fi
# Probe the configured listener. Wildcard binds are reached over loopback.
host=${host:-127.0.0.1}
case "$host" in
    0.0.0.0) host=127.0.0.1 ;;
    ::|\[::\]) host=::1 ;;
esac
port=${QUARKUS_HTTP_PORT:-${public_port:-4566}}
# Floci TLS serves HTTP and HTTPS on its public multiplexed port. Its private
# Quarkus HTTP backend requires a PROXY preface, so do not probe that directly.
if [[ ${tls_enabled,,} == true ]]; then
    port=${public_port:-4566}
fi
exec 3<>"/dev/tcp/$host/$port" || exit 1
printf 'GET /_floci/init HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
IFS= read -r -d '' response <&3
[[ $response == "HTTP/1."?" 200"* ]] || exit 1
[[ $response == *'"ready":true'* ]] && exit 0
# Ready hooks still running. Report healthy 5 s after start anyway, as the first 5 s check always did,
# so a long hook never turns the container unhealthy.
read -r uptime _ < /proc/uptime
stat=$(< /proc/1/stat)
read -ra fields <<< "${stat##*) }"
# Field 22 of /proc/1/stat is the start time in clock ticks, 100 per second like the uptime digits.
(( ${uptime/./} - fields[19] >= 500 ))
