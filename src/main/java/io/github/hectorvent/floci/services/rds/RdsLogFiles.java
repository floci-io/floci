package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.core.common.AwsException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * An instance's log files, cut from its database container's log as RDS names and rotates them,
 * and the paging {@code DownloadDBLogFilePortion} applies to one file.
 */
final class RdsLogFiles {

    /** One line of the container's log, with the time Docker recorded for it. */
    record LogLine(Instant time, String text) {}

    /** A log file: its name, its last line's time, its size in bytes and its lines in order. */
    record LogFile(String name, Instant lastWritten, long size, List<String> lines) {}

    /** What one {@code DownloadDBLogFilePortion} call returns. */
    record Portion(String data, String marker, boolean additionalDataPending) {}

    /** The most lines a download returns, and its size cap, as the API reference gives them. */
    static final int MAX_LINES = 10_000;
    static final int MAX_BYTES = 1024 * 1024;

    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH").withZone(ZoneOffset.UTC);

    private RdsLogFiles() {
    }

    /**
     * Docker's timestamped lines as {@link LogLine}s. A line whose timestamp does not parse (a
     * continuation Docker split) takes the previous line's time.
     */
    static List<LogLine> parseDockerLines(List<String> raw) {
        List<LogLine> lines = new ArrayList<>(raw.size());
        Instant last = Instant.EPOCH;
        for (String line : raw) {
            int space = line.indexOf(' ');
            Instant time = null;
            if (space > 0) {
                try {
                    time = Instant.parse(line.substring(0, space));
                } catch (DateTimeParseException ignored) {
                    // not a timestamp: the whole line is text
                }
            }
            if (time != null) {
                last = time;
                lines.add(new LogLine(time, xmlSafe(line.substring(space + 1))));
            } else {
                lines.add(new LogLine(last, xmlSafe(line)));
            }
        }
        return lines;
    }

    /**
     * Replaces the control characters XML 1.0 cannot carry (a terminal colour code, a stray NUL)
     * with U+FFFD, so {@code LogFileData} always parses; tabs stay.
     */
    static String xmlSafe(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean bad = (c < 0x20 && c != '\t') || c == 0xFFFE || c == 0xFFFF;
            if (bad && out == null) {
                out = new StringBuilder(text.length());
                out.append(text, 0, i);
            }
            if (out != null) {
                out.append(bad ? '\uFFFD' : c);
            }
        }
        return out != null ? out.toString() : text;
    }

    /**
     * The files an engine's log is cut into. PostgreSQL rotates hourly into
     * {@code error/postgresql.log.YYYY-MM-DD-HH} (UTC); MySQL and MariaDB keep
     * {@code error/mysql-error.log}; SQL Server keeps {@code log/ERROR}.
     */
    static List<LogFile> files(String engine, List<LogLine> lines) {
        Map<String, List<LogLine>> byName = new TreeMap<>();
        for (LogLine line : lines) {
            byName.computeIfAbsent(fileName(engine, line.time()), name -> new ArrayList<>()).add(line);
        }
        List<LogFile> files = new ArrayList<>();
        byName.forEach((name, fileLines) -> {
            long size = 0;
            List<String> texts = new ArrayList<>(fileLines.size());
            for (LogLine line : fileLines) {
                texts.add(line.text());
                size += line.text().getBytes(StandardCharsets.UTF_8).length + 1;
            }
            files.add(new LogFile(name, fileLines.get(fileLines.size() - 1).time(), size, List.copyOf(texts)));
        });
        return files;
    }

    private static String fileName(String engine, Instant time) {
        String name = engine == null ? "postgres" : engine.toLowerCase(Locale.ROOT);
        if (name.contains("postgres")) {
            return "error/postgresql.log." + HOUR.format(time);
        }
        if (name.startsWith("sqlserver")) {
            return "log/ERROR";
        }
        return "error/mysql-error.log";
    }

    /**
     * The files {@code DescribeDBLogFiles} lists: names containing {@code filenameContains}, written
     * at or after {@code fileLastWritten} (epoch milliseconds), larger than {@code fileSize} bytes.
     */
    static List<LogFile> matching(List<LogFile> files, String filenameContains, Long fileLastWritten, Long fileSize) {
        return files.stream()
                .filter(f -> filenameContains == null || filenameContains.isEmpty()
                        || f.name().contains(filenameContains))
                .filter(f -> fileLastWritten == null || f.lastWritten().toEpochMilli() >= fileLastWritten)
                .filter(f -> fileSize == null || f.size() > fileSize)
                .toList();
    }

    /**
     * One block of a file, by the API reference's rules. Without a marker the most recent lines
     * come back (all of them, up to {@link #MAX_LINES}, without {@code numberOfLines}); marker
     * {@code 0} starts at the first line, and any other marker is the line to continue from, as a
     * previous call returned it. A block stops at 1 MB.
     */
    static Portion portion(LogFile file, String marker, Integer numberOfLines) {
        if (numberOfLines != null && numberOfLines < 1) {
            throw new AwsException("InvalidParameterValue", "NumberOfLines must be greater than 0.", 400);
        }
        int wanted = numberOfLines == null ? MAX_LINES : Math.min(numberOfLines, MAX_LINES);
        List<String> lines = file.lines();
        int start;
        if (marker == null || marker.isEmpty()) {
            start = Math.max(0, lines.size() - wanted);
        } else {
            try {
                start = Integer.parseInt(marker);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidParameterValue", "Invalid marker: " + marker, 400);
            }
            if (start < 0) {
                throw new AwsException("InvalidParameterValue", "Invalid marker: " + marker, 400);
            }
            start = Math.min(start, lines.size());
        }
        StringBuilder data = new StringBuilder();
        int bytes = 0;
        int end = start;
        while (end < lines.size() && end - start < wanted) {
            String line = lines.get(end) + "\n";
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            if (end > start && bytes + lineBytes > MAX_BYTES) {
                break;
            }
            if (lineBytes > MAX_BYTES) {
                // A single line over 1 MB is cut there, as the API reference says the block is.
                line = truncateUtf8(lines.get(end), MAX_BYTES - 1) + "\n";
                lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            }
            data.append(line);
            bytes += lineBytes;
            end++;
        }
        return new Portion(data.toString(), Integer.toString(end), end < lines.size());
    }

    /** The longest prefix of {@code text} that is at most {@code maxBytes} in UTF-8, cut between characters. */
    static String truncateUtf8(String text, int maxBytes) {
        int bytes = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int size = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return text.substring(0, i);
    }
}
