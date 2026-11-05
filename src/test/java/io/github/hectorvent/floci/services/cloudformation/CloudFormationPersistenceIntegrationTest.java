package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PersistentStorageProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static io.github.hectorvent.floci.testing.PersistentStorageProfile.STORAGE_DIR;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that a created stack is written to persistent storage so it survives a restart
 * (criteria #10, #11). Runs with the {@code persistent} storage mode; verifies the on-disk file
 * directly with a fresh {@link PersistentStorage} (what the service would read on the next boot).
 *
 * <p>Persistent storage comes from {@link PersistentStorageProfile}, which this class shares with
 * other classes that each read their own service's file, so it deletes its stack, and with it the
 * bucket the stack created.
 */
@QuarkusTest
@TestProfile(PersistentStorageProfile.class)
class CloudFormationPersistenceIntegrationTest {

    private static final Path STACKS_FILE = Path.of(STORAGE_DIR, "cloudformation-stacks.json");

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

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
        cleanup.register(() -> {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", "persist-it-stack")
            .when()
                .post("/")
            .then()
                .statusCode(200);
            // DeleteStack answers before the deletion runs, so wait for it, then for the bucket.
            CfnStackWaits.awaitStackDeleted("persist-it-stack");
            given().when().head("/cfn-persist-it-bucket").then().statusCode(404);
        });

        CfnStackWaits.awaitStatus("persist-it-stack", "CREATE_COMPLETE");
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
        PersistentStorage<String, Stack> store = new PersistentStorage<>(
                STACKS_FILE, new TypeReference<Map<String, Stack>>() {});
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            store.load();
            assertEquals("CREATE_COMPLETE", store.get("000000000000/us-east-1:persist-it-stack")
                    .map(Stack::getStatus).orElse(null));
        });

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
}
