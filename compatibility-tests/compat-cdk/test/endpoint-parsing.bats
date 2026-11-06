#!/usr/bin/env bats

@test "run.sh derives CDK host and port from a URL endpoint" {
    workdir=$(mktemp -d)
    trap 'rm -rf "$workdir"' EXIT
    mkdir -p "$workdir/compat-cdk" "$workdir/lib/bats-core"
    cp "$BATS_TEST_DIRNAME/../run.sh" "$workdir/compat-cdk/run.sh"
    cat > "$workdir/lib/run-bats-with-junit.sh" <<'EOF'
#!/usr/bin/env bash
printf '%s|%s\n' "$LOCALSTACK_HOSTNAME" "$EDGE_PORT"
EOF
    chmod +x "$workdir/lib/run-bats-with-junit.sh"

    run env FLOCI_ENDPOINT="https://[::1]:8443/floci" bash "$workdir/compat-cdk/run.sh"

    [ "$status" -eq 0 ]
    [ "$output" = "::1|8443" ]
}

@test "run.sh uses the default port for an endpoint without one" {
    workdir=$(mktemp -d)
    trap 'rm -rf "$workdir"' EXIT
    mkdir -p "$workdir/compat-cdk" "$workdir/lib/bats-core"
    cp "$BATS_TEST_DIRNAME/../run.sh" "$workdir/compat-cdk/run.sh"
    cat > "$workdir/lib/run-bats-with-junit.sh" <<'EOF'
#!/usr/bin/env bash
printf '%s|%s\n' "$LOCALSTACK_HOSTNAME" "$EDGE_PORT"
EOF
    chmod +x "$workdir/lib/run-bats-with-junit.sh"

    run env FLOCI_ENDPOINT="floci" bash "$workdir/compat-cdk/run.sh"

    [ "$status" -eq 0 ]
    [ "$output" = "floci|4566" ]
}
