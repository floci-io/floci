package io.github.hectorvent.floci.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Executes Bedrock Runtime Converse/InvokeModel requests against a concrete backend
 * (a hardcoded stub, or a proxy to a real OpenAI-compatible model endpoint).
 */
public interface BedrockBackend {

    ObjectNode converse(String modelId, ObjectNode bedrockRequest);

    byte[] invokeModel(String modelId, byte[] body);

    Consumer<OutputStream> converseStream(String modelId, ObjectNode bedrockRequest);
}
