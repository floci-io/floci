package io.github.hectorvent.floci.testutil;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Captures a class logger for the duration of one test action. */
public final class LogCapture {

    private LogCapture() {
    }

    public static List<LogRecord> capture(Class<?> source, Runnable action) {
        Logger logger = Logger.getLogger(source.getName());
        Level originalLevel = logger.getLevel();
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
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
            action.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(originalLevel);
        }
        return records;
    }
}
