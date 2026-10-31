package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::AppConfig::Application}, {@code Environment}, {@code ConfigurationProfile} and
 * {@code DeploymentStrategy} through real stack operations, read back through the AppConfig API.
 * {@code Ref} and the ID attribute of each are the AppConfig ID; an update changes mutable
 * properties in place and replaces a profile whose {@code LocationUri} changes; a failed update puts
 * the in-place changes back and deletes the replacement; and the stack delete removes everything,
 * hosted configuration versions and deployments included. A failing operation ends in a Secrets
 * Manager secret that cannot be created.
 */
@QuarkusTest
class CloudFormationAppConfigIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALARM = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-errors";
    private static final List<String> RESOURCES = List.of("App", "Env", "Profile", "Strategy");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aStackCreatesTheFourTypesAndItsDeleteRemovesThemWithTheirVersionsAndDeployments() throws Exception {
        String stack = unique("cfn-appconfig-create");
        Map<String, Object> resources = first();
        resources.put("Untyped", Map.of("Type", "AWS::AppConfig::ConfigurationProfile", "Properties",
                Map.of("ApplicationId", ref("App"), "Name", "untyped", "LocationUri", "hosted")));
        deploy("CreateStack", stack, template(resources, false));
        assertStatus(stack, "CREATE_COMPLETE");

        Map<String, String> outputs = outputs(stack);
        Map<String, String> physicalIds = physicalIds(stack);
        for (String resource : RESOURCES) {
            assertEquals(outputs.get(resource + "Ref"), outputs.get(resource + "Id"), resource);
            assertEquals(physicalIds.get(resource), outputs.get(resource + "Ref"), resource);
        }
        String app = outputs.get("AppRef");
        String env = outputs.get("EnvRef");
        String profile = outputs.get("ProfileRef");
        String strategy = outputs.get("StrategyRef");
        String envPath = "/applications/" + app + "/environments/" + env;
        String profilePath = "/applications/" + app + "/configurationprofiles/" + profile;

        appconfig().get("/applications/" + app).then().statusCode(200)
                .body("Name", equalTo("orders"), "Description", equalTo("Order service"));
        assertEquals(Map.of("team", "payments", "tier", "gold"), applicationTags(app));
        appconfig().get(envPath).then().statusCode(200)
                .body("Name", equalTo("beta"), "Monitors[0].AlarmArn", equalTo(ALARM))
                .body("Monitors[0]", not(hasKey("AlarmRoleArn")));
        appconfig().get(profilePath).then().statusCode(200)
                .body("Name", equalTo("settings"), "LocationUri", equalTo("hosted"), "Type", equalTo("AWS.Freeform"));
        appconfig().get("/applications/" + app + "/configurationprofiles/" + physicalIds.get("Untyped"))
                .then().statusCode(200).body("Type", nullValue());
        appconfig().get("/deploymentstrategies/" + strategy).then().statusCode(200)
                .body("Name", equalTo("gradual"), "DeploymentDurationInMinutes", equalTo(0),
                        "FinalBakeTimeInMinutes", equalTo(0), "GrowthFactor", equalTo(100f),
                        "GrowthType", equalTo("LINEAR"), "ReplicateTo", equalTo("NONE"));

        // The stack's resources work together: a hosted version deploys with the stack's strategy.
        appconfig().body("{\"enabled\":true}").post(profilePath + "/hostedconfigurationversions")
                .then().statusCode(201).header("Version-Number", equalTo("1"));
        int deployment = appconfig().body(Map.of("ConfigurationProfileId", profile, "ConfigurationVersion", "1",
                        "DeploymentStrategyId", strategy))
                .post(envPath + "/deployments").then().statusCode(201)
                .body("ConfigurationName", equalTo("settings"), "ConfigurationLocationUri", equalTo("hosted"))
                .extract().path("DeploymentNumber");

        deleteStack(stack);
        appconfig().get(envPath + "/deployments/" + deployment).then().statusCode(404);
        appconfig().get(envPath).then().statusCode(404);
        appconfig().get(profilePath + "/hostedconfigurationversions/1").then().statusCode(404);
        appconfig().get(profilePath).then().statusCode(404);
        appconfig().get("/deploymentstrategies/" + strategy).then().statusCode(404);
        appconfig().get("/applications/" + app).then().statusCode(404);
    }

    @Test
    void anUpdateChangesMutablePropertiesInPlaceAndReplacesAProfileWhoseLocationChanges() throws Exception {
        String stack = unique("cfn-appconfig-update");
        deploy("CreateStack", stack, template(first(), false));
        assertStatus(stack, "CREATE_COMPLETE");
        Map<String, String> before = outputs(stack);

        deploy("UpdateStack", stack, template(second(), false));
        assertStatus(stack, "UPDATE_COMPLETE");

        Map<String, String> after = outputs(stack);
        String app = after.get("AppRef");
        for (String kept : List.of("App", "Env", "Strategy")) {
            assertEquals(before.get(kept + "Ref"), after.get(kept + "Ref"), kept + " is changed in place");
        }
        String oldProfile = before.get("ProfileRef");
        String newProfile = after.get("ProfileRef");
        assertNotEquals(oldProfile, newProfile, "a LocationUri change replaces the profile");
        assertEquals(newProfile, after.get("ProfileId"));

        appconfig().get("/applications/" + app).then().statusCode(200)
                .body("Name", equalTo("orders-v2"), "Description", nullValue());
        assertEquals(Map.of("team", "billing", "owner", "ops"), applicationTags(app));
        appconfig().get("/applications/" + app + "/environments/" + after.get("EnvRef")).then().statusCode(200)
                .body("Name", equalTo("gamma"), "Description", equalTo("Second stage"))
                .body("$", not(hasKey("Monitors")));
        appconfig().get("/applications/" + app + "/configurationprofiles/" + newProfile).then().statusCode(200)
                .body("Name", equalTo("settings"), "LocationUri", equalTo("ssm-parameter://orders-settings"));
        appconfig().get("/applications/" + app + "/configurationprofiles/" + oldProfile).then().statusCode(404);
        appconfig().get("/deploymentstrategies/" + after.get("StrategyRef")).then().statusCode(200)
                .body("Description", equalTo("Ten minutes"), "DeploymentDurationInMinutes", equalTo(10),
                        "GrowthFactor", equalTo(20f), "GrowthType", equalTo("EXPONENTIAL"));
        assertTrue(hasEvent(stack, "Profile", oldProfile, "DELETE_COMPLETE"),
                "the update's cleanup reports the displaced profile deleted");

        deleteStack(stack);
        appconfig().get("/applications/" + app).then().statusCode(404);
    }

    @Test
    void aFailedUpdatePutsTheInPlaceChangesBackAndDeletesTheReplacement() throws Exception {
        String stack = unique("cfn-appconfig-rollback");
        deploy("CreateStack", stack, template(first(), false));
        assertStatus(stack, "CREATE_COMPLETE");
        Map<String, String> before = outputs(stack);
        String app = before.get("AppRef");

        deploy("UpdateStack", stack, template(second(), true));
        assertStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(before, outputs(stack));
        appconfig().get("/applications/" + app).then().statusCode(200)
                .body("Name", equalTo("orders"), "Description", equalTo("Order service"));
        assertEquals(Map.of("team", "payments", "tier", "gold"), applicationTags(app));
        appconfig().get("/applications/" + app + "/environments/" + before.get("EnvRef")).then().statusCode(200)
                .body("Name", equalTo("beta"), "Monitors[0].AlarmArn", equalTo(ALARM));
        List<String> profiles = appconfig().get("/applications/" + app + "/configurationprofiles")
                .then().statusCode(200).extract().jsonPath().getList("Items.Id", String.class);
        assertEquals(List.of(before.get("ProfileRef")), profiles, "the replacement profile is deleted");
        appconfig().get("/deploymentstrategies/" + before.get("StrategyRef")).then().statusCode(200)
                .body("Description", equalTo("Immediate"), "DeploymentDurationInMinutes", equalTo(0),
                        "GrowthFactor", equalTo(100f), "GrowthType", equalTo("LINEAR"));

        deleteStack(stack);
        appconfig().get("/applications/" + app).then().statusCode(404);
    }

    @Test
    void aChangeSetReportsReplacementForCreateOnlyChangesOnly() throws Exception {
        String stack = unique("cfn-appconfig-change-set");
        deploy("CreateStack", stack, template(first(), false));
        assertStatus(stack, "CREATE_COMPLETE");

        Map<String, Object> renamed = first();
        properties(renamed, "App").put("Name", "orders-v2");
        properties(renamed, "Env").put("Name", "gamma");
        properties(renamed, "Profile").put("Name", "renamed");
        properties(renamed, "Strategy").put("GrowthFactor", 50);
        assertEquals(Map.of("App", "False", "Env", "False", "Profile", "False", "Strategy", "False"),
                replacements(stack, "renamed", template(renamed, false)));

        Map<String, Object> moved = first();
        properties(moved, "Profile").put("LocationUri", "ssm-parameter://orders-settings");
        properties(moved, "Strategy").put("Name", "immediate");
        assertEquals(Map.of("Profile", "True", "Strategy", "True"),
                replacements(stack, "moved", template(moved, false)));

        deleteStack(stack);
    }

    /** The first version of the four resources. */
    private static Map<String, Object> first() {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("App", resource("Application", props("Name", "orders", "Description", "Order service",
                "Tags", tags("team", "payments", "tier", "gold"))));
        resources.put("Env", resource("Environment", props("ApplicationId", ref("App"), "Name", "beta",
                "Monitors", List.of(Map.of("AlarmArn", ALARM)))));
        resources.put("Profile", resource("ConfigurationProfile", props("ApplicationId", ref("App"),
                "Name", "settings", "LocationUri", "hosted", "Type", "AWS.Freeform")));
        resources.put("Strategy", resource("DeploymentStrategy", props("Name", "gradual", "ReplicateTo", "NONE",
                "DeploymentDurationInMinutes", 0, "FinalBakeTimeInMinutes", 0, "GrowthFactor", 100,
                "Description", "Immediate")));
        return resources;
    }

    /**
     * The second version: the application renamed, its description dropped and its tags changed,
     * the environment renamed with its monitors dropped, the profile moved to another location, and
     * the strategy slowed down.
     */
    private static Map<String, Object> second() {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("App", resource("Application", props("Name", "orders-v2",
                "Tags", tags("team", "billing", "owner", "ops"))));
        resources.put("Env", resource("Environment", props("ApplicationId", ref("App"), "Name", "gamma",
                "Description", "Second stage")));
        resources.put("Profile", resource("ConfigurationProfile", props("ApplicationId", ref("App"),
                "Name", "settings", "LocationUri", "ssm-parameter://orders-settings", "Type", "AWS.Freeform")));
        resources.put("Strategy", resource("DeploymentStrategy", props("Name", "gradual", "ReplicateTo", "NONE",
                "DeploymentDurationInMinutes", 10, "GrowthFactor", 20, "GrowthType", "EXPONENTIAL",
                "Description", "Ten minutes")));
        return resources;
    }

    private static String template(Map<String, Object> resources, boolean failAfter) throws JsonProcessingException {
        Map<String, Object> all = new LinkedHashMap<>(resources);
        if (failAfter) {
            all.put("Failure", Map.of("Type", "AWS::SecretsManager::Secret", "DependsOn", RESOURCES,
                    "Properties", Map.of("SecretString", "explicit",
                            "GenerateSecretString", Map.of("PasswordLength", 32))));
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        Map<String, String> idAttributes = Map.of("App", "ApplicationId", "Env", "EnvironmentId",
                "Profile", "ConfigurationProfileId", "Strategy", "Id");
        for (String resource : RESOURCES) {
            outputs.put(resource + "Ref", Map.of("Value", ref(resource)));
            outputs.put(resource + "Id", Map.of("Value",
                    Map.of("Fn::GetAtt", List.of(resource, idAttributes.get(resource)))));
        }
        return MAPPER.writeValueAsString(Map.of("Resources", all, "Outputs", outputs));
    }

    private static Map<String, Object> resource(String type, Map<String, Object> properties) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("Type", "AWS::AppConfig::" + type);
        resource.put("Properties", properties);
        return resource;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> resources, String logicalId) {
        return (Map<String, Object>) ((Map<String, Object>) resources.get(logicalId)).get("Properties");
    }

    /** Alternating property names and values, in order, in a map a test can change. */
    private static Map<String, Object> props(Object... namesAndValues) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            properties.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return properties;
    }

    private static List<Map<String, String>> tags(String... keysAndValues) {
        List<Map<String, String>> tags = new ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            tags.add(Map.of("Key", keysAndValues[i], "Value", keysAndValues[i + 1]));
        }
        return tags;
    }

    private static Map<String, String> ref(String logicalId) {
        return Map.of("Ref", logicalId);
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private static void deploy(String action, String stack, String template) {
        cfn(action, stack).formParam("TemplateBody", template).post("/").then().statusCode(200);
    }

    private static void assertStatus(String stack, String expected) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals(expected, state.status(), state.reason());
    }

    private static void deleteStack(String stack) {
        cfn("DeleteStack", stack).post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static Map<String, String> outputs(String stack) {
        String body = cfn("DescribeStacks", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "Outputs", "OutputKey", "OutputValue");
    }

    private static Map<String, String> physicalIds(String stack) {
        String body = cfn("DescribeStackResources", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "StackResources", "LogicalResourceId", "PhysicalResourceId");
    }

    private static boolean hasEvent(String stack, String logicalId, String physicalId, String status) {
        String body = cfn("DescribeStackEvents", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractGroups(body, "member").stream()
                .anyMatch(event -> logicalId.equals(event.get("LogicalResourceId"))
                        && physicalId.equals(event.get("PhysicalResourceId"))
                        && status.equals(event.get("ResourceStatus")));
    }

    /** The {@code Replacement} a change set to {@code template} reports, by logical id. */
    private static Map<String, String> replacements(String stack, String changeSet, String template) {
        cfn("CreateChangeSet", stack).formParam("ChangeSetName", changeSet).formParam("ChangeSetType", "UPDATE")
                .formParam("TemplateBody", template).post("/").then().statusCode(200);
        XmlPath body = cfn("DescribeChangeSet", stack).formParam("ChangeSetName", changeSet).post("/")
                .then().statusCode(200).extract().xmlPath();
        List<String> logicalIds = body.getList(
                "**.findAll { it.name() == 'ResourceChange' }.LogicalResourceId", String.class);
        List<String> values = body.getList("**.findAll { it.name() == 'ResourceChange' }.Replacement", String.class);
        Map<String, String> replacements = new LinkedHashMap<>();
        for (int i = 0; i < logicalIds.size(); i++) {
            replacements.put(logicalIds.get(i), values.get(i));
        }
        return replacements;
    }

    private static RequestSpecification appconfig() {
        return given().contentType(ContentType.JSON);
    }

    private static Map<String, String> applicationTags(String applicationId) {
        JsonPath body = appconfig().get("/tags/arn:aws:appconfig:us-east-1:000000000000:application/" + applicationId)
                .then().statusCode(200).extract().jsonPath();
        return body.getMap("Tags", String.class, String.class);
    }
}
