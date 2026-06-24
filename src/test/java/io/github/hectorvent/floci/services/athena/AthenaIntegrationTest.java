package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AthenaIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    private static String queryExecutionId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void startQueryExecution() {
        String response = given()
            .header("X-Amz-Target", "AmazonAthena.StartQueryExecution")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "QueryString": "SELECT 1",
                  "WorkGroup": "primary"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueryExecutionId", notNullValue())
            .extract().path("QueryExecutionId");

        queryExecutionId = response;
    }

    @Test
    @Order(2)
    void getQueryExecution() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetQueryExecution")
            .contentType(CONTENT_TYPE)
            .body("{ \"QueryExecutionId\": \"" + queryExecutionId + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueryExecution.QueryExecutionId", equalTo(queryExecutionId))
            .body("QueryExecution.Status.State", equalTo("SUCCEEDED"))
            .body("QueryExecution.StatementType", equalTo("DML"))
            .body("QueryExecution.EngineVersion.EffectiveEngineVersion", equalTo("Athena engine version 3"))
            .body("QueryExecution.Statistics.DataScannedInBytes", equalTo(0));
    }

    @Test
    @Order(3)
    void getQueryResults() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetQueryResults")
            .contentType(CONTENT_TYPE)
            .body("{ \"QueryExecutionId\": \"" + queryExecutionId + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultSet", notNullValue());
    }

    @Test
    @Order(4)
    void listQueryExecutions() {
        given()
            .header("X-Amz-Target", "AmazonAthena.ListQueryExecutions")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueryExecutionIds", notNullValue())
            .body("QueryExecutionIds", hasItem(queryExecutionId));
    }

    @Test
    @Order(5)
    void getQueryExecutionNotFound() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetQueryExecution")
            .contentType(CONTENT_TYPE)
            .body("{ \"QueryExecutionId\": \"nonexistent-id\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(6)
    void supportsWorkGroupAndCatalogMetadataActions() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"primary\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroup.Name", equalTo("primary"))
            .body("WorkGroup.Configuration.EngineVersion.EffectiveEngineVersion", equalTo("Athena engine version 3"));

        given()
            .header("X-Amz-Target", "AmazonAthena.ListDataCatalogs")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataCatalogsSummary[0].CatalogName", equalTo("AwsDataCatalog"))
            .body("DataCatalogsSummary[0].Type", equalTo("GLUE"));
    }

    @Test
    @Order(7)
    void listsGlueDatabasesAndTablesAsAthenaMetadata() {
        given()
            .header("X-Amz-Target", "AWSGlue.CreateDatabase")
            .contentType(CONTENT_TYPE)
            .body("{ \"DatabaseInput\": { \"Name\": \"analytics_test\" } }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AWSGlue.CreateTable")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "DatabaseName": "analytics_test",
                  "TableInput": {
                    "Name": "orders",
                    "TableType": "EXTERNAL_TABLE",
                    "StorageDescriptor": {
                      "Location": "s3://bucket/orders",
                      "Columns": [
                        { "Name": "id", "Type": "string" },
                        { "Name": "payload", "Type": "struct<id:string>" }
                      ]
                    }
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.ListDatabases")
            .contentType(CONTENT_TYPE)
            .body("{ \"CatalogName\": \"AwsDataCatalog\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DatabaseList.Name", hasItem("analytics_test"));

        given()
            .header("X-Amz-Target", "AmazonAthena.GetTableMetadata")
            .contentType(CONTENT_TYPE)
            .body("{ \"CatalogName\": \"AwsDataCatalog\", \"DatabaseName\": \"analytics_test\", \"TableName\": \"orders\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableMetadata.Name", equalTo("orders"))
            .body("TableMetadata.Columns[0].Name", equalTo("id"))
            .body("TableMetadata.Columns[0].Type", equalTo("varchar"))
            .body("TableMetadata.Columns[1].Type", equalTo("varchar"))
            .body("TableMetadata.PartitionKeys", empty());
    }

    @Test
    @Order(8)
    void listsGlueDatabasesAndTablesAsAthenaMetadataHasPartitionKeys() {
        given()
                .header("X-Amz-Target", "AWSGlue.CreateDatabase")
                .contentType(CONTENT_TYPE)
                .body("{ \"DatabaseInput\": { \"Name\": \"analytics_test2\" } }")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "AWSGlue.CreateTable")
                .contentType(CONTENT_TYPE)
                .body("""
                {
                  "DatabaseName": "analytics_test2",
                  "TableInput": {
                    "Name": "orders",
                    "TableType": "EXTERNAL_TABLE",
                    "StorageDescriptor": {
                      "Location": "s3://bucket/orders",
                      "Columns": [
                        { "Name": "id", "Type": "string" },
                        { "Name": "payload", "Type": "struct<id:string>" }
                      ]
                    },
                    "PartitionKeys": [
                      { "Name": "pkey", "Type": "string" }
                    ]
                  }
                }
                """)
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "AmazonAthena.ListDatabases")
                .contentType(CONTENT_TYPE)
                .body("{ \"CatalogName\": \"AwsDataCatalog\" }")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("DatabaseList.Name", hasItem("analytics_test2"));

        given()
                .header("X-Amz-Target", "AmazonAthena.GetTableMetadata")
                .contentType(CONTENT_TYPE)
                .body("{ \"CatalogName\": \"AwsDataCatalog\", \"DatabaseName\": \"analytics_test2\", \"TableName\": \"orders\" }")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("TableMetadata.Name", equalTo("orders"))
                .body("TableMetadata.Columns[0].Name", equalTo("id"))
                .body("TableMetadata.Columns[0].Type", equalTo("varchar"))
                .body("TableMetadata.Columns[1].Type", equalTo("varchar"))
                .body("TableMetadata.PartitionKeys[0].Name", equalTo("pkey"))
                .body("TableMetadata.PartitionKeys[0].Type", equalTo("varchar"));
    }

    @Test
    @Order(9)
    void deleteWorkGroup() {
        given()
            .header("X-Amz-Target", "AmazonAthena.DeleteWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"athena-workgroup-sample\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(equalTo("{}"));
    }

    @Test
    @Order(10)
    void deleteWorkGroupMissingOrBlank() {
        given()
            .header("X-Amz-Target", "AmazonAthena.DeleteWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("WorkGroup is required."));

        given()
            .header("X-Amz-Target", "AmazonAthena.DeleteWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"  \" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("WorkGroup is required."));

        given()
            .header("X-Amz-Target", "AmazonAthena.DeleteWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"bad name\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("WorkGroup is required."));

        given()
            .header("X-Amz-Target", "AmazonAthena.DeleteWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"" + "a".repeat(129) + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("WorkGroup is required."));
    }

    @Test
    @Order(11)
    void deletePrimaryWorkGroup() {
        given()
            .header("X-Amz-Target", "AmazonAthena.DeleteWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"WorkGroup\": \"primary\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("The primary workgroup cannot be deleted."));
    }
}
