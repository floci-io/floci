#!/usr/bin/env bash
# check-glibc-floor.sh <binary> [floor]
#
# A Linux release binary links glibc dynamically and must run on glibc 2.34 (RHEL 9, Debian 12,
# Ubuntu 22.04, Amazon Linux 2023). Two checks, so a toolchain change that raises the floor fails
# the release instead of the user:
#   1. the newest GLIBC_x.y symbol version the binary requires is at most the floor (readelf reads
#      any architecture's ELF, so this covers the arm64 builds too);
#   2. the binary starts in UBI 9, whose glibc is exactly 2.34 (skipped when Docker is absent).
set -euo pipefail

BIN=$1
FLOOR=${2:-2.34}

newest=$(readelf --version-info --wide "$BIN" | grep -o 'GLIBC_[0-9][0-9.]*' | sed 's/GLIBC_//' | sort -uV | tail -1)
if [ -z "$newest" ]; then
    echo "FAIL: no GLIBC_ symbol versions found in $BIN"
    exit 1
fi
if [ "$(printf '%s\n%s\n' "$newest" "$FLOOR" | sort -V | tail -1)" != "$FLOOR" ]; then
    echo "FAIL: $BIN requires GLIBC_$newest, newer than the $FLOOR floor"
    exit 1
fi
echo "ok: newest required glibc symbol is GLIBC_$newest (floor $FLOOR)"

if ! command -v docker >/dev/null 2>&1; then
    echo "skip: no Docker, start check not run"
    exit 0
fi
docker run --rm -v "$(cd "$(dirname "$BIN")" && pwd)/$(basename "$BIN"):/floci-aws:ro" \
    registry.access.redhat.com/ubi9/ubi:latest sh -c \
    '/floci-aws -Dquarkus.http.port=0 > /tmp/out 2>&1 & pid=$!; sleep 5
     if kill -0 "$pid" 2>/dev/null; then echo "ok: starts on $(ldd --version | head -1)"; kill "$pid"
     else cat /tmp/out; exit 1; fi'
