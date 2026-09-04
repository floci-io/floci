package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Email templates (the {@code templateStore}), extracted from {@link SesService} as part of the
 * store-based domain split. Reached through the {@code SesService} facade, which delegates the CRUD
 * here; the facade's templated-send path reads templates back through {@link #getTemplate}, and its
 * ARN-dispatched tagging reads/writes through {@link #find} / {@link #save}.
 *
 * <p>Template rendering is domain logic too: {@code TestRenderTemplate} (data parsing, variable
 * substitution, and the MIME assembly) lives here, and the facade's templated-send paths call the
 * shared {@link #applyTemplateData}. The boundary MIME separator uses a constructor-passed
 * {@link SecureRandom} (no static instance, so no native-image run-time-init registration).
 *
 * <p>Tag validation is a shared cross-resource concern, so {@code createTemplate} calls
 * {@link SesTags#validate} rather than depending back on the facade. The template ARN parser
 * ({@code SesService.templateNameFromArn}) stays on the facade because external callers reference it
 * statically.
 */
@ApplicationScoped
public class SesTemplateService {

    private static final Logger LOG = Logger.getLogger(SesTemplateService.class);

    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{\\s*([\\w-]+)\\s*\\}\\}");

    private final StorageBackend<String, EmailTemplate> templateStore;
    private final ObjectMapper objectMapper;
    private final SecureRandom boundaryRandom;

    @Inject
    public SesTemplateService(StorageFactory storageFactory, ObjectMapper objectMapper) {
        this(storageFactory.create("ses", "ses-templates.json",
                new TypeReference<Map<String, EmailTemplate>>() {}), objectMapper, new SecureRandom());
    }

    SesTemplateService(StorageBackend<String, EmailTemplate> templateStore,
                       ObjectMapper objectMapper, SecureRandom boundaryRandom) {
        this.templateStore = templateStore;
        this.objectMapper = objectMapper;
        this.boundaryRandom = boundaryRandom;
    }

    public EmailTemplate createTemplate(EmailTemplate template, String region) {
        validateTemplate(template);
        SesTags.validate(template.getTags());
        String key = templateKey(region, template.getTemplateName());
        if (templateStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExists",
                    "Template " + template.getTemplateName() + " already exists.", 400);
        }
        Instant now = Instant.now();
        template.setCreatedTimestamp(now);
        template.setLastUpdatedTimestamp(now);
        templateStore.put(key, template);
        LOG.infov("Created SES template: {0} in region {1}", template.getTemplateName(), region);
        return template;
    }

    public EmailTemplate getTemplate(String templateName, String region) {
        return templateStore.get(templateKey(region, templateName))
                .orElseThrow(() -> new AwsException("TemplateDoesNotExist",
                        "Template " + templateName + " does not exist.", 400));
    }

    public EmailTemplate updateTemplate(EmailTemplate template, String region) {
        validateTemplate(template);
        String key = templateKey(region, template.getTemplateName());
        EmailTemplate existing = templateStore.get(key)
                .orElseThrow(() -> new AwsException("TemplateDoesNotExist",
                        "Template " + template.getTemplateName() + " does not exist.", 400));
        template.setCreatedTimestamp(existing.getCreatedTimestamp());
        template.setLastUpdatedTimestamp(Instant.now());
        // Tags are managed exclusively via Tag/UntagResource — preserve them on update (copied,
        // so the two objects never share a list instance).
        template.setTags(new ArrayList<>(existing.getTags()));
        templateStore.put(key, template);
        LOG.infov("Updated SES template: {0} in region {1}", template.getTemplateName(), region);
        return template;
    }

    public void deleteTemplate(String templateName, String region) {
        String key = templateKey(region, templateName);
        if (templateStore.get(key).isEmpty()) {
            throw new AwsException("TemplateDoesNotExist",
                    "Template " + templateName + " does not exist.", 400);
        }
        templateStore.delete(key);
        LOG.infov("Deleted SES template: {0} in region {1}", templateName, region);
    }

    public List<EmailTemplate> listTemplates(String region) {
        String prefix = "template::" + region + "::";
        List<EmailTemplate> all = new ArrayList<>(templateStore.scan(k -> k.startsWith(prefix)));
        all.sort(Comparator.comparing(EmailTemplate::getCreatedTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(EmailTemplate::getTemplateName,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return all;
    }

    /**
     * Reads a template without throwing, so the facade's ARN-dispatched tagging can look one up by
     * name and reuse this service's key derivation.
     */
    public Optional<EmailTemplate> find(String templateName, String region) {
        return templateStore.get(templateKey(region, templateName));
    }

    /**
     * Persists a template under its canonical key, so the facade's tagging can write the merged tags
     * back without owning the key derivation.
     */
    public void save(EmailTemplate template, String region) {
        templateStore.put(templateKey(region, template.getTemplateName()), template);
    }

    private static void validateTemplate(EmailTemplate template) {
        if (template == null) {
            throw new AwsException("InvalidTemplate", "Template is required.", 400);
        }
        validateTemplateName(template.getTemplateName());
        boolean hasSubject = template.getSubject() != null && !template.getSubject().isBlank();
        boolean hasText = template.getTextPart() != null && !template.getTextPart().isBlank();
        boolean hasHtml = template.getHtmlPart() != null && !template.getHtmlPart().isBlank();
        if (!hasSubject && !hasText && !hasHtml) {
            throw new AwsException("InvalidTemplate",
                    "Template must have at least a subject, text, or html part.", 400);
        }
    }

    private static void validateTemplateName(String templateName) {
        if (templateName == null || templateName.isBlank()) {
            throw new AwsException("InvalidTemplate", "TemplateName is required.", 400);
        }
        if (Character.isWhitespace(templateName.charAt(0))
                || Character.isWhitespace(templateName.charAt(templateName.length() - 1))) {
            throw new AwsException("InvalidTemplate",
                    "TemplateName must not contain leading or trailing whitespace.", 400);
        }
    }

    private static String templateKey(String region, String templateName) {
        validateTemplateName(templateName);
        return "template::" + region + "::" + templateName;
    }

    // ──────────────────────── Rendering (TestRenderTemplate + send-path substitution) ────────────────────────

    public String renderTestTemplate(String templateName, String templateDataRaw, String region) {
        EmailTemplate template = getTemplate(templateName, region);
        JsonNode templateData = parseRenderingData(objectMapper, templateDataRaw);
        String subject = applyTemplateData(template.getSubject(), templateData);
        String text = applyTemplateData(template.getTextPart(), templateData);
        String html = applyTemplateData(template.getHtmlPart(), templateData);
        return buildTestRenderMime(subject, text, html, ZonedDateTime.now(ZoneOffset.UTC), nextBoundary());
    }

    static JsonNode parseRenderingData(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AwsException("InvalidRenderingParameter",
                    "Template rendering data is required.", 400);
        }
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("InvalidRenderingParameter",
                    "Template rendering data is invalid: " + e.getOriginalMessage(), 400);
        }
        if (!node.isObject()) {
            throw new AwsException("InvalidRenderingParameter",
                    "Template rendering data must be a JSON object.", 400);
        }
        return node;
    }

    static String applyTemplateData(String text, JsonNode data) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = TEMPLATE_VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if ("amazonSESUnsubscribeUrl".equals(key)) {
                // Reserved list-management placeholder: leave it intact for post-render replacement
                // in the send path, so a templated body can carry {{amazonSESUnsubscribeUrl}} without
                // failing as a missing rendering attribute.
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            if (data == null || !data.hasNonNull(key)) {
                throw new AwsException("MissingRenderingAttribute",
                        "Attribute '" + key + "' is not present in the rendering data.", 400);
            }
            JsonNode value = data.get(key);
            String replacement = value.isValueNode() ? value.asText() : value.toString();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String buildTestRenderMime(String subject, String text, String html,
                                       ZonedDateTime date, String boundary) {
        String safeSubject = sanitizeSubject(subject);
        String safeText = text == null ? "" : text;
        String safeHtml = html == null ? "" : html;
        String dateHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(date);
        StringBuilder out = new StringBuilder();
        out.append("Date: ").append(dateHeader).append("\r\n");
        out.append("Subject: ").append(safeSubject).append("\r\n");
        out.append("MIME-Version: 1.0\r\n");
        out.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n");
        out.append("\r\n");
        appendMimePart(out, boundary, "text/plain", safeText);
        appendMimePart(out, boundary, "text/html", safeHtml);
        out.append("--").append(boundary).append("--\r\n");
        return out.toString();
    }

    private static void appendMimePart(StringBuilder out, String boundary, String mimeType, String body) {
        out.append("--").append(boundary).append("\r\n");
        out.append("Content-Type: ").append(mimeType).append("; charset=UTF-8\r\n");
        out.append("Content-Transfer-Encoding: ").append(pickTransferEncoding(body)).append("\r\n");
        out.append("\r\n");
        String normalized = normalizeToCrlf(body);
        out.append(normalized);
        if (!normalized.endsWith("\r\n")) {
            out.append("\r\n");
        }
    }

    static String normalizeToCrlf(String body) {
        return body.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
    }

    static String pickTransferEncoding(String body) {
        return body.codePoints().allMatch(c -> c < 128) ? "7bit" : "8bit";
    }

    static String sanitizeSubject(String subject) {
        if (subject == null) {
            return "";
        }
        // Strip C0 control characters (U+0000-U+001F) and DEL (U+007F): RFC 5322
        // forbids them in unstructured header field bodies. Replace with spaces so
        // visible content is preserved when template data accidentally injects them.
        StringBuilder out = new StringBuilder(subject.length());
        for (int i = 0; i < subject.length(); i++) {
            char c = subject.charAt(i);
            out.append((c < 0x20 || c == 0x7F) ? ' ' : c);
        }
        return out.toString();
    }

    private String nextBoundary() {
        byte[] bytes = new byte[6];
        boundaryRandom.nextBytes(bytes);
        return "===_floci_" + HexFormat.of().formatHex(bytes) + "_===";
    }
}
