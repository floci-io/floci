package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stack with the three AgentCore control-plane types, wired the way a CDK app wires them: the
 * runtime reads the memory id, the endpoint reads the runtime id, and an IAM policy scopes itself
 * to the memory ARN through {@code Fn::GetAtt}, which fails the stack when it cannot resolve.
 *
 * <p>The second stack exercises the in-place update path: an update that changes every mutable
 * property next to a resource that cannot be created is rolled back onto the entities, and a
 * later update that drops optional properties clears them, because the template is the whole
 * declared configuration.
 */
@QuarkusTest
class CloudFormationBedrockAgentCoreIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261006/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261006/us-east-1/iam/aws4_request";
    private static final String STACK = "cfn-bedrock-agentcore-integration";
    private static final String ROLLBACK_STACK = "cfn-bedrock-agentcore-rollback";
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

    /**
     * A memory, a runtime and an endpoint whose optional properties are all driven by the
     * arguments: a null description leaves the property out of the template. {@code failing} adds a
     * resource whose create is rejected, ordered after the endpoint so the update rolls back only
     * once all three were updated.
     */
    private static String rollbackTemplate(String memoryDescription, String runtimeDescription,
                                           String endpointDescription, String tagValue, boolean failing) {
        String failingResource = failing
                ? """
                ,
                "Boom": {"Type": "AWS::SecretsManager::Secret", "DependsOn": "Endpoint",
                  "Properties": {"SecretString": "x",
                  "GenerateSecretString": {"SecretStringTemplate": "{}", "GenerateStringKey": "p"}}}"""
                : "";
        return """
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
                        "Name": "cfn_rollback_memory",
                        "EventExpiryDuration": 30%s,
                        "Tags": {"team": "%s"}
                      }
                    },
                    "Runtime": {
                      "Type": "AWS::BedrockAgentCore::Runtime",
                      "Properties": {
                        "AgentRuntimeName": "cfn_rollback_runtime",
                        "AgentRuntimeArtifact": {"ContainerConfiguration": {"ContainerUri": "%s"}},
                        "RoleArn": {"Fn::GetAtt": ["Role", "Arn"]}%s,
                        "Tags": {"team": "%s"}
                      }
                    },
                    "Endpoint": {
                      "Type": "AWS::BedrockAgentCore::RuntimeEndpoint",
                      "Properties": {
                        "AgentRuntimeId": {"Fn::GetAtt": ["Runtime", "AgentRuntimeId"]},
                        "Name": "prod"%s
                      }
                    }%s
                  },
                  "Outputs": {
                    "MemoryId": {"Value": {"Fn::GetAtt": ["Memory", "MemoryId"]}},
                    "MemoryArn": {"Value": {"Fn::GetAtt": ["Memory", "MemoryArn"]}},
                    "RuntimeId": {"Value": {"Fn::GetAtt": ["Runtime", "AgentRuntimeId"]}},
                    "RuntimeArn": {"Value": {"Fn::GetAtt": ["Runtime", "AgentRuntimeArn"]}}
                  }
                }
                """.formatted(descriptionProperty(memoryDescription), tagValue, CONTAINER_URI,
                descriptionProperty(runtimeDescription), tagValue, descriptionProperty(endpointDescription),
                failingResource);
    }

    private static String descriptionProperty(String description) {
        return description == null ? "" : ",\n\"Description\": \"" + description + "\"";
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateReplaceAndDeleteAgentCoreResources() {
        cloudFormation(STACK, "CreateStack", TEMPLATE.formatted("cfn_agent_memory", 30, CONTAINER_URI, "first"));
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
        assertTrue(runtimeArn.matches("arn:aws:bedrock-agentcore:us-east-1:000000000000:runtime/[a-zA-Z][a-zA-Z0-9_]{0,47}-[a-zA-Z0-9]{10}"),
                runtimeArn);
        assertEquals("1", outputs.get("RuntimeVersion"));
        assertEquals("READY", outputs.get("RuntimeStatus"));
        assertTrue(outputs.get("WorkloadIdentityArn").contains(":workload-identity-directory/default/workload-identity/"),
                outputs.get("WorkloadIdentityArn"));

        String endpointArn = outputs.get("EndpointArn");
        assertEquals(endpointArn, outputs.get("EndpointRef"));
        assertEquals(runtimeArn + "/runtime-endpoint/prod", endpointArn);
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
        cloudFormation(STACK, "UpdateStack", TEMPLATE.formatted("cfn_agent_memory", 60, CONTAINER_URI, "second"));
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
        cloudFormation(STACK, "UpdateStack", TEMPLATE.formatted("cfn_agent_memory_v2", 60, CONTAINER_URI, "second"));
        Map<String, String> replaced = outputs("UPDATE_COMPLETE");
        String replacementArn = replaced.get("MemoryRef");
        assertNotEquals(memoryArn, replacementArn);
        assertEquals(runtimeId, replaced.get("RuntimeRef"));
        describeMemory(replaced.get("MemoryId")).statusCode(200).body("memory.name", equalTo("cfn_agent_memory_v2"));
        describeMemory(memoryId).statusCode(404);
        describeRuntime(runtimeId).statusCode(200)
                .body("environmentVariables.MEMORY_ID", equalTo(replaced.get("MemoryId")));
        assertTrue(getRolePolicy(outputs.get("RoleName"), "memory-access").contains(replacementArn));

        cloudFormation(STACK, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);
        describeMemory(replaced.get("MemoryId")).statusCode(404);
        describeRuntime(runtimeId).statusCode(404);
    }

    @Test
    void inPlaceChangesNextToAFailingResourceAreRolledBackAndOmittedPropertiesAreCleared() {
        cloudFormation(ROLLBACK_STACK, "CreateStack",
                rollbackTemplate("memory one", "runtime one", "endpoint one", "agents", false));
        Map<String, String> outputs = outputs(ROLLBACK_STACK, "CREATE_COMPLETE");
        String memoryId = outputs.get("MemoryId");
        String memoryArn = outputs.get("MemoryArn");
        String runtimeId = outputs.get("RuntimeId");
        String runtimeArn = outputs.get("RuntimeArn");

        // Every mutable property changes in place, then a sibling fails the update: the runtime
        // is restored as a further version (as an UpdateAgentRuntime rollback is on AWS), and the
        // memory, the endpoint and both tag sets carry what the stack last committed.
        cloudFormation(ROLLBACK_STACK, "UpdateStack",
                rollbackTemplate("memory two", "runtime two", "endpoint two", "ml", true));
        Map<String, String> rolledBack = outputs(ROLLBACK_STACK, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(runtimeId, rolledBack.get("RuntimeId"));
        assertEquals(memoryArn, rolledBack.get("MemoryArn"));
        describeRuntime(runtimeId).statusCode(200)
                .body("agentRuntimeVersion", equalTo("3"))
                .body("description", equalTo("runtime one"));
        given().when().get("/tags/" + runtimeArn).then().statusCode(200).body("tags.team", equalTo("agents"));
        describeMemory(memoryId).statusCode(200).body("memory.description", equalTo("memory one"));
        given().when().get("/tags/" + memoryArn).then().statusCode(200).body("tags.team", equalTo("agents"));
        describeEndpoint(runtimeId, "prod").statusCode(200).body("description", equalTo("endpoint one"));

        // Dropping the descriptions from the template clears them: the template is the whole
        // declared configuration, so an omitted property is not left over from the last update.
        cloudFormation(ROLLBACK_STACK, "UpdateStack", rollbackTemplate(null, null, null, "agents", false));
        outputs(ROLLBACK_STACK, "UPDATE_COMPLETE");
        describeRuntime(runtimeId).statusCode(200)
                .body("agentRuntimeVersion", equalTo("4"))
                .body("$", not(hasKey("description")));
        describeMemory(memoryId).statusCode(200).body("memory", not(hasKey("description")));
        describeEndpoint(runtimeId, "prod").statusCode(200).body("$", not(hasKey("description")));

        cloudFormation(ROLLBACK_STACK, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(ROLLBACK_STACK);
        describeMemory(memoryId).statusCode(404);
        describeRuntime(runtimeId).statusCode(404);
    }

    private static void cloudFormation(String stack, String action, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static Map<String, String> outputs(String expectedStatus) {
        return outputs(STACK, expectedStatus);
    }

    private static Map<String, String> outputs(String stack, String expectedStatus) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
        assertEquals(expectedStatus, state.status(), state.reason());
        String xml = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
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
