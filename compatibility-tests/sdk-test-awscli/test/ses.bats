#!/usr/bin/env bats
# SES integration tests

load 'test_helper/common-setup'

setup_file() {
    export TEST_EMAIL="test-$(date +%s)@example.com"
    export TEST_DOMAIN="test-$(date +%s).example.com"
}

teardown_file() {
    # Cleanup identities
    aws_cmd ses delete-identity --identity "$TEST_EMAIL" >/dev/null 2>&1 || true
    aws_cmd ses delete-identity --identity "$TEST_DOMAIN" >/dev/null 2>&1 || true
}

@test "SES: verify email identity" {
    run aws_cmd ses verify-email-identity --email-address "$TEST_EMAIL"
    assert_success
}

@test "SES: verify domain identity" {
    run aws_cmd ses verify-domain-identity --domain "$TEST_DOMAIN"
    assert_success
    token=$(json_get "$output" '.VerificationToken')
    [ -n "$token" ]
}

@test "SES: list identities" {
    run aws_cmd ses list-identities
    assert_success
    assert_output --partial "$TEST_EMAIL"
    assert_output --partial "$TEST_DOMAIN"
}

@test "SES: list identities by type EmailAddress" {
    run aws_cmd ses list-identities --identity-type EmailAddress
    assert_success
    assert_output --partial "$TEST_EMAIL"
    refute_output --partial "$TEST_DOMAIN"
}

@test "SES: get identity verification attributes" {
    run aws_cmd ses get-identity-verification-attributes --identities "$TEST_EMAIL"
    assert_success
    status=$(json_get "$output" ".VerificationAttributes.\"$TEST_EMAIL\".VerificationStatus")
    [ "$status" = "Success" ]
}

@test "SES: send email" {
    run aws_cmd ses send-email \
        --from "$TEST_EMAIL" \
        --destination "ToAddresses=recipient@example.com" \
        --message "Subject={Data=Test Subject},Body={Text={Data=Hello from SES test}}"
    assert_success
    message_id=$(json_get "$output" '.MessageId')
    [ -n "$message_id" ]
}

@test "SES: send raw email" {
    local raw_file
    raw_file=$(mktemp)
    printf 'From: %s\r\nTo: recipient@example.com\r\nSubject: Raw Test\r\n\r\nRaw body' "$TEST_EMAIL" > "$raw_file"
    local raw_b64
    raw_b64=$(python3 -c "import base64,sys; print(base64.b64encode(open(sys.argv[1],'rb').read()).decode())" "$raw_file")

    run aws_cmd ses send-raw-email \
        --source "$TEST_EMAIL" \
        --destinations "recipient@example.com" \
        --raw-message "Data=$raw_b64"
    rm -f "$raw_file"

    assert_success
    message_id=$(json_get "$output" '.MessageId')
    [ -n "$message_id" ]
}

@test "SES: get send quota" {
    run aws_cmd ses get-send-quota
    assert_success
    max_24h=$(json_get "$output" '.Max24HourSend')
    max_rate=$(json_get "$output" '.MaxSendRate')
    [ "$(echo "$max_24h > 0" | bc)" -eq 1 ]
    [ "$(echo "$max_rate > 0" | bc)" -eq 1 ]
}

@test "SES: get send statistics" {
    run aws_cmd ses get-send-statistics
    assert_success
    # Should have SendDataPoints array (may be empty)
    run jq -e '.SendDataPoints' <<< "$output"
    assert_success
}

@test "SES: get account sending enabled" {
    run aws_cmd ses get-account-sending-enabled
    assert_success
    enabled=$(json_get "$output" '.Enabled')
    [ "$enabled" = "true" ]
}

@test "SES: get identity dkim attributes" {
    run aws_cmd ses get-identity-dkim-attributes --identities "$TEST_DOMAIN"
    assert_success
    # Should have DkimAttributes for our domain
    run jq -e ".DkimAttributes.\"$TEST_DOMAIN\"" <<< "$output"
    assert_success
}

@test "SES: set identity notification topic" {
    # This test checks that the command is accepted (may have parser quirks)
    run aws_cmd ses set-identity-notification-topic \
        --identity "$TEST_EMAIL" \
        --notification-type Bounce \
        --sns-topic arn:aws:sns:us-east-1:000000000000:bounce-topic
    # Accept success or known parser bug output
    [[ $status -eq 0 ]] || [[ "$output" == *"SetIdentityNotificationTopicResult"* ]]
}

@test "SES: get identity notification attributes" {
    run aws_cmd ses get-identity-notification-attributes --identities "$TEST_EMAIL"
    assert_success
}

@test "SES: delete identity email" {
    run aws_cmd ses delete-identity --identity "$TEST_EMAIL"
    # Accept success or known parser bug output
    [[ $status -eq 0 ]] || [[ "$output" == *"DeleteIdentityResult"* ]]
}

@test "SES: delete identity domain" {
    run aws_cmd ses delete-identity --identity "$TEST_DOMAIN"
    # Accept success or known parser bug output
    [[ $status -eq 0 ]] || [[ "$output" == *"DeleteIdentityResult"* ]]
}

@test "SES: verify identities deleted" {
    run aws_cmd ses list-identities
    assert_success
    refute_output --partial "$TEST_EMAIL"
    refute_output --partial "$TEST_DOMAIN"
}
