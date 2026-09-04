#!/usr/bin/env bash
# Disposable runtime smoke. No host ports or shared resources are used.
set -euo pipefail
image=${1:?immutable or locally built image required}
arch=${2:?amd64 or arm64 required}
[[ "$arch" == amd64 || "$arch" == arm64 ]]
name="floci-fork-smoke-${arch}-$$"
network="$name"
container=''
cleanup() {
    status=$?
    trap - EXIT
    if [[ -n "$container" ]]; then
        docker rm -fv "$container" >/dev/null || status=1
    fi
    docker network rm "$network" >/dev/null || status=1
    remaining=$(docker container ls -aq --filter "name=^/${name}$") || status=1
    [[ -z "$remaining" ]] || status=1
    remaining=$(docker network ls -q --filter "name=^${network}$") || status=1
    [[ -z "$remaining" ]] || status=1
    exit "$status"
}
docker network create --label floci.fork.smoke=true "$network" >/dev/null
trap cleanup EXIT
container=$(docker create --platform "linux/$arch" --name "$name" --network "$network" \
    --label floci.fork.smoke=true \
    -e FLOCI_SERVICES_ECS_TASK_ROLE_CREDENTIALS_ENABLED=true \
    -e FLOCI_SERVICES_ECS_DOCKER_NETWORK="$network" "$image")
docker network disconnect "$network" "$container"
docker network connect --link-local-ip 169.254.170.2 "$network" "$container"
docker start "$container" >/dev/null
healthy=false
for attempt in $(seq 1 90); do
    if docker exec "$container" wget -q --spider http://localhost:4566/_floci/health; then
        healthy=true
        break
    fi
    sleep 2
done
if [[ "$healthy" != true ]]; then
    docker logs --tail 80 "$container"
    exit 1
fi
docker exec "$container" sh -c 'test "$(awk "/^Uid:/ {print \$2}" /proc/1/status)" = 1001'
docker exec "$container" sh -c 'test -s /usr/share/floci-build/maven-inputs.sha256; test -s /usr/share/floci-build/debian-packages.txt'
docker exec "$container" aws sts get-caller-identity --output json
# Unknown bearer URI must not enumerate or expose any credential.
docker exec -i "$container" python3 - <<'PY'
import urllib.error
import urllib.request
try:
    urllib.request.urlopen("http://169.254.170.2/v2/credentials/" + "A" * 32, timeout=5)
except urllib.error.HTTPError as error:
    assert error.code == 404, "unexpected metadata status"
    with error:
        body = error.read()
    assert b"AccessKeyId" not in body and b"SecretAccessKey" not in body
else:
    raise AssertionError("unknown metadata capability was accepted")
PY
docker exec "$container" cat /proc/sys/net/ipv4/ip_unprivileged_port_start
printf 'Runtime smoke passed: linux/%s; uid1001; health; SDK CLI; private metadata port80; unknown URI denied.\n' "$arch"
