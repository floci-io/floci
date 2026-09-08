package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
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

    @Inject
    RedshiftDataResourceResolver(RedshiftService redshiftService,
                                 SecretsManagerService secretsManagerService,
                                 ObjectMapper objectMapper) {
        this.redshiftService = redshiftService;
        this.secretsManagerService = secretsManagerService;
        this.objectMapper = objectMapper;
    }

    DatabaseTarget resolve(JsonNode request, String region) {
        if (hasText(request, "WorkgroupName")) {
            throw validation("Redshift Serverless (WorkgroupName) is not emulated by Floci.");
        }
        String database = requiredText(request, "Database");

        if (hasText(request, "SecretArn")) {
            return resolveViaSecret(request, region, database);
        }
        return resolveViaDbUser(request, database);
    }

    private DatabaseTarget resolveViaSecret(JsonNode request, String region, String database) {
        String secretArn = request.get("SecretArn").asText();
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(secretArn);
            if (region != null && !region.isBlank() && !region.equals(arn.region())) {
                throw validation("SecretArn is outside the request region.");
            }
        } catch (IllegalArgumentException e) {
            throw validation("SecretArn is not a valid ARN: " + secretArn);
        }
        String clusterId = requiredText(request, "ClusterIdentifier");
        Cluster cluster = cluster(clusterId);
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
        return target(clusterArn(clusterId, region), cluster, database, creds.username(), creds.password());
    }

    private DatabaseTarget resolveViaDbUser(JsonNode request, String database) {
        String clusterId = requiredText(request, "ClusterIdentifier");
        String dbUser = requiredText(request, "DbUser");
        Cluster cluster = cluster(clusterId);
        if (!dbUser.equals(cluster.getMasterUsername())) {
            throw validation("DbUser " + dbUser + " is not the cluster master; create it first or use "
                    + "GetClusterCredentials (not yet emulated).");
        }
        return target(clusterArn(clusterId, null), cluster, database, dbUser, cluster.getMasterPassword());
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
        return new DatabaseTarget(arn, host, port, database, user, password);
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

    private static String clusterArn(String clusterId, String region) {
        String r = (region == null || region.isBlank()) ? "us-east-1" : region;
        return "arn:aws:redshift:" + r + ":000000000000:cluster:" + clusterId;
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

    record DatabaseTarget(String arn, String host, int port, String database, String user, String password) {
    }

    private record Credentials(String username, String password) {
    }
}
