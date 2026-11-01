package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * KmsKeyId and CloudWatch Logs delivery round-trip through CreateTrail, DescribeTrails and
 * UpdateTrail. Terraform's aws_cloudtrail reads them back on every refresh, so a dropped value is
 * a perpetual diff.
 */
@QuarkusTest
class CloudTrailEncryptionAndLogsIntegrationTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";
    private static final String KEY = "arn:aws:kms:us-east-1:000000000000:key/0f1e2d3c-4b5a-6978-8091-a2b3c4d5e6f7";
    private static final String GROUP = "arn:aws:logs:us-east-1:000000000000:log-group:/trail:*";
    private static final String ROLE = "arn:aws:iam::000000000000:role/trail-to-logs";
    private static final String KEY2 = "arn:aws:kms:us-east-1:000000000000:key/9a8b7c6d-5e4f-3a2b-1c0d-e9f8a7b6c5d4";
    private static final String GROUP2 = "arn:aws:logs:us-east-1:000000000000:log-group:/trail-2:*";
    private static final String ROLE2 = "arn:aws:iam::000000000000:role/trail-to-logs-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given().header("X-Amz-Target", CT_TARGET + action).contentType(JSON11).body(body)
                .when().post("/").then();
    }

    @Test
    void encryptionAndCloudWatchLogsRoundTrip() {
        call("CreateTrail", "{\"Name\":\"enc-trail\",\"S3BucketName\":\"b\",\"KmsKeyId\":\"" + KEY
                + "\",\"CloudWatchLogsLogGroupArn\":\"" + GROUP + "\",\"CloudWatchLogsRoleArn\":\"" + ROLE + "\"}")
                .statusCode(200)
                .body("KmsKeyId", equalTo(KEY))
                .body("CloudWatchLogsLogGroupArn", equalTo(GROUP));

        // Selectors rebuild the stored trail; the settings must survive that.
        call("PutEventSelectors", "{\"TrailName\":\"enc-trail\",\"EventSelectors\":[{\"ReadWriteType\":\"All\","
                + "\"IncludeManagementEvents\":true}]}").statusCode(200);
        call("DescribeTrails", "{\"trailNameList\":[\"enc-trail\"]}")
                .statusCode(200)
                .body("trailList[0].KmsKeyId", equalTo(KEY))
                .body("trailList[0].CloudWatchLogsLogGroupArn", equalTo(GROUP))
                .body("trailList[0].CloudWatchLogsRoleArn", equalTo(ROLE));

        call("UpdateTrail", "{\"Name\":\"enc-trail\",\"KmsKeyId\":\"\"}")
                .statusCode(200)
                .body("KmsKeyId", nullValue())
                .body("CloudWatchLogsRoleArn", equalTo(ROLE));
    }

    @Test
    void cloudWatchLogsNeedsBothTheGroupAndTheRole() {
        call("CreateTrail", "{\"Name\":\"half-trail\",\"S3BucketName\":\"b\",\"CloudWatchLogsLogGroupArn\":\""
                + GROUP + "\"}")
                .statusCode(400)
                .body(containsString("InvalidCloudWatchLogsRoleArnException"));
    }

    private static ValidatableResponse describe(String name) {
        return call("DescribeTrails", "{\"trailNameList\":[\"" + name + "\"]}").statusCode(200);
    }

    private static void createWithEverything(String name) {
        call("CreateTrail", "{\"Name\":\"" + name + "\",\"S3BucketName\":\"b\",\"KmsKeyId\":\"" + KEY
                + "\",\"CloudWatchLogsLogGroupArn\":\"" + GROUP + "\",\"CloudWatchLogsRoleArn\":\"" + ROLE + "\"}")
                .statusCode(200);
    }

    @Test
    void updateTrailSetsThenReplacesTheKmsKey() {
        call("CreateTrail", "{\"Name\":\"kms-upd\",\"S3BucketName\":\"b\"}").statusCode(200);

        call("UpdateTrail", "{\"Name\":\"kms-upd\",\"KmsKeyId\":\"" + KEY + "\"}").statusCode(200);
        describe("kms-upd").body("trailList[0].KmsKeyId", equalTo(KEY));

        call("UpdateTrail", "{\"Name\":\"kms-upd\",\"KmsKeyId\":\"" + KEY2 + "\"}").statusCode(200);
        describe("kms-upd").body("trailList[0].KmsKeyId", equalTo(KEY2));
    }

    @Test
    void updateTrailSetsThenReplacesCloudWatchLogsDelivery() {
        call("CreateTrail", "{\"Name\":\"cwl-upd\",\"S3BucketName\":\"b\"}").statusCode(200);

        call("UpdateTrail", "{\"Name\":\"cwl-upd\",\"CloudWatchLogsLogGroupArn\":\"" + GROUP
                + "\",\"CloudWatchLogsRoleArn\":\"" + ROLE + "\"}").statusCode(200);
        describe("cwl-upd")
                .body("trailList[0].CloudWatchLogsLogGroupArn", equalTo(GROUP))
                .body("trailList[0].CloudWatchLogsRoleArn", equalTo(ROLE));

        call("UpdateTrail", "{\"Name\":\"cwl-upd\",\"CloudWatchLogsLogGroupArn\":\"" + GROUP2
                + "\",\"CloudWatchLogsRoleArn\":\"" + ROLE2 + "\"}").statusCode(200);
        describe("cwl-upd")
                .body("trailList[0].CloudWatchLogsLogGroupArn", equalTo(GROUP2))
                .body("trailList[0].CloudWatchLogsRoleArn", equalTo(ROLE2));
    }

    @Test
    void updateTrailClearsBothCloudWatchLogsSettings() {
        createWithEverything("cwl-clear");

        call("UpdateTrail", "{\"Name\":\"cwl-clear\",\"CloudWatchLogsLogGroupArn\":\"\","
                + "\"CloudWatchLogsRoleArn\":\"\"}").statusCode(200);
        describe("cwl-clear")
                .body("trailList[0].CloudWatchLogsLogGroupArn", nullValue())
                .body("trailList[0].CloudWatchLogsRoleArn", nullValue())
                .body("trailList[0].KmsKeyId", equalTo(KEY));
    }

    @Test
    void updateTrailRefusesAHalfClearedPairAndKeepsTheTrail() {
        createWithEverything("cwl-half");

        call("UpdateTrail", "{\"Name\":\"cwl-half\",\"CloudWatchLogsLogGroupArn\":\"\","
                + "\"CloudWatchLogsRoleArn\":\"" + ROLE2 + "\",\"KmsKeyId\":\"" + KEY2 + "\"}")
                .statusCode(400)
                .body(containsString("InvalidCloudWatchLogsLogGroupArnException"));
        call("UpdateTrail", "{\"Name\":\"cwl-half\",\"CloudWatchLogsLogGroupArn\":\"" + GROUP2
                + "\",\"CloudWatchLogsRoleArn\":\"\",\"KmsKeyId\":\"" + KEY2 + "\"}")
                .statusCode(400)
                .body(containsString("InvalidCloudWatchLogsRoleArnException"));

        describe("cwl-half")
                .body("trailList[0].KmsKeyId", equalTo(KEY))
                .body("trailList[0].CloudWatchLogsLogGroupArn", equalTo(GROUP))
                .body("trailList[0].CloudWatchLogsRoleArn", equalTo(ROLE));
    }
}
