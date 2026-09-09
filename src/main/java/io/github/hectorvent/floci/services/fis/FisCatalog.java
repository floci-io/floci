package io.github.hectorvent.floci.services.fis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static AWS FIS action and target-resource-type catalog. */
@ApplicationScoped
public class FisCatalog {

    private static final String WAIT_DESCRIPTION = "Runs the AWS FIS wait action.";
    private static final String STOP_INSTANCES_DESCRIPTION =
            "Runs the Amazon EC2 API action StopInstances on the target EC2 instances.";
    private static final String DURATION_DESCRIPTION = "The action duration in ISO 8601 format.";
    private static final String KUBERNETES_SERVICE_ACCOUNT_DESCRIPTION =
            "The Kubernetes service account used by the fault injection pod.";

    private static final Map<String, ActionDefinition> ACTIONS = buildActions();
    private static final Map<String, TargetResourceTypeDefinition> RESOURCE_TYPES = buildResourceTypes();

    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;

    @Inject
    public FisCatalog(ObjectMapper mapper, RegionResolver regionResolver) {
        this.mapper = mapper;
        this.regionResolver = regionResolver;
    }

    public boolean containsAction(String actionId) {
        return actionId != null && ACTIONS.containsKey(actionId);
    }

    public Set<String> actionIds() {
        return ACTIONS.keySet();
    }

    public ObjectNode action(String region, String actionId) {
        ActionDefinition definition = ACTIONS.get(actionId);
        if (definition == null) {
            return null;
        }
        ObjectNode action = actionSummary(region, actionId, definition);
        action.set("parameters", parameterNodes(definition.parameters()));
        return action;
    }

    public List<ObjectNode> actionSummaries(String region) {
        List<ObjectNode> result = new ArrayList<>(ACTIONS.size());
        ACTIONS.forEach((id, definition) -> result.add(actionSummary(region, id, definition)));
        return result;
    }

    public boolean containsTargetResourceType(String resourceType) {
        return resourceType != null && RESOURCE_TYPES.containsKey(resourceType);
    }

    public ObjectNode targetResourceType(String resourceType) {
        TargetResourceTypeDefinition definition = RESOURCE_TYPES.get(resourceType);
        if (definition == null) {
            return null;
        }
        ObjectNode node = targetResourceTypeSummary(resourceType, definition.description());
        node.set("parameters", parameterNodes(definition.parameters()));
        return node;
    }

    public List<ObjectNode> targetResourceTypeSummaries() {
        List<ObjectNode> result = new ArrayList<>(RESOURCE_TYPES.size());
        RESOURCE_TYPES.forEach((resourceType, definition) ->
                result.add(targetResourceTypeSummary(resourceType, definition.description())));
        return result;
    }

    private ObjectNode parameterNodes(Map<String, ParameterDefinition> definitions) {
        ObjectNode parameters = mapper.createObjectNode();
        definitions.forEach((name, parameter) -> {
            ObjectNode value = parameters.putObject(name);
            value.put("description", parameter.description());
            value.put("required", parameter.required());
        });
        return parameters;
    }

    private ObjectNode actionSummary(String region, String actionId, ActionDefinition definition) {
        ObjectNode action = mapper.createObjectNode();
        action.put("id", actionId);
        action.put("arn", regionResolver.buildArn("fis", region, "action/" + actionId));
        action.put("description", definition.description());
        ObjectNode targets = action.putObject("targets");
        definition.targets().forEach((name, resourceType) ->
                targets.putObject(name).put("resourceType", resourceType));
        action.putObject("tags");
        return action;
    }

    private ObjectNode targetResourceTypeSummary(String resourceType, String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("resourceType", resourceType);
        node.put("description", description);
        return node;
    }

