package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.CustomVerificationEmailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage for custom verification email templates (the {@code cvetStore}), extracted from
 * {@link SesService} as the third step of the store-based domain split.
 *
 * <p>This owns only the persistence and the concurrency guard. The cross-domain parts stay in the
 * {@link SesService} facade, which orchestrates them: {@code validateCustomVerificationTemplate}
 * (its "From must be a verified identity" check reaches the Identity domain) runs in the facade
 * before create/update delegate here, and {@code sendCustomVerificationEmail} stays in the facade
 * and only reads a template through {@link #find}. This is the facade-as-coordinator model:
 * a domain used by the send path is not turned into a cross-service dependency.
 */
@ApplicationScoped
public class SesCvetService {

    private static final Logger LOG = Logger.getLogger(SesCvetService.class);

    private final StorageBackend<String, CustomVerificationEmailTemplate> cvetStore;
    // Serializes custom-verification-template create/update/delete check-then-write so concurrent
    // creates for the same name can't both succeed and an update can't resurrect a concurrently
    // deleted template.
    private final Object cvetMutationLock = new Object();

    @Inject
    public SesCvetService(StorageFactory storageFactory) {
        this.cvetStore = storageFactory.create("ses", "ses-custom-verification-templates.json",
                new TypeReference<Map<String, CustomVerificationEmailTemplate>>() {});
    }

    SesCvetService(StorageBackend<String, CustomVerificationEmailTemplate> cvetStore) {
        this.cvetStore = cvetStore;
    }

    public void createCustomVerificationEmailTemplate(CustomVerificationEmailTemplate template, String region) {
        String key = cvetKey(region, template.getTemplateName());
        // Lock only the check-then-put so concurrent creates for the same name can't both observe
        // the key as absent; the facade's validation and this logging stay outside the lock.
        synchronized (cvetMutationLock) {
            if (cvetStore.get(key).isPresent()) {
                // v1-native code (verified: CustomVerificationEmailTemplateAlreadyExists / 400);
                // remapV1Exception translates it to AlreadyExistsException / 400 for the v2 boundary.
                throw new AwsException("CustomVerificationEmailTemplateAlreadyExists",
                        "Custom verification email template <" + template.getTemplateName() + "> already exists", 400);
            }
            cvetStore.put(key, template);
        }
        LOG.infov("Created custom verification email template {0} in region {1}",
                template.getTemplateName(), region);
    }

    public CustomVerificationEmailTemplate getCustomVerificationEmailTemplate(String templateName, String region) {
        return cvetStore.get(cvetKey(region, templateName)).orElseThrow(() -> cvetNotFound(templateName));
    }

    public List<CustomVerificationEmailTemplate> listCustomVerificationEmailTemplates(String region) {
        String prefix = "cvet::" + region + "::";
        return cvetStore.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(CustomVerificationEmailTemplate::getTemplateName,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    public void updateCustomVerificationEmailTemplate(CustomVerificationEmailTemplate template, String region) {
        String key = cvetKey(region, template.getTemplateName());
        // Guard the existence check and the put together so a concurrent delete can't slip between
        // them and have the update resurrect the just-deleted template.
        synchronized (cvetMutationLock) {
            if (cvetStore.get(key).isEmpty()) {
                throw cvetNotFound(template.getTemplateName());
            }
            cvetStore.put(key, template);
        }
        LOG.infov("Updated custom verification email template {0} in region {1}",
                template.getTemplateName(), region);
    }

    public void deleteCustomVerificationEmailTemplate(String templateName, String region) {
        String key = cvetKey(region, templateName);
        // Guard the check-then-delete on the same lock as create/update so the three mutations
        // serialize against each other.
        synchronized (cvetMutationLock) {
            if (cvetStore.get(key).isEmpty()) {
                throw cvetNotFound(templateName);
            }
            cvetStore.delete(key);
        }
        LOG.infov("Deleted custom verification email template {0} in region {1}", templateName, region);
    }

    /**
     * Looks up a template without throwing, so the facade's {@code sendCustomVerificationEmail} can
     * raise its own send-specific "does not exist" message rather than the CRUD one.
     */
    public Optional<CustomVerificationEmailTemplate> find(String templateName, String region) {
        return cvetStore.get(cvetKey(region, templateName));
    }

    private static AwsException cvetNotFound(String templateName) {
        // v1-native code (verified: CustomVerificationEmailTemplateDoesNotExist / 400).
        // SesController.remapV1Exception translates it to NotFoundException / 404 for the v2 boundary.
        return new AwsException("CustomVerificationEmailTemplateDoesNotExist",
                "Custom verification email template <" + templateName + "> does not exist", 400);
    }

    private static String cvetKey(String region, String templateName) {
        return "cvet::" + region + "::" + templateName;
    }
}
