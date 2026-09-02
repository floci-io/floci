package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.ArchivingOptions;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDimensionConfiguration;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.SuppressionOptions;
import io.github.hectorvent.floci.services.ses.model.VdmOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuration sets, extracted from {@link SesService} as the next step of the store-based domain
 * split: the store, key derivation, name validation, CRUD, the domain-pure option setters, and the
 * event destinations live here.
 *
 * <p>The boundary follows the cross-domain seams: option validation that reads OTHER domains stays
 * in the facade (tracking options check a verified domain identity, delivery options check a
 * dedicated IP pool), which validates first (or resolves existence through {@link #get}) and then
 * mutates through {@link #save}. The facade also keeps the send-path reads (pause check, event
 * publishing, effective suppression reasons), the ARN-dispatched tagging, and the tenant
 * delete-guard around {@link #remove}.
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
     * Stores a validated configuration set. The option validation runs in the facade first, since
     * the cross-domain pieces (tracking's verified-domain check, delivery's dedicated-pool check)
     * can't live here, so this owns only the duplicate check, the timestamp, and the write.
     */
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

    /** Reads without throwing on absence (the name is still validated, as the key derivation always
     * has), for the facade's send-path and tagging lookups. */
    public Optional<ConfigurationSet> find(String name, String region) {
        return configSetStore.get(configSetKey(region, name));
    }

    /** Persists a configuration set the facade mutated (cross-domain option setters, tagging). */
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

    static void validateVdmOptions(VdmOptions options) {
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

    static boolean isValidSuppressionReason(String reason) {
        return "BOUNCE".equals(reason) || "COMPLAINT".equals(reason);
    }

    static String invalidSuppressionReasonMessage(String reason) {
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

    static void validateConfigurationSetName(String name) {
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
