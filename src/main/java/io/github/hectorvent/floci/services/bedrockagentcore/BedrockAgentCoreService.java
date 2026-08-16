package io.github.hectorvent.floci.services.bedrockagentcore;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;

/**
 * Data-plane stub for Amazon Bedrock AgentCore ({@code InvokeAgentRuntime}).
 *
 * <p>No real agent execution: returns a fixed, configurable response body for every
 * invocation. The payload is never parsed.
 */
@ApplicationScoped
public class BedrockAgentCoreService {

    private final byte[] cannedResponse;

    @Inject
    public BedrockAgentCoreService(EmulatorConfig config) {
        this.cannedResponse = config.services().bedrockAgentCore().invokeResponse()
                .getBytes(StandardCharsets.UTF_8);
    }

    public byte[] invoke() {
        return cannedResponse;
    }
}