    private static Map<String, ActionDefinition> buildActions() {
        Map<String, ActionDefinition> actions = new LinkedHashMap<>();

        Map<String, ParameterDefinition> injectApiParameters = parameters(
                required("duration", DURATION_DESCRIPTION),
                required("service", "The target AWS API namespace."),
                required("percentage", "The percentage of API calls to impair."),
                required("operations", "The comma-separated API operations to impair."));
        add(actions, "aws:fis:inject-api-internal-error",
                "Injects internal errors into supported AWS API calls.",
                target("Roles", "aws:iam:role"), injectApiParameters);
        add(actions, "aws:fis:inject-api-throttle-error",
                "Injects throttling errors into supported AWS API calls.",
                target("Roles", "aws:iam:role"), injectApiParameters);
        add(actions, "aws:fis:inject-api-unavailable-error",
                "Injects unavailable errors into supported AWS API calls.",
                target("Roles", "aws:iam:role"), injectApiParameters);
        add(actions, "aws:arc:start-zonal-autoshift",
                "Starts an ARC zonal autoshift for the target managed resources.",
                target("ManagedResources", "aws:arc:zonal-shift-managed-resource"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        required("availabilityZoneIdentifier", "The Availability Zone to shift traffic away from."),
                        optional("managedResourceTypes", "The comma-separated managed resource types to shift."),
                        optional("zonalAutoshiftStatus", "The zonal autoshift status used to select resources.")));
        add(actions, "aws:fis:wait", WAIT_DESCRIPTION, Map.of(),
                parameters(required("duration",
                        "The duration, from one minute to 12 hours, in ISO 8601 format.")));
        add(actions, "aws:cloudwatch:assert-alarm-state",
                "Verifies that the specified alarms are in the specified alarm states.", Map.of(), parameters(
                        required("alarmArns", "The comma-separated ARNs of up to five CloudWatch alarms."),
                        required("alarmStates", "The comma-separated alarm states to assert.")));
        add(actions, "aws:dynamodb:global-table-pause-replication",
                "Pauses replication for the target DynamoDB global table.",
                target("Tables", "aws:dynamodb:global-table"), durationParameters());
        add(actions, "aws:dsql:cluster-connection-failure",
                "Injects connection failures into the target Amazon Aurora DSQL clusters.",
                target("Clusters", "aws:dsql:cluster"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        required("percentage", "The percentage of calls to impair.")));
        add(actions, "aws:ebs:pause-volume-io", "Pauses I/O operations on the target EBS volumes.",
                target("Volumes", "aws:ec2:ebs-volume"), durationParameters());
        add(actions, "aws:ebs:volume-io-latency", "Injects I/O latency into the target EBS volumes.",
                target("Volumes", "aws:ec2:ebs-volume"), parameters(
                        optional("readIOPercentage", "The percentage of read I/O operations to impair."),
                        optional("readIOLatencyMilliseconds", "The latency added to read I/O operations."),
                        optional("writeIOPercentage", "The percentage of write I/O operations to impair."),
                        optional("writeIOLatencyMilliseconds", "The latency added to write I/O operations."),
                        required("duration", DURATION_DESCRIPTION)));
        add(actions, "aws:ec2:api-insufficient-instance-capacity-error",
                "Injects insufficient instance capacity errors into EC2 API calls.",
                target("Roles", "aws:iam:role"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        required("availabilityZoneIdentifiers", "The comma-separated Availability Zones to impair."),
                        required("percentage", "The percentage of calls to impair.")));
        add(actions, "aws:ec2:asg-insufficient-instance-capacity-error",
                "Injects insufficient instance capacity errors for target Auto Scaling groups.",
                target("AutoScalingGroups", "aws:ec2:autoscaling-group"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        required("availabilityZoneIdentifiers", "The comma-separated Availability Zones to impair."),
                        optional("percentage", "The percentage of launch requests to impair.")));
        add(actions, "aws:ec2:reboot-instances",
                "Runs the Amazon EC2 API action RebootInstances on the target EC2 instances.",
                target("Instances", "aws:ec2:instance"), Map.of());
        add(actions, "aws:ec2:send-spot-instance-interruptions",
                "Sends Spot Instance interruption notices to the target EC2 Spot Instances.",
                target("SpotInstances", "aws:ec2:spot-instance"), parameters(
                        required("durationBeforeInterruption", "The delay before interruption in ISO 8601 format.")));
        add(actions, "aws:ec2:stop-instances", STOP_INSTANCES_DESCRIPTION,
                target("Instances", "aws:ec2:instance"), parameters(
                        optional("startInstancesAfterDuration", "The delay before restarting the instances."),
                        optional("completeIfInstancesTerminated",
                                "Whether the action completes if target instances were terminated.")));
        add(actions, "aws:ec2:terminate-instances",
                "Runs the Amazon EC2 API action TerminateInstances on the target EC2 instances.",
                target("Instances", "aws:ec2:instance"), Map.of());
        add(actions, "aws:ecs:drain-container-instances",
                "Drains container instances in the target ECS clusters.",
                target("Clusters", "aws:ecs:cluster"), parameters(
                        required("drainagePercentage", "The percentage of container instances to drain."),
                        required("duration", DURATION_DESCRIPTION)));
        add(actions, "aws:ecs:stop-task", "Stops the target Amazon ECS tasks.",
                target("Tasks", "aws:ecs:task"), Map.of());
        add(actions, "aws:ecs:task-cpu-stress", "Runs CPU stress on the target Amazon ECS tasks.",
                target("Tasks", "aws:ecs:task"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        optional("percent", "The target CPU load percentage."),
                        optional("workers", "The number of CPU stress workers."),
                        optional("installDependencies", "Whether to install required dependencies.")));
        add(actions, "aws:ecs:task-io-stress", "Runs I/O stress on the target Amazon ECS tasks.",
                target("Tasks", "aws:ecs:task"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        optional("percent", "The percentage of free file-system space to use."),
                        optional("workers", "The number of I/O stress workers."),
                        optional("installDependencies", "Whether to install required dependencies.")));
        add(actions, "aws:ecs:task-kill-process", "Kills a process in the target Amazon ECS tasks.",
                target("Tasks", "aws:ecs:task"), parameters(
                        required("processName", "The process name to stop."),
                        optional("signal", "The signal to send to the process."),
                        optional("installDependencies", "Whether to install required dependencies.")));
        add(actions, "aws:ecs:task-network-blackhole-port",
                "Drops network traffic on a port in the target Amazon ECS tasks.",
                target("Tasks", "aws:ecs:task"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        required("port", "The port number to impair."),
                        required("trafficType", "The ingress or egress traffic direction."),
                        optional("protocol", "The tcp or udp protocol."),
                        optional("installDependencies", "Whether to install required dependencies."),
                        optional("useEcsFaultInjectionEndpoints", "Whether to use ECS fault injection endpoints.")));
        add(actions, "aws:ecs:task-network-latency",
                "Injects network latency into the target Amazon ECS tasks.",
                target("Tasks", "aws:ecs:task"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        optional("delayMilliseconds", "The network delay in milliseconds."),
                        optional("jitterMilliseconds", "The network jitter in milliseconds."),
                        optional("flowsPercent", "The percentage of network flows to impair."),
                        optional("sources", "The comma-separated traffic sources to impair."),
                        optional("installDependencies", "Whether to install required dependencies."),
                        optional("useEcsFaultInjectionEndpoints", "Whether to use ECS fault injection endpoints.")));
        add(actions, "aws:ecs:task-network-packet-loss",
                "Injects network packet loss into the target Amazon ECS tasks.",
                target("Tasks", "aws:ecs:task"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        optional("lossPercent", "The packet loss percentage."),
                        optional("flowsPercent", "The percentage of network flows to impair."),
                        optional("sources", "The comma-separated traffic sources to impair."),
                        optional("installDependencies", "Whether to install required dependencies."),
                        optional("useEcsFaultInjectionEndpoints", "Whether to use ECS fault injection endpoints.")));
        add(actions, "aws:eks:inject-kubernetes-custom-resource",
                "Injects a Kubernetes custom resource into the target EKS cluster.",
                target("Cluster", "aws:eks:cluster"), parameters(
                        required("kubernetesApiVersion", "The Kubernetes custom resource API version."),
                        required("kubernetesKind", "The Kubernetes custom resource kind."),
                        required("kubernetesNamespace", "The Kubernetes namespace."),
                        required("kubernetesSpec", "The custom resource spec in JSON format."),
                        required("maxDuration", "The maximum automation duration in ISO 8601 format.")));
        add(actions, "aws:eks:pod-cpu-stress", "Runs CPU stress on the target Kubernetes pods.",
                target("Pods", "aws:eks:pod"), eksPodParameters(true,
                        required("duration", DURATION_DESCRIPTION),
                        optional("percent", "The target CPU load percentage."),
                        optional("workers", "The number of CPU stress workers.")));
        add(actions, "aws:eks:pod-delete", "Deletes the target Kubernetes pods.",
                target("Pods", "aws:eks:pod"), eksPodParameters(true,
                        optional("gracePeriodSeconds", "The graceful pod termination period in seconds.")));
        add(actions, "aws:eks:pod-io-stress", "Runs I/O stress on the target Kubernetes pods.",
                target("Pods", "aws:eks:pod"), eksPodParameters(true,
                        required("duration", DURATION_DESCRIPTION),
                        optional("workers", "The number of I/O stress workers."),
                        optional("percent", "The percentage of free file-system space to use.")));
        add(actions, "aws:eks:pod-memory-stress", "Runs memory stress on the target Kubernetes pods.",
                target("Pods", "aws:eks:pod"), eksPodParameters(true,
                        required("duration", DURATION_DESCRIPTION),
                        optional("workers", "The number of memory stress workers."),
                        optional("percent", "The percentage of virtual memory to use.")));
        add(actions, "aws:eks:pod-network-blackhole-port",
                "Drops network traffic on a port in the target Kubernetes pods.",
                target("Pods", "aws:eks:pod"), eksPodParameters(false,
                        required("duration", DURATION_DESCRIPTION),
                        required("protocol", "The tcp or udp protocol."),
                        required("trafficType", "The ingress or egress traffic direction."),
                        required("port", "The port number to impair.")));
        add(actions, "aws:eks:pod-network-latency",
                "Injects network latency into the target Kubernetes pods.",
                target("Pods", "aws:eks:pod"), eksPodParameters(false,
                        required("duration", DURATION_DESCRIPTION),
                        optional("interface", "The network interfaces to impair."),
                        optional("delayMilliseconds", "The network delay in milliseconds."),
                        optional("jitterMilliseconds", "The network jitter in milliseconds."),
                        optional("flowsPercent", "The percentage of network flows to impair."),
                        optional("sources", "The comma-separated traffic sources to impair.")));
        add(actions, "aws:eks:pod-network-packet-loss",
                "Injects network packet loss into the target Kubernetes pods.",
                target("Pods", "aws:eks:pod"), eksPodParameters(false,
                        required("duration", DURATION_DESCRIPTION),
                        optional("interface", "The network interfaces to impair."),
                        optional("lossPercent", "The packet loss percentage."),
                        optional("flowsPercent", "The percentage of network flows to impair."),
                        optional("sources", "The comma-separated traffic sources to impair.")));
        add(actions, "aws:eks:terminate-nodegroup-instances",
                "Terminates instances in the target Amazon EKS node groups.",
                target("Nodegroups", "aws:eks:nodegroup"), parameters(
                        required("instanceTerminationPercentage", "The percentage of instances to terminate.")));
        add(actions, "aws:elasticache:replicationgroup-interrupt-az-power",
                "Interrupts Availability Zone power for the target ElastiCache replication groups.",
                target("ReplicationGroups", "aws:elasticache:replicationgroup"), durationParameters());
        add(actions, "aws:kinesis:stream-provisioned-throughput-exception",
                "Injects ProvisionedThroughputExceededException errors into target Kinesis streams.",
                target("KinesisStreams", "aws:kinesis:stream"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        required("percentage", "The percentage of calls to impair.")));
        add(actions, "aws:kinesis:stream-expired-iterator-exception",
                "Injects ExpiredIteratorException errors into target Kinesis streams.",
                target("KinesisStreams", "aws:kinesis:stream"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        required("percentage", "The percentage of calls to impair.")));
        add(actions, "aws:lambda:invocation-add-delay",
                "Adds a delay to invocations of the target Lambda functions.",
                target("Functions", "aws:lambda:function"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        optional("invocationPercentage", "The percentage of function invocations to impair."),
                        optional("startupDelayMilliseconds", "The invocation delay in milliseconds.")));
        add(actions, "aws:lambda:invocation-error",
                "Injects errors into invocations of the target Lambda functions.",
                target("Functions", "aws:lambda:function"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        optional("invocationPercentage", "The percentage of function invocations to impair."),
                        required("preventExecution", "Whether to return the error without executing the function.")));
        add(actions, "aws:lambda:invocation-http-integration-response",
                "Injects HTTP integration responses into invocations of the target Lambda functions.",
                target("Functions", "aws:lambda:function"), parameters(
                        required("contentTypeHeader", "The HTTP content type header to return."),
                        required("duration", DURATION_DESCRIPTION),
                        optional("invocationPercentage", "The percentage of function invocations to impair."),
                        required("preventExecution", "Whether to return without executing the function."),
                        required("statusCode", "The HTTP status code to return.")));
        add(actions, "aws:memorydb:multi-region-cluster-pause-replication",
                "Pauses replication for the target MemoryDB multi-Region clusters.",
                target("MultiRegionClusters", "aws:memorydb:multi-region-cluster"), durationParameters());
        add(actions, "aws:network:disrupt-connectivity", "Disrupts network connectivity for the target subnets.",
                target("Subnets", "aws:ec2:subnet"), parameters(
                        required("scope", "The type of traffic to deny."),
                        required("duration", DURATION_DESCRIPTION),
                        optional("prefixListIdentifier", "The prefix list used when scope is prefix-list.")));
        add(actions, "aws:network:route-table-disrupt-cross-region-connectivity",
                "Disrupts cross-Region connectivity for the target subnets using route tables.",
                target("Subnets", "aws:ec2:subnet"), parameters(
                        required("region", "The Region to isolate."),
                        required("duration", DURATION_DESCRIPTION)));
        add(actions, "aws:network:transit-gateway-disrupt-cross-region-connectivity",
                "Disrupts cross-Region connectivity for the target transit gateways.",
                target("TransitGateways", "aws:ec2:transit-gateway"), parameters(
                        required("region", "The Region to isolate."),
                        required("duration", DURATION_DESCRIPTION)));
        add(actions, "aws:network:disrupt-vpc-endpoint",
                "Disrupts connectivity for the target VPC endpoints.",
                target("VPCEndpoints", "aws:ec2:vpc-endpoint"), durationParameters());
        add(actions, "aws:rds:failover-db-cluster", "Fails over the target Amazon RDS DB clusters.",
                target("Clusters", "aws:rds:cluster"), Map.of());
        add(actions, "aws:rds:reboot-db-instances", "Reboots the target Amazon RDS DB instances.",
                target("DBInstances", "aws:rds:db"), parameters(
                        optional("forceFailover", "Whether to force a Multi-AZ failover.")));
        add(actions, "aws:s3:bucket-pause-replication",
                "Pauses replication for the target Amazon S3 buckets.",
                target("Buckets", "aws:s3:bucket"), parameters(
                        required("duration", DURATION_DESCRIPTION),
                        required("region", "The Region containing destination buckets."),
                        optional("destinationBuckets", "The comma-separated destination bucket names."),
                        optional("prefixes", "The comma-separated replication rule prefixes.")));
        add(actions, "aws:ssm:send-command", "Runs an Amazon Systems Manager command on the target resources.",
                target("Instances", "aws:ec2:instance"), parameters(
                        required("documentArn", "The ARN of the Systems Manager document to run."),
                        optional("documentVersion", "The Systems Manager document version."),
                        optional("documentParameters", "The parameters accepted by the document."),
                        required("duration", DURATION_DESCRIPTION)));
        add(actions, "aws:ssm:start-automation-execution",
                "Starts an Amazon Systems Manager automation execution.", Map.of(), parameters(
                        required("documentArn", "The ARN of the Systems Manager automation document to run."),
                        optional("documentVersion", "The Systems Manager automation document version."),
                        optional("documentParameters", "The parameters accepted by the document."),
                        required("maxDuration", "The maximum automation duration in ISO 8601 format.")));
        add(actions, "aws:directconnect:virtual-interface-disconnect",
                "Disconnects the target Direct Connect virtual interfaces.",
                target("VirtualInterfaces", "aws:directconnect:virtual-interface"), durationParameters());

        if (actions.size() != 50) {
            throw new IllegalStateException("The AWS FIS action catalog must contain exactly 50 actions");
        }
        return Collections.unmodifiableMap(actions);
    }

