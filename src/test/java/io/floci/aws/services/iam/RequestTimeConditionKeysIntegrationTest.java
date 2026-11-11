package io.floci.aws.services.iam;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.MutableClock;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The request-time keys of the IAM User Guide's global condition keys: {@code aws:CurrentTime} and
 * {@code aws:EpochTime}, "always included in the request context", and {@code aws:TokenIssueTime},
 * present "only when the principal uses temporary credentials", which the guide's revoking role
 * sessions page matches with its {@code AWSRevokeOlderSessions} policy.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class RequestTimeConditionKeysIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String ALLOW_LIST_USERS = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"iam:ListUsers","Resource":"*"}]}""";
    private static final String ALLOW_ASSUME = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*"}]}""";

    @TestHTTPResource("/")
    URI baseUri;
    /** The clock STS issues sessions by, so the revocation time can sit between two of them. */
    @Inject
    MutableClock clock;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void revokingOlderSessionsDeniesSessionsIssuedBeforeAndAllowsSessionsIssuedAfter() {
        String roleName = "revoked-role-" + suffix();
        UserCredentials source = user("revoker-source-" + suffix(), ALLOW_ASSUME);
        try (IamClient admin = admin(); StsClient sts = stsClient(source)) {
            String roleArn = role(admin, roleName, ALLOW_LIST_USERS);
            Credentials older = sts.assumeRole(r -> r.roleArn(roleArn).roleSessionName("older")).credentials();
            try (IamClient olderSession = sessionClient(older)) {
                olderSession.listUsers();

                // The guide's policy names a time to the second, as the console writes it.
                Instant revokedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
                admin.putRolePolicy(r -> r.roleName(roleName).policyName("AWSRevokeOlderSessions")
                        .policyDocument(revokeOlderSessions(revokedAt)));
                denied(olderSession::listUsers);

                clock.advance(Duration.ofSeconds(2));
                Credentials newer = sts.assumeRole(r -> r.roleArn(roleArn).roleSessionName("newer")).credentials();
                try (IamClient newerSession = sessionClient(newer)) {
                    newerSession.listUsers();
                }
                denied(olderSession::listUsers);
            }
        }
    }

    @Test
    void tokenIssueTimeIsAbsentForLongTermKeysAndPresentForTheirSessionTokens() {
        String name = "token-time-user-" + suffix();
        UserCredentials keys = user(name, ALLOW_LIST_USERS);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), name, "revoke",
                revokeOlderSessions(Instant.parse("2100-01-01T00:00:00Z")));
        try (IamClient longTerm = userClient(keys); StsClient sts = stsClient(keys)) {
            longTerm.listUsers();
            Credentials session = sts.getSessionToken().credentials();
            try (IamClient temporary = sessionClient(session)) {
                denied(temporary::listUsers);
            }
        }
    }

    @Test
    void currentTimeAndEpochTimeAreInEveryRequestContext() {
        String name = "current-time-user-" + suffix();
        UserCredentials keys = user(name, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"iam:ListUsers","Resource":"*",
                "Condition":{"DateGreaterThan":{"aws:CurrentTime":"2020-01-01T00:00:00Z"},
                "NumericGreaterThan":{"aws:EpochTime":"1577836800"},
                "DateLessThan":{"aws:EpochTime":"4102444800"}}}]}""");
        try (IamClient caller = userClient(keys)) {
            caller.listUsers();
            EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), name, "deny", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Action":"*","Resource":"*",
                    "Condition":{"DateLessThan":{"aws:CurrentTime":"2100-01-01T00:00:00Z"}}}]}""");
            denied(caller::listUsers);
        }
    }

    private static String revokeOlderSessions(Instant revokedAt) {
        return """
                {"Version":"2012-10-17","Statement":{"Effect":"Deny","Action":"*","Resource":"*",
                "Condition":{"DateLessThan":{"aws:TokenIssueTime":"%s"}}}}""".formatted(revokedAt);
    }

    private UserCredentials user(String name, String policy) {
        UserCredentials keys = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), name);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), name, "base", policy);
        return keys;
    }

    private String role(IamClient admin, String name, String policy) {
        String arn = admin.createRole(r -> r.roleName(name).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                "Principal":{"AWS":"arn:aws:iam::%s:root"}}]}""".formatted(ACCOUNT))).role().arn();
        cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), auth(ACCOUNT, "iam"), name));
        admin.putRolePolicy(r -> r.roleName(name).policyName("base").policyDocument(policy));
        return arn;
    }

    private static IamClient admin() {
        return AwsTestClients.create(IamClient.builder(), ACCOUNT, Region.US_EAST_1);
    }

    private static IamClient userClient(UserCredentials keys) {
        return AwsTestClients.create(IamClient.builder(), keys.accessKeyId(), keys.secretAccessKey(), Region.US_EAST_1);
    }

    private static StsClient stsClient(UserCredentials keys) {
        return AwsTestClients.create(StsClient.builder(), keys.accessKeyId(), keys.secretAccessKey(), Region.US_EAST_1);
    }

    private IamClient sessionClient(Credentials session) {
        return IamClient.builder().endpointOverride(baseUri).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        session.accessKeyId(), session.secretAccessKey(), session.sessionToken())))
                .build();
    }

    private static void denied(Runnable operation) {
        AwsServiceException error = assertThrows(AwsServiceException.class, operation::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
