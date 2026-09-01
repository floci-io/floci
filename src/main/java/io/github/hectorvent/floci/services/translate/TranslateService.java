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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
/**
 * Dummy response builder for Amazon Translate. Stateless — {@code TranslateText} and
 * {@code TranslateDocument} ignore the actual content and return a fixed but AWS-shaped
 * translation by default; {@code ListLanguages} returns a fixed catalog. Input validation
 * (required fields, supported language codes) still follows real Translate behavior, since
 * that is protocol compatibility rather than translation logic.
 * <p>
 * {@code TranslateText} callers can override the default stub per exact {@code Text} value
 * via {@link AiMockConfigLoader} — see {@code docs/services/translate.md} "Mock Responses".
 * {@code TranslateDocument}'s {@code Document} is always {@code Content}-backed (Translate's
 * {@code Document} shape has no S3 variant), so it has no natural lookup key and is not
 * mockable, same as a {@code Bytes}-backed Textract/Rekognition input.
 * <p>
 * Real translation logic is a planned follow-up; see the tracking issue for scope.
 *
 * @see <a href="https://docs.aws.amazon.com/translate/latest/APIReference/Welcome.html">Translate API Reference</a>
 */
@ApplicationScoped
public class TranslateService {
    private static final String SERVICE_KEY = "translate";
    /**
     * Languages accepted as a {@code SourceLanguageCode}/{@code TargetLanguageCode} value,
     * and the exact catalog {@code ListLanguages} reports. Real Translate supports a much
     * larger set; this fixed subset is an intentional deviation — see
     * {@code docs/services/translate.md}.
     */
    private static final Map<String, String> SUPPORTED_LANGUAGES = new LinkedHashMap<>();
    static {
        SUPPORTED_LANGUAGES.put("en", "English");
        SUPPORTED_LANGUAGES.put("es", "Spanish");
        SUPPORTED_LANGUAGES.put("fr", "French");
        SUPPORTED_LANGUAGES.put("de", "German");
        SUPPORTED_LANGUAGES.put("it", "Italian");
        SUPPORTED_LANGUAGES.put("pt", "Portuguese");
        SUPPORTED_LANGUAGES.put("ar", "Arabic");
        SUPPORTED_LANGUAGES.put("hi", "Hindi");
        SUPPORTED_LANGUAGES.put("ja", "Japanese");
        SUPPORTED_LANGUAGES.put("ko", "Korean");
        SUPPORTED_LANGUAGES.put("zh", "Chinese (Simplified)");
        SUPPORTED_LANGUAGES.put("zh-TW", "Chinese (Traditional)");
    }
    /** The source language {@code auto} always resolves to, for lack of a real Comprehend call. */
    private static final String AUTO_DETECTED_LANGUAGE_CODE = "en";
    private final ObjectMapper objectMapper;
    private final AiMockConfigLoader mockConfigLoader;
    @Inject
    public TranslateService(ObjectMapper objectMapper, AiMockConfigLoader mockConfigLoader) {
        this.objectMapper = objectMapper;
        this.mockConfigLoader = mockConfigLoader;
    }
    /**
     * TranslateText — always returns a fixed stub translation.
     * Response shape: https://docs.aws.amazon.com/translate/latest/APIReference/API_TranslateText.html
     */
    public Response translateText(String text, String sourceLanguageCode, String targetLanguageCode) {
        requireText(text);
        String resolvedSource = requireLanguageCodes(sourceLanguageCode, targetLanguageCode);
        Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, text, "TranslateText");
        if (mock.isPresent()) {
            return Response.ok(mock.get()).build();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("TranslatedText", "Floci");
        root.put("SourceLanguageCode", resolvedSource);
        root.put("TargetLanguageCode", targetLanguageCode);
        return Response.ok(root).build();
    }
    /**
     * TranslateDocument — always returns the input document content unchanged (no real
     * translation is performed, and the content is never decoded). Not mockable — see
     * the class-level Javadoc.
     * Response shape: https://docs.aws.amazon.com/translate/latest/APIReference/API_TranslateDocument.html
     */
    public Response translateDocument(JsonNode document, String sourceLanguageCode, String targetLanguageCode) {
        JsonNode content = requireDocumentContent(document);
        String resolvedSource = requireLanguageCodes(sourceLanguageCode, targetLanguageCode);
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("TranslatedDocument").set("Content", content);
        root.put("SourceLanguageCode", resolvedSource);
        root.put("TargetLanguageCode", targetLanguageCode);
        return Response.ok(root).build();
    }
    /**
     * ListLanguages — returns the fixed catalog described in {@link #SUPPORTED_LANGUAGES}.
     * {@code NextToken}/{@code MaxResults} pagination is not implemented: the full catalog
     * is always returned in one page.
     * Response shape: https://docs.aws.amazon.com/translate/latest/APIReference/API_ListLanguages.html
     */
    public Response listLanguages(String displayLanguageCode) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode languages = root.putArray("Languages");
        SUPPORTED_LANGUAGES.forEach((code, name) -> {
            ObjectNode language = languages.addObject();
            language.put("LanguageCode", code);
            language.put("LanguageName", name);
        });
        root.put("DisplayLanguageCode", displayLanguageCode != null ? displayLanguageCode : "en");
        return Response.ok(root).build();
    }
    // Private helpers
    private void requireText(String text) {
        if (text == null || text.isEmpty()) {
            throw new AwsException("InvalidRequestException", "Text is a required field.", 400);
        }
    }
    private JsonNode requireDocumentContent(JsonNode document) {
        if (document == null) {
            throw new AwsException("InvalidRequestException", "Document is a required field.", 400);
        }
        JsonNode content = document.get("Content");
        if (content == null || content.isNull()) {
            throw new AwsException("InvalidRequestException", "Document.Content is a required field.", 400);
        }
        if (!content.isTextual()) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for 'Document.Content' is not a string.", 400);
        }
        JsonNode contentType = document.get("ContentType");
        if (contentType == null || contentType.isNull()) {
            throw new AwsException("InvalidRequestException", "Document.ContentType is a required field.", 400);
        }
        if (!contentType.isTextual()) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for 'Document.ContentType' is not a string.", 400);
        }
        return content;
    }
    /**
     * Validates that both language codes are present and form a supported pair, then
     * resolves {@code SourceLanguageCode} to the code the response should report:
     * {@code auto} always resolves to {@link #AUTO_DETECTED_LANGUAGE_CODE} (real
     * Translate would call Amazon Comprehend to detect it). Required-ness of both
     * fields is checked before either is checked against the supported-language
     * catalog, so a missing field is never masked by an unrelated unsupported-pair
     * error.
     */
    private String requireLanguageCodes(String sourceLanguageCode, String targetLanguageCode) {
        if (sourceLanguageCode == null || sourceLanguageCode.isEmpty()) {
            throw new AwsException("InvalidRequestException", "SourceLanguageCode is a required field.", 400);
        }
        if (targetLanguageCode == null || targetLanguageCode.isEmpty()) {
            throw new AwsException("InvalidRequestException", "TargetLanguageCode is a required field.", 400);
        }
        boolean sourceIsAuto = "auto".equals(sourceLanguageCode);
        if ((!sourceIsAuto && !SUPPORTED_LANGUAGES.containsKey(sourceLanguageCode))
                || !SUPPORTED_LANGUAGES.containsKey(targetLanguageCode)) {
            throw unsupportedLanguagePair(sourceLanguageCode, targetLanguageCode);
        }
        return sourceIsAuto ? AUTO_DETECTED_LANGUAGE_CODE : sourceLanguageCode;
    }
    private AwsException unsupportedLanguagePair(String sourceLanguageCode, String targetLanguageCode) {
        return new AwsException("UnsupportedLanguagePairException",
                "Amazon Translate does not support translation from \"" + sourceLanguageCode
                        + "\" into \"" + targetLanguageCode + "\".", 400);
    }
}
