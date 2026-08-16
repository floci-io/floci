package io.github.hectorvent.floci.services.cloudhsmv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudhsmv2.model.Certificates;
import io.github.hectorvent.floci.services.cloudhsmv2.model.Cluster;
import io.github.hectorvent.floci.services.cloudhsmv2.model.Hsm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.hectorvent.floci.services.cloudhsmv2.model.Backup;
import io.github.hectorvent.floci.services.cloudhsmv2.model.BackupRetentionPolicy;
import java.util.stream.Collectors;


/**
 * CloudHSM v2 JSON 1.1 handler. Dispatched from
 * {@link io.github.hectorvent.floci.core.common.AwsJson11Controller}
 * under the {@code BaldrApiService.} target prefix.
 */
@ApplicationScoped
public class CloudHsmV2JsonHandler {

    private static final Logger LOG = Logger.getLogger(CloudHsmV2JsonHandler.class);

    private final CloudHsmV2Service service;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudHsmV2JsonHandler(CloudHsmV2Service service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("CloudHSM v2 action: {0}", action);
        try {
            return switch (action) {
                case "CreateCluster" -> handleCreateCluster(request, region);
                case "DescribeClusters" -> handleDescribeClusters(request, region);
                case "DeleteCluster" -> handleDeleteCluster(request, region);
                case "ModifyCluster" -> handleModifyCluster(request, region);
                case "InitializeCluster" -> handleInitializeCluster(request, region);
                case "CreateHsm" -> handleCreateHsm(request, region);
                case "DeleteHsm" -> handleDeleteHsm(request, region);
                case "DescribeBackups" -> handleDescribeBackups(request, region);
                case "DeleteBackup" -> handleDeleteBackup(request, region);
                case "RestoreBackup" -> handleRestoreBackup(request, region);
                case "ModifyBackupAttributes" -> handleModifyBackupAttributes(request, region);
                case "CopyBackupToRegion" -> handleCopyBackupToRegion(request, region);
                case "PutResourcePolicy" -> handlePutResourcePolicy(request, region);
                case "GetResourcePolicy" -> handleGetResourcePolicy(request, region);
                case "DeleteResourcePolicy" -> handleDeleteResourcePolicy(request, region);
                case "TagResource" -> handleTagResource(request, region);
                case "UntagResource" -> handleUntagResource(request, region);
                case "ListTags" -> handleListTags(request, region);
                default -> Response.status(400)
                        .entity(new AwsErrorResponse("UnknownOperationException",
                                "Operation " + action + " is not supported."))
                        .build();
            };
        } catch (AwsException e) {
            return io.github.hectorvent.floci.core.common.JsonErrorResponseUtils.createErrorResponse(e);
        } catch (Exception e) {
            LOG.errorf(e, "CloudHSM v2 error processing action %s", action);
            return io.github.hectorvent.floci.core.common.JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    // ──────────────────────────── Action Handlers ────────────────────────────

    private Response handleCreateCluster(JsonNode request, String region) {
        String hsmType = text(request, "HsmType");
        List<String> SubnetIds = parseStringList(request.path("SubnetIds"));
        String sourceBackupId = text(request, "SourceBackupId");
        Map<String, String> tags = parseTagList(request.path("TagList"));
        String mode = text(request, "Mode");
        String networkType = text(request, "NetworkType");
        BackupRetentionPolicy backupRetentionPolicy = null;
        if (!request.path("BackupRetentionPolicy").isMissingNode()) {
            JsonNode brpNode = request.path("BackupRetentionPolicy");
            backupRetentionPolicy = new BackupRetentionPolicy(brpNode.path("Type").asText(null), brpNode.path("Value").asText(null));
        }

        Cluster cluster = service.createCluster(hsmType, SubnetIds, sourceBackupId, tags, mode, networkType, backupRetentionPolicy, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(cluster, region));
        return Response.ok(response).build();
    }

    private Response handleDescribeClusters(JsonNode request, String region) {
        JsonNode filters = request.path("Filters");
        List<String> clusterIds = null;
        List<String> states = null;
        List<String> vpcIds = null;
        if (!filters.isMissingNode() && !filters.isNull()) {
            clusterIds = parseStringList(filters.path("clusterIds"));
            states = parseStringList(filters.path("states"));
            vpcIds = parseStringList(filters.path("vpcIds"));
        }

        Collection<Cluster> clusters = service.describeClusters(clusterIds, states, vpcIds, region);

        int maxResults = parseMaxResults(request, 25);
        int start = parseNextToken(request);

        List<Cluster> list = new ArrayList<>(clusters);
        int end = Math.min(start + maxResults, list.size());
        List<Cluster> page = start < list.size() ? list.subList(start, end) : List.of();

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Clusters");
        for (Cluster c : page) {
            arr.add(clusterNode(c, region));
        }
        if (end < list.size()) {
            response.put("NextToken", String.valueOf(end));
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteCluster(JsonNode request, String region) {
        String clusterId = text(request, "ClusterId");
        Cluster cluster = service.deleteCluster(clusterId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(cluster, region));
        return Response.ok(response).build();
    }

    private Response handleInitializeCluster(JsonNode request, String region) {
        String clusterId = text(request, "ClusterId");
        String signedCert = decodeBlob(text(request, "SignedCert"));
        String trustAnchor = decodeBlob(text(request, "TrustAnchor"));

        Cluster cluster = service.initializeCluster(clusterId, signedCert, trustAnchor, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("State", cluster.getState().wireValue());
        if (cluster.getStateMessage() != null) {
            response.put("StateMessage", cluster.getStateMessage());
        }
        return Response.ok(response).build();
    }

    private Response handleCreateHsm(JsonNode request, String region) {
        String clusterId = text(request, "ClusterId");
        String az = text(request, "AvailabilityZone");
        String ipAddress = text(request, "IpAddress");

        Hsm hsm = service.createHsm(clusterId, az, ipAddress, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Hsm", hsmNode(hsm));
        return Response.ok(response).build();
    }

    private Response handleDeleteHsm(JsonNode request, String region) {
        String clusterId = text(request, "ClusterId");
        String hsmId = text(request, "HsmId");
        String eniId = text(request, "EniId");
        String eniIp = text(request, "EniIp");

        Hsm hsm = service.deleteHsm(clusterId, hsmId, eniId, eniIp, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("HsmId", hsm.getHsmId());
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String resourceId = text(request, "ResourceId");
        Map<String, String> tags = parseTagList(request.path("TagList"));
        service.tagResource(resourceId, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String resourceId = text(request, "ResourceId");
        List<String> tagKeys = parseStringList(request.path("TagKeyList"));
        service.untagResource(resourceId, tagKeys != null ? tagKeys : List.of(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTags(JsonNode request, String region) {
        String resourceId = text(request, "ResourceId");
        Map<String, String> tags = service.listTags(resourceId, region);

        int maxResults = parseMaxResults(request, 100);
        int start = parseNextToken(request);

        List<Map.Entry<String, String>> list = new ArrayList<>(tags.entrySet());
        int end = Math.min(start + maxResults, list.size());
        List<Map.Entry<String, String>> page = start < list.size() ? list.subList(start, end) : List.of();

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("TagList");
        for (Map.Entry<String, String> tagEntry : page) {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", tagEntry.getKey());
            tag.put("Value", tagEntry.getValue());
            arr.add(tag);
        }
        if (end < list.size()) {
            response.put("NextToken", String.valueOf(end));
        }
        return Response.ok(response).build();
    }

    private ObjectNode clusterNode(Cluster cluster , String region) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ClusterId", cluster.getClusterId());
        node.put("State", cluster.getState().wireValue());
        if (cluster.getStateMessage() != null) {
            node.put("StateMessage", cluster.getStateMessage());
        }
        node.put("HsmType", cluster.getHsmType());
        if (cluster.getVpcId() != null) {
            node.put("VpcId", cluster.getVpcId());
        }
        if (cluster.getSourceBackupId() != null) {
            node.put("SourceBackupId", cluster.getSourceBackupId());
        }
        if (cluster.getSecurityGroup() != null) {
            node.put("SecurityGroup", cluster.getSecurityGroup());
        }
        if (cluster.getCreateTimestamp() != null) {
            node.put("CreateTimestamp", cluster.getCreateTimestamp().toEpochMilli() / 1000.0);
        }
        if (cluster.getBackupPolicy() != null) {
            node.put("BackupPolicy", cluster.getBackupPolicy());
        }
        if (cluster.getMode() != null) {
            node.put("Mode", cluster.getMode());
        }
        if (cluster.getNetworkType() != null) {
            node.put("NetworkType", cluster.getNetworkType());
        }
        if (cluster.getBackupRetentionPolicy() != null) {
            ObjectNode brp = objectMapper.createObjectNode();
            brp.put("Type", cluster.getBackupRetentionPolicy().getType());
            brp.put("Value", cluster.getBackupRetentionPolicy().getValue());
            node.set("BackupRetentionPolicy", brp);
        }

        // SubnetMapping
        if (cluster.getSubnetMapping() != null && !cluster.getSubnetMapping().isEmpty()) {
            ObjectNode subnetNode = objectMapper.createObjectNode();
            cluster.getSubnetMapping().forEach(subnetNode::put);
            node.set("SubnetMapping", subnetNode);
        }

        // Certificates
        Certificates certs = cluster.getCertificates();
        if (certs != null) {
            ObjectNode certsNode = objectMapper.createObjectNode();
            if (certs.getClusterCsr() != null) {
                certsNode.put("ClusterCsr", certs.getClusterCsr());
            }
            if (certs.getHsmCertificate() != null) {
                certsNode.put("HsmCertificate", certs.getHsmCertificate());
            }
            if (certs.getAwsHardwareCertificate() != null) {
                certsNode.put("AwsHardwareCertificate", certs.getAwsHardwareCertificate());
            }
            if (certs.getManufacturerHardwareCertificate() != null) {
                certsNode.put("ManufacturerHardwareCertificate", certs.getManufacturerHardwareCertificate());
            }
            if (certs.getClusterCertificate() != null) {
                certsNode.put("ClusterCertificate", certs.getClusterCertificate());
            }
            node.set("Certificates", certsNode);
        }

        // HSMs
        ArrayNode hsmsArr = node.putArray("Hsms");
        for (Hsm hsm : cluster.getHsms()) {
            hsmsArr.add(hsmNode(hsm));
        }

        // Tags
        if (cluster.getTagList() != null && !cluster.getTagList().isEmpty()) {
            ArrayNode tagsArr = node.putArray("TagList");
            cluster.getTagList().forEach((k, v) -> {
                ObjectNode tag = objectMapper.createObjectNode();
                tag.put("Key", k);
                tag.put("Value", v);
                tagsArr.add(tag);
            });
        }

        return node;
    }

    private ObjectNode hsmNode(Hsm hsm) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("HsmId", hsm.getHsmId());
        if (hsm.getAvailabilityZone() != null) {
            node.put("AvailabilityZone", hsm.getAvailabilityZone());
        }
        if (hsm.getClusterId() != null) {
            node.put("ClusterId", hsm.getClusterId());
        }
        if (hsm.getSubnetId() != null) {
            node.put("SubnetId", hsm.getSubnetId());
        }
        if (hsm.getEniId() != null) {
            node.put("EniId", hsm.getEniId());
        }
        if (hsm.getEniIp() != null) {
            node.put("EniIp", hsm.getEniIp());
        }
        if (hsm.getEniIpV6() != null) {
            node.put("EniIpV6", hsm.getEniIpV6());
        }
        if (hsm.getHsmType() != null) {
            node.put("HsmType", hsm.getHsmType());
        }
        if (hsm.getState() != null) {
            node.put("State", hsm.getState());
        }
        if (hsm.getStateMessage() != null) {
            node.put("StateMessage", hsm.getStateMessage());
        }
        return node;
    }


    private Response handleModifyCluster(JsonNode request, String region) {
        String clusterId = text(request, "ClusterId");
        String hsmType = text(request, "HsmType");
        BackupRetentionPolicy backupRetentionPolicy = null;
        if (!request.path("BackupRetentionPolicy").isMissingNode()) {
            JsonNode brpNode = request.path("BackupRetentionPolicy");
            backupRetentionPolicy = new BackupRetentionPolicy(brpNode.path("Type").asText(null), brpNode.path("Value").asText(null));
        }

        Cluster cluster = service.modifyCluster(clusterId, hsmType, backupRetentionPolicy, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(cluster, region));
        return Response.ok(response).build();
    }

    private Response handleDescribeBackups(JsonNode request, String region) {
        JsonNode filters = request.path("Filters");
        List<String> backupIds = null;
        List<String> clusterIds = null;
        List<String> states = null;
        List<String> sourceBackupIds = null;
        List<String> neverExpires = null;
        if (!filters.isMissingNode() && !filters.isNull()) {
            backupIds = parseStringList(filters.path("backupIds"));
            clusterIds = parseStringList(filters.path("clusterIds"));
            states = parseStringList(filters.path("states"));
            sourceBackupIds = parseStringList(filters.path("sourceBackupIds"));
            neverExpires = parseStringList(filters.path("neverExpires"));
        }

        Boolean shared = request.path("Shared").isMissingNode() ? null : request.path("Shared").asBoolean();
        Boolean sortAscending = request.path("SortAscending").isMissingNode() ? null : request.path("SortAscending").asBoolean();

        Collection<Backup> backups = service.describeBackups(backupIds, clusterIds, states, sourceBackupIds, neverExpires, shared, sortAscending, region);

        int maxResults = parseMaxResults(request, 50);
        int start = parseNextToken(request);

        List<Backup> list = new ArrayList<>(backups);
        int end = Math.min(start + maxResults, list.size());
        List<Backup> page = start < list.size() ? list.subList(start, end) : List.of();

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Backups");
        for (Backup b : page) {
            arr.add(backupNode(b, region));
        }
        if (end < list.size()) {
            response.put("NextToken", String.valueOf(end));
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteBackup(JsonNode request, String region) {
        String backupId = text(request, "BackupId");
        Backup backup = service.deleteBackup(backupId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Backup", backupNode(backup, region));
        return Response.ok(response).build();
    }

    private Response handleRestoreBackup(JsonNode request, String region) {
        String backupId = text(request, "BackupId");
        Backup backup = service.restoreBackup(backupId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Backup", backupNode(backup, region));
        return Response.ok(response).build();
    }

    private Response handleModifyBackupAttributes(JsonNode request, String region) {
        String backupId = text(request, "BackupId");
        String neverExpires = text(request, "NeverExpires");
        Backup backup = service.modifyBackupAttributes(backupId, neverExpires, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Backup", backupNode(backup, region));
        return Response.ok(response).build();
    }

    private Response handleCopyBackupToRegion(JsonNode request, String region) {
        String destRegion = text(request, "DestinationRegion");
        if (destRegion == null || destRegion.isBlank()) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "DestinationRegion is required when copying a backup.", 400);
        }
        String backupId = text(request, "BackupId");
        Backup backup = service.copyBackupToRegion(destRegion, backupId, region);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode destBackup = objectMapper.createObjectNode();
        destBackup.put("CreateTimestamp", backup.getCreateTimestamp().toEpochMilli() / 1000.0);
        destBackup.put("SourceBackup", backup.getSourceBackup());
        destBackup.put("SourceCluster", backup.getSourceCluster());
        destBackup.put("SourceRegion", backup.getSourceRegion());
        response.set("DestinationBackup", destBackup);
        return Response.ok(response).build();
    }

    private Response handlePutResourcePolicy(JsonNode request, String region) {
        String resourceArn = text(request, "ResourceArn");
        String policy = text(request, "Policy");
        service.putResourcePolicy(resourceArn, policy, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ResourceArn", resourceArn);
        response.put("Policy", policy);
        return Response.ok(response).build();
    }

    private Response handleGetResourcePolicy(JsonNode request, String region) {
        String resourceArn = text(request, "ResourceArn");
        String policy = service.getResourcePolicy(resourceArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Policy", policy);
        return Response.ok(response).build();
    }

    private Response handleDeleteResourcePolicy(JsonNode request, String region) {
        String resourceArn = text(request, "ResourceArn");
        String oldPolicy = service.deleteResourcePolicy(resourceArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ResourceArn", resourceArn);
        if (oldPolicy != null) {
            response.put("Policy", oldPolicy);
        }
        return Response.ok(response).build();
    }

    private ObjectNode backupNode(Backup backup, String region) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("BackupId", backup.getBackupId());
        String arnRegion = region != null ? region : "us-east-1";
        node.put("BackupArn", "arn:aws:cloudhsm:" + arnRegion + ":000000000000:backup/" + backup.getBackupId());
        node.put("BackupState", backup.getBackupState());
        node.put("ClusterId", backup.getClusterId());
        if (backup.getHsmType() != null) {
            node.put("HsmType", backup.getHsmType());
        }
        if (backup.getCreateTimestamp() != null) {
            node.put("CreateTimestamp", backup.getCreateTimestamp().toEpochMilli() / 1000.0);
        }
        if (backup.getDeleteTimestamp() != null) {
            node.put("DeleteTimestamp", backup.getDeleteTimestamp().toEpochMilli() / 1000.0);
        }
        if (backup.getCopyTimestamp() != null) {
            node.put("CopyTimestamp", backup.getCopyTimestamp().toEpochMilli() / 1000.0);
        }
        if (backup.getNeverExpires() != null) {
            node.put("NeverExpires", "True".equalsIgnoreCase(backup.getNeverExpires()));
        }
        if (backup.getSourceRegion() != null) {
            node.put("SourceRegion", backup.getSourceRegion());
        }
        if (backup.getSourceBackup() != null) {
            node.put("SourceBackup", backup.getSourceBackup());
        }
        if (backup.getSourceCluster() != null) {
            node.put("SourceCluster", backup.getSourceCluster());
        }
        if (backup.getMode() != null) {
            node.put("Mode", backup.getMode());
        }
        if (backup.getTagList() != null && !backup.getTagList().isEmpty()) {
            ArrayNode tagList = node.putArray("TagList");
            for (Map.Entry<String, String> tagEntry : backup.getTagList().entrySet()) {
                ObjectNode tag = objectMapper.createObjectNode();
                tag.put("Key", tagEntry.getKey());
                tag.put("Value", tagEntry.getValue());
                tagList.add(tag);
            }
        }
        return node;
    }

    // ──────────────────────────── Parsing Helpers ────────────────────────────

    private String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private int parseMaxResults(JsonNode request, int maxAllowed) {
        if (request.path("MaxResults").isMissingNode() || request.path("MaxResults").isNull()) {
            return maxAllowed;
        }
        try {
            int max = Integer.parseInt(request.path("MaxResults").asText());
            if (max <= 0 || max > maxAllowed) {
                throw new AwsException("CloudHsmInvalidRequestException", "MaxResults must be between 1 and " + maxAllowed + ".", 400);
            }
            return max;
        } catch (NumberFormatException e) {
            throw new AwsException("CloudHsmInvalidRequestException", "MaxResults must be a valid integer.", 400);
        }
    }

    private int parseNextToken(JsonNode request) {
        String token = text(request, "NextToken");
        if (token == null) {
            return 0;
        }
        try {
            int val = Integer.parseInt(token);
            if (val < 0) {
                throw new AwsException("CloudHsmInvalidRequestException", "NextToken is invalid.", 400);
            }
            return val;
        } catch (NumberFormatException e) {
            throw new AwsException("CloudHsmInvalidRequestException", "NextToken is invalid.", 400);
        }
    }

    private List<String> parseStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        node.forEach(n -> {
            String val = n.asText(null);
            if (val != null) {
                list.add(val);
            }
        });
        return list.isEmpty() ? null : list;
    }

    private Map<String, String> parseStringMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            map.put(entry.getKey(), entry.getValue().asText());
        }
        return map.isEmpty() ? null : map;
    }

    private Map<String, String> parseTagList(JsonNode tagsNode) {
        if (tagsNode == null || !tagsNode.isArray()) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : tagsNode) {
            String key = tag.path("Key").asText(null);
            if (key != null) {
                tags.put(key, tag.path("Value").asText(null));
            }
        }
        return tags.isEmpty() ? null : tags;
    }

    /**
     * Decodes a base64-encoded blob field. AWS SDKs send binary fields as
     * base64-encoded strings. If the value is already in PEM format, it is returned as-is.
     */
    private String decodeBlob(String value) {
        if (value == null || value.startsWith("-----")) {
            return value;
        }
        try {
            return new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
