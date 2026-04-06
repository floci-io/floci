#!/usr/bin/env bats
# DynamoDB tests

setup() {
    load 'test_helper/common-setup'
    TABLE_NAME="bats-test-table-$(unique_name)"
}

teardown() {
    aws_cmd dynamodb delete-table --table-name "$TABLE_NAME" >/dev/null 2>&1 || true
}

@test "DynamoDB: create table" {
    run aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST
    assert_success
    status=$(json_get "$output" '.TableDescription.TableStatus')
    [ "$status" = "ACTIVE" ] || [ "$status" = "CREATING" ]
}

@test "DynamoDB: describe table" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    run aws_cmd dynamodb describe-table --table-name "$TABLE_NAME"
    assert_success
    name=$(json_get "$output" '.Table.TableName')
    [ "$name" = "$TABLE_NAME" ]
}

@test "DynamoDB: list tables" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    run aws_cmd dynamodb list-tables
    assert_success
    found=$(echo "$output" | jq --arg name "$TABLE_NAME" '.TableNames | any(. == $name)')
    [ "$found" = "true" ]
}

@test "DynamoDB: put and get item" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    run aws_cmd dynamodb put-item \
        --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"user#1"},"name":{"S":"Alice"}}'
    assert_success

    run aws_cmd dynamodb get-item \
        --table-name "$TABLE_NAME" \
        --key '{"pk":{"S":"user#1"}}'
    assert_success
    name=$(json_get "$output" '.Item.name.S')
    [ "$name" = "Alice" ]
}

@test "DynamoDB: update item" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    aws_cmd dynamodb put-item \
        --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"user#1"},"name":{"S":"Alice"}}' >/dev/null

    run aws_cmd dynamodb update-item \
        --table-name "$TABLE_NAME" \
        --key '{"pk":{"S":"user#1"}}' \
        --update-expression 'SET #n = :v' \
        --expression-attribute-names '{"#n":"name"}' \
        --expression-attribute-values '{":v":{"S":"Bob"}}'
    assert_success

    run aws_cmd dynamodb get-item \
        --table-name "$TABLE_NAME" \
        --key '{"pk":{"S":"user#1"}}'
    assert_success
    name=$(json_get "$output" '.Item.name.S')
    [ "$name" = "Bob" ]
}

@test "DynamoDB: scan table" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" --item '{"pk":{"S":"user#1"},"name":{"S":"Alice"}}' >/dev/null
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" --item '{"pk":{"S":"user#2"},"name":{"S":"Bob"}}' >/dev/null

    run aws_cmd dynamodb scan --table-name "$TABLE_NAME"
    assert_success
    count=$(json_get "$output" '.Count')
    [ "$count" -ge 2 ]
}

@test "DynamoDB: query table" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" --item '{"pk":{"S":"user#1"},"sk":{"S":"profile"},"name":{"S":"Alice"}}' >/dev/null

    run aws_cmd dynamodb query \
        --table-name "$TABLE_NAME" \
        --key-condition-expression 'pk = :pk' \
        --expression-attribute-values '{":pk":{"S":"user#1"}}'
    assert_success
    count=$(json_get "$output" '.Count')
    [ "$count" -ge 1 ]
}

@test "DynamoDB: delete item" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" --item '{"pk":{"S":"user#1"}}' >/dev/null

    run aws_cmd dynamodb delete-item \
        --table-name "$TABLE_NAME" \
        --key '{"pk":{"S":"user#1"}}'
    assert_success
}

@test "DynamoDB: delete table" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    run aws_cmd dynamodb delete-table --table-name "$TABLE_NAME"
    assert_success
}

# --- DynamoDB GSI/LSI Tests ---

@test "DynamoDB: create table with GSI and LSI" {
    run aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
            AttributeName=gsiPk,AttributeType=S \
            AttributeName=lsiSk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --global-secondary-indexes \
            'IndexName=gsi-1,KeySchema=[{AttributeName=gsiPk,KeyType=HASH},{AttributeName=sk,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
        --local-secondary-indexes \
            'IndexName=lsi-1,KeySchema=[{AttributeName=pk,KeyType=HASH},{AttributeName=lsiSk,KeyType=RANGE}],Projection={ProjectionType=KEYS_ONLY}' \
        --billing-mode PAY_PER_REQUEST
    assert_success
}

