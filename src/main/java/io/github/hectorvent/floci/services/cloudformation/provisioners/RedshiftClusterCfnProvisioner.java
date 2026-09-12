package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Provisions AWS::Redshift::Cluster and its metadata companion types, replacing the generic
 * unsupported-type stub for AWS::Redshift::*. Injects only RedshiftService; discovered by
 * CloudFormationResourceRegistry via CDI.
 */
@ApplicationScoped
public class RedshiftClusterCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(RedshiftClusterCfnProvisioner.class);

    private static final String CLUSTER = "AWS::Redshift::Cluster";
    private static final String PARAMETER_GROUP = "AWS::Redshift::ClusterParameterGroup";
    private static final String SUBNET_GROUP = "AWS::Redshift::ClusterSubnetGroup";
    private static final String SECURITY_GROUP = "AWS::Redshift::ClusterSecurityGroup";
    private static final int IDENTIFIER_MAX_LENGTH = 63;
    private static final int METADATA_NAME_MAX_LENGTH = 255;

    private final RedshiftService redshiftService;

    @Inject
    public RedshiftClusterCfnProvisioner(RedshiftService redshiftService) {
        this.redshiftService = redshiftService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CLUSTER, PARAMETER_GROUP, SUBNET_GROUP, SECURITY_GROUP);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case CLUSTER -> {
                Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
                provisionCluster(r, props, ctx, attributesBefore);
                ReplacementCleanup.record(r, ctx, attributesBefore);
            }
            case PARAMETER_GROUP -> provisionParameterGroup(r, props, ctx);
            case SUBNET_GROUP -> provisionSubnetGroup(r, props, ctx);
            case SECURITY_GROUP -> provisionSecurityGroup(r, props, ctx);
            default -> throw new IllegalStateException(
                    "RedshiftClusterCfnProvisioner cannot provision " + r.getResourceType());
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

    private void provisionCluster(StackResource r, JsonNode props, ProvisionContext ctx,
                                  Map<String, String> attributesBefore) {
        if (Boolean.parseBoolean(ctx.resolveOptional(props, "ManageMasterPassword"))) {
            throw new AwsException("ValidationException",
                    "ManageMasterPassword is not emulated by Floci; set MasterUserPassword instead", 400);
        }
        String nodeType = ctx.resolveOptional(props, "NodeType");
        String masterUsername = ctx.resolveOptional(props, "MasterUsername");
        String masterUserPassword = ctx.resolveOptional(props, "MasterUserPassword");
        if (masterUserPassword == null || masterUserPassword.isBlank()) {
            throw new AwsException("ValidationException",
                    "MasterUserPassword is required unless ManageMasterPassword is set", 400);
        }
        String subnetGroup = ctx.resolveOptional(props, "ClusterSubnetGroupName");
        List<String> securityGroups = ctx.resolveStringList(props, "VpcSecurityGroupIds");

        String dbName = ctx.resolveOptional(props, "DBName");
        String priorDbName = attributesBefore.get("Floci::DBName");
        boolean dbNameChanged = ctx.isUpdate() && dbName != null && priorDbName != null && !dbName.equals(priorDbName);

        String explicitId = ctx.resolveOptional(props, "ClusterIdentifier");
        Cluster prior = ctx.isUpdate() ? findExistingCluster(ctx.priorPhysicalId()) : null;
        boolean createOnlyChanged = dbNameChanged || (prior != null && (
                (masterUsername != null && !masterUsername.equals(prior.getMasterUsername()))
                || (subnetGroup != null && !Objects.equals(subnetGroup, prior.getClusterSubnetGroupName()))
        ));

        String id;
        if (createOnlyChanged && (explicitId == null || explicitId.equals(ctx.priorPhysicalId()))) {
            if (explicitId != null && !explicitId.isBlank()) {
                id = replacementId(explicitId, IDENTIFIER_MAX_LENGTH);
            } else {
                id = ctx.generatePhysicalName(r.getLogicalId(), IDENTIFIER_MAX_LENGTH, true);
            }
        } else {
            id = ctx.stablePhysicalName(explicitId, r.getLogicalId(), IDENTIFIER_MAX_LENGTH, true);
        }

        warnUnsupported(props, ctx, id);

        // provision() is the update path too. A same-id cluster already on file is reconciled;
        // a derived id that differs from the prior physical id is a replacement handled via ReplacementCleanup.
        Cluster cluster = ctx.reusesPriorEntity(id)
                ? redshiftService.modifyCluster(id, nodeType, numberOfNodes(props, ctx),
                        masterUserPassword, ctx.resolveOptional(props, "ClusterParameterGroupName"),
                        securityGroups)
                : redshiftService.createCluster(id, nodeType, masterUsername, masterUserPassword,
                        subnetGroup, securityGroups);

        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        if (!tags.isEmpty()) {
            redshiftService.createTags(id, tags);
        }

        r.setPhysicalId(id);
        if (dbName != null && !dbName.isBlank()) {
            r.getAttributes().put("Floci::DBName", dbName);
        }
        if (cluster.getEndpoint() != null) {
            r.getAttributes().put("Endpoint.Address", cluster.getEndpoint().getAddress());
            r.getAttributes().put("Endpoint.Port", String.valueOf(cluster.getEndpoint().getPort()));
        }
        r.getAttributes().put("ClusterNamespaceArn", namespaceArn(ctx, id));
    }

    private Integer numberOfNodes(JsonNode props, ProvisionContext ctx) {
        String raw = ctx.resolveOptional(props, "NumberOfNodes");
        return raw == null || raw.isBlank() ? null : Integer.valueOf(raw);
    }

    /** Deterministic from the cluster identity: enough for a template that feeds it to a policy. */
    private String namespaceArn(ProvisionContext ctx, String clusterId) {
        UUID ns = UUID.nameUUIDFromBytes(
                (ctx.accountId() + ":" + clusterId).getBytes(StandardCharsets.UTF_8));
        return "arn:aws:redshift:" + ctx.region() + ":" + ctx.accountId() + ":namespace:" + ns;
    }

    private void warnUnsupported(JsonNode props, ProvisionContext ctx, String id) {
        String dbName = ctx.resolveOptional(props, "DBName");
        if (dbName != null && !dbName.isBlank() && !"dev".equals(dbName)) {
            LOG.warnv("Cluster {0}: DBName={1} ignored, the emulated container database is always dev", id, dbName);
        }
        if (ctx.resolveOptional(props, "Port") != null) {
            LOG.warnv("Cluster {0}: Port ignored, Floci assigns the host proxy port", id);
        }
        if (!ctx.isUpdate() && ctx.resolveOptional(props, "ClusterParameterGroupName") != null) {
            LOG.warnv("Cluster {0}: ClusterParameterGroupName is only applied on update", id);
        }
    }

    private void provisionParameterGroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String explicitName = ctx.resolveOptional(props, "ParameterGroupName");
        String id = ctx.stablePhysicalName(explicitName, r.getLogicalId(), METADATA_NAME_MAX_LENGTH, false);
        String family = ctx.resolveOptional(props, "ParameterGroupFamily");
        String description = ctx.resolveOptional(props, "Description");

        if (!ctx.reusesPriorEntity(id)) {
            redshiftService.createClusterParameterGroup(id, family, description);
        } else {
            JsonNode params = props != null ? props.get("Parameters") : null;
            if (params != null && !params.isEmpty()) {
                LOG.warnv("Cluster parameter group {0}: parameter updates on update are not emulated", id);
            }
        }

        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        if (!tags.isEmpty()) {
            redshiftService.createTags(id, tags);
        }

        r.setPhysicalId(id);
    }

    private void provisionSubnetGroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String explicitName = ctx.resolveOptional(props, "ClusterSubnetGroupName");
        String id = ctx.stablePhysicalName(explicitName, r.getLogicalId(), METADATA_NAME_MAX_LENGTH, false);
        String description = ctx.resolveOptional(props, "Description");
        List<String> subnetIds = ctx.resolveStringList(props, "SubnetIds");

        if (ctx.reusesPriorEntity(id)) {
            redshiftService.modifyClusterSubnetGroup(id, description, subnetIds);
        } else {
            redshiftService.createClusterSubnetGroup(id, description, null, subnetIds);
        }

        r.setPhysicalId(id);
        r.getAttributes().put("ClusterSubnetGroupName", id);
    }

    private void provisionSecurityGroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String id = ctx.stablePhysicalName(null, r.getLogicalId(), METADATA_NAME_MAX_LENGTH, false);
        r.setPhysicalId(id);
        r.getAttributes().put("Id", id);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (resourceType == null || physicalId == null) {
            return;
        }
        switch (resourceType) {
            case CLUSTER -> CfnDeletes.safeDelete("Redshift cluster", physicalId,
                    () -> redshiftService.deleteCluster(physicalId), "ClusterNotFound");
            case PARAMETER_GROUP -> CfnDeletes.safeDelete("Redshift parameter group", physicalId,
                    () -> redshiftService.deleteClusterParameterGroup(physicalId),
                    "ClusterParameterGroupNotFound", "ClusterParameterGroupNotFoundFault");
            case SUBNET_GROUP -> CfnDeletes.safeDelete("Redshift subnet group", physicalId,
                    () -> redshiftService.deleteClusterSubnetGroup(physicalId),
                    "ClusterSubnetGroupNotFound", "ClusterSubnetGroupNotFoundFault");
            case SECURITY_GROUP -> {
                // Accept-only, delete is a no-op
            }
            default -> {
            }
        }
    }

    private Cluster findExistingCluster(String clusterIdentifier) {
        if (clusterIdentifier == null || clusterIdentifier.isBlank()) {
            return null;
        }
        try {
            List<Cluster> list = redshiftService.describeClusters(clusterIdentifier);
            return (list != null && !list.isEmpty()) ? list.get(0) : null;
        } catch (AwsException e) {
            if ("ClusterNotFound".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private String replacementId(String baseId, int maxLength) {
        String suffix = "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        int keep = Math.max(0, maxLength - suffix.length());
        String prefix = baseId.length() > keep ? baseId.substring(0, keep) : baseId;
        while (prefix.endsWith("-")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return (prefix + suffix).toLowerCase();
    }
}
