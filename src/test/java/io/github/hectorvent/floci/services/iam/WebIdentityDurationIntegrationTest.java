package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.eks.EksOidcService;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityRequest;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class WebIdentityDurationIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;
    @Inject
    EksOidcService oidc;

    @ParameterizedTest
    @CsvSource({"3600,3601,valid,ValidationError", "7200,7201,valid,ValidationError",
            "3600,3600,valid,allow", "7200,7200,valid,allow", "3600,900,valid,allow",
            "3600,0,valid,allow", "3600,3601,untrusted,AccessDenied",
            "3600,3601,invalid,InvalidIdentityToken"})
    void verifiedWebIdentityHonorsTheRoleDuration(int maximum, int requested, String tokenKind,
                                                 String expected) throws Exception {
        String keyName = "web-duration-" + UUID.randomUUID();
        String issuer = oidc.newIssuerUrl("us-east-1");
        String providerArn = "arn:aws:iam::000000000000:oidc-provider/" + issuer.substring(8);
        String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\""
                + providerArn + "\"},\"Action\":\"sts:AssumeRoleWithWebIdentity\",\"Condition\":{\"StringEquals\":{\""
                + issuer.substring(8) + ":sub\":\"system:serviceaccount:default:app\"}}}}";
        List<Credentials> sessions = new ArrayList<>();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "web-duration-", "admin",
                "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}");
             IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                             user.accessKeyId(), user.secretAccessKey()))).build();
             StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(AnonymousCredentialsProvider.create()).build()) {
            Role role = admin.createRole(b -> b.roleName(keyName).path("/teams/web/")
                    .maxSessionDuration(maximum).assumeRolePolicyDocument(trust)).role();
            try {
                String token = oidc.mintServiceAccountToken(keyName, issuer, "default",
                        "untrusted".equals(tokenKind) ? "other" : "app", "sts.amazonaws.com", 3600);
                AssumeRoleWithWebIdentityRequest.Builder request = AssumeRoleWithWebIdentityRequest.builder()
                        .roleArn(role.arn()).roleSessionName("duration")
                        .webIdentityToken("invalid".equals(tokenKind) ? "opaque-token" : token);
                if (requested != 0) {
                    request.durationSeconds(requested);
                }
                Instant before = Instant.now();
                if ("allow".equals(expected)) {
                    Credentials credentials = sts.assumeRoleWithWebIdentity(request.build()).credentials();
                    sessions.add(credentials);
                    int seconds = requested == 0 ? 3600 : requested;
                    assertFalse(credentials.expiration().isBefore(before.plusSeconds(seconds - 1)));
                    assertFalse(credentials.expiration().isAfter(Instant.now().plusSeconds(seconds)));
                    assertTrue(iam.resolveCallerArns(credentials.accessKeyId()).isPresent());
                } else {
                    StsException error = assertThrows(StsException.class, () ->
                            sessions.add(sts.assumeRoleWithWebIdentity(request.build()).credentials()));
                    assertEquals(expected, error.awsErrorDetails().errorCode());
                    assertEquals("AccessDenied".equals(expected) ? 403 : 400, error.statusCode());
                }
                assertEquals(maximum, admin.getRole(b -> b.roleName(keyName)).role().maxSessionDuration());
            } finally {
                try {
                    for (Credentials session : sessions.reversed()) {
                        iam.unregisterSession("000000000000", session.accessKeyId());
                        assertTrue(iam.resolveCallerArns(session.accessKeyId()).isEmpty());
                    }
                } finally {
                    try {
                        oidc.deleteKey(keyName);
                        assertTrue(oidc.findKeyByCluster(keyName).isEmpty());
                    } finally {
                        admin.deleteRole(b -> b.roleName(keyName));
                    }
                }
            }
        }
    }
}
