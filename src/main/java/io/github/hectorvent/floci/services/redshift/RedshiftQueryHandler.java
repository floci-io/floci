package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryLists;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.redshift.RedshiftEventCatalog.EventInfo;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSubnetGroup;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.model.RedshiftEvent;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import io.github.hectorvent.floci.services.redshift.model.SnapshotCopyGrant;
import io.github.hectorvent.floci.services.redshift.model.SnapshotSchedule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class RedshiftQueryHandler {

    private static final Logger LOG = Logger.getLogger(RedshiftQueryHandler.class);

    private static final Pattern FILTER_NAME =
            Pattern.compile("Filters\\.DescribeIntegrationsFilter\\.(\\d+)\\.Name");

    private static final DateTimeFormatter EVENT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    // GetClusterCredentials DurationSeconds bounds, inclusive (AWS: 900 to 3600).
    private static final int MIN_CREDENTIAL_DURATION_SECONDS = 900;
    private static final int MAX_CREDENTIAL_DURATION_SECONDS = 3600;

    private final RedshiftService service;
    private final RedshiftCredentialBroker credentialBroker;
    private final EmulatorConfig config;
    private final RedshiftIamDbUserResolver iamDbUserResolver;
    private final RegionResolver regionResolver;
    private final RedshiftDynamoDbZeroEtlConsumer zeroEtlConsumer;
    private final IamEnforcementFilter iamEnforcementFilter;

    @Inject
    public RedshiftQueryHandler(RedshiftService service, RedshiftCredentialBroker credentialBroker,
                                EmulatorConfig config, RedshiftIamDbUserResolver iamDbUserResolver,
                                RegionResolver regionResolver,
                                RedshiftDynamoDbZeroEtlConsumer zeroEtlConsumer,
                                IamEnforcementFilter iamEnforcementFilter) {
        this.service = service;
        this.credentialBroker = credentialBroker;
        this.config = config;
        this.iamDbUserResolver = iamDbUserResolver;
        this.regionResolver = regionResolver;
        this.zeroEtlConsumer = zeroEtlConsumer;
        this.iamEnforcementFilter = iamEnforcementFilter;
    }

    RedshiftQueryHandler(RedshiftService service, RedshiftCredentialBroker credentialBroker,
                         EmulatorConfig config, RedshiftIamDbUserResolver iamDbUserResolver,
                         RegionResolver regionResolver) {
        this(service, credentialBroker, config, iamDbUserResolver, regionResolver, null, null);
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
            boolean manageMasterPassword = Boolean.parseBoolean(params.getFirst("ManageMasterPassword"));
            String clusterSubnetGroupName = params.getFirst("ClusterSubnetGroupName");
            List<String> vpcSecurityGroupIds = memberList(params, "VpcSecurityGroupIds");
            List<String> iamRoleArns = memberList(params, "IamRoles");

            String region = regionResolver.resolveRegionFromAuth(authorizationHeader);
            String defaultIamRoleArn = params.getFirst("DefaultIamRoleArn");
            Integer automatedRetention = parseOptionalInteger(params, "AutomatedSnapshotRetentionPeriod");
            Cluster cluster;
            if (manageMasterPassword) {
                cluster = automatedRetention == null
                        ? service.createClusterWithManagedMasterPassword(identifier, nodeType, masterUsername,
                                clusterSubnetGroupName, vpcSecurityGroupIds, iamRoleArns,
                                params.getFirst("MasterPasswordSecretKmsKeyId"), region, defaultIamRoleArn)
                        : service.createClusterWithManagedMasterPassword(identifier, nodeType, masterUsername,
                                clusterSubnetGroupName, vpcSecurityGroupIds, iamRoleArns,
                                params.getFirst("MasterPasswordSecretKmsKeyId"), region, defaultIamRoleArn, automatedRetention);
            } else {
                cluster = automatedRetention == null
                        ? service.createCluster(identifier, nodeType, masterUsername, masterUserPassword,
                                clusterSubnetGroupName, vpcSecurityGroupIds, iamRoleArns, defaultIamRoleArn)
                        : service.createCluster(identifier, nodeType, masterUsername, masterUserPassword,
                                clusterSubnetGroupName, vpcSecurityGroupIds, iamRoleArns, defaultIamRoleArn, automatedRetention);
            }
            String parameterGroupName = params.getFirst("ClusterParameterGroupName");
            String multiAz = params.getFirst("MultiAZ");
            boolean hasParameterGroup = parameterGroupName != null && !parameterGroupName.isBlank();
            if (hasParameterGroup || multiAz != null) {
                cluster = service.modifyCluster(identifier, null, null, null, parameterGroupName, null,
                        multiAz == null ? null : Boolean.parseBoolean(multiAz));
            }
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
            String rawSkip = params.getFirst("SkipFinalClusterSnapshot");
            if (rawSkip != null && !"true".equalsIgnoreCase(rawSkip) && !"false".equalsIgnoreCase(rawSkip)) {
                throw new AwsException("InvalidParameterValue", "SkipFinalClusterSnapshot must be a boolean.", 400);
            }
            Cluster cluster = service.deleteCluster(identifier, rawSkip == null ? null : Boolean.valueOf(rawSkip),
                    params.getFirst("FinalClusterSnapshotIdentifier"), intParam(params, "FinalClusterSnapshotRetentionPeriod"));
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
            String snapshotType = params.getFirst("SnapshotType");
            if (snapshotType != null) {
                if (!List.of("manual", "automated").contains(snapshotType)) {
                    throw new AwsException("InvalidParameterValue", "SnapshotType must be manual or automated", 400);
                }
                snapshots = snapshots.stream().filter(snapshot -> snapshotType.equals(snapshot.getSnapshotType())).toList();
            }
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
            String snapshotArn = params.getFirst("SnapshotArn");
            String nodeType = params.getFirst("NodeType");
            Cluster cluster = service.restoreFromClusterSnapshot(
                    clusterIdentifier, resolveSnapshotIdentifier(snapshotIdentifier, snapshotArn, authorizationHeader), nodeType);
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
            String source = params.getFirst("Source");
            if (source != null) {
                if (!"user".equals(source) && !"engine-default".equals(source)) {
                    throw new AwsException("InvalidParameterValue", "Source must be user or engine-default.", 400);
                }
                parameters = parameters.stream().filter(parameter -> source.equals(parameter.getSource())).toList();
            }

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
        case "ResetClusterParameterGroup" -> {
            List<String> parameterNames = parseParameters(params).stream().map(Parameter::getParameterName).toList();
            String rawResetAll = params.getFirst("ResetAllParameters");
            if (rawResetAll != null && !"true".equalsIgnoreCase(rawResetAll) && !"false".equalsIgnoreCase(rawResetAll)) {
                throw new AwsException("InvalidParameterValue", "ResetAllParameters must be a boolean.", 400);
            }
            boolean resetAll = rawResetAll == null ? parameterNames.isEmpty() : Boolean.parseBoolean(rawResetAll);
            String parameterGroupName = params.getFirst("ParameterGroupName");
            service.resetClusterParameterGroup(parameterGroupName, resetAll, parameterNames);
            String xml = new XmlBuilder()
                    .start("ResetClusterParameterGroupResponse", AwsNamespaces.REDSHIFT)
                      .start("ResetClusterParameterGroupResult")
                        .elem("ParameterGroupName", parameterGroupName)
                        .elem("ParameterGroupStatus", "Your parameter group has been updated.")
                      .end("ResetClusterParameterGroupResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("ResetClusterParameterGroupResponse")
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
            if (zeroEtlConsumer != null) {
                zeroEtlConsumer.startPolling(integration);
            }
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
            if (zeroEtlConsumer != null) {
                zeroEtlConsumer.stopPolling(integration.getIntegrationArn());
            }
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
        case "CreateSnapshotCopyGrant" -> {
            String name = params.getFirst("SnapshotCopyGrantName");
            if (name == null || name.isBlank()) {
                throw new AwsException("InvalidParameterValue", "SnapshotCopyGrantName is required", 400);
            }
            String kmsKeyId = params.getFirst("KmsKeyId");
            SnapshotCopyGrant grant = service.createSnapshotCopyGrant(name, kmsKeyId, parseTags(params));
            String xml = new XmlBuilder()
                    .start("CreateSnapshotCopyGrantResponse")
                      .start("CreateSnapshotCopyGrantResult")
                        .raw(buildSnapshotCopyGrantXml(grant))
                      .end("CreateSnapshotCopyGrantResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateSnapshotCopyGrantResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeSnapshotCopyGrants" -> {
            String name = params.getFirst("SnapshotCopyGrantName");
            Integer maxRecords = parseOptionalInteger(params, "MaxRecords");
            String marker = params.getFirst("Marker");
            PaginatedResult<SnapshotCopyGrant> page = service.describeSnapshotCopyGrants(name, maxRecords, marker);
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeSnapshotCopyGrantsResponse")
                      .start("DescribeSnapshotCopyGrantsResult");
            // AWS returns Marker only while further pages remain, and a paginating client
            // stops when it is absent.
            if (page.nextToken() != null) {
                xmlBuilder.elem("Marker", page.nextToken());
            }
            xmlBuilder.start("SnapshotCopyGrants");
            for (SnapshotCopyGrant grant : page.items()) {
                xmlBuilder.raw(buildSnapshotCopyGrantXml(grant));
            }
            String xml = xmlBuilder
                        .end("SnapshotCopyGrants")
                      .end("DescribeSnapshotCopyGrantsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeSnapshotCopyGrantsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteSnapshotCopyGrant" -> {
            String name = params.getFirst("SnapshotCopyGrantName");
            if (name == null || name.isBlank()) {
                throw new AwsException("InvalidParameterValue", "SnapshotCopyGrantName is required", 400);
            }
            service.deleteSnapshotCopyGrant(name);
            String xml = new XmlBuilder()
                    .start("DeleteSnapshotCopyGrantResponse")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteSnapshotCopyGrantResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeEvents" -> {
            PaginatedResult<RedshiftEvent> page = service.describeEvents(params.getFirst("SourceIdentifier"),
                    params.getFirst("SourceType"), timestampParam(params, "StartTime"),
                    timestampParam(params, "EndTime"), intParam(params, "Duration"),
                    intParam(params, "MaxRecords"), params.getFirst("Marker"));
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeEventsResponse", AwsNamespaces.REDSHIFT)
                      .start("DescribeEventsResult");
            if (page.nextToken() != null) {
                xmlBuilder.elem("Marker", page.nextToken());
            }
            xmlBuilder.start("Events");
            for (RedshiftEvent event : page.items()) {
                xmlBuilder.raw(buildEventXml(event));
            }
            String xml = xmlBuilder
                        .end("Events")
                      .end("DescribeEventsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeEventsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeEventCategories" -> {
            Map<String, List<EventInfo>> bySourceType = service.describeEventCategories(params.getFirst("SourceType"));
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeEventCategoriesResponse", AwsNamespaces.REDSHIFT)
                      .start("DescribeEventCategoriesResult")
                        .start("EventCategoriesMapList");
            for (Map.Entry<String, List<EventInfo>> entry : bySourceType.entrySet()) {
                xmlBuilder.start("EventCategoriesMap")
                            .elem("SourceType", entry.getKey())
                            .start("Events");
                for (EventInfo info : entry.getValue()) {
                    xmlBuilder.start("EventInfoMap")
                                .elem("Severity", info.severity())
                                .elem("EventDescription", info.description())
                                .elem("EventId", info.id())
                                .start("EventCategories")
                                  .elem("EventCategory", info.category())
                                .end("EventCategories")
                              .end("EventInfoMap");
                }
                xmlBuilder.end("Events").end("EventCategoriesMap");
            }
            String xml = xmlBuilder
                        .end("EventCategoriesMapList")
                      .end("DescribeEventCategoriesResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeEventCategoriesResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "CreateEventSubscription" -> {
            EventSubscription subscription = service.createEventSubscription(
                    params.getFirst("SubscriptionName"), params.getFirst("SnsTopicArn"),
                    params.getFirst("SourceType"), memberList(params, "SourceIds"),
                    memberList(params, "EventCategories"), params.getFirst("Severity"),
                    booleanParam(params, "Enabled"), parseTags(params));
            return eventSubscriptionResponse(action, subscription);
        }
        case "ModifyEventSubscription" -> {
            EventSubscription subscription = service.modifyEventSubscription(
                    params.getFirst("SubscriptionName"), params.getFirst("SnsTopicArn"),
                    params.getFirst("SourceType"), optionalEventList(params, "SourceIds"),
                    optionalEventList(params, "EventCategories"), params.getFirst("Severity"),
                    booleanParam(params, "Enabled"));
            return eventSubscriptionResponse(action, subscription);
        }
        case "DeleteEventSubscription" -> {
            service.deleteEventSubscription(params.getFirst("SubscriptionName"));
            String xml = new XmlBuilder()
                    .start("DeleteEventSubscriptionResponse", AwsNamespaces.REDSHIFT)
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteEventSubscriptionResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeEventSubscriptions" -> {
            PaginatedResult<EventSubscription> page = service.describeEventSubscriptions(
                    params.getFirst("SubscriptionName"), memberList(params, "TagKeys"),
                    memberList(params, "TagValues"), intParam(params, "MaxRecords"), params.getFirst("Marker"));
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeEventSubscriptionsResponse", AwsNamespaces.REDSHIFT)
                      .start("DescribeEventSubscriptionsResult");
            if (page.nextToken() != null) {
                xmlBuilder.elem("Marker", page.nextToken());
            }
            xmlBuilder.start("EventSubscriptionsList");
            for (EventSubscription subscription : page.items()) {
                xmlBuilder.raw(buildEventSubscriptionXml(subscription));
            }
            String xml = xmlBuilder
                        .end("EventSubscriptionsList")
                      .end("DescribeEventSubscriptionsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeEventSubscriptionsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "EnableSnapshotCopy" -> {
            Cluster cluster = service.enableSnapshotCopy(requireParam(params, "ClusterIdentifier"),
                    requireParam(params, "DestinationRegion"),
                    intParam(params, "RetentionPeriod"),
                    intParam(params, "ManualSnapshotRetentionPeriod"),
                    params.getFirst("SnapshotCopyGrantName"));
            return clusterResponse(action, cluster);
        }
        case "DisableSnapshotCopy" -> {
            return clusterResponse(action, service.disableSnapshotCopy(requireParam(params, "ClusterIdentifier")));
        }
        case "ModifySnapshotCopyRetentionPeriod" -> {
            String clusterIdentifier = requireParam(params, "ClusterIdentifier");
            requireParam(params, "RetentionPeriod");
            Cluster cluster = service.modifySnapshotCopyRetentionPeriod(clusterIdentifier,
                    intParam(params, "RetentionPeriod"), Boolean.parseBoolean(params.getFirst("Manual")));
            return clusterResponse(action, cluster);
        }
        case "CreateSnapshotSchedule" -> {
            SnapshotSchedule schedule = service.createSnapshotSchedule(params.getFirst("ScheduleIdentifier"),
                    params.getFirst("ScheduleDescription"), memberList(params, "ScheduleDefinitions"),
                    parseTags(params));
            return snapshotScheduleResponse(action, schedule);
        }
        case "ModifySnapshotSchedule" -> {
            SnapshotSchedule schedule = service.modifySnapshotSchedule(requireParam(params, "ScheduleIdentifier"),
                    memberList(params, "ScheduleDefinitions"));
            return snapshotScheduleResponse(action, schedule);
        }
        case "DescribeSnapshotSchedules" -> {
            PaginatedResult<SnapshotSchedule> page = service.describeSnapshotSchedules(
                    params.getFirst("ScheduleIdentifier"), params.getFirst("ClusterIdentifier"),
                    memberList(params, "TagKeys"), memberList(params, "TagValues"),
                    intParam(params, "MaxRecords"), params.getFirst("Marker"));
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeSnapshotSchedulesResponse")
                      .start("DescribeSnapshotSchedulesResult")
                        .start("SnapshotSchedules");
            for (SnapshotSchedule schedule : page.items()) {
                xmlBuilder.start("SnapshotSchedule")
                        .raw(buildSnapshotScheduleFields(schedule))
                        .end("SnapshotSchedule");
            }
            xmlBuilder.end("SnapshotSchedules");
            if (page.nextToken() != null) {
                xmlBuilder.elem("Marker", page.nextToken());
            }
            String xml = xmlBuilder
                      .end("DescribeSnapshotSchedulesResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeSnapshotSchedulesResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteSnapshotSchedule" -> {
            service.deleteSnapshotSchedule(requireParam(params, "ScheduleIdentifier"));
            return emptyResponse(action);
        }
        case "ModifyClusterSnapshotSchedule" -> {
            service.modifyClusterSnapshotSchedule(requireParam(params, "ClusterIdentifier"),
                    params.getFirst("ScheduleIdentifier"),
                    Boolean.parseBoolean(params.getFirst("DisassociateSchedule")));
            return emptyResponse(action);
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
            String multiAz = params.getFirst("MultiAZ");
            Integer automatedRetention = parseOptionalInteger(params, "AutomatedSnapshotRetentionPeriod");
            Cluster cluster = automatedRetention == null
                    ? service.modifyCluster(clusterIdentifier, nodeType, numberOfNodes,
                            masterUserPassword, clusterParameterGroupName, vpcSecurityGroupIds,
                            multiAz == null ? null : Boolean.parseBoolean(multiAz))
                    : service.modifyCluster(clusterIdentifier, nodeType, numberOfNodes,
                            masterUserPassword, clusterParameterGroupName, vpcSecurityGroupIds,
                            multiAz == null ? null : Boolean.parseBoolean(multiAz), automatedRetention);
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
        case "DescribeClusterVersions" -> {
            String requested = params.getFirst("ClusterVersion");
            XmlBuilder builder = new XmlBuilder()
                    .start("DescribeClusterVersionsResponse")
                      .start("DescribeClusterVersionsResult")
                        .start("ClusterVersions");
            if (requested == null || requested.isBlank() || RedshiftClusterCatalog.CLUSTER_VERSION.equals(requested)) {
                builder.start("ClusterVersion")
                        .elem("ClusterVersion", RedshiftClusterCatalog.CLUSTER_VERSION)
                        .elem("ClusterParameterGroupFamily", RedshiftClusterCatalog.PARAMETER_GROUP_FAMILY)
                        .elem("Description", "Amazon Redshift emulated engine")
                        .end("ClusterVersion");
            }
            String xml = builder
                        .end("ClusterVersions")
                      .end("DescribeClusterVersionsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeClusterVersionsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeOrderableClusterOptions" -> {
            String zone = regionResolver.resolveRegionFromAuth(authorizationHeader) + "a";
            XmlBuilder builder = new XmlBuilder()
                    .start("DescribeOrderableClusterOptionsResponse")
                      .start("DescribeOrderableClusterOptionsResult")
                        .start("OrderableClusterOptions");
            for (RedshiftClusterCatalog.OrderableOption option : RedshiftClusterCatalog.orderableOptions(
                    params.getFirst("ClusterVersion"), params.getFirst("NodeType"))) {
                builder.start("OrderableClusterOption")
                        .elem("ClusterVersion", RedshiftClusterCatalog.CLUSTER_VERSION)
                        .elem("ClusterType", option.clusterType())
                        .elem("NodeType", option.nodeType())
                        .start("AvailabilityZones")
                          .start("AvailabilityZone").elem("Name", zone).end("AvailabilityZone")
                        .end("AvailabilityZones")
                        .end("OrderableClusterOption");
            }
            String xml = builder
                        .end("OrderableClusterOptions")
                      .end("DescribeOrderableClusterOptionsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeOrderableClusterOptionsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "ModifyClusterIamRoles" -> {
            String clusterIdentifier = params.getFirst("ClusterIdentifier");
            if (clusterIdentifier == null || clusterIdentifier.isBlank()) {
                throw new AwsException("InvalidParameterValue", "ClusterIdentifier is required", 400);
            }
            Cluster cluster = service.modifyClusterIamRoles(clusterIdentifier,
                    memberList(params, "AddIamRoles"), memberList(params, "RemoveIamRoles"),
                    params.getFirst("DefaultIamRoleArn"));
            String xml = new XmlBuilder()
                    .start("ModifyClusterIamRolesResponse")
                      .start("ModifyClusterIamRolesResult")
                        .raw(buildClusterXml(cluster))
                      .end("ModifyClusterIamRolesResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("ModifyClusterIamRolesResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeLoggingStatus" -> {
            String clusterIdentifier = requireParam(params, "ClusterIdentifier");
            return loggingStatusResponse(action, service.describeLoggingStatus(clusterIdentifier));
        }
        case "EnableLogging" -> {
            String clusterIdentifier = requireParam(params, "ClusterIdentifier");
            Cluster cluster = service.enableLogging(clusterIdentifier, params.getFirst("BucketName"),
                    params.getFirst("S3KeyPrefix"), params.getFirst("LogDestinationType"),
                    memberList(params, "LogExports"),
                    params.getFirst("S3TableKmsKeyId"),
                    params.getFirst("S3TableGranularity"));
            return loggingStatusResponse(action, cluster);
        }
        case "DisableLogging" -> {
            String clusterIdentifier = requireParam(params, "ClusterIdentifier");
            return loggingStatusResponse(action, service.disableLogging(clusterIdentifier));
        }
        case "PauseCluster", "ResumeCluster" -> {
            String clusterIdentifier = requireParam(params, "ClusterIdentifier");
            Cluster cluster = "PauseCluster".equals(action)
                    ? service.pauseCluster(clusterIdentifier) : service.resumeCluster(clusterIdentifier);
            String xml = new XmlBuilder()
                    .start(action + "Response", AwsNamespaces.REDSHIFT)
                      .start(action + "Result")
                        .raw(buildClusterXml(cluster))
                      .end(action + "Result")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end(action + "Response")
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
            boolean autoCreate = Boolean.parseBoolean(params.getFirst("AutoCreate"));
            List<String> dbGroups = memberList(params, "DbGroups");
            authorizeCredentialOptions(authorizationHeader, clusterId, dbUser, autoCreate, dbGroups);
            // describeClusters throws ClusterNotFound (404) for an unknown id.
            service.describeClusters(clusterId);
            // AWS prefixes the returned name IAMA: when AutoCreate is true, IAM: when it is false.
            String effectiveDbUser = (autoCreate ? "IAMA:" : "IAM:") + dbUser;
            int duration = resolveDurationSeconds(params);

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

    /**
     * Authorizes the actions GetClusterCredentials needs besides redshift:GetClusterCredentials, which
     * the API reference lists for its options: redshift:JoinGroup on each group in {@code DbGroups},
     * and redshift:CreateClusterUser on the database user when {@code AutoCreate} is true. It runs
     * before the cluster lookup, so a denied caller learns nothing about the cluster.
     */
    private void authorizeCredentialOptions(String authorizationHeader, String clusterId, String dbUser,
                                            boolean autoCreate, List<String> dbGroups) {
        if (iamEnforcementFilter == null) {
            return;
        }
        String region = regionResolver.resolveRegionFromAuth(authorizationHeader);
        for (String dbGroup : dbGroups) {
            iamEnforcementFilter.authorizeAdditionalResource(authorizationHeader, "redshift:JoinGroup",
                    regionResolver.buildArn("redshift", region, "dbgroup:" + clusterId + "/" + dbGroup));
        }
        if (autoCreate) {
            iamEnforcementFilter.authorizeAdditionalResource(authorizationHeader, "redshift:CreateClusterUser",
                    regionResolver.buildArn("redshift", region, "dbuser:" + clusterId + "/" + dbUser));
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
            .elem("AutomatedSnapshotRetentionPeriod", String.valueOf(cluster.getAutomatedSnapshotRetentionPeriod()))
            .elem("ClusterAvailabilityStatus", availabilityStatus(cluster.getClusterStatus()))
            .elem("AvailabilityZoneRelocationStatus", "disabled")
            // AWS always returns "enabled" or "disabled" here, never blank.
            .elem("MultiAZ", cluster.isMultiAZ() ? "enabled" : "disabled")
            .elem("ClusterSubnetGroupName", cluster.getClusterSubnetGroupName());

        if (cluster.getMasterPasswordSecretArn() != null) {
            builder.elem("MasterPasswordSecretArn", cluster.getMasterPasswordSecretArn())
                .elem("MasterPasswordSecretKmsKeyId", cluster.getMasterPasswordSecretKmsKeyId());
        }

        if (cluster.getVpcSecurityGroupIds() != null && !cluster.getVpcSecurityGroupIds().isEmpty()) {
            builder.start("VpcSecurityGroups");
            for (String sgId : cluster.getVpcSecurityGroupIds()) {
                builder.start("VpcSecurityGroup").elem("VpcSecurityGroupId", sgId).end("VpcSecurityGroup");
            }
            builder.end("VpcSecurityGroups");
        }

        if (cluster.getDefaultIamRoleArn() != null) {
            builder.elem("DefaultIamRoleArn", cluster.getDefaultIamRoleArn());
        }
        if (cluster.getIamRoleArns() != null && !cluster.getIamRoleArns().isEmpty()) {
            builder.start("IamRoles");
            for (String iamRoleArn : cluster.getIamRoleArns()) {
                builder.start("IamRole").elem("IamRoleArn", iamRoleArn).elem("ApplyStatus", "in-sync").end("IamRole");
            }
            builder.end("IamRoles");
        }

        // Clusters persisted before the default was assigned on create have no name stored.
        String parameterGroupName = cluster.getClusterParameterGroupName() != null
                ? cluster.getClusterParameterGroupName()
                : RedshiftService.DEFAULT_PARAMETER_GROUP_NAME;
        builder.start("ClusterParameterGroups")
            .start("ClusterParameterGroup")
              .elem("ParameterGroupName", parameterGroupName)
              .elem("ParameterApplyStatus", "in-sync")
            .end("ClusterParameterGroup")
          .end("ClusterParameterGroups");

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

        if (cluster.getSnapshotCopyDestinationRegion() != null) {
            builder.start("ClusterSnapshotCopyStatus")
                .elem("DestinationRegion", cluster.getSnapshotCopyDestinationRegion())
                .elem("RetentionPeriod", String.valueOf(cluster.getSnapshotCopyRetentionPeriod()))
                .elem("ManualSnapshotRetentionPeriod", String.valueOf(cluster.getSnapshotCopyManualRetentionPeriod()))
                .elem("SnapshotCopyGrantName", cluster.getSnapshotCopyGrantName())
              .end("ClusterSnapshotCopyStatus");
        }

        if (cluster.getSnapshotScheduleIdentifier() != null) {
            builder.elem("SnapshotScheduleIdentifier", cluster.getSnapshotScheduleIdentifier())
                .elem("SnapshotScheduleState", "ACTIVE");
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

    // RestoreFromClusterSnapshot accepts SnapshotIdentifier or SnapshotArn.
    private String resolveSnapshotIdentifier(String snapshotIdentifier, String snapshotArn, String authorizationHeader) {
        boolean hasIdentifier = snapshotIdentifier != null && !snapshotIdentifier.isBlank();
        boolean hasArn = snapshotArn != null && !snapshotArn.isBlank();
        if (hasIdentifier && hasArn) {
            throw new AwsException("InvalidParameterCombination",
                    "You must specify either SnapshotIdentifier or SnapshotArn, but not both.", 400);
        }
        if (hasIdentifier) {
            return snapshotIdentifier;
        }
        if (!hasArn) {
            throw new AwsException("InvalidParameterValue", "SnapshotIdentifier or SnapshotArn is required", 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(snapshotArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue", "Invalid SnapshotArn: " + snapshotArn, 400);
        }
        // arn:aws:redshift:<region>:<account>:snapshot:<clusterIdentifier>/<snapshotIdentifier>
        String resource = arn.resource();
        int slash = resource.lastIndexOf('/');
        if (!"redshift".equals(arn.service()) || !resource.startsWith("snapshot:")
                || slash < 0 || slash == resource.length() - 1) {
            throw new AwsException("InvalidParameterValue", "Invalid SnapshotArn: " + snapshotArn, 400);
        }
        // An ARN from another account or region must not match a local snapshot by name.
        if (!arn.accountId().equals(regionResolver.getAccountId())
                || !arn.region().equals(regionResolver.resolveRegionFromAuth(authorizationHeader))) {
            throw new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotArn + " not found", 404);
        }
        return resource.substring(slash + 1);
    }

    private String buildSnapshotXml(Snapshot snapshot) {
        // Both are absent on snapshots persisted before these fields existed; omit them.
        String createTime = snapshot.getSnapshotCreateTime() != null
                ? DateTimeFormatter.ISO_INSTANT.format(snapshot.getSnapshotCreateTime())
                : null;
        XmlBuilder builder = new XmlBuilder()
            .start("Snapshot")
            .elem("SnapshotIdentifier", snapshot.getSnapshotIdentifier())
            .elem("ClusterIdentifier", snapshot.getClusterIdentifier())
            .elem("SnapshotArn", snapshot.getSnapshotArn())
            .elem("SnapshotCreateTime", createTime)
            .elem("SnapshotType", snapshot.getSnapshotType())
            .elem("ManualSnapshotRetentionPeriod", snapshot.getManualSnapshotRetentionPeriod().toString())
            .elem("Status", snapshot.getStatus())
            .elem("Port", String.valueOf(snapshot.getPort()))
            .elem("MasterUsername", snapshot.getMasterUsername());

        return builder.end("Snapshot").build();
    }

    private Response clusterResponse(String operation, Cluster cluster) {
        String xml = new XmlBuilder()
                .start(operation + "Response")
                  .start(operation + "Result")
                    .raw(buildClusterXml(cluster))
                  .end(operation + "Result")
                  .start("ResponseMetadata")
                    .elem("RequestId", "test-req-id")
                  .end("ResponseMetadata")
                .end(operation + "Response")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    // Create/ModifySnapshotSchedule return the SnapshotSchedule members directly inside the Result.
    private Response snapshotScheduleResponse(String operation, SnapshotSchedule schedule) {
        String xml = new XmlBuilder()
                .start(operation + "Response")
                  .start(operation + "Result")
                    .raw(buildSnapshotScheduleFields(schedule))
                  .end(operation + "Result")
                  .start("ResponseMetadata")
                    .elem("RequestId", "test-req-id")
                  .end("ResponseMetadata")
                .end(operation + "Response")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private static Response emptyResponse(String operation) {
        String xml = new XmlBuilder()
                .start(operation + "Response")
                  .start("ResponseMetadata")
                    .elem("RequestId", "test-req-id")
                  .end("ResponseMetadata")
                .end(operation + "Response")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    // No snapshots are taken on a schedule, so NextInvocations is omitted. An association
    // takes effect immediately, hence ACTIVE.
    private String buildSnapshotScheduleFields(SnapshotSchedule schedule) {
        XmlBuilder builder = new XmlBuilder().start("ScheduleDefinitions");
        for (String definition : schedule.getScheduleDefinitions()) {
            builder.elem("ScheduleDefinition", definition);
        }
        builder.end("ScheduleDefinitions")
            .elem("ScheduleIdentifier", schedule.getScheduleIdentifier())
            .elem("ScheduleDescription", schedule.getScheduleDescription());
        builder.start("Tags");
        for (Map.Entry<String, String> tag : schedule.getTags().entrySet()) {
            builder.start("Tag")
                .elem("Key", tag.getKey())
                .elem("Value", tag.getValue())
              .end("Tag");
        }
        builder.end("Tags");
        List<String> associated = service.clustersUsingSnapshotSchedule(schedule.getScheduleIdentifier());
        builder.elem("AssociatedClusterCount", associated.size()).start("AssociatedClusters");
        for (String clusterIdentifier : associated) {
            builder.start("ClusterAssociatedToSchedule")
                .elem("ClusterIdentifier", clusterIdentifier)
                .elem("ScheduleAssociationState", "ACTIVE")
              .end("ClusterAssociatedToSchedule");
        }
        return builder.end("AssociatedClusters").build();
    }

    private Response loggingStatusResponse(String operation, Cluster cluster) {
        String xml = new XmlBuilder()
                .start(operation + "Response")
                  .start(operation + "Result")
                    .raw(buildLoggingStatusXml(cluster))
                  .end(operation + "Result")
                  .start("ResponseMetadata")
                    .elem("RequestId", "test-req-id")
                  .end("ResponseMetadata")
                .end(operation + "Response")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    // No log delivery is emulated, so delivery timestamps and failure fields are omitted.
    private String buildLoggingStatusXml(Cluster cluster) {
        XmlBuilder builder = new XmlBuilder()
            .elem("LoggingEnabled", cluster.isLoggingEnabled())
            .elem("BucketName", cluster.getLoggingBucketName())
            .elem("S3KeyPrefix", cluster.getLoggingS3KeyPrefix())
            .elem("LogDestinationType", cluster.getLoggingDestinationType());
        if (cluster.getLoggingExports() != null) {
            builder.start("LogExports");
            for (String export : cluster.getLoggingExports()) {
                builder.elem("member", export);
            }
            builder.end("LogExports");
        }
        if ("s3table".equalsIgnoreCase(cluster.getLoggingDestinationType())) {
            builder.start("S3Tables");
            List<String> exports = cluster.getLoggingExports();
            if (exports != null && !exports.isEmpty()) {
                builder.start("S3Tables");
                for (String export : exports) {
                    builder.elem("member", export);
                }
                builder.end("S3Tables");
            }
            if (cluster.getLoggingS3TableGranularity() != null) {
                builder.elem("S3TableGranularity", cluster.getLoggingS3TableGranularity());
            }
            builder.elem("EnabledAll", exports == null || exports.isEmpty() || exports.contains("all"));
            builder.end("S3Tables");
        }
        return builder.build();
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

    private Response eventSubscriptionResponse(String operation, EventSubscription subscription) {
        String xml = new XmlBuilder()
                .start(operation + "Response", AwsNamespaces.REDSHIFT)
                  .start(operation + "Result")
                    .raw(buildEventSubscriptionXml(subscription))
                  .end(operation + "Result")
                  .start("ResponseMetadata")
                    .elem("RequestId", "test-req-id")
                  .end("ResponseMetadata")
                .end(operation + "Response")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private String buildEventSubscriptionXml(EventSubscription subscription) {
        XmlBuilder builder = new XmlBuilder()
                .start("EventSubscription")
                .elem("Severity", subscription.getSeverity())
                .elem("CustSubscriptionId", subscription.getCustSubscriptionId());
        if (subscription.getSourceType() != null) {
            builder.elem("SourceType", subscription.getSourceType());
        }
        builder.elem("SnsTopicArn", subscription.getSnsTopicArn())
                .start("SourceIdsList");
        for (String sourceId : subscription.getSourceIdsList()) {
            builder.elem("SourceId", sourceId);
        }
        builder.end("SourceIdsList").start("EventCategoriesList");
        for (String category : subscription.getEventCategoriesList()) {
            builder.elem("EventCategory", category);
        }
        builder.end("EventCategoriesList")
                .elem("SubscriptionCreationTime", EVENT_TIMESTAMP.format(subscription.getSubscriptionCreationTime()))
                .elem("Enabled", String.valueOf(subscription.isEnabled()))
                .start("Tags");
        for (Map.Entry<String, String> tag : subscription.getTags().entrySet()) {
            builder.start("Tag").elem("Key", tag.getKey()).elem("Value", tag.getValue()).end("Tag");
        }
        return builder.end("Tags")
                .elem("Status", subscription.getStatus())
                .elem("CustomerAwsId", subscription.getCustomerAwsId())
                .end("EventSubscription").build();
    }

    private String buildEventXml(RedshiftEvent event) {
        XmlBuilder builder = new XmlBuilder()
                .start("Event")
                .elem("Date", EVENT_TIMESTAMP.format(event.getDate()))
                .elem("Severity", event.getSeverity())
                .elem("EventId", event.getEventId())
                .elem("SourceIdentifier", event.getSourceIdentifier())
                .elem("SourceType", event.getSourceType())
                .start("EventCategories");
        for (String category : event.getEventCategories()) {
            builder.elem("EventCategory", category);
        }
        return builder.end("EventCategories")
                .elem("Message", event.getMessage())
                .end("Event").build();
    }

    private static Boolean booleanParam(MultivaluedMap<String, String> params, String name) {
        String raw = params.getFirst(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
            throw new AwsException("InvalidParameterValue", name + " must be true or false.", 400);
        }
        return Boolean.valueOf(raw);
    }

    private static List<String> optionalEventList(MultivaluedMap<String, String> params, String name) {
        return params.keySet().stream().anyMatch(key -> key.equals(name) || key.startsWith(name + "."))
                ? memberList(params, name) : null;
    }

    /** ISO 8601 as the SDKs send it, with or without seconds, or epoch seconds. */
    private static Instant timestampParam(MultivaluedMap<String, String> params, String name) {
        String raw = params.getFirst(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.ofEpochMilli(new BigDecimal(raw).movePointRight(3).longValueExact());
            } catch (NumberFormatException | ArithmeticException ignored) {
                // Neither form: the ISO 8601 failure below names the parameter.
                throw new AwsException("InvalidParameterValue", name + " must be an ISO 8601 timestamp.", 400);
            }
        }
    }

    private String buildSnapshotCopyGrantXml(SnapshotCopyGrant grant) {
        XmlBuilder builder = new XmlBuilder()
            .start("SnapshotCopyGrant")
            .elem("SnapshotCopyGrantName", grant.getSnapshotCopyGrantName())
            .elem("KmsKeyId", grant.getKmsKeyId());

        if (grant.getTags() != null && !grant.getTags().isEmpty()) {
            builder.start("Tags");
            for (Map.Entry<String, String> tag : grant.getTags().entrySet()) {
                builder.start("Tag")
                    .elem("Key", tag.getKey())
                    .elem("Value", tag.getValue())
                  .end("Tag");
            }
            builder.end("Tags");
        }

        return builder.end("SnapshotCopyGrant").build();
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
        builder.elem("Source", param.getSource());
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
            Matcher matcher = FILTER_NAME.matcher(key);
            if (!matcher.matches()) {
                continue;
            }
            String index = matcher.group(1);
            String name = params.getFirst(key);
            List<String> values = params.keySet().stream()
                    .filter(candidate -> candidate.matches(
                            "Filters\\.DescribeIntegrationsFilter\\." + index + "\\.Values\\.Value\\.\\d+"))
                    .sorted(Comparator.comparingInt(AwsQueryLists::memberIndex))
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
        return AwsQueryLists.values(params, baseName, memberName(baseName));
    }

    /**
     * The name the list {@code baseName}'s shape gives its members, which the real Redshift SDK sends
     * ("SubnetIds.SubnetIdentifier.N", not "SubnetIds.member.N"), or null for a list whose shape names none.
     */
    private static String memberName(String baseName) {
        return switch (baseName) {
            case "SubnetIds" -> "SubnetIdentifier";
            case "VpcSecurityGroupIds" -> "VpcSecurityGroupId";
            case "IamRoles", "AddIamRoles", "RemoveIamRoles" -> "IamRoleArn";
            case "TagKeys" -> "TagKey";
            case "TagValues" -> "TagValue";
            case "SourceIds" -> "SourceId";
            case "EventCategories" -> "EventCategory";
            case "ScheduleDefinitions" -> "ScheduleDefinition";
            case "DbGroups" -> "DbGroup";
            default -> null;
        };
    }

    private static List<Parameter> parseParameters(MultivaluedMap<String, String> params) {
        Map<String, Parameter> parsed = new LinkedHashMap<>();
        for (String key : params.keySet().stream().sorted().toList()) {
            if ("Parameters".equals(key)) {
                if (!"".equals(params.getFirst(key))) {
                    throw new AwsException("InvalidParameterValue", "Parameters must be an indexed list or empty.", 400);
                }
                continue;
            }
            if (!key.startsWith("Parameters.")) {
                continue;
            }
            if (!key.matches("Parameters\\.(?:member|Parameter)\\.[1-9]\\d*\\.[A-Za-z]+")) {
                throw new AwsException("InvalidParameterValue", "Invalid Parameters member: " + key, 400);
            }
            String prefix = key.substring(0, key.lastIndexOf('.'));
            String name = params.getFirst(prefix + ".ParameterName");
            if (name == null || name.isBlank()) {
                throw new AwsException("InvalidParameterValue", "Each parameter must have a ParameterName.", 400);
            }
            parsed.putIfAbsent(prefix, new Parameter(name, params.getFirst(prefix + ".ParameterValue")));
        }
        return parsed.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> AwsQueryLists.memberIndex(entry.getKey())))
                .map(Map.Entry::getValue).toList();
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
