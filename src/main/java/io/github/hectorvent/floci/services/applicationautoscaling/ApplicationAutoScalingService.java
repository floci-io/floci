package io.github.hectorvent.floci.services.applicationautoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.applicationautoscaling.model.Alarm;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalableTarget;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.applicationautoscaling.model.StepScalingConfiguration;
import io.github.hectorvent.floci.services.applicationautoscaling.model.SuspendedState;
import io.github.hectorvent.floci.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Application Auto Scaling control plane.
 *
 * <p>Scalable targets and scaling policies are stored and described faithfully, but
 * policies are <strong>inert</strong>: nothing evaluates them and no capacity is ever
 * adjusted. This mirrors the existing EC2 Auto Scaling {@code PutScalingPolicy} behaviour
 * and is documented in {@code docs/services/applicationautoscaling.md}.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/Welcome.html">Application Auto Scaling API Reference</a>
 */
@ApplicationScoped
public class ApplicationAutoScalingService {

    private static final Logger LOG = Logger.getLogger(ApplicationAutoScalingService.class);

    static final Set<String> SERVICE_NAMESPACES = Set.of(
            "ecs", "elasticmapreduce", "ec2", "appstream", "dynamodb", "rds", "sagemaker",
            "custom-resource", "comprehend", "lambda", "cassandra", "kafka", "elasticache",
            "neptune", "workspaces");

    static final Set<String> SCALABLE_DIMENSIONS = Set.of(
            "ecs:service:DesiredCount",
            "ec2:spot-fleet-request:TargetCapacity",
            "elasticmapreduce:instancegroup:InstanceCount",
            "appstream:fleet:DesiredCapacity",
            "dynamodb:table:ReadCapacityUnits",
            "dynamodb:table:WriteCapacityUnits",
            "dynamodb:index:ReadCapacityUnits",
            "dynamodb:index:WriteCapacityUnits",
            "rds:cluster:ReadReplicaCount",
            "sagemaker:variant:DesiredInstanceCount",
            "custom-resource:ResourceType:Property",
            "comprehend:document-classifier-endpoint:DesiredInferenceUnits",
            "comprehend:entity-recognizer-endpoint:DesiredInferenceUnits",
            "lambda:function:ProvisionedConcurrency",
            "cassandra:table:ReadCapacityUnits",
            "cassandra:table:WriteCapacityUnits",
            "kafka:broker-storage:VolumeSize",
            "elasticache:cache-cluster:Nodes",
            "elasticache:replication-group:NodeGroups",
            "elasticache:replication-group:Replicas",
            "neptune:cluster:ReadReplicaCount",
            "sagemaker:variant:DesiredProvisionedConcurrency",
            "sagemaker:inference-component:DesiredCopyCount",
            "workspaces:workspacespool:DesiredUserSessions");

    private static final Set<String> POLICY_TYPES =
            Set.of("StepScaling", "TargetTrackingScaling", "PredictiveScaling");

    private final StorageBackend<String, ScalableTarget> targets;
    private final StorageBackend<String, ScalingPolicy> policies;
    private final RegionResolver regionResolver;
    private final CloudWatchMetricsService cloudWatchMetricsService;

    @Inject
    public ApplicationAutoScalingService(StorageFactory factory,
                                         RegionResolver regionResolver,
                                         CloudWatchMetricsService cloudWatchMetricsService) {
        this(factory.create("applicationautoscaling", "application-autoscaling-targets.json",
                        new TypeReference<Map<String, ScalableTarget>>() {}),
                factory.create("applicationautoscaling", "application-autoscaling-policies.json",
                        new TypeReference<Map<String, ScalingPolicy>>() {}),
                regionResolver,
                cloudWatchMetricsService);
    }

    ApplicationAutoScalingService(StorageBackend<String, ScalableTarget> targets,
                                  StorageBackend<String, ScalingPolicy> policies,
                                  RegionResolver regionResolver,
                                  CloudWatchMetricsService cloudWatchMetricsService) {
        this.targets = targets;
        this.policies = policies;
        this.regionResolver = regionResolver;
        this.cloudWatchMetricsService = cloudWatchMetricsService;
    }

    // ---------------------------------------------------------------- scalable targets

