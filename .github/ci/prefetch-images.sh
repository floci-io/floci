#!/usr/bin/env bash
# Best-effort background prefetch of the Docker images this shard's tests use.
# Never fails the build; a pull that loses the race just means the test pulls
# it itself, exactly as before. Pulls only images implied by shard.txt so four
# shards do not each pull all images (Docker Hub rate-limit exposure).
# Usage: prefetch-images.sh <shard.txt>
set -u
SHARD_FILE="${1:-}"
command -v docker >/dev/null 2>&1 || exit 0
[ -r "$SHARD_FILE" ] || exit 0

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
exit 0
