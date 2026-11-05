package io.github.hectorvent.floci.testutil;

import io.github.hectorvent.floci.testing.ThreadStates;
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
