package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
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

    /** Every stored message across regions, oldest first. */
    public List<SentEmail> listAll() {
        return emailStore.scan(k -> k.startsWith("email::")).stream()
                .sorted(Comparator.comparing(SentEmail::getSentAt,
                        Comparator.nullsFirst(Comparator.<Instant>naturalOrder())))
                .toList();
    }

    /** Removes the message with this id from whichever region holds it; an unknown id removes nothing. */
    public void delete(String messageId) {
        List<String> keys = emailStore.keys().stream()
                .filter(key -> messageId.equals(messageIdOf(key)))
                .toList();
        keys.forEach(emailStore::delete);
        LOG.infov("Deleted SES email {0} ({1} record(s))", messageId, keys.size());
    }

    public void clear() {
        emailStore.clear();
        LOG.info("Cleared all SES emails");
    }

    private static String emailKey(String region, String messageId) {
        return "email::" + region + "::" + messageId;
    }

    // The inverse of emailKey: a region never contains the separator, so the id follows the second.
    private static String messageIdOf(String key) {
        if (!key.startsWith("email::")) {
            return null;
        }
        int separator = key.indexOf("::", "email::".length());
        return separator < 0 ? null : key.substring(separator + 2);
    }
}
