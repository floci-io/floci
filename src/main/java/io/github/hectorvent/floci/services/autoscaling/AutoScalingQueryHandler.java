package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.autoscaling.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@ApplicationScoped
public class AutoScalingQueryHandler {

    private static final Logger LOG = Logger.getLogger(AutoScalingQueryHandler.class);
    private static final String NS = AwsNamespaces.AUTOSCALING;
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final AutoScalingService service;

    @Inject
    AutoScalingQueryHandler(AutoScalingService service) {
        this.service = service;
    }

    public Response handle(String action, MultivaluedMap<String, String> p, String region) {
        LOG.debugv("AutoScaling action: {0}", action);
        try {
            return switch (action) {
                // Launch Configuration
                case "CreateLaunchConfiguration"    -> handleCreateLaunchConfiguration(p, region);
                case "DescribeLaunchConfigurations" -> handleDescribeLaunchConfigurations(p, region);
                case "DeleteLaunchConfiguration"    -> handleDeleteLaunchConfiguration(p, region);
                // ASG
                case "CreateAutoScalingGroup"       -> handleCreateAutoScalingGroup(p, region);
                case "UpdateAutoScalingGroup"       -> handleUpdateAutoScalingGroup(p, region);
                case "DeleteAutoScalingGroup"       -> handleDeleteAutoScalingGroup(p, region);
                case "DescribeAutoScalingGroups"    -> handleDescribeAutoScalingGroups(p, region);
                case "SetDesiredCapacity"           -> handleSetDesiredCapacity(p, region);
                case "SuspendProcesses"             -> handleSuspendProcesses(p, region);
                case "ResumeProcesses"              -> handleResumeProcesses(p, region);
                case "StartInstanceRefresh"         -> handleStartInstanceRefresh(p, region);
                case "DescribeInstanceRefreshes"    -> handleDescribeInstanceRefreshes(p, region);
                case "CreateOrUpdateTags"           -> handleCreateOrUpdateTags(p, region);
                case "DeleteTags"                   -> handleDeleteTags(p, region);
                case "EnableMetricsCollection"       -> handleEnableMetricsCollection(p, region);
                case "DisableMetricsCollection"      -> handleDisableMetricsCollection(p, region);
                // Warm pools
                case "PutWarmPool"                  -> handlePutWarmPool(p, region);
                case "DescribeWarmPool"             -> handleDescribeWarmPool(p, region);
                case "DeleteWarmPool"               -> handleDeleteWarmPool(p, region);
                // Instances
                case "DescribeAutoScalingInstances" -> handleDescribeAutoScalingInstances(p, region);
                case "AttachInstances"              -> handleAttachInstances(p, region);
                case "DetachInstances"              -> handleDetachInstances(p, region);
                case "TerminateInstanceInAutoScalingGroup" -> handleTerminateInstance(p, region);
                // Load balancer attachment
                case "AttachLoadBalancerTargetGroups"    -> handleAttachLoadBalancerTargetGroups(p, region);
                case "DetachLoadBalancerTargetGroups"    -> handleDetachLoadBalancerTargetGroups(p, region);
                case "DescribeLoadBalancerTargetGroups"  -> handleDescribeLoadBalancerTargetGroups(p, region);
                case "AttachLoadBalancers"               -> handleAttachLoadBalancers(p, region);
                case "DetachLoadBalancers"               -> handleDetachLoadBalancers(p, region);
                case "DescribeLoadBalancers"             -> handleDescribeLoadBalancers(p, region);
                // Traffic sources
                case "AttachTrafficSources"              -> handleAttachTrafficSources(p, region);
                case "DetachTrafficSources"               -> handleDetachTrafficSources(p, region);
                case "DescribeTrafficSources"             -> handleDescribeTrafficSources(p, region);
                // Lifecycle hooks
                case "PutLifecycleHook"             -> handlePutLifecycleHook(p, region);
                case "DeleteLifecycleHook"          -> handleDeleteLifecycleHook(p, region);
                case "DescribeLifecycleHooks"       -> handleDescribeLifecycleHooks(p, region);
                case "CompleteLifecycleAction"      -> handleCompleteLifecycleAction(p, region);
                case "RecordLifecycleActionHeartbeat" -> handleRecordLifecycleActionHeartbeat();
                // Scaling policies
                case "PutScalingPolicy"             -> handlePutScalingPolicy(p, region);
                case "DeletePolicy"                 -> handleDeletePolicy(p, region);
                case "DescribePolicies"             -> handleDescribePolicies(p, region);
                // Scheduled actions
                case "PutScheduledUpdateGroupAction" -> handlePutScheduledUpdateGroupAction(p, region);
                case "DeleteScheduledAction"         -> handleDeleteScheduledAction(p, region);
                case "DescribeScheduledActions"      -> handleDescribeScheduledActions(p, region);
                // Activities
                case "DescribeScalingActivities"    -> handleDescribeScalingActivities(p, region);
                // Metadata
                case "DescribeAutoScalingNotificationTypes" -> handleDescribeNotificationTypes();
                case "DescribeTerminationPolicyTypes"       -> handleDescribeTerminationPolicyTypes();
                case "DescribeAdjustmentTypes"              -> handleDescribeAdjustmentTypes();
                case "DescribeAccountLimits"                -> handleDescribeAccountLimits();
                case "DescribeLifecycleHookTypes"           -> handleDescribeLifecycleHookTypes();
                case "DescribeMetricCollectionTypes"        -> handleDescribeMetricCollectionTypes();
                default -> xmlError("UnsupportedOperation",
                        "Operation " + action + " is not supported.", 400);
            };
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        } catch (Exception e) {
            LOG.warnv("Unexpected error in AutoScaling action {0}: {1}", action, e.getMessage());
            return xmlError("InternalFailure", e.getMessage(), 500);
        }
    }

    // ── Launch Configuration ──────────────────────────────────────────────────