@test "DynamoDB: GSI count is 1" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
            AttributeName=gsiPk,AttributeType=S \
            AttributeName=lsiSk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --global-secondary-indexes \
            'IndexName=gsi-1,KeySchema=[{AttributeName=gsiPk,KeyType=HASH},{AttributeName=sk,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
        --local-secondary-indexes \
            'IndexName=lsi-1,KeySchema=[{AttributeName=pk,KeyType=HASH},{AttributeName=lsiSk,KeyType=RANGE}],Projection={ProjectionType=KEYS_ONLY}' \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    run aws_cmd dynamodb describe-table --table-name "$TABLE_NAME"
    assert_success
    count=$(echo "$output" | jq '.Table.GlobalSecondaryIndexes | length')
    [ "$count" = "1" ]
}

@test "DynamoDB: GSI name is gsi-1" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
            AttributeName=gsiPk,AttributeType=S \
            AttributeName=lsiSk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --global-secondary-indexes \
            'IndexName=gsi-1,KeySchema=[{AttributeName=gsiPk,KeyType=HASH},{AttributeName=sk,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
        --local-secondary-indexes \
            'IndexName=lsi-1,KeySchema=[{AttributeName=pk,KeyType=HASH},{AttributeName=lsiSk,KeyType=RANGE}],Projection={ProjectionType=KEYS_ONLY}' \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    run aws_cmd dynamodb describe-table --table-name "$TABLE_NAME"
    assert_success
    name=$(echo "$output" | jq -r '.Table.GlobalSecondaryIndexes[0].IndexName')
    [ "$name" = "gsi-1" ]
}

@test "DynamoDB: GSI projection is ALL" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
            AttributeName=gsiPk,AttributeType=S \
            AttributeName=lsiSk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --global-secondary-indexes \
            'IndexName=gsi-1,KeySchema=[{AttributeName=gsiPk,KeyType=HASH},{AttributeName=sk,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
        --local-secondary-indexes \
            'IndexName=lsi-1,KeySchema=[{AttributeName=pk,KeyType=HASH},{AttributeName=lsiSk,KeyType=RANGE}],Projection={ProjectionType=KEYS_ONLY}' \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    run aws_cmd dynamodb describe-table --table-name "$TABLE_NAME"
    assert_success
    proj=$(echo "$output" | jq -r '.Table.GlobalSecondaryIndexes[0].Projection.ProjectionType')
    [ "$proj" = "ALL" ]
}

@test "DynamoDB: LSI count is 1" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
            AttributeName=gsiPk,AttributeType=S \
            AttributeName=lsiSk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --global-secondary-indexes \
            'IndexName=gsi-1,KeySchema=[{AttributeName=gsiPk,KeyType=HASH},{AttributeName=sk,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
        --local-secondary-indexes \
            'IndexName=lsi-1,KeySchema=[{AttributeName=pk,KeyType=HASH},{AttributeName=lsiSk,KeyType=RANGE}],Projection={ProjectionType=KEYS_ONLY}' \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    run aws_cmd dynamodb describe-table --table-name "$TABLE_NAME"
    assert_success
    count=$(echo "$output" | jq '.Table.LocalSecondaryIndexes | length')
    [ "$count" = "1" ]
}

@test "DynamoDB: LSI name is lsi-1" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
            AttributeName=gsiPk,AttributeType=S \
            AttributeName=lsiSk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --global-secondary-indexes \
            'IndexName=gsi-1,KeySchema=[{AttributeName=gsiPk,KeyType=HASH},{AttributeName=sk,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
        --local-secondary-indexes \
            'IndexName=lsi-1,KeySchema=[{AttributeName=pk,KeyType=HASH},{AttributeName=lsiSk,KeyType=RANGE}],Projection={ProjectionType=KEYS_ONLY}' \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    run aws_cmd dynamodb describe-table --table-name "$TABLE_NAME"
    assert_success
    name=$(echo "$output" | jq -r '.Table.LocalSecondaryIndexes[0].IndexName')
    [ "$name" = "lsi-1" ]
}

