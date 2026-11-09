#!/usr/bin/env bats
# Payment Cryptography PIN operation tests
#
# The PIN operations are the data plane's own routes, so a client of it reaches them
# under the internal prefix the router rewrites them onto. These tests drive them with
# the AWS CLI, which is such a client.

setup() {
    load 'test_helper/common-setup'
    PEK_ARN=""
    PVK_ARN=""
    AES_PEK_ARN=""
}

teardown() {
    for key in "$PEK_ARN" "$PVK_ARN" "$AES_PEK_ARN"; do
        if [ -n "$key" ]; then
            aws_cmd payment-cryptography delete-key --key-identifier "$key" \
                --delete-key-in-days 7 >/dev/null 2>&1 || true
        fi
    done
}

# A key of the PIN encryption usage, for the block that protects the PIN.
create_pek() {
    aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=TDES_2KEY,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_P0_PIN_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
}

# A key of the Visa PIN verification usage, for the verification value.
create_pvk() {
    aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=TDES_2KEY,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_V2_VISA_PIN_VERIFICATION_KEY,KeyModesOfUse={Generate=true,Verify=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
}

@test "Payment Cryptography PIN: a PIN is generated and verifies" {
    PEK_ARN=$(create_pek)
    [ -n "$PEK_ARN" ]
    PVK_ARN=$(create_pvk)
    [ -n "$PVK_ARN" ]

    run aws_cmd payment-cryptography-data generate-pin-data \
        --encryption-key-identifier "$PEK_ARN" \
        --generation-key-identifier "$PVK_ARN" \
        --pin-block-format ISO_FORMAT_0 \
        --primary-account-number "5081470082639564" \
        --generation-attributes 'VisaPin={PinVerificationKeyIndex=1}'
    assert_success
    BLOCK=$(json_get "$output" '.EncryptedPinBlock')
    [ -n "$BLOCK" ]
    PVV=$(json_get "$output" '.PinData.VerificationValue')
    [ -n "$PVV" ]

    run aws_cmd payment-cryptography-data verify-pin-data \
        --encryption-key-identifier "$PEK_ARN" \
        --verification-key-identifier "$PVK_ARN" \
        --pin-block-format ISO_FORMAT_0 \
        --primary-account-number "5081470082639564" \
        --encrypted-pin-block "$BLOCK" \
        --verification-attributes "VisaPin={PinVerificationKeyIndex=1,VerificationValue=$PVV}"
    assert_success
}

@test "Payment Cryptography PIN: a PIN that does not verify is refused" {
    PEK_ARN=$(create_pek)
    [ -n "$PEK_ARN" ]
    PVK_ARN=$(create_pvk)
    [ -n "$PVK_ARN" ]

    run aws_cmd payment-cryptography-data generate-pin-data \
        --encryption-key-identifier "$PEK_ARN" \
        --generation-key-identifier "$PVK_ARN" \
        --pin-block-format ISO_FORMAT_0 \
        --primary-account-number "5081470082639564" \
        --generation-attributes 'VisaPin={PinVerificationKeyIndex=1}'
    assert_success
    BLOCK=$(json_get "$output" '.EncryptedPinBlock')

    # The verification value is not the one over the PIN, so the verification fails.
    run aws_cmd payment-cryptography-data verify-pin-data \
        --encryption-key-identifier "$PEK_ARN" \
        --verification-key-identifier "$PVK_ARN" \
        --pin-block-format ISO_FORMAT_0 \
        --primary-account-number "5081470082639564" \
        --encrypted-pin-block "$BLOCK" \
        --verification-attributes 'VisaPin={PinVerificationKeyIndex=1,VerificationValue=9999}'
    assert_failure
    # A PIN that does not verify is a verification that failed, not a malformed request.
    [[ "$output" == *"VerificationFailedException"* ]]
}

@test "Payment Cryptography PIN: a PIN block translates from one format to another" {
    PEK_ARN=$(create_pek)
    [ -n "$PEK_ARN" ]
    PVK_ARN=$(create_pvk)
    [ -n "$PVK_ARN" ]

    # The format 4 block is protected by an AES key and holds the whole account number.
    run aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=AES_128,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_P0_PIN_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
    assert_success
    AES_PEK_ARN="$output"
    [ -n "$AES_PEK_ARN" ]

    run aws_cmd payment-cryptography-data generate-pin-data \
        --encryption-key-identifier "$AES_PEK_ARN" \
        --generation-key-identifier "$PVK_ARN" \
        --pin-block-format ISO_FORMAT_4 \
        --primary-account-number "5081470082639564" \
        --generation-attributes 'VisaPin={PinVerificationKeyIndex=1}'
    assert_success
    BLOCK=$(json_get "$output" '.EncryptedPinBlock')

    run aws_cmd payment-cryptography-data translate-pin-data \
        --encrypted-pin-block "$BLOCK" \
        --incoming-key-identifier "$AES_PEK_ARN" \
        --outgoing-key-identifier "$PEK_ARN" \
        --incoming-translation-attributes 'IsoFormat4={PrimaryAccountNumber=5081470082639564}' \
        --outgoing-translation-attributes 'IsoFormat0={PrimaryAccountNumber=5081470082639564}'
    assert_success
    TRANSLATED=$(json_get "$output" '.PinBlock')
    [ -n "$TRANSLATED" ]
    # The answer names the key the block is enciphered under.
    [ "$(json_get "$output" '.KeyArn')" = "$PEK_ARN" ]
}

@test "Payment Cryptography PIN: a key that may not encrypt is refused" {
    # A PIN encryption key that may only decrypt does not encrypt a PIN block.
    run aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=TDES_2KEY,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_P0_PIN_ENCRYPTION_KEY,KeyModesOfUse={Decrypt=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
    assert_success
    READ_ONLY_ARN="$output"
    [ -n "$READ_ONLY_ARN" ]

    PVK_ARN=$(create_pvk)
    [ -n "$PVK_ARN" ]

    run aws_cmd payment-cryptography-data generate-pin-data \
        --encryption-key-identifier "$READ_ONLY_ARN" \
        --generation-key-identifier "$PVK_ARN" \
        --pin-block-format ISO_FORMAT_0 \
        --primary-account-number "5081470082639564" \
        --generation-attributes 'VisaPin={PinVerificationKeyIndex=1}'
    assert_failure

    aws_cmd payment-cryptography delete-key --key-identifier "$READ_ONLY_ARN" \
        --delete-key-in-days 7 >/dev/null 2>&1 || true
}

@test "Payment Cryptography PIN: a verification key of another usage is refused" {
    PEK_ARN=$(create_pek)
    [ -n "$PEK_ARN" ]

    # The Visa verification value is computed under a Visa PIN verification key, and the
    # usage is what says which key that is.
    run aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=TDES_2KEY,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_P1_PIN_GENERATION_KEY,KeyModesOfUse={Generate=true,Verify=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
    assert_success
    WRONG_USAGE_ARN="$output"
    [ -n "$WRONG_USAGE_ARN" ]

    run aws_cmd payment-cryptography-data generate-pin-data \
        --encryption-key-identifier "$PEK_ARN" \
        --generation-key-identifier "$WRONG_USAGE_ARN" \
        --pin-block-format ISO_FORMAT_0 \
        --primary-account-number "5081470082639564" \
        --generation-attributes 'VisaPin={PinVerificationKeyIndex=1}'
    assert_failure

    aws_cmd payment-cryptography delete-key --key-identifier "$WRONG_USAGE_ARN" \
        --delete-key-in-days 7 >/dev/null 2>&1 || true
}
