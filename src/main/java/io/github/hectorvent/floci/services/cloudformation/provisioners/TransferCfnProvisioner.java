package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.transfer.TransferService;
import io.github.hectorvent.floci.services.transfer.model.Server;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** CloudFormation lifecycle for a Transfer Family server. */
@ApplicationScoped
public class TransferCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Transfer::Server";
    private static final String SECURITY_POLICY_DEFAULT = "TransferSecurityPolicy-2020-06";
    private static final Set<String> SUPPORTED_PROPERTIES = Set.of("Domain", "Protocols", "EndpointType",
            "EndpointDetails", "IdentityProviderType", "IdentityProviderDetails", "LoggingRole",
            "SecurityPolicyName", "Tags");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UPDATE_SNAPSHOT = "__FlociTransferServerUpdateSnapshot";
    private static final String TEMPLATE_TAG_KEYS = "__FlociTransferServerTemplateTagKeys";

    private final TransferService transferService;

    @Inject
    public TransferCfnProvisioner(TransferService transferService) {
        this.transferService = transferService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        resource.getAttributes().remove(UPDATE_SNAPSHOT);
        validateProperties(props);
        String domain = ctx.resolveOrDefault(props, "Domain", "S3");
        JsonNode protocolNode = props != null ? props.get("Protocols") : null;
        JsonNode resolvedProtocols = protocolNode != null ? ctx.engine().resolveNode(protocolNode) : null;
        boolean protocolsOmitted = resolvedProtocols == null || resolvedProtocols.isNull()
                || (resolvedProtocols.isTextual() && resolvedProtocols.asText().isBlank());
        List<String> protocols = protocolsOmitted ? List.of("SFTP") : ctx.resolveStringList(props, "Protocols");
        if (protocols.isEmpty()) {
            throw new AwsException("ValidationError", "AWS::Transfer::Server Protocols must not be empty", 400);
        }
        String endpointType = ctx.resolveOrDefault(props, "EndpointType", "PUBLIC");
        Map<String, Object> endpointDetails = resolvedMap(props, "EndpointDetails", ctx,
                new TypeReference<Map<String, Object>>() {});
        String identityProviderType = ctx.resolveOrDefault(props, "IdentityProviderType", "SERVICE_MANAGED");
        Map<String, String> identityProviderDetails = resolvedMap(props, "IdentityProviderDetails", ctx,
                new TypeReference<Map<String, String>>() {});
        String loggingRole = ctx.resolveOptional(props, "LoggingRole");
        if (loggingRole != null && loggingRole.isBlank()) {
            loggingRole = null;
        }
        String securityPolicyName = ctx.resolveOrDefault(props, "SecurityPolicyName", SECURITY_POLICY_DEFAULT);
        Map<String, String> tags = ctx.resolveTags(props, "Tags");

        Server server;
        if (ctx.isUpdate()) {
            Server existing = transferService.getServer(serverId(ctx.priorPhysicalId()));
            rejectUnsupportedChange("Domain", existing.getDomain(), domain);
            rejectUnsupportedChange("IdentityProviderType", existing.getIdentityProviderType(), identityProviderType);
            Map<String, String> previousTags = transferService.listTagsForResource(existing.getArn());
            String previousTemplateTagKeys = resource.getAttributes().get(TEMPLATE_TAG_KEYS);
            ConfigurationSnapshot previous = new ConfigurationSnapshot(existing.getServerId(),
                    existing.getProtocols(), existing.getEndpointType(), existing.getEndpointDetails(),
                    existing.getIdentityProviderDetails(), existing.getLoggingRole(),
                    existing.getSecurityPolicyName(), previousTags, new ArrayList<>(tags.keySet()),
                    previousTemplateTagKeys);
            resource.getAttributes().put(UPDATE_SNAPSHOT, MAPPER.valueToTree(previous).toString());
            try {
                server = transferService.replaceServerConfiguration(existing.getServerId(), protocols, endpointType,
                        endpointDetails, identityProviderDetails, loggingRole, securityPolicyName);
                reconcileTags(server.getArn(), managedTagKeys(previousTemplateTagKeys), tags);
            } catch (RuntimeException failure) {
                try {
                    rollbackUpdate(resource);
                    resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
                } catch (RuntimeException restoreFailure) {
                    resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR,
                            "Could not restore Transfer server " + existing.getServerId() + ": "
                                    + restoreFailure.getMessage());
                    failure.addSuppressed(restoreFailure);
                }
                throw failure;
            }
        } else {
            server = transferService.createServer(ctx.region(), domain, protocols, endpointType, endpointDetails,
                    identityProviderType, identityProviderDetails, loggingRole, securityPolicyName, tags);
        }
        recordServer(resource, server);
        resource.getAttributes().put(TEMPLATE_TAG_KEYS, MAPPER.valueToTree(tags.keySet()).toString());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("Transfer server", physicalId,
                () -> transferService.deleteServer(serverId(physicalId)), "ResourceNotFoundException");
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        if ("UPDATE_COMPLETE".equals(resource.getStatus())) {
            resource.getAttributes().remove(UPDATE_SNAPSHOT);
        }
        return UpdateCleanupResult.notApplicable();
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String saved = resource.getAttributes().get(UPDATE_SNAPSHOT);
        if (saved == null) {
            return true;
        }
        ConfigurationSnapshot previous;
        try {
            previous = MAPPER.readValue(saved, ConfigurationSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read Transfer server update snapshot", e);
        }
        Server server = transferService.replaceServerConfiguration(previous.serverId(), previous.protocols(),
                previous.endpointType(), previous.endpointDetails(), previous.identityProviderDetails(),
                previous.loggingRole(), previous.securityPolicyName());
        reconcileTags(server.getArn(), previous.newTagKeys(), previous.tags());
        recordServer(resource, server);
        if (previous.previousTemplateTagKeys() == null) {
            resource.getAttributes().remove(TEMPLATE_TAG_KEYS);
        } else {
            resource.getAttributes().put(TEMPLATE_TAG_KEYS, previous.previousTemplateTagKeys());
        }
        resource.getAttributes().remove(UPDATE_SNAPSHOT);
        return true;
    }

    private static void recordServer(StackResource resource, Server server) {
        resource.setPhysicalId(server.getArn());
        resource.getAttributes().put("ServerId", server.getServerId());
        resource.getAttributes().put("Arn", server.getArn());
        resource.getAttributes().put("State", server.getState());
    }

    private static String serverId(String physicalId) {
        if (!AwsArnUtils.isArn(physicalId)) {
            return physicalId;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(physicalId);
        if (!"transfer".equals(arn.service()) || !arn.resource().startsWith("server/")) {
            throw new AwsException("ValidationError", "Invalid Transfer server ARN: " + physicalId, 400);
        }
        return arn.resource().substring("server/".length());
    }

    private void reconcileTags(String arn, List<String> managedKeys, Map<String, String> desired) {
        List<String> staleTags = managedKeys.stream().filter(key -> !desired.containsKey(key)).toList();
        if (!staleTags.isEmpty()) {
            transferService.untagResource(arn, staleTags);
        }
        if (!desired.isEmpty()) {
            transferService.tagResource(arn, desired);
        }
    }

    private record ConfigurationSnapshot(String serverId, List<String> protocols, String endpointType,
                                         Map<String, Object> endpointDetails,
                                         Map<String, String> identityProviderDetails, String loggingRole,
                                         String securityPolicyName, Map<String, String> tags,
                                         List<String> newTagKeys, String previousTemplateTagKeys) {
    }

    private static List<String> managedTagKeys(String stored) {
        if (stored == null) {
            return List.of();
        }
        try {
            return MAPPER.readValue(stored, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read Transfer server template tag keys", e);
        }
    }

    private static void validateProperties(JsonNode props) {
        if (props == null || props.isNull()) {
            return;
        }
        if (!props.isObject()) {
            throw new AwsException("ValidationError", "AWS::Transfer::Server Properties must be an object", 400);
        }
        props.fieldNames().forEachRemaining(name -> {
            if (!SUPPORTED_PROPERTIES.contains(name)) {
                throw new AwsException("ValidationError",
                        "AWS::Transfer::Server property " + name + " is not supported by Floci", 400);
            }
        });
    }

    private static <T> T resolvedMap(JsonNode props, String name, ProvisionContext ctx, TypeReference<T> type) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(name));
        if (resolved == null || resolved.isNull()
                || (resolved.isTextual() && resolved.asText().isBlank())) {
            return null;
        }
        if (!resolved.isObject()) {
            throw new AwsException("ValidationError", "AWS::Transfer::Server " + name + " must be an object", 400);
        }
        return MAPPER.convertValue(resolved, type);
    }

    private static void rejectUnsupportedChange(String name, String current, String requested) {
        if (!Objects.equals(current, requested)) {
            throw new AwsException("ValidationError",
                    "Updating " + name + " is not supported by Floci.", 400);
        }
    }
}
