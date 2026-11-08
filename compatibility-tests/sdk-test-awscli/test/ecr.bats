#!/usr/bin/env bats
# ECR control-plane integration tests.
#
# Test-first: this file is committed before the server-side ECR implementation
# lands. With ECR unimplemented, every test below should fail.

setup() {
    load 'test_helper/common-setup'
    REPO_NAME="floci-it/app-cli-$(unique_name)"
}

teardown() {
    aws_cmd ecr delete-repository --repository-name "$REPO_NAME" --force >/dev/null 2>&1 || true
}

@test "ECR: create-repository returns loopback URI" {
    run aws_cmd ecr create-repository --repository-name "$REPO_NAME"
    assert_success
    arn=$(json_get "$output" '.repository.repositoryArn')
    uri=$(json_get "$output" '.repository.repositoryUri')
    name=$(json_get "$output" '.repository.repositoryName')
    [ "$name" = "$REPO_NAME" ]
    [[ "$arn" =~ ^arn:aws:ecr: ]]
    [[ "$arn" == *":repository/$REPO_NAME" ]]
    [[ "$uri" == *"localhost:"* ]]
    [[ "$uri" == *"/$REPO_NAME" ]]
}

@test "ECR: create-repository duplicate fails with RepositoryAlreadyExistsException" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr create-repository --repository-name "$REPO_NAME"
    assert_failure
    [[ "$output" == *"RepositoryAlreadyExistsException"* ]]
}

@test "ECR: describe-repositories returns the created repo" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr describe-repositories --repository-names "$REPO_NAME"
    assert_success
    name=$(json_get "$output" '.repositories[0].repositoryName')
    [ "$name" = "$REPO_NAME" ]
}

@test "ECR: get-authorization-token returns AWS:<password>" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr get-authorization-token
    assert_success
    token=$(json_get "$output" '.authorizationData[0].authorizationToken')
    proxy=$(json_get "$output" '.authorizationData[0].proxyEndpoint')
    [ -n "$token" ]
    [[ "$proxy" =~ ^https?:// ]]
    decoded=$(echo "$token" | base64 -d)
    [[ "$decoded" == AWS:* ]]
}

@test "ECR: list-images on empty repo returns []" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr list-images --repository-name "$REPO_NAME"
    assert_success
    count=$(echo "$output" | jq '.imageIds | length')
    [ "$count" = "0" ]
}

@test "ECR: put-image-tag-mutability round-trips IMMUTABLE" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr put-image-tag-mutability --repository-name "$REPO_NAME" --image-tag-mutability IMMUTABLE
    assert_success
    mut=$(json_get "$output" '.imageTagMutability')
    [ "$mut" = "IMMUTABLE" ]
}

@test "ECR: put-lifecycle-policy round-trips" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    policy='{"rules":[{"rulePriority":1,"selection":{"tagStatus":"untagged","countType":"imageCountMoreThan","countNumber":5},"action":{"type":"expire"}}]}'
    aws_cmd ecr put-lifecycle-policy --repository-name "$REPO_NAME" --lifecycle-policy-text "$policy" >/dev/null
    run aws_cmd ecr get-lifecycle-policy --repository-name "$REPO_NAME"
    assert_success
    got=$(json_get "$output" '.lifecyclePolicyText')
    [ "$got" = "$policy" ]
}

@test "ECR: set-repository-policy round-trips" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    policy='{"Version":"2012-10-17","Statement":[{"Sid":"AllowAll","Effect":"Allow","Principal":"*","Action":"ecr:*"}]}'
    aws_cmd ecr set-repository-policy --repository-name "$REPO_NAME" --policy-text "$policy" >/dev/null
    run aws_cmd ecr get-repository-policy --repository-name "$REPO_NAME"
    assert_success
    got=$(json_get "$output" '.policyText')
    [ "$got" = "$policy" ]
}

@test "ECR: delete-repository force=true removes the repo" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr delete-repository --repository-name "$REPO_NAME" --force
    assert_success
    run aws_cmd ecr describe-repositories --repository-names "$REPO_NAME"
    assert_failure
    [[ "$output" == *"RepositoryNotFoundException"* ]]
}

@test "ECR: describe-repositories on missing name fails" {
    run aws_cmd ecr describe-repositories --repository-names "does-not-exist-cli"
    assert_failure
    [[ "$output" == *"RepositoryNotFoundException"* ]]
}

