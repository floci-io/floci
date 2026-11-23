package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreControlService;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.floci.aws.testing.CfnRequests.cfn;
import static io.floci.aws.testing.CfnRequests.physicalIdUnauthenticated;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * A tag update of an AWS::BedrockAgentCore::RuntimeEndpoint whose own undo cannot complete leaves
 * the stack in UPDATE_ROLLBACK_FAILED, and ContinueUpdateRollback takes that undo up again from the
 * snapshot carried onto the restored resource.
 */
@QuarkusTest
class BedrockAgentCoreEndpointCfnUpdateRollbackRetryIntegrationTest {

    private static final String TEMPLATE = """
            {"Resources":{
              "Role":{"Type":"AWS::IAM::Role","Properties":{"AssumeRolePolicyDocument":{
                "Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"Service":"bedrock-agentcore.amazonaws.com"},"Action":"sts:AssumeRole"}]}}},
              "Runtime":{"Type":"AWS::BedrockAgentCore::Runtime","Properties":{
                "AgentRuntimeName":"cfn_endpoint_retry_runtime",
                "AgentRuntimeArtifact":{"ContainerConfiguration":{
                  "ContainerUri":"000000000000.dkr.ecr.us-east-1.amazonaws.com/cfn-agent:latest"}},
                "RoleArn":{"Fn::GetAtt":["Role","Arn"]}}},
              "Endpoint":{"Type":"AWS::BedrockAgentCore::RuntimeEndpoint","Properties":{
                "AgentRuntimeId":{"Fn::GetAtt":["Runtime","AgentRuntimeId"]},
                "Name":"prod","Tags":{"%s":"%s"}}}}}
            """;

    @InjectSpy
    BedrockAgentCoreControlService runtimeService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aRetryPutsBackTheTagsWhoseFirstRestoreFailed() {
        String stack = "agentcore-cfn-endpoint-retry-tags-it";
        try {
            stackAction(stack, "CreateStack", TEMPLATE.formatted("team", "agents"));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
            String endpointArn = physicalIdUnauthenticated(stack, "Endpoint");
            // The update removes the stored key, then both its tag write and the restore's fail.
            Mockito.doThrow(new AwsException("ThrottlingException", "Too Many Requests", 429))
                    .doThrow(new AwsException("ThrottlingException", "Too Many Requests", 429))
                    .doCallRealMethod()
                    .when(runtimeService).tagEndpointByArn(anyString(), eq(endpointArn), anyMap());

            stackAction(stack, "UpdateStack", TEMPLATE.formatted("owner", "ml"));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
            assertEquals("UPDATE_ROLLBACK_FAILED", state.status(), state.reason());
            given().when().get("/tags/" + endpointArn).then().statusCode(200).body("tags", anEmptyMap());

            CfnStackWaits.continueUpdateRollback(stack, null);
            state = CfnStackWaits.awaitTerminal(stack);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
            given().when().get("/tags/" + endpointArn).then().statusCode(200)
                    .body("tags.size()", equalTo(1))
                    .body("tags.team", equalTo("agents"));
        } finally {
            deleteStack(stack);
        }
    }

    private static void stackAction(String stack, String action, String template) {
        cfn(action, stack).formParam("TemplateBody", template).when().post("/").then().statusCode(200);
    }

    private static void deleteStack(String stack) {
        cfn("DeleteStack", stack).when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }
}
