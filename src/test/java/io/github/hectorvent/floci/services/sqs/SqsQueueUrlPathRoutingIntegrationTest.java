package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * SDKs that use the queue URL as the request endpoint post SQS calls to the queue path instead of "/". Routing
 * follows AWS: a JSON request names the queue in its QueueUrl body field, so it reaches SQS on the queue path, on
 * a /queue/{region}/ prefixed path and on either with a trailing slash. A Query request without a QueueUrl is
 * named by its path, so it reaches SQS only on the queue path, with or without a trailing slash. On a
 * /queue/{region}/ prefixed path without a QueueUrl, AWS answers 404 InvalidAddress; with a QueueUrl in the
 * body, the body names the queue and the request reaches SQS.
 */
@QuarkusTest
class SqsQueueUrlPathRoutingIntegrationTest {

    private static final String JSON_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String QUEUE_NAME = "path-routing-queue";
    private static final String QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:" + QUEUE_NAME;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createQueue() {
        given()
            .contentType(FORM_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", QUEUE_NAME)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/000000000000/" + QUEUE_NAME,
        "/000000000000/" + QUEUE_NAME + "/",
        "/queue/us-east-1/000000000000/" + QUEUE_NAME,
        "/queue/us-east-1/000000000000/" + QUEUE_NAME + "/"
    })
    void jsonRequestPostedToQueuePathReachesSqs(String path) {
        // The SDK sends the queue URL without the trailing slash in the body, only the HTTP path carries it.
        String queueUrlPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        given()
            .contentType(JSON_CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
            .body("{\"QueueUrl\":\"http://localhost:4566" + queueUrlPath + "\",\"AttributeNames\":[\"QueueArn\"]}")
        .when()
            .post(path)
        .then()
            .statusCode(200)
            .body("Attributes.QueueArn", equalTo(QUEUE_ARN));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/000000000000/" + QUEUE_NAME,
        "/000000000000/" + QUEUE_NAME + "/"
    })
    void queryRequestPostedToQueuePathWithoutQueueUrlReachesSqs(String path) {
        postQueryWithoutQueueUrl(path)
        .then()
            .statusCode(200)
            .body(containsString(QUEUE_ARN));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/queue/us-east-1/000000000000/" + QUEUE_NAME,
        "/queue/us-east-1/000000000000/" + QUEUE_NAME + "/"
    })
    void queryRequestPostedToPrefixedQueuePathWithoutQueueUrlReturnsInvalidAddress(String path) {
        // AWS rejects these with 404 InvalidAddress instead of the S3 error they used to fall through to.
        postQueryWithoutQueueUrl(path)
        .then()
            .statusCode(404)
            .body(containsString("InvalidAddress"))
            .body(not(containsString(QUEUE_ARN)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/queue/us-east-1/000000000000/" + QUEUE_NAME,
        "/queue/us-east-1/000000000000/" + QUEUE_NAME + "/"
    })
    void queryRequestPostedToPrefixedQueuePathWithQueueUrlReachesSqs(String path) {
        // The body names the queue here, so the request is routed to SQS like a JSON one.
        given()
            .contentType(FORM_CONTENT_TYPE)
            .formParam("Action", "GetQueueAttributes")
            .formParam("AttributeName.1", "QueueArn")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/" + QUEUE_NAME)
        .when()
            .post(path)
        .then()
            .statusCode(200)
            .body(containsString(QUEUE_ARN));
    }

    @ParameterizedTest
    @CsvSource({
        "queue, us-east-1/000000000000/form",
        "000000000001, form",
        "000000000002, dir/"
    })
    void formEncodedS3UploadOnQueueLikePathStaysWithS3(String bucket, String key) {
        // The Content-Type of CreateMultipartUpload describes the object, so it can be form-encoded.
        given()
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);

        given()
            .contentType(FORM_CONTENT_TYPE)
            .queryParam("uploads", "")
        .when()
            .post("/" + bucket + "/" + key)
        .then()
            .statusCode(200)
            .body(containsString("<UploadId>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/000000000000/" + QUEUE_NAME + "/",
        "/queue/us-east-1/000000000000/" + QUEUE_NAME + "/"
    })
    void sdkRequestOnQueueUrlEndpointWithTrailingSlashReachesSqs(String path) {
        String queueUrl = "http://localhost:" + RestAssured.port + path.substring(0, path.length() - 1);
        URI endpoint = URI.create(queueUrl + "/");
        try (SqsClient sqs = AwsTestClients.create(SqsClient.builder(), "000000000000", Region.US_EAST_1, endpoint)) {
            assertThat(sqs.getQueueAttributes(r -> r.queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes())
                .containsEntry(QueueAttributeName.QUEUE_ARN, QUEUE_ARN);
        }
    }

    private static Response postQueryWithoutQueueUrl(String path) {
        return given()
            .contentType(FORM_CONTENT_TYPE)
            .formParam("Action", "GetQueueAttributes")
            .formParam("AttributeName.1", "QueueArn")
        .when()
            .post(path);
    }
}
