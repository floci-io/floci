#!/bin/bash
set -e

# 1. Start Floci
echo "=== Starting Floci with docker-compose ==="
docker compose up -d --build

# Wait for healthy
echo "Waiting for Floci to be healthy..."
# Portable wait without 'timeout' command
MAX_RETRIES=60
COUNT=0
until curl -sf http://localhost:4566/_floci/health >/dev/null 2>&1; do
  if [ $COUNT -ge $MAX_RETRIES ]; then
    echo "Floci failed to become healthy in time"
    exit 1
  fi
  sleep 1
  COUNT=$((COUNT + 1))
  echo -n "."
done
echo " Floci is up!"

# 2. Network setup (Floci uses floci_default from compose)
NETWORK="floci_default"
DOCKER_GID=$(stat -c '%g' /var/run/docker.sock 2>/dev/null || stat -f '%g' /var/run/docker.sock)

# Floci's embedded DNS server resolves *.floci → Floci's IP.
# Passing --dns <floci-ip> to test containers lets the S3 virtual-host client
# send to <bucket>.floci:4566 which Floci DNS resolves correctly. Without this,
# Docker's built-in DNS only resolves the exact service name "floci", not
# wildcard subdomains like my-bucket.floci.
FLOCI_CONTAINER=$(docker compose ps -q floci 2>/dev/null | head -1)
FLOCI_IP=$(docker inspect -f "{{.NetworkSettings.Networks.${NETWORK}.IPAddress}}" "$FLOCI_CONTAINER" 2>/dev/null || true)

# 3. Test suites
SUITES=(
  "sdk-test-python"
  "sdk-test-node"
  "sdk-test-java"
  "sdk-test-go"
  "sdk-test-awscli"
  "compat-cdk"
  "compat-terraform"
  "compat-opentofu"
)

# results dir
mkdir -p test-results

for suite in "${SUITES[@]}"; do
  echo "=== Running $suite in Docker ==="
  
  IMAGE_NAME="compat-$suite"
  
  # Build
  docker build -q -t "$IMAGE_NAME" "compatibility-tests/$suite"
  
  # Build DNS args: if we resolved Floci's IP, inject it as the DNS server so
  # wildcard subdomains like <bucket>.floci resolve inside test containers.
  DNS_ARGS=()
  if [ -n "$FLOCI_IP" ]; then
    DNS_ARGS=(--dns "$FLOCI_IP")
  fi

  # Run
  docker run --rm --network "$NETWORK" \
    "${DNS_ARGS[@]}" \
    -e FLOCI_ENDPOINT=http://floci:4566 \
    -e FLOCI_S3_VHOST_ENDPOINT=http://floci:4566 \
    -v "$(pwd)/test-results:/results" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    --group-add "$DOCKER_GID" \
    "$IMAGE_NAME" || echo "Test suite $suite failed"
done

echo "=== All Docker tests completed ==="
