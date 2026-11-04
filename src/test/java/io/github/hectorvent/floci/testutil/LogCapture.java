package io.github.hectorvent.floci.testutil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Captures a class logger for the duration of one test action. */
public final class LogCapture {

    private LogCapture() {
    }

    public static List<LogRecord> capture(Class<?> source, Runnable action) {
        List<LogRecord> records = new ArrayList<>();
        withHandler(source, records::add, () -> {
            action.run();
            return null;
        });
        return records;
    }

    /** Runs the action with capture installed, then waits for a matching asynchronous record. */
    public static <T> T awaitRecord(Class<?> source, Duration timeout, Predicate<LogRecord> matches,
                                    Supplier<T> action) {
        CompletableFuture<LogRecord> matched = new CompletableFuture<>();
        return withHandler(source, record -> {
            if (matches.test(record)) {
                matched.complete(record);
            }
        }, () -> {
            T result = action.get();
            matched.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS).join();
            return result;
        });
    }

    private static <T> T withHandler(Class<?> source, Consumer<LogRecord> observer, Supplier<T> action) {
        Logger logger = Logger.getLogger(source.getName());
        Level originalLevel = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                observer.accept(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            logger.setLevel(Level.ALL);
            return action.get();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(originalLevel);
        }
    }
}
