package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CopyRecordTransformerTest {

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
}
