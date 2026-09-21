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

    /**
     * Whether a token generated for {@code host} names this endpoint. Besides the advertised
     * host, the loopback names count: Floci advertises the name containers reach it by
     * ({@code host.docker.internal} natively, its own address in Docker) while a client on the
     * host connects to the loopback interface, and both are the same proxy. The published port
     * still has to match, so a token for a different instance on the same host is refused.
     */
    public boolean acceptsHost(String host) {
        return advertisedHost.equalsIgnoreCase(host)
                || "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host);
    }
}
