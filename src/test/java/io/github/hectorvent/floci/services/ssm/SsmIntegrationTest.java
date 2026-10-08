package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SsmIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void parameterArn_hasParameterSlashAndAddressesTagOperations() {
        String suffix = Long.toString(System.nanoTime());
        String plain = "arn-plain-" + suffix;
        String nested = "/arn/nested-" + suffix;
        putFilterFixture(plain, "String", "");
        putFilterFixture(nested, "String", "");

        String plainArn = parameterArn(plain, ":parameter/" + plain);
        parameterArn(nested, ":parameter" + nested);

        given()
            .header("X-Amz-Target", "AmazonSSM.AddTagsToResource")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "ResourceType": "Parameter", "ResourceId": "%s", "Tags": [{"Key": "env", "Value": "dev"}] }
                """.formatted(plainArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonSSM.ListTagsForResource")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                { "ResourceType": "Parameter", "ResourceId": "%s" }
                """.formatted(plainArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagList", hasSize(1))
            .body("TagList[0].Key", equalTo("env"))
            .body("TagList[0].Value", equalTo("dev"));

        given()
            .header("X-Amz-Target", "AmazonSSM.DeleteParameters")
            .contentType(SSM_CONTENT_TYPE)
            .body("{ \"Names\": [\"" + plain + "\", \"" + nested + "\"] }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeletedParameters", hasSize(2));
    }

    private String parameterArn(String name, String expectedArnSuffix) {
        return given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("{ \"Name\": \"" + name + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.ARN", endsWith(expectedArnSuffix))
            .extract().path("Parameter.ARN");
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

    @Test
    void parameterChangesArePublishedToTheDefaultEventBus() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String base = "/events-" + suffix;
        String queueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "ssm-events-" + suffix)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        String queueArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", queueUrl)
            .formParam("AttributeName.1", "QueueArn")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.Name == 'QueueArn' }.Value");
        String pattern = "{\"source\":[\"aws.ssm\"],\"detail-type\":[\"Parameter Store Change\"],"
                + "\"detail\":{\"name\":[{\"prefix\":\"" + base + "/\"}]}}";
        events("PutRule", MAPPER.createObjectNode().put("Name", "ssm-events-" + suffix).put("EventPattern", pattern));
        events("PutTargets", MAPPER.readTree("{\"Rule\":\"ssm-events-" + suffix + "\","
                + "\"Targets\":[{\"Id\":\"1\",\"Arn\":\"" + queueArn + "\"}]}"));

        ssm("PutParameter", "{\"Name\":\"" + base + "/a\",\"Value\":\"v1\",\"Type\":\"String\",\"Description\":\"d\"}")
            .statusCode(200);
        ssm("PutParameter", "{\"Name\":\"" + base + "/a\",\"Value\":\"v2\",\"Type\":\"String\",\"Description\":\"d\","
                + "\"Overwrite\":true}")
            .statusCode(200);
        ssm("LabelParameterVersion", "{\"Name\":\"" + base + "/a\",\"ParameterVersion\":2,\"Labels\":[\"stable\"]}")
            .statusCode(200);
        ssm("PutParameter", "{\"Name\":\"" + base + "/a\",\"Value\":\"v3\",\"Type\":\"String\"}")
            .statusCode(400)
            .body("__type", equalTo("ParameterAlreadyExists"));
        String arn = ssm("GetParameter", "{\"Name\":\"" + base + "/a\"}").statusCode(200)
            .extract().path("Parameter.ARN");
        ssm("DeleteParameter", "{\"Name\":\"" + base + "/a\"}").statusCode(200);
        ssm("PutParameter", "{\"Name\":\"" + base + "/b\",\"Value\":\"v\",\"Type\":\"String\"}").statusCode(200);
        ssm("DeleteParameters", "{\"Names\":[\"" + base + "/b\",\"" + base + "/missing\"]}").statusCode(200);

        String stack = "ssm-events-" + suffix;
        cfn("CreateStack", stack).formParam("TemplateBody", "{\"Resources\":{\"P\":{\"Type\":\"AWS::SSM::Parameter\","
                + "\"Properties\":{\"Name\":\"" + base + "/cfn\",\"Type\":\"String\",\"Value\":\"v\"}}}}")
            .when().post("/").then().statusCode(200);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!"CREATE_COMPLETE".equals(cfn("DescribeStacks", stack).when().post("/").xmlPath()
                .getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackStatus"))) {
            assertTrue(System.currentTimeMillis() < deadline, "stack " + stack + " did not reach CREATE_COMPLETE");
            Thread.sleep(100);
        }
        cfn("DeleteStack", stack).when().post("/").then().statusCode(200);

        List<JsonNode> received = receiveEvents(queueUrl, 8);
        assertEquals(List.of("Create " + base + "/a", "Update " + base + "/a",
                        "LabelParameterVersion " + base + "/a", "Delete " + base + "/a",
                        "Create " + base + "/b", "Delete " + base + "/b",
                        "Create " + base + "/cfn", "Delete " + base + "/cfn"),
                received.stream().map(e -> e.at("/detail/operation").asText() + " " + e.at("/detail/name").asText())
                        .toList());
        JsonNode created = received.getFirst();
        assertEquals("aws.ssm", created.path("source").asText());
        assertEquals("Parameter Store Change", created.path("detail-type").asText());
        assertEquals(arn, created.at("/resources/0").asText());
        assertEquals("us-east-1", created.path("region").asText());
        assertFalse(created.path("account").asText().isEmpty());
        assertEquals(MAPPER.readTree("{\"operation\":\"Create\",\"name\":\"" + base + "/a\",\"type\":\"String\","
                + "\"description\":\"d\"}"), created.path("detail"));
        assertEquals(MAPPER.readTree("{\"operation\":\"LabelParameterVersion\",\"name\":\"" + base + "/a\","
                + "\"type\":\"String\",\"description\":\"d\",\"label\":\"stable\",\"fromVersion\":\"\",\"toVersion\":\"2\"}"),
                received.get(2).path("detail"));

        events("RemoveTargets", MAPPER.readTree("{\"Rule\":\"ssm-events-" + suffix + "\",\"Ids\":[\"1\"]}"));
        events("DeleteRule", MAPPER.createObjectNode().put("Name", "ssm-events-" + suffix));
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", queueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static ValidatableResponse ssm(String action, String body) {
        return given()
            .header("X-Amz-Target", "AmazonSSM." + action)
            .contentType(SSM_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then();
    }

    private static void events(String action, JsonNode body) {
        given()
            .header("X-Amz-Target", "AWSEvents." + action)
            .contentType(SSM_CONTENT_TYPE)
            .body(body.toString())
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static RequestSpecification cfn(String action, String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request")
            .formParam("Action", action)
            .formParam("StackName", stackName);
    }

    private static List<JsonNode> receiveEvents(String queueUrl, int expected) throws Exception {
        List<JsonNode> received = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10_000;
        while (received.size() < expected && System.currentTimeMillis() < deadline) {
            List<String> bodies = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "10")
                .formParam("WaitTimeSeconds", "1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().xmlPath().getList("ReceiveMessageResponse.ReceiveMessageResult.Message.Body");
            for (String body : bodies) {
                received.add(MAPPER.readTree(body));
            }
        }
        return received;
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
