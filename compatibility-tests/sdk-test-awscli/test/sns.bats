#!/usr/bin/env bats
# SNS tests

setup() {
    load 'test_helper/common-setup'
    TOPIC_NAME="bats-test-topic-$(unique_name)"
    TOPIC_ARN=""
}

teardown() {
    if [ -n "$TOPIC_ARN" ]; then
        aws_cmd sns delete-topic --topic-arn "$TOPIC_ARN" >/dev/null 2>&1 || true
    fi
}

@test "SNS: create topic" {
    run aws_cmd sns create-topic --name "$TOPIC_NAME"
    assert_success
    TOPIC_ARN=$(json_get "$output" '.TopicArn')
    [ -n "$TOPIC_ARN" ]
}

@test "SNS: list topics" {
    out=$(aws_cmd sns create-topic --name "$TOPIC_NAME")
    TOPIC_ARN=$(json_get "$out" '.TopicArn')

    run aws_cmd sns list-topics
    assert_success
    found=$(echo "$output" | jq --arg name "$TOPIC_NAME" '.Topics | any(.TopicArn | contains($name))')
    [ "$found" = "true" ]
}

@test "SNS: get topic attributes" {
    out=$(aws_cmd sns create-topic --name "$TOPIC_NAME")
    TOPIC_ARN=$(json_get "$out" '.TopicArn')

    run aws_cmd sns get-topic-attributes --topic-arn "$TOPIC_ARN"
    assert_success
}

@test "SNS: publish message" {
    out=$(aws_cmd sns create-topic --name "$TOPIC_NAME")
    TOPIC_ARN=$(json_get "$out" '.TopicArn')

    run aws_cmd sns publish --topic-arn "$TOPIC_ARN" --message "hello-bats"
    assert_success
    msg_id=$(json_get "$output" '.MessageId')
    [ -n "$msg_id" ]
}

@test "SNS: delete topic" {
    out=$(aws_cmd sns create-topic --name "$TOPIC_NAME")
    TOPIC_ARN=$(json_get "$out" '.TopicArn')

    run aws_cmd sns delete-topic --topic-arn "$TOPIC_ARN"
    assert_success
    TOPIC_ARN=""
}
