package io.floci.aws.testutil;

import org.jboss.logmanager.ExtLogRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Captures a class logger for the duration of one test action. */
public final class LogCapture {

    private static final Formatter JUL = new SimpleFormatter();

    private LogCapture() {
    }

    public static List<LogRecord> capture(Class<?> source, Runnable action) {
        // A record logged from another thread while the action runs is collected too.
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        withHandler(source, records::add, () -> {
            action.run();
            return null;
        });
        return records;
    }

    /**
     * The record's text as a reader sees it, formatted the way its logging backend formats it: a JBoss
     * LogManager record, which the tests' {@code java.util.logging.manager} hands out, in its own
     * format style ({@code printf} for {@code infof}, {@code MessageFormat} for {@code infov}), and any
     * other record as {@link java.util.logging.Formatter#formatMessage} does, which leaves a pattern it
     * cannot format as it is. A record without a message reads {@code "null"}.
     */
    public static String formatted(LogRecord record) {
        String text = record instanceof ExtLogRecord ext ? ext.getFormattedMessage() : JUL.formatMessage(record);
        return String.valueOf(text);
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
