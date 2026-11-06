#!/usr/bin/env bats
# ElastiCache Cluster Mode Compatibility Test
#
# Applies an aws_elasticache_replication_group with num_node_groups /
# replicas_per_node_group (Terraform's cluster-mode shape) against floci and
# verifies both sides of the contract: the terraform read-back is honest
# (cluster_enabled, member_clusters, a clean re-plan), and the data plane is a
# genuine Valkey cluster (CLUSTER INFO over RESP through the auth proxy).

setup_file() {
    load 'test_helper/common-setup'

    EC_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/elasticache-cluster-tf" && pwd)"
    cd "$EC_TF_DIR"

    echo "# === ElastiCache Cluster Mode Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $EC_TF_DIR" >&3

    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    echo "# --- terraform apply (2 shards, 1 replica each) ---" >&3
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    EC_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/elasticache-cluster-tf" && pwd)"
    cd "$EC_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    EC_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/elasticache-cluster-tf" && pwd)"
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

@test "ElastiCache cluster mode: terraform reads back cluster_enabled=true" {
    run terraform -chdir="$EC_TF_DIR" output -raw cluster_enabled
    assert_success
    assert_output "true"

    run terraform -chdir="$EC_TF_DIR" output -raw num_node_groups
    assert_success
    assert_output "2"
}

@test "ElastiCache cluster mode: describe reports the sharded topology" {
    run aws_cmd elasticache describe-replication-groups \
        --replication-group-id floci-tf-valkey-cluster
    assert_success
    [ "$(json_get "$output" '.ReplicationGroups[0].ClusterEnabled')" = "true" ]
    [ "$(json_get "$output" '.ReplicationGroups[0].AutomaticFailover')" = "enabled" ]
    [ "$(json_get "$output" '.ReplicationGroups[0].Engine')" = "valkey" ]
    [ "$(json_get "$output" '.ReplicationGroups[0].NodeGroups | length')" = "2" ]
    [ "$(json_get "$output" '.ReplicationGroups[0].NodeGroups[0].Slots')" = "0-8191" ]
    [ "$(json_get "$output" '.ReplicationGroups[0].NodeGroups[1].Slots')" = "8192-16383" ]
    [ "$(json_get "$output" '.ReplicationGroups[0].MemberClusters | length')" = "4" ]
}

@test "ElastiCache cluster mode: member cache clusters answer DescribeCacheClusters" {
    member=$(terraform -chdir="$EC_TF_DIR" output -json member_clusters | jq -r '.[0]')
    run aws_cmd elasticache describe-cache-clusters \
        --cache-cluster-id "$member" --show-cache-node-info
    assert_success
    [ "$(json_get "$output" '.CacheClusters[0].ReplicationGroupId')" = "floci-tf-valkey-cluster" ]
    [ "$(json_get "$output" '.CacheClusters[0].Engine')" = "valkey" ]
    [ -n "$(json_get "$output" '.CacheClusters[0].CacheNodes[0].Endpoint.Port')" ]
}

# The critical assertion: the endpoint terraform hands out fronts a Valkey
# node that is actually running in cluster mode, in a formed, healthy cluster.
@test "ElastiCache cluster mode: Valkey runs in cluster mode behind the configuration endpoint" {
    host=$(terraform -chdir="$EC_TF_DIR" output -raw configuration_endpoint_address)
    describe=$(aws_cmd elasticache describe-replication-groups \
        --replication-group-id floci-tf-valkey-cluster)
    port=$(json_get "$describe" '.ReplicationGroups[0].ConfigurationEndpoint.Port')

    run valkey_command "$host" "$port" INFO cluster
    assert_success
    assert_output --partial "cluster_enabled:1"

    run valkey_command "$host" "$port" CLUSTER INFO
    assert_success
    assert_output --partial "cluster_state:ok"
    assert_output --partial "cluster_size:2"
    assert_output --partial "cluster_known_nodes:4"
}

# Guards against read-back drift (the class of bug in floci-io/floci#2481):
# a second plan straight after apply must not want to change anything.
@test "ElastiCache cluster mode: re-plan after apply is clean" {
    cd "$EC_TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}
