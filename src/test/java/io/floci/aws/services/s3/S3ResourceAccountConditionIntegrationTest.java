package io.floci.aws.services.s3;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.S3IamEnforcementProfile;
import io.floci.aws.testutil.S3RequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class S3ResourceAccountConditionIntegrationTest {
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
    @ValueSource(strings = {"aws:ResourceAccount", "s3:ResourceAccount"})
    void sessionPolicyUsesBucketOwnerAndStillRestrictsPrefixAndHonorsDeny(String conditionKey) {
        String suffix = UUID.randomUUID().toString();
        String bucket = "resource-account-" + suffix;
        String user = "resource-account-" + suffix;
        String role = "resource-account-" + suffix;
        UserCredentials ownerKeys = EnforcementFixtures.createUser(
                cleanup, endpoint.getPort(), auth(OWNER, "iam"), user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(OWNER, "iam"), user, "s3", ALLOW_S3);
        cleanup.register(() -> EnforcementFixtures.removeRole(endpoint.getPort(), auth(CALLER, "iam"), role));
        cleanup.register(() -> EnforcementFixtures.removeBucket(endpoint.getPort(),
                S3RequestSigner.signedAs(ownerKeys.accessKeyId(), ownerKeys.secretAccessKey()), bucket));
        try (IamClient iam = AwsTestClients.create(IamClient.builder(), CALLER, Region.US_EAST_1);
             StsClient sts = AwsTestClients.create(StsClient.builder(), CALLER, Region.US_EAST_1);
             S3Client owner = AwsTestClients.create(S3Client.builder().forcePathStyle(true),
                     ownerKeys.accessKeyId(), ownerKeys.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String roleArn = iam.createRole(request -> request.roleName(role).assumeRolePolicyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                      "Principal":{"AWS":"arn:aws:iam::%s:root"}}]}
                    """.formatted(CALLER))).role().arn();
            iam.putRolePolicy(request -> request.roleName(role).policyName("s3").policyDocument(ALLOW_S3));
            owner.createBucket(request -> request.bucket(bucket));
            String grant = """
                    {"Effect":"Allow","Principal":{"AWS":"%s"},"Action":"s3:*",
                      "Resource":["arn:aws:s3:::%s","arn:aws:s3:::%s/*"]}
                    """.formatted(roleArn, bucket, bucket);
            owner.putBucketPolicy(request -> request.bucket(bucket).policy(
                    "{\"Version\":\"2012-10-17\",\"Statement\":[" + grant + "]}"));
            try (S3Client matching = session(sts, roleArn, bucket, conditionKey, OWNER);
                 S3Client wrongOwner = session(sts, roleArn, bucket, conditionKey, CALLER)) {
                matching.putObject(request -> request.bucket(bucket).key("allowed/index-0"),
                        RequestBody.fromString("snapshot-data"));
                assertEquals("snapshot-data", matching.getObjectAsBytes(
                        request -> request.bucket(bucket).key("allowed/index-0")).asUtf8String());
                assertEquals(1, matching.listObjectsV2(request -> request.bucket(bucket).prefix("allowed/")).keyCount());
                denied(() -> matching.listObjectsV2(request -> request.bucket(bucket).prefix("other/")));
                denied(() -> wrongOwner.listObjectsV2(request -> request.bucket(bucket).prefix("allowed/")));
                denied(() -> wrongOwner.getObjectAsBytes(request -> request.bucket(bucket).key("allowed/index-0")));

                String deny = """
                        {"Effect":"Deny","Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*",
                          "Condition":{"StringEquals":{"%s":"%s"}}}
                        """.formatted(bucket, conditionKey, OWNER);
                owner.putBucketPolicy(request -> request.bucket(bucket).policy(
                        "{\"Version\":\"2012-10-17\",\"Statement\":[" + grant + "," + deny + "]}"));
                denied(() -> matching.getObjectAsBytes(request -> request.bucket(bucket).key("allowed/index-0")));
            }
        }
    }

    private S3Client session(StsClient sts, String roleArn, String bucket, String conditionKey, String owner) {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::%1$s",
                    "Condition":{"StringLike":{"s3:prefix":"allowed/*","%2$s":"%3$s"}}},
                  {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject"],"Resource":"arn:aws:s3:::%1$s/allowed/*",
                    "Condition":{"StringEquals":{"%2$s":"%3$s"}}}]}
                """.formatted(bucket, conditionKey, owner);
        Credentials credentials = sts.assumeRole(request -> request.roleArn(roleArn)
                .roleSessionName("snapshot-" + owner).policy(policy)).credentials();
        return AwsTestClients.create(S3Client.builder().forcePathStyle(true), AwsSessionCredentials.create(
                credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()),
                Region.US_EAST_1, endpoint);
    }

    private static void denied(Runnable operation) {
        S3Exception error = assertThrows(S3Exception.class, operation::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
    }
}
