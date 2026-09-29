#!/usr/bin/env bash
# Best-effort background prefetch of the Docker images this shard's tests use.
# Never fails the build; a pull that loses the race just means the test pulls
# it itself, exactly as before. Pulls only images implied by shard.txt so four
# shards do not each pull all images (registry rate-limit exposure).
# ECR Public counts anonymous transfer per runner IP, so an image is keyed on the classes that
# start it whenever its package is spread over every shard (Lambda, ECS), and a key that names a
# class is checked against src/test/java so a rename cannot silently turn the prefetch off. The
# images the tests start come from Docker Hub wherever it carries them, which GitHub-hosted
# runners pull without a limit; TestImages says why, and why the Lambda runtimes cannot.
# Each pull writes one line to $PREFETCH_LOG (prefetch-images.log next to shard.txt by default):
# "prefetched <image>" or "prefetch failed <image>: <last line of docker's output>", timestamped,
# so a test that pulled the image itself can be told apart from a prefetch that failed or was
# still downloading. The lines go to a file because the pulls outlive the workflow step that
# started them and the step's stdout is closed by then.
# Usage: prefetch-images.sh <shard.txt>
set -u
SHARD_FILE="${1:-}"
PREFETCH_LOG="${PREFETCH_LOG:-$(dirname "${SHARD_FILE:-.}")/prefetch-images.log}"
command -v docker >/dev/null 2>&1 || exit 0
[ -r "$SHARD_FILE" ] || { echo "prefetch-images: ${SHARD_FILE:-<none>} not readable, nothing prefetched" >&2; exit 0; }
: > "$PREFETCH_LOG"

pull_and_log() {
    if output="$(docker pull -q "$1" 2>&1)"; then
        echo "$(date -u +%H:%M:%S) prefetched $1" >> "$PREFETCH_LOG"
    else
        echo "$(date -u +%H:%M:%S) prefetch failed $1: $(printf '%s\n' "$output" | tail -n 1)" >> "$PREFETCH_LOG"
    fi
}
# Docker Hub and other registries: each pull in its own background job, as before. An image two
# entries name (busybox:stable is both the EFS init image and the tests' shell) is pulled once.
REQUESTED=""
pull() {
    case " $REQUESTED " in *" $1 "*) return 0;; esac
    REQUESTED="$REQUESTED $1"
    pull_and_log "$1" &
}
# ECR Public counts unauthenticated pulls per second as well as bytes, so its images are queued
# and pulled one after another, a second apart, in a single background job instead of all at once.
ECR_PUBLIC_IMAGES=""
ecr() { ECR_PUBLIC_IMAGES="$ECR_PUBLIC_IMAGES $1"; }
# True when the shard runs any of the named classes. A name with no source file is a stale key,
# recorded in the log so the next reader can see why an image stopped being prefetched.
starts() {
    for class in "$@"; do
        [ -n "$(find src/test/java -name "$class.java" -print -quit)" ] \
            || echo "$(date -u +%H:%M:%S) stale prefetch key: no test class named $class" >> "$PREFETCH_LOG"
    done
    grep -qE "$(IFS='|'; echo "$*")" "$SHARD_FILE"
}
# The image a test constant pins, or empty (and a log line) when the constant cannot be read.
test_image() {
    image="$(grep -oE "$1 *= *\"[^\"]+\"" src/test/java/io/github/hectorvent/floci/testing/TestImages.java | grep -oE '"[^"]+"' | tr -d '"')"
    [ -n "$image" ] || echo "$(date -u +%H:%M:%S) prefetch skipped: TestImages.$1 not readable" >> "$PREFETCH_LOG"
    echo "$image"
}

# Every Docker-backed test that needs a shell uses the one busybox TestImages pins, so one pull
# serves all of them. It is also the base of the image
# EcsContainerManagerVolumesFromDockerIntegrationTest builds, and a build resolves its base against
# the registry rather than through ImageCacheService, so it is prefetched for the cache hit rather
# than for the second it saves. ContainerPlatformDockerIntegrationTest is not listed: it pulls the
# other architecture, which a prefetch of the host image cannot serve.
BUSYBOX_IMAGE="$(test_image BUSYBOX)"
starts ContainerHostNetworkDockerIntegrationTest EcsServiceDiscoveryDockerIntegrationTest \
       EcsContainerManagerEfsIsolationDockerIntegrationTest EcsContainerManagerFirelensDockerIntegrationTest \
       EcsContainerManagerStatsDockerIntegrationTest EcsContainerManagerVolumesFromDockerIntegrationTest \
       EcsExecChannelDockerIntegrationTest EcsLeftoverContainersDockerIntegrationTest BatchDockerRunnerDockerIntegrationTest \
       SageMakerDockerIntegrationTest \
    && [ -n "$BUSYBOX_IMAGE" ] && pull "$BUSYBOX_IMAGE"
PYTHON_ALPINE_IMAGE="$(test_image PYTHON_ALPINE)"
starts ContainerCaBundleDockerIntegrationTest EcsCredentialsProxyDockerIntegrationTest SageMakerDockerIntegrationTest \
    && [ -n "$PYTHON_ALPINE_IMAGE" ] && pull "$PYTHON_ALPINE_IMAGE"
