package io.github.hectorvent.floci.tui;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static io.github.hectorvent.floci.tui.TuiRenderer.*;

/**
 * Renders a scrollable log tail view for the TUI console.
 * Captures log output via a JUL Handler that bridges JBoss Logging output.
 */
final class TuiLogView {

    private static final int MAX_LOG_BUFFER = 500;

    private final LinkedList<LogEntry> logBuffer = new LinkedList<>();
    private boolean autoFollow = true;
    private int scrollOffset = 0;

    record LogEntry(String timestamp, String level, String logger, String message) {
    }

    /**
     * Adds a log entry to the buffer (thread-safe via synchronization).
     */
    synchronized void addLog(String level, String loggerName, String message) {
        String timestamp = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));

        // Shorten logger name (last 2 segments)
        String shortLogger = shortenLogger(loggerName);

        logBuffer.addLast(new LogEntry(timestamp, level, shortLogger, message));
        while (logBuffer.size() > MAX_LOG_BUFFER) {
            logBuffer.removeFirst();
        }

        if (autoFollow) {
            scrollOffset = Math.max(0, logBuffer.size() - 20);
        }
    }

    void scrollUp(int lines) {
        autoFollow = false;
        scrollOffset = Math.max(0, scrollOffset - lines);
    }

    void scrollDown(int lines, int visibleLines) {
        scrollOffset = Math.min(Math.max(0, logBuffer.size() - visibleLines), scrollOffset + lines);
        if (scrollOffset >= logBuffer.size() - visibleLines) {
            autoFollow = true;
        }
    }

    void toggleAutoFollow() {
        autoFollow = !autoFollow;
        if (autoFollow) {
            scrollOffset = Math.max(0, logBuffer.size() - 20);
        }
    }

    synchronized List<String> render(int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        int innerWidth = width - 4;

        // Header
        String followIndicator = autoFollow
                ? FG_BRIGHT_GREEN + "AUTO-FOLLOW" + RESET
                : FG_YELLOW + "PAUSED" + RESET;
        lines.add(boxRow(BOLD + FG_BRIGHT_CYAN + "  Logs" + RESET + "  " + followIndicator
                + "  " + FG_GRAY + "(" + logBuffer.size() + " entries)" + RESET, width));
        lines.add(middleBorder(width));

        int displayLines = maxLines - 4; // reserve for header + footer
        if (logBuffer.isEmpty()) {
            lines.add(boxRow(FG_GRAY + "  No log entries yet. Waiting for output..." + RESET, width));
            for (int i = 1; i < displayLines; i++) {
                lines.add(boxRow("", width));
            }
        } else {
            int start = scrollOffset;
            int end = Math.min(start + displayLines, logBuffer.size());

            List<LogEntry> snapshot = new ArrayList<>(logBuffer);
            for (int i = start; i < end; i++) {
                LogEntry entry = snapshot.get(i);
                String coloredLevel = colorizeLevel(entry.level());
                String logLine = FG_GRAY + entry.timestamp() + " " + RESET
                        + coloredLevel + " "
                        + FG_CYAN + entry.logger() + RESET + " "
                        + entry.message();

                // Truncate to fit
                if (visibleLength(logLine) > innerWidth - 2) {
                    logLine = truncateVisible(logLine, innerWidth - 5) + FG_GRAY + "..." + RESET;
                }
                lines.add(boxRow(logLine, width));
            }

            // Fill remaining lines
            for (int i = end - start; i < displayLines; i++) {
                lines.add(boxRow("", width));
            }
        }

        // Footer
        lines.add(middleBorder(width));
        lines.add(boxRow(FG_GRAY + "  [↑/↓] Scroll  [A] Toggle auto-follow  [Esc] Back" + RESET, width));

        return lines;
    }

    private static String colorizeLevel(String level) {
        return switch (level) {
            case "ERROR", "FATAL" -> BOLD + FG_RED + padRight(level, 5) + RESET;
            case "WARN" -> FG_YELLOW + padRight(level, 5) + RESET;
            case "INFO" -> FG_WHITE + padRight(level, 5) + RESET;
            case "DEBUG" -> FG_GRAY + padRight(level, 5) + RESET;
            case "TRACE" -> DIM + FG_GRAY + padRight(level, 5) + RESET;
            default -> FG_WHITE + padRight(level, 5) + RESET;
        };
    }

    private static String shortenLogger(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String[] parts = name.split("\\.");
        if (parts.length <= 2) {
            return name;
        }
        // Take last 2 segments
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    /**
     * Truncates a string with ANSI codes to the given visible width.
     */
    private static String truncateVisible(String text, int maxVisible) {
        StringBuilder result = new StringBuilder();
        int visible = 0;
        boolean inEscape = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\033') {
                inEscape = true;
                result.append(c);
            } else if (inEscape) {
                result.append(c);
                if (Character.isLetter(c)) {
                    inEscape = false;
                }
            } else {
                if (visible >= maxVisible) {
                    break;
                }
                result.append(c);
                visible++;
            }
        }
        return result.toString();
    }

    /**
     * Creates a JUL Handler that feeds logs into this view.
     * JBoss Logging routes through JUL when the JBoss LogManager is active.
     */
    Handler createLogHandler() {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null || record.getMessage() == null) {
                    return;
                }
                String level = record.getLevel() != null ? record.getLevel().getName() : "INFO";
                // Map JUL levels to familiar names
                level = switch (level) {
                    case "SEVERE" -> "ERROR";
                    case "WARNING" -> "WARN";
                    case "FINE", "FINER", "FINEST" -> "DEBUG";
                    default -> level;
                };
                String loggerName = record.getLoggerName() != null ? record.getLoggerName() : "";
                addLog(level, loggerName, record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }
}
