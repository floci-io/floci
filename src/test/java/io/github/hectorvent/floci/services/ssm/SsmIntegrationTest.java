package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SsmIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void putParameter() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/app/db/host",
                    "Value": "localhost",
                    "Type": "String"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Version", equalTo(1));
    }

    @Test
    @Order(2)
    void getParameter() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/app/db/host",
                    "WithDecryption": true
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Name", equalTo("/app/db/host"))
            .body("Parameter.Value", equalTo("localhost"))
            .body("Parameter.Type", equalTo("String"))
            .body("Parameter.Version", equalTo(1));
    }

    @Test
    @Order(3)
    void putParameterOverwrite() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/app/db/host",
                    "Value": "db.example.com",
                    "Type": "String",
                    "Overwrite": true
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Version", equalTo(2));
    }

    @Test
    @Order(4)
    void putParameterWithoutOverwriteFails() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/app/db/host",
                    "Value": "other",
                    "Type": "String"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ParameterAlreadyExists"));
    }

    @Test
    @Order(5)
    void getParameterNotFound() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/nonexistent" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ParameterNotFound"));
    }

    @Test
    @Order(6)
    void getParametersByPath() {
        // Add more parameters
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/app/db/port", "Value": "5432", "Type": "String" }
                """)
        .when()
            .post("/");

        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/app/cache/host", "Value": "redis", "Type": "String" }
                """)
        .when()
            .post("/");

        // Query by path
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParametersByPath")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Path": "/app/db", "Recursive": true }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameters.size()", equalTo(2));
    }

    @Test
    @Order(7)
    void getParameters() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameters")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Names": ["/app/db/host", "/app/db/port", "/missing"] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameters.size()", equalTo(2))
            .body("InvalidParameters", contains("/missing"));
    }

    @Test
    @Order(8)
    void getParameterHistory() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameterHistory")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/app/db/host" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameters.size()", greaterThanOrEqualTo(2));
    }

    @Test
    @Order(9)
    void deleteParameter() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DeleteParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/app/cache/host" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify it's gone
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/app/cache/host" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ParameterNotFound"));
    }

    @Test
    @Order(10)
    void deleteParameters() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DeleteParameters")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Names": ["/app/db/host", "/app/db/port", "/missing"] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeletedParameters.size()", equalTo(2))
            .body("InvalidParameters", contains("/missing"));
    }

    // ── Service settings (LZA ssm-block-public-document-sharing) ──

    @Test
    @Order(11)
    void getServiceSettingDefault() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetServiceSetting")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "SettingId": "/ssm/documents/console/public-sharing-permission" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ServiceSetting.SettingId", equalTo("/ssm/documents/console/public-sharing-permission"))
            .body("ServiceSetting.SettingValue", equalTo("Enable"))
            .body("ServiceSetting.Status", equalTo("Default"))
            .body("ServiceSetting.ARN", endsWith(":servicesetting/ssm/documents/console/public-sharing-permission"))
            .body("ServiceSetting.LastModifiedDate", notNullValue());
    }

    @Test
    @Order(12)
    void updateServiceSetting() {
        given()
            .header("X-Amz-Target", "AmazonSSM.UpdateServiceSetting")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "SettingId": "/ssm/documents/console/public-sharing-permission",
                    "SettingValue": "Disable"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonSSM.GetServiceSetting")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "SettingId": "/ssm/documents/console/public-sharing-permission" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ServiceSetting.SettingValue", equalTo("Disable"))
            .body("ServiceSetting.Status", equalTo("Customized"));
    }

    @Test
    @Order(13)
    void resetServiceSetting() {
        given()
            .header("X-Amz-Target", "AmazonSSM.ResetServiceSetting")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "SettingId": "/ssm/documents/console/public-sharing-permission" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ServiceSetting.SettingValue", equalTo("Enable"))
            .body("ServiceSetting.Status", equalTo("Default"));
    }

    @Test
    @Order(14)
    void getUnknownServiceSettingReturnsError() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetServiceSetting")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "SettingId": "/ssm/bogus/does-not-exist" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ServiceSettingNotFound"));
    }

    // ── Issue #956: DescribePatchBaselines / GetDefaultPatchBaseline (AWS-owned predefined) ──

    @Test
    void describePatchBaselines_filteredByOwnerAndOperatingSystem() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribePatchBaselines")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Filters": [
                        {"Key": "OWNER", "Values": ["AWS"]},
                        {"Key": "OPERATING_SYSTEM", "Values": ["AMAZON_LINUX_2"]}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("BaselineIdentities.size()", equalTo(1))
            .body("BaselineIdentities[0].BaselineName", equalTo("AWS-AmazonLinux2DefaultPatchBaseline"))
            .body("BaselineIdentities[0].OperatingSystem", equalTo("AMAZON_LINUX_2"))
            .body("BaselineIdentities[0].DefaultBaseline", equalTo(true))
            .body("BaselineIdentities[0].BaselineId", startsWith("pb-"));
    }

    @Test
    void describePatchBaselines_byNamePrefixReturnsPredefined() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribePatchBaselines")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Filters": [
                        {"Key": "NAME_PREFIX", "Values": ["AWS-"]}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("BaselineIdentities.size()", greaterThan(1))
            .body("BaselineIdentities.BaselineName", everyItem(startsWith("AWS-")));
    }

    @Test
    void describePatchBaselines_ownerSelfReturnsEmpty() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribePatchBaselines")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Filters": [
                        {"Key": "OWNER", "Values": ["Self"]}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("BaselineIdentities.size()", equalTo(0));
    }

    @Test
    void getDefaultPatchBaseline_windows() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetDefaultPatchBaseline")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "OperatingSystem": "WINDOWS"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("OperatingSystem", equalTo("WINDOWS"))
            .body("BaselineId", startsWith("pb-"));
    }

    // ── Read-only list operations for resources not modeled (empty results) ──

    @Test
    void listDocuments_returnsEmptyList() {
        given()
            .header("X-Amz-Target", "AmazonSSM.ListDocuments")
            .contentType(SSM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentIdentifiers.size()", equalTo(0))
            .body("$", not(hasKey("NextToken")));
    }

    @Test
    void documentPermission_modifyAndDescribeRoundTrip() {
        given()
            .header("X-Amz-Target", "AmazonSSM.ModifyDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "AwsAccelerator-SessionManagerLogging",
                    "PermissionType": "Share",
                    "AccountIdsToAdd": ["444444444444"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "AwsAccelerator-SessionManagerLogging",
                    "PermissionType": "Share"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AccountIds", hasItem("444444444444"))
            .body("AccountSharingInfoList[0].AccountId", equalTo("444444444444"))
            .body("AccountSharingInfoList[0].SharedDocumentVersion", notNullValue());
    }

    @Test
    void listAssociations_returnsEmptyList() {
        given()
            .header("X-Amz-Target", "AmazonSSM.ListAssociations")
            .contentType(SSM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Associations.size()", equalTo(0))
            .body("$", not(hasKey("NextToken")));
    }

    @Test
    void describeMaintenanceWindows_returnsEmptyList() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeMaintenanceWindows")
            .contentType(SSM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WindowIdentities.size()", equalTo(0))
            .body("$", not(hasKey("NextToken")));
    }

    @Test
    void unsupportedOperation() {
        given()
            .header("X-Amz-Target", "AmazonSSM.UnsupportedAction")
            .contentType(SSM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedOperation"));
    }

    @Test
    void getDocument_unknownReturnsInvalidDocument() {
        given()
            .header("X-Amz-Target", "AmazonSSM.GetDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "No-Such-Document"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidDocument"));
    }

    @Test
    void document_createGetUpdateRoundTrip() {
        // Create — mirrors LZA's session-manager-settings Lambda (DocumentType Session)
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "SSM-SessionManagerRunShell",
                    "DocumentType": "Session",
                    "Content": "{\\"schemaVersion\\":\\"1.0\\",\\"inputs\\":{\\"runAsEnabled\\":false}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentDescription.Name", equalTo("SSM-SessionManagerRunShell"))
            .body("DocumentDescription.DocumentType", equalTo("Session"))
            .body("DocumentDescription.DocumentVersion", equalTo("1"))
            .body("DocumentDescription.Status", equalTo("Active"));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "SSM-SessionManagerRunShell"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Name", equalTo("SSM-SessionManagerRunShell"))
            .body("DocumentType", equalTo("Session"))
            .body("DocumentVersion", equalTo("1"))
            .body("Content", containsString("runAsEnabled"));

        // Update with changed content bumps the version
        given()
            .header("X-Amz-Target", "AmazonSSM.UpdateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "SSM-SessionManagerRunShell",
                    "DocumentVersion": "$LATEST",
                    "Content": "{\\"schemaVersion\\":\\"1.0\\",\\"inputs\\":{\\"runAsEnabled\\":true}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentDescription.DocumentVersion", equalTo("2"));

        // Update with identical content fails DuplicateDocumentContent
        given()
            .header("X-Amz-Target", "AmazonSSM.UpdateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "SSM-SessionManagerRunShell",
                    "DocumentVersion": "$LATEST",
                    "Content": "{\\"schemaVersion\\":\\"1.0\\",\\"inputs\\":{\\"runAsEnabled\\":true}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("DuplicateDocumentContent"));
    }
}
