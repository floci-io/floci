package io.github.hectorvent.floci.services.ssooidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.ssoadmin.SsoAdminService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
class SsoOidcIntegrationTest {
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sso-oauth/aws4_request";

    @Inject
    SsoAdminService ssoAdminService;

    @Inject
    SsoOidcService ssoOidcService;

    @Inject
    ObjectMapper mapper;

    @Test
    void registerClientReturnsAwsOidcShapeWithoutSigV4() {
        given()
                .contentType("application/json")
                .body("{\"clientName\":\"Floci CLI\",\"clientType\":\"public\","
                        + "\"grantTypes\":[\"authorization_code\",\"refresh_token\"],"
                        + "\"redirectUris\":[\"http://127.0.0.1:8400/callback\"],"
                        + "\"scopes\":[\"sso:account:access\"]}")
            .when().post("/client/register")
            .then().statusCode(200)
                .body("clientId", matchesPattern("[0-9a-f]{32}"))
                .body("clientSecret", matchesPattern("[0-9a-f]{64}"))
                .body("clientIdIssuedAt", greaterThan(0))
                .body("clientSecretExpiresAt", greaterThan(0))
                .body("authorizationEndpoint", equalTo("http://localhost:4566/authorize"))
                .body("tokenEndpoint", equalTo("http://localhost:4566/token"));
    }

    @Test
    void startDeviceAuthorizationUsesRegisteredClient() {
        var registration = given()
                .contentType("application/json")
                .body("{\"clientName\":\"Device Integration\",\"clientType\":\"public\","
                        + "\"grantTypes\":[\"urn:ietf:params:oauth:grant-type:device_code\"]}")
            .when().post("/client/register")
            .then().statusCode(200)
            .extract().response();
        String clientId = registration.path("clientId");
        String clientSecret = registration.path("clientSecret");

        given()
                .contentType("application/json")
                .body("{\"clientId\":\"" + clientId + "\",\"clientSecret\":\"" + clientSecret
                        + "\",\"startUrl\":\"https://example.awsapps.com/start\"}")
            .when().post("/device_authorization")
            .then().statusCode(200)
                .body("deviceCode", matchesPattern("[0-9a-f]{64}"))
                .body("userCode", matchesPattern("[0-9A-F]{4}-[0-9A-F]{4}"))
                .body("verificationUri", equalTo("http://localhost:4566/device"))
                .body("expiresIn", greaterThan(0))
                .body("interval", equalTo(5));

        given()
                .contentType("application/json")
                .body("{\"clientId\":\"" + clientId + "\",\"clientSecret\":\"wrong\","
                        + "\"startUrl\":\"https://example.awsapps.com/start\"}")
            .when().post("/device_authorization")
            .then().statusCode(401)
                .body("error", equalTo("invalid_client"));
    }

    @Test
    void deviceAuthorizationRejectsCallerSelectedPrincipal() {
        var registration = given()
                .contentType("application/json")
                .body("{\"clientName\":\"Principal Guard\",\"clientType\":\"public\","
                        + "\"grantTypes\":[\"urn:ietf:params:oauth:grant-type:device_code\"]}")
            .when().post("/client/register")
            .then().statusCode(200)
            .extract().response();
        var authorization = given()
                .contentType("application/json")
                .body("{\"clientId\":\"" + registration.path("clientId") + "\",\"clientSecret\":\""
                        + registration.path("clientSecret") + "\",\"startUrl\":\"https://example.awsapps.com/start\"}")
            .when().post("/device_authorization")
            .then().statusCode(200)
            .extract().response();

        String userCode = authorization.path("userCode");
        given()
                .queryParam("user_code", userCode)
                .queryParam("principal_id", "11111111-2222-3333-4444-555555555555")
            .when().get("/device")
            .then().statusCode(403)
                .body("error", equalTo("access_denied"));
    }

