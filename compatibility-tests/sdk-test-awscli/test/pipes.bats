#!/usr/bin/env bats
# EventBridge Pipes tests

setup() {
    load 'test_helper/common-setup'
    PIPE_NAME="bats-pipe-$(unique_name)"
    SOURCE_QUEUE="bats-pipe-src-$(unique_name)"
    TARGET_QUEUE="bats-pipe-tgt-$(unique_name)"
    SOURCE_QUEUE_URL=""
    TARGET_QUEUE_URL=""
    ROLE_ARN="arn:aws:iam::000000000000:role/pipe-role"
}

teardown() {
    aws_cmd pipes delete-pipe --name "$PIPE_NAME" >/dev/null 2>&1 || true
    if [ -n "$SOURCE_QUEUE_URL" ]; then
        aws_cmd sqs delete-queue --queue-url "$SOURCE_QUEUE_URL" >/dev/null 2>&1 || true
    fi
    if [ -n "$TARGET_QUEUE_URL" ]; then
        aws_cmd sqs delete-queue --queue-url "$TARGET_QUEUE_URL" >/dev/null 2>&1 || true
    fi
}

create_queues() {
    out=$(aws_cmd sqs create-queue --queue-name "$SOURCE_QUEUE")
    SOURCE_QUEUE_URL=$(json_get "$out" '.QueueUrl')
    out=$(aws_cmd sqs create-queue --queue-name "$TARGET_QUEUE")
    TARGET_QUEUE_URL=$(json_get "$out" '.QueueUrl')
}

source_arn() {
    echo "arn:aws:sqs:${AWS_DEFAULT_REGION}:000000000000:${SOURCE_QUEUE}"
}

target_arn() {
    echo "arn:aws:sqs:${AWS_DEFAULT_REGION}:000000000000:${TARGET_QUEUE}"
}

@test "Pipes: create pipe in STOPPED state" {
    create_queues

    run aws_cmd pipes create-pipe \
        --name "$PIPE_NAME" \
        --source "$(source_arn)" \
        --target "$(target_arn)" \
        --role-arn "$ROLE_ARN" \
        --desired-state STOPPED
    assert_success

    state=$(json_get "$output" '.CurrentState')
    [ "$state" = "STOPPED" ]

    name=$(json_get "$output" '.Name')
    [ "$name" = "$PIPE_NAME" ]
}

@test "Pipes: describe pipe" {
    create_queues
    aws_cmd pipes create-pipe \
        --name "$PIPE_NAME" \
        --source "$(source_arn)" \
        --target "$(target_arn)" \
        --role-arn "$ROLE_ARN" \
        --desired-state STOPPED >/dev/null

    run aws_cmd pipes describe-pipe --name "$PIPE_NAME"
    assert_success

    name=$(json_get "$output" '.Name')
    [ "$name" = "$PIPE_NAME" ]

    source=$(json_get "$output" '.Source')
    [ "$source" = "$(source_arn)" ]

    target=$(json_get "$output" '.Target')
    [ "$target" = "$(target_arn)" ]
}

@test "Pipes: list pipes" {
    create_queues
    aws_cmd pipes create-pipe \
        --name "$PIPE_NAME" \
        --source "$(source_arn)" \
        --target "$(target_arn)" \
        --role-arn "$ROLE_ARN" \
        --desired-state STOPPED >/dev/null

    run aws_cmd pipes list-pipes
    assert_success

    found=$(echo "$output" | jq --arg name "$PIPE_NAME" '.Pipes | any(.Name == $name)')
    [ "$found" = "true" ]
}

@test "Pipes: update pipe" {
    create_queues
    aws_cmd pipes create-pipe \
        --name "$PIPE_NAME" \
        --source "$(source_arn)" \
        --target "$(target_arn)" \
        --role-arn "$ROLE_ARN" \
        --desired-state STOPPED >/dev/null

    run aws_cmd pipes update-pipe \
        --name "$PIPE_NAME" \
        --role-arn "$ROLE_ARN" \
        --description "updated description" \
        --desired-state STOPPED
    assert_success

    run aws_cmd pipes describe-pipe --name "$PIPE_NAME"
    assert_success
    desc=$(json_get "$output" '.Description')
    [ "$desc" = "updated description" ]
}

@test "Pipes: start and stop pipe" {
    create_queues
    aws_cmd pipes create-pipe \
        --name "$PIPE_NAME" \
        --source "$(source_arn)" \
        --target "$(target_arn)" \
        --role-arn "$ROLE_ARN" \
        --desired-state STOPPED >/dev/null

    run aws_cmd pipes start-pipe --name "$PIPE_NAME"
    assert_success
    state=$(json_get "$output" '.CurrentState')
    [ "$state" = "RUNNING" ]

    run aws_cmd pipes stop-pipe --name "$PIPE_NAME"
    assert_success
    state=$(json_get "$output" '.CurrentState')
    [ "$state" = "STOPPED" ]
}

@test "Pipes: delete pipe" {
    create_queues
    aws_cmd pipes create-pipe \
        --name "$PIPE_NAME" \
        --source "$(source_arn)" \
        --target "$(target_arn)" \
        --role-arn "$ROLE_ARN" \
        --desired-state STOPPED >/dev/null

    run aws_cmd pipes delete-pipe --name "$PIPE_NAME"
    assert_success

    run aws_cmd pipes describe-pipe --name "$PIPE_NAME"
    assert_failure
}

