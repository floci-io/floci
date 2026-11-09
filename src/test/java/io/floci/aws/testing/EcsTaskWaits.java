package io.floci.aws.testing;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

import static org.awaitility.Awaitility.await;

/**
 * RunTask and StartTask answer before the launch, which lands the task on the task launcher's
 * thread. A test that looks at the task right after the answer races it.
 */
public final class EcsTaskWaits {

    private EcsTaskWaits() {
    }

    /**
     * Waits until the last status of what {@code describe} returns is one of {@code statuses} and
     * returns that description. RUNNING or STOPPED waits for the launch whichever way it lands; a
     * stop during the launch passes RUNNING on its way to STOPPED, so a test expecting the stop
     * names STOPPED alone.
     */
    public static <T> T awaitLastStatus(Callable<T> describe, Function<? super T, String> lastStatus,
                                        Duration timeout, String... statuses) {
        List<String> wanted = List.of(statuses);
        return await("the task to reach " + wanted).atMost(timeout)
                .until(describe, described -> wanted.contains(lastStatus.apply(described)));
    }
}
