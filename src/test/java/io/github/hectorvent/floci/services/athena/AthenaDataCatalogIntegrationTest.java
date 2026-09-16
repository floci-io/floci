package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Data catalog lifecycle behind terraform-provider-aws's aws_athena_data_catalog resource:
 * CreateDataCatalog, GetDataCatalog, ListDataCatalogs, UpdateDataCatalog, DeleteDataCatalog
 * and the tag operations on a datacatalog ARN.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AthenaDataCatalogIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String CATALOG = "floci-lambda-catalog";
    private static final String FEDERATED_CATALOG = "floci-federated-catalog";
    private static final String CATALOG_ARN = "arn:aws:athena:us-east-1:000000000000:datacatalog/" + CATALOG;
    private static final String WORKGROUP = "floci-catalog-workgroup";
    private static final String WORKGROUP_ARN = "arn:aws:athena:us-east-1:000000000000:workgroup/" + WORKGROUP;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createDataCatalogEchoesTypeParametersAndTerminalStatus() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "%s",
                  "Type": "LAMBDA",
                  "Description": "Lambda-backed federation",
                  "Parameters": {
                    "metadata-function": "arn:aws:lambda:us-east-1:000000000000:function:meta",
                    "record-function": "arn:aws:lambda:us-east-1:000000000000:function:record"
                  },
                  "Tags": [ { "Key": "Project", "Value": "Floci" } ]
                }
                """.formatted(CATALOG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataCatalog.Name", equalTo(CATALOG))
            .body("DataCatalog.Type", equalTo("LAMBDA"))
            .body("DataCatalog.Status", equalTo("CREATE_COMPLETE"))
            .body("DataCatalog.Description", equalTo("Lambda-backed federation"))
            .body("DataCatalog.Parameters.'metadata-function'",
                    equalTo("arn:aws:lambda:us-east-1:000000000000:function:meta"));
    }

    @Test
    @Order(2)
    void createDataCatalogRejectsADuplicateName() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"" + CATALOG + "\", \"Type\": \"LAMBDA\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(3)
    void getDataCatalogSeesTheCreatedCatalog() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"" + CATALOG + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataCatalog.Name", equalTo(CATALOG))
            .body("DataCatalog.Type", equalTo("LAMBDA"))
            .body("DataCatalog.Parameters.'record-function'",
                    equalTo("arn:aws:lambda:us-east-1:000000000000:function:record"));
    }

    @Test
    @Order(4)
    void listDataCatalogsKeepsTheDefaultFirstAndIncludesTheCreatedCatalog() {
        given()
            .header("X-Amz-Target", "AmazonAthena.ListDataCatalogs")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataCatalogsSummary[0].CatalogName", equalTo("AwsDataCatalog"))
            .body("DataCatalogsSummary[0].Type", equalTo("GLUE"))
            .body("DataCatalogsSummary.CatalogName", hasItem(CATALOG))
            .body("DataCatalogsSummary.Type", hasItem("LAMBDA"));
    }

    @Test
    @Order(5)
    void getDataCatalogStillServesTheBuiltInGlueCatalog() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"AwsDataCatalog\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataCatalog.Name", equalTo("AwsDataCatalog"))
            .body("DataCatalog.Type", equalTo("GLUE"));
    }

    @Test
    @Order(6)
    void getDataCatalogAnswersInvalidRequestExceptionForAnAbsentCatalog() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"floci-absent-catalog\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(7)
    void createTimeTagsAreVisibleThroughListTagsForResource() {
        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"" + CATALOG_ARN + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags[0].Key", equalTo("Project"))
            .body("Tags[0].Value", equalTo("Floci"));
    }

    @Test
    @Order(8)
    void tagResourceAndUntagResourceRoundTripOnACatalogArn() {
        given()
            .header("X-Amz-Target", "AmazonAthena.TagResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"" + CATALOG_ARN + "\", "
                    + "\"Tags\": [ { \"Key\": \"Environment\", \"Value\": \"test\" } ] }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"" + CATALOG_ARN + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.Key", hasItem("Environment"))
            .body("Tags.Key", hasItem("Project"));

        given()
            .header("X-Amz-Target", "AmazonAthena.UntagResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"" + CATALOG_ARN + "\", \"TagKeys\": [ \"Environment\" ] }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"" + CATALOG_ARN + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.Key", not(hasItem("Environment")))
            .body("Tags.Key", hasItem("Project"));
    }

    @Test
    @Order(9)
    void workGroupTagsAreReachableThroughTheSameTagOperations() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"" + WORKGROUP + "\", "
                    + "\"Tags\": [ { \"Key\": \"Team\", \"Value\": \"Data\" } ] }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.TagResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"" + WORKGROUP_ARN + "\", "
                    + "\"Tags\": [ { \"Key\": \"Owner\", \"Value\": \"Analytics\" } ] }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"" + WORKGROUP_ARN + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.Key", hasItem("Team"))
            .body("Tags.Key", hasItem("Owner"));

        given()
            .header("X-Amz-Target", "AmazonAthena.UntagResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"" + WORKGROUP_ARN + "\", \"TagKeys\": [ \"Owner\" ] }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"" + WORKGROUP_ARN + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.Key", hasItem("Team"))
            .body("Tags.Key", not(hasItem("Owner")));
    }

    @Test
    @Order(10)
    void updateDataCatalogReplacesTypeDescriptionAndParameters() {
        given()
            .header("X-Amz-Target", "AmazonAthena.UpdateDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "%s",
                  "Type": "HIVE",
                  "Description": "Hive metastore",
                  "Parameters": { "metadata-function": "arn:aws:lambda:us-east-1:000000000000:function:hive" }
                }
                """.formatted(CATALOG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(equalTo("{}"));

        given()
            .header("X-Amz-Target", "AmazonAthena.GetDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"" + CATALOG + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataCatalog.Type", equalTo("HIVE"))
            .body("DataCatalog.Description", equalTo("Hive metastore"))
            .body("DataCatalog.Parameters.'metadata-function'",
                    equalTo("arn:aws:lambda:us-east-1:000000000000:function:hive"))
            .body("DataCatalog.Parameters.'record-function'", nullValue());
    }

    @Test
    @Order(11)
    void updateDataCatalogLeavesTagsAlone() {
        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"" + CATALOG_ARN + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.Key", hasItem("Project"));
    }

    @Test
    @Order(12)
    void federatedCatalogReportsItsConnectionType() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "%s",
                  "Type": "FEDERATED",
                  "Parameters": {
                    "connection-type": "MYSQL",
                    "connection-arn": "arn:aws:glue:us-east-1:000000000000:connection/mysql"
                  }
                }
                """.formatted(FEDERATED_CATALOG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataCatalog.Type", equalTo("FEDERATED"))
            .body("DataCatalog.ConnectionType", equalTo("MYSQL"))
            .body("DataCatalog.Status", equalTo("CREATE_COMPLETE"));

        given()
            .header("X-Amz-Target", "AmazonAthena.DeleteDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"" + FEDERATED_CATALOG + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(13)
    void theBuiltInCatalogIsReservedAgainstCreateUpdateDeleteAndTagging() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"AwsDataCatalog\", \"Type\": \"GLUE\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .header("X-Amz-Target", "AmazonAthena.UpdateDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"AwsDataCatalog\", \"Type\": \"GLUE\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .header("X-Amz-Target", "AmazonAthena.DeleteDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"AwsDataCatalog\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .header("X-Amz-Target", "AmazonAthena.TagResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"arn:aws:athena:us-east-1:000000000000:datacatalog/AwsDataCatalog\", "
                    + "\"Tags\": [ { \"Key\": \"Environment\", \"Value\": \"test\" } ] }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(14)
    void listTagsForResourceOnTheBuiltInCatalogReturnsAnEmptyList() {
        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"arn:aws:athena:us-east-1:000000000000:datacatalog/AwsDataCatalog\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.size()", equalTo(0));
    }

    @Test
    @Order(15)
    void createDataCatalogRejectsAnUnmodelledType() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"floci-bad-type\", \"Type\": \"MONGODB\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(16)
    void tagOperationsRejectACatalogArnWithNoBackingResource() {
        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"arn:aws:athena:us-east-1:000000000000:datacatalog/absent\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .header("X-Amz-Target", "AmazonAthena.TagResource")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceARN\": \"arn:aws:athena:us-east-1:000000000000:datacatalog/absent\", "
                    + "\"Tags\": [ { \"Key\": \"Environment\", \"Value\": \"test\" } ] }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(17)
    void deleteDataCatalogReturnsTheCatalogAndRemovesIt() {
        given()
            .header("X-Amz-Target", "AmazonAthena.DeleteDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"" + CATALOG + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataCatalog.Name", equalTo(CATALOG))
            .body("DataCatalog.Type", equalTo("HIVE"));

        given()
            .header("X-Amz-Target", "AmazonAthena.GetDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"" + CATALOG + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .header("X-Amz-Target", "AmazonAthena.ListDataCatalogs")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataCatalogsSummary.CatalogName", not(hasItem(CATALOG)));
    }

    @Test
    @Order(18)
    void deleteDataCatalogAnswersInvalidRequestExceptionForAnAbsentCatalog() {
        given()
            .header("X-Amz-Target", "AmazonAthena.DeleteDataCatalog")
            .contentType(CONTENT_TYPE)
            .body("{ \"Name\": \"" + CATALOG + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
}
