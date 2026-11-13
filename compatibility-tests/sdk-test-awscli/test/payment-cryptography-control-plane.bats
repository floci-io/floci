#!/usr/bin/env bats
# Payment Cryptography control plane tests
#
# The operations that are not the key exchange itself: the certificate of a key pair, the
# tags of a key, and the usage a derived key is for. The tests drive them with the AWS CLI,
# which is such a client.

setup() {
    load 'test_helper/common-setup'
    KEY_ARN=""
    PAIR_ARN=""
}

teardown() {
    for key in "$KEY_ARN" "$PAIR_ARN"; do
        if [ -n "$key" ]; then
            aws_cmd payment-cryptography delete-key --key-identifier "$key" \
                --delete-key-in-days 7 >/dev/null 2>&1 || true
        fi
    done
}

# A key of the given algorithm, for the data the cipher protects.
create_key() {
    aws_cmd payment-cryptography create-key \
        --key-attributes "KeyAlgorithm=$1,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}" \
        --exportable \
        --query 'Key.KeyArn' --output text
}

@test "Payment Cryptography control plane: the certificate of a key pair is served" {
    PAIR_ARN=$(aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=ECC_NIST_P256,KeyClass=ASYMMETRIC_KEY_PAIR,KeyUsage=TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE,KeyModesOfUse={Sign=true,Verify=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text)
    [ -n "$PAIR_ARN" ]

    run aws_cmd payment-cryptography get-public-key-certificate \
        --key-identifier "$PAIR_ARN"
    assert_success
    CERTIFICATE=$(json_get "$output" '.KeyCertificate')
    CHAIN=$(json_get "$output" '.KeyCertificateChain')
    [ -n "$CERTIFICATE" ]
    [ -n "$CHAIN" ]
    # The certificate is base64 of a PEM, so it decodes to one.
    echo "$CERTIFICATE" | base64 --decode | grep -q "BEGIN CERTIFICATE"
}

@test "Payment Cryptography control plane: a tag is added to a key and removed again" {
    KEY_ARN=$(create_key AES_256)
    [ -n "$KEY_ARN" ]

    run aws_cmd payment-cryptography tag-resource \
        --resource-arn "$KEY_ARN" \
        --tags "Key=owner,Value=team"
    assert_success

    run aws_cmd payment-cryptography list-tags-for-resource \
        --resource-arn "$KEY_ARN"
    assert_success
    [ "$(json_get "$output" '.Tags[0].Value')" = "team" ]

    run aws_cmd payment-cryptography untag-resource \
        --resource-arn "$KEY_ARN" \
        --tag-keys owner
    assert_success

    run aws_cmd payment-cryptography list-tags-for-resource \
        --resource-arn "$KEY_ARN"
    assert_success
    [ "$(json_get "$output" '.Tags | length')" = "0" ]
}

@test "Payment Cryptography control plane: a key pair is created with the usage its derived keys have" {
    # The usage a derived key is for is named when the key pair is made, as the export
    # guide's key agreement flow does.
    PAIR_ARN=$(aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=ECC_NIST_P256,KeyClass=ASYMMETRIC_KEY_PAIR,KeyUsage=TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT,KeyModesOfUse={DeriveKey=true}' \
        --derive-key-usage TR31_K1_KEY_BLOCK_PROTECTION_KEY \
        --exportable \
        --query 'Key.KeyArn' --output text)
    [ -n "$PAIR_ARN" ]

    run aws_cmd payment-cryptography get-key --key-identifier "$PAIR_ARN"
    assert_success
    [ "$(json_get "$output" '.Key.DeriveKeyUsage')" = "TR31_K1_KEY_BLOCK_PROTECTION_KEY" ]
}
