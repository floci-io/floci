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
                case "StartInstanceRefresh"         -> handleStartInstanceRefresh(p, region);
                case "DescribeInstanceRefreshes"    -> handleDescribeInstanceRefreshes(p, region);
                case "CreateOrUpdateTags"           -> handleCreateOrUpdateTags(p, region);
                case "DeleteTags"                   -> handleDeleteTags(p, region);
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
                "true".equalsIgnoreCase(p.getFirst("AssociatePublicIpAddress")));
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
               .elem("CreatedTime", ISO_FMT.format(lc.getCreatedTime()))
               .elem("AssociatePublicIpAddress", String.valueOf(lc.isAssociatePublicIpAddress()));
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
                parsedTags.propagateAtLaunch());
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
                tps.isEmpty() ? null : tps);
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
                intParam(p, "Cooldown", 300),
                nullableIntParam(p, "EstimatedInstanceWarmup"),
                parseTargetTrackingConfiguration(p));
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
               .elem("Cooldown", String.valueOf(policy.getCooldown()));
            if (policy.getAdjustmentType() != null) { xml.elem("AdjustmentType", policy.getAdjustmentType()); }
            if (policy.getEstimatedInstanceWarmup() != null) {
                xml.elem("EstimatedInstanceWarmup", String.valueOf(policy.getEstimatedInstanceWarmup()));
            }
            appendTargetTrackingConfigurationXml(xml, policy.getTargetTrackingConfiguration());
            xml.end("member");
        }
        xml.end("ScalingPolicies")
           .end("DescribePoliciesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribePoliciesResponse");
        return ok(xml.build());
    }

    private static ScalingPolicy.TargetTrackingConfiguration parseTargetTrackingConfiguration(MultivaluedMap<String, String> p) {
        String predefinedMetricType = p.getFirst("TargetTrackingConfiguration.PredefinedMetricSpecification.PredefinedMetricType");
        Double targetValue = nullableDoubleParam(p, "TargetTrackingConfiguration.TargetValue");
        if (predefinedMetricType == null && targetValue == null) {
            return null;
        }
        ScalingPolicy.TargetTrackingConfiguration configuration = new ScalingPolicy.TargetTrackingConfiguration();
        if (predefinedMetricType != null) {
            ScalingPolicy.PredefinedMetricSpecification specification =
                    new ScalingPolicy.PredefinedMetricSpecification();
            specification.setPredefinedMetricType(predefinedMetricType);
            configuration.setPredefinedMetricSpecification(specification);
        }
        configuration.setTargetValue(targetValue);
        return configuration;
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
            xml.end("PredefinedMetricSpecification");
        }
        if (configuration.getTargetValue() != null) {
            xml.elem("TargetValue", String.valueOf(configuration.getTargetValue()));
        }
        xml.end("TargetTrackingConfiguration");
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

    private List<MixedInstancesPolicy.LaunchTemplateOverride> parseMixedLaunchTemplateOverrides(
            MultivaluedMap<String, String> p) {
        List<MixedInstancesPolicy.LaunchTemplateOverride> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String instanceType = p.getFirst("MixedInstancesPolicy.LaunchTemplate.Overrides.member."
                    + i + ".InstanceType");
            if (instanceType == null) { break; }
            MixedInstancesPolicy.LaunchTemplateOverride override =
                    new MixedInstancesPolicy.LaunchTemplateOverride();
            override.setInstanceType(instanceType);
            result.add(override);
        }
        return result;
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
