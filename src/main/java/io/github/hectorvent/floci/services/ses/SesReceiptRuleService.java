package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.ReceiptRuleSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Owns the SES receipt-rule-set domain (the {@code receiptRuleSetStore}). Extracted from
 * {@link SesService} as the first step of the store-based domain split: a leaf
 * domain with no cross-service coupling, reached only through the {@code SesService} facade, which
 * now delegates its {@code *ReceiptRuleSet*} methods here.
 *
 * <p>Floci has no inbound-mail endpoint, so receipt rule sets are stored inertly: a set never holds
 * any rules and routes no mail. They exist only so the management API round-trips (enough to unblock
 * tools such as Terraform that declare a rule set during bootstrap).
 */
@ApplicationScoped
public class SesReceiptRuleService {

    private static final Logger LOG = Logger.getLogger(SesReceiptRuleService.class);

    // These RuleSetName constraints are not in the botocore model: service-2.json (SES 2010-12-01)
    // declares ReceiptRuleSetName as a bare {"type": "string"} with no pattern or length. They were
    // established by probing real SES in us-west-2 via boto3 (2026-08): a character outside
    // ^[a-zA-Z0-9_.-]+$ is a Smithy ValidationError, and a name that is >64 chars or does not
    // start/end with an alphanumeric is a service-level "Not a valid ruleSetName" InvalidParameterValue.
    // Re-verify against live SES (not the model, which can't confirm it) if these ever need to change.
    private static final Pattern RULE_SET_NAME_CHARS = Pattern.compile("^[a-zA-Z0-9_.-]+$");

    private final StorageBackend<String, ReceiptRuleSet> receiptRuleSetStore;
    // Serializes receipt-rule-set create (check-then-put) and set-active (clear-then-set) so the
    // one-active-per-region invariant and duplicate-name rejection hold under concurrency.
    private final Object receiptRuleSetLock = new Object();
    private final Clock clock;

    @Inject
    public SesReceiptRuleService(StorageFactory storageFactory, Clock clock) {
        this.receiptRuleSetStore = storageFactory.create("ses", "ses-receipt-rule-sets.json",
                new TypeReference<Map<String, ReceiptRuleSet>>() {});
        this.clock = clock;
    }

    SesReceiptRuleService(StorageBackend<String, ReceiptRuleSet> receiptRuleSetStore, Clock clock) {
        this.receiptRuleSetStore = receiptRuleSetStore;
        this.clock = clock;
    }

    public ReceiptRuleSet createReceiptRuleSet(String name, String region) {
        requireRuleSetName(name);
        String key = receiptRuleSetKey(region, name);
        ReceiptRuleSet ruleSet = new ReceiptRuleSet(name, Instant.now(clock));
        synchronized (receiptRuleSetLock) {
            if (receiptRuleSetStore.get(key).isPresent()) {
                throw new AwsException("AlreadyExists", "Rule set already exists: " + name, 400);
            }
            receiptRuleSetStore.put(key, ruleSet);
        }
        LOG.infov("Created SES receipt rule set: {0} in region {1}", name, region);
        return ruleSet;
    }

    public ReceiptRuleSet describeReceiptRuleSet(String name, String region) {
        requireRuleSetName(name);
        return receiptRuleSetStore.get(receiptRuleSetKey(region, name))
                .orElseThrow(() -> ruleSetDoesNotExist(name));
    }

    public List<ReceiptRuleSet> listReceiptRuleSets(String region) {
        String prefix = "receiptRuleSet::" + region + "::";
        List<ReceiptRuleSet> all = new ArrayList<>(receiptRuleSetStore.scan(k -> k.startsWith(prefix)));
        all.sort(Comparator.comparing(ReceiptRuleSet::getCreatedTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ReceiptRuleSet::getName, Comparator.nullsLast(Comparator.naturalOrder())));
        return all;
    }

    public void deleteReceiptRuleSet(String name, String region) {
        requireRuleSetName(name);
        // Hold the lock so the active-check-then-delete is atomic and a concurrent set-active/clear
        // (which scans and re-puts active sets) can't resurrect the rule set we just deleted.
        synchronized (receiptRuleSetLock) {
            ReceiptRuleSet existing = receiptRuleSetStore.get(receiptRuleSetKey(region, name)).orElse(null);
            if (existing != null && existing.isActive()) {
                // AWS rejects deleting the active rule set (verified: CannotDelete / 400).
                throw new AwsException("CannotDelete", "Cannot delete active rule set: " + name, 400);
            }
            // AWS is idempotent otherwise: deleting a non-existent rule set succeeds without error.
            receiptRuleSetStore.delete(receiptRuleSetKey(region, name));
        }
        LOG.infov("Deleted SES receipt rule set: {0} in region {1}", name, region);
    }

    public void setActiveReceiptRuleSet(String name, String region) {
        // No RuleSetName clears the account's active rule set (matches AWS).
        boolean clearOnly = name == null || name.isBlank();
        if (!clearOnly) {
            requireRuleSetName(name);
        }
        synchronized (receiptRuleSetLock) {
            if (!clearOnly) {
                ReceiptRuleSet target = receiptRuleSetStore.get(receiptRuleSetKey(region, name))
                        .orElseThrow(() -> ruleSetDoesNotExist(name));
                clearActiveReceiptRuleSet(region);
                target.setActive(true);
                receiptRuleSetStore.put(receiptRuleSetKey(region, name), target);
            } else {
                clearActiveReceiptRuleSet(region);
            }
        }
        if (clearOnly) {
            LOG.infov("Cleared active SES receipt rule set in region {0}", region);
        } else {
            LOG.infov("Set active SES receipt rule set: {0} in region {1}", name, region);
        }
    }

    public ReceiptRuleSet describeActiveReceiptRuleSet(String region) {
        String prefix = "receiptRuleSet::" + region + "::";
        // Read under the lock so a concurrent set-active replacement (clear-then-set) can't expose its
        // intermediate no-active state — the reader sees either the old or the new active set.
        synchronized (receiptRuleSetLock) {
            return receiptRuleSetStore.scan(k -> k.startsWith(prefix)).stream()
                    .filter(ReceiptRuleSet::isActive)
                    .findFirst()
                    .orElse(null);
        }
    }

    private void clearActiveReceiptRuleSet(String region) {
        String prefix = "receiptRuleSet::" + region + "::";
        for (ReceiptRuleSet rs : receiptRuleSetStore.scan(k -> k.startsWith(prefix))) {
            if (rs.isActive()) {
                rs.setActive(false);
                receiptRuleSetStore.put(receiptRuleSetKey(region, rs.getName()), rs);
            }
        }
    }

    private static void requireRuleSetName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "RuleSetName is required.", 400);
        }
        if (!RULE_SET_NAME_CHARS.matcher(name).matches()) {
            throw new AwsException("ValidationError",
                    "1 validation error detected: Value at 'ruleSetName' failed to satisfy constraint: "
                            + "Member must satisfy regular expression pattern: ^[a-zA-Z0-9_.-]+$", 400);
        }
        if (name.length() > 64
                || !Character.isLetterOrDigit(name.charAt(0))
                || !Character.isLetterOrDigit(name.charAt(name.length() - 1))) {
            throw new AwsException("InvalidParameterValue", "Not a valid ruleSetName: " + name, 400);
        }
    }

    private static AwsException ruleSetDoesNotExist(String name) {
        return new AwsException("RuleSetDoesNotExist", "Rule set does not exist: " + name, 400);
    }

    private static String receiptRuleSetKey(String region, String name) {
        return "receiptRuleSet::" + region + "::" + name;
    }
}
