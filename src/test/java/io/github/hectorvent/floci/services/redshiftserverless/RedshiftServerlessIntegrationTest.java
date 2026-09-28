package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class RedshiftServerlessIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "RedshiftServerless.";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/redshift-serverless/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createAppliesAwsDefaultsAndNeverReturnsTheAdminPassword() {
        call("CreateNamespace", """
                {"namespaceName":"defaults-ns","adminUsername":"admin","adminUserPassword":"Secret123!"}
                """)
                .statusCode(200)
                .body("namespace.namespaceName", equalTo("defaults-ns"))
                .body("namespace.namespaceId", matchesPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                .body("namespace.namespaceArn", matchesPattern(
                        "arn:aws:redshift-serverless:us-east-1:\\d{12}:namespace/"
                                + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                .body("namespace.dbName", equalTo("dev"))
                .body("namespace.kmsKeyId", equalTo("AWS_OWNED_KMS_KEY"))
                .body("namespace.status", equalTo("AVAILABLE"))
                .body("namespace.adminUsername", equalTo("admin"))
                .body("namespace.logExports.size()", equalTo(0))
                .body("namespace.creationDate", matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"))
                .body("namespace.adminUserPassword", nullValue());

        call("GetNamespace", "{\"namespaceName\":\"defaults-ns\"}")
                .statusCode(200)
                .body("namespace.dbName", equalTo("dev"))
                .body("namespace.kmsKeyId", equalTo("AWS_OWNED_KMS_KEY"))
                .body("namespace.adminUserPassword", nullValue());

        call("DeleteNamespace", "{\"namespaceName\":\"defaults-ns\"}").statusCode(200);
    }

    @Test
    void namespaceLifecycleIsVisibleThroughSeparateReads() {
        call("CreateNamespace", """
                {"namespaceName":"lifecycle-ns","adminUsername":"admin","dbName":"analytics",
                 "logExports":["userlog"],"iamRoles":["arn:aws:iam::000000000000:role/one"]}
                """)
                .statusCode(200)
                .body("namespace.dbName", equalTo("analytics"));

        call("ListNamespaces", "{}")
                .statusCode(200)
                .body("namespaces.namespaceName", hasItem("lifecycle-ns"));

        call("UpdateNamespace", """
                {"namespaceName":"lifecycle-ns","kmsKeyId":"custom-key",
                 "logExports":["userlog","connectionlog"],"iamRoles":[]}
                """)
                .statusCode(200);

        call("GetNamespace", "{\"namespaceName\":\"lifecycle-ns\"}")
                .statusCode(200)
                .body("namespace.kmsKeyId", equalTo("custom-key"))
                .body("namespace.logExports", hasItem("connectionlog"))
                .body("namespace.iamRoles.size()", equalTo(0))
                .body("namespace.dbName", equalTo("analytics"));

        call("DeleteNamespace", "{\"namespaceName\":\"lifecycle-ns\"}")
                .statusCode(200)
                .body("namespace.namespaceName", equalTo("lifecycle-ns"))
                .body("namespace.status", equalTo("DELETING"));

        call("GetNamespace", "{\"namespaceName\":\"lifecycle-ns\"}")
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void duplicateNamespaceNameIsRejected() {
        call("CreateNamespace", "{\"namespaceName\":\"conflict-ns\",\"adminUsername\":\"admin\"}")
                .statusCode(200);
        call("CreateNamespace", "{\"namespaceName\":\"conflict-ns\",\"adminUsername\":\"admin\"}")
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
        call("DeleteNamespace", "{\"namespaceName\":\"conflict-ns\"}").statusCode(200);
    }

    @Test
    void deletingAnUnknownNamespaceReturnsResourceNotFound() {
        call("DeleteNamespace", "{\"namespaceName\":\"absent-ns\"}")
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void invalidNamespaceNameReturnsValidationError() {
        call("CreateNamespace", "{\"namespaceName\":\"Upper-Case\",\"adminUsername\":\"admin\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void invalidLogExportReturnsValidationError() {
        call("CreateNamespace", """
                {"namespaceName":"bad-logs-ns","adminUsername":"admin","logExports":["nosuchlog"]}
                """)
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void tagsCreatedWithTheNamespaceAreReadableAndEditable() {
        String arn = call("CreateNamespace", """
                {"namespaceName":"tagged-ns","adminUsername":"admin","tags":[{"key":"env","value":"dev"}]}
                """)
                .statusCode(200)
                .extract().jsonPath().getString("namespace.namespaceArn");

        call("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("tags.size()", equalTo(1))
                .body("tags[0].key", equalTo("env"))
                .body("tags[0].value", equalTo("dev"));

        call("TagResource", "{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"key\":\"team\",\"value\":\"data\"}]}")
                .statusCode(200);
        call("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("tags.key", hasItem("team"))
                .body("tags.key", hasItem("env"));

        call("UntagResource", "{\"resourceArn\":\"" + arn + "\",\"tagKeys\":[\"env\"]}")
                .statusCode(200);
        call("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("tags.size()", equalTo(1))
                .body("tags[0].key", equalTo("team"));

        call("DeleteNamespace", "{\"namespaceName\":\"tagged-ns\"}").statusCode(200);
    }

    @Test
    void taggingAnUnknownArnReturnsResourceNotFound() {
        String absent = "arn:aws:redshift-serverless:us-east-1:000000000000:"
                + "namespace/00000000-0000-0000-0000-000000000000";
        call("ListTagsForResource", "{\"resourceArn\":\"" + absent + "\"}")
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static io.restassured.response.ValidatableResponse call(String action, String body) {
        return given()
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when()
                .post("/")
                .then();
    }
}
