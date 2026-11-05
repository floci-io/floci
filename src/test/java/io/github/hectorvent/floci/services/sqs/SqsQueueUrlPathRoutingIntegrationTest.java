package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
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

/**
 * SDKs that use the queue URL as the request endpoint post SQS calls to the queue path instead of "/". Routing
 * follows AWS: a JSON request names the queue in its QueueUrl body field, so it reaches SQS on the queue path, on
 * a /queue/{region}/ prefixed path and on either with a trailing slash. A Query request without a QueueUrl is
 * named by its path, so it reaches SQS only on the queue path, with or without a trailing slash, and AWS rejects it
 * on a prefixed path with 404 InvalidAddress.
 */
@QuarkusTest
class SqsQueueUrlPathRoutingIntegrationTest {

    private static final String JSON_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String QUEUE_NAME = "path-routing-queue";
    private static final String QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:" + QUEUE_NAME;
    private static final String SQS_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20261105/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=abc";

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
        postQueryWithoutQueueUrl(given(), path)
        .then()
            .statusCode(200)
            .body(containsString(QUEUE_ARN));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/queue/us-east-1/000000000000/" + QUEUE_NAME,
        "/queue/us-east-1/000000000000/" + QUEUE_NAME + "/"
    })
    void queryRequestPostedToPrefixedQueuePathWithoutQueueUrlIsRejectedAsInvalidAddress(String path) {
        postQueryWithoutQueueUrl(given().header("Authorization", SQS_AUTHORIZATION), path)
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("InvalidAddress"))
            .body("ErrorResponse.Error.Message", equalTo("The specified ID is invalid."));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void presignedQueryRequestOnPrefixedQueuePathIsRejectedAsInvalidAddress(boolean withBlankAuthorizationHeader) {
        // A blank Authorization header counts as absent, so the presigned credential names the service.
        RequestSpecification request = given()
            .queryParam("X-Amz-Credential", "test/20261105/us-east-1/sqs/aws4_request");
        if (withBlankAuthorizationHeader) {
            request.header("Authorization", "");
        }
        postQueryWithoutQueueUrl(request, "/queue/us-east-1/000000000000/" + QUEUE_NAME)
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("InvalidAddress"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "AWS4-HMAC-SHA256 Credential=test/20261105/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc"
    })
    void formEncodedPostNotSignedForSqsOnPrefixedQueuePathStaysWithS3(String authorization) {
        given()
        .when()
            .put("/queue")
        .then()
            .statusCode(200);

        RequestSpecification request = given()
            .contentType(FORM_CONTENT_TYPE)
            .formParam("field", "value");
        if (!authorization.isEmpty()) {
            request.header("Authorization", authorization);
        }
        request
        .when()
            .post("/queue/us-east-1/000000000000/form")
        .then()
            .statusCode(400)
            .body("Error.Code", equalTo("InvalidArgument"));
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

    private static Response postQueryWithoutQueueUrl(RequestSpecification request, String path) {
        return request
            .contentType(FORM_CONTENT_TYPE)
            .formParam("Action", "GetQueueAttributes")
            .formParam("AttributeName.1", "QueueArn")
        .when()
            .post(path);
    }
}
