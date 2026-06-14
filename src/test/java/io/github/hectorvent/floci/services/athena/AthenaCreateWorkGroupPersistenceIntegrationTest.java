package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(AthenaCreateWorkGroupPersistenceIntegrationTest.PersistentStorageProfile.class)
class AthenaCreateWorkGroupPersistenceIntegrationTest {

    static final String STORAGE_DIR = "target/athena-workgroups-it";
    private static final Path WORKGROUPS_FILE = Path.of(STORAGE_DIR, "workgroups.json");
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void setup() throws Exception {
        RestAssuredJsonUtils.configureAwsContentTypes();
        Files.deleteIfExists(WORKGROUPS_FILE);
    }

    @Test
    void createWorkGroupPersistsNormalizedStateWithoutSeedingPrimary() throws Exception {
        Files.deleteIfExists(WORKGROUPS_FILE);

        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "analytics",
                  "Description": "team analytics",
                  "Tags": [
                    { "Key": "env", "Value": "dev" }
                  ],
                  "Configuration": {
                    "ResultConfiguration": {
                      "OutputLocation": "s3://floci-athena-results/results/"
                    },
                    "EnforceWorkGroupConfiguration": true,
                    "PublishCloudWatchMetricsEnabled": true,
                    "RequesterPaysEnabled": true,
                    "BytesScannedCutoffPerQuery": 10000000,
                    "EngineVersion": {
                      "SelectedEngineVersion": "Athena engine version 3"
                    }
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        assertTrue(Files.exists(WORKGROUPS_FILE), "CreateWorkGroup should create workgroups.json");

        var store = new PersistentStorage<String, Map<String, Object>>(
                WORKGROUPS_FILE,
                new TypeReference<Map<String, Map<String, Object>>>() {});
        store.load();

        Map<String, Object> persisted = store.get("000000000000/us-east-1:analytics").orElseThrow();
        assertEquals("analytics", persisted.get("Name"));
        assertEquals("team analytics", persisted.get("Description"));
        assertEquals("ENABLED", persisted.get("State"));
        assertNotNull(persisted.get("CreationTime"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> tags = (List<Map<String, String>>) persisted.get("Tags");
        assertEquals(List.of(Map.of("Key", "env", "Value", "dev")), tags);

        @SuppressWarnings("unchecked")
        Map<String, Object> configuration = (Map<String, Object>) persisted.get("Configuration");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultConfiguration = (Map<String, Object>) configuration.get("ResultConfiguration");
        @SuppressWarnings("unchecked")
        Map<String, Object> engineVersion = (Map<String, Object>) configuration.get("EngineVersion");

        assertEquals("s3://floci-athena-results/results/", resultConfiguration.get("OutputLocation"));
        assertEquals(true, configuration.get("EnforceWorkGroupConfiguration"));
        assertEquals(true, configuration.get("PublishCloudWatchMetricsEnabled"));
        assertEquals(true, configuration.get("RequesterPaysEnabled"));
        assertEquals(10000000, configuration.get("BytesScannedCutoffPerQuery"));
        assertEquals("Athena engine version 3", engineVersion.get("SelectedEngineVersion"));
        assertEquals("Athena engine version 3", engineVersion.get("EffectiveEngineVersion"));

        assertFalse(store.get("000000000000/us-east-1:primary").isPresent(),
                "primary should be lazy-created later, not written by CreateWorkGroup");
    }

    @Test
    void createWorkGroupAppliesDefaultConfigurationWhenOmitted() throws Exception {
        Files.deleteIfExists(WORKGROUPS_FILE);

        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "defaulted-workgroup"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        var store = new PersistentStorage<String, Map<String, Object>>(
                WORKGROUPS_FILE,
                new TypeReference<Map<String, Map<String, Object>>>() {});
        store.load();

        Map<String, Object> persisted = store.get("000000000000/us-east-1:defaulted-workgroup").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> configuration = (Map<String, Object>) persisted.get("Configuration");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultConfiguration = (Map<String, Object>) configuration.get("ResultConfiguration");
        @SuppressWarnings("unchecked")
        Map<String, Object> engineVersion = (Map<String, Object>) configuration.get("EngineVersion");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> tags = (List<Map<String, String>>) persisted.get("Tags");

        assertEquals("s3://floci-athena-results/results/", resultConfiguration.get("OutputLocation"));
        assertEquals(false, configuration.get("EnforceWorkGroupConfiguration"));
        assertEquals(false, configuration.get("PublishCloudWatchMetricsEnabled"));
        assertEquals(false, configuration.get("RequesterPaysEnabled"));
        assertEquals("Athena engine version 3", engineVersion.get("SelectedEngineVersion"));
        assertEquals("Athena engine version 3", engineVersion.get("EffectiveEngineVersion"));
        assertEquals(List.of(), tags);
    }

    @Test
    void createWorkGroupNormalizesEffectiveEngineVersionFromSelectedVersion() throws Exception {
        Files.deleteIfExists(WORKGROUPS_FILE);

        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "engine-normalized",
                  "Configuration": {
                    "EngineVersion": {
                      "SelectedEngineVersion": "AUTO"
                    }
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        var store = new PersistentStorage<String, Map<String, Object>>(
                WORKGROUPS_FILE,
                new TypeReference<Map<String, Map<String, Object>>>() {});
        store.load();

        Map<String, Object> persisted = store.get("000000000000/us-east-1:engine-normalized").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> configuration = (Map<String, Object>) persisted.get("Configuration");
        @SuppressWarnings("unchecked")
        Map<String, Object> engineVersion = (Map<String, Object>) configuration.get("EngineVersion");

        assertEquals("AUTO", engineVersion.get("SelectedEngineVersion"));
        assertEquals("Athena engine version 3", engineVersion.get("EffectiveEngineVersion"));
    }

    @Test
    void createWorkGroupIgnoresExplicitEffectiveEngineVersionFromRequest() throws Exception {
        Files.deleteIfExists(WORKGROUPS_FILE);

        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "engine-explicit",
                  "Configuration": {
                    "EngineVersion": {
                      "SelectedEngineVersion": "AUTO",
                      "EffectiveEngineVersion": "Athena engine version 3"
                    }
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        var store = new PersistentStorage<String, Map<String, Object>>(
                WORKGROUPS_FILE,
                new TypeReference<Map<String, Map<String, Object>>>() {});
        store.load();

        Map<String, Object> persisted = store.get("000000000000/us-east-1:engine-explicit").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> configuration = (Map<String, Object>) persisted.get("Configuration");
        @SuppressWarnings("unchecked")
        Map<String, Object> engineVersion = (Map<String, Object>) configuration.get("EngineVersion");

        assertEquals("AUTO", engineVersion.get("SelectedEngineVersion"));
        assertEquals("Athena engine version 3", engineVersion.get("EffectiveEngineVersion"));
    }

    @Test
    void createWorkGroupKeepsDefaultEngineVersionWhenEngineVersionIsEmpty() throws Exception {
        Files.deleteIfExists(WORKGROUPS_FILE);

        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "engine-empty",
                  "Configuration": {
                    "EngineVersion": {}
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        var store = new PersistentStorage<String, Map<String, Object>>(
                WORKGROUPS_FILE,
                new TypeReference<Map<String, Map<String, Object>>>() {});
        store.load();

        Map<String, Object> persisted = store.get("000000000000/us-east-1:engine-empty").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> configuration = (Map<String, Object>) persisted.get("Configuration");
        @SuppressWarnings("unchecked")
        Map<String, Object> engineVersion = (Map<String, Object>) configuration.get("EngineVersion");

        assertEquals("Athena engine version 3", engineVersion.get("SelectedEngineVersion"));
        assertEquals("Athena engine version 3", engineVersion.get("EffectiveEngineVersion"));
    }

    @Test
    void createWorkGroupResolvesBlankSelectedEngineVersionToDefaultEffectiveVersion() throws Exception {
        Files.deleteIfExists(WORKGROUPS_FILE);

        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "engine-blank-selected",
                  "Configuration": {
                    "EngineVersion": {
                      "SelectedEngineVersion": ""
                    }
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        var store = new PersistentStorage<String, Map<String, Object>>(
                WORKGROUPS_FILE,
                new TypeReference<Map<String, Map<String, Object>>>() {});
        store.load();

        Map<String, Object> persisted = store.get("000000000000/us-east-1:engine-blank-selected").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> configuration = (Map<String, Object>) persisted.get("Configuration");
        @SuppressWarnings("unchecked")
        Map<String, Object> engineVersion = (Map<String, Object>) configuration.get("EngineVersion");

        assertEquals("Athena engine version 3", engineVersion.get("SelectedEngineVersion"));
        assertEquals("Athena engine version 3", engineVersion.get("EffectiveEngineVersion"));
    }

    public static final class PersistentStorageProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.storage.mode", "persistent",
                    "floci.storage.persistent-path", STORAGE_DIR
            );
        }
    }
}
