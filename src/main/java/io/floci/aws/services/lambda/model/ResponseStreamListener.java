package io.floci.aws.services.lambda.model;

/**
 * Receives a response that the function streams, as the runtime sends it. Only an invocation that
 * carries a listener is streamed; every other response is buffered into its {@link InvokeResult}.
 */
public interface ResponseStreamListener {

    /** The runtime started the stream; {@code contentType} is the one it sent, or null. */
    void onStart(String contentType);

    void onChunk(byte[] chunk);
}
