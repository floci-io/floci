package io.github.hectorvent.floci.services.comprehend;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.Set;
/**
 * Dummy response builder for Amazon Comprehend. Stateless — every sync Detect*
 * action ignores the actual Text content and returns a fixed but AWS-shaped
 * response. Input validation (Text/LanguageCode required, supported language
 * codes) still follows real Comprehend behavior, since that is protocol
 * compatibility rather than NLP logic.
 * <p>
 * Real detection logic (lexicon/regex based) is a planned follow-up; see the
 * tracking issue for scope.
 *
 * @see <a href="https://docs.aws.amazon.com/comprehend/latest/APIReference/Welcome.html">Comprehend API Reference</a>
 */
@ApplicationScoped
public class ComprehendService {
    /** Languages accepted by DetectSentiment/DetectKeyPhrases. */
    private static final Set<String> SUPPORTED_LANGUAGE_CODES = Set.of(
            "en", "es", "fr", "de", "it", "pt", "ar", "hi", "ja", "ko", "zh", "zh-TW");
    /**
     * Languages accepted by DetectPiiEntities/ContainsPiiEntities — a narrower set than the
     * general LanguageCode enum. AWS's own operation docs for DetectPiiEntitiesRequest.LanguageCode
     * state: "Enter the language code for English (en) or Spanish (es)."
     */
    private static final Set<String> PII_SUPPORTED_LANGUAGE_CODES = Set.of("en", "es");
    private final ObjectMapper objectMapper;
    @Inject
    public ComprehendService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    /**
     * DetectSentiment — always returns NEUTRAL with flat confidence scores.
     * Response shape: https://docs.aws.amazon.com/comprehend/latest/APIReference/API_DetectSentiment.html
     */
    public Response detectSentiment(String text, String languageCode) {
        requireText(text);
        requireLanguageCode(languageCode, SUPPORTED_LANGUAGE_CODES);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("Sentiment", "NEUTRAL");
        ObjectNode score = root.putObject("SentimentScore");
        score.put("Positive", 0.1);
        score.put("Negative", 0.1);
        score.put("Neutral", 0.7);
        score.put("Mixed", 0.1);
        return Response.ok(root).build();
    }
    /**
     * DetectKeyPhrases — always returns a single stub key phrase.
     * Response shape: https://docs.aws.amazon.com/comprehend/latest/APIReference/API_DetectKeyPhrases.html
     */
    public Response detectKeyPhrases(String text, String languageCode) {
        requireText(text);
        requireLanguageCode(languageCode, SUPPORTED_LANGUAGE_CODES);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode keyPhrases = root.putArray("KeyPhrases");
        ObjectNode phrase = keyPhrases.addObject();
        phrase.put("Score", 0.99);
        phrase.put("Text", "Floci");
        phrase.put("BeginOffset", 0);
        phrase.put("EndOffset", 5);
        return Response.ok(root).build();
    }
    /**
     * DetectDominantLanguage — always reports English. Takes no LanguageCode input.
     * Response shape: https://docs.aws.amazon.com/comprehend/latest/APIReference/API_DetectDominantLanguage.html
     */
    public Response detectDominantLanguage(String text) {
        requireText(text);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode languages = root.putArray("Languages");
        ObjectNode language = languages.addObject();
        language.put("LanguageCode", "en");
        language.put("Score", 0.99);
        return Response.ok(root).build();
    }
    /**
     * DetectPiiEntities — always reports no PII found (an empty result is a
     * legitimate real response for PII-free text, unlike a fabricated match).
     * Response shape: https://docs.aws.amazon.com/comprehend/latest/APIReference/API_DetectPiiEntities.html
     */
    public Response detectPiiEntities(String text, String languageCode) {
        requireText(text);
        requireLanguageCode(languageCode, PII_SUPPORTED_LANGUAGE_CODES);
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("Entities");
        return Response.ok(root).build();
    }
    /**
     * ContainsPiiEntities — always reports no PII labels found.
     * Response shape: https://docs.aws.amazon.com/comprehend/latest/APIReference/API_ContainsPiiEntities.html
     */
    public Response containsPiiEntities(String text, String languageCode) {
        requireText(text);
        requireLanguageCode(languageCode, PII_SUPPORTED_LANGUAGE_CODES);
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("Labels");
        return Response.ok(root).build();
    }
    // Private helpers
    private void requireText(String text) {
        if (text == null || text.isEmpty()) {
            throw new AwsException("InvalidRequestException", "Text is a required field.", 400);
        }
    }
    private void requireLanguageCode(String languageCode, Set<String> allowedLanguageCodes) {
        if (languageCode == null || languageCode.isEmpty()) {
            throw new AwsException("InvalidRequestException", "LanguageCode is a required field.", 400);
        }
        if (!allowedLanguageCodes.contains(languageCode)) {
            throw new AwsException("UnsupportedLanguageException",
                    "Confidence score for language code \"" + languageCode
                            + "\" is not supported.", 400);
        }
    }
}
