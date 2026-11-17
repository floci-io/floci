package io.floci.aws.lifecycle;

import io.floci.aws.config.FlociVersion;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * The Floci control-plane contract every emulator serves under {@code /_floci} (floci-loft decision
 * 0022): the required fields, the legacy aliases, the JSON 404 catch-all and its precedence over S3.
 */
@QuarkusTest
class ControlPlaneContractIntegrationTest {

    private static final String NOT_FOUND_BODY = "\"error\":\"NotFound\"";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/health", "/_localstack/health", "/health"})
    void health_carriesTheRequiredFieldsOnEveryPath(String path) {
        given()
            .when().get(path)
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", equalTo("UP"))
                .body("cloud", equalTo("aws"))
                .body("version", equalTo(FlociVersion.current()))
                .body("services.s3", equalTo("running"))
                .body("edition", equalTo("community"))
                .body("original_edition", equalTo("floci-always-free"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/info", "/_localstack/info"})
    void info_namesTheCloud(String path) {
        given()
            .when().get(path)
            .then()
                .statusCode(200)
                .body("cloud", equalTo("aws"))
                .body("version", equalTo(FlociVersion.current()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/tls-cert", "/_localstack/tls-cert"})
    void tlsCert_answersTheSharedNotFoundShapeWhenTlsIsOff(String path) {
        given()
            .when().get(path)
            .then()
                .statusCode(404)
                .contentType("application/json")
                .body("tlsEnabled", equalTo(false))
                .body("error", equalTo("TLS is not enabled"))
                .body("message", notNullValue());
    }

    @Test
    void caPem_staysServedWhenTlsIsOff() {
        given()
            .when().get("/_floci/ca.pem")
            .then()
                .statusCode(200)
                .contentType(startsWith("text/plain"))
                .body(containsString("BEGIN CERTIFICATE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/helth", "/_localstack/helth", "/_floci/state/nope", "/_floci/a/b/c", "/_floci"})
    void unknownGet_answersTheJsonNotFound(String path) {
        given()
            .when().get(path)
            .then()
                .statusCode(404)
                .contentType("application/json")
                .body("error", equalTo("NotFound"))
                .body("path", equalTo(path));
    }

    @Test
    void unknownPathWithAnyMethod_answersTheJsonNotFound() {
        given().body("x").when().put("/_floci/nope").then()
                .statusCode(404).body("error", equalTo("NotFound")).body("path", equalTo("/_floci/nope"));
        given().when().delete("/_floci/nope").then()
                .statusCode(404).body("error", equalTo("NotFound"));
        given().contentType("application/json").body("{}").when().post("/_localstack/nope").then()
                .statusCode(404).body("error", equalTo("NotFound")).body("path", equalTo("/_localstack/nope"));
    }

    @Test
    void bucketStyleHost_doesNotCaptureTheControlPlane() {
        given()
            .header("Host", "mybucket.s3.localhost")
            .when().get("/_floci/health")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("cloud", equalTo("aws"));

        given()
            .header("Host", "mybucket.s3.localhost")
            .when().get("/_floci/helth")
            .then()
                .statusCode(404)
                .body("error", equalTo("NotFound"))
                .body("path", equalTo("/_floci/helth"));
    }

    @Test
    void otherRoutesUnderTheControlPlanePrefix_stillAnswer() {
        given().when().get("/_floci/ui/status").then()
                .statusCode(200).body("ready", notNullValue());
        given().when().get("/_localstack/ui/status").then()
                .statusCode(200).body("ready", notNullValue());
        given().when().post("/_floci/ecr/gc").then()
                .body(not(containsString(NOT_FOUND_BODY)));
        given().when().get("/_floci/eks/clusters/no-such-cluster/oidc/.well-known/openid-configuration").then()
                .body(not(containsString(NOT_FOUND_BODY)));
        given().when().get("/_floci/signin/consent").then()
                .body(not(containsString(NOT_FOUND_BODY)));
        given().when().get("/_floci/signin/signin.css").then()
                .statusCode(200).contentType(startsWith("text/css"));
    }

    @Test
    void stateReset_clearsAResourceCreatedBeforeIt() {
        String queue = "control-plane-reset-" + System.nanoTime();
        given()
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .contentType("application/x-amz-json-1.0")
            .body("{\"QueueName\": \"" + queue + "\"}")
            .when().post("/")
            .then().statusCode(200);

        given().when().post("/_floci/state/reset").then()
                .statusCode(200).body("status", equalTo("OK"));

        given()
            .header("X-Amz-Target", "AmazonSQS.GetQueueUrl")
            .contentType("application/x-amz-json-1.0")
            .body("{\"QueueName\": \"" + queue + "\"}")
            .when().post("/")
            .then().statusCode(400);
    }
}
