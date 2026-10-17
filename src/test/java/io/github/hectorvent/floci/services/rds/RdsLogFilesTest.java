package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdsLogFilesTest {

    private static List<String> docker(String... lines) {
        return List.of(lines);
    }

    @Test
    void postgresRotatesHourlyAndMysqlKeepsOneErrorLog() {
        List<RdsLogFiles.LogLine> lines = RdsLogFiles.parseDockerLines(docker(
                "2026-10-15T09:59:59.000000001Z LOG:  database system is ready",
                "2026-10-15T10:00:00.5Z LOG:  checkpoint starting",
                "2026-10-15T10:30:00Z ERROR:  relation \"t\" does not exist"));

        List<RdsLogFiles.LogFile> postgres = RdsLogFiles.files("postgres", lines);
        assertEquals(List.of("error/postgresql.log.2026-10-15-09", "error/postgresql.log.2026-10-15-10"),
                postgres.stream().map(RdsLogFiles.LogFile::name).toList());
        RdsLogFiles.LogFile tenOClock = postgres.get(1);
        assertEquals(Instant.parse("2026-10-15T10:30:00Z"), tenOClock.lastWritten());
        assertEquals(List.of("LOG:  checkpoint starting", "ERROR:  relation \"t\" does not exist"), tenOClock.lines());
        assertEquals("LOG:  checkpoint starting\n".length() + "ERROR:  relation \"t\" does not exist\n".length(),
                tenOClock.size());

        assertEquals(List.of("error/mysql-error.log"),
                RdsLogFiles.files("mariadb", lines).stream().map(RdsLogFiles.LogFile::name).toList());
        assertEquals(List.of("log/ERROR"),
                RdsLogFiles.files("sqlserver-se", lines).stream().map(RdsLogFiles.LogFile::name).toList());
        assertEquals(2, RdsLogFiles.files("aurora-postgresql", lines).size());
    }

    @Test
    void aLineWithoutATimestampTakesThePreviousOnesTimeAndControlCharactersAreReplaced() {
        List<RdsLogFiles.LogLine> lines = RdsLogFiles.parseDockerLines(docker(
                "2026-10-15T10:00:00Z first",
                "continued\u001b[0m\ttab"));
        assertEquals(Instant.parse("2026-10-15T10:00:00Z"), lines.get(1).time());
        assertEquals("continued�[0m\ttab", lines.get(1).text());
    }

    @Test
    void describeFiltersByNameTimeAndSize() {
        List<RdsLogFiles.LogFile> files = RdsLogFiles.files("postgres", RdsLogFiles.parseDockerLines(docker(
                "2026-10-15T09:00:00Z a",
                "2026-10-15T10:00:00Z bbbbbbbbbb")));
        assertEquals(1, RdsLogFiles.matching(files, "-09", null, null).size());
        long tenOClock = Instant.parse("2026-10-15T10:00:00Z").toEpochMilli();
        assertEquals("error/postgresql.log.2026-10-15-10",
                RdsLogFiles.matching(files, null, tenOClock, null).get(0).name());
        // Larger than the size given: "a\n" is 2 bytes, "bbbbbbbbbb\n" 11.
        assertEquals(1, RdsLogFiles.matching(files, null, null, 2L).size());
        assertEquals(2, RdsLogFiles.matching(files, "", null, null).size());
    }

    private static RdsLogFiles.LogFile fileOf(int lines) {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            texts.add("line " + i);
        }
        return new RdsLogFiles.LogFile("error/mysql-error.log", Instant.EPOCH, 0, texts);
    }

    @Test
    void downloadFollowsTheApiReferencesMarkerRules() {
        RdsLogFiles.LogFile file = fileOf(5);

        // No marker: the most recent lines.
        RdsLogFiles.Portion tail = RdsLogFiles.portion(file, null, 2);
        assertEquals("line 3\nline 4\n", tail.data());
        assertFalse(tail.additionalDataPending());
        assertEquals("5", tail.marker());

        // Marker "0": from the start, in blocks, until nothing is pending.
        RdsLogFiles.Portion first = RdsLogFiles.portion(file, "0", 2);
        assertEquals("line 0\nline 1\n", first.data());
        assertTrue(first.additionalDataPending());
        RdsLogFiles.Portion second = RdsLogFiles.portion(file, first.marker(), 2);
        assertEquals("line 2\nline 3\n", second.data());
        RdsLogFiles.Portion last = RdsLogFiles.portion(file, second.marker(), 2);
        assertEquals("line 4\n", last.data());
        assertFalse(last.additionalDataPending());

        // Neither: the whole file (up to 10000 lines).
        assertEquals(5, RdsLogFiles.portion(file, null, null).data().split("\n").length);
        // A marker past the end is an empty block.
        assertEquals("", RdsLogFiles.portion(file, "99", 2).data());

        assertThrows(AwsException.class, () -> RdsLogFiles.portion(file, "next", 2));
        assertThrows(AwsException.class, () -> RdsLogFiles.portion(file, "-1", 2));
        assertThrows(AwsException.class, () -> RdsLogFiles.portion(file, "0", 0));
    }

    @Test
    void aBlockStopsAtOneMegabyteAndAtTenThousandLines() {
        List<String> big = new ArrayList<>();
        String kb = "x".repeat(1023);
        for (int i = 0; i < 2000; i++) {
            big.add(kb);
        }
        RdsLogFiles.LogFile file = new RdsLogFiles.LogFile("log/ERROR", Instant.EPOCH, 0, big);
        RdsLogFiles.Portion portion = RdsLogFiles.portion(file, "0", 5000);
        assertEquals(1024, portion.data().split("\n").length);
        assertTrue(portion.additionalDataPending());
        assertEquals("1024", portion.marker());

        assertEquals("10000", RdsLogFiles.portion(fileOf(12_000), "0", 50_000).marker());
    }

    @Test
    void aSingleLineOverOneMegabyteIsCutAtOneMegabyte() {
        String huge = "\u00e9".repeat(RdsLogFiles.MAX_BYTES);
        RdsLogFiles.LogFile file = new RdsLogFiles.LogFile("log/ERROR", Instant.EPOCH, 0, List.of(huge, "next"));
        RdsLogFiles.Portion portion = RdsLogFiles.portion(file, "0", 10);
        int bytes = portion.data().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= RdsLogFiles.MAX_BYTES, Integer.toString(bytes));
        assertTrue(portion.data().endsWith("\u00e9\n"), "cut between characters");
        assertEquals("1", portion.marker());
        assertTrue(portion.additionalDataPending());
        assertEquals("next\n", RdsLogFiles.portion(file, portion.marker(), 10).data());
    }
}
