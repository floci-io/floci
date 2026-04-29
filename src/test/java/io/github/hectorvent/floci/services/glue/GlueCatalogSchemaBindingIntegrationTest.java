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
import static org.hamcrest.Matchers.notNullValue;

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
}
