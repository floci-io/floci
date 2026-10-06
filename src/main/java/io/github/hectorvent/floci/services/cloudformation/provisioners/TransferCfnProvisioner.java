package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.transfer.TransferService;
import io.github.hectorvent.floci.services.transfer.model.HomeDirectoryMapping;
import io.github.hectorvent.floci.services.transfer.model.Server;
import io.github.hectorvent.floci.services.transfer.model.SshPublicKey;
import io.github.hectorvent.floci.services.transfer.model.User;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** CloudFormation lifecycle for Transfer Family servers and users. */
@ApplicationScoped
public class TransferCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(TransferCfnProvisioner.class);
    private static final String TYPE = "AWS::Transfer::Server";
    private static final String USER_TYPE = "AWS::Transfer::User";
    private static final Set<String> USER_SUPPORTED_PROPERTIES = Set.of("ServerId", "UserName", "Role",
            "HomeDirectory", "HomeDirectoryType", "HomeDirectoryMappings", "SshPublicKeys", "Tags");
    private static final String SECURITY_POLICY_DEFAULT = "TransferSecurityPolicy-2020-06";
    private static final Set<String> SUPPORTED_PROPERTIES = Set.of("Domain", "Protocols", "EndpointType",
            "EndpointDetails", "IdentityProviderType", "IdentityProviderDetails", "LoggingRole",
            "SecurityPolicyName", "Tags");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UPDATE_SNAPSHOT = "__FlociTransferServerUpdateSnapshot";
    private static final String TEMPLATE_TAG_KEYS = "__FlociTransferServerTemplateTagKeys";
    private static final String USER_UPDATE_SNAPSHOT = "__FlociTransferUserUpdateSnapshot";
    private static final String TEMPLATE_SSH_KEYS = "__FlociTransferUserTemplateSshKeys";

    private final TransferService transferService;

    @Inject
    public TransferCfnProvisioner(TransferService transferService) {
        this.transferService = transferService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE, USER_TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        if (USER_TYPE.equals(resource.getResourceType())) {
            provisionUser(resource, props, ctx);
            return;
        }
        resource.getAttributes().remove(UPDATE_SNAPSHOT);
        validateProperties(TYPE, SUPPORTED_PROPERTIES, props);
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
        if (USER_TYPE.equals(resourceType)) {
            UserRef ref = parseUserArn(physicalId);
            CfnDeletes.safeDelete("Transfer user", physicalId,
                    () -> transferService.deleteUser(ref.serverId(), ref.userName()), "ResourceNotFoundException");
            return;
        }
        CfnDeletes.safeDelete("Transfer server", physicalId,
                () -> transferService.deleteServer(serverId(physicalId)), "ResourceNotFoundException");
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        if (USER_TYPE.equals(resource.getResourceType())) {
            if ("UPDATE_COMPLETE".equals(resource.getStatus())) {
                resource.getAttributes().remove(USER_UPDATE_SNAPSHOT);
            }
            return ReplacementCleanup.complete(resource, this::delete);
        }
        if ("UPDATE_COMPLETE".equals(resource.getStatus())) {
            resource.getAttributes().remove(UPDATE_SNAPSHOT);
        }
        return UpdateCleanupResult.notApplicable();
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (USER_TYPE.equals(resource.getResourceType())) {
            if (resource.getAttributes().containsKey(USER_UPDATE_SNAPSHOT)) {
                restoreUser(resource);
                return true;
            }
            return ReplacementCleanup.rollback(resource, this::delete);
        }
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

    @RegisterForReflection
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

    private void provisionUser(StackResource resource, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(resource.getAttributes());
        validateProperties(USER_TYPE, USER_SUPPORTED_PROPERTIES, props);
        String serverId = requiredUserProperty(props, "ServerId", ctx);
        String userName = requiredUserProperty(props, "UserName", ctx);
        String role = requiredUserProperty(props, "Role", ctx);
        String homeDirectory = ctx.resolveOptional(props, "HomeDirectory");
        String homeDirectoryType = ctx.resolveOptional(props, "HomeDirectoryType");
        List<HomeDirectoryMapping> mappings = homeDirectoryMappings(props, ctx);
        List<String> sshKeys = ctx.resolveStringList(props, "SshPublicKeys");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");

        UserRef prior = ctx.isUpdate() ? parseUserArn(ctx.priorPhysicalId()) : null;
        boolean inPlace = prior != null && prior.serverId().equals(serverId) && prior.userName().equals(userName);
        User user;
        if (inPlace) {
            user = updateUserInPlace(resource, serverId, userName, role, homeDirectory, homeDirectoryType, mappings,
                    sshKeys, tags);
        } else {
            user = createUserWithKeys(serverId, ctx.region(), userName, role, homeDirectory, homeDirectoryType,
                    mappings, sshKeys, tags);
        }
        resource.setPhysicalId(user.getArn());
        resource.getAttributes().put("Arn", user.getArn());
        resource.getAttributes().put(TEMPLATE_TAG_KEYS, MAPPER.valueToTree(tags.keySet()).toString());
        resource.getAttributes().put(TEMPLATE_SSH_KEYS, MAPPER.valueToTree(sshKeys).toString());
        ReplacementCleanup.record(resource, ctx, attributesBefore);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    private User createUserWithKeys(String serverId, String region, String userName, String role,
                                    String homeDirectory, String homeDirectoryType,
                                    List<HomeDirectoryMapping> mappings, List<String> sshKeys,
                                    Map<String, String> tags) {
        User user = transferService.createUser(serverId, region, userName, role,
                homeDirectory != null && !homeDirectory.isBlank() ? homeDirectory : null,
                homeDirectoryType != null && !homeDirectoryType.isBlank() ? homeDirectoryType : null,
                mappings, tags);
        try {
            for (String key : sshKeys) {
                transferService.importSshPublicKey(serverId, userName, key);
            }
        } catch (RuntimeException failure) {
            try {
                transferService.deleteUser(serverId, userName);
            } catch (RuntimeException cleanupFailure) {
                LOG.warnv(cleanupFailure, "Could not remove Transfer user {0} on {1} after a failed key import",
                        userName, serverId);
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return user;
    }

    private User updateUserInPlace(StackResource resource, String serverId, String userName, String role,
                                   String homeDirectory, String homeDirectoryType,
                                   List<HomeDirectoryMapping> mappings, List<String> sshKeys,
                                   Map<String, String> tags) {
        User existing = transferService.getUser(serverId, userName);
        String previousTemplateTagKeys = resource.getAttributes().get(TEMPLATE_TAG_KEYS);
        String previousTemplateSshKeys = resource.getAttributes().get(TEMPLATE_SSH_KEYS);
        UserSnapshot previous = snapshotUser(serverId, existing,
                transferService.listTagsForResource(existing.getArn()), new ArrayList<>(tags.keySet()),
                sshKeys, previousTemplateTagKeys, previousTemplateSshKeys);
        resource.getAttributes().put(USER_UPDATE_SNAPSHOT, MAPPER.valueToTree(previous).toString());
        try {
            User user = transferService.updateUser(serverId, userName, role,
                    homeDirectory != null && !homeDirectory.isBlank() ? homeDirectory : "/",
                    homeDirectoryType != null && !homeDirectoryType.isBlank() ? homeDirectoryType : "PATH",
                    mappings);
            reconcileSshKeys(user, sshKeys, managedValues(previousTemplateSshKeys));
            reconcileUserTags(user.getArn(), tags, previousTemplateTagKeys);
            return user;
        } catch (RuntimeException failure) {
            try {
                restoreUser(resource);
                resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
            } catch (RuntimeException restoreFailure) {
                resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR,
                        "Could not restore Transfer user " + userName + ": " + restoreFailure.getMessage());
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    private static UserSnapshot snapshotUser(String serverId, User user, Map<String, String> tags,
                                             List<String> newTagKeys, List<String> updateSshKeys,
                                             String previousTemplateTagKeys, String previousTemplateSshKeys) {
        List<SnapshotMapping> mappings = new ArrayList<>();
        if (user.getHomeDirectoryMappings() != null) {
            for (HomeDirectoryMapping mapping : user.getHomeDirectoryMappings()) {
                mappings.add(new SnapshotMapping(mapping.getEntry(), mapping.getTarget()));
            }
        }
        List<SnapshotKey> keys = new ArrayList<>();
        if (user.getSshPublicKeys() != null) {
            for (SshPublicKey key : user.getSshPublicKeys()) {
                keys.add(new SnapshotKey(key.getSshPublicKeyId(), key.getSshPublicKeyBody(),
                        key.getDateImported() != null ? key.getDateImported().toString() : null));
            }
        }
        return new UserSnapshot(serverId, user.getUserName(), user.getRole(), user.getHomeDirectory(),
                user.getHomeDirectoryType(), mappings, keys, new HashMap<>(tags), newTagKeys,
                new ArrayList<>(updateSshKeys), previousTemplateTagKeys, previousTemplateSshKeys);
    }

    private void restoreUser(StackResource resource) {
        UserSnapshot previous;
        try {
            previous = MAPPER.readValue(resource.getAttributes().get(USER_UPDATE_SNAPSHOT), UserSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read Transfer user update snapshot", e);
        }
        List<HomeDirectoryMapping> mappings = new ArrayList<>();
        for (SnapshotMapping mapping : previous.mappings()) {
            mappings.add(new HomeDirectoryMapping(mapping.entry(), mapping.target()));
        }
        User user = transferService.updateUser(previous.serverId(), previous.userName(), previous.role(),
                previous.homeDirectory(), previous.homeDirectoryType(), mappings);
        Set<String> previousIds = new HashSet<>();
        Set<String> presentIds = new HashSet<>();
        for (SnapshotKey key : previous.sshKeys()) {
            previousIds.add(key.id());
        }
        for (SshPublicKey key : user.getSshPublicKeys() != null ? user.getSshPublicKeys() : List.<SshPublicKey>of()) {
            if (previousIds.contains(key.getSshPublicKeyId())) {
                presentIds.add(key.getSshPublicKeyId());
            } else if (previous.updateSshKeys().contains(key.getSshPublicKeyBody())) {
                transferService.deleteSshPublicKey(previous.serverId(), previous.userName(), key.getSshPublicKeyId());
            }
        }
        for (SnapshotKey key : previous.sshKeys()) {
            if (!presentIds.contains(key.id())) {
                transferService.restoreSshPublicKey(previous.serverId(), previous.userName(),
                        new SshPublicKey(key.id(), key.body(),
                                key.dateImported() != null ? Instant.parse(key.dateImported()) : Instant.now()));
            }
        }
        reconcileTags(user.getArn(), previous.newTagKeys(), previous.tags());
        restoreAttribute(resource, TEMPLATE_TAG_KEYS, previous.previousTemplateTagKeys());
        restoreAttribute(resource, TEMPLATE_SSH_KEYS, previous.previousTemplateSshKeys());
        resource.getAttributes().remove(USER_UPDATE_SNAPSHOT);
    }

    private static void restoreAttribute(StackResource resource, String name, String value) {
        if (value == null) {
            resource.getAttributes().remove(name);
        } else {
            resource.getAttributes().put(name, value);
        }
    }

    @RegisterForReflection
    private record SnapshotMapping(String entry, String target) {
    }

    @RegisterForReflection
    private record SnapshotKey(String id, String body, String dateImported) {
    }

    @RegisterForReflection
    private record UserSnapshot(String serverId, String userName, String role, String homeDirectory,
                                String homeDirectoryType, List<SnapshotMapping> mappings,
                                List<SnapshotKey> sshKeys, Map<String, String> tags, List<String> newTagKeys,
                                List<String> updateSshKeys, String previousTemplateTagKeys,
                                String previousTemplateSshKeys) {
    }

    private static Set<String> managedValues(String stored) {
        return new HashSet<>(managedTagKeys(stored));
    }

    private void reconcileSshKeys(User user, List<String> desired, Set<String> managed) {
        List<SshPublicKey> current = user.getSshPublicKeys() != null ? user.getSshPublicKeys() : List.of();
        Set<String> currentBodies = new HashSet<>();
        for (SshPublicKey key : current) {
            if (desired.contains(key.getSshPublicKeyBody())) {
                currentBodies.add(key.getSshPublicKeyBody());
            } else if (managed.contains(key.getSshPublicKeyBody())) {
                transferService.deleteSshPublicKey(serverIdOf(user), user.getUserName(), key.getSshPublicKeyId());
            }
        }
        for (String body : desired) {
            if (currentBodies.add(body)) {
                transferService.importSshPublicKey(serverIdOf(user), user.getUserName(), body);
            }
        }
    }

    private void reconcileUserTags(String arn, Map<String, String> desired, String previousTemplateTagKeys) {
        reconcileTags(arn, managedTagKeys(previousTemplateTagKeys), desired);
    }

    private static String serverIdOf(User user) {
        return parseUserArn(user.getArn()).serverId();
    }

    private static String requiredUserProperty(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", USER_TYPE + " requires property " + name, 400);
        }
        return value;
    }

    private static List<HomeDirectoryMapping> homeDirectoryMappings(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("HomeDirectoryMappings") || props.get("HomeDirectoryMappings").isNull()) {
            return new ArrayList<>();
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("HomeDirectoryMappings"));
        if (resolved == null || resolved.isNull()) {
            return new ArrayList<>();
        }
        if (!resolved.isArray()) {
            throw new AwsException("ValidationError", USER_TYPE + " HomeDirectoryMappings must be a list", 400);
        }
        List<HomeDirectoryMapping> mappings = new ArrayList<>();
        for (JsonNode mapping : resolved) {
            mappings.add(new HomeDirectoryMapping(mapping.path("Entry").asText(null),
                    mapping.path("Target").asText(null)));
        }
        return mappings;
    }

    private static UserRef parseUserArn(String physicalId) {
        if (AwsArnUtils.isArn(physicalId)) {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(physicalId);
            if ("transfer".equals(arn.service()) && arn.resource().startsWith("user/")) {
                String[] parts = arn.resource().substring("user/".length()).split("/", 2);
                if (parts.length == 2) {
                    return new UserRef(parts[0], parts[1]);
                }
            }
        }
        throw new AwsException("ValidationError", "Invalid Transfer user ARN: " + physicalId, 400);
    }

    private record UserRef(String serverId, String userName) {
    }

    private static void validateProperties(String type, Set<String> supported, JsonNode props) {
        if (props == null || props.isNull()) {
            return;
        }
        if (!props.isObject()) {
            throw new AwsException("ValidationError", type + " Properties must be an object", 400);
        }
        props.fieldNames().forEachRemaining(name -> {
            if (!supported.contains(name)) {
                throw new AwsException("ValidationError",
                        type + " property " + name + " is not supported by Floci", 400);
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
