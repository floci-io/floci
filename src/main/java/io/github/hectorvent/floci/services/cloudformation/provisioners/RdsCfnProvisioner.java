package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbProxyAuth;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for RDS: the two parameter-group types, {@code DBSubnetGroup},
 * {@code DBInstance}, {@code DBCluster}, {@code DBProxy} and {@code DBProxyTargetGroup}.
 * Extracted from {@code CloudFormationResourceProvisioner}.
 *
 * <p>Every type here deletes by physical id alone, so the id-only
 * {@link #delete(String, String, String)} serves all seven and none of them appears in the
 * engine's {@code DELETE_NEEDS_STACK_RESOURCE} set.
 *
 * <p>Unlike most extractions this one does not take {@code RdsService} out of the monolith.
 * {@code AWS::SecretsManager::SecretTargetAttachment} is still served there and reads an
 * instance's or cluster's endpoint to build its connection detail, so the monolith keeps its own
 * reference to the service.
 */
@ApplicationScoped
public class RdsCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(RdsCfnProvisioner.class);

    private final RdsService rdsService;
    private final CfnDynamicReferences dynamicReferences;

    @Inject
    public RdsCfnProvisioner(RdsService rdsService, CfnDynamicReferences dynamicReferences) {
        this.rdsService = rdsService;
        this.dynamicReferences = dynamicReferences;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(
                "AWS::RDS::DBSubnetGroup",
                "AWS::RDS::DBParameterGroup",
                "AWS::RDS::DBClusterParameterGroup",
                "AWS::RDS::DBInstance",
                "AWS::RDS::DBCluster",
                "AWS::RDS::DBProxy",
                "AWS::RDS::DBProxyTargetGroup");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        switch (r.getResourceType()) {
            case "AWS::RDS::DBSubnetGroup" -> provisionDbSubnetGroup(r, props, engine, ctx, region);
            case "AWS::RDS::DBParameterGroup" -> provisionDbParameterGroup(r, props, engine, ctx, region);
            case "AWS::RDS::DBClusterParameterGroup" ->
                    provisionDbClusterParameterGroup(r, props, engine, ctx, region);
            case "AWS::RDS::DBInstance" -> provisionDbInstance(r, props, engine, ctx, region);
            case "AWS::RDS::DBCluster" -> provisionDbCluster(r, props, engine, ctx, region);
            case "AWS::RDS::DBProxy" -> provisionDbProxy(r, props, engine, region);
            case "AWS::RDS::DBProxyTargetGroup" -> provisionDbProxyTargetGroup(r, props, engine, region);
            default -> throw new IllegalStateException(
                    "RdsCfnProvisioner received an unsupported type: " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case "AWS::RDS::DBInstance" -> rdsService.deleteDbInstance(physicalId, region);
            case "AWS::RDS::DBCluster" -> rdsService.deleteDbCluster(physicalId, region);
            case "AWS::RDS::DBProxy" -> deleteDbProxySafe(physicalId, region);
            case "AWS::RDS::DBProxyTargetGroup" -> clearDbProxyTargetGroupSafe(physicalId, region);
            case "AWS::RDS::DBSubnetGroup" -> rdsService.deleteDbSubnetGroup(physicalId, region);
            case "AWS::RDS::DBParameterGroup" -> rdsService.deleteDbParameterGroup(physicalId, region);
            case "AWS::RDS::DBClusterParameterGroup" ->
                    rdsService.deleteDbClusterParameterGroup(physicalId, region);
            default -> throw new IllegalStateException(
                    "RdsCfnProvisioner received an unsupported type: " + resourceType);
        }
    }

    private void provisionDbSubnetGroup(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                        ProvisionContext ctx, String region) {
        String explicitName = resolveOptional(props, "DBSubnetGroupName", engine);
        String priorPhysicalId = r.getPhysicalId();
        String name;
        if (explicitName != null && !explicitName.isBlank()) {
            name = explicitName;
        } else if (priorPhysicalId != null) {
            // No explicit name: keep the name RDS already has on file instead of generating a fresh
            // one on every update, which would otherwise orphan the previously provisioned group.
            name = priorPhysicalId;
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), 60, true);
        }
        String description = firstNonBlank(resolveOptional(props, "DBSubnetGroupDescription", engine),
                "Managed by CloudFormation");
        // SubnetIds may be a literal array, or a list-valued intrinsic such as CDK's
        // Fn::Split over a cross-stack Fn::ImportValue when the source VPC exports its
        // subnet ids as one comma-joined value (issue #2937).
        List<String> subnetIds = props != null && props.has("SubnetIds")
                ? engine.resolveStringList(props.get("SubnetIds"))
                : new ArrayList<>();

        // On UpdateStack, provision() is re-invoked for every resource regardless of whether its
        // properties actually changed, so a same-named group already on file must be reconciled in
        // place rather than re-created (createDbSubnetGroup throws DBSubnetGroupAlreadyExists).
        DbSubnetGroup existing = sameNameExistingResource(priorPhysicalId, name, n -> rdsService.getDbSubnetGroup(n, region));
        DbSubnetGroup group;
        if (existing != null) {
            group = rdsService.modifyDbSubnetGroup(name, subnetIds, region);
        } else {
            group = rdsService.createDbSubnetGroup(name, description, subnetIds, region);
            deleteRenamedResource(priorPhysicalId, name, id -> rdsService.deleteDbSubnetGroup(id), "DB subnet group");
        }
        r.setPhysicalId(group.getDbSubnetGroupName());
        r.getAttributes().put("DBSubnetGroupName", group.getDbSubnetGroupName());
    }

    private void provisionDbParameterGroup(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                           ProvisionContext ctx, String region) {
        String explicitName = resolveOptional(props, "DBParameterGroupName", engine);
        String priorPhysicalId = r.getPhysicalId();
        String name;
        if (explicitName != null && !explicitName.isBlank()) {
            name = explicitName;
        } else if (priorPhysicalId != null) {
            name = priorPhysicalId;
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), 60, true);
        }
        String family = resolveOptional(props, "Family", engine);
        String description = firstNonBlank(resolveOptional(props, "Description", engine),
                "Managed by CloudFormation");

        // DBParameterGroupName, Family and Description are all immutable on real AWS (any change
        // replaces the resource), so a same-named group already on file is a no-op, not a re-create.
        DbParameterGroup existing = sameNameExistingResource(priorPhysicalId, name,
                n -> rdsService.getDbParameterGroup(n, region));
        DbParameterGroup group;
        if (existing != null) {
            group = existing;
        } else {
            group = rdsService.createDbParameterGroup(name, family, description, region);
            deleteRenamedResource(priorPhysicalId, name, id -> rdsService.deleteDbParameterGroup(id, region),
                    "DB parameter group");
        }
        r.setPhysicalId(group.getDbParameterGroupName());
        r.getAttributes().put("DBParameterGroupName", group.getDbParameterGroupName());
    }

    private void provisionDbClusterParameterGroup(StackResource r, JsonNode props,
                                                  CloudFormationTemplateEngine engine,
                                                  ProvisionContext ctx, String region) {
        String explicitName = resolveOptional(props, "DBClusterParameterGroupName", engine);
        String priorPhysicalId = r.getPhysicalId();
        String name;
        if (explicitName != null && !explicitName.isBlank()) {
            name = explicitName;
        } else if (priorPhysicalId != null) {
            name = priorPhysicalId;
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), 60, true);
        }
        String family = resolveOptional(props, "Family", engine);
        String description = firstNonBlank(resolveOptional(props, "Description", engine),
                "Managed by CloudFormation");

        // Same immutability rationale as provisionDbParameterGroup above.
        DbClusterParameterGroup existing = sameNameExistingResource(priorPhysicalId, name,
                n -> rdsService.getDbClusterParameterGroup(n, region));
        DbClusterParameterGroup group;
        if (existing != null) {
            group = existing;
        } else {
            group = rdsService.createDbClusterParameterGroup(name, family, description, region);
            deleteRenamedResource(priorPhysicalId, name, id -> rdsService.deleteDbClusterParameterGroup(id, region),
                    "DB cluster parameter group");
        }
        r.setPhysicalId(group.getDbClusterParameterGroupName());
        r.getAttributes().put("DBClusterParameterGroupName", group.getDbClusterParameterGroupName());
    }

    private void provisionDbInstance(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                     ProvisionContext ctx, String region) {
        String explicitId = resolveOptional(props, "DBInstanceIdentifier", engine);
        String priorPhysicalId = r.getPhysicalId();
        String id;
        if (explicitId != null && !explicitId.isBlank()) {
            id = explicitId;
        } else if (priorPhysicalId != null) {
            id = priorPhysicalId;
        } else {
            id = ctx.generatePhysicalName(r.getLogicalId(), 60, true);
        }

        // provision() is re-invoked on every UpdateStack for every resource, so a same-id instance
        // already on file must be reconciled rather than re-created (createDbInstance throws
        // DBInstanceAlreadyExists). Only the properties RdsService.modifyDbInstance actually supports
        // (password, IAM auth, subnet group) are reconciled here; other property changes (engine,
        // instance class, allocated storage, ...) are a pre-existing gap in that method, not addressed
        // by this fix.
        DbInstance instance = sameNameExistingResource(priorPhysicalId, id, rdsService::getDbInstance);
        if (instance != null) {
            instance = rdsService.modifyDbInstance(
                    id,
                    resolveDynamicReferences(resolveOptionalWithoutDynamicReferences(props, "MasterUserPassword", engine), region, true),
                    parseBoolProp(props, "EnableIAMDatabaseAuthentication", engine),
                    resolveOptional(props, "DBSubnetGroupName", engine));
        } else {
            instance = rdsService.createDbInstance(
                    id,
                    resolveOptional(props, "Engine", engine),
                    resolveOptional(props, "EngineVersion", engine),
                    resolveDynamicReferences(resolveOptionalWithoutDynamicReferences(props, "MasterUsername", engine), region, false),
                    resolveDynamicReferences(resolveOptionalWithoutDynamicReferences(props, "MasterUserPassword", engine), region, true),
                    resolveOptional(props, "DBName", engine),
                    firstNonBlank(resolveOptional(props, "DBInstanceClass", engine), "db.t3.micro"),
                    parseIntProp(props, "AllocatedStorage", engine, 20),
                    parseBoolProp(props, "EnableIAMDatabaseAuthentication", engine),
                    resolveOptional(props, "DBParameterGroupName", engine),
                    resolveOptional(props, "DBSubnetGroupName", engine),
                    resolveOptional(props, "DBClusterIdentifier", engine),
                    null, false, false, null, Map.of(), region);
            deleteRenamedResource(priorPhysicalId, id, rdsService::deleteDbInstance, "DB instance");
        }
        r.setPhysicalId(instance.getDbInstanceIdentifier());
        r.getAttributes().put("DBInstanceIdentifier", instance.getDbInstanceIdentifier());
        if (instance.getEndpoint() != null) {
            r.getAttributes().put("Endpoint.Address", instance.getEndpoint().address());
            r.getAttributes().put("Endpoint.Port", String.valueOf(instance.getEndpoint().port()));
        }
        if (instance.getDbInstanceArn() != null) {
            r.getAttributes().put("DBInstanceArn", instance.getDbInstanceArn());
        }
    }

    private void provisionDbCluster(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                    ProvisionContext ctx, String region) {
        String explicitId = resolveOptional(props, "DBClusterIdentifier", engine);
        String priorPhysicalId = r.getPhysicalId();
        String id;
        if (explicitId != null && !explicitId.isBlank()) {
            id = explicitId;
        } else if (priorPhysicalId != null) {
            id = priorPhysicalId;
        } else {
            id = ctx.generatePhysicalName(r.getLogicalId(), 60, true);
        }

        // Same re-invocation rationale as provisionDbInstance above; modifyDbCluster only reconciles
        // password and IAM auth, mirroring that method's existing scope.
        DbCluster cluster = sameNameExistingResource(priorPhysicalId, id, rdsService::getDbCluster);
        if (cluster != null) {
            cluster = rdsService.modifyDbCluster(
                    id,
                    resolveDynamicReferences(resolveOptionalWithoutDynamicReferences(props, "MasterUserPassword", engine), region, true),
                    parseBoolProp(props, "EnableIAMDatabaseAuthentication", engine),
                    parseServerlessV2Capacity(props, "MinCapacity", engine),
                    parseServerlessV2Capacity(props, "MaxCapacity", engine),
                    parseServerlessV2SecondsUntilAutoPause(props, engine), region);
        } else {
            Double serverlessV2MinCapacity = parseServerlessV2Capacity(props, "MinCapacity", engine);
            Double serverlessV2MaxCapacity = parseServerlessV2Capacity(props, "MaxCapacity", engine);
            Integer serverlessV2SecondsUntilAutoPause =
                    parseServerlessV2SecondsUntilAutoPause(props, engine);
            String engineName = resolveOptional(props, "Engine", engine);
            String engineVersion = resolveOptional(props, "EngineVersion", engine);
            String masterUsername = resolveDynamicReferences(
                    resolveOptionalWithoutDynamicReferences(props, "MasterUsername", engine), region, false);
            String masterPassword = resolveDynamicReferences(
                    resolveOptionalWithoutDynamicReferences(props, "MasterUserPassword", engine), region, true);
            String databaseName = resolveOptional(props, "DatabaseName", engine);
            boolean iamEnabled = parseBoolProp(props, "EnableIAMDatabaseAuthentication", engine);
            String parameterGroup = resolveOptional(props, "DBClusterParameterGroupName", engine);
            String engineMode = resolveOptional(props, "EngineMode", engine);
            boolean storageEncrypted = parseBoolProp(props, "StorageEncrypted", engine);
            if (engineMode == null && !storageEncrypted) {
                if (serverlessV2MinCapacity == null && serverlessV2MaxCapacity == null
                        && serverlessV2SecondsUntilAutoPause == null) {
                    cluster = rdsService.createDbCluster(id, engineName, engineVersion, masterUsername,
                            masterPassword, databaseName, iamEnabled, parameterGroup, null, null, false, region);
                } else {
                    cluster = rdsService.createDbCluster(id, engineName, engineVersion, masterUsername,
                            masterPassword, databaseName, iamEnabled, parameterGroup, null, null, false, region,
                            serverlessV2MinCapacity, serverlessV2MaxCapacity, serverlessV2SecondsUntilAutoPause);
                }
            } else {
                cluster = rdsService.createDbCluster(id, engineName, engineVersion, masterUsername,
                        masterPassword, databaseName, iamEnabled, parameterGroup, null, null, false, region,
                        serverlessV2MinCapacity, serverlessV2MaxCapacity, serverlessV2SecondsUntilAutoPause,
                        false, null, engineMode, storageEncrypted);
            }
            deleteRenamedResource(priorPhysicalId, id, rdsService::deleteDbCluster, "DB cluster");
        }
        r.setPhysicalId(cluster.getDbClusterIdentifier());
        r.getAttributes().put("DBClusterIdentifier", cluster.getDbClusterIdentifier());
        if (cluster.getEndpoint() != null) {
            r.getAttributes().put("Endpoint.Address", cluster.getEndpoint().address());
            r.getAttributes().put("Endpoint.Port", String.valueOf(cluster.getEndpoint().port()));
        }
        if (cluster.getReaderEndpoint() != null) {
            r.getAttributes().put("ReadEndpoint.Address", cluster.getReaderEndpoint().address());
        }
        if (cluster.getDbClusterArn() != null) {
            r.getAttributes().put("DBClusterArn", cluster.getDbClusterArn());
        }
    }

    private Double parseServerlessV2Capacity(JsonNode props, String field,
                                             CloudFormationTemplateEngine engine) {
        JsonNode config = props.get("ServerlessV2ScalingConfiguration");
        if (config == null || config.isNull()) {
            return null;
        }
        String resolved = resolveOptional(config, field, engine);
        if (resolved == null || resolved.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(resolved.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError",
                    "ServerlessV2ScalingConfiguration " + field + " must be a number.", 400);
        }
    }

    private Integer parseServerlessV2SecondsUntilAutoPause(
            JsonNode props, CloudFormationTemplateEngine engine) {
        JsonNode config = props.get("ServerlessV2ScalingConfiguration");
        if (config == null || config.isNull()) {
            return null;
        }
        String resolved = resolveOptional(config, "SecondsUntilAutoPause", engine);
        if (resolved == null || resolved.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(resolved.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError",
                    "ServerlessV2ScalingConfiguration SecondsUntilAutoPause must be an integer.", 400);
        }
    }

    private void provisionDbProxy(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                  String region) {
        String name = resolveOptional(props, "DBProxyName", engine);
        String engineFamily = resolveOptional(props, "EngineFamily", engine);
        String defaultAuthScheme = resolveOptional(props, "DefaultAuthScheme", engine);
        if (defaultAuthScheme == null) {
            defaultAuthScheme = "NONE";
        } else if (defaultAuthScheme.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "DefaultAuthScheme must be NONE or IAM_AUTH.", 400);
        }
        String endpointNetworkType = resolveOptional(props, "EndpointNetworkType", engine);
        String targetConnectionNetworkType = resolveOptional(
                props, "TargetConnectionNetworkType", engine);
        validateIpv4DbProxyNetworkType(endpointNetworkType,
                "EndpointNetworkType", true, "IPV4, IPV6, or DUAL");
        validateIpv4DbProxyNetworkType(targetConnectionNetworkType,
                "TargetConnectionNetworkType", false, "IPV4 or IPV6");
        boolean requireTls = parseBoolProp(props, "RequireTLS", engine);
        boolean debugLogging = parseBoolProp(props, "DebugLogging", engine);
        Integer configuredIdleClientTimeout = parseOptionalIntProp(props, "IdleClientTimeout", engine);
        int idleClientTimeout = configuredIdleClientTimeout != null ? configuredIdleClientTimeout : 1800;
        String roleArn = resolveOptional(props, "RoleArn", engine);
        List<String> subnetIds = resolveStringList(props, "VpcSubnetIds", engine);
        if (subnetIds.stream().distinct().count() < 2) {
            throw new AwsException("InvalidParameterValue",
                    "AWS::RDS::DBProxy VpcSubnetIds must contain at least two distinct subnet IDs.", 400);
        }
        List<String> sgIds = resolveStringList(props, "VpcSecurityGroupIds", engine);
        List<DbProxyAuth> auth = parseProxyAuth(props, engine);
        boolean iamAuth = "IAM_AUTH".equalsIgnoreCase(defaultAuthScheme)
                || auth.stream().anyMatch(a ->
                "REQUIRED".equalsIgnoreCase(a.getIamAuth())
                        || "ENABLED".equalsIgnoreCase(a.getIamAuth()));
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, engine);
        var proxy = r.getPhysicalId() == null
                ? rdsService.createDbProxy(name, engineFamily, requireTls, iamAuth,
                defaultAuthScheme, roleArn, subnetIds, sgIds, auth, idleClientTimeout,
                debugLogging, tags, region)
                : updateDbProxy(r, name, engineFamily, defaultAuthScheme, requireTls,
                idleClientTimeout, debugLogging, roleArn, subnetIds, sgIds, auth, tags, region);
        r.setPhysicalId(proxy.getDbProxyName());              // Ref -> DBProxyName
        r.getAttributes().put("Endpoint", proxy.getEndpoint());   // GetAtt "Endpoint" (bare host)
        r.getAttributes().put("DBProxyArn", proxy.getDbProxyArn());
        if (proxy.getVpcId() != null) {
            r.getAttributes().put("VpcId", proxy.getVpcId());
        }
    }

    private io.github.hectorvent.floci.services.rds.model.DbProxy updateDbProxy(
            StackResource resource, String name, String engineFamily, String defaultAuthScheme,
            boolean requireTls, int idleClientTimeout, boolean debugLogging, String roleArn,
            List<String> subnetIds, List<String> securityGroupIds, List<DbProxyAuth> auth,
            Map<String, String> tags, String region) {
        var existing = rdsService.getDbProxy(resource.getPhysicalId(), region);
        if (!Objects.equals(existing.getDbProxyName(), name)
                || engineFamily == null
                || !existing.getEngineFamily().equalsIgnoreCase(engineFamily)
                || !Set.copyOf(existing.getVpcSubnetIds()).equals(Set.copyOf(subnetIds))) {
            throw new AwsException("UnsupportedOperation",
                    "Changing DBProxyName, EngineFamily, or VpcSubnetIds requires CloudFormation "
                            + "replacement, which is not yet supported by Floci.", 400);
        }
        return rdsService.modifyDbProxy(existing.getDbProxyName(), defaultAuthScheme, auth,
                requireTls, idleClientTimeout, debugLogging, roleArn,
                securityGroupIds, tags, region);
    }

    private void provisionDbProxyTargetGroup(StackResource r, JsonNode props,
                                             CloudFormationTemplateEngine engine, String region) {
        String dbProxyName = resolveOptional(props, "DBProxyName", engine);
        String targetGroupName = resolveOptional(props, "TargetGroupName", engine);
        if (!"default".equals(targetGroupName)) {
            throw new AwsException("InvalidParameterValue",
                    "AWS::RDS::DBProxyTargetGroup TargetGroupName must be default.", 400);
        }
        List<String> clusterIds = resolveStringList(props, "DBClusterIdentifiers", engine);
        List<String> instanceIds = resolveStringList(props, "DBInstanceIdentifiers", engine);
        Integer maxConn = null;
        Integer maxIdle = null;
        Integer connectionBorrowTimeout = null;
        String initQuery = null;
        List<String> sessionPinningFilters = List.of();
        if (props != null && props.has("ConnectionPoolConfigurationInfo")) {
            JsonNode cpc = props.get("ConnectionPoolConfigurationInfo");
            maxConn = parseOptionalIntProp(cpc, "MaxConnectionsPercent", engine);
            maxIdle = parseOptionalIntProp(cpc, "MaxIdleConnectionsPercent", engine);
            connectionBorrowTimeout = parseOptionalIntProp(cpc, "ConnectionBorrowTimeout", engine);
            initQuery = resolveOptional(cpc, "InitQuery", engine);
            sessionPinningFilters = resolveStringList(cpc, "SessionPinningFilters", engine);
        }
        if (maxIdle != null && maxConn == null) {
            throw new AwsException("InvalidParameterValue",
                    "MaxConnectionsPercent is required when MaxIdleConnectionsPercent is specified.",
                    400);
        }
        if (r.getPhysicalId() != null) {
            var existing = rdsService.getDbProxyTargetGroupByArn(r.getPhysicalId(), region);
            if (!Objects.equals(existing.getDbProxyName(), dbProxyName)
                    || !Objects.equals(existing.getTargetGroupName(), targetGroupName)) {
                throw new AwsException("UnsupportedOperation",
                        "Changing DBProxyName or TargetGroupName requires CloudFormation replacement.",
                        400);
            }
        }
        var proxy = rdsService.getDbProxy(dbProxyName, region);
        int effectiveMaxConnections = maxConn != null ? maxConn
                : ("SQLSERVER".equals(proxy.getEngineFamily()) ? 10 : 100);
        int effectiveMaxIdle = maxIdle != null ? maxIdle : effectiveMaxConnections / 2;
        int effectiveBorrowTimeout = connectionBorrowTimeout != null ? connectionBorrowTimeout : 120;
        var tg = rdsService.reconcileDbProxyTargetGroup(
                dbProxyName, targetGroupName, clusterIds, instanceIds,
                effectiveMaxConnections, effectiveMaxIdle, effectiveBorrowTimeout,
                initQuery, sessionPinningFilters, region);
        r.setPhysicalId(tg.getTargetGroupArn());              // Ref -> TargetGroupArn
        r.getAttributes().put("TargetGroupArn", tg.getTargetGroupArn());
        r.getAttributes().put("DBProxyName", tg.getDbProxyName());
    }

    private List<DbProxyAuth> parseProxyAuth(JsonNode props, CloudFormationTemplateEngine engine) {
        List<DbProxyAuth> auth = new ArrayList<>();
        if (props != null && props.has("Auth") && props.get("Auth").isArray()) {
            for (JsonNode a : props.get("Auth")) {
                DbProxyAuth entry = new DbProxyAuth();
                entry.setAuthScheme(resolveOptional(a, "AuthScheme", engine));
                entry.setSecretArn(resolveOptional(a, "SecretArn", engine));
                entry.setIamAuth(resolveOptional(a, "IAMAuth", engine));
                entry.setClientPasswordAuthType(resolveOptional(a, "ClientPasswordAuthType", engine));
                entry.setDescription(resolveOptional(a, "Description", engine));
                entry.setUserName(resolveOptional(a, "UserName", engine));
                auth.add(entry);
            }
        }
        return auth;
    }

    private void validateIpv4DbProxyNetworkType(
            String value, String propertyName, boolean dualAllowed, String validValues) {
        if (value == null) {
            return;
        }
        if ("IPV4".equalsIgnoreCase(value)) {
            return;
        }
        boolean supportedAwsValue = "IPV6".equalsIgnoreCase(value)
                || (dualAllowed && "DUAL".equalsIgnoreCase(value));
        if (value.isBlank() || !supportedAwsValue) {
            throw new AwsException("InvalidParameterValue",
                    propertyName + " must be " + validValues + ".", 400);
        }
        throw new AwsException("UnsupportedOperation",
                propertyName + " " + value.toUpperCase()
                        + " is not supported because Floci currently exposes IPv4 proxy networking only.",
                400);
    }

    private void deleteDbProxySafe(String name, String region) {
        try {
            rdsService.deleteDbProxy(name, region);
        } catch (AwsException e) {
            if (!"DBProxyNotFoundFault".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("DB proxy already gone, treating as deleted: {0}", name);
        }
    }

    private void clearDbProxyTargetGroupSafe(String targetGroupArn, String region) {
        try {
            rdsService.clearDbProxyTargetGroupByArn(targetGroupArn, region);
        } catch (AwsException e) {
            if (!"DBProxyTargetGroupNotFoundFault".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("DB proxy target group already gone, treating as deleted: {0}", targetGroupArn);
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private Integer parseOptionalIntProp(JsonNode props, String name, CloudFormationTemplateEngine engine) {
        String value = resolveOptional(props, name, engine);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", name + " must be an integer.", 400);
        }
    }

    private boolean parseBoolProp(JsonNode props, String name, CloudFormationTemplateEngine engine) {
        return Boolean.parseBoolean(resolveOptional(props, name, engine));
    }

    // ── helpers copied from the monolith, which keeps its own copies for the types still there ──

    private String resolveOptional(JsonNode props, String name, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return engine.resolve(props.get(name));
    }

    private List<String> resolveStringList(JsonNode props, String field, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(field)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(engine.resolveStringList(props.get(field)));
    }

    /**
     * Resolves a property without the engine's general dynamic-reference stage, for the two RDS
     * master-credential properties that resolve their own references with {@code ssm-secure}
     * allowed. The general stage rejects {@code ssm-secure}, so running it first would fail the
     * one case AWS permits it. Mirrors the monolith helper of the same name.
     */
    private String resolveOptionalWithoutDynamicReferences(JsonNode props, String name,
                                                           CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return engine.resolveWithoutDynamicReferences(props.get(name));
    }

    private String resolveDynamicReferences(String value, String region, boolean allowSsmSecure) {
        return dynamicReferences.resolveDynamicReferences(value, region, allowSsmSecure);
    }

    private Map<String, String> parseCfnTags(JsonNode tagsNode, CloudFormationTemplateEngine engine) {
        tagsNode = engine.resolveNode(tagsNode);
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = engine.resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    private <T> T sameNameExistingResource(String priorPhysicalId, String name,
                                           java.util.function.Function<String, T> lookup) {
        if (priorPhysicalId == null || !priorPhysicalId.equals(name)) {
            return null;
        }
        try {
            return lookup.apply(name);
        } catch (AwsException notFound) {
            // Expected when the resource was deleted out of band since the prior update; the
            // caller falls back to creating it fresh under the same name.
            LOG.debugv(notFound, "No existing {0} found on file, falling back to create", name);
            return null;
        }
    }

    private void deleteRenamedResource(String priorPhysicalId, String newName,
                                       java.util.function.Consumer<String> delete, String resourceKind) {
        if (priorPhysicalId == null || priorPhysicalId.equals(newName)) {
            return;
        }
        try {
            delete.accept(priorPhysicalId);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Failed to delete renamed {0} {1} after replacement by {2}",
                    resourceKind, priorPhysicalId, newName);
        }
    }

    private int parseIntProp(JsonNode props, String name, CloudFormationTemplateEngine engine, int fallback) {
        String value = resolveOptional(props, name, engine);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOG.debugv(e, "Non-numeric {0} value {1}; falling back to {2}", name, value, fallback);
            return fallback;
        }
    }
}
