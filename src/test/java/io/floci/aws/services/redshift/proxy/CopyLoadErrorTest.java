package io.floci.aws.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CopyLoadErrorTest {
    @Test
    void longJsonDiagnosticsKeepTheLastPhysicalLineAndBoundTheRawPreview() {
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' JSON 'auto'");
        String input = "{\r\n\"name\":\"" + "a".repeat(2048) + "\"\r\n}\n";
        CopyLoadError diagnostic = CopyLoadError.from(CopyLoadError.Context.create(List.of()), spec, "k", 5,
                input.getBytes(StandardCharsets.UTF_8), List.of(),
                new S3CopySimulator.S3TransferException("22P04", "bad JSON", null));
        assertEquals(7, diagnostic.lineNumber());
        assertEquals(1024, diagnostic.rawLine().length());
    }

    @Test
    void backendColumnNamesThatAreNumbersAreNotTreatedAsPositions() {
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY t (\"2\", id) FROM 's3://b/k'");
        S3CopySimulator.S3TransferException error = new S3CopySimulator.S3TransferException("23502", "null value", null,
                Map.of('c', "2", 'M', "null value"));
        CopyLoadError diagnostic = CopyLoadError.from(CopyLoadError.Context.create(List.of()), spec, "k", 1,
                "\\N|1\n".getBytes(StandardCharsets.UTF_8), List.of(), error);
        assertEquals("2", diagnostic.column());
    }

    @Test
    void transformErrorsResolveReorderedColumnsAndPreserveRawValues() {
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY t (day, id) FROM 's3://b/k' DATEFORMAT 'DD/MM/YYYY'");
        List<S3CopySimulator.ColumnInfo> columns = List.of(
                S3CopySimulator.columnInfo(Arrays.asList("id", null, "23", null)),
                S3CopySimulator.columnInfo(Arrays.asList("day", null, "1082", null)));
        S3CopySimulator.S3TransferException error = new S3CopySimulator.S3TransferException("22007", "invalid date", null,
                Map.of('c', "1", 'd', "bad-date"));
        CopyLoadError diagnostic = CopyLoadError.from(CopyLoadError.Context.create(List.of()), spec, "k", 12,
                "bad-date|1\n".getBytes(StandardCharsets.UTF_8), columns, error);
        assertEquals("day", diagnostic.column());
        assertEquals("date", diagnostic.type());
        assertEquals(1205, diagnostic.code());
        assertEquals("bad-date", diagnostic.rawFieldValue());
        assertEquals("bad-date|1", diagnostic.rawLine());
    }

    @Test
    void catalogScalesFollowTheCopyColumnOrder() {
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY t (amount, id) FROM 's3://b/k'");
        List<S3CopySimulator.ColumnInfo> columns = List.of(
                S3CopySimulator.columnInfo(Arrays.asList("id", null, "23", null)),
                S3CopySimulator.columnInfo(Arrays.asList("amount", null, "1700", "2")));
        assertEquals(Arrays.asList(2, null), S3CopySimulator.alignScales(spec, columns));
    }

    @Test
    void diagnosticLimitsReplaceNulsWithoutSplittingUnicodeCharacters() {
        assertEquals("a.b", CopyLoadError.limit("a\0b", 100));
        assertEquals("a😀", CopyLoadError.limit("a😀b", 2));
    }
}
