package io.github.hectorvent.floci.services.signin;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SigninIntegrationTest {

    private static final String CLIENT_ID = "arn:aws:signin:::devtools/same-device";
    private static final String REDIRECT_URI = "http://127.0.0.1:4567/oauth/callback";

    @Test
    void authorizeRedirectsWithStateAndPkceCode() throws Exception {
        String verifier = verifier();
        String state = UUID.randomUUID().toString();

        String location = request()
                .redirects().follow(false)
                .queryParam("client_id", CLIENT_ID)
                .queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "SHA-256")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid")
                .queryParam("state", state)
                .when()
                .get("/v1/authorize")
                .then()
                .statusCode(302)
                .extract()
                .header("Location");

        URI redirect = URI.create(location);
        assertEquals(REDIRECT_URI, redirect.getScheme() + "://" + redirect.getAuthority() + redirect.getPath());
        Map<String, String> params = queryParams(redirect.getRawQuery());
        assertTrue(params.get("code").length() >= 1);
        assertEquals(state, params.get("state"));
    }

    @Test
    void authorizeRejectsOauthS256Alias() throws Exception {
        request()
                .queryParam("client_id", CLIENT_ID)
                .queryParam("code_challenge", challenge(verifier()))
                .queryParam("code_challenge_method", "S256")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid")
                .queryParam("state", UUID.randomUUID().toString())
                .when()
                .get("/v1/authorize")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"))
                .body("message", equalTo("code_challenge_method must be SHA-256"))
                .body("$", not(hasKey("__type")));
    }

    @Test
    void malformedTokenJsonUsesSigninErrorEnvelope() {
        request()
                .contentType("application/json")
                .body("{")
                .when()
                .post("/v1/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"))
                .body("message", equalTo("Request body must be valid JSON"))
                .body("$", not(hasKey("__type")));
    }

    @Test
    void authorizationCodeExchangeReturnsTemporaryAwsCredentials() throws Exception {
        String verifier = verifier();
        String state = UUID.randomUUID().toString();
        String location = authorize(verifier, state);
        String code = queryParams(URI.create(location).getRawQuery()).get("code");

        var response = request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "authorization_code",
                        "code", code,
                        "redirectUri", REDIRECT_URI,
                        "codeVerifier", verifier))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(200)
                .body("accessToken.accessKeyId", notNullValue())
                .body("accessToken.secretAccessKey", notNullValue())
                .body("accessToken.sessionToken", notNullValue())
                .body("tokenType", equalTo("aws_sigv4"))
                .body("expiresIn", equalTo(900))
                .body("refreshToken", notNullValue())
                .body("idToken", notNullValue())
                .extract()
                .response();

        String accessKey = response.path("accessToken.accessKeyId");
        assertTrue(accessKey.startsWith("ASIA"));

        request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "authorization_code",
                        "code", code,
                        "redirectUri", REDIRECT_URI,
                        "codeVerifier", verifier))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));
    }

    @Test
    void refreshTokenReturnsStableCredentialsWithinAccessTokenLifetime() throws Exception {
        String verifier = verifier();
        String code = queryParams(URI.create(authorize(verifier, UUID.randomUUID().toString())).getRawQuery())
                .get("code");
        String refreshToken = request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "authorization_code",
                        "code", code,
                        "redirectUri", REDIRECT_URI,
                        "codeVerifier", verifier))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(200)
                .extract()
                .path("refreshToken");

        var firstRefresh = request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "refresh_token",
                        "refreshToken", refreshToken))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(200)
                .body("accessToken.accessKeyId", notNullValue())
                .body("tokenType", equalTo("aws_sigv4"))
                .body("expiresIn", equalTo(900))
                .body("refreshToken", not(equalTo(refreshToken)))
                .body("idToken", equalTo(null))
                .extract()
                .response();

        String rotatedRefreshToken = firstRefresh.path("refreshToken");

        request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "refresh_token",
                        "refreshToken", refreshToken))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(200)
                .body("accessToken.accessKeyId", equalTo(firstRefresh.path("accessToken.accessKeyId")))
                .body("accessToken.secretAccessKey", equalTo(firstRefresh.path("accessToken.secretAccessKey")))
                .body("accessToken.sessionToken", equalTo(firstRefresh.path("accessToken.sessionToken")))
                .body("refreshToken", equalTo(rotatedRefreshToken))
                .body("idToken", equalTo(null));
    }

    @Test
    void rejectsInvalidPkceProof() throws Exception {
        String verifier = verifier();
        String code = queryParams(URI.create(authorize(verifier, UUID.randomUUID().toString())).getRawQuery())
                .get("code");

        request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "authorization_code",
                        "code", code,
                        "redirectUri", REDIRECT_URI,
                        "codeVerifier", verifier + "wrong"))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));
    }

    private String authorize(String verifier, String state) throws Exception {
        return request()
                .redirects().follow(false)
                .queryParam("client_id", CLIENT_ID)
                .queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "SHA-256")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid")
                .queryParam("state", state)
                .when()
                .get("/v1/authorize")
                .then()
                .statusCode(302)
                .extract()
                .header("Location");
    }

    private RequestSpecification request() {
        return given();
    }

    private static String verifier() {
        return "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static Map<String, String> queryParams(String query) {
        return java.util.Arrays.stream(query.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
    }
}
