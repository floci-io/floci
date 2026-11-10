package io.floci.aws.services.redshiftserverless;

import io.floci.aws.services.redshift.TempCredential;
import io.floci.aws.services.redshift.model.Endpoint;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Under IAM enforcement, a snapshot another account owns is restored only when the snapshot's resource policy
 * names the caller, and the caller's own identity policy allows it as well: the request is checked on the
 * owner's snapshot ARN, which the policy is attached to.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class RedshiftServerlessSnapshotSharingIamEnforcementIntegrationTest {

    private static final String OWNER = "111122223333";
    private static final String CALLER = "444455556666";
    private static final String STRANGER = "777788889999";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "RedshiftServerless.";
    private static final String ALLOW_RESTORE = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"redshift-serverless:RestoreFromSnapshot\",\"Resource\":\"*\"}]}";

    @TestHTTPResource
    URI endpoint;

    @InjectMock
    RedshiftServerlessRuntime runtime;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

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

    @Test
    void aSnapshotIsRestorableByAnotherAccountOnlyWhenItsPolicyNamesTheCaller() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceNamespace = "share-src-" + suffix;
        String snapshot = "share-snap-" + suffix;
        String namespace = "share-dst-" + suffix;
        String workgroup = "share-wg-" + suffix;
        String callerKey = user(CALLER, ALLOW_RESTORE);
        String strangerKey = user(STRANGER, ALLOW_RESTORE);
        String unprivilegedKey = user(CALLER, "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"redshift-serverless:GetNamespace\",\"Resource\":\"*\"}]}");

        call(OWNER, "CreateNamespace", Map.of("namespaceName", sourceNamespace, "adminUsername", "admin"))
                .statusCode(200);
        String snapshotArn = call(OWNER, "CreateSnapshot",
                Map.of("snapshotName", snapshot, "namespaceName", sourceNamespace))
                .statusCode(200).extract().path("snapshot.snapshotArn");
        for (String account : List.of(CALLER, STRANGER)) {
            call(account, "CreateNamespace", Map.of("namespaceName", namespace, "adminUsername", "admin"))
                    .statusCode(200);
            call(account, "CreateWorkgroup", Map.of("workgroupName", workgroup, "namespaceName", namespace))
                    .statusCode(200);
        }
        Map<String, String> byName = Map.of("namespaceName", namespace, "workgroupName", workgroup,
                "snapshotName", snapshot, "ownerAccount", OWNER);

        // Not shared until the snapshot's policy says so.
        denied(call(callerKey, "RestoreFromSnapshot", byName));

        // AWS's own PutResourcePolicy example leaves Resource out: the policy is still on this snapshot.
        String sharedWithCaller = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"arn:aws:iam::" + CALLER + ":root\"},"
                + "\"Action\":\"redshift-serverless:RestoreFromSnapshot\"}]}";
        call(OWNER, "PutResourcePolicy", Map.of("resourceArn", snapshotArn, "policy", sharedWithCaller))
                .statusCode(200);
        call(callerKey, "RestoreFromSnapshot", byName).statusCode(200)
                .body("ownerAccount", equalTo(OWNER))
                .body("snapshotName", equalTo(snapshot));
        call(callerKey, "RestoreFromSnapshot", Map.of("namespaceName", namespace, "workgroupName", workgroup,
                "snapshotArn", snapshotArn, "ownerAccount", OWNER)).statusCode(200)
                .body("snapshotName", equalTo(snapshot));

        // The policy is the resource side only: a caller whose own policy does not allow it is refused, and so is
        // an account the policy does not name.
        denied(call(unprivilegedKey, "RestoreFromSnapshot", byName));
        denied(call(strangerKey, "RestoreFromSnapshot", byName));

        // Withdrawing the policy takes the access away at the next restore.
        call(OWNER, "DeleteResourcePolicy", Map.of("resourceArn", snapshotArn)).statusCode(200);
        denied(call(callerKey, "RestoreFromSnapshot", byName));
    }

    private static void denied(ValidatableResponse response) {
        response.statusCode(400).body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("redshift-serverless:RestoreFromSnapshot"));
    }

    private String user(String account, String policy) {
        String name = "share-" + UUID.randomUUID().toString().substring(0, 8);
        String authorization = auth(account, "iam");
        String accessKeyId = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, name)
                .accessKeyId();
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, name, "p", policy);
        return accessKeyId;
    }

    private ValidatableResponse call(String accessKeyId, String action, Map<String, String> body) {
        return given().port(endpoint.getPort())
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", auth(accessKeyId, "redshift-serverless"))
                .contentType(CONTENT_TYPE)
                .body(body)
                .when().post("/")
                .then();
    }
}
