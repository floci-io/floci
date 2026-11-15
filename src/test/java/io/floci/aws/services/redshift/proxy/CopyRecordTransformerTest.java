package io.floci.aws.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyRecordTransformerTest {

    @Test
    void expandedDecimalsAreBudgetedBeforeTheNextFieldIsWritten() {
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' CSV");
        AtomicInteger observed = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> new CopyRecordTransformer(spec, null, null, List.of(0, 0, 0))
                .apply("1e3,1e3,1e3\n".getBytes(StandardCharsets.UTF_8), size -> {
                    observed.set(size);
                    if (size > 3) {
                        throw new IllegalArgumentException("Output budget exceeded");
                    }
                }));
        assertEquals(4, observed.get());
    }

    @Test
    void decimalScaleUsesTruncationByDefaultAndRoundecRoundsTiesAwayFromZero() {
        for (String option : List.of("", "ROUNDEC")) {
            CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                    "COPY t FROM 's3://b/k' CSV " + option);
            byte[] output = new CopyRecordTransformer(spec, null, null, Arrays.asList(2, 2, null))
                    .apply("20.259,-20.255,20.259\n,,\\N\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(option.isEmpty() ? "20.25,-20.25,20.259\n,,\\N\n" : "20.26,-20.26,20.259\n,,\\N\n",
                    new String(output, StandardCharsets.UTF_8));
        }
    }

    @Test
    void textDecimalsDecodeCopyEscapesBeforeTruncationAndRounding() {
        String input = "12\\.345|-12\\.345|\\0612.345|12\\056345|12\\x2e345|\\x312.345|12.34\\65\n";
        assertEquals("12.34|-12.34|12.34|12.34|12.34|12.34|12.34\n",
                applyDecimals("", input, List.of(2, 2, 2, 2, 2, 2, 2)));
        assertEquals("12.35|-12.35|12.35|12.35|12.35|12.35|12.35\n",
                applyDecimals("ROUNDEC", input, List.of(2, 2, 2, 2, 2, 2, 2)));
    }

    @Test
    void decimalConversionPreservesInvalidTextAndRawNullMarkers() {
        String input = "\\N|not\\.numeric|12\\\\.345|12\\.345\n";
        assertEquals("\\N|not\\.numeric|12\\\\.345|12.34\n",
                applyDecimals("", input, List.of(2, 2, 2, 2)));
        assertEquals("12\\.345|12.34\n",
                applyDecimals("NULL AS '12\\.345'", "12\\.345|12.345\n", List.of(2, 2)));
        assertEquals("\\00012.345|12.345\\b\n",
                applyDecimals("", "\\00012.345|12.345\\b\n", List.of(2, 2)));
        assertEquals("12.34\n", applyDecimals("", "\\t12.345\\v\n", List.of(2)));
    }

    @Test
    void csvDecimalsKeepLiteralBackslashes() {
        String input = "12\\.345,\"12\\.345\",12.345\n";
        assertEquals("12\\.345,\"12\\.345\",12.34\n", applyDecimals("CSV", input, List.of(2, 2, 2)));
    }

    @Test
    void convertedDecimalsEscapePointAndDigitDelimiters() {
        assertEquals("12\\.34.x\n", applyDecimals("DELIMITER '.'", "12\\.345.x\n", Arrays.asList(2, null)));
        assertEquals("12.\\06343x\n", applyDecimals("DELIMITER '3'", "12.\\063453x\n", Arrays.asList(2, null)));
    }

    @Test
    void convertedDecimalsCannotBecomeCustomNullMarkers() {
        assertEquals("\\0612.34\n", applyDecimals("NULL AS '12.34'", "12\\.345\n", List.of(2)));
        assertEquals("\"12.34\"\n", applyDecimals("CSV NULL AS '12.34'", "12.345\n", List.of(2)));
        assertEquals("\\0612\\.34\n",
                applyDecimals("DELIMITER '.' NULL AS '12\\.34'", "12\\.345\n", List.of(2)));
        assertEquals("\\x312.34\n",
                applyDecimals("DELIMITER '1' NULL AS '\\0612.34'", "\\0612.345\n", List.of(2)));
    }

    private static String applyDecimals(String options, String input, List<Integer> scales) {
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' " + options);
        byte[] output = new CopyRecordTransformer(spec, null, null, scales)
                .apply(input.getBytes(StandardCharsets.UTF_8));
        return new String(output, StandardCharsets.UTF_8);
    }

    @Test
    void ignoreBlankLinesDoesNotDiscardWhitespaceOrQuotedEmptyFields() {
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' CSV IGNOREBLANKLINES");
        assertEquals("\"a\n\nb\"\n   \n\"\"\n,\n",
                new String(new CopyRecordTransformer(spec, null).apply(
                        "\n\r\n\"a\n\nb\"\n   \n\"\"\n,\n".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    }

    private static CopyStatementParser.CopyTransforms transforms(
            boolean blanks, boolean empty, boolean removeQuotes, boolean truncate, Character invalid) {
        return new CopyStatementParser.CopyTransforms(blanks, empty, removeQuotes, truncate, invalid);
    }

    private static String apply(String delimiter, boolean csv, String nullAs,
                                CopyStatementParser.CopyTransforms transforms, String input) {
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "k", delimiter, 0, false, csv, nullAs, null,
                false, false, false, transforms);
        byte[] out = new CopyRecordTransformer(spec, null)
                .apply(input.getBytes(StandardCharsets.UTF_8));
        return new String(out, StandardCharsets.UTF_8);
    }

    private static final List<CopyRecordTransformer.ColumnKind> KINDS = List.of(
            CopyRecordTransformer.ColumnKind.OTHER,
            CopyRecordTransformer.ColumnKind.DATE,
            CopyRecordTransformer.ColumnKind.TIMESTAMP);

    private static CopyStatementParser.CopyTransforms temporal(String dateFormat, String timeFormat, boolean any) {
        CopyTemporalFormat date = dateFormat != null ? CopyTemporalFormat.parse(dateFormat, false)
                : (any ? CopyTemporalFormat.defaultIso() : null);
        CopyTemporalFormat time = timeFormat != null ? CopyTemporalFormat.parse(timeFormat, true)
                : (any ? CopyTemporalFormat.defaultIso() : null);
        return new CopyStatementParser.CopyTransforms(false, false, false, false, null, date, time, any);
    }

    private static String applyTemporal(boolean csv, CopyStatementParser.CopyTransforms transforms, String input) {
        return applyTemporal(csv ? "," : "|", csv, transforms, input);
    }

    private static String applyTemporal(String delimiter, boolean csv,
                                        CopyStatementParser.CopyTransforms transforms, String input) {
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "k", delimiter, 0, false, csv, null, null, false, false, false, transforms);
        return new String(new CopyRecordTransformer(spec, null, KINDS).apply(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
    }

    @Test
    void dateAndTimestampColumnsAreRewrittenAndOthersLeftAlone() {
        assertEquals("03/31/2003|2003-03-31|2003-03-31 18:45:05\n",
                applyTemporal(false, temporal("MM/DD/YYYY", "MM/DD/YYYY HH:MI:SS", false),
                        "03/31/2003|03/31/2003|03/31/2003 18:45:05\n"));
    }

    @Test
    void aFormatOnlyAppliesToItsOwnKindOfColumn() {
        assertEquals("a|2003-03-31|03/31/2003 18:45:05\n",
                applyTemporal(false, temporal("YYYY-MM-DD", null, false), "a|2003-03-31|03/31/2003 18:45:05\n"));
    }

    @Test
    void nullMarkersAreNotConverted() {
        assertEquals("1|\\N|\\N\n",
                applyTemporal(false, temporal("MM/DD/YYYY", "MM/DD/YYYY", false), "1|\\N|\\N\n"));
    }

    @Test
    void emptyTemporalFieldsWithoutAcceptAnyDateLoadAsNull() {
        assertEquals("1|2003-03-31|\\N\n",
                applyTemporal(false, temporal("MM/DD/YYYY", "MM/DD/YYYY HH:MI:SS", false), "1|03/31/2003|\n"));
        assertEquals("1|\\N|2003-03-31 12:00:00\n",
                applyTemporal(false, temporal("MM/DD/YYYY", "MM/DD/YYYY HH:MI:SS", false), "1||03/31/2003 12:00:00\n"));
    }

    @Test
    void unquotedEmptyTemporalFieldsInCsvLoadAsNullWithoutAcceptAnyDate() {
        assertEquals("1,2003-03-31,\n",
                applyTemporal(true, temporal("MM/DD/YYYY", "MM/DD/YYYY HH:MI:SS", false), "1,03/31/2003,\n"));
        assertEquals("1,,2003-03-31 12:00:00\n",
                applyTemporal(true, temporal("MM/DD/YYYY", "MM/DD/YYYY HH:MI:SS", false), "1,,03/31/2003 12:00:00\n"));
    }

    @Test
    void quotedEmptyTemporalFieldsInCsvWithoutAcceptAnyDateFailTheCopy() {
        assertThrows(S3CopySimulator.S3TransferException.class,
                () -> applyTemporal(true, temporal("MM/DD/YYYY", "MM/DD/YYYY HH:MI:SS", false),
                        "1,\"\",03/31/2003 12:00:00\n"));
    }

    @Test
    void emptyNonCharacterFieldsLoadAsNull() {
        assertEquals("\\N|2003-03-31|2003-03-31 12:00:00\n",
                applyTemporal(false, temporal("MM/DD/YYYY", "MM/DD/YYYY HH:MI:SS", false),
                        "|03/31/2003|03/31/2003 12:00:00\n"));
    }

    @Test
    void emptyCharFieldRemainsEmptyStringWithoutEmptyAsNull() {
        List<CopyRecordTransformer.ColumnKind> kindsWithChar = List.of(
                CopyRecordTransformer.ColumnKind.CHAR,
                CopyRecordTransformer.ColumnKind.DATE);
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, CopyTemporalFormat.parse("MM/DD/YYYY", false), null, false);
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "k", "|", 0, false, false, null, null, false, false, false, transforms);

        assertEquals("|2003-03-31\n", new String(
                new CopyRecordTransformer(spec, null, kindsWithChar).apply("|03/31/2003\n".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));
    }

    @Test
    void emptyCharFieldWithEmptyAsNullLoadsAsNull() {
        List<CopyRecordTransformer.ColumnKind> kindsWithChar = List.of(
                CopyRecordTransformer.ColumnKind.CHAR,
                CopyRecordTransformer.ColumnKind.DATE);
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, true, false, false, null, CopyTemporalFormat.parse("MM/DD/YYYY", false), null, false);
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "k", "|", 0, false, false, null, null, false, false, false, transforms);

        assertEquals("\\N|2003-03-31\n", new String(
                new CopyRecordTransformer(spec, null, kindsWithChar).apply("|03/31/2003\n".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));
    }

    @Test
    void fillRecordFillsMissingCharColumnAsNull() {
        List<CopyRecordTransformer.ColumnKind> kinds = List.of(
                CopyRecordTransformer.ColumnKind.TEXT,
                CopyRecordTransformer.ColumnKind.CHAR);
        CopyStatementParser.CopyTransforms fill = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, true);
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "k", "|", 0, false, false, null, null, false, false, false, fill);

        assertEquals("hello|\\N\n", new String(
                new CopyRecordTransformer(spec, null, kinds).apply("hello\n".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));
    }

    @Test
    void acceptAnyDateLoadsEmptyDateAndTimestampAsNull() {
        assertEquals("1|\\N|\\N\n",
                applyTemporal(false, temporal("MM/DD/YYYY", "MM/DD/YYYY", true), "1||\n"));
        assertEquals("1,,\n",
                applyTemporal(true, temporal("MM/DD/YYYY", "MM/DD/YYYY", true), "1,\"\",\n"));
    }

    @Test
    void convertedDatesContainingTheDelimiterAreEscapedInTextMode() {
        assertEquals("a-2020\\-01\\-02-c\n",
                applyTemporal("-", false, temporal("YYYYMMDD", null, false), "a-20200102-c\n"));
        assertEquals("a:b:2020-01-02 12\\:30\\:00\n",
                applyTemporal(":", false, temporal(null, "YYYYMMDD HHMISS", false), "a:b:20200102 123000\n"));
        assertEquals("a 2003-03-31 2023-11-14\\ 22:13:20\n",
                applyTemporal(" ", false, temporal("YYYY-MM-DD", "epochsecs", false), "a 2003-03-31 1700000000\n"));
    }

    @Test
    void convertedDatesContainingTheDelimiterAreQuotedInCsvMode() {
        assertEquals("a-\"2020-01-02\"-c\n",
                applyTemporal("-", true, temporal("YYYYMMDD", null, false), "a-20200102-c\n"));
        assertEquals("a:b:\"2020-01-02 12:30:00\"\n",
                applyTemporal(":", true, temporal(null, "YYYYMMDD HHMISS", false), "a:b:20200102 123000\n"));
        assertEquals("a 2003-03-31 \"2023-11-14 22:13:20\"\n",
                applyTemporal(" ", true, temporal("YYYY-MM-DD", "epochsecs", false), "a 2003-03-31 1700000000\n"));
    }

    @Test
    void yearZeroIsRejectedOrLoadedAsNullWithAcceptAnyDate() {
        assertThrows(S3CopySimulator.S3TransferException.class,
                () -> applyTemporal(false, temporal("YYYY-MM-DD", null, false), "a|0000-01-01|x\n"));
        assertEquals("a|\\N|\\N\n",
                applyTemporal(false, temporal("YYYY-MM-DD", "YYYY-MM-DD HH:MI:SS", true),
                        "a|0000-01-01|0000-01-01 00:00:00\n"));
        assertEquals("a|\\N|\\N\n",
                applyTemporal(false, temporal(null, null, true),
                        "a|0000-01-01|0000-01-01 00:00:00\n"));
    }

    @Test
    void aQuotedCsvFieldIsConvertedAndKeepsItsQuotes() {
        assertEquals("a,\"2003-03-31\",2003-03-31 00:00:00\n",
                applyTemporal(true, temporal("MM/DD/YYYY", "MM/DD/YYYY", false), "a,\"03/31/2003\",03/31/2003\n"));
    }

    @Test
    void aValueThatDoesNotMatchFailsTheCopy() {
        S3CopySimulator.S3TransferException failure = assertThrows(S3CopySimulator.S3TransferException.class,
                () -> applyTemporal(false, temporal("MM/DD/YYYY", null, false), "a|2003-03-31|x\n"));

        assertTrue(failure.getMessage().contains("date"), failure.getMessage());
        assertTrue(failure.getMessage().contains("column 2"), failure.getMessage());
    }

    @Test
    void acceptAnyDateLoadsAValueThatDoesNotMatchAsNull() {
        assertEquals("a|\\N|\\N\n",
                applyTemporal(false, temporal("MM/DD/YYYY", "MM/DD/YYYY", true), "a|00/00/00|2003-03-31 18:45:05\n"));
    }

    private static final List<CopyRecordTransformer.ColumnKind> FILL_KINDS = List.of(
            CopyRecordTransformer.ColumnKind.OTHER,
            CopyRecordTransformer.ColumnKind.TEXT,
            CopyRecordTransformer.ColumnKind.DATE,
            CopyRecordTransformer.ColumnKind.TEXT);

    private static String applyFill(boolean csv, boolean emptyAsNull, String input) {
        return applyFill(csv, null, new CopyStatementParser.CopyTransforms(
                false, emptyAsNull, false, false, null, null, null, false, true), input);
    }

    private static String applyFill(boolean csv, String nullAs, CopyStatementParser.CopyTransforms transforms,
                                    String input) {
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "k", csv ? "," : "|", 0, false, csv, nullAs, null, false, false, false,
                transforms);
        return new String(new CopyRecordTransformer(spec, null, FILL_KINDS)
                .apply(input.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    @Test
    void fillRecordUsesTheCustomNullString() {
        CopyStatementParser.CopyTransforms fill = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, true);

        assertEquals("1|a|NA|\n", applyFill(false, "NA", fill, "1|a\n"));
    }

    @Test
    void fillRecordWithAnEmptyNullStringNullsTheCharacterColumnsToo() {
        CopyStatementParser.CopyTransforms fill = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, true);

        assertEquals("1|a||\n", applyFill(false, "", fill, "1|a\n"));
        assertEquals("1,a,,\n", applyFill(true, "", fill, "1,a\n"));
    }

    @Test
    void fillRecordLeavesARecordWithTooManyFieldsToPostgres() {
        CopyStatementParser.CopyTransforms fill = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, true);

        assertEquals("1|a|2020-01-01|x|extra\n", applyFill(false, null, fill, "1|a|2020-01-01|x|extra\n"));
    }

    @Test
    void fillRecordTreatsABlankLineAsOneEmptyField() {
        assertEquals("\\N||\\N|\n", applyFill(false, false, "\n"));
    }

    @Test
    void fillRecordWorksWithTheDateFormatAndRemoveQuotesOfTheSameCopy() {
        CopyStatementParser.CopyTransforms both = new CopyStatementParser.CopyTransforms(
                false, false, true, false, null, CopyTemporalFormat.parse("MM/DD/YYYY", false), null, false, true);

        assertEquals("1|a|2003-03-31|\n", applyFill(false, null, both, "1|\"a\"|03/31/2003\n"));
        assertEquals("1|a|\\N|\n", applyFill(false, null, both, "1|\"a\"\n"));
    }

    @Test
    void fillRecordGivesCharacterColumnsAnEmptyStringAndTheOthersNull() {
        assertEquals("1|a|\\N|\n2|b|2020-01-01|x\n", applyFill(false, false, "1|a\n2|b|2020-01-01|x\n"));
        assertEquals("1||\\N|\n", applyFill(false, false, "1\n"));
    }

    @Test
    void fillRecordWithEmptyAsNullFillsCharacterColumnsWithNull() {
        assertEquals("1|a|\\N|\\N\n", applyFill(false, true, "1|a\n"));
    }

    @Test
    void fillRecordQuotesTheEmptyStringInCsvWhereAnEmptyFieldIsNull() {
        assertEquals("1,a,,\"\"\n", applyFill(true, false, "1,a\n"));
        assertEquals("1,a,,\n", applyFill(true, true, "1,a\n"));
    }

    @Test
    void fillRecordLeavesCompleteAndCrlfRecordsAlone() {
        assertEquals("1|a|2020-01-01|x\n", applyFill(false, false, "1|a|2020-01-01|x\r\n"));
        assertEquals("1|a|\\N|\n", applyFill(false, false, "1|a\r\n"));
    }

    @Test
    void fillRecordFillsAnUnterminatedLastRecord() {
        assertEquals("1|a|\\N|\n", applyFill(false, false, "1|a"));
    }

    @Test
    void aShortRecordIsLeftAloneWithoutFillRecord() {
        assertEquals("1|a\n", apply("|", false, null, transforms(false, true, false, false, null), "1|a\n"));
    }

    @Test
    void acceptAnyDateWithoutAFormatLoadsIsoValuesAndNullsTheRest() {
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null,
                CopyTemporalFormat.defaultIso(), CopyTemporalFormat.defaultIso(), true);

        assertEquals("a|2020-01-01|2020-01-01 00:00:00\n",
                applyTemporal(false, transforms, "a|2020-01-01|2020-01-01\n"));
        assertEquals("a|\\N|\\N\n", applyTemporal(false, transforms, "a|00/00/00|00/00/00 00:00:00\n"));
    }

    @Test
    void emptyAsNullReplacesEmptyTextFields() {
        assertEquals("1|\\N|x\n2|b|\\N\n",
                apply("|", false, null, transforms(false, true, false, false, null), "1||x\n2|b|\n"));
    }

    @Test
    void blanksAsNullOnlyTouchesWhitespaceFields() {
        assertEquals("1|\\N|x\n2||y\n",
                apply("|", false, null, transforms(true, false, false, false, null), "1|   |x\n2||y\n"));
    }

    @Test
    void customNullStringIsUsedAsMarker() {
        assertEquals("1|NULL|x\n",
                apply("|", false, "NULL", transforms(false, true, false, false, null), "1||x\n"));
    }

    @Test
    void removeQuotesKeepsDelimiterInsideQuotesAndEscapesItForPostgres() {
        assertEquals("1|a\\|b|c\n",
                apply("|", false, null, transforms(false, false, true, false, null), "1|\"a|b\"|c\n"));
        assertEquals("x\n",
                apply("|", false, null, transforms(false, false, true, false, null), "'x'\n"));
    }

    @Test
    void removeQuotesWithoutClosingQuoteFails() {
        assertThrows(S3CopySimulator.S3TransferException.class,
                () -> apply("|", false, null, transforms(false, false, true, false, null), "1|\"abc\n"));
    }

    @Test
    void csvQuotedEmptyFieldBecomesNullAndQuotedCommaSurvives() {
        assertEquals("1,,x\n",
                apply(",", true, null, transforms(false, true, false, false, null), "1,\"\",x\n"));
        assertEquals("1,\"a,b\",\n",
                apply(",", true, null, transforms(true, false, false, false, null), "1,\"a,b\",   \n"));
    }

    @Test
    void crlfLineEndingsAreNormalisedAndLastFieldIsNotPolluted() {
        assertEquals("a|b\n1|\\N\n",
                apply("|", false, null, transforms(false, true, false, false, null), "a|b\r\n1|\r\n"));
    }

    @Test
    void lastRecordWithoutNewlineOrWithTrailingDelimiterIsTerminated() {
        assertEquals("1|\\N\n",
                apply("|", false, null, transforms(false, true, false, false, null), "1|"));
        assertEquals("1|b\n",
                apply("|", false, null, transforms(false, true, false, false, null), "1|b"));
    }

    @Test
    void backslashEscapedDelimiterStaysInsideItsField() {
        assertEquals("a\\|b|c\n",
                apply("|", false, null, transforms(false, true, false, false, null), "a\\|b|c\n"));
    }

    @Test
    void sanitizeUtf8ReplacesEachInvalidByteAndKeepsValidCharacters() {
        byte[] input = {'a', (byte) 0xFF, 'b', (byte) 0xC3, (byte) 0x28, (byte) 0xC3, (byte) 0xA9};
        assertEquals("a?b?(é",
                new String(CopyRecordTransformer.sanitizeUtf8(input, (byte) '?'), StandardCharsets.UTF_8));
    }

    private static String applyWithLimits(String delimiter, boolean csv, List<Integer> limits, String input) {
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "k", delimiter, 0, false, csv, null, null,
                false, false, false, transforms(false, false, false, true, null));
        byte[] out = new CopyRecordTransformer(spec, limits).apply(input.getBytes(StandardCharsets.UTF_8));
        return new String(out, StandardCharsets.UTF_8);
    }

    @Test
    void truncateColumnsCutsOnlyLimitedColumns() {
        assertEquals("1|abc|xyzxyz\n",
                applyWithLimits("|", false, Arrays.asList(null, 3, null), "1|abcdef|xyzxyz\n"));
    }

    @Test
    void truncateColumnsNeverSplitsAMultiByteCharacter() {
        // The limit counts bytes, so the 2-byte e-acute does not fit twice into 3 bytes.
        assertEquals("a\n", applyWithLimits("|", false, List.of(2), "aébc\n"));
        assertEquals("é\n", applyWithLimits("|", false, List.of(3), "éééé\n"));
        assertEquals("éé\n", applyWithLimits("|", false, List.of(4), "ééé\n"));
    }

    @Test
    void truncateColumnsLeavesAValueThatFitsItsByteLimit() {
        assertEquals("éé\n", applyWithLimits("|", false, List.of(4), "éé\n"));
    }

    @Test
    void truncateColumnsKeepsCsvQuoting() {
        assertEquals("\"abc\",x\n", applyWithLimits(",", true, Arrays.asList(3, null), "\"abcdef\",x\n"));
    }

    @Test
    void truncateColumnsLeavesTheNullMarkerIntact() {
        assertEquals("\\N|x|y\n",
                applyWithLimits("|", false, Arrays.asList(1, 10, 10), "\\N|x|y\n"));
    }

    @Test
    void truncateColumnsKeepsAnEscapedBackslashWhole() {
        // Decoded value is ab, backslash, c. The 3-byte cut keeps the backslash, re-escaped.
        assertEquals("ab\\\\|hello\n",
                applyWithLimits("|", false, Arrays.asList(3, null), "ab\\\\c|hello\n"));
    }

    @Test
    void truncateColumnsCountsAnEscapedDelimiterAsOneByte() {
        assertEquals("a\\|b|x\n", applyWithLimits("|", false, Arrays.asList(3, null), "a\\|b|x\n"));
    }

    @Test
    void truncateColumnsNeverSplitsAnOctalEscape() {
        // Backslash 101, 102 and 103 decode to A, B and C.
        assertEquals("AB\n", applyWithLimits("|", false, List.of(2), "\\101\\102\\103\n"));
    }

    @Test
    void removeQuotesDoesNotCloseOnAnEscapedQuoteBeforeTheDelimiter() {
        assertEquals("a\\\"\\|b|x\n",
                apply("|", false, null, transforms(false, false, true, false, null), "\"a\\\"|b\"|x\n"));
    }

    @Test
    void removeQuotesRecognisesTheClosingQuoteBeforeCrlf() {
        assertEquals("a\nb\n",
                apply("|", false, null, transforms(false, false, true, false, null), "'a'\r\n'b'\n"));
    }

    @Test
    void csvQuotedLastFieldBeforeCrlfIsAccepted() {
        assertEquals("1,\"x\"\n",
                apply(",", true, null, transforms(false, true, false, false, null), "1,\"x\"\r\n"));
    }

    @Test
    void acceptInvalidCharsAloneDoesNotNeedFieldParsing() {
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "k", "|", 0, false, false, null, null, false, false, false,
                transforms(false, false, false, false, '#'));
        byte[] out = new CopyRecordTransformer(spec, null).apply(new byte[]{'a', (byte) 0xFF, '\n'});
        assertEquals("a#\n", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void trimBlanksTrimsTrailingSpacesFromTextFields() {
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, false, 0, true);
        assertEquals("hello|world\n",
                apply("|", false, null, transforms, "hello   |world  \n"));
        assertEquals("  hello|world\n",
                apply("|", false, null, transforms, "  hello|world\n"));
    }

    @Test
    void trimBlanksTrimsTrailingTabsAndSpaces() {
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, false, 0, true);
        assertEquals("hello|world\n",
                apply("|", false, null, transforms, "hello \t |world\t\n"));
    }

    @Test
    void trimBlanksWithEmptyAsNullConvertsAllSpacesToNull() {
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, true, false, false, null, null, null, false, false, 0, true);
        assertEquals("\\N|hello\n",
                apply("|", false, null, transforms, "   |hello   \n"));
    }

    @Test
    void trimBlanksWithoutEmptyAsNullConvertsAllSpacesToEmptyString() {
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, false, 0, true);
        assertEquals("|hello\n",
                apply("|", false, null, transforms, "   |hello   \n"));
    }

    @Test
    void trimBlanksWithBlanksAsNullConvertsAllSpacesToNull() {
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                true, false, false, false, null, null, null, false, false, 0, true);
        assertEquals("\\N|hello\n",
                apply("|", false, null, transforms, "   |hello   \n"));
    }

    @Test
    void trimBlanksInCsvModeTrimsTrailingSpaces() {
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, false, 0, true);
        assertEquals("hello,world\n",
                apply(",", true, null, transforms, "hello   ,world  \n"));
        assertEquals("\"hello\",\"world\"\n",
                apply(",", true, null, transforms, "\"hello   \",\"world  \"\n"));
    }

    @Test
    void trimBlanksAppliesOnlyToVarcharWhenColumnKindsProvided() {
        List<CopyRecordTransformer.ColumnKind> kinds = List.of(
                CopyRecordTransformer.ColumnKind.TEXT,
                CopyRecordTransformer.ColumnKind.CHAR,
                CopyRecordTransformer.ColumnKind.OTHER);
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, false, 0, true);
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "k", "|", 0, false, false, null, null, false, false, false, transforms);
        byte[] out = new CopyRecordTransformer(spec, null, kinds)
                .apply("hello   |world   |123   \n".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello|world   |123   \n", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void trimBlanksKeepsANullMarkerThatEndsInSpaces() {
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, false, 0, true);
        assertEquals("N |hello\n", apply("|", false, "N ", transforms, "N |hello   \n"));
        assertEquals("N ,hello\n", apply(",", true, "N ", transforms, "N ,hello   \n"));
    }

    @Test
    void trimBlanksQuotesACsvFieldThatTrimsToAnEmptyStringSoItIsNotNull() {
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, false, 0, true);
        assertEquals("\"\",hello\n", apply(",", true, null, transforms, "   ,hello   \n"));
        // a field that was empty to begin with stays NULL
        assertEquals(",hello\n", apply(",", true, null, transforms, ",hello   \n"));
    }

    @Test
    void trimBlanksWithEmptyAsNullStillNullsASpaceOnlyCsvField() {
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, true, false, false, null, null, null, false, false, 0, true);
        assertEquals(",hello\n", apply(",", true, null, transforms, "   ,hello   \n"));
    }

    @Test
    void trimBlanksPreservesEscapedTrailingSpacesInTextMode() {
        CopyStatementParser.CopyTransforms transforms = new CopyStatementParser.CopyTransforms(
                false, false, false, false, null, null, null, false, false, 0, true);
        assertEquals("hello\\ \n",
                apply("|", false, null, transforms, "hello\\ \n"));
        assertEquals("hello\\\\\n",
                apply("|", false, null, transforms, "hello\\\\  \n"));
    }
}
