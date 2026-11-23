package io.floci.aws.services.redshift;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.DatabaseSecretCredentials;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.SecretVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves the address a Redshift JDBC URL names to the Floci backend that serves it, and reads the
 * database credentials a Secrets Manager secret holds. Shared by the callers that open their own JDBC
 * session to a cluster or workgroup (Firehose delivery, Glue crawlers).
 */
@ApplicationScoped
public class RedshiftJdbcTargets {

    /**
     * Where a JDBC session connects. {@code defaultUser} is the account that signs in when the caller
     * names none.
     */
    public record Backend(String description, String host, int port, String database, String defaultUser) {
    }

    private final RedshiftService redshiftService;
    private final RedshiftServerlessService serverlessService;
    private final SecretsManagerService secretsManagerService;
    private final ObjectMapper mapper;

    @Inject
    public RedshiftJdbcTargets(RedshiftService redshiftService, RedshiftServerlessService serverlessService,
                               SecretsManagerService secretsManagerService, ObjectMapper mapper) {
        this.redshiftService = redshiftService;
        this.serverlessService = serverlessService;
        this.secretsManagerService = secretsManagerService;
        this.mapper = mapper;
    }

    /**
     * The backend a URL addresses.
     *
     * @throws io.floci.aws.core.common.AwsException when the cluster or the workgroup does not exist
     * @throws IllegalStateException when a serverless URL names a database the workgroup does not have
     */
    public Backend resolve(String accountId, String region, RedshiftJdbcUrl url) {
        return url.serverless() ? workgroupBackend(accountId, region, url) : clusterBackend(accountId, url);
    }

    /**
     * The username and password stored in a secret.
     *
     * @throws io.floci.aws.core.common.AwsException when the secret does not exist
     * @throws IllegalStateException when the secret holds no username and password
     */
    public DatabaseSecretCredentials credentialsFromSecret(String secretId, String region) {
        SecretVersion secret = secretsManagerService.getSecretValue(secretId, null, null, region);
        return DatabaseSecretCredentials.parse(mapper, secret.getSecretString())
                .orElseThrow(() -> new IllegalStateException("Secret " + secretId
                        + " does not contain username and password fields."));
    }

    private Backend clusterBackend(String accountId, RedshiftJdbcUrl target) {
        Cluster cluster = redshiftService.describeClustersForAccount(accountId, target.clusterIdentifier()).get(0);
        // The database name in the URL is ignored: the emulated container database is always "dev".
        return new Backend("cluster " + target.clusterIdentifier(), "127.0.0.1", cluster.getEndpoint().getPort(),
                "dev", cluster.getMasterUsername());
    }

    /**
     * A workgroup has one database, so a URL that names another one cannot connect: the same refusal the
     * Data API gives.
     */
    private Backend workgroupBackend(String accountId, String region, RedshiftJdbcUrl target) {
        RedshiftServerlessService.WorkgroupTarget workgroup = RequestScopes.callAs(accountId, region,
                () -> serverlessService.getWorkgroupTarget(target.clusterIdentifier(), region));
        if (!target.database().equals(workgroup.database())) {
            throw new IllegalStateException("Database " + target.database() + " does not exist in workgroup "
                    + workgroup.workgroupName() + "; the only database is " + workgroup.database() + ".");
        }
        int port = workgroup.endpoint() != null && workgroup.endpoint().getPort() > 0
                ? workgroup.endpoint().getPort()
                : workgroup.port();
        return new Backend("workgroup " + workgroup.workgroupName(), "127.0.0.1", port,
                workgroup.database(), workgroup.adminUsername());
    }
}
