package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SesService#applyTemplateData} covering
 * template variable substitution edge cases.
 */
class SesServiceTemplateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void undefinedVariable_throwsMissingRenderingAttribute() {
        JsonNode data = MAPPER.createObjectNode().put("name", "Alice");
        AwsException ex = assertThrows(AwsException.class,
                () -> SesTemplateService.applyTemplateData("Hello {{name}}, team {{team}}", data));
        assertEquals("MissingRenderingAttribute", ex.getErrorCode());
    }

    @Test
    void spacedVariable_matchesCorrectly() {
        JsonNode data = MAPPER.createObjectNode().put("name", "Alice");
        String result = SesTemplateService.applyTemplateData("Hello {{ name }}", data);
        assertEquals("Hello Alice", result);
    }

    @Test
    void hyphenatedVariableName() {
        JsonNode data = MAPPER.createObjectNode().put("first-name", "Alice");
        String result = SesTemplateService.applyTemplateData("Hello {{first-name}}", data);
        assertEquals("Hello Alice", result);
    }

    @Test
    void unclosedBraces_leftAsIs() {
        JsonNode data = MAPPER.createObjectNode().put("name", "Alice");
        String result = SesTemplateService.applyTemplateData("Hello {{name}} and {{foo", data);
        assertEquals("Hello Alice and {{foo", result);
    }

    @Test
    void nonStringJsonValues() throws Exception {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("count", 42);
        data.put("active", true);
        data.set("nested", MAPPER.readTree("{\"key\":\"val\"}"));

        assertEquals("Items: 42", SesTemplateService.applyTemplateData("Items: {{count}}", data));
        assertEquals("Active: true", SesTemplateService.applyTemplateData("Active: {{active}}", data));
        assertEquals("Data: {\"key\":\"val\"}", SesTemplateService.applyTemplateData("Data: {{nested}}", data));
    }

    @Test
    void emptyTemplateData_throwsMissingRenderingAttribute() {
        JsonNode data = MAPPER.createObjectNode();
        AwsException ex = assertThrows(AwsException.class,
                () -> SesTemplateService.applyTemplateData("Hello {{name}}, {{team}}", data));
        assertEquals("MissingRenderingAttribute", ex.getErrorCode());
    }

    @Test
    void nullTemplateData_throwsMissingRenderingAttribute() {
        AwsException ex = assertThrows(AwsException.class,
                () -> SesTemplateService.applyTemplateData("Hello {{name}}", null));
        assertEquals("MissingRenderingAttribute", ex.getErrorCode());
    }

    @Test
    void nullText_returnsNull() {
        assertNull(SesTemplateService.applyTemplateData(null, MAPPER.createObjectNode()));
    }

    @Test
    void emptyText_returnsEmpty() {
        assertEquals("", SesTemplateService.applyTemplateData("", MAPPER.createObjectNode()));
    }

    @Test
    void noVariables_textUnchanged() {
        JsonNode data = MAPPER.createObjectNode().put("name", "Alice");
        assertEquals("Hello world", SesTemplateService.applyTemplateData("Hello world", data));
    }

    @Test
    void duplicateVariables_allReplaced() {
        JsonNode data = MAPPER.createObjectNode().put("name", "Alice");
        String result = SesTemplateService.applyTemplateData("{{name}} and {{name}}", data);
        assertEquals("Alice and Alice", result);
    }

    @Test
    void replacementWithRegexMetacharacters() {
        JsonNode data = MAPPER.createObjectNode().put("val", "price is $100 (50% off)");
        String result = SesTemplateService.applyTemplateData("The {{val}}", data);
        assertEquals("The price is $100 (50% off)", result);
    }

    @Test
    void variableNameCaseSensitive_matchesExact() {
        JsonNode data = MAPPER.createObjectNode().put("Name", "Alice");
        assertEquals("Hello Alice", SesTemplateService.applyTemplateData("Hello {{Name}}", data));
    }

    @Test
    void variableNameCaseSensitive_throwsForCaseMismatch() {
        JsonNode data = MAPPER.createObjectNode().put("Name", "Alice");
        AwsException ex = assertThrows(AwsException.class,
                () -> SesTemplateService.applyTemplateData("Hello {{name}}", data));
        assertEquals("MissingRenderingAttribute", ex.getErrorCode());
    }

    @Test
    void emptyStringValue() {
        JsonNode data = MAPPER.createObjectNode().put("name", "");
        assertEquals("Hello ", SesTemplateService.applyTemplateData("Hello {{name}}", data));
    }

    @Test
    void buildTestRenderMime_asciiBody_uses7bit() {
        java.time.ZonedDateTime date = java.time.ZonedDateTime.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("Hello", "Hi there", "<p>Hi</p>", date, "BOUND");
        assertTrue(mime.contains("Subject: Hello\r\n"));
        assertTrue(mime.contains("Content-Type: multipart/alternative; boundary=\"BOUND\""));
        assertTrue(mime.contains("Content-Transfer-Encoding: 7bit"));
        assertFalse(mime.contains("Content-Transfer-Encoding: 8bit"));
        assertTrue(mime.endsWith("--BOUND--\r\n"));
    }

    @Test
    void buildTestRenderMime_utf8Body_uses8bit() {
        java.time.ZonedDateTime date = java.time.ZonedDateTime.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("件名", "こんにちは", "<p>こんにちは</p>", date, "BOUND");
        assertTrue(mime.contains("Subject: 件名\r\n"));
        assertTrue(mime.contains("Content-Transfer-Encoding: 8bit"));
        assertTrue(mime.contains("こんにちは"));
    }

    @Test
    void buildTestRenderMime_subjectStripsCRLF() {
        java.time.ZonedDateTime date = java.time.ZonedDateTime.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("Multi\r\nLine", "x", "x", date, "BOUND");
        // CR and LF are both C0 controls and are replaced with spaces.
        assertTrue(mime.contains("Subject: Multi  Line\r\n"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("pickTransferEncodingCases")
    void pickTransferEncoding_returnsExpected(String body, String expected) {
        assertEquals(expected, SesTemplateService.pickTransferEncoding(body));
    }

    static Stream<Arguments> pickTransferEncodingCases() {
        return Stream.of(
                Arguments.of("ASCII text", "7bit"),
                Arguments.of("", "7bit"),
                Arguments.of("こんにちは", "8bit"),
                Arguments.of("café", "8bit")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parseRenderingDataInvalidCases")
    void parseRenderingData_invalid_throwsInvalidRenderingParameter(String label, String raw) {
        AwsException ex = assertThrows(AwsException.class,
                () -> SesTemplateService.parseRenderingData(MAPPER, raw));
        assertEquals("InvalidRenderingParameter", ex.getErrorCode());
    }

    static Stream<Arguments> parseRenderingDataInvalidCases() {
        return Stream.of(
                Arguments.of("invalid JSON", "{not json"),
                Arguments.of("non-object JSON (array)", "[1,2,3]"),
                Arguments.of("null input", null),
                Arguments.of("empty string", ""),
                Arguments.of("whitespace-only", "   ")
        );
    }

    @Test
    void parseRenderingData_emptyObject_accepted() {
        assertTrue(SesTemplateService.parseRenderingData(MAPPER, "{}").isObject());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("normalizeToCrlfCases")
    void normalizeToCrlf_normalizesAllVariants(String label, String input, String expected) {
        assertEquals(expected, SesTemplateService.normalizeToCrlf(input));
    }

    static Stream<Arguments> normalizeToCrlfCases() {
        return Stream.of(
                Arguments.of("LF only",       "a\nb\nc",       "a\r\nb\r\nc"),
                Arguments.of("CR only",       "a\rb\rc",       "a\r\nb\r\nc"),
                Arguments.of("already CRLF",  "a\r\nb\r\nc",   "a\r\nb\r\nc"),
                Arguments.of("mixed",         "a\nb\rc\r\nd",  "a\r\nb\r\nc\r\nd")
        );
    }

    @Test
    void buildTestRenderMime_bodyWithBareLf_normalizedToCrlf() {
        java.time.ZonedDateTime date = java.time.ZonedDateTime.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("S", "line1\nline2", "<p>x\ny</p>", date, "BOUND");
        assertTrue(mime.contains("line1\r\nline2"));
        assertTrue(mime.contains("x\r\ny"));
        assertFalse(mime.contains("line1\nline2"));
    }

    @Test
    void buildTestRenderMime_bodyEndingWithNewline_noExtraBlankLine() {
        java.time.ZonedDateTime date = java.time.ZonedDateTime.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("S", "hello\n", "<p>hi</p>\n", date, "BOUND");
        assertFalse(mime.contains("hello\r\n\r\n--BOUND"));
        assertTrue(mime.contains("hello\r\n--BOUND"));
        assertFalse(mime.contains("</p>\r\n\r\n--BOUND"));
        assertTrue(mime.contains("</p>\r\n--BOUND"));
    }

    @Test
    void buildTestRenderMime_bodyWithoutTrailingNewline_addsCrlfBeforeBoundary() {
        java.time.ZonedDateTime date = java.time.ZonedDateTime.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("S", "hello", "<p>hi</p>", date, "BOUND");
        assertTrue(mime.contains("hello\r\n--BOUND"));
        assertTrue(mime.contains("</p>\r\n--BOUND"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("mapErrorCodeToBulkStatusCases")
    void mapErrorCodeToBulkStatus_returnsExpected(String errorCode, BulkEmailEntryResult.Status expected) {
        assertEquals(expected, SesService.mapErrorCodeToBulkStatus(errorCode));
    }

    static Stream<Arguments> mapErrorCodeToBulkStatusCases() {
        return Stream.of(
                Arguments.of("InvalidParameterValue",     BulkEmailEntryResult.Status.INVALID_PARAMETER),
                Arguments.of("MissingRenderingAttribute", BulkEmailEntryResult.Status.INVALID_PARAMETER),
                Arguments.of("InvalidRenderingParameter", BulkEmailEntryResult.Status.INVALID_PARAMETER),
                Arguments.of("SomethingElse",             BulkEmailEntryResult.Status.FAILED)
        );
    }

    @Test
    void sanitizeSubject_nullReturnsEmpty() {
        assertEquals("", SesTemplateService.sanitizeSubject(null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sanitizeSubjectCases")
    void sanitizeSubject_returnsExpected(String label, String input, String expected) {
        assertEquals(expected, SesTemplateService.sanitizeSubject(input));
    }

    static Stream<Arguments> sanitizeSubjectCases() {
        return Stream.of(
                Arguments.of("C0 controls SOH/US",  "a\u0001b\u001fc", "a b c"),
                Arguments.of("CR and LF",           "x\ry\nz",          "x y z"),
                Arguments.of("BEL",                 "a\u0007b",          "a b"),
                Arguments.of("DEL",                 "a\u007fb",          "a b"),
                Arguments.of("Unicode preserved",   "Hello 太郎",          "Hello 太郎"),
                Arguments.of("printable preserved", "Hello!",             "Hello!")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stripXml10InvalidCharsCases")
    void stripXml10InvalidChars_returnsExpected(String label, String input, String expected) {
        assertEquals(expected, SesService.stripXml10InvalidChars(input));
    }

    static Stream<Arguments> stripXml10InvalidCharsCases() {
        // U+1F600 GRINNING FACE encoded as surrogate pair D83D DE00
        String emoji = "\uD83D\uDE00";
        return Stream.of(
                Arguments.of("keeps tab/LF/CR",          "a\tb\nc\rd",        "a\tb\nc\rd"),
                Arguments.of("removes C0 SOH/US",        "a\u0001b\u001fc",   "abc"),
                Arguments.of("removes BS",               "a\u0008b",            "ab"),
                Arguments.of("removes VT",               "a\u000bb",            "ab"),
                Arguments.of("removes FF",               "a\u000cb",            "ab"),
                Arguments.of("preserves Unicode",        "件名 太郎",            "件名 太郎"),
                Arguments.of("removes noncharacter FFFE","a\ufffeb",            "ab"),
                Arguments.of("removes noncharacter FFFF","a\uffffb",            "ab"),
                Arguments.of("removes lone high surrogate", "a\ud800b",         "ab"),
                Arguments.of("removes lone low surrogate",  "a\udc00b",         "ab"),
                Arguments.of("preserves paired surrogate (emoji)", "a" + emoji + "b", "a" + emoji + "b")
        );
    }

    @Test
    void buildTestRenderMime_subjectWithControlChars_replacedWithSpace() {
        java.time.ZonedDateTime date = java.time.ZonedDateTime.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime(
                "Hello\u0001World", "x", "x", date, "BOUND");
        assertTrue(mime.contains("Subject: Hello World\r\n"));
        assertFalse(mime.contains("\u0001"));
    }
}
