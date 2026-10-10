package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Shared setup for the custom-domain integration tests: pools, clients, certificates and token calls,
 * and AWS's answers of the revocation endpoint.
 */
final class CognitoCustomDomainFixtures {

    static final String FLOCI_URL = "http://localhost:4566";
    static final String COGNITO = "AWSCognitoIdentityProviderService";
    static final String PASSWORD = "Perm1234!";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CognitoCustomDomainFixtures() {
    }

    static String createPool(String name) throws Exception {
        return cognitoJson("CreateUserPool", "{\"PoolName\": \"" + name + "\"}").path("UserPool").path("Id").asText();
    }

    static String confidentialClient(String poolId) {
        return """
                {
                  "UserPoolId": "%s",
                  "ClientName": "routing-client",
                  "GenerateSecret": true,
                  "AllowedOAuthFlowsUserPoolClient": true,
                  "AllowedOAuthFlows": ["client_credentials"],
                  "AllowedOAuthScopes": ["aws.cognito.signin.user.admin"]
                }
                """.formatted(poolId);
    }

    static String customDomain(String domain, String poolId, String certificateArn) {
        return """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s",
                  "CustomDomainConfig": {"CertificateArn": "%s"}
                }
                """.formatted(domain, poolId, certificateArn);
    }

    static String requestCertificate(String domainName) throws Exception {
        return RestAssuredJsonUtils.awsActionJson("CertificateManager", "RequestCertificate",
                certificateRequest(domainName)).path("CertificateArn").asText();
    }

    static String requestCertificateAs(String accountId, String domainName) throws Exception {
        return awsJsonAs(accountId, "CertificateManager", "RequestCertificate", certificateRequest(domainName))
                .path("CertificateArn").asText();
    }

    private static String certificateRequest(String domainName) {
        return "{\"DomainName\": \"" + domainName + "\", \"ValidationMethod\": \"DNS\"}";
    }

    static JsonNode awsJsonAs(String accountId, String target, String action, String body) throws Exception {
        String response = awsActionAs(accountId, target, action, body).then().statusCode(200).extract().asString();
        return OBJECT_MAPPER.readTree(response);
    }

    /** A management call signed for {@code accountId}: the account comes from the credential scope. */
    static Response awsActionAs(String accountId, String target, String action, String body) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accountId
                        + "/20260905/us-east-1/cognito-idp/aws4_request")
                .header("X-Amz-Target", target + "." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
        .when()
                .post("/");
    }

    /** A public client, a confirmed user and the access token USER_PASSWORD_AUTH issues for it. */
    static String signInNewUser(String poolId) throws Exception {
        return signIn(poolId).accessToken();
    }

    /** A confirmed user signed in through a new public client with USER_PASSWORD_AUTH. */
    static SignedInUser signIn(String poolId) throws Exception {
        String publicClient = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "routing-user-client",
                  "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"]
                }
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        String username = "user-" + System.nanoTime() + "@example.com";
        cognitoAction("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "UserAttributes": [
                    {"Name": "email", "Value": "%s"},
                    {"Name": "email_verified", "Value": "true"}
                  ]
                }
                """.formatted(poolId, username, username)).then().statusCode(200);
        cognitoAction("AdminSetUserPassword", """
                {"UserPoolId": "%s", "Username": "%s", "Password": "%s", "Permanent": true}
                """.formatted(poolId, username, PASSWORD)).then().statusCode(200);
        JsonNode tokens = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {"USERNAME": "%s", "PASSWORD": "%s"}
                }
                """.formatted(publicClient, username, PASSWORD)).path("AuthenticationResult");
        return new SignedInUser(publicClient, tokens.path("AccessToken").asText(),
                tokens.path("RefreshToken").asText());
    }

    /** The REFRESH_TOKEN_AUTH call that renews a signed-in user's tokens. */
    static Response refresh(SignedInUser user) {
        return cognitoAction("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "REFRESH_TOKEN_AUTH",
                  "AuthParameters": {"REFRESH_TOKEN": "%s"}
                }
                """.formatted(user.clientId(), user.refreshToken()));
    }

    /** A public client's refresh_token grant at the token endpoint, on {@code host} when one is given. */
    static Response refreshGrant(String host, String clientId, String refreshToken) {
        RequestSpecification request = given()
                .formParam("grant_type", "refresh_token")
                .formParam("client_id", clientId)
                .formParam("refresh_token", refreshToken);
        if (host != null) {
            request.header("Host", host);
        }
        return request.when().post(host == null ? "/cognito-idp/oauth2/token" : "/oauth2/token");
    }

    /** A client-credentials request with Basic authentication, on {@code host} when one is given. */
    static Response tokenRequest(String host, String clientId, String secret, String path) {
        RequestSpecification request = given()
                .header("Authorization", basic(clientId, secret))
                .formParam("grant_type", "client_credentials");
        if (host != null) {
            request.header("Host", host);
        }
        return request.when().post(path);
    }

    /** Runs one token request and returns null on the expected status, otherwise a description. */
    static String expect(int status, String host, String clientId, String secret, String path) {
        Response response = tokenRequest(host, clientId, secret, path);
        if (response.statusCode() != status) {
            return "expected " + status + " for " + clientId + " on " + host + path
                    + " but got " + response.statusCode() + ": " + response.asString();
        }
        return null;
    }

    /** AWS's answer to a revocation it accepts: 200, with no body and no Content-Type. */
    static void assertRevoked(Response response) {
        response.then()
                .statusCode(200)
                .header("Content-Length", "0")
                .header("Content-Type", nullValue())
                .body(emptyString());
    }

    /** AWS's answer to every client the revocation endpoint cannot authenticate. */
    static void assertInvalidClient(Response response) {
        response.then()
                .statusCode(401)
                .contentType(containsString("application/json"))
                .header("WWW-Authenticate", "Basic")
                .body("error", equalTo("invalid_client"))
                .body("error_description", equalTo("Invalid client credentials in request"));
    }

    static String basic(String clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    static JsonNode jwtPayload(String token) throws Exception {
        return OBJECT_MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    /** A user signed in through the public client {@code clientId}, with the tokens it was issued. */
    record SignedInUser(String clientId, String accessToken, String refreshToken) {
    }
}
