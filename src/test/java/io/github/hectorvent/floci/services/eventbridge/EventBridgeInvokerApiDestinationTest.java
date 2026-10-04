package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.eventbridge.model.ApiDestination;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EventBridgeInvokerApiDestinationTest {

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

    private static ApiDestination rateLimited(String name, int limit) {
        return new ApiDestination(name, "arn:aws:events:us-east-1:000000000000:api-destination/" + name + "/1",
                "arn:aws:events:us-east-1:000000000000:connection/c/1", "https://api.example.com", "POST",
                limit, null);
    }
}