@test "ECR: an image pushed with the layer API retags with batch-get-image and put-image" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    printf '{"architecture":"amd64","os":"linux"}' > "$BATS_TEST_TMPDIR/config"
    printf 'floci layer bytes' > "$BATS_TEST_TMPDIR/layer"

    for blob in config layer; do
        file="$BATS_TEST_TMPDIR/$blob"
        size=$(wc -c < "$file" | tr -d ' ')
        digest="sha256:$(sha256sum "$file" | cut -d' ' -f1)"
        upload=$(aws_cmd ecr initiate-layer-upload --repository-name "$REPO_NAME" | jq -r '.uploadId')
        run aws_cmd ecr upload-layer-part --repository-name "$REPO_NAME" --upload-id "$upload" \
            --part-first-byte 0 --part-last-byte $((size - 1)) --layer-part-blob "fileb://$file"
        assert_success
        run aws_cmd ecr complete-layer-upload --repository-name "$REPO_NAME" --upload-id "$upload" \
            --layer-digests "$digest"
        assert_success
        [ "$(json_get "$output" '.layerDigest')" = "$digest" ]
        eval "${blob}_digest=$digest ${blob}_size=$size"
    done

    manifest=$(printf '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","size":%s,"digest":"%s"},"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar","size":%s,"digest":"%s"}]}' \
        "$config_size" "$config_digest" "$layer_size" "$layer_digest")
    run aws_cmd ecr put-image --repository-name "$REPO_NAME" --image-tag v1 --image-manifest "$manifest"
    assert_success
    digest=$(json_get "$output" '.image.imageId.imageDigest')

    read_back=$(aws_cmd ecr batch-get-image --repository-name "$REPO_NAME" --image-ids imageTag=v1 \
        --query 'images[0].imageManifest' --output text)
    run aws_cmd ecr put-image --repository-name "$REPO_NAME" --image-tag release --image-manifest "$read_back"
    assert_success
    [ "$(json_get "$output" '.image.imageId.imageDigest')" = "$digest" ]

    run aws_cmd ecr describe-images --repository-name "$REPO_NAME" --image-ids imageTag=release
    assert_success
    [ "$(json_get "$output" '.imageDetails[0].imageDigest')" = "$digest" ]
}

@test "ECR: registry policy, replication and scanning configuration round-trip" {
    policy='{"Version":"2012-10-17","Statement":[{"Sid":"Replicate","Effect":"Allow","Principal":{"AWS":"arn:aws:iam::111111111111:root"},"Action":["ecr:CreateRepository","ecr:ReplicateImage"],"Resource":"*"}]}'
    run aws_cmd ecr put-registry-policy --policy-text "$policy"
    assert_success
    run aws_cmd ecr get-registry-policy
    assert_success
    [ "$(json_get "$output" '.policyText')" = "$policy" ]

    run aws_cmd ecr put-replication-configuration --replication-configuration \
        'rules=[{destinations=[{region=us-west-2,registryId=111111111111}],repositoryFilters=[{filter=prod,filterType=PREFIX_MATCH}]}]'
    assert_success
    run aws_cmd ecr describe-registry
    assert_success
    [ "$(json_get "$output" '.replicationConfiguration.rules[0].destinations[0].region')" = "us-west-2" ]

    run aws_cmd ecr put-registry-scanning-configuration --scan-type ENHANCED \
        --rules '[{"scanFrequency":"SCAN_ON_PUSH","repositoryFilters":[{"filter":"*","filterType":"WILDCARD"}]}]'
    assert_success
    run aws_cmd ecr get-registry-scanning-configuration
    assert_success
    [ "$(json_get "$output" '.scanningConfiguration.scanType')" = "ENHANCED" ]

    aws_cmd ecr delete-registry-policy >/dev/null
    aws_cmd ecr put-replication-configuration --replication-configuration 'rules=[]' >/dev/null
    aws_cmd ecr put-registry-scanning-configuration --scan-type BASIC --rules '[]' >/dev/null
    run aws_cmd ecr get-registry-policy
    assert_failure
    [[ "$output" == *"RegistryPolicyNotFoundException"* ]]
}

@test "ECR: repository creation templates round-trip" {
    prefix="floci-cli-$(unique_name)"
    run aws_cmd ecr create-repository-creation-template --prefix "$prefix" \
        --applied-for PULL_THROUGH_CACHE --description "cli template"
    assert_success
    [ "$(json_get "$output" '.repositoryCreationTemplate.encryptionConfiguration.encryptionType')" = "AES256" ]

    run aws_cmd ecr update-repository-creation-template --prefix "$prefix" --image-tag-mutability IMMUTABLE
    assert_success
    [ "$(json_get "$output" '.repositoryCreationTemplate.imageTagMutability')" = "IMMUTABLE" ]

    run aws_cmd ecr describe-repository-creation-templates --prefixes "$prefix"
    assert_success
    [ "$(json_get "$output" '.repositoryCreationTemplates[0].description')" = "cli template" ]

    run aws_cmd ecr delete-repository-creation-template --prefix "$prefix"
    assert_success
    run aws_cmd ecr delete-repository-creation-template --prefix "$prefix"
    assert_failure
    [[ "$output" == *"TemplateNotFoundException"* ]]
}
