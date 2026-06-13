#!/usr/bin/env bats

setup() {
    load 'test_helper/common-setup'
    DB_NAME="$(unique_name glue-catalog-db)"
    TABLE_NAME="catalog_table"
    SECOND_TABLE_NAME="catalog_table_second"
    FUNCTION_NAME="catalog_function"
}

teardown() {
    aws_cmd glue delete-user-defined-function \
        --database-name "$DB_NAME" \
        --function-name "$FUNCTION_NAME" >/dev/null 2>&1 || true
    aws_cmd glue delete-table \
        --database-name "$DB_NAME" \
        --name "$SECOND_TABLE_NAME" >/dev/null 2>&1 || true
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
    local name="${1:-$TABLE_NAME}"
    local description="$2"
    jq -n \
        --arg name "$name" \
        --arg description "$description" \
        --arg location "s3://floci-glue-catalog/$DB_NAME/$name/" \
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
                        Type: "int",
                        Parameters: {
                            comment: "identifier"
                        }
                    }
                ]
            },
            PartitionKeys: [
                {
                    Name: "year",
                    Type: "int"
                }
            ]
        }'
}

create_table() {
    local name="${1:-$TABLE_NAME}"
    local description="${2:-created}"
    aws_cmd glue create-table \
        --database-name "$DB_NAME" \
        --table-input "$(table_input "$name" "$description")" >/dev/null
}

