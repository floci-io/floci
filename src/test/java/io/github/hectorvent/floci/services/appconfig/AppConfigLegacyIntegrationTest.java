package io.github.hectorvent.floci.services.appconfig;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AppConfigLegacyIntegrationTest {
    private String application;
    private String environment;
    private String profile;
    private String strategy;
    private String name;

    @BeforeEach
    void deployHostedConfiguration() {
        name = "legacy-" + UUID.randomUUID();
        application = given().contentType(ContentType.JSON).body(Map.of("Name", name))
                .post("/applications").then().statusCode(201).extract().path("Id");
        environment = createEnvironment("local");
        profile = createProfile(Map.of("Name", "settings", "LocationUri", "hosted"));
        createHostedVersion(profile, "{\"enabled\":true}");
        strategy = given().contentType(ContentType.JSON).body(Map.of(
                "Name", name, "DeploymentDurationInMinutes", 0, "GrowthFactor", 100,
                "FinalBakeTimeInMinutes", 0, "ReplicateTo", "NONE"))
                .post("/deploymentstrategies").then().statusCode(201).extract().path("Id");
        deploy(environment, profile, "1");
    }

    private String createEnvironment(String environmentName) {
        return given().contentType(ContentType.JSON).body(Map.of("Name", environmentName))
                .post("/applications/" + application + "/environments")
                .then().statusCode(201).extract().path("Id");
    }

    private String createProfile(Map<String, String> request) {
        return given().contentType(ContentType.JSON).body(request)
                .post("/applications/" + application + "/configurationprofiles")
                .then().statusCode(201).extract().path("Id");
    }

    private void createHostedVersion(String profileId, String content) {
        createHostedVersion(profileId, content, ContentType.JSON.toString());
    }

    private void createHostedVersion(String profileId, String content, String contentType) {
        given().contentType(contentType).body(content.getBytes(StandardCharsets.UTF_8))
                .post("/applications/" + application + "/configurationprofiles/" + profileId
                        + "/hostedconfigurationversions").then().statusCode(201);
    }

    private void deploy(String environmentId, String profileId, String version) {
        given().contentType(ContentType.JSON).body(Map.of("ConfigurationProfileId", profileId,
                "ConfigurationVersion", version, "DeploymentStrategyId", strategy))
                .post("/applications/" + application + "/environments/" + environmentId + "/deployments")
                .then().statusCode(201);
    }

    private String path() {
        return path(environment, profile);
    }

    private String path(String environmentId, String profileId) {
        return "/applications/" + application + "/environments/" + environmentId + "/configurations/" + profileId;
    }

    @Test
    void readsTheDeployedVersionByIdsAndNames() {
        for (String selected : new String[]{path(),
                "/applications/" + name + "/environments/local/configurations/settings"}) {
            given().queryParam("client_id", "reader").get(selected).then()
                    .statusCode(200)
                    .header("Configuration-Version", "1")
                    .header("Content-Type", startsWith("application/json"))
                    .body("enabled", equalTo(true));
        }
    }

    @Test
    void servesTheStoredContentTypeWhateverTheAcceptHeader() {
        String yaml = createProfile(Map.of("Name", "yaml-settings", "LocationUri", "hosted"));
        createHostedVersion(yaml, "enabled: true\n", "application/x-yaml");
        deploy(environment, yaml, "1");

        given().accept("text/plain").queryParam("client_id", "reader").get(path(environment, yaml))
                .then().statusCode(200)
                .header("Content-Type", startsWith("application/x-yaml"))
                .body(equalTo("enabled: true\n"));
    }

    @Test
    void aVersionStoredWithoutAContentTypeIsServedAsOctetStream() throws Exception {
        String untyped = createProfile(Map.of("Name", "untyped-settings", "LocationUri", "hosted"));
        // RestAssured stamps a default Content-Type on any request with a body, so this one uses a raw client.
        HttpResponse<String> created = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + RestAssured.port + "/applications/" + application
                        + "/configurationprofiles/" + untyped + "/hostedconfigurationversions"))
                .POST(HttpRequest.BodyPublishers.ofString("raw"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        deploy(environment, untyped, "1");

        given().queryParam("client_id", "reader").get(path(environment, untyped))
                .then().statusCode(200)
                .header("Content-Type", startsWith("application/octet-stream"))
                .body(equalTo("raw"));
    }

    @Test
    void matchingClientVersionReturnsNoContentWithoutEntityHeaders() {
        given().queryParam("client_id", "reader").queryParam("client_configuration_version", "1")
                .get(path()).then().statusCode(204)
                .header("Configuration-Version", "1")
                .header("Content-Type", nullValue())
                .header("Content-Length", nullValue())
                .body(equalTo(""));
        given().queryParam("client_id", "reader").queryParam("client_configuration_version", "0")
                .get(path()).then().statusCode(200).body("enabled", equalTo(true));
        given().queryParam("client_id", "another-reader").get(path())
                .then().statusCode(200).body("enabled", equalTo(true));
    }

    @Test
    void anUndeployedNewVersionDoesNotReplaceTheActiveVersion() {
        createHostedVersion(profile, "{\"enabled\":false}");
        given().queryParam("client_id", "reader").get(path())
                .then().statusCode(200).header("Configuration-Version", "1").body("enabled", equalTo(true));

        deploy(environment, profile, "2");
        given().queryParam("client_id", "reader").queryParam("client_configuration_version", "1")
                .get(path()).then().statusCode(200).header("Configuration-Version", "2").body("enabled", equalTo(false));
    }

    @Test
    void refusesMissingOrOversizedClientAndVersion() {
        given().get(path()).then().statusCode(400).body("__type", equalTo("BadRequestException"));
        given().queryParam("client_id", "").get(path()).then().statusCode(400);
        given().queryParam("client_id", "x".repeat(64)).get(path()).then().statusCode(200);
        given().queryParam("client_id", "x".repeat(65)).get(path()).then().statusCode(400);
        given().queryParam("client_id", "reader").queryParam("client_configuration_version", "")
                .get(path()).then().statusCode(400);
        given().queryParam("client_id", "reader").queryParam("client_configuration_version", "x".repeat(1024))
                .get(path()).then().statusCode(200);
        given().queryParam("client_id", "reader").queryParam("client_configuration_version", "x".repeat(1025))
                .get(path()).then().statusCode(400);
        given().queryParam("client_id", "reader").get(path(environment, "x".repeat(65)))
                .then().statusCode(400).body("__type", equalTo("BadRequestException"));
    }

    @Test
    void missingOrUndeployedEnvironmentIsABadRequest() {
        String undeployed = createEnvironment("staging");
        for (String selected : new String[]{path(undeployed, profile), path("absent", profile)}) {
            given().queryParam("client_id", "reader").get(selected)
                    .then().statusCode(400).body("__type", equalTo("BadRequestException"));
        }
    }

    @Test
    void unknownApplicationOrProfileIsNotFound() {
        String deletedApplication = given().contentType(ContentType.JSON).body(Map.of("Name", "gone-" + UUID.randomUUID()))
                .post("/applications").then().statusCode(201).extract().path("Id");
        given().delete("/applications/" + deletedApplication).then().statusCode(204);
        String otherApplication = given().contentType(ContentType.JSON).body(Map.of("Name", "other-" + UUID.randomUUID()))
                .post("/applications").then().statusCode(201).extract().path("Id");
        String otherProfile = given().contentType(ContentType.JSON)
                .body(Map.of("Name", "other-settings", "LocationUri", "hosted"))
                .post("/applications/" + otherApplication + "/configurationprofiles")
                .then().statusCode(201).extract().path("Id");

        Map<String, String> selected = new LinkedHashMap<>();
        selected.put("application by name",
                "/applications/absent-" + UUID.randomUUID() + "/environments/local/configurations/settings");
        selected.put("application by id",
                "/applications/" + deletedApplication + "/environments/local/configurations/settings");
        selected.put("profile by name", path(environment, "absent-settings"));
        selected.put("profile by id", path(environment, otherProfile));
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : selected.entrySet()) {
            Response response = given().queryParam("client_id", "reader").get(entry.getValue());
            expected.put(entry.getKey(), "404 ResourceNotFoundException");
            actual.put(entry.getKey(), response.statusCode() + " " + response.path("__type"));
        }
        assertEquals(expected, actual);
    }

    @Test
    void deployedVersionWithoutHostedContentIsNotFound() {
        String s3Profile = createProfile(Map.of("Name", "s3-settings", "LocationUri", "s3://bucket/settings.json"));
        deploy(environment, s3Profile, "3sL4kqtJlcpXroDTDmJ");
        given().queryParam("client_id", "reader").get(path(environment, s3Profile))
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().delete("/applications/" + application + "/configurationprofiles/" + profile
                + "/hostedconfigurationversions/1").then().statusCode(204);
        given().queryParam("client_id", "reader").get(path())
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void featureFlagsAreReturnedInRetrievalFormat() {
        String flags = createProfile(Map.of("Name", "flags", "LocationUri", "hosted",
                "Type", "AWS.AppConfig.FeatureFlags"));
        createHostedVersion(flags, "{\"flags\":{\"beta\":{\"name\":\"beta\"}},"
                + "\"values\":{\"beta\":{\"enabled\":true,\"_createdAt\":\"created\"}},\"version\":\"1\"}");
        deploy(environment, flags, "1");

        given().queryParam("client_id", "reader").get(path(environment, flags))
                .then().statusCode(200)
                .header("Configuration-Version", "1")
                .body("beta.enabled", equalTo(true))
                .body("beta._createdAt", nullValue())
                .body("values", nullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/ion; type=AWS.AppConfig.FeatureFlags",
            "application/ion; type=\"AWS.AppConfig.FeatureFlags\""})
    void variantFeatureFlagsAreIonForACallerThatAcceptsOnlyIon(String accept) {
        String flags = createProfile(Map.of("Name", "variant-flags", "LocationUri", "hosted",
                "Type", "AWS.AppConfig.FeatureFlags"));
        createHostedVersion(flags, "{\"flags\":{},\"version\":\"1\",\"values\":{\"promo\":{\"_variants\":["
                + "{\"attributeValues\":{\"storeId\":\"uk\"},\"enabled\":true,\"name\":\"uk\","
                + "\"rule\":\"(eq $storeId \\\"uk\\\")\"},{\"enabled\":false,\"name\":\"default\"}]}}}");
        deploy(environment, flags, "1");

        byte[] ion = given().accept(accept)
                .queryParam("client_id", "reader").get(path(environment, flags))
                .then().statusCode(200)
                .header("Configuration-Version", "1")
                .header("Content-Type", startsWith("application/ion"))
                .extract().asByteArray();
        assertEquals(IonTestSupport.parse("promo::[uk::[(eq $storeId \"uk\"), "
                        + "'''{\"_variant\":\"uk\",\"enabled\":true,\"storeId\":\"uk\"}'''],"
                        + "'''{\"_variant\":\"default\",\"enabled\":false}''']"),
                IonTestSupport.decode(ion));
    }
}
