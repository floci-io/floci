package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.redshift.RedshiftCredentialBroker;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumSession;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessRuntime;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
class RedshiftDataResourceResolver {

    private static final Logger LOG = Logger.getLogger(RedshiftDataResourceResolver.class);

    private final RedshiftService redshiftService;
    private final SecretsManagerService secretsManagerService;
    private final ObjectMapper objectMapper;
    private final RedshiftCredentialBroker credentialBroker;
    private final RegionResolver regionResolver;
    private final RedshiftServerlessService serverlessService;

    @Inject
    RedshiftDataResourceResolver(RedshiftService redshiftService,
                                 SecretsManagerService secretsManagerService,
                                 ObjectMapper objectMapper,
                                 RedshiftCredentialBroker credentialBroker,
                                 RegionResolver regionResolver,
                                 RedshiftServerlessService serverlessService) {
        this.redshiftService = redshiftService;
        this.secretsManagerService = secretsManagerService;
        this.objectMapper = objectMapper;
        this.credentialBroker = credentialBroker;
        this.regionResolver = regionResolver;
        this.serverlessService = serverlessService;
    }

    DatabaseTarget resolve(JsonNode request, String region) {
        String database = requiredText(request, "Database");
        if (hasText(request, "WorkgroupName")) {
            return resolveWorkgroup(request, region, database);
        }

        if (hasText(request, "SecretArn")) {
            return resolveViaSecret(request, region, database);
        }
        return resolveViaDbUser(request, database);
    }

    /**
     * A workgroup is reached either with a secret holding credentials, or as an IAM identity whose
     * database user AWS derives from the signing identity. Floci connects the IAM path as the
     * namespace admin, the same stand-in GetClusterCredentialsWithIAM uses for a cluster.
     */
    private DatabaseTarget resolveWorkgroup(JsonNode request, String region, String database) {
        if (hasText(request, "ClusterIdentifier")) {
            throw validation("ClusterIdentifier and WorkgroupName cannot both be specified.");
        }
        RedshiftServerlessService.WorkgroupTarget workgroup =
                serverlessService.getWorkgroupTarget(request.get("WorkgroupName").asText(), region);
        String accountId = AwsArnUtils.parse(workgroup.arn()).accountId();
        SpectrumSession spectrum = new SpectrumSession(accountId,
                accountId + ":" + RedshiftServerlessRuntime.backendId(region, workgroup.workgroupName()), database,
                workgroup.iamRoleArns(), false);
        if (!database.equals(workgroup.database())) {
            throw validation("Database " + database + " does not exist in workgroup " + workgroup.workgroupName()
                    + "; the only database is " + workgroup.database() + ".");
        }
        if (!hasText(request, "SecretArn")) {
            return new DatabaseTarget(workgroup.arn(), workgroup.host(), workgroup.port(), database,
                    workgroup.masterUsername(), workgroup.masterPassword(), spectrum);
        }
        Credentials creds = secretCredentials(request.get("SecretArn").asText(), region);
        return new DatabaseTarget(workgroup.arn(), workgroup.host(), workgroup.port(), database,
                creds.username(), creds.password(), spectrum);
    }

    private Credentials secretCredentials(String secretArn, String region) {
        validateSecretRegion(secretArn, region);
        SecretVersion secret;
        try {
            secret = secretsManagerService.getSecretValue(secretArn, null, null, region);
        } catch (AwsException e) {
            throw validation("SecretArn does not resolve to a local secret: " + secretArn);
        }
        Credentials creds = parseCredentials(secret.getSecretString());
        if (creds == null) {
            throw validation("Secret " + secretArn + " does not contain username and password fields.");
        }
        return creds;
    }

    private static void validateSecretRegion(String secretArn, String region) {
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(secretArn);
            if (region != null && !region.isBlank() && !region.equals(arn.region())) {
                throw validation("SecretArn is outside the request region.");
            }
        } catch (IllegalArgumentException e) {
            throw validation("SecretArn is not a valid ARN: " + secretArn);
        }
    }

    private DatabaseTarget resolveViaSecret(JsonNode request, String region, String database) {
        String secretArn = request.get("SecretArn").asText();
        validateSecretRegion(secretArn, region);
        String clusterId = requiredText(request, "ClusterIdentifier");
        Cluster cluster = cluster(clusterId);
        Credentials creds = secretCredentials(secretArn, region);
        return target(clusterArn(clusterId, region), cluster, database, creds.username(), creds.password());
    }

    private DatabaseTarget resolveViaDbUser(JsonNode request, String database) {
        String clusterId = requiredText(request, "ClusterIdentifier");
        String dbUser = requiredText(request, "DbUser");
        Cluster cluster = cluster(clusterId);

        if (dbUser.equals(cluster.getMasterUsername())) {
            return target(clusterArn(clusterId, null), cluster, database, dbUser, cluster.getMasterPassword());
        }
        boolean liveCredential = credentialBroker
                .resolve(regionResolver.getAccountId(), clusterId, dbUser)
                .isPresent();
        if (liveCredential) {
            // The minted DbUser is nominal: connect as the cluster master.
            return target(clusterArn(clusterId, null), cluster, database,
                    cluster.getMasterUsername(), cluster.getMasterPassword());
        }
        throw validation("DbUser " + dbUser + " has no active credentials; call GetClusterCredentials first.");
    }

    private Cluster cluster(String clusterId) {
        try {
            return redshiftService.describeClusters(clusterId).get(0);
        } catch (AwsException e) {
            throw validation("Cluster " + clusterId + " was not found.");
        }
    }

    private static DatabaseTarget target(String arn, Cluster cluster, String database, String user, String password) {
        String host = cluster.getContainerHost();
        int port = cluster.getContainerPort();
        if (host == null || host.isBlank() || port <= 0) {
            throw validation("Cluster runtime is not available for Data API execution.");
        }
        String accountId = AwsArnUtils.parse(arn).accountId();
        SpectrumSession spectrum = new SpectrumSession(accountId, accountId + ":" + cluster.getClusterIdentifier(),
                database, cluster.getIamRoleArns(), false);
        return new DatabaseTarget(arn, host, port, database, user, password, spectrum);
    }

    private Credentials parseCredentials(String secretString) {
        if (secretString == null || secretString.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(secretString);
            String username = text(node, "username");
            if (username == null) {
                username = text(node, "user");
            }
            String password = text(node, "password");
            if (username != null && password != null) {
                return new Credentials(username, password);
            }
        } catch (Exception e) {
            LOG.debugv("Could not parse Redshift Data API secret: {0}", e.getMessage());
        }
        return null;
    }

    private String clusterArn(String clusterId, String region) {
        String r = (region == null || region.isBlank()) ? regionResolver.getDefaultRegion() : region;
        return AwsArnUtils.Arn.of("redshift", r, regionResolver.getAccountId(), "cluster:" + clusterId).toString();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static boolean hasText(JsonNode node, String field) {
        String value = text(node, field);
        return value != null && !value.isBlank();
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    record DatabaseTarget(String arn, String host, int port, String database, String user, String password,
                          SpectrumSession spectrum) {
        DatabaseTarget(String arn, String host, int port, String database, String user, String password) {
            this(arn, host, port, database, user, password, null);
        }
    }

    private record Credentials(String username, String password) {
    }
}
