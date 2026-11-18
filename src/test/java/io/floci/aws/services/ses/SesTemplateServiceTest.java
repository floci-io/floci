package io.floci.aws.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.ses.model.EmailContent;
import io.floci.aws.services.ses.model.EmailTemplate;
import io.floci.aws.services.ses.model.Tag;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final String ACCOUNT = "000000000000";
    private SesTemplateService service;

    @BeforeEach
    void setUp() {
        service = new SesTemplateService(new InMemoryStorage<>(), new ObjectMapper(), new SecureRandom(),
                Clock.systemUTC());
    }

    private static EmailTemplate template(String name) {
        return new EmailTemplate(name, "Subject", "text body", "<p>html</p>");
    }

    @Test
    void create_thenGet_roundTrips() {
        EmailTemplate created = service.createTemplate(template("welcome"), REGION, ACCOUNT);
        assertNotNull(created.getCreatedTimestamp());
        assertNotNull(created.getLastUpdatedTimestamp());

        EmailTemplate fetched = service.getTemplate("welcome", REGION);
        assertEquals("Subject", fetched.getSubject());
    }

    @Test
    void create_rejectsDuplicate() {
        service.createTemplate(template("welcome"), REGION, ACCOUNT);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createTemplate(template("welcome"), REGION, ACCOUNT));
        assertEquals("AlreadyExists", e.getErrorCode());
        assertEquals("Template welcome already exists for account id " + ACCOUNT, e.getMessage());
    }

    // probed: the subject is asked for first, and a blank one counts as missing
    @ParameterizedTest
    @MethodSource("templatesWithoutSubject")
    void createAndUpdate_requireASubject(String subject, String text, String html) {
        service.createTemplate(template("stored"), REGION, ACCOUNT);
        AwsException onCreate = assertThrows(AwsException.class,
                () -> service.createTemplate(new EmailTemplate("fresh", subject, text, html), REGION, ACCOUNT));
        assertEquals("InvalidParameterValue", onCreate.getErrorCode());
        assertEquals("The subject must be specified.", onCreate.getMessage());
        AwsException onUpdate = assertThrows(AwsException.class,
                () -> service.updateTemplate(new EmailTemplate("stored", subject, text, html), REGION));
        assertEquals("InvalidParameterValue", onUpdate.getErrorCode());
        assertEquals("The subject must be specified.", onUpdate.getMessage());
    }

    static Stream<Arguments> templatesWithoutSubject() {
        return Stream.of(
                Arguments.of(null, null, null),
                Arguments.of(null, "t", null),
                Arguments.of(null, null, "<p>h</p>"),
                Arguments.of(null, "t", "<p>h</p>"),
                Arguments.of("", "t", null),
                Arguments.of(" ", "t", null),
                Arguments.of(null, " ", null));
    }

    // probed: a part counts only when it has a character that is not whitespace
    @ParameterizedTest
    @MethodSource("templatesWithoutParts")
    void createAndUpdate_requireOnePart(String text, String html) {
        service.createTemplate(template("stored"), REGION, ACCOUNT);
        AwsException onCreate = assertThrows(AwsException.class,
                () -> service.createTemplate(new EmailTemplate("fresh", "S", text, html), REGION, ACCOUNT));
        assertEquals("InvalidParameterValue", onCreate.getErrorCode());
        assertEquals("Template must specify at least one of the parts.", onCreate.getMessage());
        AwsException onUpdate = assertThrows(AwsException.class,
                () -> service.updateTemplate(new EmailTemplate("stored", "S", text, html), REGION));
        assertEquals("InvalidParameterValue", onUpdate.getErrorCode());
        assertEquals("Template must specify at least one of the parts.", onUpdate.getMessage());
        assertEquals("text body", service.getTemplate("stored", REGION).getTextPart());
    }

    static Stream<Arguments> templatesWithoutParts() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of("", null),
                Arguments.of(" ", null),
                Arguments.of(null, "\n"),
                Arguments.of(" ", ""));
    }

    // probed: the content is checked before the name is looked up, on both operations
    @Test
    void contentIsCheckedBeforeTheTemplateIsLookedUp() {
        service.createTemplate(template("stored"), REGION, ACCOUNT);
        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createTemplate(new EmailTemplate("stored", "S", null, null), REGION, ACCOUNT));
        assertEquals("Template must specify at least one of the parts.", duplicate.getMessage());
        AwsException unknown = assertThrows(AwsException.class,
                () -> service.updateTemplate(new EmailTemplate("ghost", "S", null, null), REGION));
        assertEquals("Template must specify at least one of the parts.", unknown.getMessage());
    }

    // probed: an empty part beside a real one is accepted and read back as no part, on both operations
    @Test
    void emptyPartIsStoredAsNoPart() {
        service.createTemplate(new EmailTemplate("html-only", "S", "", "<p>h</p>"), REGION, ACCOUNT);
        assertNull(service.getTemplate("html-only", REGION).getTextPart());
        service.updateTemplate(new EmailTemplate("html-only", "S", "t", ""), REGION);
        EmailTemplate updated = service.getTemplate("html-only", REGION);
        assertEquals("t", updated.getTextPart());
        assertNull(updated.getHtmlPart());
    }

    // probed: a whitespace-only part beside a real one is kept as written
    @Test
    void whitespaceOnlyPartBesideARealOneIsKept() {
        service.createTemplate(new EmailTemplate("blank-text", "S", " ", "<p>h</p>"), REGION, ACCOUNT);
        assertEquals(" ", service.getTemplate("blank-text", REGION).getTextPart());
    }

    @Test
    void create_rejectsBlankName() {
        assertThrows(AwsException.class, () -> service.createTemplate(template(" "), REGION, ACCOUNT));
    }

    @Test
    void create_rejectsEmptyBody() {
        assertThrows(AwsException.class,
                () -> service.createTemplate(new EmailTemplate("empty", null, null, null), REGION, ACCOUNT));
    }

    @Test
    void get_missingThrows() {
        assertThrows(AwsException.class, () -> service.getTemplate("ghost", REGION));
    }

    @Test
    void update_preservesCreatedTimestamp_missingThrows() {
        EmailTemplate created = service.createTemplate(template("welcome"), REGION, ACCOUNT);

        EmailTemplate update = template("welcome");
        update.setSubject("Updated");
        EmailTemplate updated = service.updateTemplate(update, REGION);
        assertEquals(created.getCreatedTimestamp(), updated.getCreatedTimestamp());
        assertEquals("Updated", service.getTemplate("welcome", REGION).getSubject());

        assertThrows(AwsException.class, () -> service.updateTemplate(template("ghost"), REGION));
    }

    @Test
    void delete_removesTemplate_missingThrows() {
        service.createTemplate(template("welcome"), REGION, ACCOUNT);
        service.deleteTemplate("welcome", REGION);
        assertThrows(AwsException.class, () -> service.getTemplate("welcome", REGION));
        assertThrows(AwsException.class, () -> service.deleteTemplate("welcome", REGION));
    }

    @Test
    void create_andUpdate_stampTheInjectedClock() {
        SesTemplateService ticking = new SesTemplateService(new InMemoryStorage<>(), new ObjectMapper(),
                new SecureRandom(), new MutableClock());

        Instant created = ticking.createTemplate(template("welcome"), REGION, ACCOUNT).getCreatedTimestamp();
        ticking.updateTemplate(template("welcome"), REGION);

        EmailTemplate stored = ticking.getTemplate("welcome", REGION);
        assertEquals(created, stored.getCreatedTimestamp());
        assertEquals(created.plusMillis(1), stored.getLastUpdatedTimestamp());
    }

    @Test
    void list_isPerRegion_newestFirst() {
        SesTemplateService ticking = new SesTemplateService(new InMemoryStorage<>(), new ObjectMapper(),
                new SecureRandom(), new MutableClock());
        for (String name : List.of("a", "b", "c", "d", "e")) {
            ticking.createTemplate(template(name), REGION, ACCOUNT);
        }
        ticking.createTemplate(template("other"), "eu-west-1", ACCOUNT);

        List<String> names = ticking.listTemplates(REGION).stream()
                .map(EmailTemplate::getTemplateName).toList();
        assertEquals(List.of("e", "d", "c", "b", "a"), names);
    }

    @Test
    void findAndSave_supportFacadeTagging() {
        service.createTemplate(template("welcome"), REGION, ACCOUNT);
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
        service.createTemplate(template("welcome"), REGION, ACCOUNT);
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
    void tripleStashIsSubstitutedLikeTheDoubleForm() {
        JsonNode data = MAPPER.createObjectNode().put("v", "<b>&</b>");
        assertEquals("esc=<b>&</b> raw=<b>&</b>",
                SesTemplateService.applyTemplateData("esc={{v}} raw={{{ v }}}", data));
    }

    @Test
    void emptyStringValue() {
        JsonNode data = MAPPER.createObjectNode().put("name", "");
        assertEquals("Hello ", SesTemplateService.applyTemplateData("Hello {{name}}", data));
    }

    @Test
    void buildTestRenderMime_asciiBody_uses7bit() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("Hello", "Hi there", "<p>Hi</p>", date, "BOUND");
        assertTrue(mime.contains("Subject: Hello\r\n"));
        assertTrue(mime.startsWith("Date: 2026-05-02T12:00:00.000Z\r\n"));
        assertTrue(mime.contains("Content-Type: multipart/alternative; \r\n\tboundary=\"BOUND\"\r\n"));
        assertTrue(mime.contains("Content-Transfer-Encoding: 7bit\r\n\r\nHi there\r\n--BOUND\r\n"));
        assertFalse(mime.contains("Content-Transfer-Encoding: 8bit"));
        assertTrue(mime.endsWith("--BOUND--\r\n"));
    }

    @Test
    void buildTestRenderMime_mostlyNonAsciiBody_usesBase64AndAnEncodedSubject() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("件名", "こんにちは", "<p>こんにちは</p>", date, "BOUND");
        assertTrue(mime.contains("Subject: =?UTF-8?B?5Lu25ZCN?=\r\n"));
        assertTrue(mime.contains("Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n44GT44KT44Gr44Gh44Gv\r\n--BOUND\r\n"));
        assertFalse(mime.contains("こんにちは"));
    }

    @Test
    void buildTestRenderMime_mostlyAsciiBody_usesQuotedPrintablePerPart() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("S", "plain text", "<p>café</p>", date, "BOUND");
        assertTrue(mime.contains("Content-Transfer-Encoding: 7bit\r\n\r\nplain text\r\n--BOUND\r\n"));
        assertTrue(mime.contains("Content-Transfer-Encoding: quoted-printable\r\n\r\n<p>caf=C3=A9</p>\r\n--BOUND--\r\n"));
    }

    @Test
    void buildTestRenderMime_subjectStripsCRLF() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("Multi\r\nLine", "x", "x", date, "BOUND");
        assertTrue(mime.contains("Subject: Multi Line\r\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parseRenderingDataInvalidCases")
    void parseRenderingData_invalid_throwsInvalidRenderingParameter(String label, String raw) {
        AwsException ex = assertThrows(AwsException.class,
                () -> SesTemplateService.parseRenderingData(MAPPER, raw));
        assertEquals("InvalidRenderingParameter", ex.getErrorCode());
    }

    // probed: malformed JSON, a JSON array and an empty string all get this one message
    @ParameterizedTest(name = "[{0}]")
    @MethodSource("invalidRenderingDataCases")
    void parseRenderingData_invalid_hasTheFixedMessage(String raw) {
        AwsException ex = assertThrows(AwsException.class,
                () -> SesTemplateService.parseRenderingData(MAPPER, raw));
        assertEquals("Template rendering data is invalid", ex.getMessage());
    }

    static Stream<String> invalidRenderingDataCases() {
        return Stream.of("{\"v\": ", "[1]", "", "   ", "\"text\"");
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

    @Test
    void buildTestRenderMime_sevenBitBodyKeepsItsBareLf() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("S", "line1\nline2", "<p>x\ny</p>", date, "BOUND");
        assertTrue(mime.contains("\r\n\r\nline1\nline2\r\n--BOUND\r\n"));
        assertTrue(mime.contains("\r\n\r\n<p>x\ny</p>\r\n--BOUND--\r\n"));
    }

    // Not probed directly: it follows from the CRLF that opens every boundary line, which the
    // base64 case below shows is written whatever the part ends with.
    @Test
    void buildTestRenderMime_bodyEndingWithNewlineKeepsItBeforeTheBoundary() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("S", "hello\n", "<p>hi</p>\r\n", date, "BOUND");
        assertTrue(mime.contains("\r\n\r\nhello\n\r\n--BOUND\r\n"));
        assertTrue(mime.contains("\r\n\r\n<p>hi</p>\r\n\r\n--BOUND--\r\n"));
    }

    // probed: a base64 part that fills its last line ends in CRLF, so a blank line precedes the boundary
    @Test
    void buildTestRenderMime_base64PartFillingItsLastLineIsFollowedByABlankLine() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("S", "あ".repeat(19), "<p>x</p>", date, "BOUND");
        assertTrue(mime.contains("Content-Transfer-Encoding: base64\r\n\r\n"
                + "44GC".repeat(19) + "\r\n\r\n--BOUND\r\n"));
    }

    // probed: a template with one part is not multipart, and nothing follows its body
    @Test
    void buildTestRenderMime_textOnlyTemplateIsASinglePart() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("S", "hello x\n", null, date, "BOUND");
        assertEquals("Date: 2026-05-02T12:00:00.000Z\r\nSubject: S\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 7bit\r\n\r\nhello x\n", mime);
    }

    @Test
    void buildTestRenderMime_htmlOnlyTemplateIsASinglePart() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("S", null, "<p>日本語のテスト</p>\r\n", date, "BOUND");
        assertEquals("Date: 2026-05-02T12:00:00.000Z\r\nSubject: S\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + "PHA+5pel5pys6Kqe44Gu44OG44K544OIPC9wPg0K", mime);
    }

    @Test
    void renderTestTemplate_readsTheDataBeforeLookingUpTheTemplate() {
        AwsException bothWrong = assertThrows(AwsException.class,
                () -> service.renderTestTemplate("ghost", "{\"v\": ", REGION, "000000000000"));
        assertEquals("InvalidRenderingParameter", bothWrong.getErrorCode());

        AwsException missing = assertThrows(AwsException.class,
                () -> service.renderTestTemplate("ghost", "{}", REGION, "000000000000"));
        assertEquals("TemplateDoesNotExist", missing.getErrorCode());
        assertEquals("Unable to find <ghost> for <000000000000>", missing.getMessage());
    }

    // probed: a whitespace-only text part beside an HTML part is still written as a part
    @Test
    void renderTestTemplate_whitespaceOnlyPartStaysAPart() {
        service.createTemplate(new EmailTemplate("blank-text", "S", " ", "<p>x</p>"), REGION, ACCOUNT);
        String mime = service.renderTestTemplate("blank-text", "{}", REGION, "000000000000");
        assertTrue(mime.contains("Content-Type: multipart/alternative; "));
        assertTrue(mime.contains("Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: 7bit\r\n\r\n \r\n--"));
        assertTrue(mime.contains("Content-Transfer-Encoding: 7bit\r\n\r\n<p>x</p>\r\n--"));
    }

    // probed: a part whose variables render to nothing, or to whitespace, is written empty
    @Test
    void renderTestTemplate_partRenderingToNothingStaysAPart() {
        service.createTemplate(new EmailTemplate("empty-html", "S", "t", "{{h}}"), REGION, ACCOUNT);
        String empty = service.renderTestTemplate("empty-html", "{\"h\": \"\"}", REGION, "000000000000");
        assertTrue(empty.contains("Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: 7bit\r\n\r\n\r\n--"));
        String blank = service.renderTestTemplate("empty-html", "{\"h\": \" \"}", REGION, "000000000000");
        assertTrue(blank.contains("Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: 7bit\r\n\r\n \r\n--"));
    }

    // probed: every spelling of the list-management placeholder renders as this sample link
    @Test
    void renderTestTemplate_showsASampleUnsubscribeLink() {
        service.createTemplate(new EmailTemplate("unsub", "U",
                "u={{amazonSESUnsubscribeUrl}} t={{{amazonSESUnsubscribeUrl}}} s={{ amazonSESUnsubscribeUrl }}",
                null), REGION, ACCOUNT);
        String mime = service.renderTestTemplate("unsub", "{}", REGION, "000000000000");
        assertTrue(mime.endsWith("\r\n\r\nu=http://unsubscribe.example.com t=http://unsubscribe.example.com"
                + " s=http://unsubscribe.example.com"));
    }

    @Test
    void sendPathKeepsThePlaceholderInItsCanonicalForm() {
        assertEquals("u={{amazonSESUnsubscribeUrl}} t={{amazonSESUnsubscribeUrl}} s={{amazonSESUnsubscribeUrl}}",
                SesTemplateService.applyTemplateData(
                        "u={{amazonSESUnsubscribeUrl}} t={{{amazonSESUnsubscribeUrl}}} s={{ amazonSESUnsubscribeUrl }}",
                        MAPPER.createObjectNode()));
    }

    @Test
    void sendPathRendersTheTripleStashInEveryPart() {
        JsonNode data = MAPPER.createObjectNode().put("v", "<b>x</b>");
        EmailContent.Simple rendered = SesTemplateService.render(new EmailContent.InlineTemplate(
                "s {{{v}}}", "t {{{v}}}", "<p>{{{v}}}</p>", data, List.of()));
        assertEquals("s <b>x</b>", rendered.subject());
        assertEquals("t <b>x</b>", rendered.bodyText());
        assertEquals("<p><b>x</b></p>", rendered.bodyHtml());
    }

    @Test
    void buildTestRenderMime_bodyWithoutTrailingNewline_addsCrlfBeforeBoundary() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
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

    // probed, each row
    static Stream<Arguments> sanitizeSubjectCases() {
        return Stream.of(
                Arguments.of("LF",                        "line1\nline2",   "line1 line2"),
                Arguments.of("CRLF is one space",         "a\r\nb",        "a b"),
                Arguments.of("space, LF and tab",         "x \nb\tc",      "x b c"),
                Arguments.of("two line breaks",           "a\n\nb",        "a b"),
                Arguments.of("plain spaces collapse too", "a  b",           "a b"),
                Arguments.of("other controls are kept",   "a\u0001b",       "a\u0001b"),
                Arguments.of("Unicode preserved",         "Hello 太郎",       "Hello 太郎")
        );
    }

    @Test
    void buildTestRenderMime_subjectControlCharacterIsEncoded() {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        String mime = SesTemplateService.buildTestRenderMime("a\u0001b", "x", "x", date, "BOUND");
        // probed
        assertTrue(mime.contains("Subject: =?UTF-8?Q?a=01b?=\r\n"));
    }

    // The v1 response carries the message as XML character data and has no sanitizing step, so
    // nothing outside printable ASCII, tab, CR and LF may ever reach the rendered message.
    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileContentCases")
    void buildTestRenderMime_isAlwaysXmlSafeAscii(String label, String value) {
        Instant date = Instant.parse("2026-05-02T12:00:00Z");
        List<String> rendered = List.of(
                SesTemplateService.buildTestRenderMime(value, value, "<p>" + value + "</p>", date, "BOUND"),
                SesTemplateService.buildTestRenderMime(value, value, null, date, "BOUND"),
                SesTemplateService.buildTestRenderMime(value, null, "<p>" + value + "</p>", date, "BOUND"));
        for (String mime : rendered) {
            for (int i = 0; i < mime.length(); i++) {
                char c = mime.charAt(i);
                assertTrue(c == '\t' || c == '\r' || c == '\n' || (c >= 0x20 && c < 0x7F),
                        "unexpected U+" + Integer.toHexString(c) + " at " + i + " in " + label);
            }
        }
    }

    static Stream<Arguments> hostileContentCases() {
        return Stream.of(
                Arguments.of("C0 control", "a\u0001b"),
                Arguments.of("every C0 control and DEL", allC0ControlsAndDel()),
                Arguments.of("controls in the majority", "\u0001\u0002\u0003a"),
                Arguments.of("NUL", "a\u0000b"),
                Arguments.of("non-ASCII", "こんにちは"),
                Arguments.of("mostly ASCII with non-ASCII", "hello café"),
                Arguments.of("emoji", "ok 😀"),
                Arguments.of("lone high surrogate", "a\uD83Db"),
                Arguments.of("lone low surrogate", "a\uDE00b"),
                Arguments.of("U+FFFE and U+FFFF", "a\uFFFE\uFFFFb"),
                Arguments.of("C1 control", "a\u0085b"),
                Arguments.of("long line with a control", "a".repeat(1200) + "\u0001")
        );
    }

    private static String allC0ControlsAndDel() {
        StringBuilder out = new StringBuilder("text ");
        for (char c = 0; c < 0x20; c++) {
            out.append(c);
        }
        return out.append('\u007F').append(" text").toString();
    }
}
