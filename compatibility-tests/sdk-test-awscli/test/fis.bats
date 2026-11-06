#!/usr/bin/env bats
# AWS Fault Injection Service compatibility tests

setup() {
    load 'test_helper/common-setup'
    TEMPLATE_ID=""
    TEMPLATE_ARN=""
    EXPERIMENT_ID=""
    TARGET_ACCOUNT_CONFIGURATION_EXISTS="false"
}

teardown() {
    # The safety lever is account-global, so always restore its safe default even
    # when an assertion aborts the lifecycle halfway through.
    aws_cmd fis update-safety-lever-state \
        --id default \
        --state '{"status":"disengaged","reason":"AWS CLI compatibility cleanup"}' \
        >/dev/null 2>&1 || true

    if [ -n "$EXPERIMENT_ID" ]; then
        aws_cmd fis stop-experiment --id "$EXPERIMENT_ID" >/dev/null 2>&1 || true
    fi
    if [ -n "$TEMPLATE_ID" ] && [ "$TARGET_ACCOUNT_CONFIGURATION_EXISTS" = "true" ]; then
        aws_cmd fis delete-target-account-configuration \
            --experiment-template-id "$TEMPLATE_ID" \
            --account-id 111122223333 >/dev/null 2>&1 || true
    fi
    if [ -n "$TEMPLATE_ID" ]; then
        aws_cmd fis delete-experiment-template --id "$TEMPLATE_ID" >/dev/null 2>&1 || true
    fi
}

