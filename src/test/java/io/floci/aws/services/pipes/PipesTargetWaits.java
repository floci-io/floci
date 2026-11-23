package io.floci.aws.services.pipes;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import static org.awaitility.Awaitility.await;

final class PipesTargetWaits {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private PipesTargetWaits() {
    }

    static <T> T awaitTarget(String alias, Callable<T> read, Predicate<T> arrived) {
        return await(alias)
                .atMost(TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(read, arrived);
    }
}
