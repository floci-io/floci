package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * github.com/floci-io/floci/issues/2791: terraform-provider-aws's read/refresh for
 * aws_athena_workgroup calls ListTagsForResource unconditionally, even with no tags set.
 */
@QuarkusTest
class AthenaListTagsForResourceIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listTagsForResourceReturnsTagsSetOnCreate() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "analytics-tagged",
                  "Tags": [
                    { "Key": "env", "Value": "prod" },
                    { "Key": "team", "Value": "data" }
                  ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "ResourceARN": "arn:aws:athena:us-east-1:000000000000:workgroup/analytics-tagged"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.size()", equalTo(2))
            .body("Tags.Key", contains("env", "team"))
            .body("Tags.Value", contains("prod", "data"));
    }

    @Test
    void listTagsForResourceOnUntaggedWorkGroupReturnsEmptyList() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "analytics-untagged"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "ResourceARN": "arn:aws:athena:us-east-1:000000000000:workgroup/analytics-untagged"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.size()", equalTo(0));
    }

    @Test
    void listTagsForResourceOnPrimaryWorkGroupReturnsEmptyList() {
        // CreateWorkGroup rejects "primary", so it can never carry real tags - matches a live account.
        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "ResourceARN": "arn:aws:athena:us-east-1:000000000000:workgroup/primary"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.size()", equalTo(0));
    }

    @Test
    void listTagsForResourceOnMissingWorkGroupReturnsInvalidRequestException() {
        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "ResourceARN": "arn:aws:athena:us-east-1:000000000000:workgroup/does-not-exist"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void listTagsForResourceRejectsUnsupportedResourceType() {
        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "ResourceARN": "arn:aws:athena:us-east-1:000000000000:datacatalog/AwsDataCatalog"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void listTagsForResourceRejectsAnArnFromADifferentService() {
        // Create a real workgroup so an unguarded service check would still resolve this: an
        // ARN naming a different service (a Neptune workgroup/-shaped resource, say) but the
        // same region and workgroup name must be rejected, not resolved to this workgroup's tags.
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "cross-service-workgroup"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "ResourceARN": "arn:aws:neptune:us-east-1:000000000000:workgroup/cross-service-workgroup"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void listTagsForResourceRejectsBlankResourceArn() {
        given()
            .header("X-Amz-Target", "AmazonAthena.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
}
