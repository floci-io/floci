package io.floci.aws.services.sqs;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Queue URL round trips shared by the endpoint strategy tests: every call that returns a queue URL
 * returns {@code expectedUrl}, and the queue resolves from that URL, from {@code otherForm}, from its
 * bare name and from a request posted to its path.
 */
final class SqsQueueUrlRoundTrip {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String JSON_CONTENT_TYPE = "application/x-amz-json-1.0";

    private SqsQueueUrlRoundTrip() {
    }

    /** Query protocol, unsigned, so the queue lives in us-east-1. */
    static void query(String queueName, String expectedUrl, String otherForm) {
        String queueArn = "arn:aws:sqs:us-east-1:" + ACCOUNT_ID + ":" + queueName;

        String createdUrl = given()
            .contentType(FORM_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", queueName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        try {
            assertEquals(expectedUrl, createdUrl);

            given()
                .contentType(FORM_CONTENT_TYPE)
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", queueName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("GetQueueUrlResponse.GetQueueUrlResult.QueueUrl", equalTo(expectedUrl));

            given()
                .contentType(FORM_CONTENT_TYPE)
                .formParam("Action", "ListQueues")
                .formParam("QueueNamePrefix", queueName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<QueueUrl>" + expectedUrl + "</QueueUrl>"));

            for (String input : List.of(expectedUrl, otherForm, queueName)) {
                given()
                    .contentType(FORM_CONTENT_TYPE)
                    .formParam("Action", "GetQueueAttributes")
                    .formParam("QueueUrl", input)
                    .formParam("AttributeName.1", "QueueArn")
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .body(containsString(queueArn));
            }

            given()
                .contentType(FORM_CONTENT_TYPE)
                .formParam("Action", "GetQueueAttributes")
                .formParam("AttributeName.1", "QueueArn")
            .when()
                .post("/" + ACCOUNT_ID + "/" + queueName)
            .then()
                .statusCode(200)
                .body(containsString(queueArn));
        } finally {
            given()
                .contentType(FORM_CONTENT_TYPE)
                .formParam("Action", "DeleteQueue")
                .formParam("QueueUrl", expectedUrl)
            .when()
                .post("/");
        }
    }

    /** JSON protocol, signed with {@code auth} for {@code region}. */
    static void json(String auth, String region, String queueName, String expectedUrl, String otherForm) {
        String queueArn = "arn:aws:sqs:" + region + ":" + ACCOUNT_ID + ":" + queueName;

        given()
            .contentType(JSON_CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .header("Authorization", auth)
            .body("{\"QueueName\":\"" + queueName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueueUrl", equalTo(expectedUrl));

        try {
            given()
                .contentType(JSON_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.GetQueueUrl")
                .header("Authorization", auth)
                .body("{\"QueueName\":\"" + queueName + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("QueueUrl", equalTo(expectedUrl));

            given()
                .contentType(JSON_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.ListQueues")
                .header("Authorization", auth)
                .body("{\"QueueNamePrefix\":\"" + queueName + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("QueueUrls", contains(expectedUrl));

            for (String input : List.of(expectedUrl, otherForm, queueName)) {
                given()
                    .contentType(JSON_CONTENT_TYPE)
                    .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                    .header("Authorization", auth)
                    .body("{\"QueueUrl\":\"" + input + "\",\"AttributeNames\":[\"QueueArn\"]}")
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .body("Attributes.QueueArn", equalTo(queueArn));
            }

            given()
                .contentType(JSON_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .header("Authorization", auth)
                .body("{\"QueueUrl\":\"" + expectedUrl + "\",\"AttributeNames\":[\"QueueArn\"]}")
            .when()
                .post("/" + ACCOUNT_ID + "/" + queueName)
            .then()
                .statusCode(200)
                .body("Attributes.QueueArn", equalTo(queueArn));
        } finally {
            given()
                .contentType(JSON_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
                .header("Authorization", auth)
                .body("{\"QueueUrl\":\"" + expectedUrl + "\"}")
            .when()
                .post("/");
        }
    }
}
