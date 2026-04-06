#!/usr/bin/env bats
# SQS tests

setup() {
    load 'test_helper/common-setup'
    QUEUE_NAME="bats-test-queue-$(unique_name)"
    QUEUE_URL=""
}

teardown() {
    if [ -n "$QUEUE_URL" ]; then
        aws_cmd sqs delete-queue --queue-url "$QUEUE_URL" >/dev/null 2>&1 || true
    fi
}

@test "SQS: create queue" {
    run aws_cmd sqs create-queue --queue-name "$QUEUE_NAME"
    assert_success
    QUEUE_URL=$(json_get "$output" '.QueueUrl')
    [ -n "$QUEUE_URL" ]
}

@test "SQS: get queue URL" {
    out=$(aws_cmd sqs create-queue --queue-name "$QUEUE_NAME")
    QUEUE_URL=$(json_get "$out" '.QueueUrl')

    run aws_cmd sqs get-queue-url --queue-name "$QUEUE_NAME"
    assert_success
    got_url=$(json_get "$output" '.QueueUrl')
    [ "$got_url" = "$QUEUE_URL" ]
}

@test "SQS: list queues" {
    out=$(aws_cmd sqs create-queue --queue-name "$QUEUE_NAME")
    QUEUE_URL=$(json_get "$out" '.QueueUrl')

    run aws_cmd sqs list-queues --queue-name-prefix "bats-test"
    assert_success
    found=$(echo "$output" | jq --arg name "$QUEUE_NAME" '.QueueUrls | any(contains($name))')
    [ "$found" = "true" ]
}

@test "SQS: send and receive message" {
    out=$(aws_cmd sqs create-queue --queue-name "$QUEUE_NAME")
    QUEUE_URL=$(json_get "$out" '.QueueUrl')

    run aws_cmd sqs send-message --queue-url "$QUEUE_URL" --message-body "hello-bats"
    assert_success
    msg_id=$(json_get "$output" '.MessageId')
    [ -n "$msg_id" ]

    run aws_cmd sqs receive-message --queue-url "$QUEUE_URL" --max-number-of-messages 1 --wait-time-seconds 1
    assert_success
    body=$(json_get "$output" '.Messages[0].Body')
    [ "$body" = "hello-bats" ]
}

@test "SQS: delete message" {
    out=$(aws_cmd sqs create-queue --queue-name "$QUEUE_NAME")
    QUEUE_URL=$(json_get "$out" '.QueueUrl')

    aws_cmd sqs send-message --queue-url "$QUEUE_URL" --message-body "hello-bats" >/dev/null

    out=$(aws_cmd sqs receive-message --queue-url "$QUEUE_URL" --max-number-of-messages 1 --wait-time-seconds 1)
    receipt=$(json_get "$out" '.Messages[0].ReceiptHandle')

    run aws_cmd sqs delete-message --queue-url "$QUEUE_URL" --receipt-handle "$receipt"
    assert_success
}

@test "SQS: get queue attributes" {
    out=$(aws_cmd sqs create-queue --queue-name "$QUEUE_NAME")
    QUEUE_URL=$(json_get "$out" '.QueueUrl')

    run aws_cmd sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names ApproximateNumberOfMessages
    assert_success
}

@test "SQS: delete queue" {
    out=$(aws_cmd sqs create-queue --queue-name "$QUEUE_NAME")
    QUEUE_URL=$(json_get "$out" '.QueueUrl')

    run aws_cmd sqs delete-queue --queue-url "$QUEUE_URL"
    assert_success
    QUEUE_URL=""
}