@test "DynamoDB: LSI projection is KEYS_ONLY" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
            AttributeName=gsiPk,AttributeType=S \
            AttributeName=lsiSk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --global-secondary-indexes \
            'IndexName=gsi-1,KeySchema=[{AttributeName=gsiPk,KeyType=HASH},{AttributeName=sk,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
        --local-secondary-indexes \
            'IndexName=lsi-1,KeySchema=[{AttributeName=pk,KeyType=HASH},{AttributeName=lsiSk,KeyType=RANGE}],Projection={ProjectionType=KEYS_ONLY}' \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    run aws_cmd dynamodb describe-table --table-name "$TABLE_NAME"
    assert_success
    proj=$(echo "$output" | jq -r '.Table.LocalSecondaryIndexes[0].Projection.ProjectionType')
    [ "$proj" = "KEYS_ONLY" ]
}

@test "DynamoDB: query GSI sparse index" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
            AttributeName=gsiPk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --global-secondary-indexes \
            'IndexName=gsi-1,KeySchema=[{AttributeName=gsiPk,KeyType=HASH},{AttributeName=sk,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    # Put 2 items with gsiPk
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-1"},"sk":{"S":"rev-1"},"gsiPk":{"S":"group-A"}}' >/dev/null
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-2"},"sk":{"S":"rev-1"},"gsiPk":{"S":"group-A"}}' >/dev/null
    # Put 1 item without gsiPk (sparse)
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-3"},"sk":{"S":"rev-1"},"data":{"S":"no-gsi"}}' >/dev/null

    # Query GSI - should return only the 2 items with gsiPk
    run aws_cmd dynamodb query \
        --table-name "$TABLE_NAME" \
        --index-name gsi-1 \
        --key-condition-expression 'gsiPk = :gpk' \
        --expression-attribute-values '{":gpk":{"S":"group-A"}}'
    assert_success
    count=$(json_get "$output" '.Count')
    [ "$count" = "2" ]
}

@test "DynamoDB: query LSI" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
            AttributeName=lsiSk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --local-secondary-indexes \
            'IndexName=lsi-1,KeySchema=[{AttributeName=pk,KeyType=HASH},{AttributeName=lsiSk,KeyType=RANGE}],Projection={ProjectionType=KEYS_ONLY}' \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-1"},"sk":{"S":"rev-1"},"lsiSk":{"S":"2024-01-01"}}' >/dev/null
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-1"},"sk":{"S":"rev-2"},"lsiSk":{"S":"2024-01-02"}}' >/dev/null

    # Query LSI with range condition
    run aws_cmd dynamodb query \
        --table-name "$TABLE_NAME" \
        --index-name lsi-1 \
        --key-condition-expression 'pk = :pk AND lsiSk > :d' \
        --expression-attribute-values '{":pk":{"S":"item-1"},":d":{"S":"2024-01-00"}}'
    assert_success
    count=$(json_get "$output" '.Count')
    [ "$count" = "2" ]
}

# --- DynamoDB FilterExpression Tests ---

@test "DynamoDB: query with FilterExpression" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    # Put items with different status values
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"user#1"},"sk":{"S":"order#1"},"status":{"S":"pending"}}' >/dev/null
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"user#1"},"sk":{"S":"order#2"},"status":{"S":"complete"}}' >/dev/null
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"user#1"},"sk":{"S":"order#3"},"status":{"S":"pending"}}' >/dev/null

    # Query with FilterExpression to get only pending
    run aws_cmd dynamodb query \
        --table-name "$TABLE_NAME" \
        --key-condition-expression 'pk = :pk' \
        --filter-expression '#s = :status' \
        --expression-attribute-names '{"#s":"status"}' \
        --expression-attribute-values '{":pk":{"S":"user#1"},":status":{"S":"pending"}}'
    assert_success
    count=$(json_get "$output" '.Count')
    [ "$count" = "2" ]
}

