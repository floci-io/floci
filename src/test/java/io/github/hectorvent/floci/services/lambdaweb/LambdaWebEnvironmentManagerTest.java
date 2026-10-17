package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.lambdaweb.LambdaWebEnvironmentManager.Environment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaWebEnvironmentManagerTest {

    @Test
    void onlyAnExitSeenBeforeTheRequestStartedAllowsARetry() {
        Environment environment = new Environment("pool", "rev-1", "000000000000");
        long started = System.nanoTime();
        assertFalse(environment.exitedBefore(started));

        environment.sawExit(started);

        assertFalse(environment.exitedBefore(started));
        assertFalse(environment.exitedBefore(started - 1));
        assertTrue(environment.exitedBefore(started + 1));
    }

    @Test
    void awaitExitEndsWhenTheExitIsSeen() throws Exception {
        Environment environment = new Environment("pool", "rev-1", "000000000000");
        CompletableFuture<Void> waited = environment.awaitExit(Duration.ofMinutes(1));
        assertFalse(waited.isDone());

        environment.sawExit(System.nanoTime());

        waited.get(5, TimeUnit.SECONDS);
    }

    @Test
    void awaitExitGivesUpOnAProcessThatKeepsRunning() throws Exception {
        Environment environment = new Environment("pool", "rev-1", "000000000000");

        environment.awaitExit(Duration.ofMillis(10)).get(5, TimeUnit.SECONDS);

        assertFalse(environment.exitedBefore(System.nanoTime()));
        environment.sawExit(System.nanoTime());
        assertTrue(environment.exitedBefore(System.nanoTime()));
    }
}
