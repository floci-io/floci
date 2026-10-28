package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.services.redshift.TempCredential;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftServerlessIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "RedshiftServerless.";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/redshift-serverless/aws4_request";

    @TestHTTPResource
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

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
        when(runtime.issueCredential(any(), any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> new TempCredential(invocation.getArgument(3), "temporary-password",
                        Instant.now().plusSeconds(900), List.of()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queryCredentialsKeepTheRegisteredDatabaseIdentity(boolean blankHeader) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "query-user-" + suffix;
        String namespace = "query-ns-" + suffix;
        String workgroup = "query-wg-" + suffix;
        String region = "eu-west-1";
        String root = EnforcementFixtures.auth("000000000000", "redshift-serverless")
                .replace("us-east-1", region);
        String caller = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                EnforcementFixtures.auth("000000000000", "iam"), user).accessKeyId();
        when(runtime.issueCredential(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(invocation -> new TempCredential(invocation.getArgument(3), "temporary-password",
                        Instant.now().plusSeconds(900), List.of()));
        given().header("Authorization", root).header("X-Amz-Target", TARGET_PREFIX + "CreateNamespace")
                .contentType(CONTENT_TYPE).body(Map.of("namespaceName", namespace, "adminUsername", "admin"))
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> given().header("Authorization", root)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteNamespace").contentType(CONTENT_TYPE)
                .body(Map.of("namespaceName", namespace)).when().post("/").then().statusCode(200));
        given().header("Authorization", root).header("X-Amz-Target", TARGET_PREFIX + "CreateWorkgroup")
                .contentType(CONTENT_TYPE).body(Map.of("workgroupName", workgroup, "namespaceName", namespace))
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> given().header("Authorization", root)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteWorkgroup").contentType(CONTENT_TYPE)
                .body(Map.of("workgroupName", workgroup)).when().post("/").then().statusCode(200));
        RequestSpecification request = given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + "GetCredentials")
                .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                .queryParam("X-Amz-Credential", caller + "/20261015/" + region + "/redshift-serverless/aws4_request")
                .queryParam("X-Amz-Signature", "abc").body(Map.of("workgroupName", workgroup));
        if (blankHeader) {
            request.header("Authorization", "");
        }
        request.when().post("/").then().statusCode(200).body("dbUser", equalTo("IAM:" + user));
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
            invocation.getArgument(6, Runnable.class).run();
            return null;
        }).when(runtime).restoreSnapshot(any(), any(), any(), any(), any(), any(), any());
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

        call("UpdateSnapshot", "{\"snapshotName\":\"snap-life\",\"retentionPeriod\":30}")
                .statusCode(200)
                .body("snapshot.snapshotRetentionPeriod", equalTo(30))
                .body("snapshot.snapshotRemainingDays", equalTo(30));
        call("UpdateSnapshot", "{\"snapshotName\":\"snap-life\",\"retentionPeriod\":0}")
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
        call("ListSnapshots", "{\"startTime\":4102444800.125}")
                .statusCode(200)
                .body("snapshots", hasSize(0));
        call("ListSnapshots", "{\"startTime\":\"invalid\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("ListSnapshots", "{\"startTime\":123456789012345678901234567890}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("RestoreFromSnapshot", "{\"namespaceName\":\"snap-life-ns\",\"workgroupName\":\"snap-life-wg\","
                + "\"snapshotName\":\"snap-life\",\"snapshotArn\":\"" + snapshotArn + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("ListSnapshots", "{\"namespaceName\":\"no-such-ns\"}")
                .statusCode(200)
                .body("snapshots", hasSize(0));

        call("RestoreFromSnapshot", "{\"namespaceName\":\"snap-life-ns\",\"workgroupName\":\"snap-life-wg\","
                + "\"snapshotArn\":\"" + snapshotArn + "\",\"manageAdminPassword\":true}")
                .statusCode(200)
                .body("snapshotName", equalTo("snap-life"))
                .body("namespace.namespaceName", equalTo("snap-life-ns"))
                .body("namespace.adminPasswordSecretArn", notNullValue())
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

    @Test
    void releaseTracksAndEmptyListsRoundTripOverTheWire() {
        call("ListTracks", "{}")
                .statusCode(200)
                .body("tracks.trackName", equalTo(List.of("current", "trailing")));
        call("GetTrack", "{\"trackName\":\"trailing\"}")
                .statusCode(200)
                .body("track.trackName", equalTo("trailing"));
        call("GetTrack", "{\"trackName\":\"preview\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("GetTrack", "{}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        call("ListManagedWorkgroups", "{}").statusCode(200).body("managedWorkgroups", hasSize(0));
        call("ListReservations", "{}").statusCode(200).body("reservationsList", hasSize(0));
        call("ListReservationOfferings", "{}").statusCode(200).body("reservationOfferingsList", hasSize(0));
        call("GetReservation", "{\"reservationId\":\"r-1\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("CreateReservation", "{\"offeringId\":\"o-1\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void resourcePolicyRoundTripsOverTheWire() {
        call("CreateNamespace", "{\"namespaceName\":\"policy-ns\",\"adminUsername\":\"admin\"}").statusCode(200);
        String snapshotArn = call("CreateSnapshot", "{\"snapshotName\":\"policy-snap\",\"namespaceName\":\"policy-ns\"}")
                .statusCode(200).extract().path("snapshot.snapshotArn");

        call("PutResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":\"{\\\"Version\\\":\\\"2012-10-17\\\"}\"}")
                .statusCode(200)
                .body("resourcePolicy.resourceArn", equalTo(snapshotArn));
        call("GetResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}")
                .statusCode(200)
                .body("resourcePolicy.policy", equalTo("{\"Version\":\"2012-10-17\"}"));
        call("DeleteResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}").statusCode(200);
        call("GetResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("PutResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("PutResourcePolicy", "{\"policy\":\"{\\\"Version\\\":\\\"2012-10-17\\\"}\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("GetResourcePolicy", "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("resourceArn does not identify a snapshot in this account and Region."));
        call("DeleteResourcePolicy", "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("resourceArn does not identify a snapshot in this account and Region."));
    }

    @Test
    void resourcePolicyIsIsolatedPerAccount() {
        String accountA = "111122223333";
        String accountB = "444455556666";
        String policyJson = "{\"Version\":\"2012-10-17\"}";
        String escapedPolicy = "{\\\"Version\\\":\\\"2012-10-17\\\"}";

        callAs(accountA, "CreateNamespace", "{\"namespaceName\":\"acct-ns\",\"adminUsername\":\"admin\"}").statusCode(200);
        String snapArnA = callAs(accountA, "CreateSnapshot", "{\"snapshotName\":\"acct-snap\",\"namespaceName\":\"acct-ns\"}")
                .statusCode(200).extract().path("snapshot.snapshotArn");

        callAs(accountA, "PutResourcePolicy", "{\"resourceArn\":\"" + snapArnA + "\",\"policy\":\"" + escapedPolicy + "\"}")
                .statusCode(200)
                .body("resourcePolicy.resourceArn", equalTo(snapArnA));

        callAs(accountB, "GetResourcePolicy", "{\"resourceArn\":\"" + snapArnA + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("resourceArn does not identify a snapshot in this account and Region."));
        callAs(accountB, "DeleteResourcePolicy", "{\"resourceArn\":\"" + snapArnA + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("resourceArn does not identify a snapshot in this account and Region."));
        callAs(accountB, "PutResourcePolicy", "{\"resourceArn\":\"" + snapArnA + "\",\"policy\":\"" + escapedPolicy + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("resourceArn does not identify a snapshot in this account and Region."));

        callAs(accountB, "CreateNamespace", "{\"namespaceName\":\"acct-ns\",\"adminUsername\":\"admin\"}").statusCode(200);
        String snapArnB = callAs(accountB, "CreateSnapshot", "{\"snapshotName\":\"acct-snap\",\"namespaceName\":\"acct-ns\"}")
                .statusCode(200).extract().path("snapshot.snapshotArn");

        callAs(accountB, "GetResourcePolicy", "{\"resourceArn\":\"" + snapArnB + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        callAs(accountA, "GetResourcePolicy", "{\"resourceArn\":\"" + snapArnA + "\"}")
                .statusCode(200)
                .body("resourcePolicy.policy", equalTo(policyJson));
    }

    private static ValidatableResponse callAs(String accountId, String action, String body) {
        return given()
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accountId
                        + "/20260904/us-east-1/redshift-serverless/aws4_request")
                .contentType(CONTENT_TYPE)
                .body(body)
                .when()
                .post("/")
                .then();
    }

    @Test
    void endpointAccessRoundTripsOverTheWire() {
        call("CreateNamespace", "{\"namespaceName\":\"endpoint-ns\",\"adminUsername\":\"admin\"}").statusCode(200);
        call("CreateWorkgroup", "{\"workgroupName\":\"endpoint-wg\",\"namespaceName\":\"endpoint-ns\"}").statusCode(200);

        call("CreateEndpointAccess", "{\"endpointName\":\"admin-ep\",\"workgroupName\":\"endpoint-wg\","
                + "\"subnetIds\":[\"subnet-a\"]}")
                .statusCode(200)
                .body("endpoint.endpointName", equalTo("admin-ep"))
                .body("endpoint.endpointStatus", equalTo("ACTIVE"))
                .body("endpoint.endpointCreateTime", matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*Z"))
                .body("endpoint.vpcSecurityGroups", hasSize(0))
                .body("endpoint.vpcEndpoint.networkInterfaces[0].subnetId", equalTo("subnet-a"));
        call("CreateEndpointAccess", "{\"endpointName\":\"admin--ep\",\"workgroupName\":\"endpoint-wg\","
                + "\"subnetIds\":[\"subnet-a\"]}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("CreateEndpointAccess", "{\"endpointName\":\"other-ep\",\"workgroupName\":\"endpoint-wg\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("ListEndpointAccess", "{\"workgroupName\":\"endpoint-wg\"}")
                .statusCode(200)
                .body("endpoints.endpointName", hasItem("admin-ep"));
        call("DeleteEndpointAccess", "{\"endpointName\":\"admin-ep\"}")
                .statusCode(200)
                .body("endpoint.endpointStatus", equalTo("DELETING"));
    }

    @Test
    void customDomainAssociationRoundTripsOverTheWire() {
        call("CreateNamespace", "{\"namespaceName\":\"domain-ns\",\"adminUsername\":\"admin\"}").statusCode(200);
        call("CreateWorkgroup", "{\"workgroupName\":\"domain-wg\",\"namespaceName\":\"domain-ns\"}").statusCode(200);

        String certificate = "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";
        call("CreateCustomDomainAssociation", "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\","
                + "\"customDomainCertificateArn\":\"" + certificate + "\"}")
                .statusCode(200)
                .body("customDomainName", equalTo("db.example.com"));
        call("CreateCustomDomainAssociation", "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"bad_domain\","
                + "\"customDomainCertificateArn\":\"" + certificate + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("GetWorkgroup", "{\"workgroupName\":\"domain-wg\"}")
                .statusCode(200)
                .body("workgroup.customDomainName", equalTo("db.example.com"))
                .body("workgroup.customDomainCertificateArn", equalTo(certificate));
        call("ListCustomDomainAssociations", "{\"customDomainName\":\"db.example.com\"}")
                .statusCode(200)
                .body("associations", hasSize(1));
        call("GetCredentials", "{\"customDomainName\":\"db.example.com\"}")
                .statusCode(200)
                .body("dbUser", notNullValue())
                .body("dbPassword", notNullValue());
        call("GetCredentials", "{\"customDomainName\":\"db.example.com\",\"workgroupName\":\"domain-wg\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("GetCredentials", "{\"customDomainName\":\"missing.example.com\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("DeleteCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\"}").statusCode(200);
        call("GetWorkgroup", "{\"workgroupName\":\"domain-wg\"}")
                .statusCode(200)
                .body("workgroup.customDomainName", nullValue());
        call("GetCredentials", "{\"customDomainName\":\"db.example.com\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void snapshotCopyConfigurationRoundTripsOverTheWire() {
        call("CreateNamespace", "{\"namespaceName\":\"copy-ns\",\"adminUsername\":\"admin\"}").statusCode(200);

        String copyId = call("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"copy-ns\",\"destinationRegion\":\"us-west-2\",\"snapshotRetentionPeriod\":10}")
                .statusCode(200)
                .body("snapshotCopyConfiguration.destinationRegion", equalTo("us-west-2"))
                .extract().path("snapshotCopyConfiguration.snapshotCopyConfigurationId");
        call("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"copy-ns\",\"destinationRegion\":\"eu-west-1\"}")
                .statusCode(400)
                .body("__type", equalTo("ConflictException"));
        call("DeleteSnapshotCopyConfiguration", "{\"snapshotCopyConfigurationId\":\"" + copyId + "\"}").statusCode(200);
        String replacementCopyId = call("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"copy-ns\",\"destinationRegion\":\"eu-west-1\"}")
                .statusCode(200)
                .body("snapshotCopyConfiguration.destinationRegion", equalTo("eu-west-1"))
                .extract().path("snapshotCopyConfiguration.snapshotCopyConfigurationId");
        call("ListSnapshotCopyConfigurations", "{\"maxResults\":0}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("ListSnapshotCopyConfigurations", "{\"nextToken\":\"bad-token\"}")
                .statusCode(400)
                .body("__type", equalTo("InvalidPaginationException"));
        call("DeleteSnapshotCopyConfiguration", "{\"snapshotCopyConfigurationId\":\"" + replacementCopyId + "\"}")
                .statusCode(200);
    }

    @Test
    void scheduledActionRoundTripsOverTheWire() {
        call("CreateNamespace", "{\"namespaceName\":\"sched-ns\",\"adminUsername\":\"admin\"}").statusCode(200);

        call("CreateScheduledAction", "{\"scheduledActionName\":\"admin-sched\",\"namespaceName\":\"sched-ns\","
                + "\"roleArn\":\"arn:aws:iam::000000000000:role/r\",\"schedule\":{\"cron\":\"cron(0 3 * * ? *)\"},"
                + "\"targetAction\":{\"createSnapshot\":{\"namespaceName\":\"sched-ns\",\"snapshotNamePrefix\":\"p\"}}}")
                .statusCode(200)
                .body("scheduledAction.state", equalTo("ACTIVE"))
                .body("scheduledAction.schedule.cron", equalTo("cron(0 3 * * ? *)"));
        call("CreateScheduledAction", "{\"scheduledActionName\":\"BAD_NAME\",\"namespaceName\":\"sched-ns\","
                + "\"roleArn\":\"arn:aws:iam::000000000000:role/r\",\"schedule\":{\"cron\":\"x\"},"
                + "\"targetAction\":{\"createSnapshot\":{\"namespaceName\":\"sched-ns\",\"snapshotNamePrefix\":\"p\"}}}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("DeleteScheduledAction", "{\"scheduledActionName\":\"admin-sched\"}").statusCode(200);
    }

    private static ValidatableResponse call(String action, String body) {
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
