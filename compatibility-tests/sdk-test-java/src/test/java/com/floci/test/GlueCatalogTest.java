package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.BatchCreatePartitionRequest;
import software.amazon.awssdk.services.glue.model.BatchDeleteTableRequest;
import software.amazon.awssdk.services.glue.model.BatchGetPartitionRequest;
import software.amazon.awssdk.services.glue.model.BatchUpdatePartitionRequest;
import software.amazon.awssdk.services.glue.model.BatchUpdatePartitionRequestEntry;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.ColumnStatistics;
import software.amazon.awssdk.services.glue.model.ColumnStatisticsData;
import software.amazon.awssdk.services.glue.model.CreateDatabaseRequest;
import software.amazon.awssdk.services.glue.model.CreatePartitionRequest;
import software.amazon.awssdk.services.glue.model.CreateTableRequest;
import software.amazon.awssdk.services.glue.model.CreateUserDefinedFunctionRequest;
import software.amazon.awssdk.services.glue.model.DatabaseInput;
import software.amazon.awssdk.services.glue.model.DeleteColumnStatisticsForPartitionRequest;
import software.amazon.awssdk.services.glue.model.DeleteColumnStatisticsForTableRequest;
import software.amazon.awssdk.services.glue.model.DeleteDatabaseRequest;
import software.amazon.awssdk.services.glue.model.DeletePartitionRequest;
import software.amazon.awssdk.services.glue.model.DeleteTableRequest;
import software.amazon.awssdk.services.glue.model.DeleteUserDefinedFunctionRequest;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GetColumnStatisticsForPartitionRequest;
import software.amazon.awssdk.services.glue.model.GetColumnStatisticsForTableRequest;
import software.amazon.awssdk.services.glue.model.GetDatabaseRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetPartitionRequest;
import software.amazon.awssdk.services.glue.model.GetPartitionsRequest;
import software.amazon.awssdk.services.glue.model.GetTableRequest;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.GetUserDefinedFunctionRequest;
import software.amazon.awssdk.services.glue.model.GetUserDefinedFunctionsRequest;
import software.amazon.awssdk.services.glue.model.LongColumnStatisticsData;
import software.amazon.awssdk.services.glue.model.PartitionInput;
import software.amazon.awssdk.services.glue.model.PartitionValueList;
import software.amazon.awssdk.services.glue.model.PrincipalType;
import software.amazon.awssdk.services.glue.model.ResourceType;
import software.amazon.awssdk.services.glue.model.ResourceUri;
import software.amazon.awssdk.services.glue.model.SerDeInfo;
import software.amazon.awssdk.services.glue.model.StorageDescriptor;
import software.amazon.awssdk.services.glue.model.TableInput;
import software.amazon.awssdk.services.glue.model.UpdateColumnStatisticsForPartitionRequest;
import software.amazon.awssdk.services.glue.model.UpdateColumnStatisticsForTableRequest;
import software.amazon.awssdk.services.glue.model.UpdatePartitionRequest;
import software.amazon.awssdk.services.glue.model.UpdateTableRequest;
import software.amazon.awssdk.services.glue.model.UpdateUserDefinedFunctionRequest;
import software.amazon.awssdk.services.glue.model.UserDefinedFunction;
import software.amazon.awssdk.services.glue.model.UserDefinedFunctionInput;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Glue catalog")
class GlueCatalogTest {

    private static final String DATABASE_NAME = TestFixtures.uniqueName("catalog_db");
    private static final String BATCH_DELETE_DATABASE_NAME = TestFixtures.uniqueName("catalog_batch_delete_db");
    private static final String TABLE_NAME = "catalog_table";
    private static final String SECOND_TABLE_NAME = "catalog_table_second";
    private static final String FUNCTION_NAME = "catalog_function";
    private static final String DATABASE_TAGGED_NAME = TestFixtures.uniqueName("catalog_tagged_db");

    private static GlueClient glue;
    private static ResourceGroupsTaggingApiClient tagging;

    @BeforeAll
    static void setup() {
        glue = TestFixtures.glueClient();
        tagging = TestFixtures.resourceGroupsTaggingApiClient();
    }

