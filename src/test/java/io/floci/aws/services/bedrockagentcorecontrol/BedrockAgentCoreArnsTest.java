package io.floci.aws.services.bedrockagentcorecontrol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BedrockAgentCoreArnsTest {

    @Test
    void resourceDropsTheQualifier() {
        assertEquals("runtime/test-AbCdEfGhIj",
                BedrockAgentCoreArns.resource("arn:aws:bedrock-agentcore:us-east-1:123456789012:runtime/test-AbCdEfGhIj:7"));
    }

    @Test
    void resourceWithoutAQualifierIsKeptWhole() {
        assertEquals("memory/m-1",
                BedrockAgentCoreArns.resource("arn:aws-us-gov:bedrock-agentcore:us-gov-west-1:123456789012:memory/m-1"));
    }

    @Test
    void resourceIsNullForAnythingThatIsNotAnArn() {
        assertNull(BedrockAgentCoreArns.resource(null));
        assertNull(BedrockAgentCoreArns.resource("runtime/test-AbCdEfGhIj"));
        assertNull(BedrockAgentCoreArns.resource("arn:aws:bedrock-agentcore:us-east-1"));
    }
}