@test "Pipes: describe non-existent pipe returns error" {
    run aws_cmd pipes describe-pipe --name "non-existent-pipe"
    assert_failure
}

@test "Pipes: SQS to SQS forwarding" {
    create_queues
    aws_cmd pipes create-pipe \
        --name "$PIPE_NAME" \
        --source "$(source_arn)" \
        --target "$(target_arn)" \
        --role-arn "$ROLE_ARN" \
        --desired-state RUNNING >/dev/null

    aws_cmd sqs send-message \
        --queue-url "$SOURCE_QUEUE_URL" \
        --message-body "hello from pipes" >/dev/null

    # Poll target queue for forwarded message
    local found=false
    for i in $(seq 1 15); do
        sleep 1
        out=$(aws_cmd sqs receive-message \
            --queue-url "$TARGET_QUEUE_URL" \
            --max-number-of-messages 1 \
            --wait-time-seconds 1)
        if echo "$out" | grep -q "hello from pipes"; then
            found=true
            break
        fi
    done

    [ "$found" = "true" ]
}

@test "Pipes: FilterCriteria filters messages" {
    create_queues
    aws_cmd pipes create-pipe \
        --name "$PIPE_NAME" \
        --source "$(source_arn)" \
        --target "$(target_arn)" \
        --role-arn "$ROLE_ARN" \
        --desired-state RUNNING \
        --source-parameters '{"FilterCriteria":{"Filters":[{"Pattern":"{\"body\": {\"status\": [\"active\"]}}"}]}}' >/dev/null

    aws_cmd sqs send-message \
        --queue-url "$SOURCE_QUEUE_URL" \
        --message-body '{"status": "active", "id": "match-1"}' >/dev/null

    aws_cmd sqs send-message \
        --queue-url "$SOURCE_QUEUE_URL" \
        --message-body '{"status": "inactive", "id": "no-match"}' >/dev/null

    # Poll target queue for matching message
    local found=false
    for i in $(seq 1 15); do
        sleep 1
        out=$(aws_cmd sqs receive-message \
            --queue-url "$TARGET_QUEUE_URL" \
            --max-number-of-messages 10 \
            --wait-time-seconds 1)
        if echo "$out" | grep -q "match-1"; then
            # Verify no non-matching message arrived
            if echo "$out" | grep -q "no-match"; then
                fail "non-matching message should not be forwarded"
            fi
            found=true
            break
        fi
    done

    [ "$found" = "true" ]

    # Source queue should be drained (non-matching messages deleted per AWS behavior)
    out=$(aws_cmd sqs get-queue-attributes \
        --queue-url "$SOURCE_QUEUE_URL" \
        --attribute-names ApproximateNumberOfMessages)
    count=$(json_get "$out" '.Attributes.ApproximateNumberOfMessages')
    [ "$count" = "0" ]
}

@test "Pipes: BatchSize in SourceParameters" {
    create_queues
    aws_cmd pipes create-pipe \
        --name "$PIPE_NAME" \
        --source "$(source_arn)" \
        --target "$(target_arn)" \
        --role-arn "$ROLE_ARN" \
        --desired-state RUNNING \
        --source-parameters '{"SqsQueueParameters":{"BatchSize":1}}' >/dev/null

    for i in 1 2 3; do
        aws_cmd sqs send-message \
            --queue-url "$SOURCE_QUEUE_URL" \
            --message-body "batch-msg-$i" >/dev/null
    done

    # Poll target queue until all 3 messages arrive
    local found_count=0
    local found1=false found2=false found3=false
    for i in $(seq 1 20); do
        sleep 1
        out=$(aws_cmd sqs receive-message \
            --queue-url "$TARGET_QUEUE_URL" \
            --max-number-of-messages 10 \
            --wait-time-seconds 1)
        if [ "$found1" = "false" ] && echo "$out" | grep -q "batch-msg-1"; then
            found1=true
        fi
        if [ "$found2" = "false" ] && echo "$out" | grep -q "batch-msg-2"; then
            found2=true
        fi
        if [ "$found3" = "false" ] && echo "$out" | grep -q "batch-msg-3"; then
            found3=true
        fi
        if [ "$found1" = "true" ] && [ "$found2" = "true" ] && [ "$found3" = "true" ]; then
            break
        fi
    done

    [ "$found1" = "true" ]
    [ "$found2" = "true" ]
    [ "$found3" = "true" ]
}

@test "Pipes: stopped pipe does not forward messages" {
    create_queues
    aws_cmd pipes create-pipe \
        --name "$PIPE_NAME" \
        --source "$(source_arn)" \
        --target "$(target_arn)" \
        --role-arn "$ROLE_ARN" \
        --desired-state STOPPED >/dev/null

    aws_cmd sqs send-message \
        --queue-url "$SOURCE_QUEUE_URL" \
        --message-body "should not forward" >/dev/null

    sleep 3

    # Source should still have the message
    out=$(aws_cmd sqs get-queue-attributes \
        --queue-url "$SOURCE_QUEUE_URL" \
        --attribute-names ApproximateNumberOfMessages)
    count=$(json_get "$out" '.Attributes.ApproximateNumberOfMessages')
    [ "$count" = "1" ]

    # Target should be empty
    out=$(aws_cmd sqs receive-message \
        --queue-url "$TARGET_QUEUE_URL" \
        --max-number-of-messages 1)
    msgs=$(echo "$out" | jq '.Messages // [] | length')
    [ "$msgs" = "0" ]
}
