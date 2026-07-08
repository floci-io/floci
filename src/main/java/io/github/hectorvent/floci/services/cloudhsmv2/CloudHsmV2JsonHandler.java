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
                case "InitializeCluster" -> handleInitializeCluster(request, region);
                case "CreateHsm" -> handleCreateHsm(request, region);
                case "DeleteHsm" -> handleDeleteHsm(request, region);
                case "TagResource" -> handleTagResource(request, region);
                case "UntagResource" -> handleUntagResource(request, region);
                case "ListTags" -> handleListTags(request, region);
                default -> Response.status(400)
                        .entity(new AwsErrorResponse("UnknownOperationException",
                                "Operation " + action + " is not supported."))
                        .build();
            };
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorv("CloudHSM v2 error processing action {0}: {1}", action, e.getMessage());
            return Response.status(500)
                    .entity(new AwsErrorResponse("CloudHsmInternalFailureException", e.getMessage()))
                    .build();
        }
    }

    // ──────────────────────────── Action Handlers ────────────────────────────

    private Response handleCreateCluster(JsonNode request, String region) {
        String hsmType = text(request, "HsmType");
        Map<String, String> subnetMapping = parseStringMap(request.path("SubnetMapping"));
        String sourceBackupId = text(request, "SourceBackupId");
        Map<String, String> tags = parseTagList(request.path("TagList"));

        Cluster cluster = service.createCluster(hsmType, subnetMapping, sourceBackupId, tags, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(cluster));
        return Response.ok(response).build();
    }

    private Response handleDescribeClusters(JsonNode request, String region) {
        JsonNode filters = request.path("Filters");
        List<String> clusterIds = null;
        List<String> states = null;
        if (!filters.isMissingNode() && !filters.isNull()) {
            clusterIds = parseStringList(filters.path("clusterIds"));
            states = parseStringList(filters.path("states"));
        }

        Collection<Cluster> clusters = service.describeClusters(clusterIds, states, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Clusters");
        for (Cluster c : clusters) {
            arr.add(clusterNode(c));
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteCluster(JsonNode request, String region) {
        String clusterId = text(request, "ClusterId");
        Cluster cluster = service.deleteCluster(clusterId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(cluster));
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

        Hsm hsm = service.createHsm(clusterId, az, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Hsm", hsmNode(hsm));
        return Response.ok(response).build();
    }

    private Response handleDeleteHsm(JsonNode request, String region) {
        String clusterId = text(request, "ClusterId");
        String hsmId = text(request, "HsmId");

        Hsm hsm = service.deleteHsm(clusterId, hsmId, region);

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

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("TagList");
        tags.forEach((k, v) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", k);
            tag.put("Value", v);
            arr.add(tag);
        });
        return Response.ok(response).build();
    }

    // ──────────────────────────── Node Builders ────────────────────────────

    private ObjectNode clusterNode(Cluster cluster) {
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
        if (hsm.getState() != null) {
            node.put("State", hsm.getState());
        }
        if (hsm.getStateMessage() != null) {
            node.put("StateMessage", hsm.getStateMessage());
        }
        return node;
    }

    // ──────────────────────────── Parsing Helpers ────────────────────────────

    private String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
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
            return new String(Base64.getDecoder().decode(value));
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
