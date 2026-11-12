package io.floci.aws.services.signer;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class SignerIntegrationTest {

    private static final String ACCOUNT_ID = "723679240095";
    private static final String PROFILE_ARN =
            "arn:aws:signer:us-east-1:" + ACCOUNT_ID + ":/signing-profiles/ItProfile";

    @Test
    void profileRoundTripsThroughRestJsonIncludingTagsAndPermissions() {
        signer(ACCOUNT_ID, "us-east-1")
                .body("""
                        {"platformId":"AWSLambda-SHA384-ECDSA",
                         "signatureValidityPeriod":{"type":"DAYS","value":135},
                         "overrides":{"signingConfiguration":{"hashAlgorithm":"SHA-384"}},
                         "tags":{"env":"it"}}
                        """)
        .when()
                .put("/signing-profiles/ItProfile")
        .then()
                .statusCode(200)
                .body("arn", equalTo(PROFILE_ARN))
                .body("profileVersion", matchesPattern("[0-9a-zA-Z]{10}"))
                .body("profileVersionArn", matchesPattern(
                        "arn:aws:signer:us-east-1:" + ACCOUNT_ID
                                + ":/signing-profiles/ItProfile/[0-9a-zA-Z]{10}"));

        try {
            Response profile = signer(ACCOUNT_ID, "us-east-1")
            .when()
                    .get("/signing-profiles/ItProfile")
            .then()
                    .statusCode(200)
                    .body("profileName", equalTo("ItProfile"))
                    .body("platformId", equalTo("AWSLambda-SHA384-ECDSA"))
                    .body("platformDisplayName", equalTo("AWS Lambda"))
                    .body("status", equalTo("Active"))
                    .body("signatureValidityPeriod.type", equalTo("DAYS"))
                    .body("signatureValidityPeriod.value", equalTo(135))
                    .body("overrides.signingConfiguration.hashAlgorithm", equalTo("SHA-384"))
                    .body("tags.env", equalTo("it"))
                    .extract().response();
            String profileVersion = profile.path("profileVersion");

            signer(ACCOUNT_ID, "us-east-1")
            .when()
                    .get("/signing-profiles")
            .then()
                    .statusCode(200)
                    .body("profiles.profileName", hasItem("ItProfile"));

            signer(ACCOUNT_ID, "us-east-1")
                    .body("{\"action\":\"signer:StartSigningJob\","
                            + "\"principal\":\"123456789012\",\"statementId\":\"cross-account\"}")
            .when()
                    .post("/signing-profiles/ItProfile/permissions")
            .then()
                    .statusCode(200)
                    .body("revisionId", not(equalTo("")));

            signer(ACCOUNT_ID, "us-east-1")
            .when()
                    .get("/signing-profiles/ItProfile/permissions")
            .then()
                    .statusCode(200)
                    .body("permissions[0].action", equalTo("signer:StartSigningJob"))
                    .body("permissions[0].statementId", equalTo("cross-account"))
                    .body("permissions[0].profileVersion", equalTo(profileVersion));

            signer(ACCOUNT_ID, "us-east-1")
                    .body("{\"tags\":{\"team\":\"platform\"}}")
            .when()
                    .post("/tags/" + PROFILE_ARN)
            .then()
                    .statusCode(204);

            signer(ACCOUNT_ID, "us-east-1")
            .when()
                    .get("/tags/" + PROFILE_ARN)
            .then()
                    .statusCode(200)
                    .body("tags.team", equalTo("platform"))
                    .body("tags.env", equalTo("it"));

            signer(ACCOUNT_ID, "us-east-1")
            .when()
                    .delete("/tags/" + PROFILE_ARN + "?tagKeys=team")
            .then()
                    .statusCode(204);

            signer(ACCOUNT_ID, "us-east-1")
            .when()
                    .get("/tags/" + PROFILE_ARN)
            .then()
                    .statusCode(200)
                    .body("tags.team", nullValue())
                    .body("tags.env", equalTo("it"));
        } finally {
            signer(ACCOUNT_ID, "us-east-1")
            .when()
                    .delete("/signing-profiles/ItProfile")
            .then()
                    .statusCode(200);
        }
    }

    @Test
    void aSigningJobFailsBecauseNoArtifactIsWritten() {
        signer(ACCOUNT_ID, "us-east-1")
                .body("{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}")
        .when()
                .put("/signing-profiles/ItProfile")
        .then()
                .statusCode(200);

        try {
            Response started = signer(ACCOUNT_ID, "us-east-1")
                    .body("""
                            {"profileName":"ItProfile",
                             "source":{"s3":{"bucketName":"artifacts","key":"app.zip","version":"1"}},
                             "destination":{"s3":{"bucketName":"signed","prefix":"out/"}},
                             "clientRequestToken":"it-token"}
                            """)
            .when()
                    .post("/signing-jobs")
            .then()
                    .statusCode(200)
                    .body("jobId", matchesPattern(
                            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    .body("jobOwner", equalTo(ACCOUNT_ID))
                    .extract().response();
            String jobId = started.path("jobId");

            signer(ACCOUNT_ID, "us-east-1")
            .when()
                    .get("/signing-jobs/" + jobId)
            .then()
                    .statusCode(200)
                    .body("status", equalTo("Failed"))
                    .body("profileName", equalTo("ItProfile"))
                    .body("source.s3.bucketName", equalTo("artifacts"))
                    .body("source.s3.key", equalTo("app.zip"))
                    .body("signedObject", nullValue())
                    .body("isRevoked", equalTo(false))
                    .body("statusReason", containsString("does not sign artifacts"))
                    .body("createdAt", notNullValue());

            // No signature exists, so there is nothing to revoke.
            signer(ACCOUNT_ID, "us-east-1")
                    .body("{\"reason\":\"artifact withdrawn\"}")
            .when()
                    .put("/signing-jobs/" + jobId + "/revoke")
            .then()
                    .statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        } finally {
            signer(ACCOUNT_ID, "us-east-1")
            .when()
                    .delete("/signing-profiles/ItProfile")
            .then()
                    .statusCode(200);
        }
    }

    @Test
    void aPermissionToSignAPayloadIsAcceptedThroughTheWire() {
        signer(ACCOUNT_ID, "us-east-1")
                .body("{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}")
        .when()
                .put("/signing-profiles/PermProfile")
        .then()
                .statusCode(200);

        signer(ACCOUNT_ID, "us-east-1")
                .body("{\"action\":\"signer:SignPayload\",\"principal\":\"123456789012\","
                        + "\"statementId\":\"sign-payload\"}")
        .when()
                .post("/signing-profiles/PermProfile/permissions")
        .then()
                .statusCode(200)
                .body("revisionId", notNullValue());

        signer(ACCOUNT_ID, "us-east-1")
        .when()
                .get("/signing-profiles/PermProfile/permissions")
        .then()
                .statusCode(200)
                .body("permissions", hasSize(1))
                .body("permissions[0].action", equalTo("signer:SignPayload"))
                .body("permissions[0].statementId", equalTo("sign-payload"));
    }

    @Test
    void aCanceledProfileStaysReadableAndIsHiddenUnlessAskedFor() {
        signer(ACCOUNT_ID, "us-east-1")
                .body("{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}")
        .when()
                .put("/signing-profiles/CancelProfile")
        .then()
                .statusCode(200);

        signer(ACCOUNT_ID, "us-east-1")
        .when()
                .delete("/signing-profiles/CancelProfile")
        .then()
                .statusCode(200);

        signer(ACCOUNT_ID, "us-east-1")
        .when()
                .get("/signing-profiles/CancelProfile")
        .then()
                .statusCode(200)
                .body("status", equalTo("Canceled"))
                .body("arn", equalTo("arn:aws:signer:us-east-1:" + ACCOUNT_ID
                        + ":/signing-profiles/CancelProfile"));

        signer(ACCOUNT_ID, "us-east-1")
        .when()
                .get("/signing-profiles")
        .then()
                .statusCode(200)
                .body("profiles.profileName", not(hasItem("CancelProfile")));

        signer(ACCOUNT_ID, "us-east-1")
                .queryParam("includeCanceled", "true")
        .when()
                .get("/signing-profiles")
        .then()
                .statusCode(200)
                .body("profiles.profileName", hasItem("CancelProfile"));

        signer(ACCOUNT_ID, "us-east-1")
                .body("{\"profileName\":\"CancelProfile\","
                        + "\"source\":{\"s3\":{\"bucketName\":\"a\",\"key\":\"k\",\"version\":\"1\"}},"
                        + "\"destination\":{\"s3\":{\"bucketName\":\"b\"}},"
                        + "\"clientRequestToken\":\"after-cancel\"}")
        .when()
                .post("/signing-jobs")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void platformsAreServedAndAnUnknownOneIsNotFound() {
        signer(ACCOUNT_ID, "us-east-1")
        .when()
                .get("/signing-platforms")
        .then()
                .statusCode(200)
                .body("platforms.platformId",
                        hasItem("AWSLambda-SHA384-ECDSA"))
                .body("platforms[0].signingConfiguration.hashAlgorithmOptions.allowedValues",
                        hasItem("SHA-384"));

        signer(ACCOUNT_ID, "us-east-1")
        .when()
                .get("/signing-platforms/AWSLambda-SHA384-ECDSA")
        .then()
                .statusCode(200)
                .body("category", equalTo("AWSLambda"))
                .body("signingImageFormat.defaultFormat", equalTo("JSON"));

        signer(ACCOUNT_ID, "us-east-1")
        .when()
                .get("/signing-platforms/Not-A-Platform")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void aRequestSignedForAnotherServiceKeepsItsPathAndDoesNotReachSigner() {
        given()
                .contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID
                        + "/20260101/us-east-1/s3/aws4_request")
        .when()
                .get("/signing-profiles")
        .then()
                .statusCode(404);
    }

    @Test
    void validationErrorsUseTheSignerErrorShape() {
        signer(ACCOUNT_ID, "us-east-1")
                .body("{\"platformId\":\"Not-A-Platform\"}")
        .when()
                .put("/signing-profiles/ItProfile")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        signer(ACCOUNT_ID, "us-east-1")
        .when()
                .get("/signing-profiles/Missing")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static RequestSpecification signer(String accountId, String region) {
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260101/" + region + "/signer/aws4_request";
        return given()
                .contentType("application/json")
                .header("Authorization", authorization);
    }
}