    private static Map<String, TargetResourceTypeDefinition> buildResourceTypes() {
        Map<String, TargetResourceTypeDefinition> types = new LinkedHashMap<>();
        resourceType(types, "aws:arc:zonal-shift-managed-resource", "An ARC zonal shift managed resource.");
        resourceType(types, "aws:directconnect:virtual-interface", "A Direct Connect virtual interface.");
        resourceType(types, "aws:dsql:cluster", "An Amazon Aurora DSQL cluster.");
        resourceType(types, "aws:dynamodb:global-table", "A DynamoDB multi-Region global table.");
        resourceType(types, "aws:ec2:autoscaling-group", "An EC2 Auto Scaling group.");
        resourceType(types, "aws:ec2:ebs-volume", "An Amazon EBS volume.", parameters(
                optional("availabilityZoneIdentifier", "The Availability Zone containing the target volumes.")));
        resourceType(types, "aws:ec2:instance", "An Amazon EC2 instance.");
        resourceType(types, "aws:ec2:spot-instance", "An Amazon EC2 Spot Instance.");
        resourceType(types, "aws:ec2:subnet", "An Amazon VPC subnet.", parameters(
                optional("availabilityZoneIdentifier", "The Availability Zone containing the target subnets."),
                optional("vpc", "The VPC containing the target subnets.")));
        resourceType(types, "aws:ec2:transit-gateway", "An Amazon VPC transit gateway.");
        resourceType(types, "aws:ec2:vpc-endpoint", "An Amazon VPC endpoint.");
        resourceType(types, "aws:ecs:cluster", "An Amazon ECS cluster.");
        resourceType(types, "aws:ecs:task", "An Amazon ECS task.", parameters(
                optional("cluster", "The cluster containing the target tasks."),
                optional("service", "The service containing the target tasks.")));
        resourceType(types, "aws:eks:cluster", "An Amazon EKS cluster.");
        resourceType(types, "aws:eks:nodegroup", "An Amazon EKS node group.");
        resourceType(types, "aws:eks:pod", "A Kubernetes pod.", parameters(
                optional("availabilityZoneIdentifier", "The Availability Zone containing the target pods."),
                required("clusterIdentifier", "The name or ARN of the target EKS cluster."),
                required("namespace", "The Kubernetes namespace of the target pods."),
                required("selectorType", "The selector type used to identify target pods."),
                required("selectorValue", "The selector value used to identify target pods."),
                optional("targetContainerName", "The target container name from the pod spec.")));
        resourceType(types, "aws:elasticache:replicationgroup", "An ElastiCache replication group.", parameters(
                required("availabilityZoneIdentifier", "The Availability Zone containing the target nodes.")));
        resourceType(types, "aws:iam:role", "An IAM role.");
        resourceType(types, "aws:kinesis:stream", "An Amazon Kinesis data stream.");
        resourceType(types, "aws:lambda:function", "An AWS Lambda function.", parameters(
                optional("functionQualifier", "The version or alias of the function to target.")));
        resourceType(types, "aws:memorydb:multi-region-cluster", "A MemoryDB multi-Region cluster.");
        resourceType(types, "aws:rds:cluster", "An Amazon Aurora DB cluster.", parameters(
                optional("writerAvailabilityZoneIdentifiers", "The Availability Zones of the DB cluster writer.")));
        resourceType(types, "aws:rds:db", "An Amazon RDS DB instance.", parameters(
                optional("availabilityZoneIdentifiers", "The Availability Zones of the DB instance.")));
        resourceType(types, "aws:s3:bucket", "An Amazon S3 bucket.");
        return Collections.unmodifiableMap(types);
    }

