package io.github.hectorvent.floci.testing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The shared part of the tests that hold one request per worker of {@link FourWorkerThreadsProfile}
 * and check what is still served: the account and Region of each held request, the signing, and
 * the probes sent while the requests are held.
 *
 * <p>Probes are sent together and share one deadline, {@link #PROBE_DEADLINE_SECONDS}. A held
 * request waits {@link #HOLD_SECONDS} and the tests allow {@link #ARRIVAL_SECONDS} for all of them to
 * reach the service, so {@link #assertServed} fails before any held request is released.
 */
public final class HeldWorkerProbes {

    /** One held request per worker, each in its own account and Region. */
    public static final List<Scope> SCOPES = List.of(
            new Scope("111122223333", "us-east-1"),
            new Scope("111122223333", "us-west-2"),
            new Scope("444455556666", "us-east-1"),
            new Scope("444455556666", "us-west-2"));

    /** How long a held request waits before it is answered on its own. */
    public static final int HOLD_SECONDS = 20;

    /** How long the tests wait for every held request to reach the service. */
    public static final long ARRIVAL_SECONDS = 10;

    /** How long all probes together may take, which is what a held worker pool would exceed. */
    public static final long PROBE_DEADLINE_SECONDS = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        if (SCOPES.size() != FourWorkerThreadsProfile.WORKER_THREADS) {
            throw new IllegalStateException("one scope per worker thread: " + SCOPES.size() + " scopes, "
                    + FourWorkerThreadsProfile.WORKER_THREADS + " workers");
        }
        if (ARRIVAL_SECONDS + PROBE_DEADLINE_SECONDS >= HOLD_SECONDS) {
            throw new IllegalStateException("the probes must finish before a held request is released");
        }
    }

    private HeldWorkerProbes() {}

    /** The account and Region a held request is signed for. */
    public record Scope(String account, String region) {
        public String identity() {
            return account + "-" + region;
        }
    }

    /** Checks a probe's response once it has been answered. */
    @FunctionalInterface
    public interface ResponseCheck {
        void check(HttpResponse<byte[]> response) throws Exception;
    }

    /** A request sent while the held requests wait, and what its answer must look like. */
    public record Probe(String name, CompletableFuture<HttpResponse<byte[]>> response, ResponseCheck check) {
    }

    /** A request builder carrying a placeholder SigV4 header for {@code scope} and {@code service}. */
    public static HttpRequest.Builder signed(URI uri, Scope scope, String service) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", EnforcementFixtures.auth(scope.account(), scope.region(), service));
    }

    /** Sends {@code request} now; its answer is checked by {@link #assertServed}. */
    public static Probe probe(String name, HttpClient client, HttpRequest request, ResponseCheck check) {
        return new Probe(name, client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()), check);
    }

    /**
     * Sends the probes every held-worker test shares, answered here only while a worker is free or
     * the request runs on a virtual thread: the health check and SSM, which still need a worker, and
     * EC2 DescribeRegions on the Query entry point.
     */
    public static List<Probe> sharedProbes(HttpClient client, URI endpoint) {
        Scope scope = SCOPES.getFirst();
        return List.of(
                probe("GET /health", client, HttpRequest.newBuilder(endpoint.resolve("health")).GET().build(),
                        response -> {
                            assertEquals(200, response.statusCode());
                            assertEquals("community", MAPPER.readTree(response.body()).path("edition").asText());
                        }),
                probe("SSM DescribeParameters", client, signed(endpoint, scope, "ssm")
                        .header("Content-Type", "application/x-amz-json-1.1")
                        .header("X-Amz-Target", "AmazonSSM.DescribeParameters")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(), response -> {
                            assertEquals(200, response.statusCode());
                            assertTrue(MAPPER.readTree(response.body()).path("Parameters").isArray());
                        }),
                probe("EC2 DescribeRegions", client, signed(endpoint, scope, "ec2")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("Action=DescribeRegions&Version=2016-11-15"))
                        .build(), response -> {
                            assertEquals(200, response.statusCode());
                            assertTrue(new String(response.body(), StandardCharsets.UTF_8)
                                    .contains("<DescribeRegionsResponse"));
                        }));
    }

    /**
     * Fails unless every probe is answered within {@link #PROBE_DEADLINE_SECONDS} of this call, then
     * runs each probe's check. The probes are already in flight, so they wait concurrently.
     */
    public static void assertServed(List<Probe> probes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROBE_DEADLINE_SECONDS);
        List<String> unanswered = new ArrayList<>();
        for (Probe probe : probes) {
            try {
                probe.response().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                unanswered.add(probe.name());
            } catch (ExecutionException e) {
                fail(probe.name() + " failed: " + e.getCause(), e.getCause());
            }
        }
        if (!unanswered.isEmpty()) {
            fail("not answered within " + PROBE_DEADLINE_SECONDS + " s while the requests are held: " + unanswered);
        }
        for (Probe probe : probes) {
            try {
                probe.check().check(probe.response().join());
            } catch (AssertionError e) {
                throw new AssertionError(probe.name() + ": " + e.getMessage(), e);
            } catch (Exception e) {
                fail(probe.name() + ": " + e, e);
            }
        }
    }

    /** Waits for one request outside the held phase, failing when it is not answered in time. */
    public static <T> HttpResponse<T> served(CompletableFuture<HttpResponse<T>> request) {
        return assertDoesNotThrow(() -> request.get(PROBE_DEADLINE_SECONDS, TimeUnit.SECONDS),
                "a request is answered within " + PROBE_DEADLINE_SECONDS + " s");
    }
}
