package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.ArchivingOptions;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDimensionConfiguration;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.DeliveryOptions;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.TrackingOptions;
import io.github.hectorvent.floci.services.ses.model.SuppressionOptions;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.VdmOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Configuration sets, extracted from {@link SesService} as the next step of the store-based domain
 * split: the store, key derivation, name validation, CRUD, the domain-pure option setters, and the
 * event destinations live here.
 *
 * <p>The option validation that reads OTHER domains lives here too, with the cross-domain probes
 * (a verified domain identity for tracking, an existing dedicated IP pool for delivery) injected
 * as predicates by the facade, so the service owns every validated create and option operation
 * without gaining a peer-service dependency. The ARN-dispatched configuration-set tagging and the
 * send-time validation ({@link #validateForSending}: existence plus the sending-paused gate) live
 * here as well; the facade keeps the send-path reads (event publishing, effective suppression
 * reasons) and the tenant delete-guard around {@link #remove}.
 */
@ApplicationScoped
public class SesConfigurationSetService {

    private static final Logger LOG = Logger.getLogger(SesConfigurationSetService.class);

    private static final Pattern CONFIG_SET_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<String> VDM_FEATURE_STATES = Set.of("ENABLED", "DISABLED");

    private static final Pattern EVENT_DESTINATION_NAME_CHARS = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final int MAX_EVENT_DESTINATION_NAME_LENGTH = 64;
    private static final List<String> VALID_EVENT_TYPES = List.of(
            "SEND", "REJECT", "BOUNCE", "COMPLAINT", "DELIVERY", "OPEN", "CLICK",
            "RENDERING_FAILURE", "DELIVERY_DELAY", "SUBSCRIPTION");

    private final StorageBackend<String, ConfigurationSet> configSetStore;

    @Inject
    public SesConfigurationSetService(StorageFactory storageFactory) {
        this(storageFactory.create("ses", "ses-config-sets.json",
                new TypeReference<Map<String, ConfigurationSet>>() {}));
    }

    SesConfigurationSetService(StorageBackend<String, ConfigurationSet> configSetStore) {
        this.configSetStore = configSetStore;
    }

    /**
     * v1 CreateConfigurationSet / v2 CreateConfigurationSet: the full probe-confirmed validation
     * sequence (name, tags, suppression reasons, tracking, delivery, VDM) followed by the write.
     * The two cross-domain probes arrive as predicates from the facade.
     */
    public ConfigurationSet createConfigurationSet(ConfigurationSet configSet, String region,
                                                   Predicate<String> verifiedDomainIdentity,
                                                   Predicate<String> dedicatedIpPoolExists) {
        if (configSet == null) {
            throw new AwsException("InvalidParameterValue",
                    "ConfigurationSetName is required.", 400);
        }
        validateConfigurationSetName(configSet.getName());
        SesTags.validate(configSet.getTags());
        if (configSet.getSuppressionOptions() != null
                && configSet.getSuppressionOptions().getSuppressedReasons() != null) {
            for (String reason : configSet.getSuppressionOptions().getSuppressedReasons()) {
                if (reason == null) {
                    throw new AwsException("BadRequestException",
                            invalidSuppressionReasonMessage(null), 400);
                }
                if (!isValidSuppressionReason(reason)) {
                    throw new AwsException("BadRequestException",
                            "1 validation error detected: Value at "
                                    + "'suppressionOptions.suppressedReasons' failed to satisfy "
                                    + "constraint: Member must satisfy constraint: "
                                    + "[Member must satisfy enum value set: [BOUNCE, COMPLAINT]]",
                            400);
                }
            }
        }
        validateTrackingOptions(configSet.getTrackingOptions(), verifiedDomainIdentity);
        validateDeliveryOptions(configSet.getDeliveryOptions(), dedicatedIpPoolExists);
        validateVdmOptions(configSet.getVdmOptions());
        return create(configSet, region);
    }

    /** The raw duplicate check, timestamp, and write behind the validated create above. */
    public ConfigurationSet create(ConfigurationSet configSet, String region) {
        String key = configSetKey(region, configSet.getName());
        if (configSetStore.get(key).isPresent()) {
            throw new AwsException("ConfigurationSetAlreadyExists",
                    "Configuration set " + configSet.getName() + " already exists.", 400);
        }
        if (configSet.getCreatedTimestamp() == null) {
            configSet.setCreatedTimestamp(Instant.now());
        }
        configSetStore.put(key, configSet);
        LOG.infov("Created SES configuration set: {0} in region {1}", configSet.getName(), region);
        return configSet;
    }

    public ConfigurationSet get(String name, String region) {
        return configSetStore.get(configSetKey(region, name))
                .orElseThrow(() -> new AwsException("ConfigurationSetDoesNotExist",
                        "Configuration set <" + name + "> does not exist.", 400));
    }

    public List<ConfigurationSet> list(String region) {
        String prefix = "configSet::" + region + "::";
        List<ConfigurationSet> all = new ArrayList<>(configSetStore.scan(k -> k.startsWith(prefix)));
        all.sort(Comparator.comparing(ConfigurationSet::getCreatedTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ConfigurationSet::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return all;
    }

    /** The raw removal; existence and the tenant delete-guard are the facade's orchestration. */
    public void remove(String name, String region) {
        configSetStore.delete(configSetKey(region, name));
        LOG.infov("Deleted SES configuration set: {0} in region {1}", name, region);
    }

    /**
     * Validate that a non-blank {@code ConfigurationSetName} is usable for a send. Performs
     * two gates:
     *   1. Existence: raises {@code ConfigurationSetDoesNotExist} (400) when the set is
     *      missing in the given region. The V2 REST controller's {@code remapV1Exception}
     *      translates that into {@code NotFoundException 404}; V1 Query keeps the original.
     *   2. Sending-enabled: raises {@code ConfigurationSetSendingPausedException} (400)
     *      when the set's {@code SendingEnabled} flag has been turned off via
     *      {@code UpdateConfigurationSetSendingEnabled} (v1) /
     *      {@code PutConfigurationSetSendingOptions} (v2). The V2 controller narrows that
     *      code to {@code SendingPausedException}; V1 keeps the longer form, matching the
     *      exact wire shape AWS returns on each surface.
     * Mirrors AWS SES behaviour: invalid or paused set fails fast instead of silently
     * storing/relaying the message and skipping event publishing later.
     */
    public void validateForSending(String configurationSetName, String region) {
        if (configurationSetName == null || configurationSetName.isBlank()) {
            return;
        }
        ConfigurationSet cs = get(configurationSetName, region);
        if (cs.getSendingEnabled() != null && !cs.getSendingEnabled()) {
            throw new AwsException("ConfigurationSetSendingPausedException",
                    "Sending is paused for configuration set " + configurationSetName, 400);
        }
    }

    // The cross-domain probes (a verified domain identity for tracking, an existing dedicated IP
    // pool for delivery) are injected as predicates by the facade, so this service stays free of
    // peer-service dependencies while owning the full probe-confirmed validation sequences.
    private static final Set<String> HTTPS_POLICIES =
            Set.of("REQUIRE", "REQUIRE_OPEN_ONLY", "OPTIONAL");
    private static final Set<String> TLS_POLICIES = Set.of("REQUIRE", "OPTIONAL");


    void validateTrackingOptions(TrackingOptions options, Predicate<String> verifiedDomainIdentity) {
        Objects.requireNonNull(verifiedDomainIdentity, "verifiedDomainIdentity probe is required");
        if (options == null) {
            return;
        }
        String domain = options.getCustomRedirectDomain();
        String httpsPolicy = options.getHttpsPolicy();
        // AWS validation order (verified against real AWS 2026-06-17): a present
        // CustomRedirectDomain must be non-blank, and it is required whenever
        // HttpsPolicy is set; then the domain must be a verified domain identity
        // (checked even without HttpsPolicy); then HttpsPolicy must be a valid enum.
        if ((domain != null && domain.isBlank()) || (httpsPolicy != null && domain == null)) {
            throw new AwsException("BadRequestException",
                    "CustomRedirectDomain must be specified.", 400);
        }
        if (domain != null && !verifiedDomainIdentity.test(domain)) {
            throw new AwsException("BadRequestException",
                    "Domain <" + domain + "> is not verified under this account.", 400);
        }
        if (httpsPolicy != null && !HTTPS_POLICIES.contains(httpsPolicy)) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'httpsPolicy' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [OPTIONAL, REQUIRE, REQUIRE_OPEN_ONLY]", 400);
        }
    }

    void validateDeliveryOptions(DeliveryOptions options, Predicate<String> dedicatedIpPoolExists) {
        Objects.requireNonNull(dedicatedIpPoolExists, "dedicatedIpPoolExists probe is required");
        if (options == null) {
            return;
        }
        if (options.getTlsPolicy() != null && !TLS_POLICIES.contains(options.getTlsPolicy())) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'tlsPolicy' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [OPTIONAL, REQUIRE]", 400);
        }
        // AWS rejects a blank SendingPoolName outright, and a non-existent
        // dedicated IP pool (both verified against real AWS 2026-06-17). The
        // pool must have been created via CreateDedicatedIpPool.
        if (options.getSendingPoolName() != null) {
            if (options.getSendingPoolName().isBlank()) {
                throw new AwsException("BadRequestException",
                        "sendingPoolName can't be blank.", 400);
            }
            if (!dedicatedIpPoolExists.test(options.getSendingPoolName())) {
                throw new AwsException("BadRequestException",
                        "SendingPool <" + options.getSendingPoolName() + "> doesn't exist", 400);
            }
        }
        // AWS constrains MaxDeliverySeconds to [300, 50400] (max verified against
        // real AWS 2026-06-17; min follows the same smithy range-constraint shape).
        if (options.getMaxDeliverySeconds() != null) {
            long maxDeliverySeconds = options.getMaxDeliverySeconds();
            if (maxDeliverySeconds < 300) {
                throw new AwsException("BadRequestException",
                        "1 validation error detected: Value at 'maxDeliverySeconds' failed to satisfy constraint: "
                                + "Member must have value greater than or equal to 300", 400);
            }
            if (maxDeliverySeconds > 50400) {
                throw new AwsException("BadRequestException",
                        "1 validation error detected: Value at 'maxDeliverySeconds' failed to satisfy constraint: "
                                + "Member must have value less than or equal to 50400", 400);
            }
        }
    }

    void requireVerifiedRedirectDomain(String domain, Predicate<String> verifiedDomainIdentity) {
        Objects.requireNonNull(verifiedDomainIdentity, "verifiedDomainIdentity probe is required");
        if (domain == null) {
            throw new AwsException("ValidationError",
                    "1 validation error detected: Value at 'trackingOptions' failed to satisfy constraint: "
                            + "Member must not be null", 400);
        }
        if (domain.isBlank()) {
            throw new AwsException("InvalidTrackingOptions",
                    "At least one field of TrackingOptions must contain a value.", 400);
        }
        if (!verifiedDomainIdentity.test(domain)) {
            throw new AwsException("InvalidTrackingOptions",
                    "Domain <" + domain + "> is not verified under this account.", 400);
        }
    }

    /** v2 PutConfigurationSetTrackingOptions: validated replace of the tracking options. */
    public void setTrackingOptions(String configSetName, TrackingOptions options, String region,
                                   Predicate<String> verifiedDomainIdentity) {
        ConfigurationSet cs = get(configSetName, region);
        validateTrackingOptions(options, verifiedDomainIdentity);
        cs.setTrackingOptions(options);
        save(cs, region);
        LOG.infov("Updated TrackingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    /** v2 PutConfigurationSetDeliveryOptions: validated replace of the delivery options. */
    public void setDeliveryOptions(String configSetName, DeliveryOptions options, String region,
                                   Predicate<String> dedicatedIpPoolExists) {
        ConfigurationSet cs = get(configSetName, region);
        validateDeliveryOptions(options, dedicatedIpPoolExists);
        cs.setDeliveryOptions(options);
        save(cs, region);
        LOG.infov("Updated DeliveryOptions on configuration set {0} in region {1}", configSetName, region);
    }

    /** v1 CreateConfigurationSetTrackingOptions: rejects a set that already has a redirect domain. */
    public void createTrackingOptions(String configSetName, String customRedirectDomain, String region,
                                      Predicate<String> verifiedDomainIdentity) {
        requireVerifiedRedirectDomain(customRedirectDomain, verifiedDomainIdentity);
        ConfigurationSet cs = get(configSetName, region);
        if (cs.getTrackingOptions() != null && cs.getTrackingOptions().getCustomRedirectDomain() != null) {
            throw new AwsException("TrackingOptionsAlreadyExistsException",
                    "Configuration set <" + configSetName + "> already has tracking options.", 400);
        }
        TrackingOptions options = new TrackingOptions();
        options.setCustomRedirectDomain(customRedirectDomain);
        cs.setTrackingOptions(options);
        save(cs, region);
        LOG.infov("Created TrackingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    /** v1 UpdateConfigurationSetTrackingOptions: requires existing tracking options. */
    public void updateTrackingOptions(String configSetName, String customRedirectDomain, String region,
                                      Predicate<String> verifiedDomainIdentity) {
        requireVerifiedRedirectDomain(customRedirectDomain, verifiedDomainIdentity);
        ConfigurationSet cs = get(configSetName, region);
        if (cs.getTrackingOptions() == null || cs.getTrackingOptions().getCustomRedirectDomain() == null) {
            throw new AwsException("TrackingOptionsDoesNotExistException",
                    "There are no tracking options for configuration set <" + configSetName + ">", 400);
        }
        cs.getTrackingOptions().setCustomRedirectDomain(customRedirectDomain);
        save(cs, region);
        LOG.infov("Updated TrackingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    /** The ARN-dispatched tag operations, sharing the store behind {@code CreateConfigurationSet.Tags}. */
    public List<Tag> listTags(String name, String region) {
        return new ArrayList<>(requireForTags(name, region).getTags());
    }

    public void tag(String name, String region, List<Tag> newTags) {
        ConfigurationSet cs = requireForTags(name, region);
        cs.setTags(SesTags.merge(cs.getTags(), newTags));
        configSetStore.put(configSetKey(region, name), cs);
        LOG.infov("Tagged SES configuration set: {0} (region {1}, +{2} tags)", name, region, newTags.size());
    }

    public void untag(String name, String region, List<String> tagKeys) {
        ConfigurationSet cs = requireForTags(name, region);
        Set<String> toRemove = new HashSet<>(tagKeys);
        // Copy-on-write: the stored list may be immutable, and unlocked readers iterate it.
        List<Tag> remaining = new ArrayList<>(cs.getTags());
        remaining.removeIf(t -> toRemove.contains(t.key()));
        cs.setTags(remaining);
        configSetStore.put(configSetKey(region, name), cs);
        LOG.infov("Untagged SES configuration set: {0} (region {1}, -{2} keys)", name, region, tagKeys.size());
    }

    private ConfigurationSet requireForTags(String name, String region) {
        return configSetStore.get(configSetKey(region, name))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "No ConfigurationSet present with name: " + name, 404));
    }

    /** Reads without throwing on absence (the name is still validated, as the key derivation always
     * has), for the facade's send-path lookups. */
    public Optional<ConfigurationSet> find(String name, String region) {
        return configSetStore.get(configSetKey(region, name));
    }

    /** Persists a configuration set mutated by an orchestration that holds the loaded record. */
    public void save(ConfigurationSet configSet, String region) {
        configSetStore.put(configSetKey(region, configSet.getName()), configSet);
    }

    /** For guards that must not trip the key derivation's name validation (the tenant gate). */
    static boolean isValidName(String name) {
        return name != null && CONFIG_SET_NAME.matcher(name).matches();
    }

    // ──────────────────────── Domain-pure option setters ────────────────────────

    public void setSendingEnabled(String configSetName, boolean enabled, String region) {
        ConfigurationSet cs = get(configSetName, region);
        cs.setSendingEnabled(enabled);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated SendingEnabled on configuration set {0} in region {1}: {2}",
                configSetName, region, enabled);
    }

    public void setReputationMetricsEnabled(String configSetName, boolean metricsEnabled, String region) {
        ConfigurationSet cs = get(configSetName, region);
        cs.setReputationMetricsEnabled(metricsEnabled);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated ReputationMetricsEnabled on configuration set {0} in region {1}: {2}",
                configSetName, region, metricsEnabled);
    }

    public void deleteTrackingOptions(String configSetName, String region) {
        ConfigurationSet cs = get(configSetName, region);
        if (cs.getTrackingOptions() == null || cs.getTrackingOptions().getCustomRedirectDomain() == null) {
            throw new AwsException("TrackingOptionsDoesNotExistException",
                    "There are no tracking options for configuration set <" + configSetName + ">", 400);
        }
        cs.setTrackingOptions(null);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Deleted TrackingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    public void setArchivingOptions(String configSetName, ArchivingOptions options, String region) {
        ConfigurationSet cs = get(configSetName, region);
        cs.setArchivingOptions(options);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated ArchivingOptions on configuration set {0} in region {1}", configSetName, region);
    }

    public void setVdmOptions(String configSetName, VdmOptions options, String region) {
        ConfigurationSet cs = get(configSetName, region);
        validateVdmOptions(options);
        cs.setVdmOptions(options);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated VdmOptions on configuration set {0} in region {1}", configSetName, region);
    }

    private static void validateVdmOptions(VdmOptions options) {
        if (options == null) {
            return;
        }
        // Enum values verified against real AWS 2026-06-19; messages use the
        // nested member path and the [ENABLED, DISABLED] value set.
        if (options.getDashboardOptions() != null
                && options.getDashboardOptions().getEngagementMetrics() != null
                && !VDM_FEATURE_STATES.contains(options.getDashboardOptions().getEngagementMetrics())) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'vdmOptions.dashboardOptions.engagementMetrics' "
                            + "failed to satisfy constraint: Member must satisfy enum value set: [ENABLED, DISABLED]", 400);
        }
        if (options.getGuardianOptions() != null
                && options.getGuardianOptions().getOptimizedSharedDelivery() != null
                && !VDM_FEATURE_STATES.contains(options.getGuardianOptions().getOptimizedSharedDelivery())) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'vdmOptions.guardianOptions.optimizedSharedDelivery' "
                            + "failed to satisfy constraint: Member must satisfy enum value set: [ENABLED, DISABLED]", 400);
        }
    }

    /**
     * Stores per-configuration-set suppression overrides. Mirrors the AWS V2
     * {@code PutConfigurationSetSuppressionOptions} contract: {@code reasons} may
     * be {@code null} or empty (explicit "no filtering" for this set) or a subset
     * of {@code [BOUNCE, COMPLAINT]}. Once set, the value is returned through
     * {@link #get}; downstream callers resolve the effective reasons for a given
     * send via the facade's {@code getEffectiveSuppressedReasons}.
     */
    public void putSuppressionOptions(String configSetName, List<String> reasons, String region) {
        List<String> sanitized = new ArrayList<>();
        if (reasons != null) {
            for (String r : reasons) {
                validateSuppressionReason(r);
                sanitized.add(r);
            }
        }
        ConfigurationSet cs = get(configSetName, region);
        SuppressionOptions options = new SuppressionOptions();
        options.setSuppressedReasons(sanitized);
        cs.setSuppressionOptions(options);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated SuppressionOptions on configuration set {0} in region {1}: {2}",
                configSetName, region, sanitized);
    }

    /**
     * Validation message used by PutConfigurationSetSuppressionOptions. AWS
     * V2 SES uses a different, simpler natural-language sentence on this
     * endpoint than on the three older suppression APIs:
     *   "Reason <X> is invalid, must be one of [BOUNCE, COMPLAINT]."
     * (Verified against real AWS V2 SES on 2026-06-03.) CreateConfigurationSet
     * reports the constraint-style validation message for invalid non-null
     * values but falls back to this sentence for null elements, matching AWS
     * (verified 2026-06-13); see the facade's {@code createConfigurationSet}.
     */
    private static void validateSuppressionReason(String reason) {
        if (!isValidSuppressionReason(reason)) {
            throw new AwsException("BadRequestException",
                    invalidSuppressionReasonMessage(reason), 400);
        }
    }

    private static boolean isValidSuppressionReason(String reason) {
        return "BOUNCE".equals(reason) || "COMPLAINT".equals(reason);
    }

    private static String invalidSuppressionReasonMessage(String reason) {
        return "Reason " + reason + " is invalid, must be one of [BOUNCE, COMPLAINT].";
    }

    // ──────────────────────────── Event destinations ────────────────────────────

    public void createEventDestination(String configSetName, String eventDestinationName,
                                       EventDestination dest, String region) {
        validateEventDestinationName(eventDestinationName);
        validateEventDestination(dest);
        ConfigurationSet cs = get(configSetName, region);
        if (indexOfEventDestination(cs.getEventDestinations(), eventDestinationName) >= 0) {
            throw new AwsException("AlreadyExists",
                    "An event destination with name <" + eventDestinationName
                            + "> already exists for configuration set <" + configSetName + ">.", 400);
        }
        dest.setName(eventDestinationName);
        cs.getEventDestinations().add(dest);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Created SES event destination {0} on configuration set {1} in region {2}",
                eventDestinationName, configSetName, region);
    }

    public List<EventDestination> getEventDestinations(String configSetName, String region) {
        return List.copyOf(get(configSetName, region).getEventDestinations());
    }

    public void updateEventDestination(String configSetName, String eventDestinationName,
                                       EventDestination dest, String region) {
        validateEventDestinationName(eventDestinationName);
        validateEventDestination(dest);
        ConfigurationSet cs = get(configSetName, region);
        int index = indexOfEventDestination(cs.getEventDestinations(), eventDestinationName);
        if (index < 0) {
            throw new AwsException("NotFoundException",
                    "An event destination with name <" + eventDestinationName
                            + "> does not exist for configuration set <" + configSetName + ">.", 404);
        }
        dest.setName(eventDestinationName);
        cs.getEventDestinations().set(index, dest);
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Updated SES event destination {0} on configuration set {1} in region {2}",
                eventDestinationName, configSetName, region);
    }

    public void deleteEventDestination(String configSetName, String eventDestinationName, String region) {
        validateEventDestinationName(eventDestinationName);
        ConfigurationSet cs = get(configSetName, region);
        boolean removed = cs.getEventDestinations().removeIf(ed -> eventDestinationName.equals(ed.getName()));
        if (!removed) {
            throw new AwsException("NotFoundException",
                    "An event destination with name <" + eventDestinationName
                            + "> does not exist for configuration set <" + configSetName + ">.", 404);
        }
        configSetStore.put(configSetKey(region, configSetName), cs);
        LOG.infov("Deleted SES event destination {0} on configuration set {1} in region {2}",
                eventDestinationName, configSetName, region);
    }

    private static int indexOfEventDestination(List<EventDestination> destinations, String name) {
        for (int i = 0; i < destinations.size(); i++) {
            if (name != null && name.equals(destinations.get(i).getName())) {
                return i;
            }
        }
        return -1;
    }

    static void validateEventDestinationName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "EventDestinationName is required.", 400);
        }
        if (!EVENT_DESTINATION_NAME_CHARS.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid event destination name <" + name + ">: only alphanumeric ASCII characters, "
                            + "'_', and '-' are allowed.", 400);
        }
        if (name.length() > MAX_EVENT_DESTINATION_NAME_LENGTH) {
            throw new AwsException("InvalidParameterValue",
                    "Event destination name cannot exceed 64 characters.", 400);
        }
    }

    static void validateEventDestination(EventDestination dest) {
        if (dest == null) {
            throw new AwsException("InvalidParameterValue", "EventDestination is required.", 400);
        }
        List<String> types = dest.getMatchingEventTypes();
        if (types == null || types.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "At least one event type must be specified.", 400);
        }
        for (String t : types) {
            if (t == null || !VALID_EVENT_TYPES.contains(t)) {
                throw new AwsException("InvalidParameterValue",
                        "Invalid event type: " + t + ". Valid values are " + VALID_EVENT_TYPES + ".", 400);
            }
        }
        int destinationCount = countDestinations(dest);
        if (destinationCount == 0) {
            throw new AwsException("InvalidParameterValue", "Event destination is not provided.", 400);
        }
        if (destinationCount > 1) {
            throw new AwsException("InvalidParameterValue",
                    "Please provide only one destination with each request. Either a Firehose Destination "
                            + "or a Cloudwatch Destination or an SNS Destination or an EventBridge Destination.", 400);
        }
        if (dest.getSnsDestination() != null
                && (dest.getSnsDestination().getTopicArn() == null
                || dest.getSnsDestination().getTopicArn().isBlank())) {
            throw new AwsException("InvalidParameterValue",
                    "SnsDestination requires a non-blank TopicArn.", 400);
        }
        if (dest.getKinesisFirehoseDestination() != null
                && (dest.getKinesisFirehoseDestination().getIamRoleArn() == null
                || dest.getKinesisFirehoseDestination().getIamRoleArn().isBlank()
                || dest.getKinesisFirehoseDestination().getDeliveryStreamArn() == null
                || dest.getKinesisFirehoseDestination().getDeliveryStreamArn().isBlank())) {
            throw new AwsException("InvalidParameterValue",
                    "KinesisFirehoseDestination requires both IamRoleArn and DeliveryStreamArn.",
                    400);
        }
        if (dest.getCloudWatchDestination() != null) {
            List<CloudWatchDimensionConfiguration> dims =
                    dest.getCloudWatchDestination().getDimensionConfigurations();
            if (dims == null || dims.isEmpty()) {
                throw new AwsException("InvalidParameterValue",
                        "CloudWatch metrics dimension configuration list cannot be empty.", 400);
            }
            for (int i = 0; i < dims.size(); i++) {
                CloudWatchDimensionConfiguration dim = dims.get(i);
                if (dim == null
                        || dim.getDimensionName() == null || dim.getDimensionName().isBlank()
                        || dim.getDimensionValueSource() == null
                        || dim.getDimensionValueSource().isBlank()
                        || dim.getDefaultDimensionValue() == null
                        || dim.getDefaultDimensionValue().isBlank()) {
                    throw new AwsException("InvalidParameterValue",
                            "CloudWatchDestination dimension configurations require "
                                    + "DimensionName, DimensionValueSource, and DefaultDimensionValue "
                                    + "(missing on member " + (i + 1) + ").", 400);
                }
            }
        }
        if (dest.getPinpointDestination() != null
                && (dest.getPinpointDestination().getApplicationArn() == null
                || dest.getPinpointDestination().getApplicationArn().isBlank())) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid Pinpoint application ARN provided: "
                            + dest.getPinpointDestination().getApplicationArn() + ".", 400);
        }
    }

    private static int countDestinations(EventDestination dest) {
        int count = 0;
        if (dest.getSnsDestination() != null) {
            count++;
        }
        if (dest.getCloudWatchDestination() != null) {
            count++;
        }
        if (dest.getKinesisFirehoseDestination() != null) {
            count++;
        }
        if (dest.getEventBridgeDestination() != null) {
            count++;
        }
        if (dest.getPinpointDestination() != null) {
            count++;
        }
        return count;
    }

    private static void validateConfigurationSetName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "ConfigurationSetName is required.", 400);
        }
        if (!CONFIG_SET_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "ConfigurationSetName must be 1-64 characters and may only contain "
                            + "alphanumeric characters, underscores, and hyphens.", 400);
        }
    }

    private static String configSetKey(String region, String name) {
        validateConfigurationSetName(name);
        return "configSet::" + region + "::" + name;
    }
}
