package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.customDomain;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.requestCertificate;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@QuarkusTestResource(value = CognitoFederatedSignInIntegrationTest.GoogleProviderResource.class,
        restrictToAnnotatedClass = true)
class CognitoFederatedSignInIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DOMAIN = "federated-" + System.nanoTime() + ".teos.localhost.floci.io";
    private MockOidcProvider googleProvider;

    public static class GoogleProviderResource implements QuarkusTestResourceLifecycleManager {
        private MockOidcProvider provider;

        @Override
        public Map<String, String> start() {
            try {
                provider = new MockOidcProvider();
                return Map.of("floci.services.cognito.google-issuer", provider.baseUrl());
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot start the Google test provider", exception);
            }
        }

        @Override
        public void inject(TestInjector testInjector) {
            testInjector.injectIntoFields(provider, new TestInjector.MatchesType(MockOidcProvider.class));
        }

        @Override
        public void stop() {
            if (provider != null) {
                provider.close();
            }
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void googleProviderSignsInWithConfiguredIssuerAndPreservesAwsProviderDetails() throws Exception {
        String domain = "google-" + System.nanoTime() + ".teos.localhost.floci.io";
        String poolId = cognitoJson("CreateUserPool", "{\"PoolName\":\"GoogleFederationPool\"}")
                .path("UserPool").path("Id").asText();
        String clientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"google-client","AllowedOAuthFlowsUserPoolClient":true,
                 "AllowedOAuthFlows":["code"],"AllowedOAuthScopes":["openid","email","profile"],
                 "SupportedIdentityProviders":["Google"],"CallbackURLs":["https://client.example/callback"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        cognitoJson("CreateIdentityProvider", """
                {"UserPoolId":"%s","ProviderName":"Google","ProviderType":"Google",
                 "ProviderDetails":{"client_id":"google-client","client_secret":"google-secret",
                 "authorize_scopes":"openid email profile"},
                 "AttributeMapping":{"email":"email","email_verified":"email_verified","name":"name",
                 "given_name":"given_name","family_name":"family_name","picture":"picture"}}
                """.formatted(poolId));
        assertEquals(0, googleProvider.discoveryRequests());
        cognitoJson("CreateUserPoolDomain", customDomain(domain, poolId, requestCertificate(domain)));

        String firstUsername = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String location = given().redirects().follow(false).header("Host", domain)
                    .queryParam("client_id", clientId).queryParam("redirect_uri", "https://client.example/callback")
                    .queryParam("response_type", "code").queryParam("scope", "openid")
                    .queryParam("identity_provider", "Google").queryParam("state", "client-state")
                    .when().get("/oauth2/authorize").then().statusCode(302).extract().header("Location");
            assertEquals(googleProvider.baseUrl() + "/authorize", location.substring(0, location.indexOf('?')));
            assertEquals("openid email profile", queryParameter(location, "scope"));
            assertEquals("google-client", queryParameter(location, "client_id"));

            String callback = given().redirects().follow(false).header("Host", domain)
                    .queryParam("state", queryParameter(location, "state")).queryParam("code", "provider-code")
                    .when().get("/oauth2/idpresponse").then().statusCode(302).extract().header("Location");
            assertEquals("client-state", queryParameter(callback, "state"));
            given().header("Host", domain).formParam("grant_type", "authorization_code")
                    .formParam("client_id", clientId).formParam("code", queryParameter(callback, "code"))
                    .formParam("redirect_uri", "https://client.example/callback")
                    .when().post("/oauth2/token").then().statusCode(200).body("token_type", equalTo("Bearer"));

            JsonNode users = cognitoJson("ListUsers", "{\"UserPoolId\":\"%s\"}".formatted(poolId)).path("Users");
            assertEquals(1, users.size());
            JsonNode user = users.get(0);
            String username = user.path("Username").asText();
            assertEquals("Google_provider-subject", username);
            if (firstUsername != null) {
                assertEquals(firstUsername, username);
            }
            firstUsername = username;
            JsonNode attributes = user.path("Attributes");
            assertEquals("federated@example.com", attributeValue(attributes, "email"));
            assertEquals("true", attributeValue(attributes, "email_verified"));
            assertEquals("Federated User", attributeValue(attributes, "name"));
            assertEquals("Federated", attributeValue(attributes, "given_name"));
            assertEquals("User", attributeValue(attributes, "family_name"));
            assertEquals("https://picture.example/user", attributeValue(attributes, "picture"));
            JsonNode identity = OBJECT_MAPPER.readTree(attributeValue(attributes, "identities")).get(0);
            assertEquals("Google", identity.path("providerName").asText());
            assertEquals("Google", identity.path("providerType").asText());
            assertEquals("provider-subject", identity.path("userId").asText());
        }
        assertEquals("google-secret", formValue(googleProvider.tokenRequestBody(), "client_secret"));
        assertEquals("Bearer provider-access-token", googleProvider.userInfoAuthorization());
        JsonNode details = cognitoJson("DescribeIdentityProvider", """
                {"UserPoolId":"%s","ProviderName":"Google"}
                """.formatted(poolId)).path("IdentityProvider").path("ProviderDetails");
        assertEquals(3, details.size());
        assertFalse(details.has("authorize_url"));
        assertFalse(details.has("oidc_issuer"));
    }

    @Test
    void oidcProviderReceivesAuthorizationRequestAndDiscoveryAdvertisesCodeFlow() throws Exception {
        try (MockOidcProvider provider = new MockOidcProvider()) {
            String poolId = cognitoJson("CreateUserPool", "{\"PoolName\":\"FederatedPool\"}")
                    .path("UserPool").path("Id").asText();
            JsonNode client = cognitoJson("CreateUserPoolClient", """
                    {"UserPoolId":"%s","ClientName":"federated-client","AllowedOAuthFlowsUserPoolClient":true,
                     "AllowedOAuthFlows":["code"],"AllowedOAuthScopes":["openid"],"CallbackURLs":["https://client.example/callback"]}
                    """.formatted(poolId)).path("UserPoolClient");
            String clientId = client.path("ClientId").asText();
            cognitoJson("CreateIdentityProvider", """
                    {"UserPoolId":"%s","ProviderName":"LocalOidc","ProviderType":"OIDC",
                     "ProviderDetails":{"authorize_url":"%s/authorize","token_url":"%s/token","attributes_url":"%s/userinfo","client_id":"provider-client","client_secret":"provider-secret"},
                     "AttributeMapping":{"email":"email","username":"sub"}}
                    """.formatted(poolId, provider.baseUrl(), provider.baseUrl(), provider.baseUrl()));
            cognitoJson("CreateUserPoolDomain", customDomain(DOMAIN, poolId, requestCertificate(DOMAIN)));

            Response authorizeResponse = given()
                    .redirects().follow(false)
                    .header("Host", DOMAIN)
                    .queryParam("client_id", clientId)
                    .queryParam("redirect_uri", "https://client.example/callback")
                    .queryParam("response_type", "code")
                    .queryParam("scope", "openid")
                    .queryParam("identity_provider", "LocalOidc")
                    .queryParam("state", "client-state")
                    .when()
                    .get("/oauth2/authorize")
                    .then().statusCode(302).extract().response();
            String location = authorizeResponse.getHeader("Location");
            assertNotNull(location, authorizeResponse.getHeaders().toString());

            assertEquals(provider.baseUrl() + "/authorize", URI.create(location).resolve("/authorize").toString());
            String providerState = queryParameter(location, "state");
            assertNotNull(providerState);
            assertFalse(providerState.equals("client-state"));
            assertEquals("code", queryParameter(location, "response_type"));

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpResponse<Void> providerAuthorizeResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create(location)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(302, providerAuthorizeResponse.statusCode());
            Map<String, String> providerParameters = provider.authorizationParameters();
            assertEquals("provider-client", providerParameters.get("client_id"));
            assertEquals("code", providerParameters.get("response_type"));
            assertEquals("https://" + DOMAIN + "/oauth2/idpresponse", providerParameters.get("redirect_uri"));
            assertEquals("openid", providerParameters.get("scope"));

            Response callbackResponse = given()
                    .redirects().follow(false)
                    .header("Host", DOMAIN)
                    .queryParam("state", providerState)
                    .queryParam("code", "provider-code")
                    .when()
                    .get("/oauth2/idpresponse")
                    .then().statusCode(302).extract().response();
            String callbackLocation = callbackResponse.getHeader("Location");
            assertEquals("client-state", queryParameter(callbackLocation, "state"));
            String authorizationCode = queryParameter(callbackLocation, "code");

            Response tokenResponse = given()
                    .header("Host", DOMAIN)
                    .formParam("grant_type", "authorization_code")
                    .formParam("client_id", clientId)
                    .formParam("code", authorizationCode)
                    .formParam("redirect_uri", "https://client.example/callback")
                    .when()
                    .post("/oauth2/token")
                    .then().statusCode(200)
                    .body("token_type", equalTo("Bearer"))
                    .extract().response();
            assertEquals("authorization_code", formValue(provider.tokenRequestBody(), "grant_type"));
            assertEquals("Bearer provider-access-token", provider.userInfoAuthorization());

            JsonNode users = cognitoJson("ListUsers", "{\"UserPoolId\":\"%s\",\"Filter\":\"email = \\\"federated@example.com\\\"\"}".formatted(poolId));
            JsonNode attributes = users.path("Users").get(0).path("Attributes");
            assertEquals("federated@example.com", attributeValue(attributes, "email"));
            JsonNode identities = OBJECT_MAPPER.readTree(attributeValue(attributes, "identities")).get(0);
            assertEquals("LocalOidc", identities.path("providerName").asText());
            assertEquals("provider-subject", identities.path("userId").asText());
            assertFalse(identities.path("primary").asBoolean());
            String accessToken = tokenResponse.path("access_token").toString();
            assertFalse(accessToken.isBlank());

            given()
                    .get("/" + poolId + "/.well-known/openid-configuration")
                    .then()
                    .statusCode(200)
                    .body("response_types_supported[0]", equalTo("code"))
                    .body("authorization_endpoint", equalTo("https://" + DOMAIN + "/oauth2/authorize"))
                    .body("grant_types_supported[1]", equalTo("authorization_code"));
        }
    }

    private String queryParameter(String location, String name) {
        for (String parameter : URI.create(location).getRawQuery().split("&")) {
            String[] pair = parameter.split("=", 2);
            if (pair[0].equals(name)) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String formValue(String form, String name) {
        return queryParameter("http://localhost/?" + form, name);
    }

    private String attributeValue(JsonNode attributes, String name) {
        for (JsonNode attribute : attributes) {
            if (name.equals(attribute.path("Name").asText())) {
                return attribute.path("Value").asText();
            }
        }
        return null;
    }
}
