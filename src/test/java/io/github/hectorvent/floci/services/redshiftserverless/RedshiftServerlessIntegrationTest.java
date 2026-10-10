package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

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

    /**
     * The metadata behaviour under test needs no PostgreSQL container, so the runtime is replaced:
     * these tests then run without Docker, and the real container and proxy are exercised by
     * {@code RedshiftServerlessRuntimeIntegrationTest}.
     */
    @InjectMock
    RedshiftServerlessRuntime runtime;

    @BeforeEach
    void stubRuntime() {
        when(runtime.generatePassword()).thenReturn("Generated123");
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
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
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void duplicateNamespaceNameIsRejected() {
        call("CreateNamespace", "{\"namespaceName\":\"conflict-ns\",\"adminUsername\":\"admin\"}")
                .statusCode(200);
        call("CreateNamespace", "{\"namespaceName\":\"conflict-ns\",\"adminUsername\":\"admin\"}")
                .statusCode(400)
                .body("__type", equalTo("ConflictException"));
        call("DeleteNamespace", "{\"namespaceName\":\"conflict-ns\"}").statusCode(200);
    }

    @Test
    void deletingAnUnknownNamespaceReturnsResourceNotFound() {
        call("DeleteNamespace", "{\"namespaceName\":\"absent-ns\"}")
                .statusCode(400)
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
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void snapshotLifecycleIsVisibleThroughSeparateReads() {
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(5, Path.class), "-- dump");
            return null;
        }).when(runtime).takeSnapshot(any(), any(), any(), any(), any(), any());
        call("CreateNamespace", "{\"namespaceName\":\"snap-life-ns\",\"adminUsername\":\"admin\"}").statusCode(200);
        call("CreateWorkgroup", "{\"workgroupName\":\"snap-life-wg\",\"namespaceName\":\"snap-life-ns\"}")
                .statusCode(200);

        String snapshotArn = call("CreateSnapshot",
                "{\"snapshotName\":\"snap-life\",\"namespaceName\":\"snap-life-ns\",\"retentionPeriod\":7,"
                        + "\"tags\":[{\"key\":\"env\",\"value\":\"dev\"}]}")
                .statusCode(200)
                .body("snapshot.snapshotName", equalTo("snap-life"))
                .body("snapshot.snapshotRetentionPeriod", equalTo(7))
                .body("snapshot.snapshotRemainingDays", equalTo(7))
                .body("snapshot.adminUsername", equalTo("admin"))
                .body("snapshot.kmsKeyId", equalTo("AWS_OWNED_KMS_KEY"))
                .body("snapshot.namespaceName", equalTo("snap-life-ns"))
                .body("snapshot.status", equalTo("AVAILABLE"))
                .body("snapshot.snapshotCreateTime", matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*Z"))
                .extract().path("snapshot.snapshotArn");

        call("CreateSnapshot", "{\"snapshotName\":\"snap-life\",\"namespaceName\":\"snap-life-ns\"}")
                .statusCode(400)
                .body("__type", equalTo("ConflictException"));

        call("GetSnapshot", "{\"snapshotName\":\"snap-life\"}")
                .statusCode(200)
                .body("snapshot.snapshotArn", equalTo(snapshotArn));
        call("GetSnapshot", "{\"snapshotArn\":\"" + snapshotArn + "\"}")
                .statusCode(200)
                .body("snapshot.snapshotName", equalTo("snap-life"));
        call("GetSnapshot", "{\"snapshotArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/snap-life\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        call("ListSnapshots", "{\"namespaceName\":\"snap-life-ns\",\"maxResults\":1}")
                .statusCode(200)
                .body("snapshots.snapshotName", hasItem("snap-life"));
        call("ListTagsForResource", "{\"resourceArn\":\"" + snapshotArn + "\"}")
                .statusCode(200)
                .body("tags.key", hasItem("env"));
        call("ListSnapshots", "{\"ownerAccount\":\"999999999999\"}")
                .statusCode(200)
                .body("snapshots", hasSize(0));
        call("ListSnapshots", "{\"startTime\":4102444800}")
                .statusCode(200)
                .body("snapshots", hasSize(0));
        call("RestoreFromSnapshot", "{\"namespaceName\":\"snap-life-ns\",\"workgroupName\":\"snap-life-wg\","
                + "\"snapshotName\":\"snap-life\",\"snapshotArn\":\"" + snapshotArn + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("ListSnapshots", "{\"namespaceName\":\"no-such-ns\"}")
                .statusCode(200)
                .body("snapshots", hasSize(0));

        call("RestoreFromSnapshot", "{\"namespaceName\":\"snap-life-ns\",\"workgroupName\":\"snap-life-wg\","
                + "\"snapshotArn\":\"" + snapshotArn + "\"}")
                .statusCode(200)
                .body("snapshotName", equalTo("snap-life"))
                .body("namespace.namespaceName", equalTo("snap-life-ns"))
                .body("namespace.status", equalTo("AVAILABLE"))
                .body("ownerAccount", matchesPattern("\\d{12}"));
        call("RestoreFromSnapshot", "{\"workgroupName\":\"snap-life-wg\",\"snapshotName\":\"snap-life\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        call("DeleteSnapshot", "{\"snapshotName\":\"snap-life\"}")
                .statusCode(200)
                .body("snapshot.snapshotName", equalTo("snap-life"));
        call("GetSnapshot", "{\"snapshotName\":\"snap-life\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void workgroupLifecycleIsVisibleThroughSeparateReads() {
        call("CreateNamespace", "{\"namespaceName\":\"wg-life-ns\",\"adminUsername\":\"admin\"}").statusCode(200);

        call("CreateWorkgroup", """
                {"workgroupName":"wg-life","namespaceName":"wg-life-ns","baseCapacity":32,
                 "publiclyAccessible":true,"tags":[{"key":"env","value":"dev"}]}
                """)
                .statusCode(200)
                .body("workgroup.workgroupName", equalTo("wg-life"))
                .body("workgroup.namespaceName", equalTo("wg-life-ns"))
                .body("workgroup.status", equalTo("AVAILABLE"))
                .body("workgroup.baseCapacity", equalTo(32))
                .body("workgroup.port", equalTo(5439))
                .body("workgroup.trackName", equalTo("current"))
                .body("workgroup.pricePerformanceTarget.status", equalTo("DISABLED"))
                .body("workgroup.endpoint.address", notNullValue())
                .body("workgroup.endpoint.port", greaterThan(0))
                .body("workgroup.workgroupArn", matchesPattern(
                        "arn:aws:redshift-serverless:us-east-1:\\d{12}:workgroup/"
                                + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                .body("workgroup.creationDate", matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));

        call("GetWorkgroup", "{\"workgroupName\":\"wg-life\"}")
                .statusCode(200)
                .body("workgroup.publiclyAccessible", equalTo(true));

        call("ListWorkgroups", "{}")
                .statusCode(200)
                .body("workgroups.workgroupName", hasItem("wg-life"));

        call("UpdateWorkgroup", "{\"workgroupName\":\"wg-life\",\"baseCapacity\":64,\"trackName\":\"trailing\"}")
                .statusCode(200)
                .body("workgroup.baseCapacity", equalTo(64))
                .body("workgroup.trackName", equalTo("current"))
                .body("workgroup.pendingTrackName", equalTo("trailing"));

        call("DeleteNamespace", "{\"namespaceName\":\"wg-life-ns\"}")
                .statusCode(400)
                .body("__type", equalTo("ConflictException"));

        call("DeleteWorkgroup", "{\"workgroupName\":\"wg-life\"}")
                .statusCode(200)
                .body("workgroup.status", equalTo("DELETING"));
        call("GetWorkgroup", "{\"workgroupName\":\"wg-life\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("DeleteNamespace", "{\"namespaceName\":\"wg-life-ns\"}").statusCode(200);
    }

    @Test
    void workgroupTagsAreEditableThroughTheWorkgroupArn() {
        call("CreateNamespace", "{\"namespaceName\":\"wg-tag-ns\",\"adminUsername\":\"admin\"}").statusCode(200);
        String arn = call("CreateWorkgroup", """
                {"workgroupName":"wg-tag","namespaceName":"wg-tag-ns","tags":[{"key":"env","value":"dev"}]}
                """)
                .statusCode(200)
                .extract().jsonPath().getString("workgroup.workgroupArn");

        call("TagResource", "{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"key\":\"team\",\"value\":\"data\"}]}")
                .statusCode(200);
        call("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("tags.key", hasItem("env"))
                .body("tags.key", hasItem("team"));
        call("UntagResource", "{\"resourceArn\":\"" + arn + "\",\"tagKeys\":[\"env\"]}").statusCode(200);
        call("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("tags.size()", equalTo(1));

        call("DeleteWorkgroup", "{\"workgroupName\":\"wg-tag\"}").statusCode(200);
        call("DeleteNamespace", "{\"namespaceName\":\"wg-tag-ns\"}").statusCode(200);
    }

    @Test
    void workgroupCreateRejectsAnUnknownNamespaceADuplicateAndABadPort() {
        call("CreateWorkgroup", "{\"workgroupName\":\"wg-orphan\",\"namespaceName\":\"absent-ns\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        call("CreateNamespace", "{\"namespaceName\":\"wg-err-ns\",\"adminUsername\":\"admin\"}").statusCode(200);
        call("CreateWorkgroup", "{\"workgroupName\":\"wg-err\",\"namespaceName\":\"wg-err-ns\",\"port\":5000}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("CreateWorkgroup", "{\"workgroupName\":\"wg-err\",\"namespaceName\":\"wg-err-ns\"}").statusCode(200);
        call("CreateWorkgroup", "{\"workgroupName\":\"wg-err\",\"namespaceName\":\"wg-err-ns\"}")
                .statusCode(400)
                .body("__type", equalTo("ConflictException"));

        call("DeleteWorkgroup", "{\"workgroupName\":\"wg-err\"}").statusCode(200);
        call("DeleteNamespace", "{\"namespaceName\":\"wg-err-ns\"}").statusCode(200);
    }

    @Test
    void usageLimitLifecycleFollowsTheApiModel() {
        call("CreateNamespace", "{\"namespaceName\":\"limit-ns\",\"adminUserPassword\":\"Secret123!\"}")
                .statusCode(200);
        String workgroupArn = call("CreateWorkgroup", "{\"workgroupName\":\"limit-wg\",\"namespaceName\":\"limit-ns\"}")
                .statusCode(200)
                .extract().path("workgroup.workgroupArn");

        String limitId = call("CreateUsageLimit", "{\"resourceArn\":\"" + workgroupArn
                + "\",\"usageType\":\"serverless-compute\",\"amount\":120,\"period\":\"daily\"}")
                .statusCode(200)
                .body("usageLimit.amount", equalTo(120))
                .body("usageLimit.period", equalTo("daily"))
                .body("usageLimit.breachAction", equalTo("log"))
                .body("usageLimit.usageLimitArn", matchesPattern(
                        "arn:aws:redshift-serverless:us-east-1:\\d{12}:usagelimit/.+"))
                .extract().path("usageLimit.usageLimitId");

        String secondLimitId = call("CreateUsageLimit", "{\"resourceArn\":\"" + workgroupArn
                + "\",\"usageType\":\"serverless-compute\",\"amount\":200,\"period\":\"monthly\"}")
                .statusCode(200)
                .extract().path("usageLimit.usageLimitId");

        call("UpdateUsageLimit", "{\"usageLimitId\":\"" + limitId + "\",\"amount\":240,\"breachAction\":\"deactivate\"}")
                .statusCode(200)
                .body("usageLimit.amount", equalTo(240))
                .body("usageLimit.breachAction", equalTo("deactivate"))
                .body("usageLimit.period", equalTo("daily"));

        call("ListUsageLimits", "{\"resourceArn\":\"" + workgroupArn + "\"}")
                .statusCode(200)
                .body("usageLimits", hasSize(2));

        call("DeleteUsageLimit", "{\"usageLimitId\":\"" + secondLimitId + "\"}").statusCode(200);

        call("ListUsageLimits", "{\"resourceArn\":\"" + workgroupArn + "\"}")
                .statusCode(200)
                .body("usageLimits", hasSize(1))
                .body("usageLimits[0].usageLimitId", equalTo(limitId));

        call("DeleteWorkgroup", "{\"workgroupName\":\"limit-wg\"}").statusCode(200);
        call("GetUsageLimit", "{\"usageLimitId\":\"" + limitId + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listUsageLimitsPaginatesAndRejectsAnOutOfRangePageSize() {
        call("CreateNamespace", "{\"namespaceName\":\"page-limit-ns\",\"adminUserPassword\":\"Secret123!\"}")
                .statusCode(200);
        String workgroupArn = call("CreateWorkgroup",
                "{\"workgroupName\":\"page-limit-wg\",\"namespaceName\":\"page-limit-ns\"}")
                .statusCode(200)
                .extract().path("workgroup.workgroupArn");
        call("CreateUsageLimit", "{\"resourceArn\":\"" + workgroupArn
                + "\",\"usageType\":\"serverless-compute\",\"amount\":1}").statusCode(200);
        call("CreateUsageLimit", "{\"resourceArn\":\"" + workgroupArn
                + "\",\"usageType\":\"cross-region-datasharing\",\"amount\":2}").statusCode(200);

        String nextToken = call("ListUsageLimits", "{\"resourceArn\":\"" + workgroupArn + "\",\"maxResults\":1}")
                .statusCode(200)
                .body("usageLimits", hasSize(1))
                .body("nextToken", notNullValue())
                .extract().path("nextToken");
        call("ListUsageLimits", "{\"resourceArn\":\"" + workgroupArn + "\",\"maxResults\":1,\"nextToken\":\""
                + nextToken + "\"}")
                .statusCode(200)
                .body("usageLimits", hasSize(1))
                .body("nextToken", nullValue());

        call("ListUsageLimits", "{\"maxResults\":0}")
                .statusCode(400)
                .body("__type", equalTo("InvalidPaginationException"));
    }

    @Test
    void aWorkgroupRecreatedUnderTheSameNameDoesNotInheritUsageLimits() {
        call("CreateNamespace", "{\"namespaceName\":\"again-limit-ns\",\"adminUserPassword\":\"Secret123!\"}")
                .statusCode(200);
        String firstArn = call("CreateWorkgroup",
                "{\"workgroupName\":\"again-limit-wg\",\"namespaceName\":\"again-limit-ns\"}")
                .statusCode(200)
                .extract().path("workgroup.workgroupArn");
        String oldLimitId = call("CreateUsageLimit", "{\"resourceArn\":\"" + firstArn
                + "\",\"usageType\":\"serverless-compute\",\"amount\":1}")
                .statusCode(200)
                .extract().path("usageLimit.usageLimitId");

        call("DeleteWorkgroup", "{\"workgroupName\":\"again-limit-wg\"}").statusCode(200);
        String secondArn = call("CreateWorkgroup",
                "{\"workgroupName\":\"again-limit-wg\",\"namespaceName\":\"again-limit-ns\"}")
                .statusCode(200)
                .extract().path("workgroup.workgroupArn");

        call("GetUsageLimit", "{\"usageLimitId\":\"" + oldLimitId + "\"}").statusCode(400);
        call("ListUsageLimits", "{\"resourceArn\":\"" + secondArn + "\"}")
                .statusCode(200)
                .body("usageLimits", hasSize(0));
        call("CreateUsageLimit", "{\"resourceArn\":\"" + secondArn
                + "\",\"usageType\":\"serverless-compute\",\"amount\":1}").statusCode(200);
    }

    @Test
    void createUsageLimitRejectsAnUnknownWorkgroup() {
        call("CreateUsageLimit", "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:000000000000:workgroup/none"
                + "\",\"usageType\":\"serverless-compute\",\"amount\":1}")
                .statusCode(400)
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
