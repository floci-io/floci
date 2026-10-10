package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.ValidateSignaturesProfile;
import io.github.hectorvent.floci.testutil.AppSyncRequestSigner;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code floci.auth.validate-signatures} authenticates every header-signed request, not only S3's:
 * a request signed with the wrong secret is refused in the error vocabulary of the protocol it
 * used, a request signed with the right one is served, and an unsigned request is left alone.
 * Shares {@link ValidateSignaturesProfile} with the S3 signature tests.
 */
@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SigV4HeaderSignatureIntegrationTest {

    private static final String QUEUE = "validate-signatures-queue";
    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String CBOR_1_1 = "application/x-amz-cbor-1.1";

    private static String userAccessKeyId;
    private static String userSecretKey;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void jsonRequestWithTheRightSecretIsServed() {
        given()
            .filter(AwsRequestSigner.signedAs("test", "test", "sqs"))
            .contentType(JSON_1_0)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body("{\"QueueName\":\"" + QUEUE + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueueUrl", containsString(QUEUE));
    }

    @Test
    @Order(2)
    void jsonRequestWithTheWrongSecretIsRefused() {
        given()
            .filter(AwsRequestSigner.signedAs("test", "not-the-secret", "sqs"))
            .contentType(JSON_1_0)
            .header("X-Amz-Target", "AmazonSQS.ListQueues")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("InvalidSignatureException"));

        given()
            .filter(AwsRequestSigner.signedAs("test", "not-the-secret", "kms"))
            .contentType(JSON_1_1)
            .header("X-Amz-Target", "TrentService.ListKeys")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("InvalidSignatureException"));
    }

    @Test
    @Order(3)
    void queryRequestWithTheWrongSecretIsRefusedInXml() {
        given()
            .filter(AwsRequestSigner.signedAs("test", "test", "iam"))
            .contentType(FORM)
            .body("Action=ListUsers&Version=2010-05-08")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .filter(AwsRequestSigner.signedAs("test", "not-the-secret", "iam"))
            .contentType(FORM)
            .body("Action=ListUsers&Version=2010-05-08")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("ErrorResponse.Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    @Test
    @Order(4)
    void restJsonRequestWithTheWrongSecretIsRefusedThroughTheErrorTypeHeader() {
        given()
            .filter(AwsRequestSigner.signedAs("test", "test", "scheduler"))
        .when()
            .get("/schedule-groups")
        .then()
            .statusCode(200);

        given()
            .filter(AwsRequestSigner.signedAs("test", "not-the-secret", "scheduler"))
        .when()
            .get("/schedule-groups")
        .then()
            .statusCode(403)
            .header("X-Amzn-Errortype", equalTo("InvalidSignatureException"));
    }

    @Test
    @Order(5)
    void pathWithEscapesVerifiesDoubleEncoded() {
        // A signature over the double-encoded ARN gets past authentication to Lambda's own 404.
        given()
            .filter(AwsRequestSigner.signedAs("test", "test", "lambda"))
            .urlEncodingEnabled(false)
        .when()
            .get("/2015-03-31/functions/arn%3Aaws%3Alambda%3Aus-east-1%3A000000000000%3Afunction%3Amissing")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(6)
    void queueUrlPathIsVerifiedBeforeItIsRewritten() {
        // An SDK v1 Query call posts to the queue URL; the router rewrites the path to / and
        // appends QueueUrl to the body, neither of which the client signed.
        given()
            .filter(AwsRequestSigner.signedAs("test", "test", "sqs"))
            .contentType(FORM)
            .body("Action=GetQueueAttributes&AttributeName.1=All&Version=2012-11-05")
        .when()
            .post("/000000000000/" + QUEUE)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void cborContentTypeIsVerifiedAsTheClientSentIt() {
        // AwsCborContentTypeFilter rewrites application/x-amz-cbor-1.1 before matching; the client
        // signed the original. An empty CBOR map is the single byte 0xA0.
        byte[] emptyMap = {(byte) 0xA0};
        given()
            .filter(AwsRequestSigner.signedAs("test", "test", "kinesis").signingContentType())
            .contentType(CBOR_1_1)
            .header("X-Amz-Target", "Kinesis_20131202.ListStreams")
            .body(emptyMap)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .filter(AwsRequestSigner.signedAs("test", "not-the-secret", "kinesis").signingContentType())
            .contentType(CBOR_1_1)
            .header("X-Amz-Target", "Kinesis_20131202.ListStreams")
            .body(emptyMap)
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .contentType(containsString("cbor"))
            .header("x-amzn-query-error", startsWith("InvalidSignatureException"));
    }

    @Test
    @Order(8)
    void unknownAccessKeyIsRefused() {
        given()
            .filter(AwsRequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret", "sqs"))
            .contentType(JSON_1_0)
            .header("X-Amz-Target", "AmazonSQS.ListQueues")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("UnrecognizedClientException"));

        given()
            .filter(AwsRequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret", "sts"))
            .contentType(FORM)
            .body("Action=GetCallerIdentity&Version=2011-06-15")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("ErrorResponse.Error.Code", equalTo("InvalidClientTokenId"));
    }

    @Test
    @Order(9)
    void iamCreatedKeyVerifiesWithItsOwnSecretOnly() {
        AwsRequestSigner iam = AwsRequestSigner.signedAs("test", "test", "iam");
        given().filter(iam).contentType(FORM)
            .body("Action=CreateUser&UserName=validate-signatures-sqs-user&Version=2010-05-08")
        .when().post("/").then().statusCode(200);
        XmlPath key = given().filter(iam).contentType(FORM)
            .body("Action=CreateAccessKey&UserName=validate-signatures-sqs-user&Version=2010-05-08")
        .when().post("/").then().statusCode(200).extract().xmlPath();
        userAccessKeyId = key.getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        userSecretKey = key.getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey");

        AwsRequestSigner user = AwsRequestSigner.signedAs(userAccessKeyId, userSecretKey, "sqs");
        given()
            .filter(user)
            .contentType(JSON_1_0)
            .header("X-Amz-Target", "AmazonSQS.ListQueues")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .filter(user.withSecret("not-the-secret"))
            .contentType(JSON_1_0)
            .header("X-Amz-Target", "AmazonSQS.ListQueues")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("InvalidSignatureException"));
    }

    @Test
    @Order(10)
    void signatureOutsideTheClockSkewWindowIsRefused() {
        given()
            .filter(AwsRequestSigner.signedAs("test", "test", "sqs")
                    .signedAt(Instant.now().minus(Duration.ofMinutes(20))))
            .contentType(JSON_1_0)
            .header("X-Amz-Target", "AmazonSQS.ListQueues")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("InvalidSignatureException"))
            .body("message", containsString("Signature expired"));
    }

    @Test
    @Order(11)
    void malformedAuthorizationHeaderIsIncomplete() {
        given()
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/sqs/aws4_request")
            .contentType(JSON_1_0)
            .header("X-Amz-Target", "AmazonSQS.ListQueues")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IncompleteSignatureException"));
    }

    @Test
    @Order(12)
    void credentialScopeNamingAnotherVerifiersServiceIsStillVerified() {
        // Which verifier owns a request is decided by the resource it matched, not by the service
        // the caller wrote into its own credential scope.
        for (String scopeService : new String[]{"s3", "s3express", "execute-api"}) {
            given()
                .filter(AwsRequestSigner.signedAs("test", "not-the-secret", scopeService))
                .contentType(JSON_1_0)
                .header("X-Amz-Target", "AmazonSQS.ListQueues")
                .body("{}")
            .when()
                .post("/")
            .then()
                .statusCode(403)
                .body("__type", equalTo("InvalidSignatureException"));
        }
    }

    @Test
    @Order(13)
    void s3ControlIsVerifiedAndAnswersInXml() {
        given()
            .filter(AwsRequestSigner.signedAs("test", "test", "s3"))
            .header("x-amz-account-id", "000000000000")
        .when()
            .get("/v20180820/accesspoint")
        .then()
            .statusCode(200);

        given()
            .filter(AwsRequestSigner.signedAs("test", "not-the-secret", "s3"))
            .header("x-amz-account-id", "000000000000")
        .when()
            .get("/v20180820/accesspoint")
        .then()
            .statusCode(403)
            .contentType(containsString("xml"))
            .body("ErrorResponse.Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    @Test
    @Order(14)
    void restXmlServiceAnswersInXml() {
        given()
            .filter(AwsRequestSigner.signedAs("test", "test", "route53"))
        .when()
            .get("/2013-04-01/hostedzone")
        .then()
            .statusCode(200);

        given()
            .filter(AwsRequestSigner.signedAs("test", "not-the-secret", "route53"))
        .when()
            .get("/2013-04-01/hostedzone")
        .then()
            .statusCode(403)
            .contentType(containsString("xml"))
            .body("ErrorResponse.Error.Code", equalTo("SignatureDoesNotMatch"));

        given()
            .filter(AwsRequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret", "route53"))
        .when()
            .get("/2013-04-01/hostedzone")
        .then()
            .statusCode(403)
            .body("ErrorResponse.Error.Code", equalTo("InvalidClientTokenId"));
    }

    @Test
    @Order(15)
    void clientSuppliedOriginalContentTypeCannotStandInForTheSignedOne() throws Exception {
        // Signed over one Content-Type, sent with another plus Floci's internal header claiming the
        // signed value. A raw client keeps both headers exactly as written. The internal header is
        // stripped on the way in, so the swap is a mismatch rather than a verified request.
        URI uri = URI.create("http://localhost:" + RestAssured.port + "/");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> auth = AwsRequestSigner.signedAs("test", "test", "sqs").headersFor("POST", uri,
                Map.of("content-type", JSON_1_0, "x-amz-target", "AmazonSQS.ListQueues"), body);

        assertEquals(200, sendSqsListQueues(uri, auth, JSON_1_0, null, body).statusCode());

        HttpResponse<String> swapped = sendSqsListQueues(uri, auth, JSON_1_0 + "; charset=utf-8", JSON_1_0, body);
        assertEquals(403, swapped.statusCode());
        assertTrue(swapped.body().contains("InvalidSignatureException"), swapped.body());
    }

    @Test
    @Order(16)
    void executeApiRoutesAreLeftToTheirOwnAuthorizer() {
        // A wrong secret on an execute-api path is ExecuteApiSigV4Authorizer's call, per method:
        // here the API does not exist, so the answer is the controller's 404, not this filter's 403.
        for (String path : new String[]{"/execute-api/nope/stage/x", "/_aws/execute-api/nope/stage/x",
                "/restapis/nope/stage/_user_request_/x"}) {
            given()
                .filter(AwsRequestSigner.signedAs("test", "not-the-secret", "execute-api"))
            .when()
                .get(path)
            .then()
                .statusCode(404);
        }
    }

    @Test
    @Order(17)
    void appSyncGraphqlIsLeftToItsOwnIamCheck() throws Exception {
        // The management plane is verified here like any other service; the GraphQL endpoint is
        // AppSync's own, and a wrong secret there gets AppSync's UnauthorizedException envelope.
        String apiId = given()
            .filter(AwsRequestSigner.signedAs("test", "test", "appsync"))
            .contentType("application/json")
            .body("{\"name\": \"validate-signatures-graphql\", \"authenticationType\": \"AWS_IAM\"}")
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        String query = "{\"query\": \"{ __typename }\"}";
        Map<String, String> forged = AppSyncRequestSigner.signedHeaders(apiId, "localhost:" + RestAssured.port,
                query, "test", "not-the-secret", "us-east-1", Instant.now());
        given()
            .headers(forged)
            .contentType("application/json")
            .body(query)
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(401)
            .header("x-amzn-errortype", containsString("UnauthorizedException"))
            .body("errors[0].errorType", equalTo("UnauthorizedException"));
    }

    private static HttpResponse<String> sendSqsListQueues(URI uri, Map<String, String> auth, String contentType,
                                                         String claimedOriginal, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", contentType)
                .header("X-Amz-Target", "AmazonSQS.ListQueues");
        auth.forEach(request::header);
        if (claimedOriginal != null) {
            request.header(AwsCborContentTypeFilter.ORIGINAL_CONTENT_TYPE_HEADER, claimedOriginal);
        }
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    @Order(18)
    void unsignedRequestIsLeftToIamEnforcement() {
        given()
            .contentType(JSON_1_0)
            .header("X-Amz-Target", "AmazonSQS.ListQueues")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
