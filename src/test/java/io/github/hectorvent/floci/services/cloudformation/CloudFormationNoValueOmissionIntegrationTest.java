package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code AWS::NoValue} is an omission wherever a template property can hold it: the properties a
 * provisioner receives have had every {@code Fn::If} that chose it, and every list item or member
 * that is it, taken out. CloudFormation documents it as removing the property, so a stack that
 * writes {@code Fn::If [Off, x, AWS::NoValue]} must end as the stack that leaves the property out,
 * on create and on update, for a resource whose provisioner never looked for NoValue.
 */
@QuarkusTest
class CloudFormationNoValueOmissionIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";
    private static final String SERVICE_TOKEN =
            "arn:aws:lambda:us-east-1:000000000000:function:no-value-probe";
    private static final String NO_VALUE_IF = "{\"Fn::If\": [\"Off\", \"unused\", {\"Ref\": \"AWS::NoValue\"}]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private CustomResourceProbe probe;

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubHandler() {
        probe = CustomResourceProbe.stub(lambdaService, SERVICE_TOKEN, "no-value-probe");
    }

    @Test
    void accessKeyStatusChosenAsNoValueIsLeftOutOnCreate() {
        String stack = unique("nv-key-create");
        createStack(stack, accessKeyTemplate("Status", NO_VALUE_IF));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("Active", accessKeyStatus(stack));
    }

    @Test
    void accessKeyStatusChosenAsNoValueActsAsOmissionOnUpdate() {
        String dropped = unique("nv-key-dropped");
        String novalue = unique("nv-key-novalue");
        createStack(dropped, accessKeyTemplate("Status", "\"Inactive\""));
        createStack(novalue, accessKeyTemplate("Status", "\"Inactive\""));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(dropped).status());
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(novalue).status());

        updateStack(dropped, accessKeyTemplate(null, null));
        updateStack(novalue, accessKeyTemplate("Status", NO_VALUE_IF));

        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(dropped).status());
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(novalue);
        assertEquals("UPDATE_COMPLETE", state.status(), state.reason());
        assertEquals(accessKeyStatus(dropped), accessKeyStatus(novalue));
    }

    @Test
    void secretDescriptionChosenAsNoValueIsLeftOutOnCreate() {
        String stack = unique("nv-secret-create");
        createStack(stack, secretTemplate("Description", NO_VALUE_IF, stack));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertNull(secretDescription(stack));
    }

    @Test
    void secretDescriptionChosenAsNoValueActsAsOmissionOnUpdate() {
        String dropped = unique("nv-secret-dropped");
        String novalue = unique("nv-secret-novalue");
        createStack(dropped, secretTemplate("Description", "\"first\"", dropped));
        createStack(novalue, secretTemplate("Description", "\"first\"", novalue));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(dropped).status());
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(novalue).status());

        updateStack(dropped, secretTemplate(null, null, dropped));
        updateStack(novalue, secretTemplate("Description", NO_VALUE_IF, novalue));

        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(dropped).status());
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(novalue).status());
        assertEquals(secretDescription(dropped), secretDescription(novalue));
    }

    @Test
    void customResourcePropertiesOmitNoValueAtEveryDepthOnCreateAndUpdate() {
        String stack = unique("nv-custom");
        createStack(stack, probeTemplate("one"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        updateStack(stack, probeTemplate("two"));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());

        assertEquals(List.of("Create", "Update"),
                probe.events().stream().map(e -> e.get("RequestType").asText()).toList());
        assertEquals(expected("one"), probe.events().get(0).get("ResourceProperties"));
        assertEquals(expected("two"), probe.events().get(1).get("ResourceProperties"));
        assertEquals(expected("one"), probe.events().get(1).get("OldResourceProperties"));
    }

    private JsonNode expected(String revision) {
        ObjectNode properties = mapper.createObjectNode();
        properties.put("ServiceToken", SERVICE_TOKEN);
        properties.put("Revision", revision);
        properties.putObject("Nested").put("Kept", "yes");
        properties.putArray("Items").add("a").add("c");
        return properties;
    }

    private static String probeTemplate(String revision) {
        return """
                {
                  "Conditions": {"Off": {"Fn::Equals": ["a", "b"]}},
                  "Resources": {
                    "Probe": {
                      "Type": "Custom::NoValueProbe",
                      "Properties": {
                        "ServiceToken": "%s",
                        "Revision": "%s",
                        "Dropped": %s,
                        "Nested": {"Kept": "yes", "Dropped": %s},
                        "Items": ["a", %s, "c"]
                      }
                    }
                  }
                }
                """.formatted(SERVICE_TOKEN, revision, NO_VALUE_IF, NO_VALUE_IF, NO_VALUE_IF);
    }

    /** A key on a user; {@code property} is left out when null. */
    private static String accessKeyTemplate(String property, String value) {
        return """
                {
                  "Conditions": {"Off": {"Fn::Equals": ["a", "b"]}},
                  "Resources": {
                    "User": {"Type": "AWS::IAM::User"},
                    "Key": {
                      "Type": "AWS::IAM::AccessKey",
                      "Properties": {"UserName": {"Ref": "User"}%s}
                    }
                  },
                  "Outputs": {"UserName": {"Value": {"Ref": "User"}}}
                }
                """.formatted(property == null ? "" : ", \"" + property + "\": " + value);
    }

    /** A secret named after its stack; {@code property} is left out when null. */
    private static String secretTemplate(String property, String value, String name) {
        return """
                {
                  "Conditions": {"Off": {"Fn::Equals": ["a", "b"]}},
                  "Resources": {
                    "Secret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "Properties": {"Name": "%s", "SecretString": "s3cret"%s}
                    }
                  }
                }
                """.formatted(name, property == null ? "" : ", \"" + property + "\": " + value);
    }

    private static String accessKeyStatus(String stack) {
        String user = outputValue(describe(stack), "UserName");
        String xml = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "ListAccessKeys")
                .formParam("UserName", user)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractFirst(xml, "Status", null);
    }

    private static String secretDescription(String name) {
        return given()
                .header("X-Amz-Target", "secretsmanager.DescribeSecret")
                .contentType("application/x-amz-json-1.1")
                .body("{\"SecretId\": \"" + name + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Description");
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private static void createStack(String stackName, String template) {
        cfn(Map.of("Action", "CreateStack", "StackName", stackName, "TemplateBody", template));
    }

    private static void updateStack(String stackName, String template) {
        cfn(Map.of("Action", "UpdateStack", "StackName", stackName, "TemplateBody", template));
    }

    private static String describe(String stackName) {
        return cfn(Map.of("Action", "DescribeStacks", "StackName", stackName));
    }

    private static String cfn(Map<String, String> params) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParams(params)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().asString();
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
