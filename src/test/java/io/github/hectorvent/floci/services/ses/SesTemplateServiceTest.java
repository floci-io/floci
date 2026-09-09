package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.ParameterizedTest;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the template domain: CRUD semantics, the find/save escape hatches, the
 * ARN-dispatched tagging, and the rendering statics (variable substitution, TestRenderTemplate
 * data parsing, and the MIME assembly), merged here from the former SesServiceTemplateTest after
 * the rendering moved into this service.
 */
class SesTemplateServiceTest {

    private static final String REGION = "us-east-1";
    private SesTemplateService service;

    @BeforeEach
    void setUp() {
        service = new SesTemplateService(new InMemoryStorage<>(), new ObjectMapper(), new SecureRandom());
    }

    private static EmailTemplate template(String name) {
        return new EmailTemplate(name, "Subject", "text body", "<p>html</p>");
    }

    @Test
    void create_thenGet_roundTrips() {
        EmailTemplate created = service.createTemplate(template("welcome"), REGION);
        assertNotNull(created.getCreatedTimestamp());
        assertNotNull(created.getLastUpdatedTimestamp());

        EmailTemplate fetched = service.getTemplate("welcome", REGION);
        assertEquals("Subject", fetched.getSubject());
    }

    @Test
    void create_rejectsDuplicate() {
        service.createTemplate(template("welcome"), REGION);
        assertThrows(AwsException.class, () -> service.createTemplate(template("welcome"), REGION));
    }

    @Test
    void create_rejectsBlankName() {
        assertThrows(AwsException.class, () -> service.createTemplate(template(" "), REGION));
    }

    @Test
    void create_rejectsEmptyBody() {
        assertThrows(AwsException.class,
                () -> service.createTemplate(new EmailTemplate("empty", null, null, null), REGION));
    }

    @Test
    void get_missingThrows() {
        assertThrows(AwsException.class, () -> service.getTemplate("ghost", REGION));
    }

    @Test
    void update_preservesCreatedTimestamp_missingThrows() {
        EmailTemplate created = service.createTemplate(template("welcome"), REGION);

        EmailTemplate update = template("welcome");
        update.setSubject("Updated");
        EmailTemplate updated = service.updateTemplate(update, REGION);
        assertEquals(created.getCreatedTimestamp(), updated.getCreatedTimestamp());
        assertEquals("Updated", service.getTemplate("welcome", REGION).getSubject());

        assertThrows(AwsException.class, () -> service.updateTemplate(template("ghost"), REGION));
    }

    @Test
    void delete_removesTemplate_missingThrows() {
        service.createTemplate(template("welcome"), REGION);
        service.deleteTemplate("welcome", REGION);
        assertThrows(AwsException.class, () -> service.getTemplate("welcome", REGION));
        assertThrows(AwsException.class, () -> service.deleteTemplate("welcome", REGION));
    }

    @Test
    void list_isSortedByCreationAndPerRegion() {
        service.createTemplate(template("a"), REGION);
        service.createTemplate(template("b"), REGION);
        service.createTemplate(template("other"), "eu-west-1");

        List<EmailTemplate> list = service.listTemplates(REGION);
        assertEquals(2, list.size());
        assertEquals("a", list.get(0).getTemplateName());
        assertEquals("b", list.get(1).getTemplateName());
    }

    @Test
    void findAndSave_supportFacadeTagging() {
        service.createTemplate(template("welcome"), REGION);
        assertTrue(service.find("ghost", REGION).isEmpty());

        EmailTemplate found = service.find("welcome", REGION).orElseThrow();
        found.setTags(List.of(new Tag("team", "ses")));
        service.save(found, REGION);

        EmailTemplate reloaded = service.find("welcome", REGION).orElseThrow();
        assertEquals(1, reloaded.getTags().size());
        assertEquals("team", reloaded.getTags().get(0).key());
        assertEquals("ses", reloaded.getTags().get(0).value());
    }

    @Test
    void tagOps_lifecycle_notFoundMessage() {
        service.createTemplate(template("welcome"), REGION);
        service.tag("welcome", REGION, List.of(new Tag("team", "floci"), new Tag("env", "dev")));
        assertEquals(2, service.listTags("welcome", REGION).size());

        // Re-tagging an existing key replaces its value; untagging a missing key is a silent success.
        service.tag("welcome", REGION, List.of(new Tag("env", "prod")));
        service.untag("welcome", REGION, List.of("team", "ghost-key"));
        List<Tag> tags = service.listTags("welcome", REGION);
        assertEquals(1, tags.size());
        assertEquals("env", tags.get(0).key());
        assertEquals("prod", tags.get(0).value());

        AwsException e = assertThrows(AwsException.class, () -> service.listTags("ghost", REGION));
        assertEquals("No Template present with name: ghost", e.getMessage());
    }

    // ──────────────────────── Rendering (moved from SesServiceTemplateTest) ────────────────────────

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

    @Test
    void buildTestRenderMime_subjectWithControlChars_replacedWithSpace() {
        java.time.ZonedDateTime date = java.time.ZonedDateTime.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime(
                "Hello\u0001World", "x", "x", date, "BOUND");
        assertTrue(mime.contains("Subject: Hello World\r\n"));
        assertFalse(mime.contains("\u0001"));
    }
}
