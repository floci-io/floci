package io.github.hectorvent.floci.services.translate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AiMockConfigLoader;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
/**
 * Dummy response builder for Amazon Translate. Stateless: no machine translation is
 * performed. {@code TranslateText} and {@code TranslateDocument} echo their input back
 * as the "translated" payload; {@code ListLanguages} returns a fixed catalog. Input
 * validation (required fields, known language codes, Text/document size limits, and the
 * English-pivot rule for documents) still follows real Translate behavior, since that is
 * protocol compatibility rather than translation logic.
 * <p>
 * Callers can override the {@code TranslateText} default per exact {@code Text} value via
 * {@link AiMockConfigLoader}; see {@code docs/services/translate.md} "Mock Responses".
 * {@code TranslateDocument} carries a binary payload with no stable lookup key, so mock
 * lookup is skipped for it (same treatment as a Bytes-backed Textract document).
 *
 * @see <a href="https://docs.aws.amazon.com/translate/latest/APIReference/welcome.html">Translate API Reference</a>
 */
@ApplicationScoped
public class TranslateService {
    private static final String SERVICE_KEY = "translate";
    /** TranslateText input cap: real Translate rejects Text longer than 10,000 UTF-8 bytes. */
    private static final int MAX_TEXT_BYTES = 10_000;
    /** TranslateDocument input cap: real Translate rejects a document larger than 100 KB. */
    private static final int MAX_DOCUMENT_BYTES = 100 * 1024;
    /**
     * Longest base64 string that can decode to {@link #MAX_DOCUMENT_BYTES} (4 chars per 3
     * bytes, rounded up). Checked before {@link Base64.Decoder#decode} so an oversized
     * payload is rejected without allocating the decoded {@code byte[]}.
     */
    private static final int MAX_DOCUMENT_CONTENT_CHARS = ((MAX_DOCUMENT_BYTES + 2) / 3) * 4;
    /** Longest request value echoed verbatim into an error message. */
    private static final int ERROR_VALUE_PREVIEW_CHARS = 64;
    /** ContentType values real Translate accepts for TranslateDocument. */
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "text/plain", "text/html",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    /**
     * DisplayLanguageCode enum accepted by ListLanguages. Matches the AWS API model; the
     * returned {@code LanguageName}s are always English here regardless of this value
     * (documented deviation: real Translate localizes the names).
     */
    private static final Set<String> SUPPORTED_DISPLAY_LANGUAGE_CODES = Set.of(
            "de", "en", "es", "fr", "it", "ja", "ko", "pt", "zh", "zh-TW");
    /**
     * Curated subset of Translate's supported languages, enough to exercise an i18n
     * pipeline without tracking AWS's full ~75-language list. Also the allow-list for
     * Source/TargetLanguageCode validation. Insertion order is preserved for ListLanguages.
     */
    private static final Map<String, String> SUPPORTED_LANGUAGES = buildSupportedLanguages();
    private final ObjectMapper objectMapper;
    private final AiMockConfigLoader mockConfigLoader;
    @Inject
    public TranslateService(ObjectMapper objectMapper, AiMockConfigLoader mockConfigLoader) {
        this.objectMapper = objectMapper;
        this.mockConfigLoader = mockConfigLoader;
    }
    /**
     * TranslateText: echoes {@code Text} back as {@code TranslatedText}. A source code of
     * {@code auto} is reported back as {@code en} (the stub's fixed "detected" language).
     * Response shape: https://docs.aws.amazon.com/translate/latest/APIReference/API_TranslateText.html
     */
    public Response translateText(String text, String sourceLanguageCode, String targetLanguageCode) {
        requireField(text, "Text");
        // length() (chars) is a lower bound on UTF-8 byte count, so the cheap check
        // short-circuits before getBytes() allocates for a pathologically large Text.
        if (text.length() > MAX_TEXT_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new AwsException("TextSizeLimitExceededException",
                    "The text is longer than the maximum supported size of "
                            + MAX_TEXT_BYTES + " bytes.", 400);
        }
        String resolvedSource = validateSourceLanguage(sourceLanguageCode);
        validateTargetLanguage(targetLanguageCode);
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, text, "TranslateText");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("TranslatedText", text);
        root.put("SourceLanguageCode", resolvedSource);
        root.put("TargetLanguageCode", targetLanguageCode);
        return Response.ok(root).build();
    }
    /**
     * TranslateDocument: echoes the document bytes back unchanged as the translated
     * document. Content is validated as base64 and size-checked, but never decoded for
     * translation. Real Translate only translates between English and another language, so
     * one of the two language codes must resolve to {@code en}.
     * Response shape: https://docs.aws.amazon.com/translate/latest/APIReference/API_TranslateDocument.html
     */
    public Response translateDocument(String content, String contentType,
                                      String sourceLanguageCode, String targetLanguageCode) {
        requireField(content, "Document.Content");
        requireField(contentType, "Document.ContentType");
        if (!SUPPORTED_CONTENT_TYPES.contains(contentType)) {
            throw new AwsException("InvalidRequestException",
                    "Unsupported document content type: \"" + preview(contentType) + "\". Supported "
                            + "types are text/plain, text/html, and the Word (.docx) MIME type.", 400);
        }
        // Validate the cheap fields before touching the (potentially large) base64 blob.
        String resolvedSource = validateSourceLanguage(sourceLanguageCode);
        validateTargetLanguage(targetLanguageCode);
        if (!"en".equals(resolvedSource) && !"en".equals(targetLanguageCode)) {
            throw new AwsException("UnsupportedLanguagePairException",
                    "Amazon Translate can only translate documents between English and another "
                            + "language. Set either the source or the target language to \"en\".", 400);
        }
        if (content.length() > MAX_DOCUMENT_CONTENT_CHARS) {
            throw new AwsException("InvalidRequestException",
                    "The document exceeds the maximum supported size of "
                            + MAX_DOCUMENT_BYTES + " bytes.", 400);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidRequestException",
                    "Document.Content is not valid base64-encoded data.", 400);
        }
        if (decoded.length > MAX_DOCUMENT_BYTES) {
            throw new AwsException("InvalidRequestException",
                    "The document size (" + decoded.length + " bytes) exceeds the maximum "
                            + "supported size of " + MAX_DOCUMENT_BYTES + " bytes.", 400);
        }
        ObjectNode root = objectMapper.createObjectNode();
        // json-1.1 marshals a blob as its base64 string; echo the caller's bytes verbatim.
        root.putObject("TranslatedDocument").put("Content", content);
        root.put("SourceLanguageCode", resolvedSource);
        root.put("TargetLanguageCode", targetLanguageCode);
        return Response.ok(root).build();
    }
    /**
     * ListLanguages: returns the full fixed catalog. Pagination inputs are accepted but
     * ignored (the catalog is small and static), so no {@code NextToken} is returned.
     * Response shape: https://docs.aws.amazon.com/translate/latest/APIReference/API_ListLanguages.html
     */
    public Response listLanguages(String displayLanguageCode) {
        String display = (displayLanguageCode == null || displayLanguageCode.isEmpty())
                ? "en" : displayLanguageCode;
        if (!SUPPORTED_DISPLAY_LANGUAGE_CODES.contains(display)) {
            throw new AwsException("UnsupportedDisplayLanguageCodeException",
                    "Unsupported display language code: \"" + preview(display) + "\".", 400);
        }
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode languages = root.putArray("Languages");
        for (Map.Entry<String, String> entry : SUPPORTED_LANGUAGES.entrySet()) {
            ObjectNode language = languages.addObject();
            language.put("LanguageCode", entry.getKey());
            language.put("LanguageName", entry.getValue());
        }
        root.put("DisplayLanguageCode", display);
        return Response.ok(root).build();
    }
    // Private helpers
    private void requireField(String value, String fieldName) {
        if (value == null || value.isEmpty()) {
            throw new AwsException("InvalidRequestException",
                    fieldName + " is a required field.", 400);
        }
    }
    /** Returns the code to report back; {@code auto} resolves to {@code en}. */
    private String validateSourceLanguage(String code) {
        requireField(code, "SourceLanguageCode");
        if ("auto".equals(code)) {
            return "en";
        }
        requireKnownLanguage(code);
        return code;
    }
    private void validateTargetLanguage(String code) {
        requireField(code, "TargetLanguageCode");
        requireKnownLanguage(code);
    }
    private void requireKnownLanguage(String code) {
        if (!SUPPORTED_LANGUAGES.containsKey(code)) {
            throw new AwsException("UnsupportedLanguagePairException",
                    "Unsupported language code: \"" + preview(code) + "\".", 400);
        }
    }
    /** Caps an untrusted request value before it goes into a client-visible error message. */
    private static String preview(String value) {
        if (value.length() <= ERROR_VALUE_PREVIEW_CHARS) {
            return value;
        }
        return value.substring(0, ERROR_VALUE_PREVIEW_CHARS) + "...";
    }
    private static Map<String, String> buildSupportedLanguages() {
        Map<String, String> languages = new LinkedHashMap<>();
        languages.put("af", "Afrikaans");
        languages.put("ar", "Arabic");
        languages.put("bn", "Bengali");
        languages.put("zh", "Chinese (Simplified)");
        languages.put("zh-TW", "Chinese (Traditional)");
        languages.put("cs", "Czech");
        languages.put("da", "Danish");
        languages.put("nl", "Dutch");
        languages.put("en", "English");
        languages.put("fi", "Finnish");
        languages.put("fr", "French");
        languages.put("de", "German");
        languages.put("el", "Greek");
        languages.put("he", "Hebrew");
        languages.put("hi", "Hindi");
        languages.put("hu", "Hungarian");
        languages.put("id", "Indonesian");
        languages.put("it", "Italian");
        languages.put("ja", "Japanese");
        languages.put("ko", "Korean");
        languages.put("ms", "Malay");
        languages.put("no", "Norwegian");
        languages.put("fa", "Persian");
        languages.put("pl", "Polish");
        languages.put("pt", "Portuguese (Brazil)");
        languages.put("pt-PT", "Portuguese (Portugal)");
        languages.put("ro", "Romanian");
        languages.put("ru", "Russian");
        languages.put("sk", "Slovak");
        languages.put("es", "Spanish");
        languages.put("sv", "Swedish");
        languages.put("th", "Thai");
        languages.put("tr", "Turkish");
        languages.put("uk", "Ukrainian");
        languages.put("vi", "Vietnamese");
        return Collections.unmodifiableMap(languages);
    }
}
