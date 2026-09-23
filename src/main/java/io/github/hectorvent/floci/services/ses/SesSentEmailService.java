package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sent-email records (the {@code emailStore}), extracted from {@link SesService} as part of the
 * store-based domain split. This is the send path's output sink: the facade's send methods keep the
 * whole send orchestration and only hand the finished record here through {@link #record}, while the
 * send-statistics and inspection endpoints read it back through {@link #countInRegion} /
 * {@link #listAll} / {@link #clear}. Nothing else reads the store, so it is a clean leaf.
 */
@ApplicationScoped
public class SesSentEmailService {

    private static final Logger LOG = Logger.getLogger(SesSentEmailService.class);

    private final StorageBackend<String, SentEmail> emailStore;

    @Inject
    public SesSentEmailService(StorageFactory storageFactory) {
        this.emailStore = storageFactory.create("ses", "ses-emails.json",
                new TypeReference<Map<String, SentEmail>>() {});
    }

    SesSentEmailService(StorageBackend<String, SentEmail> emailStore) {
        this.emailStore = emailStore;
    }

    public void record(String region, String messageId, SentEmail email) {
        emailStore.put(emailKey(region, messageId), email);
    }

    /**
     * One stored message, for {@code GetMessageInsights}. Empty when the region has no record under
     * that id; the caller owns the AWS-shaped error.
     */
    public Optional<SentEmail> find(String region, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return emailStore.get(emailKey(region, messageId));
    }

    public long countInRegion(String region) {
        String prefix = "email::" + region + "::";
        return emailStore.scan(k -> k.startsWith(prefix)).size();
    }

    /**
     * Every stored message for one region, for the metric aggregation behind BatchGetMetricData.
     */
    public List<SentEmail> listInRegion(String region) {
        String prefix = "email::" + region + "::";
        return emailStore.scan(k -> k.startsWith(prefix));
    }

    public List<SentEmail> listAll() {
        return emailStore.scan(k -> k.startsWith("email::"));
    }

    public void clear() {
        emailStore.clear();
        LOG.info("Cleared all SES emails");
    }

    private static String emailKey(String region, String messageId) {
        return "email::" + region + "::" + messageId;
    }
}
