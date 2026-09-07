package io.github.hectorvent.floci.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.nio.file.Path;

/**
 * Exposes {@link FlociCertificateAuthority} to CDI. {@link TlsConfigSource} may already have
 * created the files during config bootstrap; this loads the same pair. Lazy, so a boot with TLS
 * off and no caller of the CA never touches the disk.
 */
@ApplicationScoped
public class FlociCertificateAuthorityProducer {

    static final String TLS_DIR = "tls";

    private final EmulatorConfig config;

    @Inject
    public FlociCertificateAuthorityProducer(EmulatorConfig config) {
        this.config = config;
    }

    @Produces
    @ApplicationScoped
    FlociCertificateAuthority certificateAuthority() {
        return FlociCertificateAuthority.loadOrCreate(tlsDir(config));
    }

    /**
     * The one TLS directory. {@link TlsConfigSource} runs before CDI and resolves
     * {@code floci.storage.persistent-path} from system properties and environment only; when it
     * ran with TLS on, every CDI consumer must use exactly that directory, or a persistent path set
     * in application.yml alone would produce a second CA (served leaf signed by one, ca.pem from
     * the other) and lose the container CA bundle it wrote there. With TLS off the bootstrap laid
     * nothing down and the configured path is the only truth.
     */
    static Path tlsDir(EmulatorConfig config) {
        Path fromBootstrap = TlsConfigSource.resolvedTlsDir();
        return fromBootstrap != null ? fromBootstrap : Path.of(config.storage().persistentPath(), TLS_DIR);
    }
}
