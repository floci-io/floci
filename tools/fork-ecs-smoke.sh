#!/usr/bin/env bash
# Run the disposable ECS task-role credential contract against an explicitly supplied Floci
# image.  The probe image is local and clean; it never receives the Docker socket or a host port.
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
image=${1:?usage: $0 FLOCI_IMAGE [PROBE_IMAGE] [runner options...]}
if [[ $# -ge 2 && ${2:0:1} != '-' ]]; then
    probe_image=$2
    shift 2
else
    probe_image=${FORK_ECS_PROBE_IMAGE:-floci-fork-ecs-probe:local}
    shift
fi

probe_platform=
platform_arg=()
previous=
for arg in "$@"; do
    case "$arg" in
        --platform)
            previous=--platform
            continue
            ;;
        --platform=*)
            probe_platform=${arg#--platform=}
            ;;
        *)
            if [[ "$previous" == '--platform' ]]; then
                probe_platform=$arg
                previous=
            fi
            ;;
    esac
done
if [[ -n "$probe_platform" ]]; then
    case "$probe_platform" in
        amd64|arm64) ;;
        *) echo "--platform must be amd64 or arm64" >&2; exit 2 ;;
    esac
    platform_arg=(--platform "linux/$probe_platform")
else
    platform_arg=()
fi

docker image inspect "$image" >/dev/null
if [[ "${FORK_ECS_SKIP_PROBE_BUILD:-0}" != 1 ]]; then
    docker build --pull=true "${platform_arg[@]}" \
        --file "$repo_dir/docker/Dockerfile.fork-probe" \
        --tag "$probe_image" \
        "$repo_dir"
else
    docker image inspect "$probe_image" >/dev/null
fi

exec python3 "$repo_dir/tools/fork-ecs-smoke.py" "$image" \
    --probe-image "$probe_image" "$@"
