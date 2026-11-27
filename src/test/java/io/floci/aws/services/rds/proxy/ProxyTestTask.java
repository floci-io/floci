package io.floci.aws.services.rds.proxy;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;

record ProxyTestTask<T>(FutureTask<T> task, Thread thread) implements AutoCloseable {

    static <T> ProxyTestTask<T> start(Callable<T> driver) {
        FutureTask<T> task = new FutureTask<>(driver);
        return new ProxyTestTask<>(task, Thread.ofVirtual().start(task));
    }

    T await() throws Exception {
        return await(5);
    }

    T await(long timeoutSeconds) throws Exception {
        try {
            return task.get(timeoutSeconds, TimeUnit.SECONDS);
        } finally {
            if (!task.isDone()) {
                thread.interrupt();
            }
            thread.join(5_000);
            assertFalse(thread.isAlive(), "driver thread did not terminate");
        }
    }

    @Override
    public void close() throws Exception {
        await();
    }
}
