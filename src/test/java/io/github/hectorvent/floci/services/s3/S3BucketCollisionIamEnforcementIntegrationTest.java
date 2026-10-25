package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.S3IamEnforcementProfile;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ObjectOwnership;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class S3BucketCollisionIamEnforcementIntegrationTest {

    private static final String OWNER = "111122223333";
    private static final String CALLER = "222233334444";
    private static final String ALLOW_S3 = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:*","Resource":"*"}]}
            """;

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"none", "Allow", "Deny"})
    void foreignBucketPolicyDoesNotAuthorizeCreation(String effect) {
        UserCredentials ownerKeys = user(OWNER, ALLOW_S3);
        UserCredentials callerKeys = user(CALLER, ALLOW_S3);
        try (S3Client owner = client(ownerKeys); S3Client caller = client(callerKeys)) {
            String bucket = createBucket(owner, ownerKeys);
            if (!"none".equals(effect)) {
                owner.putBucketPolicy(request -> request.bucket(bucket).policy(bucketPolicy(bucket, effect)));
            }

            if ("Allow".equals(effect)) {
                caller.headBucket(request -> request.bucket(bucket));
            } else {
                assertEquals(403, assertThrows(S3Exception.class,
                        () -> caller.headBucket(request -> request.bucket(bucket))).statusCode());
            }
            assertError("BucketAlreadyExists", 409, () -> caller.createBucket(createRequest(bucket)));
            assertError("BucketAlreadyExists", 409, () -> caller.createBucket(createRequest(bucket)
                    .toBuilder().objectOwnership(ObjectOwnership.OBJECT_WRITER).build()));
            assertError("BucketAlreadyOwnedByYou", 409, () -> owner.createBucket(createRequest(bucket)));
            assertEquals(ObjectOwnership.BUCKET_OWNER_ENFORCED, owner.getBucketOwnershipControls(
                    request -> request.bucket(bucket)).ownershipControls().rules().getFirst().objectOwnership());
            assertTrue(caller.listBuckets().buckets().stream().noneMatch(value -> bucket.equals(value.name())));
            owner.headBucket(request -> request.bucket(bucket));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerStillNeedsIdentityPermissionBeforeCollision(boolean explicitDeny) {
        UserCredentials ownerKeys = user(OWNER, ALLOW_S3);
        String policy = explicitDeny ? """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:*","Resource":"*"},
                  {"Effect":"Deny","Action":"s3:CreateBucket","Resource":"*"}]}
                """ : """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListBucket","Resource":"*"}]}
                """;
        UserCredentials callerKeys = user(CALLER, policy);
        try (S3Client owner = client(ownerKeys); S3Client caller = client(callerKeys)) {
            String bucket = createBucket(owner, ownerKeys);
            owner.putBucketPolicy(request -> request.bucket(bucket).policy(bucketPolicy(bucket, "Allow")));
            caller.headBucket(request -> request.bucket(bucket));
            assertError("AccessDenied", 403, () -> caller.createBucket(createRequest(bucket)));
            owner.headBucket(request -> request.bucket(bucket));
        }
    }

    @Test
    void ownerPolicyStillDeniesCreationRetries() {
        UserCredentials ownerKeys = user(OWNER, ALLOW_S3);
        UserCredentials callerKeys = user(CALLER, ALLOW_S3);
        try (S3Client owner = client(ownerKeys); S3Client caller = client(callerKeys)) {
            String bucket = createBucket(owner, ownerKeys);
            try {
                owner.putBucketPolicy(request -> request.bucket(bucket).policy("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":"*",
                          "NotAction":["s3:DeleteBucketPolicy","s3:DeleteBucket","s3:GetBucketOwnershipControls"],
                          "Resource":"arn:aws:s3:::%s"}]}
                        """.formatted(bucket)));
                assertEquals(403, assertThrows(S3Exception.class,
                        () -> owner.headBucket(request -> request.bucket(bucket))).statusCode());
                assertEquals(403, assertThrows(S3Exception.class,
                        () -> caller.headBucket(request -> request.bucket(bucket))).statusCode());
                assertError("BucketAlreadyExists", 409, () -> caller.createBucket(createRequest(bucket)));
                assertError("BucketAlreadyExists", 409, () -> caller.createBucket(createRequest(bucket)
                        .toBuilder().objectOwnership(ObjectOwnership.OBJECT_WRITER).build()));
                assertError("AccessDenied", 403, () -> owner.createBucket(createRequest(bucket)));
                assertError("AccessDenied", 403, () -> owner.createBucket(createRequest(bucket)
                        .toBuilder().objectOwnership(ObjectOwnership.OBJECT_WRITER).build()));
                assertEquals(ObjectOwnership.BUCKET_OWNER_ENFORCED, owner.getBucketOwnershipControls(
                        request -> request.bucket(bucket)).ownershipControls().rules().getFirst().objectOwnership());
            } finally {
                owner.deleteBucketPolicy(request -> request.bucket(bucket));
            }
            assertError("BucketAlreadyOwnedByYou", 409, () -> owner.createBucket(createRequest(bucket)));
        }
    }

    @Test
    void creationSettingsStillNeedIdentityPermissionBeforeCollision() {
        UserCredentials ownerKeys = user(OWNER, ALLOW_S3);
        UserCredentials callerKeys = user(CALLER, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:CreateBucket","Resource":"*"}]}
                """);
        try (S3Client owner = client(ownerKeys); S3Client caller = client(callerKeys)) {
            String bucket = createBucket(owner, ownerKeys);
            owner.putBucketPolicy(request -> request.bucket(bucket).policy(bucketPolicy(bucket, "Allow")));
            assertError("BucketAlreadyExists", 409, () -> caller.createBucket(createRequest(bucket)));
            assertError("AccessDenied", 403, () -> caller.createBucket(createRequest(bucket)
                    .toBuilder().objectOwnership(ObjectOwnership.OBJECT_WRITER).build()));
            owner.headBucket(request -> request.bucket(bucket));
        }
    }

    private UserCredentials user(String account, String policy) {
        String name = "collision-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, endpoint.getPort(), auth(account, "iam"), name);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(account, "iam"), name, "s3", policy);
        return credentials;
    }

    private S3Client client(UserCredentials credentials) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_2).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(credentials.accessKeyId(), credentials.secretAccessKey())))
                .build();
    }

    private String createBucket(S3Client owner, UserCredentials credentials) {
        String bucket = "collision-" + UUID.randomUUID();
        cleanup.register(() -> EnforcementFixtures.removeBucket(endpoint.getPort(),
                S3RequestSigner.signedAs(credentials.accessKeyId(), credentials.secretAccessKey())
                        .inRegion("us-east-2"), bucket));
        owner.createBucket(createRequest(bucket));
        return bucket;
    }

    private static CreateBucketRequest createRequest(String bucket) {
        return CreateBucketRequest.builder().bucket(bucket)
                .createBucketConfiguration(configuration -> configuration.locationConstraint("us-east-2")).build();
    }

    private static String bucketPolicy(String bucket, String effect) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s",
                  "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"s3:*",
                  "Resource":["arn:aws:s3:::%s","arn:aws:s3:::%s/*"]}]}
                """.formatted(effect, CALLER, bucket, bucket);
    }

    private static void assertError(String code, int status, Runnable request) {
        S3Exception error = assertThrows(S3Exception.class, request::run);
        assertEquals(code, error.awsErrorDetails().errorCode());
        assertEquals(status, error.statusCode());
    }
}
