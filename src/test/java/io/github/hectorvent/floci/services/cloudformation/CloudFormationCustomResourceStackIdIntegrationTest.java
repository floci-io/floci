package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.lambda.LambdaService;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every request CloudFormation sends a custom resource carries the {@code StackId} of the stack that
 * contains it, the value {@code Ref AWS::StackId} returns. Handlers use it to tie what they create to
 * the owning stack, so a made-up id breaks them.
 *
 * <p>The ServiceToken Lambda is mocked, as {@code SecretsManagerRotationIntegrationTest} mocks its
 * rotation Lambda, so no Docker is needed. The mock answers like a real handler: it PUTs its result to
 * the event's {@code ResponseURL}, returning the event's {@code StackId} as {@code Data}.
 */
@QuarkusTest
class CloudFormationCustomResourceStackIdIntegrationTest {

    private static final String SERVICE_TOKEN =
            "arn:aws:lambda:us-east-1:000000000000:function:stack-id-probe";

    private CustomResourceProbe probe;

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubHandler() {
        probe = CustomResourceProbe.stub(lambdaService, SERVICE_TOKEN, "stack-id-probe")
                .data(event -> Map.of("EventStackId", event.get("StackId").asText()));
    }

    @Test
    void customResourceRequestsCarryTheStacksOwnStackId() throws InterruptedException {
        String stackName = "cr-stack-id-" + Long.toString(System.nanoTime(), 36);

        String stackId = createStack(stackName, probeTemplate("1"));
        String created = awaitStackStatus(stackId, "CREATE_COMPLETE");
        assertEquals(stackId, XmlParser.extractFirst(created, "StackId", null));
        assertEquals(stackId, outputValue(created, "RefStackId"));
        assertEquals(stackId, outputValue(created, "EventStackId"));

        updateStack(stackName, probeTemplate("2"));
        String updated = awaitStackStatus(stackId, "UPDATE_COMPLETE");
        assertEquals(stackId, outputValue(updated, "EventStackId"));

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");

        assertEquals(List.of("Create", "Update", "Delete"),
                probe.events().stream().map(e -> e.get("RequestType").asText()).toList());
        for (JsonNode event : probe.events()) {
            assertEquals(stackId, event.get("StackId").asText(), event.toString());
        }
        // What a handler compares: the property built from Ref AWS::StackId against the event.
        assertEquals(probe.events().get(0).get("StackId").asText(),
                probe.events().get(0).get("ResourceProperties").get("OwningStackArn").asText());
    }

    @Test
    void customResourceInANestedStackGetsTheNestedStacksStackId() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cr-stack-id-templates-" + suffix;
        String childKey = "child-" + suffix + ".json";
        given().when().put("/" + bucket).then().statusCode(200);
        given().contentType("application/json").body(probeTemplate("1"))
        .when().put("/" + bucket + "/" + childKey).then().statusCode(200);

        String parentTemplate = """
                {
                  "Resources": {
                    "Child": {
                      "Type": "AWS::CloudFormation::Stack",
                      "Properties": {"TemplateURL": "http://localhost/%s/%s"}
                    }
                  },
                  "Outputs": {
                    "ChildStackId": {"Value": {"Ref": "Child"}},
                    "ChildEventStackId": {"Value": {"Fn::GetAtt": ["Child", "Outputs.EventStackId"]}}
                  }
                }
                """.formatted(bucket, childKey);
        String parentStackId = createStack("cr-nested-stack-id-" + suffix, parentTemplate);
        String parent = awaitStackStatus(parentStackId, "CREATE_COMPLETE");

        String childStackId = outputValue(parent, "ChildStackId");
        assertNotEquals(parentStackId, childStackId);
        assertEquals(childStackId, outputValue(parent, "ChildEventStackId"));
        String child = awaitStackStatus(childStackId, "CREATE_COMPLETE");
        assertEquals(childStackId, outputValue(child, "RefStackId"));
        assertEquals(1, probe.events().size());
        assertEquals(childStackId, probe.events().get(0).get("StackId").asText());
    }

    private static String probeTemplate(String revision) {
        return """
                {
                  "Resources": {
                    "Probe": {
                      "Type": "Custom::StackIdProbe",
                      "Properties": {
                        "ServiceToken": "%s",
                        "OwningStackArn": {"Ref": "AWS::StackId"},
                        "Revision": "%s"
                      }
                    }
                  },
                  "Outputs": {
                    "EventStackId": {"Value": {"Fn::GetAtt": ["Probe", "EventStackId"]}},
                    "RefStackId": {"Value": {"Ref": "AWS::StackId"}}
                  }
                }
                """.formatted(SERVICE_TOKEN, revision);
    }

    private static String createStack(String stackName, String template) {
        String xml = cfn(Map.of("Action", "CreateStack", "StackName", stackName, "TemplateBody", template));
        return XmlParser.extractFirst(xml, "StackId", null);
    }

    private static void updateStack(String stackName, String template) {
        cfn(Map.of("Action", "UpdateStack", "StackName", stackName, "TemplateBody", template));
    }

    private static void deleteStack(String stackName) {
        cfn(Map.of("Action", "DeleteStack", "StackName", stackName));
    }

    /** Polls DescribeStacks by stack id, which also answers for a deleted stack, and returns its XML. */
    private static String awaitStackStatus(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = cfn(Map.of("Action", "DescribeStacks", "StackName", stackId));
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return xml;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
        return xml;
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
