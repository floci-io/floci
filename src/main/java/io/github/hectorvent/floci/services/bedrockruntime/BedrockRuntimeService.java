package io.github.hectorvent.floci.services.bedrockruntime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.bedrockruntime.backend.BedrockBackend;
import io.github.hectorvent.floci.services.bedrockruntime.backend.ProxyBackend;
import io.github.hectorvent.floci.services.bedrockruntime.backend.StubBackend;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Thin orchestration layer for Bedrock Runtime: delegates Converse/InvokeModel to the
 * backend selected by {@code floci.services.bedrock-runtime.backend}.
 */
@ApplicationScoped
public class BedrockRuntimeService {

    private static final Logger LOG = Logger.getLogger(BedrockRuntimeService.class);

    private final EmulatorConfig config;
    private final StubBackend stubBackend;
    private final ProxyBackend proxyBackend;

    @Inject
    public BedrockRuntimeService(EmulatorConfig config, StubBackend stubBackend, ProxyBackend proxyBackend) {
        this.config = config;
        this.stubBackend = stubBackend;
        this.proxyBackend = proxyBackend;
    }

    public ObjectNode buildConverseResponse(String modelId, ObjectNode bedrockRequest) {
        return backend().converse(modelId, bedrockRequest);
    }

    public byte[] buildInvokeModelResponse(String modelId, byte[] body) {
        return backend().invokeModel(modelId, body);
    }

    private BedrockBackend backend() {
        String backend = config.services().bedrockRuntime().backend();
        if ("proxy".equalsIgnoreCase(backend)) {
            return proxyBackend;
        }
        if (!"stub".equalsIgnoreCase(backend)) {
            LOG.warnv("Unrecognized floci.services.bedrock-runtime.backend value \"{0}\"; falling back to stub. "
                    + "Expected \"stub\" or \"proxy\".", backend);
        }
        return stubBackend;
    }
}
