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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Under IAM enforcement, a snapshot another account owns is restored only when the snapshot's resource policy
 * names the caller, and the caller's own identity policy allows it as well: the request is checked on the
 * owner's snapshot ARN, which the policy is attached to. A snapshot encrypted with a customer managed key also needs
 * {@code kms:Decrypt} on that key, from the key policy and the caller's own policy.
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
    private static final String ALLOW_RESTORE_AND_DECRYPT = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":"
            + "\"Allow\",\"Action\":[\"redshift-serverless:RestoreFromSnapshot\",\"kms:Decrypt\"],\"Resource\":\"*\"}]}";

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
    void sharedSnapshotReadsAndListsRespectDenialsAndSnapshotRecreation() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String namespace = "read-src-" + suffix;
        String snapshot = "read-shared-" + suffix;
        String privateSnapshot = "read-private-" + suffix;
        String callerKey = user(CALLER, """
                {"Statement":[{"Effect":"Allow","Action":["redshift-serverless:GetSnapshot",
                 "redshift-serverless:ListSnapshots"],"Resource":"*"}]}
                """);
        String allow = """
                {"Statement":{"Effect":"Allow","Principal":{"AWS":"%s"},
                 "Action":"redshift-serverless:GetSnapshot"}}
                """.formatted(CALLER);
        String deny = """
                {"Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"%s"},
                   "Action":"redshift-serverless:GetSnapshot","Resource":"*"},
                  {"Effect":"Deny","Principal":{"AWS":"%s"},
                   "Action":"redshift-serverless:GetSnapshot"}
                ]}
                """.formatted(CALLER, CALLER);
        createNamespace(OWNER, namespace, Map.of());
        String arn = createSnapshot(snapshot, namespace);
        createSnapshot(privateSnapshot, namespace);
        Map<String, String> byName = Map.of("snapshotName", snapshot, "ownerAccount", OWNER);
        Map<String, String> byArn = Map.of("snapshotArn", arn, "ownerAccount", OWNER);
        Map<String, String> list = Map.of("ownerAccount", OWNER);

        readDenied(call(callerKey, "GetSnapshot", Map.of("snapshotName", privateSnapshot, "ownerAccount", OWNER)));
        readDenied(call(callerKey, "GetSnapshot", byName));
        call(callerKey, "ListSnapshots", list).statusCode(200).body("snapshots", hasSize(0));

        call(OWNER, "PutResourcePolicy", Map.of("resourceArn", arn, "policy", allow)).statusCode(200);
        call(callerKey, "GetSnapshot", byName).statusCode(200).body("snapshot.snapshotName", equalTo(snapshot));
        call(callerKey, "GetSnapshot", byArn).statusCode(200).body("snapshot.snapshotName", equalTo(snapshot));
        call(callerKey, "ListSnapshots", list).statusCode(200)
                .body("snapshots", hasSize(1)).body("snapshots[0].snapshotName", equalTo(snapshot));

        call(OWNER, "PutResourcePolicy", Map.of("resourceArn", arn, "policy", deny)).statusCode(200);
        readDenied(call(callerKey, "GetSnapshot", byName));
        call(callerKey, "ListSnapshots", list).statusCode(200).body("snapshots", hasSize(0));

        call(OWNER, "DeleteResourcePolicy", Map.of("resourceArn", arn)).statusCode(200);
        readDenied(call(callerKey, "GetSnapshot", byName));
        call(callerKey, "ListSnapshots", list).statusCode(200).body("snapshots", hasSize(0));

        call(OWNER, "PutResourcePolicy", Map.of("resourceArn", arn, "policy", allow)).statusCode(200);
        call(callerKey, "GetSnapshot", byName).statusCode(200);
        call(OWNER, "DeleteSnapshot", Map.of("snapshotName", snapshot)).statusCode(200);
        call(OWNER, "CreateSnapshot", Map.of("snapshotName", snapshot, "namespaceName", namespace)).statusCode(200);
        readDenied(call(callerKey, "GetSnapshot", byName));
        readDenied(call(callerKey, "GetSnapshot", byArn));
        call(callerKey, "ListSnapshots", list).statusCode(200).body("snapshots", hasSize(0));
    }

    private static void readDenied(ValidatableResponse response) {
        response.statusCode(400).body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("redshift-serverless:GetSnapshot"));
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

        createNamespace(OWNER, sourceNamespace, Map.of());
        String snapshotArn = createSnapshot(snapshot, sourceNamespace);
        for (String account : List.of(CALLER, STRANGER)) {
            createNamespace(account, namespace, Map.of());
            createWorkgroup(account, workgroup, namespace);
        }
        Map<String, String> byName = Map.of("namespaceName", namespace, "workgroupName", workgroup,
                "snapshotName", snapshot, "ownerAccount", OWNER);

        // Not shared until the snapshot's policy says so.
        denied(call(callerKey, "RestoreFromSnapshot", byName));

        // AWS's own PutResourcePolicy example leaves Resource out: the policy is still on this snapshot.
        call(OWNER, "PutResourcePolicy", Map.of("resourceArn", snapshotArn, "policy", sharedWith(CALLER)))
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

    /**
     * A snapshot encrypted with a customer managed key restores in another account only when the key's policy lets
     * that account decrypt with it and the caller's own policy allows {@code kms:Decrypt} on it, as AWS requires the
     * key to be shared with the account performing the restore. Redshift Serverless reaches KMS as the caller, from
     * its endpoint in the request's Region, which a key policy's {@code kms:ViaService} condition names.
     */
    @Test
    void anEncryptedSnapshotRestoresInAnotherAccountOnlyWhenItsKeyIsSharedWithIt() {
        EncryptedSnapshot encrypted = encryptedSnapshot("KeyMetadata.KeyId", CALLER);
        call(OWNER, "PutResourcePolicy", Map.of("resourceArn", encrypted.snapshotArn(), "policy", sharedWith(CALLER)))
                .statusCode(200);
        String decrypting = user(CALLER, ALLOW_RESTORE_AND_DECRYPT);
        String restoringOnly = user(CALLER, ALLOW_RESTORE);
        Map<String, String> byName = encrypted.restore(Map.of("snapshotName", encrypted.snapshot(),
                "ownerAccount", OWNER));

        // Shared, but the key is still the owner's alone.
        keyDenied(call(decrypting, "RestoreFromSnapshot", byName));

        putKeyPolicy(encrypted.key(), keySharedWith(CALLER, null));
        call(decrypting, "RestoreFromSnapshot", byName).statusCode(200)
                .body("snapshotName", equalTo(encrypted.snapshot()));
        call(decrypting, "RestoreFromSnapshot", encrypted.restore(Map.of("snapshotArn", encrypted.snapshotArn(),
                "ownerAccount", OWNER))).statusCode(200);
        // The key policy is the resource side only: the caller's own policy must allow decrypting with the key too.
        keyDenied(call(restoringOnly, "RestoreFromSnapshot", byName));

        putKeyPolicy(encrypted.key(), keySharedWith(CALLER, "redshift-serverless.us-east-1.amazonaws.com"));
        call(decrypting, "RestoreFromSnapshot", byName).statusCode(200);
        putKeyPolicy(encrypted.key(), keySharedWith(CALLER, "ec2.us-east-1.amazonaws.com"));
        keyDenied(call(decrypting, "RestoreFromSnapshot", byName));
    }

    /**
     * The owner restoring its own encrypted snapshot needs {@code kms:Decrypt} on the key in its own policy, which the
     * default key policy delegates to; a snapshot under AWS's own key needs none (the test above restores one).
     */
    @Test
    void theOwnerRestoresItsEncryptedSnapshotOnlyWhenItsPolicyAllowsDecryptingWithTheKey() {
        EncryptedSnapshot encrypted = encryptedSnapshot("KeyMetadata.Arn", OWNER);
        Map<String, String> restore = encrypted.restore(Map.of("snapshotName", encrypted.snapshot()));

        keyDenied(call(user(OWNER, ALLOW_RESTORE), "RestoreFromSnapshot", restore));
        call(user(OWNER, ALLOW_RESTORE_AND_DECRYPT), "RestoreFromSnapshot", restore).statusCode(200);
    }

    /**
     * A snapshot whose key KMS would refuse to decrypt with, disabled or pending deletion, is not restored whatever
     * the policies allow, as KMS refuses the request Redshift Serverless makes with it.
     */
    @Test
    void aSnapshotWhoseKeyKmsWouldNotUseIsNotRestored() {
        EncryptedSnapshot encrypted = encryptedSnapshot("KeyMetadata.KeyId", OWNER);
        Map<String, String> restore = encrypted.restore(Map.of("snapshotName", encrypted.snapshot()));
        String decrypting = user(OWNER, ALLOW_RESTORE_AND_DECRYPT);

        kms(OWNER, "DisableKey", Map.of("KeyId", encrypted.key())).statusCode(200);
        keyDenied(call(decrypting, "RestoreFromSnapshot", restore));
        kms(OWNER, "EnableKey", Map.of("KeyId", encrypted.key())).statusCode(200);
        call(decrypting, "RestoreFromSnapshot", restore).statusCode(200);
        kms(OWNER, "ScheduleKeyDeletion", Map.of("KeyId", encrypted.key(), "PendingWindowInDays", "7"))
                .statusCode(200);
        keyDenied(call(decrypting, "RestoreFromSnapshot", restore));
    }

    /**
     * An owner's snapshot of a namespace encrypted with a new customer managed key, given to the namespace as the
     * {@code KeyMetadata} field {@code keyField} names (its id or its ARN), and a namespace and workgroup of
     * {@code restoringAccount} to restore it into.
     */
    private EncryptedSnapshot encryptedSnapshot(String keyField, String restoringAccount) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceNamespace = "kms-src-" + suffix;
        String snapshot = "kms-snap-" + suffix;
        String namespace = "kms-dst-" + suffix;
        String workgroup = "kms-wg-" + suffix;
        String key = kms(OWNER, "CreateKey", Map.of("Description", "snapshot key")).statusCode(200)
                .extract().path(keyField);
        cleanup.register(() -> scheduleKeyDeletion(key));
        createNamespace(OWNER, sourceNamespace, Map.of("kmsKeyId", key));
        String snapshotArn = createSnapshot(snapshot, sourceNamespace);
        createNamespace(restoringAccount, namespace, Map.of());
        createWorkgroup(restoringAccount, workgroup, namespace);
        return new EncryptedSnapshot(key, snapshot, snapshotArn, namespace, workgroup);
    }

    /**
     * A namespace of {@code account}'s with {@code settings}, deleted after the test as the workgroups and snapshots
     * below are: every {@code @QuarkusTest} class shares one emulator.
     */
    private void createNamespace(String account, String namespace, Map<String, String> settings) {
        Map<String, String> request = new HashMap<>(settings);
        request.put("namespaceName", namespace);
        request.put("adminUsername", "admin");
        call(account, "CreateNamespace", request).statusCode(200);
        cleanup.register(() -> call(account, "DeleteNamespace", Map.of("namespaceName", namespace)).statusCode(200));
    }

    private void createWorkgroup(String account, String workgroup, String namespace) {
        call(account, "CreateWorkgroup", Map.of("workgroupName", workgroup, "namespaceName", namespace))
                .statusCode(200);
        cleanup.register(() -> call(account, "DeleteWorkgroup", Map.of("workgroupName", workgroup)).statusCode(200));
    }

    /** The owner's snapshot of {@code namespace}, deleted after the test; its ARN. */
    private String createSnapshot(String snapshot, String namespace) {
        String arn = call(OWNER, "CreateSnapshot", Map.of("snapshotName", snapshot, "namespaceName", namespace))
                .statusCode(200).extract().path("snapshot.snapshotArn");
        cleanup.register(() -> call(OWNER, "DeleteSnapshot", Map.of("snapshotName", snapshot)).statusCode(200));
        return arn;
    }

    /** An encrypted snapshot, its key as the namespace was given it, and where it is restored. */
    private record EncryptedSnapshot(String key, String snapshot, String snapshotArn, String namespace,
                                     String workgroup) {

        /** A restore into the namespace and workgroup of the snapshot {@code naming} names. */
        Map<String, String> restore(Map<String, String> naming) {
            Map<String, String> request = new HashMap<>(naming);
            request.put("namespaceName", namespace);
            request.put("workgroupName", workgroup);
            return request;
        }
    }

    private static String sharedWith(String account) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"arn:aws:iam::" + account + ":root\"},"
                + "\"Action\":\"redshift-serverless:RestoreFromSnapshot\"}]}";
    }

    /** The default key policy, and {@code kms:Decrypt} for {@code account}, through {@code viaService} if given. */
    private static String keySharedWith(String account, String viaService) {
        String condition = viaService == null ? ""
                : ",\"Condition\":{\"StringEquals\":{\"kms:ViaService\":\"" + viaService + "\"}}";
        return "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::" + OWNER + ":root\"},"
                + "\"Action\":\"kms:*\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::" + account + ":root\"},"
                + "\"Action\":\"kms:Decrypt\",\"Resource\":\"*\"" + condition + "}]}";
    }

    /** Schedules {@code keyId}'s deletion, which is how KMS removes a key, unless a test has scheduled it already. */
    private void scheduleKeyDeletion(String keyId) {
        String state = kms(OWNER, "DescribeKey", Map.of("KeyId", keyId)).statusCode(200)
                .extract().path("KeyMetadata.KeyState");
        if (!"PendingDeletion".equals(state)) {
            kms(OWNER, "ScheduleKeyDeletion", Map.of("KeyId", keyId, "PendingWindowInDays", "7")).statusCode(200);
        }
    }

    private void putKeyPolicy(String keyId, String policy) {
        kms(OWNER, "PutKeyPolicy", Map.of("KeyId", keyId, "PolicyName", "default", "Policy", policy))
                .statusCode(200);
    }

    private static void keyDenied(ValidatableResponse response) {
        response.statusCode(400).body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("kms:Decrypt"));
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

    private ValidatableResponse kms(String accessKeyId, String action, Map<String, String> body) {
        return send("TrentService." + action, "kms", accessKeyId, body);
    }

    private ValidatableResponse call(String accessKeyId, String action, Map<String, String> body) {
        return send(TARGET_PREFIX + action, "redshift-serverless", accessKeyId, body);
    }

    /** A JSON request to {@code target}, signed for {@code service} by {@code accessKeyId}. */
    private ValidatableResponse send(String target, String service, String accessKeyId, Map<String, String> body) {
        return given().port(endpoint.getPort())
                .header("X-Amz-Target", target)
                .header("Authorization", auth(accessKeyId, service))
                .contentType(CONTENT_TYPE)
                .body(body)
                .when().post("/")
                .then();
    }
}
