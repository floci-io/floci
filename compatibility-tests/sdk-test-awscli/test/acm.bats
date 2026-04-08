#!/usr/bin/env bats
# ACM integration tests

setup() {
    load 'test_helper/common-setup'
    CERT_ARN=""
    IMPORTED_CERT_ARN=""
}

teardown() {
    if [ -n "$CERT_ARN" ]; then
        aws_cmd acm delete-certificate --certificate-arn "$CERT_ARN" >/dev/null 2>&1 || true
    fi
    if [ -n "$IMPORTED_CERT_ARN" ]; then
        aws_cmd acm delete-certificate --certificate-arn "$IMPORTED_CERT_ARN" >/dev/null 2>&1 || true
    fi
}

# ============================================
# US1: Certificate Lifecycle
# ============================================

@test "ACM: request certificate" {
    run aws_cmd acm request-certificate --domain-name "cli-test.example.com" --validation-method DNS
    assert_success
    CERT_ARN=$(json_get "$output" '.CertificateArn')
    [ -n "$CERT_ARN" ]
    [[ "$CERT_ARN" =~ ^arn:aws:acm: ]]
}

@test "ACM: describe certificate" {
    out=$(aws_cmd acm request-certificate --domain-name "cli-test.example.com" --validation-method DNS)
    CERT_ARN=$(json_get "$out" '.CertificateArn')

    run aws_cmd acm describe-certificate --certificate-arn "$CERT_ARN"
    assert_success
    domain=$(json_get "$output" '.Certificate.DomainName')
    status=$(json_get "$output" '.Certificate.Status')
    [ "$domain" = "cli-test.example.com" ]
    [ "$status" = "ISSUED" ]
}

@test "ACM: get certificate" {
    out=$(aws_cmd acm request-certificate --domain-name "cli-test.example.com" --validation-method DNS)
    CERT_ARN=$(json_get "$out" '.CertificateArn')

    run aws_cmd acm get-certificate --certificate-arn "$CERT_ARN"
    assert_success
    cert=$(json_get "$output" '.Certificate')
    [ -n "$cert" ]
    [[ "$cert" =~ "BEGIN CERTIFICATE" ]]
}

@test "ACM: list certificates" {
    out=$(aws_cmd acm request-certificate --domain-name "cli-test.example.com" --validation-method DNS)
    CERT_ARN=$(json_get "$out" '.CertificateArn')

    run aws_cmd acm list-certificates
    assert_success
    found=$(echo "$output" | jq --arg arn "$CERT_ARN" '.CertificateSummaryList | any(.CertificateArn == $arn)')
    [ "$found" = "true" ]
}

@test "ACM: delete certificate" {
    out=$(aws_cmd acm request-certificate --domain-name "cli-test.example.com" --validation-method DNS)
    CERT_ARN=$(json_get "$out" '.CertificateArn')

    run aws_cmd acm delete-certificate --certificate-arn "$CERT_ARN"
    assert_success
    CERT_ARN=""

    # Verify it's gone
    run aws_cmd acm describe-certificate --certificate-arn "$(json_get "$out" '.CertificateArn')"
    assert_failure
}

# ============================================
# US2: Import and Export
# ============================================

@test "ACM: import certificate" {
    # Generate self-signed certificate with openssl
    local key_file cert_file
    key_file=$(mktemp)
    cert_file=$(mktemp)
    openssl req -x509 -newkey rsa:2048 -keyout "$key_file" -out "$cert_file" \
        -days 365 -nodes -subj "/CN=cli-import.example.com" 2>/dev/null

    run aws_cmd acm import-certificate \
        --certificate "fileb://$cert_file" \
        --private-key "fileb://$key_file"
    rm -f "$key_file" "$cert_file"
    assert_success
    IMPORTED_CERT_ARN=$(json_get "$output" '.CertificateArn')
    [ -n "$IMPORTED_CERT_ARN" ]
}

@test "ACM: get imported certificate" {
    local key_file cert_file
    key_file=$(mktemp)
    cert_file=$(mktemp)
    openssl req -x509 -newkey rsa:2048 -keyout "$key_file" -out "$cert_file" \
        -days 365 -nodes -subj "/CN=cli-import.example.com" 2>/dev/null

    out=$(aws_cmd acm import-certificate \
        --certificate "fileb://$cert_file" \
        --private-key "fileb://$key_file")
    rm -f "$key_file" "$cert_file"
    IMPORTED_CERT_ARN=$(json_get "$out" '.CertificateArn')

    run aws_cmd acm get-certificate --certificate-arn "$IMPORTED_CERT_ARN"
    assert_success
    cert=$(json_get "$output" '.Certificate')
    [[ "$cert" =~ "BEGIN CERTIFICATE" ]]
}

