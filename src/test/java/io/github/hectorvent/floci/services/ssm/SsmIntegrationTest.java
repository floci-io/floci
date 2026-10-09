package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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

    // ── SettingValue (botocore: ServiceSettingValue, required, min 1 / max 4096) ──

    @Test
    void updateServiceSetting_missingSettingValueReturnsValidationException() {
        given()
            .header("X-Amz-Target", "AmazonSSM.UpdateServiceSetting")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "SettingId": "/ssm/documents/console/public-sharing-permission" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void updateServiceSetting_whitespaceOnlySettingValueIsStoredVerbatim() {
        given()
            .header("X-Amz-Target", "AmazonSSM.UpdateServiceSetting")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "SettingId": "/ssm/parameter-store/default-parameter-tier",
                    "SettingValue": " "
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
                { "SettingId": "/ssm/parameter-store/default-parameter-tier" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ServiceSetting.SettingValue", equalTo(" "));
    }

    @Test
    void updateServiceSetting_settingValueOverMaxLengthReturnsValidationException() {
        String tooLong = "x".repeat(4097);
        given()
            .header("X-Amz-Target", "AmazonSSM.UpdateServiceSetting")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "SettingId": "/ssm/documents/console/public-sharing-permission",
                    "SettingValue": "%s"
                }
                """.formatted(tooLong))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void serviceSettingOperations_missingSettingIdReturnsValidationException() {
        for (String target : new String[]{"GetServiceSetting", "UpdateServiceSetting", "ResetServiceSetting"}) {
            given()
                .header("X-Amz-Target", "AmazonSSM." + target)
                .contentType(SSM_CONTENT_TYPE)
                .body("""
                    { "SettingValue": "Enable" }
                    """)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }
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

    @Test
    void listDocuments_unmatchedNameFilterReturnsEmptyList() {
        given()
            .header("X-Amz-Target", "AmazonSSM.ListDocuments")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Filters": [
                        {"Key": "Name", "Values": ["non-existent-doc-xyz-123"]}
                    ]
                }
                """)
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
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "AwsAccelerator-SessionManagerLogging",
                    "DocumentType": "Session",
                    "Content": "{\\"schemaVersion\\":\\"1.0\\"}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

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
    void listAssociations_unmatchedInstanceFilterReturnsEmptyList() {
        given()
            .header("X-Amz-Target", "AmazonSSM.ListAssociations")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "AssociationFilterList": [
                        {"key": "InstanceId", "value": "i-nonexistent-000"}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Associations.size()", equalTo(0))
            .body("$", not(hasKey("NextToken")));
    }

    // ── Read-only list operations for resources not modeled (empty results) ──

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

    // ── Document parameter validation (botocore: DocumentName ^[a-zA-Z0-9_\-.]{3,128}$) ──

    @Test
    void documentOperations_blankNameReturnsValidationException() {
        for (String target : new String[]{
                "GetDocument", "DescribeDocument", "DeleteDocument",
                "CreateDocument", "UpdateDocument"}) {
            given()
                .header("X-Amz-Target", "AmazonSSM." + target)
                .contentType(SSM_CONTENT_TYPE)
                .body("{}")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }
    }

    @Test
    void documentOperations_nameViolatingPatternReturnsValidationException() {
        for (String target : new String[]{"DeleteDocument", "CreateDocument", "UpdateDocument"}) {
            given()
                .header("X-Amz-Target", "AmazonSSM." + target)
                .contentType(SSM_CONTENT_TYPE)
                .body("""
                    {
                        "Name": "/floci/test-doc",
                        "Content": "{\\"schemaVersion\\":\\"1.0\\"}"
                    }
                    """)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }
    }

    // GetDocument/DescribeDocument model a wider Name pattern than the other five document
    // operations (botocore: ^[a-zA-Z0-9_\-.:/]{3,128}$, vs ^[a-zA-Z0-9_\-.]{3,128}$ elsewhere) —
    // DescribeDocument's own docs say Name is the document's ARN when reading a document shared
    // from another account, so the read path must accept ARN shapes even though the other five
    // reject them.
    @Test
    void getDocumentAndDescribeDocument_acceptArnShapedName() {
        for (String target : new String[]{"GetDocument", "DescribeDocument"}) {
            given()
                .header("X-Amz-Target", "AmazonSSM." + target)
                .contentType(SSM_CONTENT_TYPE)
                .body("""
                    {
                        "Name": "arn:aws:ssm:us-east-1:000000000000:document/No-Such-Document"
                    }
                    """)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidDocument"));
        }
    }

    @Test
    void getDocumentAndDescribeDocument_stillRejectTrulyInvalidName() {
        for (String target : new String[]{"GetDocument", "DescribeDocument"}) {
            given()
                .header("X-Amz-Target", "AmazonSSM." + target)
                .contentType(SSM_CONTENT_TYPE)
                .body("""
                    {
                        "Name": "not a valid name!"
                    }
                    """)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }
    }

    @Test
    void documentPermissionOperations_blankNameReturnsValidationException() {
        for (String target : new String[]{
                "ModifyDocumentPermission", "DescribeDocumentPermission"}) {
            given()
                .header("X-Amz-Target", "AmazonSSM." + target)
                .contentType(SSM_CONTENT_TYPE)
                .body("""
                    { "PermissionType": "Share" }
                    """)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }
    }

    @Test
    void createDocument_missingContentReturnsValidationException() {
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "Floci-Missing-Content-Doc" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createDocument_nonTextContentReturnsValidationException() {
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Floci-Object-Content-Doc",
                    "Content": {"schemaVersion": "2.2", "mainSteps": []}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void updateDocument_missingContentReturnsValidationExceptionWithoutErasingExistingContent() {
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Floci-Update-Missing-Content-Doc",
                    "Content": "{\\"schemaVersion\\":\\"1.0\\"}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonSSM.UpdateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "Floci-Update-Missing-Content-Doc" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "Floci-Update-Missing-Content-Doc" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Content", containsString("schemaVersion"));
    }

    // ── PermissionType (botocore: required, enum with the single value "Share") ──

    @Test
    void documentPermissionOperations_rejectUnsupportedPermissionType() {
        for (String target : new String[]{
                "ModifyDocumentPermission", "DescribeDocumentPermission"}) {
            given()
                .header("X-Amz-Target", "AmazonSSM." + target)
                .contentType(SSM_CONTENT_TYPE)
                .body("""
                    {
                        "Name": "Doc-That-Was-Never-Created",
                        "PermissionType": "Own"
                    }
                    """)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidPermissionType"));
        }
    }

    @Test
    void documentPermissionOperations_missingPermissionTypeIsRejected() {
        for (String target : new String[]{
                "ModifyDocumentPermission", "DescribeDocumentPermission"}) {
            given()
                .header("X-Amz-Target", "AmazonSSM." + target)
                .contentType(SSM_CONTENT_TYPE)
                .body("""
                    { "Name": "Doc-That-Was-Never-Created" }
                    """)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }
    }

    @Test
    void deleteDocument_clearsSharePermissionsSoARecreatedDocumentStartsUnshared() {
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Floci-Recreated-Shared-Doc",
                    "Content": "{\\"schemaVersion\\":\\"1.0\\"}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonSSM.ModifyDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Floci-Recreated-Shared-Doc",
                    "PermissionType": "Share",
                    "AccountIdsToAdd": ["444444444444"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonSSM.DeleteDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "Floci-Recreated-Shared-Doc" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Floci-Recreated-Shared-Doc",
                    "Content": "{\\"schemaVersion\\":\\"1.0\\"}"
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
                    "Name": "Floci-Recreated-Shared-Doc",
                    "PermissionType": "Share"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AccountIds", empty());
    }

    // ── Permission ops agree with the document store (botocore models InvalidDocument) ──

    @Test
    void documentPermissionOperations_unknownDocumentReturnsInvalidDocument() {
        for (String target : new String[]{
                "ModifyDocumentPermission", "DescribeDocumentPermission"}) {
            given()
                .header("X-Amz-Target", "AmazonSSM." + target)
                .contentType(SSM_CONTENT_TYPE)
                .body("""
                    {
                        "Name": "Doc-That-Was-Never-Created",
                        "PermissionType": "Share",
                        "AccountIdsToAdd": ["444444444444"]
                    }
                    """)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidDocument"));
        }
    }

    /**
     * Only the document's owner may share it. Ownership is the storage partition:
     * the document store is account-aware, so another account's ModifyDocumentPermission
     * cannot resolve the document and gets InvalidDocument — AWS's own answer for a
     * document the caller cannot see.
     */
    @Test
    void modifyDocumentPermission_otherAccountCannotShareOwnersDocument() {
        String ownerAuth = "AWS4-HMAC-SHA256 Credential=000000000001/20260215/us-east-1/ssm/aws4_request,"
                + " SignedHeaders=host, Signature=abc";
        String otherAuth = "AWS4-HMAC-SHA256 Credential=000000000002/20260215/us-east-1/ssm/aws4_request,"
                + " SignedHeaders=host, Signature=abc";

        given()
            .header("Authorization", ownerAuth)
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Owner-Only-Document",
                    "DocumentType": "Session",
                    "Content": "{\\"schemaVersion\\":\\"1.0\\"}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", otherAuth)
            .header("X-Amz-Target", "AmazonSSM.ModifyDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Owner-Only-Document",
                    "PermissionType": "Share",
                    "AccountIdsToAdd": ["444444444444"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidDocument"));

        // …and the owner's own share state is untouched.
        given()
            .header("Authorization", ownerAuth)
            .header("X-Amz-Target", "AmazonSSM.DescribeDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Owner-Only-Document",
                    "PermissionType": "Share"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AccountIds", not(hasItem("444444444444")));
    }

    // ── DocumentType (botocore: enum, 17 values) ──

    @Test
    void createDocument_rejectsAnUnmodelledDocumentType() {
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Bad-Document-Type",
                    "DocumentType": "NotADocumentType",
                    "Content": "{\\"schemaVersion\\":\\"1.0\\"}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        // The rejected document must not have been stored.
        given()
            .header("X-Amz-Target", "AmazonSSM.GetDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "Bad-Document-Type" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidDocument"));
    }

    @Test
    void createDocument_acceptsEveryModelledDocumentType() {
        String[] documentTypes = {
                "Command", "Policy", "Automation", "Session", "Package",
                "ApplicationConfiguration", "ApplicationConfigurationSchema", "DeploymentStrategy",
                "ChangeCalendar", "Automation.ChangeTemplate", "ProblemAnalysis",
                "ProblemAnalysisTemplate", "CloudFormation", "ConformancePackTemplate",
                "QuickSetup", "ManualApprovalPolicy", "AutoApprovalPolicy"};
        for (int i = 0; i < documentTypes.length; i++) {
            given()
                .header("X-Amz-Target", "AmazonSSM.CreateDocument")
                .contentType(SSM_CONTENT_TYPE)
                .body("""
                    {
                        "Name": "Modelled-Type-%d",
                        "DocumentType": "%s",
                        "Content": "{\\"schemaVersion\\":\\"1.0\\",\\"n\\":%d}"
                    }
                    """.formatted(i, documentTypes[i], i))
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DocumentDescription.DocumentType", equalTo(documentTypes[i]));
        }
    }

    @Test
    void modifyDocumentPermission_nonListAccountIdsToAddReturnsValidationException() {
        createSharableDocument("Non-List-Account-Ids-Document");

        given()
            .header("X-Amz-Target", "AmazonSSM.ModifyDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Non-List-Account-Ids-Document",
                    "PermissionType": "Share",
                    "AccountIdsToAdd": "444444444444"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        // Nothing may have been shared — the scalar must not be silently treated as empty.
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "Non-List-Account-Ids-Document", "PermissionType": "Share" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AccountIds", empty());
    }

    // ── AccountIdsToAdd/Remove (botocore: list max 20, member (?i)all|[0-9]{12}) ──

    @Test
    void modifyDocumentPermission_rejectsAnAccountIdThatIsNotAnAccountId() {
        createSharableDocument("Bad-Account-Id-Document");

        given()
            .header("X-Amz-Target", "AmazonSSM.ModifyDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Bad-Account-Id-Document",
                    "PermissionType": "Share",
                    "AccountIdsToAdd": ["not-an-account"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        // Nothing may have been shared.
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "Bad-Account-Id-Document", "PermissionType": "Share" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AccountIds", not(hasItem("not-an-account")));
    }

    /** The model spells the wildcard {@code (?i)all}, so "all" and "All" are both account ids. */
    @Test
    void modifyDocumentPermission_acceptsTheAllWildcard() {
        createSharableDocument("All-Wildcard-Document");

        given()
            .header("X-Amz-Target", "AmazonSSM.ModifyDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "All-Wildcard-Document",
                    "PermissionType": "Share",
                    "AccountIdsToAdd": ["All"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void modifyDocumentPermission_rejectsMoreThanTwentyAccountIds() {
        createSharableDocument("Too-Many-Accounts-Document");

        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            ids.append(i == 0 ? "" : ",").append("\"%012d\"".formatted(100000000000L + i));
        }

        given()
            .header("X-Amz-Target", "AmazonSSM.ModifyDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "Too-Many-Accounts-Document",
                    "PermissionType": "Share",
                    "AccountIdsToAdd": [%s]
                }
                """.formatted(ids))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    // ── ModifyDocumentPermission: at least one of AccountIdsToAdd/AccountIdsToRemove
    // required (botocore: documented on both members, not a JSON `required` or shape
    // constraint — "You must specify a value for this parameter or the
    // AccountIdsToRemove/AccountIdsToAdd parameter.") ──

    @Test
    void modifyDocumentPermission_neitherAccountListSpecifiedReturnsValidationException() {
        createSharableDocument("No-Account-Lists-Document");

        given()
            .header("X-Amz-Target", "AmazonSSM.ModifyDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "No-Account-Lists-Document",
                    "PermissionType": "Share"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeDocumentPermission")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "No-Account-Lists-Document", "PermissionType": "Share" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AccountIds", empty());
    }

    @Test
    @Order(15)
    void publicAmiParametersResolveWithoutSetup() {
        String al2023 = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64";

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "%s" }
                """.formatted(al2023))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Name", equalTo(al2023))
            .body("Parameter.Value", equalTo("ami-0abcdef1234567891"))
            .body("Parameter.Type", equalTo("String"))
            .body("Parameter.Version", equalTo(1))
            .body("Parameter.ARN", equalTo("arn:aws:ssm:us-east-1::parameter" + al2023));

        String al2023Arm64 = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64";

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "%s" }
                """.formatted(al2023Arm64))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Name", equalTo(al2023Arm64))
            .body("Parameter.Value", equalTo("ami-amazonlinux2023-arm64"))
            .body("Parameter.Type", equalTo("String"))
            .body("Parameter.Version", equalTo(1))
            .body("Parameter.ARN", equalTo("arn:aws:ssm:us-east-1::parameter" + al2023Arm64));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameters")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Names": ["%s", "/aws/service/ami-amazon-linux-latest/no-such-variant"] }
                """.formatted(al2023))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameters.Name", contains(al2023))
            .body("InvalidParameters", contains("/aws/service/ami-amazon-linux-latest/no-such-variant"));

        String eksOptimizedAmi = "/aws/service/eks/optimized-ami/1.31/amazon-linux-2023/x86_64/standard/recommended/image_id";

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "%s" }
                """.formatted(eksOptimizedAmi))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Name", equalTo(eksOptimizedAmi))
            .body("Parameter.Value", equalTo("ami-0abcdef1234567891"))
            .body("Parameter.Type", equalTo("String"))
            .body("Parameter.Version", equalTo(1))
            .body("Parameter.ARN", equalTo("arn:aws:ssm:us-east-1::parameter" + eksOptimizedAmi));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParametersByPath")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Path": "/aws/service/ami-amazon-linux-latest" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameters.Name", hasItems(al2023, al2023Arm64,
                    "/aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-gp2"));

        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeParameters")
            .contentType(SSM_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameters.Name", not(hasItem(al2023)));
    }

    @Test
    @Order(16)
    void putParameterRejectsReservedPrefixes() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64",
                    "Value": "ami-mine",
                    "Type": "String",
                    "Overwrite": true
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("can't be prefixed with \"aws\" or \"ssm\""));

        // Only the aws and ssm path segments are reserved, so a CloudFormation-generated name
        // for a stack called ssm-auto-stack still writes.
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "ssm-auto-stack-Param-ABC123", "Value": "ok", "Type": "String" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Version", equalTo(1));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Value", equalTo("ami-0abcdef1234567891"));
    }

    private static void createSharableDocument(String name) {
        given()
            .header("X-Amz-Target", "AmazonSSM.CreateDocument")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "%s",
                    "DocumentType": "Session",
                    "Content": "{\\"schemaVersion\\":\\"1.0\\",\\"doc\\":\\"%s\\"}"
                }
                """.formatted(name, name))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(17)
    void putParameterWithTagsAndListTagsForResource() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/demo/tagged-param",
                    "Value": "hello",
                    "Type": "String",
                    "Tags": [
                        {"Key": "Project", "Value": "demo"},
                        {"Key": "Env", "Value": "test"}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Version", equalTo(1));

        given()
            .header("X-Amz-Target", "AmazonSSM.ListTagsForResource")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "ResourceType": "Parameter",
                    "ResourceId": "/demo/tagged-param"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagList", hasSize(2))
            .body("TagList.find { it.Key == 'Project' }.Value", equalTo("demo"))
            .body("TagList.find { it.Key == 'Env' }.Value", equalTo("test"));
    }

    @Test
    @Order(18)
    void putParameterOverwritePreservesExistingTags() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/demo/tagged-param",
                    "Value": "world",
                    "Type": "String",
                    "Overwrite": true
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Version", equalTo(2));

        given()
            .header("X-Amz-Target", "AmazonSSM.ListTagsForResource")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "ResourceType": "Parameter",
                    "ResourceId": "/demo/tagged-param"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagList", hasSize(2))
            .body("TagList.find { it.Key == 'Project' }.Value", equalTo("demo"));
    }

    @Test
    @Order(19)
    void putParameterOverwriteWithTagsReturns400() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/demo/tagged-param",
                    "Value": "updated",
                    "Type": "String",
                    "Overwrite": true,
                    "Tags": [
                        {"Key": "Project", "Value": "demo2"}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void addTagsToResourceWithAKeyOutsideTheAwsPatternReturns400BeforeTheLookup() {
        given()
            .header("X-Amz-Target", "AmazonSSM.AddTagsToResource")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "ResourceType": "Parameter",
                    "ResourceId": "/demo/no-such-param-for-tag-validation",
                    "Tags": [
                        {"Key": "a,b", "Value": "x"}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value at 'tags.1.member.key' failed to"
                    + " satisfy constraint: Member must satisfy regular expression pattern:"
                    + " ^([\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]*)$"));
    }

    @Test
    @Order(20)
    void describeParametersAppliesParameterFilters() {
        putFilterFixture("/dpf/prod/db", "String", "");
        putFilterFixture("/dpf/prod/api/key", "SecureString", "");
        putFilterFixture("/dpf/dev/db", "String", ", \"Tags\": [{\"Key\": \"Team\", \"Value\": \"core\"}]");

        describeParameters("""
                { "ParameterFilters": [{ "Key": "Path", "Values": ["/dpf/prod"] }] }
                """)
            .body("Parameters.Name", contains("/dpf/prod/db"));

        describeParameters("""
                { "ParameterFilters": [{ "Key": "Path", "Option": "Recursive", "Values": ["/dpf/prod"] }] }
                """)
            .body("Parameters.Name", containsInAnyOrder("/dpf/prod/db", "/dpf/prod/api/key"));

        describeParameters("""
                { "ParameterFilters": [
                    { "Key": "Name", "Option": "BeginsWith", "Values": ["/dpf/"] },
                    { "Key": "Type", "Values": ["SecureString"] }
                ] }
                """)
            .body("Parameters.Name", contains("/dpf/prod/api/key"));

        describeParameters("""
                { "ParameterFilters": [{ "Key": "tag:Team", "Values": ["core"] }] }
                """)
            .body("Parameters.Name", contains("/dpf/dev/db"));

        describeParameters("""
                { "Filters": [{ "Key": "Name", "Values": ["/dpf/dev/db"] }] }
                """)
            .body("Parameters.Name", contains("/dpf/dev/db"));
    }

    @Test
    @Order(21)
    void describeParametersPagesWithMaxResultsAndNextToken() {
        String filter = "\"ParameterFilters\": [{ \"Key\": \"Name\", \"Option\": \"BeginsWith\", \"Values\": [\"/dpf/\"] }]";

        String token = describeParameters("{ " + filter + ", \"MaxResults\": 2 }")
            .body("Parameters.Name", contains("/dpf/dev/db", "/dpf/prod/api/key"))
            .body("NextToken", notNullValue())
            .extract().path("NextToken");

        describeParameters("{ " + filter + ", \"MaxResults\": 2, \"NextToken\": \"" + token + "\" }")
            .body("Parameters.Name", contains("/dpf/prod/db"))
            .body("NextToken", nullValue());
    }

    @Test
    @Order(22)
    void describeParametersRejectsUnsupportedFilters() {
        describeParametersError("""
                { "ParameterFilters": [{ "Key": "Label", "Values": ["prod"] }] }
                """, "InvalidFilterKey");
        describeParametersError("""
                { "ParameterFilters": [{ "Key": "Type", "Option": "Contains", "Values": ["String"] }] }
                """, "InvalidFilterOption");
        describeParametersError("""
                { "ParameterFilters": [{ "Key": "Path", "Values": ["dpf"] }] }
                """, "InvalidFilterValue");
        describeParametersError("{ \"MaxResults\": 51 }", "ValidationException");
    }

    @Test
    void describeParametersReturnsKeyIdAllowedPatternTierAndPolicies() {
        String policy = "{\\\"Type\\\":\\\"Expiration\\\",\\\"Version\\\":\\\"1.0\\\","
                + "\\\"Attributes\\\":{\\\"Timestamp\\\":\\\"2099-01-01T00:00:00.000Z\\\"}}";
        putFilterFixture("/attr/secure", "SecureString", ", \"KeyId\": \"alias/custom\", \"AllowedPattern\": \"^v$\","
                + " \"Tier\": \"Advanced\", \"Policies\": \"[" + policy + "]\"");
        putFilterFixture("/attr/default-key", "SecureString", "");
        putFilterFixture("/attr/plain", "String", "");

        String policyText = describeParameters("""
                { "ParameterFilters": [{ "Key": "Name", "Values": ["/attr/secure"] }] }
                """)
            .body("Parameters[0].KeyId", equalTo("alias/custom"))
            .body("Parameters[0].AllowedPattern", equalTo("^v$"))
            .body("Parameters[0].Tier", equalTo("Advanced"))
            .body("Parameters[0].Policies", hasSize(1))
            .body("Parameters[0].Policies[0].PolicyType", equalTo("Expiration"))
            .body("Parameters[0].Policies[0].PolicyStatus", equalTo("Pending"))
            .extract().path("Parameters[0].Policies[0].PolicyText");
        assertEquals("2099-01-01T00:00:00.000Z", JsonPath.from(policyText).getString("Attributes.Timestamp"));

        describeParameters("""
                { "ParameterFilters": [{ "Key": "Name", "Values": ["/attr/default-key"] }] }
                """)
            .body("Parameters[0].KeyId", equalTo("alias/aws/ssm"))
            .body("Parameters[0].Tier", equalTo("Standard"))
            .body("Parameters[0]", not(hasKey("AllowedPattern")))
            .body("Parameters[0]", not(hasKey("Policies")));
        describeParameters("""
                { "ParameterFilters": [{ "Key": "Name", "Values": ["/attr/plain"] }] }
                """)
            .body("Parameters[0]", not(hasKey("KeyId")))
            .body("Parameters[0].Tier", equalTo("Standard"));

        // The stored tier and key, not the defaults, are what the filters see.
        describeParameters("""
                { "ParameterFilters": [
                    { "Key": "Name", "Option": "BeginsWith", "Values": ["/attr/"] },
                    { "Key": "Tier", "Values": ["Advanced"] }
                ] }
                """)
            .body("Parameters.Name", contains("/attr/secure"));
        describeParameters("""
                { "ParameterFilters": [
                    { "Key": "Name", "Option": "BeginsWith", "Values": ["/attr/"] },
                    { "Key": "KeyId", "Values": ["alias/custom"] }
                ] }
                """)
            .body("Parameters.Name", contains("/attr/secure"));

        // An overwrite that omits Tier keeps the Advanced tier.
        putFilterFixture("/attr/secure", "SecureString", ", \"Overwrite\": true");
        describeParameters("""
                { "ParameterFilters": [{ "Key": "Name", "Values": ["/attr/secure"] }] }
                """)
            .body("Parameters[0].Version", equalTo(2))
            .body("Parameters[0].Tier", equalTo("Advanced"));
    }

    @Test
    void putParameterIntelligentTieringResolvesToAdvancedOnlyWhenNeeded() {
        putFilterFixture("/attr-it/small", "String", ", \"Tier\": \"Intelligent-Tiering\"");
        putFilterFixture("/attr-it/policy", "String", ", \"Tier\": \"Intelligent-Tiering\", \"Policies\":"
                + " \"[{\\\"Type\\\":\\\"NoChangeNotification\\\",\\\"Version\\\":\\\"1.0\\\","
                + "\\\"Attributes\\\":{\\\"After\\\":\\\"30\\\",\\\"Unit\\\":\\\"Days\\\"}}]\"");
        describeParameters("""
                { "ParameterFilters": [{ "Key": "Path", "Values": ["/attr-it"] }] }
                """)
            .body("Parameters.find { it.Name == '/attr-it/small' }.Tier", equalTo("Standard"))
            .body("Parameters.find { it.Name == '/attr-it/policy' }.Tier", equalTo("Advanced"));
    }

    @Test
    void putParameterIntelligentTieringCutsOverAbove4096Utf8Bytes() {
        // An e-acute is one char but two UTF-8 bytes, so the multibyte values sit under 4096 chars.
        putIntelligentTiering("/attr-it-size/ascii-4096", "a".repeat(4096));
        putIntelligentTiering("/attr-it-size/ascii-4097", "a".repeat(4097));
        putIntelligentTiering("/attr-it-size/utf8-4096", "\\u00e9".repeat(2048));
        putIntelligentTiering("/attr-it-size/utf8-4098", "\\u00e9".repeat(2049));
        describeParameters("""
                { "ParameterFilters": [{ "Key": "Path", "Values": ["/attr-it-size"] }] }
                """)
            .body("Parameters.find { it.Name == '/attr-it-size/ascii-4096' }.Tier", equalTo("Standard"))
            .body("Parameters.find { it.Name == '/attr-it-size/ascii-4097' }.Tier", equalTo("Advanced"))
            .body("Parameters.find { it.Name == '/attr-it-size/utf8-4096' }.Tier", equalTo("Standard"))
            .body("Parameters.find { it.Name == '/attr-it-size/utf8-4098' }.Tier", equalTo("Advanced"));
    }

    @Test
    void putParameterIntelligentTieringOverwriteKeepsAnAdvancedParameterAdvanced() {
        putFilterFixture("/attr-it-keep/p", "String", ", \"Tier\": \"Advanced\"");
        putFilterFixture("/attr-it-keep/p", "String", ", \"Tier\": \"Intelligent-Tiering\", \"Overwrite\": true");
        describeParameters("""
                { "ParameterFilters": [{ "Key": "Name", "Values": ["/attr-it-keep/p"] }] }
                """)
            .body("Parameters[0].Version", equalTo(2))
            .body("Parameters[0].Tier", equalTo("Advanced"));
    }

    @Test
    void putParameterRejectsATierOutsideTheEnumAndStoresNothing() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/attr-bad-tier/p", "Value": "v", "Type": "String", "Tier": "Premium" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("Value 'Premium' at 'tier'"));
        describeParameters("""
                { "ParameterFilters": [{ "Key": "Name", "Values": ["/attr-bad-tier/p"] }] }
                """)
            .body("Parameters", hasSize(0));
    }

    private void putIntelligentTiering(String name, String jsonEscapedValue) {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("{ \"Name\": \"" + name + "\", \"Value\": \"" + jsonEscapedValue
                    + "\", \"Type\": \"String\", \"Tier\": \"Intelligent-Tiering\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void putParameterRejectsAValueOutsideAllowedPattern() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/attr/pattern", "Value": "nope", "Type": "String", "AllowedPattern": "^v$" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ParameterPatternMismatchException"));
    }

    @Test
    void putParameterTreatsAnEmptyAllowedPatternAsNoPattern() {
        putFilterFixture("/attr/empty-pattern", "String", ", \"AllowedPattern\": \"\"");
        describeParameters("""
                { "ParameterFilters": [{ "Key": "Name", "Values": ["/attr/empty-pattern"] }] }
                """)
            .body("Parameters[0].Version", equalTo(1))
            .body("Parameters[0]", not(hasKey("AllowedPattern")));
    }

    @Test
    void putParameterOverwriteKeepsPoliciesUntilNewOrEmptyPoliciesAreSent() {
        putFilterFixture("/pol/keep", "String", ", \"Tier\": \"Advanced\", \"Policies\":"
                + " \"[{\\\"Type\\\":\\\"NoChangeNotification\\\",\\\"Version\\\":\\\"1.0\\\","
                + "\\\"Attributes\\\":{\\\"After\\\":\\\"30\\\",\\\"Unit\\\":\\\"Days\\\"}}]\"");
        String byName = """
                { "ParameterFilters": [{ "Key": "Name", "Values": ["/pol/keep"] }] }
                """;

        putFilterFixture("/pol/keep", "String", ", \"Overwrite\": true");
        describeParameters(byName)
            .body("Parameters[0].Version", equalTo(2))
            .body("Parameters[0].Policies.PolicyType", contains("NoChangeNotification"));

        putFilterFixture("/pol/keep", "String", ", \"Overwrite\": true, \"Policies\": \"[]\"");
        describeParameters(byName)
            .body("Parameters[0].Version", equalTo(3))
            .body("Parameters[0]", not(hasKey("Policies")));
    }

    @Test
    void putParameterRejectsMalformedAllowedPattern() {
        putParameterError("""
                { "Name": "/pol/bad-pattern", "Value": "v", "Type": "String", "AllowedPattern": "[" }
                """, "InvalidAllowedPatternException");
    }

    @Test
    void putParameterBoundsCatastrophicAllowedPatternBacktracking() {
        // Nested groups defeat the JDK's loop memoization; unbounded, this match runs for hours.
        String body = "{ \"Name\": \"/pol/redos\", \"Value\": \"" + "a".repeat(40)
                + "!\", \"Type\": \"String\", \"AllowedPattern\": \"^((a+)+)+$\" }";
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> putParameterError(body, "InvalidAllowedPatternException"));
    }

    @Test
    void putParameterRejectsAllowedPatternOver1024Chars() {
        putParameterError("{ \"Name\": \"/pol/long-pattern\", \"Value\": \"a\", \"Type\": \"String\","
                + " \"AllowedPattern\": \"" + "a".repeat(1025) + "\" }", "ValidationException");
    }

    @Test
    void putParameterRejectsPoliciesThatAreNotAJsonArray() {
        putParameterError("""
                { "Name": "/pol/bad-policies", "Value": "v", "Type": "String", "Policies": "{}" }
                """, "ValidationException");
    }

    @Test
    void putParameterTreatsEmptyOptionalFieldsAsAbsent() {
        // Terraform's aws_ssm_parameter sends every optional field, unset ones as "".
        putFilterFixture("/empty/plain", "String",
                ", \"AllowedPattern\": \"\", \"Tier\": \"\", \"Policies\": \"\"");
        putFilterFixture("/empty/secure", "SecureString", ", \"KeyId\": \"\", \"AllowedPattern\": \"\"");

        describeParameters("""
                { "ParameterFilters": [{ "Key": "Name", "Values": ["/empty/plain"] }] }
                """)
            .body("Parameters[0]", not(hasKey("AllowedPattern")))
            .body("Parameters[0]", not(hasKey("Policies")))
            .body("Parameters[0].Tier", equalTo("Standard"));
        describeParameters("""
                { "ParameterFilters": [{ "Key": "Name", "Values": ["/empty/secure"] }] }
                """)
            .body("Parameters[0].KeyId", equalTo("alias/aws/ssm"))
            .body("Parameters[0]", not(hasKey("AllowedPattern")));
    }

    private void putParameterError(String body, String errorType) {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo(errorType));
    }

    private void putFilterFixture(String name, String type, String extra) {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("{ \"Name\": \"" + name + "\", \"Value\": \"v\", \"Type\": \"" + type + "\"" + extra + " }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void getParameterWithVersionAndLabelSelectors() {
        for (String value : new String[] {"key1", "key2"}) {
            given()
                .header("X-Amz-Target", "AmazonSSM.PutParameter")
                .contentType(SSM_CONTENT_TYPE)
                .body("""
                    { "Name": "/selector/param", "Value": "%s", "Type": "SecureString", "Overwrite": true }
                    """.formatted(value))
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
        given()
            .header("X-Amz-Target", "AmazonSSM.LabelParameterVersion")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/selector/param", "ParameterVersion": 1, "Labels": ["previous", "2", "aws:reserved"] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ParameterVersion", equalTo(1))
            .body("InvalidLabels", containsInAnyOrder("2", "aws:reserved"));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/selector/param:1", "WithDecryption": true }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Name", equalTo("/selector/param"))
            .body("Parameter.Selector", equalTo(":1"))
            .body("Parameter.Value", equalTo("key1"))
            .body("Parameter.Version", equalTo(1));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/selector/param:2", "WithDecryption": true }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Name", equalTo("/selector/param"))
            .body("Parameter.Selector", equalTo(":2"))
            .body("Parameter.Value", equalTo("key2"))
            .body("Parameter.Version", equalTo(2));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/selector/param:previous" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Selector", equalTo(":previous"))
            .body("Parameter.Version", equalTo(1));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/selector/param:9" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ParameterVersionNotFound"));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameters")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Names": ["/selector/param:1", "/selector/param:9"] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameters.Value", contains("key1"))
            .body("InvalidParameters", contains("/selector/param:9"));
    }

    @Test
    void labelParameterVersion_validationErrors() {
        given()
            .header("X-Amz-Target", "AmazonSSM.LabelParameterVersion")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/test/param", "Labels": [] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        given()
            .header("X-Amz-Target", "AmazonSSM.LabelParameterVersion")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/test/param", "Labels": [""] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        given()
            .header("X-Amz-Target", "AmazonSSM.LabelParameterVersion")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/test/param", "Labels": ["%s"] }
                """.formatted("a".repeat(101)))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        given()
            .header("X-Amz-Target", "AmazonSSM.LabelParameterVersion")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "Name": "/test/param", "Labels": ["l1","l2","l3","l4","l5","l6","l7","l8","l9","l10","l11"] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    private io.restassured.response.ValidatableResponse describeParameters(String body) {
        return given()
            .header("X-Amz-Target", "AmazonSSM.DescribeParameters")
            .contentType(SSM_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private void describeParametersError(String body, String errorType) {
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeParameters")
            .contentType(SSM_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo(errorType));
    }
}
