package io.floci.aws.services.lambdamicrovms;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class LambdaMicrovmRunRequestFieldsIntegrationTest {

    private static final String ROOT = "/2025-09-09";
    private static final String ROLE = "arn:aws:iam::000000000000:role/microvm-runtime";
    private static final String INGRESS =
            "arn:aws:lambda:us-east-1:aws:network-connector:aws-network-connector:NO_INGRESS";
    private static final String EGRESS =
            "arn:aws:lambda:us-east-1:000000000000:network-connector:nc-0123456789abcdef0";
    private static final String DEFAULT_EGRESS =
            "arn:aws:lambda:us-east-1:aws:network-connector:aws-network-connector:INTERNET_EGRESS";
    private static final String DEFAULT_INGRESS =
            "arn:aws:lambda:us-east-1:aws:network-connector:aws-network-connector:HTTP_INGRESS";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private String createImage() {
        String name = "run-fields-" + UUID.randomUUID();
        given().contentType("application/json").body("""
                {"name":"%s",
                 "baseImageArn":"arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                 "buildRoleArn":"arn:aws:iam::000000000000:role/microvm-build",
                 "codeArtifact":{"uri":"s3://bucket/code.zip"}}
                """.formatted(name))
                .when().post(ROOT + "/microvm-images").then().statusCode(201);
        cleanup.register(() -> given().when().delete(ROOT + "/microvm-images/" + name).then().statusCode(200));
        return name;
    }

    private String createImageWithTwoVersions() {
        String image = createImage();
        given().contentType("application/json").body("""
                {"baseImageArn":"arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                 "buildRoleArn":"arn:aws:iam::000000000000:role/microvm-build",
                 "codeArtifact":{"uri":"s3://bucket/code-v2.zip"}}
                """)
                .when().put(ROOT + "/microvm-images/" + image).then().statusCode(200);
        return image;
    }

    private Response run(String image, String fields) {
        Response response = given().contentType("application/json")
                .body("{\"imageIdentifier\":\"" + image + "\"" + fields + "}")
                .when().post(ROOT + "/microvms").then().statusCode(200).extract().response();
        String id = response.path("microvmId");
        cleanup.register(() -> given().when().delete(ROOT + "/microvms/" + id).then().statusCode(200));
        return response;
    }

    @ParameterizedTest
    @CsvSource({"true,300", "false,0"})
    void runAndGetRetainTheRequestedSettings(boolean autoResume, int suspendedSeconds) {
        String image = createImage();
        Response response = run(image, """
                ,"executionRoleArn":"%s","ingressNetworkConnectors":["%s"],
                 "egressNetworkConnectors":["%s"],"maximumDurationInSeconds":3600,
                 "idlePolicy":{"autoResumeEnabled":%s,"maxIdleDurationSeconds":900,
                               "suspendedDurationSeconds":%s}
                """.formatted(ROLE, INGRESS, EGRESS, autoResume, suspendedSeconds));
        response.then().body("executionRoleArn", equalTo(ROLE))
                .body("ingressNetworkConnectors", contains(INGRESS))
                .body("egressNetworkConnectors", contains(EGRESS))
                .body("maximumDurationInSeconds", equalTo(3600))
                .body("idlePolicy.autoResumeEnabled", equalTo(autoResume))
                .body("idlePolicy.maxIdleDurationSeconds", equalTo(900))
                .body("idlePolicy.suspendedDurationSeconds", equalTo(suspendedSeconds));
        given().when().get(ROOT + "/microvms/" + response.path("microvmId")).then().statusCode(200)
                .body("executionRoleArn", equalTo(ROLE))
                .body("ingressNetworkConnectors", contains(INGRESS))
                .body("egressNetworkConnectors", contains(EGRESS))
                .body("maximumDurationInSeconds", equalTo(3600))
                .body("idlePolicy.autoResumeEnabled", equalTo(autoResume))
                .body("idlePolicy.maxIdleDurationSeconds", equalTo(900))
                .body("idlePolicy.suspendedDurationSeconds", equalTo(suspendedSeconds));
        assertListSummary(response, "1.0");
    }

    @Test
    void requestedImageVersionRunsEvenWhenANewerVersionIsActive() {
        String image = createImageWithTwoVersions();
        Response response = run(image, ",\"imageVersion\":\"1.0\"");
        response.then().body("imageVersion", equalTo("1.0"));
        given().when().get(ROOT + "/microvms/" + response.path("microvmId")).then().statusCode(200)
                .body("imageVersion", equalTo("1.0"));
        assertListSummary(response, "1.0");
    }

    @ParameterizedTest
    @CsvSource({"1.0,1.0", ",2.0"})
    void listReportsTheSelectedImageVersion(String requestedVersion, String expectedVersion) {
        String fields = requestedVersion == null ? "" : ",\"imageVersion\":\"" + requestedVersion + "\"";
        Response response = run(createImageWithTwoVersions(), fields);
        assertListSummary(response, expectedVersion);
    }

    private void assertListSummary(Response response, String imageVersion) {
        String entry = "items.find { it.microvmId == '" + response.path("microvmId") + "' }";
        given().when().get(ROOT + "/microvms").then().statusCode(200)
                .body(entry + ".imageVersion", equalTo(imageVersion))
                .body(entry + ".keySet()", containsInAnyOrder(
                        "microvmId", "state", "imageArn", "imageVersion", "startedAt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"maximumDurationInSeconds\":0",
            "\"maximumDurationInSeconds\":28801",
            "\"maximumDurationInSeconds\":\"3600\"",
            "\"idlePolicy\":{\"autoResumeEnabled\":true,\"maxIdleDurationSeconds\":59,\"suspendedDurationSeconds\":0}",
            "\"idlePolicy\":{\"autoResumeEnabled\":true,\"maxIdleDurationSeconds\":60,\"suspendedDurationSeconds\":-1}",
            "\"idlePolicy\":{\"maxIdleDurationSeconds\":60,\"suspendedDurationSeconds\":0}"
    })
    void invalidSettingsAreRejected(String fields) {
        assertRejected(createImage(), "," + fields, 400);
    }

    @Test
    void aRequestedImageVersionMustExist() {
        assertRejected(createImage(), ",\"imageVersion\":\"missing\"", 404);
    }

    private void assertRejected(String image, String fields, int status) {
        Response response = given().contentType("application/json")
                .body("{\"imageIdentifier\":\"" + image + "\"" + fields + "}")
                .when().post(ROOT + "/microvms");
        if (response.statusCode() == 200) {
            String id = response.path("microvmId");
            cleanup.register(() -> given().when().delete(ROOT + "/microvms/" + id).then().statusCode(200));
        }
        response.then().statusCode(status);
    }

    @ParameterizedTest
    @MethodSource("modeledInvalidFields")
    void modeledRunConstraintsRejectInvalidStrings(String fields) {
        assertRejected(createImage(), "," + fields, 400);
    }

    private static Stream<String> modeledInvalidFields() {
        return Stream.of(
                "\"executionRoleArn\":\"\"",
                "\"executionRoleArn\":\"not-an-arn\"",
                "\"executionRoleArn\":\"arn:aws:iam::12345678901:role/runtime\"",
                "\"executionRoleArn\":\"" + ROLE + "r".repeat(2049 - ROLE.length()) + "\"",
                "\"imageVersion\":\"\"",
                "\"imageVersion\":\"1. 0\"",
                "\"imageVersion\":\"" + "1".repeat(2049) + "\"");
    }

    @Test
    void modeledRoleLengthBoundaryRemainsValid() {
        String role = ROLE + "r".repeat(2048 - ROLE.length());
        run(createImage(), ",\"executionRoleArn\":\"" + role + "\"")
                .then().body("executionRoleArn", equalTo(role));
    }

    @Test
    void modeledImageVersionLengthBoundaryReachesVersionLookup() {
        assertRejected(createImage(), ",\"imageVersion\":\"" + "1".repeat(2048) + "\"", 404);
    }

    @Test
    void omittedSettingsKeepTheExistingDefaults() {
        Response response = run(createImage(), "");
        response.then().body("imageVersion", equalTo("1.0"))
                .body("maximumDurationInSeconds", equalTo(28800))
                .body("executionRoleArn", nullValue())
                .body("idlePolicy", nullValue())
                .body("ingressNetworkConnectors", contains(DEFAULT_INGRESS))
                .body("egressNetworkConnectors", contains(DEFAULT_EGRESS));
    }

    @Test
    void emptyConnectorListsKeepTheManagedDefaults() {
        Response response = run(createImage(), ",\"ingressNetworkConnectors\":[],\"egressNetworkConnectors\":[]");
        response.then().body("ingressNetworkConnectors", contains(DEFAULT_INGRESS))
                .body("egressNetworkConnectors", contains(DEFAULT_EGRESS));
    }
}
