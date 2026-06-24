package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

@QuarkusTest
class GlueDatabaseTaggingIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String DATABASE_NAME = "tagged-db-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String DATABASE_ARN = "arn:aws:glue:us-east-1:000000000000:database/" + DATABASE_NAME;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createDatabaseWithTags_tagsReturnedByResourceGroupsTaggingApi() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateDatabase")
                .body("""
                        {
                          "DatabaseInput": {
                            "Name": "%s",
                            "Description": "catalog db with tags"
                          },
                          "Tags": {
                            "Environment": "dev",
                            "Project": "project1"
                          }
                        }
                        """.formatted(DATABASE_NAME))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ResourceGroupsTaggingAPI_20170126.GetResources")
                .body("""
                        {
                          "ResourceARNList": ["%s"]
                        }
                        """.formatted(DATABASE_ARN))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ResourceTagMappingList.size()", equalTo(1))
                .body("ResourceTagMappingList[0].ResourceARN", equalTo(DATABASE_ARN))
                .body("ResourceTagMappingList[0].Tags.size()", equalTo(2))
                .body("ResourceTagMappingList[0].Tags.Key", hasItems("Environment", "Project"))
                .body("ResourceTagMappingList[0].Tags.Value", hasItems("dev", "project1"));
    }

    @Test
    void deleteDatabase_removesTagsFromResourceGroupsTaggingApi() {
        String databaseName = "tagged-db-delete-" + UUID.randomUUID().toString().substring(0, 8);
        String databaseArn = "arn:aws:glue:us-east-1:000000000000:database/" + databaseName;

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateDatabase")
                .body("""
                        {
                          "DatabaseInput": {
                            "Name": "%s"
                          },
                          "Tags": {
                            "Environment": "dev"
                          }
                        }
                        """.formatted(databaseName))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.DeleteDatabase")
                .body("""
                        {
                          "Name": "%s"
                        }
                        """.formatted(databaseName))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ResourceGroupsTaggingAPI_20170126.GetResources")
                .body("""
                        {
                          "ResourceARNList": ["%s"]
                        }
                        """.formatted(databaseArn))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ResourceTagMappingList.size()", equalTo(0));
    }
}
