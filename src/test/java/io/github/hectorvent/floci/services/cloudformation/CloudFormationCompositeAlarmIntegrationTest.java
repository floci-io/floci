package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudFormationCompositeAlarmIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUpdateRestoresRuleTagsAndIdentity(boolean rename) throws Exception {
        String stack = unique("composite-rollback");
        String name = stack + "-alarm";
        String next = rename ? name + "-new" : name;
        deploy("CreateStack", stack, template(name, "TRUE", Map.of("team", "old", "drop", "restore"), false, false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        String arn = alarm(name).get("AlarmArn").toString();
        Response described = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks").formParam("StackName", stack).post("/");
        assertEquals(name, described.xmlPath().getString(
                "DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member.find { it.OutputKey == 'Name' }.OutputValue"));
        assertEquals(arn, described.xmlPath().getString(
                "DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member.find { it.OutputKey == 'Arn' }.OutputValue"));
        cw("TagResource", Map.of("ResourceARN", arn, "Tags", List.of(Map.of("Key", "external", "Value", "keep"))))
                .then().statusCode(200);
        deploy("UpdateStack", stack, template(next, "FALSE", Map.of("team", "new", "added", "remove"), true, false));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("TRUE", alarm(name).get("AlarmRule"));
        List<Map<String, String>> tags = cw("ListTagsForResource", Map.of("ResourceARN", arn))
                .then().statusCode(200).extract().jsonPath().getList("Tags");
        assertEquals(Map.of("team", "old", "drop", "restore", "external", "keep"),
                tags.stream().collect(Collectors.toMap(t -> t.get("Key"), t -> t.get("Value"))));
        if (rename) {
            assertMissing(next);
        }
        deleteStack(stack);
        assertMissing(name);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementHonorsRetentionAndDeletesItsCurrentBackingAlarm(boolean retain) throws Exception {
        String stack = unique("composite-replace");
        String original = stack + "-old";
        String next = stack + "-new";
        deploy("CreateStack", stack, template(original, "TRUE", Map.of(), false, retain));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        deploy("UpdateStack", stack, template(next, "FALSE", Map.of(), false, retain));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("FALSE", alarm(next).get("AlarmRule"));
        if (retain) {
            assertEquals("TRUE", alarm(original).get("AlarmRule"));
        } else {
            assertMissing(original);
        }
        deleteStack(stack);
        assertMissing(next);
        if (retain) {
            assertEquals("TRUE", alarm(original).get("AlarmRule"));
            cw("DeleteAlarms", Map.of("AlarmNames", List.of(original))).then().statusCode(200);
        }
    }

    @Test
    void committedInPlaceUpdatesAllowAnotherUpdateAndReplacement() throws Exception {
        String stack = unique("composite-sequence");
        String name = stack + "-alarm";
        String replacement = name + "-new";
        deploy("CreateStack", stack, template(name, "TRUE", Map.of("team", "initial"), false, false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        for (String rule : List.of("FALSE", "TRUE")) {
            deploy("UpdateStack", stack, template(name, rule, Map.of("team", rule), false, false));
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
            assertEquals(rule, alarm(name).get("AlarmRule"));
        }
        deploy("UpdateStack", stack, template(replacement, "FALSE", Map.of(), false, false));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertMissing(name);
        assertEquals("FALSE", alarm(replacement).get("AlarmRule"));
        deleteStack(stack);
        assertMissing(replacement);
    }

    @Test
    void createCollisionCannotOverwriteOrDeleteAnUnmanagedAlarm() throws Exception {
        String stack = unique("composite-foreign");
        String name = stack + "-alarm";
        cw("PutCompositeAlarm", Map.of("AlarmName", name, "AlarmRule", "TRUE")).then().statusCode(200);
        deploy("CreateStack", stack, template(name, "FALSE", Map.of(), false, false));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("TRUE", alarm(name).get("AlarmRule"));
        deleteStack(stack);
        assertEquals("TRUE", alarm(name).get("AlarmRule"));
        cw("DeleteAlarms", Map.of("AlarmNames", List.of(name))).then().statusCode(200);
    }

    private String template(String name, String rule, Map<String, String> tags, boolean fail, boolean retain)
            throws JsonProcessingException {
        cleanup.register(() -> cw("DeleteAlarms", Map.of("AlarmNames", List.of(name))).then().statusCode(200));
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("Alarm", Map.of("Type", "AWS::CloudWatch::CompositeAlarm",
                "UpdateReplacePolicy", retain ? "Retain" : "Delete",
                "Properties", Map.of("AlarmName", name, "AlarmRule", rule,
                        "Tags", tags.entrySet().stream().map(e -> Map.of("Key", e.getKey(), "Value", e.getValue())).toList())));
        if (fail) {
            resources.put("Failure", Map.of("Type", "AWS::SecretsManager::Secret", "DependsOn", "Alarm",
                    "Properties", Map.of("SecretString", "explicit", "GenerateSecretString", Map.of("PasswordLength", 32))));
        }
        return MAPPER.writeValueAsString(Map.of("Resources", resources, "Outputs", Map.of(
                "Name", Map.of("Value", Map.of("Ref", "Alarm")),
                "Arn", Map.of("Value", Map.of("Fn::GetAtt", List.of("Alarm", "Arn"))))));
    }

    private void deploy(String action, String stack, String template) {
        if ("CreateStack".equals(action)) {
            cleanup.register(() -> given().port(baseUri.getPort())
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteStack").formParam("StackName", stack)
                    .post("/").then().statusCode(200));
        }
        given().contentType("application/x-www-form-urlencoded").formParam("Action", action)
                .formParam("StackName", stack).formParam("TemplateBody", template).post("/").then().statusCode(200);
    }

    private Response cw(String operation, Map<String, ?> body) {
        if ("PutCompositeAlarm".equals(operation)) {
            String name = body.get("AlarmName").toString();
            cleanup.register(() -> cw("DeleteAlarms", Map.of("AlarmNames", List.of(name))).then().statusCode(200));
        }
        return given().port(baseUri.getPort()).contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "GraniteServiceVersion20100801." + operation)
                .body(MAPPER.valueToTree(body).toString()).post("/");
    }

    private Map<String, Object> alarm(String name) throws JsonProcessingException {
        List<Map<String, Object>> alarms = cw("DescribeAlarms", Map.of("AlarmNames", List.of(name),
                "AlarmTypes", List.of("CompositeAlarm"))).then().statusCode(200)
                .extract().jsonPath().getList("CompositeAlarms");
        assertEquals(1, alarms.size(), name);
        return alarms.getFirst();
    }

    private void assertMissing(String name) throws JsonProcessingException {
        assertEquals(List.of(), cw("DescribeAlarms", Map.of("AlarmNames", List.of(name),
                "AlarmTypes", List.of("CompositeAlarm"))).then().statusCode(200)
                .extract().jsonPath().getList("CompositeAlarms"));
    }

    private void deleteStack(String stack) {
        given().contentType("application/x-www-form-urlencoded").formParam("Action", "DeleteStack")
                .formParam("StackName", stack).post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }
}
