#!/usr/bin/env bats
# Payment Cryptography multi-region key replication tests
#
# A key that is replicated is one key with one identifier in several regions: the key is
# created in the region of the request and is the Primary Region key there, and every
# other region holds a Replica Region key with the same identifier and its own ARN. The
# tests drive the operations with the AWS CLI, which is such a client.
#
# The default replication regions are the ones of the region they are read in, so the
# test that changes them does it in a region of its own. The other tests of the suite
# create their keys in the region the command line names, and a key that is replicated
# has to be exportable, so the settings of one region must not reach the keys of another.

setup() {
    load 'test_helper/common-setup'
    KEY_ARN=""
    DEFAULT_REGIONS=""
    SETTINGS_REGION="us-west-2"
}

teardown() {
    if [ -n "$KEY_ARN" ]; then
        aws_cmd payment-cryptography delete-key --key-identifier "$KEY_ARN" \
            --delete-key-in-days 7 >/dev/null 2>&1 || true
    fi
    # The default regions of the region the test changed are put back, so a run against an
    # account that already had one does not take it away.
    aws_cmd_in "$SETTINGS_REGION" payment-cryptography disable-default-key-replication-regions \
        --replication-regions us-east-2 >/dev/null 2>&1 || true
    if [ -n "$DEFAULT_REGIONS" ] && [ "$DEFAULT_REGIONS" != "None" ]; then
        aws_cmd_in "$SETTINGS_REGION" payment-cryptography enable-default-key-replication-regions \
            --replication-regions $DEFAULT_REGIONS >/dev/null 2>&1 || true
    fi
}

# The command in a region of its own, for the operations whose settings are the ones of
# that region.
aws_cmd_in() {
    local region="$1"
    shift
    aws --endpoint-url "$FLOCI_ENDPOINT" --region "$region" --output json "$@" 2>&1
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
    # The default regions of the region are read before the test changes them, so the
    # teardown can put them back.
    DEFAULT_REGIONS=$(aws_cmd_in "$SETTINGS_REGION" payment-cryptography \
        get-default-key-replication-regions --query 'EnabledReplicationRegions' \
        --output text 2>/dev/null || true)

    run aws_cmd_in "$SETTINGS_REGION" payment-cryptography get-default-key-replication-regions
    assert_success
    [ -n "$(json_get "$output" '.EnabledReplicationRegions')" ]

    run aws_cmd_in "$SETTINGS_REGION" payment-cryptography enable-default-key-replication-regions \
        --replication-regions us-east-2
    assert_success
    [[ "$(json_get "$output" '.EnabledReplicationRegions')" == *"us-east-2"* ]]

    run aws_cmd_in "$SETTINGS_REGION" payment-cryptography get-default-key-replication-regions
    assert_success
    [[ "$(json_get "$output" '.EnabledReplicationRegions')" == *"us-east-2"* ]]

    run aws_cmd_in "$SETTINGS_REGION" payment-cryptography disable-default-key-replication-regions \
        --replication-regions us-east-2
    assert_success
    [[ "$(json_get "$output" '.EnabledReplicationRegions')" != *"us-east-2"* ]]
}

@test "Payment Cryptography multi-region: the default replication regions are the ones of the region" {
    # The settings are the ones of the region they are read in, so the region the command
    # line names keeps the settings it had.
    run aws_cmd_in "$SETTINGS_REGION" payment-cryptography enable-default-key-replication-regions \
        --replication-regions us-east-2
    assert_success

    run aws_cmd payment-cryptography get-default-key-replication-regions
    assert_success
    [[ "$(json_get "$output" '.EnabledReplicationRegions')" != *"us-east-2"* ]]
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

@test "Payment Cryptography multi-region: a name that is not a region is refused" {
    # A Region a request names has to be a name of a Region this service serves, and a name
    # that is not one of those is refused.
    run aws_cmd_in "$SETTINGS_REGION" payment-cryptography enable-default-key-replication-regions \
        --replication-regions moon-west-1
    assert_failure
    [[ "$output" == *"moon-west-1"* ]]
}

@test "Payment Cryptography multi-region: the region of the request is not a region to replicate to" {
    # The key is in the region of the request already, so naming that region is a request
    # that cannot be carried out.
    run aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=AES_256,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
        --exportable \
        --replication-regions us-east-1
    assert_failure
    [[ "$output" == *"us-east-1"* ]]
}