    private static Map<String, ParameterDefinition> eksPodParameters(
            boolean includeSecurityPolicy, NamedParameter... actionParameters) {
        List<NamedParameter> definitions = new ArrayList<>(List.of(actionParameters));
        definitions.add(required("kubernetesServiceAccount", KUBERNETES_SERVICE_ACCOUNT_DESCRIPTION));
        definitions.add(optional("fisPodContainerImage", "The fault injector pod container image."));
        definitions.add(optional("maxErrorsPercent", "The percentage of targets that may fail."));
        definitions.add(optional("fisPodLabels", "Labels applied to the fault orchestration pod."));
        definitions.add(optional("fisPodAnnotations", "Annotations applied to the fault orchestration pod."));
        if (includeSecurityPolicy) {
            definitions.add(optional("fisPodSecurityPolicy",
                    "The Kubernetes security policy for the fault orchestration pod."));
        }
        return parameters(definitions.toArray(NamedParameter[]::new));
    }

    private static void resourceType(Map<String, TargetResourceTypeDefinition> types,
                                     String resourceType, String description) {
        resourceType(types, resourceType, description, Map.of());
    }

    private static void resourceType(Map<String, TargetResourceTypeDefinition> types,
                                     String resourceType, String description,
                                     Map<String, ParameterDefinition> parameters) {
        types.put(resourceType, new TargetResourceTypeDefinition(description, parameters));
    }