# The Firelens test names the log router image itself rather than taking it from config.
FLUENT_BIT_IMAGE="$(test_image FLUENT_BIT)"
starts EcsContainerManagerFirelensDockerIntegrationTest && [ -n "$FLUENT_BIT_IMAGE" ] && pull "$FLUENT_BIT_IMAGE"
# ECR Public images, in queue order: the Lambda runtimes by size. They are the only images left
# on ECR Public; everything Docker Hub also carries is pulled from there (see TestImages).
# Lambda runtimes, measured from a full four-shard run by pairing each ImageCacheService
# "Pulling image" / "Image already present locally" line with the class that logged it.
# nodejs:20 is the runtime the API Gateway, ELBv2 and Lambda integration tests resolve and every
# shard reaches it; the two Python runtimes each have one Docker-backed class; nodejs:18 was
# pulled by every shard and started by none. A class missed here pulls inline, as before.
grep -qE '/apigateway/|/apigatewayv2/|/lambda/' "$SHARD_FILE" && ecr public.ecr.aws/lambda/nodejs:20
starts SwfLambdaIntegrationTest && ecr public.ecr.aws/lambda/python:3.12
starts ElbV2LambdaTargetDataPlaneIntegrationTest && ecr public.ecr.aws/lambda/python:3.14

# package token in shard.txt -> images its tests are known to launch
# (measured inline pulls; update alongside image-catalog changes)
grep -q '/docdb/'       "$SHARD_FILE" && pull mongo:7.0
grep -q '/neptune/'     "$SHARD_FILE" && { pull neo4j:5-community; pull tinkerpop/gremlin-server:3.7.3; }
grep -q '/elasticache/' "$SHARD_FILE" && { pull valkey/valkey:8; pull memcached:1.6; }
grep -q '/memorydb/'    "$SHARD_FILE" && pull valkey/valkey:8
grep -q '/ecr/'         "$SHARD_FILE" && pull registry:2
grep -q '/ec2/'         "$SHARD_FILE" && { pull busybox:stable; pull alpine:latest; }
# The Cedar sidecar pin lives in application.yml; read it rather than duplicate it.
CEDAR_IMAGE="$(grep -oE 'cedar-image: *"[^"]+"' src/main/resources/application.yml | grep -oE '"[^"]+"' | tr -d '"')"
grep -q '/verifiedpermissions/' "$SHARD_FILE" && [ -n "$CEDAR_IMAGE" ] && pull "$CEDAR_IMAGE"
# Same for the GraphQL sidecar. AppSyncCfnIntegrationTest also starts it but lives under
# services/cloudformation/, so it needs its own token alongside the /appsync/ path match.
GRAPHQL_IMAGE="$(grep -oE 'graphql-image: *"[^"]+"' src/main/resources/application.yml | grep -oE '"[^"]+"' | tr -d '"')"
grep -qE '/appsync/|AppSyncCfnIntegrationTest' "$SHARD_FILE" && [ -n "$GRAPHQL_IMAGE" ] && pull "$GRAPHQL_IMAGE"
# The Node sidecar that evaluates APPSYNC_JS resolver code; the pin lives in application.yml too.
# Matched on the one class that starts it rather than on /appsync/, since every other test in that
# package is a unit test: a path match would have all four shards pull an image one of them uses.
JS_RUNTIME_IMAGE="$(grep -oE 'image: *"node:[^"]+"' src/main/resources/application.yml | grep -oE '"[^"]+"' | tr -d '"')"
grep -q 'AppSyncJsResolverDockerIntegrationTest' "$SHARD_FILE" && [ -n "$JS_RUNTIME_IMAGE" ] && pull "$JS_RUNTIME_IMAGE"
# RdsAwsIntegrationTest is the one class that starts SQL Server (about 1.5 GB); the pin lives in
# application.yml. The other services/rds classes mock the container layer.
SQLSERVER_IMAGE="$(grep -oE 'default-sql-server-image: *"[^"]+"' src/main/resources/application.yml | grep -oE '"[^"]+"' | tr -d '"')"
grep -q 'RdsAwsIntegrationTest' "$SHARD_FILE" && [ -n "$SQLSERVER_IMAGE" ] && pull "$SQLSERVER_IMAGE"
# Postgres is the most-pulled image in CI: Redshift pins it in application.yml, RDS adapts
# postgres:<version>-alpine to the engine version its tests request (16.3), and the CloudFormation
# provisioner tests reach it through Redshift. It was pulled inline in all four shards.
REDSHIFT_PG_IMAGE="$(grep -oE 'image-version: *postgres:[^ ]+' src/main/resources/application.yml | awk '{print $2}')"
grep -qE '/redshift/|/cloudformation/' "$SHARD_FILE" && [ -n "$REDSHIFT_PG_IMAGE" ] && pull "$REDSHIFT_PG_IMAGE"
grep -q '/rds/' "$SHARD_FILE" && pull postgres:16.3-alpine
# The InfluxDB and k3s pins live in application.yml like the sidecars above.
INFLUX_IMAGE="$(grep -oE 'default-image: *"influxdb:[^"]+"' src/main/resources/application.yml | grep -oE '"[^"]+"' | tr -d '"')"
grep -q '/timestreaminfluxdb/' "$SHARD_FILE" && [ -n "$INFLUX_IMAGE" ] && pull "$INFLUX_IMAGE"
K3S_IMAGE="$(grep -oE 'default-image: *"rancher/k3s:[^"]+"' src/main/resources/application.yml | grep -oE '"[^"]+"' | tr -d '"')"
grep -q '/eks/' "$SHARD_FILE" && [ -n "$K3S_IMAGE" ] && pull "$K3S_IMAGE"
if [ -n "$ECR_PUBLIC_IMAGES" ]; then
    { for image in $ECR_PUBLIC_IMAGES; do pull_and_log "$image"; sleep 1; done; } &
fi
exit 0
