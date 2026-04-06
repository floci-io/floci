package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.*;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

@DisplayName("STS Security Token Service")
class StsTest {

    private static StsClient sts;

    @BeforeAll
    static void setup() {
        sts = TestFixtures.stsClient();
    }

    @AfterAll
    static void cleanup() {
        if (sts != null) {
            sts.close();
        }
    }

    @Test
    void getCallerIdentity() {
        GetCallerIdentityResponse response = sts.getCallerIdentity(
                GetCallerIdentityRequest.builder().build());

        assertThat(response.account()).isNotNull();
        assertThat(response.arn()).isNotNull();
        assertThat(response.userId()).isNotNull();
    }

    @Test
    void getCallerIdentityAccountId() {
        GetCallerIdentityResponse response = sts.getCallerIdentity(
                GetCallerIdentityRequest.builder().build());

        assertThat(response.account()).isEqualTo("000000000000");
    }

    @Test
    void assumeRole() {
        AssumeRoleResponse response = sts.assumeRole(AssumeRoleRequest.builder()
                .roleArn("arn:aws:iam::000000000000:role/sdk-test-assumed-role")
                .roleSessionName("sdk-test-session")
                .durationSeconds(3600)
                .build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().accessKeyId()).startsWith("ASIA");
        assertThat(response.credentials().secretAccessKey()).isNotNull();
        assertThat(response.credentials().sessionToken()).isNotNull();
        assertThat(response.credentials().expiration()).isNotNull();
    }

    @Test
    void assumeRoleReturnsAssumedRoleUserArn() {
        AssumeRoleResponse response = sts.assumeRole(AssumeRoleRequest.builder()
                .roleArn("arn:aws:iam::000000000000:role/my-role")
                .roleSessionName("my-session")
                .build());

        assertThat(response.assumedRoleUser()).isNotNull();
        assertThat(response.assumedRoleUser().arn()).contains("assumed-role/my-role/my-session");
    }

    @Test
    void assumeRoleWithCustomDuration() {
        AssumeRoleResponse response = sts.assumeRole(AssumeRoleRequest.builder()
                .roleArn("arn:aws:iam::000000000000:role/short-lived-role")
                .roleSessionName("short-session")
                .durationSeconds(900)
                .build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().expiration()).isBefore(Instant.now().plusSeconds(901));
    }

    @Test
    void getSessionToken() {
        GetSessionTokenResponse response = sts.getSessionToken(
                GetSessionTokenRequest.builder().durationSeconds(7200).build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().accessKeyId()).startsWith("ASIA");
        assertThat(response.credentials().sessionToken()).isNotNull();
        assertThat(response.credentials().expiration()).isAfter(Instant.now());
    }

    @Test
    void assumeRoleWithWebIdentity() {
        AssumeRoleWithWebIdentityResponse response = sts.assumeRoleWithWebIdentity(
                AssumeRoleWithWebIdentityRequest.builder()
                        .roleArn("arn:aws:iam::000000000000:role/web-identity-role")
                        .roleSessionName("web-session")
                        .webIdentityToken("eyJhbGciOiJSUzI1NiJ9.test-token")
                        .durationSeconds(3600)
                        .build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().accessKeyId()).startsWith("ASIA");
        assertThat(response.assumedRoleUser().arn()).contains("assumed-role/web-identity-role/web-session");
    }

    @Test
    void getFederationToken() {
        GetFederationTokenResponse response = sts.getFederationToken(
                GetFederationTokenRequest.builder()
                        .name("sdk-test-feduser")
                        .durationSeconds(3600)
                        .build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().accessKeyId()).startsWith("ASIA");
        assertThat(response.federatedUser().arn()).contains("federated-user/sdk-test-feduser");
    }

    @Test
    void decodeAuthorizationMessage() {
        DecodeAuthorizationMessageResponse response = sts.decodeAuthorizationMessage(
                DecodeAuthorizationMessageRequest.builder()
                        .encodedMessage("test-encoded-message")
                        .build());

        assertThat(response.decodedMessage()).isNotEmpty();
    }

