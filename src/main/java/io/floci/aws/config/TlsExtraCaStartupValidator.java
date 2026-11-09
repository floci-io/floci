package io.floci.aws.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Optional;

/**
 * Refuses to start when {@code floci.tls.extra-ca-path} names a file Floci's own outbound HTTPS
 * clients could not trust anything from: a missing or unreadable file, or one that holds no
 * certificate. Checked whether or not TLS is enabled, as the extra CAs apply in both modes, and
 * at startup rather than on the first outbound call, so a mistyped path is reported at once
 * instead of surfacing later as a refused handshake. A private key kept in the file is ignored.
 */
@ApplicationScoped
public class TlsExtraCaStartupValidator {

    private static final Logger LOG = Logger.getLogger(TlsExtraCaStartupValidator.class);

    private final EmulatorConfig config;

    @Inject
    public TlsExtraCaStartupValidator(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent ignored) {
        Optional<String> extraCaPath = config.tls().extraCaPath();
        if (validate(extraCaPath)) {
            LOG.infov("TLS: outbound HTTPS from Floci also trusts the CAs in {0}", extraCaPath.get());
        }
    }

    /**
     * Whether an extra CA file is set, checking it when it is.
     *
     * @throws IllegalStateException when the file is missing or unreadable, or holds no certificate
     */
    static boolean validate(Optional<String> extraCaPath) {
        if (extraCaPath.isEmpty() || extraCaPath.get().isBlank()) {
            return false;
        }
        String path = extraCaPath.get();
        if (!Files.isReadable(Path.of(path))) {
            throw new IllegalStateException("TLS extra CA file not found or not readable: " + path);
        }
        String certificates;
        try {
            certificates = ContainerCaBundle.certificatePem(Files.readString(Path.of(path)));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("TLS extra CA file cannot be read: " + path + ": " + e.getMessage(), e);
        }
        if (certificates.isEmpty()) {
            throw new IllegalStateException("TLS extra CA file holds no certificate: " + path);
        }
        return true;
    }
}