    @Test
    void createTokenCompletesDeviceFlowAndRefreshesToken() {
        var registration = given()
                .contentType("application/json")
                .body("{\"clientName\":\"Token Integration\",\"clientType\":\"public\","
                        + "\"grantTypes\":[\"urn:ietf:params:oauth:grant-type:device_code\",\"refresh_token\"]}")
            .when().post("/client/register")
            .then().statusCode(200)
            .extract().response();
        String clientId = registration.path("clientId");
        String clientSecret = registration.path("clientSecret");
        var authorization = given()
                .contentType("application/json")
                .body("{\"clientId\":\"" + clientId + "\",\"clientSecret\":\"" + clientSecret
                        + "\",\"startUrl\":\"https://example.awsapps.com/start\"}")
            .when().post("/device_authorization")
            .then().statusCode(200)
            .extract().response();
        String deviceCode = authorization.path("deviceCode");
        String userCode = authorization.path("userCode");

        given().queryParam("user_code", userCode)
            .when().get("/device")
            .then().statusCode(200).body("status", equalTo("authorized"));

        var token = given()
                .contentType("application/json")
                .body("{\"clientId\":\"" + clientId + "\",\"clientSecret\":\"" + clientSecret
                        + "\",\"grantType\":\"urn:ietf:params:oauth:grant-type:device_code\","
                        + "\"deviceCode\":\"" + deviceCode + "\"}")
            .when().post("/token")
            .then().statusCode(200)
                .body("tokenType", equalTo("Bearer"))
                .body("accessToken", matchesPattern("[0-9a-f]{64}"))
            .extract().response();
        String refreshToken = token.path("refreshToken");

        given()
                .contentType("application/json")
                .body("{\"clientId\":\"" + clientId + "\",\"clientSecret\":\"" + clientSecret
                        + "\",\"grantType\":\"refresh_token\",\"refreshToken\":\"" + refreshToken + "\"}")
            .when().post("/token")
            .then().statusCode(200)
                .body("tokenType", equalTo("Bearer"));
    }

    @Test
    void createTokenWithIamRequiresSigV4AndUsesApplicationPolicy() {
        String instanceArn = ssoAdminService.getInstanceArn();
        var create = mapper.createObjectNode();
        create.put("InstanceArn", instanceArn);
        create.put("ApplicationProviderArn", "arn:aws:sso::aws:applicationProvider/custom");
        create.put("Name", "IAM OIDC Integration");
        String applicationArn = ssoAdminService.createApplication(create, "000000000000", "us-east-1").applicationArn();

        var authentication = mapper.createObjectNode();
        authentication.put("ApplicationArn", applicationArn);
        authentication.put("AuthenticationMethodType", "IAM");
        var policy = authentication.putObject("AuthenticationMethod").putObject("Iam").putObject("ActorPolicy");
        policy.put("Version", "2012-10-17");
        var statement = policy.putArray("Statement").addObject();
        statement.put("Effect", "Allow");
        statement.put("Principal", "*");
        statement.put("Action", "sso-oauth:CreateTokenWithIAM");
        statement.put("Resource", "*");
        ssoAdminService.putApplicationAuthenticationMethod(authentication);

        String redirectUri = "http://127.0.0.1:8400/iam-callback";
        var grant = mapper.createObjectNode();
        grant.put("ApplicationArn", applicationArn);
        grant.put("GrantType", "authorization_code");
        grant.putObject("Grant").putObject("AuthorizationCode").putArray("RedirectUris").add(redirectUri);
        ssoAdminService.putApplicationGrant(grant);

        String verifier = "01234567890123456789012345678901234567890123456789";
        String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        String code = ssoOidcService.createIamAuthorizationCode(applicationArn, redirectUri, challenge,
                java.util.List.of(redirectUri)).code();
        String body = "{\"clientId\":\"" + applicationArn + "\",\"grantType\":\"authorization_code\","
                + "\"code\":\"" + code + "\",\"codeVerifier\":\"" + verifier + "\","
                + "\"redirectUri\":\"" + redirectUri + "\",\"scope\":[\"openid\"]}";

        given().contentType("application/json").body(body)
            .when().post("/token?aws_iam=t")
            .then().statusCode(400).body("error", equalTo("access_denied"));

        given().contentType("application/json").header("Authorization", AUTH_HEADER).body(body)
            .when().post("/token?aws_iam=t")
            .then().statusCode(200)
                .body("tokenType", equalTo("Bearer"))
                .body("scope[0]", equalTo("openid"))
                .body("accessToken", matchesPattern("[0-9a-f]{64}"))
                .body("idToken", org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString()))
                .body("awsAdditionalDetails.identityContext", org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString()));
    }

    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void registerClientReturnsOidcErrorShape() {
        given()
                .contentType("application/json")
                .body("{\"clientName\":\"Bad Client\",\"clientType\":\"confidential\"}")
            .when().post("/client/register")
            .then().statusCode(400)
                .body("error", equalTo("invalid_client_metadata"));
    }
}
