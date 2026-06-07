#!/usr/bin/env bats

setup() {
    load 'test_helper/common-setup'
    DB_NAME="$(unique_name glue-catalog-db)"
    TABLE_NAME="catalog_table"
    FUNCTION_NAME="catalog_function"
}

teardown() {
    aws_cmd glue delete-user-defined-function \
        --database-name "$DB_NAME" \
        --function-name "$FUNCTION_NAME" >/dev/null 2>&1 || true
    aws_cmd glue delete-table \
        --database-name "$DB_NAME" \
        --name "$TABLE_NAME" >/dev/null 2>&1 || true
    aws_cmd glue delete-database \
        --name "$DB_NAME" >/dev/null 2>&1 || true
}

create_database() {
    aws_cmd glue create-database \
        --database-input "{\"Name\":\"$DB_NAME\",\"Description\":\"catalog compatibility database\"}" >/dev/null
}

table_input() {
    local description="$1"
    jq -n \
        --arg name "$TABLE_NAME" \
        --arg description "$description" \
        --arg location "s3://floci-glue-catalog/$DB_NAME/$TABLE_NAME/" \
        '{
            Name: $name,
            Description: $description,
            Parameters: {
                classification: "json"
            },
            StorageDescriptor: {
                Location: $location,
                InputFormat: "org.apache.hadoop.mapred.TextInputFormat",
                OutputFormat: "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat",
                SerdeInfo: {
                    SerializationLibrary: "org.openx.data.jsonserde.JsonSerDe",
                    Parameters: {
                        "serialization.format": "1"
                    }
                },
                Columns: [
                    {
                        Name: "id",
                        Type: "int"
                    }
                ]
            }
        }'
}

create_table() {
    aws_cmd glue create-table \
        --database-name "$DB_NAME" \
        --table-input "$(table_input created)" >/dev/null
}

function_input() {
    local owner="$1"
    jq -n \
        --arg name "$FUNCTION_NAME" \
        --arg owner "$owner" \
        '{
            FunctionName: $name,
            ClassName: "CatalogFunction",
            OwnerName: $owner,
            OwnerType: "USER",
            ResourceUris: [
                {
                    ResourceType: "FILE",
                    Uri: "s3://floci-glue-catalog/function.jar"
                }
            ]
        }'
}

@test "Glue catalog: database lifecycle" {
    run create_database
    assert_success

    run aws_cmd glue get-database --name "$DB_NAME"
    assert_success
    name=$(json_get "$output" '.Database.Name')
    [ "$name" = "$DB_NAME" ]

    run aws_cmd glue get-databases
    assert_success
    found=$(echo "$output" | jq --arg name "$DB_NAME" '.DatabaseList | any(.Name == $name)')
    [ "$found" = "true" ]

    run aws_cmd glue delete-database --name "$DB_NAME"
    assert_success

    run aws_cmd glue get-database --name "$DB_NAME"
    assert_failure
}

@test "Glue catalog: table and partition lifecycle" {
    run create_database
    assert_success

    run create_table
    assert_success

    run aws_cmd glue get-table \
        --database-name "$DB_NAME" \
        --name "$TABLE_NAME"
    assert_success
    name=$(json_get "$output" '.Table.Name')
    version_id=$(json_get "$output" '.Table.VersionId')
    [ "$name" = "$TABLE_NAME" ]
    [ "$version_id" = "0" ]

    run aws_cmd glue get-tables --database-name "$DB_NAME"
    assert_success
    found=$(echo "$output" | jq --arg name "$TABLE_NAME" '.TableList | any(.Name == $name)')
    [ "$found" = "true" ]

    run aws_cmd glue update-table \
        --database-name "$DB_NAME" \
        --version-id "$version_id" \
        --table-input "$(table_input updated)"
    assert_success

    run aws_cmd glue get-table \
        --database-name "$DB_NAME" \
        --name "$TABLE_NAME"
    assert_success
    description=$(json_get "$output" '.Table.Description')
    version_id=$(json_get "$output" '.Table.VersionId')
    [ "$description" = "updated" ]
    [ "$version_id" = "1" ]

    run aws_cmd glue create-partition \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --partition-input '{"Values":["2026"]}'
    assert_success

    run aws_cmd glue get-partitions \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME"
    assert_success
    value=$(json_get "$output" '.Partitions[0].Values[0]')
    [ "$value" = "2026" ]

    run aws_cmd glue delete-table \
        --database-name "$DB_NAME" \
        --name "$TABLE_NAME"
    assert_success

    run aws_cmd glue get-table \
        --database-name "$DB_NAME" \
        --name "$TABLE_NAME"
    assert_failure
}

@test "Glue catalog: user-defined function lifecycle" {
    run create_database
    assert_success

    run aws_cmd glue create-user-defined-function \
        --database-name "$DB_NAME" \
        --function-input "$(function_input created-owner)"
    assert_success

    run aws_cmd glue get-user-defined-function \
        --database-name "$DB_NAME" \
        --function-name "$FUNCTION_NAME"
    assert_success
    name=$(json_get "$output" '.UserDefinedFunction.FunctionName')
    owner=$(json_get "$output" '.UserDefinedFunction.OwnerName')
    [ "$name" = "$FUNCTION_NAME" ]
    [ "$owner" = "created-owner" ]

    run aws_cmd glue get-user-defined-functions \
        --database-name "$DB_NAME" \
        --pattern "catalog_.*"
    assert_success
    found=$(echo "$output" | jq --arg name "$FUNCTION_NAME" '.UserDefinedFunctions | any(.FunctionName == $name)')
    [ "$found" = "true" ]

    run aws_cmd glue update-user-defined-function \
        --database-name "$DB_NAME" \
        --function-name "$FUNCTION_NAME" \
        --function-input "$(function_input updated-owner)"
    assert_success

    run aws_cmd glue get-user-defined-function \
        --database-name "$DB_NAME" \
        --function-name "$FUNCTION_NAME"
    assert_success
    owner=$(json_get "$output" '.UserDefinedFunction.OwnerName')
    [ "$owner" = "updated-owner" ]

    run aws_cmd glue delete-user-defined-function \
        --database-name "$DB_NAME" \
        --function-name "$FUNCTION_NAME"
    assert_success

    run aws_cmd glue get-user-defined-function \
        --database-name "$DB_NAME" \
        --function-name "$FUNCTION_NAME"
    assert_failure
}
