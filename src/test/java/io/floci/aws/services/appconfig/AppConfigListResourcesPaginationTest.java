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
    private static final String DEFAULT_PAGE_ACCOUNT =
            "AWS4-HMAC-SHA256 Credential=321098765432/20261109/us-east-1/appconfig/aws4_request";

    @Test
    void listEnvironmentsPaginatesAndScopesTokensToTheApplication() {
        String app = createApplication();
        String otherApp = createApplication();
        List<String> environments = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                environments.add(createEnvironment(app, "environment-" + i));
            }
            String path = "/applications/" + app + "/environments";
            String token = assertTwoPages(path, "Id", environments);
            assertInvalidInputs(path);
            assertInvalidToken("/applications/" + otherApp + "/environments", SCOPE, token);
        } finally {
            for (String environment : environments) {
                deleteEnvironment(app, environment);
            }
            deleteApplication(otherApp);
            deleteApplication(app);
        }
    }

    @Test
    void listEnvironmentsDefaultsToFiftyItems() {
        String app = createApplication();
        List<String> environments = new ArrayList<>();
        try {
            for (int i = 0; i < 51; i++) {
                environments.add(createEnvironment(app, "default-environment-" + i));
            }
            assertDefaultPageOfFifty("/applications/" + app + "/environments", SCOPE,
                    Set.copyOf(environments));
        } finally {
            for (String environment : environments) {
                deleteEnvironment(app, environment);
            }
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
    void listConfigurationProfilesDefaultsToFiftyItems() {
        String app = createApplication();
        List<String> profiles = new ArrayList<>();
        try {
            for (int i = 0; i < 51; i++) {
                profiles.add(createProfile(app, "default-profile-" + i));
            }
            assertDefaultPageOfFifty("/applications/" + app + "/configurationprofiles", SCOPE,
                    Set.copyOf(profiles));
        } finally {
            for (String profile : profiles) {
                deleteProfile(app, profile);
            }
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
            createHostedVersions(path, 10);
            PageWalk<Integer> versions = walkPages(path, "VersionNumber", Integer.class, 2, 10);
            assertEquals(IntStream.rangeClosed(1, 10).boxed().toList(), versions.items());
            assertEquals(5, versions.pageCount());
            assertInvalidInputs(path);
            assertInvalidToken("/applications/" + app + "/configurationprofiles/" + otherProfile
                    + "/hostedconfigurationversions", SCOPE, versions.firstToken());

            createHostedVersions(path, 41);
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
        PageWalk<String> strategies = walkPages(path, "Id", String.class, 2, 100);
        assertTrue(strategies.items().containsAll(List.of("AppConfig.AllAtOnce", "AppConfig.Linear50PercentEvery30Seconds",
                "AppConfig.Canary10Percent20Minutes")));
        assertEquals(strategies.items().size(), new HashSet<>(strategies.items()).size());
        assertInvalidInputs(path);
        assertInvalidToken(path, OTHER_ACCOUNT, strategies.firstToken());
        assertInvalidToken(path, OTHER_REGION, strategies.firstToken());
    }

    @Test
    void listDeploymentStrategiesDefaultsToFiftyItemsIncludingBuiltins() {
        List<String> strategies = new ArrayList<>();
        try {
            for (int i = 0; i < 48; i++) {
                strategies.add(given().header("Authorization", DEFAULT_PAGE_ACCOUNT)
                        .contentType(ContentType.JSON)
                        .body("{\"Name\":\"default-strategy-" + i
                                + "\",\"DeploymentDurationInMinutes\":0,\"GrowthFactor\":100,"
                                + "\"FinalBakeTimeInMinutes\":0}")
                        .when().post("/deploymentstrategies")
                        .then().statusCode(201).extract().path("Id"));
            }
            Set<String> expected = new HashSet<>(strategies);
            expected.addAll(List.of("AppConfig.AllAtOnce", "AppConfig.Linear50PercentEvery30Seconds",
                    "AppConfig.Canary10Percent20Minutes"));
            assertDefaultPageOfFifty("/deploymentstrategies", DEFAULT_PAGE_ACCOUNT, expected);
        } finally {
            for (String strategy : strategies) {
                given().header("Authorization", DEFAULT_PAGE_ACCOUNT)
                        .when().delete("/deployementstrategies/" + strategy)
                        .then().statusCode(204);
            }
        }
    }

    private static void assertDefaultPageOfFifty(String path, String authorization, Set<String> expected) {
        assertEquals(51, expected.size());
        Response first = given().header("Authorization", authorization)
                .when().get(path)
                .then().statusCode(200).extract().response();
        List<String> firstIds = first.jsonPath().getList("Items.Id", String.class);
        assertEquals(50, firstIds.size());
        String nextToken = first.jsonPath().getString("NextToken");
        assertNotNull(nextToken);

        Response second = given().header("Authorization", authorization)
                .queryParam("next_token", nextToken)
                .when().get(path)
                .then().statusCode(200).extract().response();
        List<String> secondIds = second.jsonPath().getList("Items.Id", String.class);
        assertEquals(1, secondIds.size());
        assertNull(second.jsonPath().getString("NextToken"));

        List<String> allIds = new ArrayList<>(firstIds);
        allIds.addAll(secondIds);
        assertEquals(51, new HashSet<>(allIds).size());
        assertEquals(expected, Set.copyOf(allIds));
    }

    private static <T> PageWalk<T> walkPages(String path, String field, Class<T> type,
                                             int pageSize, int maxItems) {
        List<T> items = new ArrayList<>();
        Set<String> seenTokens = new HashSet<>();
        String token = null;
        String firstToken = null;
        int pageCount = 0;
        do {
            RequestSpecification request = given().header("Authorization", SCOPE)
                    .queryParam("max_results", pageSize);
            if (token != null) {
                request.queryParam("next_token", token);
            }
            Response page = request.when().get(path)
                    .then().statusCode(200).extract().response();
            List<T> pageItems = page.jsonPath().getList("Items." + field, type);
            assertTrue(pageItems.size() <= pageSize);
            items.addAll(pageItems);
            token = page.jsonPath().getString("NextToken");
            if (pageCount++ == 0) {
                firstToken = token;
                assertNotNull(firstToken);
            }
            if (token != null) {
                assertTrue(seenTokens.add(token));
            }
        } while (token != null && items.size() < maxItems);
        assertNull(token);
        return new PageWalk<>(items, firstToken, pageCount);
    }

    private record PageWalk<T>(List<T> items, String firstToken, int pageCount) {}

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

    private static String createEnvironment(String app, String name) {
        return given().header("Authorization", SCOPE)
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"" + name + "\"}")
                .when().post("/applications/" + app + "/environments")
                .then().statusCode(201).extract().path("Id");
    }

    private static void deleteEnvironment(String app, String environment) {
        given().header("Authorization", SCOPE)
                .when().delete("/applications/" + app + "/environments/" + environment)
                .then().statusCode(204);
    }

    private static void createHostedVersions(String path, int count) {
        for (int i = 0; i < count; i++) {
            given().header("Authorization", SCOPE)
                    .contentType(ContentType.JSON)
                    .body("{}")
                    .when().post(path)
                    .then().statusCode(201);
        }
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
