package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.redshift.model.RedshiftCluster;
import io.github.hectorvent.floci.services.redshift.model.RedshiftParameter;
import io.github.hectorvent.floci.services.redshift.model.RedshiftParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.RedshiftSubnetGroup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Control plane for Amazon Redshift clusters, cluster subnet groups and cluster
 * parameter groups.
 *
 * <p>Redshift provisioning is not emulated, so a cluster reaches its terminal
 * {@code available} state on create and reports it from the first read: modelling the
 * {@code creating} transition would leave the SDK's {@code ClusterAvailable} waiter
 * polling for the full deadline against a cluster that will never change.
 */
@ApplicationScoped
public class RedshiftService implements Resettable {

    public static final String DEFAULT_PARAMETER_GROUP_FAMILY = "redshift-1.0";
    public static final String DEFAULT_PARAMETER_GROUP_NAME = "default.redshift-1.0";

    static final String RESOURCE_TYPE_CLUSTER = "cluster";
    static final String RESOURCE_TYPE_SUBNET_GROUP = "subnetgroup";
    static final String RESOURCE_TYPE_PARAMETER_GROUP = "parametergroup";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LOWER_ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int DEFAULT_PORT = 5439;
    private static final String DEFAULT_DB_NAME = "dev";
    private static final String DEFAULT_CLUSTER_VERSION = "1.0";
    private static final String DEFAULT_MAINTENANCE_TRACK = "current";
    private static final String DEFAULT_MAINTENANCE_WINDOW = "sat:05:00-sat:05:30";

    /**
     * Engine defaults for the {@code redshift-1.0} parameter group family, as AWS
     * reports them from {@code DescribeClusterParameters} with no {@code Source} filter.
     */
    private static final List<RedshiftParameter> ENGINE_DEFAULTS = List.of(
            engineDefault("auto_analyze", "true", "Use auto analyze", "boolean", "true,false", "dynamic"),
            engineDefault("auto_mv", "true", "Auto materialized views", "boolean", "true,false", "dynamic"),
            engineDefault("datestyle", "ISO, MDY", "Sets the display format for date and time values.",
                    "string", null, "dynamic"),
            engineDefault("enable_case_sensitive_identifier", "false",
                    "Enables case-sensitive identifiers", "boolean", "true,false", "dynamic"),
            engineDefault("enable_user_activity_logging", "false",
                    "parameter for audit logging purpose", "boolean", "true,false", "static"),
            engineDefault("extra_float_digits", "0",
                    "Sets the number of digits displayed for floating-point values", "integer", "-15-2", "dynamic"),
            engineDefault("max_concurrency_scaling_clusters", "1",
                    "Sets the maximum number of concurrency scaling clusters.", "integer", "0-10", "dynamic"),
            engineDefault("max_query_execution_time", "14400",
                    "Sets the max query execution time.", "integer", "0-86399", "dynamic"),
            engineDefault("query_group", "default", "This parameter applies a user-defined label to a group of "
                    + "queries that are run during the same session.", "string", null, "dynamic"),
            engineDefault("require_ssl", "false",
                    "require ssl for all databaseconnections", "boolean", "true,false", "static"),
            engineDefault("search_path", "$user, public",
                    "Sets the schema search order for names that are not schema-qualified.", "string", null, "dynamic"),
            engineDefault("statement_timeout", "0",
                    "Aborts any statement that takes over the specified number of milliseconds.",
                    "integer", "0,100-2147483647", "dynamic"),
            engineDefault("use_fips_ssl", "false", "Use fips ssl library", "boolean", "true,false", "static"),
            engineDefault("wlm_json_configuration", "[{\"auto_wlm\":true}]",
                    "wlm json configuration", "string", null, "dynamic")
    );

    private final RegionResolver regionResolver;
    private final StorageFactory storageFactory;
    private final Ec2Service ec2Service;

    private Map<String, RedshiftCluster> clusters = new ConcurrentHashMap<>();
    private Map<String, RedshiftSubnetGroup> subnetGroups = new ConcurrentHashMap<>();
    private Map<String, RedshiftParameterGroup> parameterGroups = new ConcurrentHashMap<>();

