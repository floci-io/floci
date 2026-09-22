package io.github.hectorvent.floci.services.rds.proxy;

/**
 * The identity a running RDS proxy publishes and that IAM auth tokens are validated against:
 * the endpoint the token must have been generated for (host, port, region) and the scope of
 * the {@code rds-db:connect} permission the token's principal needs
 * ({@code arn:aws:rds-db:<region>:<accountId>:dbuser:<resourceId>/<DBUser>}).
 *
 * @param resourceId the instance's {@code DbiResourceId}, the cluster's
 *                   {@code DbClusterResourceId} for an Aurora cluster endpoint, or the
 *                   {@code prx-} id of an RDS Proxy
 * @param tokensBoundToEndpoint whether a token has to name this endpoint (see
 *                              {@link #acceptsHost}), its port and its region to be accepted.
 *                              MySQL endpoints always require that; PostgreSQL endpoints unless
 *                              {@code services.rds.iam-token-endpoint-binding} is turned off for
 *                              tokens generated for a name the endpoint does not publish.
 */
public record RdsProxyBinding(String advertisedHost, int publishedPort, String region,
                              String accountId, String resourceId, boolean tokensBoundToEndpoint) {

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