    @AfterAll
    static void cleanup() {
        if (glue == null) {
            return;
        }
        try {
            glue.deleteUserDefinedFunction(DeleteUserDefinedFunctionRequest.builder()
                    .databaseName(DATABASE_NAME)
                    .functionName(FUNCTION_NAME)
                    .build());
        }
        catch (Exception ignored) {}
        try {
            glue.deleteTable(DeleteTableRequest.builder()
                    .databaseName(BATCH_DELETE_DATABASE_NAME)
                    .name(SECOND_TABLE_NAME)
                    .build());
        }
        catch (Exception ignored) {}
        try {
            glue.deleteTable(DeleteTableRequest.builder()
                    .databaseName(DATABASE_NAME)
                    .name(TABLE_NAME)
                    .build());
        }
        catch (Exception ignored) {}
        try {
            glue.deleteDatabase(DeleteDatabaseRequest.builder()
                    .name(DATABASE_TAGGED_NAME)
                    .build());
        }
        catch (Exception ignored) {}
        try {
            glue.deleteDatabase(DeleteDatabaseRequest.builder()
                    .name(BATCH_DELETE_DATABASE_NAME)
                    .build());
        }
        catch (Exception ignored) {}
        try {
            glue.deleteDatabase(DeleteDatabaseRequest.builder()
                    .name(DATABASE_NAME)
                    .build());
        }
        catch (Exception ignored) {}
        glue.close();
        tagging.close();
    }

