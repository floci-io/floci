package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.testing.S3EnforceAuthProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(S3EnforceAuthProfile.class)
class SesExportJobS3AuthEnforcementIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260101/eu-west-2/ses/aws4_request";

    @Test
    void completedExportUrlCanBeFetchedWhenS3AuthIsEnforced() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("{\"VdmAttributes\":{\"VdmEnabled\":\"ENABLED\"}}")
        .when()
            .put("/v2/email/account/vdm")
        .then()
            .statusCode(200);

        String jobId = given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("""
                {"ExportDataSource": {"MessageInsightsDataSource":
                    {"StartDate": %d, "EndDate": %d}},
                 "ExportDestination": {"DataFormat": "CSV"}}
                """.formatted(Instant.now().minus(Duration.ofDays(1)).getEpochSecond(),
                    Instant.now().plus(Duration.ofDays(1)).getEpochSecond()))
        .when()
            .post("/v2/email/export-jobs")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        assertEquals("COMPLETED", awaitTerminal(jobId));

        String url = given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/export-jobs/" + jobId)
        .then()
            .statusCode(200)
            .extract().path("ExportDestination.S3Url");
        URI uri = URI.create(url);
        String encodedCredential = Arrays.stream(uri.getRawQuery().split("&"))
                .filter(parameter -> parameter.startsWith("X-Amz-Credential="))
                .findFirst().orElseThrow().substring("X-Amz-Credential=".length());
        String credential = URLDecoder.decode(encodedCredential, StandardCharsets.UTF_8);
        assertTrue(credential.endsWith("/eu-west-2/s3/aws4_request"));

        given()
            .urlEncodingEnabled(false)
            .header("Host", uri.getRawAuthority())
        .when()
            .get(uri.getRawPath() + "?" + uri.getRawQuery())
        .then()
            .statusCode(200)
            .body(startsWith("\"messageid\",\"sendtimestamp\""));
    }

    private static String awaitTerminal(String jobId) {
        for (int attempt = 0; attempt < 100; attempt++) {
            Response response = given()
                .header("Authorization", AUTH)
            .when()
                .get("/v2/email/export-jobs/" + jobId)
            .then()
                .statusCode(200)
                .extract().response();
            String status = response.path("JobStatus");
            if (!"PROCESSING".equals(status) && !"CREATED".equals(status)) {
                return status;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the export job", e);
            }
        }
        throw new AssertionError("export job " + jobId + " never reached a terminal state");
    }
}
