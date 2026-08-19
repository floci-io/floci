package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Email templates (the {@code templateStore}), extracted from {@link SesService} as part of the
 * store-based domain split. Reached through the {@code SesService} facade, which delegates the CRUD
 * here; the facade's templated-send path reads templates back through {@link #getTemplate}, and its
 * ARN-dispatched tagging reads/writes through {@link #find} / {@link #save}.
 *
 * <p>Tag validation is a shared cross-resource concern, so {@code createTemplate} calls
 * {@link SesTags#validate} rather than depending back on the facade. The template ARN parser
 * ({@code SesService.templateNameFromArn}) stays on the facade because external callers reference it
 * statically.
 */
@ApplicationScoped
public class SesTemplateService {

    private static final Logger LOG = Logger.getLogger(SesTemplateService.class);

    private final StorageBackend<String, EmailTemplate> templateStore;

    @Inject
    public SesTemplateService(StorageFactory storageFactory) {
        this.templateStore = storageFactory.create("ses", "ses-templates.json",
                new TypeReference<Map<String, EmailTemplate>>() {});
    }

    SesTemplateService(StorageBackend<String, EmailTemplate> templateStore) {
        this.templateStore = templateStore;
    }

    public EmailTemplate createTemplate(EmailTemplate template, String region) {
        validateTemplate(template);
        if (template.getTags() != null) {
            for (Tag tag : template.getTags()) {
                SesTags.validate(tag);
            }
        }
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
        // Tags are managed exclusively via Tag/UntagResource — preserve them on update.
        template.setTags(existing.getTags());
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
}
