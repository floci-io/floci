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
    private static final int IDENTIFIER_MAX_LENGTH = 63;

    private final RedshiftService redshiftService;

    @Inject
    public RedshiftClusterCfnProvisioner(RedshiftService redshiftService) {
        this.redshiftService = redshiftService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CLUSTER);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (CLUSTER.equals(r.getResourceType())) {
            provisionCluster(r, props, ctx);
            return;
        }
        throw new IllegalStateException(
                "RedshiftClusterCfnProvisioner cannot provision " + r.getResourceType());
    }

    private void provisionCluster(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (Boolean.parseBoolean(ctx.resolveOptional(props, "ManageMasterPassword"))) {
            throw new AwsException("ValidationException",
                    "ManageMasterPassword is not emulated by Floci; set MasterUserPassword instead", 400);
        }
        String id = ctx.stablePhysicalName(ctx.resolveOptional(props, "ClusterIdentifier"),
                r.getLogicalId(), IDENTIFIER_MAX_LENGTH, true);
        String nodeType = ctx.resolveOptional(props, "NodeType");
        String masterUsername = ctx.resolveOptional(props, "MasterUsername");
        String masterUserPassword = ctx.resolveOptional(props, "MasterUserPassword");
        if (masterUserPassword == null || masterUserPassword.isBlank()) {
            throw new AwsException("ValidationException",
                    "MasterUserPassword is required unless ManageMasterPassword is set", 400);
        }
        String subnetGroup = ctx.resolveOptional(props, "ClusterSubnetGroupName");
        List<String> securityGroups = ctx.resolveStringList(props, "VpcSecurityGroupIds");

        warnUnsupported(props, ctx, id);

        // provision() is the update path too. A same-id cluster already on file is reconciled;
        // a derived id that differs from the prior physical id is a replacement (handled in Task 2).
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
        r.getAttributes().put("Id", id);
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

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (CLUSTER.equals(resourceType)) {
            CfnDeletes.safeDelete("Redshift cluster", physicalId,
                    () -> redshiftService.deleteCluster(physicalId), "ClusterNotFound");
        }
    }
}
