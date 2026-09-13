package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.docdb.DocDbService;
import io.github.hectorvent.floci.services.docdb.model.DocDbCluster;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstance;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Provisions {@code AWS::SecretsManager::SecretTargetAttachment}.
 *
 * <p>Extracted from {@code CloudFormationResourceProvisioner}. Unlike most provisioners this one
 * wraps three services rather than one: an attachment reads the endpoint of the RDS or DocumentDB
 * instance or cluster it points at, so it can write the connection detail (engine, host, port,
 * dbname, identifier) into the target secret. That dependency is inherent to the type, and it is
 * the reason {@code RdsService} stayed in the monolith when the RDS types moved out.
 *
 * <p>This type deletes with the stored {@link StackResource}, not the physical id alone, so it
 * overrides {@code delete(StackResource, String)}. The delete needs two create-time attributes
 * that no physical id carries: which secret fields this attachment managed, so only those are
 * stripped, and which attachment owns the secret, so a second attachment's fields are left alone.
 * Registering that here is what lets the type leave the engine's
 * {@code DELETE_NEEDS_STACK_RESOURCE} set; the two must change together or
 * {@code CfnDeletePrecedenceTest} fails with both claiming it.
 */
@ApplicationScoped
public class SecretTargetAttachmentCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::SecretsManager::SecretTargetAttachment";

    private static final Logger LOG = Logger.getLogger(SecretTargetAttachmentCfnProvisioner.class);

    private static final String SECRET_TARGET_MANAGED_KEYS_ATTR = "__FlociSecretTargetManagedKeys";
    private static final String SECRET_TARGET_OWNER_ATTR = "__FlociSecretTargetOwner";
    private static final List<String> SECRET_TARGET_CONNECTION_KEYS = List.of(
            "engine", "host", "port", "dbname", "dbInstanceIdentifier", "dbClusterIdentifier");

    private final SecretsManagerService secretsManagerService;
    private final RdsService rdsService;
    private final DocDbService docDbService;
    private final ObjectMapper objectMapper;

    @Inject
    public SecretTargetAttachmentCfnProvisioner(SecretsManagerService secretsManagerService,
                                                RdsService rdsService,
                                                DocDbService docDbService,
                                                ObjectMapper objectMapper) {
        this.secretsManagerService = secretsManagerService;
        this.rdsService = rdsService;
        this.docDbService = docDbService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        requireSupported(r.getResourceType());
        provisionSecretTargetAttachment(r, props, ctx);
    }

    @Override
    public void delete(StackResource resource, String region) {
        requireSupported(resource.getResourceType());
        deleteSecretTargetAttachment(resource, region);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        requireSupported(resourceType);
        throw new AwsException("ValidationError",
                "SecretTargetAttachment deletion requires the StackResource metadata that records "
                        + "its managed fields.", 400);
    }

    private static void requireSupported(String resourceType) {
        if (!TYPE.equals(resourceType)) {
            throw new IllegalStateException(
                    "SecretTargetAttachmentCfnProvisioner received an unsupported type: " + resourceType);
        }
    }

    /** Provisions an AWS-compatible Secrets Manager database target attachment. */
    private void provisionSecretTargetAttachment(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String stackName = ctx.stackName();
        String secretId = requireSecretTargetProperty(props, "SecretId", ctx);
        String targetId = requireSecretTargetProperty(props, "TargetId", ctx);
        String targetType = requireSecretTargetProperty(props, "TargetType", ctx);
        validateSecretTargetType(targetType);
        SecretTargetConnection connection = resolveSecretTargetConnection(targetType, targetId);

        String previousSecretId = r.getPhysicalId();
        String previousManagedKeys = r.getAttributes().get(SECRET_TARGET_MANAGED_KEYS_ATTR);
        String attachmentOwner = r.getAttributes().getOrDefault(
                SECRET_TARGET_OWNER_ATTR, stackName + "/" + r.getLogicalId());
        String secretArn = secretsManagerService.describeSecret(secretId, region).getArn();
        String previousSecretArn = canonicalExistingSecretArn(previousSecretId, secretArn, region);
        boolean replacingSecret = previousSecretArn != null && !previousSecretArn.equals(secretArn);
        boolean claimCreated = false;
        boolean wroteNewSecret = false;
        boolean detachedPreviousSecret = false;
        ObjectNode currentSecretJson = null;
        SecretTargetMutation previousDetach = null;

        try {
            claimCreated = secretsManagerService.claimTargetAttachment(
                    secretArn, attachmentOwner, region);

            currentSecretJson = readSecretJsonObject(secretArn, region);
            ObjectNode desiredSecretJson = currentSecretJson.deepCopy();
            SECRET_TARGET_CONNECTION_KEYS.forEach(desiredSecretJson::remove);

            List<String> managedKeys = new ArrayList<>();
            addSecretTargetConnection(desiredSecretJson, managedKeys, connection);

            if (replacingSecret) {
                previousDetach = prepareSecretTargetDetach(previousSecretArn, previousManagedKeys, region);
            }
            if (!desiredSecretJson.equals(currentSecretJson)) {
                secretsManagerService.putSecretValue(
                        secretArn, desiredSecretJson.toString(), null, null, region, null);
                wroteNewSecret = true;
            }
            if (previousDetach != null) {
                putSecretTargetMutation(previousDetach, region);
                detachedPreviousSecret = true;
            }
            if (replacingSecret) {
                secretsManagerService.releaseTargetAttachment(
                        previousSecretArn, attachmentOwner, region);
            }

            r.setPhysicalId(secretArn);
            r.getAttributes().remove("Arn");
            r.getAttributes().put("Id", secretArn);
            r.getAttributes().put(SECRET_TARGET_OWNER_ATTR, attachmentOwner);
            r.getAttributes().put(SECRET_TARGET_MANAGED_KEYS_ATTR, String.join(",", managedKeys));
        } catch (RuntimeException failure) {
            if (detachedPreviousSecret && previousDetach != null) {
                ObjectNode previousValue = previousDetach.originalValue();
                attemptSecretTargetCleanup(failure, "restore previous secret " + previousSecretArn,
                        () -> secretsManagerService.putSecretValue(
                                previousSecretArn, previousValue.toString(),
                                null, null, region, null));
            }
            if (wroteNewSecret && currentSecretJson != null) {
                ObjectNode originalValue = currentSecretJson;
                attemptSecretTargetCleanup(failure, "restore new secret " + secretArn,
                        () -> secretsManagerService.putSecretValue(
                                secretArn, originalValue.toString(),
                                null, null, region, null));
            }
            if (claimCreated) {
                attemptSecretTargetCleanup(failure, "release target attachment claim for " + secretArn,
                        () -> secretsManagerService.releaseTargetAttachment(
                                secretArn, attachmentOwner, region));
            }
            throw failure;
        }
    }

    private static void validateSecretTargetType(String targetType) {
        if (!Set.of(
                "AWS::RDS::DBInstance",
                "AWS::RDS::DBCluster",
                "AWS::DocDB::DBInstance",
                "AWS::DocDB::DBCluster").contains(targetType)) {
            throw new AwsException("ValidationError",
                    "SecretTargetAttachment TargetType " + targetType
                            + " is not supported by Floci; supported values are AWS::RDS::DBInstance,"
                            + " AWS::RDS::DBCluster, AWS::DocDB::DBInstance,"
                            + " and AWS::DocDB::DBCluster.", 400);
        }
    }

    private String canonicalExistingSecretArn(String secretId, String newSecretArn, String region) {
        if (secretId == null || secretId.isBlank() || secretId.equals(newSecretArn)) {
            return secretId;
        }
        try {
            return secretsManagerService.describeSecret(secretId, region).getArn();
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private String requireSecretTargetProperty(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError",
                    "AWS::SecretsManager::SecretTargetAttachment requires " + name + ".", 400);
        }
        return value;
    }

    private ObjectNode readSecretJsonObject(String secretId, String region) {
        return tryReadSecretJsonObject(secretId, region)
                .orElseThrow(SecretTargetAttachmentCfnProvisioner::invalidSecretTargetValue);
    }

    private Optional<ObjectNode> tryReadSecretJsonObject(String secretId, String region) {
        String secretString = secretsManagerService
                .getSecretValue(secretId, null, null, region)
                .getSecretString();
        if (secretString == null) {
            return Optional.empty();
        }
        try {
            JsonNode parsed = objectMapper.readTree(secretString);
            if (parsed == null || !parsed.isObject()) {
                return Optional.empty();
            }
            return Optional.of(((ObjectNode) parsed).deepCopy());
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static AwsException invalidSecretTargetValue() {
        return new AwsException("ValidationError",
                "SecretString for AWS::SecretsManager::SecretTargetAttachment must be a JSON object.", 400);
    }

    private SecretTargetConnection resolveSecretTargetConnection(String targetType, String targetId) {
        return switch (targetType) {
            case "AWS::RDS::DBInstance" -> dbInstanceConnection(targetId);
            case "AWS::RDS::DBCluster" -> dbClusterConnection(targetId);
            case "AWS::DocDB::DBInstance" -> docDbInstanceConnection(targetId);
            case "AWS::DocDB::DBCluster" -> docDbClusterConnection(targetId);
            default -> throw new IllegalStateException("Validated target type was not handled: " + targetType);
        };
    }

    private SecretTargetConnection dbInstanceConnection(String targetId) {
        DbInstance instance = rdsService.getDbInstance(targetId);
        if (instance == null || instance.getEngine() == null || instance.getEndpoint() == null
                || instance.getEndpoint().address() == null
                || instance.getEndpoint().address().isBlank()
                || instance.getEndpoint().port() <= 0
                || instance.getDbInstanceIdentifier() == null
                || instance.getDbInstanceIdentifier().isBlank()) {
            throw incompleteSecretTarget(targetId);
        }
        return new SecretTargetConnection(
                instance.getEngine().name().toLowerCase(Locale.ROOT),
                instance.getEndpoint().address(),
                instance.getEndpoint().port(),
                instance.getDbName(),
                "dbInstanceIdentifier",
                instance.getDbInstanceIdentifier());
    }

    private SecretTargetConnection dbClusterConnection(String targetId) {
        DbCluster cluster = rdsService.getDbCluster(targetId);
        if (cluster == null || cluster.getEngine() == null || cluster.getEndpoint() == null
                || cluster.getEndpoint().address() == null
                || cluster.getEndpoint().address().isBlank()
                || cluster.getEndpoint().port() <= 0
                || cluster.getDbClusterIdentifier() == null
                || cluster.getDbClusterIdentifier().isBlank()) {
            throw incompleteSecretTarget(targetId);
        }
        return new SecretTargetConnection(
                cluster.getEngine().name().toLowerCase(Locale.ROOT),
                cluster.getEndpoint().address(),
                cluster.getEndpoint().port(),
                cluster.getDatabaseName(),
                "dbClusterIdentifier",
                cluster.getDbClusterIdentifier());
    }

    private SecretTargetConnection docDbInstanceConnection(String targetId) {
        DocDbInstance instance = docDbService.getDbInstance(targetId);
        if (instance == null || instance.getEndpoint() == null
                || instance.getEndpoint().isBlank()
                || instance.getPort() <= 0
                || instance.getDbInstanceIdentifier() == null
                || instance.getDbInstanceIdentifier().isBlank()) {
            throw incompleteSecretTarget(targetId);
        }
        return new SecretTargetConnection(
                "mongo",
                instance.getEndpoint(),
                instance.getPort(),
                null,
                "dbInstanceIdentifier",
                instance.getDbInstanceIdentifier());
    }

    private SecretTargetConnection docDbClusterConnection(String targetId) {
        DocDbCluster cluster = docDbService.getDbCluster(targetId);
        if (cluster == null || cluster.getEndpoint() == null
                || cluster.getEndpoint().isBlank()
                || cluster.getPort() <= 0
                || cluster.getDbClusterIdentifier() == null
                || cluster.getDbClusterIdentifier().isBlank()) {
            throw incompleteSecretTarget(targetId);
        }
        return new SecretTargetConnection(
                "mongo",
                cluster.getEndpoint(),
                cluster.getPort(),
                null,
                "dbClusterIdentifier",
                cluster.getDbClusterIdentifier());
    }

    private static void addSecretTargetConnection(ObjectNode secretJson, List<String> managedKeys,
                                                  SecretTargetConnection connection) {
        putSecretTargetField(secretJson, managedKeys, "engine", connection.engine());
        putSecretTargetField(secretJson, managedKeys, "host", connection.host());
        putSecretTargetField(secretJson, managedKeys, "port", connection.port());
        putOptionalSecretTargetField(secretJson, managedKeys, "dbname", connection.dbname());
        putSecretTargetField(secretJson, managedKeys,
                connection.identifierKey(), connection.identifier());
    }

    private static AwsException incompleteSecretTarget(String targetId) {
        return new AwsException("ValidationError",
                "SecretTargetAttachment target " + targetId + " has incomplete connection information.", 400);
    }

    private record SecretTargetConnection(String engine, String host, int port, String dbname,
                                          String identifierKey, String identifier) {
    }

    private record SecretTargetMutation(String secretId, ObjectNode originalValue, ObjectNode value) {
    }

    private static void putSecretTargetField(ObjectNode secretJson, List<String> managedKeys,
                                             String name, String value) {
        secretJson.put(name, value);
        managedKeys.add(name);
    }

    private static void putSecretTargetField(ObjectNode secretJson, List<String> managedKeys,
                                             String name, int value) {
        secretJson.put(name, value);
        managedKeys.add(name);
    }

    private static void putOptionalSecretTargetField(ObjectNode secretJson, List<String> managedKeys,
                                                     String name, String value) {
        if (value != null && !value.isBlank()) {
            putSecretTargetField(secretJson, managedKeys, name, value);
        }
    }

    private void deleteSecretTargetAttachment(StackResource resource, String region) {
        String attachmentOwner = resource.getAttributes().get(SECRET_TARGET_OWNER_ATTR);
        if (!secretsManagerService.canManageTargetAttachment(
                resource.getPhysicalId(), attachmentOwner, region)) {
            LOG.warnv("Skipping SecretTargetAttachment detach because secret {0}"
                            + " is owned by a different attachment",
                    resource.getPhysicalId());
            return;
        }
        detachSecretTarget(resource.getPhysicalId(),
                resource.getAttributes().get(SECRET_TARGET_MANAGED_KEYS_ATTR), region);
        secretsManagerService.releaseTargetAttachment(
                resource.getPhysicalId(), attachmentOwner, region);
    }

    private void detachSecretTarget(String secretId, String managedKeysAttribute, String region) {
        SecretTargetMutation mutation = prepareSecretTargetDetach(secretId, managedKeysAttribute, region);
        if (mutation != null) {
            putSecretTargetMutation(mutation, region);
        }
    }

    private SecretTargetMutation prepareSecretTargetDetach(String secretId,
                                                           String managedKeysAttribute,
                                                           String region) {
        try {
            Optional<ObjectNode> parsedSecret = tryReadSecretJsonObject(secretId, region);
            if (parsedSecret.isEmpty()) {
                LOG.debugv("SecretTargetAttachment current secret value is no longer a JSON object;"
                        + " treating as already detached: {0}", secretId);
                return null;
            }
            ObjectNode currentSecretJson = parsedSecret.get();
            ObjectNode detachedSecretJson = currentSecretJson.deepCopy();
            List<String> managedKeys = managedSecretTargetKeys(managedKeysAttribute);
            managedKeys.forEach(detachedSecretJson::remove);
            return detachedSecretJson.equals(currentSecretJson)
                    ? null
                    : new SecretTargetMutation(secretId, currentSecretJson, detachedSecretJson);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("SecretTargetAttachment secret already gone, treating as detached: {0}", secretId);
            return null;
        }
    }

    private void putSecretTargetMutation(SecretTargetMutation mutation, String region) {
        secretsManagerService.putSecretValue(
                mutation.secretId(), mutation.value().toString(), null, null, region, null);
    }

    private void attemptSecretTargetCleanup(RuntimeException primaryFailure,
                                            String description,
                                            Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
            LOG.warnv("SecretTargetAttachment rollback cleanup failed while attempting to {0}: {1}",
                    description, cleanupFailure.getMessage());
        }
    }

    private static List<String> managedSecretTargetKeys(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return SECRET_TARGET_CONNECTION_KEYS;
        }
        List<String> keys = Arrays.stream(attribute.split(","))
                .filter(SECRET_TARGET_CONNECTION_KEYS::contains)
                .toList();
        return keys.isEmpty() ? SECRET_TARGET_CONNECTION_KEYS : keys;
    }
}
