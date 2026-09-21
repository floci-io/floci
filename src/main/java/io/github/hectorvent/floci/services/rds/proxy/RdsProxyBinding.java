package io.github.hectorvent.floci.services.rds.proxy;

/**
 * The identity a running RDS proxy publishes and that IAM auth tokens are validated against:
 * the endpoint the token must have been generated for (host, port, region) and the scope of
 * the {@code rds-db:connect} permission the token's principal needs
 * ({@code arn:aws:rds-db:<region>:<accountId>:dbuser:<resourceId>/<DBUser>}).
 *
 * @param resourceId the instance's {@code DbiResourceId}, or the cluster's
 *                   {@code DbClusterResourceId} for an Aurora cluster endpoint
 */
public record RdsProxyBinding(String advertisedHost, int publishedPort, String region,
                              String accountId, String resourceId) {
}
