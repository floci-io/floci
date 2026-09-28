package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies GenerateCredentialReport and GetCredentialReport against the real wire shape.
 * IAM state, including the one stored credential report, is shared across the whole test
 * suite, so a report may already exist for this account by the time these tests run: none of
 * them assume a specific prior State, only that a Generate/Get pair always succeeds and that
 * the CSV content is well-formed.
 */
@QuarkusTest
class CredentialReportIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static io.restassured.specification.RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    @Test
    void generateCredentialReportReturnsAValidState() {
        iam("GenerateCredentialReport")
        .when().post("/").then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GenerateCredentialReportResponse.GenerateCredentialReportResult.State",
                    anyOf(equalTo("STARTED"), equalTo("COMPLETE")));
    }

    @Test
    void getCredentialReportAfterGenerateReturnsWellFormedCsv() {
        iam("GenerateCredentialReport")
        .when().post("/").then().statusCode(200);

        String content = iam("GetCredentialReport")
            .when().post("/").then()
                .statusCode(200)
                .contentType("application/xml")
                .body("GetCredentialReportResponse.GetCredentialReportResult.ReportFormat", equalTo("text/csv"))
                .extract().path("GetCredentialReportResponse.GetCredentialReportResult.Content");

        String csv = new String(Base64.getDecoder().decode(content));
        assertTrue(csv.startsWith("user,arn,user_creation_time,password_enabled,password_last_used,"
                        + "password_last_changed,password_next_rotation,mfa_active,"),
                "expected the documented column header, got: " + csv);
        assertTrue(csv.contains("<root_account>,arn:aws:iam::"), "expected a root account row, got: " + csv);
    }

    @Test
    void getCredentialReportIncludesAUserCreatedAfterTheLastGenerate() {
        String user = "cred-report-it-user-" + Long.toString(System.nanoTime(), 36);
        iam("CreateUser").formParam("UserName", user)
            .when().post("/").then().statusCode(200);
        iam("GenerateCredentialReport")
        .when().post("/").then().statusCode(200);

        String content = iam("GetCredentialReport")
            .when().post("/").then().statusCode(200)
            .extract().path("GetCredentialReportResponse.GetCredentialReportResult.Content");
        String csv = new String(Base64.getDecoder().decode(content));

        assertTrue(csv.lines().anyMatch(line -> line.startsWith(user + ",")),
                "expected a row for " + user + ", got: " + csv);
    }

    @Test
    void generateCredentialReportResponseHasNoOtherUnexpectedElements() {
        iam("GenerateCredentialReport")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<GenerateCredentialReportResponse"))
            .body(containsString("<State>"))
            .body(containsString("<Description>"));
    }
}
