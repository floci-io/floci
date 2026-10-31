package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BedrockAgentCoreArnsTest {

    @Test
    void resourceDropsTheQualifier() {
        assertEquals("agent/abc",
                BedrockAgentCoreArns.resource("arn:aws:bedrock-agentcore:us-east-1:123456789012:agent/abc:7"));
    }

    @Test
    void resourceWithoutAQualifierIsKeptWhole() {
        assertEquals("memory/m-1",
                BedrockAgentCoreArns.resource("arn:aws-us-gov:bedrock-agentcore:us-gov-west-1:123456789012:memory/m-1"));
    }

    @Test
    void resourceIsNullForAnythingThatIsNotAnArn() {
        assertNull(BedrockAgentCoreArns.resource(null));
        assertNull(BedrockAgentCoreArns.resource("agent/abc"));
        assertNull(BedrockAgentCoreArns.resource("arn:aws:bedrock-agentcore:us-east-1"));
    }
}