    @Test
    void assumeRoleMissingRoleArnThrows400() {
        assertThatThrownBy(() -> sts.assumeRole(AssumeRoleRequest.builder()
                .roleSessionName("s")
                .build()))
                .isInstanceOf(StsException.class)
                .extracting(e -> ((StsException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    void assumeRoleWithSaml() {
        AssumeRoleWithSamlResponse response = sts.assumeRoleWithSAML(
                AssumeRoleWithSamlRequest.builder()
                        .roleArn("arn:aws:iam::000000000000:role/saml-role")
                        .principalArn("arn:aws:iam::000000000000:saml-provider/MySAML")
                        .samlAssertion("base64-encoded-saml-assertion")
                        .durationSeconds(3600)
                        .build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().accessKeyId()).startsWith("ASIA");
        assertThat(response.credentials().secretAccessKey()).isNotNull();
        assertThat(response.credentials().sessionToken()).isNotNull();
        assertThat(response.credentials().expiration()).isAfter(Instant.now());
    }

    @Test
    void assumeRoleWithSamlAssumedRoleUser() {
        AssumeRoleWithSamlResponse response = sts.assumeRoleWithSAML(
                AssumeRoleWithSamlRequest.builder()
                        .roleArn("arn:aws:iam::000000000000:role/my-saml-role")
                        .principalArn("arn:aws:iam::000000000000:saml-provider/Corp")
                        .samlAssertion("assertion")
                        .build());

        assertThat(response.assumedRoleUser()).isNotNull();
        assertThat(response.assumedRoleUser().arn()).contains("assumed-role/my-saml-role/");
    }

    @Test
    void assumeRoleWithWebIdentityMissingTokenThrows400() {
        assertThatThrownBy(() -> sts.assumeRoleWithWebIdentity(
                AssumeRoleWithWebIdentityRequest.builder()
                        .roleArn("arn:aws:iam::000000000000:role/r")
                        .roleSessionName("s")
                        .build()))
                .isInstanceOf(StsException.class)
                .extracting(e -> ((StsException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    void getFederationTokenFederatedUserIdFormat() {
        GetFederationTokenResponse response = sts.getFederationToken(
                GetFederationTokenRequest.builder()
                        .name("myuser")
                        .build());

        assertThat(response.federatedUser()).isNotNull();
        assertThat(response.federatedUser().federatedUserId()).isEqualTo("000000000000:myuser");
    }

    @Test
    void getFederationTokenMissingNameThrows400() {
        assertThatThrownBy(() -> sts.getFederationToken(
                GetFederationTokenRequest.builder().build()))
                .isInstanceOf(StsException.class)
                .extracting(e -> ((StsException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    void getSessionTokenDefaultDuration() {
        GetSessionTokenResponse response = sts.getSessionToken(
                GetSessionTokenRequest.builder().build());

        assertThat(response.credentials()).isNotNull();
        assertThat(response.credentials().expiration()).isAfter(Instant.now().plusSeconds(3600));
    }

    @Test
    void decodeAuthorizationMessageEcho() {
        String msg = "exact-message-to-echo-back";
        DecodeAuthorizationMessageResponse response = sts.decodeAuthorizationMessage(
                DecodeAuthorizationMessageRequest.builder()
                        .encodedMessage(msg)
                        .build());

        assertThat(response.decodedMessage()).isEqualTo(msg);
    }

    @Test
    void decodeAuthorizationMessageMissingMessageThrows400() {
        assertThatThrownBy(() -> sts.decodeAuthorizationMessage(
                DecodeAuthorizationMessageRequest.builder().build()))
                .isInstanceOf(StsException.class)
                .extracting(e -> ((StsException) e).statusCode())
                .isEqualTo(400);
    }
}
