#!/usr/bin/env bats
# Lambda MicroVMs integration tests
#
# Two AWS service models sit behind these commands: `lambda-microvms` (images
# and MicroVMs) and `lambda-core` (network connectors). Both sign as lambda.

setup() {
    load 'test_helper/common-setup'
    IMAGE_NAME=""
    MICROVM_ID=""
    CONNECTOR_ID=""
    BASE_IMAGE_ARN="arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1"
    BUILD_ROLE_ARN="arn:aws:iam::000000000000:role/microvm-build"
}

teardown() {
    if [ -n "$MICROVM_ID" ]; then
        aws_cmd lambda-microvms terminate-microvm --microvm-identifier "$MICROVM_ID" >/dev/null 2>&1 || true
    fi
    if [ -n "$IMAGE_NAME" ]; then
        aws_cmd lambda-microvms delete-microvm-image --image-identifier "$IMAGE_NAME" >/dev/null 2>&1 || true
    fi
    if [ -n "$CONNECTOR_ID" ]; then
        aws_cmd lambda-core delete-network-connector --id "$CONNECTOR_ID" >/dev/null 2>&1 || true
    fi
}

create_image() {
    local name="$1"
    aws_cmd lambda-microvms create-microvm-image \
        --name "$name" \
        --base-image-arn "$BASE_IMAGE_ARN" \
        --build-role-arn "$BUILD_ROLE_ARN" \
        --code-artifact "uri=s3://bucket/code.zip"
}

# ============================================
# Image lifecycle
# ============================================

@test "MicroVMs: create image" {
    IMAGE_NAME=$(unique_name "cli-img")
    run create_image "$IMAGE_NAME"
    assert_success
    name=$(json_get "$output" '.name')
    state=$(json_get "$output" '.state')
    version=$(json_get "$output" '.imageVersion')
    arn=$(json_get "$output" '.imageArn')
    [ "$name" = "$IMAGE_NAME" ]
    [ "$state" = "CREATING" ]
    [ "$version" = "1.0" ]
    [[ "$arn" =~ ^arn:aws:lambda: ]]
}

@test "MicroVMs: image settles to CREATED with an active version" {
    IMAGE_NAME=$(unique_name "cli-img")
    create_image "$IMAGE_NAME" >/dev/null

    run aws_cmd lambda-microvms get-microvm-image --image-identifier "$IMAGE_NAME"
    assert_success
    state=$(json_get "$output" '.state')
    latest=$(json_get "$output" '.latestActiveImageVersion')
    [ "$state" = "CREATED" ]
    [ "$latest" = "1.0" ]
}

@test "MicroVMs: list images includes the created image" {
    IMAGE_NAME=$(unique_name "cli-img")
    create_image "$IMAGE_NAME" >/dev/null

    run aws_cmd lambda-microvms list-microvm-images
    assert_success
    assert_output --partial "$IMAGE_NAME"
}

@test "MicroVMs: version and build converge to SUCCESSFUL" {
    IMAGE_NAME=$(unique_name "cli-img")
    create_image "$IMAGE_NAME" >/dev/null

    run aws_cmd lambda-microvms list-microvm-image-builds \
        --image-identifier "$IMAGE_NAME" --image-version 1.0
    assert_success
    build_id=$(json_get "$output" '.items[0].buildId')
    build_state=$(json_get "$output" '.items[0].buildState')
    [ "$build_state" = "SUCCESSFUL" ]
    [ -n "$build_id" ]

    run aws_cmd lambda-microvms get-microvm-image-build \
        --image-identifier "$IMAGE_NAME" --image-version 1.0 --build-id "$build_id"
    assert_success
    arch=$(json_get "$output" '.architecture')
    [ "$arch" = "ARM_64" ]

    run aws_cmd lambda-microvms get-microvm-image-version \
        --image-identifier "$IMAGE_NAME" --image-version 1.0
    assert_success
    status=$(json_get "$output" '.status')
    [ "$status" = "ACTIVE" ]
}

@test "MicroVMs: invalid image name is rejected" {
    run create_image "bad name!"
    assert_failure
    assert_output --partial "ValidationException"
}

@test "MicroVMs: get a nonexistent image returns not found" {
    run aws_cmd lambda-microvms get-microvm-image --image-identifier "cli-no-such-image"
    assert_failure
    assert_output --partial "ResourceNotFound"
}

@test "MicroVMs: managed catalog lists a base image" {
    run aws_cmd lambda-microvms list-managed-microvm-images
    assert_success
    arn=$(json_get "$output" '.items[0].imageArn')
    [ -n "$arn" ]
}

# ============================================
# MicroVM lifecycle
# ============================================