    @Test
    void catalogLifecycle() {
        glue.createDatabase(CreateDatabaseRequest.builder()
                .databaseInput(DatabaseInput.builder()
                        .name(DATABASE_NAME)
                        .description("catalog compatibility database")
                        .build())
                .build());

        assertThat(glue.getDatabase(GetDatabaseRequest.builder()
                .name(DATABASE_NAME)
                .build()).database().name())
                .isEqualTo(DATABASE_NAME);
        assertThat(glue.getDatabases(GetDatabasesRequest.builder().build()).databaseList())
                .extracting(database -> database.name())
                .contains(DATABASE_NAME);

        glue.createTable(CreateTableRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableInput(tableInput("created"))
                .build());

        var createdTable = glue.getTable(GetTableRequest.builder()
                .databaseName(DATABASE_NAME)
                .name(TABLE_NAME)
                .build()).table();
        assertThat(createdTable.databaseName()).isEqualTo(DATABASE_NAME);
        assertThat(createdTable.name()).isEqualTo(TABLE_NAME);
        assertThat(createdTable.versionId()).isEqualTo("0");
        assertThat(createdTable.storageDescriptor().columns())
                .singleElement()
                .satisfies(column -> assertThat(column.parameters())
                        .containsEntry("comment", "identifier"));
        assertThat(glue.getTables(GetTablesRequest.builder()
                .databaseName(DATABASE_NAME)
                .build()).tableList())
                .extracting(table -> table.name())
                .contains(TABLE_NAME);
        glue.updateColumnStatisticsForTable(UpdateColumnStatisticsForTableRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .columnStatisticsList(ColumnStatistics.builder()
                        .columnName("id")
                        .columnType("int")
                        .analyzedTime(Instant.EPOCH)
                        .statisticsData(ColumnStatisticsData.builder()
                                .type("LONG")
                                .longColumnStatisticsData(LongColumnStatisticsData.builder()
                                        .minimumValue(1L)
                                        .maximumValue(10L)
                                        .numberOfNulls(0L)
                                        .numberOfDistinctValues(10L)
                                        .build())
                                .build())
                        .build())
                .build());
        glue.deleteColumnStatisticsForTable(DeleteColumnStatisticsForTableRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .columnName("id")
                .build());
        glue.deleteColumnStatisticsForTable(DeleteColumnStatisticsForTableRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .columnName("id")
                .build());
        var deletedStatistics = glue.getColumnStatisticsForTable(GetColumnStatisticsForTableRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .columnNames("id")
                .build());
        assertThat(deletedStatistics.columnStatisticsList())
                .isEmpty();
        assertThat(deletedStatistics.errors())
                .singleElement()
                .satisfies(error -> assertThat(error.error().errorCode()).isEqualTo("EntityNotFoundException"));

        glue.updateTable(UpdateTableRequest.builder()
                .databaseName(DATABASE_NAME)
                .versionId(createdTable.versionId())
                .tableInput(tableInput("updated"))
                .build());
        var updatedTable = glue.getTable(GetTableRequest.builder()
                .databaseName(DATABASE_NAME)
                .name(TABLE_NAME)
                .build()).table();
        assertThat(updatedTable.description()).isEqualTo("updated");
        assertThat(updatedTable.versionId()).isEqualTo("1");

        glue.createPartition(CreatePartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionInput(PartitionInput.builder()
                        .values("2026")
                        .build())
                .build());
        glue.createPartition(CreatePartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionInput(PartitionInput.builder()
                        .values("2027")
                        .build())
                .build());
        var batchCreatePartition = glue.batchCreatePartition(BatchCreatePartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionInputList(PartitionInput.builder()
                                .values("2028")
                                .build(),
                        PartitionInput.builder()
                                .values("2029")
                                .build())
                .build());
        assertThat(batchCreatePartition.errors()).isEmpty();
        assertThat(glue.batchCreatePartition(BatchCreatePartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionInputList(PartitionInput.builder()
                        .values("2028")
                        .build())
                .build()).errors())
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.partitionValues()).containsExactly("2028");
                    assertThat(error.errorDetail().errorCode()).isEqualTo("AlreadyExistsException");
                });
        assertThat(glue.batchUpdatePartition(BatchUpdatePartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .entries(
                        BatchUpdatePartitionRequestEntry.builder()
                                .partitionValueList("2026")
                                .partitionInput(PartitionInput.builder()
                                        .values("2026")
                                        .parameters(Map.of("after", "yes"))
                                        .build())
                                .build(),
                        BatchUpdatePartitionRequestEntry.builder()
                                .partitionValueList("missing")
                                .partitionInput(PartitionInput.builder()
                                        .values("missing")
                                        .build())
                                .build())
                .build()).errors())
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.partitionValueList()).containsExactly("missing");
                    assertThat(error.errorDetail().errorCode()).isEqualTo("EntityNotFoundException");
                });
        var partition = glue.getPartition(GetPartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionValues("2026")
                .build()).partition();
        assertThat(partition.values()).containsExactly("2026");
        assertThat(partition.parameters()).containsEntry("after", "yes");
        glue.updatePartition(UpdatePartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionValueList("2027")
                .partitionInput(PartitionInput.builder()
                        .values("2027")
                        .parameters(Map.of("updated", "yes"))
                        .build())
                .build());
        assertThat(glue.getPartition(GetPartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionValues("2027")
                .build()).partition().parameters())
                .containsEntry("updated", "yes");
        assertThatThrownBy(() -> glue.updatePartition(UpdatePartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionValueList("missing")
                .partitionInput(PartitionInput.builder()
                        .values("missing")
                        .build())
                .build()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(glue.batchGetPartition(BatchGetPartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionsToGet(
                        PartitionValueList.builder()
                                .values("2026")
                                .build(),
                        PartitionValueList.builder()
                                .values("missing")
                                .build())
                .build()).partitions())
                .singleElement()
                .satisfies(batchPartition -> assertThat(batchPartition.values()).containsExactly("2026"));
        assertThat(glue.getPartitions(GetPartitionsRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .expression("year = 2026")
                .build()).partitions())
                .singleElement()
                .satisfies(filteredPartition -> {
                    assertThat(filteredPartition.databaseName()).isEqualTo(DATABASE_NAME);
                    assertThat(filteredPartition.tableName()).isEqualTo(TABLE_NAME);
                    assertThat(filteredPartition.values()).containsExactly("2026");
                });
        assertThat(glue.getPartitions(GetPartitionsRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .expression("year >= 2026 AND year <= 2027")
                .build()).partitions())
                .extracting(partitionInput -> partitionInput.values().get(0))
                .containsExactlyInAnyOrder("2026", "2027");
        assertThat(glue.getPartitions(GetPartitionsRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .expression("year in (2026, 2028)")
                .build()).partitions())
                .extracting(partitionInput -> partitionInput.values().get(0))
                .containsExactlyInAnyOrder("2026", "2028");
        glue.updateColumnStatisticsForPartition(UpdateColumnStatisticsForPartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionValues("2026")
                .columnStatisticsList(ColumnStatistics.builder()
                        .columnName("id")
                        .columnType("int")
                        .analyzedTime(Instant.EPOCH)
                        .statisticsData(ColumnStatisticsData.builder()
                                .type("LONG")
                                .longColumnStatisticsData(LongColumnStatisticsData.builder()
                                        .minimumValue(1L)
                                        .maximumValue(10L)
                                        .numberOfNulls(0L)
                                        .numberOfDistinctValues(10L)
                                        .build())
                                .build())
                        .build())
                .build());
        var statistics = glue.getColumnStatisticsForPartition(GetColumnStatisticsForPartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionValues("2026")
                .columnNames("id", "missing")
                .build());
        assertThat(statistics.columnStatisticsList())
                .singleElement()
                .satisfies(columnStatistics -> {
                    assertThat(columnStatistics.columnName()).isEqualTo("id");
                    assertThat(columnStatistics.statisticsData().longColumnStatisticsData().maximumValue()).isEqualTo(10);
                });
        assertThat(statistics.errors())
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.columnName()).isEqualTo("missing");
                    assertThat(error.error().errorCode()).isEqualTo("EntityNotFoundException");
                });
        glue.deleteColumnStatisticsForPartition(DeleteColumnStatisticsForPartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionValues("2026")
                .columnName("id")
                .build());
        assertThat(glue.getColumnStatisticsForPartition(GetColumnStatisticsForPartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionValues("2026")
                .columnNames("id")
                .build()).columnStatisticsList())
                .isEmpty();
        glue.deletePartition(DeletePartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionValues("2027")
                .build());
        assertThatThrownBy(() -> glue.getPartition(GetPartitionRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .partitionValues("2027")
                .build()))
                .isInstanceOf(EntityNotFoundException.class);

        glue.createUserDefinedFunction(CreateUserDefinedFunctionRequest.builder()
                .databaseName(DATABASE_NAME)
                .functionInput(functionInput("created-owner"))
                .build());
        UserDefinedFunction createdFunction = glue.getUserDefinedFunction(GetUserDefinedFunctionRequest.builder()
                .databaseName(DATABASE_NAME)
                .functionName(FUNCTION_NAME)
                .build()).userDefinedFunction();
        assertThat(createdFunction.databaseName()).isEqualTo(DATABASE_NAME);
        assertThat(createdFunction.functionName()).isEqualTo(FUNCTION_NAME);
        assertThat(createdFunction.ownerName()).isEqualTo("created-owner");
        assertThat(glue.getUserDefinedFunctions(GetUserDefinedFunctionsRequest.builder()
                .databaseName(DATABASE_NAME)
                .pattern("catalog_.*")
                .build()).userDefinedFunctions())
                .extracting(UserDefinedFunction::functionName)
                .containsExactly(FUNCTION_NAME);

        glue.updateUserDefinedFunction(UpdateUserDefinedFunctionRequest.builder()
                .databaseName(DATABASE_NAME)
                .functionName(FUNCTION_NAME)
                .functionInput(functionInput("updated-owner"))
                .build());
        assertThat(glue.getUserDefinedFunction(GetUserDefinedFunctionRequest.builder()
                .databaseName(DATABASE_NAME)
                .functionName(FUNCTION_NAME)
                .build()).userDefinedFunction().ownerName())
                .isEqualTo("updated-owner");

        glue.deleteUserDefinedFunction(DeleteUserDefinedFunctionRequest.builder()
                .databaseName(DATABASE_NAME)
                .functionName(FUNCTION_NAME)
                .build());
        assertThatThrownBy(() -> glue.getUserDefinedFunction(GetUserDefinedFunctionRequest.builder()
                .databaseName(DATABASE_NAME)
                .functionName(FUNCTION_NAME)
                .build()))
                .isInstanceOf(EntityNotFoundException.class);

        glue.deleteTable(DeleteTableRequest.builder()
                .databaseName(DATABASE_NAME)
                .name(TABLE_NAME)
                .build());
        assertThatThrownBy(() -> glue.getTable(GetTableRequest.builder()
                .databaseName(DATABASE_NAME)
                .name(TABLE_NAME)
                .build()))
                .isInstanceOf(EntityNotFoundException.class);

        glue.deleteDatabase(DeleteDatabaseRequest.builder()
                .name(DATABASE_NAME)
                .build());
        assertThatThrownBy(() -> glue.getDatabase(GetDatabaseRequest.builder()
                .name(DATABASE_NAME)
                .build()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void batchDeleteTable() {
        glue.createDatabase(CreateDatabaseRequest.builder()
                .databaseInput(DatabaseInput.builder()
                        .name(BATCH_DELETE_DATABASE_NAME)
                        .description("catalog batch delete database")
                        .build())
                .build());

        glue.createTable(CreateTableRequest.builder()
                .databaseName(BATCH_DELETE_DATABASE_NAME)
                .tableInput(tableInput(BATCH_DELETE_DATABASE_NAME, TABLE_NAME, "batch delete first table"))
                .build());
        glue.createTable(CreateTableRequest.builder()
                .databaseName(BATCH_DELETE_DATABASE_NAME)
                .tableInput(tableInput(BATCH_DELETE_DATABASE_NAME, SECOND_TABLE_NAME, "batch delete second table"))
                .build());

        var response = glue.batchDeleteTable(BatchDeleteTableRequest.builder()
                .databaseName(BATCH_DELETE_DATABASE_NAME)
                .tablesToDelete(TABLE_NAME, SECOND_TABLE_NAME)
                .build());
        assertThat(response.errors()).isEmpty();

        assertThatThrownBy(() -> glue.getTable(GetTableRequest.builder()
                .databaseName(BATCH_DELETE_DATABASE_NAME)
                .name(TABLE_NAME)
                .build()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> glue.getTable(GetTableRequest.builder()
                .databaseName(BATCH_DELETE_DATABASE_NAME)
                .name(SECOND_TABLE_NAME)
                .build()))
                .isInstanceOf(EntityNotFoundException.class);

        glue.deleteDatabase(DeleteDatabaseRequest.builder()
                .name(BATCH_DELETE_DATABASE_NAME)
                .build());
    }

    @Test
    void createDatabaseWithTags_tagsReturnedByResourceGroupsTaggingApi() {
        glue.createDatabase(CreateDatabaseRequest.builder()
                .databaseInput(DatabaseInput.builder()
                        .name(DATABASE_TAGGED_NAME)
                        .description("catalog compatibility database with tags")
                        .build())
                .tags(Map.of("Environment", "dev", "Project", "project1"))
                .build());

        String databaseArn = "arn:aws:glue:us-east-1:000000000000:database/" + DATABASE_TAGGED_NAME;
        var response = tagging.getResources(b -> b.resourceARNList(databaseArn));

        assertThat(response.resourceTagMappingList()).singleElement().satisfies(mapping -> {
            assertThat(mapping.resourceARN()).isEqualTo(databaseArn);
            assertThat(mapping.tags().stream().collect(Collectors.toMap(t -> t.key(), t -> t.value())))
                    .containsEntry("Environment", "dev")
                    .containsEntry("Project", "project1");
        });
    }

    private static TableInput tableInput(String description) {
        return tableInput(TABLE_NAME, description);
    }

    private static TableInput tableInput(String tableName, String description) {
        return tableInput(DATABASE_NAME, tableName, description);
    }

    private static TableInput tableInput(String databaseName, String tableName, String description) {
        return TableInput.builder()
                .name(tableName)
                .description(description)
                .parameters(Map.of("classification", "json"))
                .storageDescriptor(StorageDescriptor.builder()
                        .location("s3://floci-glue-catalog/" + databaseName + "/" + tableName + "/")
                        .inputFormat("org.apache.hadoop.mapred.TextInputFormat")
                        .outputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat")
                        .serdeInfo(SerDeInfo.builder()
                                .serializationLibrary("org.openx.data.jsonserde.JsonSerDe")
                                .parameters(Map.of("serialization.format", "1"))
                                .build())
                        .columns(Column.builder()
                                .name("id")
                                .type("int")
                                .parameters(Map.of("comment", "identifier"))
                                .build())
                        .build())
                .partitionKeys(Column.builder()
                        .name("year")
                        .type("int")
                        .build())
                .build();
    }

    private static UserDefinedFunctionInput functionInput(String ownerName) {
        return UserDefinedFunctionInput.builder()
                .functionName(FUNCTION_NAME)
                .className("CatalogFunction")
                .ownerName(ownerName)
                .ownerType(PrincipalType.USER)
                .resourceUris(ResourceUri.builder()
                        .resourceType(ResourceType.FILE)
                        .uri("s3://floci-glue-catalog/function.jar")
                        .build())
                .build();
    }
}
