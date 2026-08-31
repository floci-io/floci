#!/usr/bin/env bats
# Bedrock Batch Inference tests

setup() {
    load 'test_helper/common-setup'
    BUCKET="bats-bedrock-$(date +%s)-$$"
    aws_cmd s3 mb "s3://$BUCKET" >/dev/null 2>&1 || true
}

teardown() {
    aws_cmd s3 rb "s3://$BUCKET" --force >/dev/null 2>&1 || true
}

@test "Bedrock: create and get model invocation job" {
    JOB_NAME="bats-job-$(date +%s)-$$"
    INPUT_URI="s3://$BUCKET/in.jsonl"
    OUTPUT_URI="s3://$BUCKET/out"

    run aws_cmd bedrock create-model-invocation-job \
        --job-name "$JOB_NAME" \
        --model-id "amazon.titan-text-express-v1" \
        --role-arn "arn:aws:iam::000000000000:role/BedrockBatchRole" \
        --input-data-config "{\"s3InputDataConfig\":{\"s3Uri\":\"$INPUT_URI\"}}" \
        --output-data-config "{\"s3OutputDataConfig\":{\"s3Uri\":\"$OUTPUT_URI\"}}" \
        --tags "key=Environment,value=testing"
    assert_success

    JOB_ARN=$(json_get "$output" '.jobArn')
    [ -n "$JOB_ARN" ]
    [[ "$JOB_ARN" == *":model-invocation-job/"* ]]

    run aws_cmd bedrock get-model-invocation-job --job-identifier "$JOB_ARN"
    assert_success

    name=$(json_get "$output" '.jobName')
    model=$(json_get "$output" '.modelId')
    status=$(json_get "$output" '.status')

    [ "$name" = "$JOB_NAME" ]
    [ "$model" = "amazon.titan-text-express-v1" ]
    [ -n "$status" ]
}

@test "Bedrock: list model invocation jobs with filtering" {
    JOB_NAME_1="bats-list-1-$(date +%s)-$$"
    JOB_NAME_2="bats-list-2-$(date +%s)-$$"

    run aws_cmd bedrock create-model-invocation-job \
        --job-name "$JOB_NAME_1" \
        --model-id "amazon.titan-text-express-v1" \
        --role-arn "arn:aws:iam::000000000000:role/BedrockBatchRole" \
        --input-data-config "{\"s3InputDataConfig\":{\"s3Uri\":\"s3://$BUCKET/1.jsonl\"}}" \
        --output-data-config "{\"s3OutputDataConfig\":{\"s3Uri\":\"s3://$BUCKET/out1\"}}"
    assert_success

    run aws_cmd bedrock create-model-invocation-job \
        --job-name "$JOB_NAME_2" \
        --model-id "anthropic.claude-3-sonnet-20240229-v1:0" \
        --role-arn "arn:aws:iam::000000000000:role/BedrockBatchRole" \
        --input-data-config "{\"s3InputDataConfig\":{\"s3Uri\":\"s3://$BUCKET/2.jsonl\"}}" \
        --output-data-config "{\"s3OutputDataConfig\":{\"s3Uri\":\"s3://$BUCKET/out2\"}}"
    assert_success

    run aws_cmd bedrock list-model-invocation-jobs --name-contains "$JOB_NAME_1"
    assert_success

    found_1=$(echo "$output" | jq -r --arg n "$JOB_NAME_1" '.invocationJobSummaries | any(.jobName == $n)')
    found_2=$(echo "$output" | jq -r --arg n "$JOB_NAME_2" '.invocationJobSummaries | any(.jobName == $n)')

    [ "$found_1" = "true" ]
    [ "$found_2" = "false" ]
}

