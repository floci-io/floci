package io.floci.aws.services.elasticache;

import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.services.elasticache.model.AuthMode;
import io.floci.aws.services.elasticache.model.CacheCluster;
import io.floci.aws.services.elasticache.model.ClusterNode;
import io.floci.aws.services.elasticache.model.ReplicationGroup;
import io.floci.aws.testing.TlsTrust;
import jakarta.enterprise.inject.spi.CDI;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;

/** Real CA validation for cache data-plane tests; never uses a trust-all manager. */
public final class ElastiCacheTestConnections {
    private ElastiCacheTestConnections() {}

    static Socket openSocket(String host, int port) throws IOException {
        ElastiCacheService service = CDI.current().select(ElastiCacheService.class).get();
        for (ReplicationGroup group : service.listReplicationGroups(null)) {
            if (!group.isEncryptedInTransit()) {
                continue;
            }
            if (group.getProxyPort() == port) {
                return openSocket(host, port, true);
            }
            for (ClusterNode node : group.getClusterNodes()) {
                if (node.getProxyPort() == port) {
                    return openSocket(host, port, true);
                }
            }
        }
        for (CacheCluster cluster : service.findCacheClusters(null)) {
            if (cluster.getAuthMode() != AuthMode.NO_AUTH && cluster.getConfigurationEndpoint() != null
                    && cluster.getConfigurationEndpoint().port() == port) {
                return openSocket(host, port, true);
            }
        }
        return openSocket(host, port, false);
    }

    static Socket openSocket(String host, int port, boolean encrypted) throws IOException {
        if (!encrypted) {
            return new Socket(host, port);
        }
        try {
            FlociCertificateAuthority authority = CDI.current().select(FlociCertificateAuthority.class).get();
            SSLSocket socket = (SSLSocket) TlsTrust.trusting(authority.certificate()).getSocketFactory()
                    .createSocket(host, port);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(10000);
            socket.startHandshake();
            return socket;
        } catch (Exception e) {
            throw new IOException("Could not establish a trusted cache TLS connection", e);
        }
    }
}
