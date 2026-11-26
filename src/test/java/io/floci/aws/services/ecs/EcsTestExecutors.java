package io.floci.aws.services.ecs;

import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class EcsTestExecutors {

    private EcsTestExecutors() {
    }

    static ExecutorService queuedExecutor(Queue<Runnable> queue) throws InterruptedException {
        return executor(queue::add);
    }

    static ExecutorService inlineExecutor() throws InterruptedException {
        return executor(Runnable::run);
    }

    private static ExecutorService executor(Consumer<Runnable> schedule) throws InterruptedException {
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            schedule.accept(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(executor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        return executor;
    }
}
