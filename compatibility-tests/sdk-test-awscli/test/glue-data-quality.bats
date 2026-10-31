#!/usr/bin/env bats

setup() {
    load 'test_helper/common-setup'
    RULESET_NAME="$(unique_name glue-dq-ruleset)"
}

teardown() {
    aws_cmd glue delete-data-quality-ruleset --name "$RULESET_NAME" >/dev/null 2>&1 || true
}

@test "Glue data quality: ruleset lifecycle" {
    run aws_cmd glue create-data-quality-ruleset \
        --name "$RULESET_NAME" \
        --description "orders checks" \
        --ruleset 'Rules = [ RowCount > 0, IsComplete "id" ]' \
        --target-table TableName=orders,DatabaseName=sales \
        --tags team=data
    assert_success
    [ "$(json_get "$output" '.Name')" = "$RULESET_NAME" ]

    run aws_cmd glue create-data-quality-ruleset \
        --name "$RULESET_NAME" \
        --ruleset 'Rules = [ RowCount > 0 ]'
    assert_failure
    [[ "$output" == *"AlreadyExistsException"* ]]

    run aws_cmd glue get-data-quality-ruleset --name "$RULESET_NAME"
    assert_success
    [ "$(json_get "$output" '.TargetTable.DatabaseName')" = "sales" ]
    [ "$(json_get "$output" '.Ruleset')" = 'Rules = [ RowCount > 0, IsComplete "id" ]' ]

    run aws_cmd glue update-data-quality-ruleset \
        --name "$RULESET_NAME" \
        --description "orders, nightly"
    assert_success
    [ "$(json_get "$output" '.Description')" = "orders, nightly" ]

    run aws_cmd glue list-data-quality-rulesets --tags team=data \
        --filter 'TargetTable={TableName=orders,DatabaseName=sales}'
    assert_success
    count=$(echo "$output" | jq -r --arg n "$RULESET_NAME" '.Rulesets[] | select(.Name == $n) | .RuleCount')
    [ "$count" = "2" ]

    run aws_cmd glue delete-data-quality-ruleset --name "$RULESET_NAME"
    assert_success

    run aws_cmd glue get-data-quality-ruleset --name "$RULESET_NAME"
    assert_failure
    [[ "$output" == *"EntityNotFoundException"* ]]
}
