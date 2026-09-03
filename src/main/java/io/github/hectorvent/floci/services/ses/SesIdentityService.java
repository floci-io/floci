package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Email and domain identities, extracted from {@link SesService} as the last store of the
 * store-based domain split: the store, key derivation and whitespace validation, verification
 * (email and domain), listing, MAIL FROM and notification settings, the CVET pending registration,
 * the ARN-dispatched identity tagging, and the DKIM state machine (token generation, signing
 * attributes, the Route53-backed record detection with its lookup cache) live here.
 *
 * <p>The remaining boundary follows the cross-domain seams: the default-configuration-set
 * association and the send-path reads stay in the facade and reach the store through
 * {@link #find}/{@link #save}. The facade also keeps the tenant delete-guard and the policy
 * cascade around {@link #delete}.
 */
@ApplicationScoped
public class SesIdentityService {

    private static final Logger LOG = Logger.getLogger(SesIdentityService.class);

    private static final Duration DKIM_LOOKUP_CACHE_TTL = Duration.ofSeconds(5);

    private final StorageBackend<String, Identity> identityStore;
    // Null means DNS lookup disabled: DKIM records are then never detected and statuses stay Pending.
    private final Route53Service route53Service;
    private final Clock clock;
    private final ConcurrentHashMap<String, DkimLookupCacheEntry> dkimLookupCache = new ConcurrentHashMap<>();

    @Inject
    public SesIdentityService(StorageFactory storageFactory, Route53Service route53Service, Clock clock) {
        this(storageFactory.create("ses", "ses-identities.json",
                new TypeReference<Map<String, Identity>>() {}), route53Service, clock);
    }

    SesIdentityService(StorageBackend<String, Identity> identityStore,
                       Route53Service route53Service, Clock clock) {
        this.identityStore = identityStore;
        this.route53Service = route53Service;
        this.clock = clock;
    }

    public Identity verifyEmailIdentity(String emailAddress, String region) {
        validateIdentityWhitespace(emailAddress, "Email address");
        if (emailAddress == null || emailAddress.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Email address is required.", 400);
        }
        String key = identityKey(region, emailAddress);
        Identity existing = identityStore.get(key).orElse(null);
        if (existing != null) {
            return existing;
        }

        Identity identity = new Identity(emailAddress, "EmailAddress");
        identityStore.put(key, identity);
        LOG.infov("Verified email identity: {0} in region {1}", emailAddress, region);
        return identity;
    }

    public Identity verifyDomainIdentity(String domain, String region) {
        validateIdentityWhitespace(domain, "Domain");
        if (domain == null || domain.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Domain is required.", 400);
        }
        String key = identityKey(region, domain);
        Identity existing = identityStore.get(key).orElse(null);
        if (existing != null) {
            return existing;
        }

        Identity identity = new Identity(domain, "Domain");
        regenerateDkimTokens(identity);
        identity.setVerificationStatus("Pending");
        identity.setDkimEnabled(true);
        // The create response reports DKIM verification as NotStarted (SES hasn't begun tracking the
        // CNAMEs yet); the first Get/List refresh transitions it to Pending. Matches AWS, where
        // CreateEmailIdentity returns NOT_STARTED but a subsequent GetEmailIdentity returns PENDING.
        identity.setDkimVerificationStatus("NotStarted");
        identityStore.put(key, identity);
        LOG.infov("Verified domain identity: {0} in region {1}", domain, region);
        return identity;
    }

    public Identity getIdentityVerificationAttributes(String identityValue, String region) {
        Identity identity = identityStore.get(identityKey(region, identityValue)).orElse(null);
        return refreshIdentityState(identity, region);
    }

    /** True when the address itself or its parent domain is a verified identity. */
    public boolean isVerifiedSender(String fromEmail, String region) {
        if (fromEmail == null) {
            return false;
        }
        if (isIdentityVerified(fromEmail, region)) {
            return true;
        }
        int at = fromEmail.indexOf('@');
        return at >= 0 && at < fromEmail.length() - 1
                && isIdentityVerified(fromEmail.substring(at + 1), region);
    }

    private boolean isIdentityVerified(String identity, String region) {
        Identity id = getIdentityVerificationAttributes(identity, region);
        return id != null && "Success".equals(id.getVerificationStatus());
    }

    public List<Identity> listIdentities(String identityType, String region) {
        List<Identity> all = identityStore.scan(k -> k.startsWith(keyPrefix(region)));
        if (identityType == null || identityType.isBlank()) {
            return all;
        }
        return all.stream()
                .filter(i -> identityType.equals(i.getIdentityType()))
                .toList();
    }

    public List<String> getVerifiedEmailAddresses(String region) {
        List<Identity> all = identityStore.scan(k -> k.startsWith(keyPrefix(region)));
        List<String> emails = new ArrayList<>();
        for (Identity identity : all) {
            if ("EmailAddress".equals(identity.getIdentityType())
                    && "Success".equals(identity.getVerificationStatus())) {
                emails.add(identity.getIdentity());
            }
        }
        return emails;
    }

    /**
     * Deletes the identity's record and any aliased records whose stored identity value matches,
     * and invalidates the DKIM lookup cache. The facade wraps this with the tenant delete-guard
     * and the sending-authorization policy cascade.
     */
    public void delete(String identityValue, String region) {
        identityStore.delete(identityKey(region, identityValue));
        invalidateDkimLookupCache(region, identityValue);

        List<String> keys = new ArrayList<>(identityStore.keys().stream()
                .filter(k -> k.startsWith(keyPrefix(region)))
                .toList());
        for (String storedKey : keys) {
            Identity storedIdentity = identityStore.get(storedKey).orElse(null);
            if (storedIdentity != null && identityValue.equals(storedIdentity.getIdentity())) {
                identityStore.delete(storedKey);
            }
        }
    }

    public void setMailFromDomain(String identityValue, String mailFromDomain,
                                  String behaviorOnMxFailure, String region) {
        String normalizedBehavior = null;
        if (behaviorOnMxFailure != null) {
            if (!"UseDefaultValue".equals(behaviorOnMxFailure)
                    && !"RejectMessage".equals(behaviorOnMxFailure)) {
                throw new AwsException("ValidationError",
                        "1 validation error detected: Value at 'behaviorOnMXFailure' failed to satisfy "
                                + "constraint: Member must satisfy enum value set: [RejectMessage, UseDefaultValue]", 400);
            }
            normalizedBehavior = behaviorOnMxFailure;
        }
        boolean clearing = mailFromDomain == null || mailFromDomain.isEmpty();
        if (!clearing && mailFromDomain.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "MailFromDomain must be a domain or an empty string to clear; whitespace is not accepted.", 400);
        }
        String key = identityKey(region, identityValue);
        Identity identity = identityStore.get(key)
                .orElseThrow(() -> new AwsException("InvalidParameterValue",
                        "Identity <" + identityValue + "> does not exist.", 400));
        identity.setMailFromDomain(clearing ? null : mailFromDomain);
        identity.setMailFromDomainStatus(clearing ? "Pending" : "Success");
        if (clearing) {
            identity.setBehaviorOnMxFailure("UseDefaultValue");
        } else if (normalizedBehavior != null) {
            identity.setBehaviorOnMxFailure(normalizedBehavior);
        }
        identityStore.put(key, identity);
        LOG.infov("Updated MAIL FROM domain for {0}: domain={1}, behavior={2}",
                identityValue, mailFromDomain, normalizedBehavior);
    }

    public Identity getMailFromAttributes(String identityValue, String region) {
        return identityStore.get(identityKey(region, identityValue)).orElse(null);
    }

    /** Registers the CVET recipient as a pending email identity when it is not already known. */
    public void markPendingEmailIdentity(String emailAddress, String region) {
        String key = identityKey(region, emailAddress);
        if (identityStore.get(key).isEmpty()) {
            Identity identity = new Identity(emailAddress, "EmailAddress");
            identity.setVerificationStatus("Pending");
            identityStore.put(key, identity);
            LOG.infov("SES custom verification email registered pending identity {0} in region {1}",
                    emailAddress, region);
        }
    }

    static final List<String> NOTIFICATION_TYPES = List.of("Bounce", "Complaint", "Delivery");

    public void setIdentityNotificationTopic(String identityValue, String notificationType,
                                             String snsTopic, String region) {
        Identity identity = identityStore.get(identityKey(region, identityValue))
                .orElseThrow(() -> new AwsException("InvalidParameterValue",
                        "Identity does not exist: " + identityValue, 400));
        if (snsTopic != null && !snsTopic.isBlank()) {
            identity.getNotificationAttributes().put(notificationType + "Topic", snsTopic);
        } else {
            identity.getNotificationAttributes().remove(notificationType + "Topic");
        }
        identityStore.put(identityKey(region, identityValue), identity);
    }

    public Identity getIdentityNotificationAttributes(String identityValue, String region) {
        return identityStore.get(identityKey(region, identityValue)).orElse(null);
    }

    public void setFeedbackForwardingEnabled(String identityValue, boolean enabled, String region) {
        Identity identity = identityStore.get(identityKey(region, identityValue))
                .orElseThrow(() -> new AwsException("InvalidParameterValue",
                        "Identity " + identityValue
                                + " is invalid. Must be a verified email address or domain.", 400));
        identity.setFeedbackForwardingEnabled(enabled);
        identityStore.put(identityKey(region, identityValue), identity);
        LOG.infov("Updated feedback forwarding for {0}: enabled={1}", identityValue, enabled);
    }

    public void setHeadersInNotificationsEnabled(String identityValue, String notificationType,
                                                 boolean enabled, String region) {
        if (notificationType == null || notificationType.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "NotificationType is required.", 400);
        }
        if (!NOTIFICATION_TYPES.contains(notificationType)) {
            throw new AwsException("ValidationError",
                    "1 validation error detected: Value at 'notificationType' failed to satisfy "
                            + "constraint: Member must satisfy enum value set: "
                            + NOTIFICATION_TYPES, 400);
        }
        Identity identity = identityStore.get(identityKey(region, identityValue))
                .orElseThrow(() -> new AwsException("InvalidParameterValue",
                        "Identity " + identityValue
                                + " is invalid. It must be a verified email address or domain.", 400));
        identity.getHeadersInNotificationsEnabled().put(notificationType, enabled);
        identityStore.put(identityKey(region, identityValue), identity);
        LOG.infov("Updated headers-in-notifications for {0}: {1}={2}",
                identityValue, notificationType, enabled);
    }

    public List<Tag> listTags(String identityValue, String region) {
        return new ArrayList<>(requireForTags(identityValue, region).getTags());
    }

    public void tag(String identityValue, String region, List<Tag> newTags) {
        Identity identity = requireForTags(identityValue, region);
        identity.setTags(SesTags.merge(identity.getTags(), newTags));
        identityStore.put(identityKey(region, identityValue), identity);
        LOG.infov("Tagged SES identity: {0} (region {1}, +{2} tags)", identityValue, region, newTags.size());
    }

    public void untag(String identityValue, String region, List<String> tagKeys) {
        Identity identity = requireForTags(identityValue, region);
        Set<String> toRemove = new HashSet<>(tagKeys);
        // Copy-on-write: the stored list may be immutable, and unlocked readers iterate it.
        List<Tag> remaining = new ArrayList<>(identity.getTags());
        remaining.removeIf(t -> toRemove.contains(t.key()));
        identity.setTags(remaining);
        identityStore.put(identityKey(region, identityValue), identity);
        LOG.infov("Untagged SES identity: {0} (region {1}, -{2} keys)", identityValue, region, tagKeys.size());
    }

    public void setTags(String identityValue, String region, List<Tag> tags) {
        SesTags.validate(tags);
        Identity identity = requireForTags(identityValue, region);
        identity.setTags(tags);
        identityStore.put(identityKey(region, identityValue), identity);
    }

    private Identity requireForTags(String identityValue, String region) {
        return identityStore.get(identityKey(region, identityValue))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No EmailIdentity present with name: " + identityValue, 404));
    }

    // ──────────────────────── DKIM (second stage) ────────────────────────

    public void setDkimAttributes(String identityValue, boolean signingEnabled, String region) {
        if (identityValue == null || identityValue.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Identity is required.", 400);
        }
        // DKIM is a domain concept and an email reports its parent domain's DKIM (via effectiveDkimSource),
        // so toggling DKIM on an email whose parent domain is a registered identity is a no-op that leaves
        // the domain untouched, matching real AWS, regardless of whether the email identity itself exists.
        if (identityValue.contains("@")) {
            String domain = identityValue.substring(identityValue.indexOf('@') + 1);
            if (identityStore.get(identityKey(region, domain)).isPresent()) {
                return;
            }
        }
        Identity identity = identityStore.get(identityKey(region, identityValue)).orElse(null);
        if (identity == null) {
            String domain = identityValue.contains("@")
                    ? identityValue.substring(identityValue.indexOf('@') + 1)
                    : identityValue;
            // v1-native code; the v2 controller remaps InvalidParameterValue -> BadRequestException.
            throw new AwsException("InvalidParameterValue",
                    "Domain " + domain + " is not verified for DKIM signing.", 400);
        }

        // Only toggle the signing flag. DkimVerificationStatus tracks DNS record detection (via the
        // Route53 lookup in refreshIdentityState), not the enabled flag, matching real AWS, where
        // SetIdentityDkimEnabled / PutEmailIdentityDkimAttributes leave the verification status alone.
        identity.setDkimEnabled(signingEnabled);
        identityStore.put(identityKey(region, identityValue), identity);
        LOG.infov("Updated DKIM attributes for {0}: signingEnabled={1}", identityValue, signingEnabled);
    }

    private List<String> generateDkimTokens() {
        List<String> tokens = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            tokens.add(UUID.randomUUID().toString().replace("-", ""));
        }
        return tokens;
    }

    /**
     * Generates a fresh Easy DKIM token set and records the key length / generation timestamp. New
     * tokens mean the previously published CNAMEs no longer match, so the verification status resets
     * to Pending (re-detected via the Route53 lookup) and the origin returns to AWS_SES. Only meaningful
     * for domain identities; refreshIdentityState re-upgrades to Success once the new records exist.
     */
    private void regenerateDkimTokens(Identity identity) {
        identity.setDkimTokens(generateDkimTokens());
        identity.setDkimCurrentSigningKeyLength(identity.getDkimNextSigningKeyLength());
        identity.setDkimLastKeyGenerationTimestamp(Instant.now(clock));
        identity.setDkimSigningAttributesOrigin("AWS_SES");
        // The new tokens' CNAMEs aren't detected yet, so DKIM verification resets to Pending. The
        // identity's own verification is NOT revoked by a key rotation (matching AWS): keep Success
        // when already verified; a not-yet-verified identity stays Pending.
        identity.setDkimVerificationStatus("Pending");
        if (!"Success".equals(identity.getVerificationStatus())) {
            identity.setVerificationStatus("Pending");
        }
    }

    /**
     * v1 VerifyDomainDkim: returns the domain identity's DKIM tokens (3), generating them if needed.
     * Tokens are stable across calls (AWS does not regenerate them). The domain is registered as a
     * pending identity if it does not exist yet, matching AWS's lenient behavior (VerifyDomainDkim
     * starts DKIM setup for any domain).
     */
    public List<String> verifyDomainDkim(String domain, String region) {
        validateIdentityWhitespace(domain, "Domain");
        if (domain == null || domain.isBlank()) {
            throw new AwsException("InvalidParameterValue", "Domain is required.", 400);
        }
        if (domain.contains("@")) {
            // Domain-only action: an email-shaped value must not create an email-valued "Domain".
            throw new AwsException("InvalidParameterValue", "Domain " + domain + " is invalid.", 400);
        }
        Identity identity = identityStore.get(identityKey(region, domain)).orElse(null);
        if (identity == null) {
            identity = new Identity(domain, "Domain");
            identity.setVerificationStatus("Pending");
            identity.setDkimEnabled(true);
            identity.setDkimVerificationStatus("Pending");
        }
        if (!hasDkimTokens(identity)) {
            regenerateDkimTokens(identity);
        }
        identityStore.put(identityKey(region, domain), identity);
        LOG.infov("VerifyDomainDkim: {0} (region {1})", domain, region);
        return identity.getDkimTokens();
    }

    /**
     * v2 PutEmailIdentityDkimSigningAttributes. AWS_SES (Easy DKIM): sets the next signing key length
     * and regenerates tokens when the length changes. EXTERNAL (BYODKIM): switches the origin and
     * clears the Easy DKIM tokens (the caller publishes its own selector, which Floci does not use for
     * signing). Returns the resulting DKIM status and tokens.
     */
    public DkimSigningResult putDkimSigningAttributes(String identityValue, String origin,
                                                      String signingSelector, String nextKeyLength,
                                                      String region) {
        // DKIM signing attributes are domain-level; AWS rejects a missing/blank value or an
        // email-address identity here (verified: all return the same 400 "must be a valid domain")
        // rather than mutating state that the email would just inherit back from its parent domain.
        if (identityValue == null || identityValue.isBlank() || identityValue.contains("@")) {
            throw new AwsException("BadRequestException",
                    "The EmailIdentity value must be a valid domain.", 400);
        }
        Identity identity = identityStore.get(identityKey(region, identityValue))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Email identity " + identityValue + " does not exist.", 404));
        if ("EXTERNAL".equals(origin)) {
            identity.setDkimSigningAttributesOrigin("EXTERNAL");
            // Clear the Easy DKIM tokens and reset the status: Floci can't verify a BYODKIM selector,
            // so leaving a prior Success/Pending with no tokens (and no Route53 detection path) would
            // be inconsistent.
            identity.setDkimTokens(new ArrayList<>());
            identity.setDkimVerificationStatus("Pending");
            LOG.infov("PutEmailIdentityDkimSigningAttributes(EXTERNAL): {0} selector={1}",
                    identityValue, signingSelector);
        } else {
            identity.setDkimSigningAttributesOrigin("AWS_SES");
            // DKIM tokens are a domain concept; only (re)generate them for a domain identity.
            if ("Domain".equals(identity.getIdentityType())) {
                if (nextKeyLength != null && !nextKeyLength.equals(identity.getDkimCurrentSigningKeyLength())) {
                    identity.setDkimNextSigningKeyLength(nextKeyLength);
                    regenerateDkimTokens(identity);
                } else if (!hasDkimTokens(identity)) {
                    regenerateDkimTokens(identity);
                }
            }
            LOG.infov("PutEmailIdentityDkimSigningAttributes(AWS_SES): {0} keyLength={1}",
                    identityValue, nextKeyLength);
        }
        identityStore.put(identityKey(region, identityValue), refreshIdentityState(identity, region));
        Identity src = effectiveDkimSource(identity, region);
        return new DkimSigningResult(src.getDkimVerificationStatus(),
                src.getDkimTokens() == null ? List.of() : src.getDkimTokens());
    }

    /** Carrier for the PutEmailIdentityDkimSigningAttributes response ({@code dkimStatus} is v1-native). */
    public record DkimSigningResult(String dkimStatus, List<String> dkimTokens) {}

    /**
     * Resolves which identity's DKIM state should be reported for {@code identity}. A domain reports
     * its own DKIM; an email address reports its parent domain's DKIM (SigningEnabled / Status /
     * Tokens all inherit from the domain), matching AWS. Falls back to the identity itself when the
     * parent domain is not a registered identity.
     */
    public Identity effectiveDkimSource(Identity identity, String region) {
        if (identity == null || !"EmailAddress".equals(identity.getIdentityType())) {
            return identity;
        }
        String addr = identity.getIdentity();
        int at = addr == null ? -1 : addr.indexOf('@');
        if (at < 0 || at == addr.length() - 1) {
            return identity;
        }
        Identity domainIdentity = identityStore.get(identityKey(region, addr.substring(at + 1))).orElse(null);
        return domainIdentity == null ? identity : refreshIdentityState(domainIdentity, region);
    }

    Identity refreshIdentityState(Identity identity, String region) {
        if (identity == null) {
            return null;
        }

        boolean changed = false;
        if ("Domain".equals(identity.getIdentityType()) && identity.getDkimTokens() == null) {
            regenerateDkimTokens(identity);
            changed = true;
        }

        if ("Domain".equals(identity.getIdentityType()) && hasDkimTokens(identity)) {
            changed |= normalizePendingDomainState(identity);
            // Upgrade identity- and DKIM-verification independently so that, e.g., after a key rotation
            // (which resets only DkimVerificationStatus while the identity stays verified), the DKIM
            // status can still return to Success once the new records are detected.
            // Only look up DNS when a status can still be upgraded: skip the (cached) Route53 check
            // once both identity- and DKIM-verification are already Success. DKIM verification tracks
            // DNS detection independently of the signing-enabled flag, so it can reach Success even
            // when DKIM signing is disabled, and can re-pend/re-upgrade after a key rotation.
            boolean needsUpgrade = !"Success".equals(identity.getVerificationStatus())
                    || !"Success".equals(identity.getDkimVerificationStatus());
            if (needsUpgrade && hasAllExpectedDkimRecords(identity, region)) {
                if (!"Success".equals(identity.getVerificationStatus())) {
                    identity.setVerificationStatus("Success");
                    changed = true;
                }
                if (!"Success".equals(identity.getDkimVerificationStatus())) {
                    identity.setDkimVerificationStatus("Success");
                    changed = true;
                }
            }
        }

        if ("Success".equals(identity.getVerificationStatus())) {
            invalidateDkimLookupCache(region, identity.getIdentity());
        }

        if (changed) {
            save(identity, region);
        }
        return identity;
    }

    private boolean hasDkimTokens(Identity identity) {
        return identity.getDkimTokens() != null && !identity.getDkimTokens().isEmpty();
    }

    private boolean normalizePendingDomainState(Identity identity) {
        boolean changed = false;
        if (!"Success".equals(identity.getVerificationStatus())
                && !"Pending".equals(identity.getVerificationStatus())) {
            identity.setVerificationStatus("Pending");
            changed = true;
        }
        // DKIM verification tracks DNS detection, not the signing-enabled flag, so a domain that has
        // begun tracking (NotStarted -> Pending) reports Pending on Get even while signing is disabled.
        if (!"Success".equals(identity.getDkimVerificationStatus())
                && !"Pending".equals(identity.getDkimVerificationStatus())) {
            identity.setDkimVerificationStatus("Pending");
            changed = true;
        }
        return changed;
    }

    private boolean hasAllExpectedDkimRecords(Identity identity, String region) {
        if (route53Service == null) {
            return false;
        }
        Instant now = Instant.now(clock);
        String cacheKey = dkimLookupCacheKey(region, identity);
        DkimLookupCacheEntry cached = dkimLookupCache.get(cacheKey);
        if (cached != null) {
            if (now.isBefore(cached.expiresAt())) {
                return cached.present();
            }
            dkimLookupCache.remove(cacheKey, cached);
        }

        boolean present = true;
        for (String token : identity.getDkimTokens()) {
            if (!hasExpectedDkimRecord(identity.getIdentity(), token)) {
                present = false;
                break;
            }
        }
        dkimLookupCache.put(cacheKey, new DkimLookupCacheEntry(present, now.plus(DKIM_LOOKUP_CACHE_TTL)));
        return present;
    }

    private boolean hasExpectedDkimRecord(String domain, String token) {
        String expectedName = normalizeDnsName(token + "._domainkey." + domain);
        String expectedValue = normalizeDnsName(token + ".dkim.amazonses.com");
        for (HostedZone zone : route53Service.listHostedZones(null, Integer.MAX_VALUE)) {
            for (ResourceRecordSet recordSet : route53Service.listResourceRecordSets(zone.getId(), null, null,
                    Integer.MAX_VALUE)) {
                if (!"CNAME".equalsIgnoreCase(recordSet.getType())) {
                    continue;
                }
                if (!expectedName.equals(normalizeDnsName(recordSet.getName()))) {
                    continue;
                }
                List<ResourceRecord> records = recordSet.getRecords();
                if (records == null) {
                    continue;
                }
                for (ResourceRecord record : records) {
                    if (record != null && expectedValue.equals(normalizeDnsName(record.getValue()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String normalizeDnsName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void invalidateDkimLookupCache(String region, String identityValue) {
        if (identityValue == null || identityValue.isBlank()) {
            return;
        }
        String cachePrefix = region + "::" + normalizeDnsName(identityValue) + "::";
        dkimLookupCache.keySet().removeIf(key -> key.startsWith(cachePrefix));
    }

    private String dkimLookupCacheKey(String region, Identity identity) {
        List<String> normalizedTokens = identity.getDkimTokens().stream()
                .map(this::normalizeDnsName)
                .sorted()
                .toList();
        return region + "::" + normalizeDnsName(identity.getIdentity()) + "::" + String.join(",", normalizedTokens);
    }

    private record DkimLookupCacheEntry(boolean present, Instant expiresAt) {}

    /**
     * Reads an identity without throwing, so the facade's default-configuration-set association and
     * send-path reads can keep their own error contracts.
     */
    public Optional<Identity> find(String identityValue, String region) {
        return identityStore.get(identityKey(region, identityValue));
    }

    /** Persists an identity mutated by the facade's cross-domain flows. */
    public void save(Identity identity, String region) {
        identityStore.put(identityKey(region, identity.getIdentity()), identity);
    }

    private static String keyPrefix(String region) {
        return "identity::" + region + "::";
    }

    static String identityKey(String region, String identity) {
        validateIdentityWhitespace(identity, "Identity");
        return "identity::" + region + "::" + identity;
    }

    static void validateIdentityWhitespace(String identity, String fieldName) {
        if (identity == null || identity.isBlank()) {
            return;
        }
        if (Character.isWhitespace(identity.charAt(0)) || Character.isWhitespace(identity.charAt(identity.length() - 1))) {
            throw new AwsException("InvalidParameterValue", fieldName + " must not contain leading or trailing whitespace.", 400);
        }
    }
}
