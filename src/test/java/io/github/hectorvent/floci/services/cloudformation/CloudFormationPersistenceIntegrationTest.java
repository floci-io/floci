package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that a created stack is written to persistent storage so it survives a restart
 * (criteria #10, #11). Runs with the {@code persistent} storage mode; verifies the on-disk file
 * directly with a fresh {@link PersistentStorage} (what the service would read on the next boot).
 */
@QuarkusTest
@TestProfile(CloudFormationPersistenceIntegrationTest.PersistentStorageProfile.class)
class CloudFormationPersistenceIntegrationTest {

    static final String STORAGE_DIR = "target/cfn-persistence-it";
    private static final Path STACKS_FILE = Path.of(STORAGE_DIR, "cloudformation-stacks.json");

    @BeforeAll
    static void setup() throws Exception {
        RestAssuredJsonUtils.configureAwsContentTypes();
        Files.deleteIfExists(STACKS_FILE);
    }

    @Test
    void createdStackIsPersistedToDiskForRestart() {
        String template = """
            {
              "Resources": {
                "PersistBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "cfn-persist-it-bucket" }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "persist-it-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "persist-it-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // Read the on-disk file the way a fresh Floci process would on the next boot.
        var store = new PersistentStorage<String, Stack>(
                STACKS_FILE, new TypeReference<Map<String, Stack>>() {});
        store.load();

        // Account-prefixed key: <defaultAccount>/<region>:<stackName>
        Optional<Stack> loaded = store.get("000000000000/us-east-1:persist-it-stack");
        assertTrue(loaded.isPresent(), "Stack must be written to persistent storage to survive a restart");
        assertEquals("CREATE_COMPLETE", loaded.get().getStatus());
        assertEquals("us-east-1", loaded.get().getRegion());
        assertTrue(loaded.get().getResources().containsKey("PersistBucket"),
                "Persisted stack must retain its resources");
        assertEquals("cfn-persist-it-bucket",
                loaded.get().getResources().get("PersistBucket").getPhysicalId());
    }

    public static final class PersistentStorageProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.storage.mode", "persistent",
                    "floci.storage.persistent-path", STORAGE_DIR);
        }
    }
}
