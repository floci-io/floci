package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.GetFederationTokenRequest;
import software.amazon.awssdk.services.sts.model.GetSessionTokenRequest;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class StsAccountTokenDurationIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @Inject
    IamService iam;

    @ParameterizedTest
    @CsvSource({"GetSessionToken, absent, 3600", "GetSessionToken, 900, 900",
            "GetSessionToken, 3600, 3600", "GetSessionToken, 3601, 3600",
            "GetSessionToken, 129600, 3600", "GetFederationToken, absent, 3600",
            "GetFederationToken, 900, 900", "GetFederationToken, 3600, 3600",
            "GetFederationToken, 3601, 3600", "GetFederationToken, 129600, 3600"})
    void rootIssuedCredentialsUseTheCappedLifetime(String action, String duration, int expected) {
        try (StsClient sts = AwsTestClients.create(StsClient.builder(), "test", Region.US_EAST_1)) {
            AwsArnUtils.Arn caller = AwsArnUtils.parse(sts.getCallerIdentity().arn());
            assertEquals("iam", caller.service());
            assertEquals(ACCOUNT, caller.accountId());
            assertEquals("root", caller.resource());
            checkLifetime(sts, action, duration, expected);
        }
    }

    @ParameterizedTest
    @CsvSource({"GetSessionToken, absent, 43200", "GetSessionToken, 900, 900",
            "GetSessionToken, 3601, 3601", "GetSessionToken, 129600, 129600",
            "GetFederationToken, absent, 43200", "GetFederationToken, 900, 900",
            "GetFederationToken, 3601, 3601", "GetFederationToken, 129600, 129600"})
    void signedIamUsersRetainTheirRequestedLifetime(String action, String duration, int expected) throws Exception {
        SignedIamUsers.User user = SignedIamUsers.createUser(endpoint(), "token-duration-", "federation",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"sts:GetFederationToken\",\"Resource\":\"*\"}]}");
        try (user; StsClient sts = client(user.accessKeyId(), user.secretAccessKey())) {
            assertTrue(sts.getCallerIdentity().arn().endsWith("user/" + user.name()));
            checkLifetime(sts, action, duration, expected);
        } finally {
            assertFalse(iam.isKnownAccessKey(user.accessKeyId()));
            assertEquals("NoSuchEntity", assertThrows(AwsException.class, () -> iam.getUser(user.name())).getErrorCode());
        }
    }

    private void checkLifetime(StsClient sts, String action, String duration, int expected) {
        Integer seconds = "absent".equals(duration) ? null : Integer.valueOf(duration);
        Instant before = Instant.now();
        Credentials issued = "GetSessionToken".equals(action)
                ? sts.getSessionToken(GetSessionTokenRequest.builder().durationSeconds(seconds).build()).credentials()
                : sts.getFederationToken(GetFederationTokenRequest.builder().name("duration")
                        .durationSeconds(seconds).build()).credentials();
        try {
            Instant after = Instant.now();
            assertTrue(!issued.expiration().isBefore(before.plusSeconds(expected).minusSeconds(1))
                    && !issued.expiration().isAfter(after.plusSeconds(expected)), "Unexpected issued expiration");
            assertFalse(iam.isExpiredSession(issued.accessKeyId(), issued.expiration().minusSeconds(1)));
            assertTrue(iam.isExpiredSession(issued.accessKeyId(), issued.expiration().plusSeconds(1)));
            try (StsClient session = StsClient.builder().endpointOverride(endpoint()).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                            issued.accessKeyId(), issued.secretAccessKey(), issued.sessionToken()))).build()) {
                assertEquals(ACCOUNT, session.getCallerIdentity().account());
            }
        } finally {
            iam.unregisterSession(ACCOUNT, issued.accessKeyId());
            assertFalse(iam.isKnownAccessKey(issued.accessKeyId()));
        }
    }

    private static StsClient client(String key, String secret) {
        return StsClient.builder().endpointOverride(endpoint()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret))).build();
    }

    private static URI endpoint() {
        return URI.create("http://localhost:" + RestAssured.port);
    }
}
