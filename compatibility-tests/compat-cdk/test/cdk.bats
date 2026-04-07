#!/usr/bin/env bats
# CDK Compatibility Tests for floci

setup_file() {
    load 'test_helper/common-setup'

    cd "$CDK_DIR"

    echo "# === CDK Compatibility Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3

    echo "# --- Bootstrap ---" >&3
    run cdklocal bootstrap --force
    if [ "$status" -ne 0 ]; then
        echo "# Bootstrap failed: $output" >&3
        return 1
    fi

    echo "# --- Deploy ---" >&3
    run cdklocal deploy --require-approval never
    if [ "$status" -ne 0 ]; then
        echo "# Deploy failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    cd "$CDK_DIR"

    echo "# --- Destroy ---" >&3
    cdklocal destroy --force || true
}

setup() {
    load 'test_helper/common-setup'
}

# --- Spot Checks ---

@test "CDK: S3 bucket exists" {
    run aws_cmd s3 ls
    assert_success
    bucket_count=$(echo "$output" | wc -l)
    [ "$bucket_count" -gt 0 ]
}

@test "CDK: SQS queue exists" {
    run aws_cmd sqs list-queues
    assert_success
    queue_count=$(json_get "$output" '.QueueUrls | length')
    [ "$queue_count" -gt 0 ]
}

@test "CDK: DynamoDB table exists" {
    run aws_cmd dynamodb list-tables
    assert_success
    table_count=$(json_get "$output" '.TableNames | length')
    [ "$table_count" -gt 0 ]
}

@test "CDK: DynamoDB GSI exists on index table" {
    run aws_cmd dynamodb describe-table --table-name floci-cdk-index-table
    assert_success
    gsi_count=$(json_get "$output" '.Table.GlobalSecondaryIndexes | length')
    [ "$gsi_count" -eq 1 ]
}

@test "CDK: DynamoDB GSI name is gsi-1" {
    run aws_cmd dynamodb describe-table --table-name floci-cdk-index-table
    assert_success
    gsi_name=$(json_get "$output" '.Table.GlobalSecondaryIndexes[0].IndexName')
    [ "$gsi_name" = "gsi-1" ]
}

@test "CDK: DynamoDB GSI projection ALL" {
    run aws_cmd dynamodb describe-table --table-name floci-cdk-index-table
    assert_success
    gsi_proj=$(json_get "$output" '.Table.GlobalSecondaryIndexes[0].Projection.ProjectionType')
    [ "$gsi_proj" = "ALL" ]
}

@test "CDK: DynamoDB LSI exists on index table" {
    run aws_cmd dynamodb describe-table --table-name floci-cdk-index-table
    assert_success
    lsi_count=$(json_get "$output" '.Table.LocalSecondaryIndexes | length')
    [ "$lsi_count" -eq 1 ]
}

@test "CDK: DynamoDB LSI name is lsi-1" {
    run aws_cmd dynamodb describe-table --table-name floci-cdk-index-table
    assert_success
    lsi_name=$(json_get "$output" '.Table.LocalSecondaryIndexes[0].IndexName')
    [ "$lsi_name" = "lsi-1" ]
}

@test "CDK: DynamoDB LSI projection KEYS_ONLY" {
    run aws_cmd dynamodb describe-table --table-name floci-cdk-index-table
    assert_success
    lsi_proj=$(json_get "$output" '.Table.LocalSecondaryIndexes[0].Projection.ProjectionType')
    [ "$lsi_proj" = "KEYS_ONLY" ]
}

@test "CDK: CloudFormation stack CREATE_COMPLETE" {
    run aws_cmd cloudformation describe-stacks --stack-name FlociTestStack
    assert_success
    stack_status=$(json_get "$output" '.Stacks[0].StackStatus')
    [ "$stack_status" = "CREATE_COMPLETE" ]
}

@test "CDK: Secrets Manager generated secret exists" {
    run aws_cmd secretsmanager describe-secret --secret-id floci-cdk-generated-secret
    assert_success
    secret_name=$(json_get "$output" '.Name')
    [ "$secret_name" = "floci-cdk-generated-secret" ]
}

@test "CDK: GeneratedSecret username is admin" {
    run aws_cmd secretsmanager get-secret-value --secret-id floci-cdk-generated-secret
    assert_success
    username=$(json_get "$output" '.SecretString' | jq -r '.username')
    [ "$username" = "admin" ]
}

@test "CDK: GeneratedSecret password length is 24" {
    run aws_cmd secretsmanager get-secret-value --secret-id floci-cdk-generated-secret
    assert_success
    password=$(json_get "$output" '.SecretString' | jq -r '.password')
    password_len=${#password}
    [ "$password_len" -eq 24 ]
}

@test "CDK: GeneratedSecret password excludes abc" {
    run aws_cmd secretsmanager get-secret-value --secret-id floci-cdk-generated-secret
    assert_success
    password=$(json_get "$output" '.SecretString' | jq -r '.password')
    # Check that password does not contain a, b, or c
    [[ ! "$password" =~ [abc] ]]
}

# --- DockerImageFunction (ECR + Lambda end-to-end) ---

@test "CDK: DockerImageFunction was created" {
    run aws_cmd lambda get-function --function-name floci-cdk-docker-hello
    assert_success
    package_type=$(json_get "$output" '.Configuration.PackageType')
    [ "$package_type" = "Image" ]
    image_uri=$(json_get "$output" '.Code.ImageUri')
    [ -n "$image_uri" ]
    # The stored ImageUri preserves the AWS-shaped form (CDK assets use this);
    # Floci's Lambda launcher rewrites it to the loopback registry at pull time.
    [[ "$image_uri" == *"dkr.ecr."*"amazonaws.com"* ]]
    [[ "$image_uri" == *"cdk-hnb659fds-container-assets"* ]]
}

@test "CDK: DockerImageFunction invokes successfully" {
    # End-to-end invocation requires the Lambda runtime API server (host port range
    # floci.services.lambda.runtime-api-base-port) to be reachable from inside the
    # Lambda container. On native Linux Docker without Docker Desktop, this depends
    # on host firewall rules permitting traffic from the docker bridge to the host.
    # If your host blocks this, the test fails with Function.TimedOut — this is a
    # pre-existing Lambda networking constraint, not an ECR issue (it affects
    # Zip-packaged Lambdas too).
    payload_b64=$(printf '{"name":"world"}' | base64 -w 0 2>/dev/null || printf '{"name":"world"}' | base64)
    run aws_cmd lambda invoke \
        --function-name floci-cdk-docker-hello \
        --payload "$payload_b64" \
        /tmp/floci-cdk-docker-hello.out
    assert_success
    status_code=$(json_get "$output" '.StatusCode')
    [ "$status_code" = "200" ]

    body=$(cat /tmp/floci-cdk-docker-hello.out)
    [[ "$body" == *"Hello, world!"* ]]
    [[ "$body" == *"emulated ECR"* ]]
}

@test "CDK: DockerImageFunction asset image is in emulated ECR" {
    # CDK pushes to a bootstrap repo named cdk-hnb659fds-container-assets-<account>-<region>
    run aws_cmd ecr describe-repositories
    assert_success
    repos=$(echo "$output" | jq -r '.repositories[].repositoryName')
    [[ "$repos" == *"cdk-hnb659fds-container-assets"* ]]
}
