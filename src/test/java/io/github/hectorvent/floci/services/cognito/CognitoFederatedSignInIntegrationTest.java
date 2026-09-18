package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
class CognitoFederatedSignInIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DOMAIN = "federated-" + System.nanoTime() + ".teos.localhost.floci.io";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
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