    public ScalableTarget registerScalableTarget(String serviceNamespace, String resourceId,
                                                 String scalableDimension, Integer minCapacity,
                                                 Integer maxCapacity, String roleArn,
                                                 SuspendedState suspendedState,
                                                 Map<String, String> tags, String region) {
        validateTriple(serviceNamespace, resourceId, scalableDimension);

        String key = targetKey(region, serviceNamespace, resourceId, scalableDimension);
        ScalableTarget existing = targets.get(key).orElse(null);

        if (existing == null) {
            if (minCapacity == null || maxCapacity == null) {
                throw new AwsException("ValidationException",
                        "Min and max capacity are required when registering a new scalable target.", 400);
            }
            ScalableTarget target = new ScalableTarget();
            target.setServiceNamespace(serviceNamespace);
            target.setResourceId(resourceId);
            target.setScalableDimension(scalableDimension);
            target.setMinCapacity(minCapacity);
            target.setMaxCapacity(maxCapacity);
            target.setRoleArn(roleArn != null ? roleArn : serviceLinkedRoleArn(serviceNamespace));
            target.setScalableTargetArn(buildScalableTargetArn(region));
            target.setCreationTime(nowEpochSeconds());
            target.setSuspendedState(suspendedState != null ? suspendedState : new SuspendedState());
            if (tags != null) {
                target.setTags(tags);
            }
            targets.put(key, target);
            LOG.infov("RegisterScalableTarget: {0} {1} {2} in {3}",
                    serviceNamespace, resourceId, scalableDimension, region);
            return target;
        }

        // Update semantics: only supplied parameters change.
        if (minCapacity != null) {
            existing.setMinCapacity(minCapacity);
        }
        if (maxCapacity != null) {
            existing.setMaxCapacity(maxCapacity);
        }
        if (roleArn != null) {
            existing.setRoleArn(roleArn);
        }
        if (suspendedState != null) {
            existing.setSuspendedState(suspendedState);
        }
        if (tags != null && !tags.isEmpty()) {
            existing.putTags(tags);
        }
        targets.put(key, existing);
        LOG.infov("RegisterScalableTarget (update): {0} {1} {2} in {3}",
                serviceNamespace, resourceId, scalableDimension, region);
        return existing;
    }

    public List<ScalableTarget> describeScalableTargets(String serviceNamespace, List<String> resourceIds,
                                                        String scalableDimension, String region) {
        requireNamespace(serviceNamespace);
        if (scalableDimension != null && (resourceIds == null || resourceIds.isEmpty())) {
            throw new AwsException("ValidationException",
                    "A resource ID must be specified when a scalable dimension is specified.", 400);
        }
        String prefix = region + "::" + serviceNamespace + "::";
        return targets.scan(k -> k.startsWith(prefix)).stream()
                .filter(t -> resourceIds == null || resourceIds.isEmpty() || resourceIds.contains(t.getResourceId()))
                .filter(t -> scalableDimension == null || scalableDimension.equals(t.getScalableDimension()))
                .sorted(Comparator.comparing(ScalableTarget::getResourceId)
                        .thenComparing(ScalableTarget::getScalableDimension))
                .toList();
    }

    /**
     * Deregisters a scalable target and, per AWS semantics, deletes every scaling policy
     * attached to it along with the CloudWatch alarms those policies own.
     */
    public void deregisterScalableTarget(String serviceNamespace, String resourceId,
                                         String scalableDimension, String region) {
        validateTriple(serviceNamespace, resourceId, scalableDimension);
        String key = targetKey(region, serviceNamespace, resourceId, scalableDimension);
        if (targets.get(key).isEmpty()) {
            throw new AwsException("ObjectNotFoundException",
                    "No scalable target found for service namespace: " + serviceNamespace
                            + ", resource ID: " + resourceId
                            + ", scalable dimension: " + scalableDimension, 400);
        }
        for (ScalingPolicy policy : findPolicies(region, serviceNamespace, resourceId, scalableDimension)) {
            deleteAlarms(policy, region);
            policies.delete(policyKey(region, serviceNamespace, resourceId, scalableDimension, policy.getPolicyName()));
        }
        targets.delete(key);
        LOG.infov("DeregisterScalableTarget: {0} {1} {2} in {3}",
                serviceNamespace, resourceId, scalableDimension, region);
    }

    // ---------------------------------------------------------------- scaling policies

