package io.floci.aws.services.pipes;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
class PipesSqsTargetIntegrationTest {

    private static final String SQS_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String ROLE = "arn:aws:iam::000000000000:role/pipe-role";

    @Test
    void fifoTargetReceivesTheGroupAndDeduplicationIdsReadOffTheEvent() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceName = "pipe-sqs-src-" + suffix;
        String targetName = "pipe-sqs-fifo-" + suffix + ".fifo";
        String pipeName = "pipe-sqs-fifo-" + suffix;
        String sourceUrl = createQueue(sourceName, false);
        String targetUrl = createQueue(targetName, true);
        try {
            given()
                .contentType("application/json")
                .body("""
                    {
                        "Source": "arn:aws:sqs:us-east-1:000000000000:%s",
                        "Target": "arn:aws:sqs:us-east-1:000000000000:%s",
                        "RoleArn": "%s",
                        "TargetParameters": {
                            "InputTemplate": "{\\"t\\": <$.body.g>}",
                            "SqsQueueParameters": {
                                "MessageGroupId": "$.body.g",
                                "MessageDeduplicationId": "$.body.d"
                            }
                        }
                    }
                    """.formatted(sourceName, targetName, ROLE))
            .when()
                .post("/v1/pipes/" + pipeName)
            .then()
                .statusCode(200);

            given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", sourceUrl)
                .formParam("MessageBody", "{\"g\":\"grp\",\"d\":7}")
            .when()
                .post("/")
            .then()
                .statusCode(200);

            XmlPath message = receive(targetUrl);
            if (message == null) {
                fail("The FIFO target queue never received the message");
            }
            assertEquals("{\"t\": \"grp\"}",
                    message.getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Body"));
            assertEquals("grp", attribute(message, "MessageGroupId"));
            assertEquals("7", attribute(message, "MessageDeduplicationId"));
        } finally {
            given().when().delete("/v1/pipes/" + pipeName);
            deleteQueue(sourceUrl);
            deleteQueue(targetUrl);
        }
    }

    @Test
    void createPipeForAFifoTargetWithoutTargetParametersIsRefusedWithTheFieldList() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceName = "pipe-sqs-src-" + suffix;
        String targetName = "pipe-sqs-fifo-" + suffix + ".fifo";
        String pipeName = "pipe-sqs-refused-" + suffix;
        String sourceUrl = createQueue(sourceName, false);
        String targetUrl = createQueue(targetName, true);
        try {
            given()
                .contentType("application/json")
                .body("""
                    {
                        "Source": "arn:aws:sqs:us-east-1:000000000000:%s",
                        "Target": "arn:aws:sqs:us-east-1:000000000000:%s",
                        "RoleArn": "%s"
                    }
                    """.formatted(sourceName, targetName, ROLE))
            .when()
                .post("/v1/pipes/" + pipeName)
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("{\"ValidationErrorFields\":[{\"Name\":"
                        + "\"TargetParameters.SqsQueueParameters\","
                        + "\"Message\":\"Missing required target parameter.\"}]}"))
                .body("fieldList.size()", equalTo(1))
                .body("fieldList[0].name", equalTo("TargetParameters.SqsQueueParameters"))
                .body("fieldList[0].message", equalTo("Missing required target parameter."));
        } finally {
            given().when().delete("/v1/pipes/" + pipeName);
            deleteQueue(sourceUrl);
            deleteQueue(targetUrl);
        }
    }

    private static String createQueue(String name, boolean fifo) {
        RequestSpecification request = given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", name);
        if (fifo) {
            request = request.formParam("Attribute.1.Name", "FifoQueue").formParam("Attribute.1.Value", "true");
        }
        return request.when().post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private static XmlPath receive(String queueUrl) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(500);
            XmlPath result = given()
                    .contentType(SQS_CONTENT_TYPE)
                    .formParam("Action", "ReceiveMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MaxNumberOfMessages", "1")
                    .formParam("AttributeName.1", "All")
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .extract().xmlPath();
            if (!result.getList("ReceiveMessageResponse.ReceiveMessageResult.Message").isEmpty()) {
                return result;
            }
        }
        return null;
    }

    private static String attribute(XmlPath message, String name) {
        return message.getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Attribute.find { it.Name == '"
                + name + "' }.Value");
    }

    private static void deleteQueue(String queueUrl) {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", queueUrl)
        .when()
            .post("/");
    }
}
