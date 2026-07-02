package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
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

    @Test
    void lookupEventsReturnsEmptyEventPage() {
        given()
                .header("X-Amz-Target", TARGET_PREFIX + "LookupEvents")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "LookupAttributes": [
                                {"AttributeKey": "EventName", "AttributeValue": "CreateBucket"}
                            ],
                            "MaxResults": 10
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Events", hasSize(0));
    }

    @Test
    void trailProvisioningLifecycleWorksWithBucketNotificationsAndQueuePolicy() {
        String bucket = "cloudtrail-audit-feed-bucket";
        String bucket2 = "cloudtrail-audit-feed-bucket-2";
        String queueName = "cloudtrail-audit-feed-events";
        String queueUrl = createQueue(queueName);
        String queueArn = getQueueArn(queueUrl);
        String bucketArn = "arn:aws:s3:::" + bucket;
        String trailName = "cloudtrail-audit-feed";

        createBucket(bucket);
        putBucketPolicy(bucket, trailName);
        putBucketNotification(bucket, queueArn);
        setQueuePolicy(queueUrl, bucketArn, queueArn);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "CreateTrail")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "%s",
                            "S3BucketName": "%s",
                            "IncludeGlobalServiceEvents": true,
                            "IsMultiRegionTrail": false,
                            "IsOrganizationTrail": false,
                            "TagsList": [
                                {"Key": "example:component", "Value": "cloudAudit"}
                            ]
                        }
                        """.formatted(trailName, bucket))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Name", equalTo(trailName))
                .body("TrailARN", startsWith("arn:aws:cloudtrail:"))
                .body("S3BucketName", equalTo(bucket));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "PutEventSelectors")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "TrailName": "%s",
                            "AdvancedEventSelectors": [
                                {
                                    "Name": "Management events",
                                    "FieldSelectors": [
                                        {"Field": "eventCategory", "Equals": ["Management"]}
                                    ]
                                }
                            ]
                        }
                        """.formatted(trailName))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "StartLogging")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "%s"
                        }
                        """.formatted(trailName))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetTrailStatus")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "%s"
                        }
                        """.formatted(trailName))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("IsLogging", equalTo(true));

        given()
        .when()
                .get("/" + bucket + "?notification")
        .then()
                .statusCode(200)
                .body(containsString("<Queue>" + queueArn + "</Queue>"))
                .body(containsString("<Event>s3:ObjectCreated:*</Event>"));

        getQueuePolicy(queueUrl)
                .statusCode(200)
                .body(containsString(bucketArn));

        createBucket(bucket2);
        given()
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateTrail")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "%s",
                            "S3BucketName": "%s",
                            "IncludeGlobalServiceEvents": true,
                            "IsMultiRegionTrail": false
                        }
                        """.formatted(trailName, bucket2))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("S3BucketName", equalTo(bucket2));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DescribeTrails")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "trailNameList": ["%s"]
                        }
                        """.formatted(trailName))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("trailList", hasSize(1))
                .body("trailList[0].S3BucketName", equalTo(bucket2));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "StopLogging")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "%s"
                        }
                        """.formatted(trailName))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteTrail")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                            "Name": "%s"
                        }
                        """.formatted(trailName))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void createBucket(String bucket) {
        given()
        .when()
                .put("/" + bucket)
        .then()
                .statusCode(200);
    }

    private static String createQueue(String queueName) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", queueName)
                .formParam("Attribute.1.Name", "VisibilityTimeout")
                .formParam("Attribute.1.Value", "30")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private static String getQueueArn(String queueUrl) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueAttributes")
                .formParam("QueueUrl", queueUrl)
                .formParam("AttributeName.1", "QueueArn")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().xmlPath().getString("GetQueueAttributesResponse.GetQueueAttributesResult.Attribute.Value");
    }

    private static io.restassured.response.ValidatableResponse getQueuePolicy(String queueUrl) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueAttributes")
                .formParam("QueueUrl", queueUrl)
                .formParam("AttributeName.1", "Policy")
        .when()
                .post("/")
        .then();
    }

    private static void setQueuePolicy(String queueUrl, String bucketArn, String queueArn) {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SetQueueAttributes")
                .formParam("QueueUrl", queueUrl)
                .formParam("Attribute.1.Name", "Policy")
                .formParam("Attribute.1.Value", """
                        {"Version":"2012-10-17","Statement":[{"Sid":"AllowS3Notifications","Effect":"Allow","Principal":{"Service":"s3.amazonaws.com"},"Action":"sqs:SendMessage","Resource":"%s","Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}
                        """.formatted(queueArn, bucketArn))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putBucketPolicy(String bucket, String trailName) {
        given()
                .contentType("application/json")
                .body("""
                        {"Version":"2012-10-17","Statement":[{"Sid":"AllowCloudTrail","Effect":"Allow","Principal":{"Service":"cloudtrail.amazonaws.com"},"Action":"s3:PutObject","Resource":"arn:aws:s3:::%s/AWSLogs/*","Condition":{"StringEquals":{"aws:SourceArn":"arn:aws:cloudtrail:us-east-1:000000000000:trail/%s"}}}]}
                        """.formatted(bucket, trailName))
        .when()
                .put("/" + bucket + "?policy")
        .then()
                .statusCode(200);
    }

    private static void putBucketNotification(String bucket, String queueArn) {
        given()
                .contentType("application/xml")
                .body("""
                        <NotificationConfiguration>
                            <QueueConfiguration>
                                <Id>cloudtrail-to-sqs</Id>
                                <Queue>%s</Queue>
                                <Event>s3:ObjectCreated:*</Event>
                            </QueueConfiguration>
                        </NotificationConfiguration>
                        """.formatted(queueArn))
        .when()
                .put("/" + bucket + "?notification")
        .then()
                .statusCode(200);
    }
}