    public ScalingPolicy putScalingPolicy(String policyName, String policyType, String serviceNamespace,
                                          String resourceId, String scalableDimension,
                                          TargetTrackingConfiguration targetTracking,
                                          StepScalingConfiguration stepScaling, String region) {
        validateTriple(serviceNamespace, resourceId, scalableDimension);
        if (policyName == null || policyName.isBlank()) {
            throw new AwsException("ValidationException", "PolicyName is required.", 400);
        }
        if (policyType != null && !POLICY_TYPES.contains(policyType)) {
            throw new AwsException("ValidationException",
                    "Unsupported policy type: " + policyType, 400);
        }
        String targetKey = targetKey(region, serviceNamespace, resourceId, scalableDimension);
        if (targets.get(targetKey).isEmpty()) {
            throw new AwsException("ObjectNotFoundException",
                    "No scalable target found for service namespace: " + serviceNamespace
                            + ", resource ID: " + resourceId
                            + ", scalable dimension: " + scalableDimension, 400);
        }

        String key = policyKey(region, serviceNamespace, resourceId, scalableDimension, policyName);
        ScalingPolicy existing = policies.get(key).orElse(null);

        ScalingPolicy policy = existing != null ? existing : new ScalingPolicy();
        policy.setPolicyName(policyName);
        policy.setPolicyType(policyType != null ? policyType : "StepScaling");
        policy.setServiceNamespace(serviceNamespace);
        policy.setResourceId(resourceId);
        policy.setScalableDimension(scalableDimension);
        policy.setTargetTrackingConfiguration(targetTracking);
        policy.setStepScalingConfiguration(stepScaling);
        if (existing == null) {
            policy.setCreationTime(nowEpochSeconds());
            policy.setPolicyArn(buildPolicyArn(region, serviceNamespace, resourceId, policyName));
        }

        if ("TargetTrackingScaling".equals(policy.getPolicyType())) {
            if (existing != null) {
                deleteAlarms(existing, region);
            }
            policy.setAlarms(createTargetTrackingAlarms(policy, region));
        } else {
            policy.setAlarms(new ArrayList<>());
        }

        policies.put(key, policy);
        LOG.infov("PutScalingPolicy: {0} ({1}) for {2} {3} in {4}",
                policyName, policy.getPolicyType(), serviceNamespace, resourceId, region);
        return policy;
    }

    public List<ScalingPolicy> describeScalingPolicies(String serviceNamespace, String resourceId,
                                                       String scalableDimension, List<String> policyNames,
                                                       String region) {
        requireNamespace(serviceNamespace);
        if (scalableDimension != null && (resourceId == null || resourceId.isBlank())) {
            throw new AwsException("ValidationException",
                    "A resource ID must be specified when a scalable dimension is specified.", 400);
        }
        String prefix = region + "::" + serviceNamespace + "::";
        return policies.scan(k -> k.startsWith(prefix)).stream()
                .filter(p -> resourceId == null || resourceId.equals(p.getResourceId()))
                .filter(p -> scalableDimension == null || scalableDimension.equals(p.getScalableDimension()))
                .filter(p -> policyNames == null || policyNames.isEmpty() || policyNames.contains(p.getPolicyName()))
                .sorted(Comparator.comparing(ScalingPolicy::getPolicyName))
                .toList();
    }

    public void deleteScalingPolicy(String policyName, String serviceNamespace, String resourceId,
                                    String scalableDimension, String region) {
        validateTriple(serviceNamespace, resourceId, scalableDimension);
        if (policyName == null || policyName.isBlank()) {
            throw new AwsException("ValidationException", "PolicyName is required.", 400);
        }
        String key = policyKey(region, serviceNamespace, resourceId, scalableDimension, policyName);
        ScalingPolicy policy = policies.get(key).orElseThrow(() -> new AwsException("ObjectNotFoundException",
                "No scaling policy found for service namespace: " + serviceNamespace
                        + ", resource ID: " + resourceId
                        + ", scalable dimension: " + scalableDimension
                        + ", policy name: " + policyName, 400));
        deleteAlarms(policy, region);
        policies.delete(key);
        LOG.infov("DeleteScalingPolicy: {0} for {1} {2} in {3}",
                policyName, serviceNamespace, resourceId, region);
    }

