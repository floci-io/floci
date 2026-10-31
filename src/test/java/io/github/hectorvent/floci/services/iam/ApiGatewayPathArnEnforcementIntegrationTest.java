package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * API Gateway authorizes a management call as its HTTP method on the path it names,
 * {@code arn:<partition>:apigateway:<region>::<path>}, with no account and without the {@code /v2} of an HTTP
 * API path, so a grant or a deny of {@code apigateway:GET} on a path covers that path's reads and no others.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ApiGatewayPathArnEnforcementIntegrationTest {

    private static final String ADMIN = "test";

    @TestHTTPResource("/")
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    /** Each user's access key ID, by user name. */
    private final Map<String, String> keys = new HashMap<>();

    @Test
    void getRestApisIsCheckedOnTheRestApisPath() {
        String user = user("apigw-restapis");

        policy(user, allow("apigateway:GET", "arn:aws:apigateway:*::/restapis"));
        get(user, "/restapis").statusCode(200);

        policy(user, allow("apigateway:GET", "arn:aws:apigateway:*::/restapis/*/stages"));
        refused(get(user, "/restapis"));

        policy(user, allow("apigateway:GET", "arn:aws:apigateway:eu-west-1::/restapis"));
        refused(get(user, "/restapis"));

        policy(user, allow("apigateway:*", "*") + ","
                + deny("apigateway:GET", "arn:aws:apigateway:us-east-1::/restapis"));
        refused(get(user, "/restapis"));
    }

    @Test
    void getStagesIsCheckedOnTheStagesPathOfItsApi() throws Exception {
        String apiId = createRestApi();
        String user = user("apigw-stages");

        policy(user, allow("apigateway:GET", "arn:aws:apigateway:*::/restapis/*/stages"));
        get(user, "/restapis/" + apiId + "/stages").statusCode(200);
        get(user, "/restapis/" + encodedFirstCharacter(apiId) + "/stages").statusCode(200);
        get(user, "/restapis/x/%2E%2E/" + apiId + "/stages").statusCode(200);
        assertReached(getAsWritten(user, "/restapis//" + apiId + "/stages"));
        assertReached(getAsWritten(user, "/restapis/x/%2E%2E//" + apiId + "/stages"));

        policy(user, allow("apigateway:GET", "arn:aws:apigateway:*::/restapis"));
        refused(get(user, "/restapis/" + apiId + "/stages"));

        policy(user, allow("apigateway:*", "*") + ","
                + deny("apigateway:GET", "arn:aws:apigateway:*::/restapis/" + apiId + "/stages"));
        refused(get(user, "/restapis/" + apiId + "/stages"));
        refused(get(user, "/restapis/" + encodedFirstCharacter(apiId) + "/stages"));
        refused(get(user, "/restapis/x/%2E%2E/" + apiId + "/stages"));
        assertRefused(getAsWritten(user, "/restapis//" + apiId + "/stages"));
        assertRefused(getAsWritten(user, "/restapis/x/%2E%2E//" + apiId + "/stages"));
        get(user, "/restapis").statusCode(200);
    }

    @Test
    void getApisIsCheckedOnTheApisPathWithoutItsV2Prefix() {
        String user = user("apigw-apis");

        policy(user, allow("apigateway:GET", "arn:aws:apigateway:*::/apis"));
        get(user, "/v2/apis").statusCode(200);

        policy(user, allow("apigateway:GET", "arn:aws:apigateway:*::/v2/apis"));
        refused(get(user, "/v2/apis"));

        policy(user, allow("apigateway:*", "*") + ","
                + deny("apigateway:GET", "arn:aws:apigateway:*::/apis"));
        refused(get(user, "/v2/apis"));
    }

    @Test
    void getStageIsCheckedOnTheStageNameAsWritten() {
        String apiId = createHttpApiWithDefaultStage();
        String user = user("apigw-default-stage");

        policy(user, allow("apigateway:GET", "arn:aws:apigateway:*::/apis/" + apiId + "/stages/$default"));
        get(user, "/v2/apis/" + apiId + "/stages/$default").statusCode(200);
        get(user, "/v2/apis/" + apiId + "/stages/%24default").statusCode(200);

        policy(user, allow("apigateway:*", "*") + ","
                + deny("apigateway:GET", "arn:aws:apigateway:*::/apis/" + apiId + "/stages/$default"));
        refused(get(user, "/v2/apis/" + apiId + "/stages/%24default"));
    }

    @Test
    void getTagsIsCheckedOnTheEncodedArnItTagsHoweverItIsSent() {
        String apiId = createHttpApiWithDefaultStage();
        String apiArn = "arn:aws:apigateway:us-east-1::/apis/" + apiId;
        String encoded = "arn%3Aaws%3Aapigateway%3Aus-east-1%3A%3A%2Fapis%2F" + apiId;
        String user = user("apigw-tags");

        policy(user, allow("apigateway:GET", "arn:aws:apigateway:us-east-1::/tags/" + encoded));
        get(user, "/v2/tags/" + apiArn).statusCode(200);
        get(user, "/v2/tags/" + encoded).statusCode(200);

        policy(user, allow("apigateway:*", "*") + ","
                + deny("apigateway:GET", "arn:aws:apigateway:us-east-1::/tags/" + encoded));
        refused(get(user, "/v2/tags/" + apiArn));
        refused(get(user, "/v2/tags/" + encoded));
    }

    private String createHttpApiWithDefaultStage() {
        String apiId = given()
                .header("Authorization", auth(ADMIN, "apigateway"))
                .contentType("application/json")
                .body("{\"name\":\"apigw-path-" + suffix() + "\",\"protocolType\":\"HTTP\"}")
        .when().post("/v2/apis")
        .then().statusCode(201)
                .extract().path("apiId");
        cleanup.register(() -> given().header("Authorization", auth(ADMIN, "apigateway"))
                .delete("/v2/apis/" + apiId));
        given()
                .header("Authorization", auth(ADMIN, "apigateway"))
                .contentType("application/json")
                .body("{\"stageName\":\"$default\"}")
        .when().post("/v2/apis/" + apiId + "/stages")
        .then().statusCode(201);
        return apiId;
    }

    private String createRestApi() {
        String apiId = given()
                .header("Authorization", auth(ADMIN, "apigateway"))
                .contentType("application/json")
                .body("{\"name\":\"apigw-path-" + suffix() + "\"}")
        .when().post("/restapis")
        .then().statusCode(201)
                .extract().path("id");
        cleanup.register(() -> given().header("Authorization", auth(ADMIN, "apigateway"))
                .delete("/restapis/" + apiId));
        return apiId;
    }

    private ValidatableResponse get(String user, String path) {
        return given().urlEncodingEnabled(false)
                .header("Authorization", auth(keys.get(user), "apigateway")).get(path).then();
    }

    /** Sends {@code path} with its empty segments, which RestAssured collapses before sending. */
    private HttpResponse<String> getAsWritten(String user, String path) throws Exception {
        URI uri = URI.create(baseUri.getScheme() + "://" + baseUri.getRawAuthority() + path);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(uri)
                            .header("Authorization", auth(keys.get(user), "apigateway")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void assertReached(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        assertThat(response.body(), containsString("\"item\""));
    }

    private static void assertRefused(HttpResponse<String> response) {
        assertEquals(403, response.statusCode(), response.body());
        assertThat(response.body(), containsString("AccessDeniedException"));
        assertThat(response.body(), containsString("apigateway:GET"));
    }

    /** {@code id} with its first character percent-encoded, a path that routes to the same API. */
    private static String encodedFirstCharacter(String id) {
        return "%%%02X".formatted((int) id.charAt(0)) + id.substring(1);
    }

    private static void refused(ValidatableResponse response) {
        response.statusCode(403)
                .body(containsString("AccessDeniedException"))
                .body(containsString("apigateway:GET"));
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String deny(String action, String resource) {
        return "{\"Effect\":\"Deny\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    /** Replaces the user's one inline policy. */
    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), user, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private String user(String name) {
        String userName = name + "-" + suffix();
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"), userName)
                .accessKeyId();
        keys.put(userName, accessKeyId);
        return userName;
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
