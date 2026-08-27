package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Suppression: the account-level suppression attributes and the per-address suppression list,
 * extracted from {@link SesService} as the sixth step of the store-based domain split.
 *
 * <p>New facet: this resolves the shared-helper deferral called out in the account step. The
 * account-suppression and suppression-list sub-domains share {@code validateSuppressionReason}, so
 * rather than splitting one off, both stores move together into this service and the helper becomes
 * a private detail of it. The send path keeps its cross-domain orchestration
 * ({@code getEffectiveSuppressedReasons} reads a configuration set's options or falls back here;
 * {@code collectSuppressedReasons}/{@code resolveSuppressionReason} filter a send) in the
 * {@link SesService} facade, which reads entries back through {@link #findSuppressedDestination}.
 */
@ApplicationScoped
public class SesSuppressionService {

    private static final Logger LOG = Logger.getLogger(SesSuppressionService.class);

    private final StorageBackend<String, SuppressedDestination> suppressionStore;
    private final StorageBackend<String, AccountSuppressionAttributes> accountSuppressionStore;

    @Inject
    public SesSuppressionService(StorageFactory storageFactory) {
        this.suppressionStore = storageFactory.create("ses", "ses-suppression.json",
                new TypeReference<Map<String, SuppressedDestination>>() {});
        this.accountSuppressionStore = storageFactory.create("ses", "ses-account-suppression.json",
                new TypeReference<Map<String, AccountSuppressionAttributes>>() {});
    }

    SesSuppressionService(StorageBackend<String, SuppressedDestination> suppressionStore,
                          StorageBackend<String, AccountSuppressionAttributes> accountSuppressionStore) {
        this.suppressionStore = suppressionStore;
        this.accountSuppressionStore = accountSuppressionStore;
    }

    // ──────────────────── Account-level suppression attributes ────────────────────

    public AccountSuppressionAttributes getAccountSuppressionAttributes(String region) {
        return accountSuppressionStore.get(accountSuppressionKey(region))
                .orElseGet(SesSuppressionService::defaultAccountSuppressionAttributes);
    }

    private static AccountSuppressionAttributes defaultAccountSuppressionAttributes() {
        // Fresh SES accounts default to auto-suppression on both BOUNCE and COMPLAINT;
        // an explicit PUT (including an empty list) overrides this.
        AccountSuppressionAttributes attrs = new AccountSuppressionAttributes();
        attrs.setSuppressedReasons(new ArrayList<>(List.of("BOUNCE", "COMPLAINT")));
        return attrs;
    }

    public void putAccountSuppressionAttributes(String region, List<String> suppressedReasons) {
        List<String> sanitized = new ArrayList<>();
        if (suppressedReasons != null) {
            for (String r : suppressedReasons) {
                validateSuppressionReason(r, "suppressedReasons", true);
                sanitized.add(r);
            }
        }
        AccountSuppressionAttributes attrs = new AccountSuppressionAttributes();
        attrs.setSuppressedReasons(sanitized);
        accountSuppressionStore.put(accountSuppressionKey(region), attrs);
        LOG.infov("Updated account suppression attributes for region {0}: {1}", region, sanitized);
    }

    private static String accountSuppressionKey(String region) {
        return "account-suppression::" + region;
    }

    // ──────────────────────────── Suppression list ────────────────────────────

    public void putSuppressedDestination(String region, String emailAddress, String reason) {
        String normalized = normalizeSuppressionEmail(emailAddress);
        validateSuppressionReason(reason, "reason", false);
        String key = suppressionKey(region, normalized);
        SuppressionMatch match = existingSuppressionMatch(region, emailAddress, normalized).orElse(null);
        SuppressedDestination entry = match != null ? match.entry() : new SuppressedDestination(normalized, reason);
        entry.setEmailAddress(normalized);
        entry.setReason(reason);
        entry.setLastUpdateTime(Instant.now());
        // Write the canonical key first, then drop a legacy key it migrated from,
        // so a failed write can't lose the entry. The legacy form was persisted by
        // a pre-canonicalization Floci (trim-only key); migrating it avoids leaving
        // a stuck duplicate after a re-PUT.
        suppressionStore.put(key, entry);
        if (match != null && !match.key().equals(key)) {
            suppressionStore.delete(match.key());
        }
        LOG.infov("Suppressed destination {0} in region {1} (reason={2})", normalized, region, reason);
    }

    public SuppressedDestination getSuppressedDestination(String region, String emailAddress) {
        String normalized = normalizeSuppressionEmail(emailAddress);
        return existingSuppressionMatch(region, emailAddress, normalized)
                .map(SuppressionMatch::entry)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Email address " + normalized + " does not exist on your suppression list.",
                        404));
    }

    public void deleteSuppressedDestination(String region, String emailAddress) {
        String normalized = normalizeSuppressionEmail(emailAddress);
        SuppressionMatch match = existingSuppressionMatch(region, emailAddress, normalized)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Email address " + normalized + " does not exist on your suppression list.",
                        404));
        suppressionStore.delete(match.key());
        LOG.infov("Removed suppression entry for {0} in region {1}", normalized, region);
    }

    /**
     * Reads a suppression entry without throwing, so the facade's send-path filters
     * ({@code collectSuppressedReasons} / {@code resolveSuppressionReason}) can look one up by raw
     * address and share this service's normalization and legacy-key fallback.
     */
    public Optional<SuppressedDestination> findSuppressedDestination(String region, String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeSuppressionEmail(rawEmail);
        return existingSuppressionMatch(region, rawEmail, normalized).map(SuppressionMatch::entry);
    }

    /** A suppression entry together with the storage key it currently lives under. */
    private record SuppressionMatch(String key, SuppressedDestination entry) {
    }

    /**
     * Resolve a suppression entry by its canonical (domain-lower-cased) key, falling
     * back to the legacy raw-trimmed key used by a pre-canonicalization Floci. Returns
     * the entry and the key it was found under in a single read per candidate, so
     * callers don't re-fetch the store.
     */
    private Optional<SuppressionMatch> existingSuppressionMatch(String region, String rawEmail, String normalized) {
        String canonical = suppressionKey(region, normalized);
        Optional<SuppressedDestination> hit = suppressionStore.get(canonical);
        if (hit.isPresent()) {
            return Optional.of(new SuppressionMatch(canonical, hit.get()));
        }
        String legacy = suppressionKey(region, rawEmail.trim());
        if (!legacy.equals(canonical)) {
            Optional<SuppressedDestination> legacyHit = suppressionStore.get(legacy);
            if (legacyHit.isPresent()) {
                return Optional.of(new SuppressionMatch(legacy, legacyHit.get()));
            }
        }
        return Optional.empty();
    }

    public List<SuppressedDestination> listSuppressedDestinations(String region, List<String> reasonFilters) {
        Set<String> filters = new HashSet<>();
        if (reasonFilters != null) {
            for (String r : reasonFilters) {
                if (r != null && !r.isBlank()) {
                    validateSuppressionReason(r, "reasons", true);
                    filters.add(r);
                }
            }
        }
        String prefix = "suppression::" + region + "::";
        List<SuppressedDestination> all = new ArrayList<>(suppressionStore.scan(k -> k.startsWith(prefix)));
        all.sort(Comparator.comparing(SuppressedDestination::getLastUpdateTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SuppressedDestination::getEmailAddress,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        if (filters.isEmpty()) {
            return all;
        }
        return all.stream()
                .filter(s -> filters.contains(s.getReason()))
                .toList();
    }

    private static String suppressionKey(String region, String emailAddress) {
        return "suppression::" + region + "::" + emailAddress;
    }

    private static String normalizeSuppressionEmail(String emailAddress) {
        if (emailAddress == null || emailAddress.isBlank()) {
            throw new AwsException("BadRequestException", "EmailAddress is required.", 400);
        }
        // AWS trims the EmailAddress and canonicalizes only the domain to lower
        // case; the local-part keeps its case. Verified against real AWS SES V2
        // (2026-06-15): `Foo@Example.COM` and `Foo@example.com` collapse to one
        // suppression entry (`Foo@example.com`), but `Foo@x` and `foo@x` are two
        // distinct entries. Lower-casing the whole address would wrongly merge
        // local-part variants and alter the stored value on read-back.
        // Locale.ROOT avoids the JVM-locale Turkish-i pitfall.
        String trimmed = emailAddress.trim();
        int at = trimmed.lastIndexOf('@');
        if (at < 0) {
            return trimmed;
        }
        return trimmed.substring(0, at) + "@" + trimmed.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Validation message used by PutAccountSuppressionAttributes, PutSuppressedDestination, and
     * ListSuppressedDestinations — all three return the AWS "1 validation error detected: Value at
     * '<fieldName>' failed to satisfy constraint: ..." V1-style nested message verbatim. (Verified
     * against real AWS V2 SES on 2026-06-03.) The {@code nested} flag controls whether the inner enum
     * constraint is wrapped in {@code Member must satisfy constraint: [...]} — PutSuppressedDestination
     * (single Reason field) returns the unwrapped form; the two list-bearing APIs return the wrapped
     * form. Shared by the two sub-domains above, which is why they were extracted together.
     */
    private static void validateSuppressionReason(String reason, String fieldName, boolean nested) {
        if (reason == null || (!"BOUNCE".equals(reason) && !"COMPLAINT".equals(reason))) {
            String constraint = nested
                    ? "Member must satisfy constraint: [Member must satisfy enum value set: [BOUNCE, COMPLAINT]]"
                    : "Member must satisfy enum value set: [BOUNCE, COMPLAINT]";
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at '" + fieldName + "' failed to satisfy constraint: "
                            + constraint, 400);
        }
    }
}