@test "ACM: export certificate" {
    local key_file cert_file
    key_file=$(mktemp)
    cert_file=$(mktemp)
    openssl req -x509 -newkey rsa:2048 -keyout "$key_file" -out "$cert_file" \
        -days 365 -nodes -subj "/CN=cli-export.example.com" 2>/dev/null

    out=$(aws_cmd acm import-certificate \
        --certificate "fileb://$cert_file" \
        --private-key "fileb://$key_file")
    rm -f "$key_file" "$cert_file"
    IMPORTED_CERT_ARN=$(json_get "$out" '.CertificateArn')

    run aws_cmd acm export-certificate \
        --certificate-arn "$IMPORTED_CERT_ARN" \
        --passphrase "dGVzdC1wYXNzcGhyYXNl"
    assert_success
    cert=$(json_get "$output" '.Certificate')
    [ -n "$cert" ]
    pk=$(json_get "$output" '.PrivateKey')
    [ -n "$pk" ]
}

@test "ACM: export requested certificate fails" {
    out=$(aws_cmd acm request-certificate --domain-name "cli-noexport.example.com" --validation-method DNS)
    CERT_ARN=$(json_get "$out" '.CertificateArn')

    run aws_cmd acm export-certificate \
        --certificate-arn "$CERT_ARN" \
        --passphrase "dGVzdC1wYXNzcGhyYXNl"
    assert_failure
}

# ============================================
# US3: Tagging
# ============================================

@test "ACM: add and list tags" {
    out=$(aws_cmd acm request-certificate --domain-name "cli-tag.example.com" --validation-method DNS)
    CERT_ARN=$(json_get "$out" '.CertificateArn')

    run aws_cmd acm add-tags-to-certificate \
        --certificate-arn "$CERT_ARN" \
        --tags Key=Env,Value=test Key=Project,Value=floci
    assert_success

    run aws_cmd acm list-tags-for-certificate --certificate-arn "$CERT_ARN"
    assert_success
    env_tag=$(echo "$output" | jq '.Tags[] | select(.Key == "Env") | .Value' -r)
    [ "$env_tag" = "test" ]
    project_tag=$(echo "$output" | jq '.Tags[] | select(.Key == "Project") | .Value' -r)
    [ "$project_tag" = "floci" ]
}

@test "ACM: remove tags" {
    out=$(aws_cmd acm request-certificate --domain-name "cli-untag.example.com" --validation-method DNS)
    CERT_ARN=$(json_get "$out" '.CertificateArn')

    aws_cmd acm add-tags-to-certificate \
        --certificate-arn "$CERT_ARN" \
        --tags Key=Env,Value=test Key=Project,Value=floci

    run aws_cmd acm remove-tags-from-certificate \
        --certificate-arn "$CERT_ARN" \
        --tags Key=Env
    assert_success

    run aws_cmd acm list-tags-for-certificate --certificate-arn "$CERT_ARN"
    assert_success
    env_gone=$(echo "$output" | jq '.Tags[] | select(.Key == "Env")' 2>/dev/null)
    [ -z "$env_gone" ]
    project_tag=$(echo "$output" | jq '.Tags[] | select(.Key == "Project") | .Value' -r)
    [ "$project_tag" = "floci" ]
}

# ============================================
# US4: Account Configuration
# ============================================

@test "ACM: put and get account configuration" {
    run aws_cmd acm put-account-configuration \
        --expiry-events DaysBeforeExpiry=45 \
        --idempotency-token "cli-test-$(date +%s)"
    assert_success

    run aws_cmd acm get-account-configuration
    assert_success
    days=$(json_get "$output" '.ExpiryEvents.DaysBeforeExpiry')
    [ "$days" = "45" ]
}

# ============================================
# US5: Error Handling
# ============================================

@test "ACM: describe non-existent certificate" {
    run aws_cmd acm describe-certificate \
        --certificate-arn "arn:aws:acm:us-east-1:000000000000:certificate/00000000-0000-0000-0000-000000000000"
    assert_failure
}

@test "ACM: request certificate with SANs" {
    run aws_cmd acm request-certificate \
        --domain-name "cli-san.example.com" \
        --validation-method DNS \
        --subject-alternative-names "alt1.example.com" "alt2.example.com"
    assert_success
    CERT_ARN=$(json_get "$output" '.CertificateArn')

    run aws_cmd acm describe-certificate --certificate-arn "$CERT_ARN"
    assert_success
    sans=$(json_get "$output" '.Certificate.SubjectAlternativeNames')
    [[ "$sans" =~ "alt1.example.com" ]]
    [[ "$sans" =~ "alt2.example.com" ]]
}

@test "ACM: import invalid PEM" {
    run aws_cmd acm import-certificate \
        --certificate "not-valid-pem-data" \
        --private-key "also-not-valid-pem"
    assert_failure
}
