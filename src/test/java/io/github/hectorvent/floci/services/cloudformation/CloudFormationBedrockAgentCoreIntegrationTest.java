package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stack with the three AgentCore control-plane types, wired the way a CDK app wires them: the
 * runtime reads the memory id, the endpoint reads the runtime id, and an IAM policy scopes itself
 * to the memory ARN through {@code Fn::GetAtt}, which fails the stack when it cannot resolve.
 */
@QuarkusTest
class CloudFormationBedrockAgentCoreIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261006/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261006/us-east-1/iam/aws4_request";
    private static final String STACK = "cfn-bedrock-agentcore-integration";
    private static final String CONTAINER_URI = "000000000000.dkr.ecr.us-east-1.amazonaws.com/cfn-agent:latest";

    private static final String TEMPLATE = """
            {
              "Resources": {
                "Role": {
                  "Type": "AWS::IAM::Role",
                  "Properties": {
                    "AssumeRolePolicyDocument": {
                      "Version": "2012-10-17",
                      "Statement": [{"Effect": "Allow",
                        "Principal": {"Service": "bedrock-agentcore.amazonaws.com"}, "Action": "sts:AssumeRole"}]
                    }
                  }
                },
                "Memory": {
                  "Type": "AWS::BedrockAgentCore::Memory",
                  "Properties": {
                    "Name": "%s",
                    "EventExpiryDuration": %d,
                    "Description": "agent memory",
                    "MemoryExecutionRoleArn": {"Fn::GetAtt": ["Role", "Arn"]},
                    "Tags": {"team": "agents"}
                  }
                },
                "MemoryPolicy": {
                  "Type": "AWS::IAM::Policy",
                  "Properties": {
                    "PolicyName": "memory-access",
                    "Roles": [{"Ref": "Role"}],
                    "PolicyDocument": {
                      "Version": "2012-10-17",
                      "Statement": [{"Effect": "Allow", "Action": ["bedrock-agentcore:CreateEvent"],
                        "Resource": {"Fn::GetAtt": ["Memory", "MemoryArn"]}}]
                    }
                  }
                },
                "Runtime": {
                  "Type": "AWS::BedrockAgentCore::Runtime",
                  "Properties": {
                    "AgentRuntimeName": "cfn_agent_runtime",
                    "AgentRuntimeArtifact": {"ContainerConfiguration": {"ContainerUri": "%s"}},
                    "RoleArn": {"Fn::GetAtt": ["Role", "Arn"]},
                    "NetworkConfiguration": {"NetworkMode": "PUBLIC"},
                    "ProtocolConfiguration": "HTTP",
                    "EnvironmentVariables": {"MEMORY_ID": {"Fn::GetAtt": ["Memory", "MemoryId"]}},
                    "Description": "%s",
                    "Tags": {"team": "agents"}
                  }
                },
                "Endpoint": {
                  "Type": "AWS::BedrockAgentCore::RuntimeEndpoint",
                  "Properties": {
                    "AgentRuntimeId": {"Fn::GetAtt": ["Runtime", "AgentRuntimeId"]},
                    "Name": "prod",
                    "Description": "production endpoint"
                  }
                }
              },
              "Outputs": {
                "RoleArn": {"Value": {"Fn::GetAtt": ["Role", "Arn"]}},
                "RoleName": {"Value": {"Ref": "Role"}},
                "MemoryRef": {"Value": {"Ref": "Memory"}},
                "MemoryArn": {"Value": {"Fn::GetAtt": ["Memory", "MemoryArn"]}},
                "MemoryId": {"Value": {"Fn::GetAtt": ["Memory", "MemoryId"]}},
                "MemoryStatus": {"Value": {"Fn::GetAtt": ["Memory", "Status"]}},
                "RuntimeRef": {"Value": {"Ref": "Runtime"}},
                "RuntimeArn": {"Value": {"Fn::GetAtt": ["Runtime", "AgentRuntimeArn"]}},
                "RuntimeId": {"Value": {"Fn::GetAtt": ["Runtime", "AgentRuntimeId"]}},
                "RuntimeVersion": {"Value": {"Fn::GetAtt": ["Runtime", "AgentRuntimeVersion"]}},
                "RuntimeStatus": {"Value": {"Fn::GetAtt": ["Runtime", "Status"]}},
                "WorkloadIdentityArn": {"Value": {"Fn::GetAtt": ["Runtime", "WorkloadIdentityDetails.WorkloadIdentityArn"]}},
                "EndpointRef": {"Value": {"Ref": "Endpoint"}},
                "EndpointArn": {"Value": {"Fn::GetAtt": ["Endpoint", "AgentRuntimeEndpointArn"]}},
                "EndpointId": {"Value": {"Fn::GetAtt": ["Endpoint", "Id"]}},
                "EndpointRuntimeArn": {"Value": {"Fn::GetAtt": ["Endpoint", "AgentRuntimeArn"]}},
                "EndpointTargetVersion": {"Value": {"Fn::GetAtt": ["Endpoint", "TargetVersion"]}},
                "EndpointStatus": {"Value": {"Fn::GetAtt": ["Endpoint", "Status"]}}
              }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateReplaceAndDeleteAgentCoreResources() {
        cloudFormation("CreateStack", TEMPLATE.formatted("cfn_agent_memory", 30, CONTAINER_URI, "first"));
        Map<String, String> outputs = outputs("CREATE_COMPLETE");

        // Ref follows each registry schema's primaryIdentifier.
        String memoryArn = outputs.get("MemoryArn");
        String memoryId = outputs.get("MemoryId");
        assertEquals(memoryArn, outputs.get("MemoryRef"));
        assertEquals("arn:aws:bedrock-agentcore:us-east-1:000000000000:memory/" + memoryId, memoryArn);
        assertEquals("ACTIVE", outputs.get("MemoryStatus"));

        String runtimeId = outputs.get("RuntimeId");
        String runtimeArn = outputs.get("RuntimeArn");
        assertEquals(runtimeId, outputs.get("RuntimeRef"));
        assertTrue(runtimeId.startsWith("cfn_agent_runtime-"), runtimeId);
        assertTrue(runtimeArn.matches("arn:aws:bedrock-agentcore:us-east-1:000000000000:agent/[0-9a-f-]{36}:1"),
                runtimeArn);
        assertEquals("1", outputs.get("RuntimeVersion"));
        assertEquals("READY", outputs.get("RuntimeStatus"));
        assertTrue(outputs.get("WorkloadIdentityArn").contains(":workload-identity-directory/default/workload-identity/"),
                outputs.get("WorkloadIdentityArn"));

        String endpointArn = outputs.get("EndpointArn");
        assertEquals(endpointArn, outputs.get("EndpointRef"));
        assertTrue(endpointArn.startsWith("arn:aws:bedrock-agentcore:us-east-1:000000000000:agentEndpoint/"), endpointArn);
        assertEquals(runtimeArn, outputs.get("EndpointRuntimeArn"));
        assertEquals("1", outputs.get("EndpointTargetVersion"));
        assertEquals("READY", outputs.get("EndpointStatus"));

        // The resources exist with the template's properties, as the AgentCore API reports them.
        describeMemory(memoryId)
                .statusCode(200)
                .body("memory.arn", equalTo(memoryArn))
                .body("memory.name", equalTo("cfn_agent_memory"))
                .body("memory.eventExpiryDuration", equalTo(30))
                .body("memory.description", equalTo("agent memory"))
                .body("memory.memoryExecutionRoleArn", equalTo(outputs.get("RoleArn")));
        given().when().get("/tags/" + memoryArn).then().statusCode(200).body("tags.team", equalTo("agents"));

        describeRuntime(runtimeId)
                .statusCode(200)
                .body("agentRuntimeArn", equalTo(runtimeArn))
                .body("agentRuntimeName", equalTo("cfn_agent_runtime"))
                .body("agentRuntimeVersion", equalTo("1"))
                .body("roleArn", equalTo(outputs.get("RoleArn")))
                .body("description", equalTo("first"))
                .body("agentRuntimeArtifact.containerConfiguration.containerUri", equalTo(CONTAINER_URI))
                .body("networkConfiguration.networkMode", equalTo("PUBLIC"))
                .body("protocolConfiguration.serverProtocol", equalTo("HTTP"))
                .body("environmentVariables.MEMORY_ID", equalTo(memoryId))
                .body("workloadIdentityDetails.workloadIdentityArn", equalTo(outputs.get("WorkloadIdentityArn")));
        given().when().get("/tags/" + runtimeArn).then().statusCode(200).body("tags.team", equalTo("agents"));

        describeEndpoint(runtimeId, "prod")
                .statusCode(200)
                .body("agentRuntimeEndpointArn", equalTo(endpointArn))
                .body("id", equalTo(outputs.get("EndpointId")))
                .body("targetVersion", equalTo("1"))
                .body("description", equalTo("production endpoint"));

        // The policy scoped itself to the real memory ARN through Fn::GetAtt.
        String storedPolicy = getRolePolicy(outputs.get("RoleName"), "memory-access");
        assertTrue(storedPolicy.contains(memoryArn), "expected the memory ARN in the stored policy: " + storedPolicy);

        // An update that keeps every create-only property changes the entities in place: the
        // memory and runtime keep their ids, the runtime publishes a new version, and the
        // untouched endpoint is left alone.
        cloudFormation("UpdateStack", TEMPLATE.formatted("cfn_agent_memory", 60, CONTAINER_URI, "second"));
        Map<String, String> updated = outputs("UPDATE_COMPLETE");
        assertEquals(memoryArn, updated.get("MemoryRef"));
        assertEquals(runtimeId, updated.get("RuntimeRef"));
        assertEquals(endpointArn, updated.get("EndpointRef"));
        assertEquals("2", updated.get("RuntimeVersion"));
        assertEquals("1", updated.get("EndpointTargetVersion"));
        describeMemory(memoryId).statusCode(200).body("memory.eventExpiryDuration", equalTo(60));
        describeRuntime(runtimeId).statusCode(200)
                .body("agentRuntimeVersion", equalTo("2"))
                .body("description", equalTo("second"));
        describeEndpoint(runtimeId, "prod").statusCode(200).body("targetVersion", equalTo("1"));

        // Renaming the memory replaces it: a new ARN, the runtime re-pointed at the new id, the
        // policy re-scoped, and the displaced memory removed once the update commits.
        cloudFormation("UpdateStack", TEMPLATE.formatted("cfn_agent_memory_v2", 60, CONTAINER_URI, "second"));
        Map<String, String> replaced = outputs("UPDATE_COMPLETE");
        String replacementArn = replaced.get("MemoryRef");
        assertNotEquals(memoryArn, replacementArn);
        assertEquals(runtimeId, replaced.get("RuntimeRef"));
        describeMemory(replaced.get("MemoryId")).statusCode(200).body("memory.name", equalTo("cfn_agent_memory_v2"));
        describeMemory(memoryId).statusCode(404);
        describeRuntime(runtimeId).statusCode(200)
                .body("environmentVariables.MEMORY_ID", equalTo(replaced.get("MemoryId")));
        assertTrue(getRolePolicy(outputs.get("RoleName"), "memory-access").contains(replacementArn));

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);
        describeMemory(replaced.get("MemoryId")).statusCode(404);
        describeRuntime(runtimeId).statusCode(404);
    }

    private static void cloudFormation(String action, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", STACK);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static Map<String, String> outputs(String expectedStatus) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(STACK, CFN_AUTH);
        assertEquals(expectedStatus, state.status(), state.reason());
        String xml = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }

    private static ValidatableResponse describeMemory(String memoryId) {
        return given().when().get("/memories/" + memoryId + "/details").then();
    }

    private static ValidatableResponse describeRuntime(String runtimeId) {
        return given().when().get("/runtimes/" + runtimeId + "/").then();
    }

    private static ValidatableResponse describeEndpoint(String runtimeId, String name) {
        return given().when().get("/runtimes/" + runtimeId + "/runtime-endpoints/" + name + "/").then();
    }

    private static String getRolePolicy(String roleName, String policyName) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", "GetRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyName", policyName)
                .when().post("/").then().statusCode(200).extract().asString();
    }
}