column_statistics_input() {
    jq -n '[
        {
            ColumnName: "id",
            ColumnType: "int",
            AnalyzedTime: "2026-06-08T00:00:00Z",
            StatisticsData: {
                Type: "LONG",
                LongColumnStatisticsData: {
                    MinimumValue: 1,
                    MaximumValue: 10,
                    NumberOfNulls: 0,
                    NumberOfDistinctValues: 10
                }
            }
        }
    ]'
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

    run aws_cmd glue update-database \
        --name "$DB_NAME" \
        --database-input "{\"Name\":\"$DB_NAME\",\"Description\":\"updated database\",\"LocationUri\":\"s3://floci-glue-catalog/$DB_NAME/\"}"
    assert_success

    run aws_cmd glue get-database --name "$DB_NAME"
    assert_success
    description=$(json_get "$output" '.Database.Description')
    location=$(json_get "$output" '.Database.LocationUri')
    [ "$description" = "updated database" ]
    [ "$location" = "s3://floci-glue-catalog/$DB_NAME/" ]

    run aws_cmd glue update-database \
        --name "$DB_NAME" \
        --database-input "{\"Name\":\"${DB_NAME}-renamed\",\"Description\":\"renamed database\"}"
    assert_failure
    [[ "$output" == *"InvalidInputException"* ]]
    [[ "$output" == *"Database cannot be renamed"* ]]

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
    column_comment=$(json_get "$output" '.Table.StorageDescriptor.Columns[0].Parameters.comment')
    [ "$name" = "$TABLE_NAME" ]
    [ "$version_id" = "0" ]
    [ "$column_comment" = "identifier" ]

    run aws_cmd glue get-tables --database-name "$DB_NAME"
    assert_success
    found=$(echo "$output" | jq --arg name "$TABLE_NAME" '.TableList | any(.Name == $name)')
    [ "$found" = "true" ]

    run aws_cmd glue update-table \
        --database-name "$DB_NAME" \
        --version-id "$version_id" \
        --table-input "$(table_input "$TABLE_NAME" updated)"
    assert_success

    run aws_cmd glue get-table \
        --database-name "$DB_NAME" \
        --name "$TABLE_NAME"
    assert_success
    description=$(json_get "$output" '.Table.Description')
    version_id=$(json_get "$output" '.Table.VersionId')
    [ "$description" = "updated" ]
    [ "$version_id" = "1" ]

    run aws_cmd glue get-table-versions \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME"
    assert_success
    version_count=$(echo "$output" | jq '.TableVersions | length')
    current_version=$(json_get "$output" '.TableVersions[0].VersionId')
    current_description=$(json_get "$output" '.TableVersions[0].Table.Description')
    archived_version=$(json_get "$output" '.TableVersions[1].VersionId')
    archived_description=$(json_get "$output" '.TableVersions[1].Table.Description')
    [ "$version_count" = "2" ]
    [ "$current_version" = "1" ]
    [ "$current_description" = "updated" ]
    [ "$archived_version" = "0" ]
    [ "$archived_description" = "created" ]

    run aws_cmd glue update-column-statistics-for-table \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --column-statistics-list "$(column_statistics_input)"
    assert_success

    run aws_cmd glue get-column-statistics-for-table \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --column-names id missing
    assert_success
    column_name=$(json_get "$output" '.ColumnStatisticsList[0].ColumnName')
    statistics_type=$(json_get "$output" '.ColumnStatisticsList[0].StatisticsData.Type')
    minimum_value=$(json_get "$output" '.ColumnStatisticsList[0].StatisticsData.LongColumnStatisticsData.MinimumValue')
    missing_column_name=$(json_get "$output" '.Errors[0].ColumnName')
    error_code=$(json_get "$output" '.Errors[0].Error.ErrorCode')
    [ "$column_name" = "id" ]
    [ "$statistics_type" = "LONG" ]
    [ "$minimum_value" = "1" ]
    [ "$missing_column_name" = "missing" ]
    [ "$error_code" = "EntityNotFoundException" ]

    run aws_cmd glue delete-column-statistics-for-table \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --column-name id
    assert_success

    run aws_cmd glue delete-column-statistics-for-table \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --column-name id
    assert_success

    run aws_cmd glue get-column-statistics-for-table \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --column-names id
    assert_success
    deleted_statistics_count=$(json_get "$output" '.ColumnStatisticsList | length')
    deleted_error_code=$(json_get "$output" '.Errors[0].Error.ErrorCode')
    [ "$deleted_statistics_count" = "0" ]
    [ "$deleted_error_code" = "EntityNotFoundException" ]

    run aws_cmd glue create-partition \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --partition-input '{"Values":["2026"]}'
    assert_success

    run aws_cmd glue batch-create-partition \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --partition-input-list '[{"Values":["2027"]},{"Values":["2028"]}]'
    assert_success
    batch_create_error_count=$(json_get "$output" '.Errors | length')
    [ "$batch_create_error_count" = "0" ]

    run aws_cmd glue batch-create-partition \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --partition-input-list '[{"Values":["2027"]}]'
    assert_success
    duplicate_partition_value=$(json_get "$output" '.Errors[0].PartitionValues[0]')
    duplicate_error_code=$(json_get "$output" '.Errors[0].ErrorDetail.ErrorCode')
    [ "$duplicate_partition_value" = "2027" ]
    [ "$duplicate_error_code" = "AlreadyExistsException" ]

    batch_update_entries='[
        {"PartitionValueList":["2026"],"PartitionInput":{"Values":["2026"],"Parameters":{"after":"yes"}}},
        {"PartitionValueList":["missing"],"PartitionInput":{"Values":["missing"]}}
    ]'
    run aws_cmd glue batch-update-partition \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --entries "$batch_update_entries"
    assert_success
    updated_missing_partition_value=$(json_get "$output" '.Errors[0].PartitionValueList[0]')
    updated_missing_error_code=$(json_get "$output" '.Errors[0].ErrorDetail.ErrorCode')
    [ "$updated_missing_partition_value" = "missing" ]
    [ "$updated_missing_error_code" = "EntityNotFoundException" ]

    run aws_cmd glue update-partition \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --partition-value-list 2027 \
        --partition-input '{"Values":["2027"],"Parameters":{"updated":"yes"}}'
    assert_success

    run aws_cmd glue update-partition \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --partition-value-list missing \
        --partition-input '{"Values":["missing"]}'
    assert_failure
    [[ "$output" == *"EntityNotFoundException"* ]]

    run aws_cmd glue get-partitions \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME"
    assert_success
    value=$(json_get "$output" '.Partitions[0].Values[0]')
    parameter=$(json_get "$output" '.Partitions[0].Parameters.after')
    [ "$value" = "2026" ]
    [ "$parameter" = "yes" ]

    run aws_cmd glue get-partitions \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --expression "year >= 2026 AND year <= 2027"
    assert_success
    filtered_count=$(json_get "$output" '.Partitions | length')
    [ "$filtered_count" = "2" ]

    run aws_cmd glue get-partitions \
        --database-name "$DB_NAME" \
        --table-name "$TABLE_NAME" \
        --expression "year in (2026, 2028)"
    assert_success
    filtered_count=$(json_get "$output" '.Partitions | length')
    [ "$filtered_count" = "2" ]

    run aws_cmd glue delete-table \
        --database-name "$DB_NAME" \
        --name "$TABLE_NAME"
    assert_success

    run aws_cmd glue get-table \
        --database-name "$DB_NAME" \
        --name "$TABLE_NAME"
    assert_failure
}

@test "Glue catalog: batch delete table" {
    run create_database
    assert_success

    run create_table "$TABLE_NAME" "batch delete first table"
    assert_success

    run create_table "$SECOND_TABLE_NAME" "batch delete second table"
    assert_success

    run aws_cmd glue batch-delete-table \
        --database-name "$DB_NAME" \
        --tables-to-delete "$TABLE_NAME" "$SECOND_TABLE_NAME"
    assert_success
    error_count=$(echo "$output" | jq '.Errors | length')
    [ "$error_count" = "0" ]

    run aws_cmd glue get-table \
        --database-name "$DB_NAME" \
        --name "$TABLE_NAME"
    assert_failure

    run aws_cmd glue get-table \
        --database-name "$DB_NAME" \
        --name "$SECOND_TABLE_NAME"
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
