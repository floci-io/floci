#!/usr/bin/env bash
# Best-effort background prefetch of the Docker images this shard's tests use.
# Never fails the build; a pull that loses the race just means the test pulls
# it itself, exactly as before. Pulls only images implied by shard.txt so four
# shards do not each pull all images (Docker Hub rate-limit exposure).
# Usage: prefetch-images.sh <shard.txt>
set -u
SHARD_FILE="${1:-}"
command -v docker >/dev/null 2>&1 || exit 0
[ -r "$SHARD_FILE" ] || { echo "prefetch-images: ${SHARD_FILE:-<none>} not readable, nothing prefetched" >&2; exit 0; }

pull() { docker pull -q "$1" >/dev/null 2>&1 & }

# package token in shard.txt -> images its tests are known to launch
# (measured inline pulls; update alongside image-catalog changes)
grep -q '/lambda/'      "$SHARD_FILE" && { pull public.ecr.aws/lambda/python:3.14; pull public.ecr.aws/lambda/nodejs:18; pull public.ecr.aws/lambda/nodejs:20; pull public.ecr.aws/lambda/python:3.12; }
grep -q '/docdb/'       "$SHARD_FILE" && pull mongo:7.0
grep -q '/neptune/'     "$SHARD_FILE" && { pull neo4j:5-community; pull tinkerpop/gremlin-server:3.7.3; }
grep -q '/elasticache/' "$SHARD_FILE" && { pull valkey/valkey:8; pull memcached:1.6; }
grep -q '/memorydb/'    "$SHARD_FILE" && pull valkey/valkey:8
grep -q '/ecr/'         "$SHARD_FILE" && pull registry:2
grep -q '/ec2/'         "$SHARD_FILE" && { pull busybox:stable; pull alpine:latest; }
grep -q '/common/docker/' "$SHARD_FILE" && pull public.ecr.aws/docker/library/python:3.12-alpine
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
# The Firelens test names the log router image itself rather than taking it from config.
# busybox is the base of the image EcsContainerManagerVolumesFromDockerIntegrationTest builds, and
# a build resolves its base against the registry rather than through ImageCacheService. That pull
# has hit "toomanyrequests: Rate exceeded" and failed the shard three times (PRs 4075, 4142, 4164),
# so it is prefetched for the cache hit rather than for the second it saves.
grep -q '/ecs/container/' "$SHARD_FILE" && { pull public.ecr.aws/aws-observability/aws-for-fluent-bit:3; pull public.ecr.aws/docker/library/busybox:latest; }
exit 0