    private Response handleCreateLaunchConfiguration(MultivaluedMap<String, String> p, String region) {
        service.createLaunchConfiguration(region,
                p.getFirst("LaunchConfigurationName"),
                p.getFirst("InstanceId"),
                p.getFirst("ImageId"),
                p.getFirst("InstanceType"),
                p.getFirst("KeyName"),
                memberList(p, "SecurityGroups"),
                p.getFirst("UserData"),
                p.getFirst("IamInstanceProfile"),
                nullableBoolParam(p, "AssociatePublicIpAddress"));
        String xml = new XmlBuilder()
                .start("CreateLaunchConfigurationResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("CreateLaunchConfigurationResponse")
                .build();
        return ok(xml);
    }

    private Response handleDescribeLaunchConfigurations(MultivaluedMap<String, String> p, String region) {
        List<LaunchConfiguration> lcs = service.describeLaunchConfigurations(
                region, memberList(p, "LaunchConfigurationNames"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeLaunchConfigurationsResponse", NS)
                  .start("DescribeLaunchConfigurationsResult")
                    .start("LaunchConfigurations");
        for (LaunchConfiguration lc : lcs) {
            xml.start("member")
               .elem("LaunchConfigurationName", lc.getLaunchConfigurationName())
               .elem("LaunchConfigurationARN", lc.getLaunchConfigurationArn())
               .elem("CreatedTime", ISO_FMT.format(lc.getCreatedTime()));
            // AWS omits the flag entirely when the LC never set it, which is
            // what tells a caller the subnet default still applies.
            if (lc.getAssociatePublicIpAddress() != null) {
                xml.elem("AssociatePublicIpAddress", String.valueOf(lc.getAssociatePublicIpAddress()));
            }
            if (lc.getImageId() != null) { xml.elem("ImageId", lc.getImageId()); }
            if (lc.getInstanceType() != null) { xml.elem("InstanceType", lc.getInstanceType()); }
            if (lc.getKeyName() != null) { xml.elem("KeyName", lc.getKeyName()); }
            if (lc.getUserData() != null) { xml.elem("UserData", lc.getUserData()); }
            if (lc.getIamInstanceProfile() != null) { xml.elem("IamInstanceProfile", lc.getIamInstanceProfile()); }
            xml.start("SecurityGroups");
            for (String sg : lc.getSecurityGroups()) { xml.elem("member", sg); }
            xml.end("SecurityGroups").end("member");
        }
        xml.end("LaunchConfigurations")
           .end("DescribeLaunchConfigurationsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeLaunchConfigurationsResponse");
        return ok(xml.build());
    }

    private Response handleDeleteLaunchConfiguration(MultivaluedMap<String, String> p, String region) {
        service.deleteLaunchConfiguration(region, p.getFirst("LaunchConfigurationName"));
        return ok(new XmlBuilder()
                .start("DeleteLaunchConfigurationResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DeleteLaunchConfigurationResponse").build());
    }

    // ── Auto Scaling Group ────────────────────────────────────────────────────

    private Response handleCreateAutoScalingGroup(MultivaluedMap<String, String> p, String region) {
        ParsedTags parsedTags = parseTags(p);
        AutoScalingGroup asg = service.createAutoScalingGroup(region,
                p.getFirst("AutoScalingGroupName"),
                p.getFirst("LaunchConfigurationName"),
                p.getFirst("LaunchTemplate.LaunchTemplateId"),
                p.getFirst("LaunchTemplate.LaunchTemplateName"),
                p.getFirst("LaunchTemplate.Version"),
                parseMixedInstancesPolicy(p),
                intParam(p, "MinSize", 0),
                intParam(p, "MaxSize", 0),
                intParam(p, "DesiredCapacity", intParam(p, "MinSize", 0)),
                intParam(p, "DefaultCooldown", 300),
                memberList(p, "AvailabilityZones"),
                commaList(p.getFirst("VPCZoneIdentifier")),
                memberList(p, "TargetGroupARNs"),
                memberList(p, "LoadBalancerNames"),
                p.getFirst("HealthCheckType"),
                intParam(p, "HealthCheckGracePeriod", 0),
                memberList(p, "TerminationPolicies"),
                parsedTags.tags(),
                parsedTags.propagateAtLaunch(),
                parseAsgOptionalFields(p));
        return ok(new XmlBuilder()
                .start("CreateAutoScalingGroupResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("CreateAutoScalingGroupResponse").build());
    }

    private Response handleUpdateAutoScalingGroup(MultivaluedMap<String, String> p, String region) {
        List<String> azs = memberList(p, "AvailabilityZones");
        List<String> tps = memberList(p, "TerminationPolicies");
        List<String> subnetIds = commaList(p.getFirst("VPCZoneIdentifier"));
        service.updateAutoScalingGroup(region,
                p.getFirst("AutoScalingGroupName"),
                p.getFirst("LaunchConfigurationName"),
                p.getFirst("LaunchTemplate.LaunchTemplateId"),
                p.getFirst("LaunchTemplate.LaunchTemplateName"),
                p.getFirst("LaunchTemplate.Version"),
                parseMixedInstancesPolicy(p),
                p.getFirst("MinSize") != null ? Integer.parseInt(p.getFirst("MinSize")) : null,
                p.getFirst("MaxSize") != null ? Integer.parseInt(p.getFirst("MaxSize")) : null,
                p.getFirst("DesiredCapacity") != null ? Integer.parseInt(p.getFirst("DesiredCapacity")) : null,
                p.getFirst("DefaultCooldown") != null ? Integer.parseInt(p.getFirst("DefaultCooldown")) : null,
                azs.isEmpty() ? null : azs,
                subnetIds.isEmpty() ? null : subnetIds,
                p.getFirst("HealthCheckType"),
                p.getFirst("HealthCheckGracePeriod") != null ? Integer.parseInt(p.getFirst("HealthCheckGracePeriod")) : null,
                tps.isEmpty() ? null : tps,
                parseAsgOptionalFields(p));
        return ok(new XmlBuilder()
                .start("UpdateAutoScalingGroupResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("UpdateAutoScalingGroupResponse").build());
    }

    private Response handleDeleteAutoScalingGroup(MultivaluedMap<String, String> p, String region) {
        service.deleteAutoScalingGroup(region,
                p.getFirst("AutoScalingGroupName"),
                "true".equalsIgnoreCase(p.getFirst("ForceDelete")));
        return ok(new XmlBuilder()
                .start("DeleteAutoScalingGroupResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DeleteAutoScalingGroupResponse").build());
    }

    private Response handleDescribeAutoScalingGroups(MultivaluedMap<String, String> p, String region) {
        List<AutoScalingGroup> groups = service.describeAutoScalingGroups(
                region, memberList(p, "AutoScalingGroupNames"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeAutoScalingGroupsResponse", NS)
                  .start("DescribeAutoScalingGroupsResult")
                    .start("AutoScalingGroups");
        for (AutoScalingGroup asg : groups) {
            xml.start("member");
            appendAsgXml(xml, asg);
            xml.end("member");
        }
        xml.end("AutoScalingGroups")
           .end("DescribeAutoScalingGroupsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeAutoScalingGroupsResponse");
        return ok(xml.build());
    }

    private void appendAsgXml(XmlBuilder xml, AutoScalingGroup asg) {
        xml.elem("AutoScalingGroupName", asg.getAutoScalingGroupName())
           .elem("AutoScalingGroupARN", asg.getAutoScalingGroupArn())
           .elem("MinSize", String.valueOf(asg.getMinSize()))
           .elem("MaxSize", String.valueOf(asg.getMaxSize()))
           .elem("DesiredCapacity", String.valueOf(asg.getDesiredCapacity()))
           .elem("DefaultCooldown", String.valueOf(asg.getDefaultCooldown()))
           .elem("HealthCheckType", asg.getHealthCheckType())
           .elem("HealthCheckGracePeriod", String.valueOf(asg.getHealthCheckGracePeriod()))
           .elem("CreatedTime", ISO_FMT.format(asg.getCreatedTime()));
        if (asg.getDesiredCapacityType() != null) {
            xml.elem("DesiredCapacityType", asg.getDesiredCapacityType());
        }

        if (asg.getLaunchConfigurationName() != null) {
            xml.elem("LaunchConfigurationName", asg.getLaunchConfigurationName());
        }
        if (asg.getLaunchTemplateId() != null || asg.getLaunchTemplateName() != null) {
            xml.start("LaunchTemplate");
            if (asg.getLaunchTemplateId() != null) {
                xml.elem("LaunchTemplateId", asg.getLaunchTemplateId());
            }
            if (asg.getLaunchTemplateName() != null) {
                xml.elem("LaunchTemplateName", asg.getLaunchTemplateName());
            }
            if (asg.getLaunchTemplateVersion() != null) {
                xml.elem("Version", asg.getLaunchTemplateVersion());
            }
            xml.end("LaunchTemplate");
        }
        appendMixedInstancesPolicyXml(xml, asg.getMixedInstancesPolicy());
        // lex00/floci#112's round-5 re-measure: see appendWarmPoolConfigurationXml's own doc
        // comment for why DescribeAutoScalingGroups itself needs this, not just DescribeWarmPool.
        appendWarmPoolConfigurationXml(xml, service.describeWarmPool(asg.getRegion(), asg.getAutoScalingGroupName()));

        xml.start("AvailabilityZones");
        for (String az : asg.getAvailabilityZones()) { xml.elem("member", az); }
        xml.end("AvailabilityZones");

        if (!asg.getSubnetIds().isEmpty()) {
            xml.elem("VPCZoneIdentifier", String.join(",", asg.getSubnetIds()));
        }

        xml.start("TargetGroupARNs");
        for (String arn : asg.getTargetGroupARNs()) { xml.elem("member", arn); }
        xml.end("TargetGroupARNs");

        xml.start("LoadBalancerNames");
        for (String lb : asg.getLoadBalancerNames()) { xml.elem("member", lb); }
        xml.end("LoadBalancerNames");

        xml.start("TerminationPolicies");
        for (String tp : asg.getTerminationPolicies()) { xml.elem("member", tp); }
        xml.end("TerminationPolicies");

        xml.start("SuspendedProcesses");
        for (String sp : asg.getSuspendedProcesses()) {
            xml.start("member").elem("ProcessName", sp).end("member");
        }
        xml.end("SuspendedProcesses");

        if (!asg.getEnabledMetrics().isEmpty()) {
            xml.start("EnabledMetrics");
            for (String metric : asg.getEnabledMetrics()) {
                xml.start("member")
                   .elem("Metric", metric)
                   .elem("Granularity", "1Minute")
                   .end("member");
            }
            xml.end("EnabledMetrics");
        }

        xml.start("Instances");
        for (AsgInstance inst : asg.getInstances()) {
            xml.start("member")
               .elem("InstanceId", inst.getInstanceId())
               .elem("AvailabilityZone", inst.getAvailabilityZone())
               .elem("LifecycleState", inst.getLifecycleState())
               .elem("HealthStatus", inst.getHealthStatus())
               .elem("ProtectedFromScaleIn", String.valueOf(inst.isProtectedFromScaleIn()));
            if (inst.getLaunchConfigurationName() != null) {
                xml.elem("LaunchConfigurationName", inst.getLaunchConfigurationName());
            }
            appendInstanceLaunchTemplateXml(xml, inst);
            if (inst.getInstanceType() != null) { xml.elem("InstanceType", inst.getInstanceType()); }
            xml.end("member");
        }
        xml.end("Instances");

        xml.start("Tags");
        for (Map.Entry<String, String> tag : asg.getTags().entrySet()) {
            xml.start("member")
               .elem("Key", tag.getKey())
               .elem("Value", tag.getValue())
               .elem("ResourceId", asg.getAutoScalingGroupName())
               .elem("ResourceType", "auto-scaling-group")
               .elem("PropagateAtLaunch", String.valueOf(asg.getTagPropagateAtLaunch().getOrDefault(tag.getKey(), false)))
               .end("member");
        }
        xml.end("Tags");

        if (asg.getStatus() != null) { xml.elem("Status", asg.getStatus()); }

        if (asg.getDefaultInstanceWarmup() != null) {
            xml.elem("DefaultInstanceWarmup", String.valueOf(asg.getDefaultInstanceWarmup()));
        }
        if (asg.getCapacityRebalance() != null) {
            xml.elem("CapacityRebalance", String.valueOf(asg.getCapacityRebalance()));
        }
        if (asg.getMaxInstanceLifetime() != null) {
            xml.elem("MaxInstanceLifetime", String.valueOf(asg.getMaxInstanceLifetime()));
        }
        if (asg.getServiceLinkedRoleArn() != null) {
            xml.elem("ServiceLinkedRoleARN", asg.getServiceLinkedRoleArn());
        }
        AutoScalingGroup.InstanceMaintenancePolicy maintenancePolicy = asg.getInstanceMaintenancePolicy();
        if (maintenancePolicy != null && !maintenancePolicy.isEmpty()) {
            xml.start("InstanceMaintenancePolicy");
            if (maintenancePolicy.getMinHealthyPercentage() != null) {
                xml.elem("MinHealthyPercentage", String.valueOf(maintenancePolicy.getMinHealthyPercentage()));
            }
            if (maintenancePolicy.getMaxHealthyPercentage() != null) {
                xml.elem("MaxHealthyPercentage", String.valueOf(maintenancePolicy.getMaxHealthyPercentage()));
            }
            xml.end("InstanceMaintenancePolicy");
        }
        AutoScalingGroup.AvailabilityZoneDistribution azDistribution = asg.getAvailabilityZoneDistribution();
        if (azDistribution != null && !azDistribution.isEmpty()) {
            xml.start("AvailabilityZoneDistribution")
               .elem("CapacityDistributionStrategy", azDistribution.getCapacityDistributionStrategy())
               .end("AvailabilityZoneDistribution");
        }
        AutoScalingGroup.CapacityReservationSpecification capacityReservationSpecification =
                asg.getCapacityReservationSpecification();
        if (capacityReservationSpecification != null && !capacityReservationSpecification.isEmpty()) {
            xml.start("CapacityReservationSpecification")
               .elem("CapacityReservationPreference", capacityReservationSpecification.getCapacityReservationPreference())
               .end("CapacityReservationSpecification");
        }
    }

    private Response handleSetDesiredCapacity(MultivaluedMap<String, String> p, String region) {
        service.setDesiredCapacity(region,
                p.getFirst("AutoScalingGroupName"),
                intParam(p, "DesiredCapacity", 0));
        return ok(new XmlBuilder()
                .start("SetDesiredCapacityResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("SetDesiredCapacityResponse").build());
    }

    private Response handleSuspendProcesses(MultivaluedMap<String, String> p, String region) {
        service.suspendProcesses(region,
                p.getFirst("AutoScalingGroupName"),
                memberList(p, "ScalingProcesses"));
        return ok(new XmlBuilder()
                .start("SuspendProcessesResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("SuspendProcessesResponse").build());
    }

    private Response handleResumeProcesses(MultivaluedMap<String, String> p, String region) {
        service.resumeProcesses(region,
                p.getFirst("AutoScalingGroupName"),
                memberList(p, "ScalingProcesses"));
        return ok(new XmlBuilder()
                .start("ResumeProcessesResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("ResumeProcessesResponse").build());
    }

    private Response handleStartInstanceRefresh(MultivaluedMap<String, String> p, String region) {
        InstanceRefresh refresh = service.startInstanceRefresh(region,
                p.getFirst("AutoScalingGroupName"), parseInstanceRefresh(p));
        return ok(new XmlBuilder()
                .start("StartInstanceRefreshResponse", NS)
                  .start("StartInstanceRefreshResult")
                    .elem("InstanceRefreshId", refresh.getInstanceRefreshId())
                  .end("StartInstanceRefreshResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("StartInstanceRefreshResponse").build());
    }

    private Response handleDescribeInstanceRefreshes(MultivaluedMap<String, String> p, String region) {
        AutoScalingService.InstanceRefreshPage page = service.describeInstanceRefreshes(region,
                p.getFirst("AutoScalingGroupName"),
                memberList(p, "InstanceRefreshIds"),
                nullableIntParam(p, "MaxRecords"),
                p.getFirst("NextToken"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeInstanceRefreshesResponse", NS)
                  .start("DescribeInstanceRefreshesResult")
                    .start("InstanceRefreshes");
        for (InstanceRefresh refresh : page.instanceRefreshes()) {
            appendInstanceRefreshXml(xml, refresh);
        }
        xml.end("InstanceRefreshes");
        if (page.nextToken() != null) {
            xml.elem("NextToken", page.nextToken());
        }
        xml.end("DescribeInstanceRefreshesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeInstanceRefreshesResponse");
        return ok(xml.build());
    }

    private void appendInstanceRefreshXml(XmlBuilder xml, InstanceRefresh refresh) {
        xml.start("member")
           .elem("InstanceRefreshId", refresh.getInstanceRefreshId())
           .elem("AutoScalingGroupName", refresh.getAutoScalingGroupName())
           .elem("Status", refresh.getStatus())
           .elem("StatusReason", refresh.getStatusReason())
           .elem("PercentageComplete", String.valueOf(refresh.getPercentageComplete()))
           .elem("InstancesToUpdate", String.valueOf(refresh.getInstancesToUpdate()))
           .elem("StartTime", ISO_FMT.format(refresh.getStartTime()));
        if (refresh.getEndTime() != null) {
            xml.elem("EndTime", ISO_FMT.format(refresh.getEndTime()));
        }
        if (refresh.getStrategy() != null) {
            xml.elem("Strategy", refresh.getStrategy());
        }
        appendDesiredConfigurationXml(xml, refresh);
        appendPreferencesXml(xml, refresh);
        xml.end("member");
    }

    private void appendDesiredConfigurationXml(XmlBuilder xml, InstanceRefresh refresh) {
        if (!refresh.hasDesiredConfiguration()) {
            return;
        }
        xml.start("DesiredConfiguration")
           .start("LaunchTemplate")
           .elem("LaunchTemplateId", refresh.getDesiredLaunchTemplateId())
           .elem("LaunchTemplateName", refresh.getDesiredLaunchTemplateName())
           .elem("Version", refresh.getDesiredLaunchTemplateVersion())
           .end("LaunchTemplate")
           .end("DesiredConfiguration");
    }

    private void appendPreferencesXml(XmlBuilder xml, InstanceRefresh refresh) {
        xml.start("Preferences")
           .elem("MinHealthyPercentage", intString(refresh.getMinHealthyPercentage()))
           .elem("MaxHealthyPercentage", intString(refresh.getMaxHealthyPercentage()))
           .elem("InstanceWarmup", intString(refresh.getInstanceWarmup()))
           .elem("SkipMatching", boolString(refresh.getSkipMatching()))
           .elem("AutoRollback", boolString(refresh.getAutoRollback()))
           .elem("ScaleInProtectedInstances", refresh.getScaleInProtectedInstances())
           .elem("StandbyInstances", refresh.getStandbyInstances())
           .elem("CheckpointDelay", intString(refresh.getCheckpointDelay()))
           .elem("BakeTime", intString(refresh.getBakeTime()));
        if (!refresh.getCheckpointPercentages().isEmpty()) {
            xml.start("CheckpointPercentages");
            for (Integer percentage : refresh.getCheckpointPercentages()) {
                xml.elem("member", String.valueOf(percentage));
            }
            xml.end("CheckpointPercentages");
        }
        xml.end("Preferences");
    }

    private Response handleCreateOrUpdateTags(MultivaluedMap<String, String> p, String region) {
        for (TagRequest tag : parseTagRequests(p)) {
            service.createOrUpdateTags(
                    region,
                    tag.resourceId(),
                    tag.resourceType(),
                    Map.of(tag.key(), tag.value()),
                    Map.of(tag.key(), tag.propagateAtLaunch()));
        }
        return ok(new XmlBuilder()
                .start("CreateOrUpdateTagsResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("CreateOrUpdateTagsResponse").build());
    }

    private Response handleDeleteTags(MultivaluedMap<String, String> p, String region) {
        for (TagRequest tag : parseTagRequests(p)) {
            service.deleteTags(region, tag.resourceId(), tag.resourceType(), List.of(tag.key()));
        }
        return ok(new XmlBuilder()
                .start("DeleteTagsResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DeleteTagsResponse").build());
    }

    private Response handleEnableMetricsCollection(MultivaluedMap<String, String> p, String region) {
        service.enableMetricsCollection(region, p.getFirst("AutoScalingGroupName"), memberList(p, "Metrics"));
        return ok(new XmlBuilder()
                .start("EnableMetricsCollectionResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("EnableMetricsCollectionResponse").build());
    }

    private Response handleDisableMetricsCollection(MultivaluedMap<String, String> p, String region) {
        service.disableMetricsCollection(region, p.getFirst("AutoScalingGroupName"), memberList(p, "Metrics"));
        return ok(new XmlBuilder()
                .start("DisableMetricsCollectionResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DisableMetricsCollectionResponse").build());
    }

    // ── Warm pools ──────────────────────────────────────────────────────────────
    // Wire shapes verified directly against botocore's own
    // autoscaling/2011-01-01/service-2.json (PutWarmPoolType, DescribeWarmPoolType,
    // DescribeWarmPoolAnswer, DeleteWarmPoolType, WarmPoolConfiguration,
    // InstanceReusePolicy) rather than docs prose, per lex00/floci#84 - see
    // AutoScalingService's warm-pool section for the full-replace-with-defaults
    // rationale behind PutWarmPool's parsing here.

    private Response handlePutWarmPool(MultivaluedMap<String, String> p, String region) {
        service.putWarmPool(region,
                p.getFirst("AutoScalingGroupName"),
                nullableIntParam(p, "MaxGroupPreparedCapacity"),
                nullableIntParam(p, "MinSize"),
                p.getFirst("PoolState"),
                nullableBoolParam(p, "InstanceReusePolicy.ReuseOnScaleIn"));
        return ok(new XmlBuilder()
                .start("PutWarmPoolResponse", NS)
                  .start("PutWarmPoolResult").end("PutWarmPoolResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("PutWarmPoolResponse").build());
    }

    private Response handleDescribeWarmPool(MultivaluedMap<String, String> p, String region) {
        WarmPoolConfiguration pool = service.describeWarmPool(region, p.getFirst("AutoScalingGroupName"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeWarmPoolResponse", NS)
                  .start("DescribeWarmPoolResult");
        appendWarmPoolConfigurationXml(xml, pool);
        xml.start("Instances").end("Instances")
           .end("DescribeWarmPoolResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeWarmPoolResponse");
        return ok(xml.build());
    }

    // lex00/floci#112's round-5 re-measure: botocore's own AutoScalingGroup shape documents
    // WarmPoolConfiguration as a member of DescribeAutoScalingGroups' own response, not only
    // reachable via the separate DescribeWarmPool action - terraform-aws-autoscaling's own
    // warm_pool example reads the group's warm pool state this way, so appendAsgXml below needs
    // this too, not just handleDescribeWarmPool. Shared so the two never drift.
    private static void appendWarmPoolConfigurationXml(XmlBuilder xml, WarmPoolConfiguration pool) {
        if (pool == null) {
            return;
        }
        xml.start("WarmPoolConfiguration");
        if (pool.getMaxGroupPreparedCapacity() != null) {
            xml.elem("MaxGroupPreparedCapacity", String.valueOf(pool.getMaxGroupPreparedCapacity()));
        }
        xml.elem("MinSize", String.valueOf(pool.getMinSize()))
           .elem("PoolState", pool.getPoolState())
           .start("InstanceReusePolicy")
             .elem("ReuseOnScaleIn", String.valueOf(pool.isReuseOnScaleIn()))
           .end("InstanceReusePolicy")
           .end("WarmPoolConfiguration");
    }

    private Response handleDeleteWarmPool(MultivaluedMap<String, String> p, String region) {
        service.deleteWarmPool(region,
                p.getFirst("AutoScalingGroupName"),
                "true".equalsIgnoreCase(p.getFirst("ForceDelete")));
        return ok(new XmlBuilder()
                .start("DeleteWarmPoolResponse", NS)
                  .start("DeleteWarmPoolResult").end("DeleteWarmPoolResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DeleteWarmPoolResponse").build());
    }

    // ── Instances ─────────────────────────────────────────────────────────────

    private Response handleDescribeAutoScalingInstances(MultivaluedMap<String, String> p, String region) {
        List<AsgInstance> instances = service.describeAutoScalingInstances(
                region, memberList(p, "InstanceIds"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeAutoScalingInstancesResponse", NS)
                  .start("DescribeAutoScalingInstancesResult")
                    .start("AutoScalingInstances");
        for (AsgInstance inst : instances) {
            xml.start("member")
               .elem("InstanceId", inst.getInstanceId())
               .elem("AvailabilityZone", inst.getAvailabilityZone())
               .elem("LifecycleState", inst.getLifecycleState())
               .elem("HealthStatus", inst.getHealthStatus())
               .elem("ProtectedFromScaleIn", String.valueOf(inst.isProtectedFromScaleIn()));
            if (inst.getLaunchConfigurationName() != null) {
                xml.elem("LaunchConfigurationName", inst.getLaunchConfigurationName());
            }
            appendInstanceLaunchTemplateXml(xml, inst);
            if (inst.getInstanceType() != null) { xml.elem("InstanceType", inst.getInstanceType()); }
            xml.end("member");
        }
        xml.end("AutoScalingInstances")
           .end("DescribeAutoScalingInstancesResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("DescribeAutoScalingInstancesResponse");
        return ok(xml.build());
    }

    private static void appendInstanceLaunchTemplateXml(XmlBuilder xml, AsgInstance inst) {
        if (inst.getLaunchTemplateId() == null && inst.getLaunchTemplateName() == null) {
            return;
        }
        xml.start("LaunchTemplate");
        if (inst.getLaunchTemplateId() != null) {
            xml.elem("LaunchTemplateId", inst.getLaunchTemplateId());
        }
        if (inst.getLaunchTemplateName() != null) {
            xml.elem("LaunchTemplateName", inst.getLaunchTemplateName());
        }
        if (inst.getLaunchTemplateVersion() != null) {
            xml.elem("Version", inst.getLaunchTemplateVersion());
        }
        xml.end("LaunchTemplate");
    }

    private static void appendMixedInstancesPolicyXml(XmlBuilder xml, MixedInstancesPolicy policy) {
        if (policy == null) {
            return;
        }
        xml.start("MixedInstancesPolicy");
        MixedInstancesPolicy.LaunchTemplate launchTemplate = policy.getLaunchTemplate();
        if (launchTemplate != null) {
            xml.start("LaunchTemplate");
            appendMixedLaunchTemplateSpecificationXml(xml, launchTemplate.getLaunchTemplateSpecification());
            if (!launchTemplate.getOverrides().isEmpty()) {
                xml.start("Overrides");
                for (MixedInstancesPolicy.LaunchTemplateOverride override : launchTemplate.getOverrides()) {
                    xml.start("member");
                    if (override.getInstanceType() != null) {
                        xml.elem("InstanceType", override.getInstanceType());
                    }
                    if (override.getWeightedCapacity() != null) {
                        xml.elem("WeightedCapacity", override.getWeightedCapacity());
                    }
                    appendMixedInstancesOverrideInstanceRequirementsXml(xml, override.getInstanceRequirements());
                    xml.end("member");
                }
                xml.end("Overrides");
            }
            xml.end("LaunchTemplate");
        }
        MixedInstancesPolicy.InstancesDistribution distribution = policy.getInstancesDistribution();
        if (distribution != null) {
            xml.start("InstancesDistribution");
            if (distribution.getOnDemandBaseCapacity() != null) {
                xml.elem("OnDemandBaseCapacity", String.valueOf(distribution.getOnDemandBaseCapacity()));
            }
            if (distribution.getOnDemandPercentageAboveBaseCapacity() != null) {
                xml.elem("OnDemandPercentageAboveBaseCapacity",
                        String.valueOf(distribution.getOnDemandPercentageAboveBaseCapacity()));
            }
            if (distribution.getSpotAllocationStrategy() != null) {
                xml.elem("SpotAllocationStrategy", distribution.getSpotAllocationStrategy());
            }
            xml.end("InstancesDistribution");
        }
        xml.end("MixedInstancesPolicy");
    }

    // lex00/floci#112's round-5 re-measure: see MixedInstancesPolicy.LaunchTemplateOverride's own
    // doc comment on instanceRequirements for why this exists.
    private static void appendMixedInstancesOverrideInstanceRequirementsXml(
            XmlBuilder xml, MixedInstancesPolicy.InstanceRequirements requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return;
        }
        xml.start("InstanceRequirements");
        if (requirements.getVCpuCount() != null) {
            xml.start("VCpuCount");
            if (requirements.getVCpuCount().getMin() != null) {
                xml.elem("Min", String.valueOf(requirements.getVCpuCount().getMin()));
            }
            if (requirements.getVCpuCount().getMax() != null) {
                xml.elem("Max", String.valueOf(requirements.getVCpuCount().getMax()));
            }
            xml.end("VCpuCount");
        }
        if (requirements.getMemoryMiB() != null) {
            xml.start("MemoryMiB");
            if (requirements.getMemoryMiB().getMin() != null) {
                xml.elem("Min", String.valueOf(requirements.getMemoryMiB().getMin()));
            }
            if (requirements.getMemoryMiB().getMax() != null) {
                xml.elem("Max", String.valueOf(requirements.getMemoryMiB().getMax()));
            }
            xml.end("MemoryMiB");
        }
        appendStringMemberList(xml, "CpuManufacturers", requirements.getCpuManufacturers());
        if (requirements.getMemoryGiBPerVCpu() != null) {
            xml.start("MemoryGiBPerVCpu");
            if (requirements.getMemoryGiBPerVCpu().getMin() != null) {
                xml.elem("Min", String.valueOf(requirements.getMemoryGiBPerVCpu().getMin()));
            }
            if (requirements.getMemoryGiBPerVCpu().getMax() != null) {
                xml.elem("Max", String.valueOf(requirements.getMemoryGiBPerVCpu().getMax()));
            }
            xml.end("MemoryGiBPerVCpu");
        }
        appendStringMemberList(xml, "ExcludedInstanceTypes", requirements.getExcludedInstanceTypes());
        appendStringMemberList(xml, "InstanceGenerations", requirements.getInstanceGenerations());
        appendStringMemberList(xml, "LocalStorageTypes", requirements.getLocalStorageTypes());
        if (requirements.getMaxSpotPriceAsPercentageOfOptimalOnDemandPrice() != null) {
            xml.elem("MaxSpotPriceAsPercentageOfOptimalOnDemandPrice",
                    String.valueOf(requirements.getMaxSpotPriceAsPercentageOfOptimalOnDemandPrice()));
        }
        if (requirements.getBareMetal() != null) {
            xml.elem("BareMetal", requirements.getBareMetal());
        }
        if (requirements.getBurstablePerformance() != null) {
            xml.elem("BurstablePerformance", requirements.getBurstablePerformance());
        }
        appendStringMemberList(xml, "AllowedInstanceTypes", requirements.getAllowedInstanceTypes());
        xml.end("InstanceRequirements");
    }

    private static void appendStringMemberList(XmlBuilder xml, String elementName, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        xml.start(elementName);
        for (String value : values) {
            xml.elem("member", value);
        }
        xml.end(elementName);
    }

    private static void appendMixedLaunchTemplateSpecificationXml(
            XmlBuilder xml, MixedInstancesPolicy.LaunchTemplateSpecification specification) {
        if (specification == null) {
            return;
        }
        xml.start("LaunchTemplateSpecification");
        if (specification.getLaunchTemplateId() != null) {
            xml.elem("LaunchTemplateId", specification.getLaunchTemplateId());
        }
        if (specification.getLaunchTemplateName() != null) {
            xml.elem("LaunchTemplateName", specification.getLaunchTemplateName());
        }
        if (specification.getVersion() != null) {
            xml.elem("Version", specification.getVersion());
        }
        xml.end("LaunchTemplateSpecification");
    }

    private Response handleAttachInstances(MultivaluedMap<String, String> p, String region) {
        service.attachInstances(region, p.getFirst("AutoScalingGroupName"), memberList(p, "InstanceIds"));
        return ok(new XmlBuilder()
                .start("AttachInstancesResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("AttachInstancesResponse").build());
    }

    private Response handleDetachInstances(MultivaluedMap<String, String> p, String region) {
        service.detachInstances(region, p.getFirst("AutoScalingGroupName"),
                memberList(p, "InstanceIds"),
                "true".equalsIgnoreCase(p.getFirst("ShouldDecrementDesiredCapacity")));
        return ok(new XmlBuilder()
                .start("DetachInstancesResponse", NS)
                  .start("DetachInstancesResult")
                    .start("Activities").end("Activities")
                  .end("DetachInstancesResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DetachInstancesResponse").build());
    }

    private Response handleTerminateInstance(MultivaluedMap<String, String> p, String region) {
        service.terminateInstanceInAutoScalingGroup(region,
                p.getFirst("InstanceId"),
                "true".equalsIgnoreCase(p.getFirst("ShouldDecrementDesiredCapacity")));
        return ok(new XmlBuilder()
                .start("TerminateInstanceInAutoScalingGroupResponse", NS)
                  .start("TerminateInstanceInAutoScalingGroupResult")
                  .end("TerminateInstanceInAutoScalingGroupResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("TerminateInstanceInAutoScalingGroupResponse").build());
    }

    // ── Load balancer attachment ───────────────────────────────────────────────

    private Response handleAttachLoadBalancerTargetGroups(MultivaluedMap<String, String> p, String region) {
        service.attachLoadBalancerTargetGroups(region,
                p.getFirst("AutoScalingGroupName"), memberList(p, "TargetGroupARNs"));
        return ok(new XmlBuilder()
                .start("AttachLoadBalancerTargetGroupsResponse", NS)
                  .start("AttachLoadBalancerTargetGroupsResult").end("AttachLoadBalancerTargetGroupsResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("AttachLoadBalancerTargetGroupsResponse").build());
    }

    private Response handleDetachLoadBalancerTargetGroups(MultivaluedMap<String, String> p, String region) {
        service.detachLoadBalancerTargetGroups(region,
                p.getFirst("AutoScalingGroupName"), memberList(p, "TargetGroupARNs"));
        return ok(new XmlBuilder()
                .start("DetachLoadBalancerTargetGroupsResponse", NS)
                  .start("DetachLoadBalancerTargetGroupsResult").end("DetachLoadBalancerTargetGroupsResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DetachLoadBalancerTargetGroupsResponse").build());
    }

    private Response handleDescribeLoadBalancerTargetGroups(MultivaluedMap<String, String> p, String region) {
        List<String> tgArns = service.describeLoadBalancerTargetGroups(
                region, p.getFirst("AutoScalingGroupName"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeLoadBalancerTargetGroupsResponse", NS)
                  .start("DescribeLoadBalancerTargetGroupsResult")
                    .start("LoadBalancerTargetGroups");
        for (String arn : tgArns) {
            xml.start("member")
               .elem("LoadBalancerTargetGroupARN", arn)
               .elem("State", "InService")
               .end("member");
        }
        xml.end("LoadBalancerTargetGroups")
           .end("DescribeLoadBalancerTargetGroupsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeLoadBalancerTargetGroupsResponse");
        return ok(xml.build());
    }

    private Response handleAttachLoadBalancers(MultivaluedMap<String, String> p, String region) {
        service.attachLoadBalancers(region,
                p.getFirst("AutoScalingGroupName"), memberList(p, "LoadBalancerNames"));
        return ok(new XmlBuilder()
                .start("AttachLoadBalancersResponse", NS)
                  .start("AttachLoadBalancersResult").end("AttachLoadBalancersResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("AttachLoadBalancersResponse").build());
    }

    private Response handleDetachLoadBalancers(MultivaluedMap<String, String> p, String region) {
        service.detachLoadBalancers(region,
                p.getFirst("AutoScalingGroupName"), memberList(p, "LoadBalancerNames"));
        return ok(new XmlBuilder()
                .start("DetachLoadBalancersResponse", NS)
                  .start("DetachLoadBalancersResult").end("DetachLoadBalancersResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DetachLoadBalancersResponse").build());
    }

    private Response handleDescribeLoadBalancers(MultivaluedMap<String, String> p, String region) {
        String name = p.getFirst("AutoScalingGroupName");
        List<String> lbNames = service.describeAutoScalingGroups(region, List.of(name))
                .stream().findFirst().map(AutoScalingGroup::getLoadBalancerNames).orElse(List.of());
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeLoadBalancersResponse", NS)
                  .start("DescribeLoadBalancersResult")
                    .start("LoadBalancers");
        for (String lb : lbNames) {
            xml.start("member")
               .elem("LoadBalancerName", lb)
               .elem("State", "InService")
               .end("member");
        }
        xml.end("LoadBalancers")
           .end("DescribeLoadBalancersResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeLoadBalancersResponse");
        return ok(xml.build());
    }

    // ── Traffic sources ─────────────────────────────────────────────────────────
    // The modern, unified elbv2/vpc-lattice ASG-to-load-balancer wiring API -
    // aws_autoscaling_traffic_source_attachment uses this instead of the older
    // AttachLoadBalancerTargetGroups above. AWS's own DescribeTrafficSources doc
    // page example shows a bare, unwrapped <TrafficSources> element per item, which
    // reads as a flattened list - but botocore's actual service-2.json models the
    // TrafficSources list WITHOUT "flattened": true and with member locationName
    // "member" like every other list in this API, so the wire shape is the normal
    // <TrafficSources><member>...</member></TrafficSources>. The doc page's example
    // was simply wrong; trusting it verbatim left the AWS provider's own
    // post-create waiter polling DescribeTrafficSources and never finding the
    // resource it had just attached ("couldn't find resource (21 retries)"),
    // caught crossing terraform-aws-modules/terraform-aws-autoscaling's "complete"
    // example - the actual wire format, not the docs, is the source of truth here.

    private Response handleAttachTrafficSources(MultivaluedMap<String, String> p, String region) {
        service.attachTrafficSources(region, p.getFirst("AutoScalingGroupName"), parseTrafficSources(p));
        return ok(new XmlBuilder()
                .start("AttachTrafficSourcesResponse", NS)
                  .start("AttachTrafficSourcesResult").end("AttachTrafficSourcesResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("AttachTrafficSourcesResponse").build());
    }

    private Response handleDetachTrafficSources(MultivaluedMap<String, String> p, String region) {
        service.detachTrafficSources(region, p.getFirst("AutoScalingGroupName"), parseTrafficSources(p));
        return ok(new XmlBuilder()
                .start("DetachTrafficSourcesResponse", NS)
                  .start("DetachTrafficSourcesResult").end("DetachTrafficSourcesResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DetachTrafficSourcesResponse").build());
    }

    private Response handleDescribeTrafficSources(MultivaluedMap<String, String> p, String region) {
        Map<String, String> sources = service.describeTrafficSources(region,
                p.getFirst("AutoScalingGroupName"), p.getFirst("TrafficSourceType"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTrafficSourcesResponse", NS)
                  .start("DescribeTrafficSourcesResult")
                    .start("TrafficSources");
        sources.forEach((identifier, type) -> xml.start("member")
               .elem("Identifier", identifier)
               .elem("State", "InService")
               .elem("Type", type)
               .end("member"));
        xml.end("TrafficSources")
           .end("DescribeTrafficSourcesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeTrafficSourcesResponse");
        return ok(xml.build());
    }

    private List<AutoScalingService.TrafficSourceIdentifier> parseTrafficSources(MultivaluedMap<String, String> p) {
        List<AutoScalingService.TrafficSourceIdentifier> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String identifier = p.getFirst("TrafficSources.member." + i + ".Identifier");
            if (identifier == null) { break; }
            String type = p.getFirst("TrafficSources.member." + i + ".Type");
            result.add(new AutoScalingService.TrafficSourceIdentifier(identifier, type));
        }
        return result;
    }

    // ── Lifecycle hooks ────────────────────────────────────────────────────────

    private Response handlePutLifecycleHook(MultivaluedMap<String, String> p, String region) {
        Integer timeout = p.getFirst("HeartbeatTimeout") != null
                ? Integer.parseInt(p.getFirst("HeartbeatTimeout")) : null;
        service.putLifecycleHook(region,
                p.getFirst("AutoScalingGroupName"),
                p.getFirst("LifecycleHookName"),
                p.getFirst("LifecycleTransition"),
                p.getFirst("NotificationTargetARN"),
                p.getFirst("RoleARN"),
                p.getFirst("NotificationMetadata"),
                timeout,
                p.getFirst("DefaultResult"));
        return ok(new XmlBuilder()
                .start("PutLifecycleHookResponse", NS)
                  .start("PutLifecycleHookResult").end("PutLifecycleHookResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("PutLifecycleHookResponse").build());
    }

    private Response handleDeleteLifecycleHook(MultivaluedMap<String, String> p, String region) {
        service.deleteLifecycleHook(region,
                p.getFirst("AutoScalingGroupName"), p.getFirst("LifecycleHookName"));
        return ok(new XmlBuilder()
                .start("DeleteLifecycleHookResponse", NS)
                  .start("DeleteLifecycleHookResult").end("DeleteLifecycleHookResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DeleteLifecycleHookResponse").build());
    }

    private Response handleDescribeLifecycleHooks(MultivaluedMap<String, String> p, String region) {
        List<LifecycleHook> hooks = service.describeLifecycleHooks(region,
                p.getFirst("AutoScalingGroupName"), memberList(p, "LifecycleHookNames"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeLifecycleHooksResponse", NS)
                  .start("DescribeLifecycleHooksResult")
                    .start("LifecycleHooks");
        for (LifecycleHook hook : hooks) {
            xml.start("member")
               .elem("LifecycleHookName", hook.getLifecycleHookName())
               .elem("AutoScalingGroupName", hook.getAutoScalingGroupName())
               .elem("LifecycleTransition", hook.getLifecycleTransition())
               .elem("HeartbeatTimeout", String.valueOf(hook.getHeartbeatTimeout()))
               .elem("GlobalTimeout", String.valueOf(hook.getGlobalTimeout()))
               .elem("DefaultResult", hook.getDefaultResult());
            if (hook.getNotificationTargetArn() != null) {
                xml.elem("NotificationTargetARN", hook.getNotificationTargetArn());
            }
            if (hook.getRoleArn() != null) { xml.elem("RoleARN", hook.getRoleArn()); }
            xml.end("member");
        }
        xml.end("LifecycleHooks")
           .end("DescribeLifecycleHooksResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeLifecycleHooksResponse");
        return ok(xml.build());
    }

    private Response handleCompleteLifecycleAction(MultivaluedMap<String, String> p, String region) {
        service.completeLifecycleAction(region,
                p.getFirst("AutoScalingGroupName"), p.getFirst("LifecycleHookName"),
                p.getFirst("InstanceId"), p.getFirst("LifecycleActionResult"),
                p.getFirst("LifecycleActionToken"));
        return ok(new XmlBuilder()
                .start("CompleteLifecycleActionResponse", NS)
                  .start("CompleteLifecycleActionResult").end("CompleteLifecycleActionResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("CompleteLifecycleActionResponse").build());
    }

    private Response handleRecordLifecycleActionHeartbeat() {
        return ok(new XmlBuilder()
                .start("RecordLifecycleActionHeartbeatResponse", NS)
                  .start("RecordLifecycleActionHeartbeatResult").end("RecordLifecycleActionHeartbeatResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("RecordLifecycleActionHeartbeatResponse").build());
    }

    // ── Scaling policies ───────────────────────────────────────────────────────

    private Response handlePutScalingPolicy(MultivaluedMap<String, String> p, String region) {
        ScalingPolicy policy = service.putScalingPolicy(region,
                p.getFirst("AutoScalingGroupName"),
                p.getFirst("PolicyName"),
                p.getFirst("PolicyType"),
                p.getFirst("AdjustmentType"),
                intParam(p, "ScalingAdjustment", 0),
                nullableIntParam(p, "Cooldown"),
                nullableBoolParam(p, "Enabled"),
                nullableIntParam(p, "EstimatedInstanceWarmup"),
                parseTargetTrackingConfiguration(p),
                parseStepAdjustments(p),
                parsePredictiveScalingConfiguration(p));
        return ok(new XmlBuilder()
                .start("PutScalingPolicyResponse", NS)
                  .start("PutScalingPolicyResult")
                    .elem("PolicyARN", policy.getPolicyArn())
                  .end("PutScalingPolicyResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("PutScalingPolicyResponse").build());
    }

    private Response handleDeletePolicy(MultivaluedMap<String, String> p, String region) {
        service.deletePolicy(region,
                p.getFirst("AutoScalingGroupName"), p.getFirst("PolicyName"));
        return ok(new XmlBuilder()
                .start("DeletePolicyResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DeletePolicyResponse").build());
    }

    private Response handleDescribePolicies(MultivaluedMap<String, String> p, String region) {
        List<ScalingPolicy> policies = service.describePolicies(
                region, p.getFirst("AutoScalingGroupName"), memberList(p, "PolicyNames"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribePoliciesResponse", NS)
                  .start("DescribePoliciesResult")
                    .start("ScalingPolicies");
        for (ScalingPolicy policy : policies) {
            xml.start("member")
               .elem("PolicyName", policy.getPolicyName())
               .elem("PolicyARN", policy.getPolicyArn())
               .elem("AutoScalingGroupName", policy.getAutoScalingGroupName())
               .elem("PolicyType", policy.getPolicyType() != null ? policy.getPolicyType() : "SimpleScaling")
               .elem("ScalingAdjustment", String.valueOf(policy.getScalingAdjustment()))
               .elem("Enabled", String.valueOf(policy.getEnabled() != null ? policy.getEnabled() : Boolean.TRUE));
            if (policy.getCooldown() != null) { xml.elem("Cooldown", String.valueOf(policy.getCooldown())); }
            if (policy.getAdjustmentType() != null) { xml.elem("AdjustmentType", policy.getAdjustmentType()); }
            if (policy.getEstimatedInstanceWarmup() != null) {
                xml.elem("EstimatedInstanceWarmup", String.valueOf(policy.getEstimatedInstanceWarmup()));
            }
            appendTargetTrackingConfigurationXml(xml, policy.getTargetTrackingConfiguration());
            appendStepAdjustmentsXml(xml, policy.getStepAdjustments());
            appendPredictiveScalingConfigurationXml(xml, policy.getPredictiveScalingConfiguration());
            xml.end("member");
        }
        xml.end("ScalingPolicies")
           .end("DescribePoliciesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribePoliciesResponse");
        return ok(xml.build());
    }

    // ── Scheduled actions ───────────────────────────────────────────────────────

    private Response handlePutScheduledUpdateGroupAction(MultivaluedMap<String, String> p, String region) {
        ScheduledAction action = service.putScheduledUpdateGroupAction(region,
                p.getFirst("AutoScalingGroupName"),
                p.getFirst("ScheduledActionName"),
                parseInstant(p.getFirst("StartTime")),
                parseInstant(p.getFirst("EndTime")),
                p.getFirst("Recurrence"),
                p.getFirst("TimeZone"),
                nullableIntParam(p, "MinSize"),
                nullableIntParam(p, "MaxSize"),
                nullableIntParam(p, "DesiredCapacity"));
        return ok(new XmlBuilder()
                .start("PutScheduledUpdateGroupActionResponse", NS)
                  .start("PutScheduledUpdateGroupActionResult").end("PutScheduledUpdateGroupActionResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("PutScheduledUpdateGroupActionResponse").build());
    }

    private Response handleDeleteScheduledAction(MultivaluedMap<String, String> p, String region) {
        service.deleteScheduledAction(region,
                p.getFirst("AutoScalingGroupName"), p.getFirst("ScheduledActionName"));
        return ok(new XmlBuilder()
                .start("DeleteScheduledActionResponse", NS)
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DeleteScheduledActionResponse").build());
    }

    private Response handleDescribeScheduledActions(MultivaluedMap<String, String> p, String region) {
        List<ScheduledAction> actions = service.describeScheduledActions(
                region, p.getFirst("AutoScalingGroupName"), memberList(p, "ScheduledActionNames"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeScheduledActionsResponse", NS)
                  .start("DescribeScheduledActionsResult")
                    .start("ScheduledUpdateGroupActions");
        for (ScheduledAction action : actions) {
            xml.start("member")
               .elem("ScheduledActionName", action.getScheduledActionName())
               .elem("ScheduledActionARN", action.getScheduledActionArn())
               .elem("AutoScalingGroupName", action.getAutoScalingGroupName());
            if (action.getStartTime() != null) { xml.elem("StartTime", ISO_FMT.format(action.getStartTime())); }
            if (action.getEndTime() != null) { xml.elem("EndTime", ISO_FMT.format(action.getEndTime())); }
            if (action.getRecurrence() != null) { xml.elem("Recurrence", action.getRecurrence()); }
            if (action.getTimeZone() != null) { xml.elem("TimeZone", action.getTimeZone()); }
            if (action.getMinSize() != null) { xml.elem("MinSize", String.valueOf(action.getMinSize())); }
            if (action.getMaxSize() != null) { xml.elem("MaxSize", String.valueOf(action.getMaxSize())); }
            if (action.getDesiredCapacity() != null) {
                xml.elem("DesiredCapacity", String.valueOf(action.getDesiredCapacity()));
            }
            xml.end("member");
        }
        xml.end("ScheduledUpdateGroupActions")
           .end("DescribeScheduledActionsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeScheduledActionsResponse");
        return ok(xml.build());
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) { return null; }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static ScalingPolicy.TargetTrackingConfiguration parseTargetTrackingConfiguration(MultivaluedMap<String, String> p) {
        String predefinedMetricType = p.getFirst("TargetTrackingConfiguration.PredefinedMetricSpecification.PredefinedMetricType");
        ScalingPolicy.CustomizedMetricSpecification customizedMetric = parseCustomizedMetricSpecification(p);
        Double targetValue = nullableDoubleParam(p, "TargetTrackingConfiguration.TargetValue");
        Boolean disableScaleIn = p.getFirst("TargetTrackingConfiguration.DisableScaleIn") != null
                ? Boolean.parseBoolean(p.getFirst("TargetTrackingConfiguration.DisableScaleIn")) : null;
        if (predefinedMetricType == null && customizedMetric == null && targetValue == null && disableScaleIn == null) {
            return null;
        }
        ScalingPolicy.TargetTrackingConfiguration configuration = new ScalingPolicy.TargetTrackingConfiguration();
        if (predefinedMetricType != null) {
            ScalingPolicy.PredefinedMetricSpecification specification =
                    new ScalingPolicy.PredefinedMetricSpecification();
            specification.setPredefinedMetricType(predefinedMetricType);
            specification.setResourceLabel(p.getFirst(
                    "TargetTrackingConfiguration.PredefinedMetricSpecification.ResourceLabel"));
            configuration.setPredefinedMetricSpecification(specification);
        }
        configuration.setCustomizedMetricSpecification(customizedMetric);
        configuration.setTargetValue(targetValue);
        configuration.setDisableScaleIn(disableScaleIn);
        return configuration;
    }

    // lex00/floci#119's disclaimed sub-gap - see
    // ScalingPolicy.TargetTrackingConfiguration.CustomizedMetricSpecification's own doc comment.
    private static ScalingPolicy.CustomizedMetricSpecification parseCustomizedMetricSpecification(
            MultivaluedMap<String, String> p) {
        String prefix = "TargetTrackingConfiguration.CustomizedMetricSpecification";
        String metricName = p.getFirst(prefix + ".MetricName");
        String namespace = p.getFirst(prefix + ".Namespace");
        String statistic = p.getFirst(prefix + ".Statistic");
        String unit = p.getFirst(prefix + ".Unit");
        String period = p.getFirst(prefix + ".Period");
        List<ScalingPolicy.MetricDimension> dimensions = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = p.getFirst(prefix + ".Dimensions.member." + i + ".Name");
            if (name == null) break;
            dimensions.add(new ScalingPolicy.MetricDimension(name, p.getFirst(prefix + ".Dimensions.member." + i + ".Value")));
        }
        List<ScalingPolicy.TargetTrackingMetricDataQuery> metrics = parseTargetTrackingMetricDataQueries(p, prefix + ".Metrics");
        if (metricName == null && namespace == null && statistic == null && unit == null
                && period == null && dimensions.isEmpty() && metrics.isEmpty()) {
            return null;
        }
        ScalingPolicy.CustomizedMetricSpecification spec = new ScalingPolicy.CustomizedMetricSpecification();
        spec.setMetricName(metricName);
        spec.setNamespace(namespace);
        spec.setDimensions(dimensions);
        spec.setStatistic(statistic);
        spec.setUnit(unit);
        spec.setPeriod(period != null && !period.isBlank() ? Integer.parseInt(period) : null);
        spec.setMetrics(metrics);
        return spec;
    }

    // lex00/floci#122: the metric-math form of CustomizedMetricSpecification - see
    // ScalingPolicy.CustomizedMetricSpecification's own doc comment on `metrics` for why this
    // exists. Field names/nesting taken from botocore's autoscaling/2011-01-01/service-2.json
    // TargetTrackingMetricDataQuery/TargetTrackingMetricStat/Metric shapes.
    private static List<ScalingPolicy.TargetTrackingMetricDataQuery> parseTargetTrackingMetricDataQueries(
            MultivaluedMap<String, String> p, String basePrefix) {
        List<ScalingPolicy.TargetTrackingMetricDataQuery> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String prefix = basePrefix + ".member." + i;
            String id = p.getFirst(prefix + ".Id");
            String expression = p.getFirst(prefix + ".Expression");
            String label = p.getFirst(prefix + ".Label");
            String period = p.getFirst(prefix + ".Period");
            String returnData = p.getFirst(prefix + ".ReturnData");
            String statPrefix = prefix + ".MetricStat";
            String stat = p.getFirst(statPrefix + ".Stat");
            String metricPrefix = statPrefix + ".Metric";
            String metricNamespace = p.getFirst(metricPrefix + ".Namespace");
            String metricName = p.getFirst(metricPrefix + ".MetricName");
            String metricUnit = p.getFirst(statPrefix + ".Unit");
            String metricPeriod = p.getFirst(statPrefix + ".Period");
            List<ScalingPolicy.MetricDimension> dimensions = new ArrayList<>();
            for (int d = 1; ; d++) {
                String name = p.getFirst(metricPrefix + ".Dimensions.member." + d + ".Name");
                if (name == null) break;
                dimensions.add(new ScalingPolicy.MetricDimension(
                        name, p.getFirst(metricPrefix + ".Dimensions.member." + d + ".Value")));
            }
            if (id == null && expression == null && label == null && period == null
                    && returnData == null && stat == null && metricNamespace == null
                    && metricName == null && metricUnit == null && metricPeriod == null
                    && dimensions.isEmpty()) {
                break;
            }
            ScalingPolicy.TargetTrackingMetricDataQuery query = new ScalingPolicy.TargetTrackingMetricDataQuery();
            query.setId(id);
            query.setExpression(expression);
            query.setLabel(label);
            query.setPeriod(period != null && !period.isBlank() ? Integer.parseInt(period) : null);
            query.setReturnData(returnData != null ? Boolean.parseBoolean(returnData) : null);
            if (stat != null || metricNamespace != null || metricName != null || metricUnit != null
                    || metricPeriod != null || !dimensions.isEmpty()) {
                ScalingPolicy.TargetTrackingMetricStat metricStat = new ScalingPolicy.TargetTrackingMetricStat();
                metricStat.setStat(stat);
                metricStat.setUnit(metricUnit);
                metricStat.setPeriod(metricPeriod != null && !metricPeriod.isBlank() ? Integer.parseInt(metricPeriod) : null);
                if (metricNamespace != null || metricName != null || !dimensions.isEmpty()) {
                    ScalingPolicy.Metric metric = new ScalingPolicy.Metric();
                    metric.setNamespace(metricNamespace);
                    metric.setMetricName(metricName);
                    metric.setDimensions(dimensions);
                    metricStat.setMetric(metric);
                }
                query.setMetricStat(metricStat);
            }
            result.add(query);
        }
        return result;
    }

    // parseStepAdjustments / appendStepAdjustmentsXml and
    // parsePredictiveScalingConfiguration / appendPredictiveScalingConfigurationXml:
    // see ScalingPolicy's own doc comment on stepAdjustments and
    // predictiveScalingConfiguration for why these exist. Field names and
    // shapes are taken from aws-sdk-go-v2's service/autoscaling/types
    // (StepAdjustment, PredictiveScalingConfiguration,
    // PredictiveScalingMetricSpecification), not guessed.
    private static List<ScalingPolicy.StepAdjustment> parseStepAdjustments(MultivaluedMap<String, String> p) {
        List<ScalingPolicy.StepAdjustment> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String prefix = "StepAdjustments.member." + i + ".";
            String scalingAdjustment = p.getFirst(prefix + "ScalingAdjustment");
            if (scalingAdjustment == null) { break; }
            ScalingPolicy.StepAdjustment step = new ScalingPolicy.StepAdjustment();
            step.setScalingAdjustment(Integer.parseInt(scalingAdjustment));
            step.setMetricIntervalLowerBound(nullableDoubleParam(p, prefix + "MetricIntervalLowerBound"));
            step.setMetricIntervalUpperBound(nullableDoubleParam(p, prefix + "MetricIntervalUpperBound"));
            result.add(step);
        }
        return result;
    }

    private static void appendStepAdjustmentsXml(XmlBuilder xml, List<ScalingPolicy.StepAdjustment> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        xml.start("StepAdjustments");
        for (ScalingPolicy.StepAdjustment step : steps) {
            xml.start("member").elem("ScalingAdjustment", String.valueOf(step.getScalingAdjustment()));
            if (step.getMetricIntervalLowerBound() != null) {
                xml.elem("MetricIntervalLowerBound", String.valueOf(step.getMetricIntervalLowerBound()));
            }
            if (step.getMetricIntervalUpperBound() != null) {
                xml.elem("MetricIntervalUpperBound", String.valueOf(step.getMetricIntervalUpperBound()));
            }
            xml.end("member");
        }
        xml.end("StepAdjustments");
    }

    private static ScalingPolicy.PredefinedMetricSpecification parsePredefinedMetricSpecification(
            MultivaluedMap<String, String> p, String prefix) {
        String type = p.getFirst(prefix + ".PredefinedMetricType");
        if (type == null) { return null; }
        ScalingPolicy.PredefinedMetricSpecification spec = new ScalingPolicy.PredefinedMetricSpecification();
        spec.setPredefinedMetricType(type);
        spec.setResourceLabel(p.getFirst(prefix + ".ResourceLabel"));
        return spec;
    }

    private static void appendPredefinedMetricSpecificationXml(
            XmlBuilder xml, String elementName, ScalingPolicy.PredefinedMetricSpecification spec) {
        if (spec == null) { return; }
        xml.start(elementName);
        if (spec.getPredefinedMetricType() != null) { xml.elem("PredefinedMetricType", spec.getPredefinedMetricType()); }
        if (spec.getResourceLabel() != null) { xml.elem("ResourceLabel", spec.getResourceLabel()); }
        xml.end(elementName);
    }

    private static ScalingPolicy.PredictiveScalingConfiguration parsePredictiveScalingConfiguration(
            MultivaluedMap<String, String> p) {
        String base = "PredictiveScalingConfiguration.";
        List<ScalingPolicy.PredictiveScalingMetricSpecification> specs = new ArrayList<>();
        for (int i = 1; ; i++) {
            String specPrefix = base + "MetricSpecifications.member." + i + ".";
            Double targetValue = nullableDoubleParam(p, specPrefix + "TargetValue");
            ScalingPolicy.PredefinedMetricSpecification scaling =
                    parsePredefinedMetricSpecification(p, specPrefix + "PredefinedScalingMetricSpecification");
            ScalingPolicy.PredefinedMetricSpecification load =
                    parsePredefinedMetricSpecification(p, specPrefix + "PredefinedLoadMetricSpecification");
            ScalingPolicy.PredefinedMetricSpecification pair =
                    parsePredefinedMetricSpecification(p, specPrefix + "PredefinedMetricPairSpecification");
            if (targetValue == null && scaling == null && load == null && pair == null) { break; }
            ScalingPolicy.PredictiveScalingMetricSpecification spec = new ScalingPolicy.PredictiveScalingMetricSpecification();
            spec.setTargetValue(targetValue);
            spec.setPredefinedScalingMetricSpecification(scaling);
            spec.setPredefinedLoadMetricSpecification(load);
            spec.setPredefinedMetricPairSpecification(pair);
            specs.add(spec);
        }
        if (specs.isEmpty()) {
            return null;
        }
        ScalingPolicy.PredictiveScalingConfiguration configuration = new ScalingPolicy.PredictiveScalingConfiguration();
        configuration.setMetricSpecifications(specs);
        configuration.setMode(p.getFirst(base + "Mode"));
        configuration.setSchedulingBufferTime(nullableIntParamStatic(p, base + "SchedulingBufferTime"));
        configuration.setMaxCapacityBreachBehavior(p.getFirst(base + "MaxCapacityBreachBehavior"));
        configuration.setMaxCapacityBuffer(nullableIntParamStatic(p, base + "MaxCapacityBuffer"));
        return configuration;
    }

    private static Integer nullableIntParamStatic(MultivaluedMap<String, String> p, String key) {
        String v = p.getFirst(key);
        return v == null || v.isBlank() ? null : Integer.parseInt(v);
    }

    private static void appendPredictiveScalingConfigurationXml(
            XmlBuilder xml, ScalingPolicy.PredictiveScalingConfiguration configuration) {
        if (configuration == null) {
            return;
        }
        xml.start("PredictiveScalingConfiguration");
        xml.start("MetricSpecifications");
        for (ScalingPolicy.PredictiveScalingMetricSpecification spec : configuration.getMetricSpecifications()) {
            xml.start("member");
            if (spec.getTargetValue() != null) { xml.elem("TargetValue", String.valueOf(spec.getTargetValue())); }
            appendPredefinedMetricSpecificationXml(xml, "PredefinedScalingMetricSpecification",
                    spec.getPredefinedScalingMetricSpecification());
            appendPredefinedMetricSpecificationXml(xml, "PredefinedLoadMetricSpecification",
                    spec.getPredefinedLoadMetricSpecification());
            appendPredefinedMetricSpecificationXml(xml, "PredefinedMetricPairSpecification",
                    spec.getPredefinedMetricPairSpecification());
            xml.end("member");
        }
        xml.end("MetricSpecifications");
        if (configuration.getMode() != null) { xml.elem("Mode", configuration.getMode()); }
        if (configuration.getSchedulingBufferTime() != null) {
            xml.elem("SchedulingBufferTime", String.valueOf(configuration.getSchedulingBufferTime()));
        }
        if (configuration.getMaxCapacityBreachBehavior() != null) {
            xml.elem("MaxCapacityBreachBehavior", configuration.getMaxCapacityBreachBehavior());
        }
        if (configuration.getMaxCapacityBuffer() != null) {
            xml.elem("MaxCapacityBuffer", String.valueOf(configuration.getMaxCapacityBuffer()));
        }
        xml.end("PredictiveScalingConfiguration");
    }

    private static void appendTargetTrackingConfigurationXml(
            XmlBuilder xml, ScalingPolicy.TargetTrackingConfiguration configuration) {
        if (configuration == null) {
            return;
        }
        xml.start("TargetTrackingConfiguration");
        ScalingPolicy.PredefinedMetricSpecification predefinedMetric =
                configuration.getPredefinedMetricSpecification();
        if (predefinedMetric != null) {
            xml.start("PredefinedMetricSpecification");
            if (predefinedMetric.getPredefinedMetricType() != null) {
                xml.elem("PredefinedMetricType", predefinedMetric.getPredefinedMetricType());
            }
            if (predefinedMetric.getResourceLabel() != null) {
                xml.elem("ResourceLabel", predefinedMetric.getResourceLabel());
            }
            xml.end("PredefinedMetricSpecification");
        }
        ScalingPolicy.CustomizedMetricSpecification customizedMetric = configuration.getCustomizedMetricSpecification();
        if (customizedMetric != null) {
            xml.start("CustomizedMetricSpecification");
            if (customizedMetric.getMetricName() != null) {
                xml.elem("MetricName", customizedMetric.getMetricName());
            }
            if (customizedMetric.getNamespace() != null) {
                xml.elem("Namespace", customizedMetric.getNamespace());
            }
            if (!customizedMetric.getDimensions().isEmpty()) {
                xml.start("Dimensions");
                for (ScalingPolicy.MetricDimension dimension : customizedMetric.getDimensions()) {
                    xml.start("member")
                       .elem("Name", dimension.getName())
                       .elem("Value", dimension.getValue())
                       .end("member");
                }
                xml.end("Dimensions");
            }
            if (customizedMetric.getStatistic() != null) {
                xml.elem("Statistic", customizedMetric.getStatistic());
            }
            if (customizedMetric.getUnit() != null) {
                xml.elem("Unit", customizedMetric.getUnit());
            }
            if (customizedMetric.getPeriod() != null) {
                xml.elem("Period", String.valueOf(customizedMetric.getPeriod()));
            }
            appendTargetTrackingMetricDataQueriesXml(xml, customizedMetric.getMetrics());
            xml.end("CustomizedMetricSpecification");
        }
        if (configuration.getTargetValue() != null) {
            xml.elem("TargetValue", String.valueOf(configuration.getTargetValue()));
        }
        if (configuration.getDisableScaleIn() != null) {
            xml.elem("DisableScaleIn", String.valueOf(configuration.getDisableScaleIn()));
        }
        xml.end("TargetTrackingConfiguration");
    }

    // lex00/floci#122: writes CustomizedMetricSpecification's Metrics (metric-math) list back -
    // see ScalingPolicy.CustomizedMetricSpecification's own doc comment on `metrics` for why.
    private static void appendTargetTrackingMetricDataQueriesXml(
            XmlBuilder xml, List<ScalingPolicy.TargetTrackingMetricDataQuery> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        xml.start("Metrics");
        for (ScalingPolicy.TargetTrackingMetricDataQuery query : metrics) {
            xml.start("member");
            if (query.getId() != null) { xml.elem("Id", query.getId()); }
            if (query.getExpression() != null) { xml.elem("Expression", query.getExpression()); }
            ScalingPolicy.TargetTrackingMetricStat metricStat = query.getMetricStat();
            if (metricStat != null) {
                xml.start("MetricStat");
                ScalingPolicy.Metric metric = metricStat.getMetric();
                if (metric != null) {
                    xml.start("Metric");
                    if (metric.getNamespace() != null) { xml.elem("Namespace", metric.getNamespace()); }
                    if (metric.getMetricName() != null) { xml.elem("MetricName", metric.getMetricName()); }
                    if (!metric.getDimensions().isEmpty()) {
                        xml.start("Dimensions");
                        for (ScalingPolicy.MetricDimension dimension : metric.getDimensions()) {
                            xml.start("member")
                               .elem("Name", dimension.getName())
                               .elem("Value", dimension.getValue())
                               .end("member");
                        }
                        xml.end("Dimensions");
                    }
                    xml.end("Metric");
                }
                if (metricStat.getStat() != null) { xml.elem("Stat", metricStat.getStat()); }
                if (metricStat.getUnit() != null) { xml.elem("Unit", metricStat.getUnit()); }
                if (metricStat.getPeriod() != null) { xml.elem("Period", String.valueOf(metricStat.getPeriod())); }
                xml.end("MetricStat");
            }
            if (query.getLabel() != null) { xml.elem("Label", query.getLabel()); }
            if (query.getPeriod() != null) { xml.elem("Period", String.valueOf(query.getPeriod())); }
            if (query.getReturnData() != null) { xml.elem("ReturnData", String.valueOf(query.getReturnData())); }
            xml.end("member");
        }
        xml.end("Metrics");
    }

    // ── Activities ────────────────────────────────────────────────────────────

    private Response handleDescribeScalingActivities(MultivaluedMap<String, String> p, String region) {
        List<ScalingActivity> activities = service.describeScalingActivities(
                region, p.getFirst("AutoScalingGroupName"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeScalingActivitiesResponse", NS)
                  .start("DescribeScalingActivitiesResult")
                    .start("Activities");
        for (ScalingActivity a : activities) {
            xml.start("member")
               .elem("ActivityId", a.getActivityId())
               .elem("AutoScalingGroupName", a.getAutoScalingGroupName())
               .elem("StatusCode", a.getStatusCode())
               .elem("Progress", String.valueOf(a.getProgress()))
               .elem("StartTime", ISO_FMT.format(a.getStartTime()));
            if (a.getDescription() != null) { xml.elem("Description", a.getDescription()); }
            if (a.getCause() != null) { xml.elem("Cause", a.getCause()); }
            if (a.getEndTime() != null) { xml.elem("EndTime", ISO_FMT.format(a.getEndTime())); }
            if (a.getStatusMessage() != null) { xml.elem("StatusMessage", a.getStatusMessage()); }
            xml.end("member");
        }
        xml.end("Activities")
           .end("DescribeScalingActivitiesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeScalingActivitiesResponse");
        return ok(xml.build());
    }

    // ── Metadata responses ────────────────────────────────────────────────────

    private Response handleDescribeNotificationTypes() {
        return ok(new XmlBuilder()
                .start("DescribeAutoScalingNotificationTypesResponse", NS)
                  .start("DescribeAutoScalingNotificationTypesResult")
                    .start("AutoScalingNotificationTypes")
                      .elem("member", "autoscaling:EC2_INSTANCE_LAUNCH")
                      .elem("member", "autoscaling:EC2_INSTANCE_LAUNCH_ERROR")
                      .elem("member", "autoscaling:EC2_INSTANCE_TERMINATE")
                      .elem("member", "autoscaling:EC2_INSTANCE_TERMINATE_ERROR")
                    .end("AutoScalingNotificationTypes")
                  .end("DescribeAutoScalingNotificationTypesResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DescribeAutoScalingNotificationTypesResponse").build());
    }

    private Response handleDescribeTerminationPolicyTypes() {
        return ok(new XmlBuilder()
                .start("DescribeTerminationPolicyTypesResponse", NS)
                  .start("DescribeTerminationPolicyTypesResult")
                    .start("TerminationPolicyTypes")
                      .elem("member", "Default")
                      .elem("member", "OldestInstance")
                      .elem("member", "NewestInstance")
                      .elem("member", "OldestLaunchConfiguration")
                      .elem("member", "ClosestToNextInstanceHour")
                    .end("TerminationPolicyTypes")
                  .end("DescribeTerminationPolicyTypesResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DescribeTerminationPolicyTypesResponse").build());
    }

    private Response handleDescribeAdjustmentTypes() {
        return ok(new XmlBuilder()
                .start("DescribeAdjustmentTypesResponse", NS)
                  .start("DescribeAdjustmentTypesResult")
                    .start("AdjustmentTypes")
                      .elem("member", "ChangeInCapacity")
                      .elem("member", "ExactCapacity")
                      .elem("member", "PercentChangeInCapacity")
                    .end("AdjustmentTypes")
                  .end("DescribeAdjustmentTypesResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DescribeAdjustmentTypesResponse").build());
    }

    private Response handleDescribeAccountLimits() {
        return ok(new XmlBuilder()
                .start("DescribeAccountLimitsResponse", NS)
                  .start("DescribeAccountLimitsResult")
                    .elem("MaxNumberOfAutoScalingGroups", "200")
                    .elem("MaxNumberOfLaunchConfigurations", "200")
                    .elem("NumberOfAutoScalingGroups", "0")
                    .elem("NumberOfLaunchConfigurations", "0")
                  .end("DescribeAccountLimitsResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DescribeAccountLimitsResponse").build());
    }

    private Response handleDescribeLifecycleHookTypes() {
        return ok(new XmlBuilder()
                .start("DescribeLifecycleHookTypesResponse", NS)
                  .start("DescribeLifecycleHookTypesResult")
                    .start("LifecycleHookTypes")
                      .elem("member", "autoscaling:EC2_INSTANCE_LAUNCHING")
                      .elem("member", "autoscaling:EC2_INSTANCE_TERMINATING")
                    .end("LifecycleHookTypes")
                  .end("DescribeLifecycleHookTypesResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DescribeLifecycleHookTypesResponse").build());
    }

    private Response handleDescribeMetricCollectionTypes() {
        return ok(new XmlBuilder()
                .start("DescribeMetricCollectionTypesResponse", NS)
                  .start("DescribeMetricCollectionTypesResult")
                    .start("Metrics")
                      .elem("member", "GroupMinSize")
                      .elem("member", "GroupMaxSize")
                      .elem("member", "GroupDesiredCapacity")
                      .elem("member", "GroupInServiceInstances")
                      .elem("member", "GroupTotalInstances")
                    .end("Metrics")
                    .start("Granularities")
                      .elem("member", "1Minute")
                    .end("Granularities")
                  .end("DescribeMetricCollectionTypesResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DescribeMetricCollectionTypesResponse").build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> memberList(MultivaluedMap<String, String> p, String prefix) {
        List<String> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String val = p.getFirst(prefix + ".member." + i);
            if (val == null) { break; }
            result.add(val);
        }
        return result;
    }

    private List<String> commaList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    // lex00/floci#112: see AutoScalingGroup's own doc comment and AsgOptionalFields for context.
    private AsgOptionalFields parseAsgOptionalFields(MultivaluedMap<String, String> p) {
        Integer defaultInstanceWarmup = nullableIntParam(p, "DefaultInstanceWarmup");
        Boolean capacityRebalance = p.getFirst("CapacityRebalance") != null
                ? Boolean.parseBoolean(p.getFirst("CapacityRebalance")) : null;
        Integer maxInstanceLifetime = nullableIntParam(p, "MaxInstanceLifetime");
        String serviceLinkedRoleArn = p.getFirst("ServiceLinkedRoleARN");

        AutoScalingGroup.InstanceMaintenancePolicy maintenancePolicy = null;
        Integer minHealthy = nullableIntParam(p, "InstanceMaintenancePolicy.MinHealthyPercentage");
        Integer maxHealthy = nullableIntParam(p, "InstanceMaintenancePolicy.MaxHealthyPercentage");
        if (minHealthy != null || maxHealthy != null) {
            maintenancePolicy = new AutoScalingGroup.InstanceMaintenancePolicy();
            maintenancePolicy.setMinHealthyPercentage(minHealthy);
            maintenancePolicy.setMaxHealthyPercentage(maxHealthy);
        }

        AutoScalingGroup.AvailabilityZoneDistribution azDistribution = null;
        String capacityDistributionStrategy = p.getFirst("AvailabilityZoneDistribution.CapacityDistributionStrategy");
        if (capacityDistributionStrategy != null) {
            azDistribution = new AutoScalingGroup.AvailabilityZoneDistribution();
            azDistribution.setCapacityDistributionStrategy(capacityDistributionStrategy);
        }

        AutoScalingGroup.CapacityReservationSpecification capacityReservationSpecification = null;
        String capacityReservationPreference = p.getFirst("CapacityReservationSpecification.CapacityReservationPreference");
        if (capacityReservationPreference != null) {
            capacityReservationSpecification = new AutoScalingGroup.CapacityReservationSpecification();
            capacityReservationSpecification.setCapacityReservationPreference(capacityReservationPreference);
        }

        String desiredCapacityType = p.getFirst("DesiredCapacityType");

        if (defaultInstanceWarmup == null && capacityRebalance == null && maxInstanceLifetime == null
                && serviceLinkedRoleArn == null && maintenancePolicy == null && azDistribution == null
                && capacityReservationSpecification == null && desiredCapacityType == null) {
            return null;
        }
        return new AsgOptionalFields(defaultInstanceWarmup, capacityRebalance, maxInstanceLifetime,
                serviceLinkedRoleArn, maintenancePolicy, azDistribution, capacityReservationSpecification,
                desiredCapacityType);
    }

    private MixedInstancesPolicy parseMixedInstancesPolicy(MultivaluedMap<String, String> p) {
        if (!hasAnyPrefix(p, "MixedInstancesPolicy.")) {
            return null;
        }
        MixedInstancesPolicy policy = new MixedInstancesPolicy();

        MixedInstancesPolicy.LaunchTemplate launchTemplate = new MixedInstancesPolicy.LaunchTemplate();
        MixedInstancesPolicy.LaunchTemplateSpecification specification =
                new MixedInstancesPolicy.LaunchTemplateSpecification();
        specification.setLaunchTemplateId(p.getFirst(
                "MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId"));
        specification.setLaunchTemplateName(p.getFirst(
                "MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateName"));
        specification.setVersion(p.getFirst(
                "MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.Version"));
        if (specification.getLaunchTemplateId() != null
                || specification.getLaunchTemplateName() != null
                || specification.getVersion() != null) {
            launchTemplate.setLaunchTemplateSpecification(specification);
        }
        launchTemplate.setOverrides(parseMixedLaunchTemplateOverrides(p));
        if (launchTemplate.getLaunchTemplateSpecification() != null || !launchTemplate.getOverrides().isEmpty()) {
            policy.setLaunchTemplate(launchTemplate);
        }

        MixedInstancesPolicy.InstancesDistribution distribution =
                new MixedInstancesPolicy.InstancesDistribution();
        distribution.setOnDemandBaseCapacity(nullableIntParam(
                p, "MixedInstancesPolicy.InstancesDistribution.OnDemandBaseCapacity"));
        distribution.setOnDemandPercentageAboveBaseCapacity(nullableIntParam(
                p, "MixedInstancesPolicy.InstancesDistribution.OnDemandPercentageAboveBaseCapacity"));
        distribution.setSpotAllocationStrategy(
                p.getFirst("MixedInstancesPolicy.InstancesDistribution.SpotAllocationStrategy"));
        if (distribution.getOnDemandBaseCapacity() != null
                || distribution.getOnDemandPercentageAboveBaseCapacity() != null
                || distribution.getSpotAllocationStrategy() != null) {
            policy.setInstancesDistribution(distribution);
        }
        return policy;
    }

    private static boolean hasAnyPrefix(MultivaluedMap<String, String> p, String prefix) {
        for (String key : p.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // lex00/floci#112's round-5 re-measure: this loop used to treat InstanceType's presence as
    // the sole "is there another override" signal, so an attribute-based override that set ONLY
    // InstanceRequirements (mutually exclusive with InstanceType by AWS's own design - see
    // MixedInstancesPolicy.LaunchTemplateOverride's own doc comment) was never reached at all,
    // dropping the entire Overrides list rather than just the missing field.
    private List<MixedInstancesPolicy.LaunchTemplateOverride> parseMixedLaunchTemplateOverrides(
            MultivaluedMap<String, String> p) {
        List<MixedInstancesPolicy.LaunchTemplateOverride> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String prefix = "MixedInstancesPolicy.LaunchTemplate.Overrides.member." + i;
            String instanceType = p.getFirst(prefix + ".InstanceType");
            String weightedCapacity = p.getFirst(prefix + ".WeightedCapacity");
            MixedInstancesPolicy.InstanceRequirements instanceRequirements =
                    parseMixedInstancesOverrideInstanceRequirements(p, prefix + ".InstanceRequirements");
            if (instanceType == null && weightedCapacity == null && instanceRequirements == null) {
                break;
            }
            MixedInstancesPolicy.LaunchTemplateOverride override =
                    new MixedInstancesPolicy.LaunchTemplateOverride();
            override.setInstanceType(instanceType);
            override.setWeightedCapacity(weightedCapacity);
            override.setInstanceRequirements(instanceRequirements);
            result.add(override);
        }
        return result;
    }

    private MixedInstancesPolicy.InstanceRequirements parseMixedInstancesOverrideInstanceRequirements(
            MultivaluedMap<String, String> p, String prefix) {
        Integer vCpuMin = nullableIntParam(p, prefix + ".VCpuCount.Min");
        Integer vCpuMax = nullableIntParam(p, prefix + ".VCpuCount.Max");
        Integer memoryMiBMin = nullableIntParam(p, prefix + ".MemoryMiB.Min");
        Integer memoryMiBMax = nullableIntParam(p, prefix + ".MemoryMiB.Max");
        List<String> cpuManufacturers = memberList(p, prefix + ".CpuManufacturers");
        Double memoryGiBPerVCpuMin = nullableDoubleParam(p, prefix + ".MemoryGiBPerVCpu.Min");
        Double memoryGiBPerVCpuMax = nullableDoubleParam(p, prefix + ".MemoryGiBPerVCpu.Max");
        List<String> excludedInstanceTypes = memberList(p, prefix + ".ExcludedInstanceTypes");
        List<String> instanceGenerations = memberList(p, prefix + ".InstanceGenerations");
        List<String> localStorageTypes = memberList(p, prefix + ".LocalStorageTypes");
        Integer maxSpotPricePct = nullableIntParam(p, prefix + ".MaxSpotPriceAsPercentageOfOptimalOnDemandPrice");
        String bareMetal = p.getFirst(prefix + ".BareMetal");
        String burstablePerformance = p.getFirst(prefix + ".BurstablePerformance");
        List<String> allowedInstanceTypes = memberList(p, prefix + ".AllowedInstanceTypes");

        boolean present = vCpuMin != null || vCpuMax != null || memoryMiBMin != null || memoryMiBMax != null
                || !cpuManufacturers.isEmpty() || memoryGiBPerVCpuMin != null || memoryGiBPerVCpuMax != null
                || !excludedInstanceTypes.isEmpty() || !instanceGenerations.isEmpty()
                || !localStorageTypes.isEmpty() || maxSpotPricePct != null || bareMetal != null
                || burstablePerformance != null || !allowedInstanceTypes.isEmpty();
        if (!present) {
            return null;
        }
        MixedInstancesPolicy.InstanceRequirements requirements = new MixedInstancesPolicy.InstanceRequirements();
        if (vCpuMin != null || vCpuMax != null) {
            MixedInstancesPolicy.IntRange range = new MixedInstancesPolicy.IntRange();
            range.setMin(vCpuMin);
            range.setMax(vCpuMax);
            requirements.setVCpuCount(range);
        }
        if (memoryMiBMin != null || memoryMiBMax != null) {
            MixedInstancesPolicy.IntRange range = new MixedInstancesPolicy.IntRange();
            range.setMin(memoryMiBMin);
            range.setMax(memoryMiBMax);
            requirements.setMemoryMiB(range);
        }
        requirements.setCpuManufacturers(cpuManufacturers);
        if (memoryGiBPerVCpuMin != null || memoryGiBPerVCpuMax != null) {
            MixedInstancesPolicy.DoubleRange range = new MixedInstancesPolicy.DoubleRange();
            range.setMin(memoryGiBPerVCpuMin);
            range.setMax(memoryGiBPerVCpuMax);
            requirements.setMemoryGiBPerVCpu(range);
        }
        requirements.setExcludedInstanceTypes(excludedInstanceTypes);
        requirements.setInstanceGenerations(instanceGenerations);
        requirements.setLocalStorageTypes(localStorageTypes);
        requirements.setMaxSpotPriceAsPercentageOfOptimalOnDemandPrice(maxSpotPricePct);
        requirements.setBareMetal(bareMetal);
        requirements.setBurstablePerformance(burstablePerformance);
        requirements.setAllowedInstanceTypes(allowedInstanceTypes);
        return requirements;
    }

    private ParsedTags parseTags(MultivaluedMap<String, String> p) {
        Map<String, String> tags = new LinkedHashMap<>();
        Map<String, Boolean> propagateAtLaunch = new LinkedHashMap<>();
        for (int i = 1; ; i++) {
            String key = p.getFirst("Tags.member." + i + ".Key");
            if (key == null) { break; }
            String value = p.getFirst("Tags.member." + i + ".Value");
            tags.put(key, value != null ? value : "");
            propagateAtLaunch.put(key,
                    Boolean.parseBoolean(p.getFirst("Tags.member." + i + ".PropagateAtLaunch")));
        }
        return new ParsedTags(tags, propagateAtLaunch);
    }

    private List<TagRequest> parseTagRequests(MultivaluedMap<String, String> p) {
        List<TagRequest> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String key = p.getFirst("Tags.member." + i + ".Key");
            if (key == null) { break; }
            String resourceId = p.getFirst("Tags.member." + i + ".ResourceId");
            String resourceType = p.getFirst("Tags.member." + i + ".ResourceType");
            String value = p.getFirst("Tags.member." + i + ".Value");
            boolean propagateAtLaunch = Boolean.parseBoolean(p.getFirst("Tags.member." + i + ".PropagateAtLaunch"));
            result.add(new TagRequest(resourceId, resourceType, key, value != null ? value : "", propagateAtLaunch));
        }
        return result;
    }

    private record TagRequest(String resourceId, String resourceType, String key, String value, boolean propagateAtLaunch) {}

    private record ParsedTags(Map<String, String> tags, Map<String, Boolean> propagateAtLaunch) {}

    private InstanceRefresh parseInstanceRefresh(MultivaluedMap<String, String> p) {
        InstanceRefresh refresh = new InstanceRefresh();
        refresh.setStrategy(p.getFirst("Strategy"));
        refresh.setDesiredLaunchTemplateId(p.getFirst("DesiredConfiguration.LaunchTemplate.LaunchTemplateId"));
        refresh.setDesiredLaunchTemplateName(p.getFirst("DesiredConfiguration.LaunchTemplate.LaunchTemplateName"));
        refresh.setDesiredLaunchTemplateVersion(p.getFirst("DesiredConfiguration.LaunchTemplate.Version"));
        refresh.setMinHealthyPercentage(nullableIntParam(p, "Preferences.MinHealthyPercentage"));
        refresh.setMaxHealthyPercentage(nullableIntParam(p, "Preferences.MaxHealthyPercentage"));
        refresh.setInstanceWarmup(nullableIntParam(p, "Preferences.InstanceWarmup"));
        refresh.setSkipMatching(nullableBoolParam(p, "Preferences.SkipMatching"));
        refresh.setAutoRollback(nullableBoolParam(p, "Preferences.AutoRollback"));
        refresh.setScaleInProtectedInstances(p.getFirst("Preferences.ScaleInProtectedInstances"));
        refresh.setStandbyInstances(p.getFirst("Preferences.StandbyInstances"));
        refresh.setCheckpointDelay(nullableIntParam(p, "Preferences.CheckpointDelay"));
        refresh.setBakeTime(nullableIntParam(p, "Preferences.BakeTime"));
        refresh.setCheckpointPercentages(memberIntList(p, "Preferences.CheckpointPercentages"));
        return refresh;
    }

    private List<Integer> memberIntList(MultivaluedMap<String, String> p, String prefix) {
        List<Integer> result = new ArrayList<>();
        for (String value : memberList(p, prefix)) {
            try {
                result.add(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                // Keep Query parsing permissive like the existing integer helpers.
            }
        }
        return result;
    }

    private int intParam(MultivaluedMap<String, String> p, String key, int defaultValue) {
        String val = p.getFirst(key);
        if (val == null || val.isBlank()) { return defaultValue; }
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return defaultValue; }
    }

    private Integer nullableIntParam(MultivaluedMap<String, String> p, String key) {
        String val = p.getFirst(key);
        if (val == null || val.isBlank()) { return null; }
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return null; }
    }

    private static Double nullableDoubleParam(MultivaluedMap<String, String> p, String key) {
        String val = p.getFirst(key);
        if (val == null || val.isBlank()) { return null; }
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return null; }
    }

    private Boolean nullableBoolParam(MultivaluedMap<String, String> p, String key) {
        String val = p.getFirst(key);
        if (val == null || val.isBlank()) { return null; }
        return Boolean.parseBoolean(val);
    }

    private String intString(Integer value) {
        return value != null ? String.valueOf(value) : null;
    }

    private String boolString(Boolean value) {
        return value != null ? String.valueOf(value) : null;
    }

    private Response ok(String xml) {
        return Response.ok(xml).type("application/xml").build();
    }

    private Response xmlError(String code, String message, int status) {
        String xml = new XmlBuilder()
                .start("ErrorResponse", NS)
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", code)
                    .elem("Message", message)
                  .end("Error")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("ErrorResponse")
                .build();
        return Response.status(status).entity(xml).type("application/xml").build();
    }
}
