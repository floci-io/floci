package io.floci.aws.services.iam;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.Tag;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class PrincipalTagEnforcementIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "222222222222";
    private static final String ALLOW_ASSUME = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*"}]}""";
    private static final String TAG_POLICY = """
            {"Version":"2012-10-17","Statement":[
            {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*",
             "Condition":{"StringEquals":{"aws:PrincipalTag/Team":"platform"}}},
            {"Effect":"Deny","Action":"iam:ListUsers","Resource":"*",
             "Condition":{"StringEquals":{"aws:PrincipalTag/Blocked":"yes"}}}]}""";

    @TestHTTPResource("/")
    URI baseUri;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void userTagsControlAllowAndDenyAndUpdatesApplyToExistingCredentials() {
        String name = "principal-user-" + suffix();
        UserCredentials keys = user(ACCOUNT, name, TAG_POLICY);
        try (IamClient admin = admin(ACCOUNT); IamClient caller = userClient(keys)) {
            denied(caller::listUsers);
            admin.tagUser(r -> r.userName(name).tags(tag("Team", "Platform")));
            denied(caller::listUsers);
            admin.tagUser(r -> r.userName(name).tags(tag("Team", "platform")));
            caller.listUsers();
            admin.tagUser(r -> r.userName(name).tags(tag("Blocked", "yes")));
            denied(caller::listUsers);
            admin.untagUser(r -> r.userName(name).tagKeys("Blocked"));
            caller.listUsers();
            admin.untagUser(r -> r.userName(name).tagKeys("Team"));
            denied(caller::listUsers);
        }
    }

    @Test
    void sessionTokenUsesTheIssuingUsersTags() {
        String name = "principal-token-" + suffix();
        UserCredentials keys = user(ACCOUNT, name, TAG_POLICY);
        try (IamClient admin = admin(ACCOUNT);
             StsClient sts = AwsTestClients.create(StsClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                     Region.US_EAST_1)) {
            admin.tagUser(r -> r.userName(name).tags(tag("Team", "platform")));
            Credentials session = sts.getSessionToken().credentials();
            try (IamClient caller = sessionClient(session)) {
                caller.listUsers();
                admin.untagUser(r -> r.userName(name).tagKeys("Team"));
                denied(caller::listUsers);
            }
        }
    }

    @Test
    void assumedRoleUsesItsOwnAccountAndTagsRatherThanTheSourceUserOrSameNamedRole() {
        String name = "principal-role-" + suffix();
        String source = "principal-source-" + suffix();
        UserCredentials keys = user(ACCOUNT, source, ALLOW_ASSUME);
        try (IamClient own = admin(ACCOUNT); IamClient other = admin(OTHER_ACCOUNT);
             StsClient sts = AwsTestClients.create(StsClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                     Region.US_EAST_1)) {
            own.tagUser(r -> r.userName(source).tags(tag("Team", "source-user"), tag("Blocked", "yes")));
            role(own, ACCOUNT, name, trust(null), TAG_POLICY);
            own.tagRole(r -> r.roleName(name).tags(tag("Team", "wrong-account"), tag("Blocked", "yes")));
            String target = role(other, OTHER_ACCOUNT, name, trust(null), TAG_POLICY);
            other.tagRole(r -> r.roleName(name).tags(tag("Team", "platform")));
            Credentials session = sts.assumeRole(r -> r.roleArn(target).roleSessionName("tag-test")).credentials();
            try (IamClient caller = sessionClient(session)) {
                caller.listUsers();
                other.tagRole(r -> r.roleName(name).tags(tag("Blocked", "yes")));
                denied(caller::listUsers);
                other.untagRole(r -> r.roleName(name).tagKeys("Blocked", "Team"));
                denied(caller::listUsers);
            }
        }
    }

    @Test
    void trustPolicyReadsTheCallersTagsInsteadOfTheTargetRolesTags() {
        String name = "principal-trust-" + suffix();
        String source = "principal-truster-" + suffix();
        UserCredentials keys = user(ACCOUNT, source, ALLOW_ASSUME);
        try (IamClient admin = admin(ACCOUNT);
             StsClient sts = AwsTestClients.create(StsClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                     Region.US_EAST_1)) {
            String target = role(admin, ACCOUNT, name, trust("platform"), ALLOW_ASSUME);
            admin.tagRole(r -> r.roleName(name).tags(tag("Team", "platform")));
            denied(() -> sts.assumeRole(r -> r.roleArn(target).roleSessionName("missing")));
            admin.tagUser(r -> r.userName(source).tags(tag("Team", "other")));
            denied(() -> sts.assumeRole(r -> r.roleArn(target).roleSessionName("wrong")));
            admin.tagUser(r -> r.userName(source).tags(tag("Team", "platform")));
            Credentials session = sts.assumeRole(r -> r.roleArn(target).roleSessionName("matching")).credentials();
            // A chained request sees the assumed role's tags, not the original user's tags.
            admin.tagUser(r -> r.userName(source).tags(tag("Team", "other")));
            try (StsClient chained = AwsTestClients.create(StsClient.builder(),
                    sessionCredentials(session), Region.US_EAST_1, baseUri)) {
                chained.assumeRole(r -> r.roleArn(target).roleSessionName("chain"));
                admin.untagRole(r -> r.roleName(name).tagKeys("Team"));
                denied(() -> chained.assumeRole(r -> r.roleArn(target).roleSessionName("untagged")));
            }
        }
    }

    @Test
    void sameNamedUsersInDifferentAccountsKeepTheirOwnTags() {
        String name = "principal-account-" + suffix();
        UserCredentials first = user(ACCOUNT, name, TAG_POLICY);
        UserCredentials second = user(OTHER_ACCOUNT, name, TAG_POLICY);
        try (IamClient own = admin(ACCOUNT); IamClient other = admin(OTHER_ACCOUNT);
             IamClient allowed = userClient(first); IamClient refused = userClient(second)) {
            own.tagUser(r -> r.userName(name).tags(tag("Team", "platform")));
            other.tagUser(r -> r.userName(name).tags(tag("Team", "other")));
            allowed.listUsers();
            denied(refused::listUsers);
        }
    }

    @Test
    void s3BucketPolicyDeniesTheTaggedCaller() {
        String bucket = "principal-bucket-" + suffix();
        String name = "principal-reader-" + suffix();
        UserCredentials keys = user(ACCOUNT, name, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}""".formatted(bucket));
        try (S3Client owner = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, Region.US_EAST_1);
             S3Client reader = AwsTestClients.create(S3Client.builder().forcePathStyle(true), keys.accessKeyId(),
                     keys.secretAccessKey(), Region.US_EAST_1);
             IamClient admin = admin(ACCOUNT)) {
            owner.createBucket(r -> r.bucket(bucket));
            cleanup.register(() -> {
                try (S3Client client = AwsTestClients.create(S3Client.builder().forcePathStyle(true),
                        ACCOUNT, Region.US_EAST_1)) {
                    client.deleteObject(r -> r.bucket(bucket).key("key"));
                    client.deleteBucket(r -> r.bucket(bucket));
                }
            });
            owner.putObject(r -> r.bucket(bucket).key("key"), RequestBody.fromString("content"));
            owner.putBucketPolicy(r -> r.bucket(bucket).policy("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":"*",
                    "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*",
                    "Condition":{"StringEquals":{"aws:PrincipalTag/Blocked":"yes"}}}]}""".formatted(bucket)));
            assertEquals("content", reader.getObjectAsBytes(r -> r.bucket(bucket).key("key")).asUtf8String());
            admin.tagUser(r -> r.userName(name).tags(tag("Blocked", "yes")));
            denied(() -> reader.getObjectAsBytes(r -> r.bucket(bucket).key("key")));
        }
    }

    private UserCredentials user(String account, String name, String policy) {
        UserCredentials keys = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(account, "iam"), name);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(account, "iam"), name, "principal", policy);
        return keys;
    }

    private String role(IamClient admin, String account, String name, String trust, String policy) {
        String arn = admin.createRole(r -> r.roleName(name).path("/teams/platform/")
                .assumeRolePolicyDocument(trust)).role().arn();
        cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), auth(account, "iam"), name));
        admin.putRolePolicy(r -> r.roleName(name).policyName("principal").policyDocument(policy));
        return arn;
    }

    private static String trust(String team) {
        String condition = team == null ? "" : ",\"Condition\":{\"StringEquals\":{\"aws:PrincipalTag/Team\":\"" + team + "\"}}";
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                "Principal":{"AWS":"arn:aws:iam::%s:root"}%s}]}""".formatted(ACCOUNT, condition);
    }

    private static IamClient admin(String account) {
        return AwsTestClients.create(IamClient.builder(), account, Region.US_EAST_1);
    }

    private static IamClient userClient(UserCredentials keys) {
        return AwsTestClients.create(IamClient.builder(), keys.accessKeyId(), keys.secretAccessKey(), Region.US_EAST_1);
    }

    private IamClient sessionClient(Credentials session) {
        return AwsTestClients.create(IamClient.builder(), sessionCredentials(session), Region.US_EAST_1, baseUri);
    }

    private static AwsSessionCredentials sessionCredentials(Credentials session) {
        return AwsSessionCredentials.create(session.accessKeyId(), session.secretAccessKey(), session.sessionToken());
    }

    private static void denied(Runnable operation) {
        AwsServiceException error = assertThrows(AwsServiceException.class, operation::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
