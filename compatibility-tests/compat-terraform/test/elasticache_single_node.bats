#!/usr/bin/env bats
# ElastiCache single-node Redis Compatibility Test
#
# Applies an aws_elasticache_cluster with engine = "redis", the shape terraform
# sends to CreateCacheCluster rather than CreateReplicationGroup, and verifies
# both sides of the contract: the read-back is honest enough that a second plan
# wants nothing (the optional snapshot and maintenance arguments included), and
# the node endpoint terraform hands out is a real Redis/Valkey answering RESP.

setup_file() {
    load 'test_helper/common-setup'

    EC_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/elasticache-single-node-tf" && pwd)"
    cd "$EC_TF_DIR"

    echo "# === ElastiCache Single-Node Redis Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $EC_TF_DIR" >&3

    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    echo "# --- terraform apply (single-node redis) ---" >&3
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    EC_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/elasticache-single-node-tf" && pwd)"
    cd "$EC_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    EC_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/elasticache-single-node-tf" && pwd)"
}

# Sends one RESP command over /dev/tcp and echoes whatever the server replies
# within the read window. No redis-cli in this image, so raw RESP it is.
valkey_command() {
    local host="$1" port="$2"
    shift 2
    local payload="*$#\r\n"
    local arg
    for arg in "$@"; do
        payload+="\$${#arg}\r\n${arg}\r\n"
    done
    exec 9<>"/dev/tcp/${host}/${port}" || return 1
    printf '%b' "$payload" >&9
    local reply="" chunk
    while IFS= read -r -t 3 -u 9 chunk; do
        reply+="${chunk}"$'\n'
    done
    exec 9<&- 9>&-
    printf '%s' "$reply"
}

@test "ElastiCache single node: terraform reads back the engine and port" {
    run terraform -chdir="$EC_TF_DIR" output -raw engine
    assert_success
    assert_output "redis"

    run terraform -chdir="$EC_TF_DIR" output -raw port
    assert_success
    assert_output "6396"
}

@test "ElastiCache single node: describe reports one node, no replication group" {
    run aws_cmd elasticache describe-cache-clusters \
        --cache-cluster-id floci-tf-redis-single --show-cache-node-info
    assert_success
    [ "$(json_get "$output" '.CacheClusters[0].Engine')" = "redis" ]
    [ "$(json_get "$output" '.CacheClusters[0].NumCacheNodes')" = "1" ]
    [ "$(json_get "$output" '.CacheClusters[0].CacheNodes | length')" = "1" ]
    [ "$(json_get "$output" '.CacheClusters[0].ReplicationGroupId')" = "null" ]

    # a cluster created this way is not a replication group and must not answer as one
    run aws_cmd elasticache describe-replication-groups \
        --replication-group-id floci-tf-redis-single
    assert_failure
}

@test "ElastiCache single node: the optional arguments read back as sent" {
    run aws_cmd elasticache describe-cache-clusters --cache-cluster-id floci-tf-redis-single
    assert_success
    [ "$(json_get "$output" '.CacheClusters[0].SnapshotRetentionLimit')" = "5" ]
    [ "$(json_get "$output" '.CacheClusters[0].SnapshotWindow')" = "03:00-05:00" ]
    [ "$(json_get "$output" '.CacheClusters[0].PreferredMaintenanceWindow')" = "tue:04:00-tue:05:00" ]
}

# The critical assertion: the endpoint terraform hands out fronts a real cache.
@test "ElastiCache single node: the node endpoint answers RESP" {
    host=$(terraform -chdir="$EC_TF_DIR" output -raw cache_node_address)
    port=$(terraform -chdir="$EC_TF_DIR" output -raw cache_node_port)

    run valkey_command "$host" "$port" PING
    assert_success
    assert_output --partial "PONG"

    run valkey_command "$host" "$port" SET tf-single-node-key a-value
    assert_success
    assert_output --partial "OK"

    run valkey_command "$host" "$port" GET tf-single-node-key
    assert_success
    assert_output --partial "a-value"
}

# Guards against read-back drift (the class of bug in floci-io/floci#2481):
# a second plan straight after apply must not want to change anything.
@test "ElastiCache single node: re-plan after apply is clean" {
    cd "$EC_TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}
