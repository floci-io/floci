package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class CloudTrailIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void trailLifecycleRoundTripsThroughJsonHandler() {
        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DescribeTrails")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "trailNameList": ["sample-audit"]
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("trailList", hasSize(0));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "CreateTrail")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "sample-audit",
                            "S3BucketName": "sample-audit-bucket",
                            "IncludeGlobalServiceEvents": true,
                            "IsMultiRegionTrail": true,
                            "IsOrganizationTrail": false,
                            "TagsList": [
                                {"Key": "example:component", "Value": "audit"}
                            ]
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Name", equalTo("sample-audit"))
                .body("TrailARN", startsWith("arn:aws:cloudtrail:"))
                .body("S3BucketName", equalTo("sample-audit-bucket"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "PutEventSelectors")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "TrailName": "sample-audit",
                            "AdvancedEventSelectors": []
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "StartLogging")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "sample-audit"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetTrailStatus")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "sample-audit"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("IsLogging", equalTo(true))
                .body("LatestDeliveryTime", notNullValue());

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateTrail")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "sample-audit",
                            "S3BucketName": "sample-audit-bucket-2"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("S3BucketName", equalTo("sample-audit-bucket-2"))
                .body("IncludeGlobalServiceEvents", equalTo(true))
                .body("IsMultiRegionTrail", equalTo(true));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DescribeTrails")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "trailNameList": ["sample-audit"]
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("trailList", hasSize(1))
                .body("trailList[0].Name", equalTo("sample-audit"))
                .body("trailList[0].S3BucketName", equalTo("sample-audit-bucket-2"))
                .body("trailList[0].IncludeGlobalServiceEvents", equalTo(true))
                .body("trailList[0].IsMultiRegionTrail", equalTo(true));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "StopLogging")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "sample-audit"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteTrail")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "sample-audit"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }
}
