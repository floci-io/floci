package io.floci.aws.services.ssooidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.core.common.Digests;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
class SsoOidcIntegrationTest {
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sso-oauth/aws4_request";
    private static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

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
        Response registration = given()
                .contentType("application/json")
                .body("{\"clientName\":\"Device Integration\",\"clientType\":\"public\","
                        + "\"grantTypes\":[\"" + DEVICE_CODE_GRANT + "\"]}")
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
        DeviceAuthorization authorization = startDeviceAuthorization("Principal Guard", DEVICE_CODE_GRANT);

        given()
                .queryParam("user_code", authorization.userCode())
                .queryParam("principal_id", "11111111-2222-3333-4444-555555555555")
            .when().get("/device")
            .then().statusCode(403)
                .body("error", equalTo("access_denied"));
    }

    @Test
    void createTokenCompletesDeviceFlowAndRefreshesToken() {
        DeviceAuthorization authorization =
                startDeviceAuthorization("Token Integration", DEVICE_CODE_GRANT, "refresh_token");
        String clientId = authorization.clientId();
        String clientSecret = authorization.clientSecret();

        given().queryParam("user_code", authorization.userCode())
            .when().get("/device")
            .then().statusCode(200).body("status", equalTo("authorized"));

        Response token = createDeviceCodeToken(authorization)
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
        ObjectNode create = mapper.createObjectNode();
        create.put("InstanceArn", instanceArn);
        create.put("ApplicationProviderArn", "arn:aws:sso::aws:applicationProvider/custom");
        create.put("Name", "IAM OIDC Integration");
        String applicationArn = ssoAdminService.createApplication(create, "000000000000", "us-east-1").applicationArn();

        ObjectNode authentication = mapper.createObjectNode();
        authentication.put("ApplicationArn", applicationArn);
        authentication.put("AuthenticationMethodType", "IAM");
        ObjectNode policy = authentication.putObject("AuthenticationMethod").putObject("Iam").putObject("ActorPolicy");
        policy.put("Version", "2012-10-17");
        ObjectNode statement = policy.putArray("Statement").addObject();
        statement.put("Effect", "Allow");
        statement.put("Principal", "*");
        statement.put("Action", "sso-oauth:CreateTokenWithIAM");
        statement.put("Resource", "*");
        ssoAdminService.putApplicationAuthenticationMethod(authentication);

        String redirectUri = "http://127.0.0.1:8400/iam-callback";
        ObjectNode grant = mapper.createObjectNode();
        grant.put("ApplicationArn", applicationArn);
        grant.put("GrantType", "authorization_code");
        grant.putObject("Grant").putObject("AuthorizationCode").putArray("RedirectUris").add(redirectUri);
        ssoAdminService.putApplicationGrant(grant);

        String verifier = "01234567890123456789012345678901234567890123456789";
        String challenge = Base64Url.encode(Digests.sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        String code = ssoOidcService.createIamAuthorizationCode(applicationArn, redirectUri, challenge,
                List.of(redirectUri)).code();
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
                .body("idToken", Matchers.not(Matchers.blankOrNullString()))
                .body("awsAdditionalDetails.identityContext", Matchers.not(Matchers.blankOrNullString()));
    }

    @Test
    void registerClientReturnsOidcErrorShape() {
        given()
                .contentType("application/json")
                .body("{\"clientName\":\"Bad Client\",\"clientType\":\"confidential\"}")
            .when().post("/client/register")
            .then().statusCode(400)
                .body("error", equalTo("invalid_client_metadata"))
                .header("x-amzn-ErrorType", "InvalidClientMetadataException");
    }

    @Test
    void pendingDeviceAuthorizationNamesTheModeledExceptionForSdkRetries() {
        // AWS SDKs map OIDC errors from x-amzn-ErrorType; `aws sso login` only keeps polling on
        // AuthorizationPendingException.
        DeviceAuthorization authorization = startDeviceAuthorization("Pending Integration", DEVICE_CODE_GRANT);

        createDeviceCodeToken(authorization)
            .then().statusCode(400)
                .header("x-amzn-ErrorType", "AuthorizationPendingException")
                .body("error", equalTo("authorization_pending"));
    }

    private record DeviceAuthorization(String clientId, String clientSecret, String deviceCode, String userCode) {
    }

    /** Sends the device-code CreateToken request a polling client sends for {@code authorization}. */
    private static Response createDeviceCodeToken(DeviceAuthorization authorization) {
        return given()
                .contentType("application/json")
                .body("{\"clientId\":\"" + authorization.clientId() + "\",\"clientSecret\":\""
                        + authorization.clientSecret() + "\",\"grantType\":\"" + DEVICE_CODE_GRANT
                        + "\",\"deviceCode\":\"" + authorization.deviceCode() + "\"}")
            .when().post("/token");
    }

    /** Registers a public client with the given grant types and starts a device authorization for it. */
    private static DeviceAuthorization startDeviceAuthorization(String clientName, String... grantTypes) {
        String grants = String.join("\",\"", grantTypes);
        Response registration = given()
                .contentType("application/json")
                .body("{\"clientName\":\"" + clientName + "\",\"clientType\":\"public\","
                        + "\"grantTypes\":[\"" + grants + "\"]}")
            .when().post("/client/register")
            .then().statusCode(200)
            .extract().response();
        String clientId = registration.path("clientId");
        String clientSecret = registration.path("clientSecret");
        Response authorization = given()
                .contentType("application/json")
                .body("{\"clientId\":\"" + clientId + "\",\"clientSecret\":\"" + clientSecret
                        + "\",\"startUrl\":\"https://example.awsapps.com/start\"}")
            .when().post("/device_authorization")
            .then().statusCode(200)
            .extract().response();
        return new DeviceAuthorization(clientId, clientSecret,
                authorization.path("deviceCode"), authorization.path("userCode"));
    }
}
