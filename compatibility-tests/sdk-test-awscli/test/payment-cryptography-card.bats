#!/usr/bin/env bats
# Payment Cryptography card validation value and authorization cryptogram tests
#
# The card operations are the data plane's own routes, so a client of it reaches them
# under the internal prefix the router rewrites them onto. These tests drive them with
# the AWS CLI, which is such a client.

setup() {
    load 'test_helper/common-setup'
    CVK_ARN=""
    ACG_ARN=""
    DATA_KEY_ARN=""
}

teardown() {
    for key in "$CVK_ARN" "$ACG_ARN" "$DATA_KEY_ARN"; do
        if [ -n "$key" ]; then
            aws_cmd payment-cryptography delete-key --key-identifier "$key" \
                --delete-key-in-days 7 >/dev/null 2>&1 || true
        fi
    done
}

# A key of the card verification usage, for the value of the card account.
create_cvk() {
    aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=TDES_2KEY,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_C0_CARD_VERIFICATION_KEY,KeyModesOfUse={Generate=true,Verify=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
}

# A key of the application cryptogram usage, for the cryptogram of the transaction.
create_acg() {
    aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=TDES_2KEY,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS,KeyModesOfUse={Generate=true,DeriveKey=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
}

@test "Payment Cryptography card: a card verification value is generated and verifies" {
    CVK_ARN=$(create_cvk)
    [ -n "$CVK_ARN" ]

    run aws_cmd payment-cryptography-data generate-card-validation-data \
        --key-identifier "$CVK_ARN" \
        --primary-account-number "5081470082639564" \
        --generation-attributes 'CardVerificationValue1={CardExpiryDate=3003,ServiceCode=201}'
    assert_success
    VALUE=$(json_get "$output" '.ValidationData')
    [ -n "$VALUE" ]
    # The value is three digits.
    [ "${#VALUE}" -eq 3 ]
    # The answer names the key the value was computed under.
    [ "$(json_get "$output" '.KeyArn')" = "$CVK_ARN" ]

    run aws_cmd payment-cryptography-data verify-card-validation-data \
        --key-identifier "$CVK_ARN" \
        --primary-account-number "5081470082639564" \
        --validation-data "$VALUE" \
        --verification-attributes 'CardVerificationValue1={CardExpiryDate=3003,ServiceCode=201}'
    assert_success
}

@test "Payment Cryptography card: a card verification value that does not verify is refused" {
    CVK_ARN=$(create_cvk)
    [ -n "$CVK_ARN" ]

    # The value is not the one over the card account, so the verification fails.
    run aws_cmd payment-cryptography-data verify-card-validation-data \
        --key-identifier "$CVK_ARN" \
        --primary-account-number "5081470082639564" \
        --validation-data "999" \
        --verification-attributes 'CardVerificationValue1={CardExpiryDate=3003,ServiceCode=201}'
    assert_failure
    # A value that does not verify is a verification that failed, not a malformed request.
    [[ "$output" == *"VerificationFailedException"* ]]
}

@test "Payment Cryptography card: a key of the wrong usage is refused" {
    # The card verification value is computed under the card verification key, and the
    # usage is what says which key that is.
    run aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=TDES_2KEY,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
    assert_success
    DATA_KEY_ARN="$output"
    [ -n "$DATA_KEY_ARN" ]

    run aws_cmd payment-cryptography-data generate-card-validation-data \
        --key-identifier "$DATA_KEY_ARN" \
        --primary-account-number "5081470082639564" \
        --generation-attributes 'CardVerificationValue1={CardExpiryDate=3003,ServiceCode=201}'
    assert_failure
    [[ "$output" == *"ValidationException"* ]]
}

@test "Payment Cryptography card: the authorization cryptogram is generated and verifies" {
    ACG_ARN=$(create_acg)
    [ -n "$ACG_ARN" ]

    run aws_cmd payment-cryptography-data generate-auth-request-cryptogram \
        --key-identifier "$ACG_ARN" \
        --transaction-data "00000000170000000000000008400080008000084016051700000000093800000B1F2201030000000000000000000000000000000000000000000000000000008000000000000000" \
        --major-key-derivation-mode EMV_OPTION_A \
        --session-key-derivation-attributes 'EmvCommon={PrimaryAccountNumber=5081470082639564,PanSequenceNumber=01,ApplicationTransactionCounter=0001}'
    assert_success
    CRYPTOGRAM=$(json_get "$output" '.AuthRequestCryptogram')
    [ -n "$CRYPTOGRAM" ]
    # The cryptogram is the eight bytes of the retail MAC, as hexadecimal.
    [ "${#CRYPTOGRAM}" -eq 16 ]

    run aws_cmd payment-cryptography-data verify-auth-request-cryptogram \
        --key-identifier "$ACG_ARN" \
        --transaction-data "00000000170000000000000008400080008000084016051700000000093800000B1F2201030000000000000000000000000000000000000000000000000000008000000000000000" \
        --auth-request-cryptogram "$CRYPTOGRAM" \
        --major-key-derivation-mode EMV_OPTION_A \
        --session-key-derivation-attributes 'EmvCommon={PrimaryAccountNumber=5081470082639564,PanSequenceNumber=01,ApplicationTransactionCounter=0001}'
    assert_success
}

@test "Payment Cryptography card: an authorization cryptogram that does not verify is refused" {
    ACG_ARN=$(create_acg)
    [ -n "$ACG_ARN" ]

    # The cryptogram is not the one over the transaction, so the verification fails.
    run aws_cmd payment-cryptography-data verify-auth-request-cryptogram \
        --key-identifier "$ACG_ARN" \
        --transaction-data "00000000170000000000000008400080008000084016051700000000093800000B1F2201030000000000000000000000000000000000000000000000000000008000000000000000" \
        --auth-request-cryptogram "0000000000000000" \
        --major-key-derivation-mode EMV_OPTION_A \
        --session-key-derivation-attributes 'EmvCommon={PrimaryAccountNumber=5081470082639564,PanSequenceNumber=01,ApplicationTransactionCounter=0001}'
    assert_failure
    [[ "$output" == *"VerificationFailedException"* ]]
}

@test "Payment Cryptography card: the American Express cryptogram is refused" {
    ACG_ARN=$(create_acg)
    [ -n "$ACG_ARN" ]

    # The AEIPS session key derivation is proprietary, so the service answers with the
    # error that says so rather than with a cryptogram of its own making.
    run aws_cmd payment-cryptography-data generate-auth-request-cryptogram \
        --key-identifier "$ACG_ARN" \
        --transaction-data "00000000170000000000000008400080008000084016051700000000093800000B1F2201030000000000000000000000000000000000000000000000000000008000000000000000" \
        --major-key-derivation-mode EMV_OPTION_A \
        --session-key-derivation-attributes 'Amex={PrimaryAccountNumber=5081470082639564,PanSequenceNumber=01}'
    assert_failure
    [[ "$output" == *"ValidationException"* ]]
}
