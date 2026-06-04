package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlueCatalogSchemaBindingIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String REGISTRY = "binding-registry";
    private static final String SCHEMA = "users";
    private static final String DATABASE = "binding-db";
    private static final String TABLE = "users_table";

    private static final String AVRO_V1 =
            "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"namespace\\\":\\\"x\\\","
                    + "\\\"fields\\\":[{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\"},"
                    + "{\\\"name\\\":\\\"name\\\",\\\"type\\\":\\\"string\\\"}]}";

    private static final String AVRO_V2 =
            "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"namespace\\\":\\\"x\\\","
                    + "\\\"fields\\\":[{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\"},"
                    + "{\\\"name\\\":\\\"name\\\",\\\"type\\\":\\\"string\\\"},"
                    + "{\\\"name\\\":\\\"email\\\",\\\"type\\\":[\\\"null\\\",\\\"string\\\"],\\\"default\\\":null}]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void seed_registryAndSchemaAndDatabase() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateRegistry")
            .body("{ \"RegistryName\": \"" + REGISTRY + "\" }")
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateSchema")
            .body("{"
                    + " \"RegistryId\": { \"RegistryName\": \"" + REGISTRY + "\" },"
                    + " \"SchemaName\": \"" + SCHEMA + "\","
                    + " \"DataFormat\": \"AVRO\","
                    + " \"Compatibility\": \"BACKWARD\","
                    + " \"SchemaDefinition\": \"" + AVRO_V1 + "\""
                    + " }")
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateDatabase")
            .body("{ \"DatabaseInput\": { \"Name\": \"" + DATABASE + "\" } }")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(2)
    void createTableWithSchemaReferenceLatest() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateTable")
            .body("{"
                    + " \"DatabaseName\": \"" + DATABASE + "\","
                    + " \"TableInput\": {"
                    + "   \"Name\": \"" + TABLE + "\","
                    + "   \"StorageDescriptor\": {"
                    + "     \"Location\": \"s3://bucket/users/\","
                    + "     \"Columns\": [],"
                    + "     \"SchemaReference\": {"
                    + "       \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" }"
                    + "     }"
                    + "   }"
                    + " } }")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(3)
    void getTableReturnsColumnsDerivedFromV1() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetTable")
            .body("{ \"DatabaseName\": \"" + DATABASE + "\", \"Name\": \"" + TABLE + "\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("Table.Name", equalTo(TABLE))
            .body("Table.StorageDescriptor.Columns", hasSize(2))
            .body("Table.StorageDescriptor.Columns[0].Name", equalTo("id"))
            .body("Table.StorageDescriptor.Columns[0].Type", equalTo("bigint"))
            .body("Table.StorageDescriptor.Columns[1].Name", equalTo("name"))
            .body("Table.StorageDescriptor.Columns[1].Type", equalTo("string"))
            .body("Table.VersionId", equalTo("0"))
            .body("Table.StorageDescriptor.SchemaReference", notNullValue());
    }

    @Test
    @Order(4)
    void registerV2AndGetTableReflectsLatestSchema() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.RegisterSchemaVersion")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaDefinition\": \"" + AVRO_V2 + "\" }")
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetTable")
            .body("{ \"DatabaseName\": \"" + DATABASE + "\", \"Name\": \"" + TABLE + "\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("Table.StorageDescriptor.Columns", hasSize(3))
            .body("Table.VersionId", equalTo("0"))
            .body("Table.StorageDescriptor.Columns[2].Name", equalTo("email"))
            .body("Table.StorageDescriptor.Columns[2].Type", equalTo("string"));
    }

    @Test
    @Order(5)
    void createTableWithBrokenReferenceReturns400() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateTable")
            .body("{"
                    + " \"DatabaseName\": \"" + DATABASE + "\","
                    + " \"TableInput\": {"
                    + "   \"Name\": \"broken-table\","
                    + "   \"StorageDescriptor\": {"
                    + "     \"Location\": \"s3://bucket/x/\","
                    + "     \"SchemaReference\": {"
                    + "       \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"does-not-exist\" }"
                    + "     }"
                    + "   }"
                    + " } }")
        .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    @Order(6)
    void getTablesAppliesResolutionToEachTable() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetTables")
            .body("{ \"DatabaseName\": \"" + DATABASE + "\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("TableList[0].StorageDescriptor.Columns", hasSize(3));
    }

    @Test
    @Order(7)
    void createTableRoundTripsViewFields() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateTable")
            .body("""
                    {
                      "DatabaseName": "%s",
                      "TableInput": {
                        "Name": "view_table",
                        "Owner": "test-owner",
                        "TableType": "VIRTUAL_VIEW",
                        "StorageDescriptor": {
                          "Columns": [{"Name":"x", "Type":"int"}]
                        },
                        "ViewOriginalText": "SELECT 1 AS x",
                        "ViewExpandedText": "SELECT 1 AS x",
                        "Parameters": {"presto_view":"true"}
                      }
                    }
                    """.formatted(DATABASE))
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetTable")
            .body("{ \"DatabaseName\": \"" + DATABASE + "\", \"Name\": \"view_table\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("Table.Name", equalTo("view_table"))
            .body("Table.Owner", equalTo("test-owner"))
            .body("Table.TableType", equalTo("VIRTUAL_VIEW"))
            .body("Table.ViewOriginalText", equalTo("SELECT 1 AS x"))
            .body("Table.ViewExpandedText", equalTo("SELECT 1 AS x"))
            .body("Table.Parameters.presto_view", equalTo("true"));
    }

    @Test
    @Order(8)
    void userDefinedFunctionLifecycle() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateUserDefinedFunction")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "FunctionInput": {
                      "FunctionName": "udf__test__integer",
                      "ClassName": "ExampleFunction",
                      "FunctionType": "REGULAR_FUNCTION",
                      "OwnerName": "owner",
                      "OwnerType": "USER",
                      "ResourceUris": [
                        {
                          "ResourceType": "FILE",
                          "Uri": "s3://bucket/function.json"
                        }
                      ]
                    }
                  }
                  """.formatted(DATABASE))
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateUserDefinedFunction")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "FunctionInput": {
                      "FunctionName": "udf__test__varchar",
                      "ClassName": "ExampleFunction",
                      "FunctionType": "REGULAR_FUNCTION",
                      "OwnerName": "owner",
                      "OwnerType": "USER"
                    }
                  }
                  """.formatted(DATABASE))
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetUserDefinedFunction")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "FunctionName": "udf__test__integer"
                  }
                  """.formatted(DATABASE))
        .when().post("/").then()
            .statusCode(200)
            .body("UserDefinedFunction.FunctionName", equalTo("udf__test__integer"))
            .body("UserDefinedFunction.DatabaseName", equalTo(DATABASE))
            .body("UserDefinedFunction.FunctionType", equalTo("REGULAR_FUNCTION"))
            .body("UserDefinedFunction.OwnerName", equalTo("owner"))
            .body("UserDefinedFunction.CreateTime", notNullValue())
            .body("UserDefinedFunction.ResourceUris[0].Uri", equalTo("s3://bucket/function.json"));

        String nextToken = given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetUserDefinedFunctions")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "Pattern": "udf__test__.*",
                    "FunctionType": "REGULAR_FUNCTION",
                    "MaxResults": 1
                  }
                  """.formatted(DATABASE))
        .when().post("/").then()
            .statusCode(200)
            .body("UserDefinedFunctions", hasSize(1))
            .body("UserDefinedFunctions[0].FunctionName", equalTo("udf__test__integer"))
            .body("NextToken", notNullValue())
            .extract().path("NextToken");

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetUserDefinedFunctions")
            .body("""
                  {
                    "Pattern": "udf__test__.*",
                    "FunctionType": "REGULAR_FUNCTION",
                    "MaxResults": 1,
                    "NextToken": "%s"
                  }
                  """.formatted(nextToken))
        .when().post("/").then()
            .statusCode(200)
            .body("UserDefinedFunctions", hasSize(1))
            .body("UserDefinedFunctions[0].FunctionName", equalTo("udf__test__varchar"))
            .body("NextToken", nullValue());

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetUserDefinedFunctions")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "Pattern": "udf__("
                  }
                  """.formatted(DATABASE))
        .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.UpdateUserDefinedFunction")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "FunctionName": "udf__test__integer",
                    "FunctionInput": {
                      "FunctionName": "udf__test__integer",
                      "ClassName": "ExampleFunction",
                      "FunctionType": "REGULAR_FUNCTION",
                      "OwnerName": "new-owner",
                      "OwnerType": "USER"
                    }
                  }
                  """.formatted(DATABASE))
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetUserDefinedFunction")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "FunctionName": "udf__test__integer"
                  }
                  """.formatted(DATABASE))
        .when().post("/").then()
            .statusCode(200)
            .body("UserDefinedFunction.OwnerName", equalTo("new-owner"));

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.DeleteUserDefinedFunction")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "FunctionName": "udf__test__integer"
                  }
                  """.formatted(DATABASE))
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetUserDefinedFunction")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "FunctionName": "udf__test__integer"
                  }
                  """.formatted(DATABASE))
        .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    @Order(9)
    void deleteDatabaseRemovesCatalogDatabase() {
        String database = "database-to-delete";

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateDatabase")
            .body("{ \"DatabaseInput\": { \"Name\": \"" + database + "\" } }")
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateTable")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "TableInput": {
                      "Name": "table_to_delete",
                      "StorageDescriptor": {
                        "Location": "s3://bucket/delete/"
                      }
                    }
                  }
                  """.formatted(database))
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.DeleteDatabase")
            .body("{ \"Name\": \"" + database + "\" }")
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetDatabases")
            .body("{}")
        .when().post("/").then()
            .statusCode(200)
            .body("DatabaseList.Name", not(hasItem(database)));
    }

    @Test
    @Order(10)
    void updateTableRejectsStaleVersionId() {
        String versionId = given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetTable")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "Name": "%s"
                  }
                  """.formatted(DATABASE, TABLE))
        .when().post().then()
            .statusCode(200)
            .extract().path("Table.VersionId");

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.UpdateTable")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "VersionId": "stale-version",
                    "TableInput": {
                      "Name": "%s",
                      "StorageDescriptor": {
                        "Location": "s3://bucket/users/",
                        "Columns": []
                      },
                      "Parameters": {
                        "metadata_location": "s3://bucket/v2.metadata.json"
                      }
                    }
                  }
                  """.formatted(DATABASE, TABLE))
        .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("ConcurrentModificationException"));

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetTable")
            .body("""
                  {
                    "DatabaseName": "%s",
                    "Name": "%s"
                  }
                  """.formatted(DATABASE, TABLE))
        .when().post("/").then()
            .statusCode(200)
            .body("Table.VersionId", equalTo(versionId));
    }
}