# This safe lifecycle invokes every AWS FIS management operation. It uses only
# the built-in wait action and starts the experiment with actionsMode=skip-all.
@test "FIS: all management operations use AWS CLI v2 wire shapes" {
    local action_id target_resource_type client_token target_account_token
    local next_token state template_listed account_listed owner experiment_status
    local experiment_listed account_snapshot_listed

    action_id="aws:fis:wait"
    target_resource_type="aws:ec2:instance"

    run aws_cmd fis get-action --id "$action_id"
    assert_success
    [ "$(json_get "$output" '.action.id')" = "$action_id" ]
    [[ "$(json_get "$output" '.action.arn')" =~ :action/aws:fis:wait$ ]]

    run aws_cmd fis list-actions --max-results 1
    assert_success
    [ "$(json_get "$output" '.actions | length')" = "1" ]
    next_token=$(json_get "$output" '.nextToken')
    [ -n "$next_token" ]
    [ "$next_token" != "null" ]

    run aws_cmd fis list-actions --max-results 1 --next-token "$next_token"
    assert_success
    [ "$(json_get "$output" '.actions | length')" = "1" ]

    run aws_cmd fis get-target-resource-type --resource-type "$target_resource_type"
    assert_success
    [ "$(json_get "$output" '.targetResourceType.resourceType')" = "$target_resource_type" ]

    run aws_cmd fis list-target-resource-types --max-results 1
    assert_success
    [ "$(json_get "$output" '.targetResourceTypes | length')" = "1" ]
    next_token=$(json_get "$output" '.nextToken')
    [ -n "$next_token" ]
    [ "$next_token" != "null" ]

    run aws_cmd fis list-target-resource-types --max-results 1 --next-token "$next_token"
    assert_success
    [ "$(json_get "$output" '.targetResourceTypes | length')" = "1" ]

    run aws_cmd fis get-safety-lever --id default
    assert_success
    [ "$(json_get "$output" '.safetyLever.id')" = "default" ]

    run aws_cmd fis update-safety-lever-state \
        --id default \
        --state '{"status":"engaged","reason":"AWS CLI compatibility test"}'
    assert_success
    [ "$(json_get "$output" '.safetyLever.state.status')" = "engaged" ]

    run aws_cmd fis update-safety-lever-state \
        --id default \
        --state '{"status":"disengaged","reason":"AWS CLI compatibility test ready"}'
    assert_success
    [ "$(json_get "$output" '.safetyLever.state.status')" = "disengaged" ]

    client_token=$(unique_name fis-cli-template)
    run aws_cmd fis create-experiment-template \
        --client-token "$client_token" \
        --description "AWS CLI compatibility template" \
        --stop-conditions '[{"source":"none"}]' \
        --actions '{"wait":{"actionId":"aws:fis:wait","parameters":{"duration":"PT1M"}}}' \
        --role-arn "arn:aws:iam::000000000000:role/fis-cli-role" \
        --tags '{"suite":"awscli"}' \
        --experiment-options '{"accountTargeting":"multi-account","emptyTargetResolutionMode":"skip"}'
    assert_success
    TEMPLATE_ID=$(json_get "$output" '.experimentTemplate.id')
    TEMPLATE_ARN=$(json_get "$output" '.experimentTemplate.arn')
    [ -n "$TEMPLATE_ID" ]
    [[ "$TEMPLATE_ARN" =~ :experiment-template/${TEMPLATE_ID}$ ]]
    [ "$(json_get "$output" '.experimentTemplate.tags.suite')" = "awscli" ]

    run aws_cmd fis get-experiment-template --id "$TEMPLATE_ID"
    assert_success
    [ "$(json_get "$output" '.experimentTemplate.id')" = "$TEMPLATE_ID" ]

    run aws_cmd fis update-experiment-template \
        --id "$TEMPLATE_ID" \
        --description "Updated through AWS CLI v2"
    assert_success
    [ "$(json_get "$output" '.experimentTemplate.description')" = "Updated through AWS CLI v2" ]

    run aws_cmd fis list-experiment-templates --max-results 100
    assert_success
    template_listed=$(echo "$output" | jq --arg id "$TEMPLATE_ID" \
        '.experimentTemplates | any(.id == $id)')
    [ "$template_listed" = "true" ]

    target_account_token=$(unique_name fis-cli-account)
    run aws_cmd fis create-target-account-configuration \
        --client-token "$target_account_token" \
        --experiment-template-id "$TEMPLATE_ID" \
        --account-id 111122223333 \
        --role-arn "arn:aws:iam::111122223333:role/fis-cli-target-role" \
        --description "AWS CLI target account"
    assert_success
    TARGET_ACCOUNT_CONFIGURATION_EXISTS="true"
    [ "$(json_get "$output" '.targetAccountConfiguration.accountId')" = "111122223333" ]

    run aws_cmd fis get-target-account-configuration \
        --experiment-template-id "$TEMPLATE_ID" \
        --account-id 111122223333
    assert_success
    [ "$(json_get "$output" '.targetAccountConfiguration.description')" = "AWS CLI target account" ]

    run aws_cmd fis update-target-account-configuration \
        --experiment-template-id "$TEMPLATE_ID" \
        --account-id 111122223333 \
        --role-arn "arn:aws:iam::111122223333:role/fis-cli-target-role" \
        --description "Updated AWS CLI target account"
    assert_success
    [ "$(json_get "$output" '.targetAccountConfiguration.description')" = \
        "Updated AWS CLI target account" ]

    run aws_cmd fis list-target-account-configurations \
        --experiment-template-id "$TEMPLATE_ID" \
        --max-results 100
    assert_success
    account_listed=$(echo "$output" | jq \
        '.targetAccountConfigurations | any(.accountId == "111122223333")')
    [ "$account_listed" = "true" ]

    run aws_cmd fis tag-resource \
        --resource-arn "$TEMPLATE_ARN" \
        --tags '{"owner":"compatibility"}'
    assert_success

    run aws_cmd fis list-tags-for-resource --resource-arn "$TEMPLATE_ARN"
    assert_success
    [ "$(json_get "$output" '.tags.suite')" = "awscli" ]
    [ "$(json_get "$output" '.tags.owner')" = "compatibility" ]

    run aws_cmd fis untag-resource \
        --resource-arn "$TEMPLATE_ARN" \
        --tag-keys owner
    assert_success

    run aws_cmd fis list-tags-for-resource --resource-arn "$TEMPLATE_ARN"
    assert_success
    owner=$(json_get "$output" '.tags.owner // empty')
    [ -z "$owner" ]
    [ "$(json_get "$output" '.tags.suite')" = "awscli" ]

    run aws_cmd fis start-experiment \
        --client-token "$(unique_name fis-cli-experiment)" \
        --experiment-template-id "$TEMPLATE_ID" \
        --experiment-options '{"actionsMode":"skip-all"}' \
        --tags '{"mode":"safe-preview"}'
    assert_success
    EXPERIMENT_ID=$(json_get "$output" '.experiment.id')
    [ -n "$EXPERIMENT_ID" ]
    [ "$(json_get "$output" '.experiment.experimentTemplateId')" = "$TEMPLATE_ID" ]
    [ "$(json_get "$output" '.experiment.experimentOptions.actionsMode')" = "skip-all" ]
    experiment_status=$(json_get "$output" '.experiment.state.status')
    [[ "$experiment_status" = "running" || "$experiment_status" = "completed" ]]

    run aws_cmd fis get-experiment --id "$EXPERIMENT_ID"
    assert_success
    [ "$(json_get "$output" '.experiment.id')" = "$EXPERIMENT_ID" ]
    [ "$(json_get "$output" '.experiment.tags.mode')" = "safe-preview" ]

    run aws_cmd fis list-experiments \
        --experiment-template-id "$TEMPLATE_ID" \
        --max-results 100
    assert_success
    experiment_listed=$(echo "$output" | jq --arg id "$EXPERIMENT_ID" \
        '.experiments | any(.id == $id)')
    [ "$experiment_listed" = "true" ]

    run aws_cmd fis list-experiment-resolved-targets \
        --experiment-id "$EXPERIMENT_ID" \
        --max-results 100
    assert_success
    [ "$(json_get "$output" '.resolvedTargets | length')" = "0" ]

    run aws_cmd fis get-experiment-target-account-configuration \
        --experiment-id "$EXPERIMENT_ID" \
        --account-id 111122223333
    assert_success
    [ "$(json_get "$output" '.targetAccountConfiguration.accountId')" = "111122223333" ]

    run aws_cmd fis list-experiment-target-account-configurations \
        --experiment-id "$EXPERIMENT_ID"
    assert_success
    account_snapshot_listed=$(echo "$output" | jq \
        '.targetAccountConfigurations | any(.accountId == "111122223333")')
    [ "$account_snapshot_listed" = "true" ]

    run aws_cmd fis stop-experiment --id "$EXPERIMENT_ID"
    assert_success
    state=$(json_get "$output" '.experiment.state.status')
    [[ "$state" = "stopped" || "$state" = "completed" ]]
    EXPERIMENT_ID=""

    run aws_cmd fis delete-target-account-configuration \
        --experiment-template-id "$TEMPLATE_ID" \
        --account-id 111122223333
    assert_success
    [ "$(json_get "$output" '.targetAccountConfiguration.accountId')" = "111122223333" ]
    TARGET_ACCOUNT_CONFIGURATION_EXISTS="false"

    run aws_cmd fis delete-experiment-template --id "$TEMPLATE_ID"
    assert_success
    [ "$(json_get "$output" '.experimentTemplate.id')" = "$TEMPLATE_ID" ]
    TEMPLATE_ID=""
    TEMPLATE_ARN=""
}
