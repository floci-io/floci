package io.floci.aws.services.iam;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.MfaDevice;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class PrincipalTagEnforcementIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "222222222222";
    /**
     * What a caller needs of its own policy to pass session tags, beside the operation itself:
     * the generated tagging table has {@code sts:AssumeRole} and {@code sts:GetFederationToken}
     * requiring {@code sts:TagSession} of a request that carries {@code Tags}, which the
     * enforcement filter reads before the operation runs. The role's trust policy is a separate
     * permission, and the cases below tell the two refusals apart by their sentences.
     */
    private static final String ALLOW_TAGGING = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:TagSession","Resource":"*"}]}""";
    private static final String ALLOW_FEDERATION = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Action":["sts:GetFederationToken","sts:TagSession"],"Resource":"*"}]}""";

    private static final String ALLOW_ASSUME = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*"}]}""";
    /**
     * What a role in a chain needs: the assume and the tagging for the next link, the tag-keyed
     * Allow the probe turns on, and a Deny keyed on a tag that must not have come across.
     */
    private static final String CHAINED_POLICY = """
            {"Version":"2012-10-17","Statement":[
            {"Effect":"Allow","Action":["sts:AssumeRole","sts:TagSession"],"Resource":"*"},
            {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*",
             "Condition":{"StringEquals":{"aws:PrincipalTag/Team":"platform"}}},
            {"Effect":"Deny","Action":"iam:ListUsers","Resource":"*",
             "Condition":{"StringEquals":{"aws:PrincipalTag/Scratch":"local"}}}]}""";
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
            // A session token reaches an IAM operation only where a device minted it, so the
            // device is what makes this probe reachable; what decides it is still the user's tags.
            Credentials session = sessionBehindADevice(sts, name);
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

    /**
     * A session's own tags are principal tags of its requests, over the tags of the role it
     * assumed: "when you request a session, you can specify principal tags as the session tags.
     * The tags apply to requests that you make using the session's credentials", and a session tag
     * overrides the role's tag of the same key. The role here carries the wrong team, so only the
     * session that passes the right one is allowed, and a session tag of its own can bring the Deny
     * on as well.
     */
    @Test
    void sessionTagsDecideTheCallAndOverrideTheRolesOwnTags() {
        String name = "principal-session-" + suffix();
        String source = "principal-passer-" + suffix();
        UserCredentials keys = user(ACCOUNT, source, ALLOW_ASSUME);
        policy(source, "tagging", ALLOW_TAGGING);
        try (IamClient admin = admin(ACCOUNT);
             StsClient sts = AwsTestClients.create(StsClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                     Region.US_EAST_1)) {
            String target = role(admin, ACCOUNT, name, trustAllowingTags(), TAG_POLICY);
            admin.tagRole(r -> r.roleName(name).tags(tag("Team", "other")));

            Credentials passed = sts.assumeRole(r -> r.roleArn(target).roleSessionName("passed")
                    .tags(sessionTag("Team", "platform"))).credentials();
            try (IamClient caller = sessionClient(passed)) {
                caller.listUsers();
            }

            Credentials none = sts.assumeRole(r -> r.roleArn(target).roleSessionName("none")).credentials();
            try (IamClient caller = sessionClient(none)) {
                denied(caller::listUsers);
            }

            Credentials blocked = sts.assumeRole(r -> r.roleArn(target).roleSessionName("blocked")
                    .tags(sessionTag("Team", "platform"), sessionTag("Blocked", "yes"))).credentials();
            try (IamClient caller = sessionClient(blocked)) {
                denied(caller::listUsers);
            }
        }
    }

    /**
     * A caller the trust policy lets assume the role but not tag the session cannot choose its own
     * principal tags: "for roles without this permission in the trust policy, the AssumeRole
     * operation fails". The same role is assumable without tags, so what is refused is the tagging.
     */
    @Test
    void aTaggedAssumeNeedsTheTrustPolicysPermissionToTag() {
        String name = "principal-untagged-" + suffix();
        String source = "principal-untrusted-" + suffix();
        UserCredentials keys = user(ACCOUNT, source, ALLOW_ASSUME);
        policy(source, "tagging", ALLOW_TAGGING);
        try (IamClient admin = admin(ACCOUNT);
             StsClient sts = AwsTestClients.create(StsClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                     Region.US_EAST_1)) {
            String target = role(admin, ACCOUNT, name, trust(null), TAG_POLICY);

            AwsServiceException refused = assertThrows(AwsServiceException.class,
                    () -> sts.assumeRole(r -> r.roleArn(target).roleSessionName("tagged")
                            .tags(sessionTag("Team", "platform"))));
            assertEquals(403, refused.statusCode());
            // The role's own refusal, which names the resource it was read for, rather than the
            // caller's policy being short of the action: that grant is in place here.
            assertTrue(refused.awsErrorDetails().errorMessage()
                            .endsWith("is not authorized to perform: sts:TagSession on resource: " + target),
                    refused.awsErrorDetails().errorMessage());
            sts.assumeRole(r -> r.roleArn(target).roleSessionName("untagged"));
        }
    }

    /**
     * A federated user's session tags are principal tags of its requests, beside the tags of the
     * user whose key minted it: "when you use the temporary credentials returned by the
     * GetFederationToken operation, the session principal tags include the user tags and the passed
     * session tags". The user is untagged here, so the session tag is the whole of what decides the
     * call.
     *
     * <p>The probe is an object read rather than an IAM call, which such a session never reaches,
     * and it carries the session policy AWS requires of it: without one "the temporary security
     * credentials will not grant any permissions", so the policy is what the tag is read in.
     */
    @Test
    void federatedSessionTagsJoinTheIssuingUsersTags() {
        String bucket = "principal-federated-" + suffix();
        String name = "principal-fed-user-" + suffix();
        String read = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject",
                "Resource":"arn:aws:s3:::%s/*"}]}""".formatted(bucket);
        String readWhenTagged = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject",
                "Resource":"arn:aws:s3:::%s/*",
                "Condition":{"StringEquals":{"aws:PrincipalTag/Team":"platform"}}}]}""".formatted(bucket);
        UserCredentials keys = user(ACCOUNT, name, read);
        policy(name, "federation", ALLOW_FEDERATION);
        try (S3Client owner = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, Region.US_EAST_1);
             StsClient sts = AwsTestClients.create(StsClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                     Region.US_EAST_1)) {
            owner.createBucket(r -> r.bucket(bucket));
            cleanup.register(() -> {
                try (S3Client client = AwsTestClients.create(S3Client.builder().forcePathStyle(true),
                        ACCOUNT, Region.US_EAST_1)) {
                    client.deleteObject(r -> r.bucket(bucket).key("key"));
                    client.deleteBucket(r -> r.bucket(bucket));
                }
            });
            owner.putObject(r -> r.bucket(bucket).key("key"), RequestBody.fromString("content"));

            Credentials tagged = sts.getFederationToken(r -> r.name("fed-" + suffix()).policy(readWhenTagged)
                    .tags(sessionTag("Team", "platform"))).credentials();
            try (S3Client reader = federatedClient(tagged)) {
                assertEquals("content", reader.getObjectAsBytes(r -> r.bucket(bucket).key("key")).asUtf8String());
            }

            Credentials untagged = sts.getFederationToken(r -> r.name("fed-" + suffix())
                    .policy(readWhenTagged)).credentials();
            try (S3Client reader = federatedClient(untagged)) {
                denied(() -> reader.getObjectAsBytes(r -> r.bucket(bucket).key("key")));
            }
        }
    }

    /**
     * A transitive session tag passes into the session assumed from it and stays transitive, so it
     * reaches every later link: "if you set a tag key as transitive, the corresponding key and
     * value passes to subsequent sessions in a role chain", and in the guide's own chain the
     * second session's inherited tags "continue to be transitive" without the second request
     * naming them again.
     *
     * <p>The two controls are what make it a test of the opt-in rather than of tags leaking. The
     * tag that is not named transitive does not come across, which the Deny in the role's policy
     * would have turned on, and a session that named no transitive key at all passes nothing,
     * which is AWS's default: "by default, AWS STS does not pass tags to subsequent role sessions".
     * The role assumed in the chain carries no tag of its own, so an inherited tag is the only way
     * the Allow can match.
     */
    @Test
    void aTransitiveTagPassesIntoEveryLaterSessionOfTheChain() {
        String first = "principal-chain-one-" + suffix();
        String second = "principal-chain-two-" + suffix();
        String source = "principal-chainer-" + suffix();
        UserCredentials keys = user(ACCOUNT, source, ALLOW_ASSUME);
        policy(source, "tagging", ALLOW_TAGGING);
        try (IamClient admin = admin(ACCOUNT);
             StsClient sts = AwsTestClients.create(StsClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                     Region.US_EAST_1)) {
            String one = role(admin, ACCOUNT, first, trustAllowingTags(), CHAINED_POLICY);
            // The role assumed in the chain permits tagging too, which an inherited tag needs of it
            // as much as a passed one does.
            String two = role(admin, ACCOUNT, second, trustAllowingTags(), CHAINED_POLICY);

            Credentials passed = sts.assumeRole(r -> r.roleArn(one).roleSessionName("chain-one")
                    .tags(sessionTag("Team", "platform"), sessionTag("Scratch", "local"))
                    .transitiveTagKeys("Team")).credentials();
            try (StsClient chained = AwsTestClients.create(StsClient.builder(), sessionCredentials(passed),
                    Region.US_EAST_1, baseUri)) {
                Credentials inherited = chained.assumeRole(r -> r.roleArn(two)
                        .roleSessionName("chain-two")).credentials();
                try (IamClient caller = sessionClient(inherited)) {
                    caller.listUsers();
                }
                try (StsClient onwards = AwsTestClients.create(StsClient.builder(),
                        sessionCredentials(inherited), Region.US_EAST_1, baseUri)) {
                    Credentials third = onwards.assumeRole(r -> r.roleArn(two)
                            .roleSessionName("chain-three")).credentials();
                    try (IamClient caller = sessionClient(third)) {
                        caller.listUsers();
                    }
                }
            }

            Credentials kept = sts.assumeRole(r -> r.roleArn(one).roleSessionName("chain-kept")
                    .tags(sessionTag("Team", "platform"))).credentials();
            try (StsClient chained = AwsTestClients.create(StsClient.builder(), sessionCredentials(kept),
                    Region.US_EAST_1, baseUri)) {
                Credentials nothing = chained.assumeRole(r -> r.roleArn(two)
                        .roleSessionName("chain-default")).credentials();
                try (IamClient caller = sessionClient(nothing)) {
                    denied(caller::listUsers);
                }
            }
        }
    }

    /**
     * A chained assume is held to the role's tagging permission for the tags it inherits, not only
     * for the tags its own request passes. The guide lists "incoming transitive session tags" among
     * the tags a principal carries, and where a base role's own tags are passed on by STS "the
     * trust policy must also allow sts:TagSession", the refusal naming that action. The same role
     * takes an assume from a session that passes nothing down, so what is refused here is the
     * tagging rather than the assume.
     */
    @Test
    void aChainedAssumeNeedsTheRolesTaggingPermissionForTheTagsItInherits() {
        String first = "principal-inherit-one-" + suffix();
        String second = "principal-inherit-two-" + suffix();
        String source = "principal-inheritor-" + suffix();
        UserCredentials keys = user(ACCOUNT, source, ALLOW_ASSUME);
        policy(source, "tagging", ALLOW_TAGGING);
        try (IamClient admin = admin(ACCOUNT);
             StsClient sts = AwsTestClients.create(StsClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                     Region.US_EAST_1)) {
            String one = role(admin, ACCOUNT, first, trustAllowingTags(), CHAINED_POLICY);
            String untagged = role(admin, ACCOUNT, second, trust(null), CHAINED_POLICY);

            Credentials passing = sts.assumeRole(r -> r.roleArn(one).roleSessionName("inherit-one")
                    .tags(sessionTag("Team", "platform")).transitiveTagKeys("Team")).credentials();
            try (StsClient chained = AwsTestClients.create(StsClient.builder(), sessionCredentials(passing),
                    Region.US_EAST_1, baseUri)) {
                AwsServiceException refused = assertThrows(AwsServiceException.class,
                        () -> chained.assumeRole(r -> r.roleArn(untagged).roleSessionName("inherit-two")));
                assertEquals(403, refused.statusCode());
                assertTrue(refused.awsErrorDetails().errorMessage()
                                .endsWith("is not authorized to perform: sts:TagSession on resource: " + untagged),
                        refused.awsErrorDetails().errorMessage());
            }

            Credentials passingNothing = sts.assumeRole(r -> r.roleArn(one).roleSessionName("inherit-none")
                    .tags(sessionTag("Team", "platform"))).credentials();
            try (StsClient chained = AwsTestClients.create(StsClient.builder(),
                    sessionCredentials(passingNothing), Region.US_EAST_1, baseUri)) {
                chained.assumeRole(r -> r.roleArn(untagged).roleSessionName("inherit-plain"));
            }
        }
    }

    /**
     * A session tag whose key the calling session already passes down is refused: "if you pass a
     * session tag with the same key as an inherited tag, the operation fails". The key is matched
     * without regard to case, as a tag key is anywhere else, so the lowercase spelling here is the
     * same tag. What AWS answers is not published, so the code and the sentence are Floci's, and
     * the role being assumed permits tagging, which is how this refusal is told apart from the
     * tagging permission's.
     */
    @Test
    void aSessionTagCollidingWithAnInheritedTransitiveTagIsRefused() {
        String first = "principal-collide-one-" + suffix();
        String second = "principal-collide-two-" + suffix();
        String source = "principal-collider-" + suffix();
        UserCredentials keys = user(ACCOUNT, source, ALLOW_ASSUME);
        policy(source, "tagging", ALLOW_TAGGING);
        try (IamClient admin = admin(ACCOUNT);
             StsClient sts = AwsTestClients.create(StsClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                     Region.US_EAST_1)) {
            String one = role(admin, ACCOUNT, first, trustAllowingTags(), CHAINED_POLICY);
            String two = role(admin, ACCOUNT, second, trustAllowingTags(), CHAINED_POLICY);

            Credentials passed = sts.assumeRole(r -> r.roleArn(one).roleSessionName("collide-one")
                    .tags(sessionTag("Team", "platform")).transitiveTagKeys("Team")).credentials();
            try (StsClient chained = AwsTestClients.create(StsClient.builder(), sessionCredentials(passed),
                    Region.US_EAST_1, baseUri)) {
                AwsServiceException refused = assertThrows(AwsServiceException.class,
                        () -> chained.assumeRole(r -> r.roleArn(two).roleSessionName("collide-two")
                                .tags(sessionTag("team", "other"))));
                assertEquals(400, refused.statusCode());
                assertEquals("ValidationError", refused.awsErrorDetails().errorCode());
                assertTrue(refused.awsErrorDetails().errorMessage().contains("Session tag team"),
                        refused.awsErrorDetails().errorMessage());

                // A key the calling session does not pass down is not held to it.
                chained.assumeRole(r -> r.roleArn(two).roleSessionName("collide-other")
                        .tags(sessionTag("Scratch", "local")));
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

    /** A second inline policy on a user of this account, for a case that needs two grants. */
    private void policy(String userName, String policyName, String document) {
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), userName, policyName, document);
    }

    private String role(IamClient admin, String account, String name, String trust, String policy) {
        String arn = admin.createRole(r -> r.roleName(name).path("/teams/platform/")
                .assumeRolePolicyDocument(trust)).role().arn();
        cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), auth(account, "iam"), name));
        admin.putRolePolicy(r -> r.roleName(name).policyName("principal").policyDocument(policy));
        return arn;
    }

    private static String trust(String team) {
        return trust(team, "\"sts:AssumeRole\"");
    }

    /**
     * A trust policy that permits tagging the session as well as assuming the role, which a
     * request passing session tags needs: "trust policies for all roles connected to the identity
     * provider passing tags must have the sts:TagSession permission."
     */
    private static String trustAllowingTags() {
        return trust(null, "[\"sts:AssumeRole\",\"sts:TagSession\"]");
    }

    private static String trust(String team, String action) {
        String condition = team == null ? "" : ",\"Condition\":{\"StringEquals\":{\"aws:PrincipalTag/Team\":\"" + team + "\"}}";
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":%s,
                "Principal":{"AWS":"arn:aws:iam::%s:root"}%s}]}""".formatted(action, ACCOUNT, condition);
    }

    private static IamClient admin(String account) {
        return AwsTestClients.create(IamClient.builder(), account, Region.US_EAST_1);
    }

    private static IamClient userClient(UserCredentials keys) {
        return AwsTestClients.create(IamClient.builder(), keys.accessKeyId(), keys.secretAccessKey(), Region.US_EAST_1);
    }

    /** A session minted behind a virtual device enabled for the user, the way a client sets one up. */
    private Credentials sessionBehindADevice(StsClient sts, String userName) {
        MfaDevice device = EnforcementFixtures.enableMfaDevice(baseUri.getPort(), auth(ACCOUNT, "iam"), userName);
        return sts.getSessionToken(r -> r.serialNumber(device.serialNumber())
                .tokenCode(device.codeNow())).credentials();
    }

    private S3Client federatedClient(Credentials session) {
        return AwsTestClients.create(S3Client.builder().forcePathStyle(true), sessionCredentials(session),
                Region.US_EAST_1, baseUri);
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

    /**
     * A session tag for a request that mints a session. Both services model a {@code Tag} of their
     * own, and IAM's is the one this class names most, so STS's is qualified here, once.
     */
    private static software.amazon.awssdk.services.sts.model.Tag sessionTag(String key, String value) {
        return software.amazon.awssdk.services.sts.model.Tag.builder().key(key).value(value).build();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
