package io.floci.aws.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsServer;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.eventbridge.model.ApiDestination;
import io.floci.aws.services.eventbridge.model.Connection;
import io.floci.aws.services.eventbridge.model.Target;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.testing.LocalHttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventBridgeInvokerApiDestinationTest {

    private static final String REGION = "us-east-1";
    private static final String DESTINATION_ARN = "arn:aws:events:us-east-1:000000000000:api-destination/tls/1";
    private static final String CONNECTION_ARN = "arn:aws:events:us-east-1:000000000000:connection/tls/1";

    @Test
    void substitutePathParameters_replacesWildcardsInOrder() {
        assertEquals("https://api.example.com/a/one/b/two",
                EventBridgeInvoker.substitutePathParameters(
                        "https://api.example.com/a/*/b/*", List.of("one", "two")));
    }

    @Test
    void substitutePathParameters_encodesValuesAsSingleSegment() {
        assertEquals("https://api.example.com/x/a%2Fb%3Fc%23d%40e%20f",
                EventBridgeInvoker.substitutePathParameters(
                        "https://api.example.com/x/*", List.of("a/b?c#d@e f")));
    }

    @Test
    void substitutePathParameters_neverTouchesHostOrQuery() {
        assertEquals("https://*.example.com/p/v?q=*",
                EventBridgeInvoker.substitutePathParameters(
                        "https://*.example.com/p/*?q=*", List.of("v", "ignored")));
    }

    @Test
    void substitutePathParameters_withoutPathLeavesUrlUntouched() {
        assertEquals("https://*.example.com",
                EventBridgeInvoker.substitutePathParameters("https://*.example.com", List.of("v")));
    }

    @Test
    void substitutePathParameters_keepsExtraWildcardsWhenValuesRunOut() {
        assertEquals("https://api.example.com/one/*",
                EventBridgeInvoker.substitutePathParameters("https://api.example.com/*/*", List.of("one")));
    }

    @Test
    void appendQueryParameters_encodesAndJoinsExistingQuery() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("a b", "c&d");
        assertEquals("https://x.test/p?k=v&a+b=c%26d",
                EventBridgeInvoker.appendQueryParameters("https://x.test/p?k=v", params));
        assertEquals("https://x.test/p?a+b=c%26d",
                EventBridgeInvoker.appendQueryParameters("https://x.test/p", params));
    }

    @Test
    void appendQueryParameters_insertsTheQueryBeforeAFragment() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("a", "b");
        assertEquals("https://x.test/p?a=b#frag",
                EventBridgeInvoker.appendQueryParameters("https://x.test/p#frag", params));
        assertEquals("https://x.test/p?k=v&a=b#frag?x",
                EventBridgeInvoker.appendQueryParameters("https://x.test/p?k=v#frag?x", params));
    }

    @Test
    void appendQueryParameters_withoutParamsReturnsUrl() {
        assertEquals("https://x.test/p", EventBridgeInvoker.appendQueryParameters("https://x.test/p", Map.of()));
    }

    @Test
    void acquireRatePermit_neverGrantsMoreThanTheLimitToConcurrentDeliveries() throws Exception {
        EventBridgeInvoker invoker = new EventBridgeInvoker(mock(LambdaService.class), mock(SqsService.class),
                mock(SnsService.class), new ObjectMapper(), mock(EmulatorConfig.class));
        // More than the sweep threshold, so the sweep runs while the deliveries below are counting
        for (int i = 0; i < 300; i++) {
            invoker.acquireRatePermit(rateLimited("filler-" + i, 1));
        }
        ApiDestination destination = rateLimited("busy", 5);

        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<Future<?>> workers = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                workers.add(pool.submit(() -> {
                    start.await();
                    try {
                        invoker.acquireRatePermit(destination);
                        granted.incrementAndGet();
                    } catch (AwsException throttled) {
                        assertEquals("ThrottlingException", throttled.getErrorCode());
                    }
                    return null;
                }));
            }
            start.countDown();
            // get() rethrows a worker's failure, which awaiting the pool alone would not surface
            for (Future<?> worker : workers) {
                worker.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Two permits' worth of slack covers the one-second window rolling over mid-test
        assertTrue(granted.get() >= 5 && granted.get() <= 10, "granted " + granted.get());
    }

    /**
     * An endpoint whose certificate chains to a private CA is refused while only the JVM's roots are trusted, and
     * delivered to once that CA is in the floci.tls.extra-ca-path file, with TLS off as in the reported case.
     */
    @Test
    void deliversToAnEndpointWhoseCertificateChainsToAnExtraCa(@TempDir Path dir) throws Exception {
        FlociCertificateAuthority privateCa = FlociCertificateAuthority.loadOrCreate(dir.resolve("private-ca"));
        AtomicInteger delivered = new AtomicInteger();
        HttpsServer server = httpsEndpoint(privateCa, delivered);
        try {
            assertRefused(hookUrl(server), Optional.empty(), delivered);

            assertDoesNotThrow(() -> deliver(hookUrl(server), Optional.of(privateCa.certificatePath().toString())));
            assertEquals(1, delivered.get());
        } finally {
            server.stop(0);
        }
    }

    /** The extra CAs add trust; they do not loosen it: a CA the endpoint's certificate does not chain to is no help. */
    @Test
    void refusesAnEndpointWhoseCaIsNotInTheExtraCaFile(@TempDir Path dir) throws Exception {
        FlociCertificateAuthority privateCa = FlociCertificateAuthority.loadOrCreate(dir.resolve("private-ca"));
        FlociCertificateAuthority unrelatedCa = FlociCertificateAuthority.loadOrCreate(dir.resolve("unrelated-ca"));
        AtomicInteger delivered = new AtomicInteger();
        HttpsServer server = httpsEndpoint(privateCa, delivered);
        try {
            assertRefused(hookUrl(server), Optional.of(unrelatedCa.certificatePath().toString()), delivered);
        } finally {
            server.stop(0);
        }
    }

    // Asserts that delivering to endpoint fails the TLS handshake, so the endpoint never sees a request.
    private static void assertRefused(String endpoint, Optional<String> extraCaPath, AtomicInteger delivered) {
        AwsException refused = assertThrows(AwsException.class, () -> deliver(endpoint, extraCaPath));
        assertTrue(refused.getMessage().contains("SSLHandshakeException"), refused.getMessage());
        assertEquals(0, delivered.get());
    }

    // Sends one event to the API destination at endpoint, with extraCaPath as the extra CA setting.
    private static void deliver(String endpoint, Optional<String> extraCaPath) {
        invoker(endpoint, extraCaPath).invokeTarget(apiDestinationTarget(), "{}", REGION);
    }

    private static String hookUrl(HttpsServer server) {
        return "https://localhost:" + server.getAddress().getPort() + "/hook";
    }

    // An invoker built the way CDI builds it, its API destination pointing at endpoint, with TLS off.
    private static EventBridgeInvoker invoker(String endpoint, Optional<String> extraCaPath) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(false);
        when(config.tls().extraCaPath()).thenReturn(extraCaPath);
        EventBridgeService eventBridgeService = mock(EventBridgeService.class);
        when(eventBridgeService.findApiDestinationByArn(DESTINATION_ARN, REGION)).thenReturn(
                new ApiDestination("tls", DESTINATION_ARN, CONNECTION_ARN, endpoint, "POST", null, null));
        when(eventBridgeService.findConnectionByArn(CONNECTION_ARN, REGION)).thenReturn(new Connection());
        return new EventBridgeInvoker(mock(LambdaService.class), mock(SqsService.class), mock(SnsService.class),
                null, null, eventBridgeService, null, null, null, null, null, null, null, new ObjectMapper(), config,
                (AppSyncTargetInvoker) null);
    }

    private static Target apiDestinationTarget() {
        return new Target("tls-target", DESTINATION_ARN, null, null);
    }

    // An HTTPS endpoint on localhost serving a certificate the CA issued, counting the requests to /hook.
    private static HttpsServer httpsEndpoint(FlociCertificateAuthority ca, AtomicInteger requests) throws Exception {
        HttpsServer server = LocalHttpsServer.issuedBy(ca);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static ApiDestination rateLimited(String name, int limit) {
        return new ApiDestination(name, "arn:aws:events:us-east-1:000000000000:api-destination/" + name + "/1",
                "arn:aws:events:us-east-1:000000000000:connection/c/1", "https://api.example.com", "POST",
                limit, null);
    }
}