    private static void add(Map<String, ActionDefinition> actions, String id, String description,
                            Map<String, String> targets, Map<String, ParameterDefinition> parameters) {
        actions.put(id, new ActionDefinition(description, targets, parameters));
    }

    private static Map<String, String> target(String name, String resourceType) {
        return Map.of(name, resourceType);
    }

    private static Map<String, ParameterDefinition> durationParameters() {
        return parameters(required("duration", DURATION_DESCRIPTION));
    }

    private static Map<String, ParameterDefinition> parameters(NamedParameter... definitions) {
        Map<String, ParameterDefinition> parameters = new LinkedHashMap<>();
        for (NamedParameter definition : definitions) {
            parameters.put(definition.name(),
                    new ParameterDefinition(definition.description(), definition.required()));
        }
        return Collections.unmodifiableMap(parameters);
    }

    private static NamedParameter required(String name, String description) {
        return new NamedParameter(name, description, true);
    }

    private static NamedParameter optional(String name, String description) {
        return new NamedParameter(name, description, false);
    }

    private record ActionDefinition(String description, Map<String, String> targets,
                                    Map<String, ParameterDefinition> parameters) {}

    private record TargetResourceTypeDefinition(String description,
                                                Map<String, ParameterDefinition> parameters) {}

    private record NamedParameter(String name, String description, boolean required) {}

    private record ParameterDefinition(String description, boolean required) {}
}