@test "Bedrock: stop model invocation job" {
    JOB_NAME="bats-stop-$(date +%s)-$$"

    run aws_cmd bedrock create-model-invocation-job \
        --job-name "$JOB_NAME" \
        --model-id "amazon.titan-text-express-v1" \
        --role-arn "arn:aws:iam::000000000000:role/BedrockBatchRole" \
        --input-data-config "{\"s3InputDataConfig\":{\"s3Uri\":\"s3://$BUCKET/stop.jsonl\"}}" \
        --output-data-config "{\"s3OutputDataConfig\":{\"s3Uri\":\"s3://$BUCKET/stop-out\"}}"
    assert_success

    JOB_ARN=$(json_get "$output" '.jobArn')

    # Only call stop if the job hasn't already reached a terminal status
    current_status=$(aws_cmd bedrock get-model-invocation-job --job-identifier "$JOB_ARN" 2>/dev/null | jq -r '.status')
    if [[ "$current_status" != "Completed" && "$current_status" != "Failed" && \
          "$current_status" != "Stopped" && "$current_status" != "Expired" && \
          "$current_status" != "PartiallyCompleted" ]]; then
        run aws_cmd bedrock stop-model-invocation-job --job-identifier "$JOB_ARN"
        assert_success
    fi

    run aws_cmd bedrock get-model-invocation-job --job-identifier "$JOB_ARN"
    assert_success

    status=$(json_get "$output" '.status')
    end_time=$(json_get "$output" '.endTime')

    [[ "$status" == "Stopping" || "$status" == "Stopped" || "$status" == "Failed" ]]
    [ -n "$end_time" ]
}

@test "Bedrock: batch inference execution with S3 and manifest" {
    INPUT_KEY="prompts.jsonl"
    OUTPUT_PREFIX="bats-batch-out-$(date +%s)-$$"
    INPUT_URI="s3://$BUCKET/$INPUT_KEY"
    OUTPUT_URI="s3://$BUCKET/$OUTPUT_PREFIX"

    JSONL_CONTENT='{"recordId":"rec-1","modelInput":{"inputText":"Hello Bedrock"}}
{"recordId":"rec-2","modelInput":{"inputText":"How are you?"}}'

    echo "$JSONL_CONTENT" | aws_cmd s3 cp - "$INPUT_URI" >/dev/null

    JOB_NAME="bats-s3-exec-$(date +%s)-$$"
    run aws_cmd bedrock create-model-invocation-job \
        --job-name "$JOB_NAME" \
        --model-id "amazon.titan-text-express-v1" \
        --role-arn "arn:aws:iam::000000000000:role/BedrockBatchRole" \
        --input-data-config "{\"s3InputDataConfig\":{\"s3Uri\":\"$INPUT_URI\"}}" \
        --output-data-config "{\"s3OutputDataConfig\":{\"s3Uri\":\"$OUTPUT_URI\"}}"
    assert_success

    JOB_ARN=$(json_get "$output" '.jobArn')
    JOB_ID="${JOB_ARN##*/}"

    # Poll until job reaches a terminal status (max 30s)
    TERMINAL_STATUSES="Completed Failed Stopped Expired PartiallyCompleted"
    DEADLINE=$(($(date +%s) + 30))
    status=""
    while [ "$(date +%s)" -lt "$DEADLINE" ]; do
        run aws_cmd bedrock get-model-invocation-job --job-identifier "$JOB_ARN"
        assert_success
        status=$(json_get "$output" '.status')
        for ts in $TERMINAL_STATUSES; do
            if [ "$status" = "$ts" ]; then break 2; fi
        done
        sleep 0.5
    done

    [ "$status" = "Completed" ]

    # Verify output JSONL file in S3
    OUT_KEY="$OUTPUT_PREFIX/$JOB_ID/$INPUT_KEY.out"
    run aws_cmd s3 cp "s3://$BUCKET/$OUT_KEY" -
    assert_success

    line_count=$(echo "$output" | wc -l)
    [ "$line_count" -eq 2 ]

    rec1_id=$(echo "$output" | head -n 1 | jq -r '.recordId')
    [ "$rec1_id" = "rec-1" ]

    # Verify manifest file in S3
    MANIFEST_KEY="$OUTPUT_PREFIX/$JOB_ID/manifest.json.out"
    run aws_cmd s3 cp "s3://$BUCKET/$MANIFEST_KEY" -
    assert_success

    total=$(json_get "$output" '.totalRecordCount')
    processed=$(json_get "$output" '.processedRecordCount')
    success=$(json_get "$output" '.successRecordCount')
    errors=$(json_get "$output" '.errorRecordCount')

    [ "$total" -eq 2 ]
    [ "$processed" -eq 2 ]
    [ "$success" -eq 2 ]
    [ "$errors" -eq 0 ]
}
