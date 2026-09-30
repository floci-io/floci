package io.github.hectorvent.floci.testutil;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
