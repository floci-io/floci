package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provisions an {@code AWS::Events::Archive} through a CloudFormation stack and reads it back
 * through the EventBridge API. {@code Ref} is the archive name and {@code Fn::GetAtt Arn} its ARN;
 * a description, pattern or retention change updates the archive in place, a property removed
 * from the template keeps its value, and a later resource failing rolls the change back; a source
 * change replaces the archive, and is refused for an explicitly named one, as is declaring a
 * generated name explicitly; an update of a stack whose archive was stubbed creates a real one;
 * a description EventBridge rejects fails the create with its validation message; and the stack
 * delete removes it. Custom source buses are created through the EventBridge API so the stacks
 * hold only archives.
 */
@QuarkusTest
class CloudFormationEventsArchiveIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/cloudformation/aws4_request";
    private static final String EVENTS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/events/aws4_request";
    private static final String PATTERN = "{\"source\":[\"app.orders\"]}";
    private final List<String> stacks = new ArrayList<>();
    private final List<String> buses = new ArrayList<>();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String stack : List.copyOf(stacks)) {
            deleteStack(stack);
        }
        for (String bus : buses) {
            events("DeleteEventBus", Map.of("Name", bus)).then().statusCode(200);
        }
    }

    @Test
    void createReadsBackThroughDescribeArchive() throws Exception {
        String bus = createBus();
        String name = "orders-" + suffix();
        Map<String, Object> properties = archiveProperties(name, bus);
        properties.put("Description", "orders archive");
        properties.put("EventPattern", MAPPER.readTree(PATTERN));
        properties.put("RetentionDays", 7);

        String body = awaitStatus(createStack(template(properties), "CREATE_COMPLETE"), "CREATE_COMPLETE");

        assertEquals(name, output(body, "ArchiveRef"), "Ref is the archive name");
        JsonPath archive = describeArchive(name).then().statusCode(200).extract().jsonPath();
        assertEquals(bus, archive.getString("EventSourceArn"));
        assertEquals(PATTERN, archive.getString("EventPattern"));
        assertEquals(7, archive.getInt("RetentionDays"));
        assertEquals("orders archive", archive.getString("Description"));
        assertEquals("ENABLED", archive.getString("State"));
        assertEquals(archive.getString("ArchiveArn"), output(body, "ArchiveArn"), "GetAtt Arn is the archive ARN");
    }

    @Test
    void unnamedArchiveGetsAGeneratedNameAndZeroRetention() throws Exception {
        String stack = createStack(template(archiveProperties(null, createBus())), "CREATE_COMPLETE");
        String name = output(awaitStatus(stack, "CREATE_COMPLETE"), "ArchiveRef");

        assertTrue(name.startsWith(stack + "-Archive-"), name);
        assertTrue(name.length() <= 48, name);
        JsonPath archive = describeArchive(name).then().statusCode(200).extract().jsonPath();
        assertEquals(0, archive.getInt("RetentionDays"));
    }

    @Test
    void inPlaceUpdateChangesRetentionAndKeepsARemovedDescription() throws Exception {
        String bus = createBus();
        String name = "orders-" + suffix();
        Map<String, Object> created = archiveProperties(name, bus);
        created.put("Description", "orders archive");
        created.put("EventPattern", MAPPER.readTree(PATTERN));
        created.put("RetentionDays", 7);
        String stack = createStack(template(created), "CREATE_COMPLETE");
        String arn = output(awaitStatus(stack, "CREATE_COMPLETE"), "ArchiveArn");

        Map<String, Object> updated = archiveProperties(name, bus);
        updated.put("EventPattern", MAPPER.readTree(PATTERN));
        updated.put("RetentionDays", 30);
        updateStack(stack, template(updated));
        String body = awaitStatus(stack, "UPDATE_COMPLETE");

        assertEquals(name, output(body, "ArchiveRef"));
        assertEquals(arn, output(body, "ArchiveArn"));
        JsonPath archive = describeArchive(name).then().statusCode(200).extract().jsonPath();
        assertEquals(30, archive.getInt("RetentionDays"));
        assertEquals("orders archive", archive.getString("Description"),
                "a property removed from the template keeps its value");
        assertEquals(PATTERN, archive.getString("EventPattern"));
    }

    @Test
    void noValueDescriptionAndPatternKeepTheirStoredValues() throws Exception {
        String bus = createBus();
        String name = "orders-" + suffix();
        Map<String, Object> created = archiveProperties(name, bus);
        created.put("Description", "orders archive");
        created.put("EventPattern", MAPPER.readTree(PATTERN));
        String stack = createStack(template(created), "CREATE_COMPLETE");

        Map<String, Object> updated = archiveProperties(name, bus);
        updated.put("Description", Map.of("Ref", "AWS::NoValue"));
        updated.put("EventPattern", Map.of("Ref", "AWS::NoValue"));
        updated.put("RetentionDays", 30);
        updateStack(stack, template(updated));
        awaitStatus(stack, "UPDATE_COMPLETE");

        JsonPath archive = describeArchive(name).then().statusCode(200).extract().jsonPath();
        assertEquals(30, archive.getInt("RetentionDays"));
        assertEquals("orders archive", archive.getString("Description"));
        assertEquals(PATTERN, archive.getString("EventPattern"));
    }

    @Test
    void literalEmptyDescriptionClearsTheStoredDescription() throws Exception {
        String bus = createBus();
        String name = "orders-" + suffix();
        Map<String, Object> created = archiveProperties(name, bus);
        created.put("Description", "orders archive");
        String stack = createStack(template(created), "CREATE_COMPLETE");

        Map<String, Object> updated = archiveProperties(name, bus);
        updated.put("Description", "");
        updateStack(stack, template(updated));
        awaitStatus(stack, "UPDATE_COMPLETE");

        assertFalse(describeArchive(name).then().statusCode(200).extract().jsonPath()
                .getMap("$").containsKey("Description"));
    }

    @Test
    void changingTheSourceReplacesTheArchive() throws Exception {
        String first = createBus();
        String second = createBus();
        String stack = createStack(template(archiveProperties(null, first)), "CREATE_COMPLETE");
        String original = output(awaitStatus(stack, "CREATE_COMPLETE"), "ArchiveRef");

        updateStack(stack, template(archiveProperties(null, second)));
        String replacement = output(awaitStatus(stack, "UPDATE_COMPLETE"), "ArchiveRef");

        assertNotEquals(original, replacement);
        assertEquals(second, describeArchive(replacement).then().statusCode(200)
                .extract().jsonPath().getString("EventSourceArn"));
        assertArchiveGone(original);
    }

    @Test
    void replacingAnExplicitlyNamedArchiveIsRefused() throws Exception {
        String first = createBus();
        String second = createBus();
        String name = "orders-" + suffix();
        Map<String, Object> created = archiveProperties(name, first);
        created.put("RetentionDays", 7);
        String stack = createStack(template(created), "CREATE_COMPLETE");

        Map<String, Object> moved = archiveProperties(name, second);
        moved.put("RetentionDays", 30);
        updateStack(stack, template(moved));
        String body = awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(name, output(body, "ArchiveRef"));
        assertRefusedAsCustomNamedReplacement(stack, name);
        JsonPath archive = describeArchive(name).then().statusCode(200).extract().jsonPath();
        assertEquals(first, archive.getString("EventSourceArn"));
        assertEquals(7, archive.getInt("RetentionDays"));
    }

    @Test
    void declaringTheGeneratedNameExplicitlyIsRefused() throws Exception {
        String bus = createBus();
        Map<String, Object> created = archiveProperties(null, bus);
        created.put("RetentionDays", 7);
        String stack = createStack(template(created), "CREATE_COMPLETE");
        String generated = output(awaitStatus(stack, "CREATE_COMPLETE"), "ArchiveRef");

        Map<String, Object> named = archiveProperties(generated, bus);
        named.put("RetentionDays", 7);
        updateStack(stack, template(named));
        String body = awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(generated, output(body, "ArchiveRef"));
        assertRefusedAsCustomNamedReplacement(stack, generated);
        JsonPath archive = describeArchive(generated).then().statusCode(200).extract().jsonPath();
        assertEquals(bus, archive.getString("EventSourceArn"));
        assertEquals(7, archive.getInt("RetentionDays"));
    }

    @Test
    void failedUpdateRestoresTheInPlaceChangeMadeBeforeALaterResourceFailed() throws Exception {
        String name = "orders-" + suffix();
        String broken = "broken-" + suffix();
        String stack = createStack(defaultBusTemplate(name, "orders archive", 7, null), "CREATE_COMPLETE");

        updateStack(stack, defaultBusTemplate(name, "changed archive", 30, broken));
        awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        JsonPath archive = describeArchive(name).then().statusCode(200).extract().jsonPath();
        assertEquals("orders archive", archive.getString("Description"));
        assertEquals(7, archive.getInt("RetentionDays"));
        assertArchiveGone(broken);
    }

    @Test
    void updateAfterStubUpgradeProvisionsTheRealArchive() throws Exception {
        // A stack created before AWS::Events::Archive had a provisioner holds the dispatcher's stub
        // for it, a <logicalId>-<8 hex> physical id naming no archive. Its next update must create
        // the archive instead of failing to describe that id.
        String stack = createStack(MAPPER.writeValueAsString(Map.of("Resources",
                Map.of("Archive", Map.of("Type", "AWS::Foo::Bar", "Properties", Map.of())))), "CREATE_COMPLETE");
        String stubId = XmlParser.extractFirst(cfn(stack, "DescribeStackResources", null).then().statusCode(200)
                .extract().asString(), "PhysicalResourceId", null);
        assertTrue(stubId != null && stubId.startsWith("Archive-"), stubId);

        updateStack(stack, template(archiveProperties(null, busArnSub("default"))));
        String name = output(awaitStatus(stack, "UPDATE_COMPLETE"), "ArchiveRef");

        assertNotEquals(stubId, name);
        assertTrue(name.startsWith(stack + "-Archive-"), name);
        String defaultBus = events("DescribeEventBus", Map.of("Name", "default")).then().statusCode(200)
                .extract().jsonPath().getString("Arn");
        assertEquals(defaultBus, describeArchive(name).then().statusCode(200)
                .extract().jsonPath().getString("EventSourceArn"));
    }

    @Test
    void anOverlongDescriptionFailsTheCreateWithTheEventBridgeValidationMessage() throws Exception {
        String name = "orders-" + suffix();
        Map<String, Object> properties = archiveProperties(name, createBus());
        properties.put("Description", "d".repeat(513));

        String stack = createStack(template(properties), "ROLLBACK_COMPLETE");

        assertArchiveEvent(stack, "CREATE_FAILED", reason -> reason.contains("at 'description' failed to satisfy "
                + "constraint: Member must have length less than or equal to 512"));
        assertArchiveGone(name);
    }

    @Test
    void deleteStackRemovesTheArchive() throws Exception {
        String name = "orders-" + suffix();
        String stack = createStack(template(archiveProperties(name, createBus())), "CREATE_COMPLETE");
        describeArchive(name).then().statusCode(200);

        deleteStack(stack);

        assertArchiveGone(name);
    }

    @Test
    void deleteStackToleratesAnArchiveDeletedOutOfBand() throws Exception {
        String name = "orders-" + suffix();
        String stack = createStack(template(archiveProperties(name, createBus())), "CREATE_COMPLETE");
        events("DeleteArchive", Map.of("ArchiveName", name)).then().statusCode(200);

        deleteStack(stack);

        assertArchiveGone(name);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private String createBus() throws Exception {
        String bus = "cfn-archive-bus-" + suffix();
        String arn = events("CreateEventBus", Map.of("Name", bus)).then().statusCode(200)
                .extract().jsonPath().getString("EventBusArn");
        buses.add(bus);
        return arn;
    }

    private String createStack(String template, String status) {
        String stack = "cfn-archive-" + suffix();
        cfn(stack, "CreateStack", template).then().statusCode(200);
        stacks.add(stack);
        awaitStatus(stack, status);
        return stack;
    }

    private static void updateStack(String stack, String template) {
        cfn(stack, "UpdateStack", template).then().statusCode(200);
    }

    /** Waits for the stack to be gone, which a DELETE_FAILED stack never is. */
    private void deleteStack(String stack) {
        cfn(stack, "DeleteStack", null).then().statusCode(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Response response = cfn(stack, "DescribeStacks", null);
            assertEquals(400, response.statusCode(), response.asString());
            assertTrue(response.asString().contains("does not exist"), response.asString());
        });
        stacks.remove(stack);
    }

    private static String awaitStatus(String stack, String status) {
        return await().atMost(Duration.ofSeconds(15)).until(
                () -> cfn(stack, "DescribeStacks", null).then().statusCode(200).extract().asString(),
                body -> status.equals(XmlParser.extractFirst(body, "StackStatus", null)));
    }

    private static String output(String body, String key) {
        String value = XmlParser.extractPairs(body, "Outputs", "OutputKey", "OutputValue").get(key);
        assertFalse(value == null || value.isBlank(), body);
        return value;
    }

    private static Response cfn(String stack, String action, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH).formParam("Action", action).formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        return request.post("/");
    }

    private static Response events(String action, Map<String, ?> body) throws Exception {
        return given().contentType("application/x-amz-json-1.1").header("Authorization", EVENTS_AUTH)
                .header("X-Amz-Target", "AWSEvents." + action).body(MAPPER.writeValueAsString(body)).post("/");
    }

    private static Response describeArchive(String name) throws Exception {
        return events("DescribeArchive", Map.of("ArchiveName", name));
    }

    private static void assertArchiveGone(String name) throws Exception {
        assertEquals("ResourceNotFoundException", describeArchive(name).then().statusCode(400)
                .extract().jsonPath().getString("__type"));
    }

    private static void assertRefusedAsCustomNamedReplacement(String stack, String name) {
        String expectedReason = "CloudFormation cannot update a stack when a custom-named resource requires "
                + "replacing. Rename " + name + " and update the stack again.";
        assertArchiveEvent(stack, "UPDATE_FAILED", expectedReason::equals);
    }

    private static void assertArchiveEvent(String stack, String status, Predicate<String> reason) {
        List<Map<String, String>> stackEvents = XmlParser.extractGroups(cfn(stack, "DescribeStackEvents", null)
                .then().statusCode(200).extract().asString(), "member");
        assertTrue(stackEvents.stream().anyMatch(event -> "Archive".equals(event.get("LogicalResourceId"))
                        && status.equals(event.get("ResourceStatus"))
                        && event.get("ResourceStatusReason") != null
                        && reason.test(event.get("ResourceStatusReason"))),
                "no " + status + " event with the expected reason in " + stackEvents);
    }

    private static Map<String, Object> archiveProperties(String name, Object sourceArn) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (name != null) {
            properties.put("ArchiveName", name);
        }
        properties.put("SourceArn", sourceArn);
        return properties;
    }

    private static String template(Map<String, Object> archiveProperties) throws Exception {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("ArchiveRef", Map.of("Value", Map.of("Ref", "Archive")));
        outputs.put("ArchiveArn", Map.of("Value", Map.of("Fn::GetAtt", List.of("Archive", "Arn"))));
        return MAPPER.writeValueAsString(Map.of(
                "Resources", Map.of("Archive", Map.of("Type", "AWS::Events::Archive", "Properties", archiveProperties)),
                "Outputs", outputs));
    }

    /**
     * An archive on the default bus and, when {@code brokenName} is given, a second one that depends
     * on it and names a bus that does not exist, so it fails after the first one changed.
     */
    private static String defaultBusTemplate(String name, String description, int retention, String brokenName)
            throws Exception {
        Map<String, Object> archive = archiveProperties(name, busArnSub("default"));
        archive.put("Description", description);
        archive.put("RetentionDays", retention);
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("Archive", Map.of("Type", "AWS::Events::Archive", "Properties", archive));
        if (brokenName != null) {
            resources.put("Broken", Map.of("Type", "AWS::Events::Archive", "DependsOn", "Archive",
                    "Properties", archiveProperties(brokenName, busArnSub(brokenName + "-missing-bus"))));
        }
        return MAPPER.writeValueAsString(Map.of("Resources", resources));
    }

    private static Map<String, String> busArnSub(String bus) {
        return Map.of("Fn::Sub", "arn:${AWS::Partition}:events:${AWS::Region}:${AWS::AccountId}:event-bus/" + bus);
    }
}
