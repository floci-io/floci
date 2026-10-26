package io.github.hectorvent.floci.services.autoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.autoscaling.model.*;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.Ec2UserDataDecoder;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.iam.PassedRoleLookup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class AutoScalingService implements PassedRoleLookup {

    /** The tag Auto Scaling puts on every instance it launches or attaches, valued with the group's name. */
    static final String GROUP_NAME_TAG = "aws:autoscaling:groupName";

    private static final Logger LOG = Logger.getLogger(AutoScalingService.class);
    private static final String FULLY_FORMED_LAUNCH_TEMPLATE = "You must use a valid fully-formed launch template. ";
    static final String MISSING_LAUNCH_TEMPLATE_IMAGE_ID_MESSAGE =
            FULLY_FORMED_LAUNCH_TEMPLATE + "The request must contain the parameter ImageId";
    static final String INVALID_LAUNCH_TEMPLATE_MESSAGE =
            "The specified launch template does not exist.";
    static final String INVALID_LAUNCH_CONFIGURATION_PARAMETERS_MESSAGE =
            "Valid requests must contain either the InstanceID parameter "
                    + "or both the ImageId and InstanceType parameters.";
    static final Set<String> DESIRED_CAPACITY_TYPES = Set.of("units", "vcpu", "memory-mib");
    static final int MIN_MAX_INSTANCE_LIFETIME_SECONDS = 86400;
    static final String INVALID_DESIRED_CAPACITY_TYPE_MESSAGE =
            "The specified value for DesiredCapacityType is not valid. Valid values are: units, vcpu, memory-mib.";
    static final String INVALID_MAX_INSTANCE_LIFETIME_MESSAGE =
            "MaxInstanceLifetime must be equal to 0 or a value greater than or equal to 86400 seconds.";
    static final String INVALID_DEFAULT_INSTANCE_WARMUP_MESSAGE =
            "DefaultInstanceWarmup must be greater than or equal to 0, or -1 to remove a previously set value.";
    static final String INSTANCE_REQUIREMENTS_AND_INSTANCE_TYPE_MESSAGE =
            "A launch template override must not specify both InstanceType and InstanceRequirements.";
    static final String DEFAULT_DESIRED_CAPACITY_TYPE = "units";
    static final String INSTANCE_REQUIREMENTS_MISSING_RANGES_MESSAGE =
            "You must specify VCpuCount and MemoryMiB when you specify InstanceRequirements "
                    + "on a launch template override.";
    static final String DESIRED_CAPACITY_TYPE_NEEDS_INSTANCE_REQUIREMENTS_MESSAGE =
            "Amazon EC2 Auto Scaling supports DesiredCapacityType for attribute-based instance type "
                    + "selection only. Specify InstanceRequirements on a MixedInstancesPolicy launch "
                    + "template override, or use the default DesiredCapacityType of units.";
    static final String INSTANCE_MAINTENANCE_POLICY_BOTH_REQUIRED_MESSAGE =
            "Both MinHealthyPercentage and MaxHealthyPercentage must be specified in InstanceMaintenancePolicy.";
    static final String INVALID_MIN_HEALTHY_PERCENTAGE_MESSAGE =
            "MinHealthyPercentage must be between 0 and 100, or -1 to clear a previously set value.";
    static final String INVALID_MAX_HEALTHY_PERCENTAGE_MESSAGE =
            "MaxHealthyPercentage must be between 100 and 200, or -1 to clear a previously set value.";
    static final String INSTANCE_MAINTENANCE_POLICY_PARTIAL_CLEAR_MESSAGE =
            "To clear an InstanceMaintenancePolicy, specify -1 for both MinHealthyPercentage and MaxHealthyPercentage.";
    static final String INSTANCE_MAINTENANCE_POLICY_RANGE_MESSAGE =
            "The difference between MaxHealthyPercentage and MinHealthyPercentage cannot be greater than 100.";
    static final Set<String> CAPACITY_DISTRIBUTION_STRATEGIES =
            Set.of("balanced-only", "balanced-best-effort", "reservations-then-balanced");
    static final String INVALID_CAPACITY_DISTRIBUTION_STRATEGY_MESSAGE =
            "The specified value for CapacityDistributionStrategy is not valid. Valid values are: "
                    + "balanced-only, balanced-best-effort, reservations-then-balanced.";
    static final Set<String> CAPACITY_RESERVATION_PREFERENCES =
            Set.of("capacity-reservations-only", "capacity-reservations-first", "none", "default");
    static final String INVALID_CAPACITY_RESERVATION_PREFERENCE_MESSAGE =
            "The specified value for CapacityReservationPreference is not valid. Valid values are: "
                    + "capacity-reservations-only, capacity-reservations-first, none, default.";
    static final String CAPACITY_RESERVATION_TARGET_WITH_NONE_MESSAGE =
            "You cannot specify a CapacityReservationTarget when CapacityReservationPreference is none.";
    static final int MAX_SERVICE_LINKED_ROLE_ARN_LENGTH = 1600;
    static final int MAX_RESOURCE_NAME_LENGTH = 1600;
    private static final Pattern CAPACITY_RESERVATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9\\-_/]+$");
    static final int MAX_WEIGHTED_CAPACITY_LENGTH = 32;
    static final String INVALID_SERVICE_LINKED_ROLE_ARN_MESSAGE =
            "ServiceLinkedRoleARN must be between 1 and 1600 characters long.";
    static final int MIN_WEIGHTED_CAPACITY = 1;
    static final int MAX_WEIGHTED_CAPACITY = 999;
    static final String INVALID_WEIGHTED_CAPACITY_MESSAGE =
            "WeightedCapacity must be an integer in the range of 1-999.";
    static final String PARTIAL_WEIGHTED_CAPACITY_MESSAGE =
            "If you specify a value for WeightedCapacity for one instance type, you must specify a value "
                    + "for WeightedCapacity for all of them.";
    public static final List<String> GROUP_METRICS = List.of(
            "GroupMinSize", "GroupMaxSize", "GroupDesiredCapacity", "GroupInServiceInstances",
            "GroupPendingInstances", "GroupStandbyInstances", "GroupTerminatingInstances",
            "GroupTotalInstances", "GroupInServiceCapacity", "GroupPendingCapacity",
            "GroupStandbyCapacity", "GroupTerminatingCapacity", "GroupTotalCapacity",
            "WarmPoolDesiredCapacity", "WarmPoolWarmedCapacity", "WarmPoolPendingCapacity",
            "WarmPoolTerminatingCapacity", "WarmPoolTotalCapacity",
            "GroupAndWarmPoolDesiredCapacity", "GroupAndWarmPoolTotalCapacity");
    public static final String METRICS_GRANULARITY = "1Minute";
    static final String MISSING_GRANULARITY_MESSAGE =
            SmithyValidationErrors.message(List.of(SmithyValidationErrors.notNull("granularity")));
    public static final String INVALID_GRANULARITY_MESSAGE =
            "Granularity must be 1Minute. 1Minute is the only valid value.";
    static final String ACTIVE_INSTANCE_REFRESH_DESIRED_CONFIGURATION_MESSAGE =
            "An active instance refresh with a desired configuration exists. All configuration options derived from the desired configuration are not available for update while the instance refresh is active.";

    @Inject
    RegionResolver regionResolver;

    @Inject
    StorageFactory storageFactory;

    @Inject
    Ec2Service ec2Service;

    // region :: name → resource
    private Map<String, LaunchConfiguration> launchConfigs = new ConcurrentHashMap<>();
    Map<String, AutoScalingGroup> groups = new ConcurrentHashMap<>();
    private final Map<String, Object> groupLocks = new ConcurrentHashMap<>();
    private AccountAwareStorageBackend<AutoScalingGroup> groupsStore;
    private Map<String, LifecycleHook> hooks = new ConcurrentHashMap<>();
    private Map<String, ScalingPolicy> policies = new ConcurrentHashMap<>();
    private Map<String, ScalingActivity> activities = new ConcurrentHashMap<>();
    private Map<String, InstanceRefresh> instanceRefreshes = new ConcurrentHashMap<>();
    private Map<String, ScheduledAction> scheduledActions = new ConcurrentHashMap<>();
    private Map<String, WarmPoolConfiguration> warmPools = new ConcurrentHashMap<>();

    @PostConstruct
    void initializeStorage()
    {
        if (storageFactory == null) {
            return;
        }
        this.launchConfigs = storageBacked("autoscaling-launch-configurations.json", new TypeReference<Map<String, LaunchConfiguration>>() {});
        this.groupsStore = storageFactory.create("autoscaling", "autoscaling-groups.json",
                new TypeReference<Map<String, AutoScalingGroup>>() {});
        this.groups = new StorageBackedMap<>(groupsStore);
        this.hooks = storageBacked("autoscaling-lifecycle-hooks.json", new TypeReference<Map<String, LifecycleHook>>() {});
        this.policies = storageBacked("autoscaling-policies.json", new TypeReference<Map<String, ScalingPolicy>>() {});
        this.activities = storageBacked("autoscaling-activities.json", new TypeReference<Map<String, ScalingActivity>>() {});
        this.instanceRefreshes = storageBacked("autoscaling-instance-refreshes.json", new TypeReference<Map<String, InstanceRefresh>>() {});
        this.scheduledActions = storageBacked("autoscaling-scheduled-actions.json", new TypeReference<Map<String, ScheduledAction>>() {});
        this.warmPools = storageBacked("autoscaling-warm-pools.json", new TypeReference<Map<String, WarmPoolConfiguration>>() {});
    }

    private <V> Map<String, V> storageBacked(String fileName, TypeReference<Map<String, V>> typeReference)
    {
        return new StorageBackedMap<>(storageFactory.create("autoscaling", fileName, typeReference));
    }

    // ── Launch Configurations ──────────────────────────────────────────────────

    public LaunchConfiguration createLaunchConfiguration(String region, String name, String instanceId,
                                                          String imageId, String instanceType, String keyName,
                                                          List<String> securityGroups, String userData,
                                                          String iamInstanceProfile,
                                                          Boolean associatePublicIpAddress) {
        return createLaunchConfiguration(region, name, instanceId, imageId, instanceType, keyName,
                securityGroups, userData, iamInstanceProfile, associatePublicIpAddress, null, List.of());
    }

    public LaunchConfiguration createLaunchConfiguration(String region, String name, String instanceId,
                                                          String imageId, String instanceType, String keyName,
                                                          List<String> securityGroups, String userData,
                                                          String iamInstanceProfile,
                                                          Boolean associatePublicIpAddress,
                                                          Boolean instanceMonitoringEnabled,
                                                          List<LaunchConfigurationBlockDeviceMapping> blockDeviceMappings) {
        return createLaunchConfiguration(region, name, instanceId, imageId, instanceType, keyName,
                securityGroups, userData, iamInstanceProfile, associatePublicIpAddress,
                instanceMonitoringEnabled, blockDeviceMappings, null);
    }

    public LaunchConfiguration createLaunchConfiguration(String region, String name, String instanceId,
                                                          String imageId, String instanceType, String keyName,
                                                          List<String> securityGroups, String userData,
                                                          String iamInstanceProfile, Boolean associatePublicIpAddress,
                                                          Boolean instanceMonitoringEnabled,
                                                          List<LaunchConfigurationBlockDeviceMapping> blockDeviceMappings,
                                                          LaunchTemplateData.MetadataOptions metadataOptions) {
        validateMetadataOptions(metadataOptions);
        String key = lcKey(region, name);
        if (launchConfigs.containsKey(key)) {
            throw new AwsException("AlreadyExists",
                    "Launch configuration '" + name + "' already exists.", 400);
        }
        if (isBlank(instanceId) && (isBlank(imageId) || isBlank(instanceType))) {
            throw new AwsException("ValidationError", INVALID_LAUNCH_CONFIGURATION_PARAMETERS_MESSAGE, 400);
        }
        if (notBlank(instanceId) && ec2Service != null) {
            Optional<Instance> sourceInstance = sourceInstance(region, instanceId);
            if (sourceInstance.isPresent()) {
                Instance source = sourceInstance.get();
                if (isBlank(imageId)) {
                    imageId = source.getImageId();
                }
                if (isBlank(instanceType)) {
                    instanceType = source.getInstanceType();
                }
                if (isBlank(keyName)) {
                    keyName = source.getKeyName();
                }
                if ((securityGroups == null || securityGroups.isEmpty())
                        && source.getSecurityGroups() != null) {
                    securityGroups = source.getSecurityGroups().stream()
                            .map(group -> group.getGroupId() != null ? group.getGroupId() : group.getGroupName())
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                }
                if (isBlank(userData)) {
                    userData = source.getEncodedUserData();
                    if (userData == null && source.getUserData() != null) {
                        userData = Base64.getEncoder().encodeToString(
                                source.getUserData().getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (isBlank(iamInstanceProfile)) {
                    iamInstanceProfile = source.getIamInstanceProfileArn();
                }
            }
        }
        Ec2UserDataDecoder.decode(userData);
        resolveLaunchConfigurationProfile(iamInstanceProfile);
        LaunchConfiguration lc = new LaunchConfiguration();
        lc.setLaunchConfigurationName(name);
        lc.setLaunchConfigurationArn(
                AwsArnUtils.Arn.of("autoscaling", region, regionResolver.getAccountId(),
                        "launchConfiguration:" + name).toString());
        lc.setImageId(imageId);
        lc.setInstanceType(instanceType);
        lc.setKeyName(keyName);
        lc.setSecurityGroups(securityGroups != null ? new ArrayList<>(securityGroups) : new ArrayList<>());
        lc.setUserData(userData);
        lc.setUserDataBase64Encoded(Boolean.TRUE);
        lc.setIamInstanceProfile(iamInstanceProfile);
        lc.setMetadataOptions(metadataOptions);
        lc.setAssociatePublicIpAddress(associatePublicIpAddress);
        // AWS documents InstanceMonitoring as enabled by default and always returns the
        // structure from Describe, unlike AssociatePublicIpAddress whose absence is meaningful.
        lc.setInstanceMonitoringEnabled(instanceMonitoringEnabled != null ? instanceMonitoringEnabled : Boolean.TRUE);
        lc.setBlockDeviceMappings(blockDeviceMappings != null ? new ArrayList<>(blockDeviceMappings) : new ArrayList<>());
        lc.setCreatedTime(Instant.now());
        lc.setRegion(region);
        launchConfigs.put(key, lc);
        return lc;
    }

    /**
     * Refuses metadata options outside the Auto Scaling model, every violation in one
     * {@code ValidationError} with AWS's texts.
     */
    private static void validateMetadataOptions(LaunchTemplateData.MetadataOptions options) {
        if (options == null) {
            return;
        }
        List<String> violations = new ArrayList<>();
        requireMetadataEnum(violations, "httpTokens", options.getHttpTokens(), "optional", "required");
        Integer hopLimit = options.getHttpPutResponseHopLimit();
        if (hopLimit != null && (hopLimit < 1 || hopLimit > 64)) {
            violations.add("Value '" + hopLimit + "' at 'metadataOptions.httpPutResponseHopLimit' failed to satisfy"
                    + " constraint: Member must have value "
                    + (hopLimit < 1 ? "greater than or equal to 1" : "less than or equal to 64"));
        }
        requireMetadataEnum(violations, "httpEndpoint", options.getHttpEndpoint(), "disabled", "enabled");
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationError(violations);
        }
    }

    private static void requireMetadataEnum(List<String> violations, String member, String value,
                                            String... allowed) {
        if (value != null && !List.of(allowed).contains(value)) {
            violations.add("Value '" + value + "' at 'metadataOptions." + member + "' failed to satisfy constraint:"
                    + " Member must satisfy enum value set: [" + String.join(", ", allowed) + "]");
        }
    }

    /** The EC2 instance a launch configuration is made from, which it derives what the request leaves out from. */
    private Optional<Instance> sourceInstance(String region, String instanceId) {
        return ec2Service.describeInstances(region, List.of(instanceId), Map.of()).stream()
                .flatMap(reservation -> reservation.getInstances().stream())
                .findFirst();
    }

    /** The instance profile a launch configuration made from the instance takes, for the {@code iam:PassRole} check. */
    @Override
    public Optional<String> launchConfigurationInstanceProfile(String instanceId, String region) {
        if (ec2Service == null || isBlank(instanceId)) {
            return Optional.empty();
        }
        try {
            return sourceInstance(region, instanceId).map(Instance::getIamInstanceProfileArn)
                    .filter(profile -> !isBlank(profile));
        } catch (AwsException e) {
            // An instance that does not exist is the operation's to refuse, and passes no role.
            LOG.debugv("Instance {0} passes no role: {1}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    String resolveLaunchConfigurationProfile(String profile) {
        if (isBlank(profile)) {
            return null;
        }
        boolean arn = AwsArnUtils.isArn(profile);
        String name = arn ? profile.substring(profile.lastIndexOf('/') + 1) : profile;
        try {
            String resolved = ec2Service.resolveIamInstanceProfileName(name);
            if (arn && !profile.equals(resolved)) {
                throw new AwsException("ValidationError", "Invalid IamInstanceProfile: " + profile, 400);
            }
            return resolved;
        } catch (AwsException error) {
            if (!"InvalidParameterValue".equals(error.getErrorCode())) {
                throw error;
            }
            throw new AwsException("ValidationError", "Invalid IamInstanceProfile: " + profile, 400);
        }
    }

    public List<LaunchConfiguration> describeLaunchConfigurations(String region, List<String> names) {
        if (names != null && !names.isEmpty()) {
            return names.stream()
                    .map(n -> launchConfigs.get(lcKey(region, n)))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        return launchConfigs.values().stream()
                .filter(lc -> region.equals(lc.getRegion()))
                .collect(Collectors.toList());
    }

    public void deleteLaunchConfiguration(String region, String name) {
        if (launchConfigs.remove(lcKey(region, name)) == null) {
            throw new AwsException("ValidationError",
                    "Launch configuration '" + name + "' not found.", 400);
        }
    }

    // ── Auto Scaling Groups ────────────────────────────────────────────────────

    public AutoScalingGroup createAutoScalingGroup(String region, String name,
                                                    String launchConfigName,
                                                    String launchTemplateId, String launchTemplateName,
                                                    String launchTemplateVersion,
                                                    MixedInstancesPolicy mixedInstancesPolicy,
                                                    int minSize, int maxSize, int desiredCapacity,
                                                    int defaultCooldown, List<String> availabilityZones,
                                                    List<String> subnetIds,
                                                    List<String> targetGroupArns, List<String> lbNames,
                                                    String healthCheckType, int healthCheckGracePeriod,
                                                    List<String> terminationPolicies,
                                                    Map<String, String> tags,
                                                    Map<String, Boolean> tagPropagateAtLaunch,
                                                    AsgOptionalFields optionalFields) {
        String key = asgKey(region, name);
        if (groups.containsKey(key)) {
            throw new AwsException("AlreadyExists",
                    "Auto Scaling group '" + name + "' already exists.", 400);
        }
        validateLaunchSource(launchConfigName, launchTemplateId, launchTemplateName, mixedInstancesPolicy);
        if (launchConfigName == null && launchTemplateId == null && launchTemplateName == null
                && mixedInstancesPolicy == null) {
            throw new AwsException("ValidationError",
                    "Valid requests must contain either LaunchTemplate, LaunchConfigurationName, "
                            + "InstanceId or MixedInstancesPolicy parameter.", 400);
        }
        validateEffectiveLaunchImage(region, launchConfigName, launchTemplateId, launchTemplateName,
                launchTemplateVersion, mixedInstancesPolicy);
        validateOptionalFields(optionalFields);
        validateDesiredCapacityType(
                optionalFields != null ? optionalFields.desiredCapacityType() : null,
                mixedInstancesPolicy);

        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setAutoScalingGroupName(name);
        asg.setAutoScalingGroupArn(
                AwsArnUtils.Arn.of("autoscaling", region, regionResolver.getAccountId(),
                        "autoScalingGroup:" + name).toString());
        asg.setLaunchConfigurationName(launchConfigName);
        asg.setLaunchTemplateId(launchTemplateId);
        asg.setLaunchTemplateName(launchTemplateName);
        asg.setLaunchTemplateVersion(launchTemplateVersion);
        asg.setMixedInstancesPolicy(mixedInstancesPolicy);
        asg.setMinSize(minSize);
        asg.setMaxSize(maxSize);
        asg.setDesiredCapacity(desiredCapacity);
        asg.setDefaultCooldown(defaultCooldown > 0 ? defaultCooldown : 300);
        asg.setAvailabilityZones(availabilityZones != null ? new ArrayList<>(availabilityZones) : new ArrayList<>());
        asg.setSubnetIds(subnetIds != null ? new ArrayList<>(subnetIds) : new ArrayList<>());
        asg.setTargetGroupARNs(targetGroupArns != null ? new ArrayList<>(targetGroupArns) : new ArrayList<>());
        asg.setLoadBalancerNames(lbNames != null ? new ArrayList<>(lbNames) : new ArrayList<>());
        asg.setHealthCheckType(healthCheckType != null ? healthCheckType : "EC2");
        asg.setHealthCheckGracePeriod(healthCheckGracePeriod);
        asg.setTerminationPolicies(terminationPolicies != null ? new ArrayList<>(terminationPolicies) : List.of("Default"));
        asg.setCreatedTime(Instant.now());
        asg.setRegion(region);
        if (tags != null) {
            asg.getTags().putAll(tags);
        }
        if (tagPropagateAtLaunch != null) {
            asg.getTagPropagateAtLaunch().putAll(tagPropagateAtLaunch);
        }
        if (optionalFields != null) {
            optionalFields.applyToNewGroup(asg);
        }
        groups.put(key, asg);
        return asg;
    }

    public void updateAutoScalingGroup(String region, String name,
                                        String launchConfigName,
                                        String launchTemplateId, String launchTemplateName,
                                        String launchTemplateVersion,
                                        MixedInstancesPolicy mixedInstancesPolicy,
                                        Integer minSize, Integer maxSize, Integer desiredCapacity,
                                        Integer defaultCooldown, List<String> availabilityZones,
                                        List<String> subnetIds,
                                        String healthCheckType, Integer healthCheckGracePeriod,
                                        List<String> terminationPolicies,
                                        AsgOptionalFields optionalFields) {
        AutoScalingGroup asg = requireGroup(region, name);
        validateLaunchSource(launchConfigName, launchTemplateId, launchTemplateName, mixedInstancesPolicy);
        if (launchTemplateVersion != null && launchTemplateId == null && launchTemplateName == null) {
            throw new AwsException("ValidationError",
                    "LaunchTemplateVersion requires a LaunchTemplateId or LaunchTemplateName.", 400);
        }
        validateOptionalFields(optionalFields);
        rejectDesiredConfigurationUpdateDuringActiveRefresh(region, name,
                launchConfigName, launchTemplateId, launchTemplateName, launchTemplateVersion, mixedInstancesPolicy);
        LaunchIdentity effectiveIdentity = effectiveLaunchIdentity(asg, launchConfigName,
                launchTemplateId, launchTemplateName, launchTemplateVersion, mixedInstancesPolicy);
        validateEffectiveLaunchImage(region,
                effectiveIdentity.launchConfigurationName(),
                effectiveIdentity.launchTemplateId(),
                effectiveIdentity.launchTemplateName(),
                effectiveIdentity.launchTemplateVersion(),
                effectiveIdentity.mixedInstancesPolicy());
        validateDesiredCapacityType(
                effectiveDesiredCapacityType(asg, optionalFields),
                effectiveIdentity.mixedInstancesPolicy());
        if (launchConfigName != null) {
            asg.setLaunchConfigurationName(launchConfigName);
            asg.setLaunchTemplateId(null);
            asg.setLaunchTemplateName(null);
            asg.setLaunchTemplateVersion(null);
            asg.setMixedInstancesPolicy(null);
        }
        if (launchTemplateId != null || launchTemplateName != null) {
            asg.setLaunchConfigurationName(null);
            asg.setLaunchTemplateId(launchTemplateId);
            asg.setLaunchTemplateName(launchTemplateName);
            asg.setLaunchTemplateVersion(launchTemplateVersion);
            asg.setMixedInstancesPolicy(null);
        }
        if (mixedInstancesPolicy != null) {
            asg.setLaunchConfigurationName(null);
            asg.setLaunchTemplateId(null);
            asg.setLaunchTemplateName(null);
            asg.setLaunchTemplateVersion(null);
            asg.setMixedInstancesPolicy(mixedInstancesPolicy);
        }
        if (minSize != null) { asg.setMinSize(minSize); }
        if (maxSize != null) { asg.setMaxSize(maxSize); }
        if (desiredCapacity != null) { asg.setDesiredCapacity(desiredCapacity); }
        if (defaultCooldown != null) { asg.setDefaultCooldown(defaultCooldown); }
        if (availabilityZones != null) { asg.setAvailabilityZones(new ArrayList<>(availabilityZones)); }
        if (subnetIds != null) { asg.setSubnetIds(new ArrayList<>(subnetIds)); }
        if (healthCheckType != null) { asg.setHealthCheckType(healthCheckType); }
        if (healthCheckGracePeriod != null) { asg.setHealthCheckGracePeriod(healthCheckGracePeriod); }
        if (terminationPolicies != null) { asg.setTerminationPolicies(new ArrayList<>(terminationPolicies)); }
        if (optionalFields != null) {
            optionalFields.applyToExistingGroup(asg);
        }
        groups.put(asgKey(region, name), asg);
    }

    /**
     * EnableMetricsCollection: {@code Granularity} is required and 1Minute is its only valid value.
     * Naming no metrics enables all of them.
     */
    public void enableMetricsCollection(String region, String name, List<String> metrics, String granularity) {
        if (granularity == null) {
            throw new AwsException("ValidationError", MISSING_GRANULARITY_MESSAGE, 400);
        }
        if (!METRICS_GRANULARITY.equals(granularity)) {
            throw new AwsException("ValidationError", INVALID_GRANULARITY_MESSAGE, 400);
        }
        validateMetricNames(metrics);
        String key = asgKey(region, name);
        synchronized (lockFor(key)) {
            AutoScalingGroup asg = requireGroup(region, name);
            List<String> requested = metrics == null || metrics.isEmpty() ? GROUP_METRICS : metrics;
            LinkedHashSet<String> enabled = new LinkedHashSet<>(asg.getEnabledMetrics());
            enabled.addAll(requested);
            asg.setEnabledMetrics(new ArrayList<>(enabled));
            groups.put(key, asg);
        }
    }

    /** DisableMetricsCollection: naming no metrics disables all of them. */
    public void disableMetricsCollection(String region, String name, List<String> metrics) {
        validateMetricNames(metrics);
        String key = asgKey(region, name);
        synchronized (lockFor(key)) {
            AutoScalingGroup asg = requireGroup(region, name);
            if (metrics == null || metrics.isEmpty()) {
                asg.setEnabledMetrics(new ArrayList<>());
            } else {
                List<String> remaining = new ArrayList<>(asg.getEnabledMetrics());
                remaining.removeAll(metrics);
                asg.setEnabledMetrics(remaining);
            }
            groups.put(key, asg);
        }
    }

    public static void validateMetricNames(List<String> metrics) {
        if (metrics == null) {
            return;
        }
        for (String metric : metrics) {
            if (!GROUP_METRICS.contains(metric)) {
                throw new AwsException("ValidationError",
                        "Metric " + metric + " is not a valid Auto Scaling group metric.", 400);
            }
        }
    }

    public void deleteAutoScalingGroup(String region, String name, boolean forceDelete) {
        String key = asgKey(region, name);
        synchronized (lockFor(key)) {
            AutoScalingGroup asg = requireGroup(region, name);
            List<AsgInstance> active = asg.getInstances().stream()
                    .filter(i -> !"Terminated".equals(i.getLifecycleState()))
                    .collect(Collectors.toList());
            if (!active.isEmpty() && !forceDelete) {
                throw new AwsException("ResourceInUse",
                        "Auto Scaling group '" + name + "' has " + active.size()
                                + " instance(s). Set ForceDelete=true to delete anyway.", 400);
            }
            if (forceDelete && ec2Service != null && !active.isEmpty()) {
                active.stream()
                        .map(AsgInstance::getInstanceId)
                        .filter(Objects::nonNull)
                        .forEach(instanceId -> {
                            try {
                                ec2Service.terminateInstances(region, List.of(instanceId));
                            }
                            catch (AwsException ignored) {
                                // ForceDelete should remove stale ASG membership even if EC2 no longer has the instance.
                            }
                        });
            }
            groups.remove(key);
            instanceRefreshes.entrySet().removeIf(e -> region.equals(e.getValue().getRegion())
                    && name.equals(e.getValue().getAutoScalingGroupName()));
        }
        // clean up associated hooks and policies
        hooks.entrySet().removeIf(e -> e.getValue().getAutoScalingGroupName().equals(name));
        policies.entrySet().removeIf(e -> e.getValue().getAutoScalingGroupName().equals(name));
        scheduledActions.entrySet().removeIf(e -> region.equals(e.getValue().getRegion())
                && name.equals(e.getValue().getAutoScalingGroupName()));
        warmPools.remove(warmPoolKey(region, name));
    }

    public List<AutoScalingGroup> describeAutoScalingGroups(String region, List<String> names) {
        if (names != null && !names.isEmpty()) {
            return names.stream()
                    .map(n -> groups.get(asgKey(region, n)))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        // StorageBackedMap reads each value lazily, so a group deleted mid-iteration shows up as null.
        return groups.values().stream()
                .filter(Objects::nonNull)
                .filter(g -> region == null || region.equals(g.getRegion()))
                .collect(Collectors.toList());
    }

    public boolean saveAutoScalingGroupIfPresent(AutoScalingGroup asg) {
        String key = asgKey(asg.getRegion(), asg.getAutoScalingGroupName());
        synchronized (lockFor(key)) {
            AutoScalingGroup current = groups.get(key);
            if (current == asg) {
                groups.put(key, asg);
                return true;
            }
            return false;
        }
    }

    void reconcileIfPresent(AutoScalingGroup asg, Runnable reconciliation) {
        String key = asgKey(asg.getRegion(), asg.getAutoScalingGroupName());
        synchronized (lockFor(key)) {
            if (groups.get(key) == asg) {
                reconciliation.run();
            }
        }
    }

    private Object lockFor(String key) {
        return groupLocks.computeIfAbsent(regionResolver.getAccountId() + "::" + key, ignored -> new Object());
    }

    Set<String> autoScalingGroupAccountIds() {
        if (groupsStore == null) {
            return Set.of();
        }
        return groupsStore.scanAllAccountEntries(key -> true).stream()
                .map(AccountAwareStorageBackend.AccountEntry::accountId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void setDesiredCapacity(String region, String name, int desiredCapacity) {
        AutoScalingGroup asg = requireGroup(region, name);
        if (desiredCapacity < asg.getMinSize() || desiredCapacity > asg.getMaxSize()) {
            throw new AwsException("ValidationError",
                    "New DesiredCapacity=" + desiredCapacity + " must be between MinSize="
                            + asg.getMinSize() + " and MaxSize=" + asg.getMaxSize() + ".", 400);
        }
        asg.setDesiredCapacity(desiredCapacity);
        groups.put(asgKey(region, name), asg);
    }

    public void createOrUpdateTags(
            String region,
            String resourceId,
            String resourceType,
            Map<String, String> tags,
            Map<String, Boolean> tagPropagateAtLaunch) {
        AutoScalingGroup asg = requireTaggableGroup(region, resourceId, resourceType);
        asg.getTags().putAll(tags);
        asg.getTagPropagateAtLaunch().putAll(tagPropagateAtLaunch);
        groups.put(asgKey(region, asg.getAutoScalingGroupName()), asg);
    }

    public void deleteTags(String region, String resourceId, String resourceType, List<String> tagKeys) {
        AutoScalingGroup asg = requireTaggableGroup(region, resourceId, resourceType);
        tagKeys.forEach(asg.getTags()::remove);
        tagKeys.forEach(asg.getTagPropagateAtLaunch()::remove);
        groups.put(asgKey(region, asg.getAutoScalingGroupName()), asg);
    }

    // AWS: values inside one filter are ORed, separate filters are ANDed; only these four
    // filter names exist. Ordered by (group, key); NextToken is the last pair returned, so tags
    // added or removed between pages neither skip nor repeat an entry.
    public PaginatedResult<TagDescription> describeTags(String region, List<Map.Entry<String, List<String>>> filters,
                                                         Integer maxRecords, String nextToken) {
        List<Map.Entry<String, List<String>>> normalizedFilters = new ArrayList<>();
        for (Map.Entry<String, List<String>> filter : filters) {
            String name = filter.getKey() == null ? "" : filter.getKey().toLowerCase(Locale.ROOT);
            if (!Set.of("auto-scaling-group", "key", "value", "propagate-at-launch").contains(name)) {
                throw new AwsException("ValidationError", "Filter name '" + filter.getKey() + "' is not valid.", 400);
            }
            if (filter.getValue() == null) {
                throw new AwsException("ValidationError", "The Values attribute and one or more filter values are required.", 400);
            }
            List<String> values = filter.getValue();
            if ("propagate-at-launch".equals(name)) {
                values = values.stream().map(value -> value.trim().toLowerCase(Locale.ROOT)).toList();
                if (values.stream().anyMatch(value -> !Set.of("true", "false").contains(value))) {
                    throw new AwsException("ValidationError", "The propagate-at-launch filter requires true or false.", 400);
                }
            }
            normalizedFilters.add(Map.entry(name, values));
        }
        if (maxRecords != null && (maxRecords < 1 || maxRecords > 100)) {
            throw new AwsException("ValidationError", "MaxRecords must be between 1 and 100.", 400);
        }
        if (nextToken != null && nextToken.isBlank()) {
            throw new AwsException("ValidationError", "The NextToken value is not valid.", 400);
        }
        int limit = maxRecords != null ? maxRecords : 50;
        List<TagDescription> matches = groups.values().stream()
                .filter(Objects::nonNull) // group deleted between key snapshot and value read
                .filter(g -> region.equals(g.getRegion()))
                .flatMap(g -> g.getTags().entrySet().stream()
                        .map(t -> new TagDescription(g.getAutoScalingGroupName(), t.getKey(), t.getValue(),
                                g.getTagPropagateAtLaunch().getOrDefault(t.getKey(), false))))
                .filter(t -> matchesFilter(normalizedFilters, "auto-scaling-group", t.resourceId())
                        && matchesFilter(normalizedFilters, "key", t.key())
                        && matchesFilter(normalizedFilters, "value", t.value())
                        && matchesFilter(normalizedFilters, "propagate-at-launch", String.valueOf(t.propagateAtLaunch())))
                .collect(Collectors.toList());
        // "\0" sorts below every other character, so the cursor orders as the (group, key) pair.
        return Pagination.paginate(matches,
                t -> t.resourceId() + "\0" + t.key(), limit, nextToken, "autoscaling-tags",
                token -> new AwsException("ValidationError", "The NextToken value is not valid.", 400));
    }

    // Every filter of this name must match (a repeated name is a second filter, ANDed).
    private static boolean matchesFilter(List<Map.Entry<String, List<String>>> filters, String name, String actual) {
        return filters.stream()
                .filter(f -> f.getKey().equals(name) && !f.getValue().isEmpty())
                .allMatch(f -> f.getValue().contains(actual));
    }

    public record TagDescription(String resourceId, String key, String value, boolean propagateAtLaunch) {}

    private AutoScalingGroup requireTaggableGroup(String region, String resourceId, String resourceType) {
        if (!"auto-scaling-group".equals(resourceType)) {
            throw new AwsException("ValidationError",
                    "Unsupported tag resource type '" + resourceType + "'.", 400);
        }
        return requireGroup(region, resourceId);
    }

    // An empty list suspends all processes. The reconciler honors Launch and Terminate
    // for desired-capacity adjustments and owned refresh replacements. Other names remain
    // stored state; InstanceRefresh also pauses its owned replacement work.
    private static final List<String> ALL_SCALING_PROCESSES = List.of(
            "Launch", "Terminate", "AddToLoadBalancer", "AlarmNotification", "AZRebalance",
            "HealthCheck", "InstanceRefresh", "ReplaceUnhealthy", "ScheduledActions");

    public void suspendProcesses(String region, String name, List<String> processes) {
        String key = asgKey(region, name);
        synchronized (lockFor(key)) {
            AutoScalingGroup asg = requireGroup(region, name);
            List<String> toSuspend = processes.isEmpty() ? ALL_SCALING_PROCESSES : processes;
            AutoScalingGroup.SuspensionState previous = asg.suspensionState();
            Set<String> suspended = new LinkedHashSet<>(previous.processes());
            Map<String, Instant> times = new LinkedHashMap<>(previous.times());
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            for (String process : toSuspend) {
                if (suspended.add(process)) {
                    times.put(process, now);
                }
            }
            times.keySet().retainAll(suspended);
            asg.updateSuspensionState(new ArrayList<>(suspended), times);
            groups.put(key, asg);
        }
    }

    public void resumeProcesses(String region, String name, List<String> processes) {
        String key = asgKey(region, name);
        synchronized (lockFor(key)) {
            AutoScalingGroup asg = requireGroup(region, name);
            AutoScalingGroup.SuspensionState previous = asg.suspensionState();
            List<String> remaining = new ArrayList<>(previous.processes());
            if (processes.isEmpty()) {
                remaining.clear();
            } else {
                remaining.removeAll(processes);
            }
            Map<String, Instant> times = new LinkedHashMap<>(previous.times());
            times.keySet().retainAll(remaining);
            asg.updateSuspensionState(remaining, times);
            groups.put(key, asg);
        }
    }

    // ── Instance management ────────────────────────────────────────────────────

    public List<AsgInstance> describeAutoScalingInstances(String region, List<String> instanceIds) {
        List<AsgInstance> all = groups.values().stream()
                .filter(Objects::nonNull)
                .filter(g -> region.equals(g.getRegion()))
                .flatMap(g -> g.getInstances().stream())
                .collect(Collectors.toList());
        if (instanceIds != null && !instanceIds.isEmpty()) {
            Set<String> ids = new HashSet<>(instanceIds);
            return all.stream().filter(i -> ids.contains(i.getInstanceId())).collect(Collectors.toList());
        }
        return all;
    }

    public void setInstanceProtection(String region, String groupName, List<String> instanceIds,
                                      boolean protectedFromScaleIn) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            throw new AwsException("ValidationError", "At least one instance ID is required.", 400);
        }
        AutoScalingGroup asg = requireGroup(region, groupName);
        Set<String> requested = new HashSet<>(instanceIds);
        Set<String> found = asg.getInstances().stream()
                .map(AsgInstance::getInstanceId)
                .filter(requested::contains)
                .collect(Collectors.toSet());
        if (!found.containsAll(requested)) {
            String missing = requested.stream()
                    .filter(id -> !found.contains(id))
                    .collect(Collectors.joining(", "));
            throw new AwsException("ValidationError",
                    "Instance(s) '" + missing + "' is/are not part of Auto Scaling group '" + groupName + "'.", 400);
        }
        asg.getInstances().stream()
                .filter(instance -> requested.contains(instance.getInstanceId()))
                .forEach(instance -> instance.setProtectedFromScaleIn(protectedFromScaleIn));
        groups.put(asgKey(region, groupName), asg);
    }

    /**
     * {@code shouldRespectGracePeriod} is parsed and accepted for wire fidelity (2011-01-01 model)
     * but not yet enforced — floci does not currently track instance launch time against
     * {@link AutoScalingGroup#getHealthCheckGracePeriod()}, so the health status change below always
     * applies immediately regardless of the flag.
     */
    public void setInstanceHealth(String region, String instanceId, String healthStatus,
                                   boolean shouldRespectGracePeriod) {
        if (!"Healthy".equals(healthStatus) && !"Unhealthy".equals(healthStatus)) {
            throw new AwsException("ValidationError",
                    "HealthStatus must be Healthy or Unhealthy.", 400);
        }
        updateGroupOfInstance(region, instanceId, asg -> asg.getInstances().stream()
                .filter(instance -> instanceId.equals(instance.getInstanceId()))
                .findFirst()
                .ifPresent(instance -> instance.setHealthStatus(healthStatus)));
    }

    /** Applies {@code update} to the group holding {@code instanceId}, under that group's lock. */
    private void updateGroupOfInstance(String region, String instanceId, Consumer<AutoScalingGroup> update) {
        String name = groups.values().stream()
                .filter(Objects::nonNull)
                .filter(g -> region.equals(g.getRegion()) && containsInstance(g, instanceId))
                .map(AutoScalingGroup::getAutoScalingGroupName)
                .findFirst()
                .orElseThrow(() -> instanceNotFound(instanceId));
        String key = asgKey(region, name);
        synchronized (lockFor(key)) {
            // Re-read under the lock: the group may have been deleted or replaced since the scan.
            AutoScalingGroup asg = groups.get(key);
            if (asg == null || !containsInstance(asg, instanceId)) {
                throw instanceNotFound(instanceId);
            }
            update.accept(asg);
            groups.put(key, asg);
        }
    }

    private static AwsException instanceNotFound(String instanceId) {
        return new AwsException("ValidationError",
                "Instance '" + instanceId + "' not found in any Auto Scaling group.", 400);
    }

    private static boolean containsInstance(AutoScalingGroup asg, String instanceId) {
        return instanceId != null && asg.getInstances().stream().anyMatch(i -> instanceId.equals(i.getInstanceId()));
    }

    /**
     * The tags an instance carries as a member of {@code asg}: the launch template's instance tags,
     * then the group's PropagateAtLaunch tags, which win a key both set, then the group-name tag,
     * which names {@code asg} whatever the other two say.
     */
    static List<Tag> propagatedInstanceTags(AutoScalingGroup asg, List<Tag> templateTags) {
        Map<String, String> tags = new LinkedHashMap<>();
        templateTags.forEach(tag -> tags.put(tag.getKey(), tag.getValue()));
        asg.getTags().forEach((key, value) -> {
            if (asg.getTagPropagateAtLaunch().getOrDefault(key, false)) {
                tags.put(key, value);
            }
        });
        tags.put(GROUP_NAME_TAG, asg.getAutoScalingGroupName());
        return tags.entrySet().stream().map(entry -> new Tag(entry.getKey(), entry.getValue())).toList();
    }

    public void attachInstances(String region, String name, List<String> instanceIds) {
        // An instance joins once, however often the request names it.
        List<String> requested = new ArrayList<>(new LinkedHashSet<>(instanceIds));
        // The region lock keeps two groups from each attaching the same instance at once.
        synchronized (lockFor(region)) {
            synchronized (lockFor(asgKey(region, name))) {
                AutoScalingGroup asg = requireGroup(region, name);
                // An instance belongs to one group at a time, so the whole batch fails before any tag changes.
                List<String> conflicts = new ArrayList<>();
                for (AutoScalingGroup group : groups.values()) {
                    if (group == null || !region.equals(group.getRegion())) {
                        continue;
                    }
                    // Under the group's own lock, which guards its member list against the reconciler.
                    synchronized (lockFor(asgKey(region, group.getAutoScalingGroupName()))) {
                        for (AsgInstance member : group.getInstances()) {
                            if (requested.contains(member.getInstanceId())) {
                                conflicts.add("The Instance: " + member.getInstanceId()
                                        + " is already part of AutoScalingGroup:" + group.getAutoScalingGroupName());
                            }
                        }
                    }
                }
                if (!conflicts.isEmpty()) {
                    throw new AwsException("ValidationError", conflicts.toString(), 400);
                }
                // Attaching does not yet refuse an ID EC2 has no instance for, as AWS does, so such
                // an ID gets no tags: they would outlive it in EC2's DescribeTags.
                List<String> existing = requested.stream()
                        .filter(id -> ec2Service.findInstanceForAccount(regionResolver.getAccountId(), region, id)
                                .isPresent())
                        .toList();
                if (!existing.isEmpty()) {
                    ec2Service.createSystemTags(region, existing, propagatedInstanceTags(asg, List.of()));
                }
                for (String id : requested) {
                    AsgInstance inst = new AsgInstance();
                    inst.setInstanceId(id);
                    inst.setLifecycleState("InService");
                    inst.setHealthStatus("Healthy");
                    inst.setAvailabilityZone(
                            asg.getAvailabilityZones().isEmpty() ? region + "a" : asg.getAvailabilityZones().get(0));
                    inst.setLaunchConfigurationName(asg.getLaunchConfigurationName());
                    inst.setLaunchTemplateId(asg.getLaunchTemplateId());
                    inst.setLaunchTemplateName(asg.getLaunchTemplateName());
                    inst.setLaunchTemplateVersion(asg.getLaunchTemplateVersion());
                    asg.getInstances().add(inst);
                }
                if (asg.getInstances().size() > asg.getDesiredCapacity()) {
                    asg.setDesiredCapacity(asg.getInstances().size());
                }
                groups.put(asgKey(region, name), asg);
            }
        }
    }

    public void detachInstances(String region, String name, List<String> instanceIds,
                                 boolean decrementDesiredCapacity) {
        String key = asgKey(region, name);
        synchronized (lockFor(key)) {
            AutoScalingGroup asg = requireGroup(region, name);
            List<String> detached = asg.getInstances().stream().map(AsgInstance::getInstanceId)
                    .filter(instanceIds::contains).toList();
            if (!detached.isEmpty()) {
                // Detaching removes only the group-name tag, and only where it still names this group.
                ec2Service.deleteSystemTag(region, detached, GROUP_NAME_TAG, name);
            }
            releaseRefreshOwnership(asg, detached);
            asg.getInstances().removeIf(i -> instanceIds.contains(i.getInstanceId()));
            if (decrementDesiredCapacity) {
                int newDesired = Math.max(asg.getMinSize(), asg.getDesiredCapacity() - instanceIds.size());
                asg.setDesiredCapacity(newDesired);
            }
            groups.put(key, asg);
        }
    }

    public void terminateInstanceInAutoScalingGroup(String region, String instanceId,
                                                     boolean decrementDesiredCapacity) {
        updateGroupOfInstance(region, instanceId, asg -> {
            releaseRefreshOwnership(asg, List.of(instanceId));
            asg.getInstances().stream()
                    .filter(i -> instanceId.equals(i.getInstanceId()))
                    .findFirst()
                    .ifPresent(i -> i.setLifecycleState("Terminating"));
            if (decrementDesiredCapacity) {
                int newDesired = Math.max(asg.getMinSize(), asg.getDesiredCapacity() - 1);
                asg.setDesiredCapacity(newDesired);
            }
        });
    }

    private void releaseRefreshOwnership(AutoScalingGroup asg, List<String> instanceIds) {
        InstanceRefresh refresh = activeInstanceRefresh(asg);
        if (refresh != null && refresh.getReplacements() != null) {
            refresh.getReplacements().removeIf(replacement -> instanceIds.stream().anyMatch(instanceId ->
                    instanceId.equals(replacement.getOriginalInstanceId())
                            || instanceId.equals(replacement.getReplacementInstanceId())));
            saveInstanceRefresh(refresh);
        }
    }

    // ── Instance refreshes ────────────────────────────────────────────────────

    public InstanceRefresh startInstanceRefresh(String region, String asgName, InstanceRefresh requestedRefresh) {
        synchronized (lockFor(asgKey(region, asgName))) {
            AutoScalingGroup asg = requireGroup(region, asgName);
            boolean activeRefresh = instanceRefreshes.values().stream()
                    .filter(r -> region.equals(r.getRegion()))
                    .filter(r -> asgName.equals(r.getAutoScalingGroupName()))
                    .anyMatch(r -> isActiveRefreshStatus(r.getStatus()));
            if (activeRefresh) {
                throw new AwsException("InstanceRefreshInProgress",
                        "An active instance refresh already exists for Auto Scaling group '" + asgName + "'.", 400);
            }

            Instant now = Instant.now();
            InstanceRefresh refresh = new InstanceRefresh();
            refresh.setInstanceRefreshId(UUID.randomUUID().toString());
            refresh.setAutoScalingGroupName(asgName);
            refresh.setStrategy(normalizeRefreshStrategy(requestedRefresh.getStrategy()));
            refresh.setStartTime(now);
            refresh.setRegion(region);
            copyDesiredConfiguration(requestedRefresh, refresh);
            copyPreferences(requestedRefresh, refresh);

            applyDesiredConfiguration(asg, refresh);
            List<String> instanceIds = markInstancesForRefresh(asg, refresh);
            List<InstanceRefreshReplacement> replacements = new ArrayList<>();
            for (String instanceId : instanceIds) {
                InstanceRefreshReplacement replacement = new InstanceRefreshReplacement();
                replacement.setOriginalInstanceId(instanceId);
                replacement.setLaunchClientToken(UUID.randomUUID().toString());
                replacements.add(replacement);
            }
            refresh.setReplacements(replacements);
            if (instanceIds.isEmpty()) {
                refresh.setStatus("Successful");
                refresh.setStatusReason("Instance refresh completed.");
                refresh.setPercentageComplete(100);
                refresh.setInstancesToUpdate(0);
                refresh.setEndTime(now);
            } else {
                refresh.setStatus("InProgress");
                refresh.setStatusReason("Instance refresh in progress.");
                refresh.setPercentageComplete(0);
                refresh.setInstancesToUpdate(instanceIds.size());
            }
            instanceRefreshes.put(instanceRefreshKey(region, asgName, refresh.getInstanceRefreshId()), refresh);
            groups.put(asgKey(region, asgName), asg);
            if (!instanceIds.isEmpty()) {
                recordActivity(region, asgName,
                        "Marked EC2 instance(s) for refresh: " + instanceIds,
                        "At " + now + " an instance refresh selected active instances for replacement.",
                        "Successful");
            }
            recordActivity(region, asgName,
                    "Started instance refresh " + refresh.getInstanceRefreshId() + ".",
                    "At " + now + " an instance refresh was started.",
                    "Successful");
            return refresh;
        }
    }

    InstanceRefresh activeInstanceRefresh(AutoScalingGroup asg) {
        return instanceRefreshes.values().stream()
                .filter(refresh -> asg.getRegion().equals(refresh.getRegion()))
                .filter(refresh -> asg.getAutoScalingGroupName().equals(refresh.getAutoScalingGroupName()))
                .filter(refresh -> isActiveRefreshStatus(refresh.getStatus()))
                .findFirst().orElse(null);
    }

    void saveInstanceRefresh(InstanceRefresh refresh) {
        instanceRefreshes.put(instanceRefreshKey(refresh.getRegion(), refresh.getAutoScalingGroupName(),
                refresh.getInstanceRefreshId()), refresh);
    }

    public void completeInstanceRefreshIfSettled(String region, String asgName) {
        AutoScalingGroup asg = requireGroup(region, asgName);
        List<InstanceRefresh> activeRefreshes = instanceRefreshes.values().stream()
                .filter(r -> region.equals(r.getRegion()))
                .filter(r -> asgName.equals(r.getAutoScalingGroupName()))
                .filter(r -> isActiveRefreshStatus(r.getStatus()))
                .collect(Collectors.toList());
        for (InstanceRefresh refresh : activeRefreshes) {
            if (refresh.getReplacements() != null) {
                for (InstanceRefreshReplacement replacement : refresh.getReplacements()) {
                    if (asg.getInstances().stream().anyMatch(instance ->
                            instance.getInstanceId().equals(replacement.getReplacementInstanceId())
                                    && "InService".equals(instance.getLifecycleState()))) {
                        replacement.setPhase("Complete");
                    }
                }
            }
            int remaining = refresh.getReplacements() == null ? remainingInstancesToUpdate(asg)
                    : (int) refresh.getReplacements().stream()
                    .filter(replacement -> !replacement.isComplete())
                    .count();
            refresh.setInstancesToUpdate(remaining);
            refresh.setPercentageComplete(remaining == 0 ? 100 : 0);
            if (remaining == 0) {
                refresh.setStatus("Successful");
                refresh.setStatusReason("Instance refresh completed.");
                refresh.setEndTime(Instant.now());
            }
            instanceRefreshes.put(instanceRefreshKey(region, asgName, refresh.getInstanceRefreshId()), refresh);
        }
    }

    public InstanceRefreshPage describeInstanceRefreshes(String region, String asgName, List<String> refreshIds,
                                                          Integer maxRecords, String nextToken) {
        requireGroup(region, asgName);
        int offset = parseNextToken(nextToken);
        int limit = maxRecords != null ? Math.min(Math.max(maxRecords, 1), 100) : 50;
        Set<String> requestedIds = refreshIds != null && !refreshIds.isEmpty()
                ? new HashSet<>(refreshIds) : null;
        List<InstanceRefresh> matches = instanceRefreshes.values().stream()
                .filter(r -> region.equals(r.getRegion()))
                .filter(r -> asgName.equals(r.getAutoScalingGroupName()))
                .filter(r -> requestedIds == null || requestedIds.contains(r.getInstanceRefreshId()))
                .sorted(Comparator.comparing(InstanceRefresh::getStartTime).reversed())
                .collect(Collectors.toList());
        if (offset > matches.size()) {
            throw new AwsException("InvalidNextToken", "The NextToken value is not valid.", 400);
        }
        int end = Math.min(offset + limit, matches.size());
        String followingToken = end < matches.size() ? String.valueOf(end) : null;
        return new InstanceRefreshPage(matches.subList(offset, end), followingToken);
    }

    // ── Load balancer attachment ───────────────────────────────────────────────

    public void attachLoadBalancerTargetGroups(String region, String name, List<String> tgArns) {
        AutoScalingGroup asg = requireGroup(region, name);
        for (String arn : tgArns) {
            if (!asg.getTargetGroupARNs().contains(arn)) {
                asg.getTargetGroupARNs().add(arn);
            }
        }
        groups.put(asgKey(region, name), asg);
    }

    public void detachLoadBalancerTargetGroups(String region, String name, List<String> tgArns) {
        AutoScalingGroup asg = requireGroup(region, name);
        asg.getTargetGroupARNs().removeAll(tgArns);
        groups.put(asgKey(region, name), asg);
    }

    public List<String> describeLoadBalancerTargetGroups(String region, String name) {
        return requireGroup(region, name).getTargetGroupARNs();
    }

    public void attachLoadBalancers(String region, String name, List<String> lbNames) {
        AutoScalingGroup asg = requireGroup(region, name);
        for (String lb : lbNames) {
            if (!asg.getLoadBalancerNames().contains(lb)) {
                asg.getLoadBalancerNames().add(lb);
            }
        }
        groups.put(asgKey(region, name), asg);
    }

    public void detachLoadBalancers(String region, String name, List<String> lbNames) {
        AutoScalingGroup asg = requireGroup(region, name);
        asg.getLoadBalancerNames().removeAll(lbNames);
        groups.put(asgKey(region, name), asg);
    }

    // ── Traffic sources ─────────────────────────────────────────────────────────────
    // AttachTrafficSources/DetachTrafficSources/DescribeTrafficSources is the modern,
    // unified ASG-to-load-balancer wiring API (elb, elbv2, vpc-lattice), which
    // aws_autoscaling_traffic_source_attachment uses instead of the older
    // AttachLoadBalancerTargetGroups. "accept and remember" like the target-group
    // attachment above - Floci has no real health-checking loop, so every attached
    // traffic source is reported InService immediately.

    public record TrafficSourceIdentifier(String identifier, String type) {}

    public void attachTrafficSources(String region, String name, List<TrafficSourceIdentifier> sources) {
        AutoScalingGroup asg = requireGroup(region, name);
        for (TrafficSourceIdentifier source : sources) {
            asg.getTrafficSourceTypeByIdentifier().put(source.identifier(),
                    source.type() != null ? source.type() : "elbv2");
        }
        groups.put(asgKey(region, name), asg);
    }

    public void detachTrafficSources(String region, String name, List<TrafficSourceIdentifier> sources) {
        AutoScalingGroup asg = requireGroup(region, name);
        for (TrafficSourceIdentifier source : sources) {
            asg.getTrafficSourceTypeByIdentifier().remove(source.identifier());
        }
        groups.put(asgKey(region, name), asg);
    }

    public Map<String, String> describeTrafficSources(String region, String name, String trafficSourceType) {
        Map<String, String> all = requireGroup(region, name).getTrafficSourceTypeByIdentifier();
        if (trafficSourceType == null || trafficSourceType.isBlank()) {
            return all;
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        all.forEach((identifier, type) -> {
            if (trafficSourceType.equals(type)) {
                filtered.put(identifier, type);
            }
        });
        return filtered;
    }

    // ── Lifecycle hooks ────────────────────────────────────────────────────────

    public void putLifecycleHook(String region, String asgName, String hookName,
                                  String transition, String notificationTargetArn,
                                  String roleArn, String notificationMetadata,
                                  Integer heartbeatTimeout, String defaultResult) {
        requireGroup(region, asgName);
        validateLifecycleHookName(hookName);
        String key = hookKey(region, asgName, hookName);
        LifecycleHook hook = hooks.computeIfAbsent(key, k -> new LifecycleHook());
        hook.setLifecycleHookName(hookName);
        hook.setAutoScalingGroupName(asgName);
        hook.setLifecycleTransition(transition);
        hook.setNotificationTargetArn(notificationTargetArn);
        hook.setRoleArn(roleArn);
        hook.setNotificationMetadata(notificationMetadata);
        if (heartbeatTimeout != null) { hook.setHeartbeatTimeout(heartbeatTimeout); }
        if (defaultResult != null) { hook.setDefaultResult(defaultResult); }
        hooks.put(key, hook);
    }

    public void deleteLifecycleHook(String region, String asgName, String hookName) {
        validateLifecycleHookName(hookName);
        hooks.remove(hookKey(region, asgName, hookName));
    }

    /**
     * Deletes every hook with this name in the region, whichever group owns it. Callers that only
     * hold the hook name use this — the CloudFormation delete path is keyed by physical id, and a
     * lifecycle hook's physical id is its name, not its group.
     */
    public void deleteLifecycleHookByName(String region, String hookName) {
        if (hookName == null) {
            return;
        }
        // The physical id was only ever assigned from a name that already passed this check
        // (see putLifecycleHook), so this call for parity with deleteLifecycleHook should never
        // reject a hook created through the normal path — it exists to fail loudly, not silently
        // no-op, if a corrupted or hand-authored physical id ever reaches this delete path.
        validateLifecycleHookName(hookName);
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, LifecycleHook> entry : hooks.entrySet()) {
            LifecycleHook hook = entry.getValue();
            if (hook == null || !hookName.equals(hook.getLifecycleHookName())) {
                continue;
            }
            if (region != null && !entry.getKey().startsWith(region + "::")) {
                continue;
            }
            keys.add(entry.getKey());
        }
        keys.forEach(hooks::remove);
    }

    public List<LifecycleHook> describeLifecycleHooks(String region, String asgName, List<String> hookNames) {
        requireGroup(region, asgName);
        List<LifecycleHook> result = hooks.values().stream()
                .filter(h -> asgName.equals(h.getAutoScalingGroupName()))
                .collect(Collectors.toList());
        if (hookNames != null && !hookNames.isEmpty()) {
            Set<String> names = new HashSet<>(hookNames);
            result = result.stream().filter(h -> names.contains(h.getLifecycleHookName())).collect(Collectors.toList());
        }
        return result;
    }

    public void completeLifecycleAction(String region, String asgName, String hookName,
                                         String instanceId, String actionResult, String token) {
        // Stored-only — Phase 2 reconciler observes this via the instance lifecycle state
        requireGroup(region, asgName);
        validateLifecycleHookName(hookName);
        if (token != null) {
            validateLifecycleActionToken(token);
        }
    }

    /** LifecycleHookName pattern/length from the Auto Scaling model (autoscaling/2011-01-01/service-2.json). */
    private static final Pattern LIFECYCLE_HOOK_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9\\-_/]+$");

    private static void validateLifecycleHookName(String hookName) {
        if (hookName == null) {
            throw SmithyValidationErrors.validationError(
                    SmithyValidationErrors.notNull("lifecycleHookName"));
        }
        if (hookName.length() > 255) {
            throw SmithyValidationErrors.validationError(
                    "Value '" + hookName
                            + "' at 'lifecycleHookName' failed to satisfy constraint: Member must have length "
                            + "less than or equal to 255");
        }
        if (!LIFECYCLE_HOOK_NAME_PATTERN.matcher(hookName).matches()) {
            throw SmithyValidationErrors.validationError(
                    "Value '" + hookName
                            + "' at 'lifecycleHookName' failed to satisfy constraint: Member must satisfy "
                            + "regular expression pattern: [A-Za-z0-9\\-_/]+");
        }
    }

    /** LifecycleActionToken is modeled as a fixed 36-character token (autoscaling/2011-01-01/service-2.json). */
    private static void validateLifecycleActionToken(String token) {
        if (token == null || token.length() != 36) {
            throw SmithyValidationErrors.validationError(
                    "Value at 'lifecycleActionToken' failed to satisfy constraint: "
                            + "Member must have length equal to 36");
        }
    }

    // ── Scaling policies ───────────────────────────────────────────────────────

    public ScalingPolicy putScalingPolicy(String region, String asgName, String policyName,
                                           String policyType, String adjustmentType,
                                           int scalingAdjustment, int cooldown,
                                           Integer estimatedInstanceWarmup,
                                           ScalingPolicy.TargetTrackingConfiguration targetTrackingConfiguration) {
        requireGroup(region, asgName);
        String key = policyKey(region, asgName, policyName);
        ScalingPolicy policy = policies.computeIfAbsent(key, k -> new ScalingPolicy());
        policy.setPolicyName(policyName);
        policy.setPolicyArn(AwsArnUtils.Arn.of("autoscaling", region, regionResolver.getAccountId(),
                "scalingPolicy:" + asgName + ":" + policyName).toString());
        policy.setAutoScalingGroupName(asgName);
        policy.setPolicyType(policyType != null ? policyType : "SimpleScaling");
        policy.setAdjustmentType(adjustmentType);
        policy.setScalingAdjustment(scalingAdjustment);
        policy.setCooldown(cooldown);
        policy.setEstimatedInstanceWarmup(estimatedInstanceWarmup);
        policy.setTargetTrackingConfiguration(targetTrackingConfiguration);
        policy.setRegion(region);
        policies.put(key, policy);
        return policy;
    }

    public void deletePolicy(String region, String asgName, String policyNameOrArn) {
        policies.entrySet().removeIf(e -> {
            ScalingPolicy p = e.getValue();
            return p.getPolicyName().equals(policyNameOrArn) || p.getPolicyArn().equals(policyNameOrArn);
        });
    }

    public List<ScalingPolicy> describePolicies(String region, String asgName, List<String> policyNames) {
        return policies.values().stream()
                .filter(p -> region.equals(p.getRegion()))
                .filter(p -> asgName == null || asgName.equals(p.getAutoScalingGroupName()))
                .filter(p -> policyNames == null || policyNames.isEmpty() || policyNames.contains(p.getPolicyName()))
                .collect(Collectors.toList());
    }

    // ── Scheduled actions ───────────────────────────────────────────────────
    // PutScheduledUpdateGroupAction/DescribeScheduledActions/DeleteScheduledAction -
    // aws_autoscaling_schedule's whole lifecycle. Floci has no cron scheduler to
    // actually run these, so - same "accept and remember" shape as scaling
    // policies above - this only needs to record and read back the schedule
    // definition itself; the AWS provider's own Read never expects a scheduled
    // action to have fired inside an emulator.

    public ScheduledAction putScheduledUpdateGroupAction(String region, String asgName, String actionName,
                                                           Instant startTime, Instant endTime, String recurrence,
                                                           String timeZone, Integer minSize, Integer maxSize,
                                                           Integer desiredCapacity) {
        requireGroup(region, asgName);
        String key = scheduledActionKey(region, asgName, actionName);
        ScheduledAction action = scheduledActions.computeIfAbsent(key, k -> new ScheduledAction());
        action.setScheduledActionName(actionName);
        action.setAutoScalingGroupName(asgName);
        action.setScheduledActionArn(AwsArnUtils.Arn.of("autoscaling", region, regionResolver.getAccountId(),
                "scheduledUpdateGroupAction:" + asgName + ":" + actionName).toString());
        action.setStartTime(startTime);
        action.setEndTime(endTime);
        action.setRecurrence(recurrence);
        action.setTimeZone(timeZone);
        action.setMinSize(minSize);
        action.setMaxSize(maxSize);
        action.setDesiredCapacity(desiredCapacity);
        action.setRegion(region);
        scheduledActions.put(key, action);
        return action;
    }

    public void deleteScheduledAction(String region, String asgName, String actionName) {
        scheduledActions.remove(scheduledActionKey(region, asgName, actionName));
    }

    public List<ScheduledAction> describeScheduledActions(String region, String asgName, List<String> actionNames) {
        return scheduledActions.values().stream()
                .filter(a -> region.equals(a.getRegion()))
                .filter(a -> asgName == null || asgName.equals(a.getAutoScalingGroupName()))
                .filter(a -> actionNames == null || actionNames.isEmpty() || actionNames.contains(a.getScheduledActionName()))
                .collect(Collectors.toList());
    }

    private static String scheduledActionKey(String region, String asgName, String actionName) {
        return region + "::" + asgName + "::" + actionName;
    }

    // ── Warm pools ──────────────────────────────────────────────────────────
    // PutWarmPool/DescribeWarmPool/DeleteWarmPool - aws_autoscaling_group's
    // warm_pool block. Verified directly against botocore's
    // autoscaling/2011-01-01/service-2.json wire model rather than docs prose:
    // PutWarmPoolType/DeleteWarmPoolType/DescribeWarmPoolType
    // and their *Answer response shapes, WarmPoolConfiguration, WarmPoolState,
    // InstanceReusePolicy. PutWarmPool is a full replace: MinSize's own shape
    // doc says "Defaults to 0 if not specified" and PoolState's says "Default is
    // Stopped" - an omitted field resets to that default, it does not carry the
    // prior value forward, which is why putWarmPool always rebuilds the
    // configuration from scratch rather than mutating the stored one in place.
    // MaxGroupPreparedCapacity has no default (absent unless set), and the shape
    // doc calls out -1 as the caller's own documented sentinel for "clear a
    // previously-set value" - not a real capacity, so it maps back to null and
    // is never rendered as -1.
    //
    // Floci has no scaling loop that actually launches pre-initialized instances
    // into the pool, so DescribeWarmPool's own Instances list is always empty
    // and WarmPoolConfiguration.Status ("PendingDelete") is never set - the
    // "accept and remember" shape scaling policies and scheduled actions above
    // already use for AWS features with no local reconciler. DeleteWarmPool
    // removes the stored configuration outright and is silently idempotent when
    // no warm pool exists, matching deletePolicy above rather than throwing.

    public WarmPoolConfiguration putWarmPool(String region, String asgName,
                                              Integer maxGroupPreparedCapacity,
                                              Integer minSize,
                                              String poolState,
                                              Boolean reuseOnScaleIn) {
        requireGroup(region, asgName);
        WarmPoolConfiguration pool = new WarmPoolConfiguration();
        pool.setAutoScalingGroupName(asgName);
        pool.setRegion(region);
        // -1 is the wire model's own documented sentinel for "remove a
        // previously-set value" - not a literal capacity.
        pool.setMaxGroupPreparedCapacity(
                maxGroupPreparedCapacity != null && maxGroupPreparedCapacity != -1
                        ? maxGroupPreparedCapacity : null);
        pool.setMinSize(minSize != null ? minSize : 0);
        pool.setPoolState(poolState != null && !poolState.isBlank() ? poolState : "Stopped");
        pool.setReuseOnScaleIn(Boolean.TRUE.equals(reuseOnScaleIn));
        warmPools.put(warmPoolKey(region, asgName), pool);
        return pool;
    }

    public WarmPoolConfiguration describeWarmPool(String region, String asgName) {
        requireGroup(region, asgName);
        return warmPools.get(warmPoolKey(region, asgName));
    }

    public void deleteWarmPool(String region, String asgName, boolean forceDelete) {
        requireGroup(region, asgName);
        warmPools.remove(warmPoolKey(region, asgName));
    }

    private static String warmPoolKey(String region, String asgName) {
        return region + "::" + asgName;
    }

    // ── Scaling activities ─────────────────────────────────────────────────────

    public List<ScalingActivity> describeScalingActivities(String region, String asgName) {
        return activities.values().stream()
                .filter(a -> a.getRegion() == null || a.getRegion().equals(region))
                .filter(a -> asgName == null || asgName.equals(a.getAutoScalingGroupName()))
                .sorted(Comparator.comparing(ScalingActivity::getStartTime).reversed())
                .collect(Collectors.toList());
    }

    public ScalingActivity recordActivity(String region, String asgName, String description,
                                           String cause, String statusCode) {
        ScalingActivity activity = new ScalingActivity();
        activity.setActivityId(UUID.randomUUID().toString());
        activity.setAutoScalingGroupName(asgName);
        activity.setRegion(region);
        activity.setDescription(description);
        activity.setCause(cause);
        activity.setStartTime(Instant.now());
        activity.setStatusCode(statusCode);
        activity.setProgress("Successful".equals(statusCode) ? 100 : 0);
        activities.put(activity.getActivityId(), activity);
        return activity;
    }

    public void completeActivity(String activityId, String statusCode, String statusMessage) {
        ScalingActivity activity = activities.get(activityId);
        if (activity != null) {
            activity.setEndTime(Instant.now());
            activity.setStatusCode(statusCode);
            activity.setStatusMessage(statusMessage);
            activity.setProgress(100);
            activities.put(activityId, activity);
        }
    }

    public record InstanceRefreshPage(List<InstanceRefresh> instanceRefreshes, String nextToken) {}

    // ── Internal helpers ───────────────────────────────────────────────────────

    AutoScalingGroup requireGroup(String region, String name) {
        AutoScalingGroup asg = groups.get(asgKey(region, name));
        if (asg == null) {
            throw new AwsException("ValidationError",
                    "Auto Scaling group '" + name + "' not found.", 400);
        }
        return asg;
    }

    private static String lcKey(String region, String name) {
        return region + "::" + name;
    }

    static String asgKey(String region, String name) {
        return region + "::" + name;
    }

    private static String hookKey(String region, String asgName, String hookName) {
        return region + "::" + asgName + "::" + hookName;
    }

    private static String policyKey(String region, String asgName, String policyName) {
        return region + "::" + asgName + "::" + policyName;
    }

    private static void validateLaunchSource(String launchConfigName,
                                             String launchTemplateId,
                                             String launchTemplateName,
                                             MixedInstancesPolicy mixedInstancesPolicy) {
        int sources = 0;
        if (launchConfigName != null) {
            sources++;
        }
        if (launchTemplateId != null || launchTemplateName != null) {
            sources++;
        }
        if (mixedInstancesPolicy != null) {
            sources++;
        }
        if (sources > 1) {
            throw new AwsException("ValidationError",
                    "LaunchConfigurationName, LaunchTemplate, and MixedInstancesPolicy are mutually exclusive.", 400);
        }
        if (mixedInstancesPolicy != null && !hasUsableLaunchTemplate(mixedInstancesPolicy)) {
            throw new AwsException("ValidationError",
                    "A MixedInstancesPolicy must specify a LaunchTemplate with a LaunchTemplateId "
                            + "or LaunchTemplateName.", 400);
        }
        validateLaunchTemplateOverrides(mixedInstancesPolicy);
    }

    private static void validateLaunchTemplateOverrides(MixedInstancesPolicy mixedInstancesPolicy) {
        validateWeightedCapacities(overridesOf(mixedInstancesPolicy));
        for (MixedInstancesPolicy.LaunchTemplateOverride override : overridesOf(mixedInstancesPolicy)) {
            MixedInstancesPolicy.InstanceRequirements requirements = override.getInstanceRequirements();
            if (requirements == null || requirements.isEmpty()) {
                continue;
            }
            if (override.getInstanceType() != null) {
                throw new AwsException("ValidationError",
                        INSTANCE_REQUIREMENTS_AND_INSTANCE_TYPE_MESSAGE, 400);
            }
            if (requirements.getVCpuCount() == null || requirements.getMemoryMiB() == null) {
                throw new AwsException("ValidationError",
                        INSTANCE_REQUIREMENTS_MISSING_RANGES_MESSAGE, 400);
            }
        }
    }

    /**
     * Botocore types {@code WeightedCapacity} as a string of 1-32 characters, while its prose
     * requires a value in the range 1-999 and requires every override to carry one once any does.
     */
    private static void validateWeightedCapacities(List<MixedInstancesPolicy.LaunchTemplateOverride> overrides) {
        int weighted = 0;
        for (MixedInstancesPolicy.LaunchTemplateOverride override : overrides) {
            String weightedCapacity = override.getWeightedCapacity();
            if (weightedCapacity == null) {
                continue;
            }
            weighted++;
            if (weightedCapacity.isEmpty() || weightedCapacity.length() > MAX_WEIGHTED_CAPACITY_LENGTH) {
                throw new AwsException("ValidationError", INVALID_WEIGHTED_CAPACITY_MESSAGE, 400);
            }
            int value;
            try {
                value = Integer.parseInt(weightedCapacity.trim());
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationError", INVALID_WEIGHTED_CAPACITY_MESSAGE, 400);
            }
            if (value < MIN_WEIGHTED_CAPACITY || value > MAX_WEIGHTED_CAPACITY) {
                throw new AwsException("ValidationError", INVALID_WEIGHTED_CAPACITY_MESSAGE, 400);
            }
        }
        if (weighted > 0 && weighted < overrides.size()) {
            throw new AwsException("ValidationError", PARTIAL_WEIGHTED_CAPACITY_MESSAGE, 400);
        }
    }

    /**
     * Botocore documents {@code DesiredCapacityType} as supported "for attribute-based instance type
     * selection only", and it types the member as a plain string rather than an enum, so the rule
     * lives in prose and not in the model constraints.
     *
     * <p>Attribute-based selection here means the mixed instances policy that the request leaves in
     * effect carries at least one launch template override holding a non-empty
     * {@code InstanceRequirements}. On create that is the policy the request supplies. On update it
     * is the policy the request supplies, or the stored one when the request names no launch source
     * at all, or none when the request switches the group to a launch configuration or a plain
     * launch template.
     *
     * <p>{@code units} is the documented default and stays legal for every group.
     */
    private static void validateDesiredCapacityType(String desiredCapacityType,
                                                    MixedInstancesPolicy effectivePolicy) {
        if (desiredCapacityType == null || DEFAULT_DESIRED_CAPACITY_TYPE.equals(desiredCapacityType)) {
            return;
        }
        if (!usesInstanceRequirements(effectivePolicy)) {
            throw new AwsException("ValidationError",
                    DESIRED_CAPACITY_TYPE_NEEDS_INSTANCE_REQUIREMENTS_MESSAGE, 400);
        }
    }

    private static String effectiveDesiredCapacityType(AutoScalingGroup asg,
                                                       AsgOptionalFields optionalFields) {
        if (optionalFields != null && optionalFields.desiredCapacityType() != null) {
            return optionalFields.desiredCapacityType();
        }
        return asg.getDesiredCapacityType();
    }

    private static boolean usesInstanceRequirements(MixedInstancesPolicy mixedInstancesPolicy) {
        for (MixedInstancesPolicy.LaunchTemplateOverride override : overridesOf(mixedInstancesPolicy)) {
            MixedInstancesPolicy.InstanceRequirements requirements = override.getInstanceRequirements();
            if (requirements != null && !requirements.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<MixedInstancesPolicy.LaunchTemplateOverride> overridesOf(
            MixedInstancesPolicy mixedInstancesPolicy) {
        if (mixedInstancesPolicy == null || mixedInstancesPolicy.getLaunchTemplate() == null) {
            return List.of();
        }
        List<MixedInstancesPolicy.LaunchTemplateOverride> overrides =
                mixedInstancesPolicy.getLaunchTemplate().getOverrides();
        return overrides != null ? overrides : List.of();
    }

    private static void validateOptionalFields(AsgOptionalFields optionalFields) {
        if (optionalFields == null) {
            return;
        }
        String desiredCapacityType = optionalFields.desiredCapacityType();
        if (desiredCapacityType != null && !DESIRED_CAPACITY_TYPES.contains(desiredCapacityType)) {
            throw new AwsException("ValidationError", INVALID_DESIRED_CAPACITY_TYPE_MESSAGE, 400);
        }
        Integer maxInstanceLifetime = optionalFields.maxInstanceLifetime();
        if (maxInstanceLifetime != null && maxInstanceLifetime != 0
                && maxInstanceLifetime < MIN_MAX_INSTANCE_LIFETIME_SECONDS) {
            throw new AwsException("ValidationError", INVALID_MAX_INSTANCE_LIFETIME_MESSAGE, 400);
        }
        Integer defaultInstanceWarmup = optionalFields.defaultInstanceWarmup();
        if (defaultInstanceWarmup != null
                && defaultInstanceWarmup < AsgOptionalFields.DEFAULT_INSTANCE_WARMUP_REMOVAL_SENTINEL) {
            throw new AwsException("ValidationError", INVALID_DEFAULT_INSTANCE_WARMUP_MESSAGE, 400);
        }
        String serviceLinkedRoleArn = optionalFields.serviceLinkedRoleArn();
        if (serviceLinkedRoleArn != null
                && (serviceLinkedRoleArn.isEmpty() || serviceLinkedRoleArn.length() > MAX_SERVICE_LINKED_ROLE_ARN_LENGTH)) {
            throw new AwsException("ValidationError", INVALID_SERVICE_LINKED_ROLE_ARN_MESSAGE, 400);
        }
        validateInstanceMaintenancePolicy(optionalFields.instanceMaintenancePolicy());
        AvailabilityZoneDistribution azDistribution = optionalFields.availabilityZoneDistribution();
        if (azDistribution != null && azDistribution.getCapacityDistributionStrategy() != null
                && !CAPACITY_DISTRIBUTION_STRATEGIES.contains(azDistribution.getCapacityDistributionStrategy())) {
            throw new AwsException("ValidationError", INVALID_CAPACITY_DISTRIBUTION_STRATEGY_MESSAGE, 400);
        }
        validateCapacityReservationSpecification(optionalFields.capacityReservationSpecification());
    }

    /**
     * Botocore bounds MinHealthyPercentage to [-1, 100] and MaxHealthyPercentage to [-1, 200], and
     * its prose narrows the maximum to 100-200, requires both members, caps their difference at 100,
     * and reserves -1 for clearing a previously set policy.
     */
    private static void validateInstanceMaintenancePolicy(InstanceMaintenancePolicy policy) {
        if (policy == null) {
            return;
        }
        Integer min = policy.getMinHealthyPercentage();
        Integer max = policy.getMaxHealthyPercentage();
        if (min == null || max == null) {
            throw new AwsException("ValidationError", INSTANCE_MAINTENANCE_POLICY_BOTH_REQUIRED_MESSAGE, 400);
        }
        if (policy.isRemoval()) {
            return;
        }
        if (min == InstanceMaintenancePolicy.REMOVAL_SENTINEL || max == InstanceMaintenancePolicy.REMOVAL_SENTINEL) {
            throw new AwsException("ValidationError", INSTANCE_MAINTENANCE_POLICY_PARTIAL_CLEAR_MESSAGE, 400);
        }
        if (min < 0 || min > 100) {
            throw new AwsException("ValidationError", INVALID_MIN_HEALTHY_PERCENTAGE_MESSAGE, 400);
        }
        if (max < 100 || max > 200) {
            throw new AwsException("ValidationError", INVALID_MAX_HEALTHY_PERCENTAGE_MESSAGE, 400);
        }
        if (max - min > 100) {
            throw new AwsException("ValidationError", INSTANCE_MAINTENANCE_POLICY_RANGE_MESSAGE, 400);
        }
    }

    static void validateCapacityReservationSpecification(CapacityReservationSpecification specification) {
        if (specification == null) {
            return;
        }
        String preference = specification.getCapacityReservationPreference();
        if (preference != null && !CAPACITY_RESERVATION_PREFERENCES.contains(preference)) {
            throw new AwsException("ValidationError", INVALID_CAPACITY_RESERVATION_PREFERENCE_MESSAGE, 400);
        }
        if ("none".equals(preference) && specification.hasTarget()) {
            throw new AwsException("ValidationError", CAPACITY_RESERVATION_TARGET_WITH_NONE_MESSAGE, 400);
        }
        if (specification.getCapacityReservationIds().size()
                + specification.getCapacityReservationResourceGroupArns().size() > 1) {
            throw new AwsException("ValidationError", "Specify only one Capacity Reservation target.", 400);
        }
        for (String id : specification.getCapacityReservationIds()) {
            if (id == null || id.isEmpty() || id.length() > 255
                    || !CAPACITY_RESERVATION_ID_PATTERN.matcher(id).matches()) {
                throw SmithyValidationErrors.validationError(
                        "Value '" + id
                                + "' at 'capacityReservationSpecification.capacityReservationTarget"
                                + ".capacityReservationIds' failed to satisfy constraint: Member must have length "
                                + "between 1 and 255 and satisfy regular expression pattern: [A-Za-z0-9\\-_\\/]+");
            }
        }
        for (String arn : specification.getCapacityReservationResourceGroupArns()) {
            if (arn == null || arn.isEmpty() || arn.length() > MAX_RESOURCE_NAME_LENGTH) {
                throw SmithyValidationErrors.validationError(
                        "Value at 'capacityReservationSpecification"
                                + ".capacityReservationTarget.capacityReservationResourceGroupArns' failed to "
                                + "satisfy constraint: Member must have length between 1 and 1600");
            }
        }
    }

    private static boolean hasUsableLaunchTemplate(MixedInstancesPolicy mixedInstancesPolicy) {
        MixedInstancesPolicy.LaunchTemplate launchTemplate = mixedInstancesPolicy.getLaunchTemplate();
        if (launchTemplate == null) {
            return false;
        }
        MixedInstancesPolicy.LaunchTemplateSpecification specification =
                launchTemplate.getLaunchTemplateSpecification();
        if (specification == null) {
            return false;
        }
        return notBlank(specification.getLaunchTemplateId())
                || notBlank(specification.getLaunchTemplateName());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private LaunchIdentity effectiveLaunchIdentity(AutoScalingGroup asg,
                                                   String launchConfigName,
                                                   String launchTemplateId,
                                                   String launchTemplateName,
                                                   String launchTemplateVersion,
                                                   MixedInstancesPolicy mixedInstancesPolicy) {
        LaunchIdentity identity = new LaunchIdentity(
                asg.getLaunchConfigurationName(),
                asg.getLaunchTemplateId(),
                asg.getLaunchTemplateName(),
                asg.getLaunchTemplateVersion(),
                asg.getMixedInstancesPolicy());
        if (launchConfigName != null) {
            return new LaunchIdentity(launchConfigName, null, null, null, null);
        }
        if (launchTemplateId != null || launchTemplateName != null) {
            return new LaunchIdentity(null, launchTemplateId, launchTemplateName, launchTemplateVersion, null);
        }
        if (mixedInstancesPolicy != null) {
            return new LaunchIdentity(null, null, null, null, mixedInstancesPolicy);
        }
        return identity;
    }

    private void validateEffectiveLaunchImage(String region,
                                              String launchConfigName,
                                              String launchTemplateId,
                                              String launchTemplateName,
                                              String launchTemplateVersion,
                                              MixedInstancesPolicy mixedInstancesPolicy) {
        if (launchConfigName != null) {
            return;
        }
        if (launchTemplateId != null || launchTemplateName != null) {
            validateLaunchTemplateImage(region, launchTemplateId, launchTemplateName, launchTemplateVersion);
            return;
        }
        MixedInstancesPolicy.LaunchTemplateSpecification specification =
                mixedInstancesLaunchTemplateSpecification(mixedInstancesPolicy);
        if (specification != null) {
            validateLaunchTemplateImage(region,
                    specification.getLaunchTemplateId(),
                    specification.getLaunchTemplateName(),
                    specification.getVersion());
        }
    }

    private void validateLaunchTemplateImage(String region, String launchTemplateId,
                                             String launchTemplateName, String launchTemplateVersion) {
        if (ec2Service == null) {
            return;
        }
        List<String> versions = isBlank(launchTemplateVersion) ? List.of() : List.of(launchTemplateVersion);
        List<LaunchTemplate> launchTemplateVersions = ec2Service.describeLaunchTemplateVersions(
                region,
                launchTemplateId,
                launchTemplateName,
                versions);
        if (launchTemplateVersions.isEmpty()) {
            throw invalidLaunchTemplate();
        }
        LaunchTemplateData data = launchTemplateVersions.getFirst().getData();
        if (isBlank(data.getImageId())) {
            throw missingLaunchTemplateImageId();
        }
        // EC2 stores a template naming both profile selectors; the group's launch would fail, so
        // the group is refused up front, as AWS does.
        if (data.getIamInstanceProfile() != null) {
            try {
                Ec2Service.validateLaunchProfileSelectors(data.getIamInstanceProfile().getName(),
                        data.getIamInstanceProfile().getArn());
            } catch (AwsException invalid) {
                throw new AwsException("ValidationError", FULLY_FORMED_LAUNCH_TEMPLATE + invalid.getMessage(), 400);
            }
        }
    }

    /**
     * The instance profile a group's instances get from the launch template version, resolved as the
     * group's own validation resolves it, for the {@code iam:PassRole} check of the operations naming it.
     */
    @Override
    public Optional<String> launchTemplateInstanceProfile(String templateId, String templateName, String version,
                                                          String region) {
        if (ec2Service == null || (isBlank(templateId) && isBlank(templateName))) {
            return Optional.empty();
        }
        List<LaunchTemplate> versions;
        try {
            versions = ec2Service.describeLaunchTemplateVersions(region, templateId, templateName,
                    isBlank(version) ? List.of() : List.of(version));
        } catch (AwsException e) {
            // A version that does not exist is the operation's to refuse, and passes no role.
            LOG.debugv("Launch template {0} has no version {1}, so it passes no role: {2}",
                    isBlank(templateId) ? templateName : templateId, version, e.getMessage());
            return Optional.empty();
        }
        LaunchTemplateData.IamInstanceProfile profile =
                versions.isEmpty() ? null : versions.getFirst().getData().getIamInstanceProfile();
        if (profile == null) {
            return Optional.empty();
        }
        if (!isBlank(profile.getArn())) {
            return Optional.of(profile.getArn());
        }
        return isBlank(profile.getName()) ? Optional.empty() : Optional.of(profile.getName());
    }

    private static MixedInstancesPolicy.LaunchTemplateSpecification mixedInstancesLaunchTemplateSpecification(
            MixedInstancesPolicy policy) {
        if (policy == null || policy.getLaunchTemplate() == null) {
            return null;
        }
        MixedInstancesPolicy.LaunchTemplateSpecification specification =
                policy.getLaunchTemplate().getLaunchTemplateSpecification();
        if (specification == null) {
            return null;
        }
        if (isBlank(specification.getLaunchTemplateId()) && isBlank(specification.getLaunchTemplateName())) {
            return null;
        }
        return specification;
    }

    private static AwsException missingLaunchTemplateImageId() {
        return new AwsException("ValidationError", MISSING_LAUNCH_TEMPLATE_IMAGE_ID_MESSAGE, 400);
    }

    private static AwsException invalidLaunchTemplate() {
        return new AwsException("ValidationError", INVALID_LAUNCH_TEMPLATE_MESSAGE, 400);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record LaunchIdentity(
            String launchConfigurationName,
            String launchTemplateId,
            String launchTemplateName,
            String launchTemplateVersion,
            MixedInstancesPolicy mixedInstancesPolicy) {}

    private static String instanceRefreshKey(String region, String asgName, String refreshId) {
        return region + "::" + asgName + "::" + refreshId;
    }

    private static boolean isActiveRefreshStatus(String status) {
        return "Pending".equals(status)
                || "InProgress".equals(status)
                || "Cancelling".equals(status)
                || "RollbackInProgress".equals(status)
                || "Baking".equals(status);
    }

    private void rejectDesiredConfigurationUpdateDuringActiveRefresh(String region, String asgName,
                                                                     String launchConfigName,
                                                                     String launchTemplateId,
                                                                     String launchTemplateName,
                                                                     String launchTemplateVersion,
                                                                     MixedInstancesPolicy mixedInstancesPolicy) {
        if (!updatesLaunchSource(launchConfigName, launchTemplateId, launchTemplateName, launchTemplateVersion, mixedInstancesPolicy)) {
            return;
        }
        boolean activeDesiredConfigurationRefresh = instanceRefreshes.values().stream()
                .filter(r -> region.equals(r.getRegion()))
                .filter(r -> asgName.equals(r.getAutoScalingGroupName()))
                .filter(r -> isActiveRefreshStatus(r.getStatus()))
                .anyMatch(InstanceRefresh::hasDesiredConfiguration);
        if (activeDesiredConfigurationRefresh) {
            throw new AwsException("ValidationError", ACTIVE_INSTANCE_REFRESH_DESIRED_CONFIGURATION_MESSAGE, 400);
        }
    }

    private static boolean updatesLaunchSource(String launchConfigName,
                                               String launchTemplateId,
                                               String launchTemplateName,
                                               String launchTemplateVersion,
                                               MixedInstancesPolicy mixedInstancesPolicy) {
        return launchConfigName != null
                || launchTemplateId != null
                || launchTemplateName != null
                || launchTemplateVersion != null
                || mixedInstancesPolicy != null;
    }

    private static String normalizeRefreshStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return "Rolling";
        }
        if (!"Rolling".equals(strategy) && !"ReplaceRootVolume".equals(strategy)) {
            throw new AwsException("ValidationError",
                    "Unsupported instance refresh strategy '" + strategy + "'.", 400);
        }
        return strategy;
    }

    private static int parseNextToken(String nextToken) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(nextToken);
            if (offset < 0) {
                throw new NumberFormatException("negative");
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidNextToken", "The NextToken value is not valid.", 400);
        }
    }

    private static void copyDesiredConfiguration(InstanceRefresh source, InstanceRefresh target) {
        target.setDesiredLaunchTemplateId(source.getDesiredLaunchTemplateId());
        target.setDesiredLaunchTemplateName(source.getDesiredLaunchTemplateName());
        target.setDesiredLaunchTemplateVersion(source.getDesiredLaunchTemplateVersion());
    }

    private static void copyPreferences(InstanceRefresh source, InstanceRefresh target) {
        target.setMinHealthyPercentage(source.getMinHealthyPercentage());
        target.setMaxHealthyPercentage(source.getMaxHealthyPercentage());
        target.setInstanceWarmup(source.getInstanceWarmup());
        target.setSkipMatching(source.getSkipMatching());
        target.setAutoRollback(source.getAutoRollback());
        target.setScaleInProtectedInstances(source.getScaleInProtectedInstances());
        target.setStandbyInstances(source.getStandbyInstances());
        target.setCheckpointDelay(source.getCheckpointDelay());
        target.setBakeTime(source.getBakeTime());
        target.setCheckpointPercentages(new ArrayList<>(source.getCheckpointPercentages()));
    }

    private static void applyDesiredConfiguration(AutoScalingGroup asg, InstanceRefresh refresh) {
        if (!refresh.hasDesiredConfiguration()) {
            return;
        }
        DesiredLaunchTemplate desired = desiredLaunchTemplate(asg, refresh.getDesiredLaunchTemplateId(),
                refresh.getDesiredLaunchTemplateName(), refresh.getDesiredLaunchTemplateVersion());
        if (desired.replacesTemplate()) {
            asg.setLaunchConfigurationName(null);
        }
        asg.setLaunchTemplateId(desired.id());
        asg.setLaunchTemplateName(desired.name());
        asg.setLaunchTemplateVersion(desired.version());
    }

    /** The launch template a group launches from once a refresh's desired configuration is applied to it. */
    private record DesiredLaunchTemplate(String id, String name, String version, boolean replacesTemplate) {
    }

    /**
     * A desired configuration naming a template replaces the group's, and one naming a version replaces
     * its version, so a template named without a version keeps the group's version number.
     */
    private static DesiredLaunchTemplate desiredLaunchTemplate(AutoScalingGroup asg, String templateId,
                                                               String templateName, String version) {
        boolean replacesTemplate = templateId != null || templateName != null;
        return new DesiredLaunchTemplate(
                replacesTemplate ? templateId : asg.getLaunchTemplateId(),
                replacesTemplate ? templateName : asg.getLaunchTemplateName(),
                version != null ? version : asg.getLaunchTemplateVersion(),
                replacesTemplate);
    }

    /**
     * The instance profile the group launches with once the refresh applies its desired configuration,
     * merged with the group as {@link #applyDesiredConfiguration} merges it, for the {@code iam:PassRole} check.
     */
    @Override
    public Optional<String> instanceRefreshInstanceProfile(String groupName, String templateId, String templateName,
                                                           String version, String region) {
        AutoScalingGroup asg = isBlank(groupName) ? null : groups.get(asgKey(region, groupName));
        if (asg == null || (templateId == null && templateName == null && version == null)) {
            return Optional.empty();
        }
        DesiredLaunchTemplate desired = desiredLaunchTemplate(asg, templateId, templateName, version);
        return launchTemplateInstanceProfile(desired.id(), desired.name(), desired.version(), region);
    }

    private static List<String> markInstancesForRefresh(AutoScalingGroup asg, InstanceRefresh refresh) {
        boolean skipMatching = Boolean.TRUE.equals(refresh.getSkipMatching());
        List<String> instanceIds = new ArrayList<>();
        for (AsgInstance instance : asg.getInstances()) {
            if (!isRefreshCandidate(instance, asg, skipMatching)) {
                continue;
            }
            instance.setLifecycleState("Terminating");
            instanceIds.add(instance.getInstanceId());
        }
        return instanceIds;
    }

    private static boolean isRefreshCandidate(AsgInstance instance, AutoScalingGroup asg, boolean skipMatching) {
        String state = instance.getLifecycleState();
        if (!"Pending".equals(state) && !"InService".equals(state)) {
            return false;
        }
        if (!skipMatching) {
            return true;
        }
        if (isDynamicLaunchTemplateVersion(asg.getLaunchTemplateVersion())
                && (asg.getLaunchTemplateId() != null || asg.getLaunchTemplateName() != null)) {
            return true;
        }
        return !Objects.equals(instance.getLaunchConfigurationName(), asg.getLaunchConfigurationName())
                || !Objects.equals(instance.getLaunchTemplateId(), asg.getLaunchTemplateId())
                || !Objects.equals(instance.getLaunchTemplateName(), asg.getLaunchTemplateName())
                || !Objects.equals(instance.getLaunchTemplateVersion(), asg.getLaunchTemplateVersion());
    }

    private static int remainingInstancesToUpdate(AutoScalingGroup asg) {
        int remaining = 0;
        int matchingInService = 0;
        for (AsgInstance instance : asg.getInstances()) {
            String state = instance.getLifecycleState();
            if ("Terminating".equals(state) || "Pending".equals(state)) {
                remaining++;
                continue;
            }
            if ("InService".equals(state)) {
                if (matchesCurrentLaunchSource(instance, asg)) {
                    matchingInService++;
                } else {
                    remaining++;
                }
            }
        }
        remaining += Math.max(0, asg.getDesiredCapacity() - matchingInService);
        return remaining;
    }

    private static boolean matchesCurrentLaunchSource(AsgInstance instance, AutoScalingGroup asg) {
        if (isDynamicLaunchTemplateVersion(asg.getLaunchTemplateVersion())
                && Objects.equals(instance.getLaunchTemplateId(), asg.getLaunchTemplateId())
                && Objects.equals(instance.getLaunchTemplateName(), asg.getLaunchTemplateName())) {
            return true;
        }
        return Objects.equals(instance.getLaunchConfigurationName(), asg.getLaunchConfigurationName())
                && Objects.equals(instance.getLaunchTemplateId(), asg.getLaunchTemplateId())
                && Objects.equals(instance.getLaunchTemplateName(), asg.getLaunchTemplateName())
                && Objects.equals(instance.getLaunchTemplateVersion(), asg.getLaunchTemplateVersion());
    }

    private static boolean isLaunchTemplateVersionAlias(String version) {
        return "$Latest".equals(version) || "$Default".equals(version);
    }

    private static boolean isDynamicLaunchTemplateVersion(String version) {
        return version == null || version.isBlank() || isLaunchTemplateVersionAlias(version);
    }
}
