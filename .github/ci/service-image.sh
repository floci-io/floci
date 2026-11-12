#!/usr/bin/env bash
# Read one service image key from the checked-out runtime configuration.
# An absent key prints nothing so callers retain their own failure policy.
set -eu
if [ "$#" -ne 2 ]; then
    echo "Usage: service-image.sh <service> <key>" >&2
    exit 2
fi
awk -v service="$1" -v key="$2" '
     $0 == "    " service ":" { in_service=1; next }
    in_service && /^    [a-z]/ { exit }
    in_service && $1 == key ":" { gsub(/"/, "", $2); print $2; exit }
' src/main/resources/application.yml