@test "MicroVMs: run a MicroVM and read it back running" {
    IMAGE_NAME=$(unique_name "cli-img")
    create_image "$IMAGE_NAME" >/dev/null

    run aws_cmd lambda-microvms run-microvm --image-identifier "$IMAGE_NAME"
    assert_success
    MICROVM_ID=$(json_get "$output" '.microvmId')
    state=$(json_get "$output" '.state')
    duration=$(json_get "$output" '.maximumDurationInSeconds')
    [[ "$MICROVM_ID" =~ ^microvm- ]]
    [ "$state" = "PENDING" ]
    [ "$duration" = "28800" ]

    run aws_cmd lambda-microvms get-microvm --microvm-identifier "$MICROVM_ID"
    assert_success
    state=$(json_get "$output" '.state')
    [ "$state" = "RUNNING" ]
}

@test "MicroVMs: run against a missing image returns not found" {
    run aws_cmd lambda-microvms run-microvm --image-identifier "cli-no-such-image"
    assert_failure
    assert_output --partial "ResourceNotFound"
}

@test "MicroVMs: an image with a running MicroVM refuses delete" {
    IMAGE_NAME=$(unique_name "cli-img")
    create_image "$IMAGE_NAME" >/dev/null
    out=$(aws_cmd lambda-microvms run-microvm --image-identifier "$IMAGE_NAME")
    MICROVM_ID=$(json_get "$out" '.microvmId')

    run aws_cmd lambda-microvms delete-microvm-image --image-identifier "$IMAGE_NAME"
    assert_failure
    assert_output --partial "running microvms"
}

@test "MicroVMs: terminate is idempotent only once" {
    IMAGE_NAME=$(unique_name "cli-img")
    create_image "$IMAGE_NAME" >/dev/null
    out=$(aws_cmd lambda-microvms run-microvm --image-identifier "$IMAGE_NAME")
    microvm_id=$(json_get "$out" '.microvmId')

    run aws_cmd lambda-microvms terminate-microvm --microvm-identifier "$microvm_id"
    assert_success

    run aws_cmd lambda-microvms get-microvm --microvm-identifier "$microvm_id"
    assert_success
    state=$(json_get "$output" '.state')
    [ "$state" = "TERMINATED" ]

    # Terminal-state mutations are a ValidationException, not a second success.
    run aws_cmd lambda-microvms terminate-microvm --microvm-identifier "$microvm_id"
    assert_failure
    assert_output --partial "state cannot be changed"
}

# ============================================
# Tagging
# ============================================

@test "MicroVMs: tag, read and untag an image ARN" {
    IMAGE_NAME=$(unique_name "cli-img")
    out=$(create_image "$IMAGE_NAME")
    image_arn=$(json_get "$out" '.imageArn')

    run aws_cmd lambda-microvms tag-resource --resource "$image_arn" --tags team=conformance
    assert_success

    run aws_cmd lambda-microvms list-tags --resource "$image_arn"
    assert_success
    team=$(json_get "$output" '.Tags.team')
    [ "$team" = "conformance" ]

    run aws_cmd lambda-microvms untag-resource --resource "$image_arn" --tag-keys team
    assert_success

    run aws_cmd lambda-microvms list-tags --resource "$image_arn"
    assert_success
    team=$(json_get "$output" '.Tags.team')
    [ "$team" = "null" ] || [ -z "$team" ]
}

# ============================================
# Network connectors (lambda-core)
# ============================================

@test "MicroVMs: network connector lifecycle" {
    name=$(unique_name "cli-conn")
    run aws_cmd lambda-core create-network-connector \
        --name "$name" \
        --operator-role "arn:aws:iam::000000000000:role/microvm-connector-operator" \
        --configuration "VpcEgressConfiguration={AssociatedComputeResourceTypes=MicroVm,NetworkProtocol=IPv4,SubnetIds=subnet-0000000000000cli1,SecurityGroupIds=sg-0000000000000cli1}"
    assert_success
    CONNECTOR_ID=$(json_get "$output" '.Id')
    state=$(json_get "$output" '.State')
    [ -n "$CONNECTOR_ID" ]
    [ "$state" = "PENDING" ]

    run aws_cmd lambda-core get-network-connector --id "$CONNECTOR_ID"
    assert_success
    state=$(json_get "$output" '.State')
    got_name=$(json_get "$output" '.Name')
    [ "$state" = "ACTIVE" ]
    [ "$got_name" = "$name" ]

    run aws_cmd lambda-core list-network-connectors
    assert_success
    assert_output --partial "$CONNECTOR_ID"

    run aws_cmd lambda-core delete-network-connector --id "$CONNECTOR_ID"
    assert_success
    CONNECTOR_ID=""
}

@test "MicroVMs: get a nonexistent connector returns not found" {
    run aws_cmd lambda-core get-network-connector --id "nc-00000000000000cli"
    assert_failure
    assert_output --partial "ResourceNotFound"
}

# ============================================
# Region isolation
# ============================================

@test "MicroVMs: an image created in one region is not visible in another" {
    IMAGE_NAME=$(unique_name "cli-img")
    create_image "$IMAGE_NAME" >/dev/null

    run aws --endpoint-url "$FLOCI_ENDPOINT" --region eu-west-1 --output json \
        lambda-microvms get-microvm-image --image-identifier "$IMAGE_NAME" 2>&1
    assert_failure
    assert_output --partial "ResourceNotFound"
}
