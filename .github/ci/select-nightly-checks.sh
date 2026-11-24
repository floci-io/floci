#!/usr/bin/env bash
# Picks the nightly checks one run of nightly-checks.yml runs and writes them as the checks job's matrix: one entry
# per group that has a check to run, {"group", "timeout", "classes"}, with classes in surefire includesFile form.
#
# Groups:
#   docker-runner  the EMR Docker runner checks, named in RUNNER below
#   docker-e2e     *DockerNightlyIntegrationTest: container end-to-end checks moved out of the pull request shards
#   standard       every other *NightlyIntegrationTest
#
# On a schedule or on demand every check runs. On a pull request ($PR set), every check runs when the pull request
# changes nightly-checks.yml or this script; otherwise the checks it adds or changes run, plus the docker-e2e checks
# of each service whose src/main package it changes (services/<svc>/ in src/main selects services/<svc>/ in
# src/test), and every docker-e2e check when it changes core/common/docker.
#
# Writes matrix=<json> to $GITHUB_OUTPUT, or prints it when that is unset. Needs gh (for $PR) and jq.
set -euo pipefail

RUNNER='/(EmrDockerRunner|EmrClusterShrink|EmrContainersSpark|EmrContainersGateway|EmrTrino)NightlyIntegrationTest$'
DOCKER_E2E='DockerNightlyIntegrationTest$'
MAIN=src/main/java/io/floci/aws
TESTS=io/floci/aws

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

(cd src/test/java && find . -name '*NightlyIntegrationTest.java' | sed 's|^\./||; s|\.java$||' | LC_ALL=C sort) \
    > "$work/all.txt"

if [ -n "${PR:-}" ]; then
    gh pr diff "$PR" --repo "$GITHUB_REPOSITORY" --name-only > "$work/changed.txt"
    if grep -qxE '\.github/(workflows/nightly-checks\.yml|ci/select-nightly-checks\.sh)' "$work/changed.txt"; then
        cp "$work/all.txt" "$work/selected.txt"
    else
        {
            sed -n 's|^src/test/java/\(.*NightlyIntegrationTest\)\.java$|\1|p' "$work/changed.txt"
            if grep -q "^$MAIN/core/common/docker/" "$work/changed.txt"; then
                grep -E "$DOCKER_E2E" "$work/all.txt" || true
            fi
            sed -n "s|^$MAIN/services/\([^/]*\)/.*|\1|p" "$work/changed.txt" | LC_ALL=C sort -u | while read -r svc; do
                grep -E "^$TESTS/services/$svc/.*$DOCKER_E2E" "$work/all.txt" || true
            done
        } | LC_ALL=C sort -u | comm -12 "$work/all.txt" - > "$work/selected.txt"
    fi
else
    cp "$work/all.txt" "$work/selected.txt"
fi

grep -E "$RUNNER" "$work/selected.txt" > "$work/docker-runner.txt" || true
grep -E "$DOCKER_E2E" "$work/selected.txt" > "$work/docker-e2e.txt" || true
grep -vE "$RUNNER|$DOCKER_E2E" "$work/selected.txt" > "$work/standard.txt" || true

# Timeouts in minutes: each group's checks run one after another, plus about twenty minutes for compilation, the image
# and the prefetch (see nightly-checks.yml). docker-e2e's tests took about 23 minutes in the shards on 2026-11-23.
matrix='[]'
for entry in standard:359 docker-runner:214 docker-e2e:60; do
    group="${entry%%:*}"
    timeout="${entry##*:}"
    count=$(wc -l < "$work/$group.txt" | tr -d ' ')
    echo "$group: $count nightly checks" >&2
    sed 's/^/  /' "$work/$group.txt" >&2
    if [ "$count" -gt 0 ]; then
        matrix="$(jq -c --arg g "$group" --argjson t "$timeout" --rawfile c "$work/$group.txt" \
            '. + [{group: $g, timeout: $t, classes: ($c | split("\n") | map(select(length > 0)) | join(" "))}]' \
            <<< "$matrix")"
    fi
done

if [ -n "${GITHUB_OUTPUT:-}" ]; then
    if [ "$matrix" = '[]' ]; then
        echo "::notice title=Nightly checks::This run selects no nightly check, so none ran."
    fi
    echo "matrix=$matrix" >> "$GITHUB_OUTPUT"
else
    echo "$matrix"
fi
