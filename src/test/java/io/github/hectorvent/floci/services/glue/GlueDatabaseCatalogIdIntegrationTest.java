package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * terraform-provider-aws builds the aws_glue_catalog_database resource id as
 * {@code catalog-id:database-name}, so an absent CatalogId yields a malformed id.
 */
@QuarkusTest
class GlueDatabaseCatalogIdIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String DATABASE_NAME = "catalog-id-db-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDatabaseAndGetDatabasesReturnCatalogId() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateDatabase")
                .body("""
                        {
                          "DatabaseInput": {
                            "Name": "%s"
                          }
                        }
                        """.formatted(DATABASE_NAME))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetDatabase")
                .body("""
                        {
                          "Name": "%s"
                        }
                        """.formatted(DATABASE_NAME))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Database.Name", equalTo(DATABASE_NAME))
                .body("Database.CatalogId", equalTo(ACCOUNT_ID));

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetDatabases")
                .body("{}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("DatabaseList.find { it.Name == '%s' }.CatalogId".formatted(DATABASE_NAME),
                        equalTo(ACCOUNT_ID));
    }
}