    @Inject
    RedshiftService(RegionResolver regionResolver, StorageFactory storageFactory, Ec2Service ec2Service) {
        this.regionResolver = regionResolver;
        this.storageFactory = storageFactory;
        this.ec2Service = ec2Service;
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return;
        }
        this.clusters = new StorageBackedMap<>(storageFactory.create("redshift", "redshift-clusters.json",
                new TypeReference<Map<String, RedshiftCluster>>() {}));
        this.subnetGroups = new StorageBackedMap<>(storageFactory.create("redshift", "redshift-subnet-groups.json",
                new TypeReference<Map<String, RedshiftSubnetGroup>>() {}));
        this.parameterGroups = new StorageBackedMap<>(storageFactory.create("redshift",
                "redshift-parameter-groups.json",
                new TypeReference<Map<String, RedshiftParameterGroup>>() {}));
    }

    @Override
    public void clear() {
        clusters.clear();
        subnetGroups.clear();
        parameterGroups.clear();
    }

    // ── clusters ──────────────────────────────────────────────────────────────

    public synchronized RedshiftCluster createCluster(String region, Map<String, String> scalars,
                                                      List<String> clusterSecurityGroups,
                                                      List<String> vpcSecurityGroupIds,
                                                      List<String> iamRoles,
                                                      Map<String, String> tags) {
        String identifier = scalars.get("ClusterIdentifier");
        validateClusterIdentifier(identifier);
        requireValue(scalars.get("NodeType"), "NodeType");
        requireValue(scalars.get("MasterUsername"), "MasterUsername");
        if (clusters.containsKey(key(region, identifier))) {
            throw new AwsException("ClusterAlreadyExists",
                    "Cluster already exists: " + identifier, 400);
        }

        String subnetGroupName = scalars.get("ClusterSubnetGroupName");
        RedshiftSubnetGroup subnetGroup = null;
        if (subnetGroupName != null && !subnetGroupName.isBlank()) {
            subnetGroup = requireSubnetGroup(region, subnetGroupName);
        }

        String parameterGroupName = scalars.get("ClusterParameterGroupName");
        if (parameterGroupName == null || parameterGroupName.isBlank()) {
            parameterGroupName = DEFAULT_PARAMETER_GROUP_NAME;
            ensureDefaultParameterGroup(region);
        } else {
            requireParameterGroup(region, parameterGroupName);
        }

        String clusterType = scalars.get("ClusterType");
        Integer requestedNodes = parseInt(scalars.get("NumberOfNodes"), "NumberOfNodes");
        if (clusterType == null || clusterType.isBlank()) {
            clusterType = requestedNodes != null && requestedNodes > 1 ? "multi-node" : "single-node";
        }
        int numberOfNodes = resolveNodeCount(clusterType, requestedNodes);

        RedshiftCluster cluster = new RedshiftCluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setNodeType(scalars.get("NodeType"));
        cluster.setClusterType(clusterType);
        cluster.setNumberOfNodes(numberOfNodes);
        cluster.setMasterUsername(scalars.get("MasterUsername"));
        cluster.setDbName(orDefault(scalars.get("DBName"), DEFAULT_DB_NAME));
        cluster.setClusterStatus("available");
        cluster.setClusterAvailabilityStatus("Available");
        cluster.setModifyStatus("");
        cluster.setClusterCreateTime(Instant.now());
        cluster.setEndpointAddress(endpointAddress(identifier, region));
        Integer port = parseInt(scalars.get("Port"), "Port");
        cluster.setEndpointPort(port != null ? port : DEFAULT_PORT);
        cluster.setClusterSubnetGroupName(subnetGroup != null ? subnetGroup.getClusterSubnetGroupName() : "");
        cluster.setVpcId(subnetGroup != null ? subnetGroup.getVpcId() : defaultVpcId(region));
        cluster.setAvailabilityZone(orDefault(scalars.get("AvailabilityZone"),
                subnetGroup != null ? firstAvailabilityZone(subnetGroup, region) : region + "a"));
        cluster.setPreferredMaintenanceWindow(
                orDefault(scalars.get("PreferredMaintenanceWindow"), DEFAULT_MAINTENANCE_WINDOW));
        cluster.setClusterVersion(orDefault(scalars.get("ClusterVersion"), DEFAULT_CLUSTER_VERSION));
        cluster.setClusterRevisionNumber("1.0");
        cluster.setAutomatedSnapshotRetentionPeriod(
                orDefault(parseInt(scalars.get("AutomatedSnapshotRetentionPeriod"),
                        "AutomatedSnapshotRetentionPeriod"), 1));
        cluster.setManualSnapshotRetentionPeriod(
                orDefault(parseInt(scalars.get("ManualSnapshotRetentionPeriod"),
                        "ManualSnapshotRetentionPeriod"), -1));
        cluster.setAllowVersionUpgrade(parseBoolean(scalars.get("AllowVersionUpgrade"), true));
        cluster.setPubliclyAccessible(parseBoolean(scalars.get("PubliclyAccessible"), false));
        cluster.setEncrypted(parseBoolean(scalars.get("Encrypted"), false));
        cluster.setKmsKeyId(scalars.get("KmsKeyId"));
        cluster.setEnhancedVpcRouting(parseBoolean(scalars.get("EnhancedVpcRouting"), false));
        cluster.setMaintenanceTrackName(orDefault(scalars.get("MaintenanceTrackName"), DEFAULT_MAINTENANCE_TRACK));
        cluster.setElasticIp(scalars.get("ElasticIp"));
        cluster.setDefaultIamRoleArn(scalars.get("DefaultIamRoleArn"));
        cluster.setIpAddressType(orDefault(scalars.get("IpAddressType"), "ipv4"));
        cluster.setMultiAz(parseBoolean(scalars.get("MultiAZ"), false) ? "Enabled" : "Disabled");
        cluster.setAvailabilityZoneRelocationStatus(
                parseBoolean(scalars.get("AvailabilityZoneRelocation"), false) ? "enabled" : "disabled");
        cluster.setClusterNamespaceArn(regionResolver.buildArn("redshift", region,
                "namespace:" + java.util.UUID.randomUUID()));
        cluster.getClusterSecurityGroups().addAll(clusterSecurityGroups);
        cluster.getVpcSecurityGroupIds().addAll(vpcSecurityGroupIds);
        cluster.getClusterParameterGroups().add(parameterGroupName);
        cluster.getIamRoles().addAll(iamRoles);
        cluster.getTags().putAll(tags);

        clusters.put(key(region, identifier), cluster);
        return cluster;
    }

    public List<RedshiftCluster> describeClusters(String region, String clusterIdentifier,
                                                  List<String> tagKeys, List<String> tagValues) {
        if (clusterIdentifier != null && !clusterIdentifier.isBlank()) {
            return List.of(requireCluster(region, clusterIdentifier));
        }
        return clusters.entrySet().stream()
                .filter(e -> e.getKey().startsWith(region + "::"))
                .map(Map.Entry::getValue)
                .filter(cluster -> matchesTagFilter(cluster.getTags(), tagKeys, tagValues))
                .sorted(Comparator.comparing(RedshiftCluster::getClusterIdentifier))
                .toList();
    }

    public synchronized RedshiftCluster modifyCluster(String region, Map<String, String> scalars,
                                                      List<String> clusterSecurityGroups,
                                                      List<String> vpcSecurityGroupIds) {
        String identifier = scalars.get("ClusterIdentifier");
        RedshiftCluster cluster = requireCluster(region, identifier);

        String newIdentifier = scalars.get("NewClusterIdentifier");
        if (newIdentifier != null && !newIdentifier.isBlank() && !newIdentifier.equals(identifier)) {
            validateClusterIdentifier(newIdentifier);
            if (clusters.containsKey(key(region, newIdentifier))) {
                throw new AwsException("ClusterAlreadyExists",
                        "Cluster already exists: " + newIdentifier, 400);
            }
        }

        applyIfPresent(scalars.get("NodeType"), cluster::setNodeType);
        applyIfPresent(scalars.get("ClusterVersion"), cluster::setClusterVersion);
        applyIfPresent(scalars.get("PreferredMaintenanceWindow"), cluster::setPreferredMaintenanceWindow);
        applyIfPresent(scalars.get("MaintenanceTrackName"), cluster::setMaintenanceTrackName);
        applyIfPresent(scalars.get("KmsKeyId"), cluster::setKmsKeyId);
        applyIfPresent(scalars.get("ElasticIp"), cluster::setElasticIp);
        applyIfPresent(scalars.get("AvailabilityZone"), cluster::setAvailabilityZone);
        applyIfPresent(scalars.get("IpAddressType"), cluster::setIpAddressType);

        String clusterType = scalars.get("ClusterType");
        Integer requestedNodes = parseInt(scalars.get("NumberOfNodes"), "NumberOfNodes");
        if (clusterType != null && !clusterType.isBlank()) {
            cluster.setClusterType(clusterType);
            cluster.setNumberOfNodes(resolveNodeCount(clusterType, requestedNodes));
        } else if (requestedNodes != null) {
            cluster.setNumberOfNodes(resolveNodeCount(cluster.getClusterType(), requestedNodes));
        }

        String parameterGroupName = scalars.get("ClusterParameterGroupName");
        if (parameterGroupName != null && !parameterGroupName.isBlank()) {
            requireParameterGroup(region, parameterGroupName);
            cluster.setClusterParameterGroups(new ArrayList<>(List.of(parameterGroupName)));
        }

        Integer port = parseInt(scalars.get("Port"), "Port");
        if (port != null) {
            cluster.setEndpointPort(port);
        }
        applyIfPresent(parseBooleanOrNull(scalars.get("AllowVersionUpgrade")), cluster::setAllowVersionUpgrade);
        applyIfPresent(parseBooleanOrNull(scalars.get("PubliclyAccessible")), cluster::setPubliclyAccessible);
        applyIfPresent(parseBooleanOrNull(scalars.get("Encrypted")), cluster::setEncrypted);
        applyIfPresent(parseBooleanOrNull(scalars.get("EnhancedVpcRouting")), cluster::setEnhancedVpcRouting);
        Boolean multiAz = parseBooleanOrNull(scalars.get("MultiAZ"));
        if (multiAz != null) {
            cluster.setMultiAz(multiAz ? "Enabled" : "Disabled");
        }
        Integer automated = parseInt(scalars.get("AutomatedSnapshotRetentionPeriod"),
                "AutomatedSnapshotRetentionPeriod");
        if (automated != null) {
            cluster.setAutomatedSnapshotRetentionPeriod(automated);
        }
        Integer manual = parseInt(scalars.get("ManualSnapshotRetentionPeriod"), "ManualSnapshotRetentionPeriod");
        if (manual != null) {
            cluster.setManualSnapshotRetentionPeriod(manual);
        }
        if (!clusterSecurityGroups.isEmpty()) {
            cluster.setClusterSecurityGroups(new ArrayList<>(clusterSecurityGroups));
        }
        if (!vpcSecurityGroupIds.isEmpty()) {
            cluster.setVpcSecurityGroupIds(new ArrayList<>(vpcSecurityGroupIds));
        }

        // A modify that completes instantly must not leave the caller polling a
        // pending state that will never clear.
        cluster.setClusterStatus("available");
        cluster.setClusterAvailabilityStatus("Available");
        cluster.setModifyStatus("");

        if (newIdentifier != null && !newIdentifier.isBlank() && !newIdentifier.equals(identifier)) {
            clusters.remove(key(region, identifier));
            cluster.setClusterIdentifier(newIdentifier);
            cluster.setEndpointAddress(endpointAddress(newIdentifier, region));
            clusters.put(key(region, newIdentifier), cluster);
        } else {
            clusters.put(key(region, identifier), cluster);
        }
        return cluster;
    }

    public synchronized RedshiftCluster deleteCluster(String region, String clusterIdentifier) {
        RedshiftCluster cluster = requireCluster(region, clusterIdentifier);
        clusters.remove(key(region, clusterIdentifier));
        cluster.setClusterStatus("deleting");
        cluster.setClusterAvailabilityStatus("Unavailable");
        return cluster;
    }

    public synchronized RedshiftCluster rebootCluster(String region, String clusterIdentifier) {
        RedshiftCluster cluster = requireCluster(region, clusterIdentifier);
        // The reboot completes within the call, so the cluster is reported available
        // rather than parked in a `rebooting` state no background worker would clear.
        cluster.setClusterStatus("available");
        cluster.setClusterAvailabilityStatus("Available");
        clusters.put(key(region, clusterIdentifier), cluster);
        return cluster;
    }

    public RedshiftCluster requireCluster(String region, String clusterIdentifier) {
        requireValue(clusterIdentifier, "ClusterIdentifier");
        RedshiftCluster cluster = clusters.get(key(region, clusterIdentifier));
        if (cluster == null) {
            throw new AwsException("ClusterNotFound",
                    "Cluster " + clusterIdentifier + " not found.", 404);
        }
        return cluster;
    }

    // ── cluster subnet groups ─────────────────────────────────────────────────

    public synchronized RedshiftSubnetGroup createSubnetGroup(String region, String name, String description,
                                                              List<String> subnetIds, Map<String, String> tags) {
        requireValue(name, "ClusterSubnetGroupName");
        requireValue(description, "Description");
        if (subnetIds.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "SubnetIds is required.", 400);
        }
        if (subnetGroups.containsKey(key(region, name))) {
            throw new AwsException("ClusterSubnetGroupAlreadyExists",
                    "Cluster subnet group " + name + " already exists.", 400);
        }
        RedshiftSubnetGroup group = buildSubnetGroup(region, name, description, subnetIds);
        group.getTags().putAll(tags);
        subnetGroups.put(key(region, name), group);
        return group;
    }

    public List<RedshiftSubnetGroup> describeSubnetGroups(String region, String name,
                                                          List<String> tagKeys, List<String> tagValues) {
        if (name != null && !name.isBlank()) {
            return List.of(requireSubnetGroup(region, name));
        }
        return subnetGroups.entrySet().stream()
                .filter(e -> e.getKey().startsWith(region + "::"))
                .map(Map.Entry::getValue)
                .filter(group -> matchesTagFilter(group.getTags(), tagKeys, tagValues))
                .sorted(Comparator.comparing(RedshiftSubnetGroup::getClusterSubnetGroupName))
                .toList();
    }

    public synchronized RedshiftSubnetGroup modifySubnetGroup(String region, String name, String description,
                                                              List<String> subnetIds) {
        RedshiftSubnetGroup existing = requireSubnetGroup(region, name);
        if (subnetIds.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "SubnetIds is required.", 400);
        }
        RedshiftSubnetGroup updated = buildSubnetGroup(region, name,
                description != null && !description.isBlank() ? description : existing.getDescription(), subnetIds);
        updated.getTags().putAll(existing.getTags());
        subnetGroups.put(key(region, name), updated);
        return updated;
    }

    public synchronized void deleteSubnetGroup(String region, String name) {
        requireSubnetGroup(region, name);
        boolean inUse = clusters.entrySet().stream()
                .filter(e -> e.getKey().startsWith(region + "::"))
                .map(Map.Entry::getValue)
                .anyMatch(cluster -> name.equals(cluster.getClusterSubnetGroupName()));
        if (inUse) {
            throw new AwsException("InvalidClusterSubnetGroupStateFault",
                    "Cluster subnet group " + name + " is in use by a cluster.", 400);
        }
        subnetGroups.remove(key(region, name));
    }

    public RedshiftSubnetGroup requireSubnetGroup(String region, String name) {
        requireValue(name, "ClusterSubnetGroupName");
        RedshiftSubnetGroup group = subnetGroups.get(key(region, name));
        if (group == null) {
            throw new AwsException("ClusterSubnetGroupNotFoundFault",
                    "Cluster subnet group " + name + " not found.", 400);
        }
        return group;
    }

    // ── cluster parameter groups ──────────────────────────────────────────────

    public synchronized RedshiftParameterGroup createParameterGroup(String region, String name, String family,
                                                                    String description, Map<String, String> tags) {
        requireValue(name, "ParameterGroupName");
        requireValue(family, "ParameterGroupFamily");
        requireValue(description, "Description");
        ensureDefaultParameterGroup(region);
        if (parameterGroups.containsKey(key(region, name))) {
            throw new AwsException("ClusterParameterGroupAlreadyExists",
                    "Cluster parameter group " + name + " already exists.", 400);
        }
        RedshiftParameterGroup group = new RedshiftParameterGroup();
        group.setParameterGroupName(name);
        group.setParameterGroupFamily(family);
        group.setDescription(description);
        group.getTags().putAll(tags);
        parameterGroups.put(key(region, name), group);
        return group;
    }

    public List<RedshiftParameterGroup> describeParameterGroups(String region, String name,
                                                                List<String> tagKeys, List<String> tagValues) {
        ensureDefaultParameterGroup(region);
        if (name != null && !name.isBlank()) {
            return List.of(requireParameterGroup(region, name));
        }
        return parameterGroups.entrySet().stream()
                .filter(e -> e.getKey().startsWith(region + "::"))
                .map(Map.Entry::getValue)
                .filter(group -> matchesTagFilter(group.getTags(), tagKeys, tagValues))
                .sorted(Comparator.comparing(RedshiftParameterGroup::getParameterGroupName))
                .toList();
    }

    public synchronized RedshiftParameterGroup modifyParameterGroup(String region, String name,
                                                                    List<RedshiftParameter> parameters) {
        RedshiftParameterGroup group = requireParameterGroup(region, name);
        if (parameters.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "Parameters is required.", 400);
        }
        for (RedshiftParameter parameter : parameters) {
            if (parameter.getParameterName() == null || parameter.getParameterName().isBlank()) {
                throw new AwsException("InvalidParameterValue", "ParameterName is required.", 400);
            }
            RedshiftParameter stored = new RedshiftParameter();
            stored.setParameterName(parameter.getParameterName());
            stored.setParameterValue(parameter.getParameterValue() == null ? "" : parameter.getParameterValue());
            stored.setSource("user");
            RedshiftParameter engineDefault = engineDefault(parameter.getParameterName());
            if (engineDefault != null) {
                stored.setDescription(engineDefault.getDescription());
                stored.setDataType(engineDefault.getDataType());
                stored.setAllowedValues(engineDefault.getAllowedValues());
                stored.setApplyType(engineDefault.getApplyType());
            } else {
                stored.setApplyType(orDefault(parameter.getApplyType(), "dynamic"));
                stored.setDataType(orDefault(parameter.getDataType(), "string"));
            }
            group.getParameters().put(stored.getParameterName(), stored);
        }
        parameterGroups.put(key(region, name), group);
        return group;
    }

    public synchronized void deleteParameterGroup(String region, String name) {
        requireParameterGroup(region, name);
        if (DEFAULT_PARAMETER_GROUP_NAME.equals(name)) {
            throw new AwsException("InvalidClusterParameterGroupState",
                    "Default parameter group " + name + " cannot be deleted.", 400);
        }
        boolean inUse = clusters.entrySet().stream()
                .filter(e -> e.getKey().startsWith(region + "::"))
                .map(Map.Entry::getValue)
                .anyMatch(cluster -> cluster.getClusterParameterGroups().contains(name));
        if (inUse) {
            throw new AwsException("InvalidClusterParameterGroupState",
                    "Cluster parameter group " + name + " is in use by a cluster.", 400);
        }
        parameterGroups.remove(key(region, name));
    }

    /**
     * Engine defaults overlaid with the group's user-set values, filtered by the
     * requested {@code Source} ({@code user} or {@code engine-default}) when given.
     */
    public List<RedshiftParameter> describeParameters(String region, String name, String source) {
        RedshiftParameterGroup group = requireParameterGroup(region, name);
        Map<String, RedshiftParameter> merged = new LinkedHashMap<>();
        for (RedshiftParameter parameter : ENGINE_DEFAULTS) {
            merged.put(parameter.getParameterName(), parameter);
        }
        merged.putAll(group.getParameters());
        return merged.values().stream()
                .filter(parameter -> source == null || source.isBlank() || source.equals(parameter.getSource()))
                .sorted(Comparator.comparing(RedshiftParameter::getParameterName))
                .toList();
    }

    public RedshiftParameterGroup requireParameterGroup(String region, String name) {
        requireValue(name, "ParameterGroupName");
        ensureDefaultParameterGroup(region);
        RedshiftParameterGroup group = parameterGroups.get(key(region, name));
        if (group == null) {
            throw new AwsException("ClusterParameterGroupNotFound",
                    "Cluster parameter group " + name + " not found.", 404);
        }
        return group;
    }

    // ── tags ──────────────────────────────────────────────────────────────────

    public synchronized void createTags(String region, String resourceArn, Map<String, String> tags) {
        TaggedResource resource = resolveResource(region, resourceArn);
        resource.tags().putAll(tags);
        resource.persister().run();
    }

    public synchronized void deleteTags(String region, String resourceArn, List<String> tagKeys) {
        TaggedResource resource = resolveResource(region, resourceArn);
        tagKeys.forEach(resource.tags()::remove);
        resource.persister().run();
    }

    /**
     * Every tag on every taggable Redshift resource in the region, optionally narrowed
     * to one resource ARN, one resource type, or a key/value filter.
     */
    public List<TaggedResourceEntry> describeTags(String region, String resourceArn, String resourceType,
                                                  List<String> tagKeys, List<String> tagValues) {
        List<TaggedResourceEntry> entries = new ArrayList<>();
        if (resourceArn != null && !resourceArn.isBlank()) {
            TaggedResource resource = resolveResource(region, resourceArn);
            collect(entries, resource.arn(), resource.type(), resource.tags());
        } else {
            if (resourceType == null || resourceType.isBlank() || RESOURCE_TYPE_CLUSTER.equals(resourceType)) {
                for (RedshiftCluster cluster : regionValues(clusters, region)) {
                    collect(entries, clusterArn(region, cluster.getClusterIdentifier()),
                            RESOURCE_TYPE_CLUSTER, cluster.getTags());
                }
            }
            if (resourceType == null || resourceType.isBlank() || RESOURCE_TYPE_SUBNET_GROUP.equals(resourceType)) {
                for (RedshiftSubnetGroup group : regionValues(subnetGroups, region)) {
                    collect(entries, subnetGroupArn(region, group.getClusterSubnetGroupName()),
                            RESOURCE_TYPE_SUBNET_GROUP, group.getTags());
                }
            }
            if (resourceType == null || resourceType.isBlank() || RESOURCE_TYPE_PARAMETER_GROUP.equals(resourceType)) {
                for (RedshiftParameterGroup group : regionValues(parameterGroups, region)) {
                    collect(entries, parameterGroupArn(region, group.getParameterGroupName()),
                            RESOURCE_TYPE_PARAMETER_GROUP, group.getTags());
                }
            }
        }
        return entries.stream()
                .filter(entry -> tagKeys == null || tagKeys.isEmpty() || tagKeys.contains(entry.key()))
                .filter(entry -> tagValues == null || tagValues.isEmpty() || tagValues.contains(entry.value()))
                .toList();
    }

    public String clusterArn(String region, String identifier) {
        return regionResolver.buildArn("redshift", region, RESOURCE_TYPE_CLUSTER + ":" + identifier);
    }

    public String subnetGroupArn(String region, String name) {
        return regionResolver.buildArn("redshift", region, RESOURCE_TYPE_SUBNET_GROUP + ":" + name);
    }

    public String parameterGroupArn(String region, String name) {
        return regionResolver.buildArn("redshift", region, RESOURCE_TYPE_PARAMETER_GROUP + ":" + name);
    }

    public record TaggedResourceEntry(String resourceName, String resourceType, String key, String value) {}

    private record TaggedResource(String arn, String type, Map<String, String> tags, Runnable persister) {}

    private TaggedResource resolveResource(String region, String resourceArn) {
        requireValue(resourceArn, "ResourceName");
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid resource name: " + resourceArn, 400);
        }
        String[] parts = arn.resource().split(":", 2);
        if (parts.length != 2) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid resource name: " + resourceArn, 400);
        }
        String scopedRegion = arn.region().isEmpty() ? region : arn.region();
        String name = parts[1];
        return switch (parts[0]) {
            case RESOURCE_TYPE_CLUSTER -> {
                RedshiftCluster cluster = clusters.get(key(scopedRegion, name));
                if (cluster == null) {
                    throw new AwsException("ResourceNotFoundFault",
                            "Resource not found: " + resourceArn, 404);
                }
                yield new TaggedResource(resourceArn, RESOURCE_TYPE_CLUSTER, cluster.getTags(),
                        () -> clusters.put(key(scopedRegion, name), cluster));
            }
            case RESOURCE_TYPE_SUBNET_GROUP -> {
                RedshiftSubnetGroup group = subnetGroups.get(key(scopedRegion, name));
                if (group == null) {
                    throw new AwsException("ResourceNotFoundFault",
                            "Resource not found: " + resourceArn, 404);
                }
                yield new TaggedResource(resourceArn, RESOURCE_TYPE_SUBNET_GROUP, group.getTags(),
                        () -> subnetGroups.put(key(scopedRegion, name), group));
            }
            case RESOURCE_TYPE_PARAMETER_GROUP -> {
                ensureDefaultParameterGroup(scopedRegion);
                RedshiftParameterGroup group = parameterGroups.get(key(scopedRegion, name));
                if (group == null) {
                    throw new AwsException("ResourceNotFoundFault",
                            "Resource not found: " + resourceArn, 404);
                }
                yield new TaggedResource(resourceArn, RESOURCE_TYPE_PARAMETER_GROUP, group.getTags(),
                        () -> parameterGroups.put(key(scopedRegion, name), group));
            }
            default -> throw new AwsException("ResourceNotFoundFault",
                    "Unsupported Redshift resource type in " + resourceArn, 404);
        };
    }

    private static void collect(List<TaggedResourceEntry> entries, String arn, String type,
                                Map<String, String> tags) {
        tags.forEach((key, value) -> entries.add(new TaggedResourceEntry(arn, type, key, value)));
    }

    private static <T> List<T> regionValues(Map<String, T> store, String region) {
        return store.entrySet().stream()
                .filter(e -> e.getKey().startsWith(region + "::"))
                .map(Map.Entry::getValue)
                .toList();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private synchronized void ensureDefaultParameterGroup(String region) {
        if (parameterGroups.containsKey(key(region, DEFAULT_PARAMETER_GROUP_NAME))) {
            return;
        }
        RedshiftParameterGroup group = new RedshiftParameterGroup();
        group.setParameterGroupName(DEFAULT_PARAMETER_GROUP_NAME);
        group.setParameterGroupFamily(DEFAULT_PARAMETER_GROUP_FAMILY);
        group.setDescription("Default parameter group for redshift-1.0");
        parameterGroups.put(key(region, DEFAULT_PARAMETER_GROUP_NAME), group);
    }

    private RedshiftSubnetGroup buildSubnetGroup(String region, String name, String description,
                                                 List<String> subnetIds) {
        List<Subnet> resolved = ec2Service.describeSubnets(region, subnetIds, Map.of());
        if (resolved.size() != subnetIds.size()) {
            throw new AwsException("InvalidSubnet",
                    "One or more subnets for cluster subnet group " + name + " do not exist.", 400);
        }
        String vpcId = resolved.getFirst().getVpcId();
        boolean sameVpc = resolved.stream()
                .map(Subnet::getVpcId)
                .filter(Objects::nonNull)
                .allMatch(vpcId::equals);
        if (!sameVpc) {
            throw new AwsException("InvalidVPCNetworkStateFault",
                    "Cluster subnet group " + name + " contains subnets in multiple VPCs.", 400);
        }

        RedshiftSubnetGroup group = new RedshiftSubnetGroup();
        group.setClusterSubnetGroupName(name);
        group.setDescription(description);
        group.setVpcId(vpcId);
        group.setSubnetGroupStatus("Complete");
        group.getSubnetIds().addAll(subnetIds);
        group.getSupportedClusterIpAddressTypes().add("ipv4");
        // Keyed off the requested order so the group's first availability zone — the one a
        // cluster inherits when it names no zone of its own — does not depend on EC2 scan order.
        Map<String, String> zonesBySubnetId = new LinkedHashMap<>();
        for (Subnet subnet : resolved) {
            zonesBySubnetId.put(subnet.getSubnetId(), subnet.getAvailabilityZone());
        }
        for (String subnetId : subnetIds) {
            group.getSubnetAvailabilityZones().put(subnetId, zonesBySubnetId.getOrDefault(subnetId, ""));
        }
        return group;
    }

    private String defaultVpcId(String region) {
        List<Subnet> subnets = ec2Service.describeSubnets(region, List.of(), Map.of());
        return subnets.isEmpty() ? "" : subnets.getFirst().getVpcId();
    }

    private static String firstAvailabilityZone(RedshiftSubnetGroup group, String region) {
        return group.getSubnetAvailabilityZones().values().stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(region + "a");
    }

    private static int resolveNodeCount(String clusterType, Integer requestedNodes) {
        if ("single-node".equals(clusterType)) {
            if (requestedNodes != null && requestedNodes != 1) {
                throw new AwsException("InvalidParameterCombination",
                        "NumberOfNodes must be 1 for a single-node cluster.", 400);
            }
            return 1;
        }
        if (requestedNodes == null) {
            throw new AwsException("MissingParameter",
                    "NumberOfNodes is required for a multi-node cluster.", 400);
        }
        if (requestedNodes < 2) {
            throw new AwsException("InvalidParameterValue",
                    "NumberOfNodes must be at least 2 for a multi-node cluster.", 400);
        }
        return requestedNodes;
    }

    private static String endpointAddress(String identifier, String region) {
        return identifier + "." + randomLower(12) + "." + region + ".redshift.amazonaws.com";
    }

    private static RedshiftParameter engineDefault(String name) {
        return ENGINE_DEFAULTS.stream()
                .filter(parameter -> parameter.getParameterName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static RedshiftParameter engineDefault(String name, String value, String description,
                                                   String dataType, String allowedValues, String applyType) {
        return new RedshiftParameter(name, value, description, "engine-default", dataType, allowedValues, applyType);
    }

    private static boolean matchesTagFilter(Map<String, String> tags, List<String> tagKeys, List<String> tagValues) {
        boolean keyMatch = tagKeys == null || tagKeys.isEmpty() || tagKeys.stream().anyMatch(tags::containsKey);
        boolean valueMatch = tagValues == null || tagValues.isEmpty()
                || tagValues.stream().anyMatch(tags.values()::contains);
        return keyMatch && valueMatch;
    }

    private static void validateClusterIdentifier(String identifier) {
        requireValue(identifier, "ClusterIdentifier");
        if (!identifier.equals(identifier.toLowerCase(Locale.ROOT))
                || !identifier.matches("[a-z][a-z0-9-]{0,62}")
                || identifier.endsWith("-")
                || identifier.contains("--")) {
            throw new AwsException("InvalidParameterValue",
                    "ClusterIdentifier must be 1 to 63 lowercase alphanumeric characters or hyphens, "
                            + "start with a letter, and not end with a hyphen or contain two consecutive hyphens.",
                    400);
        }
    }

    private static void requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AwsException("MissingParameter", field + " is required.", 400);
        }
    }

    private static void applyIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    private static void applyIfPresent(Boolean value, java.util.function.Consumer<Boolean> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static Integer parseInt(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", field + " must be an integer.", 400);
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        Boolean parsed = parseBooleanOrNull(value);
        return parsed != null ? parsed : fallback;
    }

    private static Boolean parseBooleanOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.valueOf("true".equalsIgnoreCase(value.trim()));
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String randomLower(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(LOWER_ALNUM.charAt(RANDOM.nextInt(LOWER_ALNUM.length())));
        }
        return result.toString();
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }
}
