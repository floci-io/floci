#!/usr/bin/env bats
# Payment Cryptography data plane tests
#
# The data plane is a rest-json service of its own, signed for its own scope, so a
# client of it reaches the routes under the internal prefix the router rewrites them
# onto. These tests drive it with the AWS CLI, which is such a client.

setup() {
    load 'test_helper/common-setup'
    KEY_ARN=""
    MAC_KEY_ARN=""
}

teardown() {
    for key in "$KEY_ARN" "$MAC_KEY_ARN"; do
        if [ -n "$key" ]; then
            aws_cmd payment-cryptography delete-key --key-identifier "$key" \
                --delete-key-in-days 7 >/dev/null 2>&1 || true
        fi
    done
}

# A key of the data encryption usage, for the data the cipher encrypts.
create_data_key() {
    aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=AES_256,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
}

# A key of the CMAC usage, for the MAC the service makes.
create_mac_key() {
    aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=AES_256,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_M6_ISO_9797_5_CMAC_KEY,KeyModesOfUse={Generate=true,Verify=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
}

@test "Payment Cryptography data plane: a key encrypts and decrypts" {
    KEY_ARN=$(create_data_key)
    [ -n "$KEY_ARN" ]

    # The cipher pads no data, so the plaintext is a whole number of AES blocks.
    PLAIN_TEXT="68656c6c6f20776f726c642121212121"

    run aws_cmd payment-cryptography-data encrypt-data \
        --key-identifier "$KEY_ARN" \
        --plain-text "$PLAIN_TEXT" \
        --encryption-attributes 'Symmetric={Mode=CBC}'
    assert_success
    CIPHER_TEXT=$(json_get "$output" '.CipherText')
    [ -n "$CIPHER_TEXT" ]
    [ "$CIPHER_TEXT" != "$PLAIN_TEXT" ]

    run aws_cmd payment-cryptography-data decrypt-data \
        --key-identifier "$KEY_ARN" \
        --cipher-text "$CIPHER_TEXT" \
        --decryption-attributes 'Symmetric={Mode=CBC}'
    assert_success
    # The service carries the plaintext as hexadecimal in capitals.
    [ "$(json_get "$output" '.PlainText')" = "$(echo "$PLAIN_TEXT" | tr '[:lower:]' '[:upper:]')" ]
}

@test "Payment Cryptography data plane: a key makes a MAC and verifies it" {
    MAC_KEY_ARN=$(create_mac_key)
    [ -n "$MAC_KEY_ARN" ]

    MESSAGE="6d65737361676520746f2061757468656e746963617465"

    run aws_cmd payment-cryptography-data generate-mac \
        --key-identifier "$MAC_KEY_ARN" \
        --message-data "$MESSAGE" \
        --mac-length 16 \
        --generation-attributes 'Algorithm=CMAC'
    assert_success
    MAC=$(json_get "$output" '.Mac')
    [ -n "$MAC" ]

    run aws_cmd payment-cryptography-data verify-mac \
        --key-identifier "$MAC_KEY_ARN" \
        --message-data "$MESSAGE" \
        --mac "$MAC" \
        --mac-length 16 \
        --verification-attributes 'Algorithm=CMAC'
    assert_success
}

@test "Payment Cryptography data plane: a MAC of another message is refused" {
    MAC_KEY_ARN=$(create_mac_key)
    [ -n "$MAC_KEY_ARN" ]

    MESSAGE="6d65737361676520746f2061757468656e746963617465"

    run aws_cmd payment-cryptography-data generate-mac \
        --key-identifier "$MAC_KEY_ARN" \
        --message-data "$MESSAGE" \
        --mac-length 16 \
        --generation-attributes 'Algorithm=CMAC'
    assert_success
    MAC=$(json_get "$output" '.Mac')

    run aws_cmd payment-cryptography-data verify-mac \
        --key-identifier "$MAC_KEY_ARN" \
        --message-data "616e6f74686572206d65737361676520656e746972656c79" \
        --mac "$MAC" \
        --mac-length 16 \
        --verification-attributes 'Algorithm=CMAC'
    assert_failure
}

@test "Payment Cryptography data plane: a key that is not there is refused" {
    run aws_cmd payment-cryptography-data encrypt-data \
        --key-identifier "arn:aws:payment-cryptography:us-east-1:000000000000:key/nope" \
        --plain-text "68656c6c6f20776f726c642121212121" \
        --encryption-attributes 'Symmetric={Mode=CBC}'
    assert_failure
}

@test "Payment Cryptography data plane: a key of another purpose does not encrypt" {
    # A MAC key may not encrypt data, so the request is refused.
    MAC_KEY_ARN=$(create_mac_key)
    [ -n "$MAC_KEY_ARN" ]

    run aws_cmd payment-cryptography-data encrypt-data \
        --key-identifier "$MAC_KEY_ARN" \
        --plain-text "68656c6c6f20776f726c642121212121" \
        --encryption-attributes 'Symmetric={Mode=CBC}'
    assert_failure
}
