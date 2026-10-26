package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.config.ServerCertificateRenewal;
import io.github.hectorvent.floci.config.TlsConfigSource;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Signs the cache listener certificate with the same local CA clients trust for Floci. */
@ApplicationScoped
public class ElastiCacheTlsCertificates {
    private static final Logger LOG = Logger.getLogger(ElastiCacheTlsCertificates.class);

    private final ServerCertificateRenewal renewal;
    private final Set<String> hosts;
    private volatile ServerCertificateRenewal.Leaf leaf;

    @Inject
    public ElastiCacheTlsCertificates(FlociCertificateAuthority authority, EmulatorConfig config,
                                     CertificateGenerator generator) {
        this(authority, config, generator, Clock.systemUTC());
    }

    ElastiCacheTlsCertificates(FlociCertificateAuthority authority, EmulatorConfig config,
                              CertificateGenerator generator, Clock clock) {
        this.renewal = new ServerCertificateRenewal(authority, generator, clock);
        this.hosts = new LinkedHashSet<>(TlsConfigSource.defaultEndpointHostnames());
        hosts.add("::1");
        config.hostname().ifPresent(this::addHost);
        config.services().elasticache().clusterAnnounceHostname().ifPresent(this::addHost);
        String baseUrl = config.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            try {
                addHost(URI.create(baseUrl).getHost());
            } catch (IllegalArgumentException e) {
                LOG.warnv(e, "ElastiCache TLS: could not extract a hostname from floci.base-url: {0}", baseUrl);
            }
        }
    }

    private void addHost(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        String name = host.strip();
        hosts.add(name.startsWith("[") && name.endsWith("]") ? name.substring(1, name.length() - 1) : name);
    }

    public SSLContext sslContext() {
        ServerCertificateRenewal.Leaf current = leaf;
        if (renewal.due(current)) {
            synchronized (this) {
                if (renewal.due(leaf)) {
                    leaf = build();
                }
                current = leaf;
            }
        }
        return current.context();
    }

    private ServerCertificateRenewal.Leaf build() {
        try {
            return renewal.issue(new ArrayList<>(hosts));
        } catch (Exception e) {
            LOG.errorv(e, "Failed to issue the ElastiCache endpoint certificate");
            throw new IllegalStateException("Failed to issue the ElastiCache endpoint certificate", e);
        }
    }
}
