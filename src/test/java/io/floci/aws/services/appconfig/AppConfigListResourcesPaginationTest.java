package io.floci.aws.services.appconfig;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppConfigListResourcesPaginationTest {
    private static final String SCOPE =
            "AWS4-HMAC-SHA256 Credential=123456789012/20261109/us-east-1/appconfig/aws4_request";
    private static final String OTHER_ACCOUNT =
            "AWS4-HMAC-SHA256 Credential=210987654321/20261109/us-east-1/appconfig/aws4_request";
    private static final String OTHER_REGION =
            "AWS4-HMAC-SHA256 Credential=123456789012/20261109/us-west-2/appconfig/aws4_request";

    @Test
    void listEnvironmentsPaginatesAndScopesTokensToTheApplication() {
        String app = createApplication();
        String otherApp = createApplication();
        List<String> environments = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                environments.add(given().header("Authorization", SCOPE)
                        .contentType(ContentType.JSON)
                        .body("{\"Name\":\"environment-" + i + "\"}")
                        .when().post("/applications/" + app + "/environments")
                        .then().statusCode(201).extract().path("Id"));
            }
            String path = "/applications/" + app + "/environments";
            String token = assertTwoPages(path, "Id", environments);
            assertInvalidInputs(path);
            assertInvalidToken("/applications/" + otherApp + "/environments", SCOPE, token);
        } finally {
            for (String environment : environments) {
                given().header("Authorization", SCOPE)
                        .when().delete("/applications/" + app + "/environments/" + environment)
                        .then().statusCode(204);
            }
            deleteApplication(otherApp);
            deleteApplication(app);
        }
    }

    @Test
    void listConfigurationProfilesPaginatesAndScopesTokensToTheApplicationAndOperation() {
        String app = createApplication();
        String otherApp = createApplication();
        List<String> profiles = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                profiles.add(createProfile(app, "profile-" + i));
            }
            String path = "/applications/" + app + "/configurationprofiles";
            String token = assertTwoPages(path, "Id", profiles);
            assertInvalidInputs(path);
            assertInvalidToken("/applications/" + otherApp + "/configurationprofiles", SCOPE, token);
            assertInvalidToken("/applications/" + app + "/environments", SCOPE, token);
        } finally {
            for (String profile : profiles) {
                deleteProfile(app, profile);
            }
            deleteApplication(otherApp);
            deleteApplication(app);
        }
    }

    @Test
    void listHostedVersionsPaginatesInNumericOrderAndScopesTokensToTheProfile() {
        String app = createApplication();
        String profile = createProfile(app, "hosted");
        String otherProfile = createProfile(app, "other");
        try {
            String path = "/applications/" + app + "/configurationprofiles/" + profile
                    + "/hostedconfigurationversions";
            for (int version = 1; version <= 10; version++) {
                given().header("Authorization", SCOPE)
                        .contentType("application/json")
                        .body("{}")
                        .when().post(path)
                        .then().statusCode(201);
            }
            List<Integer> versions = new ArrayList<>();
            String token = null;
            String firstToken = null;
            do {
                RequestSpecification request = given().header("Authorization", SCOPE)
                        .queryParam("max_results", 2);
                if (token != null) {
                    request.queryParam("next_token", token);
                }
                Response page = request
                        .when().get(path)
                        .then().statusCode(200).extract().response();
                List<Integer> items = page.jsonPath().getList("Items.VersionNumber", Integer.class);
                assertEquals(2, items.size());
                versions.addAll(items);
                token = page.jsonPath().getString("NextToken");
                if (firstToken == null) {
                    firstToken = token;
                    assertNotNull(firstToken);
                }
            } while (token != null && versions.size() < 10);
            assertEquals(IntStream.rangeClosed(1, 10).boxed().toList(), versions);
            assertNull(token);
            assertInvalidInputs(path);
            assertInvalidToken("/applications/" + app + "/configurationprofiles/" + otherProfile
                    + "/hostedconfigurationversions", SCOPE, firstToken);

            for (int version = 11; version <= 51; version++) {
                given().header("Authorization", SCOPE)
                        .contentType("application/json")
                        .body("{}")
                        .when().post(path)
                        .then().statusCode(201);
            }
            Response defaultPage = given().header("Authorization", SCOPE)
                    .when().get(path)
                    .then().statusCode(200).extract().response();
            assertEquals(IntStream.rangeClosed(1, 50).boxed().toList(),
                    defaultPage.jsonPath().getList("Items.VersionNumber", Integer.class));
            String defaultToken = defaultPage.jsonPath().getString("NextToken");
            assertNotNull(defaultToken);
            Response lastPage = given().header("Authorization", SCOPE)
                    .queryParam("next_token", defaultToken)
                    .when().get(path)
                    .then().statusCode(200).extract().response();
            assertEquals(List.of(51), lastPage.jsonPath().getList("Items.VersionNumber", Integer.class));
            assertNull(lastPage.jsonPath().getString("NextToken"));
        } finally {
            deleteProfile(app, otherProfile);
            deleteProfile(app, profile);
            deleteApplication(app);
        }
    }

    @Test
    void listDeploymentStrategiesPaginatesAndScopesTokensToAccountAndRegion() {
        String path = "/deploymentstrategies";
        List<String> ids = new ArrayList<>();
        String token = null;
        String firstToken = null;
        do {
            RequestSpecification request = given().header("Authorization", SCOPE)
                    .queryParam("max_results", 2);
            if (token != null) {
                request.queryParam("next_token", token);
            }
            Response page = request
                    .when().get(path)
                    .then().statusCode(200).extract().response();
            List<String> items = page.jsonPath().getList("Items.Id", String.class);
            assertTrue(items.size() <= 2);
            ids.addAll(items);
            token = page.jsonPath().getString("NextToken");
            if (firstToken == null) {
                firstToken = token;
                assertNotNull(firstToken);
            }
        } while (token != null && ids.size() < 100);
        assertNull(token);
        assertTrue(ids.containsAll(List.of("AppConfig.AllAtOnce", "AppConfig.Linear50PercentEvery30Seconds",
                "AppConfig.Canary10Percent20Minutes")));
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertInvalidInputs(path);
        assertInvalidToken(path, OTHER_ACCOUNT, firstToken);
        assertInvalidToken(path, OTHER_REGION, firstToken);
    }

    private static String assertTwoPages(String path, String idField, List<String> expected) {
        Response unpaged = given().header("Authorization", SCOPE)
                .when().get(path)
                .then().statusCode(200).extract().response();
        assertEquals(expected.size(), unpaged.jsonPath().getList("Items").size());
        assertNull(unpaged.jsonPath().getString("NextToken"));

        Response first = given().header("Authorization", SCOPE)
                .queryParam("max_results", 1)
                .when().get(path)
                .then().statusCode(200).extract().response();
        String token = first.jsonPath().getString("NextToken");
        assertEquals(1, first.jsonPath().getList("Items").size());
        assertNotNull(token);

        Response second = given().header("Authorization", SCOPE)
                .queryParam("max_results", 1)
                .queryParam("next_token", token)
                .when().get(path)
                .then().statusCode(200).extract().response();
        assertEquals(1, second.jsonPath().getList("Items").size());
        assertNull(second.jsonPath().getString("NextToken"));
        String firstId = first.jsonPath().getString("Items[0]." + idField);
        String secondId = second.jsonPath().getString("Items[0]." + idField);
        assertNotEquals(firstId, secondId);
        assertEquals(Set.copyOf(expected), Set.of(firstId, secondId));
        return token;
    }

    private static void assertInvalidInputs(String path) {
        given().header("Authorization", SCOPE)
                .when().get(path + "?max_results=")
                .then().statusCode(400);
        given().header("Authorization", SCOPE)
                .when().get(path + "?max_results=%20")
                .then().statusCode(400);
        for (int maxResults : List.of(0, 51)) {
            given().header("Authorization", SCOPE)
                    .queryParam("max_results", maxResults)
                    .when().get(path)
                    .then().statusCode(400);
        }
        for (String token : List.of("not-a-token", "x".repeat(2049))) {
            assertInvalidToken(path, SCOPE, token);
        }
        given().header("Authorization", SCOPE)
                .when().get(path + "?next_token=")
                .then().statusCode(400);
    }

    private static void assertInvalidToken(String path, String scope, String token) {
        given().header("Authorization", scope)
                .queryParam("next_token", token)
                .when().get(path)
                .then().statusCode(400);
    }

    private static String createApplication() {
        return given().header("Authorization", SCOPE)
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"pagination-" + UUID.randomUUID() + "\"}")
                .when().post("/applications")
                .then().statusCode(201).extract().path("Id");
    }

    private static String createProfile(String app, String name) {
        return given().header("Authorization", SCOPE)
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"" + name + "\",\"LocationUri\":\"hosted\"}")
                .when().post("/applications/" + app + "/configurationprofiles")
                .then().statusCode(201).extract().path("Id");
    }

    private static void deleteProfile(String app, String profile) {
        given().header("Authorization", SCOPE)
                .when().delete("/applications/" + app + "/configurationprofiles/" + profile)
                .then().statusCode(204);
    }

    private static void deleteApplication(String app) {
        given().header("Authorization", SCOPE)
                .when().delete("/applications/" + app)
                .then().statusCode(204);
    }
}
