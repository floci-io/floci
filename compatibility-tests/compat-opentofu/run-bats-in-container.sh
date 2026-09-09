#!/usr/bin/env bash
# Runs this suite's bats files, one process per file, concurrently by default.
#
# Each test/*.bats file is an independent root module: it creates its own state
# (local state for test/*-tf/, the S3 backend for the main suite), uses distinct
# resource identifiers, and cleans up in teardown_file. Nothing is shared, so the
# only thing serialising them was running `bats test/` as a single process.
#
# bats' own --jobs needs GNU parallel or rush, neither of which is in this image,
# so the files are forked here and reaped with wait.
#
# Environment:
#   RESULTS_DIR          where junit-<file>.xml is written (default /results)
#   BATS_PARALLEL_FILES  0 to run the files sequentially (bisecting a flake)
#   IAC_BIN              terraform | tofu (default: whichever is on PATH)
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RESULTS_DIR="${RESULTS_DIR:-/results}"
PARALLEL="${BATS_PARALLEL_FILES:-1}"

if [ -n "${IAC_BIN:-}" ]; then
    :
elif command -v terraform >/dev/null 2>&1; then
    IAC_BIN=terraform
elif command -v tofu >/dev/null 2>&1; then
    IAC_BIN=tofu
else
    echo "Error: neither terraform nor tofu is on PATH" >&2
    exit 1
fi

# Same resolution order as test/test_helper/common-setup.bash, so this script
# behaves identically in the image (BATS_LIB_PATH=/opt) and from a local checkout.
if [ -n "${BATS_LIB_PATH:-}" ] && [ -x "${BATS_LIB_PATH}/bats-core/bin/bats" ]; then
    BATS_BIN="${BATS_LIB_PATH}/bats-core/bin/bats"
elif [ -x "${SCRIPT_DIR}/../lib/bats-core/bin/bats" ]; then
    BATS_BIN="${SCRIPT_DIR}/../lib/bats-core/bin/bats"
else
    echo "Error: bats-core not found. Run 'just setup-bats' first." >&2
    exit 1
fi

FILES=()
for f in test/*.bats; do
    [ -e "$f" ] || continue
    FILES+=("$f")
done
if [ "${#FILES[@]}" -eq 0 ]; then
    echo "Error: no test/*.bats files found in $SCRIPT_DIR" >&2
    exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bats-run-XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
mkdir -p "$RESULTS_DIR"
# Reports from an earlier run are not all overwritten: a renamed or removed bats file, or a run
# that stops early, leaves its XML behind, and every consumer globs the directory. CI always
# starts from an empty one, but a local test-results/ persists between runs, so this is what
# keeps a developer's summary from mixing generations. The glob covers the single junit.xml the
# previous runner wrote, which the per-file names would never replace.
rm -f "${RESULTS_DIR}"/junit*.xml

# One shared provider download instead of one per file. Every fixture deletes its
# lock file in setup_file and re-resolves, so without this the concurrent inits
# would each fetch the provider at the same time. Warmed sequentially here because
# the plugin cache is only safe for concurrent readers, not concurrent writers.
warm_provider_cache() {
    local constraint warm_dir
    constraint="$(sed -n 's/.*version[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' provider.tf | head -1)"
    if [ -z "$constraint" ]; then
        echo "warn: no provider version constraint found in provider.tf, skipping cache warm-up" >&2
        return 0
    fi

    export TF_PLUGIN_CACHE_DIR="${TF_PLUGIN_CACHE_DIR:-${WORK_DIR}/plugin-cache}"
    export TF_PLUGIN_CACHE_MAY_BREAK_DEPENDENCY_LOCK_FILE=true
    mkdir -p "$TF_PLUGIN_CACHE_DIR"

    warm_dir="${WORK_DIR}/warm"
    mkdir -p "$warm_dir"
    cat > "${warm_dir}/versions.tf" <<EOF
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "${constraint}"
    }
  }
}
EOF
    echo "# --- warming provider cache (aws ${constraint}) into ${TF_PLUGIN_CACHE_DIR} ---"
    if ! (cd "$warm_dir" && "$IAC_BIN" init -backend=false -input=false -no-color >/dev/null 2>&1); then
        # Not fatal: every fixture still resolves the provider itself, just without
        # the shared cache. Losing the warm-up costs time, never correctness.
        echo "warn: provider cache warm-up failed; files will resolve the provider individually" >&2
        unset TF_PLUGIN_CACHE_DIR TF_PLUGIN_CACHE_MAY_BREAK_DEPENDENCY_LOCK_FILE
    fi
}

run_one() {
    local file="$1" name="$2"
    "$BATS_BIN" --report-formatter junit -o "${WORK_DIR}/${name}" "$file" \
        > "${WORK_DIR}/${name}.log" 2>&1
    echo "$?" > "${WORK_DIR}/${name}.status"
}

warm_provider_cache

NAMES=()
for f in "${FILES[@]}"; do
    name="$(basename "$f" .bats)"
    NAMES+=("$name")
    mkdir -p "${WORK_DIR}/${name}"
done

start=$(date +%s)
if [ "$PARALLEL" = "0" ]; then
    echo "# --- running ${#FILES[@]} bats files sequentially ---"
    for i in "${!FILES[@]}"; do
        run_one "${FILES[$i]}" "${NAMES[$i]}"
    done
else
    echo "# --- running ${#FILES[@]} bats files concurrently ---"
    pids=()
    for i in "${!FILES[@]}"; do
        run_one "${FILES[$i]}" "${NAMES[$i]}" &
        pids+=("$!")
    done
    for pid in "${pids[@]}"; do
        wait "$pid"
    done
fi
elapsed=$(( $(date +%s) - start ))

# Output is collected per file and replayed in a fixed order: six interleaved bats
# streams are unreadable, and grouped blocks keep a failure next to its own TAP plan.
status=0
for name in "${NAMES[@]}"; do
    echo
    echo "=============== ${name}.bats ==============="
    cat "${WORK_DIR}/${name}.log" 2>/dev/null || true

    file_status=1
    if [ -f "${WORK_DIR}/${name}.status" ]; then
        file_status="$(cat "${WORK_DIR}/${name}.status")"
    fi
    [ "$file_status" -eq 0 ] || status=1

    if [ -f "${WORK_DIR}/${name}/report.xml" ]; then
        mv "${WORK_DIR}/${name}/report.xml" "${RESULTS_DIR}/junit-${name}.xml"
    else
        echo "Error: no JUnit report produced for ${name}.bats" >&2
        status=1
    fi
done

echo
echo "=============== summary ==============="
for name in "${NAMES[@]}"; do
    file_status="$(cat "${WORK_DIR}/${name}.status" 2>/dev/null || echo 1)"
    if [ "$file_status" -eq 0 ]; then
        echo "  PASS  ${name}"
    else
        echo "  FAIL  ${name} (exit ${file_status})"
    fi
done
echo "total ${elapsed}s"

exit "$status"