    // ---------------------------------------------------------------- tagging

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        return findTargetByArn(resourceArn, region).getTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        if (tags == null || tags.isEmpty()) {
            throw new AwsException("ValidationException", "Tags are required.", 400);
        }
        ScalableTarget target = findTargetByArn(resourceArn, region);
        target.putTags(tags);
        targets.put(targetKey(region, target.getServiceNamespace(), target.getResourceId(),
                target.getScalableDimension()), target);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw new AwsException("ValidationException", "TagKeys are required.", 400);
        }
        ScalableTarget target = findTargetByArn(resourceArn, region);
        target.removeTags(tagKeys);
        targets.put(targetKey(region, target.getServiceNamespace(), target.getResourceId(),
                target.getScalableDimension()), target);
    }

    // ---------------------------------------------------------------- internals

    private ScalableTarget findTargetByArn(String resourceArn, String region) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("ValidationException", "ResourceARN is required.", 400);
        }
        String prefix = region + "::";
        return targets.scan(k -> k.startsWith(prefix)).stream()
                .filter(t -> resourceArn.equals(t.getScalableTargetArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceArn, 400));
    }

    private List<ScalingPolicy> findPolicies(String region, String serviceNamespace, String resourceId,
                                             String scalableDimension) {
        String prefix = region + "::" + serviceNamespace + "::";
        return policies.scan(k -> k.startsWith(prefix)).stream()
                .filter(p -> resourceId.equals(p.getResourceId()))
                .filter(p -> scalableDimension.equals(p.getScalableDimension()))
                .toList();
    }

    /**
     * Creates the pair of CloudWatch alarms that AWS provisions on behalf of a target
     * tracking policy. The alarms are real: they show up in DescribeAlarms exactly as they
     * would in AWS, even though nothing evaluates them here.
     */
    private List<Alarm> createTargetTrackingAlarms(ScalingPolicy policy, String region) {
        List<Alarm> alarms = new ArrayList<>();
        for (String suffix : List.of("AlarmHigh", "AlarmLow")) {
            String alarmName = "TargetTracking-" + policy.getResourceId() + "-" + suffix + "-"
                    + UUID.randomUUID();
            MetricAlarm alarm = new MetricAlarm();
            alarm.setAlarmName(alarmName);
            alarm.setAlarmDescription("DO NOT EDIT OR DELETE. For TargetTrackingScaling policy "
                    + policy.getPolicyArn() + ".");
            alarm.setNamespace(cloudWatchNamespace(policy.getServiceNamespace()));
            alarm.setMetricName(predefinedMetricType(policy));
            alarm.setStatistic("Average");
            alarm.setPeriod(60);
            alarm.setEvaluationPeriods("AlarmHigh".equals(suffix) ? 3 : 15);
            alarm.setThreshold(targetValue(policy));
            alarm.setComparisonOperator("AlarmHigh".equals(suffix)
                    ? "GreaterThanThreshold" : "LessThanThreshold");
            alarm.setAlarmActions(List.of(policy.getPolicyArn()));
            cloudWatchMetricsService.putMetricAlarm(alarm, region);
            alarms.add(new Alarm(alarmName, alarm.getAlarmArn()));
        }
        return alarms;
    }

    private void deleteAlarms(ScalingPolicy policy, String region) {
        List<String> names = policy.getAlarms().stream().map(Alarm::getAlarmName).toList();
        if (names.isEmpty()) {
            return;
        }
        try {
            cloudWatchMetricsService.deleteAlarms(names, region);
        } catch (RuntimeException e) {
            // Alarm cleanup is best-effort: a user may have deleted them out of band.
            LOG.debugv(e, "Could not delete target-tracking alarms {0} in {1}", names, region);
        }
    }

    private static String cloudWatchNamespace(String serviceNamespace) {
        return switch (serviceNamespace) {
            case "ecs" -> "AWS/ECS";
            case "kafka" -> "AWS/Kafka";
            case "dynamodb" -> "AWS/DynamoDB";
            case "lambda" -> "AWS/Lambda";
            case "rds" -> "AWS/RDS";
            case "elasticache" -> "AWS/ElastiCache";
            default -> "AWS/" + serviceNamespace.toUpperCase(Locale.ROOT);
        };
    }

    private static String predefinedMetricType(ScalingPolicy policy) {
        TargetTrackingConfiguration config = policy.getTargetTrackingConfiguration();
        if (config != null && config.getPredefinedMetricSpecification() != null
                && config.getPredefinedMetricSpecification().getPredefinedMetricType() != null) {
            return config.getPredefinedMetricSpecification().getPredefinedMetricType();
        }
        return "CustomizedMetric";
    }

    private static double targetValue(ScalingPolicy policy) {
        TargetTrackingConfiguration config = policy.getTargetTrackingConfiguration();
        return config != null && config.getTargetValue() != null ? config.getTargetValue() : 0.0d;
    }

    private void validateTriple(String serviceNamespace, String resourceId, String scalableDimension) {
        requireNamespace(serviceNamespace);
        if (resourceId == null || resourceId.isBlank()) {
            throw new AwsException("ValidationException", "ResourceId is required.", 400);
        }
        if (scalableDimension == null || scalableDimension.isBlank()) {
            throw new AwsException("ValidationException", "ScalableDimension is required.", 400);
        }
        if (!SCALABLE_DIMENSIONS.contains(scalableDimension)) {
            throw new AwsException("ValidationException",
                    "Value '" + scalableDimension + "' at 'scalableDimension' failed to satisfy constraint: "
                            + "Member must satisfy enum value set.", 400);
        }
    }

    private void requireNamespace(String serviceNamespace) {
        if (serviceNamespace == null || serviceNamespace.isBlank()) {
            throw new AwsException("ValidationException", "ServiceNamespace is required.", 400);
        }
        if (!SERVICE_NAMESPACES.contains(serviceNamespace)) {
            throw new AwsException("ValidationException",
                    "Value '" + serviceNamespace + "' at 'serviceNamespace' failed to satisfy constraint: "
                            + "Member must satisfy enum value set.", 400);
        }
    }

    /**
     * The scalable-target ARN must match
     * {@code ^arn:.+:application-autoscaling:.+:[0-9]+:scalable-target/[a-zA-Z0-9-]+$},
     * so the suffix is a dashless UUID.
     */
    private String buildScalableTargetArn(String region) {
        String id = UUID.randomUUID().toString().replace("-", "");
        return regionResolver.buildArn("application-autoscaling", region, "scalable-target/" + id);
    }

    /**
     * Policy ARNs use the {@code autoscaling} service name — not
     * {@code application-autoscaling} — and embed the resource path and policy name.
     */
    private String buildPolicyArn(String region, String serviceNamespace, String resourceId, String policyName) {
        String resource = "scalingPolicy:" + UUID.randomUUID()
                + ":resource/" + serviceNamespace + "/" + resourceId
                + ":policyName/" + policyName;
        return regionResolver.buildArn("autoscaling", region, resource);
    }

    /**
     * AWS creates and returns a service-linked role when the caller supplies none, so the
     * emulator synthesizes one in the same shape to keep the Computed attribute stable.
     */
    private String serviceLinkedRoleArn(String serviceNamespace) {
        String suffix = switch (serviceNamespace) {
            case "ecs" -> "ECSService";
            case "kafka" -> "KafkaCluster";
            case "dynamodb" -> "DynamoDBTable";
            case "lambda" -> "LambdaConcurrency";
            case "rds" -> "RDSCluster";
            case "elasticache" -> "ElastiCacheRG";
            default -> "CustomResource";
        };
        return "arn:aws:iam::" + regionResolver.getAccountId()
                + ":role/aws-service-role/" + serviceNamespace + ".application-autoscaling.amazonaws.com/"
                + "AWSServiceRoleForApplicationAutoScaling_" + suffix;
    }

    private static double nowEpochSeconds() {
        Instant now = Instant.now();
        return now.getEpochSecond() + (now.getNano() / 1_000_000 / 1000.0d);
    }

    private static String targetKey(String region, String serviceNamespace, String resourceId,
                                    String scalableDimension) {
        return region + "::" + serviceNamespace + "::" + scalableDimension + "::" + resourceId;
    }

    private static String policyKey(String region, String serviceNamespace, String resourceId,
                                    String scalableDimension, String policyName) {
        return targetKey(region, serviceNamespace, resourceId, scalableDimension) + "::" + policyName;
    }

    Optional<ScalableTarget> findTarget(String region, String serviceNamespace, String resourceId,
                                        String scalableDimension) {
        return targets.get(targetKey(region, serviceNamespace, resourceId, scalableDimension));
    }
}
