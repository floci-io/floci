package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSubnetGroup;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class RedshiftQueryHandler {

    private static final Logger LOG = Logger.getLogger(RedshiftQueryHandler.class);

    private static final Pattern FILTER_NAME =
            Pattern.compile("Filters\\.DescribeIntegrationsFilter\\.(\\d+)\\.Name");

    // GetClusterCredentials DurationSeconds bounds, inclusive (AWS: 900 to 3600).
    private static final int MIN_CREDENTIAL_DURATION_SECONDS = 900;
    private static final int MAX_CREDENTIAL_DURATION_SECONDS = 3600;

    private final RedshiftService service;
    private final RedshiftCredentialBroker credentialBroker;
    private final EmulatorConfig config;
    private final RedshiftIamDbUserResolver iamDbUserResolver;
    private final RegionResolver regionResolver;

    @Inject
    public RedshiftQueryHandler(RedshiftService service, RedshiftCredentialBroker credentialBroker,
                                EmulatorConfig config, RedshiftIamDbUserResolver iamDbUserResolver,
                                RegionResolver regionResolver) {
        this.service = service;
        this.credentialBroker = credentialBroker;
        this.config = config;
        this.iamDbUserResolver = iamDbUserResolver;
        this.regionResolver = regionResolver;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        return handle(action, params, null);
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String authorizationHeader) {
        switch (action) {
        case "CreateCluster" -> {
            String identifier = params.getFirst("ClusterIdentifier");
            String nodeType = params.getFirst("NodeType");
            String masterUsername = params.getFirst("MasterUsername");
            String masterUserPassword = params.getFirst("MasterUserPassword");
            String clusterSubnetGroupName = params.getFirst("ClusterSubnetGroupName");
            List<String> vpcSecurityGroupIds = memberList(params, "VpcSecurityGroupIds");
            List<String> iamRoleArns = memberList(params, "IamRoles");

            Cluster cluster = iamRoleArns.isEmpty()
                    ? service.createCluster(identifier, nodeType, masterUsername, masterUserPassword,
                            clusterSubnetGroupName, vpcSecurityGroupIds)
                    : service.createCluster(identifier, nodeType, masterUsername, masterUserPassword,
                            clusterSubnetGroupName, vpcSecurityGroupIds, iamRoleArns);
            String xml = new XmlBuilder()
                    .start("CreateClusterResponse")
                      .start("CreateClusterResult")
                        .raw(buildClusterXml(cluster))
                      .end("CreateClusterResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateClusterResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeClusters" -> {
            String identifier = params.getFirst("ClusterIdentifier");
            List<Cluster> clusters = service.describeClusters(identifier);
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeClustersResponse")
                      .start("DescribeClustersResult")
                        .start("Clusters");
            for (Cluster cluster : clusters) {
                xmlBuilder.raw(buildClusterXml(cluster));
            }
            String xml = xmlBuilder
                        .end("Clusters")
                      .end("DescribeClustersResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeClustersResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteCluster" -> {
            String identifier = params.getFirst("ClusterIdentifier");
            Cluster cluster = service.deleteCluster(identifier);
            String xml = new XmlBuilder()
                    .start("DeleteClusterResponse")
                      .start("DeleteClusterResult")
                        .raw(buildClusterXml(cluster))
                      .end("DeleteClusterResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteClusterResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "CreateClusterSnapshot" -> {
            String snapshotIdentifier = params.getFirst("SnapshotIdentifier");
            String clusterIdentifier = params.getFirst("ClusterIdentifier");
            Snapshot snapshot = service.createSnapshot(snapshotIdentifier, clusterIdentifier);
            String xml = new XmlBuilder()
                    .start("CreateClusterSnapshotResponse")
                      .start("CreateClusterSnapshotResult")
                        .raw(buildSnapshotXml(snapshot))
                      .end("CreateClusterSnapshotResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateClusterSnapshotResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeClusterSnapshots" -> {
            String snapshotIdentifier = params.getFirst("SnapshotIdentifier");
            String clusterIdentifier = params.getFirst("ClusterIdentifier");
            List<Snapshot> snapshots = service.describeSnapshots(snapshotIdentifier, clusterIdentifier);
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeClusterSnapshotsResponse")
                      .start("DescribeClusterSnapshotsResult")
                        .start("Snapshots");
            for (Snapshot snapshot : snapshots) {
                xmlBuilder.raw(buildSnapshotXml(snapshot));
            }
            String xml = xmlBuilder
                        .end("Snapshots")
                      .end("DescribeClusterSnapshotsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeClusterSnapshotsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteClusterSnapshot" -> {
            String snapshotIdentifier = params.getFirst("SnapshotIdentifier");
            Snapshot snapshot = service.deleteSnapshot(snapshotIdentifier);
            String xml = new XmlBuilder()
                    .start("DeleteClusterSnapshotResponse")
                      .start("DeleteClusterSnapshotResult")
                        .raw(buildSnapshotXml(snapshot))
                      .end("DeleteClusterSnapshotResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteClusterSnapshotResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "RestoreFromClusterSnapshot" -> {
            String clusterIdentifier = params.getFirst("ClusterIdentifier");
            String snapshotIdentifier = params.getFirst("SnapshotIdentifier");
            String nodeType = params.getFirst("NodeType");
            Cluster cluster = service.restoreFromClusterSnapshot(clusterIdentifier, snapshotIdentifier, nodeType);
            String xml = new XmlBuilder()
                    .start("RestoreFromClusterSnapshotResponse")
                      .start("RestoreFromClusterSnapshotResult")
                        .raw(buildClusterXml(cluster))
                      .end("RestoreFromClusterSnapshotResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("RestoreFromClusterSnapshotResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "CreateClusterParameterGroup" -> {
            String parameterGroupName = params.getFirst("ParameterGroupName");
            String parameterGroupFamily = params.getFirst("ParameterGroupFamily");
            String description = params.getFirst("Description");
            ClusterParameterGroup group = service.createClusterParameterGroup(parameterGroupName, parameterGroupFamily, description);
            String xml = new XmlBuilder()
                    .start("CreateClusterParameterGroupResponse")
                      .start("CreateClusterParameterGroupResult")
                        .raw(buildClusterParameterGroupXml(group))
                      .end("CreateClusterParameterGroupResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateClusterParameterGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeClusterParameterGroups" -> {
            String parameterGroupName = params.getFirst("ParameterGroupName");
            List<ClusterParameterGroup> groups = service.describeClusterParameterGroups(parameterGroupName);
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeClusterParameterGroupsResponse")
                      .start("DescribeClusterParameterGroupsResult")
                        .start("ParameterGroups");
            for (ClusterParameterGroup group : groups) {
                xmlBuilder.raw(buildClusterParameterGroupXml(group));
            }
            String xml = xmlBuilder
                        .end("ParameterGroups")
                      .end("DescribeClusterParameterGroupsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeClusterParameterGroupsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeClusterParameters" -> {
            String parameterGroupName = params.getFirst("ParameterGroupName");
            if (parameterGroupName == null || parameterGroupName.isBlank()) {
                throw new AwsException("InvalidParameterValue", "ParameterGroupName is required", 400);
            }
            List<Parameter> parameters = service.describeClusterParameters(parameterGroupName);

            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeClusterParametersResponse")
                      .start("DescribeClusterParametersResult")
                        .start("Parameters");
            for (Parameter param : parameters) {
                xmlBuilder.raw(buildParameterXml(param));
            }
            String xml = xmlBuilder.end("Parameters")
                      .end("DescribeClusterParametersResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeClusterParametersResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "ModifyClusterParameterGroup" -> {
            String parameterGroupName = params.getFirst("ParameterGroupName");
            if (parameterGroupName == null || parameterGroupName.isBlank()) {
                throw new AwsException("InvalidParameterValue", "ParameterGroupName is required", 400);
            }
            List<Parameter> updates = parseParameters(params);
            service.modifyClusterParameterGroup(parameterGroupName, updates);
            String xml = new XmlBuilder()
                    .start("ModifyClusterParameterGroupResponse")
                      .start("ModifyClusterParameterGroupResult")
                        .elem("ParameterGroupName", parameterGroupName)
                        .elem("ParameterGroupStatus", "pending-reboot")
                      .end("ModifyClusterParameterGroupResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("ModifyClusterParameterGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteClusterParameterGroup" -> {
            String parameterGroupName = params.getFirst("ParameterGroupName");
            service.deleteClusterParameterGroup(parameterGroupName);
            String xml = new XmlBuilder()
                    .start("DeleteClusterParameterGroupResponse")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteClusterParameterGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "CreateTags" -> {
            String resourceName = params.getFirst("ResourceName");
            service.createTags(resourceName, parseTags(params));
            String xml = new XmlBuilder()
                    .start("CreateTagsResponse")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateTagsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteTags" -> {
            String resourceName = params.getFirst("ResourceName");
            List<String> tagKeys = memberList(params, "TagKeys");
            service.deleteTags(resourceName, tagKeys);
            String xml = new XmlBuilder()
                    .start("DeleteTagsResponse")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteTagsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeTags" -> {
            String resourceName = params.getFirst("ResourceName");
            String resourceType = params.getFirst("ResourceType");
            List<String> tagKeys = memberList(params, "TagKeys");
            List<RedshiftService.TaggedResource> tagged = service.describeTags(resourceName, resourceType, tagKeys);
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeTagsResponse")
                      .start("DescribeTagsResult")
                        .start("TaggedResources");
            for (RedshiftService.TaggedResource t : tagged) {
                xmlBuilder.start("TaggedResource")
                        .elem("ResourceName", t.resourceName())
                        .elem("ResourceType", t.resourceType())
                        .start("Tag")
                          .elem("Key", t.tagKey())
                          .elem("Value", t.tagValue())
                        .end("Tag")
                      .end("TaggedResource");
            }
            String xml = xmlBuilder
                        .end("TaggedResources")
                      .end("DescribeTagsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeTagsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "CreateClusterSubnetGroup" -> {
            String name = params.getFirst("ClusterSubnetGroupName");
            if (name == null || name.isBlank()) {
                throw new AwsException("InvalidParameterValue", "ClusterSubnetGroupName is required", 400);
            }
            String description = params.getFirst("Description");
            List<String> subnetIds = memberList(params, "SubnetIds");
            ClusterSubnetGroup group = service.createClusterSubnetGroup(name, description, null, subnetIds);
            String xml = new XmlBuilder()
                    .start("CreateClusterSubnetGroupResponse")
                      .start("CreateClusterSubnetGroupResult")
                        .raw(buildClusterSubnetGroupXml(group))
                      .end("CreateClusterSubnetGroupResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateClusterSubnetGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "CreateIntegration" -> {
            Integration integration = service.createIntegration(
                    params.getFirst("IntegrationName"),
                    params.getFirst("SourceArn"),
                    params.getFirst("TargetArn"),
                    params.getFirst("KMSKeyId"),
                    params.getFirst("Description"),
                    encryptionContextMap(params),
                    tagMap(params),
                    regionResolver.resolveRegionFromAuth(authorizationHeader));
            String xml = new XmlBuilder()
                    .start("CreateIntegrationResponse")
                      .start("CreateIntegrationResult")
                        .raw(buildIntegrationXml(integration, false))
                      .end("CreateIntegrationResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateIntegrationResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeIntegrations" -> {
            RedshiftService.IntegrationPage found = service.describeIntegrations(
                    params.getFirst("IntegrationArn"),
                    intParam(params, "MaxRecords"),
                    params.getFirst("Marker"),
                    integrationFilters(params));
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeIntegrationsResponse")
                      .start("DescribeIntegrationsResult")
                        .start("Integrations");
            for (Integration integration : found.integrations()) {
                xmlBuilder.raw(buildIntegrationXml(integration, true));
            }
            xmlBuilder.end("Integrations");
            // Marker only when a further page exists: real Redshift omits it on the terminal page,
            // and an absent marker is what stops a caller's pagination loop.
            if (found.marker() != null) {
                xmlBuilder.elem("Marker", found.marker());
            }
            String xml = xmlBuilder
                      .end("DescribeIntegrationsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeIntegrationsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteIntegration" -> {
            Integration integration = service.deleteIntegration(params.getFirst("IntegrationArn"));
            String xml = new XmlBuilder()
                    .start("DeleteIntegrationResponse")
                      .start("DeleteIntegrationResult")
                        .raw(buildIntegrationXml(integration, false))
                      .end("DeleteIntegrationResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteIntegrationResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeClusterSubnetGroups" -> {
            String name = params.getFirst("ClusterSubnetGroupName");
            List<ClusterSubnetGroup> groups = service.describeClusterSubnetGroups(name);
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeClusterSubnetGroupsResponse")
                      .start("DescribeClusterSubnetGroupsResult")
                        .start("ClusterSubnetGroups");
            for (ClusterSubnetGroup group : groups) {
                xmlBuilder.raw(buildClusterSubnetGroupXml(group));
            }
            String xml = xmlBuilder
                        .end("ClusterSubnetGroups")
                      .end("DescribeClusterSubnetGroupsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeClusterSubnetGroupsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "ModifyClusterSubnetGroup" -> {
            String name = params.getFirst("ClusterSubnetGroupName");
            String description = params.getFirst("Description");
            List<String> subnetIds = memberList(params, "SubnetIds");
            ClusterSubnetGroup group = service.modifyClusterSubnetGroup(name, description, subnetIds);
            String xml = new XmlBuilder()
                    .start("ModifyClusterSubnetGroupResponse")
                      .start("ModifyClusterSubnetGroupResult")
                        .raw(buildClusterSubnetGroupXml(group))
                      .end("ModifyClusterSubnetGroupResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("ModifyClusterSubnetGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteClusterSubnetGroup" -> {
            String name = params.getFirst("ClusterSubnetGroupName");
            service.deleteClusterSubnetGroup(name);
            String xml = new XmlBuilder()
                    .start("DeleteClusterSubnetGroupResponse")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteClusterSubnetGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "ModifyCluster" -> {
            String clusterIdentifier = params.getFirst("ClusterIdentifier");
            if (clusterIdentifier == null || clusterIdentifier.isBlank()) {
                throw new AwsException("InvalidParameterValue", "ClusterIdentifier is required", 400);
            }
            String nodeType = params.getFirst("NodeType");
            Integer numberOfNodes = parseOptionalInteger(params, "NumberOfNodes");
            String masterUserPassword = params.getFirst("MasterUserPassword");
            String clusterParameterGroupName = params.getFirst("ClusterParameterGroupName");
            List<String> vpcSecurityGroupIds = memberList(params, "VpcSecurityGroupIds");
            Cluster cluster = service.modifyCluster(clusterIdentifier, nodeType, numberOfNodes,
                    masterUserPassword, clusterParameterGroupName, vpcSecurityGroupIds);
            String xml = new XmlBuilder()
                    .start("ModifyClusterResponse")
                      .start("ModifyClusterResult")
                        .raw(buildClusterXml(cluster))
                      .end("ModifyClusterResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("ModifyClusterResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "RebootCluster" -> {
            String clusterIdentifier = params.getFirst("ClusterIdentifier");
            if (clusterIdentifier == null || clusterIdentifier.isBlank()) {
                throw new AwsException("InvalidParameterValue", "ClusterIdentifier is required", 400);
            }
            Cluster cluster = service.rebootCluster(clusterIdentifier);
            String xml = new XmlBuilder()
                    .start("RebootClusterResponse")
                      .start("RebootClusterResult")
                        .raw(buildClusterXml(cluster))
                      .end("RebootClusterResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("RebootClusterResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "GetClusterCredentials" -> {
            String clusterId = requireParam(params, "ClusterIdentifier");
            String dbUser = requireParam(params, "DbUser");
            // describeClusters throws ClusterNotFound (404) for an unknown id.
            service.describeClusters(clusterId);
            // AWS prefixes the returned name IAMA: when AutoCreate is true, IAM: when it is false.
            boolean autoCreate = Boolean.parseBoolean(params.getFirst("AutoCreate"));
            String effectiveDbUser = (autoCreate ? "IAMA:" : "IAM:") + dbUser;
            int duration = resolveDurationSeconds(params);
            List<String> dbGroups = memberList(params, "DbGroups");

            TempCredential credential = credentialBroker.issue(
                    regionResolver.getAccountId(), clusterId, effectiveDbUser, dbGroups, duration);
            return Response.ok(getClusterCredentialsXml("GetClusterCredentials", credential))
                    .type(MediaType.APPLICATION_XML).build();
        }
        case "GetClusterCredentialsWithIAM" -> {
            String clusterId = requireParam(params, "ClusterIdentifier");
            service.describeClusters(clusterId);
            String dbUser = iamDbUserResolver.resolveDbUser(authorizationHeader);
            int duration = resolveDurationSeconds(params);
            List<String> dbGroups = memberList(params, "DbGroups");

            TempCredential credential = credentialBroker.issue(
                    regionResolver.getAccountId(), clusterId, dbUser, dbGroups, duration);
            return Response.ok(getClusterCredentialsXml("GetClusterCredentialsWithIAM", credential))
                    .type(MediaType.APPLICATION_XML).build();
        }
        default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        }
    }

    private static String requireParam(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidParameterValue", name + " is required", 400);
        }
        return value;
    }

    private int resolveDurationSeconds(MultivaluedMap<String, String> params) {
        String raw = params.getFirst("DurationSeconds");
        if (raw == null || raw.isBlank()) {
            return defaultDurationSeconds();
        }
        int duration;
        try {
            duration = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", "DurationSeconds must be an integer", 400);
        }
        if (duration < MIN_CREDENTIAL_DURATION_SECONDS || duration > MAX_CREDENTIAL_DURATION_SECONDS) {
            throw new AwsException("InvalidParameterValue",
                    "DurationSeconds must be between " + MIN_CREDENTIAL_DURATION_SECONDS
                            + " and " + MAX_CREDENTIAL_DURATION_SECONDS, 400);
        }
        return duration;
    }

    // The YAML default is operator-supplied, so hold it to the same AWS bounds as a request
    // value: an out-of-range override falls back to the AWS minimum rather than minting a
    // credential that is already expired or outlives the documented range.
    private int defaultDurationSeconds() {
        int configured = config.services().redshift().defaultCredentialDurationSeconds();
        if (configured < MIN_CREDENTIAL_DURATION_SECONDS || configured > MAX_CREDENTIAL_DURATION_SECONDS) {
            LOG.warnv("floci.services.redshift.default-credential-duration-seconds={0} is outside the "
                    + "AWS range {1} to {2}; using {1}", configured,
                    MIN_CREDENTIAL_DURATION_SECONDS, MAX_CREDENTIAL_DURATION_SECONDS);
            return MIN_CREDENTIAL_DURATION_SECONDS;
        }
        return configured;
    }

    private String getClusterCredentialsXml(String operation, TempCredential credential) {
        XmlBuilder builder = new XmlBuilder()
                .start(operation + "Response")
                  .start(operation + "Result")
                    .elem("DbUser", credential.dbUser())
                    .elem("DbPassword", credential.password())
                    .elem("Expiration", DateTimeFormatter.ISO_INSTANT.format(credential.expiresAt()));
        if (!credential.dbGroups().isEmpty()) {
            builder.start("DbGroups");
            for (String group : credential.dbGroups()) {
                builder.elem("DbGroup", group);
            }
            builder.end("DbGroups");
        }
        return builder
                  .end(operation + "Result")
                  .start("ResponseMetadata")
                    .elem("RequestId", "test-req-id")
                  .end("ResponseMetadata")
                .end(operation + "Response")
                .build();
    }

    private String buildClusterXml(Cluster cluster) {
        XmlBuilder builder = new XmlBuilder()
            .start("Cluster")
            .elem("ClusterIdentifier", cluster.getClusterIdentifier())
            .elem("NodeType", cluster.getNodeType())
            .elem("MasterUsername", cluster.getMasterUsername())
            .elem("ClusterStatus", cluster.getClusterStatus())
            .elem("ClusterAvailabilityStatus", availabilityStatus(cluster.getClusterStatus()))
            .elem("AvailabilityZoneRelocationStatus", "disabled")
            .elem("ClusterSubnetGroupName", cluster.getClusterSubnetGroupName());

        if (cluster.getVpcSecurityGroupIds() != null && !cluster.getVpcSecurityGroupIds().isEmpty()) {
            builder.start("VpcSecurityGroups");
            for (String sgId : cluster.getVpcSecurityGroupIds()) {
                builder.start("VpcSecurityGroup").elem("VpcSecurityGroupId", sgId).end("VpcSecurityGroup");
            }
            builder.end("VpcSecurityGroups");
        }

        if (cluster.getIamRoleArns() != null && !cluster.getIamRoleArns().isEmpty()) {
            builder.start("IamRoles");
            for (String iamRoleArn : cluster.getIamRoleArns()) {
                builder.start("IamRole").elem("IamRoleArn", iamRoleArn).end("IamRole");
            }
            builder.end("IamRoles");
        }

        if (cluster.getClusterParameterGroupName() != null) {
            builder.start("ClusterParameterGroups")
                .start("ClusterParameterGroup")
                  .elem("ParameterGroupName", cluster.getClusterParameterGroupName())
                  .elem("ParameterApplyStatus", "in-sync")
                .end("ClusterParameterGroup")
              .end("ClusterParameterGroups");
        }

        if (cluster.getTags() != null && !cluster.getTags().isEmpty()) {
            builder.start("Tags");
            for (Map.Entry<String, String> tag : cluster.getTags().entrySet()) {
                builder.start("Tag")
                    .elem("Key", tag.getKey())
                    .elem("Value", tag.getValue())
                  .end("Tag");
            }
            builder.end("Tags");
        }

        if (cluster.getEndpoint() != null) {
            builder.start("Endpoint")
                .elem("Address", cluster.getEndpoint().getAddress())
                .elem("Port", String.valueOf(cluster.getEndpoint().getPort()))
                .end("Endpoint");
        }

        return builder.end("Cluster").build();
    }

    // Terraform's AWS provider polls ClusterAvailabilityStatus during create and validates it on
    // read, so DescribeClusters must always carry it. Floci has no maintenance window concept, and
    // every transient lifecycle state (creating, deleting, rebooting, modifying) maps to Modifying.
    private static String availabilityStatus(String clusterStatus) {
        if (clusterStatus == null) {
            return "Modifying";
        }
        return switch (clusterStatus) {
            case "available" -> "Available";
            case "unavailable" -> "Unavailable";
            case "failed" -> "Failed";
            default -> "Modifying";
        };
    }

    private String buildSnapshotXml(Snapshot snapshot) {
        XmlBuilder builder = new XmlBuilder()
            .start("Snapshot")
            .elem("SnapshotIdentifier", snapshot.getSnapshotIdentifier())
            .elem("ClusterIdentifier", snapshot.getClusterIdentifier())
            .elem("Status", snapshot.getStatus())
            .elem("Port", String.valueOf(snapshot.getPort()))
            .elem("MasterUsername", snapshot.getMasterUsername());
        
        return builder.end("Snapshot").build();
    }

    private String buildClusterParameterGroupXml(ClusterParameterGroup group) {
        XmlBuilder builder = new XmlBuilder()
            .start("ClusterParameterGroup")
            .elem("ParameterGroupName", group.getParameterGroupName())
            .elem("ParameterGroupFamily", group.getParameterGroupFamily())
            .elem("Description", group.getDescription());
        
        return builder.end("ClusterParameterGroup").build();
    }

    private String buildClusterSubnetGroupXml(ClusterSubnetGroup group) {
        XmlBuilder builder = new XmlBuilder()
            .start("ClusterSubnetGroup")
            .elem("ClusterSubnetGroupName", group.getClusterSubnetGroupName())
            .elem("Description", group.getDescription())
            .elem("VpcId", group.getVpcId())
            .start("Subnets");
        for (String subnetId : group.getSubnetIds()) {
            builder.start("Subnet").elem("SubnetIdentifier", subnetId).end("Subnet");
        }
        return builder.end("Subnets").end("ClusterSubnetGroup").build();
    }

    private String buildParameterXml(Parameter param) {
        XmlBuilder builder = new XmlBuilder()
            .start("Parameter")
            .elem("ParameterName", param.getParameterName())
            .elem("ParameterValue", param.getParameterValue());

        if (param.getDescription() != null) {
            builder.elem("Description", param.getDescription());
        }
        if (param.getDataType() != null) {
            builder.elem("DataType", param.getDataType());
        }
        return builder.end("Parameter").build();
    }


    /**
     * Renders one integration. {@code Errors} is emitted even when empty, which is what a live
     * integration returns, and {@code Status} stays lower case for the same reason.
     */
    private String buildIntegrationXml(Integration integration, boolean includeErrors) {
        XmlBuilder builder = new XmlBuilder()
                .start("Integration")
                  .elem("IntegrationArn", integration.getIntegrationArn())
                  .elem("IntegrationName", integration.getIntegrationName())
                  .elem("SourceArn", integration.getSourceArn())
                  .elem("TargetArn", integration.getTargetArn())
                  .elem("Status", integration.getStatus())
                  .elem("CreateTime", integration.getCreateTime());
        if (integration.getDescription() != null) {
            builder.elem("Description", integration.getDescription());
        }
        if (integration.getKmsKeyId() != null) {
            builder.elem("KMSKeyId", integration.getKmsKeyId());
        }
        if (integration.getAdditionalEncryptionContext() != null
                && !integration.getAdditionalEncryptionContext().isEmpty()) {
            builder.start("AdditionalEncryptionContext");
            for (Map.Entry<String, String> entry : integration.getAdditionalEncryptionContext().entrySet()) {
                builder.start("entry")
                    .elem("key", entry.getKey())
                    .elem("value", entry.getValue())
                  .end("entry");
            }
            builder.end("AdditionalEncryptionContext");
        }
        if (includeErrors) {
            builder.start("Errors").end("Errors");
        }
        if (integration.getTags() != null && !integration.getTags().isEmpty()) {
            builder.start("Tags");
            for (Map.Entry<String, String> tag : integration.getTags().entrySet()) {
                builder.start("Tag")
                    .elem("Key", tag.getKey())
                    .elem("Value", tag.getValue())
                  .end("Tag");
            }
            builder.end("Tags");
        }
        return builder.end("Integration").build();
    }

    /**
     * Reads the {@code TagList.Tag.N.Key} / {@code .Value} pairs of a Query request.
     *
     * <p>The member is {@code TagList}, not {@code Tags}: an SDK serialises the list under its own
     * member name, so reading {@code Tags.Tag.N} silently drops every tag a real client sends.
     */
    private static Map<String, String> tagMap(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (String key : params.keySet()) {
            if (key.matches("TagList\\.Tag\\.\\d+\\.Key")) {
                String value = params.getFirst(key.replaceAll("\\.Key$", ".Value"));
                String name = params.getFirst(key);
                if (name != null && !name.isBlank()) {
                    tags.put(name, value == null ? "" : value);
                }
            }
        }
        return tags;
    }

    /** Reads an {@code AdditionalEncryptionContext.entry.N.key} / {@code .value} map. */
    private static Map<String, String> encryptionContextMap(MultivaluedMap<String, String> params) {
        Map<String, String> context = new LinkedHashMap<>();
        for (String key : params.keySet()) {
            if (key.matches("AdditionalEncryptionContext\\.entry\\.\\d+\\.key")) {
                String value = params.getFirst(key.replaceAll("\\.key$", ".value"));
                String name = params.getFirst(key);
                if (name != null && !name.isBlank()) {
                    context.put(name, value == null ? "" : value);
                }
            }
        }
        return context;
    }

    /** Reads {@code Filters.DescribeIntegrationsFilter.N.Name} and its {@code Values.Value.M} list. */
    private static List<RedshiftService.IntegrationFilter> integrationFilters(MultivaluedMap<String, String> params) {
        Map<String, RedshiftService.IntegrationFilter> byIndex = new LinkedHashMap<>();
        for (String key : params.keySet()) {
            java.util.regex.Matcher matcher = FILTER_NAME.matcher(key);
            if (!matcher.matches()) {
                continue;
            }
            String index = matcher.group(1);
            String name = params.getFirst(key);
            List<String> values = params.keySet().stream()
                    .filter(candidate -> candidate.matches(
                            "Filters\\.DescribeIntegrationsFilter\\." + index + "\\.Values\\.Value\\.\\d+"))
                    .sorted(Comparator.comparingInt(RedshiftQueryHandler::numericSuffix))
                    .map(params::getFirst)
                    .toList();
            byIndex.put(index, new RedshiftService.IntegrationFilter(name, values));
        }
        return List.copyOf(byIndex.values());
    }

    private static Integer intParam(MultivaluedMap<String, String> params, String name) {
        String raw = params.getFirst(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", name + " must be an integer.", 400);
        }
    }

    private static List<String> memberList(MultivaluedMap<String, String> params, String baseName) {
        return params.keySet().stream()
                .filter(key -> key.matches(memberKeyRegex(baseName)))
                .sorted(Comparator.comparingInt(RedshiftQueryHandler::numericSuffix))
                .map(params::getFirst)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    // The AWS Query protocol accepts both the generic ".member.N" form and each shape's own
    // locationName (e.g. the real Redshift SDK sends "SubnetIds.SubnetIdentifier.N", not "SubnetIds.member.N").
    private static String memberKeyRegex(String baseName) {
        String quoted = Pattern.quote(baseName);
        return switch (baseName) {
            case "SubnetIds" -> quoted + "(\\.member|\\.SubnetIdentifier)?\\.\\d+";
            case "VpcSecurityGroupIds" -> quoted + "(\\.member|\\.VpcSecurityGroupId)?\\.\\d+";
            case "IamRoles" -> quoted + "(\\.member|\\.IamRoleArn)?\\.\\d+";
            case "TagKeys" -> quoted + "(\\.member|\\.TagKey)?\\.\\d+";
            case "DbGroups" -> quoted + "(\\.member|\\.DbGroup)?\\.\\d+";
            default -> quoted + "(\\.member)?\\.\\d+";
        };
    }

    private static int numericSuffix(String key) {
        int lastDot = key.lastIndexOf('.');
        if (lastDot < 0 || lastDot == key.length() - 1) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(key.substring(lastDot + 1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static List<Parameter> parseParameters(MultivaluedMap<String, String> params) {
        List<Parameter> parsed = new ArrayList<>();
        readParameters(params, "Parameters.member", parsed);
        readParameters(params, "Parameters.Parameter", parsed);
        return parsed;
    }

    private static void readParameters(MultivaluedMap<String, String> params, String prefix, List<Parameter> parsed) {
        for (int i = 1; ; i++) {
            String name = params.getFirst(prefix + "." + i + ".ParameterName");
            if (name == null) {
                break;
            }
            String value = params.getFirst(prefix + "." + i + ".ParameterValue");
            parsed.add(new Parameter(name, value));
        }
    }

    private static Map<String, String> parseTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        readTags(params, "Tags.member", tags);
        readTags(params, "Tags.Tag", tags);
        return tags;
    }

    private static void readTags(MultivaluedMap<String, String> params, String prefix, Map<String, String> tags) {
        for (int i = 1; ; i++) {
            String key = params.getFirst(prefix + "." + i + ".Key");
            if (key == null) {
                break;
            }
            String value = params.getFirst(prefix + "." + i + ".Value");
            tags.put(key, value == null ? "" : value);
        }
    }

    private static Integer parseOptionalInteger(MultivaluedMap<String, String> params, String parameterName) {
        String value = params.getFirst(parameterName);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", parameterName + " must be an integer.", 400);
        }
    }
}
