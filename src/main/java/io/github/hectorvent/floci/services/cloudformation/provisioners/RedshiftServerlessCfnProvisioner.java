package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.services.redshiftserverless.WorkgroupSettings;
import io.github.hectorvent.floci.services.redshiftserverless.model.ConfigParameter;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.PricePerformanceTarget;
import io.github.hectorvent.floci.services.redshiftserverless.model.Workgroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::RedshiftServerless::Namespace} and {@code AWS::RedshiftServerless::Workgroup}.
 * Both types use their name as the physical id, which is what {@code Ref} returns.
 *
 * <p>The registry schema declares every read-only attribute under a nested pointer
 * ({@code /properties/Namespace/NamespaceArn}), so {@code Fn::GetAtt} takes the dotted form
 * ({@code Namespace.NamespaceArn}, {@code Workgroup.Endpoint.Address}) and each is set under that
 * exact key.
 */
@ApplicationScoped
public class RedshiftServerlessCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessCfnProvisioner.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NAMESPACE = "AWS::RedshiftServerless::Namespace";
    private static final String WORKGROUP = "AWS::RedshiftServerless::Workgroup";
    private static final int NAME_MAX_LENGTH = 64;
    private static final List<String> UNSUPPORTED_WORKGROUP_PROPERTIES =
            List.of("SnapshotArn", "SnapshotName", "SnapshotOwnerAccount", "RecoveryPointId");

    private final RedshiftServerlessService service;

    @Inject
    public RedshiftServerlessCfnProvisioner(RedshiftServerlessService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(NAMESPACE, WORKGROUP);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        switch (r.getResourceType()) {
            case NAMESPACE -> provisionNamespace(r, props, ctx);
            case WORKGROUP -> provisionWorkgroup(r, props, ctx);
            default -> throw new IllegalStateException(
                    "RedshiftServerlessCfnProvisioner cannot provision " + r.getResourceType());
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case NAMESPACE -> CfnDeletes.safeDelete("Redshift Serverless namespace", physicalId,
                    () -> service.deleteNamespace(physicalId, region), "ResourceNotFoundException");
            case WORKGROUP -> CfnDeletes.safeDelete("Redshift Serverless workgroup", physicalId,
                    () -> service.deleteWorkgroup(physicalId, region), "ResourceNotFoundException");
            default -> throw new IllegalStateException(
                    "RedshiftServerlessCfnProvisioner cannot delete " + resourceType);
        }
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
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    private void provisionNamespace(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "NamespaceName"),
                r.getLogicalId(), NAME_MAX_LENGTH, true);
        String adminUsername = ctx.resolveOptional(props, "AdminUsername");
        String adminUserPassword = ctx.resolveOptional(props, "AdminUserPassword");
        String kmsKeyId = ctx.resolveOptional(props, "KmsKeyId");
        String defaultIamRoleArn = ctx.resolveOptional(props, "DefaultIamRoleArn");
        List<String> iamRoles = ctx.resolveStringList(props, "IamRoles");
        List<String> logExports = ctx.resolveStringList(props, "LogExports");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");

        Namespace namespace;
        if (ctx.reusesPriorEntity(name)) {
            String dbName = ctx.resolveOptional(props, "DbName");
            String currentDbName = service.getNamespace(name, ctx.region()).getDbName();
            if (dbName != null && !dbName.equals(currentDbName)) {
                throw new AwsException("ValidationException",
                        "DbName cannot be changed in place; it is fixed when the namespace is created, so "
                                + "remove the namespace from the template and add it back",
                        400);
            }
            namespace = service.reconcileNamespace(name, adminUsername, adminUserPassword, kmsKeyId,
                    defaultIamRoleArn, iamRoles, logExports, ctx.region());
            reconcileTags(namespace.getNamespaceArn(), tags, ctx);
        } else {
            namespace = service.createNamespace(name, adminUsername, adminUserPassword,
                    ctx.resolveOptional(props, "DbName"), kmsKeyId, defaultIamRoleArn, iamRoles, logExports, tags,
                    ctx.region());
        }

        r.setPhysicalId(name);
        r.getAttributes().put("Namespace.NamespaceName", namespace.getNamespaceName());
        r.getAttributes().put("Namespace.NamespaceId", namespace.getNamespaceId());
        r.getAttributes().put("Namespace.NamespaceArn", namespace.getNamespaceArn());
        r.getAttributes().put("Namespace.DbName", namespace.getDbName());
        r.getAttributes().put("Namespace.KmsKeyId", namespace.getKmsKeyId());
        r.getAttributes().put("Namespace.Status", namespace.getStatus());
        r.getAttributes().put("Namespace.CreationDate", namespace.getCreationDate().toString());
        r.getAttributes().put("Namespace.IamRoles", String.join(",", namespace.getIamRoles()));
        r.getAttributes().put("Namespace.LogExports", String.join(",", namespace.getLogExports()));
        if (namespace.getAdminUsername() != null) {
            r.getAttributes().put("Namespace.AdminUsername", namespace.getAdminUsername());
        }
        if (namespace.getDefaultIamRoleArn() != null) {
            r.getAttributes().put("Namespace.DefaultIamRoleArn", namespace.getDefaultIamRoleArn());
        } else {
            // An update carries the prior attributes forward, so a role the template dropped must be removed.
            r.getAttributes().remove("Namespace.DefaultIamRoleArn");
        }
    }

    private void provisionWorkgroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        for (String unsupported : UNSUPPORTED_WORKGROUP_PROPERTIES) {
            if (ctx.resolveOptional(props, unsupported) != null) {
                LOG.warnv("{0} is not supported on AWS::RedshiftServerless::Workgroup {1}; restoring from a "
                        + "snapshot is not emulated and the property is ignored", unsupported, r.getLogicalId());
            }
        }
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "WorkgroupName"),
                r.getLogicalId(), NAME_MAX_LENGTH, true);
        String namespaceName = ctx.resolveOptional(props, "NamespaceName");
        WorkgroupSettings settings = settings(props, ctx);
        Map<String, String> tags = ctx.resolveTags(props, "Tags");

        Workgroup workgroup;
        if (ctx.reusesPriorEntity(name)) {
            Workgroup current = service.getWorkgroup(name, ctx.region());
            if (namespaceName != null && !namespaceName.equals(current.getNamespaceName())) {
                throw new AwsException("ValidationException",
                        "NamespaceName cannot be changed in place; a workgroup belongs to one namespace for its "
                                + "lifetime, so remove the workgroup from the template and add it back",
                        400);
            }
            workgroup = service.updateWorkgroup(name, settings, ctx.region());
            reconcileTags(workgroup.getWorkgroupArn(), tags, ctx);
        } else {
            workgroup = service.createWorkgroup(name, namespaceName, settings, tags, ctx.region());
        }

        r.setPhysicalId(name);
        r.getAttributes().put("Workgroup.WorkgroupName", workgroup.getWorkgroupName());
        r.getAttributes().put("Workgroup.WorkgroupId", workgroup.getWorkgroupId());
        r.getAttributes().put("Workgroup.WorkgroupArn", workgroup.getWorkgroupArn());
        r.getAttributes().put("Workgroup.NamespaceName", workgroup.getNamespaceName());
        r.getAttributes().put("Workgroup.Status", workgroup.getStatus());
        r.getAttributes().put("Workgroup.BaseCapacity", String.valueOf(workgroup.getBaseCapacity()));
        r.getAttributes().put("Workgroup.EnhancedVpcRouting", String.valueOf(workgroup.isEnhancedVpcRouting()));
        r.getAttributes().put("Workgroup.PubliclyAccessible", String.valueOf(workgroup.isPubliclyAccessible()));
        r.getAttributes().put("Workgroup.TrackName", workgroup.getTrackName());
        r.getAttributes().put("Workgroup.CreationDate", workgroup.getCreationDate().toString());
        r.getAttributes().put("Workgroup.SecurityGroupIds", String.join(",", workgroup.getSecurityGroupIds()));
        r.getAttributes().put("Workgroup.SubnetIds", String.join(",", workgroup.getSubnetIds()));
        r.getAttributes().put("Workgroup.ConfigParameters", configParametersJson(workgroup.getConfigParameters()));
        r.getAttributes().put("Workgroup.Endpoint.Address", workgroup.getEndpoint().getAddress());
        r.getAttributes().put("Workgroup.Endpoint.Port", String.valueOf(workgroup.getEndpoint().getPort()));
        if (workgroup.getMaxCapacity() != null) {
            r.getAttributes().put("Workgroup.MaxCapacity", String.valueOf(workgroup.getMaxCapacity()));
        }
    }

    private static String configParametersJson(List<ConfigParameter> parameters) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ConfigParameter parameter : parameters) {
            array.addObject()
                    .put("ParameterKey", parameter.getParameterKey())
                    .put("ParameterValue", parameter.getParameterValue());
        }
        return array.toString();
    }

    private static JsonNode parseConfigParametersAttribute(String text) {
        try {
            JsonNode parsed = MAPPER.readTree(text);
            if (parsed != null && parsed.isArray()) {
                return parsed;
            }
        } catch (JsonProcessingException e) {
            LOG.debugv(e, "ConfigParameters is not valid JSON: {0}", text);
        }
        throw new AwsException("ValidationException",
                "ConfigParameters must be a list of ParameterKey/ParameterValue pairs, got: " + text, 400);
    }

    /**
     * CloudFormation drives a resource to the template, so a property the template omits means its
     * default, not "keep what is stored". Every setting that has a default is therefore sent with it,
     * which also resets a value an earlier template set. {@code MaxCapacity} has no default and the
     * API cannot unset it, so removing it from a template leaves the stored value.
     */
    private WorkgroupSettings settings(JsonNode props, ProvisionContext ctx) {
        List<ConfigParameter> configParameters = new ArrayList<>();
        JsonNode configNode = props != null ? ctx.engine().resolveNode(props.get("ConfigParameters")) : null;
        if (configNode != null && configNode.isTextual() && !configNode.textValue().isBlank()) {
            configNode = parseConfigParametersAttribute(configNode.textValue());
        }
        if (configNode != null && configNode.isArray()) {
            for (JsonNode entry : configNode) {
                configParameters.add(new ConfigParameter(ctx.engine().resolve(entry.path("ParameterKey")),
                        ctx.engine().resolve(entry.path("ParameterValue"))));
            }
        }
        PricePerformanceTarget target = null;
        JsonNode targetNode = props != null ? ctx.engine().resolveNode(props.get("PricePerformanceTarget")) : null;
        if (targetNode != null && targetNode.isObject()) {
            String level = ctx.engine().resolve(targetNode.path("Level"));
            target = new PricePerformanceTarget(ctx.engine().resolve(targetNode.path("Status")),
                    level == null || level.isBlank() ? null : Integer.valueOf(level.trim()));
        } else {
            target = new PricePerformanceTarget("DISABLED", null);
        }
        return new WorkgroupSettings(
                orDefault(integer(props, "BaseCapacity", ctx), RedshiftServerlessService.DEFAULT_BASE_CAPACITY),
                integer(props, "MaxCapacity", ctx),
                orDefault(bool(props, "EnhancedVpcRouting", ctx), Boolean.FALSE),
                orDefault(bool(props, "PubliclyAccessible", ctx), Boolean.FALSE),
                null,
                configParameters,
                ctx.resolveStringList(props, "SecurityGroupIds"),
                ctx.resolveStringList(props, "SubnetIds"),
                orDefault(integer(props, "Port", ctx), RedshiftServerlessService.DEFAULT_PORT),
                target,
                null,
                ctx.resolveOrDefault(props, "TrackName", RedshiftServerlessService.DEFAULT_TRACK_NAME));
    }

    private static <T> T orDefault(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static Integer integer(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", name + " must be an integer, got: " + value, 400);
        }
    }

    private static Boolean bool(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        return value == null || value.isBlank() ? null : Boolean.valueOf(value.trim());
    }

    /** Drives the stored tags to the template's, since TagResource alone never removes a key. */
    private void reconcileTags(String resourceArn, Map<String, String> desired, ProvisionContext ctx) {
        Map<String, String> current = service.listTagsForResource(resourceArn, ctx.region());
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            service.untagResource(resourceArn, stale, ctx.region());
        }
        if (!desired.isEmpty()) {
            service.tagResource(resourceArn, desired, ctx.region());
        }
    }
}
