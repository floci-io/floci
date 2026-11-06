package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.IdentityProviderType;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Cognito Google federation")
class CognitoGoogleFederationTest {

    @Test
    @DisplayName("AWS SDK provider details round-trip and Google authorization redirect")
    void googleProviderUsesDefaultAuthorizationEndpointWithoutChangingStoredDetails() throws Exception {
        try (CognitoIdentityProviderClient cognito = TestFixtures.cognitoClient()) {
            String poolId = cognito.createUserPool(request -> request.poolName("google-federation-" + UUID.randomUUID()))
                    .userPool().id();
            try {
                Map<String, String> details = Map.of("client_id", "google-client", "client_secret", "google-secret",
                        "authorize_scopes", "openid email profile");
                cognito.createIdentityProvider(request -> request.userPoolId(poolId).providerName("Google")
                        .providerType("Google").providerDetails(details).attributeMapping(Map.of("email", "email")));
                IdentityProviderType provider = cognito.describeIdentityProvider(request -> request
                        .userPoolId(poolId).providerName("Google")).identityProvider();
                assertThat(provider.providerTypeAsString()).isEqualTo("Google");
                assertThat(provider.providerDetails()).containsExactlyInAnyOrderEntriesOf(details);
                assertThat(provider.attributeMapping()).containsEntry("email", "email");

                String callback = "https://client.example/callback";
                String clientId = cognito.createUserPoolClient(request -> request.userPoolId(poolId)
                        .clientName("google-client").allowedOAuthFlowsUserPoolClient(true)
                        .allowedOAuthFlowsWithStrings("code").allowedOAuthScopes("openid", "email", "profile")
                        .supportedIdentityProviders("Google").callbackURLs(callback)).userPoolClient().clientId();
                URI authorize = URI.create(TestFixtures.endpoint() + "/cognito-idp/oauth2/authorize?client_id=" + clientId
                        + "&response_type=code&scope=openid&identity_provider=Google&redirect_uri="
                        + URLEncoder.encode(callback, StandardCharsets.UTF_8));
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(authorize).timeout(Duration.ofSeconds(20))
                        .GET().build(), HttpResponse.BodyHandlers.discarding());

                assertThat(response.statusCode()).isEqualTo(302);
                URI redirect = URI.create(response.headers().firstValue("Location").orElseThrow());
                assertThat(redirect.getHost()).isEqualTo("accounts.google.com");
                assertThat(redirect.getPath()).isEqualTo("/o/oauth2/v2/auth");
                Map<String, String> parameters = queryParameters(redirect);
                assertThat(parameters).containsEntry("client_id", "google-client")
                        .containsEntry("scope", "openid email profile").containsEntry("response_type", "code");
                assertThat(parameters.get("state")).isNotBlank();
                assertThat(redirect.toString()).doesNotContain("google-secret");
                assertThat(cognito.describeIdentityProvider(request -> request.userPoolId(poolId).providerName("Google"))
                        .identityProvider().providerDetails()).containsExactlyInAnyOrderEntriesOf(details);
            } finally {
                cognito.deleteUserPool(request -> request.userPoolId(poolId));
            }
        }
    }

    private Map<String, String> queryParameters(URI uri) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] entry = pair.split("=", 2);
            parameters.put(URLDecoder.decode(entry[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
        }
        return parameters;
    }
}
