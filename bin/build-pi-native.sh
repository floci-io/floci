#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# build-pi-native.sh — Build a GraalVM native image for Raspberry Pi 3B+
# ──────────────────────────────────────────────────────────────────────
#
# Target: aarch64 / ARMv8-A (Cortex-A53)
# Method: Docker with QEMU user-mode emulation (--platform linux/arm64)
#
# Prerequisites:
#   - Docker with BuildKit
#   - QEMU binfmt handlers:
#       docker run --privileged --rm tonistiigi/binfmt --install arm64
#
# Usage:
#   ./bin/build-pi-native.sh              # build only
#   ./bin/build-pi-native.sh --docker     # build + create Docker image
#
# Output:
#   target/floci-pi-runner                # native aarch64 binary
#   floci/floci:pi                        # Docker image (if --docker)
# ──────────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DOCKER="${1:-}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*" >&2; exit 1; }

# ── Check prerequisites ────────────────────────────────────────────────

info "Checking prerequisites..."

if ! command -v docker &>/dev/null; then
    fail "Docker is required. Install Docker and try again."
fi

# Check QEMU binfmt support for aarch64
if ! docker run --rm --platform linux/arm64 alpine:3.20 uname -m 2>/dev/null | grep -q aarch64; then
    warn "QEMU binfmt handlers for arm64 not detected. Installing..."
    docker run --privileged --rm tonistiigi/binfmt --install arm64
    ok "QEMU binfmt handlers installed."
fi

ok "Prerequisites satisfied."

# ── Build native image via Docker ───────────────────────────────────────

BUILDER_IMAGE="quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25"
CONTAINER_NAME="floci-pi-builder-$$"

info "Building Floci native image for aarch64 (Raspberry Pi 3B+)..."
info "Builder image: $BUILDER_IMAGE"
info "This will take 30-60 minutes with QEMU emulation."
echo ""

# Create a temporary Dockerfile for the build
BUILD_DOCKERFILE=$(mktemp "${PROJECT_ROOT}/Dockerfile.pi-build.XXXXXX")
trap 'rm -f "$BUILD_DOCKERFILE"' EXIT

cat > "$BUILD_DOCKERFILE" <<'DOCKERFILE'
# Multi-stage build: compile on arm64 via QEMU, extract binary
FROM --platform=linux/arm64 quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25 AS build

USER root
WORKDIR /app

# Install Maven 3.9.x
RUN curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.12/binaries/apache-maven-3.9.12-bin.tar.gz | tar xz -C /opt \
    && ln -sf /opt/apache-maven-3.9.12/bin/mvn /usr/local/bin/mvn

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source and build
COPY src ./src
RUN mvn clean package -Dnative -DskipTests -B \
    -Dquarkus.native.additional-build-args-append="-R:MaxHeapSize=512m"

# Output stage — just the binary
FROM --platform=linux/arm64 alpine:3.20 AS output
COPY --from=build /app/target/*-runner /output/floci-pi-runner
DOCKERFILE

info "Starting Docker build (platform: linux/arm64)..."

docker build \
    --platform linux/arm64 \
    -f "$BUILD_DOCKERFILE" \
    -t floci-pi-build:temp \
    --target output \
    "$PROJECT_ROOT"

# Extract the binary
mkdir -p "$PROJECT_ROOT/target"
CONTAINER_ID=$(docker create --platform linux/arm64 floci-pi-build:temp /bin/true)
docker cp "$CONTAINER_ID:/output/floci-pi-runner" "$PROJECT_ROOT/target/floci-pi-runner"
docker rm "$CONTAINER_ID" >/dev/null
docker rmi floci-pi-build:temp >/dev/null 2>&1 || true

chmod +x "$PROJECT_ROOT/target/floci-pi-runner"

ok "Native binary built: target/floci-pi-runner"
ls -lh "$PROJECT_ROOT/target/floci-pi-runner"

# ── Optionally build Docker image ──────────────────────────────────────

if [[ "$BUILD_DOCKER" == "--docker" ]]; then
    info "Building Pi Docker image: floci/floci:pi"

    docker build \
        --platform linux/arm64 \
        -f "$PROJECT_ROOT/docker/Dockerfile.pi" \
        -t floci/floci:pi \
        "$PROJECT_ROOT"

    ok "Docker image built: floci/floci:pi"
    echo ""
    info "To run on your Raspberry Pi:"
    info "  docker save floci/floci:pi | ssh pi@raspberrypi docker load"
    info "  ssh pi@raspberrypi 'docker run -it --rm -p 4566:4566 floci/floci:pi'"
fi

echo ""
ok "Build complete!"
echo ""
info "To copy the binary to your Raspberry Pi:"
info "  scp target/floci-pi-runner pi@raspberrypi:~/"
info ""
info "To run on the Pi:"
info "  # Without TUI:"
info "  ./floci-pi-runner -Dquarkus.profile=pi"
info ""
info "  # With TUI (interactive):"
info "  FLOCI_TUI_ENABLED=true ./floci-pi-runner -Dquarkus.profile=pi"
info ""
info "  # Or use the Docker image:"
info "  docker run -it --rm -p 4566:4566 floci/floci:pi"
