package io.github.hectorvent.floci.services.apprunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerServiceModel;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerVpcIngressConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App Runner management plane.
 *
 * <p>Operations that are not implemented fall through to a clean
 * {@code UnknownOperationException} rather than a stub success, so callers fail fast
 * instead of stranding a waiter.
 */
@ApplicationScoped
public class AppRunnerJsonHandler {

    private final AppRunnerService service;
    private final ObjectMapper mapper;

    @Inject
    public AppRunnerJsonHandler(AppRunnerService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateAutoScalingConfiguration" -> Response.ok(Map.of("AutoScalingConfiguration",
                    service.createAutoScalingConfiguration(
                            text(request, "AutoScalingConfigurationName"),
                            integer(request, "MaxConcurrency"),
                            integer(request, "MinSize"),
                            integer(request, "MaxSize"),
                            tagMap(request.path("Tags")),
                            region))).build();
            case "DescribeAutoScalingConfiguration" -> Response.ok(Map.of("AutoScalingConfiguration",
                    service.describeAutoScalingConfiguration(
                            text(request, "AutoScalingConfigurationArn"), region))).build();
            case "DeleteAutoScalingConfiguration" -> Response.ok(Map.of("AutoScalingConfiguration",
                    service.deleteAutoScalingConfiguration(
                            text(request, "AutoScalingConfigurationArn"),
                            Boolean.TRUE.equals(bool(request, "DeleteAllRevisions")),
                            region))).build();
            case "ListAutoScalingConfigurations" -> Response.ok(Map.of("AutoScalingConfigurationSummaryList",
                    service.listAutoScalingConfigurations(
                            text(request, "AutoScalingConfigurationName"),
                            bool(request, "LatestOnly"),
                            region))).build();

            case "CreateObservabilityConfiguration" -> Response.ok(Map.of("ObservabilityConfiguration",
                    service.createObservabilityConfiguration(
                            text(request, "ObservabilityConfigurationName"),
                            nodeOrNull(request, "TraceConfiguration"),
                            tagMap(request.path("Tags")),
                            region))).build();
            case "DescribeObservabilityConfiguration" -> Response.ok(Map.of("ObservabilityConfiguration",
                    service.describeObservabilityConfiguration(
                            text(request, "ObservabilityConfigurationArn"), region))).build();
            case "DeleteObservabilityConfiguration" -> Response.ok(Map.of("ObservabilityConfiguration",
                    service.deleteObservabilityConfiguration(
                            text(request, "ObservabilityConfigurationArn"), region))).build();
            case "ListObservabilityConfigurations" -> Response.ok(Map.of("ObservabilityConfigurationSummaryList",
                    service.listObservabilityConfigurations(
                            text(request, "ObservabilityConfigurationName"),
                            bool(request, "LatestOnly"),
                            region))).build();

            case "CreateVpcIngressConnection" -> {
                AppRunnerVpcIngressConnection created = service.createVpcIngressConnection(
                        text(request, "VpcIngressConnectionName"),
                        text(request, "ServiceArn"),
                        nodeOrNull(request, "IngressVpcConfiguration"),
                        tagMap(request.path("Tags")),
                        region);
                yield Response.ok(Map.of(
                        "VpcIngressConnection", created,
                        "OperationId", service.recordOperation("CREATE_VPC_INGRESS_CONNECTION",
                                created.getVpcIngressConnectionArn()).getId()))
                        .build();
            }
            case "DescribeVpcIngressConnection" -> Response.ok(Map.of("VpcIngressConnection",
                    service.describeVpcIngressConnection(text(request, "VpcIngressConnectionArn")))).build();
            case "DeleteVpcIngressConnection" -> {
                AppRunnerVpcIngressConnection deleted =
                        service.deleteVpcIngressConnection(text(request, "VpcIngressConnectionArn"));
                yield Response.ok(Map.of(
                        "VpcIngressConnection", deleted,
                        "OperationId", service.recordOperation("DELETE_VPC_INGRESS_CONNECTION",
                                deleted.getVpcIngressConnectionArn()).getId()))
                        .build();
            }
            case "ListVpcIngressConnections" -> Response.ok(Map.of("VpcIngressConnectionSummaryList",
                    vpcIngressConnectionSummaries(
                            service.listVpcIngressConnections(text(request, "ServiceArn"), region)))).build();

            case "CreateVpcConnector" -> Response.ok(Map.of("VpcConnector", service.createVpcConnector(
                    text(request, "VpcConnectorName"),
                    stringList(request.path("Subnets")),
                    stringList(request.path("SecurityGroups")),
                    tagMap(request.path("Tags")),
                    region))).build();
            case "DescribeVpcConnector" -> Response.ok(Map.of("VpcConnector",
                    service.describeVpcConnector(text(request, "VpcConnectorArn")))).build();
            case "DeleteVpcConnector" -> Response.ok(Map.of("VpcConnector",
                    service.deleteVpcConnector(text(request, "VpcConnectorArn")))).build();
            case "ListVpcConnectors" -> Response.ok(Map.of("VpcConnectors",
                    service.listVpcConnectors(region))).build();

            case "CreateConnection" -> Response.ok(Map.of("Connection", service.createConnection(
                    text(request, "ConnectionName"),
                    text(request, "ProviderType"),
                    tagMap(request.path("Tags")),
                    region))).build();
            case "DeleteConnection" -> Response.ok(Map.of("Connection",
                    service.deleteConnection(text(request, "ConnectionArn")))).build();
            case "ListConnections" -> Response.ok(Map.of("ConnectionSummaryList",
                    service.listConnections(text(request, "ConnectionName"), region))).build();

            case "CreateService" -> {
                AppRunnerServiceModel created = service.createService(
                        text(request, "ServiceName"),
                        nodeOrNull(request, "SourceConfiguration"),
                        nodeOrNull(request, "InstanceConfiguration"),
                        nodeOrNull(request, "EncryptionConfiguration"),
                        nodeOrNull(request, "HealthCheckConfiguration"),
                        text(request, "AutoScalingConfigurationArn"),
                        nodeOrNull(request, "NetworkConfiguration"),
                        nodeOrNull(request, "ObservabilityConfiguration"),
                        tagMap(request.path("Tags")),
                        region);
                yield Response.ok(Map.of(
                        "Service", created,
                        "OperationId", service.recordOperation("CREATE_SERVICE", created.getServiceArn()).getId()))
                        .build();
            }
            case "DescribeService" -> Response.ok(Map.of("Service",
                    service.describeService(text(request, "ServiceArn")))).build();
            case "UpdateService" -> {
                AppRunnerServiceModel updated = service.updateService(
                        text(request, "ServiceArn"),
                        nodeOrNull(request, "SourceConfiguration"),
                        nodeOrNull(request, "InstanceConfiguration"),
                        text(request, "AutoScalingConfigurationArn"),
                        nodeOrNull(request, "HealthCheckConfiguration"),
                        nodeOrNull(request, "NetworkConfiguration"),
                        nodeOrNull(request, "ObservabilityConfiguration"),
                        region);
                yield Response.ok(Map.of(
                        "Service", updated,
                        "OperationId", service.recordOperation("UPDATE_SERVICE", updated.getServiceArn()).getId()))
                        .build();
            }
            case "DeleteService" -> {
                AppRunnerServiceModel deleted = service.deleteService(text(request, "ServiceArn"));
                yield Response.ok(Map.of(
                        "Service", deleted,
                        "OperationId", service.recordOperation("DELETE_SERVICE", deleted.getServiceArn()).getId()))
                        .build();
            }
            case "ListServices" -> Response.ok(Map.of("ServiceSummaryList",
                    serviceSummaries(service.listServices(region)))).build();
            case "PauseService" -> {
                AppRunnerServiceModel paused = service.pauseService(text(request, "ServiceArn"));
                yield Response.ok(Map.of(
                        "Service", paused,
                        "OperationId", service.recordOperation("PAUSE_SERVICE", paused.getServiceArn()).getId()))
                        .build();
            }
            case "ResumeService" -> {
                AppRunnerServiceModel resumed = service.resumeService(text(request, "ServiceArn"));
                yield Response.ok(Map.of(
                        "Service", resumed,
                        "OperationId", service.recordOperation("RESUME_SERVICE", resumed.getServiceArn()).getId()))
                        .build();
            }
            case "StartDeployment" -> {
                AppRunnerServiceModel deployed = service.startDeployment(text(request, "ServiceArn"));
                yield Response.ok(Map.of("OperationId",
                        service.recordOperation("START_DEPLOYMENT", deployed.getServiceArn()).getId())).build();
            }
            case "ListOperations" -> Response.ok(Map.of("OperationSummaryList",
                    service.listOperations(text(request, "ServiceArn")))).build();

            case "TagResource" -> {
                service.tagResource(text(request, "ResourceArn"), tagMap(request.path("Tags")));
                yield Response.ok(Map.of()).build();
            }
            case "UntagResource" -> {
                service.untagResource(text(request, "ResourceArn"), stringList(request.path("TagKeys")));
                yield Response.ok(Map.of()).build();
            }
            case "ListTagsForResource" -> Response.ok(Map.of("Tags",
                    tagList(service.listTagsForResource(text(request, "ResourceArn"))))).build();

            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported by floci", 400);
        };
    }

    private ArrayNode vpcIngressConnectionSummaries(List<AppRunnerVpcIngressConnection> connections) {
        ArrayNode summaries = mapper.createArrayNode();
        for (AppRunnerVpcIngressConnection connection : connections) {
            ObjectNode summary = summaries.addObject();
            summary.put("VpcIngressConnectionArn", connection.getVpcIngressConnectionArn());
            summary.put("ServiceArn", connection.getServiceArn());
        }
        return summaries;
    }

    private ArrayNode serviceSummaries(List<AppRunnerServiceModel> services) {
        ArrayNode summaries = mapper.createArrayNode();
        for (AppRunnerServiceModel service : services) {
            ObjectNode summary = summaries.addObject();
            summary.put("ServiceName", service.getServiceName());
            summary.put("ServiceId", service.getServiceId());
            summary.put("ServiceArn", service.getServiceArn());
            summary.put("ServiceUrl", service.getServiceUrl());
            summary.put("CreatedAt", service.getCreatedAt());
            summary.put("UpdatedAt", service.getUpdatedAt());
            summary.put("Status", service.getStatus());
        }
        return summaries;
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static Boolean bool(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asBoolean();
    }

    private static Integer integer(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private static JsonNode nodeOrNull(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        node.forEach(element -> values.add(element.asText()));
        return values;
    }

    private static Map<String, String> tagMap(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : node) {
            String key = tag.path("Key").asText(null);
            if (key == null) {
                throw new AwsException("InvalidRequestException", "Every tag must carry a Key.", 400);
            }
            tags.put(key, tag.path("Value").asText(""));
        }
        return tags;
    }

    private ArrayNode tagList(Map<String, String> tags) {
        ArrayNode list = mapper.createArrayNode();
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
        return list;
    }
}
