package io.floci.aws.services.apigatewayv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsServer;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.services.apigateway.AwsServiceRouter;
import io.floci.aws.services.apigateway.VtlTemplateEngine;
import io.floci.aws.services.apigatewayv2.model.Integration;
import io.floci.aws.services.apigatewayv2.proxy.HttpProxyInvoker;
import io.floci.aws.services.apigatewayv2.proxy.HttpProxyInvoker.ProxyOptions;
import io.floci.aws.services.apigatewayv2.proxy.ProxyResult;
import io.floci.aws.services.apigatewayv2.proxy.RequestContext;
import io.floci.aws.services.apigatewayv2.websocket.WebSocketIntegrationInvoker;
import io.floci.aws.services.apigatewayv2.websocket.WebSocketIntegrationInvoker.IntegrationResult;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.testing.LocalHttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static io.floci.aws.testing.LocalHttpsServer.hookUrl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP and HTTP_PROXY integrations reach an https:// backend through Floci's outbound trust, so a certificate from
 * a CA in floci.tls.extra-ca-path is accepted, with TLS off.
 */
class ApiGatewayHttpsBackendTrustTest {

    @TempDir
    Path dir;

    private FlociCertificateAuthority privateCa;
    private HttpsServer backend;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startBackend() throws Exception {
        privateCa = FlociCertificateAuthority.loadOrCreate(dir.resolve("private-ca"));
        backend = LocalHttpsServer.hook(privateCa, exchange -> hits.incrementAndGet());
    }

    @AfterEach
    void stopBackend() {
        backend.stop(0);
    }

    /**
     * REST HTTP and HTTP_PROXY integrations and HTTP API HTTP_PROXY integrations call the backend through
     * HttpProxyInvoker. A backend whose certificate chains to a private CA answers 502 while only the JVM's roots
     * are trusted, and is reached once that CA is in the floci.tls.extra-ca-path file.
     */
    @Test
    void httpProxyReachesABackendWhoseCertificateChainsToAnExtraCa() {
        assertProxyRefused(Optional.empty());

        assertEquals(200, proxy(privateCaPath(), ProxyOptions.DEFAULTS).statusCode());
        assertEquals(1, hits.get());
    }

    @Test
    void httpProxyRefusesABackendWhoseCaIsNotInTheExtraCaFile() {
        assertProxyRefused(unrelatedCaPath());
    }

    /** insecureSkipVerification keeps its own client: a backend whose CA is not in the extra CA file is still reached. */
    @Test
    void insecureSkipVerificationStillReachesABackendOutsideTheExtraCas() {
        ProxyResult result = proxy(unrelatedCaPath(), new ProxyOptions(Duration.ofSeconds(30), true));

        assertEquals(200, result.statusCode());
        assertEquals(1, hits.get());
    }

    /** WebSocket API HTTP and HTTP_PROXY integrations post to an https:// backend through their own client. */
    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "HTTP_PROXY"})
    void webSocketIntegrationReachesABackendWhoseCertificateChainsToAnExtraCa(String integrationType) {
        assertWebSocketRefused(integrationType, Optional.empty());

        assertEquals(200, webSocket(integrationType, privateCaPath()).statusCode());
        assertEquals(1, hits.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "HTTP_PROXY"})
    void webSocketIntegrationRefusesABackendWhoseCaIsNotInTheExtraCaFile(String integrationType) {
        assertWebSocketRefused(integrationType, unrelatedCaPath());
    }

    // Asserts that the TLS handshake with the backend fails, so the integration answers 502 and the backend never
    // sees a request.
    private void assertProxyRefused(Optional<String> extraCaPath) {
        ProxyResult result = proxy(extraCaPath, ProxyOptions.DEFAULTS);
        String body = new String(result.body(), StandardCharsets.UTF_8);
        assertEquals(502, result.statusCode(), body);
        assertTrue(body.contains("PKIX path"), body);
        assertEquals(0, hits.get());
    }

    // As above, for a WebSocket integration, which reports the failure as a 500 naming its type.
    private void assertWebSocketRefused(String integrationType, Optional<String> extraCaPath) {
        IntegrationResult result = webSocket(integrationType, extraCaPath);
        assertEquals(500, result.statusCode(), result.functionError());
        assertTrue(result.functionError().startsWith(integrationType + " integration error: ")
                && result.functionError().contains("PKIX path"), result.functionError());
        assertEquals(0, hits.get());
    }

    // Calls the backend as an HTTP_PROXY integration, through an invoker built the way CDI builds it.
    private ProxyResult proxy(Optional<String> extraCaPath, ProxyOptions options) {
        Integration integration = integration("HTTP_PROXY");
        integration.setIntegrationMethod("GET");
        RequestContext ctx = new RequestContext("api1", "$default", "GET", "/hook", null, "GET /hook",
                "req-test", "127.0.0.1", Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of());
        return new HttpProxyInvoker(config(extraCaPath)).invoke(integration, ctx, options);
    }

    // Calls the backend as a WebSocket integration of integrationType, through an invoker built the way CDI builds it.
    private IntegrationResult webSocket(String integrationType, Optional<String> extraCaPath) {
        WebSocketIntegrationInvoker invoker = new WebSocketIntegrationInvoker(mock(LambdaService.class),
                mock(AwsServiceRouter.class), new ObjectMapper(), mock(VtlTemplateEngine.class), config(extraCaPath));
        return invoker.invoke("us-east-1", integration(integrationType), "{}", Map.of(), Map.of(), Map.of());
    }

    private Integration integration(String integrationType) {
        Integration integration = new Integration();
        integration.setIntegrationType(integrationType);
        integration.setIntegrationUri(hookUrl(backend));
        return integration;
    }

    // TLS off, with extraCaPath as the extra CA setting.
    private static EmulatorConfig config(Optional<String> extraCaPath) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(false);
        when(config.tls().extraCaPath()).thenReturn(extraCaPath);
        return config;
    }

    private Optional<String> privateCaPath() {
        return Optional.of(privateCa.certificatePath().toString());
    }

    private Optional<String> unrelatedCaPath() {
        return Optional.of(FlociCertificateAuthority.loadOrCreate(dir.resolve("unrelated-ca"))
                .certificatePath().toString());
    }
}
