package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.BatchDeleteTableRequest;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.CreateDatabaseRequest;
import software.amazon.awssdk.services.glue.model.CreatePartitionRequest;
import software.amazon.awssdk.services.glue.model.CreateTableRequest;
import software.amazon.awssdk.services.glue.model.CreateUserDefinedFunctionRequest;
import software.amazon.awssdk.services.glue.model.DatabaseInput;
import software.amazon.awssdk.services.glue.model.DeleteDatabaseRequest;
import software.amazon.awssdk.services.glue.model.DeleteTableRequest;
import software.amazon.awssdk.services.glue.model.DeleteUserDefinedFunctionRequest;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GetDatabaseRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetPartitionsRequest;
import software.amazon.awssdk.services.glue.model.GetTableRequest;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.GetUserDefinedFunctionRequest;
import software.amazon.awssdk.services.glue.model.GetUserDefinedFunctionsRequest;
import software.amazon.awssdk.services.glue.model.PartitionInput;
import software.amazon.awssdk.services.glue.model.PrincipalType;
import software.amazon.awssdk.services.glue.model.ResourceType;
import software.amazon.awssdk.services.glue.model.ResourceUri;
import software.amazon.awssdk.services.glue.model.SerDeInfo;
import software.amazon.awssdk.services.glue.model.StorageDescriptor;
import software.amazon.awssdk.services.glue.model.TableInput;
import software.amazon.awssdk.services.glue.model.UpdateTableRequest;
import software.amazon.awssdk.services.glue.model.UpdateUserDefinedFunctionRequest;
import software.amazon.awssdk.services.glue.model.UserDefinedFunction;
import software.amazon.awssdk.services.glue.model.UserDefinedFunctionInput;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;

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
        assertThat(glue.getPartitions(GetPartitionsRequest.builder()
                .databaseName(DATABASE_NAME)
                .tableName(TABLE_NAME)
                .build()).partitions())
                .singleElement()
                .satisfies(partition -> {
                    assertThat(partition.databaseName()).isEqualTo(DATABASE_NAME);
                    assertThat(partition.tableName()).isEqualTo(TABLE_NAME);
                    assertThat(partition.values()).containsExactly("2026");
                });

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
