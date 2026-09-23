package io.github.hectorvent.floci.services.lambda;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SqsEsmAliasDockerIntegrationTest {

    private static final String LAMBDA_BASE = "/2015-03-31";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available to run the mapped Lambda function");
    }

    @Test
    void aliasQualifiedMappingInvokesTheAliasVersion() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String functionName = "esm-alias-" + suffix;
        String queueName = "esm-alias-" + suffix;
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT_ID + ":" + queueName;
        String queueUrl = createQueue(queueName);
        String esmUuid = null;

        try {
            given()
                .contentType("application/json")
                .body("""
                    {
                        "FunctionName": "%s",
                        "Runtime": "nodejs20.x",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Handler": "index.handler",
                        "Timeout": 10,
                        "Code": {"ZipFile": "%s"}
                    }
                    """.formatted(functionName, handlerZip("exports.handler = async () => ({});")))
            .when()
                .post(LAMBDA_BASE + "/functions")
            .then()
                .statusCode(201);

            given()
                .contentType("application/json")
                .body("{}")
            .when()
                .post(LAMBDA_BASE + "/functions/" + functionName + "/versions")
            .then()
                .statusCode(201)
                .body("Version", equalTo("1"));

            // $LATEST fails, so a mapping that invokes it leaves the message on the queue.
            given()
                .contentType("application/json")
                .body("{\"ZipFile\": \"%s\"}".formatted(
                        handlerZip("exports.handler = async () => { throw new Error('latest'); };")))
            .when()
                .put(LAMBDA_BASE + "/functions/" + functionName + "/code")
            .then()
                .statusCode(200);

            given()
                .contentType("application/json")
                .body("{\"Name\": \"live\", \"FunctionVersion\": \"1\"}")
            .when()
                .post(LAMBDA_BASE + "/functions/" + functionName + "/aliases")
            .then()
                .statusCode(201);

            esmUuid = given()
                .contentType("application/json")
                .body("""
                    {
                        "FunctionName": "%s:live",
                        "EventSourceArn": "%s",
                        "BatchSize": 1
                    }
                    """.formatted(functionName, queueArn))
            .when()
                .post(LAMBDA_BASE + "/event-source-mappings")
            .then()
                .statusCode(202)
            .extract()
                .path("UUID");

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MessageBody", "hello")
            .when()
                .post("/")
            .then()
                .statusCode(200);

            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1))
                    .until(() -> messagesOnQueue(queueUrl) == 0);
        } finally {
            if (esmUuid != null) {
                given().delete(LAMBDA_BASE + "/event-source-mappings/" + esmUuid);
            }
            given().delete(LAMBDA_BASE + "/functions/" + functionName);
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteQueue")
                .formParam("QueueUrl", queueUrl)
                .post("/");
        }
    }

    private static String createQueue(String queueName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", queueName)
            .formParam("Attribute.1.Name", "VisibilityTimeout")
            .formParam("Attribute.1.Value", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
        .extract()
            .xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private static int messagesOnQueue(String queueUrl) {
        XmlPath xml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", queueUrl)
            .formParam("AttributeName.1", "All")
        .when()
            .post("/")
        .then()
            .statusCode(200)
        .extract()
            .xmlPath();
        return attribute(xml, "ApproximateNumberOfMessages")
                + attribute(xml, "ApproximateNumberOfMessagesNotVisible");
    }

    private static int attribute(XmlPath xml, String name) {
        return Integer.parseInt(xml.getString(
                "GetQueueAttributesResponse.GetQueueAttributesResult.Attribute.find { it.Name == '"
                        + name + "' }.Value"));
    }

    private static String handlerZip(String source) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(source.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
