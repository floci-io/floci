#!/usr/bin/env bash
# Read one Neptune image key from the checked-out runtime configuration.
# An absent key prints nothing so callers retain their own failure policy.
set -eu
if [ "$#" -ne 1 ]; then
    echo "Usage: neptune-image.sh <key>" >&2
    exit 2
fi
awk -v key="$1" '
    /^    neptune:/ { in_service=1; next }
    in_service && /^    [a-z]/ { exit }
    in_service && $1 == key ":" { gsub(/"/, "", $2); print $2; exit }
' src/main/resources/application.yml
