#!/usr/bin/env bats
# Payment Cryptography key exchange tests
#
# The key exchange is the control plane's own operations, so a client reaches them at the
# control plane's route. These tests drive them with the AWS CLI, which is such a client,
# and its shorthand for a union member is a client's own way of writing the form.

setup() {
    load 'test_helper/common-setup'
    WRAPPING_ARN=""
    KEY_ARN=""
    CREATED_ARNS=""
}

teardown() {
    for key in "$WRAPPING_ARN" "$KEY_ARN" $CREATED_ARNS; do
        if [ -n "$key" ]; then
            aws_cmd payment-cryptography delete-key --key-identifier "$key" \
                --delete-key-in-days 7 >/dev/null 2>&1 || true
        fi
    done
}

# The key a key block is wrapped under.
create_wrapping_key() {
    aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=AES_256,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_K1_KEY_BLOCK_PROTECTION_KEY,KeyModesOfUse={Wrap=true,Unwrap=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
}

# The key that leaves the service.
create_key() {
    aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=AES_256,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
}

# The key that leaves the service in a TR-34 block. The block is wrapped under an RSA key,
# and the wrapping key has to be at least as strong as the key it protects, so the key is
# a Triple DES one.
create_tdes_key() {
    aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=TDES_2KEY,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
        --exportable \
        --query 'Key.KeyArn' --output text
}

# The public half of a certificate, held by the service as the authority of the party it
# belongs to. The certificate is self-signed, so it comes in as a root certificate's key.
import_authority() {
    local certificate="$1"
    local algorithm="$2"
    local arn
    arn=$(aws_cmd payment-cryptography import-key \
        --key-material "RootCertificatePublicKey={KeyAttributes={KeyAlgorithm=$algorithm,KeyClass=PUBLIC_KEY,KeyUsage=TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE,KeyModesOfUse={Verify=true}},PublicKeyCertificate=$certificate}" \
        --query 'Key.KeyArn' --output text)
    CREATED_ARNS="$CREATED_ARNS $arn"
    echo "$arn"
}

@test "Payment Cryptography key exchange: the import parameters carry a token and a certificate" {
    run aws_cmd payment-cryptography get-parameters-for-import \
        --key-material-type KEY_CRYPTOGRAM \
        --wrapping-key-algorithm RSA_2048
    assert_success
    TOKEN=$(json_get "$output" '.ImportToken')
    [ -n "$TOKEN" ]
    # The token is named for the operation that issued it.
    [[ "$TOKEN" == import-token-* ]]
    [ "$(json_get "$output" '.WrappingKeyAlgorithm')" = "RSA_2048" ]
    [ -n "$(json_get "$output" '.WrappingKeyCertificate')" ]
    # The chain of a self-signed certificate is the certificate itself.
    [ "$(json_get "$output" '.WrappingKeyCertificateChain')" = "$(json_get "$output" '.WrappingKeyCertificate')" ]
    [ -n "$(json_get "$output" '.ParametersValidUntilTimestamp')" ]
}

@test "Payment Cryptography key exchange: the export parameters carry a token and a certificate" {
    run aws_cmd payment-cryptography get-parameters-for-export \
        --key-material-type TR34_KEY_BLOCK \
        --signing-key-algorithm RSA_2048
    assert_success
    TOKEN=$(json_get "$output" '.ExportToken')
    [ -n "$TOKEN" ]
    [[ "$TOKEN" == export-token-* ]]
    [ "$(json_get "$output" '.SigningKeyAlgorithm')" = "RSA_2048" ]
    [ -n "$(json_get "$output" '.SigningKeyCertificate')" ]
    [ -n "$(json_get "$output" '.ParametersValidUntilTimestamp')" ]
}

@test "Payment Cryptography key exchange: a key leaves in a key block and comes back" {
    WRAPPING_ARN=$(create_wrapping_key)
    KEY_ARN=$(create_key)
    [ -n "$WRAPPING_ARN" ]
    [ -n "$KEY_ARN" ]

    run aws_cmd payment-cryptography export-key \
        --export-key-identifier "$KEY_ARN" \
        --key-material "Tr31KeyBlock={WrappingKeyIdentifier=$WRAPPING_ARN}"
    assert_success
    [ "$(json_get "$output" '.WrappedKey.WrappedKeyMaterialFormat')" = "TR31_KEY_BLOCK" ]
    [ "$(json_get "$output" '.WrappedKey.WrappingKeyArn')" = "$WRAPPING_ARN" ]
    BLOCK=$(json_get "$output" '.WrappedKey.KeyMaterial')
    [ -n "$BLOCK" ]
    [ -n "$(json_get "$output" '.WrappedKey.KeyCheckValue')" ]

    run aws_cmd payment-cryptography import-key \
        --key-material "Tr31KeyBlock={WrappingKeyIdentifier=$WRAPPING_ARN,WrappedKeyBlock=$BLOCK}"
    assert_success
    IMPORTED=$(json_get "$output" '.Key.KeyArn')
    [ -n "$IMPORTED" ]
    [ "$(json_get "$output" '.Key.KeyAttributes.KeyUsage')" = "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY" ]
}

@test "Payment Cryptography key exchange: a key that may not leave is refused" {
    WRAPPING_ARN=$(create_wrapping_key)
    [ -n "$WRAPPING_ARN" ]
    # The key is made with the export off, which the CLI writes as the negated flag.
    run aws_cmd payment-cryptography create-key \
        --key-attributes 'KeyAlgorithm=AES_256,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
        --no-exportable \
        --query 'Key.KeyArn' --output text
    assert_success
    KEY_ARN="$output"
    [ -n "$KEY_ARN" ]

    run aws_cmd payment-cryptography export-key \
        --export-key-identifier "$KEY_ARN" \
        --key-material "Tr31KeyBlock={WrappingKeyIdentifier=$WRAPPING_ARN}"
    assert_failure
    [[ "$output" == *"ValidationException"* ]]
}

@test "Payment Cryptography key exchange: a form the service does not serve is refused" {
    KEY_ARN=$(create_key)
    [ -n "$KEY_ARN" ]
    # The request has to carry what the model asks for, or the client refuses it before
    # the service ever sees it.
    run aws_cmd payment-cryptography get-parameters-for-import \
        --key-material-type KEY_CRYPTOGRAM \
        --wrapping-key-algorithm RSA_2048
    assert_success
    CERTIFICATE=$(json_get "$output" '.WrappingKeyCertificate')
    [ -n "$CERTIFICATE" ]

    # Every form the model's union holds is one the service serves now, so the client names
    # the forms it can ask for.
    run aws_cmd payment-cryptography export-key \
        --export-key-identifier "$KEY_ARN" \
        --key-material "Nonsense={}"
    assert_failure
    [[ "$output" == *"Tr31KeyBlock, Tr34KeyBlock, KeyCryptogram, DiffieHellmanTr31KeyBlock, As2805KeyCryptogram"* ]]
}

@test "Payment Cryptography key exchange: a key leaves under a TR-34 block and comes back" {
    # The parameters of the import name the certificate the block is wrapped under, and the
    # parameters of the export name the certificate the block is signed with.
    run aws_cmd payment-cryptography get-parameters-for-import \
        --key-material-type TR34_KEY_BLOCK \
        --wrapping-key-algorithm RSA_3072
    assert_success
    IMPORT_TOKEN=$(json_get "$output" '.ImportToken')
    WRAPPING_CERTIFICATE=$(json_get "$output" '.WrappingKeyCertificate')
    [ -n "$IMPORT_TOKEN" ]
    [ -n "$WRAPPING_CERTIFICATE" ]

    # The receiver's authority: the public half of the certificate the block is wrapped
    # under.
    RECEIVER_ARN=$(import_authority "$WRAPPING_CERTIFICATE" RSA_3072)
    [ -n "$RECEIVER_ARN" ]

    run aws_cmd payment-cryptography get-parameters-for-export \
        --key-material-type TR34_KEY_BLOCK \
        --signing-key-algorithm RSA_2048
    assert_success
    EXPORT_TOKEN=$(json_get "$output" '.ExportToken')
    SIGNING_CERTIFICATE=$(json_get "$output" '.SigningKeyCertificate')
    [ -n "$EXPORT_TOKEN" ]
    [ -n "$SIGNING_CERTIFICATE" ]

    # The sender's authority: the public half of the certificate the block is signed with.
    SENDER_ARN=$(import_authority "$SIGNING_CERTIFICATE" RSA_2048)
    [ -n "$SENDER_ARN" ]

    KEY_ARN=$(create_tdes_key)
    [ -n "$KEY_ARN" ]

    run aws_cmd payment-cryptography export-key \
        --export-key-identifier "$KEY_ARN" \
        --key-material "Tr34KeyBlock={CertificateAuthorityPublicKeyIdentifier=$RECEIVER_ARN,WrappingKeyCertificate=$WRAPPING_CERTIFICATE,ExportToken=$EXPORT_TOKEN,KeyBlockFormat=X9_TR34_2012,RandomNonce=A1B2C3D4E5F60718}"
    assert_success
    [ "$(json_get "$output" '.WrappedKey.WrappedKeyMaterialFormat')" = "TR34_KEY_BLOCK" ]
    BLOCK=$(json_get "$output" '.WrappedKey.KeyMaterial')
    [ -n "$BLOCK" ]

    run aws_cmd payment-cryptography import-key \
        --key-material "Tr34KeyBlock={CertificateAuthorityPublicKeyIdentifier=$SENDER_ARN,SigningKeyCertificate=$SIGNING_CERTIFICATE,ImportToken=$IMPORT_TOKEN,WrappedKeyBlock=$BLOCK,KeyBlockFormat=X9_TR34_2012,RandomNonce=A1B2C3D4E5F60718}"
    assert_success
    [ -n "$(json_get "$output" '.Key.KeyArn')" ]
    [ "$(json_get "$output" '.Key.KeyAttributes.KeyUsage')" = "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY" ]
}
