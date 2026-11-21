package io.floci.aws.testutil;

import io.floci.aws.testing.ThreadStates;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogCaptureTest {

    @Test
    void captureRestoresLoggerLevelAndHandlers() {
        Logger logger = Logger.getLogger(LogCaptureTest.class.getName());
        Level originalLevel = logger.getLevel();
        int originalHandlerCount = logger.getHandlers().length;
        try {
            logger.setLevel(Level.SEVERE);

            List<LogRecord> records = LogCapture.capture(LogCaptureTest.class,
                    () -> logger.info("captured message"));

            assertTrue(records.stream().anyMatch(record -> "captured message".equals(record.getMessage())));
            assertEquals(Level.SEVERE, logger.getLevel());
            assertEquals(originalHandlerCount, logger.getHandlers().length);
        } finally {
            logger.setLevel(originalLevel);
        }
    }

    @Test
    void captureRestoresLoggerWhenActionThrows() {
        Logger logger = Logger.getLogger(LogCaptureTest.class.getName());
        Level originalLevel = logger.getLevel();
        int originalHandlerCount = logger.getHandlers().length;
        try {
            logger.setLevel(Level.WARNING);

            assertThrows(IllegalStateException.class, () -> LogCapture.capture(LogCaptureTest.class,
                    () -> { throw new IllegalStateException("failed action"); }));

            assertEquals(Level.WARNING, logger.getLevel());
            assertEquals(originalHandlerCount, logger.getHandlers().length);
        } finally {
            logger.setLevel(originalLevel);
        }
    }
    @Test
    void awaitsMatchingRecordAfterActionReturns() throws Exception {
        Logger logger = Logger.getLogger(LogCaptureTest.class.getName());
        Level originalLevel = logger.getLevel();
        int originalHandlerCount = logger.getHandlers().length;
        CountDownLatch actionReturned = new CountDownLatch(1);
        FutureTask<String> task = new FutureTask<>(() -> LogCapture.awaitRecord(LogCaptureTest.class,
                Duration.ofSeconds(5), record -> "matched".equals(record.getMessage()), () -> {
                    actionReturned.countDown();
                    return "action result";
                }));
        Thread worker = new Thread(task);
        worker.start();
        try {
            assertTrue(actionReturned.await(5, TimeUnit.SECONDS));
            ThreadStates.awaitState(worker, Set.of(Thread.State.WAITING), Duration.ofSeconds(5), "log waiter");
            logger.info("unrelated");
            assertFalse(task.isDone(), "an unrelated record must not release the waiter");
            logger.info("matched");
            assertEquals("action result", task.get(5, TimeUnit.SECONDS));
            assertEquals(originalLevel, logger.getLevel());
            assertEquals(originalHandlerCount, logger.getHandlers().length);
        } finally {
            logger.info("matched");
            worker.join(6000);
            assertFalse(worker.isAlive(), "log waiter exited");
        }
    }

    @Test
    void awaitRestoresLoggerWhenNoRecordMatches() {
        Logger logger = Logger.getLogger(LogCaptureTest.class.getName());
        Level originalLevel = logger.getLevel();
        int originalHandlerCount = logger.getHandlers().length;
        CompletionException failure = assertThrows(CompletionException.class, () ->
                LogCapture.awaitRecord(LogCaptureTest.class, Duration.ZERO,
                        record -> "missing".equals(record.getMessage()), () -> {
                            logger.info("unrelated");
                            return null;
                        }));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertEquals(originalLevel, logger.getLevel());
        assertEquals(originalHandlerCount, logger.getHandlers().length);
    }

    @Test
    void formattedSubstitutesTheParametersARecordStillCarries() {
        LogRecord pattern = new LogRecord(Level.WARNING, "key {0} in account {1}");
        pattern.setParameters(new Object[]{"AKIAEXAMPLE", "123456789012"});
        LogRecord plain = new LogRecord(Level.WARNING, "the user's key {0}");

        assertEquals("key AKIAEXAMPLE in account 123456789012", LogCapture.formatted(pattern));
        assertEquals("the user's key {0}", LogCapture.formatted(plain));
    }

    /** A JBoss record is formatted in its own style: printf for {@code infof}, MessageFormat for {@code infov}. */
    @Test
    void formattedReadsAJbossRecordInItsOwnFormatStyle() {
        ExtLogRecord printf = new ExtLogRecord(Level.INFO, "=== Floci %s Starting ===",
                ExtLogRecord.FormatStyle.PRINTF, LogCaptureTest.class.getName());
        printf.setParameters(new Object[]{"2.5.0"});
        ExtLogRecord messageFormat = new ExtLogRecord(Level.INFO, "broker {0} is now RUNNING",
                ExtLogRecord.FormatStyle.MESSAGE_FORMAT, LogCaptureTest.class.getName());
        messageFormat.setParameters(new Object[]{"orders"});

        assertEquals("=== Floci 2.5.0 Starting ===", LogCapture.formatted(printf));
        assertEquals("broker orders is now RUNNING", LogCapture.formatted(messageFormat));
    }

    /** A pattern MessageFormat cannot read, an empty parameter list and no message at all don't throw. */
    @Test
    void formattedLeavesWhatItCannotFormatAsItIs() {
        LogRecord braces = new LogRecord(Level.WARNING, "bad payload {0 {\"a\":1}");
        braces.setParameters(new Object[]{"x"});
        LogRecord empty = new LogRecord(Level.WARNING, "role ''{0}''");
        empty.setParameters(new Object[0]);

        assertEquals("bad payload {0 {\"a\":1}", LogCapture.formatted(braces));
        assertEquals("role ''{0}''", LogCapture.formatted(empty));
        assertEquals("null", LogCapture.formatted(new LogRecord(Level.WARNING, null)));
    }

    @Test
    void awaitRestoresLoggerWhenActionThrows() {
        Logger logger = Logger.getLogger(LogCaptureTest.class.getName());
        Level originalLevel = logger.getLevel();
        int originalHandlerCount = logger.getHandlers().length;
        assertThrows(IllegalStateException.class, () -> LogCapture.awaitRecord(LogCaptureTest.class,
                Duration.ofSeconds(5), record -> true, () -> {
                    throw new IllegalStateException("failed action");
                }));
        assertEquals(originalLevel, logger.getLevel());
        assertEquals(originalHandlerCount, logger.getHandlers().length);
    }
}
