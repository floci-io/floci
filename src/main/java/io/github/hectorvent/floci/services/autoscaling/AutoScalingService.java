package io.github.hectorvent.floci.services.autoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.autoscaling.model.*;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AutoScalingService {

    static final String MISSING_LAUNCH_TEMPLATE_IMAGE_ID_MESSAGE =
            "You must use a valid fully-formed launch template. The request must contain the parameter ImageId";
    static final String INVALID_LAUNCH_TEMPLATE_MESSAGE =
            "The specified launch template does not exist.";
    static final String INVALID_LAUNCH_CONFIGURATION_PARAMETERS_MESSAGE =
            "Valid requests must contain either the InstanceID parameter "
                    + "or both the ImageId and InstanceType parameters.";
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
    private Map<String, AutoScalingGroup> groups = new ConcurrentHashMap<>();
    private Map<String, LifecycleHook> hooks = new ConcurrentHashMap<>();
    private Map<String, ScalingPolicy> policies = new ConcurrentHashMap<>();
    private Map<String, ScalingActivity> activities = new ConcurrentHashMap<>();
    private Map<String, InstanceRefresh> instanceRefreshes = new ConcurrentHashMap<>();

    @PostConstruct
    void initializeStorage()
    {
        if (storageFactory == null) {
            return;
        }
        this.launchConfigs = storageBacked("autoscaling-launch-configurations.json", new TypeReference<Map<String, LaunchConfiguration>>() {});
        this.groups = storageBacked("autoscaling-groups.json", new TypeReference<Map<String, AutoScalingGroup>>() {});
        this.hooks = storageBacked("autoscaling-lifecycle-hooks.json", new TypeReference<Map<String, LifecycleHook>>() {});
        this.policies = storageBacked("autoscaling-policies.json", new TypeReference<Map<String, ScalingPolicy>>() {});
        this.activities = storageBacked("autoscaling-activities.json", new TypeReference<Map<String, ScalingActivity>>() {});
        this.instanceRefreshes = storageBacked("autoscaling-instance-refreshes.json", new TypeReference<Map<String, InstanceRefresh>>() {});
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
                                                          boolean associatePublicIpAddress) {
        String key = lcKey(region, name);
        if (launchConfigs.containsKey(key)) {
            throw new AwsException("AlreadyExists",
                    "Launch configuration '" + name + "' already exists.", 400);
        }
        if (isBlank(instanceId) && (isBlank(imageId) || isBlank(instanceType))) {
            throw new AwsException("ValidationError", INVALID_LAUNCH_CONFIGURATION_PARAMETERS_MESSAGE, 400);
        }
        if (notBlank(instanceId) && ec2Service != null) {
            List<Instance> sourceInstances = ec2Service.describeInstances(region, List.of(instanceId), Map.of())
                    .stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .collect(Collectors.toList());
            if (!sourceInstances.isEmpty()) {
                Instance source = sourceInstances.getFirst();
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
                    userData = source.getUserData();
                }
                if (isBlank(iamInstanceProfile)) {
                    iamInstanceProfile = source.getIamInstanceProfileArn();
                }
            }
        }
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
        lc.setIamInstanceProfile(iamInstanceProfile);
        lc.setAssociatePublicIpAddress(associatePublicIpAddress);
        lc.setCreatedTime(Instant.now());
        lc.setRegion(region);
        launchConfigs.put(key, lc);
        return lc;
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
                                                    Map<String, String> tags) {
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
                                        List<String> terminationPolicies) {
        AutoScalingGroup asg = requireGroup(region, name);
        validateLaunchSource(launchConfigName, launchTemplateId, launchTemplateName, mixedInstancesPolicy);
        if (launchTemplateVersion != null && launchTemplateId == null && launchTemplateName == null) {
            throw new AwsException("ValidationError",
                    "LaunchTemplateVersion requires a LaunchTemplateId or LaunchTemplateName.", 400);
        }
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
        groups.put(asgKey(region, name), asg);
    }

    public void deleteAutoScalingGroup(String region, String name, boolean forceDelete) {
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
        groups.remove(asgKey(region, name));
        // clean up associated hooks and policies
        hooks.entrySet().removeIf(e -> e.getValue().getAutoScalingGroupName().equals(name));
        policies.entrySet().removeIf(e -> e.getValue().getAutoScalingGroupName().equals(name));
        instanceRefreshes.entrySet().removeIf(e -> e.getValue().getAutoScalingGroupName().equals(name));
    }

    public List<AutoScalingGroup> describeAutoScalingGroups(String region, List<String> names) {
        if (names != null && !names.isEmpty()) {
            return names.stream()
                    .map(n -> groups.get(asgKey(region, n)))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        return groups.values().stream()
                .filter(g -> region == null || region.equals(g.getRegion()))
                .collect(Collectors.toList());
    }

    public void saveAutoScalingGroup(AutoScalingGroup asg) {
        groups.put(asgKey(asg.getRegion(), asg.getAutoScalingGroupName()), asg);
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

    public void createOrUpdateTags(String region, String resourceId, String resourceType, Map<String, String> tags) {
        AutoScalingGroup asg = requireTaggableGroup(region, resourceId, resourceType);
        asg.getTags().putAll(tags);
        groups.put(asgKey(region, asg.getAutoScalingGroupName()), asg);
    }

    public void deleteTags(String region, String resourceId, String resourceType, List<String> tagKeys) {
        AutoScalingGroup asg = requireTaggableGroup(region, resourceId, resourceType);
        tagKeys.forEach(asg.getTags()::remove);
        groups.put(asgKey(region, asg.getAutoScalingGroupName()), asg);
    }

    private AutoScalingGroup requireTaggableGroup(String region, String resourceId, String resourceType) {
        if (!"auto-scaling-group".equals(resourceType)) {
            throw new AwsException("ValidationError",
                    "Unsupported tag resource type '" + resourceType + "'.", 400);
        }
        return requireGroup(region, resourceId);
    }

    // ── Instance management ────────────────────────────────────────────────────

    public List<AsgInstance> describeAutoScalingInstances(String region, List<String> instanceIds) {
        List<AsgInstance> all = groups.values().stream()
                .filter(g -> region.equals(g.getRegion()))
                .flatMap(g -> g.getInstances().stream())
                .collect(Collectors.toList());
        if (instanceIds != null && !instanceIds.isEmpty()) {
            Set<String> ids = new HashSet<>(instanceIds);
            return all.stream().filter(i -> ids.contains(i.getInstanceId())).collect(Collectors.toList());
        }
        return all;
    }

    public void attachInstances(String region, String name, List<String> instanceIds) {
        AutoScalingGroup asg = requireGroup(region, name);
        for (String id : instanceIds) {
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

    public void detachInstances(String region, String name, List<String> instanceIds,
                                 boolean decrementDesiredCapacity) {
        AutoScalingGroup asg = requireGroup(region, name);
        asg.getInstances().removeIf(i -> instanceIds.contains(i.getInstanceId()));
        if (decrementDesiredCapacity) {
            int newDesired = Math.max(asg.getMinSize(), asg.getDesiredCapacity() - instanceIds.size());
            asg.setDesiredCapacity(newDesired);
        }
        groups.put(asgKey(region, name), asg);
    }

    public void terminateInstanceInAutoScalingGroup(String region, String instanceId,
                                                     boolean decrementDesiredCapacity) {
        AutoScalingGroup asg = groups.values().stream()
                .filter(g -> region.equals(g.getRegion()))
                .filter(g -> g.getInstances().stream().anyMatch(i -> instanceId.equals(i.getInstanceId())))
                .findFirst()
                .orElseThrow(() -> new AwsException("ValidationError",
                        "Instance '" + instanceId + "' not found in any Auto Scaling group.", 400));
        asg.getInstances().stream()
                .filter(i -> instanceId.equals(i.getInstanceId()))
                .findFirst()
                .ifPresent(i -> i.setLifecycleState("Terminating"));
        if (decrementDesiredCapacity) {
            int newDesired = Math.max(asg.getMinSize(), asg.getDesiredCapacity() - 1);
            asg.setDesiredCapacity(newDesired);
        }
        groups.put(asgKey(region, asg.getAutoScalingGroupName()), asg);
    }

    // ── Instance refreshes ────────────────────────────────────────────────────

    public InstanceRefresh startInstanceRefresh(String region, String asgName, InstanceRefresh requestedRefresh) {
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

    public void completeInstanceRefreshIfSettled(String region, String asgName) {
        AutoScalingGroup asg = requireGroup(region, asgName);
        List<InstanceRefresh> activeRefreshes = instanceRefreshes.values().stream()
                .filter(r -> region.equals(r.getRegion()))
                .filter(r -> asgName.equals(r.getAutoScalingGroupName()))
                .filter(r -> isActiveRefreshStatus(r.getStatus()))
                .collect(Collectors.toList());
        for (InstanceRefresh refresh : activeRefreshes) {
            int remaining = remainingInstancesToUpdate(asg);
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

    // ── Lifecycle hooks ────────────────────────────────────────────────────────

    public void putLifecycleHook(String region, String asgName, String hookName,
                                  String transition, String notificationTargetArn,
                                  String roleArn, String notificationMetadata,
                                  Integer heartbeatTimeout, String defaultResult) {
        requireGroup(region, asgName);
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
        hooks.remove(hookKey(region, asgName, hookName));
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

    // ── Scaling activities ─────────────────────────────────────────────────────

    public List<ScalingActivity> describeScalingActivities(String region, String asgName) {
        return activities.values().stream()
                .filter(a -> asgName == null || asgName.equals(a.getAutoScalingGroupName()))
                .sorted(Comparator.comparing(ScalingActivity::getStartTime).reversed())
                .collect(Collectors.toList());
    }

    public ScalingActivity recordActivity(String region, String asgName, String description,
                                           String cause, String statusCode) {
        ScalingActivity activity = new ScalingActivity();
        activity.setActivityId(UUID.randomUUID().toString());
        activity.setAutoScalingGroupName(asgName);
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
        if (isBlank(launchTemplateVersions.getFirst().getImageId())) {
            throw missingLaunchTemplateImageId();
        }
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
        if (refresh.getDesiredLaunchTemplateId() != null || refresh.getDesiredLaunchTemplateName() != null) {
            asg.setLaunchTemplateId(refresh.getDesiredLaunchTemplateId());
            asg.setLaunchTemplateName(refresh.getDesiredLaunchTemplateName());
            asg.setLaunchConfigurationName(null);
        }
        if (refresh.getDesiredLaunchTemplateVersion() != null) {
            asg.setLaunchTemplateVersion(refresh.getDesiredLaunchTemplateVersion());
        }
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
