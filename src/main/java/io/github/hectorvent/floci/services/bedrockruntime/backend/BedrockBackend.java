package io.github.hectorvent.floci.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Executes Bedrock Runtime Converse/InvokeModel requests against a concrete backend
 * (a hardcoded stub, or a proxy to a real OpenAI-compatible model endpoint).
 */
public interface BedrockBackend {

    ObjectNode converse(String modelId, ObjectNode bedrockRequest);

    byte[] invokeModel(String modelId, byte[] body);
}
