#!/bin/sh
# Unit tests for entrypoint.sh argument handling.
# Run directly: sh docker/test-entrypoint.sh
# Exit 0 on success, non-zero on first failure summary.
#
# These tests run the entrypoint as an unprivileged user with
# LOCALSTACK_PARITY=false, so the root-only gosu block and the parity
# script (installed at an absolute path inside the image) stay out of
# the way. The root/gosu path is covered by the Docker image tests.

set -eu

SCRIPT="$(cd "$(dirname "$0")" && pwd)/entrypoint.sh"
PASS=0
FAIL=0

assert_eq() {
    desc="$1"; expected="$2"; actual="$3"
    if [ "${actual}" = "${expected}" ]; then
        printf '[PASS] %s\n' "${desc}"
        PASS=$((PASS + 1))
    else
        printf '[FAIL] %s\n  expected: %s\n  actual:   %s\n' "${desc}" "${expected}" "${actual}"
        FAIL=$((FAIL + 1))
    fi
}

if [ "$(id -u)" = '0' ]; then
    echo "These tests must run as an unprivileged user (the root path re-execs via gosu)." >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT INT TERM

# Stub java on PATH that prints the argv it was exec'd with.
mkdir -p "${WORK}/bin"
cat > "${WORK}/bin/java" <<'EOF'
#!/bin/sh
printf '%s\n' "java $*"
EOF
chmod +x "${WORK}/bin/java"

# --- explicit arguments are exec'd unchanged ---
assert_eq "explicit command is exec'd unchanged" \
    "one two" \
    "$(LOCALSTACK_PARITY=false sh "${SCRIPT}" echo one two)"

assert_eq "explicit java command bypasses the fallback" \
    "java -jar /custom/app.jar" \
    "$(PATH="${WORK}/bin:${PATH}" LOCALSTACK_PARITY=false sh "${SCRIPT}" java -jar /custom/app.jar)"

# --- empty argv falls back to the image default command ---
# Without /app/application (JVM image layout), the fallback must exec the
# Quarkus runner jar with the same arguments as the published image CMD.
if [ ! -e /app/application ]; then
    assert_eq "empty argv falls back to the JVM default command" \
        "java -jar /app/quarkus-app/quarkus-run.jar -Dquarkus.http.host=0.0.0.0" \
        "$(PATH="${WORK}/bin:${PATH}" LOCALSTACK_PARITY=false sh "${SCRIPT}")"
else
    printf '[SKIP] empty argv falls back to the JVM default command (/app/application exists on this host)\n'
fi

# With an executable /app/application (native image layout), the fallback
# must prefer the native binary. Only runs where /app is writable or the
# binary already exists (always true inside the published images).
NATIVE_TESTABLE=false
if [ -x /app/application ]; then
    NATIVE_TESTABLE=true
elif mkdir -p /app 2>/dev/null && [ -w /app ]; then
    cat > /app/application <<'EOF'
#!/bin/sh
printf '%s\n' "/app/application $*"
EOF
    chmod +x /app/application
    trap 'rm -f /app/application; rm -rf "${WORK}"' EXIT INT TERM
    NATIVE_TESTABLE=true
fi
if [ "${NATIVE_TESTABLE}" = 'true' ]; then
    assert_eq "empty argv prefers the native binary when present" \
        "/app/application -Dquarkus.http.host=0.0.0.0" \
        "$(LOCALSTACK_PARITY=false sh "${SCRIPT}")"
else
    printf '[SKIP] empty argv prefers the native binary when present (/app not writable)\n'
fi

# --- unwritable state dir prints a warning but still execs the command ---
RO_DIR="${WORK}/ro-data"
mkdir -p "${RO_DIR}"
chmod 555 "${RO_DIR}"
RO_ERR="${WORK}/ro-err.txt"
assert_eq "unwritable state dir still execs the command" \
    "ok" \
    "$(FLOCI_STORAGE_PERSISTENT_PATH="${RO_DIR}" LOCALSTACK_PARITY=false sh "${SCRIPT}" echo ok 2>"${RO_ERR}")"
if grep -q 'not writable' "${RO_ERR}"; then
    printf '[PASS] unwritable state dir prints a loud warning\n'
    PASS=$((PASS + 1))
else
    printf '[FAIL] unwritable state dir prints a loud warning\n  stderr was:\n%s\n' "$(cat "${RO_ERR}")"
    FAIL=$((FAIL + 1))
fi
chmod 755 "${RO_DIR}"

# --- writable state dir stays quiet and leaves no probe behind ---
RW_DIR="${WORK}/rw-data"
mkdir -p "${RW_DIR}"
RW_ERR="${WORK}/rw-err.txt"
FLOCI_STORAGE_PERSISTENT_PATH="${RW_DIR}" LOCALSTACK_PARITY=false sh "${SCRIPT}" echo ok >/dev/null 2>"${RW_ERR}"
if ! grep -q 'not writable' "${RW_ERR}" && [ -z "$(ls -A "${RW_DIR}")" ]; then
    printf '[PASS] writable state dir stays quiet and leaves no probe file\n'
    PASS=$((PASS + 1))
else
    printf '[FAIL] writable state dir stays quiet and leaves no probe file\n'
    FAIL=$((FAIL + 1))
fi

printf '\n%d passed, %d failed\n' "${PASS}" "${FAIL}"
[ "${FAIL}" -eq 0 ]
