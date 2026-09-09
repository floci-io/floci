package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies GetAccountSettings: {@code GET /2016-08-19/account-settings/}. Account limits mirror
 * AWS's fixed storage defaults plus the configured concurrency limit; usage reflects the caller's
 * stored functions. Distinct account ids in the credential keep the usage assertions isolated
 * from other Lambda tests.
 */
@QuarkusTest
class LambdaAccountSettingsIntegrationTest {

    private static final String SETTINGS_PATH = "/2016-08-19/account-settings/";

    @Test
    void accountSettings_emptyAccount_returnsAwsLimitsAndZeroUsage() {
        given()
            .header("Authorization", auth("000000000401"))
        .when()
            .get(SETTINGS_PATH)
        .then()
            .statusCode(200)
            .body("AccountLimit.TotalCodeSize", equalTo(80530636800L))
            .body("AccountLimit.CodeSizeUnzipped", equalTo(262144000))
            .body("AccountLimit.CodeSizeZipped", equalTo(52428800))
            .body("AccountLimit.ConcurrentExecutions", equalTo(1000))
            .body("AccountLimit.UnreservedConcurrentExecutions", equalTo(1000))
            .body("AccountUsage.TotalCodeSize", equalTo(0))
            .body("AccountUsage.FunctionCount", equalTo(0));
    }

    @Test
    void accountSettings_reflectsStoredFunctionsAndReservedConcurrency() {
        String authorization = auth("000000000402");
        int codeSize = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                    {
                        "FunctionName": "account-settings-fn",
                        "Runtime": "nodejs20.x",
                        "Role": "arn:aws:iam::000000000402:role/lambda-role",
                        "Handler": "index.handler",
                        "Code": {"ZipFile": "%s"}
                    }
                    """.formatted(zipBase64()))
                .when()
                .post("/2015-03-31/functions")
                .then()
                .statusCode(201)
                .extract().path("CodeSize");

        given()
            .contentType("application/json")
            .header("Authorization", authorization)
            .body("{\"ReservedConcurrentExecutions\": 25}")
        .when()
            .put("/2017-10-31/functions/account-settings-fn/concurrency")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", authorization)
        .when()
            .get(SETTINGS_PATH)
        .then()
            .statusCode(200)
            .body("AccountUsage.FunctionCount", equalTo(1))
            .body("AccountUsage.TotalCodeSize", equalTo(codeSize))
            .body("AccountLimit.ConcurrentExecutions", equalTo(1000))
            .body("AccountLimit.UnreservedConcurrentExecutions", equalTo(975));
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260101/us-east-1/lambda/aws4_request";
    }

    private static String zipBase64() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("index.js"));
                zip.write("exports.handler = async () => ({ ok: true });".getBytes());
                zip.closeEntry();
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