@test "DynamoDB: query FilterExpression with limit" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    # Put multiple items
    for i in 1 2 3 4 5; do
        aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
            --item "{\"pk\":{\"S\":\"user#1\"},\"sk\":{\"S\":\"order#$i\"},\"status\":{\"S\":\"pending\"}}" >/dev/null
    done

    # Query with limit
    run aws_cmd dynamodb query \
        --table-name "$TABLE_NAME" \
        --key-condition-expression 'pk = :pk' \
        --expression-attribute-values '{":pk":{"S":"user#1"}}' \
        --limit 2
    assert_success
    count=$(json_get "$output" '.Count')
    [ "$count" = "2" ]
}

@test "DynamoDB: query FilterExpression pagination" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    # Put multiple items
    for i in 1 2 3 4 5; do
        aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
            --item "{\"pk\":{\"S\":\"user#1\"},\"sk\":{\"S\":\"order#$i\"},\"status\":{\"S\":\"pending\"}}" >/dev/null
    done

    # First page
    run aws_cmd dynamodb query \
        --table-name "$TABLE_NAME" \
        --key-condition-expression 'pk = :pk' \
        --expression-attribute-values '{":pk":{"S":"user#1"}}' \
        --limit 2
    assert_success

    # Check that LastEvaluatedKey exists for pagination
    lek=$(echo "$output" | jq '.LastEvaluatedKey')
    [ "$lek" != "null" ]

    # Get next page
    run aws_cmd dynamodb query \
        --table-name "$TABLE_NAME" \
        --key-condition-expression 'pk = :pk' \
        --expression-attribute-values '{":pk":{"S":"user#1"}}' \
        --limit 2 \
        --exclusive-start-key "$lek"
    assert_success
    count=$(json_get "$output" '.Count')
    [ "$count" = "2" ]
}

# --- DynamoDB Advanced Filter Tests ---

@test "DynamoDB: scan with contains on List" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-1"},"tags":{"L":[{"S":"foo"},{"S":"bar"}]}}' >/dev/null
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-2"},"tags":{"L":[{"S":"baz"},{"S":"qux"}]}}' >/dev/null

    run aws_cmd dynamodb scan \
        --table-name "$TABLE_NAME" \
        --filter-expression 'contains(tags, :tag)' \
        --expression-attribute-values '{":tag":{"S":"foo"}}'
    assert_success
    count=$(json_get "$output" '.Count')
    [ "$count" = "1" ]
}

@test "DynamoDB: scan with BOOL filter" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-1"},"active":{"BOOL":true}}' >/dev/null
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-2"},"active":{"BOOL":false}}' >/dev/null

    run aws_cmd dynamodb scan \
        --table-name "$TABLE_NAME" \
        --filter-expression 'active = :val' \
        --expression-attribute-values '{":val":{"BOOL":true}}'
    assert_success
    count=$(json_get "$output" '.Count')
    [ "$count" = "1" ]
}

@test "DynamoDB: scan with attribute_exists on nested Map" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-1"},"meta":{"M":{"created":{"S":"2024-01-01"}}}}' >/dev/null
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-2"},"other":{"S":"data"}}' >/dev/null

    run aws_cmd dynamodb scan \
        --table-name "$TABLE_NAME" \
        --filter-expression 'attribute_exists(meta.created)'
    assert_success
    count=$(json_get "$output" '.Count')
    [ "$count" = "1" ]
}

@test "DynamoDB: scan with contains on String Set" {
    aws_cmd dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions AttributeName=pk,AttributeType=S \
        --key-schema AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST >/dev/null

    ddb_wait_table "$TABLE_NAME"

    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-1"},"roles":{"SS":["admin","user"]}}' >/dev/null
    aws_cmd dynamodb put-item --table-name "$TABLE_NAME" \
        --item '{"pk":{"S":"item-2"},"roles":{"SS":["guest"]}}' >/dev/null

    run aws_cmd dynamodb scan \
        --table-name "$TABLE_NAME" \
        --filter-expression 'contains(roles, :role)' \
        --expression-attribute-values '{":role":{"S":"admin"}}'
    assert_success
    count=$(json_get "$output" '.Count')
    [ "$count" = "1" ]
}
