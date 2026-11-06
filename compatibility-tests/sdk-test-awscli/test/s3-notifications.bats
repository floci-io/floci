#!/usr/bin/env bats
# S3 Notification Filter integration tests

load 'test_helper/common-setup'

setup_file() {
    export TEST_BUCKET="$(unique_name s3-notif-filter-bucket)"
    export TEST_QUEUE="$(unique_name s3-notif-filter-queue)"
    export TEST_TOPIC="$(unique_name s3-notif-filter-topic)"
    export ACCOUNT_ID="000000000000"
    export QUEUE_ARN="arn:aws:sqs:us-east-1:${ACCOUNT_ID}:${TEST_QUEUE}"

    # Create SQS queue
    aws_cmd sqs create-queue --queue-name "$TEST_QUEUE" >/dev/null 2>&1

    # Create SNS topic and capture ARN
    local topic_out
    topic_out=$(aws_cmd sns create-topic --name "$TEST_TOPIC")
    export TOPIC_ARN=$(json_get "$topic_out" '.TopicArn')

    # Create S3 bucket
    aws_cmd s3api create-bucket --bucket "$TEST_BUCKET" >/dev/null 2>&1
}

teardown_file() {
    # Cleanup resources
    aws_cmd s3api delete-bucket --bucket "$TEST_BUCKET" >/dev/null 2>&1 || true
    aws_cmd sqs delete-queue --queue-url "${FLOCI_ENDPOINT}/${ACCOUNT_ID}/${TEST_QUEUE}" >/dev/null 2>&1 || true
    aws_cmd sns delete-topic --topic-arn "$TOPIC_ARN" >/dev/null 2>&1 || true
}

@test "S3 Notifications: put bucket notification configuration with filter" {
    local notif_config
    notif_config=$(cat <<EOF
{
  "QueueConfigurations": [{
    "Id": "sqs-filtered",
    "QueueArn": "${QUEUE_ARN}",
    "Events": ["s3:ObjectCreated:*"],
    "Filter": {
      "Key": {
        "FilterRules": [
          {"Name": "prefix", "Value": "incoming/"},
          {"Name": "suffix", "Value": ".csv"}
        ]
      }
    }
  }],
  "TopicConfigurations": [{
    "Id": "sns-filtered",
    "TopicArn": "${TOPIC_ARN}",
    "Events": ["s3:ObjectRemoved:*"],
    "Filter": {
      "Key": {
        "FilterRules": [
          {"Name": "prefix", "Value": ""},
          {"Name": "suffix", "Value": ".txt"}
        ]
      }
    }
  }]
}
EOF
)

    run aws_cmd s3api put-bucket-notification-configuration \
        --bucket "$TEST_BUCKET" \
        --notification-configuration "$notif_config"
    assert_success
}

@test "S3 Notifications: get bucket notification configuration - queue filter round-trip" {
    run aws_cmd s3api get-bucket-notification-configuration --bucket "$TEST_BUCKET"
    assert_success

    # Verify queue configuration has 2 filter rules
    local queue_filter_count
    queue_filter_count=$(echo "$output" | jq "[.QueueConfigurations[] | select(.QueueArn == \"${QUEUE_ARN}\")][0].Filter.Key.FilterRules | length" 2>/dev/null || echo "0")
    [ "$queue_filter_count" = "2" ]
}

@test "S3 Notifications: get bucket notification configuration - topic filter round-trip" {
    run aws_cmd s3api get-bucket-notification-configuration --bucket "$TEST_BUCKET"
    assert_success

    # Verify topic configuration has 2 filter rules
    local topic_filter_count
    topic_filter_count=$(echo "$output" | jq "[.TopicConfigurations[] | select(.TopicArn == \"${TOPIC_ARN}\")][0].Filter.Key.FilterRules | length" 2>/dev/null || echo "0")
    [ "$topic_filter_count" = "2" ]
}
