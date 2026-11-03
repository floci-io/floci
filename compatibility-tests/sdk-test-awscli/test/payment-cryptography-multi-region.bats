#!/usr/bin/env bats
# Payment Cryptography multi-region key replication tests
#
# A key that is replicated is one key with one identifier in several regions: the key is
# created in the region of the request and is the Primary Region key there, and every
# other region holds a Replica Region key with the same identifier and its own ARN. The
# tests drive the operations with the AWS CLI, which is such a client.

setup() {
    load 'test_helper/common-setup'
    KEY_ARN=""
}

teardown() {
    if [ -n "$KEY_ARN" ]; then
        aws_cmd payment-cryptography delete-key --key-identifier "$KEY_ARN" \
            --delete-key-in-days 7 >/dev/null 2>&1 || true
    fi
    aws_cmd payment-cryptography disable-default-key-replication-regions \
        --replication-regions us-west-2 >/dev/null 2>&1 || true
}

# The key that leaves the service, replicated to the regions named.
create_replicated_key() {
    aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=AES_256,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
        --exportable \
        --replication-regions "$1" \
        --query 'Key.KeyArn' --output text
}

@test "Payment Cryptography multi-region: the default replication regions are read and set" {
    run aws_cmd payment-cryptography get-default-key-replication-regions
    assert_success
    [ -n "$(json_get "$output" '.EnabledReplicationRegions')" ]

    run aws_cmd payment-cryptography enable-default-key-replication-regions \
        --replication-regions us-west-2
    assert_success
    [[ "$(json_get "$output" '.EnabledReplicationRegions')" == *"us-west-2"* ]]

    run aws_cmd payment-cryptography get-default-key-replication-regions
    assert_success
    [[ "$(json_get "$output" '.EnabledReplicationRegions')" == *"us-west-2"* ]]

    run aws_cmd payment-cryptography disable-default-key-replication-regions \
        --replication-regions us-west-2
    assert_success
    [[ "$(json_get "$output" '.EnabledReplicationRegions')" != *"us-west-2"* ]]
}

@test "Payment Cryptography multi-region: a key is replicated to the regions it names" {
    KEY_ARN=$(create_replicated_key us-west-2)
    [ -n "$KEY_ARN" ]

    run aws_cmd payment-cryptography get-key --key-identifier "$KEY_ARN"
    assert_success
    [ "$(json_get "$output" '.Key.MultiRegionKeyType')" = "PRIMARY" ]
    [ "$(json_get "$output" '.Key.PrimaryRegion')" = "us-east-1" ]
    # The status of a replica that is there is that it is in sync.
    [ "$(json_get "$output" '.Key.ReplicationStatus."us-west-2".Status')" = "SYNCHRONIZED" ]
}

@test "Payment Cryptography multi-region: the regions of a key can be changed" {
    KEY_ARN=$(create_replicated_key us-west-2)
    [ -n "$KEY_ARN" ]

    run aws_cmd payment-cryptography add-key-replication-regions \
        --key-identifier "$KEY_ARN" \
        --replication-regions us-west-1
    assert_success
    [[ "$(json_get "$output" '.Key.ReplicationStatus')" == *"us-west-1"* ]]

    run aws_cmd payment-cryptography remove-key-replication-regions \
        --key-identifier "$KEY_ARN" \
        --replication-regions us-west-2
    assert_success
    [[ "$(json_get "$output" '.Key.ReplicationStatus')" != *"us-west-2"* ]]
}

@test "Payment Cryptography multi-region: a key that is replicated has to be exportable" {
    # The model asks for the flag, and the CLI writes a false one as the negated flag.
    run aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=AES_256,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
        --no-exportable \
        --replication-regions us-west-2
    assert_failure
    [[ "$output" == *"exportable"* ]]
}

@test "Payment Cryptography multi-region: a region the service does not serve is refused" {
    run aws_cmd payment-cryptography enable-default-key-replication-regions \
        --replication-regions moon-west-1
    assert_failure
    [[ "$output" == *"moon-west-1"* ]]
}
