package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs the Bedrock Runtime {@code proxy} backend against a stand-in OpenAI-compatible server on
 * port {@value #PORT}, for the classes that test the proxy. Quarkus builds the application once
 * per distinct profile class, so they share this one rather than each declaring their own. Each
 * class starts its own stand-in on that port and stops it when its test ends.
 *
 * <p>The overrides, all under {@code floci.services.bedrock-runtime}, and who relies on them:
 * <ul>
 *   <li>{@code backend=proxy}, with {@code proxy.url} on port {@value #PORT}, sends Converse to the
 *   stand-in. Every class here relies on it.</li>
 *   <li>{@code proxy.api-key=test-key} is sent as a bearer token.
 *   {@code BedrockProxyIntegrationTest} relies on it.</li>
 *   <li>{@code proxy.model-mapping} maps {@value #MAPPED_MODEL_ID} to {@code claude-3-haiku}.
 *   {@code BedrockProxyIntegrationTest} relies on it.</li>
 *   <li>{@code proxy.passthrough=true} forwards a model id with no mapping as it is.
 *   {@code BedrockProxyPassthroughIntegrationTest}, {@code BedrockProxyCancellationIntegrationTest}
 *   and {@code BedrockProxyLoggingIntegrationTest} rely on it.</li>
 *   <li>{@code proxy.error-body-timeout-millis=10} shortens the deadline for collecting an
 *   upstream error body, so the stalled-error-body tests do not each wait the one-second default.
 *   {@code BedrockProxyLoggingIntegrationTest} relies on it.</li>
 * </ul>
 *
 * <p>Each override is inert for the classes that do not rely on it. Only the stand-in in
 * {@code BedrockProxyIntegrationTest} reads the {@code Authorization} header. The other classes
 * call model ids the mapping does not name, and every call in {@code BedrockProxyIntegrationTest}
 * names the mapped id, which the mapping resolves before passthrough is consulted. Their stand-ins
 * write each error body whole, so the shorter error-body deadline never fires for them.
 *
 * <p>{@code BedrockProxyTimeoutIntegrationTest} stays apart. Its one-second request timeout would
 * also close an upstream that stalls before its headers, so the cancellation tests would pass
 * without the disconnect handling they check. It also holds the test that needs passthrough off.
 */
public class BedrockProxyBackendProfile implements QuarkusTestProfile {

    public static final int PORT = 18930;
    public static final String MAPPED_MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.bedrock-runtime.backend", "proxy",
                "floci.services.bedrock-runtime.proxy.url", "http://127.0.0.1:" + PORT + "/v1",
                "floci.services.bedrock-runtime.proxy.api-key", "test-key",
                "floci.services.bedrock-runtime.proxy.model-mapping", MAPPED_MODEL_ID + "=claude-3-haiku",
                "floci.services.bedrock-runtime.proxy.passthrough", "true",
                "floci.services.bedrock-runtime.proxy.error-body-timeout-millis", "10");
    }
}
