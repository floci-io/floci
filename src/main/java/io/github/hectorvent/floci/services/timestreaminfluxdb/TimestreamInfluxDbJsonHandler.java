package io.github.hectorvent.floci.services.timestreaminfluxdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.timestreaminfluxdb.TimestreamInfluxDbService.Reported;
import io.github.hectorvent.floci.services.timestreaminfluxdb.TimestreamInfluxDbService.RestoreResult;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbBackup;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbCluster;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbInstance;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbParameterGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@ApplicationScoped
public class TimestreamInfluxDbJsonHandler {

    private final TimestreamInfluxDbService service;
    private final ObjectMapper objectMapper;

    @Inject
    public TimestreamInfluxDbJsonHandler(TimestreamInfluxDbService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateDbInstance" -> ok(reportedInstance(service.createDbInstance(request, region)));
            case "GetDbInstance" -> ok(instanceNode(service.getDbInstance(request, region), null));
            case "ListDbInstances" -> ok(page(service.listDbInstances(request, region), this::instanceSummary));
            case "UpdateDbInstance" -> ok(reportedInstance(service.updateDbInstance(request, region)));
            case "RebootDbInstance" -> ok(reportedInstance(service.rebootDbInstance(request, region)));
            case "DeleteDbInstance" -> ok(reportedInstance(service.deleteDbInstance(request, region)));
            case "CreateDbCluster" -> ok(clusterStatus(service.createDbCluster(request, region)));
            case "GetDbCluster" -> ok(clusterNode(service.getDbCluster(request, region)));
            case "ListDbClusters" -> ok(page(service.listDbClusters(request, region), this::clusterSummary));
            case "ListDbInstancesForCluster" -> ok(page(service.listDbInstancesForCluster(request, region),
                    this::clusterMemberSummary));
            case "UpdateDbCluster" -> ok(clusterStatusOnly(service.updateDbCluster(request, region)));
            case "RebootDbCluster" -> ok(clusterStatusOnly(service.rebootDbCluster(request, region)));
            case "DeleteDbCluster" -> ok(clusterStatusOnly(service.deleteDbCluster(request, region)));
            case "CreateDbParameterGroup" -> ok(parameterGroupNode(service.createDbParameterGroup(request, region)));
            case "GetDbParameterGroup" -> ok(parameterGroupNode(service.getDbParameterGroup(request, region)));
            case "ListDbParameterGroups" -> ok(page(service.listDbParameterGroups(request, region),
                    this::parameterGroupSummary));
            case "CreateDbBackup" -> ok(reportedBackup(service.createDbBackup(request, region)));
            case "GetDbBackup" -> ok(backupNode(service.getDbBackup(request, region), null));
            case "ListDbBackups" -> ok(page(service.listDbBackups(request, region), this::backupSummary));
            case "DeleteDbBackup" -> ok(reportedBackup(service.deleteDbBackup(request, region)));
            case "RestoreFromDbBackup" -> ok(restoreNode(service.restoreFromDbBackup(request, region)));
            case "ListTagsForResource" -> ok(tagsNode(service.listTagsForResource(request)));
            case "TagResource" -> {
                service.tagResource(request);
                yield ok(objectMapper.createObjectNode());
            }
            case "UntagResource" -> {
                service.untagResource(request);
                yield ok(objectMapper.createObjectNode());
            }
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse("AmazonTimestreamInfluxDB." + action);
        };
    }

    private ObjectNode reportedInstance(Reported<DbInstance> reported) {
        return instanceNode(reported.resource(), reported.status());
    }

    private ObjectNode reportedBackup(Reported<DbBackup> reported) {
        return backupNode(reported.resource(), reported.status());
    }

    private ObjectNode instanceNode(DbInstance instance, String reportedStatus) {
        String status = reportedStatus != null ? reportedStatus : instance.getStatus();
        ObjectNode node = instanceSummary(instance);
        node.put("status", status);
        putStrings(node, "vpcSubnetIds", instance.getVpcSubnetIds());
        put(node, "publiclyAccessible", instance.getPubliclyAccessible());
        putStrings(node, "vpcSecurityGroupIds", instance.getVpcSecurityGroupIds());
        put(node, "dbParameterGroupIdentifier", instance.getDbParameterGroupIdentifier());
        put(node, "availabilityZone", instance.getAvailabilityZone());
        put(node, "secondaryAvailabilityZone", instance.getSecondaryAvailabilityZone());
        putJson(node, "logDeliveryConfiguration", instance.getLogDeliveryConfiguration());
        if (!"CREATING".equals(status)) {
            put(node, "influxAuthParametersSecretArn", instance.getInfluxAuthParametersSecretArn());
        }
        put(node, "dbClusterId", instance.getDbClusterId());
        put(node, "instanceMode", instance.getInstanceMode());
        if (instance.getDbClusterId() != null) {
            putStrings(node, "instanceModes", instance.getInstanceModes());
        }
        putJson(node, "maintenanceSchedule", instance.getMaintenanceSchedule());
        putJson(node, "dbBackupConfigurations", instance.getDbBackupConfigurations());
        put(node, "kmsKeyId", instance.getKmsKeyId());
        return node;
    }

    private ObjectNode instanceSummary(DbInstance instance) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", instance.getId());
        node.put("name", instance.getName());
        node.put("arn", instance.getArn());
        put(node, "status", instance.getStatus());
        put(node, "endpoint", instance.getEndpoint());
        put(node, "port", instance.getPort());
        put(node, "networkType", instance.getNetworkType());
        put(node, "dbInstanceType", instance.getDbInstanceType());
        put(node, "dbStorageType", instance.getDbStorageType());
        put(node, "allocatedStorage", instance.getAllocatedStorage());
        put(node, "deploymentType", instance.getDeploymentType());
        return node;
    }

    private ObjectNode clusterMemberSummary(DbInstance instance) {
        ObjectNode node = instanceSummary(instance);
        put(node, "instanceMode", instance.getInstanceMode());
        putStrings(node, "instanceModes", instance.getInstanceModes());
        return node;
    }

    private ObjectNode clusterStatus(Reported<DbCluster> reported) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("dbClusterId", reported.resource().getId());
        node.put("dbClusterStatus", reported.status());
        return node;
    }

    private ObjectNode clusterStatusOnly(Reported<DbCluster> reported) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("dbClusterStatus", reported.status());
        return node;
    }

    private ObjectNode clusterNode(DbCluster cluster) {
        ObjectNode node = clusterSummary(cluster);
        put(node, "publiclyAccessible", cluster.getPubliclyAccessible());
        put(node, "dbParameterGroupIdentifier", cluster.getDbParameterGroupIdentifier());
        putJson(node, "logDeliveryConfiguration", cluster.getLogDeliveryConfiguration());
        putJson(node, "maintenanceSchedule", cluster.getMaintenanceSchedule());
        if (!"CREATING".equals(cluster.getStatus())) {
            put(node, "influxAuthParametersSecretArn", cluster.getInfluxAuthParametersSecretArn());
        }
        putStrings(node, "vpcSubnetIds", cluster.getVpcSubnetIds());
        putStrings(node, "vpcSecurityGroupIds", cluster.getVpcSecurityGroupIds());
        put(node, "failoverMode", cluster.getFailoverMode());
        putJson(node, "clusterConfiguration", cluster.getClusterConfiguration());
        putJson(node, "dbBackupConfigurations", cluster.getDbBackupConfigurations());
        put(node, "kmsKeyId", cluster.getKmsKeyId());
        return node;
    }

    private ObjectNode clusterSummary(DbCluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", cluster.getId());
        node.put("name", cluster.getName());
        node.put("arn", cluster.getArn());
        put(node, "status", cluster.getStatus());
        put(node, "endpoint", cluster.getEndpoint());
        put(node, "readerEndpoint", cluster.getReaderEndpoint());
        put(node, "port", cluster.getPort());
        put(node, "deploymentType", cluster.getDeploymentType());
        put(node, "dbInstanceType", cluster.getDbInstanceType());
        put(node, "networkType", cluster.getNetworkType());
        put(node, "dbStorageType", cluster.getDbStorageType());
        put(node, "allocatedStorage", cluster.getAllocatedStorage());
        put(node, "engineType", cluster.getEngineType());
        return node;
    }

    private ObjectNode parameterGroupNode(DbParameterGroup group) {
        ObjectNode node = parameterGroupSummary(group);
        putJson(node, "parameters", group.getParameters());
        return node;
    }

    private ObjectNode parameterGroupSummary(DbParameterGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", group.getId());
        node.put("name", group.getName());
        node.put("arn", group.getArn());
        put(node, "description", group.getDescription());
        return node;
    }

    private ObjectNode backupNode(DbBackup backup, String reportedStatus) {
        ObjectNode node = backupSummary(backup);
        if (reportedStatus != null) {
            node.put("status", reportedStatus);
        }
        putJson(node, "clusterConfiguration", backup.getClusterConfiguration());
        put(node, "dbParameterGroupId", backup.getDbParameterGroupId());
        put(node, "dbInstanceType", backup.getDbInstanceType());
        putJson(node, "logDeliveryConfiguration", backup.getLogDeliveryConfiguration());
        put(node, "failoverMode", backup.getFailoverMode());
        put(node, "dbStorageType", backup.getDbStorageType());
        put(node, "allocatedStorage", backup.getAllocatedStorage());
        putStrings(node, "vpcSubnetIds", backup.getVpcSubnetIds());
        putStrings(node, "vpcSecurityGroupIds", backup.getVpcSecurityGroupIds());
        put(node, "publiclyAccessible", backup.getPubliclyAccessible());
        put(node, "port", backup.getPort());
        put(node, "networkType", backup.getNetworkType());
        put(node, "influxAuthParametersSecretArn", backup.getInfluxAuthParametersSecretArn());
        putJson(node, "maintenanceSchedule", backup.getMaintenanceSchedule());
        return node;
    }

    private ObjectNode backupSummary(DbBackup backup) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", backup.getId());
        put(node, "name", backup.getName());
        node.put("arn", backup.getArn());
        put(node, "status", backup.getStatus());
        if (backup.getCreatedAt() != null) {
            node.put("createdAt", epochSeconds(backup.getCreatedAt()));
        }
        put(node, "expiresAfter", backup.getExpiresAfter());
        put(node, "dbResourceId", backup.getDbResourceId());
        put(node, "type", backup.getType());
        put(node, "engineType", backup.getEngineType());
        put(node, "deploymentType", backup.getDeploymentType());
        put(node, "kmsKeyId", backup.getKmsKeyId());
        return node;
    }

    private ObjectNode restoreNode(RestoreResult result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("restoredDbResourceId", result.restoredDbResourceId());
        node.put("restoreStatus", "RESTORING");
        node.put("resourceType", result.resourceType());
        put(node, "engineType", result.engineType());
        put(node, "deploymentType", result.deploymentType());
        return node;
    }

    private ObjectNode tagsNode(Map<String, String> tags) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode tagNode = node.putObject("tags");
        tags.forEach(tagNode::put);
        return node;
    }

    private <T> ObjectNode page(PaginatedResult<T> result, Function<T, ObjectNode> mapper) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode items = node.putArray("items");
        for (T item : result.items()) {
            items.add(mapper.apply(item));
        }
        put(node, "nextToken", result.nextToken());
        return node;
    }

    private static Response ok(ObjectNode node) {
        return Response.ok(node).build();
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static void put(ObjectNode node, String field, Integer value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static void put(ObjectNode node, String field, Boolean value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static void putJson(ObjectNode node, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            node.set(field, value);
        }
    }

    private static void putStrings(ObjectNode node, String field, List<String> values) {
        if (values != null && !values.isEmpty()) {
            ArrayNode array = node.putArray(field);
            values.forEach(array::add);
        }
    }

    private static double epochSeconds(Instant instant) {
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }
}
