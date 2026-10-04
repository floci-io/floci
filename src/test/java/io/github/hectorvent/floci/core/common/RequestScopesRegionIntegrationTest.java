package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class RequestScopesRegionIntegrationTest {

    @Inject
    RegionResolver regionResolver;

    @Test
    void runAsWithRegionSetsTheRegionAndItsPartition() {
        AtomicReference<String> seen = new AtomicReference<>();
        runOffRequestThread(() -> RequestScopes.runAs("111122223333", "cn-north-1",
                () -> seen.set(regionResolver.getAccountId() + "|" + regionResolver.getRegion()
                        + "|" + regionResolver.getPartition())));
        assertEquals("111122223333|cn-north-1|aws-cn", seen.get());
    }

    @Test
    void accountOnlyRunAsLeavesTheDefaultRegion() {
        AtomicReference<String> seen = new AtomicReference<>();
        runOffRequestThread(() -> RequestScopes.runAs("111122223333",
                () -> seen.set(regionResolver.getRegion() + "|" + regionResolver.getPartition())));
        assertEquals(regionResolver.getDefaultRegion() + "|" + regionResolver.getDefaultPartition(), seen.get());
    }

    @Test
    void nestedScopeRestoresTheOuterAccountRegionAndPartition() {
        AtomicReference<String> inner = new AtomicReference<>();
        AtomicReference<String> outerAfter = new AtomicReference<>();
        runOffRequestThread(() -> RequestScopes.runAs("111122223333", "eu-west-1", () -> {
            RequestScopes.runAs("444455556666", "cn-north-1",
                    () -> inner.set(regionResolver.getAccountId() + "|" + regionResolver.getRegion()
                            + "|" + regionResolver.getPartition()));
            outerAfter.set(regionResolver.getAccountId() + "|" + regionResolver.getRegion()
                    + "|" + regionResolver.getPartition());
        }));
        assertEquals("444455556666|cn-north-1|aws-cn", inner.get());
        assertEquals("111122223333|eu-west-1|aws", outerAfter.get());
    }

    @Test
    void regionOutsideEveryPartitionFallsBackToTheDeploymentPartition() {
        AtomicReference<String> seen = new AtomicReference<>();
        runOffRequestThread(() -> RequestScopes.runAs(null, "xx-nowhere-9",
                () -> seen.set(regionResolver.getRegion() + "|" + regionResolver.getPartition())));
        assertEquals("xx-nowhere-9|" + regionResolver.getDefaultPartition(), seen.get());
    }

    @Test
    void callAsReturnsTheBodysValueInTheRegion() {
        AtomicReference<String> result = new AtomicReference<>();
        runOffRequestThread(() -> result.set(
                RequestScopes.callAs(null, "ap-southeast-2", regionResolver::getRegion)));
        assertEquals("ap-southeast-2", result.get());
        assertNotEquals("ap-southeast-2", regionResolver.getDefaultRegion());
    }

    /**
     * Background workers run on threads with no active request scope; reproduce that. Anything the
     * worker throws, including while a scope is restored after the body, fails the test.
     */
    private static void runOffRequestThread(Runnable body) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (failure.get() != null) {
            throw new AssertionError("background worker failed", failure.get());
        }
    }
}
