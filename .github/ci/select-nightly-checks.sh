#!/usr/bin/env bash
# Picks the nightly checks one run of nightly-checks.yml runs and writes them as the checks job's matrix: one entry
# per group that has a check to run, {"group", "timeout", "classes"}, with classes in surefire includesFile form.
#
# Groups:
#   docker-runner  the EMR Docker runner checks, named in RUNNER below
#   docker-e2e     *DockerNightlyIntegrationTest: container end-to-end checks moved out of the pull request shards
#   standard       every other *NightlyIntegrationTest
#
# On a schedule or on demand every check runs, which is also the backstop for anything the pull request rules below
# miss. On a pull request ($PR set), every check runs when the pull request changes nightly-checks.yml, this script
# or its test; otherwise these run:
#   - the nightly checks it adds or changes;
#   - for each service whose src/main package (services/<svc>/) it changes, the docker-e2e checks under
#     services/<svc>/ in src/test, the docker-e2e checks that import io.floci.aws.services.<svc>, and the ones
#     CONSUMERS names for that service;
#   - every docker-e2e check when it changes core/common/docker, or changes an image pin: a changed line that mentions
#     "image" in application.yml (main or test) or under config/, or one of those files with no patch from GitHub;
#   - for each other src/test/java class it changes, the docker-e2e checks that name that class.
#
# Writes matrix=<json> to $GITHUB_OUTPUT, or prints it when that is unset. Needs gh (for $PR) and jq.
# SELECT_NIGHTLY_FILES, when set, names a JSON array of {filename, patch} read in place of the pull request's files
# (test_select_nightly_checks.py).
set -euo pipefail

RUNNER='/(EmrDockerRunner|EmrClusterShrink|EmrContainersSpark|EmrContainersGateway|EmrTrino)NightlyIntegrationTest$'
DOCKER_E2E='DockerNightlyIntegrationTest$'
MAIN=src/main/java/io/floci/aws
TESTS=io/floci/aws
IMAGE_PINS='^(src/(main|test)/resources/application\.yml|src/main/java/io/floci/aws/config/.*)$'
# <service>:<regex over a docker-e2e check's class path>, for checks that exercise a service without importing it.
CONSUMERS=(
    # Redshift Spectrum and UNLOAD to Parquet run their SQL on floci-duck (services/floci/duck).
    'floci:/services/redshift/(RedshiftSpectrum|RedshiftUnloadParquet)DockerNightlyIntegrationTest$'
    # Redshift Serverless and the Redshift Data API, which Step Functions' redshiftdata tasks call, run on Redshift's
    # engine.
    'redshift:/services/(redshiftserverless/|stepfunctions/StepFunctionsRedshiftData)'
)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

(cd src/test/java && find . -name '*NightlyIntegrationTest.java' | sed 's|^\./||; s|\.java$||' | LC_ALL=C sort) \
    > "$work/all.txt"

if [ -n "${PR:-}" ]; then
    if [ -n "${SELECT_NIGHTLY_FILES:-}" ]; then
        cp "$SELECT_NIGHTLY_FILES" "$work/files.json"
    else
        # The files API pages up to 3000 files; gh pr diff refuses a diff of more than 300.
        gh api --paginate "repos/$GITHUB_REPOSITORY/pulls/$PR/files" --jq '.[] | {filename, patch}' \
            | jq -s . > "$work/files.json"
    fi
    jq -r '.[].filename' "$work/files.json" > "$work/changed.txt"
    grep -E "$DOCKER_E2E" "$work/all.txt" > "$work/e2e.txt" || true
    sed 's|^|src/test/java/|; s|$|.java|' "$work/e2e.txt" > "$work/e2e-sources.txt"
    # Changed lines (not the +++/--- file headers) that mention an image, or "unknown" for a file with no patch.
    pins="$(jq -r --arg re "$IMAGE_PINS" '.[] | select(.filename | test($re))
        | if .patch == null then "unknown"
          else .patch | split("\n") | map(select(test("^[-+]") and (test("^(\\+\\+\\+|---) ") | not)
              and test("image"; "i"))) | .[] end' "$work/files.json")"
    selection='\.github/(workflows/nightly-checks\.yml|ci/(select-nightly-checks\.sh|test_select_nightly_checks\.py))'
    if grep -qxE "$selection" "$work/changed.txt"; then
        cp "$work/all.txt" "$work/selected.txt"
    else
        {
            sed -n 's|^src/test/java/\(.*NightlyIntegrationTest\)\.java$|\1|p' "$work/changed.txt"
            if grep -q "^$MAIN/core/common/docker/" "$work/changed.txt" || [ -n "$pins" ]; then
                cat "$work/e2e.txt"
            fi
            sed -n "s|^$MAIN/services/\([^/]*\)/.*|\1|p" "$work/changed.txt" | LC_ALL=C sort -u | while read -r svc; do
                grep -E "^$TESTS/services/$svc/" "$work/e2e.txt" || true
                xargs grep -lE "^import (static )?io\.floci\.aws\.services\.$svc\." < "$work/e2e-sources.txt" \
                    | sed 's|^src/test/java/||; s|\.java$||' || true
                for consumer in "${CONSUMERS[@]}"; do
                    if [ "${consumer%%:*}" = "$svc" ]; then
                        grep -E "${consumer#*:}" "$work/e2e.txt" || true
                    fi
                done
            done
            # A src/test/java class's simple name; every test source is a top-level class, as ci.yml relies on.
            { grep -E '^src/test/java/.*\.java$' "$work/changed.txt" || true; } \
                | { grep -v 'NightlyIntegrationTest\.java$' || true; } \
                | sed 's|.*/||; s|\.java$||' | LC_ALL=C sort -u | while read -r helper; do
                    xargs grep -lw "$helper" < "$work/e2e-sources.txt" | sed 's|^src/test/java/||; s|\.java$||' || true
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
