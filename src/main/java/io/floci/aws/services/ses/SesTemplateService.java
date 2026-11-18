package io.floci.aws.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ses.mime.SesMimeEncoding;
import io.floci.aws.services.ses.mime.SesMimeEncoding.TransferEncoding;
import io.floci.aws.services.ses.model.EmailContent;
import io.floci.aws.services.ses.model.EmailTemplate;
import io.floci.aws.services.ses.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Email templates (the {@code templateStore}), extracted from {@link SesService} as part of the
 * store-based domain split. Reached through the {@code SesService} facade, which delegates the CRUD
 * here, along with the ARN-dispatched template tagging; the facade's templated-send path reads
 * templates back through {@link #getTemplate}.
 *
 * <p>Template rendering is domain logic too: {@code TestRenderTemplate} (data parsing, variable
 * substitution, and the MIME assembly) lives here, and the facade's templated-send paths call the
 * shared {@link #applyTemplateData}. The boundary MIME separator uses a constructor-passed
 * {@link SecureRandom} (no static instance, so no native-image run-time-init registration).
 *
 * <p>Tag validation is a shared cross-resource concern, so {@code createTemplate} calls
 * {@link SesTags#validate} rather than depending back on the facade.
 */
@ApplicationScoped
public class SesTemplateService implements SesTaggable {

    private static final Logger LOG = Logger.getLogger(SesTemplateService.class);

    // The triple-stash form comes first so its braces are consumed with the variable. SES renders
    // both forms verbatim, without HTML escaping (probed on us-west-2, 2026-11-17).
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile(
            "\\{\\{\\{\\s*([\\w-]+)\\s*\\}\\}\\}|\\{\\{\\s*([\\w-]+)\\s*\\}\\}");
    private static final DateTimeFormatter RENDER_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSX", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Pattern SUBJECT_WHITESPACE_RUN = Pattern.compile("[ \\t\\r\\n]+");
    static final String UNSUBSCRIBE_PLACEHOLDER = "{{amazonSESUnsubscribeUrl}}";
    // What TestRenderTemplate shows in place of the list-management link a send would carry.
    private static final String TEST_RENDER_UNSUBSCRIBE_URL = "http://unsubscribe.example.com";

    private final StorageBackend<String, EmailTemplate> templateStore;
    private final ObjectMapper objectMapper;
    private final SecureRandom boundaryRandom;
    private final Clock clock;

    @Inject
    public SesTemplateService(StorageFactory storageFactory, ObjectMapper objectMapper, Clock clock) {
        this(storageFactory.create("ses", "ses-templates.json",
                new TypeReference<Map<String, EmailTemplate>>() {}), objectMapper, new SecureRandom(), clock);
    }

    SesTemplateService(StorageBackend<String, EmailTemplate> templateStore,
                       ObjectMapper objectMapper, SecureRandom boundaryRandom, Clock clock) {
        this.templateStore = templateStore;
        this.objectMapper = objectMapper;
        this.boundaryRandom = boundaryRandom;
        this.clock = clock;
    }

    public EmailTemplate createTemplate(EmailTemplate template, String region, String accountId) {
        validateTemplate(template);
        dropEmptyParts(template);
        SesTags.validate(template.getTags());
        String key = templateKey(region, template.getTemplateName());
        if (templateStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExists",
                    "Template " + template.getTemplateName() + " already exists for account id " + accountId, 400);
        }
        Instant now = Instant.now(clock);
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

    EmailContent.InlineTemplate inline(EmailContent.Template template, String region) {
        EmailTemplate stored = getTemplate(template.templateName(), region);
        return new EmailContent.InlineTemplate(stored.getSubject(), stored.getTextPart(),
                stored.getHtmlPart(), template.templateData(), template.headers());
    }

    public EmailTemplate updateTemplate(EmailTemplate template, String region) {
        validateTemplate(template);
        dropEmptyParts(template);
        String key = templateKey(region, template.getTemplateName());
        EmailTemplate existing = templateStore.get(key)
                .orElseThrow(() -> new AwsException("TemplateDoesNotExist",
                        "Template " + template.getTemplateName() + " does not exist.", 400));
        template.setCreatedTimestamp(existing.getCreatedTimestamp());
        template.setLastUpdatedTimestamp(Instant.now(clock));
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

    public PaginatedResult<EmailTemplate> listTemplates(String region, SesListPaging paging, Integer pageSize,
                                                        String nextToken) {
        return paging.page(region, listTemplates(region), SesTemplateService::cursor, pageSize, nextToken);
    }

    /** Newest first, as SES lists them; the paged lists resume on the same order. */
    public List<EmailTemplate> listTemplates(String region) {
        String prefix = "template::" + region + "::";
        return templateStore.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(SesTemplateService::cursor))
                .toList();
    }

    private static String cursor(EmailTemplate template) {
        return SesListPaging.newestFirst(template.getCreatedTimestamp(), template.getTemplateName());
    }

    /**
     * Reads a template without throwing, for the facade's orchestration lookups (the tenant
     * association check and the send-path reads).
     */
    public Optional<EmailTemplate> find(String templateName, String region) {
        return templateStore.get(templateKey(region, templateName));
    }

    /**
     * Persists a template under its canonical key, so facade orchestration that mutates a found
     * template can write it back without owning the key derivation.
     */
    public void save(EmailTemplate template, String region) {
        templateStore.put(templateKey(region, template.getTemplateName()), template);
    }

    private static void validateTemplate(EmailTemplate template) {
        if (template == null) {
            throw new AwsException("InvalidTemplate", "Template is required.", 400);
        }
        validateTemplateName(template.getTemplateName());
        // SES asks for the subject first, then for a part, and a blank value counts as missing.
        if (template.getSubject() == null || template.getSubject().isBlank()) {
            throw new AwsException("InvalidParameterValue", "The subject must be specified.", 400);
        }
        boolean hasText = template.getTextPart() != null && !template.getTextPart().isBlank();
        boolean hasHtml = template.getHtmlPart() != null && !template.getHtmlPart().isBlank();
        if (!hasText && !hasHtml) {
            throw new AwsException("InvalidParameterValue",
                    "Template must specify at least one of the parts.", 400);
        }
    }

    /**
     * SES stores an empty part as no part: a template sent with {@code Text: ""} beside an HTML
     * part reads back without {@code Text}. A whitespace-only part beside another one is kept.
     */
    private static void dropEmptyParts(EmailTemplate template) {
        if (template.getTextPart() != null && template.getTextPart().isEmpty()) {
            template.setTextPart(null);
        }
        if (template.getHtmlPart() != null && template.getHtmlPart().isEmpty()) {
            template.setHtmlPart(null);
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

    /**
     * Extracts the template name from an SES template ARN of the form
     * {@code arn:aws:ses:<region>:<account>:template/<name>}. Region and
     * account segments are not validated; only the {@code template/<name>}
     * suffix is required.
     */
    public static String templateNameFromArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TemplateArn is required.", 400);
        }
        int marker = arn.indexOf(":template/");
        if (!AwsArnUtils.looksLikeArn(arn) || marker < 0) {
            throw new AwsException("InvalidParameterValue",
                    "TemplateArn is not a valid SES template ARN: " + arn, 400);
        }
        String name = arn.substring(marker + ":template/".length());
        if (name.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "TemplateArn is missing a template name: " + arn, 400);
        }
        return name;
    }

    /** The ARN-dispatched tag operations, sharing the store behind {@code CreateEmailTemplate.Tags}. */
    @Override
    public List<Tag> listTags(String name, String region) {
        return new ArrayList<>(requireForTags(name, region).getTags());
    }

    @Override
    public void tag(String name, String region, List<Tag> newTags) {
        EmailTemplate template = requireForTags(name, region);
        template.setTags(SesTags.merge(template.getTags(), newTags));
        templateStore.put(templateKey(region, name), template);
        LOG.infov("Tagged SES template: {0} (region {1}, +{2} tags)", name, region, newTags.size());
    }

    @Override
    public void untag(String name, String region, List<String> tagKeys) {
        EmailTemplate template = requireForTags(name, region);
        Set<String> toRemove = new HashSet<>(tagKeys);
        // Copy-on-write: the stored list may be immutable, and unlocked readers iterate it.
        List<Tag> remaining = new ArrayList<>(template.getTags());
        remaining.removeIf(t -> toRemove.contains(t.key()));
        template.setTags(remaining);
        templateStore.put(templateKey(region, name), template);
        LOG.infov("Untagged SES template: {0} (region {1}, -{2} keys)", name, region, tagKeys.size());
    }

    private EmailTemplate requireForTags(String name, String region) {
        return templateStore.get(templateKey(region, name))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No Template present with name: " + name, 404));
    }

    // ──────────────────────── Rendering (TestRenderTemplate + send-path substitution) ────────────────────────

    public String renderTestTemplate(String templateName, String templateDataRaw, String region,
                                     String accountId) {
        // The rendering data is read before the template is looked up: both wrong answers the data error.
        JsonNode templateData = parseRenderingData(objectMapper, templateDataRaw);
        // TestRenderTemplate words a missing template its own way, unlike the template CRUD.
        EmailTemplate template = templateStore.get(templateKey(region, templateName))
                .orElseThrow(() -> new AwsException("TemplateDoesNotExist",
                        "Unable to find <" + templateName + "> for <" + accountId + ">", 400));
        String subject = applyTemplateData(template.getSubject(), templateData, TEST_RENDER_UNSUBSCRIBE_URL);
        String text = renderStoredPart(template.getTextPart(), templateData);
        String html = renderStoredPart(template.getHtmlPart(), templateData);
        return buildTestRenderMime(subject, text, html, Instant.now(clock), nextBoundary());
    }

    /**
     * Null when the template has no such part. A stored part stays a part of the message whatever
     * it renders to: SES writes a whitespace-only part, and one whose variables render to nothing.
     */
    private static String renderStoredPart(String stored, JsonNode templateData) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        return applyTemplateData(stored, templateData, TEST_RENDER_UNSUBSCRIBE_URL);
    }

    static JsonNode parseRenderingData(ObjectMapper mapper, String raw) {
        if (raw == null) {
            throw new AwsException("InvalidRenderingParameter",
                    "Template rendering data is required.", 400);
        }
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            LOG.debugv("Template rendering data is not JSON: {0}", e.getOriginalMessage());
            throw invalidRenderingData();
        }
        // An empty string and a JSON value that is not an object get the same answer as malformed JSON.
        if (node == null || !node.isObject()) {
            throw invalidRenderingData();
        }
        return node;
    }

    private static AwsException invalidRenderingData() {
        return new AwsException("InvalidRenderingParameter", "Template rendering data is invalid", 400);
    }

    static EmailContent.Simple render(EmailContent.InlineTemplate template) {
        return new EmailContent.Simple(
                applyTemplateData(template.subject(), template.templateData()),
                applyTemplateData(template.textPart(), template.templateData()),
                applyTemplateData(template.htmlPart(), template.templateData()),
                template.headers());
    }

    /** Send-path substitution: the list-management placeholder stays, in its canonical form, for the send to fill. */
    static String applyTemplateData(String text, JsonNode data) {
        return applyTemplateData(text, data, UNSUBSCRIBE_PLACEHOLDER);
    }

    static String applyTemplateData(String text, JsonNode data, String unsubscribeReplacement) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = TEMPLATE_VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if ("amazonSESUnsubscribeUrl".equals(key)) {
                // Reserved list-management placeholder, never a missing rendering attribute. The
                // triple-stash and spaced spellings are the same placeholder.
                matcher.appendReplacement(out, Matcher.quoteReplacement(unsubscribeReplacement));
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

    /**
     * A template with both a text and an HTML part renders as multipart/alternative. One with a
     * single part renders as that part alone, with nothing after its encoded body. A null part
     * is absent; an empty or blank one is written as it is.
     */
    static String buildTestRenderMime(String subject, String text, String html,
                                       Instant date, String boundary) {
        boolean hasText = text != null;
        boolean hasHtml = html != null;
        StringBuilder out = new StringBuilder();
        out.append("Date: ").append(RENDER_DATE.format(date)).append("\r\n");
        out.append("Subject: ").append(SesMimeEncoding.encodeSubject(sanitizeSubject(subject))).append("\r\n");
        out.append("MIME-Version: 1.0\r\n");
        if (hasText && hasHtml) {
            out.append("Content-Type: multipart/alternative; \r\n\tboundary=\"").append(boundary).append("\"\r\n");
            out.append("\r\n");
            out.append("--").append(boundary).append("\r\n");
            appendEntity(out, "text/plain", text);
            out.append("\r\n--").append(boundary).append("\r\n");
            appendEntity(out, "text/html", html);
            out.append("\r\n--").append(boundary).append("--\r\n");
        } else if (hasHtml) {
            appendEntity(out, "text/html", html);
        } else {
            appendEntity(out, "text/plain", text == null ? "" : text);
        }
        return out.toString();
    }

    private static void appendEntity(StringBuilder out, String mimeType, String body) {
        byte[] octets = body.getBytes(StandardCharsets.UTF_8);
        TransferEncoding transferEncoding = TransferEncoding.of(octets);
        out.append("Content-Type: ").append(mimeType).append("; charset=UTF-8\r\n");
        out.append("Content-Transfer-Encoding: ").append(transferEncoding.headerValue()).append("\r\n");
        out.append("\r\n");
        out.append(transferEncoding.encode(octets));
    }

    static String sanitizeSubject(String subject) {
        if (subject == null) {
            return "";
        }
        // SES writes every run of spaces, tabs and line breaks in the Subject as one space, so a
        // value cannot break the header. Any other control character stays and is written inside
        // an encoded word.
        return SUBJECT_WHITESPACE_RUN.matcher(subject).replaceAll(" ");
    }

    private String nextBoundary() {
        return "----=_Part_" + boundaryRandom.nextInt(10_000) + "_" + boundaryRandom.nextInt(Integer.MAX_VALUE)
                + "." + clock.millis();
    }
}
