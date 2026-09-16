package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.ssoadmin.SsoAdminClient;
import software.amazon.awssdk.services.ssooidc.SsoOidcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("IAM Identity Center OIDC")
class SsoOidcTest {

    @Test
    @DisplayName("registers a public OIDC client through the AWS SDK")
    void registerClientUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only client registration");

        try (SsoOidcClient oidc = TestFixtures.ssoOidcClient()) {
            var response = oidc.registerClient(request -> request
                    .clientName("Floci SDK CLI")
                    .clientType("public")
                    .grantTypes("authorization_code", "refresh_token")
                    .redirectUris("http://127.0.0.1:8400/callback")
                    .scopes("sso:account:access"));

            assertThat(response.clientId()).matches("[0-9a-f]{32}");
            assertThat(response.clientSecret()).matches("[0-9a-f]{64}");
            assertThat(response.clientIdIssuedAt()).isPositive();
            assertThat(response.clientSecretExpiresAt()).isGreaterThan(response.clientIdIssuedAt());
            assertThat(response.authorizationEndpoint()).endsWith("/authorize");
            assertThat(response.tokenEndpoint()).endsWith("/token");
        }
    }

    @Test
    @DisplayName("starts device authorization through the AWS SDK")
    void startDeviceAuthorizationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only device authorization");

        try (SsoOidcClient oidc = TestFixtures.ssoOidcClient()) {
            var client = oidc.registerClient(request -> request
                    .clientName("Floci Device SDK")
                    .clientType("public")
                    .grantTypes("urn:ietf:params:oauth:grant-type:device_code", "refresh_token"));
            var response = oidc.startDeviceAuthorization(request -> request
                    .clientId(client.clientId())
                    .clientSecret(client.clientSecret())
                    .startUrl("https://example.awsapps.com/start"));

            assertThat(response.deviceCode()).matches("[0-9a-f]{64}");
            assertThat(response.userCode()).matches("[0-9A-F]{4}-[0-9A-F]{4}");
            assertThat(response.verificationUri()).endsWith("/device");
            assertThat(response.verificationUriComplete()).contains("user_code=" + response.userCode());
            assertThat(response.expiresIn()).isPositive();
            assertThat(response.interval()).isEqualTo(5);

            try {
                var advertisedUri = java.net.URI.create(response.verificationUriComplete());
                var uri = TestFixtures.endpoint().resolve(
                        advertisedUri.getRawPath() + "?" + advertisedUri.getRawQuery());
                var request = java.net.http.HttpRequest.newBuilder(uri).GET().build();
                var browserResponse = TestFixtures.emulatorHttpClient().send(
                        request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(browserResponse.statusCode()).isEqualTo(200);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            var token = oidc.createToken(request -> request
                    .clientId(client.clientId())
                    .clientSecret(client.clientSecret())
                    .grantType("urn:ietf:params:oauth:grant-type:device_code")
                    .deviceCode(response.deviceCode()));
            assertThat(token.tokenType()).isEqualTo("Bearer");
            assertThat(token.accessToken()).isNotBlank();
            assertThat(token.refreshToken()).isNotBlank();

            var refreshed = oidc.createToken(request -> request
                    .clientId(client.clientId())
                    .clientSecret(client.clientSecret())
                    .grantType("refresh_token")
                    .refreshToken(token.refreshToken()));
            assertThat(refreshed.accessToken()).isNotEqualTo(token.accessToken());
        }
    }

    @Test
    @DisplayName("creates IAM-authenticated tokens through the AWS SDK")
    void createTokenWithIamUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator IAM application configuration");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient();
             SsoOidcClient oidc = TestFixtures.ssoOidcClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String applicationArn = sso.createApplication(request -> request
                    .instanceArn(instanceArn)
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom")
                    .name("Floci IAM OIDC SDK"))
                    .applicationArn();

            Document statement = Document.mapBuilder()
                    .putString("Effect", "Allow")
                    .putDocument("Principal", Document.fromString("*"))
                    .putString("Action", "sso-oauth:CreateTokenWithIAM")
                    .putString("Resource", "*")
                    .build();
            Document actorPolicy = Document.mapBuilder()
                    .putString("Version", "2012-10-17")
                    .putDocument("Statement", Document.fromList(java.util.List.of(statement)))
                    .build();
            sso.putApplicationAuthenticationMethod(request -> request
                    .applicationArn(applicationArn)
                    .authenticationMethodType("IAM")
                    .authenticationMethod(method -> method.iam(iam -> iam.actorPolicy(actorPolicy))));
            String redirectUri = "http://127.0.0.1:8400/callback";
            sso.putApplicationGrant(request -> request
                    .applicationArn(applicationArn)
                    .grantType("authorization_code")
                    .grant(grant -> grant.authorizationCode(code -> code.redirectUris(redirectUri))));
            sso.putApplicationAccessScope(request -> request
                    .applicationArn(applicationArn)
                    .scope("api:read")
                    .authorizedTargets(applicationArn));

            String verifier = "01234567890123456789012345678901234567890123456789";
            String challenge = pkceChallenge(verifier);
            String authorizeUrl = TestFixtures.endpoint().toString()
                    + "/authorize?response_type=code&client_id="
                    + java.net.URLEncoder.encode(applicationArn, java.nio.charset.StandardCharsets.UTF_8)
                    + "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8)
                    + "&code_challenge=" + java.net.URLEncoder.encode(challenge, java.nio.charset.StandardCharsets.UTF_8)
                    + "&code_challenge_method=S256";
            String code;
            try {
                var browserResponse = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .build()
                        .send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(authorizeUrl)).GET().build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(browserResponse.statusCode()).isEqualTo(303);
                var params = java.net.URI.create(browserResponse.headers().firstValue("location").orElseThrow())
                        .getRawQuery().split("&");
                code = java.util.Arrays.stream(params)
                        .filter(value -> value.startsWith("code="))
                        .map(value -> java.net.URLDecoder.decode(value.substring(5), java.nio.charset.StandardCharsets.UTF_8))
                        .findFirst().orElseThrow();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            var response = oidc.createTokenWithIAM(request -> request
                    .clientId(applicationArn)
                    .grantType("authorization_code")
                    .code(code)
                    .codeVerifier(verifier)
                    .redirectUri(redirectUri)
                    .scope("api:read"));

            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.accessToken()).isNotBlank();
            assertThat(response.refreshToken()).isNotBlank();
            assertThat(response.idToken()).isNotBlank();
            assertThat(response.scope()).containsExactly("api:read");
            assertThat(response.awsAdditionalDetails().identityContext()).isNotBlank();
        }
    }

    private static String pkceChallenge(String verifier) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
