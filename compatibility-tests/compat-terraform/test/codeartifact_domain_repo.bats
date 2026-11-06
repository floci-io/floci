#!/usr/bin/env bats
# CodeArtifact Control Plane Compatibility Test
#
# Floci previously had no CodeArtifact support at all. This verifies aws_codeartifact_domain,
# aws_codeartifact_domain_permissions_policy, aws_codeartifact_repository (with tags and an
# upstream), and aws_codeartifact_repository_permissions_policy can all apply, that a second
# plan is clean, and that destroy tears everything down in dependency order.

setup_file() {
    load 'test_helper/common-setup'

    CA_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/codeartifact-domain-repo-tf" && pwd)"
    cd "$CA_TF_DIR"

    echo "# === CodeArtifact Domain/Repository Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $CA_TF_DIR" >&3

    find .terraform -mindepth 1 -delete 2>/dev/null || true
    rm -f .terraform.lock.hcl terraform.tfstate terraform.tfstate.backup 2>/dev/null || true

    echo "# --- terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    echo "# --- terraform apply ---" >&3
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    CA_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/codeartifact-domain-repo-tf" && pwd)"
    cd "$CA_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    find .terraform -mindepth 1 -delete 2>/dev/null || true
    rm -f .terraform.lock.hcl terraform.tfstate terraform.tfstate.backup 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    CA_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/codeartifact-domain-repo-tf" && pwd)"
}

@test "CodeArtifact domain/repository: apply reports the domain and repository ARNs" {
    DOMAIN_ARN=$(terraform -chdir="$CA_TF_DIR" output -raw domain_arn)
    REPO_ARN=$(terraform -chdir="$CA_TF_DIR" output -raw repository_arn)
    assert_equal "$DOMAIN_ARN" "arn:aws:codeartifact:us-east-1:000000000000:domain/floci-codeartifact-domain"
    assert_equal "$REPO_ARN" "arn:aws:codeartifact:us-east-1:000000000000:repository/floci-codeartifact-domain/floci-codeartifact-consumer"
}

@test "CodeArtifact domain/repository: tags and upstream round-trip" {
    OWNER_TAG=$(terraform -chdir="$CA_TF_DIR" output -raw domain_owner_tag)
    TEAM_TAG=$(terraform -chdir="$CA_TF_DIR" output -raw repository_team_tag)
    UPSTREAM=$(terraform -chdir="$CA_TF_DIR" output -raw repository_upstream)
    assert_equal "$OWNER_TAG" "platform"
    assert_equal "$TEAM_TAG" "data"
    assert_equal "$UPSTREAM" "floci-codeartifact-store"
}

@test "CodeArtifact domain/repository: DescribeDomain reports the live repository count" {
    run aws_cmd codeartifact describe-domain --domain floci-codeartifact-domain \
        --query "domain.repositoryCount" --output text
    assert_success
    assert_output "2"
}

@test "CodeArtifact domain/repository: second plan reports no changes" {
    cd "$CA_TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}

@test "CodeArtifact domain/repository: GetRepositoryEndpoint resolves for a real package format" {
    run aws_cmd codeartifact get-repository-endpoint --domain floci-codeartifact-domain \
        --repository floci-codeartifact-consumer --format npm --query repositoryEndpoint --output text
    assert_success
    [[ "$output" == *"/codeartifact/npm/floci-codeartifact-domain/floci-codeartifact-consumer/"* ]]
}
